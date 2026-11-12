package com.vloc.motion.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
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
import com.vloc.motion.location.LocationSwitch;
import com.vloc.motion.location.RealLocationProvider;
import com.vloc.motion.location.RootShell;
import com.vloc.motion.location.TestProviderInjector;
import com.vloc.motion.ui.FloatingWindow;
import com.vloc.motion.ui.MainActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

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
    /** 系统级注入失效告警用独立 id：复用前台通知 id 会把常驻通知顶掉。 */
    private static final int ALERT_NOTIFICATION_ID = 1002;
    private static final long TICK_MS = 100L;

    // ---------------------------------------------------------------- 系统级注入存活判定
    /**
     * "模块正在接管"的新鲜度窗口（毫秒）。
     *
     * <p>依据：system_server 里的模块每拦截一次定位/卫星投递就会回读本应用的快照，
     * {@code hidden/SystemHooks.java} 的定位缓存 {@code LOCATION_CACHE_MS = 120ms}
     * 意味着目标 App 正常定位时约每 120ms 就有一次 provider 读取；真实定位回调通常 1Hz。
     * 取 5 秒 ≈ 40 个读取周期：足够吸收系统调度 / 息屏抖动，又不会把"模块早就没在接管"
     * 误判成在线（更长的窗口会让假成功继续存在）。
     */
    private static final long SYSTEM_MODULE_FRESH_MS = 5_000L;
    /** 没有既有读取痕迹时，等待模块首次读取快照的上限（毫秒）。 */
    private static final long SYSTEM_MODULE_PROBE_WAIT_MS = 3_000L;
    /** 轮询间隔（毫秒）。 */
    private static final long SYSTEM_MODULE_PROBE_POLL_MS = 100L;
    /**
     * 运行期"模块完全没动静"的告警门槛（毫秒），比启动时的 5 秒宽松得多。
     *
     * <p>原因：启动阶段用严格窗口 {@link #SYSTEM_MODULE_FRESH_MS} 卡住假成功就够了；
     * 运行中目标 App 可能暂时不请求定位（被切到后台 / 暂停），模块自然没有回读，
     * 用 5 秒会在正常环境里误报。30 秒（≈300 次注入 tick）足以把"短暂空闲"和
     * "模块已经不在接管"区分开，同时最早也能在 30 秒内暴露静默空转。
     */
    private static final long SYSTEM_MODULE_SILENCE_WARN_MS = 30_000L;
    /**
     * 模块心跳（{@code kind=system}）的可信窗口（毫秒）。
     *
     * <p>心跳按"进程注入成功"打一次，不是周期性的：模块注入 system_server 后可能几小时都
     * 不再上报，所以不能用 5 秒 / 30 秒那种活跃窗口去卡它，取 24 小时。
     */
    private static final long SYSTEM_MODULE_HEARTBEAT_MS = HookHeartbeatStore.FRESH_MS;
    /** 系统级注入但模块不在线时的统一错误文案（UI 直接展示）。 */
    private static final String SYSTEM_MODULE_MISSING_MSG =
            "系统级注入已开启，但未检测到模块活动：请确认模块已在 LSPosed 启用、作用域勾选"
                    + "「系统框架」后重启手机；或关闭「系统级注入」改用 root 注入。"
                    + "可打开「模块状态」查看各进程心跳";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RoutePlayer player = new RoutePlayer();

    private TestProviderInjector injector;
    /**
     * 串行化 appop 的"授权留痕"与"停止回收"：停止的回收线程在 su 执行期间，
     * 用户可能立刻重新开始模拟，必须避免把新会话刚拿到的权限回收掉。
     *
     * <p>用 {@link ReentrantLock} 而不是 {@code synchronized}，是为了让 start 侧能带超时
     * 抢锁（回收侧最坏要连跑多次 su，见 {@link #MOCK_APPOP_LOCK_WAIT_MS}）。锁顺序固定为
     * "先本锁、后 SharedPreferences 内部锁"（授权路径与回收路径一致），回收线程持锁期间
     * <b>不获取本类其他锁</b>，因此不会出现环路等待。
     */
    private static final ReentrantLock MOCK_APPOP_LOCK = new ReentrantLock();
    /**
     * start 侧等待 {@link #MOCK_APPOP_LOCK} 的上限（毫秒）。
     *
     * <p>回收侧依次做"查询 → 写回 default → 复核"最多三次 su，每次最长 20s，su 本身还有
     * 多候选路径 fallback，最坏约两分钟。start 若无限等待，用户会一直停在「准备注入…」。
     * 超时后按"无锁继续"处理：此时 {@link #mockSessionEpoch} 已自增，回收线程在写 appop
     * 之前会复查代次并放弃本次回收；留痕标记保证下一次彻底停止仍会重试回收。
     */
    private static final long MOCK_APPOP_LOCK_WAIT_MS = 1500L;
    /** 每次真正进入 root 注入准备时自增；异步回收据此判断是否已被新会话取代。 */
    private static final java.util.concurrent.atomic.AtomicLong mockSessionEpoch =
            new java.util.concurrent.atomic.AtomicLong();
    /**
     * 会话代次：每次「开始 / 彻底停止」在主线程自增。
     *
     * <p>后台准备线程把结果 post 回主线程时必须带回自己的代次，主线程据此判断
     * "这个准备结果还属不属于当前会话"。{@link #mockSessionEpoch} 只覆盖 root 注入
     * 路径（system_inject 模式不增它），单靠 mode 判定又分不清"停止后重新开始"，
     * 所以用这个只在主线程读写的代次做权威判定。
     */
    private int sessionSeq;
    /** 停止时触发一次真实定位上报（用于覆盖 passive 的系统缓存），每个 Service 实例一次。 */
    private boolean passiveRefreshStarted;
    /**
     * 本会话开始时定格的 {@code system_inject} 模式：<b>这一份是本会话的判定依据</b>。
     *
     * <p>会话已开始的接管 / 授权不会因为用户在运行中改开关而改变，停止时必须按同一份
     * 判定收尾。旧实现停止时重读最新配置，用户在本次会话运行期间打开 system_inject，
     * 停止路径就会跳过 appop 回收，{@code mock_appop_granted_by_app} 留痕永远清不掉、
     * appop 永久 allow。只在主线程的 start / stop 里读写。
     */
    private volatile boolean sessionSystemInject;
    /** 系统级注入下"模块不在线"只提示一次，避免 10Hz 刷屏。 */
    private boolean systemModuleWarned;
    /**
     * 本会话是否已经提示过"部分接管"（哪些 Provider 没接管成功）。
     *
     * <p>每会话最多一次：这条提示只说明"部分 App 可能仍读真实定位"，不改变会话状态，
     * 也不能跟着每次 notifyChanged / 每帧注入重复出现。在 startRoute/startSpot 重置。
     */
    private boolean partialTakeoverNotified;
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

    /**
     * 准备结果：把 injector 从后台准备线程**通过返回值**传回主线程。
     *
     * <p>旧实现让后台线程直接读写 {@code injector} 字段：主线程 stop 把它置 null 之后，
     * 后台线程的 {@code injector.getFailedProviders()} 就会 NPE；而且停止之后到达的结果
     * 仍会被当成当前会话继续 {@code prepared=true / startTicker() / showOverlay()}，
     * 出现"已停止却弹悬浮窗"。现在字段只在主线程写，跨线程只传这个不可变对象。
     */
    private static final class PrepareResult {
        final boolean ok;
        /** 仅 root 注入路径非 null；system_inject 模式恒为 null（由模块负责注入）。 */
        final TestProviderInjector injector;
        /**
         * 本次准备失败的原因（ok=false 时非 null）：随结果带回，不靠共享的
         * {@code session.error} 传话——那个字段可能残留上一次会话的失败文案。
         */
        final String error;
        /** 成功后仍需一次性告知用户的提示（例如部分 Provider 没接管成功），null=无需提示。 */
        final String notice;

        PrepareResult(boolean ok, TestProviderInjector injector) {
            this(ok, injector, null, null);
        }

        PrepareResult(boolean ok, TestProviderInjector injector, String error, String notice) {
            this.ok = ok;
            this.injector = injector;
            this.error = error;
            this.notice = notice;
        }

        /** 失败：把原因写进会话状态，并原样带回给主线程（两者永远是同一份文案）。 */
        static PrepareResult failed(Session session, String error) {
            session.error = error;
            return new PrepareResult(false, null, error, null);
        }

        /** 失败但不需要写会话状态（会话已被停止/取代，结果会被丢弃）。 */
        static PrepareResult aborted() {
            return new PrepareResult(false, null, null, null);
        }
    }

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
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 启动

    private void startRoute(Intent intent) {
        Session session = Session.INSTANCE;
        // 新会话代次：仍在后台准备的旧会话 post 回主线程时会发现自己已经过期
        final int seq = ++sessionSeq;
        // 定格本会话的注入方式（这一份是本会话的判定依据）：运行中用户改 system_inject
        // 不会改变本会话已做的接管 / 授权，停止时必须按同一份判定收尾。
        final boolean systemInject = RouteStore.loadSystemInject(this);
        sessionSystemInject = systemInject;
        partialTakeoverNotified = false;
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
            // 结果只经局部变量回主线程：不再让后台线程读写 injector 字段（P1-B）
            PrepareResult result = ensureMockPermissionAndPrepare(providers, systemInject);
            handler.post(() -> {
                // 先判会话代次：停止（或已重新开始）之后到达的结果一律丢弃，
                // 旧会话的失败也不能再 stopInternal 掉新会话。
                if (isStalePrepare(seq)) {
                    discardStalePrepare(result);
                    return;
                }
                if (!result.ok) {
                    // 失败原因只认本次 preparation 带回的那一份：绝不复用 session.error，
                    // 那个字段可能还留着上一次会话/上一次失败的文案，会把用户引向错的原因。
                    stopInternal(result.error != null ? result.error
                            : "定位准备被中止，已停止");
                    return;
                }
                injector = result.injector;
                Session.INSTANCE.prepared = true;
                Session.INSTANCE.notifyChanged();
                startTicker();
                updateNotification();
                // 部分接管：明确告诉用户哪些 Provider 没成功（每会话最多一次，只提示不打断）
                notifyPartialTakeover(result.notice);
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
        // 新会话代次：仍在后台准备的旧会话 post 回主线程时会发现自己已经过期
        final int seq = ++sessionSeq;
        // 定格本会话的注入方式（这一份是本会话的判定依据），理由见 startRoute
        final boolean systemInject = RouteStore.loadSystemInject(this);
        sessionSystemInject = systemInject;
        partialTakeoverNotified = false;
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
            // 结果只经局部变量回主线程：不再让后台线程读写 injector 字段（P1-B）
            PrepareResult result = ensureMockPermissionAndPrepare(providers, systemInject);
            handler.post(() -> {
                // 先判会话代次：停止（或已重新开始）之后到达的结果一律丢弃，
                // 旧会话的失败也不能再 stopInternal 掉新会话。
                if (isStalePrepare(seq)) {
                    discardStalePrepare(result);
                    return;
                }
                if (!result.ok) {
                    // 失败原因只认本次 preparation 带回的那一份：绝不复用 session.error，
                    // 那个字段可能还留着上一次会话/上一次失败的文案，会把用户引向错的原因。
                    stopInternal(result.error != null ? result.error
                            : "定位准备被中止，已停止");
                    return;
                }
                injector = result.injector;
                Session.INSTANCE.prepared = true;
                Session.INSTANCE.notifyChanged();
                startTicker();
                updateNotification();
                // 部分接管：明确告诉用户哪些 Provider 没成功（每会话最多一次，只提示不打断）
                notifyPartialTakeover(result.notice);
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

    /**
     * 拿 root → 授权 appops → 接管测试 Provider。
     *
     * @param systemInject 会话开始时定格的注入方式（见 {@link #sessionSystemInject}）：
     *                     本次准备全程按它走，不重读开关，避免准备途中改开关导致
     *                     "按 root 路径接管、停止时却按系统级模式收尾"的错配。
     */
    private PrepareResult ensureMockPermissionAndPrepare(List<String> providers,
                                                         boolean systemInject) {
        Session session = Session.INSTANCE;
        String pkg = getPackageName();

        // 系统级注入：定位由模块在 system_server 改写，本进程不接管测试 Provider，
        // 也就不需要 appops / 测试 Provider（但模块本身依赖 root + LSPosed 已安装）
        if (systemInject) {
            // 不改既有判定（模块存活由 SystemInject 的读取痕迹证明），只留诊断线索：
            // 定位总开关关着时目标 App 的定位请求可能根本到不了模块，日志会表现为
            // "模块无活动"，排查时不要误以为 LSPosed 作用域没勾。
            if (!LocationSwitch.isSystemLocationEnabled(this)) {
                Log.w(TAG, "system inject: system location switch is off; "
                        + "module reads may never arrive");
            }
            return prepareSystemInject(session);
        }

        if (!RootShell.isRooted()) {
            session.rootOk = false;
            return PrepareResult.failed(session,
                    "未获得 root 权限，请确认设备已 root 并在 root 管理器中授权本应用");
        }
        session.rootOk = true;

        // 前置检查（在任何 test provider 接管 / appop 修改之前）：定位总开关关闭时
        // addTestProvider 仍可能返回成功（dumpsys 里已有 "gps provider [mock]"），但
        // user0 enabled=false、last mock location=null，isProviderEnabled("gps") 恒为 false。
        // 真实原因只是"没开定位"，不是 appop 被拒——这里直接给出可操作提示并停止，
        // 不再走接管流程，避免用户看到误导性的"接管被拒绝"。
        if (!LocationSwitch.isSystemLocationEnabled(this)) {
            Log.w(TAG, "system location switch is off, abort before test provider takeover");
            return PrepareResult.failed(session, LocationSwitch.DISABLED_MSG);
        }

        // 新会话：让上一次停止尚未完成的 appop 回收让位（见 revokeMockAppopIfGranted）
        final long myEpoch = mockSessionEpoch.incrementAndGet();

        // start 不能无限期等 appop 锁：回收侧最坏要连跑多次 su（单次 20s + 多候选路径
        // fallback）。这里带短超时抢锁，超时就跳过等待继续授权——代次已在上面自增，
        // 回收线程在写 appop 前会复查代次并放弃本次回收；留痕标记也保证下一次停止重试。
        final boolean locked = tryLockMockAppop();
        if (!locked) {
            Log.w(TAG, "appop revoke still holds the lock after " + MOCK_APPOP_LOCK_WAIT_MS
                    + "ms; continue without it (revoke side re-checks the session epoch)");
        }
        try {
            RootShell.Result current = RootShell.getMockAppop(pkg);
            if (!RootShell.isMockAppopAllowed(current)) {
                for (int attempt = 1; ; attempt++) {
                    RootShell.Result grant = RootShell.grantMockAppop(pkg);
                    if (!grant.ok()) {
                        return PrepareResult.failed(session,
                                "授予模拟位置权限失败：" + grant.combined());
                    }
                    // 留痕：这条 allow 是本应用改的，停止时只回收它；用户/系统本来就允许的
                    // （走不到这个分支）没有标记，绝不能在停止时去动。
                    RouteStore.saveMockAppopGrantedByApp(this);
                    current = RootShell.getMockAppop(pkg);
                    if (RootShell.isMockAppopAllowed(current)) {
                        break;
                    }
                    // 无锁路径上回收线程可能刚好把 appop 写回 default（它已通过代次检查、
                    // 正在那条 su 里）：再试一次授权。有锁路径不可能并发，直接按失败处理。
                    if (locked || attempt >= 2) {
                        return PrepareResult.failed(session,
                                "模拟位置权限未生效（appops：" + current.combined() + "）");
                    }
                    Log.w(TAG, "mock_location appop lost right after grant, retry once: "
                            + current.combined());
                }
            }
        } finally {
            if (locked) {
                MOCK_APPOP_LOCK.unlock();
            }
        }

        // 拿 root / 授权 appop 期间用户可能已经点了「彻底停止」：这时不要再接管
        // 测试 Provider，直接返回失败让上层 stopInternal 回收刚授出的 appop。
        if (Session.INSTANCE.mode == Session.Mode.IDLE && mockSessionEpoch.get() == myEpoch) {
            Log.i(TAG, "session stopped during prepare, abort provider takeover");
            // 会话已被停止/取代：结果会被 isStalePrepare 丢弃，不要再写 session.error，
            // 否则这条"放弃接管"的文案会粘到下一次会话的界面上。
            return PrepareResult.aborted();
        }

        // 用局部变量持有 injector：后台线程不碰 injector 字段（主线程可能已在 stop 里
        // 把它置 null），也就不会有 "injector.getFailedProviders()" 的 NPE。
        TestProviderInjector newInjector = new TestProviderInjector(this, providers);
        try {
            newInjector.prepare();
        } catch (SecurityException e) {
            // 只有"所有 Provider 都失败"才会抛到这里；describe() 已列出逐个原因。
            // prepare() 失败前可能已经注册了部分 test provider（例如 gps 已 add、启用被拒），
            // 必须在这里 release，否则它们会作为常驻 mock provider 留到设备重启。
            String detail = newInjector.describe();
            releaseQuietly(newInjector);
            if (!LocationSwitch.isSystemLocationEnabled(this)) {
                Log.w(TAG, "take over aborted: system location switch was disabled | "
                        + detail, e);
                return PrepareResult.failed(session, LocationSwitch.DISABLED_MSG);
            }
            Log.e(TAG, "take over location provider rejected: " + detail, e);
            return PrepareResult.failed(session, "接管定位 Provider 被拒绝：" + detail);
        } catch (RuntimeException e) {
            String detail = newInjector.describe();
            releaseQuietly(newInjector);
            Log.e(TAG, "take over location provider failed: " + detail + " | " + e, e);
            return PrepareResult.failed(session,
                    "接管定位 Provider 失败：" + detail + "（" + e + "）");
        }
        // 只要 ≥1 个 Provider 接管成功就继续：Android 12+ 上 network 常由系统融合定位
        // 持有、第三方接管被拒是常态，gps 往往仍可用，不能因此停掉整场模拟。
        String partialNotice = null;
        if (!newInjector.getFailedProviders().isEmpty()) {
            Log.w(TAG, "test provider partially prepared: " + newInjector.describe());
            // 仅 logcat 用户看不到：把"哪些 Provider 没接管成功 + 会导致部分 App 不生效"
            // 带回主线程，由 notifyPartialTakeover 每会话提示一次（不停止会话）。
            // 只放 Provider 名（异常细节已在上面进了 logcat），避免 toast 过长被截断。
            partialNotice = "部分接管：" + String.join("、", newInjector.getFailedProviders())
                    + " 未接管成功\n只读这些 Provider 的 App 可能仍拿到真实定位";
        }
        return new PrepareResult(true, newInjector, null, partialNotice);
    }

    /**
     * 部分接管的一次性提示（每会话最多一次，只提示、不打断会话、不刷屏）。
     *
     * <p>接管是"尽力而为"：只接管到 gps 时会话照常运行，但只读 network/fused 的
     * App 仍会拿到真实定位——过去这条信息只写在 logcat（{@code describe()}）里。
     */
    private void notifyPartialTakeover(String notice) {
        if (notice == null || partialTakeoverNotified) {
            return;
        }
        partialTakeoverNotified = true;
        Log.w(TAG, "partial takeover told to user: " + notice);
        toast(notice);
    }

    /**
     * 带超时抢 {@link #MOCK_APPOP_LOCK}。
     *
     * @return true=已持有（调用方必须在 finally 里 unlock）；false=超时（<b>没有</b>持有，
     *         不得 unlock）
     */
    private static boolean tryLockMockAppop() {
        try {
            return MOCK_APPOP_LOCK.tryLock(MOCK_APPOP_LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 系统级注入的准备：必须证明 system_server 里的模块**真的在接管**才允许返回成功。
     *
     * <p>旧实现直接 {@code return true}，于是 LSPosed 作用域没勾「系统框架」时，
     * 界面显示运行中、里程在涨，但 {@link #injectFix} 里 {@code injector == null}
     * 静默返回，一个坐标都没注入。这里改为：有真实读取痕迹（新鲜度
     * {@link #SYSTEM_MODULE_FRESH_MS}）才通过；否则主动探测一轮，仍无痕迹就按
     * "与 root 失败相同"的失败路径返回 false（上层 {@link #stopInternal} 会提示并停止）。
     *
     * <p>本方法在后台准备线程执行，<b>不得</b>读写 {@code injector} 字段：主线程可能同时
     * stop 并把它置 null。上一个会话的 injector 已由 startRoute/startSpot 的
     * {@link #resetForRestart()} 在主线程释放。
     *
     * @return ok=模块确实在接管；失败时 {@code session.error} 已写明原因
     */
    private PrepareResult prepareSystemInject(Session session) {
        session.rootOk = RootShell.isRooted();
        if (isSystemModuleOnline()) {
            Log.i(TAG, "system inject ready: " + describeSystemModuleEvidence());
            return new PrepareResult(true, null);
        }
        // 没有既有痕迹：主动触发一次定位读取，逼 system_server 里的模块回读快照
        probeSystemModule();
        final long deadline = SystemClock.elapsedRealtime() + SYSTEM_MODULE_PROBE_WAIT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isSystemModuleOnline()) {
                Log.i(TAG, "system inject ready after probe");
                return new PrepareResult(true, null);
            }
            SystemClock.sleep(SYSTEM_MODULE_PROBE_POLL_MS);
        }
        // 仍无痕迹：绝不再"假成功"，走与 root 失败相同的失败路径
        Log.w(TAG, "system inject enabled but no module evidence: no snapshot read within "
                + SYSTEM_MODULE_FRESH_MS + "ms (probe waited " + SYSTEM_MODULE_PROBE_WAIT_MS
                + "ms) and no system heartbeat within " + SYSTEM_MODULE_HEARTBEAT_MS + "ms");
        return PrepareResult.failed(session, SYSTEM_MODULE_MISSING_MSG);
    }

    /**
     * system_server 里的模块是否还活着。两条证据取**或**：
     *
     * <ol>
     *   <li><b>快照读取新鲜</b>：模块的定位/GNSS hook 一触发就会 query
     *       {@code content://com.vloc.motion.env/location|gnss}（{@code hidden/SystemHooks.java}），
     *       该 query 由 system_server（uid 1000）发起，{@link EnvProvider} 记录时间戳。
     *       窗口 {@link #SYSTEM_MODULE_FRESH_MS}（5 秒）——启动阶段的严格证据。</li>
     *   <li><b>系统框架心跳</b>：模块注入 {@code android} 进程后 insert 了一条
     *       {@code kind=system} 的心跳（{@link HookHeartbeatStore}）。窗口
     *       {@link #SYSTEM_MODULE_HEARTBEAT_MS}（24 小时），SDK 一致优先。</li>
     * </ol>
     *
     * <p>为什么要加第 2 条：目标 App 可能暂时没请求定位（刚开机、被切后台、用户还没打开），
     * 此时第 1 条自然没有新痕迹，旧判定会把"模块明明在"误报成"未检测到模块活动"。
     * 心跳是模块自己写的，不受 LSPosed 管理器包名被隐藏模块屏蔽的影响。
     *
     * <p>不使用 {@link StepSession#moduleHeartbeatAt}（{@code call("module_heartbeat")}）：
     * 那个心跳来自**目标 App 进程**里的模块，只能证明模块在某处注入成功，不能证明系统框架
     * 作用域生效。{@link HookHeartbeatStore} 区分了 {@code kind=system}/{@code app}，才敢用。
     *
     * <p>也不使用 {@link VlocStateFile}：它只被 App 单向写给 Zygisk 原生模块
     * （{@code zygisk/src/module.cpp} 用 PROT_READ mmap），模块不回写任何 ack，没有可读信号。
     */
    private boolean isSystemModuleOnline() {
        final long now = System.currentTimeMillis();
        final long seenAt = StepSession.INSTANCE.moduleSystemReadAt;
        if (seenAt > 0L) {
            final long age = now - seenAt;
            if (age >= 0L && age <= SYSTEM_MODULE_FRESH_MS) {
                return true;
            }
        }
        return systemHeartbeatFresh();
    }

    /**
     * 运行期是否还该认为「系统级注入在动」：快照读取新鲜（30 秒）
     * **或** 有 24 小时内的系统框架心跳。
     *
     * <p>心跳不是周期性的，用 30 秒窗口会在注入后 30 秒就报"模块不见了"，
     * 所以这里对心跳仍用 24 小时；真正"作用域没勾、模块根本没进去"的场景
     * 一条心跳都没有，照样会告警。
     */
    private boolean isSystemModuleRunning() {
        final long now = System.currentTimeMillis();
        final long seenAt = StepSession.INSTANCE.moduleSystemReadAt;
        if (seenAt > 0L) {
            final long age = now - seenAt;
            if (age >= 0L && age <= SYSTEM_MODULE_SILENCE_WARN_MS) {
                return true;
            }
        }
        return systemHeartbeatFresh();
    }

    /** 最近 {@link #SYSTEM_MODULE_HEARTBEAT_MS} 内有没有 {@code kind=system} 的心跳。 */
    private boolean systemHeartbeatFresh() {
        return HookHeartbeatStore.freshestSystem(getApplicationContext(),
                SYSTEM_MODULE_HEARTBEAT_MS, Build.VERSION.SDK_INT) != null;
    }

    /** 日志用：说明这次判活靠哪条证据（快照读取 or 系统框架心跳），失败时说明两条都没有。 */
    private String describeSystemModuleEvidence() {
        final long now = System.currentTimeMillis();
        final long seenAt = StepSession.INSTANCE.moduleSystemReadAt;
        final long age = seenAt > 0L ? now - seenAt : -1L;
        if (age >= 0L && age <= SYSTEM_MODULE_FRESH_MS) {
            return "module read our snapshot " + age + "ms ago";
        }
        HookHeartbeatStore.Beat beat = HookHeartbeatStore.freshestSystem(getApplicationContext(),
                SYSTEM_MODULE_HEARTBEAT_MS, Build.VERSION.SDK_INT);
        if (beat != null) {
            return "system heartbeat from " + beat.process + " (" + beat.hooks + " hooks, "
                    + (now - beat.at) + "ms ago)";
        }
        if (age >= 0L) {
            return "no system heartbeat; last snapshot read " + age + "ms ago";
        }
        return "no system heartbeat and no snapshot read yet";
    }

    /**
     * 主动探测：本进程读一次"最后已知位置"。
     *
     * <p>system_server 里的模块挂了 {@code getLastLocation/getCurrentLocation} 的
     * after-hook（{@code hidden/SystemHooks.java:203-219}），hook 里会调用
     * {@code currentLocation()} → query 本应用的 /location 快照，从而留下真实读取痕迹。
     * 权限不足 / ROM 差异导致失败时静默忽略，由调用方继续轮询到超时。
     */
    private void probeSystemModule() {
        try {
            android.location.LocationManager lm =
                    (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) {
                return;
            }
            try {
                if (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                    lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
                }
            } catch (Throwable ignored) {
                // 未授予定位权限 / provider 不可用：换下一个
            }
            try {
                lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
            } catch (Throwable ignored) {
                // 同上
            }
        } catch (Throwable t) {
            Log.w(TAG, "probe system module failed", t);
        }
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
                        final boolean autoStop =
                                RouteStore.loadAutoStopOnFinish(LocationService.this);
                        toast(com.vloc.motion.core.ArrivalBehavior.toastText(autoStop));
                        if (autoStop) {
                            // 只有明确开启时才自动结束；默认保持定位继续上报
                            handler.postDelayed(autoStopRunnable,
                                    com.vloc.motion.core.ArrivalBehavior.AUTO_STOP_DELAY_MS);
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
            // 系统级注入：本进程不写测试 Provider，由 system_server 里的模块改写定位。
            // 但只有"能证明模块在动"时才允许空转，否则界面会显示运行中、里程在涨，
            // 却一个坐标都没注入（v0.40.0 之前的静默缺陷）。
            // 证据：system_server 读过快照（30 秒内）**或** 有 24 小时内的系统框架心跳——
            // 后者覆盖"目标 App 暂时没请求定位"的正常场景，避免误报（见 isSystemModuleRunning）。
            if (isSystemModuleRunning()) {
                if (systemModuleWarned) {
                    systemModuleWarned = false;
                    // 模块恢复读取 / 有心跳：立刻清掉上一次的静默 error，否则一次误报会永久粘在界面上
                    clearSystemModuleError();
                }
                return;
            }
            Session session = Session.INSTANCE;
            // 只在真正"运行中"才判静默：held（含"已定位到起点（待开始）"）、暂停、已到达
            // 时目标 App 本来就可能不请求定位，模块自然没有回读，这时报"请重启手机"是误报。
            final boolean running = session.mode == Session.Mode.ROUTE
                    && !session.paused && !session.held && !session.arrived;
            if (!running || systemModuleWarned) {
                return;
            }
            systemModuleWarned = true;
            Log.e(TAG, "injectFix: system inject mode, but no module evidence ("
                    + describeSystemModuleEvidence() + "); nothing is being injected");
            session.error = SYSTEM_MODULE_MISSING_MSG;
            session.notifyChanged();
            // 用户此刻通常在目标 App 里，MainActivity.onPause 已 removeListener：
            // 只写 session.error 永远看不到，必须直接提示（toast + 通知双保险）。
            toast(SYSTEM_MODULE_MISSING_MSG);
            postSystemModuleAlert();
            return;
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
        // 先让仍在后台准备的会话过期：它之后的 post 会被 isStalePrepare 丢弃并自行释放
        ++sessionSeq;
        stopTicker();
        releaseInjector();
        // 收尾顺序不能反：removeTestProvider 本身也受 mock_location appop 检查，
        // 必须先移除测试 Provider（releaseInjector），再回收 appop。
        // 判定一律用会话开始时的定格值 sessionSystemInject，不要重读开关：
        // 运行中改 system_inject 会改变"当前模式"，但不会改变本会话已做过的接管/授权。
        // appop 回收只看留痕（本应用授予的那条 allow），与本会话模式无关——系统级注入
        // 本身不授 appop，但更早会话留下的留痕同样必须回收，否则就是永久 allow。
        revokeMockAppopIfGranted();
        if (!sessionSystemInject) {
            // passive 清理只针对 root 注入会话（system_inject 不接管测试 Provider）
            refreshRealFixForPassive();
        }
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
        cancelSystemModuleAlert();
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

    /**
     * 后台准备结果回到主线程时，是否已经不属于当前会话。
     *
     * <p>两种情况都算过期：①用户在这期间点了「彻底停止」——{@link #sessionSeq} 已自增、
     * {@code mode} 已是 IDLE、{@code injector} 已被 {@link #releaseInjector()} 置 null；
     * ②停止后又立刻重新开始——{@link #sessionSeq} 再次自增，旧结果同样作废。
     * 过期时必须丢弃结果，绝不能再执行 {@code prepared=true / startTicker() / showOverlay()}。
     */
    private boolean isStalePrepare(int seq) {
        return seq != sessionSeq || Session.INSTANCE.mode == Session.Mode.IDLE;
    }

    /** 丢弃一份已过期的准备结果：释放它自带的 injector，避免留下常驻 mock provider。 */
    private void discardStalePrepare(PrepareResult result) {
        Log.i(TAG, "discard prepare result: session is no longer current");
        releaseQuietly(result == null ? null : result.injector);
    }

    /** 释放一个还没交给 {@code injector} 字段的实例（失败只记日志，不打断收尾流程）。 */
    private void releaseQuietly(TestProviderInjector target) {
        if (target == null) {
            return;
        }
        try {
            target.release();
        } catch (RuntimeException e) {
            Log.w(TAG, "release discarded injector failed", e);
        }
    }

    /**
     * 停止时回收**本应用授出**的 {@code android:mock_location} appop。
     *
     * <p>只认 {@link RouteStore#loadMockAppopGrantedByApp} 的留痕（授权成功时写入）：
     * 用户自己在开发者选项里选的"模拟位置应用"、或系统/上次运行留下的 allow 没有这个
     * 标记，停止时**绝不触碰**，避免越权修改用户设置。
     *
     * <p>留痕是唯一依据，<b>与当前 system_inject 开关无关</b>：这条 allow 是本应用改的，
     * 不管这次会话跑的是哪种注入模式，都要恢复成 default；否则运行中打开 system_inject
     * 就会让这条记录泄漏成永久 allow（P2）。系统级注入本身不授 appop，走这里时通常
     * 没有留痕、直接返回，不会触碰系统面。
     *
     * <p>用 {@code appops set ... default} 而不是 {@code deny}：default 只是把这条 op
     * 恢复成"未显式设置"（mock_location 的默认态就是不允许），不额外写一条 denial；
     * 而 deny/ignore 本身就是一条用户可见的 AppOps 记录，反而会覆盖用户之后的手动授权。
     *
     * <p>root 调用可能阻塞 20s，必须离开主线程；失败只记日志并保留标记，下次停止可重试。
     * 校验到 appop 仍为 allow 时同样保留标记（可能只是 su 超时），不误报成功。
     *
     * <p>与授权路径共用 {@link #MOCK_APPOP_LOCK}，并用 {@link #mockSessionEpoch} 判断
     * 停止后是否已开始新会话：用户点了彻底停止又立刻点开始，回收线程绝不能把新会话
     * 刚拿到的 appop 收走。start 侧已改为"带超时抢锁"（见 {@link #tryLockMockAppop}），
     * 可能不等本方法就继续授权，所以本方法在<b>每次写 appop / 清留痕之前</b>都要复查
     * 代次，发现新会话立即放弃，不能假定自己独占。
     */
    private void revokeMockAppopIfGranted() {
        if (RouteStore.loadMockAppopGrantedByApp(this) <= 0L) {
            return;   // 不是本应用授予的：用户/系统的设置，原样保留
        }
        final long epoch = mockSessionEpoch.get();
        final String pkg = getPackageName();
        new Thread(() -> {
            MOCK_APPOP_LOCK.lock();
            try {
                revokeMockAppopLocked(epoch, pkg);
            } catch (Throwable t) {
                Log.w(TAG, "revoke mock_location appop failed", t);
            } finally {
                MOCK_APPOP_LOCK.unlock();
            }
        }, "vloc-appop-revoke").start();
    }

    /**
     * {@link #revokeMockAppopIfGranted} 的持锁主体。
     *
     * <p>成功才清留痕；su 失败、复核仍为 allow、或期间开始了新会话都保留留痕，
     * 由下一次彻底停止重试，绝不把"没确认回收"当成已回收。
     *
     * <p>持锁期间只做 su 与 SharedPreferences 读写（锁顺序与授权路径一致：先
     * {@link #MOCK_APPOP_LOCK}、后 prefs 内部锁），不获取本类其他锁，不构成环路。
     */
    private void revokeMockAppopLocked(long epoch, String pkg) {
        if (isAppopRevokeSuperseded(epoch)) {
            // 已经重新开始模拟：权限留给新会话，由新会话停止时回收
            Log.i(TAG, "skip appop revoke: a newer session has started");
            return;
        }
        if (RouteStore.loadMockAppopGrantedByApp(this) <= 0L) {
            return;   // 另一个并发停止已经回收
        }
        RootShell.Result current = RootShell.getMockAppop(pkg);
        if (isAppopRevokeSuperseded(epoch)) {
            // 查询期间新会话已经开始（它可能刚授出 allow）：不要再写 appop
            Log.i(TAG, "skip appop revoke: a newer session started during query");
            return;
        }
        if (!current.ok()) {
            // su / appops 不可用：无法判断当前状态，保留留痕下次再试，
            // 不能凭"查不到"就当作已回收。
            Log.w(TAG, "query mock_location appop failed, keep grant mark: " + current);
            return;
        }
        if (!RootShell.isMockAppopAllowed(current)) {
            // 用户/系统已经改回不允许：没有要回收的 allow，只清自己的留痕
            RouteStore.clearMockAppopGrantedByApp(this);
            Log.i(TAG, "mock_location appop already not allowed, grant mark cleared");
            return;
        }
        RootShell.Result revoke = RootShell.revokeMockAppop(pkg);
        if (!revoke.ok()) {
            Log.w(TAG, "revoke mock_location appop failed: " + revoke);
            return;   // 保留标记，下次彻底停止再试
        }
        RootShell.Result after = RootShell.getMockAppop(pkg);
        if (isAppopRevokeSuperseded(epoch)) {
            // 复核期间新会话可能刚好重新授权：留痕不清，交给下一次停止核对
            Log.i(TAG, "keep grant mark: a newer session started during revoke");
            return;
        }
        if (!after.ok() || RootShell.isMockAppopAllowed(after)) {
            Log.w(TAG, "mock_location appop still allowed after revoke: " + after);
            return;   // 保留标记，下次彻底停止再试
        }
        RouteStore.clearMockAppopGrantedByApp(this);
        Log.i(TAG, "revoked mock_location appop granted by this app");
    }

    /** 回收线程：本次停止之后是否已经开始了新会话（新会话可能正在/已经重新授权 appop）。 */
    private static boolean isAppopRevokeSuperseded(long epoch) {
        return mockSessionEpoch.get() != epoch;
    }

    /**
     * 停止后的被动 Provider 尽力清理。
     *
     * <p>passive 没有被本应用接管（{@code addTestProvider("passive")} 在 Android 8.0+
     * 直接抛 {@code IllegalArgumentException}，见 docs/root-injection.md:324），它保留的
     * 最后一条 mock 镜像来自 system_server 的系统级缓存，App 没有 API 能删除/清空。
     * 唯一能做的：测试 Provider 全部移除后请求一次**真实**定位，让真实 provider 再上报
     * 一次，覆盖 passive 的旧值；8 秒超时、只在主线程 Handler 上注册一次，不新增线程。
     * 室内/无信号时可能始终拿不到真实 fix，passive 会一直保留旧值——这是系统层限制，
     * 不是本应用能强制的（不要试图 add/removeTestProvider("passive") 假清理）。
     */
    private void refreshRealFixForPassive() {
        if (passiveRefreshStarted) {
            return;
        }
        passiveRefreshStarted = true;
        if (!RealLocationProvider.hasPermission(this)) {
            Log.i(TAG, "passive best-effort skipped: no location permission");
            return;
        }
        RealLocationProvider.requestFresh(this, new RealLocationProvider.Callback() {
            @Override
            public void onLocation(Location location) {
                Log.i(TAG, "passive best-effort: " + location.getProvider()
                        + " fix received (mock=" + location.isFromMockProvider() + ")");
            }

            @Override
            public void onError(String message) {
                Log.i(TAG, "passive best-effort: no real fix (" + message
                        + "); passive may keep the last mock location until next real fix");
            }
        });
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
            final String phase;
            if (session.arrived) {
                phase = com.vloc.motion.core.ArrivalBehavior.notificationLabel(
                        RouteStore.loadAutoStopOnFinish(this));
            } else if (session.paused) {
                phase = "已暂停";
            } else {
                phase = "运行中";
            }
            text = String.format(java.util.Locale.US, "%s%s · %.0f km/h · %s / %s",
                    session.goalMode ? "配速 " : "",
                    phase,
                    session.speedKmh,
                    formatDistance(session.traveledMeters),
                    formatDistance(session.totalMeters));
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    /**
     * "系统级注入失效"的可见告警。
     *
     * <p>用户在目标 App 里时 {@code MainActivity.onPause} 已经
     * {@code removeListener}，{@code onResume} 又不会补显示，所以只写
     * {@code session.error} 是看不到的。这里绕过 Activity 直接发一条通知，
     * 走已有通道（低优先级）、独立 id，不会顶掉前台服务通知。
     */
    private void postSystemModuleAlert() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification alert = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("虚拟运动 · 系统级注入未生效")
                .setContentText(SYSTEM_MODULE_MISSING_MSG)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(SYSTEM_MODULE_MISSING_MSG))
                .setContentIntent(openPending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        nm.notify(ALERT_NOTIFICATION_ID, alert);
    }

    /** 撤掉系统级注入告警通知（模块恢复读取 / 会话结束时）。 */
    private void cancelSystemModuleAlert() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(ALERT_NOTIFICATION_ID);
        }
    }

    /** 模块恢复读取：清掉"系统级注入失效"的 error，避免一次误报永久粘住。 */
    private void clearSystemModuleError() {
        Session session = Session.INSTANCE;
        if (SYSTEM_MODULE_MISSING_MSG.equals(session.error)) {
            session.error = null;
            session.notifyChanged();
        }
        cancelSystemModuleAlert();
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
            line1 = com.vloc.motion.core.ArrivalBehavior.overlayLabel(
                    RouteStore.loadAutoStopOnFinish(this));
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
    }

    /** 悬浮窗：切换到下一个虚拟摄像头视频。 */
    

    /** 悬浮窗：播放 / 暂停虚拟摄像头视频。 */
    

    /** 悬浮窗：开 / 关虚拟摄像头。 */
    

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
