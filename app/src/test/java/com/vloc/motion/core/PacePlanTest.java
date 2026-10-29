package com.vloc.motion.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PacePlanTest {

    @Test
    public void lapsRoundUpToCoverTarget() {
        PacePlan plan = PacePlan.create(400, 1000, 600, 4.5, 400);
        assertEquals(3, plan.laps);
        assertEquals(1200.0, plan.plannedMeters, 1e-6);
        assertEquals(1200.0, plan.actualMeters, 1e-6);
    }

    @Test
    public void actualDistanceUsesRouteLength() {
        // 实际路线一圈 350m、用户设定 400m：3 圈实际跑 1050m，仍超过 1000m 目标
        PacePlan plan = PacePlan.create(400, 1000, 600, 4.5, 350);
        assertEquals(3, plan.laps);
        assertEquals(1200.0, plan.plannedMeters, 1e-6);
        assertEquals(1050.0, plan.actualMeters, 1e-6);
    }

    @Test
    public void durationIsCloseToTargetTime() {
        PacePlan plan = PacePlan.create(400, 1000, 600, 4.5, 400);
        assertTrue("duration=" + plan.durationSeconds,
                Math.abs(plan.durationSeconds - 600) <= 9.9);
    }

    @Test
    public void speedsNeverExceedMaxSpeed() {
        PacePlan plan = PacePlan.create(400, 2000, 900, 3.5, 400);
        for (int i = 0; i < plan.blockCount(); i++) {
            assertTrue("block " + i + " speed " + plan.speedAtBlock(i),
                    plan.speedAtBlock(i) <= plan.maxSpeedMps + 1e-9);
        }
    }

    @Test
    public void blockSpeedsAreNotTooFast() {
        PacePlan plan = PacePlan.create(400, 1000, 600, 4.5, 400);
        double cap = Math.min(plan.maxSpeedMps, plan.avgSpeedMps * 1.25 + 0.2);
        for (int i = 0; i < plan.blockCount(); i++) {
            assertTrue("block " + i + " speed " + plan.speedAtBlock(i) + " cap " + cap,
                    plan.speedAtBlock(i) <= cap + 1e-6);
        }
    }

    @Test
    public void speedActuallyVaries() {
        PacePlan plan = PacePlan.create(400, 1200, 700, 4.5, 400);
        double min = Double.MAX_VALUE;
        double max = 0;
        for (int i = 0; i < plan.blockCount(); i++) {
            min = Math.min(min, plan.speedAtBlock(i));
            max = Math.max(max, plan.speedAtBlock(i));
        }
        assertTrue("speed did not vary: " + min + ".." + max, max - min > 0.05);
    }

    @Test
    public void infeasibleWhenTargetTooFast() {
        // 1200m / 60s = 20 m/s，远超成年人最大速度
        PacePlan plan = PacePlan.create(400, 1000, 60, 4.0, 400);
        assertFalse(plan.feasible);
        assertTrue(plan.durationSeconds > 60);
    }

    @Test
    public void speedAtClampsToValidRange() {
        PacePlan plan = PacePlan.create(400, 1000, 600, 4.5, 400);
        assertTrue(plan.speedAt(-100) > 0);
        assertTrue(plan.speedAt(1e9) > 0);
    }
}
