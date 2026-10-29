package com.vloc.motion.hidden;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 主 App 提供的“基站 / Wi-Fi 环境快照”（v0.4.0 起）。
 *
 * <p>契约（与主 App 固定，不要改）：
 * <pre>
 * content://com.vloc.motion.env/snapshot   exported、无权限
 *   mode        TEXT   replay | hide | off
 *   json        TEXT   mode=replay 时是 schema v1 环境 JSON
 *   updated_at  LONG
 * 无行 = 不干预（透传真实值）
 * </pre>
 *
 * <p>查询策略：惰性查询 + 3 秒缓存；用 {@link CancellationSignal} + 看门狗线程把单次查询限制在
 * {@link #QUERY_TIMEOUT_MS} 内；任何异常 / 超时 / 无行 / 解析失败都返回 null，调用方据此透传。
 */
final class EnvSnapshot {

    private static final String TAG = "[VLocHidden] ";

    static final String MODE_HIDE = "hide";
    static final String MODE_REPLAY = "replay";
    static final String MODE_OFF = "off";

    private static final Uri SNAPSHOT_URI =
            Uri.parse("content://com.vloc.motion.env/snapshot");
    private static final String COL_MODE = "mode";
    private static final String COL_JSON = "json";
    private static final String COL_UPDATED_AT = "updated_at";
    private static final String[] PROJECTION = {COL_MODE, COL_JSON, COL_UPDATED_AT};

    /** 快照缓存时长：主 App 写快照的频率不高，3 秒足够且不会拖慢目标 App。 */
    private static final long CACHE_TTL_MS = 3000L;

    /** 单次 provider 查询的硬超时（本地 provider，正常几毫秒；超时说明主 App 没在跑）。 */
    private static final long QUERY_TIMEOUT_MS = 800L;

    /** 防御异常 JSON：最多采纳多少条，避免被超大快照拖垮。 */
    private static final int MAX_CELLS = 16;
    private static final int MAX_WIFI = 16;

    private static final Object LOCK = new Object();
    private static volatile EnvSnapshot sCached;
    private static volatile long sCachedAtElapsed;
    private static ScheduledExecutorService sWatchdog;

    final String mode;
    final long updatedAt;
    final int mcc;
    final int mnc;
    final List<CellSpec> cells;
    final List<WifiSpec> wifi;

    private EnvSnapshot(String mode, long updatedAt, int mcc, int mnc,
                        List<CellSpec> cells, List<WifiSpec> wifi) {
        this.mode = mode;
        this.updatedAt = updatedAt;
        this.mcc = mcc;
        this.mnc = mnc;
        this.cells = cells;
        this.wifi = wifi;
    }

    boolean isActive() {
        return MODE_HIDE.equals(mode) || MODE_REPLAY.equals(mode);
    }

    boolean isHide() {
        return MODE_HIDE.equals(mode);
    }

    boolean isReplay() {
        return MODE_REPLAY.equals(mode);
    }

    /**
     * 读取快照（带 3 秒缓存）。
     *
     * @return 快照；null 表示“无行 / 查询失败 / 解析失败”，调用方应完全透传真实值
     */
    static EnvSnapshot get() {
        try {
            final long now = SystemClock.elapsedRealtime();
            final EnvSnapshot cached = sCached;
            if (cached != null && now - sCachedAtElapsed < CACHE_TTL_MS) {
                return cached;
            }
            synchronized (LOCK) {
                final long locked = SystemClock.elapsedRealtime();
                final EnvSnapshot again = sCached;
                if (again != null && locked - sCachedAtElapsed < CACHE_TTL_MS) {
                    return again;
                }
                final EnvSnapshot fresh = querySnapshot();
                sCached = fresh;
                sCachedAtElapsed = SystemClock.elapsedRealtime();
                return fresh;
            }
        } catch (Throwable t) {
            log("env snapshot get failed: " + t);
            return null;
        }
    }

    /** 真正去 provider 拉一次；失败 / 超时 / 无行返回 null。 */
    private static EnvSnapshot querySnapshot() {
        final Context context = currentApplication();
        if (context == null) {
            log("env snapshot: no Application context, passthrough");
            return null;
        }

        final CancellationSignal signal = new CancellationSignal();
        ScheduledFuture<?> watchdogTask = null;
        Cursor cursor = null;
        try {
            watchdogTask = watchdog().schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        signal.cancel();
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }, QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            cursor = context.getContentResolver()
                    .query(SNAPSHOT_URI, PROJECTION, null, null, null, signal);
            if (cursor == null || !cursor.moveToFirst()) {
                return null; // 无行 = 透传
            }
            final int modeIndex = cursor.getColumnIndex(COL_MODE);
            if (modeIndex < 0) {
                return null;
            }
            final String mode = cursor.getString(modeIndex);
            final int jsonIndex = cursor.getColumnIndex(COL_JSON);
            final String json = jsonIndex >= 0 ? cursor.getString(jsonIndex) : null;
            final int updatedIndex = cursor.getColumnIndex(COL_UPDATED_AT);
            final long updatedAt = updatedIndex >= 0 ? cursor.getLong(updatedIndex) : 0L;
            return parse(mode, json, updatedAt);
        } catch (Throwable t) {
            log("env snapshot query failed: " + t);
            return null; // 查询失败 / 超时 / 权限异常 → 透传
        } finally {
            if (watchdogTask != null) {
                try {
                    watchdogTask.cancel(false);
                } catch (Throwable ignored) {
                    // ignore
                }
            }
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    private static EnvSnapshot parse(String rawMode, String json, long updatedAt) {
        if (rawMode == null) {
            return null;
        }
        final String mode = rawMode.trim().toLowerCase(Locale.ROOT);
        if (MODE_OFF.equals(mode)) {
            return new EnvSnapshot(MODE_OFF, updatedAt, 460, 0,
                    Collections.<CellSpec>emptyList(), Collections.<WifiSpec>emptyList());
        }
        if (MODE_HIDE.equals(mode)) {
            return new EnvSnapshot(MODE_HIDE, updatedAt, 460, 0,
                    Collections.<CellSpec>emptyList(), Collections.<WifiSpec>emptyList());
        }
        if (!MODE_REPLAY.equals(mode)) {
            log("env snapshot: unknown mode '" + rawMode + "', passthrough");
            return null;
        }

        if (json == null || json.trim().length() == 0) {
            // mode=replay 却没有快照内容：主 App 状态不一致，宁可透传也不要把环境清空
            log("env snapshot: replay without json, passthrough");
            return null;
        }
        try {
            final JSONObject root = new JSONObject(json);
            final int mcc = root.optInt("mcc", 460);
            final int mnc = root.optInt("mnc", 0);

            final List<CellSpec> cells = new ArrayList<>();
            final JSONArray cellsArray = root.optJSONArray("cells");
            if (cellsArray != null) {
                for (int i = 0; i < cellsArray.length() && cells.size() < MAX_CELLS; i++) {
                    final JSONObject item = cellsArray.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    final CellSpec spec = parseCell(item, mcc, mnc);
                    if (spec != null) {
                        cells.add(spec);
                    }
                }
            }

            final List<WifiSpec> wifi = new ArrayList<>();
            final JSONArray wifiArray = root.optJSONArray("wifi");
            if (wifiArray != null) {
                for (int i = 0; i < wifiArray.length() && wifi.size() < MAX_WIFI; i++) {
                    final JSONObject item = wifiArray.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    final WifiSpec spec = parseWifi(item);
                    if (spec != null) {
                        wifi.add(spec);
                    }
                }
            }

            return new EnvSnapshot(MODE_REPLAY, updatedAt, mcc, mnc, cells, wifi);
        } catch (Throwable t) {
            log("env snapshot json parse failed: " + t);
            return null; // 解析失败 → 透传
        }
    }

    private static CellSpec parseCell(JSONObject item, int defaultMcc, int defaultMnc) {
        final String type = optStringOrNull(item, "type");
        if (type == null) {
            return null;
        }
        final String normalized = type.trim().toLowerCase(Locale.ROOT);
        if (!"gsm".equals(normalized) && !"lte".equals(normalized) && !"nr".equals(normalized)
                && !"wcdma".equals(normalized) && !"cdma".equals(normalized)) {
            return null;
        }
        final CellSpec spec = new CellSpec();
        spec.type = normalized;
        spec.mcc = item.optInt("mcc", defaultMcc);
        spec.mnc = item.optInt("mnc", defaultMnc);
        spec.lac = item.optInt("lac", -1);
        spec.cid = item.optInt("cid", -1);
        spec.tac = item.optInt("tac", -1);
        spec.ci = item.optInt("ci", -1);
        spec.nci = item.optLong("nci", -1L);
        spec.pci = item.optInt("pci", -1);
        spec.earfcn = item.optInt("earfcn", -1);
        spec.arfcn = item.optInt("arfcn", -1);
        spec.bsic = item.optInt("bsic", -1);
        spec.rssi = item.optInt("rssi", -70);
        spec.rsrp = item.optInt("rsrp", -95);
        spec.rsrq = item.optInt("rsrq", -10);
        spec.rssnr = item.optInt("rssnr", 10);
        spec.level = item.optInt("level", -1);
        return spec;
    }

    private static WifiSpec parseWifi(JSONObject item) {
        final String bssid = optStringOrNull(item, "bssid");
        final String ssid = optStringOrNull(item, "ssid");
        if (bssid == null && ssid == null) {
            return null;
        }
        final WifiSpec spec = new WifiSpec();
        spec.bssid = bssid == null ? null : bssid.trim().toLowerCase(Locale.ROOT);
        spec.ssid = ssid;
        spec.rssi = item.optInt("rssi", -60);
        spec.frequency = item.optInt("frequency", 2412);
        if (spec.frequency <= 0) {
            spec.frequency = 2412;
        }
        return spec;
    }

    private static String optStringOrNull(JSONObject item, String key) {
        if (!item.has(key) || item.isNull(key)) {
            return null;
        }
        final String value = item.optString(key, null);
        return value == null || value.length() == 0 ? null : value;
    }

    /** 拿当前进程的 Application（ActivityThread.currentApplication，@hide）。 */
    private static Context currentApplication() {
        try {
            final Class<?> activityThread = Class.forName("android.app.ActivityThread");
            final Object app = XposedHelpers.callStaticMethod(activityThread, "currentApplication");
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
            // 部分进程（isolated / 早期启动）拿不到，退回 AndroidAppHelper
        }
        try {
            final Class<?> helper = XposedHelpers.findClassIfExists(
                    "android.app.AndroidAppHelper", EnvSnapshot.class.getClassLoader());
            if (helper != null) {
                final Object app = XposedHelpers.callStaticMethod(helper, "currentApplication");
                if (app instanceof Context) {
                    return (Context) app;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    private static synchronized ScheduledExecutorService watchdog() {
        if (sWatchdog == null) {
            sWatchdog = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    final Thread thread = new Thread(runnable, "VLocHidden-env");
                    thread.setDaemon(true);
                    return thread;
                }
            });
        }
        return sWatchdog;
    }

    private static void log(String message) {
        try {
            XposedBridge.log(TAG + message);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 一条基站的描述（JSON cells[] 的解析结果，字段缺失用缺省值）。 */
    static final class CellSpec {
        String type;
        int mcc;
        int mnc;
        int lac;
        int cid;
        int tac;
        int ci;
        long nci;
        int pci;
        int earfcn;
        int arfcn;
        int bsic;
        int rssi;
        int rsrp;
        int rsrq;
        int rssnr;
        int level;
    }

    /** 一个 Wi-Fi AP 的描述（JSON wifi[] 的解析结果）。 */
    static final class WifiSpec {
        String bssid;
        String ssid;
        int rssi;
        int frequency;
    }
}
