package com.vloc.motion.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.vloc.motion.core.RouteStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Set;

/**
 * 快照 world-readable prefs：把定位 / 卫星 / 作用域配置写进 LSPosed 官方
 * <b>New XSharedPreferences</b> 通道（Android 11+ 的标准做法）。
 *
 * <p><b>为什么还要第三条通道</b>：模块跑在 {@code system_server}（uid 1000）里，
 * <ul>
 *   <li>读 {@code content://com.vloc.motion.env/*} → {@code SecurityException}；</li>
 *   <li>连 {@link SnapshotServer} 的 Unix 抽象套接字 → SELinux 拒绝
 *       （{@code avc: denied { connectto } scontext=u:r:system_server:s0
 *       tcontext=u:r:untrusted_app_34:s0}）。</li>
 * </ul>
 * 于是改成 prefs：主 App 用 {@link Context#MODE_WORLD_READABLE} 写，
 * 模块（{@code hidden/PrefsClient}）用
 * {@code new XSharedPreferences("com.vloc.motion", "vloc_snapshot")} 读。
 * 这套机制只在 {@code AndroidManifest.xml} 里 {@code xposedminversion≥93} 且
 * {@code xposedsharedprefs=true} 时由 LSPosed 打开——它 hook 了
 * {@code ContextImpl.getPreferencesDir()}/{@code checkMode(int)}，所以
 * {@code MODE_WORLD_READABLE} 在 Android 7+ 上不再是 {@code SecurityException}。
 *
 * <p><b>数据契约</b>（模块侧 {@code hidden/PrefsClient} 逐字对应）：
 * <pre>
 *   loc      {"active":bool,"lat":..,"lon":..,"alt":..,"speed":..,"bearing":..,
 *             "acc":..,"satellites":..,"updatedAt":..}   ← {@link LocationSession#toJson()} 原样
 *   gnss     [{"svid":..,"const":..,...}, ...]            ← {@link GnssSession#json} 原样
 *   targets  {"enabled":bool,"packages":["pkg1",...]}     ← 与 /targets 同款
 * </pre>
 *
 * <p><b>写入节流</b>：
 * <ul>
 *   <li>{@code loc}：最多 <b>4Hz</b>（{@link #LOC_INTERVAL_MS}=250ms）——定位投递是 10Hz，
 *       写盘没必要跟着跑；</li>
 *   <li>{@code gnss}：1s 一次，且内容没变就不写（一局会话里卫星表只在开始时采一次）；</li>
 *   <li>{@code targets}：1s 检查一次，<b>只在名单变化时</b>写。</li>
 * </ul>
 * 所有写入都走 {@link SharedPreferences.Editor#apply()}（异步落盘），绝不阻塞主线程。
 *
 * <p><b>降级</b>：LSPosed 未启用该机制（旧版本 / 模块元数据不足）时，
 * {@code getSharedPreferences(..., MODE_WORLD_READABLE)} 会抛 {@code SecurityException}：
 * 这里只记一条 {@code Log.w("VLocPrefs", "world-readable prefs unavailable: ...")}、
 * 置 {@code available=false} 并<b>不再尝试</b>，模块照旧走套接字 / ContentProvider 回退。
 *
 * <p><b>生命周期</b>：{@link LocationService} 在会话启动处 {@link #start(Context)}、
 * 在 {@code stopInternal} 收尾处 {@link #stop()}（与 {@link SnapshotServer} 同步调用）。
 * {@link #stop()} 会写一条 {@code active=false} 的快照并清掉卫星表，让模块<b>立刻</b>
 * 停止改写（而不是等陈旧守卫超时）。{@code start}/{@code stop} 都是幂等的。
 */
public final class SnapshotPrefs {

    /**
     * prefs 文件名（模块侧按此名读：{@code new XSharedPreferences("com.vloc.motion", "vloc_snapshot")}）。
     * 改名等于换协议。
     */
    public static final String PREFS_NAME = "vloc_snapshot";

    /** key：定位快照（与 {@code content://com.vloc.motion.env/location} 完全相同的 JSON）。 */
    public static final String KEY_LOC = "loc";

    /** key：卫星表（与 {@code /gnss} 相同的 JSON 数组）。 */
    public static final String KEY_GNSS = "gnss";

    /** key：作用域配置（与 {@code /targets} 相同的 {@code {"enabled":..,"packages":[..]}}）。 */
    public static final String KEY_TARGETS = "targets";

    /** 唯一实例：与 {@link LocationSession}、{@link GnssSession} 等通道单例并列。 */
    public static final SnapshotPrefs INSTANCE = new SnapshotPrefs();

    private static final String TAG = "VLocPrefs";

    /** 定位写入间隔：最多 4Hz。 */
    private static final long LOC_INTERVAL_MS = 250L;

    /** 卫星表刷新间隔。 */
    private static final long GNSS_INTERVAL_MS = 1_000L;

    /** 作用域配置检查间隔（内容不变就不写）。 */
    private static final long TARGETS_INTERVAL_MS = 1_000L;

