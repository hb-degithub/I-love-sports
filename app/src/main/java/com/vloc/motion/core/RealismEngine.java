package com.vloc.motion.core;

import java.util.Random;

/**
 * 拟真引擎：在注入坐标上叠加符合 GNSS 误差特征的抖动，降低"完美合成轨迹"被识别的概率。
 *
 * <p>包含四类处理：
 * <ul>
 *   <li>位置漂移：均值回归随机游走（模拟多路径/电离层低频误差），默认半径 2.5 m</li>
 *   <li>速度/朝向噪声：±5% 速度、±1.5° 方位</li>
 *   <li>海拔与垂直精度噪声</li>
 *   <li>GNSS 附属数据：卫星数、平均/最大载噪比（真实 Location 里常见 extras）</li>
 * </ul>
 *
 * 纯 Java，可单元测试。
 */
public final class RealismEngine {

    private static final double METERS_PER_DEG_LAT = 111320.0;
    /**
     * 漂移时间常数（秒）：真实 GNSS 的多路径/电离层误差是<b>时间连续</b>的低频游走，
     * 采样间隔越短，相邻两帧的差异越小。这里的随机步按 √dt 缩放，保证 1Hz 与 5Hz
     * 采样看到的噪声量级一致（旧实现每帧固定 0.28 m，5Hz 时噪声被放大 2.5 倍，轨迹发"跳"）。
     */
    private static final double DRIFT_TAU_SECONDS = 8.0;
    /** 漂移强度（m/√s）：稳态幅度 ≈ sigma * √(tau/2) ≈ 0.5 m，贴近真机定位噪声。 */
    private static final double DRIFT_SIGMA_M_PER_SQRT_S = 0.20;
    private static final double DEFAULT_DRIFT_MAX_METERS = 1.2;

    private final Random random;
    private final double driftMaxMeters;
    /** 逐颗卫星天空模型：聚合值进 Location extras，逐颗数据下发给 LSPosed 模块回放 GnssStatus。 */
    private final GnssSky gnss;
    private boolean enabled = true;
    private double driftNorth;
    private double driftEast;
    private long lastApplyMs;

    public RealismEngine() {
        this(new Random(), DEFAULT_DRIFT_MAX_METERS);
    }

    public RealismEngine(Random random, double driftMaxMeters) {
        this.random = random;
        this.driftMaxMeters = Math.max(0.1, driftMaxMeters);
        this.gnss = new GnssSky();
    }

    /**
     * 载入真实 GNSS 星表（CelesTrak TLE 快照，见 assets/gnss_tle.txt）。
     *
     * <p>传入空/ null 时退化为"不产出卫星"（模块会透传真实 GNSS），不会崩。
     */
    public void setTleData(String tleText) {
        gnss.setCatalog(GnssTle.parse(tleText));
    }

    /** 星表里有多少颗卫星（0 = 没有星表，此时不下发 gnss 数据）。 */
    public int gnssCatalogSize() {
        return gnss.catalogSize();
    }

    /** 此刻该位置可见卫星数（仰角 > 3°）。 */
    public int gnssVisibleCount() {
        return gnss.count();
    }

    /** 此刻参与定位的卫星数（与写入 Location extras 的 satellites 一致）。 */
    public int usedInFixCount() {
        return gnss.usedInFixCount();
    }

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    /** 开始新的播放会话时重置漂移，避免上一段轨迹的偏移带进来。 */
    public synchronized void resetDrift() {
        driftNorth = 0;
        driftEast = 0;
    }

    public synchronized double driftMagnitude() {
        return Math.hypot(driftNorth, driftEast);
    }

    /**
     * 生成一次拟真定位。
     *
     * @param lat          轨迹上的真实纬度
     * @param lon          轨迹上的真实经度
     * @param altitudeMeters 基础海拔（米）
     * @param speedMps     轨迹速度（米/秒）
     * @param bearingDeg   轨迹朝向（0=正北）
     * @param baseAccuracy 基准水平精度（米）
     */
    public synchronized Fix apply(double lat, double lon, double altitudeMeters,
                                  double speedMps, double bearingDeg, float baseAccuracy) {
        return applyAt(lat, lon, altitudeMeters, speedMps, bearingDeg, baseAccuracy,
                System.currentTimeMillis());
    }

