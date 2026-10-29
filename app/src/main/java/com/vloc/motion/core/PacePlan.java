package com.vloc.motion.core;

import java.util.Random;

/**
 * 目标配速计划：按"圈数 × 单圈距离"凑够目标距离，并在目标时间内完成。
 *
 * <p>生成一条分段速度曲线：
 * <ul>
 *   <li>平均值 = 实际距离 / 目标时间</li>
 *   <li>每段速度围绕平均值随机游走（时快时慢），但有上下限</li>
 *   <li>最快不超过 {@code maxSpeedMps}（正常成年人水平），单段不超过
 *       {@code min(maxSpeed, 平均×1.25 + 0.2)}（"小分段不过快"）</li>
 *   <li>迭代校准总时长，使其贴近目标时间</li>
 * </ul>
 * 纯 Java，可单元测试。
 */
public final class PacePlan {

    /** 单圈距离（用户设定，米）。 */
    public final double lapMeters;
    /** 实际路线一圈长度（米）。 */
    public final double routeMeters;
    /** 目标距离（米）。 */
    public final double targetMeters;
    /** 目标时间（秒）。 */
    public final double targetSeconds;
    /** 允许的最大速度（米/秒）。 */
    public final double maxSpeedMps;

    /** 圈数 = ceil(目标距离 / 单圈距离)。 */
    public final int laps;
    /** 计划距离 = 圈数 × 单圈距离（≥ 目标距离）。 */
    public final double plannedMeters;
    /** 实际播放距离 = 圈数 × 实际一圈长度。 */
    public final double actualMeters;
    /** 实际平均速度（米/秒）。 */
    public final double avgSpeedMps;
    /** 计划总时长（秒），按分段速度求和得到。 */
    public final double durationSeconds;
    /** 是否能在目标时间内、不超过最大速度完成。 */
    public final boolean feasible;
    /** 分段长度（米）。 */
    public final double blockMeters;

    private final double[] blockSpeeds;
    private static final int TARGET_BLOCKS = 80;
    private static final double MIN_BLOCK_METERS = 10.0;

    private PacePlan(double lapMeters, double routeMeters, double targetMeters,
                     double targetSeconds, double maxSpeedMps, int laps,
                     double plannedMeters, double actualMeters, double avgSpeedMps,
                     double durationSeconds, boolean feasible, double blockMeters,
                     double[] blockSpeeds) {
        this.lapMeters = lapMeters;
        this.routeMeters = routeMeters;
        this.targetMeters = targetMeters;
        this.targetSeconds = targetSeconds;
        this.maxSpeedMps = maxSpeedMps;
        this.laps = laps;
        this.plannedMeters = plannedMeters;
        this.actualMeters = actualMeters;
        this.avgSpeedMps = avgSpeedMps;
        this.durationSeconds = durationSeconds;
        this.feasible = feasible;
        this.blockMeters = blockMeters;
        this.blockSpeeds = blockSpeeds;
    }

    /**
     * 生成配速计划。
     *
     * @param lapMeters     用户设定的单圈距离（米），≤0 时用路线长度
     * @param targetMeters  目标距离（米）
     * @param targetSeconds 目标时间（秒）
     * @param maxSpeedMps   最大速度（米/秒），正常成年人建议 2.5~4.5
     * @param routeMeters   实际路线一圈长度（米）
     */
    public static PacePlan create(double lapMeters, double targetMeters, double targetSeconds,
                                  double maxSpeedMps, double routeMeters) {
        double route = Math.max(1.0, routeMeters);
        double lap = lapMeters > 1.0 ? lapMeters : route;
        double target = Math.max(1.0, targetMeters);
        double time = Math.max(5.0, targetSeconds);
        double maxSpeed = maxSpeedMps > 0.3 ? maxSpeedMps : 4.0;

        int laps = (int) Math.ceil(target / lap);
        laps = Math.max(1, laps);
        double planned = laps * lap;
        double actual = laps * route;
        double base = actual / time;

        double effectiveBase = Math.min(base, maxSpeed);
        double hardCap = Math.min(maxSpeed, effectiveBase * 1.25 + 0.2);
        double softFloor = Math.max(0.35, effectiveBase * 0.65);

        double block = Math.max(MIN_BLOCK_METERS, actual / TARGET_BLOCKS);
        int blocks = Math.max(6, (int) Math.ceil(actual / block));
        double blockLen = actual / blocks;

        Random random = new Random(laps * 1000003L + (long) (actual * 7) + (long) time);
        double[] speeds = new double[blocks];
        double walk = 1.0;
        for (int i = 0; i < blocks; i++) {
            walk += random.nextGaussian() * 0.06;
            walk = clamp(walk, 0.88, 1.12);
            speeds[i] = clamp(effectiveBase * walk, softFloor, hardCap);
        }

        // 迭代校准：让总时长尽量贴近目标时间
        double duration = totalTime(blockLen, speeds);
        for (int iteration = 0; iteration < 10; iteration++) {
            if (Math.abs(duration - time) <= 0.5) {
                break;
            }
            double factor = duration / time;
            for (int i = 0; i < blocks; i++) {
                speeds[i] = clamp(speeds[i] * factor, softFloor, hardCap);
            }
            double next = totalTime(blockLen, speeds);
            if (Math.abs(next - duration) < 0.05) {
                duration = next;
                break;
            }
            duration = next;
        }

        boolean feasible = base <= maxSpeed + 1e-6 && duration <= time + 10.0;
        return new PacePlan(lap, route, target, time, maxSpeed, laps, planned, actual,
                actual / time, duration, feasible, blockLen, speeds);
    }

    /** 当前累计距离对应的目标速度（米/秒）。 */
    public double speedAt(double meters) {
        if (blockSpeeds.length == 0) {
            return avgSpeedMps;
        }
        int index = (int) Math.floor(Math.max(0, meters) / blockLengthSafe());
        if (index < 0) {
            index = 0;
        }
        if (index >= blockSpeeds.length) {
            index = blockSpeeds.length - 1;
        }
        return blockSpeeds[index];
    }

    private double blockLengthSafe() {
        return blockMeters > 1e-6 ? blockMeters : 1.0;
    }

    public int blockCount() {
        return blockSpeeds.length;
    }

    public double speedAtBlock(int index) {
        if (index < 0 || index >= blockSpeeds.length) {
            return avgSpeedMps;
        }
        return blockSpeeds[index];
    }

    private static double totalTime(double blockLen, double[] speeds) {
        double total = 0;
        for (double speed : speeds) {
            total += blockLen / Math.max(0.05, speed);
        }
        return total;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
