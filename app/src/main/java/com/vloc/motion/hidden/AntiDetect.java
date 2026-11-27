package com.vloc.motion.hidden;

import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import com.vloc.motion.core.DetectionAvoidance;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.Locale;

import com.vloc.motion.hidden.compat.Callback;
import com.vloc.motion.hidden.compat.Param;
import com.vloc.motion.hidden.compat.Xp;

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

    /** 本类要挂的两个框架类；都在 boot classloader，走 {@link HookCompat} 统一解析 + 候选日志。 */
    private static final String LOCATION_CLASS = "android.location.Location";
    private static final String LOCATION_MANAGER_CLASS = "android.location.LocationManager";

    private static volatile boolean sLoggedProviders;
    private static final Map<Object, Executor> NMEA_LISTENERS = new WeakHashMap<>();
    private static final Runnable NMEA_TICK = AntiDetect::publishNmea;
    private static Handler nmeaHandler;
    private static volatile boolean sLoggedTime;
    private static volatile boolean sLoggedAccuracy;

    private AntiDetect() {
    }

    static int install(ClassLoader classLoader) {
        int hooked = hookNmea(classLoader);
        if (!StepReplay.snapshotActive()) {
            return hooked;
        }
        hooked += hookProviderList(classLoader);
        hooked += hookTimestampConsistency(classLoader);
        hooked += hookAccuracyJitter(classLoader);
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
            final Class<?> manager = locationManagerClass(classLoader);
            if (manager == null) {
                return 0;   // 类缺失：只记了候选日志，跳过本条 hook
            }
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
                    Xp.hook(method, new Callback() {
                        @Override
                        protected void after(Param param) {
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

    /** 仅为缺少单调时间的旧定位提供回退。已有的采样时间保持原值。 */
    private static int hookTimestampConsistency(ClassLoader classLoader) {
        try {
            final Class<?> location = locationClass(classLoader);
            if (location == null) {
                return 0;
            }
            Xp.hook(Xp.findMethodExact(location, "getElapsedRealtimeNanos"), new Callback() {
                @Override
                protected void after(Param param) {
                    try {
                        if (!(param.getResult() instanceof Long) || (Long) param.getResult() != 0L
                                || !StepReplay.snapshotActive()) {
                            return;
                        }
                        final Location fix = (Location) param.thisObject;
                        final long wall = fix.getTime();
                        final long ageMs = System.currentTimeMillis() - wall;
                        final long nowNanos = android.os.SystemClock.elapsedRealtimeNanos();
                        if (wall > 0L && ageMs >= 0L && ageMs <= nowNanos / 1_000_000L) {
                            final long elapsedNanos = nowNanos - ageMs * 1_000_000L;
                            fix.setElapsedRealtimeNanos(elapsedNanos);
                            param.setResult(elapsedNanos);
                            if (!sLoggedTime) {
                                sLoggedTime = true;
                                log("location missing elapsed realtime restored");
                            }
                        }
                    } catch (Throwable ignored) {
                        // 保留系统返回值
                    }
                }
            });
            return 1;
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
            final Class<?> location = locationClass(classLoader);
            if (location == null) {
                return 0;   // 类缺失：只记了候选日志，跳过本条 hook
            }
            Xp.hook(Xp.findMethodExact(location, "getAccuracy"), new Callback() {
                @Override
                protected void after(Param param) {
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
            final Class<?> manager = locationManagerClass(classLoader);
            if (manager == null) {
                return 0;   // 类缺失：只记了候选日志，跳过本条 hook
            }
            int count = 0;
            for (Method method : manager.getDeclaredMethods()) {
                final boolean add = "addNmeaListener".equals(method.getName());
                if (!add && !"removeNmeaListener".equals(method.getName())) {
                    continue;
                }
                try {
                    Xp.hook(method, new Callback() {
                        @Override
                        protected void after(Param param) {
                            if (param.args == null || param.hasThrowable()
                                    || Boolean.FALSE.equals(param.getResult())) {
                                return;
                            }
                            Object listener = null;
                            Executor executor = Runnable::run;
                            for (Object arg : param.args) {
                                if (arg instanceof android.location.OnNmeaMessageListener
                                        || arg instanceof android.location.GpsStatus.NmeaListener) {
                                    listener = arg;
                                } else if (arg instanceof Executor) {
                                    executor = (Executor) arg;
                                } else if (arg instanceof Handler) {
                                    executor = ((Handler) arg)::post;
                                }
                            }
                            if (listener != null) {
                                updateNmeaListener(listener, executor, add);
                            }
                        }
                    });
                    count++;
                } catch (Throwable ignored) {
                    // 此签名无法挂载时保留原生 NMEA 回调。
                }
            }
            return count > 0 ? 1 : 0;
        } catch (Throwable t) {
            log("hookNmea failed: " + t);
            return 0;
        }
    }

    private static void updateNmeaListener(Object listener, Executor executor, boolean add) {
        synchronized (NMEA_LISTENERS) {
            if (add) {
                if (nmeaHandler == null) {
                    final Looper looper = Looper.getMainLooper();
                    if (looper == null) {
                        return;
                    }
                    nmeaHandler = new Handler(looper);
                }
                final boolean first = NMEA_LISTENERS.isEmpty();
                NMEA_LISTENERS.put(listener, executor);
                if (first) {
                    nmeaHandler.removeCallbacks(NMEA_TICK);
                    nmeaHandler.post(NMEA_TICK);
                }
            } else {
                NMEA_LISTENERS.remove(listener);
                if (nmeaHandler != null && NMEA_LISTENERS.isEmpty()) {
                    nmeaHandler.removeCallbacks(NMEA_TICK);
                }
            }
        }
    }

    /** 每个进程只读取一次同帧数据，再向仍注册的监听器发布。 */
    private static void publishNmea() {
        final Map<Object, Executor> listeners;
        synchronized (NMEA_LISTENERS) {
            if (NMEA_LISTENERS.isEmpty()) {
                return;
            }
            listeners = new HashMap<>(NMEA_LISTENERS);
            nmeaHandler.postDelayed(NMEA_TICK, 1000L);
        }
        final org.json.JSONObject frame = currentLocationFrame();
        if (frame == null) {
            return;
        }
        final long timeMs = frame.optLong("updatedAt", 0L);
        final double lat = frame.optDouble("lat");
        final double lon = frame.optDouble("lon");
        final long ageMs = System.currentTimeMillis() - timeMs;
        if (timeMs <= 0L || ageMs < 0L || ageMs > 5_000L
                || !Double.isFinite(lat) || !Double.isFinite(lon)) {
            return;
        }
        final String gga = DetectionAvoidance.buildGga(lat, lon, timeMs,
                frame.optInt("satellites", 0), frame.optDouble("alt", 0.0));
        final String rmc = DetectionAvoidance.buildRmc(lat, lon, timeMs,
                frame.optDouble("speed", 0.0), frame.optDouble("bearing", 0.0));
        for (Map.Entry<Object, Executor> entry : listeners.entrySet()) {
            final Object listener = entry.getKey();
            final Executor executor = entry.getValue();
            try {
                executor.execute(() -> {
                    synchronized (NMEA_LISTENERS) {
                        if (NMEA_LISTENERS.get(listener) != executor) {
                            return;
                        }
                    }
                    if (System.currentTimeMillis() - timeMs <= 5_000L) {
                        deliverNmea(listener, gga, timeMs);
                        deliverNmea(listener, rmc, timeMs);
                    }
                });
            } catch (RuntimeException e) {
                log("NMEA callback executor rejected delivery: " + e.getClass().getSimpleName());
            }
        }
    }

    private static org.json.JSONObject currentLocationFrame() {
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
                return json;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static void deliverNmea(Object listener, String sentence, long timeMs) {
        try {
            if (listener instanceof android.location.OnNmeaMessageListener) {
                ((android.location.OnNmeaMessageListener) listener).onNmeaMessage(sentence, timeMs);
                return;
            }
            // 旧接口 android.location.GpsStatus$NmeaListener
            Xp.callMethod(listener, "onNmeaReceived", timeMs, sentence);
        } catch (Throwable t) {
            log("deliverNmea failed: " + t);
        }
    }

    /** {@code $GPGGA}：时间、经纬度、定位质量、卫星数、HDOP、海拔。 */
    
    /** {@code $GPRMC}：时间、状态、经纬度、速度、航向、日期。 */
    
    /** 坐标 → NMEA 的 {@code ddmm.mmmm,N/S} 形式。 */
    
    
    /** 加 {@code $} 前缀与 XOR 校验和，组成完整 NMEA 句子。 */
    
    /**
     * 解析 {@code android.location.Location}；缺失返回 null。
     *
     * <p>候选表只有一项（API 1 起就存在），仍统一走 {@link HookCompat#find}：任何 Throwable
     * 都只变成一条 {@code hook candidate … found=false} 日志 + 本条 hook 跳过，绝不抛到框架层。
     */
    private static Class<?> locationClass(ClassLoader classLoader) {
        final Class<?> clazz = HookCompat.find(classLoader, LOCATION_CLASS);
        if (clazz == null) {
            HookCompat.logCandidate(LOCATION_CLASS, false, 0, "anti-detect", "class absent");
        }
        return clazz;
    }

    /** 解析 {@code android.location.LocationManager}；缺失返回 null（同上）。 */
    private static Class<?> locationManagerClass(ClassLoader classLoader) {
        final Class<?> clazz = HookCompat.find(classLoader, LOCATION_MANAGER_CLASS);
        if (clazz == null) {
            HookCompat.logCandidate(LOCATION_MANAGER_CLASS, false, 0, "anti-detect", "class absent");
        }
        return clazz;
    }

    private static void log(String message) {
        try {
            Xp.log(message);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
