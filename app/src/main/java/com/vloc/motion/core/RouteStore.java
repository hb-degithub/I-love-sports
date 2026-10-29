package com.vloc.motion.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/** 本地持久化（SharedPreferences）：路径、速度、循环、注入 Provider、地图视野。 */
public final class RouteStore {

    private static final String PREFS = "vloc_prefs";
    private static final String KEY_ROUTE = "route";
    private static final String KEY_SPEED = "speed_kmh";
    private static final String KEY_LOOP = "loop";
    private static final String KEY_PROVIDERS = "providers";
    private static final String KEY_MAP_LAT = "map_lat";
    private static final String KEY_MAP_LON = "map_lon";
    private static final String KEY_MAP_ZOOM = "map_zoom";
    private static final String KEY_ACCURACY = "accuracy";
    private static final String KEY_ALTITUDE = "base_altitude";
    private static final String KEY_REALISM = "realism";
    private static final String KEY_OVERLAY = "overlay_enabled";
    private static final String KEY_OVERLAY_X = "overlay_x";
    private static final String KEY_OVERLAY_Y = "overlay_y";
    private static final String KEY_ROUTE_LIBRARY = "route_library";
    private static final String KEY_GOAL_ENABLED = "goal_enabled";
    private static final String KEY_GOAL_LAP = "goal_lap_m";
    private static final String KEY_GOAL_DISTANCE = "goal_distance_m";
    private static final String KEY_GOAL_TIME = "goal_time_s";
    private static final String KEY_GOAL_MAX_KMH = "goal_max_kmh";
    private static final String KEY_GOAL_PACE_S = "goal_pace_s_per_km";
    private static final String KEY_SPEED_VARIANCE = "speed_variance";
    private static final String KEY_ATTITUDE = "attitude_replay";
    private static final String KEY_AUTO_STOP_ON_FINISH = "auto_stop_on_finish";
    private static final String KEY_CAMERA_ENABLED = "camera_enabled";
    private static final String KEY_CAMERA_CLIPS = "camera_clips";
    private static final String KEY_CAMERA_INDEX = "camera_index";
    private static final String KEY_CAMERA_PLAYING = "camera_playing";
    private static final String KEY_CAMERA_IMU_UPRIGHT = "camera_imu_upright";
    private static final String KEY_CAMERA_OVERLAY_X = "camera_overlay_x";
    private static final String KEY_CAMERA_OVERLAY_Y = "camera_overlay_y";
    private static final String KEY_CAMERA_OVERLAY_HIDDEN = "camera_overlay_hidden";
    private static final String KEY_OVERLAY_COLLAPSED = "overlay_collapsed";
    private static final String KEY_GNSS_SNAPSHOT = "gnss_snapshot";
    private static final String KEY_RANDOM_START = "random_start";
    private static final String KEY_BUILTIN_SEEDED = "builtin_routes_v3";

    private RouteStore() {
    }

    /** 路线库里的一条命名路线。 */
    public static final class NamedRoute {
        public final String name;
        public final Route route;
        public final long updatedAt;

        NamedRoute(String name, Route route, long updatedAt) {
            this.name = name;
            this.route = route;
            this.updatedAt = updatedAt;
        }
    }

    /**
     * 写入内置路线（用户提供的田径场坐标）。
     *
     * <p>v3：经高德卫星图核对，用户 JSON 的 glat/glon 才是 GCJ-02 地图坐标（对准操场），
     * 之前误用了 lat/lon；本次覆盖修正，并把内置路线存为 WGS-84 + 首尾闭环。
     */
    public static void ensureBuiltinRoutes(Context context) {
        boolean seeded = prefs(context).getBoolean(KEY_BUILTIN_SEEDED, false);
        if (!seeded) {
            saveNamedRoute(context, BuiltinRoutes.TRACK_NAME, BuiltinRoutes.track());
            prefs(context).edit().putBoolean(KEY_BUILTIN_SEEDED, true).apply();
            return;
        }
        // 已迁移过：仅在用户删掉该路线后不再自动补，避免干扰用户管理
    }

