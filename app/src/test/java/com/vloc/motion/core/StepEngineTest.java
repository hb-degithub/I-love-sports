package com.vloc.motion.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StepEngineTest {

    @Test
    public void walkingProducesPlausibleCadenceAndStride() {
        StepEngine engine = new StepEngine();
        engine.start();
        for (int i = 0; i < 600; i++) {   // 60 秒，每帧 0.1s
            engine.update(1.4, 0.1);
        }
        double cadence = engine.getCadenceSpm();
        double stride = engine.getStrideMeters();
        assertTrue("cadence=" + cadence, cadence > 78 && cadence < 140);
        assertTrue("stride=" + stride, stride > 0.52 && stride < 1.0);
        // 距离 84m，步数约 84/0.8 ≈ 105
        assertTrue("steps=" + engine.getSessionSteps(),
                engine.getSessionSteps() > 80 && engine.getSessionSteps() < 140);
    }

    @Test
    public void runningProducesHigherCadenceAndLongerStride() {
        StepEngine engine = new StepEngine();
        engine.start();
        for (int i = 0; i < 600; i++) {
            engine.update(3.5, 0.1);
        }
        assertTrue("cadence=" + engine.getCadenceSpm(),
                engine.getCadenceSpm() > 145 && engine.getCadenceSpm() < 215);
        assertTrue("stride=" + engine.getStrideMeters(),
                engine.getStrideMeters() > 0.98 && engine.getStrideMeters() < 1.40);
    }

    @Test
    public void stepsOnlyIncrease() {
        StepEngine engine = new StepEngine();
        engine.start();
        int previous = engine.getSteps();
        for (int i = 0; i < 200; i++) {
            engine.update(2.0, 0.2);
            int now = engine.getSteps();
            assertTrue("steps decreased: " + previous + " -> " + now, now >= previous);
            previous = now;
        }
    }

    @Test
    public void idleFreezesCadenceButKeepsSteps() {
        StepEngine engine = new StepEngine();
        engine.start();
        for (int i = 0; i < 100; i++) {
            engine.update(1.5, 0.1);
        }
        int steps = engine.getSteps();
        engine.idle();
        for (int i = 0; i < 100; i++) {
            engine.update(0, 0.1);
        }
        assertEquals(0.0, engine.getCadenceSpm(), 1e-9);
        assertEquals(steps, engine.getSteps());
    }

    @Test
    public void cadenceVariesWithinOneSession() {
        StepEngine engine = new StepEngine();
        engine.start();
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double sum = 0;
        double sumSq = 0;
        int samples = 0;
        // 模拟 10 分钟定速步行，每 0.2s 采一次步频
        for (int i = 0; i < 3000; i++) {
            engine.update(1.4, 0.2);
            double cadence = engine.getCadenceSpm();
            min = Math.min(min, cadence);
            max = Math.max(max, cadence);
            sum += cadence;
            sumSq += cadence * cadence;
            samples++;
        }
        double mean = sum / samples;
        double variance = sumSq / samples - mean * mean;
        double stddev = Math.sqrt(Math.max(0, variance));
        assertTrue("cadence range too small: " + min + ".." + max + " mean=" + mean,
                max - min > 4.0);
        assertTrue("cadence stddev too small: " + stddev, stddev > 1.0);
        assertTrue("cadence mean out of range: " + mean, mean > 95 && mean < 125);
    }

    @Test
    public void strideVariesWithinOneSession() {
        StepEngine engine = new StepEngine();
        engine.start();
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int i = 0; i < 3000; i++) {
            engine.update(1.4, 0.2);
            min = Math.min(min, engine.getStrideMeters());
            max = Math.max(max, engine.getStrideMeters());
        }
        assertTrue("stride range too small: " + min + ".." + max, max - min > 0.03);
    }

    @Test
    public void stepCountMatchesDistanceWithinTolerance() {
        StepEngine engine = new StepEngine();
        engine.start();
        double distance = 0;
        for (int i = 0; i < 600; i++) {   // 60 秒，1.5 m/s → 90 m
            engine.update(1.5, 0.1);
            distance += 1.5 * 0.1;
        }
        double expected = distance / engine.getStrideMeters();
        assertTrue("steps=" + engine.getSessionSteps() + " expected≈" + expected,
                Math.abs(engine.getSessionSteps() - expected) / expected < 0.10);
    }

    @Test
    public void differentSessionsProduceDifferentGait() {
        double[] means = new double[5];
        for (int session = 0; session < means.length; session++) {
            StepEngine engine = new StepEngine();
            engine.start();
            double sum = 0;
            for (int i = 0; i < 600; i++) {
                engine.update(1.4, 0.1);
                sum += engine.getCadenceSpm();
            }
            means[session] = sum / 600;
        }
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double mean : means) {
            min = Math.min(min, mean);
            max = Math.max(max, mean);
        }
        assertTrue("sessions too similar: " + min + ".." + max, max - min > 0.3);
    }

    @Test
    public void jsonContainsStepFields() {
        StepEngine engine = new StepEngine();
        engine.start();
        engine.update(1.5, 1.0);
        String gnss = "[{\"svid\":5,\"const\":1,\"ele\":42.3,\"azi\":128.7,\"cn0\":34.2,"
                + "\"fix\":true,\"alm\":true,\"eph\":true,\"cf\":1575420000,\"base\":33.1}]";
        String json = engine.toJson(true, true, 12, 30f, 42f, gnss);
        assertTrue(json.contains("\"steps\""));
        assertTrue(json.contains("\"cadenceSpm\""));
        assertTrue(json.contains("\"strideM\""));
        assertTrue(json.contains("\"active\":true"));
        assertTrue(json.contains("\"accelAmp\""));
        assertTrue(json.contains("\"yawRateDps\""));
        assertTrue("version=2 表示带 gnss 数组", json.contains("\"version\":2"));
        assertTrue(json.contains("\"gnss\":[{\"svid\":5"));
    }

    @Test
    public void jsonFallsBackToEmptyGnssArrayWhenMissing() {
        StepEngine engine = new StepEngine();
        engine.start();
        engine.update(1.5, 1.0);
        String json = engine.toJson(true, true, 0, 0f, 0f, null);
        assertTrue(json.contains("\"gnss\":[]"));
    }
}
