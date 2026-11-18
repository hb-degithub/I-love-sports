package com.vloc.motion.hidden;

import android.os.SystemClock;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * 快照通道：读主 App 的 <b>world-readable SharedPreferences</b>（LSPosed 官方
 * <b>New XSharedPreferences</b>，API 93+）。
 *
 * <p><b>为什么还需要它</b>：模块跑在 {@code system_server}（uid 1000）里，
 * <ul>
 *   <li>读 {@code content://com.vloc.motion.env/*} → 抛 {@code SecurityException}；</li>
 *   <li>连主 App 的 Unix 抽象套接字（{@link SnapshotClient}）→ SELinux 拒绝
 *       （{@code avc: denied { connectto } scontext=u:r:system_server:s0
 *       tcontext=u:r:untrusted_app_34:s0}）。</li>
 * </ul>
 * 两条跨进程通道都不可用之后，只剩 LSPosed 自己的 prefs 通道：主 App 用
 * {@code MODE_WORLD_READABLE} 写（见 {@code service/SnapshotPrefs}），这里用
 * {@link XSharedPreferences} 读。LSPosed 从 API 93 起会 hook
 * {@code ContextImpl.getPreferencesDir()} 与 {@code checkMode(int)}，把 world-readable
 * prefs 落到模块读得到的随机目录，所以<b>模块的 {@code AndroidManifest.xml} 必须</b>
 * {@code xposedminversion≥93} 且带 {@code xposedsharedprefs=true}，否则
 * {@code XSharedPreferences} 只会去读
 * {@code /data/data/com.vloc.motion/shared_prefs/vloc_snapshot.xml}（system_server 读不到）。
 *
 * <p><b>数据契约</b>（主 App 侧 {@code SnapshotPrefs} 逐字对应）：
 * <pre>
 *   prefs 名  vloc_snapshot
 *   loc       {"active":bool,"lat":..,"lon":..,...}   （与 /location 同款）
 *   gnss      [{"svid":..,"const":..,...}]            （与 /gnss 同款）
 *   targets   {"enabled":bool,"packages":["pkg1",...]}（与 /targets 同款）
 *   socket    vloc-<16 hex>                             （本会话抽象套接字名，每次会话随机）
 * </pre>
 * 解析<b>不在</b>这里做：调用方复用既有的 {@code parseLocationJson} /
 * {@code parseSatellitesJson} / {@code TargetScope.parse()}，保证三条通道语义完全一致。
 *
 * <p><b>日志</b>（每进程每类<b>一条</b>，{@link AtomicBoolean} CAS 去重）：
 * <ul>
 *   <li>成功：{@code snapshot via prefs: <key> ok}</li>
 *   <li>失败：{@code snapshot via prefs failed: <异常类名> (<key>)，fallback}</li>
 * </ul>
 * "文件可读但没有这个 key" = 主 App 还没发布快照（正常），<b>不</b>算失败、不记日志。
 *
 * <p><b>为什么缓存 {@link XSharedPreferences} 实例</b>：它的构造函数<b>每次</b>都要
 * {@code MetaDataReader.getMetaData(apk)}（打开 APK、解析二进制 AndroidManifest）+ 一次
 * {@code serviceClient.getPrefsPath()}（binder 到 LSPosed daemon）。定位投递是 8Hz，
 * 每次都 new 一遍等于每秒 8 次 APK 解析，会拖慢系统进程。所以这里<b>每进程只构造一次</b>，
 * 每次读只调 {@code reload()}（只做一次 stat，文件没变就不解析）——这也正是 LSPosed
 * 文档推荐用法。实例失效（文件读不到 / 构造失败）时按 {@link #RESOLVE_RETRY_MS} 退避重建，
 * 既不会把"文件还没出现"的旧路径永久缓存，也不会每次读都重建。
 *
 * <p>线程安全：投递是多线程并发的，实例用双检锁构造、字段全 volatile；任何 Throwable
 * 都被吞掉，<b>绝不</b>抛出给 host 进程。
 */
final class PrefsClient {

    private static final String TAG = "[VLocHidden] ";

    /** 主 App 包名（同时也是本模块自身，单 APK 双身份）。 */
    static final String MODULE_PACKAGE = "com.vloc.motion";

    /** prefs 名，必须与 {@code service/SnapshotPrefs.PREFS_NAME} 逐字一致。 */
    static final String PREFS_NAME = "vloc_snapshot";

    /** key：定位快照（与 {@code /location} 完全相同的 JSON）。 */
    static final String KEY_LOC = "loc";

    /** key：卫星表（与 {@code /gnss} 相同的 JSON 数组）。 */
    static final String KEY_GNSS = "gnss";

    /** key：作用域配置（与 {@code /targets} 相同的 {@code {"enabled":..,"packages":[..]}}）。 */
    static final String KEY_TARGETS = "targets";

    /**
     * key：本会话的抽象套接字名（{@code vloc-<16 hex>}，纯字符串、无坐标）。
     *
     * <p>必须与 {@code service/SnapshotPrefs.KEY_SOCKET} 逐字一致。{@code SnapshotClient}
     * 用它连接主 App：名字每次会话随机，所以不能写死在代码里。
     */
    static final String KEY_SOCKET = "socket";

    /** 实例失效后的重建退避：既不错过主 App 之后才写出的文件，也不会每次读都重建。 */
    private static final long RESOLVE_RETRY_MS = 5_000L;

    private static final Object LOCK = new Object();

    /** 每进程一个读取实例（构造代价高，见类注释）。 */
    private static volatile XSharedPreferences sPrefs;

    /** 上次构造失败的原因（给失败日志用）；null = 没失败过。 */
    private static volatile Throwable sBuildError;

    /** 允许重建的最早时刻（{@link SystemClock#elapsedRealtime()}）；0 = 不必重建。 */
    private static volatile long sRebuildAt;

    /** 每类结果每进程一条日志（成功 / 失败各一个标记）。 */
    private static final AtomicBoolean sLoggedLocOk = new AtomicBoolean();
    private static final AtomicBoolean sLoggedGnssOk = new AtomicBoolean();
    private static final AtomicBoolean sLoggedTargetsOk = new AtomicBoolean();
    private static final AtomicBoolean sLoggedLocFail = new AtomicBoolean();
    private static final AtomicBoolean sLoggedGnssFail = new AtomicBoolean();
    private static final AtomicBoolean sLoggedTargetsFail = new AtomicBoolean();

    private PrefsClient() {
    }

    /**
     * 读一个 key：{@code XSharedPreferences(pkg, name)} → {@code reload()} → {@code getString(key, null)}。
     *
     * <p>首次调用才构造实例（每进程一次），之后只 {@code reload()}：prefs 本身是几百字节的
     * 内存/文件映射，读取代价远小于一次 binder 调用，不再另加缓存（缓存的是 read 实例，不是值）。
     *
     * @param key {@link #KEY_LOC} / {@link #KEY_GNSS} / {@link #KEY_TARGETS}
     * @return 主 App 写下的字符串；<b>null</b> = 不可用 / 还没有数据（调用方回退下一条通道）
     */
    static String read(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        try {
            final XSharedPreferences prefs = prefs();
            if (prefs == null) {
                // 构造就失败了（旧运行时没有这个类 / APK 元数据读不到）：回退
                noteFailureOnce(key, sBuildError);
                return null;
            }
            // reload() 只做一次 stat，文件 mtime 没变时不会重新解析
            prefs.reload();
            final String value = prefs.getString(key, null);
            if (value != null) {
                noteSuccessOnce(key);
                return value;
            }
            // 没有值：区分"主 App 还没发布"（文件可读）与"机制没启用"（文件根本读不到）
            noteUnreadableOnce(key, prefs);
            return null;
        } catch (Throwable t) {
            // 解析失败 / 权限异常 / 类缺失：记一条失败日志并安排重建，绝不抛出
            noteFailureOnce(key, t);
            scheduleRebuild();
            return null;
        }
    }

    // ================================================================ 实例管理（每进程一次）

    /**
     * 取读取实例：正常路径直接复用；只有"构造失败"或"文件读不到"之后才按退避重建。
     *
     * @return 可用的实例；{@code null} = 本进程既没建成功、也没缓存（调用方回退）
     */
    private static XSharedPreferences prefs() {
        final XSharedPreferences cached = sPrefs;
        if (cached != null) {
            final long rebuildAt = sRebuildAt;
            if (rebuildAt == 0L || SystemClock.elapsedRealtime() < rebuildAt) {
                return cached;   // 命中（绝大多数调用走这里：零构造、零 IPC）
            }
        }
        synchronized (LOCK) {
            final XSharedPreferences current = sPrefs;
            if (current != null) {
                final long rebuildAt = sRebuildAt;
                if (rebuildAt == 0L || SystemClock.elapsedRealtime() < rebuildAt) {
                    return current;   // 双检锁：别的线程刚重建过
                }
            }
            try {
                final XSharedPreferences fresh =
                        new XSharedPreferences(MODULE_PACKAGE, PREFS_NAME);
                sPrefs = fresh;
                sBuildError = null;
                sRebuildAt = 0L;
                return fresh;
            } catch (Throwable t) {
                // 构造失败（例如旧 LSPosed 的 XSharedPreferences 不认识这种用法）：记住原因，
                // 按退避再试；期间继续用旧实例（可能为 null）
                sBuildError = t;
                sRebuildAt = SystemClock.elapsedRealtime() + RESOLVE_RETRY_MS;
                return current;
            }
        }
    }

    /** 安排一次重建（退避 {@link #RESOLVE_RETRY_MS}，不阻塞当前调用）。 */
    private static void scheduleRebuild() {
        try {
            synchronized (LOCK) {
                sRebuildAt = SystemClock.elapsedRealtime() + RESOLVE_RETRY_MS;
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ================================================================ 日志（每进程每类一条）

    /** 成功：本类通道首次真的读到值 —— 现场据此确认"prefs 通道已生效"。 */
    private static void noteSuccessOnce(String key) {
        try {
            final AtomicBoolean flag = okFlag(key);
            if (flag != null && flag.compareAndSet(false, true)) {
                log("snapshot via prefs: " + key + " ok");
            }
        } catch (Throwable ignored) {
            // 日志绝不抛出
        }
    }

    /** 失败（异常路径）：前缀是异常类名，与 SnapshotClient 的日志风格一致。 */
    private static void noteFailureOnce(String key, Throwable t) {
        try {
            final AtomicBoolean flag = failFlag(key);
            if (flag == null || !flag.compareAndSet(false, true)) {
                return;
            }
            final String kind = t == null ? "unknown" : t.getClass().getName();
            log("snapshot via prefs failed: " + kind + " (" + key + ")，fallback");
        } catch (Throwable ignored) {
            // 日志绝不抛出
        }
    }

    /**
     * 读到 null 时的判定：<b>文件不可读</b>才记失败（LSPosed 的 New XSharedPreferences 没启用），
     * 并安排一次重建（主 App 之后才写出文件 / 目录变化时能自愈）。
     *
     * <p>{@code getFile()} 返回 null（个别实现拿不到路径）时无法判定，按"还没发布"处理：
     * 宁可少一条日志，也不误报一个能正常工作的通道。
     */
    private static void noteUnreadableOnce(String key, XSharedPreferences prefs) {
        try {
            final File file = fileOf(prefs);
            if (file == null || file.canRead()) {
                return;   // 拿不到路径 / 文件可读（只是还没写这个 key）：不算失败
            }
            scheduleRebuild();
            final AtomicBoolean flag = failFlag(key);
            if (flag != null && flag.compareAndSet(false, true)) {
                log("snapshot via prefs failed: java.io.FileNotFoundException (" + key
                        + ")，fallback");
            }
        } catch (Throwable ignored) {
            // 判定失败时不记日志：下次读再判
        }
    }

    /** {@code getFile()}：旧框架 / 精简实现可能没有或返回 null，任何异常都返回 null。 */
    private static File fileOf(XSharedPreferences prefs) {
        if (prefs == null) {
            return null;
        }
        try {
            return prefs.getFile();
        } catch (Throwable t) {
            return null;
        }
    }

    /** key → "首成功"日志标记。 */
    private static AtomicBoolean okFlag(String key) {
        if (KEY_LOC.equals(key)) {
            return sLoggedLocOk;
        }
        if (KEY_GNSS.equals(key)) {
            return sLoggedGnssOk;
        }
        if (KEY_TARGETS.equals(key)) {
            return sLoggedTargetsOk;
        }
        return null;
    }

    /** key → "首失败"日志标记。 */
    private static AtomicBoolean failFlag(String key) {
        if (KEY_LOC.equals(key)) {
            return sLoggedLocFail;
        }
        if (KEY_GNSS.equals(key)) {
            return sLoggedGnssFail;
        }
        if (KEY_TARGETS.equals(key)) {
            return sLoggedTargetsFail;
        }
        return null;
    }

    private static void log(String message) {
        try {
            XposedBridge.log(TAG + message);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
