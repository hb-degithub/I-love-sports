package com.vloc.motion.core;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RoutePlayerPaceTest {

    private RoutePlayer player;

    @Before
    public void setUp() {
        Route route = new Route();
        // 约 100m
        route.add(new Route.Point(39.9000, 116.4000));
        route.add(new Route.Point(39.9009, 116.4000));
        player = new RoutePlayer();
        player.setRoute(route);
    }

    @Test
    public void pacePlanCompletesLapsWithinTargetTime() {
        double routeMeters = player.getRoute().totalMeters();
        PacePlan plan = PacePlan.create(routeMeters, routeMeters * 3, 90, 4.0, routeMeters);
        assertEquals(3, plan.laps);
        player.setPacePlan(plan);
        player.start();

        long now = 1000;
        int guard = 0;
        while (!player.isFinished() && guard++ < 20000) {
            player.advance(now);
            now += 200;
        }
        assertTrue("player did not finish", player.isFinished());
        assertEquals(3, player.getDisplayLap());
        assertEquals(plan.actualMeters, player.getGlobalTraveledMeters(), 1.0);
        // 目标 90s，允许 ±10s
        assertTrue("elapsed=" + player.getElapsedSeconds(),
                Math.abs(player.getElapsedSeconds() - 90) <= 10);
    }

    @Test
    public void maxSpeedNeverExceededDuringPlayback() {
        double routeMeters = player.getRoute().totalMeters();
        PacePlan plan = PacePlan.create(routeMeters, routeMeters * 2, 60, 3.0, routeMeters);
        player.setPacePlan(plan);
        player.start();
        long now = 1000;
        double maxSeen = 0;
        int guard = 0;
        while (!player.isFinished() && guard++ < 20000) {
            player.advance(now);
            maxSeen = Math.max(maxSeen, player.getCurrentSpeedMps());
            now += 200;
        }
        assertTrue("max speed seen=" + maxSeen, maxSeen <= 3.0 + 0.2);
    }

    @Test
    public void speedVarianceKeepsSpeedInRange() {
        player.setSpeedKmh(10.0);
        player.setLoop(true);
        player.setSpeedVariance(true);
        player.start();
        long now = 1000;
        for (int i = 0; i < 400; i++) {
            player.advance(now);
            now += 200;
            double speed = player.getCurrentSpeedMps();
            assertTrue("speed=" + speed, speed > 0 && speed < (10 / 3.6) * 1.4 + 0.1);
        }
    }
}
