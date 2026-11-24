package com.vloc.motion.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
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
import com.vloc.motion.core.GaitAttitude;
import com.vloc.motion.core.GnssSky;
import com.vloc.motion.core.GnssTle;
import com.vloc.motion.core.ImuWaveform;
import com.vloc.motion.core.PacePlan;
import com.vloc.motion.core.RealismEngine;
import com.vloc.motion.core.Route;
import com.vloc.motion.core.RoutePlayer;
import com.vloc.motion.core.RouteStore;
import com.vloc.motion.core.StepEngine;
import com.vloc.motion.core.TleUpdater;
import com.vloc.motion.core.TraceCleaner;
import com.vloc.motion.core.UsageLog;
import com.vloc.motion.core.VLog;
import com.vloc.motion.location.GnssCollector;
import com.vloc.motion.location.LocationSwitch;
import com.vloc.motion.location.RealLocationProvider;
import com.vloc.motion.location.RootShell;
import com.vloc.motion.location.TestProviderInjector;
import com.vloc.motion.ui.FloatingWindow;
import com.vloc.motion.ui.MainActivity;

import org.json.JSONArray;
import org.json.JSONObject;

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
    /** 主渠道被用户关闭时使用（新 id 不会继承"已关闭"状态）。 */
    private static final String CHANNEL_ID_BACKUP = "vloc_playback_v2";
    /**
     * 前台通知的固定文案（<b>中性化</b>）。
     *
     * <p>旧文案会带出坐标（{@code "%.6f, %.6f"}）、路线进度 / 速度
     * （{@code "运行中 · 12 km/h · 300 m / 5.00 km"}）、以及"临时定位 / 路径播放"
     * 这类字眼——通知栏在锁屏、下拉栏、车机投屏上都可能被别人看到，
     * 等于把"正在模拟某个位置"直接摊开。现在只保留应用名 + 一句"服务运行中"，
     * 具体状态只在应用内展示。
     */
    private static final String NOTIFICATION_TITLE = "虚拟运动";
    /** 通知内容：同样中性，不随会话状态变化（见 {@link #NOTIFICATION_TITLE}）。 */
    private static final String NOTIFICATION_TEXT = "服务运行中";
    private static final int NOTIFICATION_ID = 1001;
    /** 系统级注入失效告警用独立 id：复用前台通知 id 会把常驻通知顶掉。 */
    private static final int ALERT_NOTIFICATION_ID = 1002;
    private static final long TICK_MS = 100L;
    /**
     * 悬浮窗空闲多久后自动隐藏（毫秒）。
     *
     * <p>会话停在「待开始 / 已暂停」超过这么久就把窗口从屏幕上摘掉
     * （见 {@link #updateOverlayIdle}），恢复移动时自动重新显示。60 秒：足够用户看到
     * "已定位到起点 / 已暂停"的提示，又不至于让窗口在人离开后一直挂着。
     */
    private static final long OVERLAY_IDLE_HIDE_MS = 60_000L;
    /**
     * 「GNSS 完全模拟」的卫星表刷新间隔：卫星仰角/方位是慢变量（分钟级才有可感变化），
     * 1 秒足够跟上坐标移动，又避免每帧（100ms）几百次浮点轨道递推。
     */
    private static final long GNSS_SYNTHETIC_INTERVAL_MS = 1_000L;

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
     * 当前进程里活着的服务实例（{@code onDestroy} 置空）。
     *
     * <p>只给「星表更新成功后立刻换用新表」用：星表由 {@link TleUpdater} 写在文件里，
     * 但已经加载进 {@code RealismEngine} 的是服务实例里的那一份，必须让活着的服务重读一次。
     * 服务没在跑时不做事（下次 {@code onCreate} 自然读到新文件）。
     */
    private static volatile LocationService activeInstance;
    /**
     * 星表加载快照（来源 + 过期天数）：供设置界面只读展示与日志排查。
     *
     * <p>真正的加载逻辑在 {@link TleUpdater#load}（本地已更新文件优先、内置资产兜底），
     * 这里只记"最近一次加载"的结果，免得 UI 为了展示一行字去动引擎。
     */
    private static volatile String tleSource = TleUpdater.SOURCE_NONE;
    private static volatile int tleAgeDays = -1;
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
    // ---------------------------------------------------------------- 使用记录（UsageLog）
    /**
     * 本会话的开始时刻（墙钟毫秒；0 = 没有待结算的会话）。
     *
     * <p>它同时是<b>去重标志</b>：{@link #finishUsageSession} 结算完立刻置 0，
     * 所以 {@link #stopInternal} 被重复调用（ACTION_STOP + 准备失败 + onDestroy 兜底）
     * 也只会产生一条记录。
     */
    private long usageStartedAtMs;
    /** 本会话的模式（{@code UsageLog.MODE_ROUTE} / {@code UsageLog.MODE_SPOT}）。 */
    private String usageMode;
    /** 本会话的路径点数（临时定位为 0）。 */
    private int usagePoints;
    /**
     * 已经写入、等待 {@code TraceCleaner} 回填清理结果的记录 id（0 = 没有待回填的记录）。
     *
     * <p>清理是异步的：停止时先记一条 {@code cleanup=null} 的记录，拿到 id 后由
     * {@code startTraceCleanup()} 的完成回调按 id 回填，绝不阻塞停止流程。
     */
    private long usagePendingId;
    /**
     * 退出兜底清理是否已经发起（{@link #onTaskRemoved} 与 {@link #onDestroy} 可能连着来）。
     *
     * <p>只在主线程读写；新会话开始时复位（见 {@link #startRoute} / {@link #startSpot}）。
     */
    private boolean exitCleanupRequested;
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
    /**
     * 本会话是否<b>强制</b>「GNSS 完全模拟」（设置项 {@code gnss_synthetic}，会话开始时定格）。
     *
     * <p>false = 自动：真实采集有卫星就用真实的，采不到（室内 / 模拟器）才回退合成。
     * 会话开始时定格，与注入方式 / 拟真开关的处理一致（运行中改设置只对下次会话生效）。
     */
    private boolean gnssSyntheticForced;
    /** 本会话真实采集到的原始 JSON（null = 没采到；自动模式据此决定是否回退合成）。 */
    private volatile String gnssCollectedJson;
    /** 采集流程是否已经有结论（回调必定触发；没结论前不抢着发合成数据，避免真实/合成来回跳）。 */
    private volatile boolean gnssCollectResolved;
    /** 上次发布合成卫星表的时刻（单调时钟），1Hz 节流。 */
    private long lastSyntheticGnssMs;
    /** 上次写进日志的卫星来源（synthetic / collected），来源变化才再打一条。 */
    private String gnssSourceLogged;
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
     * 悬浮窗"空闲自动隐藏"的计时起点（{@link SystemClock#uptimeMillis()}；0 = 没在计时）。
     *
     * <p>会话停在「待开始 / 已暂停」时不必一直把窗口挂在屏幕上：它会把"正在跑某个路线"
     * 直接暴露给同一块屏幕前的人。超过 {@link #OVERLAY_IDLE_HIDE_MS} 就隐藏，
     * 恢复运行（{@link #resumeMovement()}）时再显示。计时复用现有 ticker，
     * <b>不新增线程</b>；相关字段只在主线程读写。
     */
    private long overlayIdleSinceMs;
    /** 悬浮窗是否因为空闲被自动隐藏（恢复运行时据此重新显示）。 */
    private boolean overlayIdleHidden;

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
        // 若服务是被 startForegroundService 拉起的，系统要求尽快 startForeground；
        // 这里先挂一条最朴素的常驻通知，避免"未及时前台化"与"通知非法"两类问题。
        ensureNotificationChannel();
        startForegroundSafe(buildNotification());
        activeInstance = this;
        loadGnssCatalog();
        VlocStateFile.init(this);
    }

    /**
     * 载入 GNSS 星表：<b>应用内已更新的 {@code files/gnss_tle.txt} 优先，内置
     * {@code assets/gnss_tle.txt} 兜底</b>（解析优先级见 {@link TleUpdater#load}）。
     *
     * <p>来源与 {@code ageDays}（{@link GnssTle#newestEpochMs} 推出的过期天数）记录到静态字段，
     * 供设置界面展示与日志排查；超过 {@link TleUpdater#STALE_DAYS} 天打告警。
     *
     * <p>失败不影响主流程：没有星表时不产出 gnss 数据，模块会透传真实卫星状态。
     */
    private void loadGnssCatalog() {
        final TleUpdater.Catalog catalog = TleUpdater.load(this);
        if (catalog.text == null) {
            resetTleSnapshot();
            VLog.w(TAG, "gnss catalog missing: 不产出卫星数据");
            return;
        }
        realism.setTleData(catalog.text);
        tleSource = catalog.source;
        tleAgeDays = catalog.ageDays;
        if (catalog.ageDays > TleUpdater.STALE_DAYS) {
            VLog.w(TAG, "gnss tle stale: " + catalog.ageDays + " 天未更新（>" + TleUpdater.STALE_DAYS
                    + "），仰角/方位角误差会变大；可在设置里点「检查更新」或运行"
                    + " scripts/update_gnss_tle.py 刷新");
        }
        VLog.i(TAG, "gnss catalog=" + realism.gnssCatalogSize()
                + " source=" + catalog.source
                + " sats=" + catalog.satCount
                + " tleAgeDays=" + catalog.ageDays);
    }

    /** 最近一次加载的星表来源：{@link TleUpdater#SOURCE_UPDATED} / {@link TleUpdater#SOURCE_ASSET} / {@link TleUpdater#SOURCE_NONE}。 */
    public static String gnssTleSource() {
        return tleSource;
    }

    /** 最近一次加载的星表过期天数（-1 = 未知/无星表）。 */
    public static int gnssTleAgeDays() {
        return tleAgeDays;
    }

    /**
     * 星表文件更新成功后重新加载。
     *
     * <p>服务活着就在一次性后台线程里重读（写盘 + 解析约几十 KB，不占主线程）；
     * 服务没在跑（还没开始模拟）则什么都不做——下次 {@code onCreate} 读到的就是新文件。
     */
    public static void reloadTleCatalog() {
        final LocationService service = activeInstance;
        if (service == null) {
            // 服务没在跑：清掉"最近一次加载"快照，让设置界面回落到现场读盘，
            // 否则更新后的文件会被旧的"内置 · 过期 N 天"盖住。
            resetTleSnapshot();
            VLog.i(TAG, "gnss tle reload skipped: service not running（下次启动自动加载）");
            return;
        }
        new Thread(service::loadGnssCatalog, "vloc-tle-reload").start();
    }

    /** 清空星表加载快照（= 没有在运行的引擎在用星表）。 */
    private static void resetTleSnapshot() {
        tleSource = TleUpdater.SOURCE_NONE;
        tleAgeDays = -1;
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
                stopInternal(null, StopReason.USER);
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
        // 兜底：Service 被外部销毁（系统回收 / 被别的组件 stopService）而没走 stopInternal 时，
        // 不能把这场会话连同使用记录一起丢掉——按「手动停止」补记一条（正常停止时
        // usageStartedAtMs 已在 stopInternal 里置 0，这里不会重复记）。
        if (usageStartedAtMs > 0L) {
            finishUsageSession(StopReason.USER, null);
        }
        if (activeInstance == this) {
            activeInstance = null;
        }
        stopTicker();
        releaseInjector();
        hideOverlay();
        releaseWakeLock();
        // 空闲期收尾：没有运行中的会话才做（运行中被销毁是异常路径，不能删正在用的快照）
        if (Session.INSTANCE.mode == Session.Mode.IDLE) {
            startExitTraceCleanup("destroy");
        }
        super.onDestroy();
    }

    /**
     * 任务被移除（用户从最近任务列表把应用划掉）。
     *
     * <p>这是"应用退出"最常见的一种：Activity 全没了、进程可能被系统随时回收，但
     * {@link #stopInternal} 不一定走（用户没点停止）。此时按空闲与否分两种：
     * <ul>
     *   <li><b>会话仍在跑</b>（{@code mode != IDLE}）：什么都不动。前台服务和快照都还在用，
     *       清掉 {@code vloc_snapshot.xml} 会让模块回退真实值、删除正在用的星表快照；</li>
     *   <li><b>空闲</b>：补一次 {@link TraceCleaner} 清理（选项与 {@code stopInternal} 完全一致）。
     *       与 {@link #onDestroy} 之间用 {@link #exitCleanupRequested} 去重，只清一次。</li>
     * </ul>
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        startExitTraceCleanup("task_removed");
        super.onTaskRemoved(rootIntent);
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
        // 使用记录：会话从这里开始计时（点数取本次路线点数）。上一场还没结算的会话
        // （"到达终点后直接重新开始"不经过 stopInternal）会在里面先补记一条。
        startUsageSession(UsageLog.MODE_ROUTE, route.size());
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

        startForegroundSafe(buildNotification());
        acquireWakeLock();
        // 维持真实采集优先：先定格本会话的卫星来源判定，再采一次真实卫星（采不到时由 1Hz 节拍回退合成）
        beginGnssSyntheticSession();
        collectGnssSnapshot();
        // 先开快照套接字再准备注入：模块（system_server）在 Android 17 上读不了
        // ContentProvider（SecurityException），只能从抽象套接字拿快照；
        // 绑定失败只记一条 VLog.w，不影响会话（见 SnapshotServer）。
        SnapshotServer.INSTANCE.start(this);
        exitCleanupRequested = false;   // 新会话：允许下次退出时再做一次兜底清理
        // 同一份快照再写进 world-readable prefs（LSPosed New XSharedPreferences）：
        // 抽象套接字在 system_server 侧会被 SELinux 拒（avc: denied { connectto }），
        // prefs 是 Android 11+ 唯一稳定的通道；打不开时它自己降级，不影响下面任何流程。
        SnapshotPrefs.INSTANCE.start(this);

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
                            : "定位准备被中止，已停止", StopReason.FAILED);
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
        // 使用记录：临时定位没有路径点，点数记 0
        startUsageSession(UsageLog.MODE_SPOT, 0);
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

        startForegroundSafe(buildNotification());
        acquireWakeLock();
        // 维持真实采集优先：先定格本会话的卫星来源判定，再采一次真实卫星（采不到时由 1Hz 节拍回退合成）
        beginGnssSyntheticSession();
        collectGnssSnapshot();
        // 临时定位会话同样要发布快照套接字（与 startRoute 一致，start() 幂等）
        SnapshotServer.INSTANCE.start(this);
        exitCleanupRequested = false;   // 新会话：允许下次退出时再做一次兜底清理
        // prefs 通道同上：start() 幂等，打不开时自行降级（模块回退套接字 / Provider）
        SnapshotPrefs.INSTANCE.start(this);

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
                            : "定位准备被中止，已停止", StopReason.FAILED);
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

    /**
     * 新会话的「GNSS 完全模拟」起始状态：定格设置项、清掉上一轮的采集结果与节流。
     *
     * <p>必须在 {@link #collectGnssSnapshot()} 之前调用：上一轮采集到的卫星 JSON 不能当成
     * 本轮的证据（否则在室内第二次开始时会误判"有真实卫星"而不回退合成）。
     */
    private void beginGnssSyntheticSession() {
        try {
            gnssSyntheticForced = RouteStore.loadGnssSynthetic(this);
        } catch (Throwable t) {
            gnssSyntheticForced = false;   // 读设置失败：退回自动模式
        }
        gnssCollectedJson = null;
        gnssCollectResolved = false;
        lastSyntheticGnssMs = 0L;
        gnssSourceLogged = null;
    }

    /** 采集真实卫星分布（用于模块回放 GnssStatus），失败则复用上次快照。 */
    private void collectGnssSnapshot() {
        GnssCollector.collect(this, json -> {
            // 自动模式的判据只有这一份"本次采集结果"：null / 空 / 解析不出卫星 → 回退合成
            gnssCollectedJson = json;
            gnssCollectResolved = true;
            if (gnssSyntheticForced) {
                // 强制合成：采集结果只留作诊断，绝不写进卫星通道
                // （否则模块会先用真实卫星注入，下一秒才被合成表覆盖）
                return;
            }
            if (json != null) {
                GnssSession.INSTANCE.set(json);
                if (hasSatellites(json)) {
                    logGnssSource("collected");
                }
            }
        });
    }

    /**
     * 「GNSS 完全模拟」的发布节拍：用<b>当前模拟坐标 + 当前时间</b>重算卫星表并写进
     * {@link GnssSession}（模块经 prefs / 套接字 / Provider 读到后回放 GnssStatus）。
     *
     * <p>为什么不能靠 {@code realism.applyAt()} 顺带更新：拟真关闭时它提前 return，
     * 根本不碰 {@link com.vloc.motion.core.GnssSky}，卫星表会永远停在上一次（或空）。
     * 所以这里显式调用 {@link RealismEngine#updateGnss} 驱动，它是唯一不受拟真开关影响的入口。
     *
     * <p>节流 {@link #GNSS_SYNTHETIC_INTERVAL_MS}（1 秒）：卫星仰角/方位是慢变量，1Hz 与真机
     * GnssStatus 上报节奏一致；但必须**持续**更新——坐标一变，可见卫星集合就要跟着变。
     *
     * @param nowMs 单调时钟（{@link SystemClock#uptimeMillis()}），只用于节流；
     *              TLE 推算用的是墙钟 {@link System.currentTimeMillis()}
     */
    private void publishSyntheticGnss(long nowMs) {
        try {
            if (!shouldPublishSyntheticGnss()) {
                return;
            }
            if (nowMs - lastSyntheticGnssMs < GNSS_SYNTHETIC_INTERVAL_MS) {
                return;
            }
            final double[] position = currentGnssPosition();
            if (Double.isNaN(position[0]) || Double.isNaN(position[1])) {
                return;   // 还没有有效模拟坐标：本帧不发，下一帧再试
            }
            lastSyntheticGnssMs = nowMs;
            realism.updateGnss(position[0], position[1], System.currentTimeMillis());
            final String json = realism.gnssJsonAlways();
            if (json == null || json.length() <= 2) {
                // "[]"：星表缺失 / 全部算不出可见卫星。此时保持原样（真实快照仍可用），不清空通道
                return;
            }
            GnssSession.INSTANCE.set(json);
            logGnssSource("synthetic");
        } catch (Throwable t) {
            // 合成失败绝不能影响注入主流程
            VLog.w(TAG, "publish synthetic gnss failed", t);
        }
    }

    /**
     * 本帧的卫星表是否由<b>合成</b>数据提供。
     *
     * <ul>
     *   <li>{@code gnss_synthetic=true}：无条件合成（不等真实采集结果）；</li>
     *   <li>自动模式：等采集流程有结论后，只有<b>没采到卫星</b>才合成。</li>
     * </ul>
     */
    private boolean syntheticGnssActive() {
        if (gnssSyntheticForced) {
            return true;
        }
        return gnssCollectResolved && !hasSatellites(gnssCollectedJson);
    }

    /** 是否该发合成卫星表（与 {@link #syntheticGnssActive()} 同义，语义分开便于阅读）。 */
    private boolean shouldPublishSyntheticGnss() {
        return syntheticGnssActive();
    }

    /**
     * 合成卫星表用的观测点：优先本帧正在注入的坐标。
     *
     * <p>SPOT = 定点坐标；ROUTE = 当前轨迹采样点（暂停/保持时也有效）；
     * 都取不到时退回会话里最后一次注入的坐标。返回 {lat, lon}，无效为 NaN。
     */
    private double[] currentGnssPosition() {
        final double[] out = {Double.NaN, Double.NaN};
        try {
            final Session session = Session.INSTANCE;
            if (session.mode == Session.Mode.SPOT) {
                out[0] = spotLat;
                out[1] = spotLon;
                return out;
            }
            final Route.Sample sample = player.currentSample();
            if (sample != null) {
                out[0] = sample.lat;
                out[1] = sample.lon;
                return out;
            }
            if (session.hasStart) {
                out[0] = session.lat;
                out[1] = session.lon;
            }
        } catch (Throwable ignored) {
            // 取不到就当无效，调用方本帧不发布
        }
        return out;
    }

    /**
     * 统一出口：{@code realism.apply(...)} + （合成模式下）补齐 GNSS 附属数据。
     *
     * <p>为什么需要补齐：拟真关闭时 {@link RealismEngine#applyAt} 提前 return，
     * {@code satellites / meanCn0 / maxCn0} 恒为 0；而「GNSS 完全模拟」此刻正把一张
     * 真实可见的卫星表注入给模块——目标 App 若同时看 Location extras 与 GnssStatus，
     * 就会看到"0 颗"与"几十颗"的矛盾。
     *
     * <p>这里只在"拟真关闭 + 合成生效"这一组合下用<b>同一份</b> GnssSky 结果
     * 补上聚合值（与 {@link GnssSky#meanCn0()} / {@link GnssSky#maxCn0()} 同一口径：
     * 只统计 usedInFix，不人为放大）；拟真开着时 applyAt 已经是这个口径，原样返回。
     */
    private Fix applyRealismFix(double lat, double lon, double altitude,
                                double speedMps, double bearingDeg) {
        final Fix fix = realism.apply(lat, lon, altitude, speedMps, bearingDeg, accuracyMeters);
        try {
            if (realism.isEnabled() || !syntheticGnssActive()) {
                return fix;
            }
            int used = 0;
            double sum = 0;
            double max = 0;
            for (GnssSky.Satellite sat : realism.gnssSatellites()) {
                if (!sat.usedInFix) {
                    continue;
                }
                used++;
                sum += sat.cn0DbHz;
                max = Math.max(max, sat.cn0DbHz);
            }
            if (used <= 0) {
                return fix;   // 这一刻没有可用卫星：保持原值（0），不编造
            }
            return new Fix(fix.lat, fix.lon, fix.altitudeMeters, fix.speedMps, fix.bearingDeg,
                    fix.accuracyMeters, fix.verticalAccuracyMeters, used,
                    (float) (sum / used), (float) max);
        } catch (Throwable t) {
            return fix;   // 补齐失败就用原值，绝不影响注入主流程
        }
    }

    /**
     * 采集结果里是否真的有可用卫星（数组 / 列式对象两种格式都认）。
     *
     * <p>判定口径与模块解析器一致：必须存在一颗 {@code svid > 0} 的卫星。
     * 空串 / {@code "[]"} / 缺 {@code svid} 列 / svid 全是 0 / 解析失败 一律按"没有卫星"
     * 处理 = 自动回退合成。
     */
    private static boolean hasSatellites(String json) {
        if (json == null) {
            return false;
        }
        final String trimmed = json.trim();
        if (trimmed.isEmpty() || "null".equals(trimmed)) {
            return false;
        }
        try {
            if (trimmed.charAt(0) == '[') {
                final JSONArray array = new JSONArray(trimmed);
                for (int i = 0; i < array.length(); i++) {
                    final JSONObject item = array.optJSONObject(i);
                    if (item != null && item.optInt("svid", 0) > 0) {
                        return true;
                    }
                }
                return false;
            }
            final JSONArray svid = new JSONObject(trimmed).optJSONArray("svid");
            if (svid == null) {
                return false;
            }
            for (int i = 0; i < svid.length(); i++) {
                if (svid.optInt(i, 0) > 0) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 卫星表来源打日志：只在来源变化时打一条（现场据此判定 synthetic / collected）。 */
    private void logGnssSource(String source) {
        if (source == null || source.equals(gnssSourceLogged)) {
            return;
        }
        gnssSourceLogged = source;
        VLog.i("VLocService", "gnss source: " + source);
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
                VLog.w(TAG, "system inject: system location switch is off; "
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
            VLog.w(TAG, "system location switch is off, abort before test provider takeover");
            return PrepareResult.failed(session, LocationSwitch.DISABLED_MSG);
        }

        // 新会话：让上一次停止尚未完成的 appop 回收让位（见 revokeMockAppopIfGranted）
        final long myEpoch = mockSessionEpoch.incrementAndGet();

        // start 不能无限期等 appop 锁：回收侧最坏要连跑多次 su（单次 20s + 多候选路径
        // fallback）。这里带短超时抢锁，超时就跳过等待继续授权——代次已在上面自增，
        // 回收线程在写 appop 前会复查代次并放弃本次回收；留痕标记也保证下一次停止重试。
        final boolean locked = tryLockMockAppop();
        if (!locked) {
            VLog.w(TAG, "appop revoke still holds the lock after " + MOCK_APPOP_LOCK_WAIT_MS
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
                    VLog.w(TAG, "mock_location appop lost right after grant, retry once: "
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
            VLog.i(TAG, "session stopped during prepare, abort provider takeover");
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
                VLog.w(TAG, "take over aborted: system location switch was disabled | "
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
            VLog.w(TAG, "test provider partially prepared: " + newInjector.describe());
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
        VLog.w(TAG, "partial takeover told to user: " + notice);
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
            VLog.i(TAG, "system inject ready: " + describeSystemModuleEvidence());
            return new PrepareResult(true, null);
        }
        // 没有既有痕迹：主动触发一次定位读取，逼 system_server 里的模块回读快照
        probeSystemModule();
        final long deadline = SystemClock.elapsedRealtime() + SYSTEM_MODULE_PROBE_WAIT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isSystemModuleOnline()) {
                VLog.i(TAG, "system inject ready after probe");
                return new PrepareResult(true, null);
            }
            SystemClock.sleep(SYSTEM_MODULE_PROBE_POLL_MS);
        }
        // 仍无痕迹：绝不再"假成功"，走与 root 失败相同的失败路径
        VLog.w(TAG, "system inject enabled but no module evidence: no snapshot read within "
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
            VLog.w(TAG, "probe system module failed", t);
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
                    // 「GNSS 完全模拟」：定点坐标不变，卫星仍会随时间升落，照样按 1Hz 刷新
                    long spotNow = SystemClock.uptimeMillis();
                    publishSyntheticGnss(spotNow);
                    // 定点模式没有"移动/暂停"，这里只是让空闲计时跟随状态复位
                    updateOverlayIdle(spotNow);
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
                // 「GNSS 完全模拟」：放在本帧坐标推进/保持之后、updateSteps 之前——
                // ①用的是**当前**模拟坐标 + 当前时间；②steps 里的 "gnss" 数组随后就能取到这一份。
                publishSyntheticGnss(now);
                updateSteps(now);
                updateNotification();
                updateOverlay();
                // 空闲（待开始 / 已暂停）超过阈值就把悬浮窗摘掉，避免长期挂在屏幕上被看到
                updateOverlayIdle(now);
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
            stopInternal("已到达目标 · 已自动停止，定位已恢复", StopReason.COMPLETED);
        }
    };

    private void stopTicker() {
        if (ticker != null) {
            handler.removeCallbacks(ticker);
            ticker = null;
        }
    }

    private void pushSpotLocation() {
        Fix fix = applyRealismFix(spotLat, spotLon, baseAltitude, 0.0, 0.0);
        // 系统级注入：本进程不写测试 Provider，但要发布快照给模块
        LocationSession.INSTANCE.update(fix.lat, fix.lon, fix.altitudeMeters,
                fix.speedMps, fix.bearingDeg, fix.accuracyMeters, fix.satellites,
                fix.meanCn0, fix.maxCn0);
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
        return applyRealismFix(sample.lat, sample.lon, altitude,
                player.getCurrentSpeedMps(), sample.bearingDeg);
    }

    private void injectFix(Fix fix) {
        // 无论哪种注入方式，都先把最新快照发布给模块（系统级注入在 system_server 读取它）
        LocationSession.INSTANCE.update(fix.lat, fix.lon, fix.altitudeMeters,
                fix.speedMps, fix.bearingDeg, fix.accuracyMeters, fix.satellites,
                fix.meanCn0, fix.maxCn0);
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
        VLog.w(TAG, "inject failed", e);
        stopInternal("定位注入失败，已停止：" + e.getMessage(), StopReason.FAILED);
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
        Fix fix = applyRealismFix(sample.lat, sample.lon, altitude, 0.0, sample.bearingDeg);
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
                        lastSatellites, lastCn0Mean, lastCn0Max, stepsGnssJson()), true);
        publishNativeSensorState(dt, speed, bearing);
    }

    /**
     * 下发给模块（目标进程内 {@code hidden/GnssReplay}）的卫星数组。
     *
     * <p>合成模式用 {@link RealismEngine#gnssJsonAlways()}：拟真关闭时 {@link RealismEngine#gnssJson()}
     * 恒为 {@code "[]"}，目标进程会以为"没有卫星数据"而透传真实 GnssStatus——同一台设备上
     * system_server 注入合成表、目标进程却回真实（室内为空）状态，自相矛盾。
     * 其它情况保持原语义：拟真开关决定模块是否接管卫星状态。
     */
    private String stepsGnssJson() {
        try {
            if (syntheticGnssActive()) {
                return realism.gnssJsonAlways();
            }
        } catch (Throwable ignored) {
            // 兜底：退回原语义，绝不让卫星表拖垮 steps 通道
        }
        return realism.gnssJson();
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
        // 之前因"空闲太久"被自动隐藏的悬浮窗，恢复移动时重新显示（用户手动关掉的不显示）
        restoreOverlayAfterIdle();
    }

    /** 会话结束原因（决定使用记录里的 {@code result}）。 */
    private enum StopReason {
        /** 用户主动停止（主界面「彻底停止」）。 */
        USER,
        /** 正常跑完（到达终点 / 目标，自动停止）。 */
        COMPLETED,
        /** 失败（准备失败 / 注入失败 / 注入通道断开）。 */
        FAILED
    }

    /**
     * 停止会话。
     *
     * <p><b>使用记录</b>：开头先按 {@code reason} 结算一条 {@link UsageLog} 记录
     * （此时 {@code session.traveledMeters} / {@code session.error} 还是本会话的值），
     * 然后才重置运行态；{@code usageStartedAtMs} 结算后立刻归零，所以重复调用本方法
     * 不会产生第二条记录。
     */
    private void stopInternal(String message, StopReason reason) {
        // ---- 使用记录：必须在下面重置 session 之前结算 ----
        finishUsageSession(reason, message);
        // 先让仍在后台准备的会话过期：它之后的 post 会被 isStalePrepare 丢弃并自行释放
        ++sessionSeq;
        stopTicker();
        // 紧接着摘掉快照套接字：模块必须立刻停止改写、回到真实定位，不能继续读到本场快照。
        // stop() 幂等、且最多等待守护线程 500ms（见 SnapshotServer），不会拖住主线程收尾。
        SnapshotServer.INSTANCE.stop();
        // prefs 通道同步收尾：写一条 active=false 的快照，模块解析后立即回退（不等陈旧守卫超时）
        SnapshotPrefs.INSTANCE.stop();
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
        // 本会话结束：空闲自动隐藏状态复位（下一次会话重新计时）
        overlayIdleSinceMs = 0L;
        overlayIdleHidden = false;
        // ---- 落盘痕迹清理：文件 IO + root 全部在后台线程，这里只发出请求 ----
        // 必须在上面 SnapshotPrefs.INSTANCE.stop() 之后调用：清理线程要删 vloc_snapshot.xml，
        // 而 stop() 已经写回 active=false，模块此刻已回退；先 stop 再删文件不会影响正在跑的会话
        // （Options.protectActiveSession 还会拦住"停止后立刻重新开始"的新会话）。
        startTraceCleanup();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ---------------------------------------------------------------- 使用记录

    /**
     * 会话开始：记下使用记录需要的上下文（开始时间 / 模式 / 路径点数）。
     *
     * <p>如果上一场会话还没结算（"到达终点后点重新开始"直接走 startRoute，不经过
     * {@link #stopInternal}），先按「手动停止」把它补记一条——那场会话确实发生过，
     * 不该因为走了另一条代码路径就从记录里消失。
     */
    private void startUsageSession(String mode, int points) {
        if (usageStartedAtMs > 0L) {
            finishUsageSession(StopReason.USER, null);
        }
        usageStartedAtMs = System.currentTimeMillis();
        usageMode = mode;
        usagePoints = points;
        usagePendingId = 0L;
    }

    /**
     * 会话结束：写一条使用记录（只写一次，靠 {@link #usageStartedAtMs} 去重）。
     *
     * <p>结果判定：
     * <ul>
     *   <li>{@link StopReason#COMPLETED} → {@code completed}（到达终点自动停止，
     *       即使中途出现过"模块不在线"之类的 error 也以完成为准）；</li>
     *   <li>{@link StopReason#FAILED} → {@code failed}，error 取停止文案 / {@code session.error}；</li>
     *   <li>{@link StopReason#USER}：如果已经到达终点（默认不自动停止，用户看完再点停止）
     *       仍记 {@code completed}；否则 {@code session.error} 非空说明本会话中途已经失败过
     *       （注入失败、模块不在线），记 {@code failed} 并带上那条 error；其余记 {@code stopped}。</li>
     * </ul>
     *
     * <p>写盘失败只记日志：记录是附属功能，绝不能影响停止流程。
     */
    private void finishUsageSession(StopReason reason, String message) {
        final long startedAt = usageStartedAtMs;
        if (startedAt <= 0L) {
            // 没有待结算的会话，或已经结算过（重复 stopInternal 走这里直接返回）
            return;
        }
        // 先清标志：即使下面写盘抛异常，也不会重复记第二条
        usageStartedAtMs = 0L;
        final String mode = usageMode != null ? usageMode : UsageLog.MODE_SPOT;
        final int points = usagePoints;
        usageMode = null;
        usagePoints = 0;
        try {
            Session session = Session.INSTANCE;
            final long endedAt = System.currentTimeMillis();
            final long durationSec = Math.max(0L, (endedAt - startedAt) / 1000L);
            final String sessionError = session.error;
            String result;
            String error = null;
            if (reason == StopReason.COMPLETED) {
                result = UsageLog.RESULT_COMPLETED;
            } else if (reason == StopReason.FAILED) {
                result = UsageLog.RESULT_FAILED;
                error = firstNonBlank(message, sessionError, "未知错误");
            } else if (session.arrived) {
                // 已经到了终点、只是用户随后手动停止（auto_stop_on_finish 默认关闭时就是这样）
                result = UsageLog.RESULT_COMPLETED;
            } else if (!isBlank(sessionError)) {
                result = UsageLog.RESULT_FAILED;
                error = sessionError;
            } else {
                result = UsageLog.RESULT_STOPPED;
            }
            UsageLog.Entry entry = new UsageLog.Entry(startedAt, endedAt, mode, points,
                    session.traveledMeters, durationSec, result);
            entry.error = error;
            usagePendingId = UsageLog.record(getApplicationContext(), entry);
            // 这条日志是"这一局跑了多远"的汇总：数值由坐标推导，统一过 VLog.sanitize()——
            // 将来若有人把坐标 / 精度（带小数）加进来，也不会原样写进 logcat
            VLog.i(TAG, VLog.sanitize("usage: mode=" + mode + " result=" + result
                    + " distance=" + Math.round(session.traveledMeters) + "m"
                    + " duration=" + durationSec + "s id=" + usagePendingId));
        } catch (RuntimeException e) {
            // 记录失败不影响停止流程
            usagePendingId = 0L;
            VLog.w(TAG, "usage record failed: " + e);
        }
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    /** 取第一个非空白字符串（都可能为空时返回 null）。 */
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 会话结束后清理「注入痕迹」（{@link TraceCleaner}）。
     *
     * <p><b>线程模型</b>：本方法只组装选项并起一条 daemon 线程
     * （{@code "vloc-trace-cleanup"}），立刻返回——主线程的停止流程不会被文件删除、
     * {@code su}（默认 4s 超时）、prefs 写入拖住。悬浮窗移除这一项由 TraceCleaner
     * post 回主线程执行（{@code WindowManager} 有线程亲和性），最多等 1s。
     *
     * <p>清理结果由 {@link TraceCleaner} 自己按 {@code VLog.i("VLocCleanup", "cleanup: ...")}
     * 逐项打印，同时也通过 {@link TraceCleaner#lastResult()} 留给设置页；本方法还会把
     * {@link TraceCleaner.Result#summary()} 回填到刚写下的那条使用记录（按 id 定位）。
     */
    private void startTraceCleanup() {
        // 清理是异步的：先把待回填的 id 取出来并清掉，避免回调串到下一条记录上
        final long recordId = usagePendingId;
        usagePendingId = 0L;
        // 停止路径已经清理过一次：之后的 onTaskRemoved / onDestroy 兜底不必再来一遍
        // （文件已删、prefs 已清，重复执行只会多几次 su 调用）。
        exitCleanupRequested = true;
        final Context appContext = getApplicationContext();
        TraceCleaner.Options options = newTraceCleanupOptions();
        TraceCleaner.cleanup(appContext, options, result -> {
            VLog.i(TAG, "trace cleanup: " + result.summary());
            // 回填使用记录：找不到（记录被删 / 已被挤掉）时静默忽略，不阻塞也不报错
            if (recordId > 0L) {
                UsageLog.updateCleanup(appContext, recordId, result.summary());
            }
        });
    }

    /**
     * 清理选项：<b>停止路径与退出兜底路径共用同一份</b>，避免两条路各写一套导致行为漂移
     * （例如退出时忘了撤告警通知、或把 logcat 开关读错）。
     *
     * <p>不是会话状态的快照，可以重复构造；{@code hideOverlay} 复用本服务实例，
     * 服务销毁后 {@code this} 仍然可达，且 {@code hideOverlay()} 自身幂等。
     */
    private TraceCleaner.Options newTraceCleanupOptions() {
        TraceCleaner.Options options = new TraceCleaner.Options();
        // 前台通知 + 系统级注入告警：stopForeground(REMOVE) 只撤前台那条，告警要单独 cancel
        options.notificationId = NOTIFICATION_ID;
        options.alertNotificationId = ALERT_NOTIFICATION_ID;
        // 复用本服务的实例：hideOverlay() 内部就是 floatingWindow.hide()，自带 shown 判空、幂等；
        // 绝不能在 TraceCleaner 里 new 一个 FloatingWindow，那样删不掉真正挂在 WindowManager 上的 View
        options.hideOverlay = this::hideOverlay;
        // 清 logcat 会清掉整个缓冲区（含其它应用日志），默认关闭，只认用户显式打开的设置
        options.clearLogcat = RouteStore.loadClearLogcatOnStop(this);
        return options;
    }

    /**
     * 退出兜底清理（{@link #onTaskRemoved} / {@link #onDestroy}）：<b>仅在没有运行中的会话时</b>
     * 做一次 {@link TraceCleaner}，挡住"用户没点停止就把应用划掉"留下的落盘痕迹
     * （{@code vloc_snapshot.xml} 的最后一次坐标、{@code gnss_snapshot}、{@code /data/local/tmp/vloc*}、
     * logcat）。选项与 {@link #startTraceCleanup()} 完全一致。
     *
     * <p>三个保护：
     * <ul>
     *   <li>{@code Session.mode != IDLE} 直接跳过——运行中删掉快照会打断模块正在用的通道；</li>
     *   <li>{@link #exitCleanupRequested} 去重：{@code onTaskRemoved} 之后系统往往紧接着
     *       {@code onDestroy}，两次调用只清理一次；</li>
     *   <li>清理在 daemon 线程，失败只记日志，绝不阻塞销毁流程。</li>
     * </ul>
     *
     * <p>{@code TraceCleaner.Options.protectActiveSession} 默认开：万一用户"划掉后立刻重开"，
     * 新会话的 {@code SnapshotPrefs.start()} 已经先跑了，清理线程会跳过 prefs 两项。
     */
    private void startExitTraceCleanup(String where) {
        final Session session = Session.INSTANCE;
        if (session.mode != Session.Mode.IDLE) {
            // 运行中：不清（快照正在用），也不打 warn——这属于正常的"划掉但还在跑"
            VLog.i(TAG, "exit trace cleanup skipped (" + where + "): session active");
            return;
        }
        if (exitCleanupRequested) {
            return;   // onTaskRemoved + onDestroy 连着来：只清一次
        }
        exitCleanupRequested = true;
        final Context appContext = getApplicationContext();
        TraceCleaner.cleanup(appContext, newTraceCleanupOptions(),
                result -> VLog.i(TAG, "exit trace cleanup (" + where + "): " + result.summary()));
    }

    private void releaseInjector() {
        if (injector != null) {
            try {
                injector.release();
            } catch (RuntimeException e) {
                VLog.w(TAG, "release injector failed", e);
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
        VLog.i(TAG, "discard prepare result: session is no longer current");
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
            VLog.w(TAG, "release discarded injector failed", e);
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
                VLog.w(TAG, "revoke mock_location appop failed", t);
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
            VLog.i(TAG, "skip appop revoke: a newer session has started");
            return;
        }
        if (RouteStore.loadMockAppopGrantedByApp(this) <= 0L) {
            return;   // 另一个并发停止已经回收
        }
        RootShell.Result current = RootShell.getMockAppop(pkg);
        if (isAppopRevokeSuperseded(epoch)) {
            // 查询期间新会话已经开始（它可能刚授出 allow）：不要再写 appop
            VLog.i(TAG, "skip appop revoke: a newer session started during query");
            return;
        }
        if (!current.ok()) {
            // su / appops 不可用：无法判断当前状态，保留留痕下次再试，
            // 不能凭"查不到"就当作已回收。
            VLog.w(TAG, "query mock_location appop failed, keep grant mark: " + current);
            return;
        }
        if (!RootShell.isMockAppopAllowed(current)) {
            // 用户/系统已经改回不允许：没有要回收的 allow，只清自己的留痕
            RouteStore.clearMockAppopGrantedByApp(this);
            VLog.i(TAG, "mock_location appop already not allowed, grant mark cleared");
            return;
        }
        RootShell.Result revoke = RootShell.revokeMockAppop(pkg);
        if (!revoke.ok()) {
            VLog.w(TAG, "revoke mock_location appop failed: " + revoke);
            return;   // 保留标记，下次彻底停止再试
        }
        RootShell.Result after = RootShell.getMockAppop(pkg);
        if (isAppopRevokeSuperseded(epoch)) {
            // 复核期间新会话可能刚好重新授权：留痕不清，交给下一次停止核对
            VLog.i(TAG, "keep grant mark: a newer session started during revoke");
            return;
        }
        if (!after.ok() || RootShell.isMockAppopAllowed(after)) {
            VLog.w(TAG, "mock_location appop still allowed after revoke: " + after);
            return;   // 保留标记，下次彻底停止再试
        }
        RouteStore.clearMockAppopGrantedByApp(this);
        VLog.i(TAG, "revoked mock_location appop granted by this app");
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
            VLog.i(TAG, "passive best-effort skipped: no location permission");
            return;
        }
        RealLocationProvider.requestFresh(this, new RealLocationProvider.Callback() {
            @Override
            public void onLocation(Location location) {
                VLog.i(TAG, "passive best-effort: " + location.getProvider()
                        + " fix received (mock=" + location.isFromMockProvider() + ")");
            }

            @Override
            public void onError(String message) {
                VLog.i(TAG, "passive best-effort: no real fix (" + message
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

    /** 当前实际使用的通知渠道（渠道被用户关闭时会切到备用 id）。 */
    private String activeChannelId = CHANNEL_ID;

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        createChannel(nm, CHANNEL_ID, "运行状态");
        createChannel(nm, CHANNEL_ID_BACKUP, "运行状态（备用）");
        // 渠道被用户/系统设为"关闭"(IMPORTANCE_NONE) 时，继续往它发前台通知
        // 会被判 Bad notification（真机踩过）。此时切到备用渠道，保证会话能起来。
        NotificationChannel primary = nm.getNotificationChannel(CHANNEL_ID);
        if (primary != null && primary.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            activeChannelId = CHANNEL_ID_BACKUP;
            Log.w(TAG, "channel " + CHANNEL_ID + " disabled, using backup channel");
        } else {
            activeChannelId = CHANNEL_ID;
        }
    }

    private static void createChannel(NotificationManager nm, String id, String name) {
        try {
            NotificationChannel channel = new NotificationChannel(id, name,
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setDescription("虚拟运动运行状态");
            nm.createNotificationChannel(channel);
        } catch (Throwable ignored) {
            // 部分 ROM 会拒绝建渠道：忽略，后面还有降级
        }
    }

    /**
     * 构建前台通知：<b>中性文案</b>，保留点击打开 App。
     *
     * <p>不再接收动态文案参数——调用方原来会传入坐标 / 速度 / 路线进度，
     * 那些信息一律不进入通知（见 {@link #NOTIFICATION_TITLE}）。
     */
    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 前台服务通知必须是"最朴素"的形态：只用平台 Notification.Builder、
        // 不带任何 action / 自定义 extras。真机曾因通知被判非法而抛
        // CannotPostForegroundServiceNotificationException（异步到主线程，catch 不住）。
        // 通知栏里的暂停/停止按钮属于"锦上添花"，稳定性优先，不做。
        final Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, activeChannelId)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(com.vloc.motion.R.drawable.ic_my_location)
                .setContentTitle(NOTIFICATION_TITLE)
                .setContentText(NOTIFICATION_TEXT)
                .setContentIntent(openPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    /**
     * 刷新前台通知。
     *
     * <p>仍保留 1s 节流（{@code notify()} 走 Binder，跟 10Hz 的 ticker 跑没必要），
     * 但内容固定为中性文案：会话状态变化不再改变通知文本。
     */
    private void updateNotification() {
        long now = SystemClock.uptimeMillis();
        if (now - lastNotificationMs < 1000L) {
            return;
        }
        lastNotificationMs = now;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
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
                .setSmallIcon(com.vloc.motion.R.drawable.ic_my_location)
                .setContentTitle("虚拟运动 · 需要处理")
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
        // 每次显式显示都重新开始空闲计时：否则「暂停中手动重开悬浮窗」会因为
        // overlayIdleHidden 仍为 true 而永远不再自动隐藏
        overlayIdleSinceMs = 0L;
        overlayIdleHidden = false;
        updateOverlay();
    }

    private void hideOverlay() {
        if (floatingWindow != null) {
            floatingWindow.hide();
        }
    }

    /**
     * 悬浮窗空闲自动隐藏：会话处于「待开始 / 已暂停」超过 {@link #OVERLAY_IDLE_HIDE_MS}
     * 时把窗口摘掉。
     *
     * <p>为什么需要：暂停 / 待开始时定位仍然保持，但窗口会一直显示"已暂停 / 海拔 / 步数"，
     * 长时间挂在屏幕上等于把"有人在模拟运动"摊给别人看。恢复运行
     * （{@link #resumeMovement()}）会把它重新显示出来。
     *
     * <p><b>线程模型</b>：只在 ticker（主线程 Handler）里调用，复用现有 100ms 节拍
     * （SPOT 模式 1s），不新增线程、不加定时器。状态只看
     * {@code Session.paused / held}——「待开始」在会话语义里就是 {@code held=true}
     * （见 {@link #updateOverlay()} 的"已定位到起点 · 待开始"分支）；到达终点后
     * {@code paused=true}，同样按空闲处理。
     */
    private void updateOverlayIdle(long now) {
        Session session = Session.INSTANCE;
        final boolean idle = session.mode != Session.Mode.IDLE
                && (session.paused || session.held);
        if (!idle) {
            // 运行中：清掉计时，下次暂停重新从 0 开始
            overlayIdleSinceMs = 0L;
            overlayIdleHidden = false;
            return;
        }
        if (floatingWindow == null || !floatingWindow.isShown()) {
            // 窗口本来就不在（没授权 / 用户关掉了）：不用计时
            overlayIdleSinceMs = 0L;
            return;
        }
        if (overlayIdleSinceMs == 0L) {
            overlayIdleSinceMs = now;   // 刚开始空闲：记下起点
            return;
        }
        if (!overlayIdleHidden && now - overlayIdleSinceMs >= OVERLAY_IDLE_HIDE_MS) {
            overlayIdleHidden = true;
            hideOverlay();
            VLog.i(TAG, "overlay auto-hidden after idle " + (now - overlayIdleSinceMs) + "ms");
        }
    }

    /**
     * 恢复运行时，把「因空闲被自动隐藏」的悬浮窗重新显示。
     *
     * <p>只还原"我们自动藏起来的"那一次：用户自己在菜单里关掉悬浮窗
     * （{@code overlay_enabled=false}）时不会因为这条路径又弹出来。
     */
    private void restoreOverlayAfterIdle() {
        if (!overlayIdleHidden) {
            return;
        }
        overlayIdleHidden = false;
        overlayIdleSinceMs = 0L;
        if (!RouteStore.loadOverlayEnabled(this) || !Settings.canDrawOverlays(this)) {
            return;
        }
        showOverlay();
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
        // 通道必须在 startForeground 之前就存在：缺失时系统会抛
        // CannotPostForegroundServiceNotificationException（"Bad notification"）
        // 且该异常是异步投递到主线程的，外面 catch 不住 → 直接闪退。
        ensureNotificationChannel();
        // 通知被整体关闭时，startForeground 会被 ROM 判非法并异步抛异常（catch 不住）。
        // 这种情况降级：不前台化，提示用户去开通知，避免整个进程被系统杀掉。
        try {
            final NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && !nm.areNotificationsEnabled()) {
                Log.w(TAG, "notifications disabled, skip startForeground");
                toast("请在系统设置中允许「虚拟运动」发送通知，否则后台运行会被限制");
                return;
            }
        } catch (Throwable ignored) {
            // 查询失败按"可用"处理，继续走正常路径
        }
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
