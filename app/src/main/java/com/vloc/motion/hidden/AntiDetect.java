package com.vloc.motion.hidden;

import android.location.Location;
import android.location.LocationManager;

import com.vloc.motion.core.DetectionAvoidance;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 反检测补强（v0.38.0）：补齐 {@link HookEntry} 之外几类常见「模拟位置指纹」。
 *
 * <p>对应 {@code docs/mock-detection.md} 里此前标记为未覆盖的项：
 * <ol>
 *   <li><b>provider 列表指纹</b>：{@code getAllProviders()} / {@code getProviders(...)} 可能
 *       暴露测试 Provider 名字或异常组合 → 规范化成真机的 gps/network/fused/passive；</li>
 *   <li><b>时间戳一致性</b>：检测方常用 {@code elapsedRealtimeNanos} 与 {@code time} 的偏差判断
 *       「定位是否在正常刷新」→ 保证两套时钟同步推进且不过期；</li>
 *   <li><b>精度恒定</b>：{@code accuracy} 长期不变是典型 mock 特征 → 兜底加轻微抖动
 *       （主 App 已做拟真，这里防"某条路径漏了"）；</li>
 *   <li><b>NMEA 语句</b>：部分 App 读 {@code addNmeaListener} 判断有无真实 GNSS →
 *       回放与虚拟坐标一致的 {@code $GPGGA/$GPRMC} 句子。</li>
 * </ol>
 *
 * <p>所有 hook 独立 try/catch；未在模拟（快照 inactive）时一律透传真实值。
 */
final class AntiDetect {

    private static final String TAG = "[VLocHidden] ";

    private static volatile boolean sLoggedProviders;
    private static volatile boolean sLoggedNmea;
    private static volatile boolean sLoggedTime;
    private static volatile boolean sLoggedAccuracy;

    private AntiDetect() {
    }

    static int install(ClassLoader classLoader) {
        final boolean active = StepReplay.snapshotActive();
        if (!active) {
            // 未在模拟：不安装任何改写，避免对真实定位产生任何影响
            log("anti-detect skipped (not simulating)");
            return 0;
        }
        int hooked = 0;
        hooked += hookProviderList(classLoader);
        hooked += hookTimestampConsistency(classLoader);
        hooked += hookAccuracyJitter(classLoader);
        hooked += hookNmea(classLoader);
        log("anti-detect hooks=" + hooked + "/4");
        return hooked;
    }

    // ============================================================ 1. provider 列表

