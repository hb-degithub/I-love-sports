package com.vloc.motion.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

/**
 * 读取设备"真实位置"（非注入位置）。
 *
 * <p>优先返回 2 分钟内的 last known；否则向 GPS/网络请求一次单次定位，8 秒超时。
 * 注意：模拟运行中测试 Provider 会接管 gps/network，此时读到的是注入坐标。
 */
public final class RealLocationProvider {

    public interface Callback {
        void onLocation(Location location);

        void onError(String message);
    }

    private static final long TIMEOUT_MS = 8000L;

    private RealLocationProvider() {
    }

    public static boolean hasPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void request(final Context context, final Callback callback) {
        if (!hasPermission(context)) {
            callback.onError("未授予定位权限");
            return;
        }
        final LocationManager locationManager =
                (LocationManager) context.getApplicationContext()
                        .getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            callback.onError("系统定位服务不可用");
            return;
        }

        Location lastKnown = bestLastKnown(locationManager);
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.getTime() < 120000L) {
            callback.onLocation(lastKnown);
            return;
        }

        final String provider = pickProvider(locationManager);
        if (provider == null) {
            if (lastKnown != null) {
                callback.onLocation(lastKnown);
            } else {
                callback.onError("没有可用的定位 Provider，请检查定位开关");
            }
            return;
        }

        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] done = {false};
        final LocationListener[] listenerRef = new LocationListener[1];

        final Runnable timeout = () -> {
            if (!done[0]) {
                done[0] = true;
                unregister(locationManager, listenerRef[0]);
                callback.onError("定位超时：请确认系统定位已开启，或到室外/窗边重试");
            }
        };

        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (!done[0]) {
                    done[0] = true;
                    handler.removeCallbacks(timeout);
                    unregister(locationManager, listenerRef[0]);
                    callback.onLocation(location);
                }
            }

            @Override
            public void onStatusChanged(String providerName, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String providerName) {
            }

            @Override
            public void onProviderDisabled(String providerName) {
            }
        };
        listenerRef[0] = listener;
        handler.postDelayed(timeout, TIMEOUT_MS);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.getCurrentLocation(provider, null,
                        context.getMainExecutor(), location -> {
                            if (!done[0] && location != null) {
                                done[0] = true;
                                handler.removeCallbacks(timeout);
                                unregister(locationManager, listenerRef[0]);
                                callback.onLocation(location);
                            }
                        });
            } else {
                locationManager.requestSingleUpdate(provider, listener,
                        Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            handler.removeCallbacks(timeout);
            callback.onError("定位权限被拒绝");
        } catch (RuntimeException e) {
            handler.removeCallbacks(timeout);
            callback.onError("定位失败：" + e.getMessage());
        }
    }

    private static Location bestLastKnown(LocationManager locationManager) {
        Location best = null;
        for (String provider : new String[]{"gps", "network", "fused", "passive"}) {
            try {
                Location location = locationManager.getLastKnownLocation(provider);
                if (location != null && (best == null || location.getTime() > best.getTime())) {
                    best = location;
                }
            } catch (RuntimeException ignored) {
                // 忽略不可用 provider
            }
        }
        return best;
    }

    private static String pickProvider(LocationManager locationManager) {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return LocationManager.GPS_PROVIDER;
            }
        } catch (RuntimeException ignored) {
            // 继续
        }
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                return LocationManager.NETWORK_PROVIDER;
            }
        } catch (RuntimeException ignored) {
            // 继续
        }
        try {
            if (locationManager.isProviderEnabled("fused")) {
                return "fused";
            }
        } catch (RuntimeException ignored) {
            // 继续
        }
        return null;
    }

    private static void unregister(LocationManager locationManager, LocationListener listener) {
        if (listener == null) {
            return;
        }
        try {
            locationManager.removeUpdates(listener);
        } catch (RuntimeException ignored) {
            // 忽略
        }
    }
}
