package com.vloc.motion.core;

/**
 * 跑步/走路时的设备姿态模型：旋转矢量（ROTATION_VECTOR）与磁力计（MAGNETIC_FIELD）。
 *
 * <p>不做"设备静止"假设，而是模拟手持跑步时的摆动：
 * <ul>
 *   <li>基础姿态：手机大致竖直握在手里，屏幕朝内，顶部朝行进方向，基础俯仰约 20°</li>
 *   <li>每步摆动：俯仰（前后摆）随速度 6–22°、侧倾（左右摆）2–10°、偏航小抖动 ±4°</li>
 *   <li>摆动频率 = 步频（与步态同相位），因此姿态角与步数/步频自洽</li>
 *   <li>航向 = 路线航向（弯道时随之改变）</li>
 * </ul>
 * 纯 Java，可单元测试；模块侧直接调用本类，保证与 App 内一致。
 */
public final class GaitAttitude {

    /** 地磁水平分量（µT），中国中纬度典型值。 */
    public static final double MAG_HORIZONTAL = 29.0;
    /** 地磁垂直分量（µT，北半球向下，ENU 中 z 为负）。 */
    public static final double MAG_VERTICAL = 41.0;
    /** 基础俯仰角（度）。 */
    private static final double BASE_PITCH_DEG = 20.0;

    private GaitAttitude() {
    }

    /** 俯仰摆幅（度）：静止 0，走路 ~8，跑步 ~20。 */
    public static double pitchAmplitude(double speedMps) {
        if (speedMps <= 0.15) {
            return 0.0;
        }
        return Math.min(22.0, 3.0 + 4.6 * speedMps);
    }

    /** 侧倾摆幅（度）：约为俯仰的一半。 */
    public static double rollAmplitude(double speedMps) {
        return pitchAmplitude(speedMps) * 0.45;
    }

    /** 偏航抖动幅度（度）：跑步时躯干轻微扭转。 */
    public static double yawWobble(double speedMps) {
        if (speedMps <= 0.15) {
            return 0.0;
        }
        return Math.min(5.0, 1.2 * speedMps);
    }

    /**
     * 生成旋转矢量（设备 → ENU 的四元数），写入 out[0..3]=(x,y,z,w)。
     *
     * @param headingDeg 路线航向（0=正北，顺时针）
     * @param cadenceSpm 步频（决定摆动频率）
     * @param phaseRad   步态相位（与加速度波形同一相位）
     * @param speedMps   速度（决定摆幅；0 表示静止，只有基础姿态）
     */
    public static void rotationVector(double headingDeg, double cadenceSpm, double phaseRad,
                                      double speedMps, float[] out) {
        double pitch = BASE_PITCH_DEG + pitchAmplitude(speedMps) * Math.sin(phaseRad);
        double roll = rollAmplitude(speedMps) * Math.sin(phaseRad + Math.PI / 2);
        double yaw = -headingDeg + yawWobble(speedMps) * Math.sin(phaseRad * 0.5);
        double[] q = quaternion(yaw, pitch, roll);
        out[0] = (float) q[0];
        out[1] = (float) q[1];
        out[2] = (float) q[2];
        out[3] = (float) q[3];
    }

    /**
     * 生成磁力计读数（设备坐标系，µT）：地磁矢量随姿态一起摆动。
     */
    public static void magneticField(double headingDeg, double cadenceSpm, double phaseRad,
                                     double speedMps, float[] out) {
        double pitch = BASE_PITCH_DEG + pitchAmplitude(speedMps) * Math.sin(phaseRad);
        double roll = rollAmplitude(speedMps) * Math.sin(phaseRad + Math.PI / 2);
        double yaw = -headingDeg + yawWobble(speedMps) * Math.sin(phaseRad * 0.5);
        double[] q = quaternion(yaw, pitch, roll);
        // ENU 中的地磁矢量（东, 北, 天）
        double[] enu = new double[]{0.0, MAG_HORIZONTAL, -MAG_VERTICAL};
        double[] dev = rotateByInverse(q, enu);
        out[0] = (float) dev[0];
        out[1] = (float) dev[1];
        out[2] = (float) dev[2];
    }

    /**
     * 按当前姿态把重力（ENU 中 (0,0,-9.80665)）转到设备坐标系，写入 out[0..2]。
     * 用于让加速度计/重力传感器的重力方向与旋转矢量严格一致。
     */
    public static void gravityDevice(double headingDeg, double cadenceSpm, double phaseRad,
                                     double speedMps, float[] out) {
        double pitch = BASE_PITCH_DEG + pitchAmplitude(speedMps) * Math.sin(phaseRad);
        double roll = rollAmplitude(speedMps) * Math.sin(phaseRad + Math.PI / 2);
        double yaw = -headingDeg + yawWobble(speedMps) * Math.sin(phaseRad * 0.5);
        double[] q = quaternion(yaw, pitch, roll);
        double[] enu = new double[]{0.0, 0.0, -ImuWaveform.GRAVITY};
        double[] dev = rotateByInverse(q, enu);
        out[0] = (float) dev[0];
        out[1] = (float) dev[1];
        out[2] = (float) dev[2];
    }

