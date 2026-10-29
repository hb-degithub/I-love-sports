package com.vloc.motion.location;

import android.content.Context;
import android.location.GnssStatus;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.vloc.motion.core.RouteStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 采集真实 GNSS 卫星状态（模拟开始前），或复用上次保存的快照。
 *
 * <p>目标 App（尤其校园跑类）会用 GnssStatus 判断"GPS 信号弱"，所以回放时
 * 要用本机真实卫星分布，而不是凭空造 12 颗。
 */
public final class GnssCollector {

    private static final String TAG = "VLocGnss";
    private static final long COLLECT_TIMEOUT_MS = 3000L;

    public interface Callback {
        void onSnapshot(String json);
    }

    private GnssCollector() {
    }

    /** 采集一次（最多 3 秒）；采不到就用上次保存的快照；都没有回调 null。 */
    public static void collect(Context context, Callback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            callback.onSnapshot(loadSaved(context));
            return;
        }
        final LocationManager lm =
                (LocationManager) context.getApplicationContext()
                        .getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            callback.onSnapshot(loadSaved(context));
            return;
        }
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] done = {false};
        final GnssStatus.Callback statusCallback = new GnssStatus.Callback() {
            @Override
            public void onSatelliteStatusChanged(GnssStatus status) {
                if (done[0] || status == null) {
                    return;
                }
                done[0] = true;
                String json = toJson(status);
                if (json != null) {
                    RouteStore.saveGnssSnapshot(context, json);
                }
                callback.onSnapshot(json != null ? json : loadSaved(context));
            }
        };
        try {
            lm.registerGnssStatusCallback(statusCallback, handler);
        } catch (SecurityException e) {
            Log.w(TAG, "registerGnssStatusCallback denied", e);
            callback.onSnapshot(loadSaved(context));
            return;
        } catch (RuntimeException e) {
            Log.w(TAG, "registerGnssStatusCallback failed", e);
            callback.onSnapshot(loadSaved(context));
            return;
        }
        handler.postDelayed(() -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            try {
                lm.unregisterGnssStatusCallback(statusCallback);
            } catch (RuntimeException ignored) {
                // 忽略
            }
            callback.onSnapshot(loadSaved(context));
        }, COLLECT_TIMEOUT_MS);
    }

    private static String toJson(GnssStatus status) {
        try {
            int count = status.getSatelliteCount();
            if (count <= 0) {
                return null;
            }
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("count", count);
            root.put("ttffMillis", 1200);
            root.put("updatedAt", System.currentTimeMillis());
            JSONArray svid = new JSONArray();
            JSONArray cn0 = new JSONArray();
            JSONArray elevation = new JSONArray();
            JSONArray azimuth = new JSONArray();
            JSONArray used = new JSONArray();
            JSONArray constellation = new JSONArray();
            for (int i = 0; i < count; i++) {
                svid.put(status.getSvid(i));
                cn0.put(Math.round(status.getCn0DbHz(i) * 10) / 10.0);
                elevation.put(Math.round(status.getElevationDegrees(i) * 10) / 10.0);
                azimuth.put(Math.round(status.getAzimuthDegrees(i) * 10) / 10.0);
                used.put(status.usedInFix(i));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    constellation.put(status.getConstellationType(i));
                } else {
                    constellation.put(1);
                }
            }
            root.put("svid", svid);
            root.put("cn0", cn0);
            root.put("elevation", elevation);
            root.put("azimuth", azimuth);
            root.put("usedInFix", used);
            root.put("constellation", constellation);
            return root.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    /** 上次保存的卫星快照（没有返回 null）。 */
    public static String loadSaved(Context context) {
        return RouteStore.loadGnssSnapshot(context);
    }
}
