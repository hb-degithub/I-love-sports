package com.vloc.motion.core;

import java.util.Random;

/**
 * 路径播放器：按里程推进，输出当前采样点。
 *
 * <p>支持两种模式：
 * <ul>
 *   <li>普通模式：以设定速度（可叠加速度混淆）沿路径循环/单程移动</li>
 *   <li>目标模式：按 {@link PacePlan} 的分段随机速度，跑够 N 圈并在目标时间内完成；
 *       带时间闭环修正、加速度限制与最大速度限制</li>
 * </ul>
 * 纯 Java，可单元测试。
 */
public final class RoutePlayer {

    private static final double ACCEL_LIMIT_MPS2 = 0.8;
    private static final double MIN_SPEED_MPS = 0.2;

    private Route route;
    private double speedMps = 5.0;
    private boolean loop;
    private boolean running;
    private boolean paused;
    private boolean finished;
    private double traveledMeters;
    private long lastTickMs;
    private double rampSeconds;
    private double runElapsedSeconds;

    private PacePlan pacePlan;
    private boolean speedVariance;
    private final Random random;
    private double variance;
    /** 慢变配速偏差（疲劳 / 地形 / 心率漂移）：OU 过程，τ≈180s，稳态幅度 ±3% */
    private double slowBias;
    /** 事件式加减速（冲刺 / 过马路 / 补水）：平均约 2 分钟一次，幅度 6–14%，指数衰减 τ≈25s */
    private double surge;
    /** 公里级地形/风向波动：OU，τ≈420s，稳态幅度 ±4% */
    private double terrainBias;
    /** 累积疲劳（心率漂移）：随跑步时长线性累积，约每分钟 -0.12%，上限 8% */
    private static final double FATIGUE_PER_MINUTE = 0.0012;
    private double currentSpeedMps;
    private double elapsedSeconds;
    private int completedLaps;

    public RoutePlayer() {
        this(new Random());
    }

    /** 测试用：注入固定种子的随机源，让配速波动可复现。 */
    RoutePlayer(Random random) {
        this.random = random == null ? new Random() : random;
    }

    public Route getRoute() {
        return route;
    }

    public synchronized void setRoute(Route r) {
        this.route = r;
        resetProgress();
    }

    public synchronized void setPacePlan(PacePlan plan) {
        this.pacePlan = plan;
        resetProgress();
    }

    public synchronized PacePlan getPacePlan() {
        return pacePlan;
    }

    /** 是否开启"时快时慢"的速度随机化。 */
    public synchronized void setSpeedVariance(boolean enabled) {
        this.speedVariance = enabled;
    }

    public synchronized boolean isSpeedVarianceEnabled() {
        return speedVariance;
    }

    private void resetProgress() {
        traveledMeters = 0;
        completedLaps = 0;
        elapsedSeconds = 0;
        currentSpeedMps = 0;
        variance = 0;
        slowBias = 0;
        surge = 0;
        terrainBias = 0;
        lastTickMs = 0L;
        finished = false;
    }

    public synchronized double getSpeedMps() {
        return speedMps;
    }

    public synchronized void setSpeedMps(double speedMps) {
        this.speedMps = Math.max(0.0, speedMps);
    }

    public synchronized void setSpeedKmh(double kmh) {
        setSpeedMps(kmh / 3.6);
    }

    public synchronized boolean isLoop() {
        return loop;
    }

