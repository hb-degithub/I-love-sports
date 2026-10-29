package com.vloc.motion.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GoalPlannerTest {

    @Test
    public void paceAndDistanceProduceTime() {
        // 400m 单圈、目标 1km、路线正好 400m、配速 6:00/km
        GoalPlanner.Estimate e = GoalPlanner.estimate(400, 1000, 400, 360, 15);
        assertEquals(3, e.laps);
        assertEquals(1200.0, e.plannedMeters, 1e-6);
        assertEquals(1200.0, e.actualMeters, 1e-6);
        assertEquals(432.0, e.timeSeconds, 1e-6);   // 1.2km × 6min
        assertEquals(1200.0 / 432.0, e.avgSpeedMps, 1e-9);
        assertTrue(e.feasible);
    }

    @Test
    public void routeLengthDiffersFromLap() {
        // 路线实际一圈 398m，仍按 3 圈：实际 1194m
        GoalPlanner.Estimate e = GoalPlanner.estimate(400, 1000, 398, 360, 15);
        assertEquals(3, e.laps);
        assertEquals(1200.0, e.plannedMeters, 1e-6);
        assertEquals(1194.0, e.actualMeters, 1e-6);
        assertEquals(1194.0 / 1000.0 * 360, e.timeSeconds, 1e-6);
    }

    @Test
    public void infeasibleWhenPaceFasterThanMaxSpeed() {
        // 4:00/km = 4.17 m/s 接近 15km/h 上限；3:00/km = 5.56 m/s 超过
        GoalPlanner.Estimate fast = GoalPlanner.estimate(400, 1000, 400, 180, 15);
        assertFalse(fast.feasible);
        assertTrue(fast.minPossibleSeconds > fast.timeSeconds);
    }

    @Test
    public void zeroOrNegativeValuesAreClamped() {
        GoalPlanner.Estimate e = GoalPlanner.estimate(0, -5, 0, 0, 0);
        assertTrue(e.laps >= 1);
        assertTrue(e.plannedMeters > 0);
        assertTrue(e.timeSeconds > 0);
        assertTrue(e.avgSpeedMps > 0);
    }
}
