package com.vloc.motion.service;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.vloc.motion.core.CameraClips;
import com.vloc.motion.core.RouteStore;

import java.io.FileNotFoundException;
import java.util.List;

/**
 * 环境快照 Provider：仅在模拟进行时向目标 App 进程（LSPosed 模块）暴露基站/Wi-Fi 快照。
 * 无快照或未启用时返回 0 行，模块据此透传真实值。
 */
public final class EnvProvider extends ContentProvider {

    public static final String AUTHORITY = "com.vloc.motion.env";
    public static final String PATH_SNAPSHOT = "snapshot";
    public static final String PATH_STEPS = "steps";
    public static final String PATH_GNSS = "gnss";
    /** 虚拟摄像头配置（JSON：开关 / 当前视频 / 视频列表）。 */
    public static final String PATH_CAMERA = "camera";
    /** 虚拟摄像头视频文件（openFile 返回视频 FD，供模块解码）。 */
    public static final String PATH_CAMERA_VIDEO = "camera_video";
    public static final Uri SNAPSHOT_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH_SNAPSHOT);
    public static final Uri STEPS_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH_STEPS);
    public static final Uri GNSS_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH_GNSS);
    public static final Uri CAMERA_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH_CAMERA);
    public static final Uri CAMERA_VIDEO_URI =
            Uri.parse("content://" + AUTHORITY + "/" + PATH_CAMERA_VIDEO);
    /** 虚拟摄像头运行时状态（模块上报 → 控制页展示）。 */
    public static final String PATH_CAMERA_STATUS = "camera_status";
    /** 定位快照（供模块在 system_server 做系统级注入）。 */
    public static final String PATH_LOCATION = "location";
    public static final Uri LOCATION_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH_LOCATION);
    public static final Uri CAMERA_STATUS_URI =
            Uri.parse("content://" + AUTHORITY + "/" + PATH_CAMERA_STATUS);

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
        if (isCameraStatusUri(uri)) {
            cursor.addRow(new Object[]{"camera_status",
                    CameraSession.INSTANCE.toJson(), System.currentTimeMillis()});
            return cursor;
        }
        if (isCameraUri(uri)) {
            List<CameraClips.Clip> clips = RouteStore.loadCameraClips(getContext());
            int index = RouteStore.loadCameraIndex(getContext());
            cursor.addRow(new Object[]{
                    "camera",
                    CameraClips.toProviderJson(
                            RouteStore.loadCameraEnabled(getContext()),
                            RouteStore.loadCameraPlaying(getContext()), index, clips,
                            RouteStore.loadCameraImuUpright(getContext())),
                    System.currentTimeMillis()
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

    private static boolean isCameraStatusUri(Uri uri) {
        return uri != null && AUTHORITY.equals(uri.getAuthority())
                && PATH_CAMERA_STATUS.equals(uri.getLastPathSegment());
    }

    private static boolean isCameraUri(Uri uri) {
        return uri != null && AUTHORITY.equals(uri.getAuthority())
                && PATH_CAMERA.equals(uri.getLastPathSegment());
    }

    private static boolean isCameraVideoUri(Uri uri) {
        return uri != null && AUTHORITY.equals(uri.getAuthority())
                && PATH_CAMERA_VIDEO.equals(uri.getLastPathSegment());
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
        if (method.equalsIgnoreCase("camera_report")) {
            int hooks = extras == null ? 0 : extras.getInt("hooks", 0);
            boolean session = extras != null && extras.getBoolean("session", false);
            boolean render = extras != null && extras.getBoolean("render", false);
            String error = extras == null ? null : extras.getString("error");
            String pkg = extras == null ? null : extras.getString("pkg");
            String event = extras == null ? null : extras.getString("event");
            CameraSession.INSTANCE.report(pkg, hooks, session, render, error, event);
            if (extras != null && extras.containsKey("posMs")) {
                CameraSession.INSTANCE.reportProgress(extras.getLong("posMs", 0L),
                        extras.getLong("durMs", 0L));
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

    /**
     * 虚拟摄像头视频：{@code content://com.vloc.motion.env/camera_video?index=N}
     * 返回相册里选中视频的 FD，供目标 App 进程内的模块用 MediaExtractor/MediaCodec 解码。
     *
     * <p>{@code index} 缺省时用当前选中的视频。主 App 持有 SAF 持久化读权限，
     * 模块只拿到 FD，不需要、也无法访问主 App 的授权。
     */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!isCameraVideoUri(uri)) {
            throw new FileNotFoundException("unsupported uri: " + uri);
        }
        List<CameraClips.Clip> clips = RouteStore.loadCameraClips(getContext());
        if (clips.isEmpty()) {
            throw new FileNotFoundException("no camera clips");
        }
        int index = RouteStore.loadCameraIndex(getContext());
        String raw = uri.getQueryParameter("index");
        if (raw != null) {
            try {
                index = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {
                // 用当前选中的
            }
        }
        CameraClips.Clip clip = CameraClips.current(clips, CameraClips.clampIndex(clips, index));
        if (clip == null) {
            throw new FileNotFoundException("clip not found");
        }
        Uri clipUri = Uri.parse(clip.uri);
        try {
            ParcelFileDescriptor pfd = getContext().getContentResolver()
                    .openFileDescriptor(clipUri, "r");
            if (pfd == null) {
                throw new FileNotFoundException("cannot open: " + clip.uri);
            }
            return pfd;
        } catch (SecurityException e) {
            throw new FileNotFoundException("no permission for clip: " + e.getMessage());
        }
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
