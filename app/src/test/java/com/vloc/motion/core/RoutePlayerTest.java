package com.vloc.motion.core;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RoutePlayerTest {

    private RoutePlayer player;

    @Before
    public void setUp() {
        Route route = new Route();
        // 约 1112 米（0.01 度纬度）
        route.add(new Route.Point(39.9000, 116.4000));
        route.add(new Route.Point(39.9100, 116.4000));
        player = new RoutePlayer();
        player.setRoute(route);
        player.setSpeedMps(10.0);
    }

    @Test
    public void advancesBySpeedTimesTime() {
        player.start();
        player.advance(1000);      // 第一帧只对齐时间
        player.advance(2000);      // 1 秒
        assertEquals(10.0, player.getTraveledMeters(), 1e-6);
        player.advance(3000);      // 再 1 秒
        assertEquals(20.0, player.getTraveledMeters(), 1e-6);
    }

    @Test
    public void clampsElapsedToAvoidJumps() {
        player.start();
        player.advance(1000);
        player.advance(11000); // 10 秒，被限制为 1 秒
        assertEquals(10.0, player.getTraveledMeters(), 1e-6);
    }

    @Test
    public void finishesAtEndWhenNotLooping() {
        player.setLoop(false);
        player.start();
        player.advance(1000);
        double total = player.getRoute().totalMeters();
        // 大步推进直到结束
        for (int i = 0; i < 2000 && !player.isFinished(); i++) {
            player.advance(1000 * (i + 2));
        }
        assertTrue(player.isFinished());
        assertFalse(player.isRunning());
        assertEquals(total, player.getTraveledMeters(), 1e-6);
    }

    @Test
    public void loopsBackToStart() {
        player.setLoop(true);
        player.setSpeedMps(1000.0);
        player.start();
        player.advance(0);
        player.advance(1000); // 1 秒 1000m
        double total = player.getRoute().totalMeters();
        assertTrue(player.getTraveledMeters() < total);
        assertFalse(player.isFinished());
    }

    @Test
    public void pauseStopsAdvanceAndResumeContinues() {
        player.start();
        player.advance(1000);
        player.advance(2000);
        double after = player.getTraveledMeters();
        player.pause();
        assertFalse(player.advance(3000));
        assertEquals(after, player.getTraveledMeters(), 1e-9);
        player.resume();
        player.advance(4000);
        player.advance(5000);
        assertEquals(after + 10.0, player.getTraveledMeters(), 1e-6);
    }

    @Test
    public void currentSampleIsNotNullWhenRouteSet() {
        player.start();
        player.advance(1000);
        assertNotNull(player.currentSample());
    }

    @Test
    public void rampEasesInSpeed() {
        player.setRampSeconds(2.0);
        player.start();
        player.advance(1000);   // 对齐时间基准
        player.advance(2000);   // 推进 1 秒：缓动到 50%
        assertEquals(10.0 * 0.5, player.getCurrentSpeedMps(), 0.2);
        player.advance(4000);   // 再推进 1 秒：缓动完成
        assertEquals(10.0, player.getCurrentSpeedMps(), 1e-6);
    }

    @Test
    public void pausedSpeedIsZero() {
        player.start();
        player.advance(0);
        player.pause();
        assertEquals(0.0, player.getCurrentSpeedMps(), 1e-9);
    }

    @Test
    public void seekJumpsToPosition() {
        player.seek(500);
        assertEquals(500, player.getTraveledMeters(), 1e-9);
    }
}
