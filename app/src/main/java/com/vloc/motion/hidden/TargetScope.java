package com.vloc.motion.hidden;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 作用域名单：把「系统级注入对所有 App 生效」收窄成「只对用户选定的 App 生效」。
 *
 * <p>数据契约（主 App 侧实现，见 {@code EnvProvider#PATH_TARGETS} 与
 * {@code service/SnapshotPrefs#KEY_TARGETS}）：
 * <pre>
 * world-readable prefs（{@code vloc_snapshot} 的 {@code targets} 键）     或
 * 套接字（Unix 抽象，名字见 prefs 的 {@code socket} key，请求 {@code TARGETS}）  或
 * content://com.vloc.motion.env/targets  →  单列 {@code json}、单行
 * {"enabled":true,"packages":["com.a","com.b"]}
 * </pre>
 * 三条通道内容一致，解析逻辑共用一份：<b>prefs 优先</b>（LSPosed New XSharedPreferences，
 * 不带 uid 限制）→ 套接字 → Provider（system_server 里读 Provider 会抛 SecurityException，
 * 只作兜底）。
 * <ul>
 *   <li>{@code enabled=false}：不做限制，保持旧行为（全机所有进程生效）；</li>
 *   <li>{@code enabled=true}：只有 {@code packages} 里的包名会被安装 hook / 改写定位；</li>
 *   <li>读不到 / 解析失败 / 拿不到 Context / 查不到接收方包名：<b>一律放行</b>
 *       （宁可少拦，也不把用户锁死在“无法判定就什么都不生效”的状态），每个进程只记一条日志。</li>
 * </ul>
 *
 * <p>性能：定位投递是高频路径（10Hz × N 个注册者），所以整份配置按 {@link #TTL_MS} 缓存，
 * 过期才走一次通道（prefs 优先 → 套接字 → 跨进程 query Provider）；判定本身只是一次 HashSet 查找。
 * 套接字单次交换总超时 ≤300ms，且只在缓存过期、且 prefs 不可用时才会发生。
 *
 * <p>线程安全：投递可能在多个 binder / handler 线程并发触发，缓存的读写用
 * {@code volatile + 双检锁}，uid→包名表用一把小锁保护。
 */
final class TargetScope {

    private static final String TAG = "[VLocHidden] ";

    /** 主 App 的下发内容 URI（另一列是 {@code json}）。 */
    private static final String TARGETS_URI = "content://com.vloc.motion.env/targets";
    private static final String COLUMN_JSON = "json";

    /** 配置缓存有效期：读成功 30s，读失败 10s（好让主 App 改完能较快被看到）。 */
    private static final long TTL_MS = 30_000L;
    private static final long TTL_FAIL_MS = 10_000L;

    /** uid→包名缓存上限（超过就整体清空，避免 system_server 里无界增长）。 */
    private static final int UID_CACHE_MAX = 256;

    private static final Object READ_LOCK = new Object();
    private static final Object UID_LOCK = new Object();
    private static final Map<Integer, String[]> UID_PACKAGES = new HashMap<>();

    /** 当前生效的配置；null = 还没读到 / 读失败（行为上等同放行）。 */
    private static volatile Scope sScope;
    /** 缓存到期时间（SystemClock.elapsedRealtime，单调时钟，不受改时间/对时影响）。 */
    private static volatile long sExpiresAt;

    private static volatile Context sContext;

    private static volatile boolean sLoggedFirstRead;
    private static volatile boolean sLoggedNoContext;
    private static volatile boolean sLoggedReadFail;
    private static volatile boolean sLoggedParseFail;
    private static volatile boolean sLoggedUnresolvedTarget;
    private static volatile boolean sLoggedUidFail;

    private TargetScope() {
    }

    /** 一份配置快照（不可变）。 */
    private static final class Scope {
        final boolean enabled;
        final Set<String> packages;

        Scope(boolean enabled, Set<String> packages) {
            this.enabled = enabled;
            this.packages = packages;
        }
    }

    // ================================================================ 判定入口

    /**
     * 目标进程内（{@link HookEntry}）的准入判定：是否给这个包名安装 hook。
     *
     * @param packageName 进程所属包名（非空；由 LSPosed 提供）
     * @return true = 允许安装 hook（未启用名单 / 在名单内 / 读不到名单的兜底）
     */
    static boolean isAllowed(String packageName) {
        final Scope scope = current();
        if (scope == null || !scope.enabled) {
            return true;
        }
        if (packageName == null || packageName.isEmpty()) {
            logUnresolvedTarget("packageName is empty");
            return true;
        }
        if (scope.packages.contains(packageName)) {
            return true;
        }
        // 有的进程名是 "pkg:remote" 形式；允许按前缀匹配，避免用户只勾一个包却漏掉子进程
        final int colon = packageName.indexOf(':');
        return colon > 0 && scope.packages.contains(packageName.substring(0, colon));
    }

    /**
     * 是否开启了作用域限制。未开启 = 保持旧行为（对所有进程生效）；
     * 读不到配置时也返回 false（放行），避免把用户锁死。
     */
    static boolean isScoped() {
        final Scope scope = current();
        return scope != null && scope.enabled;
    }

    /**
     * 系统层投递（{@link SystemHooks} 的 {@code deliverOnLocationChanged}）的准入判定。
     *
     * @param packageName 本次投递的接收方包名；<b>null 表示反查不到</b>
     * @return true = 改写这一次投递；false = 不在名单内，透传真实定位
     */
    static boolean allowsDelivery(String packageName) {
        final Scope scope = current();
        if (scope == null || !scope.enabled) {
            return true;
        }
        if (packageName == null || packageName.isEmpty()) {
            // 字段改名 / 类型不符 / 查表未命中：按“保持现状”处理，只记一次
            logUnresolvedTarget("cannot resolve target pkg");
            return true;
        }
        return scope.packages.contains(packageName);
    }

    /**
     * 用 Binder 调用方 uid 判名单，供 system_server 里“不经过投递链”的同步方法
     * （如 {@code getLastLocation}）使用：这类方法参数里虽然有 packageName，
     * 但不同版本位置不一致，用 {@code Binder.getCallingUid()} 反查更可靠。
     *
     * @return true = 应该改写这一次调用的结果
     */
    static boolean allowsCaller() {
        final Scope scope = current();
        if (scope == null || !scope.enabled) {
            return true;
        }
        final int uid;
        try {
            uid = Binder.getCallingUid();
        } catch (Throwable ignored) {
            return true;
        }
        if (uid <= 0) {
            // 非 binder 线程（如 system_server 自己的 handler 线程）→ 无法判定，保持现状
            logUnresolvedTarget("calling uid unavailable");
            return true;
        }
        final String[] packages = packagesForUid(uid);
        if (packages == null) {
            logUidFail(uid);
            return true;
        }
        for (String pkg : packages) {
            if (pkg != null && scope.packages.contains(pkg)) {
                return true;
            }
        }
        return false;
    }

    /** 供日志排查：当前配置的一句话摘要（会触发一次（带缓存的）读取）。 */
    static String summary() {
        final Scope scope = current();
        if (scope == null) {
            return "unavailable -> allow all (prefs + socket + " + TARGETS_URI + " all failed)";
        }
        if (!scope.enabled) {
            return "disabled -> all apps (enabled=false)";
        }
        return "enabled -> " + scope.packages.size() + " app(s) " + scope.packages;
    }

    // ================================================================ 配置读取（带缓存）

    /** 取当前配置；过期则（加锁）重新读一次。读不到返回 null（= 放行）。 */
    private static Scope current() {
        final long now = SystemClock.elapsedRealtime();
        if (now < sExpiresAt) {
            return sScope;
        }
        synchronized (READ_LOCK) {
            if (now < sExpiresAt) {
                return sScope;
            }
            Scope scope = null;
            long ttl = TTL_MS;
            try {
                scope = read();
                if (scope == null) {
                    ttl = TTL_FAIL_MS;
                }
            } catch (Throwable t) {
                ttl = TTL_FAIL_MS;
                logReadFail(t);
            }
            sScope = scope;
            sExpiresAt = SystemClock.elapsedRealtime() + ttl;
            return scope;
        }
    }

    /**
     * 读一次作用域配置：<b>prefs → 套接字 → ContentProvider</b>；任何一步失败都返回 null（= 放行）。
     *
     * <p>顺序与原因：
     * <ol>
     *   <li><b>world-readable prefs</b>（{@link PrefsClient#KEY_TARGETS}，LSPosed New
     *       XSharedPreferences）：不带调用方 uid 限制，是 system_server 里唯一可用的通道；</li>
     *   <li>{@code TARGETS} → Unix 抽象套接字（{@link SnapshotClient}）：旧的回退路径
     *       （本 ROM 上 system_server 连它会被 SELinux 拒）；</li>
     *   <li>原来的 Provider 路径（{@link #readProvider()}），行为不变。</li>
     * </ol>
     * 三条通道共用 {@link #parse(String)}，enabled / packages 的语义完全一致；
     * TTL 缓存（{@link #TTL_MS} / {@link #TTL_FAIL_MS}）与判定逻辑都不变。
     */
    private static Scope read() {
        // 1) prefs：会话期间主 App 每 1s 检查一次，只在名单变化时重写
        final String prefs = PrefsClient.read(PrefsClient.KEY_TARGETS);
        if (prefs != null && !prefs.isEmpty()) {
            final Scope scope = parse(prefs);
            if (scope != null) {
                return scope;
            }
            // prefs 有内容但解析失败（主 App 正在升级 / 契约不符）：继续回退
        }
        // 2) 套接字
        final String line = SnapshotClient.readLine(SnapshotClient.REQ_TARGETS,
                SnapshotClient.DEFAULT_TIMEOUT_MS);
        if (line != null && !line.isEmpty()) {
            final Scope scope = parse(line);
            if (scope != null) {
                return scope;
            }
            // 套接字有内容但解析失败（主 App 正在升级 / 契约不符）：再用 Provider 兜一次
        }
        // 3) Provider
        return readProvider();
    }

    /** 解析 {@code {"enabled":bool,"packages":[...]}}；失败记一条日志并返回 null。 */
    private static Scope parse(String raw) {
        final JSONObject root;
        try {
            root = new JSONObject(raw);
        } catch (Throwable t) {
            logParseFail(t);
            return null;
        }
        final boolean enabled = root.optBoolean("enabled", false);
        final Set<String> packages = new HashSet<>();
        final JSONArray array = root.optJSONArray("packages");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                final String pkg = array.optString(i, null);
                if (pkg != null && !pkg.trim().isEmpty()) {
                    packages.add(pkg.trim());
                }
            }
        }
        final Scope scope = new Scope(enabled, Collections.unmodifiableSet(packages));
        if (!sLoggedFirstRead) {
            sLoggedFirstRead = true;
            log("target scope: enabled=" + enabled + " packages=" + packages.size());
        }
        return scope;
    }

    /**
     * 兜底通道：主 App 的 ContentProvider（原逻辑，行为不变）。
     *
     * <p>Android 17 的 system_server 里这条路会抛 SecurityException（由 {@link #current()} 兜住），
     * 只在套接字不可用时才会走到。
     */
    private static Scope readProvider() {
        final Context context = context();
        if (context == null) {
            logNoContext();
            return null;
        }
        final ContentResolver resolver = context.getContentResolver();
        if (resolver == null) {
            logNoContext();
            return null;
        }
        Cursor cursor = null;
        try {
            cursor = resolver.query(Uri.parse(TARGETS_URI), new String[]{COLUMN_JSON},
                    null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                logReadFail("empty cursor");
                return null;
            }
            int column = cursor.getColumnIndex(COLUMN_JSON);
            if (column < 0) {
                column = 0;   // 列名不符时退化成“第一列”，契约只有一列
            }
            final String raw = cursor.getString(column);
            if (raw == null || raw.isEmpty()) {
                logReadFail("empty json");
                return null;
            }
            return parse(raw);
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

    /**
     * 拿一个可用的 Context。
     *
     * <p>顺序：Application（app 进程 attach 之后才有）→ AndroidAppHelper →
     * 当前 ActivityThread 的 system context（{@code handleLoadPackage} 早期、
     * system_server 里都拿得到，主 App 的 Provider 解析不需要 Application 级别的东西）。
     */
    private static Context context() {
        Context context = sContext;
        if (context != null) {
            return context;
        }
        context = callStaticContext("android.app.ActivityThread", "currentApplication");
        if (context == null) {
            context = callStaticContext("android.app.AndroidAppHelper", "currentApplication");
        }
        if (context == null) {
            try {
                final Class<?> activityThread = Class.forName("android.app.ActivityThread");
                final Object thread = XposedHelpers.callStaticMethod(activityThread, "currentActivityThread");
                if (thread != null) {
                    final Object value = XposedHelpers.callMethod(thread, "getSystemContext");
                    if (value instanceof Context) {
                        context = (Context) value;
                    }
                }
            } catch (Throwable ignored) {
                // 拿不到就走“放行 + 记日志”
            }
        }
        if (context != null) {
            sContext = context;
        }
        return context;
    }

    private static Context callStaticContext(String className, String methodName) {
        try {
            final Class<?> clazz = XposedHelpers.findClassIfExists(className,
                    TargetScope.class.getClassLoader());
            if (clazz == null) {
                return null;
            }
            final Object value = XposedHelpers.callStaticMethod(clazz, methodName);
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ================================================================ uid → 包名

    /**
     * uid → 该 uid 下的全部包名（sharedUserId / 多包同 uid 会有多个）。
     * 在 system_server 里走 {@code AppGlobals.getPackageManager()}，是进程内调用，无额外 IPC 开销。
     *
     * @return null = 解析失败（调用方放行）
     */
    private static String[] packagesForUid(int uid) {
        synchronized (UID_LOCK) {
            final String[] cached = UID_PACKAGES.get(uid);
            if (cached != null) {
                return cached.length == 0 ? null : cached;
            }
        }
        String[] result = null;
        try {
            final Class<?> appGlobals = Class.forName("android.app.AppGlobals");
            final Object packageManager = XposedHelpers.callStaticMethod(appGlobals, "getPackageManager");
            if (packageManager != null) {
                final Object value = XposedHelpers.callMethod(packageManager, "getPackagesForUid", uid);
                if (value instanceof String[]) {
                    result = (String[]) value;
                }
            }
        } catch (Throwable ignored) {
            // 解析失败：调用方放行
        }
        synchronized (UID_LOCK) {
            if (UID_PACKAGES.size() >= UID_CACHE_MAX) {
                UID_PACKAGES.clear();
            }
            UID_PACKAGES.put(uid, result == null ? new String[0] : result);
        }
        return result;
    }

    // ================================================================ 日志（每进程一次）

    private static void logUnresolvedTarget(String why) {
        if (sLoggedUnresolvedTarget) {
            return;
        }
        sLoggedUnresolvedTarget = true;
        log("target scope: cannot resolve target pkg, applying globally (" + why + ")");
    }

    private static void logNoContext() {
        if (sLoggedNoContext) {
            return;
        }
        sLoggedNoContext = true;
        log("target scope: no context to read " + TARGETS_URI + ", applying to all apps");
    }

    private static void logReadFail(Object why) {
        if (sLoggedReadFail) {
            return;
        }
        sLoggedReadFail = true;
        log("target scope: read failed, applying to all apps (" + why + ")");
    }

    private static void logParseFail(Object why) {
        if (sLoggedParseFail) {
            return;
        }
        sLoggedParseFail = true;
        log("target scope: parse failed, applying to all apps (" + why + ")");
    }

    private static void logUidFail(int uid) {
        if (sLoggedUidFail) {
            return;
        }
        sLoggedUidFail = true;
        log("target scope: cannot resolve packages for uid " + uid + ", applying globally");
    }

    private static void log(String message) {
        try {
            XposedBridge.log(TAG + message);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