    /**
     * 会话结束写回的"未激活"快照。
     *
     * <p>模块的 {@code parseLocationJson} 只认 {@code active}：{@code false} 直接返回 null，
     * 于是模块立刻停止改写并回退（套接字 / ContentProvider），不会把最后一帧坐标继续注入。
     */
    private static final String INACTIVE_LOC_JSON = "{\"active\":false,\"lat\":0.0,\"lon\":0.0,"
            + "\"alt\":0.0,\"speed\":0.0,\"bearing\":0.0,\"acc\":0.0,\"satellites\":0,"
            + "\"updatedAt\":0}";

    /** 串行化 start / stop / open 对共享字段的读写。 */
    private final Object lock = new Object();

    /** 主线程定时器：start 之后每 {@link #LOC_INTERVAL_MS} 跑一次 {@link #onTick()}。 */
    private volatile Handler handler;

    /** 仅用于读作用域配置 / 打开 prefs；{@link #start(Context)} 时换成 application context。 */
    private volatile Context appContext;

    /** world-readable prefs；null = 还没打开成功。 */
    private volatile SharedPreferences prefs;

    /** 是否已经成功打开（true 之后才允许写）。 */
    private volatile boolean available;

    /** 已确认不可用（{@code SecurityException}）：此后<b>不再</b>尝试打开，避免每次会话重复抛异常。 */
    private volatile boolean unavailable;

    private volatile boolean running;

    private Runnable ticker;

    /** 上次各类写入的时刻（{@link SystemClock#elapsedRealtime()}，单调时钟）。 */
    private long lastLocAt;
    private long lastGnssAt;
    private long lastTargetsAt;

    /** 上一次真正写进 prefs 的内容：内容没变就不重复写（gnss / targets 用）。 */
    private String lastGnssJson;
    private String lastTargetsJson;

    /** 单例供运行时使用；包内可见的构造函数供单元测试。 */
    SnapshotPrefs() {
    }

    // ---------------------------------------------------------------- 生命周期

    /**
     * 会话启动：打开 world-readable prefs 并开始按节流写快照（幂等）。
     *
     * <p>打不开（LSPosed 未启用该机制 / 旧版不认 {@code MODE_WORLD_READABLE}）时<b>什么都不做</b>：
     * 不排定时器、不抛异常，模块照旧走套接字 / ContentProvider。
     *
     * @param context 任意 Context（内部取 application context，只用于 prefs 与作用域配置）
     */
    public void start(Context context) {
        if (context == null) {
            return;
        }
        try {
            final Context app = context.getApplicationContext() != null
                    ? context.getApplicationContext() : context;
            synchronized (lock) {
                appContext = app;
                if (running) {
                    return;   // 已在运行：重复 start 安全（"重新开始"不会重复排定时器）
                }
                if (!openLocked()) {
                    return;   // 不可用：本会话不写 prefs，回退通道继续工作
                }
                lastLocAt = 0L;
                lastGnssAt = 0L;
                lastTargetsAt = 0L;
                lastGnssJson = null;
                lastTargetsJson = null;
                if (handler == null) {
                    handler = new Handler(Looper.getMainLooper());
                }
                if (ticker == null) {
                    ticker = new Ticker();
                }
                running = true;
            }
            final Handler h = handler;
            if (h != null) {
                h.post(ticker);   // 立刻写第一帧，别等下个节流周期
            }
        } catch (Throwable t) {
            // 打开 / 排程失败都不能影响会话启动
            Log.w(TAG, "start failed: " + t);
        }
    }

    /**
     * 会话结束：停掉定时器，并写一条"未激活"快照让模块立刻停止改写（幂等）。
     *
     * <p>能在主线程调用：只做一次 {@code apply()}（异步落盘）；prefs 打不开时直接返回。
     */
    public void stop() {
        try {
            SharedPreferences target = null;
            synchronized (lock) {
                running = false;
                if (handler != null && ticker != null) {
                    handler.removeCallbacks(ticker);
                }
                if (available) {
                    target = prefs;
                }
            }
            if (target == null) {
                return;   // 从没打开成功：没有东西要清（模块本来就走回退通道）
            }
            // 清掉卫星表：下一次会话会重新采集，避免把上一场的卫星表继续喂给模块。
            // loc 覆盖成 active=false：模块解析出"未激活"立刻透传真实定位。
            target.edit()
                    .putString(KEY_LOC, INACTIVE_LOC_JSON)
                    .remove(KEY_GNSS)
                    .apply();
            lastGnssJson = null;
        } catch (Throwable t) {
            // 收尾失败不影响会话停止（模块还有陈旧守卫兜底）
            Log.w(TAG, "stop failed: " + t);
        }
    }

    // ---------------------------------------------------------------- 定时写入

    /** 定时任务：每 {@link #LOC_INTERVAL_MS} 跑一次 {@link #onTick()}（会话停止后自行结束）。 */
    private final class Ticker implements Runnable {
        @Override
        public void run() {
            try {
                onTick();
            } catch (Throwable ignored) {
                // 单次写入失败不能停掉定时器
            }
            final Handler h = handler;
            if (h != null && running) {
                h.postDelayed(this, LOC_INTERVAL_MS);
            }
        }
    }

