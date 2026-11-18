package com.vloc.motion.hidden;

import android.content.SharedPreferences;
import android.os.SystemClock;

import com.vloc.motion.hidden.compat.Xp;

import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * {@code MODE_WORLD_READABLE} 写（见 {@code service/SnapshotPrefs}），这里通过
 * {@link Xp#getPrefs(String)} 读 —— 旧后端内部就是 LSPosed 的 New XSharedPreferences，
 * 新后端是 {@code getRemotePreferences}。LSPosed 从 API 93 起会 hook
 * {@code ContextImpl.getPreferencesDir()} 与 {@code checkMode(int)}，把 world-readable
 * prefs 落到模块读得到的随机目录，所以<b>模块的 {@code AndroidManifest.xml} 必须</b>
 * {@code xposedminversion≥93} 且带 {@code xposedsharedprefs=true}，否则该通道只会去读
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
 * <p><b>为什么缓存 prefs 实例</b>：旧后端的构造<b>每次</b>都要
 * {@code MetaDataReader.getMetaData(apk)}（打开 APK、解析二进制 AndroidManifest）+ 一次
 * {@code serviceClient.getPrefsPath()}（binder 到 LSPosed daemon）。定位投递是 8Hz，
 * 每次都 new 一遍等于每秒 8 次 APK 解析，会拖慢系统进程。所以这里<b>每进程只构造一次</b>，
 * 每次读只 {@code reload()}（只做一次 stat，文件没变就不解析）——这也正是 LSPosed
 * 文档推荐用法。实例失效（拿不到实例 / 构造失败）时按 {@link #RESOLVE_RETRY_MS} 退避重建，
 * 既不会把"文件还没出现"的旧路径永久缓存，也不会每次读都重建。
 *
 * <p><b>为什么 reload()/getFile() 走反射</b>：{@link Xp#getPrefs(String)} 的返回类型是标准
 * {@link SharedPreferences}，而 {@code reload()}（增量重载）与 {@code getFile()}（诊断用）
 * 是旧实现类自己的方法；新后端的远程偏好是只读实时视图，本来就不需要 reload。
 * 于是这里按"有就调、没有就跳过"处理，两个后端共用同一份代码。
 *
 * <p>线程安全：投递是多线程并发的，实例用双检锁构造、字段全 volatile；任何 Throwable
 * 都被吞掉，<b>绝不</b>抛出给 host 进程。
 */
final class PrefsClient {

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

    /** 通道永久不可用（后端明确不支持 / 链接失败）：之后每次读直接回退，不再尝试构造。 */
    private static volatile boolean sUnavailable;

    /** “通道不可用”日志每进程只打一条。 */
    private static final AtomicBoolean sLoggedUnavailable = new AtomicBoolean();

    /** 每进程一个读取实例（构造代价高，见类注释）。 */
    private static volatile SharedPreferences sPrefs;

    /** 上次构造失败的原因（给失败日志用）；null = 没失败过。 */
    private static volatile Throwable sBuildError;

    /** 允许重建的最早时刻（{@link SystemClock#elapsedRealtime()}）；0 = 不必重建。 */
    private static volatile long sRebuildAt;

    // ---- reload() / getFile() 的反射句柄（每进程探测一次，见类注释） ----

    private static volatile Method sReloadMethod;
    private static volatile boolean sReloadProbed;
    private static volatile Method sGetFileMethod;
    private static volatile boolean sGetFileProbed;

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
     * 读一个 key：{@code Xp.getPrefs("vloc_snapshot")} → {@code reload()} → {@code getString(key, null)}。
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
            final SharedPreferences prefs = prefs();
            if (prefs == null) {
                // 构造就失败了（后端不支持远程偏好 / APK 元数据读不到）：回退
                noteFailureOnce(key, sBuildError);
                return null;
            }
            // reload() 只做一次 stat，文件 mtime 没变时不会重新解析（新后端没有这个方法，跳过）
            reloadIfSupported(prefs);
            final String value = prefs.getString(key, null);
            if (value != null) {
                noteSuccessOnce(key);
                return value;
            }
            // 没有值：区分"主 App 还没发布"（文件可读）与"机制没启用"（文件根本读不到）
            noteUnreadableOnce(key, prefs);
            return null;
        } catch (NoClassDefFoundError e) {
            // 链接期缺失（Error，不是 Exception）：后端依赖的类没有 ——
            // 永久降级到下一通道，绝不抛给宿主进程。
            sUnavailable = true;
            noteUnavailableOnce(e);
            noteFailureOnce(key, e);
            return null;
        } catch (Throwable t) {
            // 解析失败 / 权限异常 / 类型不符：记一条失败日志并安排重建，绝不抛出
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
    private static SharedPreferences prefs() {
        final SharedPreferences cached = sPrefs;
        if (cached != null) {
            final long rebuildAt = sRebuildAt;
            if (rebuildAt == 0L || SystemClock.elapsedRealtime() < rebuildAt) {
                return cached;   // 命中（绝大多数调用走这里：零构造、零 IPC）
            }
        }
        synchronized (LOCK) {
            final SharedPreferences current = sPrefs;
            if (current != null) {
                final long rebuildAt = sRebuildAt;
                if (rebuildAt == 0L || SystemClock.elapsedRealtime() < rebuildAt) {
                    return current;   // 双检锁：别的线程刚重建过
                }
            }
            if (sUnavailable) {
                return current;   // 已判定不可用：永久降级，不再尝试构造
            }
            try {
                // 旧后端 = new XSharedPreferences(模块包名, name)；新后端 = getRemotePreferences(name)
                final SharedPreferences fresh = Xp.getPrefs(PREFS_NAME);
                if (fresh == null) {
                    // 后端明确表示"本进程拿不到远程偏好"（例如框架没有 PROP_CAP_REMOTE）：
                    // 与迁移前"类缺失"同一处理 —— 永久降级，只在第一次读时记一条日志
                    sUnavailable = true;
                    sBuildError = null;
                    noteUnavailableOnce(null);
                    return current;
                }
                sPrefs = fresh;
                sBuildError = null;
                sRebuildAt = 0L;
                return fresh;
            } catch (NoClassDefFoundError e) {
                // 构造时才暴露依赖缺失（同样是 Error）：永久降级
                sUnavailable = true;
                sBuildError = e;
                noteUnavailableOnce(e);
                return current;
            } catch (Throwable t) {
                // 构造失败（例如旧 LSPosed 的 prefs 实现不认识这种用法）：记住原因，
                // 按退避再试；期间继续用旧实例（可能为 null）
                sBuildError = t;
                sRebuildAt = SystemClock.elapsedRealtime() + RESOLVE_RETRY_MS;
                return current;
            }
        }
    }

    /**
     * 旧后端的 {@code XSharedPreferences#reload()}：只做一次 stat，文件没变就不重新解析。
     *
     * <p>新后端（{@code getRemotePreferences}）没有这个方法，探测结果为 null 后直接跳过；
     * 每进程只探测一次，高频投递路径上零反射开销。
     */
    private static void reloadIfSupported(SharedPreferences prefs) {
        Method reload = sReloadMethod;
        if (reload == null) {
            if (sReloadProbed) {
                return;   // 已确认没有 reload()：跳过（新后端）
            }
            reload = noArgMethod(prefs, "reload");
            sReloadProbed = true;
            sReloadMethod = reload;
            if (reload == null) {
                return;
            }
        }
        try {
            reload.invoke(prefs);
        } catch (Throwable ignored) {
            // reload 失败不能影响读取：下次再试（getString 仍可能拿到缓存值）
        }
    }

    /** 取实例上与迁移前逐字同名的无参方法；没有则返回 null（绝不影响主流程）。 */
    private static Method noArgMethod(SharedPreferences prefs, String name) {
        if (prefs == null) {
            return null;
        }
        try {
            final Method method = prefs.getClass().getMethod(name);
            return method;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 通道永久不可用：每进程一条日志（不刷屏），之后所有读取直接回退。 */
    private static void noteUnavailableOnce(Throwable t) {
        try {
            if (sLoggedUnavailable.compareAndSet(false, true)) {
                final String kind = t == null ? "unsupported by backend" : t.getClass().getName();
                log("snapshot via prefs unavailable: " + kind + " (Xp.getPrefs(" + PREFS_NAME
                        + "))，channel disabled, fallback");
            }
        } catch (Throwable ignored) {
            // 日志绝不抛出
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
     * <p>{@code getFile()} 返回 null（个别实现拿不到路径 / 新后端没有这个方法）时无法判定，
     * 按"还没发布"处理：宁可少一条日志，也不误报一个能正常工作的通道。
     */
    private static void noteUnreadableOnce(String key, SharedPreferences prefs) {
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

    /** 旧后端的 {@code getFile()}（新后端没有）：任何异常 / 缺失都返回 null。 */
    private static File fileOf(SharedPreferences prefs) {
        if (prefs == null) {
            return null;
        }
        try {
            Method getFile = sGetFileMethod;
            if (getFile == null) {
                if (sGetFileProbed) {
                    return null;
                }
                getFile = noArgMethod(prefs, "getFile");
                sGetFileProbed = true;
                sGetFileMethod = getFile;
                if (getFile == null) {
                    return null;
                }
            }
            final Object value = getFile.invoke(prefs);
            return value instanceof File ? (File) value : null;
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

    /** 统一日志：{@code [VLocHidden] } 前缀由 {@link Xp} 后端统一加。 */
    private static void log(String message) {
        try {
            Xp.log(message);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