    /** 读取路线库（按最近保存排序）。 */
    public static List<NamedRoute> loadRouteLibrary(Context context) {
        List<NamedRoute> list = new ArrayList<>();
        String raw = prefs(context).getString(KEY_ROUTE_LIBRARY, "");
        if (raw == null || raw.isEmpty()) {
            return list;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String name = o.optString("name", "");
                Route route = Route.decode(o.optString("route", ""));
                if (!name.isEmpty() && route.size() > 0) {
                    list.add(new NamedRoute(name, route, o.optLong("updatedAt", 0L)));
                }
            }
        } catch (JSONException ignored) {
            // 损坏则丢弃
        }
        list.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return list;
    }

    /** 保存/覆盖一条命名路线。 */
    public static void saveNamedRoute(Context context, String name, Route route) {
        if (name == null || name.trim().isEmpty() || route == null || route.size() == 0) {
            return;
        }
        String trimmed = name.trim();
        List<NamedRoute> current = loadRouteLibrary(context);
        List<NamedRoute> merged = new ArrayList<>();
        boolean replaced = false;
        for (NamedRoute item : current) {
            if (item.name.equals(trimmed)) {
                merged.add(new NamedRoute(trimmed, route, System.currentTimeMillis()));
                replaced = true;
            } else {
                merged.add(item);
            }
        }
        if (!replaced) {
            merged.add(new NamedRoute(trimmed, route, System.currentTimeMillis()));
        }
        writeRouteLibrary(context, merged);
    }

    public static void deleteNamedRoute(Context context, String name) {
        if (name == null) {
            return;
        }
        List<NamedRoute> merged = new ArrayList<>();
        for (NamedRoute item : loadRouteLibrary(context)) {
            if (!item.name.equals(name)) {
                merged.add(item);
            }
        }
        writeRouteLibrary(context, merged);
    }

    private static void writeRouteLibrary(Context context, List<NamedRoute> items) {
        JSONArray array = new JSONArray();
        try {
            for (NamedRoute item : items) {
                JSONObject o = new JSONObject();
                o.put("name", item.name);
                o.put("route", item.route.encode());
                o.put("updatedAt", item.updatedAt);
                array.put(o);
            }
        } catch (JSONException ignored) {
            // 忽略
        }
        prefs(context).edit().putString(KEY_ROUTE_LIBRARY, array.toString()).apply();
    }

    /**
     * 容错读 int：同一 key 在历史版本里可能被存成 float/string，
     * 直接 getInt 会抛 ClassCastException 导致启动崩溃，这里统一降级。
     */
    private static int readIntCompat(SharedPreferences p, String key, int fallback) {
        try {
            return p.getInt(key, fallback);
        } catch (ClassCastException e) {
            try {
                return Math.round(p.getFloat(key, fallback));
            } catch (ClassCastException e2) {
                try {
                    return (int) Math.round(Double.parseDouble(
                            p.getString(key, String.valueOf(fallback))));
                } catch (RuntimeException ignored) {
                    return fallback;
                }
            }
        }
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Route loadRoute(Context context) {
        return Route.decode(prefs(context).getString(KEY_ROUTE, ""));
    }

    public static void saveRoute(Context context, Route route) {
        prefs(context).edit().putString(KEY_ROUTE, route == null ? "" : route.encode()).apply();
    }

    public static int loadSpeedKmh(Context context) {
        return readIntCompat(prefs(context), KEY_SPEED, 6);
    }

    public static void saveSpeedKmh(Context context, int kmh) {
        prefs(context).edit().putInt(KEY_SPEED, kmh).apply();
    }

    public static boolean loadLoop(Context context) {
        return prefs(context).getBoolean(KEY_LOOP, false);
    }

    public static void saveLoop(Context context, boolean loop) {
        prefs(context).edit().putBoolean(KEY_LOOP, loop).apply();
    }

    public static List<String> loadProviders(Context context) {
        String raw = prefs(context).getString(KEY_PROVIDERS, "gps,network");
        List<String> list = new ArrayList<>();
        for (String s : raw.split(",")) {
            if (!s.trim().isEmpty()) {
                list.add(s.trim());
            }
        }
        if (list.isEmpty()) {
            list.addAll(Arrays.asList("gps", "network"));
        }
        return list;
    }

    public static void saveProviders(Context context, Collection<String> providers) {
        StringBuilder sb = new StringBuilder();
        for (String p : providers) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p);
        }
        prefs(context).edit().putString(KEY_PROVIDERS, sb.toString()).apply();
    }

    public static float loadAccuracy(Context context) {
        return prefs(context).getFloat(KEY_ACCURACY, 5.0f);
    }

    public static void saveAccuracy(Context context, float accuracy) {
        prefs(context).edit().putFloat(KEY_ACCURACY, accuracy).apply();
    }

    // ---- 目标配速 ----

    public static boolean loadGoalEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GOAL_ENABLED, false);
    }

    public static void saveGoalEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_GOAL_ENABLED, enabled).apply();
    }

    public static float loadGoalLapMeters(Context context) {
        return prefs(context).getFloat(KEY_GOAL_LAP, 400f);
    }

    public static void saveGoalLapMeters(Context context, float meters) {
        prefs(context).edit().putFloat(KEY_GOAL_LAP, meters).apply();
    }

    public static float loadGoalDistanceMeters(Context context) {
        return prefs(context).getFloat(KEY_GOAL_DISTANCE, 1000f);
    }

    public static void saveGoalDistanceMeters(Context context, float meters) {
        prefs(context).edit().putFloat(KEY_GOAL_DISTANCE, meters).apply();
    }

    public static int loadGoalTimeSeconds(Context context) {
        return readIntCompat(prefs(context), KEY_GOAL_TIME, 600);
    }

    public static void saveGoalTimeSeconds(Context context, int seconds) {
        prefs(context).edit().putInt(KEY_GOAL_TIME, seconds).apply();
    }

    /** 目标配速（秒/公里），默认 360 = 6:00/km。 */
    public static int loadGoalPaceSeconds(Context context) {
        return readIntCompat(prefs(context), KEY_GOAL_PACE_S, 360);
    }

    public static void saveGoalPaceSeconds(Context context, int seconds) {
        prefs(context).edit().putInt(KEY_GOAL_PACE_S, seconds).apply();
    }

    public static float loadGoalMaxKmh(Context context) {
        return prefs(context).getFloat(KEY_GOAL_MAX_KMH, 15f);
    }

    public static void saveGoalMaxKmh(Context context, float kmh) {
        prefs(context).edit().putFloat(KEY_GOAL_MAX_KMH, kmh).apply();
    }

    /** 速度混淆：时快时慢（默认开启）。 */
    public static boolean loadSpeedVariance(Context context) {
        return prefs(context).getBoolean(KEY_SPEED_VARIANCE, true);
    }

    public static void saveSpeedVariance(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SPEED_VARIANCE, enabled).apply();
    }

    /** 上次采集/使用的卫星快照（JSON）。 */
    public static String loadGnssSnapshot(Context context) {
        return prefs(context).getString(KEY_GNSS_SNAPSHOT, null);
    }

    public static void saveGnssSnapshot(Context context, String json) {
        prefs(context).edit().putString(KEY_GNSS_SNAPSHOT, json).apply();
    }

    /** 每次开始模拟时随机更换起点。 */
    public static boolean loadRandomStart(Context context) {
        return prefs(context).getBoolean(KEY_RANDOM_START, false);
    }

    public static void saveRandomStart(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_RANDOM_START, enabled).apply();
    }

    public static boolean loadOverlayCollapsed(Context context) {
        return prefs(context).getBoolean(KEY_OVERLAY_COLLAPSED, false);
    }

    public static void saveOverlayCollapsed(Context context, boolean collapsed) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_COLLAPSED, collapsed).apply();
    }

    /** 是否伪造姿态类传感器（ROTATION_VECTOR / MAGNETIC_FIELD），默认开启。 */
    public static boolean loadAttitudeReplay(Context context) {
        return prefs(context).getBoolean(KEY_ATTITUDE, true);
    }

    public static void saveAttitudeReplay(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ATTITUDE, enabled).apply();
    }

    /**
     * 到达终点后是否自动停止模拟并恢复真实定位（默认开启）。
     *
     * <p>关闭后保持旧行为：定位冻结在终点、持续 1Hz 上报，需要手动「彻底停止」才恢复真实定位。
     */
    public static boolean loadAutoStopOnFinish(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_STOP_ON_FINISH, true);
    }

    public static void saveAutoStopOnFinish(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_STOP_ON_FINISH, enabled).apply();
    }

    // ---------------------------------------------------------------- 虚拟摄像头

    /** 虚拟摄像头总开关（默认关闭）。 */
    public static boolean loadCameraEnabled(Context context) {
        return prefs(context).getBoolean(KEY_CAMERA_ENABLED, false);
    }

    public static void saveCameraEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_CAMERA_ENABLED, enabled).apply();
    }

    /** 视频列表（相册里选的若干个视频，SAF 持久化授权）。 */
    public static java.util.List<CameraClips.Clip> loadCameraClips(Context context) {
        return CameraClips.fromJson(prefs(context).getString(KEY_CAMERA_CLIPS, ""));
    }

    public static void saveCameraClips(Context context, java.util.List<CameraClips.Clip> clips) {
        prefs(context).edit().putString(KEY_CAMERA_CLIPS, CameraClips.toJson(clips)).apply();
    }

    /** 当前播放第几个视频（悬浮窗可切换）。 */
    public static int loadCameraIndex(Context context) {
        return CameraClips.clampIndex(loadCameraClips(context),
                readIntCompat(prefs(context), KEY_CAMERA_INDEX, 0));
    }

    public static void saveCameraIndex(Context context, int index) {
        prefs(context).edit().putInt(KEY_CAMERA_INDEX, index).apply();
    }

    /** 虚拟摄像头视频是否正在播放（暂停时目标 App 的相机画面冻结在最后一帧）。 */
    public static boolean loadCameraPlaying(Context context) {
        return prefs(context).getBoolean(KEY_CAMERA_PLAYING, false);
    }

    public static void saveCameraPlaying(Context context, boolean playing) {
        prefs(context).edit().putBoolean(KEY_CAMERA_PLAYING, playing).apply();
    }

    /**
     * 相机接管期间，IMU 是否固定成"手机竖直、静止"（默认开）。
     *
     * <p>这是和「运动模拟（跑步姿态）」互斥的另一套逻辑：运动检测类 App 靠陀螺仪/加速度
     * 判断手机是否立好，竖直模式让它通过；关掉则继续按跑步姿态回放。
     */
    public static boolean loadCameraImuUpright(Context context) {
        return prefs(context).getBoolean(KEY_CAMERA_IMU_UPRIGHT, true);
    }

    public static void saveCameraImuUpright(Context context, boolean upright) {
        prefs(context).edit().putBoolean(KEY_CAMERA_IMU_UPRIGHT, upright).apply();
    }

    /** 基础海拔（米），路径点没写海拔时使用。 */
    public static float loadAltitude(Context context) {
        return prefs(context).getFloat(KEY_ALTITUDE, 50.0f);
    }

    public static void saveAltitude(Context context, float altitude) {
        prefs(context).edit().putFloat(KEY_ALTITUDE, altitude).apply();
    }

    /** 拟真模式（漂移/噪声/卫星数据/起步缓动）。 */
    public static boolean loadRealism(Context context) {
        return prefs(context).getBoolean(KEY_REALISM, true);
    }

    public static void saveRealism(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_REALISM, enabled).apply();
    }

    public static boolean loadOverlayEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OVERLAY, false);
    }

    public static void saveOverlayEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OVERLAY, enabled).apply();
    }

    /** @return {x, y}；未保存过返回 null */
    public static int[] loadOverlayPosition(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.contains(KEY_OVERLAY_X) || !p.contains(KEY_OVERLAY_Y)) {
            return null;
        }
        return new int[]{p.getInt(KEY_OVERLAY_X, 0), p.getInt(KEY_OVERLAY_Y, 200)};
    }

    /** 虚拟摄像头独立悬浮窗的位置（与定位悬浮窗分开记）。 */
    public static int[] loadCameraOverlayPosition(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.contains(KEY_CAMERA_OVERLAY_X) || !p.contains(KEY_CAMERA_OVERLAY_Y)) {
            return null;
        }
        return new int[]{p.getInt(KEY_CAMERA_OVERLAY_X, 0), p.getInt(KEY_CAMERA_OVERLAY_Y, 260)};
    }

    public static void saveCameraOverlayPosition(Context context, int x, int y) {
        prefs(context).edit()
                .putInt(KEY_CAMERA_OVERLAY_X, x)
                .putInt(KEY_CAMERA_OVERLAY_Y, y)
                .apply();
    }

    /** 用户手动关掉悬浮窗（摄像头仍启用）时不自动弹回。 */
    public static boolean loadCameraOverlayHidden(Context context) {
        return prefs(context).getBoolean(KEY_CAMERA_OVERLAY_HIDDEN, false);
    }

    public static void saveCameraOverlayHidden(Context context, boolean hidden) {
        prefs(context).edit().putBoolean(KEY_CAMERA_OVERLAY_HIDDEN, hidden).apply();
    }

    public static void saveOverlayPosition(Context context, int x, int y) {
        prefs(context).edit().putInt(KEY_OVERLAY_X, x).putInt(KEY_OVERLAY_Y, y).apply();
    }

    public static void saveMapView(Context context, double lat, double lon, float zoom) {
        prefs(context).edit()
                .putString(KEY_MAP_LAT, String.valueOf(lat))
                .putString(KEY_MAP_LON, String.valueOf(lon))
                .putFloat(KEY_MAP_ZOOM, zoom)
                .apply();
    }

    /** @return {lat, lon, zoom}，无记录时返回 null */
    public static double[] loadMapView(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.contains(KEY_MAP_LAT) || !p.contains(KEY_MAP_LON)) {
            return null;
        }
        try {
            double lat = Double.parseDouble(p.getString(KEY_MAP_LAT, "0"));
            double lon = Double.parseDouble(p.getString(KEY_MAP_LON, "0"));
            // (0,0) 说明上次保存时地图尚未初始化，按"未保存"处理，避免默认视野跑到几内亚湾
            if (Math.abs(lat) < 1e-6 && Math.abs(lon) < 1e-6) {
                return null;
            }
            return new double[]{lat, lon, p.getFloat(KEY_MAP_ZOOM, 16f)};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
