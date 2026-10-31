package com.vloc.motion.service;

import java.util.Locale;

/**
 * 定位数据通道（供 LSPosed 模块在 <b>system_server</b> 里读取，实现系统级注入）。
 *
 * <p>与 {@link StepSession}（步数）、{@link GnssSession}（卫星）并列：
 * 主 App 每推一次定位就写一份最新快照，模块在系统框架进程里按此改写所有 App 收到的 Location，
 * 因此<b>不需要</b>测试 Provider、也<b>不会</b>带模拟位置标记。
 */
public final class LocationSession {

    public static final LocationSession INSTANCE = new LocationSession();

    public volatile boolean active;
    public volatile double lat;
    public volatile double lon;
    public volatile double altitude;
    public volatile float speedMps;
    public volatile float bearingDeg;
    public volatile float accuracyMeters;
    public volatile int satellites;
    public volatile long updatedAt;

    /** 单例供运行时使用；包内可见的构造函数供单元测试。 */
    LocationSession() {
    }

    /** 主 App 每推一帧定位时调用。 */
    public void update(double lat, double lon, double altitude, float speedMps,
                       float bearingDeg, float accuracyMeters, int satellites) {
        this.lat = lat;
        this.lon = lon;
        this.altitude = altitude;
        this.speedMps = speedMps;
        this.bearingDeg = bearingDeg;
        this.accuracyMeters = accuracyMeters;
        this.satellites = satellites;
        this.active = true;
        this.updatedAt = System.currentTimeMillis();
    }

    public void clear() {
        active = false;
        updatedAt = 0L;
        satellites = 0;
    }

    /** 供 ContentProvider 下发的 JSON（模块侧按同一契约解析）。 */
    public String toJson() {
        return String.format(Locale.US,
                "{\"active\":%s,\"lat\":%.7f,\"lon\":%.7f,\"alt\":%.1f,"
                        + "\"speed\":%.3f,\"bearing\":%.1f,\"acc\":%.1f,"
                        + "\"satellites\":%d,\"updatedAt\":%d}",
                active, lat, lon, altitude, speedMps, bearingDeg, accuracyMeters,
                satellites, updatedAt);
    }
}