    /**
     * 让 {@code getAllProviders()} / {@code getProviders(...)} 只返回真机常见组合。
     *
     * <p>作用：如果主 App（或其它模块）创建过自定义 Provider 名，会在这里被过滤掉；
     * 同时保证顺序与真机一致（passive → gps → network → fused）。
     */
    private static int hookProviderList(ClassLoader classLoader) {
        try {
            Class<?> manager = XposedHelpers.findClass(
                    "android.location.LocationManager", classLoader);
            int count = 0;
            for (Method method : manager.getDeclaredMethods()) {
                String name = method.getName();
                if (!"getAllProviders".equals(name) && !"getProviders".equals(name)
                        && !"getProvider".equals(name) && !"isProviderEnabled".equals(name)) {
                    continue;
                }
                if (method.getReturnType() != List.class
                        && method.getReturnType() != boolean.class) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!StepReplay.snapshotActive()) {
                                    return;
                                }
                                Object result = param.getResult();
                                if (result instanceof List) {
                                    @SuppressWarnings("unchecked")
                                    List<String> actual = (List<String>) result;
                                    List<String> normalized = DetectionAvoidance.normalizeProviders(actual);
                                    if (!normalized.equals(actual)) {
                                        param.setResult(normalized);
                                        if (!sLoggedProviders) {
                                            sLoggedProviders = true;
                                            log("provider list normalized: " + actual
                                                    + " -> " + normalized);
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }
                    });
                    count++;
                } catch (Throwable ignored) {
                    // 单个重载失败不影响其它
                }
            }
            if (count > 0) {
                log("hooked LocationManager provider queries x" + count);
            }
            return count > 0 ? 1 : 0;
        } catch (Throwable t) {
            log("hookProviderList failed: " + t);
            return 0;
        }
    }

    /**
     * 规范化 provider 列表：去掉自定义/测试名，补回真机应有的项。
     *
     * <p>保留顺序 passive → gps → network → fused，多余项丢弃。
     */
    
    
    // ============================================================ 2. 时间戳一致性

    /**
     * 保证 {@code Location.elapsedRealtimeNanos} 与 {@code time} 同步推进。
     *
     * <p>检测思路：真实 GPS 每帧两套时钟一起前进；mock 若只写其中一个（或都固定），
     * 差值会漂移。这里在读取时按"当前时刻"校正，使差值始终等于开机以来的固定偏移。
     */
    private static int hookTimestampConsistency(ClassLoader classLoader) {
        try {
            Class<?> location = XposedHelpers.findClass("android.location.Location", classLoader);
            int count = 0;
            for (String getter : new String[]{"getElapsedRealtimeNanos", "getElapsedRealtimeUncertaintyNanos"}) {
                try {
                    XposedHelpers.findAndHookMethod(location, getter, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!StepReplay.snapshotActive()) {
                                    return;
                                }
                                final Location self = (Location) param.thisObject;
                                if (self == null) {
                                    return;
                                }
                                // 用墙钟时间反推"应该"的 elapsedRealtime，使两者差值恒定
                                long wall = self.getTime();
                                if (wall <= 0) {
                                    return;
                                }
                                long drift = System.currentTimeMillis() - wall;
                                // 只修正明显不一致（>300ms），避免每帧都改动引入抖动
                                if (drift > 300L) {
                                    long expectedNanos = android.os.SystemClock.elapsedRealtimeNanos()
                                            - drift * 1_000_000L;
                                    if (expectedNanos > 0) {
                                        self.setElapsedRealtimeNanos(expectedNanos);
                                        if (!sLoggedTime) {
                                            sLoggedTime = true;
                                            log("location timestamp drift corrected: " + drift + "ms");
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }
                    });
                    count++;
                } catch (Throwable ignored) {
                    // 该 getter 在此版本不存在
                }
            }
            return count > 0 ? 1 : 0;
        } catch (Throwable t) {
            log("hookTimestampConsistency failed: " + t);
            return 0;
        }
    }

    // ============================================================ 3. 精度抖动兜底

    /**
     * {@code getAccuracy()} 兜底抖动：避免"每次都是 5.0m"这种完美恒定值。
     *
     * <p>主 App 的拟真模式已让精度浮动，这里是防止某些注入路径（如系统级快照）漏掉。
     */
    private static int hookAccuracyJitter(ClassLoader classLoader) {
        try {
            Class<?> location = XposedHelpers.findClass("android.location.Location", classLoader);
            XposedHelpers.findAndHookMethod(location, "getAccuracy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!StepReplay.snapshotActive()) {
                            return;
                        }
                        Object result = param.getResult();
                        if (!(result instanceof Float)) {
                            return;
                        }
                        float accuracy = (Float) result;
                        if (accuracy <= 0f) {
                            return;
                        }
                        // ±12% 抖动，保持量级不变
                        float jitter = 1f + (float) ((Math.random() - 0.5) * 0.24);
                        float adjusted = accuracy * jitter;
                        if (Math.abs(adjusted - accuracy) > 0.01f) {
                            param.setResult(adjusted);
                            if (!sLoggedAccuracy) {
                                sLoggedAccuracy = true;
                                log("accuracy jitter applied: " + accuracy + " -> " + adjusted);
                            }
                        }
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            });
            return 1;
        } catch (Throwable t) {
            log("hookAccuracyJitter failed: " + t);
            return 0;
        }
    }

    // ============================================================ 4. NMEA 语句

    /**
     * 回放 NMEA 句子（{@code $GPGGA} / {@code $GPRMC}）。
     *
     * <p>部分 App 用 {@code addNmeaListener} 判断"有没有真实 GNSS"：室内真实设备通常没有句子，
     * 但如果它同时拿到了 gps 定位却没有 NMEA，就会矛盾。这里按虚拟坐标生成一致的句子。
     */
    private static int hookNmea(ClassLoader classLoader) {
        try {
            Class<?> manager = XposedHelpers.findClass(
                    "android.location.LocationManager", classLoader);
            // GpsStatus.NmeaListener（旧接口）与 OnNmeaMessageListener（API 24+）
            int count = 0;
            for (Method method : manager.getDeclaredMethods()) {
                if (!"addNmeaListener".equals(method.getName())) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!StepReplay.snapshotActive() || param.args == null) {
                                    return;
                                }
                                Object listener = param.args[0];
                                if (listener == null) {
                                    return;
                                }
                                startNmeaFeeder(listener);
                                if (!sLoggedNmea) {
                                    sLoggedNmea = true;
                                    log("NMEA feeder started for " + listener.getClass().getName());
                                }
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }
                    });
                    count++;
                } catch (Throwable ignored) {
                    // ignore
                }
            }
            return count > 0 ? 1 : 0;
        } catch (Throwable t) {
            log("hookNmea failed: " + t);
            return 0;
        }
    }

    /** 每秒给监听者投递一条 GGA + RMC，坐标来自当前快照。 */
    private static void startNmeaFeeder(final Object listener) {
        final android.os.Handler handler =
                new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!StepReplay.snapshotActive()) {
                        return;   // 模拟结束：停止投递，交回真实 NMEA
                    }
                    final StepReplay.StepsSnapshot snapshot = StepReplay.currentSnapshot();
                    final double[] latLon = currentLatLon();
                    if (latLon != null) {
                        final long now = System.currentTimeMillis();
                        deliverNmea(listener, DetectionAvoidance.buildGga(latLon[0], latLon[1], now, 10, 50.0));
                        deliverNmea(listener, DetectionAvoidance.buildRmc(latLon[0], latLon[1], now, 0.0, 0.0));
                    }
                    handler.postDelayed(this, 1000L);
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        });
    }

    private static double[] currentLatLon() {
        try {
            final android.content.Context context = StepReplay.currentApplication();
            if (context == null) {
                return null;
            }
            try (android.database.Cursor cursor = context.getContentResolver().query(
                    android.net.Uri.parse("content://com.vloc.motion.env/location"),
                    new String[]{"json"}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) {
                    return null;
                }
                int column = cursor.getColumnIndex("json");
                if (column < 0) {
                    column = 1;
                }
                org.json.JSONObject json = new org.json.JSONObject(cursor.getString(column));
                if (!json.optBoolean("active", false)) {
                    return null;
                }
                return new double[]{json.optDouble("lat"), json.optDouble("lon")};
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static void deliverNmea(Object listener, String sentence) {
        try {
            if (listener instanceof android.location.OnNmeaMessageListener) {
                ((android.location.OnNmeaMessageListener) listener).onNmeaMessage(sentence, 0L);
                return;
            }
            // 旧接口 android.location.GpsStatus$NmeaListener
            XposedHelpers.callMethod(listener, "onNmeaReceived", 0L, sentence);
        } catch (Throwable t) {
            log("deliverNmea failed: " + t);
        }
    }

    /** {@code $GPGGA}：时间、经纬度、定位质量、卫星数、HDOP、海拔。 */
    
    /** {@code $GPRMC}：时间、状态、经纬度、速度、航向、日期。 */
    
    /** 坐标 → NMEA 的 {@code ddmm.mmmm,N/S} 形式。 */
    
    
    /** 加 {@code $} 前缀与 XOR 校验和，组成完整 NMEA 句子。 */
    
    private static void log(String message) {
        try {
            XposedBridge.log(TAG + message);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
