package com.vloc.motion.hidden;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorEventListener2;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.vloc.motion.core.GaitAttitude;
import com.vloc.motion.core.ImuWaveform;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * IMU 伪造（v0.4.x）：让目标 App 用加速度计 / 陀螺仪自己算步频时，也与虚拟路线一致。
 *
 * <p>数据来源：复用 {@link StepReplay} 的 steps 快照（{@code content://com.vloc.motion.env/steps}，
 * 1 秒缓存），其中 {@code cadenceSpm / accelAmp / yawRateDps} 由主 App 的时变步态模型逐秒更新。
 *
 * <p>实现：
 * <ul>
 *   <li>hook {@code SensorManager.registerListener} 全重载，只接管
 *       TYPE_ACCELEROMETER(1) / TYPE_LINEAR_ACCELERATION(10) / TYPE_GYROSCOPE(4) / TYPE_GRAVITY(9) /
 *       TYPE_ROTATION_VECTOR(11) / TYPE_MAGNETIC_FIELD(2)；
 *       姿态类（旋转矢量 / 磁力计）按跑步摆动合成，不做“设备静止”假设；</li>
 *   <li>wrapper 在 active=true 时吞掉真实回调，改由独占 HandlerThread 按 **50Hz** 投递合成事件；
 *       active=false / 无快照 / 查询失败 → 透传真实传感器；</li>
 *   <li>波形直接调用主 App 的 {@link ImuWaveform}（与 App 内显示/注入完全同一套公式，不抄公式）；</li>
 *   <li>ACCELEROMETER / LINEAR_ACCELERATION / GRAVITY 在 {@code attitude=true} 时使用
 *       {@code ImuWaveform.*WithAttitude(...)}（重力按 GaitAttitude 当前姿态旋转，与旋转矢量 /
 *       磁力计反推的倾角自洽）；{@code attitude=false} 时回退旧的“竖直握持”假设
 *       {@code ImuWaveform.accelerometer/linearAcceleration/gravity}；</li>
 *   <li>姿态类（ROTATION_VECTOR / MAGNETIC_FIELD / GAME_ROTATION_VECTOR / ORIENTATION）可被主 App 的 JSON 字段
 *       {@code "attitude":false} 单独关闭（默认 true）：关闭时 wrapper 透传真实值、合成线程跳过，
 *       避免直接破坏目标 App 的指南针 / AR / 相机方向判断；</li>
 *   <li>SensorEvent 构造复用 {@link StepReplay#newSensorEvent(int)}（反射包私有 {@code SensorEvent(int)}）；</li>
 *   <li>hook {@code unregisterListener} 把“注销原 listener”翻译成“注销 wrapper”（与 StepReplay 同样的坑）。</li>
 * </ul>
 *
 * <p>相位处理：每 tick 用 {@code ImuWaveform.phase(cadence, dt)} 作为相位增量累加，
 * 这样主 App 时变步态（步频每秒变化）下波形仍然连续，不会因为 {@code 2π·f(t)·t} 产生相位跳变。
 */
final class ImuReplay {

    /**
     * 竖直静止模式：虚拟摄像头接管目标 App 相机时打开。
     *
     * <p>运动/姿态识别类 App 会先用陀螺仪/加速度计判断"手机是否竖直立在支架上"，
     * 不竖直就提示"请调整手机角度"并拒绝采集画面。接管相机期间把 IMU 固定成
     * "手机竖直、静止"的姿态，让它顺利进入采集状态。
     */
    private static volatile boolean sUprightMode;

    /** 打开 / 关闭竖直静止模式。 */
    static void setUprightMode(boolean upright) {
        sUprightMode = upright;
    }


    private static final String TAG = "[VLocHidden] ";

    /** 50Hz 合成。 */
    private static final long TICK_MS = 20L;
    private static final long TICK_NANOS = 20_000_000L;

    /** 真机加速度计/陀螺仪通常上报 HIGH；合成事件用同一个 accuracy。 */
    private static final int SYNTHETIC_ACCURACY = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;

    private static final double TWO_PI = Math.PI * 2.0d;

    /** 每个 tick 换一个噪声种子（黄金比例小数），让 x/y 噪声逐帧随机而不产生周期性。 */
    private static final double NOISE_SEED_STEP = 0.6180339887d;

    /**
     * GAME_ROTATION_VECTOR 的偏航基准：每进程初始化一次固定随机值（-180~180°）。
     * game RV 没有磁力计，偏航基准本来就是任意的，但必须稳定不漂移。
     */
    private static final double GAME_RV_YAW_OFFSET_DEG =
            new java.util.Random().nextDouble() * 360.0d - 180.0d;

    private static final Object LOCK = new Object();
    private static final List<Registration> sRegistrations = new ArrayList<>();
    private static final ThreadLocal<Registration> sPendingRegistration = new ThreadLocal<>();

    private static HandlerThread sTickerThread;
    private static Handler sTickerHandler;
    private static long sNextTickNanos;
    private static long sLastTickNanos;
    private static long sTickIndex;
    private static double sPhase;
    private static volatile boolean sSynthesisDisabled;

    private static boolean sLoggedRegister;
    private static boolean sLoggedActive;
    private static boolean sLoggedUnregister;
    /** 记录上一次的 attitude 开关状态，变化时打一条日志（attitude=on/off）。 */
    private static volatile Boolean sLastAttitudeEnabled;
    private static boolean sLoggedGameRvOffset;

    private ImuReplay() {
    }

    // ==================================================================
    // 安装
    // ==================================================================

    static int install(ClassLoader classLoader) {
        int hooked = 0;
        final Class<?> sensorManager = SensorManager.class;
        try {
            final int count =
                    XposedBridge.hookAllMethods(sensorManager, "registerListener", REGISTER_HOOK).size();
            if (count > 0) {
                hooked++;
                log("hooked SensorManager#registerListener (imu) (" + count + " overload(s))");
            }
        } catch (Throwable t) {
            log("skip imu registerListener: " + t);
        }
        try {
            final int count =
                    XposedBridge.hookAllMethods(sensorManager, "unregisterListener", UNREGISTER_HOOK).size();
            if (count > 0) {
                hooked++;
                log("hooked SensorManager#unregisterListener (imu) (" + count + " overload(s))");
            }
        } catch (Throwable t) {
            log("skip imu unregisterListener: " + t);
        }
        log("imu hooks=" + hooked + "/2 (TYPE_ACCELEROMETER=1 / TYPE_LINEAR_ACCELERATION=10"
                + " / TYPE_GYROSCOPE=4 / TYPE_GRAVITY=9"
                + " / TYPE_ROTATION_VECTOR=11 / TYPE_MAGNETIC_FIELD=2"
                + " / TYPE_GAME_ROTATION_VECTOR=15 / TYPE_ORIENTATION=3)");
        return hooked;
    }

    private static boolean isImuType(int type) {
        return type == Sensor.TYPE_ACCELEROMETER
                || type == Sensor.TYPE_LINEAR_ACCELERATION
                || type == Sensor.TYPE_GYROSCOPE
                || type == Sensor.TYPE_GRAVITY
                || type == Sensor.TYPE_ROTATION_VECTOR
                || type == Sensor.TYPE_MAGNETIC_FIELD
                || type == Sensor.TYPE_GAME_ROTATION_VECTOR
                || type == Sensor.TYPE_ORIENTATION;
    }

    /** 姿态类传感器：可被主 App 的 JSON {@code "attitude":false} 单独关闭（透传真实值）。 */
    private static boolean isAttitudeType(int type) {
        return type == Sensor.TYPE_ROTATION_VECTOR
                || type == Sensor.TYPE_MAGNETIC_FIELD
                || type == Sensor.TYPE_GAME_ROTATION_VECTOR
                || type == Sensor.TYPE_ORIENTATION;
    }

    /** 该类型合成事件的 values 长度（两种旋转矢量是 4，其余是 3）。 */
    private static int valueSizeFor(int sensorType) {
        return sensorType == Sensor.TYPE_ROTATION_VECTOR
                || sensorType == Sensor.TYPE_GAME_ROTATION_VECTOR ? 4 : 3;
    }

    // ==================================================================
    // register / unregister 翻译
    // ==================================================================

    private static final XC_MethodHook REGISTER_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                final Object[] args = param.args;
                if (args == null || args.length < 3) {
                    return;
                }
                if (!(args[0] instanceof SensorEventListener) || !(args[1] instanceof Sensor)) {
                    return;
                }
                final SensorEventListener original = (SensorEventListener) args[0];
                if (original instanceof ImuWrapper) {
                    return; // 我们内部转发的调用
                }
                final Sensor sensor = (Sensor) args[1];
                final int type = sensor.getType();
                if (!isImuType(type)) {
                    return; // 计步传感器由 StepReplay 负责，其它传感器完全放过
                }
                final Registration existing = findRegistration(original, sensor);
                if (existing != null) {
                    args[0] = existing.wrapper; // 幂等：沿用已有 wrapper
                    return;
                }
                if (!(param.thisObject instanceof SensorManager)) {
                    return;
                }
                final Registration registration = new Registration(
                        (SensorManager) param.thisObject,
                        original,
                        sensor,
                        type,
                        findHandler(args),
                        new ImuWrapper(original, type));
                synchronized (LOCK) {
                    sRegistrations.add(registration);
                }
                sPendingRegistration.set(registration);
                args[0] = registration.wrapper;
                ensureTicker();
                logRegisterOnce(type);
            } catch (Throwable t) {
                log("imu registerListener hook failed: " + t);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            final Registration registration = sPendingRegistration.get();
            sPendingRegistration.remove();
            if (registration == null) {
                return;
            }
            final Object result = param.hasThrowable() ? Boolean.FALSE : param.getResult();
            final boolean registered = !(result instanceof Boolean) || ((Boolean) result).booleanValue();
            if (!registered) {
                synchronized (LOCK) {
                    sRegistrations.remove(registration);
                }
                log("imu registerListener returned false/throw, wrapper removed");
            }
        }
    };

    private static final XC_MethodHook UNREGISTER_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                final Object[] args = param.args;
                if (args == null || args.length < 1 || !(args[0] instanceof SensorEventListener)) {
                    return;
                }
                final SensorEventListener listener = (SensorEventListener) args[0];
                if (listener instanceof ImuWrapper) {
                    return; // 我们自己发起的注销，放行原始实现
                }
                final Sensor filter = args.length > 1 && args[1] instanceof Sensor
                        ? (Sensor) args[1] : null;
                final List<Registration> matches = takeRegistrations(listener, filter);
                if (matches.isEmpty()) {
                    return;
                }
                final SensorManager manager = param.thisObject instanceof SensorManager
                        ? (SensorManager) param.thisObject : null;
                for (Registration registration : matches) {
                    try {
                        if (manager != null) {
                            if (filter != null) {
                                manager.unregisterListener(registration.wrapper, registration.sensor);
                            } else {
                                manager.unregisterListener(registration.wrapper);
                            }
                        }
                    } catch (Throwable t) {
                        log("imu unregister wrapper failed: " + t);
                    }
                }
                logUnregisterOnce(matches.size());
                // 不 setResult：StepReplay 的同类钩子还要在同一次调用里翻译它自己的 wrapper，
                // 且原始调用（用原 listener 注销）本身就是 no-op / 正确注销未包装的注册。
            } catch (Throwable t) {
                log("imu unregisterListener hook failed: " + t);
            }
        }
    };

    private static Handler findHandler(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Handler) {
                return (Handler) arg;
            }
        }
        return StepReplay.mainHandler();
    }

    private static Registration findRegistration(SensorEventListener target, Sensor sensor) {
        synchronized (LOCK) {
            for (Registration registration : sRegistrations) {
                if (registration.sensor == sensor && registration.target.get() == target) {
                    return registration;
                }
            }
        }
        return null;
    }

    private static List<Registration> takeRegistrations(SensorEventListener target, Sensor filter) {
        final List<Registration> matches = new ArrayList<>();
        synchronized (LOCK) {
            for (int i = sRegistrations.size() - 1; i >= 0; i--) {
                final Registration registration = sRegistrations.get(i);
                if (registration.target.get() != target) {
                    continue;
                }
                if (filter != null && registration.sensor != filter) {
                    continue;
                }
                sRegistrations.remove(i);
                matches.add(registration);
            }
        }
        return matches;
    }

    // ==================================================================
    // 50Hz 合成线程
    // ==================================================================

    private static void ensureTicker() {
        if (sSynthesisDisabled) {
            return;
        }
        synchronized (LOCK) {
            if (sTickerThread != null) {
                return;
            }
            try {
                final HandlerThread thread = new HandlerThread("VLocHidden-imu");
                thread.start();
                sTickerThread = thread;
                sTickerHandler = new Handler(thread.getLooper());
                final long now = SystemClock.elapsedRealtimeNanos();
                sLastTickNanos = now;
                sNextTickNanos = now + TICK_NANOS;
                sPhase = 0d;
                sTickerHandler.postDelayed(TICK_TASK, TICK_MS);
            } catch (Throwable t) {
                sTickerThread = null;
                sTickerHandler = null;
                disableSynthesis("ticker start failed: " + t);
            }
        }
    }

    private static void stopTicker() {
        synchronized (LOCK) {
            if (sTickerHandler != null) {
                try {
                    sTickerHandler.removeCallbacks(TICK_TASK);
                } catch (Throwable ignored) {
                    // ignore
                }
                sTickerHandler = null;
            }
            if (sTickerThread != null) {
                try {
                    sTickerThread.quitSafely();
                } catch (Throwable ignored) {
                    // ignore
                }
                sTickerThread = null;
            }
        }
    }

    private static void disableSynthesis(String reason) {
        if (!sSynthesisDisabled) {
            sSynthesisDisabled = true;
            log("imu synthesis disabled: " + reason);
        }
        stopTicker();
    }

    private static final Runnable TICK_TASK = new Runnable() {
        @Override
        public void run() {
            try {
                onTick();
            } catch (Throwable t) {
                log("imu tick failed: " + t);
            }
            final Handler handler = sTickerHandler;
            if (handler == null) {
                return;
            }
            try {
                final long now = SystemClock.elapsedRealtimeNanos();
                sNextTickNanos += TICK_NANOS;
                long delayNanos = sNextTickNanos - now;
                if (delayNanos < 0L) {
                    // 落后太多（线程被冻结/调度延迟）：不追帧，直接从现在重新对齐
                    sNextTickNanos = now;
                    delayNanos = 0L;
                }
                handler.postDelayed(this, Math.max(1L, delayNanos / 1_000_000L));
            } catch (Throwable ignored) {
                // 线程退出等情况：停掉
                sTickerHandler = null;
            }
        }
    };

    private static void onTick() {
        final List<Registration> registrations;
        synchronized (LOCK) {
            if (sRegistrations.isEmpty()) {
                stopTicker();
                return;
            }
            registrations = new ArrayList<>(sRegistrations);
        }

        final StepReplay.StepsSnapshot snapshot = StepReplay.currentSnapshot();
        final boolean active = snapshot != null && snapshot.active;
        final long nowNanos = SystemClock.elapsedRealtimeNanos();
        final double dtSeconds = Math.min(0.25d,
                Math.max(0d, (nowNanos - sLastTickNanos) / 1_000_000_000d));
        sLastTickNanos = nowNanos;

        if (!active) {
            // 不接管：wrapper 会把真实事件原样转发；这里只清理已失效的注册
            sPhase = 0d;
            for (Registration registration : registrations) {
                if (registration.target.get() == null) {
                    cleanupDead(registration);
                }
            }
            return;
        }

        logAttitudeSwitchOnce(snapshot.attitudeEnabled);
        final boolean attitudeMode = snapshot.attitudeEnabled;
        final double cadence = snapshot.cadenceSpm;
        final double amplitude = snapshot.accelAmp;
        final double yawRateRad = snapshot.yawRateDps * Math.PI / 180.0d;
        // 用官方 phase 公式作为增量：主 App 的时变步态下相位仍然连续
        sPhase += ImuWaveform.phase(cadence, dtSeconds);
        if (sPhase > TWO_PI * 1_000_000d) {
            sPhase %= TWO_PI; // 防止浮点数无限增长
        }
        sTickIndex++;
        final double noiseSeed = NOISE_SEED_STEP * sTickIndex;
        logActiveOnce(snapshot);

        for (Registration registration : registrations) {
            if (registration.target.get() == null) {
                cleanupDead(registration);
                continue;
            }
            if (isAttitudeType(registration.sensorType) && !snapshot.attitudeEnabled
                    && !sUprightMode) {
                continue; // 姿态类被主 App 关闭：不合成（wrapper 已透传真实事件）
            }
            final SensorEvent event =
                    StepReplay.newSensorEvent(valueSizeFor(registration.sensorType));
            if (event == null) {
                disableSynthesis("SensorEvent(int) ctor unavailable");
                return;
            }

            try {
                if (sUprightMode && fillUpright(registration.sensorType, event.values)) {
                    postEvent(registration, event);
                    continue;
                }
                switch (registration.sensorType) {
                    case Sensor.TYPE_ACCELEROMETER:
                        if (attitudeMode) {
                            // 姿态自洽版：重力已按 GaitAttitude 当前姿态旋转到设备系，
                            // 与 ROTATION_VECTOR / MAGNETIC_FIELD 反推的倾角一致。
                            ImuWaveform.accelerometerWithAttitude(snapshot.headingDeg, cadence, sPhase,
                                    snapshot.speedMps, noiseSeed, event.values);
                        } else {
                            // attitude=false：姿态类透传真实值，这里回退“竖直握持”假设，
                            // 保持与“不接管姿态”的语义一致。
                            ImuWaveform.accelerometer(amplitude, sPhase, noiseSeed, event.values);
                        }
                        break;
                    case Sensor.TYPE_LINEAR_ACCELERATION:
                        if (attitudeMode) {
                            ImuWaveform.linearAccelerationWithAttitude(snapshot.headingDeg, cadence,
                                    sPhase, snapshot.speedMps, noiseSeed, event.values);
                        } else {
                            ImuWaveform.linearAcceleration(amplitude, sPhase, noiseSeed, event.values);
                        }
                        break;
                    case Sensor.TYPE_GYROSCOPE:
                        ImuWaveform.gyroscope(amplitude, sPhase, yawRateRad, noiseSeed, event.values);
                        break;
                    case Sensor.TYPE_GRAVITY:
                        if (attitudeMode) {
                            ImuWaveform.gravityWithAttitude(snapshot.headingDeg, cadence, sPhase,
                                    snapshot.speedMps, noiseSeed, event.values);
                        } else {
                            ImuWaveform.gravity(noiseSeed, event.values);
                        }
                        break;
                    case Sensor.TYPE_ROTATION_VECTOR:
                        // 姿态按跑步摆动合成（基础俯仰 20° + 每步摆动），与加速度/陀螺同相位
                        GaitAttitude.rotationVector(snapshot.headingDeg, cadence, sPhase,
                                snapshot.speedMps, event.values);
                        break;
                    case Sensor.TYPE_MAGNETIC_FIELD:
                        GaitAttitude.magneticField(snapshot.headingDeg, cadence, sPhase,
                                snapshot.speedMps, event.values);
                        break;
                    case Sensor.TYPE_GAME_ROTATION_VECTOR:
                        // 无磁力计四元数：偏航基准用进程内固定随机偏移（任意但稳定）
                        GaitAttitude.gameRotationVector(snapshot.headingDeg, cadence, sPhase,
                                snapshot.speedMps, GAME_RV_YAW_OFFSET_DEG, event.values);
                        logGameRvOnce();
                        break;
                    case Sensor.TYPE_ORIENTATION:
                        // (方位角 0~360°, 俯仰 -180~180°, 滚转 -90~90°)，方位跟随路线航向
                        GaitAttitude.orientation(snapshot.headingDeg, cadence, sPhase,
                                snapshot.speedMps, event.values);
                        break;
                    default:
                        continue;
                }
            } catch (Throwable t) {
                log("imu waveform failed: " + t);
                continue;
            }

            event.sensor = registration.sensor;
            event.accuracy = SYNTHETIC_ACCURACY;
            event.timestamp = nowNanos;
            postEvent(registration, event);
        }
    }

    /**
     * 竖直静止姿态下的传感器值（手机竖立、屏幕朝向用户、几乎静止）。
     *
     * @return false 表示这个传感器类型不处理（走原来的跑步波形）
     */
    private static boolean fillUpright(int sensorType, float[] out) {
        switch (sensorType) {
            case Sensor.TYPE_ACCELEROMETER:
            case Sensor.TYPE_GRAVITY:
                // 手机竖直：重力沿设备 +Y（约 9.81），X/Z 接近 0，加一点点噪声
                out[0] = (float) (Math.random() - 0.5) * 0.06f;
                out[1] = 9.81f + (float) (Math.random() - 0.5) * 0.06f;
                out[2] = (float) (Math.random() - 0.5) * 0.06f;
                return true;
            case Sensor.TYPE_LINEAR_ACCELERATION:
                out[0] = (float) (Math.random() - 0.5) * 0.05f;
                out[1] = (float) (Math.random() - 0.5) * 0.05f;
                out[2] = (float) (Math.random() - 0.5) * 0.05f;
                return true;
            case Sensor.TYPE_GYROSCOPE:
            case Sensor.TYPE_GYROSCOPE_UNCALIBRATED:
                if (out.length >= 3) {
                    out[0] = (float) (Math.random() - 0.5) * 0.01f;
                    out[1] = (float) (Math.random() - 0.5) * 0.01f;
                    out[2] = (float) (Math.random() - 0.5) * 0.01f;
                }
                return true;
            case Sensor.TYPE_ROTATION_VECTOR:
            case Sensor.TYPE_GAME_ROTATION_VECTOR:
                // 设备 Y 轴朝上、Z 轴水平指向用户：对应四元数 (w, x, y, z) ≈ (0.7071, 0, 0, 0.7071)?
                // 这里用"绕 X 轴 -90°"：把设备从平放转为竖直，方向向量取 (w, x, y, z) 归一化。
                if (out.length >= 3) {
                    out[0] = 0.7071068f;
                    out[1] = 0f;
                    out[2] = -0.7071068f;
                    if (out.length >= 4) {
                        out[3] = 0f;
                    }
                }
                return true;
            case Sensor.TYPE_ORIENTATION:
                if (out.length >= 3) {
                    out[0] = 0f;      // azimuth
                    out[1] = -90f;    // pitch：竖直
                    out[2] = 0f;      // roll
                }
                return true;
            case Sensor.TYPE_MAGNETIC_FIELD:
                if (out.length >= 3) {
                    out[0] = 0f;
                    out[1] = 45f;
                    out[2] = 0f;
                }
                return true;
            default:
                return false;
        }
    }

    private static void postEvent(final Registration registration, final SensorEvent event) {
        try {
            final Runnable delivery = new Runnable() {
                @Override
                public void run() {
                    registration.wrapper.deliverSynthetic(event);
                }
            };
            if (registration.handler != null) {
                registration.handler.post(delivery);
            } else {
                StepReplay.mainHandler().post(delivery);
            }
        } catch (Throwable ignored) {
            // 目标 Handler 已退出：丢弃该事件
        }
    }

    private static void cleanupDead(Registration registration) {
        synchronized (LOCK) {
            sRegistrations.remove(registration);
        }
        try {
            if (registration.manager != null) {
                registration.manager.unregisterListener(registration.wrapper);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ==================================================================
    // wrapper
    // ==================================================================

    private static final class ImuWrapper implements SensorEventListener2 {
        private final WeakReference<SensorEventListener> target;
        private final int sensorType;

        ImuWrapper(SensorEventListener target, int sensorType) {
            this.target = new WeakReference<>(target);
            this.sensorType = sensorType;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            final SensorEventListener listener = target.get();
            if (listener == null) {
                return;
            }
            try {
                final StepReplay.StepsSnapshot snapshot = StepReplay.currentSnapshot();
                if (snapshot == null || !snapshot.active) {
                    listener.onSensorChanged(event); // 不接管：透传真实传感器
                    return;
                }
                if (isAttitudeType(sensorType) && !snapshot.attitudeEnabled) {
                    listener.onSensorChanged(event); // 姿态类被主 App 关闭：透传真实值
                    return;
                }
                // active：真实事件吞掉，改由 50Hz 合成线程按 ImuWaveform / GaitAttitude 投递
            } catch (Throwable t) {
                try {
                    listener.onSensorChanged(event);
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            final SensorEventListener listener = target.get();
            if (listener == null) {
                return;
            }
            try {
                listener.onAccuracyChanged(sensor, accuracy);
            } catch (Throwable ignored) {
                // ignore
            }
        }

        @Override
        public void onFlushCompleted(Sensor sensor) {
            final SensorEventListener listener = target.get();
            if (listener instanceof SensorEventListener2) {
                try {
                    ((SensorEventListener2) listener).onFlushCompleted(sensor);
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }

        void deliverSynthetic(SensorEvent event) {
            final SensorEventListener listener = target.get();
            if (listener == null) {
                return;
            }
            try {
                listener.onSensorChanged(event);
            } catch (Throwable ignored) {
                // 目标 App 自己的回调异常：吞掉，不影响其它 listener
            }
        }
    }

    // ==================================================================
    // 数据结构 / 日志
    // ==================================================================

    private static final class Registration {
        final SensorManager manager;
        final WeakReference<SensorEventListener> target;
        final Sensor sensor;
        final int sensorType;
        final Handler handler;
        final ImuWrapper wrapper;

        Registration(SensorManager manager, SensorEventListener target, Sensor sensor,
                     int sensorType, Handler handler, ImuWrapper wrapper) {
            this.manager = manager;
            this.target = new WeakReference<>(target);
            this.sensor = sensor;
            this.sensorType = sensorType;
            this.handler = handler;
            this.wrapper = wrapper;
        }
    }

    private static void logRegisterOnce(int type) {
        if (sLoggedRegister) {
            return;
        }
        sLoggedRegister = true;
        log("imu sensor wrapped: TYPE_ACCELEROMETER=1 / TYPE_LINEAR_ACCELERATION=10"
                + " / TYPE_GYROSCOPE=4 / TYPE_GRAVITY=9"
                + " / TYPE_ROTATION_VECTOR=11 / TYPE_MAGNETIC_FIELD=2"
                + " / TYPE_GAME_ROTATION_VECTOR=15 / TYPE_ORIENTATION=3, type=" + type);
    }

    private static void logActiveOnce(StepReplay.StepsSnapshot snapshot) {
        if (sLoggedActive) {
            return;
        }
        sLoggedActive = true;
        log("imu replay active: accelAmp=" + snapshot.accelAmp
                + ", cadenceSpm=" + snapshot.cadenceSpm
                + ", yawRateDps=" + snapshot.yawRateDps
                + ", attitude=" + (snapshot.attitudeEnabled ? "on" : "off")
                + ", game_rv=" + (snapshot.attitudeEnabled ? "on" : "off")
                + ", orientation=" + (snapshot.attitudeEnabled ? "on" : "off")
                + ", gravity mode=" + (snapshot.attitudeEnabled ? "attitude" : "vertical"));
    }

    /** attitude 开关状态变化时打一条（on=接管 ROTATION_VECTOR/MAGNETIC_FIELD，off=透传真实值）。 */
    private static void logAttitudeSwitchOnce(boolean enabled) {
        final Boolean previous = sLastAttitudeEnabled;
        if (previous != null && previous.booleanValue() == enabled) {
            return;
        }
        sLastAttitudeEnabled = Boolean.valueOf(enabled);
        log("attitude=" + (enabled ? "on" : "off")
                + " (ROTATION_VECTOR / MAGNETIC_FIELD / GAME_ROTATION_VECTOR / ORIENTATION "
                + (enabled ? "takeover" : "passthrough") + ")"
                + ", game_rv=" + (enabled ? "on" : "off")
                + ", orientation=" + (enabled ? "on" : "off")
                + ", gravity mode=" + (enabled ? "attitude" : "vertical"));
    }

    /** 首次合成 GAME_ROTATION_VECTOR 时把本进程的固定偏航基准打出来，方便对日志。 */
    private static void logGameRvOnce() {
        if (sLoggedGameRvOffset) {
            return;
        }
        sLoggedGameRvOffset = true;
        log("game_rv yaw offset=" + GAME_RV_YAW_OFFSET_DEG + " deg (fixed per process)");
    }

    private static void logUnregisterOnce(int count) {
        if (sLoggedUnregister) {
            return;
        }
        sLoggedUnregister = true;
        log("imu wrapper unregistered (" + count + " registration(s))");
    }

    private static void log(String message) {
        try {
            XposedBridge.log(TAG + message);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
