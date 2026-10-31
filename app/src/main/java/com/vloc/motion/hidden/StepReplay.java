package com.vloc.motion.hidden;

import android.content.Context;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorEventListener2;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 计步传感器回放（v0.4.x）：让目标 App 读到的步数 / 步频与虚拟路线一致。
 *
 * <p>数据来源：主 App 的 {@code content://com.vloc.motion.env/steps}（exported，无权限），
 * 列 {@code mode / json / updated_at}；JSON 里有 {@code active / steps / cadenceSpm / ...}，
 * {@code version=2} 起还有 {@code gnss} 卫星数组（由 {@link GnssReplay} 消费，本类只负责解析进快照，
 * 保证 GNSS 与计步 / IMU 共用同一次 provider 查询和 1 秒缓存）。
 * 无行、{@code mode=off}、{@code active=false}、查询失败 → 完全透传真实传感器。
 *
 * <p>实现：
 * <ul>
 *   <li>hook {@code SensorManager.registerListener} 全重载：TYPE_STEP_COUNTER(19) /
 *       TYPE_STEP_DETECTOR(18) 时把 listener 换成 {@link StepWrapper}，并记录注册关系；</li>
 *   <li>hook {@code SensorManager.unregisterListener}：把“注销原 listener”翻译成“注销 wrapper”，
 *       避免出现 App 已注销但 wrapper 还挂着、回调继续打到 App 的情况；</li>
 *   <li>真实回调：COUNTER 用我们的累计步数改写（复制 event，避免污染其它 listener）；
 *       DETECTOR 吞掉，改由合成线程按步频投递；</li>
 *   <li>合成：HandlerThread 每 500ms 一次，COUNTER 发累计步数、DETECTOR 按 cadenceSpm 折算成
 *       若干 values[0]=1 的事件；事件投递到 App 注册时使用的 Handler / 主线程，保持回调线程语义。</li>
 * </ul>
 *
 * <p>SensorEvent 构造：所有版本（8.1 ~ 15）都有包私有构造器 {@code SensorEvent(int valueSize)}，
 * 用 {@code getDeclaredConstructor(int.class)} + {@code setAccessible(true)}；构造失败则关闭合成并降级
 * 为透传，绝不把异常抛给目标 App。
 */
final class StepReplay {

    private static final String TAG = "[VLocHidden] ";

    private static final String STEPS_URI_STRING = "content://com.vloc.motion.env/steps";
    private static final Uri STEPS_URI = Uri.parse(STEPS_URI_STRING);

    private static final String COL_MODE = "mode";
    private static final String COL_JSON = "json";
    private static final String COL_UPDATED_AT = "updated_at";
    private static final String[] PROJECTION = {COL_MODE, COL_JSON, COL_UPDATED_AT};

    private static final String MODE_REPLAY = "replay";
    private static final String MODE_OFF = "off";

    /** steps 快照只需 1 秒新鲜度；避免高频 provider 查询。 */
    private static final long CACHE_TTL_MS = 1000L;

    /** 回 ping 主 App 的最小间隔：主界面用“模块已读 Ns前”判断模块是否真的在跑。 */
    private static final long PING_INTERVAL_MS = 5000L;

    /** 合成间隔。 */
    private static final long TICK_MS = 500L;

    /** 单次 tick 最多投递几步，防止异常 cadence 刷爆目标 App。 */
    private static final int MAX_DETECTOR_STEPS_PER_TICK = 6;

    /** 真机计步传感器的 accuracy 常见为 0（UNRELIABLE），这里按任务约定固定 0。 */
    private static final int SYNTHETIC_ACCURACY = 0;

    private static final Object LOCK = new Object();

    private static final List<Registration> sRegistrations = new ArrayList<>();

    /** registerListener 的 before/after 之间传递本次新建的注册（同一线程）。 */
    private static final ThreadLocal<Registration> sPendingRegistration = new ThreadLocal<>();

    private static volatile StepsSnapshot sCachedSnapshot;
    private static volatile long sCachedAtElapsed;
    private static volatile boolean sHasCache;

    private static long sLastPingElapsed;
    private static boolean sLoggedStepsPing;

    /** 注入心跳：每进程最多成功回报一次；早期进程 Context 未就绪时主线程延迟重试。 */
    private static volatile boolean sHeartbeatSent;
    private static int sHeartbeatAttempts;

    private static HandlerThread sTickerThread;
    private static Handler sTickerHandler;
    private static Handler sMainHandler;
    private static double sDetectorStepCarry;
    private static volatile boolean sSynthesisDisabled;

    private static volatile Constructor<?> sEventConstructor;
    private static volatile boolean sEventConstructorFailed;

    private static boolean sLoggedRegister;
    private static boolean sLoggedActive;
    private static boolean sLoggedQueryFail;
    private static boolean sLoggedCtorFail;
    private static boolean sLoggedUnregister;

    private StepReplay() {
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
                log("hooked SensorManager#registerListener (" + count + " overload(s))");
            }
        } catch (Throwable t) {
            log("skip SensorManager#registerListener: " + t);
        }
        try {
            final int count =
                    XposedBridge.hookAllMethods(sensorManager, "unregisterListener", UNREGISTER_HOOK).size();
            if (count > 0) {
                hooked++;
                log("hooked SensorManager#unregisterListener (" + count + " overload(s))");
            }
        } catch (Throwable t) {
            log("skip SensorManager#unregisterListener: " + t);
        }
        log("step hooks=" + hooked + "/2 (TYPE_STEP_COUNTER=19 / TYPE_STEP_DETECTOR=18)");
        return hooked;
    }

    // ==================================================================
    // registerListener / unregisterListener
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
                if (original instanceof StepWrapper) {
                    return; // 我们自己转发的内部调用
                }
                final Sensor sensor = (Sensor) args[1];
                final int type = sensor.getType();
                if (type != Sensor.TYPE_STEP_COUNTER && type != Sensor.TYPE_STEP_DETECTOR) {
                    return;
                }

                // 同一个 listener 对同一个 sensor 重复注册：真实 SensorManager 是幂等的，
                // 这里复用已有 wrapper，让底层也保持幂等。
                final Registration existing = findRegistration(original, sensor);
                if (existing != null) {
                    args[0] = existing.wrapper;
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
                        new StepWrapper(original, type));
                synchronized (LOCK) {
                    sRegistrations.add(registration);
                }
                sPendingRegistration.set(registration);
                args[0] = registration.wrapper;
                ensureTicker();
                logRegisterOnce(type);
            } catch (Throwable t) {
                log("registerListener hook failed: " + t);
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
                log("registerListener returned false/throw, step wrapper removed");
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
                if (listener instanceof StepWrapper) {
                    return; // 我们自己发起的注销，放行给原始实现
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
                        log("unregister wrapper failed: " + t);
                    }
                }
                logUnregisterOnce(matches.size());
                // 注意：这里不 setResult —— 同一 listener 可能同时注册了 IMU 传感器，
                // ImuReplay 的 unregister 钩子还要在同一次调用里翻译它自己的 wrapper；
                // 让原始调用继续执行（用原 listener 注销）是 no-op 或正确注销未包装的注册。
            } catch (Throwable t) {
                log("unregisterListener hook failed: " + t);
            }
        }
    };

    private static Handler findHandler(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Handler) {
                return (Handler) arg;
            }
        }
        return mainHandler();
    }

    static Handler mainHandler() {
        synchronized (LOCK) {
            if (sMainHandler == null) {
                sMainHandler = new Handler(Looper.getMainLooper());
            }
            return sMainHandler;
        }
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
    // 合成线程
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
                final HandlerThread thread = new HandlerThread("VLocHidden-steps");
                thread.start();
                sTickerThread = thread;
                sTickerHandler = new Handler(thread.getLooper());
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
            log("step synthesis disabled: " + reason);
        }
        stopTicker();
    }

    private static final Runnable TICK_TASK = new Runnable() {
        @Override
        public void run() {
            try {
                onTick();
            } catch (Throwable t) {
                log("step tick failed: " + t);
            }
            final Handler handler = sTickerHandler;
            if (handler != null) {
                handler.postDelayed(this, TICK_MS);
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

        final StepsSnapshot snapshot = getSnapshot();
        final boolean replay = snapshot != null && snapshot.active;
        final long nowNanos = SystemClock.elapsedRealtimeNanos();
        int detectorSteps = 0;
        if (replay) {
            sDetectorStepCarry += Math.max(0d, snapshot.cadenceSpm * (TICK_MS / 60000.0d));
            detectorSteps = (int) Math.floor(sDetectorStepCarry);
            sDetectorStepCarry -= detectorSteps;
            if (detectorSteps > MAX_DETECTOR_STEPS_PER_TICK) {
                detectorSteps = MAX_DETECTOR_STEPS_PER_TICK;
                sDetectorStepCarry = 0d;
            }
            logActiveOnce(snapshot);
        } else {
            sDetectorStepCarry = 0d;
        }

        for (Registration registration : registrations) {
            if (registration.target.get() == null) {
                // App 丢掉了 listener 却没注销：把 wrapper 也注销掉，避免空转
                removeRegistration(registration);
                unregisterWrapper(registration);
                continue;
            }
            if (!replay) {
                registration.lastCounterValue = Integer.MIN_VALUE;
                continue;
            }
            if (registration.sensorType == Sensor.TYPE_STEP_COUNTER) {
                if (registration.lastCounterValue != snapshot.steps) {
                    registration.lastCounterValue = snapshot.steps;
                    final SensorEvent event = newSensorEvent(1);
                    if (event == null) {
                        disableSynthesis("SensorEvent(int) ctor unavailable");
                        return;
                    }
                    event.values[0] = snapshot.steps;
                    event.sensor = registration.sensor;
                    event.accuracy = SYNTHETIC_ACCURACY;
                    event.timestamp = nowNanos;
                    postEvent(registration, event);
                }
            } else {
                for (int i = 0; i < detectorSteps; i++) {
                    final SensorEvent event = newSensorEvent(1);
                    if (event == null) {
                        disableSynthesis("SensorEvent(int) ctor unavailable");
                        return;
                    }
                    event.values[0] = 1.0f;
                    event.sensor = registration.sensor;
                    event.accuracy = SYNTHETIC_ACCURACY;
                    final long intervalNanos = snapshot.cadenceSpm > 1d
                            ? (long) (60_000_000_000d / snapshot.cadenceSpm) : 0L;
                    event.timestamp = nowNanos - (long) (detectorSteps - 1 - i) * intervalNanos;
                    postEvent(registration, event);
                }
            }
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
                mainHandler().post(delivery);
            }
        } catch (Throwable ignored) {
            // 目标 Handler 已退出等情况：丢弃这一个事件
        }
    }

    private static void removeRegistration(Registration registration) {
        synchronized (LOCK) {
            sRegistrations.remove(registration);
        }
    }

    private static void unregisterWrapper(Registration registration) {
        try {
            if (registration.manager != null) {
                registration.manager.unregisterListener(registration.wrapper);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ==================================================================
    // SensorEvent 构造（反射，8.1 ~ 15 都是包私有 (int) 构造器）
    // ==================================================================

    static SensorEvent newSensorEvent(int valueSize) {
        try {
            Constructor<?> constructor = sEventConstructor;
            if (constructor == null) {
                if (sEventConstructorFailed) {
                    return null;
                }
                constructor = SensorEvent.class.getDeclaredConstructor(int.class);
                constructor.setAccessible(true);
                sEventConstructor = constructor;
            }
            final Object instance = constructor.newInstance(Integer.valueOf(Math.max(1, valueSize)));
            return instance instanceof SensorEvent ? (SensorEvent) instance : null;
        } catch (Throwable t) {
            if (!sEventConstructorFailed) {
                sEventConstructorFailed = true;
                if (!sLoggedCtorFail) {
                    sLoggedCtorFail = true;
                    log("SensorEvent(int) ctor unavailable, step synthesis disabled: " + t);
                }
            }
            return null;
        }
    }

    /** 复制一个 SensorEvent 并改写 values[0]，避免修改框架/其它 listener 共享的事件对象。 */
    private static SensorEvent copyWithCounterValue(SensorEvent source, int steps) {
        try {
            final int size = source.values != null && source.values.length > 0
                    ? source.values.length : 1;
            final SensorEvent copy = newSensorEvent(size);
            if (copy == null) {
                return null;
            }
            if (source.values != null) {
                System.arraycopy(source.values, 0, copy.values, 0,
                        Math.min(source.values.length, copy.values.length));
            }
            copy.values[0] = steps;
            copy.sensor = source.sensor;
            copy.accuracy = source.accuracy;
            copy.timestamp = source.timestamp;
            return copy;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================================================================
    // listener wrapper
    // ==================================================================

    private static final class StepWrapper implements SensorEventListener2 {
        private final WeakReference<SensorEventListener> target;
        private final int sensorType;

        StepWrapper(SensorEventListener target, int sensorType) {
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
                final StepsSnapshot snapshot = getSnapshot();
                if (snapshot == null || !snapshot.active) {
                    listener.onSensorChanged(event); // 完全透传真实值
                    return;
                }
                if (sensorType == Sensor.TYPE_STEP_COUNTER) {
                    final SensorEvent rewritten = copyWithCounterValue(event, snapshot.steps);
                    listener.onSensorChanged(rewritten != null ? rewritten : event);
                }
                // TYPE_STEP_DETECTOR：真实事件吞掉，改由合成线程投递
            } catch (Throwable t) {
                // 任何处理失败都退回真实事件，绝不吞掉 App 的定位/计步数据
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
                // 目标 App 自己的回调异常：吞掉，不能影响其它 listener
            }
        }
    }

    // ==================================================================
    // steps 快照（content://com.vloc.motion.env/steps，1 秒缓存）
    // ==================================================================

    private static StepsSnapshot getSnapshot() {
        try {
            final long now = SystemClock.elapsedRealtime();
            if (sHasCache && now - sCachedAtElapsed < CACHE_TTL_MS) {
                return sCachedSnapshot;
            }
            synchronized (LOCK) {
                final long locked = SystemClock.elapsedRealtime();
                if (sHasCache && locked - sCachedAtElapsed < CACHE_TTL_MS) {
                    return sCachedSnapshot;
                }
                final StepsSnapshot fresh = querySnapshot();
                sCachedSnapshot = fresh;
                sCachedAtElapsed = SystemClock.elapsedRealtime();
                sHasCache = true;
                return fresh;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static StepsSnapshot querySnapshot() {
        final Context context = currentApplication();
        if (context == null) {
            return null;
        }
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(STEPS_URI, PROJECTION, null, null, null);
            // 读取成功（哪怕 0 行）就回报主 App：用于区分“LSPosed 没勾选”与“模块读了但
            // 目标 App 不走传感器”这两种情况。
            maybePingProvider(context);
            if (cursor == null || !cursor.moveToFirst()) {
                return null; // 无行 = 透传
            }
            final int modeIndex = cursor.getColumnIndex(COL_MODE);
            if (modeIndex < 0) {
                return null;
            }
            final String mode = cursor.getString(modeIndex);
            final int jsonIndex = cursor.getColumnIndex(COL_JSON);
            final String json = jsonIndex >= 0 ? cursor.getString(jsonIndex) : null;
            if (json == null) {
                return null;
            }
            final boolean replayMode = MODE_REPLAY.equalsIgnoreCase(mode);
            final boolean offMode = MODE_OFF.equalsIgnoreCase(mode);
            if (!replayMode && !offMode) {
                return null;
            }

            final JSONObject root = new JSONObject(json);
            final StepsSnapshot snapshot = new StepsSnapshot();
            snapshot.active = replayMode && root.optBoolean("active", false);
            snapshot.steps = root.optInt("steps", 0);
            snapshot.sessionSteps = root.optInt("sessionSteps", 0);
            snapshot.cadenceSpm = root.optDouble("cadenceSpm", 0d);
            snapshot.strideM = root.optDouble("strideM", 0d);
            snapshot.distanceM = root.optDouble("distanceM", 0d);
            snapshot.accelAmp = root.optDouble("accelAmp", 0d);
            snapshot.yawRateDps = root.optDouble("yawRateDps", 0d);
            snapshot.headingDeg = root.optDouble("headingDeg", 0d);
            snapshot.speedMps = root.optDouble("speedMps", 0d);
            snapshot.attitudeEnabled = root.optBoolean("attitude", true);
            // GNSS 卫星快照（json version=2 的 "gnss":[...]）：解析失败 / 字段缺失（老版本主 App）
            // → 空列表，GnssReplay 的所有 hook 一律透传原值。
            try {
                GnssReplay.parseInto(snapshot.gnss, root);
            } catch (Throwable ignored) {
                // 静默降级：绝不影响 steps / imu 回放
            }
            final int updatedIndex = cursor.getColumnIndex(COL_UPDATED_AT);
            snapshot.updatedAt = updatedIndex >= 0
                    ? cursor.getLong(updatedIndex) : root.optLong("updatedAt", 0L);
            return snapshot;
        } catch (Throwable t) {
            logQueryFailOnce(t);
            return null;
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    /** 供 {@link ImuReplay} 复用同一份 steps / accelAmp 快照（1 秒缓存，避免重复查询 provider）。 */
    /** 当前是否在模拟（供其它旁路模块判断要不要改写返回值）。 */
    static boolean snapshotActive() {
        final StepsSnapshot snapshot = currentSnapshot();
        return snapshot != null && snapshot.active;
    }

    static StepsSnapshot currentSnapshot() {
        return getSnapshot();
    }

    /**
     * 目标进程注入成功后回报一次“模块已注入”（provider 方法 {@code module_heartbeat}）。
     *
     * <p>主 App 据此区分三种状态：①模块未注入（LSPosed 没启用 / 作用域没勾 / 没重启目标 App）；
     * ②模块已注入但目标 App 不读传感器（微信运动这类走系统健康数据）；③模块正在读取（steps_ping）。
     *
     * <p>每进程最多成功回报一次；注入早期 {@code ActivityThread.currentApplication()} 可能还是 null，
     * 这里会在主线程延迟重试（最多 6 次 × 1.5s）；全程 try/catch 静默失败，不影响任何 hook。
     */
    static void sendHeartbeatOnce() {
        if (sHeartbeatSent) {
            return;
        }
        try {
            final Context context = currentApplication();
            if (context != null) {
                sHeartbeatSent = true;
                try {
                    final Object result = context.getContentResolver()
                            .call(STEPS_URI, "module_heartbeat", null, null);
                    if (result != null) {
                        log("module_heartbeat ok");
                    }
                } catch (Throwable ignored) {
                    // 旧版主 App 不支持该方法：静默
                }
                return;
            }
        } catch (Throwable ignored) {
            // 拿 Context 失败：继续走重试
        }
        try {
            if (sHeartbeatAttempts < 6) {
                sHeartbeatAttempts++;
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        sendHeartbeatOnce();
                    }
                }, 1500L);
            }
        } catch (Throwable ignored) {
            // 主线程不可用：放弃（不影响功能）
        }
    }

    /**
     * 通知主 App“模块刚刚读过 steps 快照”：调用 provider 的自检方法 {@code steps_ping}，
     * 主界面据此显示“模块已读 Ns前 / 模块未读取”。
     *
     * <p>节流：最多每 {@link #PING_INTERVAL_MS} 毫秒一次；失败静默（旧版主 App 没有该方法、
     * provider 暂不可用等），绝不影响回放。
     */
    private static void maybePingProvider(Context context) {
        try {
            final long now = SystemClock.elapsedRealtime();
            if (sLastPingElapsed != 0L && now - sLastPingElapsed < PING_INTERVAL_MS) {
                return;
            }
            sLastPingElapsed = now;
            context.getContentResolver().call(STEPS_URI, "steps_ping", null, null);
            if (!sLoggedStepsPing) {
                sLoggedStepsPing = true;
                log("steps_ping ok");
            }
        } catch (Throwable ignored) {
            // 静默失败：不影响回放
        }
    }

    /** 拿当前进程的 Application（ActivityThread.currentApplication，@hide）。 */
    static Context currentApplication() {
        try {
            final Class<?> activityThread = Class.forName("android.app.ActivityThread");
            final Object application =
                    XposedHelpers.callStaticMethod(activityThread, "currentApplication");
            if (application instanceof Context) {
                return (Context) application;
            }
        } catch (Throwable ignored) {
            // 早期启动进程 / isolated 进程拿不到
        }
        try {
            final Class<?> helper = XposedHelpers.findClassIfExists(
                    "android.app.AndroidAppHelper", StepReplay.class.getClassLoader());
            if (helper != null) {
                final Object application =
                        XposedHelpers.callStaticMethod(helper, "currentApplication");
                if (application instanceof Context) {
                    return (Context) application;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    // ==================================================================
    // 数据结构
    // ==================================================================

    private static final class Registration {
        final SensorManager manager;
        final WeakReference<SensorEventListener> target;
        final Sensor sensor;
        final int sensorType;
        final Handler handler;
        final StepWrapper wrapper;
        int lastCounterValue = Integer.MIN_VALUE;

        Registration(SensorManager manager, SensorEventListener target, Sensor sensor,
                     int sensorType, Handler handler, StepWrapper wrapper) {
            this.manager = manager;
            this.target = new WeakReference<>(target);
            this.sensor = sensor;
            this.sensorType = sensorType;
            this.handler = handler;
            this.wrapper = wrapper;
        }
    }

    static final class StepsSnapshot {
        boolean active;
        int steps;
        int sessionSteps;
        double cadenceSpm;
        double strideM;
        double distanceM;
        double accelAmp;
        double yawRateDps;
        double headingDeg;
        double speedMps;
        /** 姿态类传感器（ROTATION_VECTOR / MAGNETIC_FIELD）是否也接管；主 App 可通过 JSON 里
         *  {@code "attitude":false} 单独关掉（默认 true，兼容当前主 App 尚未下发该字段）。 */
        boolean attitudeEnabled;
        /** v2 契约的 {@code "gnss":[{...}]} 卫星快照，供 {@link GnssReplay} 按 1Hz 回放。
         *  老版本主 App 不下发该字段时为空列表，GNSS hook 全部透传。 */
        final List<GnssReplay.Satellite> gnss = new ArrayList<>();
        long updatedAt;
    }

    // ==================================================================
    // 日志
    // ==================================================================

    private static void logRegisterOnce(int type) {
        if (sLoggedRegister) {
            return;
        }
        sLoggedRegister = true;
        log("step sensor wrapped: TYPE_STEP_COUNTER=19 / TYPE_STEP_DETECTOR=18, type=" + type);
    }

    private static void logActiveOnce(StepsSnapshot snapshot) {
        if (sLoggedActive) {
            return;
        }
        sLoggedActive = true;
        log("step replay active: steps=" + snapshot.steps
                + ", cadenceSpm=" + snapshot.cadenceSpm);
    }

    private static void logUnregisterOnce(int count) {
        if (sLoggedUnregister) {
            return;
        }
        sLoggedUnregister = true;
        log("step wrapper unregistered (" + count + " registration(s))");
    }

    private static void logQueryFailOnce(Throwable t) {
        if (sLoggedQueryFail) {
            return;
        }
        sLoggedQueryFail = true;
        log("steps provider query failed (passthrough): " + t);
    }

    private static void log(String message) {
        try {
            XposedBridge.log(TAG + message);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
