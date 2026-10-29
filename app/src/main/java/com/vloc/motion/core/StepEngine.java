package com.vloc.motion.core;

import java.util.Locale;
import java.util.Random;

/**
 * 步数 / 步频 / 步幅推算：由路线行驶速度与时间推导，保证与虚拟定位自洽。
 *
 * <p>模型：
 * <ul>
 *   <li>步幅随速度增长：步行 ~0.65–0.8 m，跑步 ~1.0–1.2 m，带慢变抖动</li>
 *   <li>步频 = 速度 ÷ 步幅 × 60（步行 ~100–120 spm，跑步 ~160–180 spm）</li>
 *   <li>步数按累计距离 ÷ 步幅累加，只增不减</li>
 * </ul>
 * 纯 Java，可单元测试。
 */
public final class StepEngine {

    private final Random random = new Random();
    private double steps;
    private double distanceMeters;
    private double strideMeters = 0.75;
    private double cadenceSpm;
    private double jitter = 1.0;
    private long baseSteps;
    private double accelAmplitude;
    private double yawRateDegPerSec;
    private double lastBearing = Double.NaN;

    // ---- 时变步态：保证同一次模拟里步频/步幅不会恒定 ----
    /** 慢变漂移（Ornstein-Uhlenbeck），影响步频 ±10% */
    private double cadenceBias;
    /** 呼吸式周期波动（周期 25–45 秒，幅度 ±3%） */
    private double breathPhase;
    private double breathPeriod;
    /** 步数累加器与下一步所需距离（每步单独抖动，步幅不会恒定） */
    private double stepAccumulator;
    private double nextStepDistance;
    private int stepCount;
    private double lastSpeed;
    /** 每次模拟的个体步态系数（0.94–1.06）：同一个人不同次模拟也不会完全一样 */
    private double sessionStrideFactor;

    /** 每次开始模拟时调用：清空累计并按真实设备习惯给一个起始累计值。 */
    public synchronized void start() {
        distanceMeters = 0;
        steps = 0;
        strideMeters = 0.75;
        cadenceSpm = 0;
        jitter = 1.0;
        accelAmplitude = 0;
        yawRateDegPerSec = 0;
        lastBearing = Double.NaN;
        cadenceBias = (random.nextDouble() - 0.5) * 0.04;
        sessionStrideFactor = 0.94 + random.nextDouble() * 0.12;
        breathPhase = random.nextDouble() * Math.PI * 2;
        breathPeriod = 25 + random.nextDouble() * 20;
        stepAccumulator = 0;
        stepCount = 0;
        nextStepDistance = 0.75 * (1 + random.nextGaussian() * 0.04);
        baseSteps = 800 + random.nextInt(4000);
    }

    public synchronized void reset() {
        start();
    }

    /** 暂停/保持时调用：步频归零，步数冻结。 */
    public synchronized void idle() {
        cadenceSpm = 0;
        accelAmplitude = 0;
        yawRateDegPerSec = 0;
    }

    /**
     * 推进一帧（带航向，用于推算转向角速度）。
     *
     * @param bearingDeg 当前航向（0=正北），NaN 表示未知
     */
    public synchronized void update(double speedMps, double dtSeconds, double bearingDeg) {
        update(speedMps, dtSeconds);
        if (!Double.isNaN(bearingDeg) && !Double.isNaN(lastBearing) && dtSeconds > 0) {
            double delta = bearingDeg - lastBearing;
            while (delta > 180) {
                delta -= 360;
            }
            while (delta < -180) {
                delta += 360;
            }
            double rate = delta / dtSeconds;
            yawRateDegPerSec += (rate - yawRateDegPerSec) * Math.min(1.0, dtSeconds * 2.0);
            yawRateDegPerSec = Math.max(-60, Math.min(60, yawRateDegPerSec));
        } else if (dtSeconds > 0) {
            yawRateDegPerSec *= Math.max(0.0, 1.0 - dtSeconds * 2.0);
        }
        lastBearing = Double.isNaN(bearingDeg) ? lastBearing : bearingDeg;
    }

    /** 最近一次航向（度）；未知返回 NaN。 */
    public synchronized double getHeadingDeg() {
        return lastBearing;
    }

    /** 最近一次速度（米/秒）。 */
    public synchronized double getSpeedMps() {
        return lastSpeed;
    }

    /** 垂直加速度振幅（m/s²），用于 IMU 波形。 */
    public synchronized double getAccelAmplitude() {
        return accelAmplitude;
    }

    /** 转向角速度（度/秒）。 */
    public synchronized double getYawRateDegPerSec() {
        return yawRateDegPerSec;
    }

