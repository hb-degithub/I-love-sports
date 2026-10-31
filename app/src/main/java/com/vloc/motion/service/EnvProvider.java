package com.vloc.motion.service;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import com.vloc.motion.core.RouteStore;

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

    private static final String[] COLUMNS = {"mode", "json", "updated_at"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
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
