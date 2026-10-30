package com.vloc.motion.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.vloc.motion.core.Fix;
import com.vloc.motion.core.PacePlan;
import com.vloc.motion.core.StepEngine;
import com.vloc.motion.core.GaitAttitude;
import com.vloc.motion.core.GnssTle;
import com.vloc.motion.core.ImuWaveform;
import com.vloc.motion.core.RealismEngine;
import com.vloc.motion.core.Route;
import com.vloc.motion.core.RoutePlayer;
import com.vloc.motion.core.RouteStore;
import com.vloc.motion.location.GnssCollector;
import com.vloc.motion.location.RootShell;
import com.vloc.motion.location.TestProviderInjector;
import com.vloc.motion.ui.FloatingWindow;
import com.vloc.motion.ui.MainActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 前台服务：驱动路径播放并持续向系统测试 Provider 写入坐标。
 */
public class LocationService extends Service {

    private static final String TAG = "VLocService";

    public static final String ACTION_START_ROUTE = "com.vloc.motion.action.START_ROUTE";
    public static final String ACTION_START_SPOT = "com.vloc.motion.action.START_SPOT";
    public static final String ACTION_TOGGLE_PAUSE = "com.vloc.motion.action.TOGGLE_PAUSE";
    public static final String ACTION_STOP = "com.vloc.motion.action.STOP";
    public static final String ACTION_SET_SPEED = "com.vloc.motion.action.SET_SPEED";
    public static final String ACTION_SET_OVERLAY = "com.vloc.motion.action.SET_OVERLAY";
    /** 开始 / 继续移动（悬浮窗「开始」） */
    public static final String ACTION_RESUME_MOVE = "com.vloc.motion.action.RESUME_MOVE";
    /** 停止移动但保持定位（悬浮窗「暂停 / 停止移动」，不恢复真实定位） */
    public static final String ACTION_HOLD_MOVE = "com.vloc.motion.action.HOLD_MOVE";

    public static final String EXTRA_SPEED_KMH = "speed_kmh";
    public static final String EXTRA_LOOP = "loop";
    public static final String EXTRA_PROVIDERS = "providers";
    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LON = "lon";
    public static final String EXTRA_ACCURACY = "accuracy";
    public static final String EXTRA_KMH = "kmh";
    public static final String EXTRA_OVERLAY_ENABLED = "overlay_enabled";
    public static final String EXTRA_GOAL_ENABLED = "goal_enabled";
    public static final String EXTRA_GOAL_LAP_M = "goal_lap_m";
    public static final String EXTRA_GOAL_DISTANCE_M = "goal_distance_m";
    public static final String EXTRA_GOAL_TIME_S = "goal_time_s";
    public static final String EXTRA_GOAL_PACE_S = "goal_pace_s";
    public static final String EXTRA_GOAL_MAX_KMH = "goal_max_kmh";
    public static final String EXTRA_SPEED_VARIANCE = "speed_variance";

    private static final String CHANNEL_ID = "vloc_playback";
    private static final int NOTIFICATION_ID = 1001;
    private static final long TICK_MS = 100L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RoutePlayer player = new RoutePlayer();