    /**
     * 与 {@link #apply} 相同，但由调用方指定时间戳（毫秒）。供测试按仿真时间推进，
     * 保证漂移的时间常数与真实运行一致。
     */
    public synchronized Fix applyAt(double lat, double lon, double altitudeMeters,
                                    double speedMps, double bearingDeg, float baseAccuracy,
                                    long nowMs) {
        float accuracy = (float) (baseAccuracy * (0.72 + random.nextFloat() * 0.5));
        float verticalAccuracy = accuracy * 1.6f;

        if (!enabled) {
            return new Fix(lat, lon, altitudeMeters, (float) speedMps,
                    (float) normalizeBearing(bearingDeg), accuracy, verticalAccuracy, 0, 0f, 0f);
        }

        // 位置漂移：时间连续的均值回归随机游走（Ornstein–Uhlenbeck），随机步按 √dt 缩放。
        // 这样 4-5Hz 注入时相邻帧几乎连续，更像真机 GNSS；1Hz 采样的噪声量级与真机一致。
        double dtSeconds = lastApplyMs == 0L ? 0.2 : (nowMs - lastApplyMs) / 1000.0;
        lastApplyMs = nowMs;
        dtSeconds = Math.max(0.02, Math.min(1.0, dtSeconds));
        double decay = Math.exp(-dtSeconds / DRIFT_TAU_SECONDS);
        double step = DRIFT_SIGMA_M_PER_SQRT_S * Math.sqrt(dtSeconds);
        driftNorth = driftNorth * decay + random.nextGaussian() * step;
        driftEast = driftEast * decay + random.nextGaussian() * step;
        double magnitude = Math.hypot(driftNorth, driftEast);
        if (magnitude > driftMaxMeters) {
            double scale = driftMaxMeters / magnitude;
            driftNorth *= scale;
            driftEast *= scale;
        }
        double dLat = driftNorth / METERS_PER_DEG_LAT;
        double cosLat = Math.max(0.05, Math.cos(Math.toRadians(lat)));
        double dLon = driftEast / (METERS_PER_DEG_LAT * cosLat);

        double noisySpeed = Math.max(0.0, speedMps * (1.0 + random.nextGaussian() * 0.05));
        double noisyBearing = normalizeBearing(bearingDeg + random.nextGaussian() * 1.5);
        double noisyAltitude = altitudeMeters + random.nextGaussian() * 1.2;

        // GNSS 附属数据来自真实星历 + 目标位置 + 当前 UTC 时间：
        // 此刻这里真实可见的卫星，就是目标 App 会看到的卫星。
        // 口径与 AOSP GnssLocationProvider 相同：satellites / meanCn0 / maxCn0 只统计"参与定位"的卫星。
        gnss.update(0, lat, lon, nowMs);
        int satellites = gnss.usedInFixCount();
        float meanCn0 = (float) gnss.meanCn0();
        float maxCn0 = (float) gnss.maxCn0();

        return new Fix(lat + dLat, lon + dLon, noisyAltitude, (float) noisySpeed,
                (float) noisyBearing, accuracy, verticalAccuracy,
                satellites, meanCn0, maxCn0);
    }

    /** 开始新的模拟会话：重置漂移并重新抽一套卫星组合。 */
    public synchronized void startNewSession() {
        resetDrift();
        lastApplyMs = 0L;
        gnss.start();
    }

    /** 逐颗卫星快照（供自检 / 测试）。 */
    public java.util.List<GnssSky.Satellite> gnssSatellites() {
        return gnss.satellites();
    }

    /** 逐颗卫星 JSON，供 steps provider 下发给 LSPosed 模块；拟真关闭时为空数组（模块据此透传）。 */
    public String gnssJson() {
        return enabled ? gnss.toJsonArray() : "[]";
    }

    private static double normalizeBearing(double degrees) {
        double value = degrees % 360.0;
        return value < 0 ? value + 360.0 : value;
    }
}
