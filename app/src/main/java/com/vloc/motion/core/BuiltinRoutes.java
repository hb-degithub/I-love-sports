package com.vloc.motion.core;

/**
 * 内置路线：用户提供的 5 点田径场轨迹。
 *
 * <p><b>坐标口径（已用高德卫星图实地核对）</b>：用户 JSON 中
 * {@code glat/glon} 才是 GCJ-02 地图坐标——把它直接叠到高德卫星图上，
 * 正中对准一个标准红色田径场 + 足球场；而 {@code lat/lon} 相对它多出
 * 约 (+0.0061, +0.0065) 的常数偏移，落在旁边山林里，不能用。
 *
 * <p>因此：以 {@code glat/glon}（GCJ-02）为权威输入 → 精确转换成 WGS-84 存储
 * （注入给系统的是真实 GPS 坐标）→ 显示在国内底图时再转回 GCJ-02，
 * 与用户当时看到的操场位置完全重合。路线已首尾相连（闭环）。
 */
public final class BuiltinRoutes {

    public static final String TRACK_NAME = "田径场（示例）";

    /** 用户 JSON 中的 5 个点（glat/glon，GCJ-02 地图坐标，lat, lon）。 */
    private static final double[][] GCJ_POINTS = {
            {38.895602, 121.53375},    // 田径场1
            {38.894902, 121.53395},    // 田径场2
            {38.895014, 121.535287},   // 田径场3
            {38.895672, 121.535413},   // 田径场4
            {38.895702, 121.534518}    // 田径场5
    };

    private BuiltinRoutes() {
    }

    /** 用户原始 GCJ-02 坐标（副本）。 */
    public static double[][] gcjPoints() {
        double[][] copy = new double[GCJ_POINTS.length][];
        for (int i = 0; i < GCJ_POINTS.length; i++) {
            copy[i] = new double[]{GCJ_POINTS[i][0], GCJ_POINTS[i][1]};
        }
        return copy;
    }

    /** 转为 WGS-84 并首尾相连的路线。 */
    public static Route track() {
        Route route = new Route();
        for (double[] gcj : GCJ_POINTS) {
            double[] wgs = ChinaCoord.gcj02ToWgs84(gcj[0], gcj[1]);
            route.add(new Route.Point(wgs[0], wgs[1]));
        }
        // 首尾相连，形成闭环
        if (route.size() >= 2) {
            Route.Point first = route.get(0);
            route.add(new Route.Point(first.lat, first.lon));
        }
        return route;
    }

    /** 锚点（用户 JSON 中的 anchor，GCJ-02 口径，位于操场西北侧）。 */
    public static double anchorLat() {
        return 38.896592;
    }

    public static double anchorLon() {
        return 121.532861;
    }
}
