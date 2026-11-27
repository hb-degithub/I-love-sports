package com.vloc.motion.service;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;
import android.util.Log;

import com.vloc.motion.core.RouteStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 环境快照 Provider：仅在模拟进行时向目标 App 进程（LSPosed 模块）暴露基站/Wi-Fi 快照。
 * 无快照或未启用时返回 0 行，模块据此透传真实值。
 *
 * <p>除快照外还承担两条辅助通道：
 * <ul>
 *   <li>{@code /targets}：作用域配置（模块读"只对选定应用生效 + 名单"）；</li>
 *   <li>{@code /hookbeat}：模块心跳，模块在每个被注入的进程里 insert 一次，
 *       App 用它判断"模块是否生效"（{@link HookHeartbeatStore}）。</li>
 * </ul>
 * 心跳是唯一可从模块侧主动写进来的数据，所以 insert 只做轻量字段校验、只写 prefs。
 */
public final class EnvProvider extends ContentProvider {

    public static final String AUTHORITY = "com.vloc.motion.env";
    public static final String PATH_SNAPSHOT = "snapshot";
    public static final String PATH_STEPS = "steps";
    public static final String PATH_GNSS = "gnss";
    public static final Uri SNAPSHOT_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH_SNAPSHOT);
    public static final Uri STEPS_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH_STEPS);
    public static final Uri GNSS_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH_GNSS);
    /** 定位快照（供模块在 system_server 做系统级注入）。 */
    public static final String PATH_LOCATION = "location";
    public static final Uri LOCATION_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH_LOCATION);
    /**
     * 作用域（目标应用）配置：给模块读「是否只对选定应用生效」+「名单」。
     *
     * <p>只有一列 {@code json}、一行，形如
     * {@code {"enabled":true,"packages":["com.a","com.b"]}}；不含坐标等敏感信息。
     */
    public static final String PATH_TARGETS = "targets";
    public static final Uri TARGETS_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH_TARGETS);
    /**
     * 模块心跳：模块在**每个被注入的进程**里 insert 一次，字段
     * {@code process / hooks / kind / at / sdk}，外加可选的 {@code gnss}
     * （GNSS 注入结果）、{@code ver} / {@code vn}（模块代码版本，见 {@link HookHeartbeatStore}）。
     *
     * <p>这条通道是"模块是否生效"的判定依据：包名（LSPosed 管理器）会被隐藏模块屏蔽，
     * 心跳不会。只接收 insert，数据落在 prefs，不落任何其它副作用。
     */
    public static final String PATH_HOOKBEAT = "hookbeat";
    public static final Uri HOOKBEAT_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH_HOOKBEAT);

    /** hookbeat 的 ContentValues key（与模块侧契约一致）。 */
    private static final String KEY_PROCESS = "process";
    private static final String KEY_HOOKS = "hooks";
    private static final String KEY_KIND = "kind";
    private static final String KEY_AT = "at";
    private static final String KEY_SDK = "sdk";
    /**
     * 可选：GNSS 注入结果（{@code built:builder} / {@code built:ctor} / {@code built:unsafe} /
     * {@code force:mutated} / {@code skip:no-satellites} / {@code skip:null}）。
     *
     * <p>缺失 = 该进程还没确定结果（老版本模块也不带）；{@link HookHeartbeatStore} 按 null 存，
     * 设置面板显示「未知」。取值由 {@link HookHeartbeatStore#normalizeGnss(String)} 做白名单校验。
     */
    private static final String KEY_GNSS = "gnss";
    /**
     * 可选：模块代码版本号（{@code BuildConfig.VERSION_CODE}）。
     *
     * <p>缺失 = 老版本模块（不报版本）→ 落库为 0；主 App 侧把 0 当"未知"，
     * <b>不会</b>据此弹"模块已更新，请重启"（避免把老模块误报成版本失配）。
     */
    private static final String KEY_VER = "ver";
    /**
     * 可选：模块版本名（{@code BuildConfig.VERSION_NAME}），只用于设置面板显示。
     *
     * <p>缺失 / 非法值 → 落库为 null，读取侧退回数字版本号；取值由
     * {@link HookHeartbeatStore#normalizeVersionName(String)} 做长度与控制字符校验。
     */
    private static final String KEY_VN = "vn";

    private static final String[] COLUMNS = {"mode", "json", "updated_at"};
    /** targets 路径单独用一列，避免模块侧还要按列名索引。 */
    private static final String[] TARGET_COLUMNS = {"json"};

    @Override
    public boolean onCreate() {
        return true;
    }

    // ---------------------------------------------------------------- 组件开关（空闲期不暴露）
    // 说明：本 Provider 在 manifest 里是 exported=true（无权限），空闲期也保持启用。
    // 曾尝试「只在会话期间启用、空闲期禁用组件」以降低可发现性，但同一 authority 还承载
    // 模块心跳（hookbeat）与作用域名单（targets）：禁用会让模块开机加载时上报失败、
    // 作用域在 prefs/套接字都不可用时静默失效。收益（隐藏一个 authority，而包名本身仍可见）
    // 远小于代价，故保持常驻启用；快照路径本身在非会话期返回 0 行。

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        // hookbeat 走最短路径：只读本地 prefs，内容与持久化结构一致（{进程名: {hooks,kind,at,sdk}}）。
        if (isHookbeatUri(uri)) {
            return hookbeatCursor();
        }
        // targets 走最短路径：只读本地 prefs，不碰 PackageManager / 磁盘扫描，
        // 模块每次投递都会（带缓存地）读它，必须够快。
        if (isTargetsUri(uri)) {
            return targetsCursor();
        }
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        if (isLocationUri(uri) || isGnssUri(uri)) {
            // uid==1000 说明这次读取来自 system_server（系统级注入的模块，见
            // hidden/SystemHooks.java 的 currentLocation()/currentSatellites()）。
            // 目标进程内的模块（hidden/AntiDetect.java）也读同一个 URI，但 uid 是目标 App，
            // 不能算作"系统级接管"的证据。
            if (Binder.getCallingUid() == Process.SYSTEM_UID) {
                StepSession.INSTANCE.markModuleSystemRead();
            }
        }
        if (isLocationUri(uri)) {
            LocationSession location = LocationSession.INSTANCE;
            if (!location.active) {
                return cursor;   // 未在模拟：模块透传真实定位
            }
            cursor.addRow(new Object[]{"replay", location.toJson(), location.updatedAt});
            return cursor;
        }
        if (isGnssUri(uri)) {
            GnssSession gnss = GnssSession.INSTANCE;
            if (gnss.json == null) {
                return cursor;
            }
            cursor.addRow(new Object[]{"replay", gnss.json, gnss.updatedAt});
            return cursor;
        }
        if (isStepsUri(uri)) {
            StepSession steps = StepSession.INSTANCE;
            if (steps.json == null ) {
                return cursor;
            }
            cursor.addRow(new Object[]{
                    steps.active ? "replay" : "off",
                    steps.json,
                    steps.updatedAt
            });
            return cursor;
        }
        EnvSession session = EnvSession.INSTANCE;
        if (!session.isActive() || !isSnapshotUri(uri)) {
            return cursor;
        }
        cursor.addRow(new Object[]{
                session.mode.name().toLowerCase(java.util.Locale.US),
                session.json,
                session.updatedAt
        });
        return cursor;
    }

    
    
    
    private static boolean isLocationUri(Uri uri) {
        return uri != null && AUTHORITY.equals(uri.getAuthority())
                && PATH_LOCATION.equals(uri.getLastPathSegment());
    }

    private static boolean isGnssUri(Uri uri) {
        return uri != null && AUTHORITY.equals(uri.getAuthority())
                && PATH_GNSS.equals(uri.getLastPathSegment());
    }

    private static boolean isStepsUri(Uri uri) {
        return uri != null && AUTHORITY.equals(uri.getAuthority())
                && PATH_STEPS.equals(uri.getLastPathSegment());
    }

    private static boolean isSnapshotUri(Uri uri) {
        return uri != null && AUTHORITY.equals(uri.getAuthority())
                && PATH_SNAPSHOT.equals(uri.getLastPathSegment());
    }

    private static boolean isTargetsUri(Uri uri) {
        return uri != null && AUTHORITY.equals(uri.getAuthority())
                && PATH_TARGETS.equals(uri.getLastPathSegment());
    }

    private static boolean isHookbeatUri(Uri uri) {
        return uri != null && AUTHORITY.equals(uri.getAuthority())
                && PATH_HOOKBEAT.equals(uri.getLastPathSegment());
    }

    /**
     * 作用域配置游标：单列 {@code json}、单行。
     *
     * <p>{@code enabled=false} 表示「作用域限制未开启，对所有应用生效」，此时 packages 可以是空数组。
     * 这里只做一次 prefs 读取 + 字符串拼装；出错也返回一行合法 JSON，模块不用处理 0 行。
     */
    private Cursor targetsCursor() {
        MatrixCursor cursor = new MatrixCursor(TARGET_COLUMNS);
        Context context = getContext();
        JSONObject json = new JSONObject();
        try {
            json.put("enabled", context != null && RouteStore.loadScopeEnabled(context));
            JSONArray packages = new JSONArray();
            if (context != null) {
                for (String pkg : RouteStore.loadTargetPackages(context)) {
                    packages.put(pkg);
                }
            }
            json.put("packages", packages);
        } catch (JSONException ignored) {
            // JSONObject/JSONArray 在已知 key 下不会失败，兜底返回已构造部分
        }
        cursor.addRow(new Object[]{json.toString()});
        return cursor;
    }

    /**
     * 心跳游标：单列 {@code json}、单行，内容同持久化结构，例如
     * {@code {"com.android.location.fused":{"hooks":12,"kind":"system","at":1699...,"sdk":34,
     * "ver":89,"vn":"0.46.5"}}}。
     *
     * <p>{@code ver} / {@code vn} 是<b>可选</b>列：老版本模块上报的条目（以及本次改动之前
     * 已经落库的历史条目）里没有它们，{@link HookHeartbeatStore#snapshotJson} 就原样不写；
     * 读方必须按"缺列 = 0 / null"兼容（{@link HookHeartbeatStore#beats} /
     * {@link HookHeartbeatStore#freshestSystemVersionCode} 已按这个口径解析）。
     *
     * <p>没有心跳时也返回一行 {@code {}}，读方不用处理 0 行（与 targets 一致）。
     */
    private Cursor hookbeatCursor() {
        MatrixCursor cursor = new MatrixCursor(TARGET_COLUMNS);
        Context context = getContext();
        cursor.addRow(new Object[]{
                context == null ? "{}" : HookHeartbeatStore.snapshotJson(context)
        });
        return cursor;
    }

    @Override
    public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
        if (method == null) {
            return null;
        }
        if (method.equalsIgnoreCase("module_heartbeat") || method.equalsIgnoreCase("steps_ping")) {
            StepSession.INSTANCE.markModuleHeartbeat();
            if (method.equalsIgnoreCase("steps_ping")) {
                StepSession.INSTANCE.markModuleRead();
            }
            android.os.Bundle result = new android.os.Bundle();
            result.putBoolean("ok", true);
            return result;
        }
        if (method.equalsIgnoreCase("env_ping")) {
            android.os.Bundle result = new android.os.Bundle();
            result.putBoolean("ok", true);
            return result;
        }
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vloc-env-snapshot";
    }

    /**
     * 唯一可写的路径：{@code /hookbeat}（模块心跳）。
     *
     * <p>其它路径（snapshot/location/gnss/steps/targets）保持只读语义，insert 返回 null——
     * 注入数据由 App 单向写出，模块不该能改。
     *
     * <p>本方法运行在 Binder 线程，而且模块会在被注入进程的启动路径上调它，
     * 所以只做**字段合法性**校验 + 一次 prefs 写入（apply），绝不查 PackageManager、
     * 不扫磁盘、不抛异常（任何非法输入都返回 null）。
     */
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (!isHookbeatUri(uri) || values == null) {
            return null;
        }
        final String process = readString(values, KEY_PROCESS);
        final Integer hooks = readInt(values, KEY_HOOKS);
        final String kind = readString(values, KEY_KIND);
        final Long rawAt = readLong(values, KEY_AT);
        final Integer sdk = readInt(values, KEY_SDK);
        // 可选字段：老模块不带；带了但值不认识由 store 的白名单丢掉（不影响整条心跳落库）
        final String gnss = readString(values, KEY_GNSS);
        // 可选字段（本轮新增）：模块代码版本。老模块不带 → ver 落库为 0（"未知"）、vn 为 null；
        // 负数 / 离谱值一律按 0 处理，绝不因为一个附加列把整条心跳丢掉。
        final Integer rawVer = readInt(values, KEY_VER);
        final int ver = rawVer == null || rawVer < 0 ? 0 : rawVer;
        final String vn = readString(values, KEY_VN);
        // 必需字段：process / hooks / kind。at 缺省时用本机时间（老模块漏字段也能记下来），
        // 但显式给了就必须在合理范围内。
        if (!HookHeartbeatStore.isValidProcess(process) || hooks == null || hooks < 0) {
            return null;
        }
        if (HookHeartbeatStore.normalizeKind(kind) == null) {
            return null;
        }
        if (HookHeartbeatStore.isSystemServerProcess(process)
                && Binder.getCallingUid() != Process.SYSTEM_UID) {
            return null;
        }
        final long now = System.currentTimeMillis();
        final long at = rawAt == null || rawAt <= 0L ? now : rawAt;
        if (!HookHeartbeatStore.isValidAt(at, now)) {
            return null;
        }
        Context context = getContext();
        if (context == null) {
            return null;
        }
        final int sdkValue = sdk == null ? 0 : sdk;   // <=0 时 store 用当前 SDK_INT 兜底
        return HookHeartbeatStore.record(context, process, hooks, kind, at, sdkValue, gnss, ver, vn)
                ? uri : null;
    }

    // ---------------------------------------------------------------- ContentValues 读取
    // 模块可能把数值写成 String（个别老版本这样传），这里统一兼容；
    // 类型不对只返回 null，绝不抛 ClassCastException。

    private static Integer readInt(ContentValues values, String key) {
        Object raw = values.get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long readLong(ContentValues values, String key) {
        Object raw = values.get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readString(ContentValues values, String key) {
        Object raw = values.get(key);
        return raw == null ? null : String.valueOf(raw);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
