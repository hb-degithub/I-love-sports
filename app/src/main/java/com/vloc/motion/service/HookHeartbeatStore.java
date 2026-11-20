package com.vloc.motion.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * 模块心跳存储：模块在**每个被注入的进程**里向 {@code content://com.vloc.motion.env/hookbeat}
 * {@code insert} 一条心跳（{@code process / hooks / kind / at / sdk}，外加可选的
 * {@code gnss}），这里是它的落地层。
 *
 * <p>为什么需要它：App 以前用「LSPosed 管理器包名是否可见」判断模块是否生效，用户装了
 * HideMyAppList 这类隐藏模块后包名查不到，于是误报"框架没有生效"——而模块其实已经注入。
 * 心跳由模块自己上报，隐藏模块管不到，才是模块侧的真信号。
 *
 * <p>存储：独立 prefs 文件 {@code vloc_hookbeat}，一个 key 存整份 JSON
 * {@code {进程名: {hooks, kind, at, sdk, gnss}}}，按 {@code process} 覆盖写。
 * {@code gnss} 是<b>可选</b>字段（老版本模块不带；{@code null} = 该进程还没确定结果 /
 * 不是 GNSS 注入进程），读取侧一律按"缺失 = null"兼容。
 *
 * <p>性能约束（模块在 system_server / 目标进程启动路径上调用）：只读写 prefs，
 * 不做磁盘扫描、不查 PackageManager；写入用 {@code apply()}。
 */
public final class HookHeartbeatStore {

    /** 心跳"新鲜"窗口：超过这个时间的系统框架心跳不再当作存活证据（24 小时）。 */
    public static final long FRESH_MS = 24L * 60L * 60L * 1000L;

    public static final String KIND_SYSTEM = "system";
    public static final String KIND_APP = "app";

    /** 独立 prefs：心跳写入频繁，混进 {@code vloc_prefs} 会连用户设置一起刷盘。 */
    private static final String PREFS = "vloc_hookbeat";
    private static final String KEY_PROCESSES = "processes";

    /** 最多保留的进程条目（异常/恶意输入不至于把 prefs 撑大）。 */
    static final int MAX_PROCESSES = 32;
    /** 单个进程最多记多少 hook（挡住离谱数值，不影响正常量级）。 */
    static final int MAX_HOOKS = 9999;
    /** 超过这个时长没再上报过的进程直接丢弃（7 天）。 */
    private static final long PRUNE_MS = 7L * 24L * 60L * 60L * 1000L;
    /** 允许的未来时间偏差（模块与本机时钟可能有几秒到几分钟误差）。 */
    static final long MAX_CLOCK_SKEW_MS = 10L * 60L * 1000L;
    /** {@code at} 的合理下限（2020-01-01），挡住 0 / 负数 / 明显错乱的时钟。 */
    static final long MIN_AT_MS = 1_577_836_800_000L;

    /** 进程名长度上限：只做合法性校验，不做格式强校验。 */
    static final int MAX_PROCESS_LEN = 200;

    /** {@code gnss} 字段的合法取值（与模块侧 {@code SystemHooks} / 设置面板逐字对应）。 */
    public static final String GNSS_BUILT_BUILDER = "built:builder";
    public static final String GNSS_BUILT_CTOR = "built:ctor";
    public static final String GNSS_BUILT_UNSAFE = "built:unsafe";
    public static final String GNSS_FORCE_MUTATED = "force:mutated";
    public static final String GNSS_SKIP_NO_SATELLITES = "skip:no-satellites";
    public static final String GNSS_SKIP_NULL = "skip:null";

    /** {@code gnss} 字段长度上限：只做防御（面板会原样显示，不让脏数据撑爆界面）。 */
    static final int MAX_GNSS_LEN = 40;

    /** 全部合法取值（{@link #normalizeGnss(String)} 的白名单）。 */
    private static final String[] GNSS_VALUES = {
            GNSS_BUILT_BUILDER, GNSS_BUILT_CTOR, GNSS_BUILT_UNSAFE,
            GNSS_FORCE_MUTATED, GNSS_SKIP_NO_SATELLITES, GNSS_SKIP_NULL,
    };

    /** 读-改-写序列化：insert 走 Binder 线程，Dialog/Service 可能同时在读。 */
    private static final Object LOCK = new Object();

    private HookHeartbeatStore() {
    }

    /** 一次心跳，字段与模块 insert 的 ContentValues 一一对应。 */
    public static final class Beat {
        public final String process;
        public final int hooks;
        public final String kind;
        /** 模块上报的时间戳（毫秒）。 */
        public final long at;
        public final int sdk;
        /**
         * GNSS 注入结果；{@code null} = 模块没上报（老版本）/ 该进程还没确定。
         *
         * <p>取值见 {@code GNSS_*} 常量；设置面板的「卫星表注入」一行由它决定。
         */
        public final String gnss;

        Beat(String process, int hooks, String kind, long at, int sdk, String gnss) {
            this.process = process;
            this.hooks = hooks;
            this.kind = kind;
            this.at = at;
            this.sdk = sdk;
            this.gnss = gnss;
        }

        public boolean isSystem() {
            return KIND_SYSTEM.equals(kind);
        }
    }

    // ------------------------------------------------------------------ 写入

    /**
     * 记一次心跳（按 {@code process} 覆盖；{@code at} 用最新值）。
     *
     * @return false 表示字段不合法被丢弃（调用方返回 null，不抛异常）
     */
    public static boolean record(Context context, String process, int hooks, String kind,
                                 long at, int sdk) {
        return record(context, process, hooks, kind, at, sdk, null);
    }

    /**
     * 记一次心跳（带可选的 GNSS 结果）。
     *
     * @param gnss {@code built:builder} / {@code built:ctor} / {@code built:unsafe} /
     *             {@code force:mutated} / {@code skip:no-satellites} / {@code skip:null}；
     *             {@code null} 或无法识别 → 不写该字段（读取侧得到 {@code null}）
     * @return false 表示字段不合法被丢弃（调用方返回 null，不抛异常）
     */
    public static boolean record(Context context, String process, int hooks, String kind,
                                 long at, int sdk, String gnss) {
        if (context == null || !isValidProcess(process)) {
            return false;
        }
        final long now = System.currentTimeMillis();
        if (!isValidAt(at, now)) {
            return false;
        }
        final String name = process.trim();
        final String normKind = normalizeKind(kind);
        if (normKind == null) {
            return false;
        }
        final int safeHooks = Math.max(0, Math.min(hooks, MAX_HOOKS));
        final int safeSdk = sdk > 0 ? sdk : Build.VERSION.SDK_INT;
        // gnss 可选：白名单外的值一律当"没上报"（Provider 对外暴露，不能把任意字符串存进面板）
        final String normGnss = normalizeGnss(gnss);
        synchronized (LOCK) {
            JSONObject all = load(context);
            try {
                JSONObject entry = new JSONObject();
                entry.put("hooks", safeHooks);
                entry.put("kind", normKind);
                entry.put("at", at);
                entry.put("sdk", safeSdk);
                if (normGnss != null) {
                    entry.put("gnss", normGnss);
                }
                all.put(name, entry);
            } catch (JSONException e) {
                return false;   // 已知 key + 基本类型不会失败，兜底
            }
            prune(all, now);
            prefs(context).edit().putString(KEY_PROCESSES, all.toString()).apply();
        }
        return true;
    }

    // ------------------------------------------------------------------ 读取

    /**
     * 全部心跳的 JSON 字符串（{@code query(PATH_HOOKBEAT)} 直接下发它）。
     *
     * <p>每次返回新构造的副本，调用方改了也不会影响 prefs。空存储返回 {@code "{}"}。
     */
    public static String snapshotJson(Context context) {
        if (context == null) {
            return "{}";
        }
        return load(context).toString();
    }

    /** 全部心跳，按 {@code at} 从新到旧排序。 */
    public static List<Beat> beats(Context context) {
        List<Beat> out = new ArrayList<>();
        if (context == null) {
            return out;
        }
        JSONObject all = load(context);
        Iterator<String> it = all.keys();
        while (it.hasNext()) {
            String name = it.next();
            JSONObject entry = all.optJSONObject(name);
            if (entry == null) {
                continue;
            }
            Beat beat = toBeat(name, entry);
            if (beat != null) {
                out.add(beat);
            }
        }
        Collections.sort(out, new Comparator<Beat>() {
            @Override
            public int compare(Beat a, Beat b) {
                return Long.compare(b.at, a.at);
            }
        });
        return out;
    }

    /**
     * 最新的「系统框架」心跳（{@code kind=system}、{@code at} 在 {@code maxAgeMs} 内）。
     *
     * <p>选择优先级（因为「系统级注入」真正要的是 system_server 里的 hook）：
     * <ol>
     *   <li>system_server 进程（{@code android} / {@code system_server}）且 SDK 一致；</li>
     *   <li>system_server 进程（SDK 不一致也可）；</li>
     *   <li>其它 {@code kind=system} 进程（如 {@code com.android.location.fused}）且 SDK 一致；</li>
     *   <li>任意的 {@code kind=system} 心跳（最新）。</li>
     * </ol>
     *
     * <p>为什么还要第 3、4 档：LSPosed 的「系统框架」作用域会让模块进到多个 uid 1000 进程，
     * 只有 {@code com.android.location.fused} 有心跳时也不能说"模块没生效"；但那种心跳
     * 只能证明"某处注入了"，不能证明 system_server 的 hook 装上了，所以排在后面，
     * 调用方在日志里能看出证据来自哪个进程。
     *
     * @param sdkPreference 当前 {@code Build.VERSION.SDK_INT}；&lt;=0 表示不区分
     * @return 没有符合条件的返回 null
     */
    public static Beat freshestSystem(Context context, long maxAgeMs, int sdkPreference) {
        final long now = System.currentTimeMillis();
        Beat bestAny = null;
        Beat bestSdk = null;
        Beat bestCore = null;
        Beat bestCoreSdk = null;
        for (Beat beat : beats(context)) {
            if (!beat.isSystem()) {
                continue;
            }
            long age = now - beat.at;
            if (age < 0L) {
                age = 0L;   // 时钟偏差：算作刚刚，不当作过期
            }
            if (age > maxAgeMs) {
                continue;
            }
            final boolean sdkMatch = sdkPreference > 0 && beat.sdk == sdkPreference;
            final boolean core = isSystemServerProcess(beat.process);
            if (bestAny == null) {
                bestAny = beat;     // beats 已按 at 降序，第一条就是最新的
            }
            if (sdkMatch && bestSdk == null) {
                bestSdk = beat;
            }
            if (core && bestCore == null) {
                bestCore = beat;
            }
            if (core && sdkMatch && bestCoreSdk == null) {
                bestCoreSdk = beat;
            }
        }
        if (bestCoreSdk != null) {
            return bestCoreSdk;
        }
        if (bestCore != null) {
            return bestCore;
        }
        if (bestSdk != null) {
            return bestSdk;
        }
        return bestAny;
    }

    /** system_server 的进程名：LSPosed 里「系统框架」注入的就是它（{@code android} 包）。 */
    public static boolean isSystemServerProcess(String process) {
        if (process == null) {
            return false;
        }
        String name = process.trim();
        return "android".equals(name) || "system_server".equals(name);
    }

    // ------------------------------------------------------------------ 校验

    /** 进程名合法性：非空、去空白后仍非空、长度 &lt; {@link #MAX_PROCESS_LEN}、无控制字符。 */
    public static boolean isValidProcess(String process) {
        if (process == null) {
            return false;
        }
        String name = process.trim();
        if (name.isEmpty() || name.length() >= MAX_PROCESS_LEN) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch < 0x20 || ch == 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** {@code at} 合理性：不早于 2020-01-01，且不早于"现在 + 10 分钟"。 */
    public static boolean isValidAt(long at, long now) {
        return at >= MIN_AT_MS && at <= now + MAX_CLOCK_SKEW_MS;
    }

    /** 归一化 kind：只认 {@code system} / {@code app}（大小写不敏感），其余返回 null。 */
    static String normalizeKind(String kind) {
        if (kind == null) {
            return null;
        }
        String value = kind.trim();
        if (KIND_SYSTEM.equalsIgnoreCase(value)) {
            return KIND_SYSTEM;
        }
        if (KIND_APP.equalsIgnoreCase(value)) {
            return KIND_APP;
        }
        return null;
    }

    /**
     * 归一化 {@code gnss}：只认 6 个已知值（大小写不敏感），其余一律返回 {@code null}。
     *
     * <p><b>为什么用白名单</b>：{@code content://com.vloc.motion.env/hookbeat} 是 exported
     * Provider，任何应用都能 insert；设置面板会把这段文本原样显示给用户，所以只接受已知枚举，
     * 避免被塞进任意内容（同时也挡住了超长字符串）。
     *
     * @param gnss 模块上报的原始值，可为 null
     * @return 归一化后的值；{@code null} = 没上报 / 无法识别
     */
    static String normalizeGnss(String gnss) {
        if (gnss == null) {
            return null;
        }
        String value = gnss.trim();
        if (value.isEmpty() || value.length() > MAX_GNSS_LEN) {
            return null;
        }
        for (String known : GNSS_VALUES) {
            if (known.equalsIgnoreCase(value)) {
                return known;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 内部

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 读整份 JSON（每次新对象，可安全修改）；损坏/为空时返回空对象。 */
    private static JSONObject load(Context context) {
        String raw = prefs(context).getString(KEY_PROCESSES, null);
        if (raw == null || raw.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static Beat toBeat(String name, JSONObject entry) {
        long at = entry.optLong("at", 0L);
        if (at <= 0L) {
            return null;
        }
        String kind = KIND_SYSTEM.equals(entry.optString("kind", "")) ? KIND_SYSTEM : KIND_APP;
        return new Beat(name, Math.max(0, entry.optInt("hooks", 0)), kind, at,
                entry.optInt("sdk", 0), readGnss(entry));
    }

    /**
     * 读 {@code gnss} 字段：缺失 / 空 / 超长 / 不在白名单 → {@code null}。
     *
     * <p>老版本模块不带这个字段、更早的心跳数据里也没有它，所以这里必须"读不到就是 null"，
     * 不能报错、不能抛异常（否则既有心跳会整条读不出来）。
     */
    private static String readGnss(JSONObject entry) {
        try {
            return normalizeGnss(entry.optString("gnss", null));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 丢掉过期条目；条目数超限时按 {@code at} 从旧到新删到上限。 */
    private static void prune(JSONObject all, long now) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = all.keys();
        while (it.hasNext()) {
            names.add(it.next());
        }
        for (String name : names) {
            JSONObject entry = all.optJSONObject(name);
            long at = entry == null ? 0L : entry.optLong("at", 0L);
            if (entry == null || at < now - PRUNE_MS) {
                all.remove(name);
            }
        }
        while (all.length() > MAX_PROCESSES) {
            String oldest = null;
            long oldestAt = Long.MAX_VALUE;
            Iterator<String> keys = all.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                JSONObject entry = all.optJSONObject(name);
                long at = entry == null ? 0L : entry.optLong("at", 0L);
                if (at < oldestAt) {
                    oldestAt = at;
                    oldest = name;
                }
            }
            if (oldest == null) {
                return;
            }
            all.remove(oldest);
        }
    }
}