    /**
     * 游戏旋转矢量（GAME_ROTATION_VECTOR）：只用陀螺+加速度、不含磁力计，
     * 因此偏航基准是"任意的"——这里用 {@code yawOffsetDeg} 表示该任意基准，
     * 不暴露真实航向（避免与 absolute ROTATION_VECTOR 完全一致而露馅）。
     *
     * @param yawOffsetDeg 本次进程内固定的偏航基准偏移（模块侧初始化时随机一次）
     */
    public static void gameRotationVector(double headingDeg, double cadenceSpm, double phaseRad,
                                          double speedMps, double yawOffsetDeg, float[] out) {
        double pitch = BASE_PITCH_DEG + pitchAmplitude(speedMps) * Math.sin(phaseRad);
        double roll = rollAmplitude(speedMps) * Math.sin(phaseRad + Math.PI / 2);
        double yaw = -headingDeg + yawOffsetDeg + yawWobble(speedMps) * Math.sin(phaseRad * 0.5);
        double[] q = quaternion(yaw, pitch, roll);
        out[0] = (float) q[0];
        out[1] = (float) q[1];
        out[2] = (float) q[2];
        out[3] = (float) q[3];
    }

    /**
     * 方向传感器（TYPE_ORIENTATION）：values = (方位角 0–360°, 俯仰 −180–180°, 滚转 −90–90°)。
     *
     * <p>与 ROTATION_VECTOR 由同一姿态推导，保证两者一致；方位角跟随路线航向。
     */
    public static void orientation(double headingDeg, double cadenceSpm, double phaseRad,
                                   double speedMps, float[] out) {
        double pitch = BASE_PITCH_DEG + pitchAmplitude(speedMps) * Math.sin(phaseRad);
        double roll = rollAmplitude(speedMps) * Math.sin(phaseRad + Math.PI / 2);
        double yaw = -headingDeg + yawWobble(speedMps) * Math.sin(phaseRad * 0.5);
        double[] q = quaternion(yaw, pitch, roll);
        double[] r = rotationMatrix(q);
        double azimuth = Math.toDegrees(Math.atan2(r[1], r[4]));
        azimuth = (azimuth + 360.0) % 360.0;
        double pitchDeg = Math.toDegrees(Math.asin(clampValue(-r[7], -1, 1)));
        double rollDeg = Math.toDegrees(Math.atan2(-r[6], r[8]));
        out[0] = (float) azimuth;
        out[1] = (float) pitchDeg;
        out[2] = (float) rollDeg;
    }

    /** 四元数 → 3×3 行主序旋转矩阵（设备 → ENU）。 */
    static double[] rotationMatrix(double[] q) {
        double x = q[0];
        double y = q[1];
        double z = q[2];
        double w = q[3];
        return new double[]{
                1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
                2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
                2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)
        };
    }

    private static double clampValue(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 由偏航/俯仰/侧倾（度）构造四元数 (x,y,z,w)，顺序：先滚转、再俯仰、最后偏航。 */
    static double[] quaternion(double yawDeg, double pitchDeg, double rollDeg) {
        double[] qz = axisAngle(0, 0, 1, Math.toRadians(yawDeg));
        double[] qx = axisAngle(1, 0, 0, Math.toRadians(pitchDeg));
        double[] qy = axisAngle(0, 1, 0, Math.toRadians(rollDeg));
        return multiply(multiply(qz, qx), qy);
    }

    private static double[] axisAngle(double x, double y, double z, double angleRad) {
        double half = angleRad / 2.0;
        double s = Math.sin(half);
        return new double[]{x * s, y * s, z * s, Math.cos(half)};
    }

    private static double[] multiply(double[] a, double[] b) {
        return new double[]{
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
        };
    }

    /** 用四元数的逆把 ENU 向量转到设备坐标系。 */
    private static double[] rotateByInverse(double[] q, double[] v) {
        double[] inv = new double[]{-q[0], -q[1], -q[2], q[3]};
        double[] pure = new double[]{v[0], v[1], v[2], 0};
        double[] rotated = multiply(multiply(inv, pure), q);
        return new double[]{rotated[0], rotated[1], rotated[2]};
    }

    /** 四元数模长（用于测试/校验）。 */
    public static double norm(float[] q) {
        return Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
    }

    /** 由四元数反推偏航角（度，正北顺时针），用于测试一致性。 */
    public static double headingFrom(float[] q) {
        // 设备 y 轴在 ENU 上的投影方向
        double[] inv = new double[]{-q[0], -q[1], -q[2], q[3]};
        double[] yAxis = rotateByInverse(inv, new double[]{0, 1, 0});
        double degrees = Math.toDegrees(Math.atan2(yAxis[0], yAxis[1]));
        return (degrees + 360.0) % 360.0;
    }
}
