package com.vloc.motion.location;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.vloc.motion.core.Fix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 通过系统测试 Provider 注入定位。
 *
 * 原理：调用 LocationManager.addTestProvider 接管同名 Provider（gps / network / fused），
 * 再用 setTestProviderLocation 写入伪造坐标。普通应用需要在 root 下先获得
 * android:mock_location appop（见 RootShell），或以 priv-app 形式安装。
 */
public final class TestProviderInjector {

    private static final String TAG = "VLocInjector";

    /** 默认接管 gps + network 两个 Provider。 */
    public static final List<String> DEFAULT_PROVIDERS =
            Arrays.asList("gps", "network");

    private final LocationManager locationManager;
    private final List<String> providers;
    private boolean prepared;

    public TestProviderInjector(Context context, List<String> providers) {
        this.locationManager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        List<String> list = new ArrayList<>();
        if (providers != null) {
            for (String p : providers) {
                if (p != null && !p.trim().isEmpty()) {
                    list.add(p.trim());
                }
            }
        }
        if (list.isEmpty()) {
            list.addAll(DEFAULT_PROVIDERS);
        }
        this.providers = list;
    }

    public List<String> getProviders() {
        return new ArrayList<>(providers);
    }

    public boolean isPrepared() {
        return prepared;
    }

    /**
     * 接管 Provider。
     *
     * @throws SecurityException 未获得模拟位置权限
     */
    // 旧版 addTestProvider 重载用的是 Criteria 常量（与 ProviderProperties 数值一致），lint 会误报
    @SuppressLint("WrongConstant")
    public void prepare() {
        for (String provider : providers) {
            try {
                locationManager.addTestProvider(provider,
                        false,   // requiresNetwork
                        false,   // requiresSatellite
                        false,   // requiresCell
                        false,   // hasMonetaryCost
                        true,    // supportsAltitude
                        true,    // supportsSpeed
                        true,    // supportsBearing
                        Criteria.POWER_LOW,
                        Criteria.ACCURACY_FINE);
                locationManager.setTestProviderEnabled(provider, true);
                Log.d(TAG, "test provider ready: " + provider);
            } catch (IllegalArgumentException alreadyExists) {
                // 之前已经添加过同名测试 Provider（例如上次进程被强杀），直接启用
                try {
                    locationManager.setTestProviderEnabled(provider, true);
                } catch (SecurityException e) {
                    throw e;
                } catch (Exception ignored) {
                    // 该 Provider 不是测试 Provider 时 enable 会失败，交给下面的确认逻辑报错
                }
            }
            // appops 被显式 deny 时框架可能「静默 return」，不抛异常也不会真正接管，
            // 所以必须二次确认，否则会出现「界面显示运行中但坐标根本没变」。
            if (!locationManager.isProviderEnabled(provider)) {
                throw new SecurityException("Provider 未接管成功：" + provider
                        + "（android:mock_location appop 可能未生效或被拒绝）");
            }
        }
        prepared = true;
    }

    /** 写入一次拟真定位（含海拔 / 垂直精度 / GNSS 附属数据）。 */
    public void push(Fix fix, long timeMs) {
        if (!prepared) {
            throw new IllegalStateException("injector not prepared");
        }
        long elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos();
        for (String provider : providers) {
            Location location = new Location(provider);
            location.setLatitude(fix.lat);
            location.setLongitude(fix.lon);
            location.setTime(timeMs);
            location.setElapsedRealtimeNanos(elapsedRealtimeNanos);
            location.setAccuracy(fix.accuracyMeters);
            location.setAltitude(fix.altitudeMeters);
            location.setSpeed(fix.speedMps);
            location.setBearing(fix.bearingDeg);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                location.setVerticalAccuracyMeters(fix.verticalAccuracyMeters);
                location.setSpeedAccuracyMetersPerSecond(Math.max(0.2f, fix.speedMps * 0.05f));
                location.setBearingAccuracyDegrees(3f);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+：写入平均海平面高度，部分应用会读 MSL 而不是椭球高
                location.setMslAltitudeMeters(fix.altitudeMeters);
                location.setMslAltitudeAccuracyMeters(fix.verticalAccuracyMeters);
            }
            if (fix.satellites > 0) {
                // 真实 GPS 定位常带这些 extras，缺失本身就是一种"假定位"特征
                Bundle extras = new Bundle();
                extras.putInt("satellites", fix.satellites);
                extras.putFloat("meanCn0", fix.meanCn0);
                extras.putFloat("maxCn0", fix.maxCn0);
                location.setExtras(extras);
            }
            try {
                locationManager.setTestProviderLocation(provider, location);
            } catch (SecurityException e) {
                throw e;
            } catch (Exception e) {
                Log.w(TAG, "push failed for " + provider + ": " + e);
            }
        }
    }

    /** 进程异常退出后清理残留的测试 Provider（尽力而为，不需要 root）。 */
    public static void forceCleanup(Context context, List<String> providerNames) {
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || providerNames == null) {
            return;
        }
        for (String name : providerNames) {
            try {
                lm.removeTestProvider(name);
                Log.i(TAG, "cleaned leftover test provider: " + name);
            } catch (Exception ignored) {
                // 不是测试 Provider 或权限不足：忽略
            }
        }
    }

    /** 还原系统真实定位（尽力而为）。 */
    public void release() {
        for (String provider : providers) {
            try {
                locationManager.setTestProviderEnabled(provider, false);
            } catch (Exception ignored) {
                // 忽略
            }
            try {
                locationManager.removeTestProvider(provider);
            } catch (Exception e) {
                Log.w(TAG, "remove test provider failed: " + provider + " " + e);
            }
        }
        prepared = false;
    }
}
