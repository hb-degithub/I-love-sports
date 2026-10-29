package com.vloc.motion.ui;

import org.osmdroid.tileprovider.MapTileProviderBase;
import org.osmdroid.tileprovider.MapTileProviderBasic;
import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck;
import org.osmdroid.views.MapView;

import java.lang.reflect.Field;

/**
 * osmdroid 的下载器会先查询系统的"网络是否可用"（ConnectivityManager），
 * 在部分 ROM / VPN / 双卡 5G 场景下该判断会误报为不可用，导致瓦片一张都不下载（地图网格）。
 * 这里把该检查替换为"始终可用"——真正能否下载由 HTTP 请求本身决定。
 */
public final class TileProviderTuner {

    private static final INetworkAvailablityCheck ALWAYS_AVAILABLE = new INetworkAvailablityCheck() {
        @Override
        public boolean getNetworkAvailable() {
            return true;
        }

        @Override
        public boolean getWiFiNetworkAvailable() {
            return true;
        }

        @Override
        public boolean getCellularDataNetworkAvailable() {
            return true;
        }

        @Override
        public boolean getRouteToPathExists(int hostAddress) {
            return true;
        }
    };

    private static volatile boolean applied;

    private TileProviderTuner() {
    }

    /** 是否成功替换了 osmdroid 的网络检查（用于诊断）。 */
    public static boolean isApplied() {
        return applied;
    }

    public static void relaxNetworkCheck(MapView mapView) {
        try {
            MapTileProviderBase base = mapView.getTileProvider();
            if (!(base instanceof MapTileProviderBasic)) {
                return;
            }
            setField(base, "mNetworkAvailabilityCheck", ALWAYS_AVAILABLE);
            Object downloader = readField(base, "mDownloaderProvider");
            if (downloader != null) {
                setField(downloader, "mNetworkAvailablityCheck", ALWAYS_AVAILABLE);
            }
            applied = true;
        } catch (Throwable ignored) {
            // 反射失败不影响主流程
        }
    }

    private static Object readField(Object target, String name) {
        try {
            Field field = findField(target.getClass(), name);
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = findField(target.getClass(), name);
            if (field == null) {
                return;
            }
            field.setAccessible(true);
            field.set(target, value);
        } catch (Throwable ignored) {
            // 某些 ART 版本禁止改 final 字段时静默跳过
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续找父类
            }
        }
        return null;
    }
}
