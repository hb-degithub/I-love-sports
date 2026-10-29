package com.vloc.motion.core;

/** 球面地理计算，纯 Java，无 Android 依赖（可单元测试）。 */
public final class GeoMath {

    /** 地球平均半径（米），WGS84 平均半径。 */
    public static final double EARTH_RADIUS_M = 6371008.8;

    private GeoMath() {
    }

    /** 两点间大圆距离（米）。 */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** 起点指向终点的初始方位角，0=正北，顺时针 0..360。 */
    public static double initialBearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        double deg = Math.toDegrees(Math.atan2(y, x));
        return (deg + 360.0) % 360.0;
    }

    /**
     * 沿大圆弧在起终点之间插值。
     *
     * @param fraction 0..1
     * @return {latitude, longitude}
     */
    public static double[] interpolate(double lat1, double lon1, double lat2, double lon2,
                                       double fraction) {
        double f = Math.max(0.0, Math.min(1.0, fraction));
        if (f == 0.0) {
            return new double[]{lat1, lon1};
        }
        if (f == 1.0) {
            return new double[]{lat2, lon2};
        }
        double p1 = Math.toRadians(lat1);
        double l1 = Math.toRadians(lon1);
        double p2 = Math.toRadians(lat2);
        double l2 = Math.toRadians(lon2);

        double x1 = Math.cos(p1) * Math.cos(l1);
        double y1 = Math.cos(p1) * Math.sin(l1);
        double z1 = Math.sin(p1);
        double x2 = Math.cos(p2) * Math.cos(l2);
        double y2 = Math.cos(p2) * Math.sin(l2);
        double z2 = Math.sin(p2);

        double x = x1 + (x2 - x1) * f;
        double y = y1 + (y2 - y1) * f;
        double z = z1 + (z2 - z1) * f;
        double norm = Math.sqrt(x * x + y * y + z * z);
        if (norm < 1e-12) {
            return new double[]{lat1, lon1};
        }
        x /= norm;
        y /= norm;
        z /= norm;

        double lat = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, z))));
        double lon = Math.toDegrees(Math.atan2(y, x));
        return new double[]{lat, lon};
    }

    /** 经纬度是否在合法范围内。 */
    public static boolean isValid(double lat, double lon) {
        return lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0
                && !Double.isNaN(lat) && !Double.isNaN(lon);
    }

    /** 把经度规范到 -180..180。 */
    public static double normalizeLongitude(double lon) {
        double v = lon % 360.0;
        if (v > 180.0) {
            v -= 360.0;
        } else if (v < -180.0) {
            v += 360.0;
        }
        return v;
    }

    /** 度分秒 / 十进制格式化，保留 6 位小数。 */
    public static String format(double lat, double lon) {
        return String.format(java.util.Locale.US, "%.6f, %.6f", lat, lon);
    }
}
