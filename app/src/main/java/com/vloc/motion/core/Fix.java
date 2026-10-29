package com.vloc.motion.core;

/** 一次"拟真化"后的定位结果：坐标、海拔、速度、朝向、精度与 GNSS 附属数据。 */
public final class Fix {

    public final double lat;
    public final double lon;
    public final double altitudeMeters;
    public final float speedMps;
    public final float bearingDeg;
    public final float accuracyMeters;
    public final float verticalAccuracyMeters;
    public final int satellites;
    public final float meanCn0;
    public final float maxCn0;

    public Fix(double lat, double lon, double altitudeMeters, float speedMps, float bearingDeg,
               float accuracyMeters, float verticalAccuracyMeters,
               int satellites, float meanCn0, float maxCn0) {
        this.lat = lat;
        this.lon = lon;
        this.altitudeMeters = altitudeMeters;
        this.speedMps = speedMps;
        this.bearingDeg = bearingDeg;
        this.accuracyMeters = accuracyMeters;
        this.verticalAccuracyMeters = verticalAccuracyMeters;
        this.satellites = satellites;
        this.meanCn0 = meanCn0;
        this.maxCn0 = maxCn0;
    }
}