    /**
     * 按节流把三类快照写进 prefs；不是会话中 / prefs 不可用时立即返回。
     *
     * <p>由 {@link #start(Context)} 排的主线程定时器每 250ms 调用一次，
     * 也可以外部直接调用（自带节流，重复调用不会多写）。
     */
    public void onTick() {
        if (!available || !running) {
            return;
        }
        final SharedPreferences opened = prefs;
        if (opened == null) {
            return;
        }
        try {
            final long now = SystemClock.elapsedRealtime();
            final SharedPreferences.Editor editor = opened.edit();
            boolean dirty = false;

            if (now - lastLocAt >= LOC_INTERVAL_MS) {
                lastLocAt = now;
                // 与 /location 完全同源：模块侧复用同一份解析代码
                editor.putString(KEY_LOC, LocationSession.INSTANCE.toJson());
                dirty = true;
            }

            if (now - lastGnssAt >= GNSS_INTERVAL_MS) {
                lastGnssAt = now;
                final String gnss = GnssSession.INSTANCE.json;
                if (gnss != null && !gnss.isEmpty() && !gnss.equals(lastGnssJson)) {
                    editor.putString(KEY_GNSS, gnss);
                    lastGnssJson = gnss;
                    dirty = true;
                }
            }

            final Context context = appContext;
            if (context != null && now - lastTargetsAt >= TARGETS_INTERVAL_MS) {
                lastTargetsAt = now;
                final String targets = targetsJson(context);
                if (!targets.equals(lastTargetsJson)) {
                    editor.putString(KEY_TARGETS, targets);
                    lastTargetsJson = targets;
                    dirty = true;
                }
            }

            if (dirty) {
                // apply() 异步落盘：主线程只做一次编辑器提交，不碰磁盘
                editor.apply();
            }
        } catch (Throwable ignored) {
            // prefs 被删 / 编辑器失效 / 序列化异常：一律静默，绝不影响会话
        }
    }

    // ---------------------------------------------------------------- 打开 / 降级

    /**
     * 打开 world-readable prefs（只在 {@link #start(Context)} 里、持锁调用）。
     *
     * <p><b>降级契约</b>：{@code SecurityException} 说明 LSPosed 的 New XSharedPreferences
     * 未启用（{@code xposedminversion<93} / {@code xposedsharedprefs} 缺失 / 模块没重新注册），
     * 记一条 {@code Log.w} 后把 {@code available} 置 false、{@code unavailable} 置 true，
     * 本次进程内<b>不再尝试</b>——模块的套接字 / Provider 回退照常工作。
     *
     * @return true = 可写；false = 本进程不写 prefs
     */
    @SuppressWarnings("deprecation")
    private boolean openLocked() {
        if (available) {
            return true;
        }
        if (unavailable) {
            return false;   // 已经确认不可用：不再重复抛异常
        }
        final Context context = appContext;
        if (context == null) {
            return false;
        }
        try {
            // MODE_WORLD_READABLE 是这条通道的"开关"：LSPosed 只在该模式下把文件放到
            // 模块可读的目录（并 hook checkMode 放行，否则 Android 7+ 会抛 SecurityException）
            final SharedPreferences opened = context.getSharedPreferences(
                    PREFS_NAME, Context.MODE_WORLD_READABLE);
            if (opened == null) {
                unavailable = true;
                prefs = null;
                available = false;
                Log.w(TAG, "world-readable prefs unavailable: getSharedPreferences returned null");
                return false;
            }
            prefs = opened;
            available = true;
            Log.i(TAG, "world-readable prefs ready: " + PREFS_NAME
                    + " (LSPosed New XSharedPreferences)");
            return true;
        } catch (SecurityException e) {
            unavailable = true;
            prefs = null;
            available = false;
            // 这条日志是现场判定"机制没启用"的唯一线索
            Log.w(TAG, "world-readable prefs unavailable: " + e);
            return false;
        } catch (Throwable t) {
            // 兜底：任何异常都按"不可用"处理，绝不影响会话
            unavailable = true;
            prefs = null;
            available = false;
            Log.w(TAG, "world-readable prefs unavailable: " + t);
            return false;
        }
    }

    // ---------------------------------------------------------------- 作用域配置

    /**
     * 作用域配置 JSON，与 {@link EnvProvider} 的 {@code /targets}、
     * {@link SnapshotServer} 的 {@code TARGETS} 应答完全一致（模块三处共用一份解析）。
     *
     * <p>已知 key 不会失败；兜底返回已构造部分，绝不给模块半截 JSON。
     */
    private static String targetsJson(Context context) {
        final JSONObject json = new JSONObject();
        try {
            json.put("enabled", RouteStore.loadScopeEnabled(context));
            final JSONArray packages = new JSONArray();
            final Set<String> targets = RouteStore.loadTargetPackages(context);
            if (targets != null) {
                for (String pkg : targets) {
                    packages.put(pkg);
                }
            }
            json.put("packages", packages);
        } catch (Throwable ignored) {
            // 与 EnvProvider.targetsCursor 一致：兜底返回已构造部分
        }
        return json.toString();
    }
}
