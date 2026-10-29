package com.vloc.motion.core;

/**
 * 简化轨道传播：TLE 平均根数 + J2 长期项 → 卫星 ECEF → 观测点仰角/方位角。
 *
 * <p>精度取舍：TLE 的平均根数来自 SGP4 拟合，这里只做 J2 长期摄动（不做大气阻力与短周期项）。
 * 对 MEO/IGSO 卫星（轨道高度 ~2 万公里），数天内的星下点误差在几十公里量级，折算到
 * 观测点仰角/方位角误差通常 &lt; 0.5°，足够让"目标 App 看到的卫星几何"与真实一致。
 *
 * <p>纯 Java、无 Android 依赖，可单元测试。
 */
final class GnssOrbit {

    private static final double MU = 3.986004418e14;      // 地球引力常数 m³/s²
    private static final double EARTH_RADIUS = 6378137.0; // WGS-84 长半轴
    private static final double J2 = 1.08262668e-3;
    private static final double FLATTENING = 1.0 / 298.257223563;
    private static final double E2 = FLATTENING * (2 - FLATTENING);

    private GnssOrbit() {
    }

    /** 卫星 ECEF 坐标（米）。 */
    static double[] ecef(GnssTle.Sat sat, long utcMs) {
        final double n0 = sat.meanMotionRevPerDay * 2 * Math.PI / 86400.0; // rad/s
        final double a = Math.cbrt(MU / (n0 * n0));                        // 米
        final double e = sat.eccentricity;
        final double inc = Math.toRadians(sat.inclinationDeg);
        final double sinI = Math.sin(inc);
        final double cosI = Math.cos(inc);
        final double p = a * (1 - e * e);

        // J2 长期摄动：升交点赤经、近地点幅角、平运动
        final double j2Factor = 1.5 * J2 * (EARTH_RADIUS / p) * (EARTH_RADIUS / p) * n0;
        final double raanRate = -j2Factor * cosI;
        final double argpRate = j2Factor * (2 - 2.5 * sinI * sinI);
        final double n = n0 * (1 + 1.5 * J2 * (EARTH_RADIUS / p) * (EARTH_RADIUS / p)
                * Math.sqrt(1 - e * e) * (1 - 1.5 * sinI * sinI));

        final double dt = (utcMs - sat.epochUtcMs) / 1000.0;
        final double raan = Math.toRadians(sat.raanDeg) + raanRate * dt;
        final double argp = Math.toRadians(sat.argPerigeeDeg) + argpRate * dt;
        final double meanAnomaly = wrap2Pi(Math.toRadians(sat.meanAnomalyDeg) + n * dt);

        // 解开普勒方程
        double eccAnomaly = meanAnomaly;
        for (int k = 0; k < 8; k++) {
            final double delta = (eccAnomaly - e * Math.sin(eccAnomaly) - meanAnomaly)
                    / (1 - e * Math.cos(eccAnomaly));
            eccAnomaly -= delta;
            if (Math.abs(delta) < 1e-12) {
                break;
            }
        }
        final double xv = a * (Math.cos(eccAnomaly) - e);
        final double yv = a * Math.sqrt(1 - e * e) * Math.sin(eccAnomaly);
        final double r = Math.hypot(xv, yv);
        final double trueAnomaly = Math.atan2(yv, xv);
        final double u = argp + trueAnomaly;   // 升交点到卫星的角距

        final double cosU = Math.cos(u);
        final double sinU = Math.sin(u);
        final double cosRaan = Math.cos(raan);
        final double sinRaan = Math.sin(raan);
        // 惯性系（TEME 近似）位置
        final double xi = r * (cosRaan * cosU - sinRaan * sinU * cosI);
        final double yi = r * (sinRaan * cosU + cosRaan * sinU * cosI);
        final double zi = r * (sinU * sinI);

        // 惯性系 → 地固系（只做 GMST 旋转）
        final double gmst = gmstRad(utcMs);
        final double cosG = Math.cos(gmst);
        final double sinG = Math.sin(gmst);
        return new double[]{
                xi * cosG + yi * sinG,
                -xi * sinG + yi * cosG,
                zi
        };
    }

    /**
     * 观测点看卫星的方位角/仰角/距离。
     *
     * @return {方位角(度, 0=正北, 顺时针), 仰角(度), 距离(米)}
     */
    static double[] azEl(GnssTle.Sat sat, double latDeg, double lonDeg, double heightM, long utcMs) {
        final double[] satEcef = ecef(sat, utcMs);
        final double lat = Math.toRadians(latDeg);
        final double lon = Math.toRadians(lonDeg);
        final double sinLat = Math.sin(lat);
        final double cosLat = Math.cos(lat);
        final double sinLon = Math.sin(lon);
        final double cosLon = Math.cos(lon);

        // 观测点 ECEF
        final double n = EARTH_RADIUS / Math.sqrt(1 - E2 * sinLat * sinLat);
        final double ox = (n + heightM) * cosLat * cosLon;
        final double oy = (n + heightM) * cosLat * sinLon;
        final double oz = (n * (1 - E2) + heightM) * sinLat;

        final double dx = satEcef[0] - ox;
        final double dy = satEcef[1] - oy;
        final double dz = satEcef[2] - oz;

        // ECEF → 站心（南/东/天）
        final double south = sinLat * cosLon * dx + sinLat * sinLon * dy - cosLat * dz;
        final double east = -sinLon * dx + cosLon * dy;
        final double zenith = cosLat * cosLon * dx + cosLat * sinLon * dy + sinLat * dz;
        final double range = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (range <= 0) {
            return new double[]{0, -90, 0};
        }
        final double elevation = Math.toDegrees(Math.asin(clamp(zenith / range, -1, 1)));
        double azimuth = Math.toDegrees(Math.atan2(east, -south));
        if (azimuth < 0) {
            azimuth += 360;
        }
        return new double[]{azimuth, elevation, range};
    }

    /** 格林尼治平恒星时（弧度）。 */
    static double gmstRad(long utcMs) {
        final double julianDate = utcMs / 86400000.0 + 2440587.5;
        final double daysSinceJ2000 = julianDate - 2451545.0;
        double degrees = 280.46061837 + 360.98564736629 * daysSinceJ2000;
        degrees %= 360.0;
        if (degrees < 0) {
            degrees += 360.0;
        }
        return Math.toRadians(degrees);
    }

    private static double wrap2Pi(double angle) {
        double value = angle % (2 * Math.PI);
        return value < 0 ? value + 2 * Math.PI : value;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
