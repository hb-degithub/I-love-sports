package com.vloc.motion.core;

/**
 * 步态 IMU 波形：按步频、速度、转向率合成加速度计 / 陀螺仪读数。
 *
 * <p>模型（设备约竖直握持，重力主要落在 z 轴）：
 * <ul>
 *   <li>加速度计 z = 9.81 + A·sin(2πf·t)：步行 A≈2–3、跑步 A≈6–9 m/s²</li>
 *   <li>x/y 为小幅随机噪声（±0.15 m/s²），线加速度 = 加速度 − 重力</li>
 *   <li>陀螺仪：x/y 与步频同相小幅摆动（±0.15 rad/s），z = 转向角速度</li>
 *   <li>静止（速度为 0）时只剩重力，无周期振荡</li>
 * </ul>
 * 纯 Java，可单元测试；模块侧直接调用同一套公式，保证 App 显示与注入一致。
 */
public final class ImuWaveform {

    public static final double GRAVITY = 9.80665;

    private ImuWaveform() {
    }

    /** 由速度推导垂直加速度振幅（m/s²）。 */
    public static double accelAmplitude(double speedMps) {
        if (speedMps <= 0.15) {
            return 0.0;
        }
        double v = Math.min(6.0, speedMps);
        return clamp(0.55 + 1.1 * v + 0.18 * v * v, 0.3, 9.5);
    }

    /** 步态相位（弧度）。 */
    public static double phase(double cadenceSpm, double timeSeconds) {
        double hz = Math.max(0.0, cadenceSpm) / 60.0;
        return 2.0 * Math.PI * hz * timeSeconds;
    }

    /**
     * 加速度计读数。
     *
     * @param out 长度 ≥3 的数组，写入 x/y/z（m/s²）
     */
    public static void accelerometer(double accelAmplitude, double phase, double noiseSeed,
                                     float[] out) {
        double oscillation = accelAmplitude == 0 ? 0 : accelAmplitude * Math.sin(phase);
        double n1 = pseudoNoise(noiseSeed, 1) * 0.15;
        double n2 = pseudoNoise(noiseSeed, 2) * 0.15;
        out[0] = (float) n1;
        out[1] = (float) n2;
        out[2] = (float) (GRAVITY + oscillation + pseudoNoise(noiseSeed, 3) * 0.08);
    }

    /** 线加速度（去掉重力）。 */
    public static void linearAcceleration(double accelAmplitude, double phase, double noiseSeed,
                                          float[] out) {
        float[] accel = new float[3];
        accelerometer(accelAmplitude, phase, noiseSeed, accel);
        out[0] = accel[0];
        out[1] = accel[1];
        out[2] = (float) (accel[2] - GRAVITY);
    }

    /** 重力传感器读数。 */
    public static void gravity(double noiseSeed, float[] out) {
        out[0] = (float) (pseudoNoise(noiseSeed, 4) * 0.02);
        out[1] = (float) (pseudoNoise(noiseSeed, 5) * 0.02);
        out[2] = (float) (GRAVITY + pseudoNoise(noiseSeed, 6) * 0.02);
    }

    /**
     * 陀螺仪读数（rad/s）。
     *
     * @param yawRateRadPerSec 转向角速度（来自路线转弯）
     */
    public static void gyroscope(double accelAmplitude, double phase, double yawRateRadPerSec,
                                 double noiseSeed, float[] out) {
        double swing = accelAmplitude == 0 ? 0 : Math.min(0.6, accelAmplitude * 0.05);
        out[0] = (float) (swing * Math.sin(phase + Math.PI / 2)
                + pseudoNoise(noiseSeed, 7) * 0.02);
        out[1] = (float) (swing * 0.6 * Math.sin(phase)
                + pseudoNoise(noiseSeed, 8) * 0.02);
        out[2] = (float) (yawRateRadPerSec + pseudoNoise(noiseSeed, 9) * 0.02);
    }

    // ------------------------------------------------------------ 与姿态一致的版本
    // 重力方向由 GaitAttitude 按当前姿态旋转得到，摆动分量叠加在设备 x/y/z 上，
    // 这样 App 用 getRotationMatrix(accel, mag) 反推的倾角与 rotation vector 一致。

    /** 加速度计（姿态一致版）：重力按姿态旋转 + 步态摆动。 */
    public static void accelerometerWithAttitude(double headingDeg, double cadenceSpm,
                                                 double phaseRad, double speedMps,
                                                 double noiseSeed, float[] out) {
        float[] gravity = new float[3];
        GaitAttitude.gravityDevice(headingDeg, cadenceSpm, phaseRad, speedMps, gravity);
        double amp = accelAmplitude(speedMps);
        double oscillation = amp == 0 ? 0 : amp * Math.sin(phaseRad);
        out[0] = (float) (gravity[0] + pseudoNoise(noiseSeed, 1) * 0.15);
        out[1] = (float) (gravity[1] + pseudoNoise(noiseSeed, 2) * 0.15);
        out[2] = (float) (gravity[2] + oscillation + pseudoNoise(noiseSeed, 3) * 0.08);
    }

    /** 线加速度（姿态一致版）：= 加速度 − 重力。 */
    public static void linearAccelerationWithAttitude(double headingDeg, double cadenceSpm,
                                                      double phaseRad, double speedMps,
                                                      double noiseSeed, float[] out) {
        float[] gravity = new float[3];
        GaitAttitude.gravityDevice(headingDeg, cadenceSpm, phaseRad, speedMps, gravity);
        double amp = accelAmplitude(speedMps);
        double oscillation = amp == 0 ? 0 : amp * Math.sin(phaseRad);
        out[0] = (float) (pseudoNoise(noiseSeed, 1) * 0.15);
        out[1] = (float) (pseudoNoise(noiseSeed, 2) * 0.15);
        out[2] = (float) (oscillation + pseudoNoise(noiseSeed, 3) * 0.08);
    }

    /** 重力传感器（姿态一致版）。 */
    public static void gravityWithAttitude(double headingDeg, double cadenceSpm,
                                           double phaseRad, double speedMps,
                                           double noiseSeed, float[] out) {
        GaitAttitude.gravityDevice(headingDeg, cadenceSpm, phaseRad, speedMps, out);
        out[0] = (float) (out[0] + pseudoNoise(noiseSeed, 4) * 0.02);
        out[1] = (float) (out[1] + pseudoNoise(noiseSeed, 5) * 0.02);
        out[2] = (float) (out[2] + pseudoNoise(noiseSeed, 6) * 0.02);
    }

    /** 平滑随机噪声（-1..1），按 seed 与序号确定，便于测试复现。 */
    private static double pseudoNoise(double seed, int index) {
        double x = Math.sin(seed * 12.9898 + index * 78.233) * 43758.5453;
        return (x - Math.floor(x)) * 2.0 - 1.0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
