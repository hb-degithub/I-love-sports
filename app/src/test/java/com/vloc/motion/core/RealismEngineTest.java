package com.vloc.motion.core;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RealismEngineTest {

    private static final double LAT = 39.9;
    private static final double LON = 116.4;

    @Test
    public void disabledReturnsExactCoordinates() {
        RealismEngine engine = new RealismEngine(new Random(1L), 2.5);
        engine.setEnabled(false);
        Fix fix = engine.apply(LAT, LON, 50.0, 5.0, 90.0, 5f);
        assertEquals(LAT, fix.lat, 1e-9);
        assertEquals(LON, fix.lon, 1e-9);
        assertEquals(50.0, fix.altitudeMeters, 1e-9);
        assertEquals(5.0, fix.speedMps, 1e-4);
        assertEquals(90.0, fix.bearingDeg, 1e-4);
    }

    @Test
    public void enabledDriftStaysWithinRadius() {
        RealismEngine engine = new RealismEngine(new Random(42L), 2.5);
        for (int i = 0; i < 500; i++) {
            engine.apply(LAT, LON, 50.0, 5.0, 90.0, 5f);
            assertTrue("drift " + engine.driftMagnitude(),
                    engine.driftMagnitude() <= 2.5 + 1e-6);
        }
    }

    @Test
    public void enabledProducesPlausibleGnssData() {
        RealismEngine engine = new RealismEngine(new Random(7L), 2.5);
        engine.setTleData(loadTleText());
        for (int i = 0; i < 200; i++) {
            Fix fix = engine.apply(LAT, LON, 50.0, 5.0, 350.0, 5f);
            // extras 口径 = 参与定位的卫星数（真实 GnssLocationProvider 就是这个口径）
            assertTrue("satellites=" + fix.satellites, fix.satellites >= 8 && fix.satellites <= 96);
            int visible = engine.gnssSatellites().size();
            assertTrue("visible=" + visible, visible >= 15 && visible <= 96);  // 四星座同时可见可达 60+
            assertTrue("used<=visible", fix.satellites <= visible);
            assertTrue(fix.accuracyMeters > 0);
            assertTrue(fix.verticalAccuracyMeters > fix.accuracyMeters);
            assertTrue(fix.maxCn0 >= fix.meanCn0);
            assertTrue("cn0 合理范围", fix.meanCn0 >= 20f && fix.maxCn0 <= 45f);
            assertTrue(fix.bearingDeg >= 0 && fix.bearingDeg < 360);
            assertTrue(fix.speedMps >= 0);
            // 位置偏移不超过漂移半径 + 数值误差
            double dLatMeters = (fix.lat - LAT) * 111320.0;
            double dLonMeters = (fix.lon - LON) * 111320.0 * Math.cos(Math.toRadians(LAT));
            assertTrue(Math.hypot(dLatMeters, dLonMeters) <= 2.6);
        }
    }

    /**
     * 漂移必须是"时间连续"的：同样 1 秒内，4-5 Hz 注入时相邻两帧的位置变化
     * 应明显小于 1 Hz 注入（旧实现每帧固定 0.28m，与帧率无关，导致高刷注入轨迹发跳）。
     */
    @Test
    public void driftIsTimeContinuousNotPerFrame() {
        long t0 = 1_700_000_000_000L;
        RealismEngine fast = new RealismEngine(new Random(11L), 1.2);
        RealismEngine slow = new RealismEngine(new Random(11L), 1.2);
        double fastStepSum = 0;
        double slowStepSum = 0;
        int fastCount = 0;
        int slowCount = 0;
        double prevFastLat = Double.NaN;
        double prevFastLon = Double.NaN;
        double prevSlowLat = Double.NaN;
        double prevSlowLon = Double.NaN;
        for (int i = 1; i <= 300; i++) {
            long now = t0 + i * 200L;   // 5 Hz
            Fix f = fast.applyAt(LAT, LON, 50, 0, 90, 5f, now);
            if (!Double.isNaN(prevFastLat)) {
                fastStepSum += Math.hypot((f.lat - prevFastLat) * 111320.0,
                        (f.lon - prevFastLon) * 111320.0 * Math.cos(Math.toRadians(LAT)));
                fastCount++;
            }
            prevFastLat = f.lat;
            prevFastLon = f.lon;
        }
        for (int i = 1; i <= 60; i++) {
            long now = t0 + i * 1000L;  // 1 Hz
            Fix f = slow.applyAt(LAT, LON, 50, 0, 90, 5f, now);
            if (!Double.isNaN(prevSlowLat)) {
                slowStepSum += Math.hypot((f.lat - prevSlowLat) * 111320.0,
                        (f.lon - prevSlowLon) * 111320.0 * Math.cos(Math.toRadians(LAT)));
                slowCount++;
            }
            prevSlowLat = f.lat;
            prevSlowLon = f.lon;
        }
        double fastMean = fastStepSum / fastCount;
        double slowMean = slowStepSum / slowCount;
        assertTrue("5Hz 单帧漂移 " + fastMean + " 不应大于 1Hz 单帧漂移 " + slowMean,
                fastMean < slowMean * 0.7);
        assertTrue("5Hz 单帧漂移应 < 0.3m（真机噪声量级）: " + fastMean, fastMean < 0.3);
        assertTrue("1Hz 单帧漂移应 > 0.25m: " + slowMean, slowMean > 0.25);
    }

    @Test
    public void withoutTleNoGnssData() {
        RealismEngine engine = new RealismEngine(new Random(7L), 2.5);
        Fix fix = engine.apply(LAT, LON, 50.0, 5.0, 350.0, 5f);
        assertEquals("没有星表时不应编造卫星", 0, fix.satellites);
        assertEquals("[]", engine.gnssJson());
    }

    private static String loadTleText() {
        for (String path : new String[]{
                "src/main/assets/gnss_tle.txt",
                "app/src/main/assets/gnss_tle.txt"}) {
            File file = new File(path);
            if (file.isFile()) {
                try {
                    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new IllegalStateException("读取 TLE 失败: " + path, e);
                }
            }
        }
        throw new IllegalStateException("找不到 assets/gnss_tle.txt");
    }

    @Test
    public void resetDriftZeroesOffset() {
        RealismEngine engine = new RealismEngine(new Random(3L), 2.5);
        for (int i = 0; i < 50; i++) {
            engine.apply(LAT, LON, 50.0, 5.0, 0.0, 5f);
        }
        assertTrue(engine.driftMagnitude() > 0);
        engine.resetDrift();
        assertEquals(0.0, engine.driftMagnitude(), 1e-12);
    }
}