    private TestProviderInjector injector;
    private PowerManager.WakeLock wakeLock;
    private Runnable ticker;
    private float accuracyMeters = 5.0f;
    private double spotLat;
    private double spotLon;
    private long lastNotificationMs;
    private final RealismEngine realism = new RealismEngine();
    /** 注入间隔抖动：真实 GNSS 回调间隔并不恒定，固定 200ms 本身是一种特征 */
    private final java.util.Random intervalRandom = new java.util.Random();
    /** 是否已到达终点并处于"保持定位"状态 */
    private final StepEngine stepEngine = new StepEngine();
    private final java.util.Random random = new java.util.Random();
    private int lastSatellites;
    private float lastCn0Mean;
    private float lastCn0Max;
    private long lastStepTickMs;
    private boolean arrived;
    private long lastHoldPushMs;
    private float baseAltitude = 50.0f;
    /** 系统级（Zygisk）传感器状态：相位与噪声种子。 */
    private double nativeImuPhase;
    private long nativeImuTick;
    private final float[] nativeAccel = new float[3];
    private final float[] nativeGyro = new float[3];
    private final float[] nativeGravity = new float[3];
    private final float[] nativeRotation = new float[4];
    private final float[] nativeMagnetic = new float[3];
    private FloatingWindow floatingWindow;

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
        loadGnssCatalog();
        VlocStateFile.init(this);
    }

    /**
     * 载入内置的真实 GNSS 星表（assets/gnss_tle.txt，CelesTrak TLE 快照）。
     *
     * <p>失败不影响主流程：没有星表时不产出 gnss 数据，模块会透传真实卫星状态。
     */
    private void loadGnssCatalog() {
        String text = null;
        try (java.io.InputStream is = getAssets().open("gnss_tle.txt")) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = is.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            text = buffer.toString("UTF-8");
        } catch (Exception e) {
            android.util.Log.w("VLocService", "load gnss tle failed: " + e);
        }
        if (text != null) {
            realism.setTleData(text);
            final long newest = GnssTle.newestEpochMs(GnssTle.parse(text));
            final long ageDays = newest <= 0 ? -1
                    : (System.currentTimeMillis() - newest) / 86400000L;
            if (ageDays > 45) {
                android.util.Log.w("VLocService", "gnss tle stale: " + ageDays
                        + " 天未更新，仰角/方位角误差会变大；用 scripts/update_gnss_tle.py 刷新");
            }
            android.util.Log.i("VLocService", "gnss catalog=" + realism.gnssCatalogSize()
                    + " tleAgeDays=" + ageDays);
        } else {
            android.util.Log.w("VLocService", "gnss catalog missing: 不产出卫星数据");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }
        switch (intent.getAction()) {
            case ACTION_START_ROUTE:
                startRoute(intent);
                break;
            case ACTION_START_SPOT:
                startSpot(intent);
                break;
            case ACTION_TOGGLE_PAUSE:
                togglePause();
                break;
            case ACTION_STOP:
                stopInternal(null);
                break;
            case ACTION_SET_SPEED:
                player.setSpeedKmh(intent.getDoubleExtra(EXTRA_KMH, 18.0));
                Session.INSTANCE.speedKmh = intent.getDoubleExtra(EXTRA_KMH, 18.0);
                Session.INSTANCE.notifyChanged();
                updateNotification();
                break;
            case ACTION_SET_OVERLAY:
                setOverlayEnabled(intent.getBooleanExtra(EXTRA_OVERLAY_ENABLED, false));
                break;
            case ACTION_RESUME_MOVE:
                resumeMovement();
                break;
            case ACTION_HOLD_MOVE:
                holdMovement();
                break;
            default:
                break;
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopTicker();
        releaseInjector();
        hideOverlay();
        releaseWakeLock();
        // 定位会话结束后不主动弹摄像头悬浮窗：交给用户在主界面「摄像头」页点播放时再拉起
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 启动

    private void startRoute(Intent intent) {
        Session session = Session.INSTANCE;
        if (session.isActive()) {
            // 重新开始（例如到达终点后点「重新开始」）：只重置运行态，不要 stopSelf 把服务拆掉
            resetForRestart();
        }
        Route route = session.route;
        if (route == null || route.size() < 2) {
            toast("路径至少需要 2 个点");
            return;
        }
        accuracyMeters = intent.getFloatExtra(EXTRA_ACCURACY, 5.0f);
        List<String> providers = readProviders(intent);
        configureRealism(true);
        arrived = false;
        lastHoldPushMs = 0L;
        session.arrived = false;
        stepEngine.start();
        lastStepTickMs = 0L;
        StepSession.INSTANCE.clear();

        player.setRoute(route);
        // 随机起点：在路线全程随机取一点作为起点（每次开始都不同）
        double startOffset = 0.0;
        if (RouteStore.loadRandomStart(this) && route.totalMeters() > 10) {
            startOffset = random.nextDouble() * route.totalMeters();
        }
        player.setSpeedKmh(intent.getDoubleExtra(EXTRA_SPEED_KMH, 18.0));
        player.setLoop(intent.getBooleanExtra(EXTRA_LOOP, false));
        player.setSpeedVariance(intent.getBooleanExtra(EXTRA_SPEED_VARIANCE, true));

        boolean goalEnabled = intent.getBooleanExtra(EXTRA_GOAL_ENABLED, false);
        PacePlan plan = null;
        if (goalEnabled) {
            double lap = intent.getFloatExtra(EXTRA_GOAL_LAP_M, 400f);
            double target = intent.getFloatExtra(EXTRA_GOAL_DISTANCE_M, 1000f);
            int paceSeconds = intent.getIntExtra(EXTRA_GOAL_PACE_S, 0);
            double maxSpeed = intent.getFloatExtra(EXTRA_GOAL_MAX_KMH, 15f) / 3.6;
            double seconds;
            if (paceSeconds > 0) {
                // 配速优先：时间 = 实际距离 ÷ 配速（按路线实际长度重算，避免路线不一致导致偏差）
                int laps = Math.max(1, (int) Math.ceil(target / Math.max(1.0, lap)));
                double actualMeters = laps * Math.max(1.0, route.totalMeters());
                seconds = actualMeters / 1000.0 * paceSeconds;
            } else {
                seconds = intent.getIntExtra(EXTRA_GOAL_TIME_S, 600);
            }
            plan = PacePlan.create(lap, target, seconds, maxSpeed, route.totalMeters());
            player.setPacePlan(plan);
            player.setRampSeconds(0);
            session.goalMode = true;
            session.goalLaps = plan.laps;
            session.goalDistance = plan.actualMeters;
            session.goalSeconds = plan.durationSeconds;
            session.goalAvgKmh = plan.avgSpeedMps * 3.6;
            session.goalFeasible = plan.feasible;
            if (!plan.feasible) {
                toast("目标不可达：平均速度超过上限，将按最大速度约 "
                        + Math.round(plan.durationSeconds) + " 秒完成");
            }
        } else {
            player.setPacePlan(null);
            session.goalMode = false;
        }
        // 两段式启动：先把定位定在起点并保持，路线移动等悬浮窗「开始」
        player.stop();
        player.seek(startOffset);
        session.held = true;

        session.mode = Session.Mode.ROUTE;
        session.paused = false;
        session.error = null;
        session.route = route;
        Route.Sample startSample = route.sampleAt(startOffset);
        if (startSample != null) {
            session.startLat = startSample.lat;
            session.startLon = startSample.lon;
            session.hasStart = true;
        }
        session.speedKmh = player.getCurrentSpeedMps() * 3.6;
        session.totalMeters = plan != null ? plan.actualMeters : route.totalMeters();
        session.traveledMeters = 0;
        session.notifyChanged();

        startForegroundSafe(buildNotification("准备注入…"));
        acquireWakeLock();
        collectGnssSnapshot();

        new Thread(() -> {
            boolean ok = ensureMockPermissionAndPrepare(providers);
            handler.post(() -> {
                if (!ok) {
                    stopInternal(Session.INSTANCE.error);
                    return;
                }
                Session.INSTANCE.prepared = true;
                Session.INSTANCE.notifyChanged();
                startTicker();
                updateNotification();
                // 主界面「开始」同时启动悬浮窗（有权限时），悬浮窗负责开始/暂停/保持
                if (Settings.canDrawOverlays(this)) {
                    RouteStore.saveOverlayEnabled(this, true);
                    showOverlay();
                    toast("已定位到起点并保持定位\n在悬浮窗点「开始」开始路线模拟");
                } else {
                    toast("已定位到起点并保持定位（悬浮窗未授权，可到菜单「悬浮窗」按提示开通）");
                }
            });
        }, "vloc-prepare").start();
    }

    private void startSpot(Intent intent) {
        Session session = Session.INSTANCE;
        if (session.isActive()) {
            resetForRestart();
        }
        spotLat = intent.getDoubleExtra(EXTRA_LAT, 0.0);
        spotLon = intent.getDoubleExtra(EXTRA_LON, 0.0);
        accuracyMeters = intent.getFloatExtra(EXTRA_ACCURACY, 5.0f);
        List<String> providers = readProviders(intent);
        configureRealism(false);
        arrived = false;
        lastHoldPushMs = 0L;
        session.arrived = false;
        StepSession.INSTANCE.clear();
        stepEngine.start();
        stepEngine.idle();
        lastStepTickMs = 0L;

        session.mode = Session.Mode.SPOT;
        session.paused = false;
        session.error = null;
        session.lat = spotLat;
        session.lon = spotLon;
        session.speedKmh = 0;
        session.traveledMeters = 0;
        session.totalMeters = 0;
        session.notifyChanged();

        startForegroundSafe(buildNotification("准备注入…"));
        acquireWakeLock();
        collectGnssSnapshot();

        new Thread(() -> {
            boolean ok = ensureMockPermissionAndPrepare(providers);
            handler.post(() -> {
                if (!ok) {
                    stopInternal(Session.INSTANCE.error);
                    return;
                }
                Session.INSTANCE.prepared = true;
                Session.INSTANCE.notifyChanged();
                startTicker();
                updateNotification();
                // 主界面「开始」同时启动悬浮窗（有权限时），悬浮窗负责开始/暂停/保持
                if (Settings.canDrawOverlays(this)) {
                    RouteStore.saveOverlayEnabled(this, true);
                    showOverlay();
                    toast("已定位到起点并保持定位\n在悬浮窗点「开始」开始路线模拟");
                } else {
                    toast("已定位到起点并保持定位（悬浮窗未授权，可到菜单「悬浮窗」按提示开通）");
                }
            });
        }, "vloc-prepare").start();
    }

    /** 服务不退出、只重置播放/注入状态（用于"重新开始"）。 */
    private void resetForRestart() {
        stopTicker();
        releaseInjector();
        hideOverlay();
        releaseWakeLock();
        arrived = false;
        lastHoldPushMs = 0L;
    }

    /** 采集真实卫星分布（用于模块回放 GnssStatus），失败则复用上次快照。 */
    private void collectGnssSnapshot() {
        GnssCollector.collect(this, json -> {
            if (json != null) {
                GnssSession.INSTANCE.set(json);
            }
        });
    }

    /** 读取并应用"拟真模式 / 海拔 / 起步缓动"配置。 */
    private void configureRealism(boolean withRamp) {
        baseAltitude = RouteStore.loadAltitude(this);
        boolean realismOn = RouteStore.loadRealism(this);
        realism.setEnabled(realismOn);
        // 新会话：漂移归零 + 重新抽一套卫星组合（逐颗 GNSS 数据随 steps JSON 下发）
        realism.startNewSession();
        player.setRampSeconds(realismOn && withRamp ? 2.0 : 0.0);
    }

    /** 拿 root → 授权 appops → 接管测试 Provider。 */
    private boolean ensureMockPermissionAndPrepare(List<String> providers) {
        Session session = Session.INSTANCE;
        String pkg = getPackageName();

        // 系统级注入：定位由模块在 system_server 改写，本进程不接管测试 Provider，
        // 也就不需要 appops / 测试 Provider（但模块本身依赖 root + LSPosed 已安装）
        if (RouteStore.loadSystemInject(this)) {
            releaseInjector();
            session.rootOk = RootShell.isRooted();
            return true;
        }

        if (!RootShell.isRooted()) {
            session.rootOk = false;
            session.error = "未获得 root 权限，请确认设备已 root 并在 root 管理器中授权本应用";
            return false;
        }
        session.rootOk = true;

        RootShell.Result current = RootShell.getMockAppop(pkg);
        if (!RootShell.isMockAppopAllowed(current)) {
            RootShell.Result grant = RootShell.grantMockAppop(pkg);
            if (!grant.ok()) {
                session.error = "授予模拟位置权限失败：" + grant.combined();
                return false;
            }
            current = RootShell.getMockAppop(pkg);
            if (!RootShell.isMockAppopAllowed(current)) {
                session.error = "模拟位置权限未生效（appops：" + current.combined() + "）";
                return false;
            }
        }

        injector = new TestProviderInjector(this, providers);
        try {
            injector.prepare();
        } catch (SecurityException e) {
            injector = null;
            session.error = "接管定位 Provider 被拒绝：" + e.getMessage();
            return false;
        } catch (RuntimeException e) {
            injector = null;
            session.error = "接管定位 Provider 失败：" + e;
            return false;
        }
        return true;
    }

    private List<String> readProviders(Intent intent) {
        ArrayList<String> list = intent.getStringArrayListExtra(EXTRA_PROVIDERS);
        if (list == null || list.isEmpty()) {
            list = new ArrayList<>();
            list.add("gps");
            list.add("network");
        }
        return list;
    }

    // ---------------------------------------------------------------- 播放循环

    private void startTicker() {
        stopTicker();
        ticker = new Runnable() {
            @Override
            public void run() {
                // 服务已被停止（例如准备阶段用户就点了停止）：结束循环，避免野回调
                if (Session.INSTANCE.mode == Session.Mode.IDLE) {
                    return;
                }
                if (Session.INSTANCE.mode == Session.Mode.SPOT) {
                    pushSpotLocation();
                    handler.postDelayed(this, 1000L);
                    return;
                }
                long now = SystemClock.uptimeMillis();
                boolean advanced = player.advance(now);
                Route.Sample sample = player.currentSample();
                if (advanced && sample != null && !player.isPaused()) {
                    Fix fix = buildRouteFix(sample);
                    injectFix(fix);
                    updateSessionFromFix(sample, fix);
                }
                if (player.isFinished()) {
                    if (!arrived) {
                        arrived = true;
                        player.pause();
                        Session.INSTANCE.arrived = true;
                        Session.INSTANCE.paused = true;
                        Session.INSTANCE.notifyChanged();
                        if (RouteStore.loadAutoStopOnFinish(LocationService.this)) {
                            // 到达目标：先展示「已到达」，2.5 秒后自动彻底停止并恢复真实定位
                            toast("已到达目标，正在自动停止…");
                            handler.postDelayed(autoStopRunnable, AUTO_STOP_DELAY_MS);
                        } else {
                            toast("已到达终点，定位保持在终点（点「停止」恢复真实定位）");
                        }
                    }
                    holdPosition(now, sample);
                } else if (player.isPaused() || Session.INSTANCE.held) {
                    // 暂停 / 停止移动时真实 GPS 仍在持续上报，保持 1Hz 同一坐标，避免"定位冻结"
                    holdPosition(now, sample);
                }
                updateSteps(now);
                updateNotification();
                updateOverlay();
                handler.postDelayed(this, TICK_MS + intervalRandom.nextInt(40));
            }
        };
        handler.post(ticker);
    }

    /** 到达目标后自动停止的延迟：留出时间让用户看到「已到达」状态。 */
    private static final long AUTO_STOP_DELAY_MS = 2500L;

    private final Runnable autoStopRunnable = new Runnable() {
        @Override
        public void run() {
            if (!arrived) {
                return;   // 期间用户已经手动停止/重新开始
            }
            stopInternal("已到达目标 · 已自动停止，定位已恢复");
        }
    };

    private void stopTicker() {
        if (ticker != null) {
            handler.removeCallbacks(ticker);
            ticker = null;
        }
    }

    private void pushSpotLocation() {
        Fix fix = realism.apply(spotLat, spotLon, baseAltitude, 0.0, 0.0, accuracyMeters);
        // 系统级注入：本进程不写测试 Provider，但要发布快照给模块
        LocationSession.INSTANCE.update(fix.lat, fix.lon, fix.altitudeMeters,
                fix.speedMps, fix.bearingDeg, fix.accuracyMeters, fix.satellites);
        if (injector != null) {
            try {
                injector.push(fix, System.currentTimeMillis());
            } catch (RuntimeException e) {
                failInjection(e);
                return;
            }
        }
        Session session = Session.INSTANCE;
        session.lat = fix.lat;
        session.lon = fix.lon;
        session.altitude = fix.altitudeMeters;
        session.accuracyMeters = fix.accuracyMeters;
        session.satellites = fix.satellites;
        lastSatellites = fix.satellites;
        lastCn0Mean = fix.meanCn0;
        lastCn0Max = fix.maxCn0;
        session.traveledMeters = 0;
        session.totalMeters = 0;
        session.lastUpdateMs = System.currentTimeMillis();
        session.notifyChanged();
    }

    /** 轨迹采样 → 拟真化（漂移/噪声/卫星数据）→ 注入。 */
    private Fix buildRouteFix(Route.Sample sample) {
        double altitude = Double.isNaN(sample.altitude) ? baseAltitude : sample.altitude;
        return realism.apply(sample.lat, sample.lon, altitude,
                player.getCurrentSpeedMps(), sample.bearingDeg, accuracyMeters);
    }

    private void injectFix(Fix fix) {
        // 无论哪种注入方式，都先把最新快照发布给模块（系统级注入在 system_server 读取它）
        LocationSession.INSTANCE.update(fix.lat, fix.lon, fix.altitudeMeters,
                fix.speedMps, fix.bearingDeg, fix.accuracyMeters, fix.satellites);
        if (injector == null) {
            return;   // 系统级注入：本进程不写测试 Provider
        }
        try {
            injector.push(fix, System.currentTimeMillis());
        } catch (RuntimeException e) {
            failInjection(e);
        }
    }

    /** 注入失败（provider 被系统移除、appop 被撤销等）：停止并提示，不要让界面假装在跑。 */
    private void failInjection(RuntimeException e) {
        Log.w(TAG, "inject failed", e);
        stopInternal("定位注入失败，已停止：" + e.getMessage());
    }

    private void updateSessionFromFix(Route.Sample sample, Fix fix) {
        lastSatellites = fix.satellites;
        lastCn0Mean = fix.meanCn0;
        lastCn0Max = fix.maxCn0;
        Session session = Session.INSTANCE;
        session.lat = fix.lat;
        session.lon = fix.lon;
        session.altitude = fix.altitudeMeters;
        session.accuracyMeters = fix.accuracyMeters;
        session.satellites = fix.satellites;
        lastSatellites = fix.satellites;
        lastCn0Mean = fix.meanCn0;
        lastCn0Max = fix.maxCn0;
        session.traveledMeters = player.getGlobalTraveledMeters();
        session.totalMeters = player.getPacePlan() != null
                ? player.getPacePlan().actualMeters : sample.totalMeters;
        session.speedKmh = player.getCurrentSpeedMps() * 3.6;
        session.completedLaps = player.getDisplayLap();
        session.paused = player.isPaused();
        session.lastUpdateMs = System.currentTimeMillis();
        session.notifyChanged();
    }

    /** 暂停/到达终点时按 1Hz 持续上报同一坐标（含噪声与卫星数据），模拟真实静止定位。 */
    private void holdPosition(long nowMs, Route.Sample sample) {
        if (sample == null) {
            return;   // injectFix 内部已处理"系统级模式没有 injector"的情况
        }
        if (nowMs - lastHoldPushMs < 1000L) {
            return;
        }
        lastHoldPushMs = nowMs;
        double altitude = Double.isNaN(sample.altitude) ? baseAltitude : sample.altitude;
        Fix fix = realism.apply(sample.lat, sample.lon, altitude, 0.0, sample.bearingDeg,
                accuracyMeters);
        injectFix(fix);
        updateSessionFromFix(sample, fix);
    }

    /** 由当前速度推进步数模型，并发布给模块。 */
    private void updateSteps(long nowMs) {
        Session session = Session.INSTANCE;
        if (session.mode == Session.Mode.IDLE) {
            return;
        }
        double dt = lastStepTickMs == 0L ? 0.0 : (nowMs - lastStepTickMs) / 1000.0;
        lastStepTickMs = nowMs;
        double speed = (player.isPaused() || session.held || player.isFinished())
                ? 0.0 : player.getCurrentSpeedMps();
        Route.Sample sample = player.currentSample();
        double bearing = sample == null ? Double.NaN : sample.bearingDeg;
        stepEngine.update(speed, Math.min(1.0, Math.max(0.0, dt)), bearing);
        if (speed <= 0.15) {
            stepEngine.idle();
        }
        session.steps = stepEngine.getSteps();
        session.sessionSteps = stepEngine.getSessionSteps();
        session.cadenceSpm = stepEngine.getCadenceSpm();
        session.strideMeters = stepEngine.getStrideMeters();
        StepSession.INSTANCE.update(
                stepEngine.toJson(true, RouteStore.loadAttitudeReplay(this),
                        lastSatellites, lastCn0Mean, lastCn0Max, realism.gnssJson()), true);
        publishNativeSensorState(dt, speed, bearing);
    }

    /**
     * 发布给 Zygisk 模块的传感器状态（系统级传感器注入用）。
     *
     * <p>只在「系统级注入」开关打开时写；否则写一帧 active=false 让模块透传真实传感器。
     */
    private void publishNativeSensorState(double dt, double speed, double bearing) {
        try {
            if (!RouteStore.loadSystemInject(this)) {
                if (nativeImuTick != -1L) {
                    nativeImuTick = -1L;
                    VlocStateFile.clear();
                }
                return;
            }
            nativeImuTick++;
            final double cadence = stepEngine.getCadenceSpm();
            final double phaseStep = ImuWaveform.phase(cadence, Math.max(0.0, dt));
            nativeImuPhase += phaseStep;
            if (nativeImuPhase > Math.PI * 2) {
                nativeImuPhase %= Math.PI * 2;
            }
            final double heading = Double.isNaN(bearing) ? 0.0 : bearing;
            final boolean attitude = RouteStore.loadAttitudeReplay(this);
            ImuWaveform.accelerometerWithAttitude(heading, cadence, nativeImuPhase, speed,
                    nativeImuTick, nativeAccel);
            ImuWaveform.gravityWithAttitude(heading, cadence, nativeImuPhase, speed,
                    nativeImuTick, nativeGravity);
            ImuWaveform.gyroscope(ImuWaveform.accelAmplitude(speed), nativeImuPhase, 0.0,
                    nativeImuTick, nativeGyro);
            GaitAttitude.rotationVector(heading, cadence, nativeImuPhase, speed, nativeRotation);
            GaitAttitude.magneticField(heading, cadence, nativeImuPhase, speed, nativeMagnetic);
            VlocStateFile.write(true, stepEngine.getSteps(), (float) cadence, (float) speed,
                    (float) heading, nativeAccel, nativeGyro, nativeGravity,
                    nativeRotation, nativeMagnetic, attitude);
        } catch (Throwable ignored) {
            // 发布失败不影响主流程
        }
    }

    /** 从悬浮窗打开主界面（查看自检/启用步骤）。 */
    private void openMainActivity() {
        try {
            Intent intent = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } catch (RuntimeException ignored) {
            // 忽略
        }
    }

    // ---------------------------------------------------------------- 控制

    private void togglePause() {
        Session session = Session.INSTANCE;
        if (session.mode != Session.Mode.ROUTE) {
            return;
        }
        if (player.isPaused() || session.held) {
            resumeMovement();
        } else {
            player.pause();
            session.paused = true;
            session.held = false;
            toast("已暂停移动：定位保持在当前位置");
            session.notifyChanged();
            updateNotification();
            updateOverlay();
        }
    }

    /** 悬浮窗「停止移动」：停住路线，但定位继续保持在当前位置（不恢复真实定位）。 */
    private void holdMovement() {
        Session session = Session.INSTANCE;
        if (session.mode == Session.Mode.IDLE) {
            return;
        }
        if (session.mode == Session.Mode.ROUTE) {
            player.pause();
        }
        session.held = true;
        session.paused = false;
        toast("已停止移动：定位保持在当前位置。\n回到「虚拟运动」主界面点「彻底停止」才会恢复真实定位");
        session.notifyChanged();
        updateNotification();
        updateOverlay();
    }

    /** 悬浮窗「开始 / 继续」：从当前位置继续移动。 */
    private void resumeMovement() {
        Session session = Session.INSTANCE;
        if (session.mode != Session.Mode.ROUTE) {
            return;   // 临时定位模式没有"移动"概念
        }
        session.held = false;
        arrived = false;
        session.arrived = false;
        if (session.mode == Session.Mode.ROUTE) {
            if (player.isFinished()) {
                player.reset();
                player.start();
            } else if (player.isPaused() || !player.isRunning()) {
                player.resume();
            } else {
                player.start();
            }
        }
        session.paused = false;
        toast("开始移动");
        startTicker();
        session.notifyChanged();
        updateNotification();
        updateOverlay();
    }

    private void stopInternal(String message) {
        stopTicker();
        releaseInjector();
        hideOverlay();
        releaseWakeLock();
        Session session = Session.INSTANCE;
        if (message != null) {
            session.error = message;
            toast(message);
        }
        arrived = false;
        lastHoldPushMs = 0L;
        EnvSession.INSTANCE.clear();
        StepSession.INSTANCE.clear();
        LocationSession.INSTANCE.clear();
        VlocStateFile.clear();
        GnssSession.INSTANCE.clear();
        session.mode = Session.Mode.IDLE;
        session.paused = false;
        session.held = false;
        session.arrived = false;
        session.prepared = false;
        session.goalMode = false;
        session.completedLaps = 0;
        session.notifyChanged();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releaseInjector() {
        if (injector != null) {
            try {
                injector.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "release injector failed", e);
            }
            injector = null;
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) {
            return;
        }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vloc:playback");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(60 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    // ---------------------------------------------------------------- 通知

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "定位播放",
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.setDescription("虚拟定位运行状态");
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent pauseIntent = new Intent(this, LocationService.class)
                .setAction(ACTION_TOGGLE_PAUSE);
        PendingIntent pausePending = PendingIntent.getService(this, 1, pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, LocationService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 2, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Session session = Session.INSTANCE;
        String title = session.mode == Session.Mode.SPOT ? "虚拟运动 · 临时定位" : "虚拟运动 · 路径播放";
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, (session.paused || session.held) ? "继续" : "暂停", pausePending)
                .addAction(0, "彻底停止", stopPending)
                .build();
    }

    private void updateNotification() {
        long now = SystemClock.uptimeMillis();
        if (now - lastNotificationMs < 1000L) {
            return;
        }
        lastNotificationMs = now;
        Session session = Session.INSTANCE;
        String text;
        if (session.mode == Session.Mode.SPOT) {
            text = String.format(java.util.Locale.US, "%.6f, %.6f", session.lat, session.lon);
        } else {
            text = String.format(java.util.Locale.US, "%s%s · %.0f km/h · %s / %s",
                    session.goalMode ? "配速 " : "",
                    session.paused ? "已暂停" : "运行中",
                    session.speedKmh,
                    formatDistance(session.traveledMeters),
                    formatDistance(session.totalMeters));
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    // ---------------------------------------------------------------- 悬浮窗

    private void setOverlayEnabled(boolean enabled) {
        RouteStore.saveOverlayEnabled(this, enabled);
        if (!enabled) {
            hideOverlay();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("请先在系统设置中允许本应用显示在其他应用上层");
            return;
        }
        showOverlay();
    }

    private void showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            toast("悬浮窗权限未授予，已跳过（可在菜单「悬浮窗」里按提示授权）");
            return;
        }
        if (floatingWindow == null) {
            floatingWindow = new FloatingWindow(this, new FloatingWindow.Actions() {
                @Override
                public void onStatusClick() {
                    openMainActivity();
                }

                @Override
                public void onStart() {
                    resumeMovement();
                }

                @Override
                public void onPause() {
                    togglePause();
                }

                @Override
                public void onHold() {
                    holdMovement();
                }

                @Override
                public void onCameraSwitch() {
                    switchCameraClip();
                }

                @Override
                public void onCameraToggle() {
                    toggleCameraEnabled();
                }

                @Override
                public void onCameraPlayPause() {
                    toggleCameraPlaying();
                }
            });
        }
        floatingWindow.restoreCollapsed();
        floatingWindow.show();
        updateOverlay();
    }

    private void hideOverlay() {
        if (floatingWindow != null) {
            floatingWindow.hide();
        }
    }

    private void updateOverlay() {
        if (floatingWindow == null || !floatingWindow.isShown()) {
            return;
        }
        Session session = Session.INSTANCE;
        boolean moving = session.mode == Session.Mode.ROUTE
                && !session.paused && !session.held && !session.arrived;
        String line1;
        if (session.mode == Session.Mode.SPOT) {
            line1 = String.format(java.util.Locale.US, "临时定位 · %.0f m", session.altitude);
        } else if (session.held && session.traveledMeters <= 1.0) {
            line1 = "已定位到起点 · 待开始（点开始移动）";
        } else if (session.held) {
            line1 = String.format(java.util.Locale.US, "已停止移动 · 定位保持 · %.0f m", session.altitude);
        } else if (session.arrived) {
            line1 = "已到达终点 · 定位保持";
        } else {
            line1 = String.format(java.util.Locale.US, "%s · %.1f km/h",
                    session.paused ? "已暂停" : "运行中", session.speedKmh);
        }
        String line2 = String.format(java.util.Locale.US, "%s · 海拔 %.0f m · 步 %d（%d 步/分）",
                formatDistance(session.traveledMeters), session.altitude,
                session.sessionSteps, Math.round(session.cadenceSpm));
        long lastRead = StepSession.INSTANCE.moduleLastReadAt;
        long heartbeat = StepSession.INSTANCE.moduleHeartbeatAt;
        String status;
        if (lastRead > 0) {
            long age = Math.max(0, (System.currentTimeMillis() - lastRead) / 1000);
            status = String.format(java.util.Locale.US, "模块读取中 %ds前", age);
        } else if (heartbeat > 0) {
            status = "模块已注入（目标App未读传感器）";
        } else {
            status = "模块未注入（检查 LSPosed 作用域）";
        }
        floatingWindow.update(line1, line2, status, moving);
        // 跑步悬浮窗不再显示摄像头/视频那一行（视频控制只在「虚拟摄像头」悬浮窗里）
    }

    /** 悬浮窗：切换到下一个虚拟摄像头视频。 */
    private void switchCameraClip() {
        java.util.List<com.vloc.motion.core.CameraClips.Clip> clips =
                RouteStore.loadCameraClips(this);
        if (clips.isEmpty()) {
            toast("还没有添加视频：主界面菜单 →「虚拟摄像头」");
            return;
        }
        int next = com.vloc.motion.core.CameraClips.nextIndex(
                clips, RouteStore.loadCameraIndex(this));
        RouteStore.saveCameraIndex(this, next);
        com.vloc.motion.core.CameraClips.Clip clip = clips.get(next);
        toast(String.format(java.util.Locale.US, "虚拟摄像头 %d/%d%s",
                next + 1, clips.size(), clip.name.isEmpty() ? "" : "：" + clip.name));
        updateOverlay();
    }

    /** 悬浮窗：播放 / 暂停虚拟摄像头视频。 */
    private void toggleCameraPlaying() {
        boolean playing = !RouteStore.loadCameraPlaying(this);
        RouteStore.saveCameraPlaying(this, playing);
        toast(playing ? "虚拟摄像头：播放" : "虚拟摄像头：已暂停（画面冻结）");
        updateOverlay();
    }

    /** 悬浮窗：开 / 关虚拟摄像头。 */
    private void toggleCameraEnabled() {
        boolean enabled = !RouteStore.loadCameraEnabled(this);
        RouteStore.saveCameraEnabled(this, enabled);
        toast(enabled ? "虚拟摄像头：已开启" : "虚拟摄像头：已关闭");
        updateOverlay();
    }

    private void startForegroundSafe(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "startForeground failed", e);
            Session.INSTANCE.error = "前台服务启动失败：" + e.getMessage();
        }
    }

    private void toast(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        handler.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private static String formatDistance(double meters) {
        if (meters < 1000) {
            return String.format(java.util.Locale.US, "%.0f m", meters);
        }
        return String.format(java.util.Locale.US, "%.2f km", meters / 1000.0);
    }
}