    public synchronized void setLoop(boolean loop) {
        this.loop = loop;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    public synchronized boolean isFinished() {
        return finished;
    }

    /** 路线内的里程（未跨圈累计）。 */
    public synchronized double getTraveledMeters() {
        return traveledMeters;
    }

    /** 跨圈累计里程（目标模式用于进度显示）。 */
    public synchronized double getGlobalTraveledMeters() {
        return completedLaps * routeLength() + traveledMeters;
    }

    public synchronized int getCompletedLaps() {
        return completedLaps;
    }

    public synchronized int getPlannedLaps() {
        return pacePlan == null ? 0 : pacePlan.laps;
    }

    public synchronized double getElapsedSeconds() {
        return elapsedSeconds;
    }

    /** 界面展示用的"第几圈"（1-based，完成时等于总圈数）。 */
    public synchronized int getDisplayLap() {
        if (pacePlan == null) {
            return 0;
        }
        int lap = completedLaps + 1;
        if (traveledMeters >= routeLength() - 0.01 && completedLaps >= pacePlan.laps - 1) {
            lap = pacePlan.laps;
        }
        return Math.max(1, Math.min(pacePlan.laps, lap));
    }

    /** 当前实际注入速度（含混淆/缓动/暂停状态）。 */
    public synchronized double getCurrentSpeedMps() {
        return running ? currentSpeedMps : 0.0;
    }

    public synchronized void start() {
        running = true;
        paused = false;
        finished = false;
        lastTickMs = 0L;
        runElapsedSeconds = 0.0;
        currentSpeedMps = 0.0;
    }

    public synchronized void pause() {
        running = false;
        paused = true;
        lastTickMs = 0L;
        currentSpeedMps = 0.0;
    }

    public synchronized void resume() {
        if (finished) {
            return;
        }
        running = true;
        paused = false;
        lastTickMs = 0L;
    }

    public synchronized void stop() {
        running = false;
        paused = false;
        currentSpeedMps = 0.0;
    }

    public synchronized void reset() {
        resetProgress();
        runElapsedSeconds = 0.0;
    }

    public synchronized void seek(double meters) {
        traveledMeters = Math.max(0.0, meters);
        finished = false;
    }

    public synchronized void setRampSeconds(double seconds) {
        this.rampSeconds = Math.max(0.0, seconds);
    }

    public synchronized double getRampSeconds() {
        return rampSeconds;
    }

    /** 推进一帧。 */
    public synchronized boolean advance(long nowMs) {
        if (!running || route == null || route.size() == 0) {
            return false;
        }
        boolean firstTick = lastTickMs == 0L;
        if (firstTick) {
            lastTickMs = nowMs;
        }
        double dt = (nowMs - lastTickMs) / 1000.0;
        if (dt < 0) {
            dt = 0;
        }
        if (dt > 1.0) {
            dt = 1.0;
        }
        lastTickMs = nowMs;

        double routeLength = routeLength();
        if (routeLength <= 1e-9) {
            currentSpeedMps = 0;
            return true;
        }

        elapsedSeconds += dt;
        runElapsedSeconds += dt;
        double globalBefore = getGlobalTraveledMeters();
        double baseSpeed = pacePlan != null ? pacePlan.speedAt(globalBefore) : speedMps;

        double target = baseSpeed;
        if (pacePlan == null && rampSeconds > 0) {
            target *= Math.min(1.0, runElapsedSeconds / rampSeconds);
        }
        if (speedVariance) {
            // 真实跑者配速由三个时间尺度叠加而成，缺了慢变会显得"配速一条直线"：
            // 1) 秒级快抖动 ±2%（GPS 速度噪声）
            variance = variance * 0.90 + random.nextGaussian() * 0.010;
            variance = Math.max(-0.04, Math.min(0.04, variance));
            // 2) 分钟级慢变 ±3%（疲劳、坡度、心率漂移）—— App 的配速曲线就看这一段
            final double tauSlow = 180.0;
            slowBias += -slowBias * Math.min(1.0, dt / tauSlow)
                    + random.nextGaussian() * 0.0045 * Math.sqrt(dt);
            slowBias = Math.max(-0.08, Math.min(0.08, slowBias));
            // 3) 公里级地形/风向波动（±4%）：决定"每公里配速"的天然差异
            final double tauTerrain = 420.0;
            terrainBias += -terrainBias * Math.min(1.0, dt / tauTerrain)
                    + random.nextGaussian() * 0.0028 * Math.sqrt(dt);
            terrainBias = Math.max(-0.10, Math.min(0.10, terrainBias));
            // 4) 累积疲劳：跑得越久越慢一点（真人后半程掉速），上限 8%
            final double fatigue = Math.min(0.08, FATIGUE_PER_MINUTE * (runElapsedSeconds / 60.0));
            // 5) 事件式加减速：平均约 2 分钟一次、持续 ~25 秒、幅度 6–14%
            if (random.nextDouble() < dt / 120.0) {
                surge = (0.06 + random.nextDouble() * 0.08) * (random.nextBoolean() ? 1 : -1);
            }
            surge *= Math.exp(-dt / 25.0);
            target *= (1.0 + variance + slowBias + terrainBias - fatigue + surge);
        }
        if (pacePlan != null) {
            double remainingMeters = pacePlan.actualMeters - globalBefore;
            double remainingTime = pacePlan.durationSeconds - elapsedSeconds;
            if (remainingTime > 0.5 && remainingMeters > 0.5) {
                double required = remainingMeters / remainingTime;
                double planned = Math.max(MIN_SPEED_MPS, baseSpeed);
                double correction = required / planned;
                correction = Math.max(0.85, Math.min(1.15, correction));
                target *= correction;
            }
        }

        double cap = pacePlan != null ? pacePlan.maxSpeedMps : Math.max(0.5, speedMps * 1.4);
        target = Math.max(MIN_SPEED_MPS, Math.min(cap, target));

        boolean smooth = pacePlan != null || speedVariance;
        if (firstTick || !smooth || rampSeconds > 0.0) {
            // 普通模式：直接使用目标速度；有起步缓动时由缓动本身负责平滑
            currentSpeedMps = target;
        } else {
            double maxDelta = ACCEL_LIMIT_MPS2 * dt;
            double delta = target - currentSpeedMps;
            delta = Math.max(-maxDelta, Math.min(maxDelta, delta));
            currentSpeedMps = Math.max(MIN_SPEED_MPS, Math.min(cap, currentSpeedMps + delta));
        }

        traveledMeters += currentSpeedMps * dt;

        if (traveledMeters >= routeLength) {
            if (pacePlan != null && pacePlan.laps > 0) {
                completedLaps++;
                if (completedLaps >= pacePlan.laps) {
                    // 最后一圈：位置停在终点，但跨圈累计里程不能重复加这一圈
                    completedLaps = pacePlan.laps - 1;
                    traveledMeters = routeLength;
                    running = false;
                    finished = true;
                    currentSpeedMps = 0;
                } else {
                    traveledMeters -= routeLength;
                    if (traveledMeters < 0) {
                        traveledMeters = 0;
                    }
                }
            } else if (loop) {
                traveledMeters %= routeLength;
            } else {
                traveledMeters = routeLength;
                running = false;
                finished = true;
                currentSpeedMps = 0;
            }
        }
        return true;
    }

    private double routeLength() {
        if (route == null) {
            return 0;
        }
        return route.totalMeters();
    }

    /** 当前采样点；路径为空时返回 null。 */
    public synchronized Route.Sample currentSample() {
        if (route == null) {
            return null;
        }
        return route.sampleAt(traveledMeters);
    }
}