    /**
     * 推进一帧。
     *
     * @param speedMps 当前速度（米/秒）
     * @param dtSeconds 距上一帧的秒数
     */
    public synchronized void update(double speedMps, double dtSeconds) {
        if (dtSeconds <= 0) {
            return;
        }
        if (speedMps <= 0.15) {
            cadenceSpm = 0;
            return;
        }
        jitter += (random.nextGaussian() * 0.02) * Math.min(1.0, dtSeconds * 0.5);
        jitter = clamp(jitter, 0.94, 1.06);

        // 慢变漂移：均值回归的随机游走，限制在 ±10%
        cadenceBias += -0.6 * cadenceBias * dtSeconds
                + random.nextGaussian() * 0.10 * Math.sqrt(dtSeconds);
        cadenceBias = clamp(cadenceBias, -0.06, 0.06);

        // 呼吸式周期波动
        breathPhase += dtSeconds * 2 * Math.PI / Math.max(10.0, breathPeriod);
        if (breathPhase > Math.PI * 2) {
            breathPhase -= Math.PI * 2;
            breathPeriod = 25 + random.nextDouble() * 20;
        }
        double breath = 0.03 * Math.sin(breathPhase);

        // 步幅：基础值随速度增长，叠加抖动与"步频偏高→步幅略短"的反相关
        double baseStride = clamp(0.55 + 0.18 * speedMps, 0.50, 1.35) * sessionStrideFactor;
        double targetStride = baseStride * jitter * (1 - 0.55 * (cadenceBias + breath));
        double blend = Math.min(1.0, dtSeconds * 1.5);
        strideMeters += (targetStride - strideMeters) * blend;
        strideMeters = clamp(strideMeters, 0.42, 1.50);

        lastSpeed = speedMps;
        double moved = speedMps * dtSeconds;
        distanceMeters += moved;

        // 逐步推进：每"一步"所需距离单独抖动 ±4%，步频/步长不会一成不变
        stepAccumulator += moved;
        int guard = 0;
        while (stepAccumulator >= nextStepDistance && guard++ < 200) {
            stepAccumulator -= nextStepDistance;
            stepCount++;
            nextStepDistance = strideMeters * (1 + random.nextGaussian() * 0.04);
            nextStepDistance = clamp(nextStepDistance, strideMeters * 0.88, strideMeters * 1.12);
        }
        cadenceSpm = speedMps / Math.max(0.35, strideMeters) * 60.0 * (1 + cadenceBias + breath);
        // 真实人类步频上限约 210 步/分：超过则把步幅按同一速度放大，保持距离/步数一致
        if (cadenceSpm > 210.0) {
            cadenceSpm = 210.0;
            strideMeters = Math.max(strideMeters, speedMps / (cadenceSpm / 60.0));
        }
        cadenceSpm = clamp(cadenceSpm, 0, 210);
        accelAmplitude = ImuWaveform.accelAmplitude(speedMps)
                * (1 + 0.5 * (cadenceBias + breath));
    }

    /** 累计步数（含起始基数，模拟真实设备"开机以来"的计数）。 */
    public synchronized int getSteps() {
        return (int) Math.floor(baseSteps + stepCount);
    }

    /** 本次会话新增步数。 */
    public synchronized int getSessionSteps() {
        return stepCount;
    }

    public synchronized double getCadenceSpm() {
        return cadenceSpm;
    }

    public synchronized double getStrideMeters() {
        return strideMeters;
    }

    public synchronized double getDistanceMeters() {
        return distanceMeters;
    }

    /**
     * 供 ContentProvider 暴露给 LSPosed 模块的 JSON。
     *
     * @param gnssJson 逐颗卫星数组（{@link GnssSky#toJsonArray()}），拟真关闭时为 {@code "[]"}
     */
    public synchronized String toJson(boolean active, boolean attitudeReplay,
                                       int satellites, float cn0Mean, float cn0Max,
                                       String gnssJson) {
        return String.format(Locale.US,
                "{\"version\":2,\"active\":%s,\"steps\":%d,\"sessionSteps\":%d,"
                        + "\"cadenceSpm\":%.1f,\"strideM\":%.3f,\"distanceM\":%.1f,"
                        + "\"accelAmp\":%.2f,\"yawRateDps\":%.1f,"
                        + "\"headingDeg\":%.1f,\"speedMps\":%.2f,\"attitude\":%s,"
                        + "\"satellites\":%d,\"cn0Mean\":%.1f,\"cn0Max\":%.1f,"
                        + "\"gnss\":%s,"
                        + "\"updatedAt\":%d}",
                active, getSteps(), getSessionSteps(), cadenceSpm, strideMeters,
                distanceMeters, accelAmplitude, yawRateDegPerSec,
                Double.isNaN(lastBearing) ? 0.0 : lastBearing, lastSpeed,
                attitudeReplay, satellites, cn0Mean, cn0Max,
                gnssJson == null || gnssJson.isEmpty() ? "[]" : gnssJson,
                System.currentTimeMillis());
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
