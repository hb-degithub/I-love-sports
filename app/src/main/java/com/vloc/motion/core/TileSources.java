package com.vloc.motion.core;

import android.content.Context;

import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 地图瓦片源：国内默认高德，可切换 OSM / Carto。 */
public final class TileSources {

    public static final String PREF_KEY = "tile_source_id";
    public static final String DEFAULT_ID = "autonavi";

    /** 一个可选地图源。 */
    public static final class Source {
        public final String id;
        public final String label;
        public final ITileSource tileSource;
        /** 诊断用模板，{x}/{y}/{z} 会被替换 */
        public final String probeUrl;

        Source(String id, String label, ITileSource tileSource, String probeUrl) {
            this.id = id;
            this.label = label;
            this.tileSource = tileSource;
            this.probeUrl = probeUrl;
        }
    }

    private static final List<Source> SOURCES = new ArrayList<>();

    static {
        // 高德矢量（国内可用，无需 Key）
        SOURCES.add(new Source("autonavi", "高德地图（国内推荐）", new PlaceholderTileSource(
                "AutoNavi", 3, 19, 256, ".png",
                new String[]{
                        "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}",
                        "https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}",
                        "https://webrd03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}",
                        "https://webrd04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}"
                }),
                "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}"));
        // 高德卫星
        SOURCES.add(new Source("autonavi_sat", "高德卫星影像", new PlaceholderTileSource(
                "AutoNaviSat", 3, 19, 256, ".jpg",
                new String[]{
                        "https://webst01.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}",
                        "https://webst02.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}",
                        "https://webst03.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}",
                        "https://webst04.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}"
                }),
                "https://webst01.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}"));
        // OpenStreetMap
        SOURCES.add(new Source("osm", "OpenStreetMap（海外）", TileSourceFactory.MAPNIK,
                "https://tile.openstreetmap.org/{z}/{x}/{y}.png"));
        // Carto 浅色
        SOURCES.add(new Source("carto_light", "Carto 浅色（海外）", new PlaceholderTileSource(
                "CartoLight", 0, 19, 256, ".png",
                new String[]{"https://basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png"}),
                "https://basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png"));
    }

    private TileSources() {
    }

    public static List<Source> all() {
        return Collections.unmodifiableList(SOURCES);
    }

    public static Source byId(String id) {
        for (Source source : SOURCES) {
            if (source.id.equals(id)) {
                return source;
            }
        }
        return SOURCES.get(0);
    }

    public static String loadSelectedId(Context context) {
        return RouteStore.prefs(context).getString(PREF_KEY, DEFAULT_ID);
    }

    public static void saveSelectedId(Context context, String id) {
        RouteStore.prefs(context).edit().putString(PREF_KEY, id).apply();
    }

    public static ITileSource load(Context context) {
        return byId(loadSelectedId(context)).tileSource;
    }

    /** 当前瓦片源是否使用 GCJ-02 坐标系（高德系）。 */
    public static boolean usesGcj02(Context context) {
        return loadSelectedId(context).startsWith("autonavi");
    }

    /** WGS-84 → 当前地图坐标系（{lat, lon}）。 */
    public static double[] toMapLatLon(Context context, double lat, double lon) {
        return usesGcj02(context) ? ChinaCoord.wgs84ToGcj02(lat, lon)
                : new double[]{lat, lon};
    }

    /** 当前地图坐标系 → WGS-84（{lat, lon}）。 */
    public static double[] toWgsLatLon(Context context, double lat, double lon) {
        return usesGcj02(context) ? ChinaCoord.gcj02ToWgs84(lat, lon)
                : new double[]{lat, lon};
    }

    public static String labelOf(Context context) {
        return byId(loadSelectedId(context)).label;
    }
}
