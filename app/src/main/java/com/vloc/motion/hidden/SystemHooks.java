package com.vloc.motion.hidden;

import android.content.Context;
import android.location.GnssStatus;
import android.location.Location;
import android.net.Uri;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 系统级注入：在 <b>system_server</b>（LSPosed 作用域「系统框架 / android」）内改写定位与 GNSS。
 *
 * <p>与进程内注入的区别：
 * <ul>
 *   <li>所有 App 都生效，不需要逐个勾选作用域；</li>
 *   <li>Location 由我们自己 new 出来，<b>不带 mock 标记</b>，所以 isMock/isFromMockProvider
 *       天然为 false，也不再需要 appops 授权；</li>
 *   <li>数据来源：主 App 的 ContentProvider
 *       {@code content://com.vloc.motion.env/location}（定位）与 {@code /gnss}（卫星）。</li>
 * </ul>
 *
 * <p>计步 / IMU / 摄像头<b>不在此处</b>：传感器事件由 native SensorService 分发、
 * 相机在 HAL/相机服务层，Java 层拦不到系统级，仍然走目标进程内 Hook。
 *
 * <p>安全约束：所有 hook 独立 try/catch；读 Provider 失败 / 未在模拟时一律透传真实定位，
 * 绝不阻断系统服务。
 */
final class SystemHooks {

    private static final String TAG = "[VLocHidden] ";

    private static final String LOCATION_URI = "content://com.vloc.motion.env/location";
    private static final String GNSS_URI = "content://com.vloc.motion.env/gnss";

    /** 定位快照缓存：Pro**vider 读取有 binder 开销，缓存 120ms 足够跟上 10Hz 注入。 */
    private static final long LOCATION_CACHE_MS = 120L;
    private static final long GNSS_CACHE_MS = 900L;

    private static volatile Context sContext;
    private static volatile long sLocationAt;
    private static volatile Snapshot sLocation;
    private static volatile long sGnssAt;
    private static volatile List<Satellite> sSatellites;

    private static volatile int sHookCount;
    private static volatile boolean sLoggedFirstReplace;
    private static volatile boolean sLoggedFirstGnss;

    private SystemHooks() {
    }

    /** 主 App 推来的一帧定位。 */
    private static final class Snapshot {
        final double lat;
        final double lon;
        final double alt;
        final float speed;
        final float bearing;
        final float accuracy;
        final int satellites;
        final long updatedAt;

        Snapshot(double lat, double lon, double alt, float speed, float bearing,
                 float accuracy, int satellites, long updatedAt) {
            this.lat = lat;
            this.lon = lon;
            this.alt = alt;
            this.speed = speed;
            this.bearing = bearing;
            this.accuracy = accuracy;
            this.satellites = satellites;
            this.updatedAt = updatedAt;
        }
    }

    /** 一颗卫星（与主 App gnss 数组字段一致）。 */
    private static final class Satellite {
        int svid;
        int constellation;
        float elevation;
        float azimuth;
        float cn0;
        float carrierHz;
        boolean usedInFix;
        boolean hasAlmanac;
        boolean hasEphemeris;
    }

    // ================================================================== 安装

    static int install(ClassLoader classLoader) {
        int hooked = 0;
        hooked += hookLocationDelivery(classLoader);
        hooked += hookLastLocation(classLoader);
        hooked += hookGnssDelivery(classLoader);
        sHookCount = hooked;
        log("system hooks=" + hooked + " (system_server 级定位/GNSS 注入)");
        report(hooked);
        return hooked;
    }

    // ================================================================== 定位下发

    private static int hookLocationDelivery(ClassLoader classLoader) {
        final String[] classes = {
                "com.android.server.location.LocationListenerTransport",
                "com.android.server.location.LocationManagerService$LocationListenerTransport",
                "com.android.server.location.provider.LocationProviderManager$LocationRegistration",
                "com.android.server.location.provider.LocationProviderManager$LocationRegistration$1"
        };
        int count = 0;
        for (String name : classes) {
            try {
                final Class<?> clazz = XposedHelpers.findClass(name, classLoader);
                // 逐个重载挂：既兼容 onLocationChanged(Location)，也兼容 (LocationResult)
                for (Method method : clazz.getDeclaredMethods()) {
                    if (!"onLocationChanged".equals(method.getName())) {
                        continue;
                    }
                    try {
                        XposedBridge.hookMethod(method, LOCATION_HOOK);
                        count++;
                    } catch (Throwable ignored) {
                        // 单个重载失败不影响其它
                    }
                }
                if (count > 0) {
                    log("hooked " + name + "#onLocationChanged");
                }
            } catch (Throwable ignored) {
                // 该类在此 Android 版本不存在：继续尝试下一个
            }
        }
        if (count == 0) {
            log("no location transport class found (版本差异)，改用 getLastLocation 兜底");
        }
        return count > 0 ? 1 : 0;
    }

    /** 替换投递给 App 的 Location / LocationResult；未在模拟时不改动。 */
    private static final XC_MethodHook LOCATION_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                final Snapshot snapshot = currentLocation();
                if (snapshot == null || param.args == null) {
                    return;
                }
                for (int i = 0; i < param.args.length; i++) {
                    final Object arg = param.args[i];
                    if (arg instanceof Location) {
                        param.args[i] = buildLocation(snapshot, ((Location) arg).getProvider());
                    } else if (isLocationResult(arg)) {
                        // LocationResult 是 @SystemApi（不在公开 SDK 里），只能反射构造
                        final List<Location> list = new ArrayList<>();
                        list.add(buildLocation(snapshot, "gps"));
                        final Object replaced = createLocationResult(list);
                        if (replaced != null) {
                            param.args[i] = replaced;
                        }
                    }
                }
                if (!sLoggedFirstReplace) {
                    sLoggedFirstReplace = true;
                    log("system location replaced: " + snapshot.lat + "," + snapshot.lon);
                }
            } catch (Throwable t) {
                // 出错就透传真实定位
            }
        }
    };

    /** getLastLocation / getCurrentLocation 的返回值也换掉（App 冷启动常先读 last known）。 */
    private static int hookLastLocation(ClassLoader classLoader) {
        final String[] classes = {
                "com.android.server.location.LocationManagerService",
                "com.android.server.location.provider.LocationProviderManager",
                "com.android.server.location.LocationManagerService$LocalService"
        };
        int count = 0;
        for (String name : classes) {
            try {
                final Class<?> clazz = XposedHelpers.findClass(name, classLoader);
                for (Method method : clazz.getDeclaredMethods()) {
                    final String methodName = method.getName();
                    if (!"getLastLocation".equals(methodName) && !"getCurrentLocation".equals(methodName)) {
                        continue;
                    }
                    if (method.getReturnType() != Location.class) {
                        continue;
                    }
                    try {
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    final Snapshot snapshot = currentLocation();
                                    if (snapshot == null) {
                                        return;
                                    }
                                    final Object result = param.getResult();
                                    final String provider = result instanceof Location
                                            ? ((Location) result).getProvider() : "gps";
                                    param.setResult(buildLocation(snapshot, provider));
                                } catch (Throwable t) {
                                    // 保留真实结果
                                }
                            }
                        });
                        count++;
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
        if (count > 0) {
            log("hooked getLastLocation/getCurrentLocation x" + count);
        }
        return 0;   // 不单独计数，避免日志夸大
    }

    /** 判断参数是否是 android.location.LocationResult（@SystemApi，不能用 instanceof）。 */
    private static boolean isLocationResult(Object arg) {
        return arg != null && "android.location.LocationResult".equals(arg.getClass().getName());
    }

    /** 反射构造 LocationResult（create(List&lt;Location&gt;)）。 */
    private static Object createLocationResult(List<Location> locations) {
        try {
            final Class<?> clazz = Class.forName("android.location.LocationResult");
            return XposedHelpers.callStaticMethod(clazz, "create", locations);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 用快照造一个不带 mock 标记的 Location。 */
    private static Location buildLocation(Snapshot snapshot, String provider) {
        final Location location = new Location(provider == null ? "gps" : provider);
        location.setLatitude(snapshot.lat);
        location.setLongitude(snapshot.lon);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        location.setAccuracy(snapshot.accuracy > 0 ? snapshot.accuracy : 5f);
        location.setAltitude(snapshot.alt);
        location.setSpeed(snapshot.speed);
        location.setBearing(snapshot.bearing);
        try {
            location.setVerticalAccuracyMeters(Math.max(1f, (snapshot.accuracy > 0
                    ? snapshot.accuracy : 5f) * 1.6f));
            location.setSpeedAccuracyMetersPerSecond(Math.max(0.2f, snapshot.speed * 0.05f));
            location.setBearingAccuracyDegrees(3f);
        } catch (Throwable ignored) {
            // 低版本没有这些 setter
        }
        if (snapshot.satellites > 0) {
            try {
                final android.os.Bundle extras = new android.os.Bundle();
                extras.putInt("satellites", snapshot.satellites);
                location.setExtras(extras);
            } catch (Throwable ignored) {
                // ignore
            }
        }
        return location;
    }

    // ================================================================== GNSS 卫星下发

    private static int hookGnssDelivery(ClassLoader classLoader) {
        final String[] classes = {
                "com.android.server.location.GnssStatusListenerTransport",
                "com.android.server.location.gnss.GnssStatusListenerTransport",
                "android.location.GnssStatus$GnssStatusListenerTransport"
        };
        int count = 0;
        for (String name : classes) {
            try {
                final Class<?> clazz = XposedHelpers.findClass(name, classLoader);
                for (Method method : clazz.getDeclaredMethods()) {
                    if (!"onSatelliteStatusChanged".equals(method.getName())) {
                        continue;
                    }
                    try {
                        XposedBridge.hookMethod(method, GNSS_HOOK);
                        count++;
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
                if (count > 0) {
                    log("hooked " + name + "#onSatelliteStatusChanged");
                    break;
                }
            } catch (Throwable ignored) {
                // 继续下一个
            }
        }
        return count > 0 ? 1 : 0;
    }

    private static final XC_MethodHook GNSS_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (currentLocation() == null) {
                    return;   // 未在模拟：透传真实卫星
                }
                final List<Satellite> satellites = currentSatellites();
                if (satellites == null || satellites.isEmpty() || param.args == null) {
                    return;
                }
                final GnssStatus status = buildGnssStatus(satellites);
                if (status == null) {
                    return;
                }
                for (int i = 0; i < param.args.length; i++) {
                    if (param.args[i] instanceof GnssStatus) {
                        param.args[i] = status;
                    }
                }
                if (!sLoggedFirstGnss) {
                    sLoggedFirstGnss = true;
                    log("system gnss replaced: satellites=" + satellites.size());
                }
            } catch (Throwable t) {
                // 出错透传
            }
        }
    };

    /** 用反射的 GnssStatus$Builder 造一套与主 App 一致的卫星（API 30+）。 */
    private static GnssStatus buildGnssStatus(List<Satellite> satellites) {
        try {
            final Class<?> builderClass = XposedHelpers.findClass(
                    "android.location.GnssStatus$Builder", GnssStatus.class.getClassLoader());
            final Object builder = XposedHelpers.newInstance(builderClass);
            for (Satellite sat : satellites) {
                XposedHelpers.callMethod(builder, "addSatellite",
                        sat.svid, sat.constellation, sat.cn0, sat.elevation, sat.azimuth,
                        sat.carrierHz, sat.usedInFix, sat.hasAlmanac, sat.hasEphemeris);
            }
            return (GnssStatus) XposedHelpers.callMethod(builder, "build");
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================== Provider 读取（带缓存）

    private static Snapshot currentLocation() {
        final long now = System.currentTimeMillis();
        if (now - sLocationAt < LOCATION_CACHE_MS) {
            return sLocation;
        }
        sLocationAt = now;
        Snapshot parsed = null;
        try {
            final Context context = systemContext();
            if (context != null) {
                try (android.database.Cursor cursor = context.getContentResolver()
                        .query(Uri.parse(LOCATION_URI), new String[]{"json"}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int column = cursor.getColumnIndex("json");
                        if (column < 0) {
                            column = 1;
                        }
                        final JSONObject root = new JSONObject(cursor.getString(column));
                        if (root.optBoolean("active", false)) {
                            parsed = new Snapshot(
                                    root.optDouble("lat"), root.optDouble("lon"),
                                    root.optDouble("alt"), (float) root.optDouble("speed"),
                                    (float) root.optDouble("bearing"),
                                    (float) root.optDouble("acc"),
                                    root.optInt("satellites", 0),
                                    root.optLong("updatedAt", 0L));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 读取失败：视为未模拟
        }
        sLocation = parsed;
        return parsed;
    }

    private static List<Satellite> currentSatellites() {
        final long now = System.currentTimeMillis();
        if (now - sGnssAt < GNSS_CACHE_MS) {
            return sSatellites;
        }
        sGnssAt = now;
        List<Satellite> parsed = null;
        try {
            final Context context = systemContext();
            if (context != null) {
                try (android.database.Cursor cursor = context.getContentResolver()
                        .query(Uri.parse(GNSS_URI), new String[]{"json"}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int column = cursor.getColumnIndex("json");
                        if (column < 0) {
                            column = 1;
                        }
                        final JSONArray array = new JSONArray(cursor.getString(column));
                        final List<Satellite> list = new ArrayList<>(array.length());
                        for (int i = 0; i < array.length(); i++) {
                            final JSONObject item = array.optJSONObject(i);
                            if (item == null) {
                                continue;
                            }
                            Satellite sat = new Satellite();
                            sat.svid = item.optInt("svid", 0);
                            if (sat.svid <= 0) {
                                continue;
                            }
                            sat.constellation = item.optInt("const", 1);
                            sat.elevation = (float) item.optDouble("ele", 0);
                            sat.azimuth = (float) item.optDouble("azi", 0);
                            sat.cn0 = (float) item.optDouble("cn0", 30);
                            sat.usedInFix = item.optBoolean("fix", true);
                            sat.hasAlmanac = item.optBoolean("alm", true);
                            sat.hasEphemeris = item.optBoolean("eph", true);
                            sat.carrierHz = (float) item.optDouble("cf", 1575.42e6);
                            list.add(sat);
                        }
                        if (!list.isEmpty()) {
                            parsed = list;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        sSatellites = parsed;
        return parsed;
    }

    /** system_server 里的系统 Context（ActivityThread.systemMain().getSystemContext()）。 */
    static Context systemContext() {
        Context context = sContext;
        if (context != null) {
            return context;
        }
        try {
            final Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = XposedHelpers.callStaticMethod(activityThread, "systemMain");
            if (thread == null) {
                thread = XposedHelpers.callStaticMethod(activityThread, "currentActivityThread");
            }
            if (thread != null) {
                context = (Context) XposedHelpers.callMethod(thread, "getSystemContext");
            }
        } catch (Throwable ignored) {
            // ignore
        }
        if (context != null) {
            sContext = context;
        }
        return context;
    }

    // ================================================================== 状态回报

    /** 把"系统级已生效"回报给主 App（复用相机状态通道的通用字段）。 */
    private static void report(int hooks) {
        // 仅记日志：系统级 hook 的状态通过 LSPosed 日志排查，
        // 主 App 不再需要相机/系统状态的独立上报通道（相机功能已移除）。
        log("system-level hooks active: " + hooks);
    }

    private static void log(String message) {
        try {
            XposedBridge.log(TAG + message);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
