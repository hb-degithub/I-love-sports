package com.vloc.motion.service;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

import com.vloc.motion.core.RouteStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 环境快照 Provider：仅在模拟进行时向目标 App 进程（LSPosed 模块）暴露基站/Wi-Fi 快照。
 * 无快照或未启用时返回 0 行，模块据此透传真实值。
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

    private static final String[] COLUMNS = {"mode", "json", "updated_at"};
    /** targets 路径单独用一列，避免模块侧还要按列名索引。 */
    private static final String[] TARGET_COLUMNS = {"json"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
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

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
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
