package com.vloc.motion.core;

/**
 * 目标配速估算：由「单圈距离 / 目标距离 / 配速 / 最大速度」自动推导圈数、实际距离、目标时间与可行性。
 *
 * <p>时间 = 实际距离 ÷ 配速（配速为 秒/公里）；实际距离 = 圈数 × 路线一圈实际长度。
 * 纯 Java，可单元测试。
 */
public final class GoalPlanner {

    public static final class Estimate {
        /** 圈数 = ceil(目标距离 / 单圈距离)。 */
        public final int laps;
        /** 计划距离 = 圈数 × 单圈距离（≥ 目标距离）。 */
        public final double plannedMeters;
        /** 实际播放距离 = 圈数 × 路线实际长度。 */
        public final double actualMeters;
        /** 目标时间（秒）= 实际距离 ÷ 配速。 */
        public final double timeSeconds;
        /** 平均速度（米/秒）。 */
        public final double avgSpeedMps;
        /** 最大速度（米/秒）。 */
        public final double maxSpeedMps;
        /** 平均速度是否超过最大速度（超过则不可达）。 */
        public final boolean feasible;
        /** 按最大速度完成所需时间（秒）。 */
        public final double minPossibleSeconds;

        Estimate(int laps, double plannedMeters, double actualMeters, double timeSeconds,
                 double avgSpeedMps, double maxSpeedMps, boolean feasible,
                 double minPossibleSeconds) {
            this.laps = laps;
            this.plannedMeters = plannedMeters;
            this.actualMeters = actualMeters;
            this.timeSeconds = timeSeconds;
            this.avgSpeedMps = avgSpeedMps;
            this.maxSpeedMps = maxSpeedMps;
            this.feasible = feasible;
            this.minPossibleSeconds = minPossibleSeconds;
        }
    }

    private GoalPlanner() {
    }

    /**
     * @param lapMeters        单圈距离（米）
     * @param targetMeters     目标距离（米）
     * @param routeMeters      路线一圈实际长度（米），≤0 时用 lapMeters
     * @param paceSecondsPerKm 配速（秒/公里），例如 6:00/km = 360
     * @param maxSpeedKmh      最大速度（km/h）
     */
    public static Estimate estimate(double lapMeters, double targetMeters, double routeMeters,
                                    double paceSecondsPerKm, double maxSpeedKmh) {
        double lap = lapMeters > 1 ? lapMeters : 400;
        double target = Math.max(1, targetMeters);
        double route = routeMeters > 1 ? routeMeters : lap;
        double pace = paceSecondsPerKm > 10 ? paceSecondsPerKm : 360;
        double maxSpeed = (maxSpeedKmh > 1 ? maxSpeedKmh : 15) / 3.6;

        int laps = (int) Math.ceil(target / lap);
        laps = Math.max(1, laps);
        double planned = laps * lap;
        double actual = laps * route;
        double time = actual / 1000.0 * pace;
        double avg = actual / Math.max(1.0, time);
        double minPossible = actual / maxSpeed;
        return new Estimate(laps, planned, actual, time, avg, maxSpeed,
                avg <= maxSpeed + 1e-9, minPossible);
    }
}
