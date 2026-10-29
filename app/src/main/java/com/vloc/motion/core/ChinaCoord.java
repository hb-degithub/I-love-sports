package com.vloc.motion.core;

/**
 * WGS-84 ↔ GCJ-02（"火星坐标"）转换。
 *
 * <p>国内地图（高德/腾讯/百度底图）使用 GCJ-02，而 Android Location、GPS、几乎所有
 * 定位 API 使用 WGS-84。把 WGS-84 直接画在 GCJ-02 瓦片上会偏移约 400–600 米，
 * 反之亦然。本项目：
 * <ul>
 *   <li>路线/坐标一律以 <b>WGS-84</b> 存储与注入（真实 GPS 语义）</li>
 *   <li>使用国内瓦片源（高德）显示时，临时转换成 GCJ-02</li>
 *   <li>地图点击/拖动得到的 GCJ-02 再转回 WGS-84 存储</li>
 * </ul>
 * 纯 Java，可单元测试。
 */
public final class ChinaCoord {

    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    private ChinaCoord() {
    }

    /** 是否在中国大陆范围外（境外不做偏移）。 */
    public static boolean outOfChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    /** WGS-84 → GCJ-02，返回 {lat, lon}。 */
    public static double[] wgs84ToGcj02(double lat, double lon) {
        if (outOfChina(lat, lon)) {
            return new double[]{lat, lon};
        }
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI);
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lat + dLat, lon + dLon};
    }

    /** GCJ-02 → WGS-84，返回 {lat, lon}（迭代逼近，误差 < 0.1 米）。 */
    public static double[] gcj02ToWgs84(double lat, double lon) {
        if (outOfChina(lat, lon)) {
            return new double[]{lat, lon};
        }
        double wgsLat = lat;
        double wgsLon = lon;
        for (int i = 0; i < 8; i++) {
            double[] gcj = wgs84ToGcj02(wgsLat, wgsLon);
            double dLat = gcj[0] - lat;
            double dLon = gcj[1] - lon;
            if (Math.abs(dLat) < 1e-9 && Math.abs(dLon) < 1e-9) {
                break;
            }
            wgsLat -= dLat;
            wgsLon -= dLon;
        }
        return new double[]{wgsLat, wgsLon};
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }
}
