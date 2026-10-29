package com.vloc.motion.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GaitAttitudeTest {

    @Test
    public void quaternionIsUnitLength() {
        float[] q = new float[4];
        for (int i = 0; i < 100; i++) {
            double t = i * 0.02;
            GaitAttitude.rotationVector(307.9, 170, t, 3.5, q);
            assertEquals(1.0, GaitAttitude.norm(q), 1e-5);
        }
    }

    @Test
    public void headingFollowsRoute() {
        float[] q = new float[4];
        for (double heading : new double[]{0, 45, 90, 180, 270, 307.9}) {
            GaitAttitude.rotationVector(heading, 170, 0.3, 3.5, q);
            double recovered = GaitAttitude.headingFrom(q);
            double diff = Math.abs(recovered - heading);
            diff = Math.min(diff, 360 - diff);
            assertTrue("heading " + heading + " -> " + recovered, diff < 12.0);
        }
    }

    @Test
    public void attitudeSwingsWhileRunning() {
        float[] forward = new float[4];
        float[] back = new float[4];
        GaitAttitude.rotationVector(90, 170, Math.PI / 2, 3.5, forward);
        GaitAttitude.rotationVector(90, 170, -Math.PI / 2, 3.5, back);
        // 两个半周期姿态应有明显差异
        double diff = Math.abs(forward[0] - back[0]) + Math.abs(forward[1] - back[1])
                + Math.abs(forward[2] - back[2]);
        assertTrue("swing too small: " + diff, diff > 0.15);
    }

    @Test
    public void stationaryHasLittleSwing() {
        float[] a = new float[4];
        float[] b = new float[4];
        GaitAttitude.rotationVector(90, 0, 0, 0, a);
        GaitAttitude.rotationVector(90, 0, Math.PI, 0, b);
        assertEquals(a[0], b[0], 1e-6);
        assertEquals(a[2], b[2], 1e-6);
    }

    @Test
    public void magneticFieldMagnitudeStaysEarthLike() {
        float[] values = new float[3];
        double expected = Math.hypot(GaitAttitude.MAG_HORIZONTAL, GaitAttitude.MAG_VERTICAL);
        for (int i = 0; i < 200; i++) {
            double t = i * 0.02;
            GaitAttitude.magneticField(123, 170, t, 3.5, values);
            double magnitude = Math.sqrt(values[0] * values[0]
                    + values[1] * values[1] + values[2] * values[2]);
            assertEquals("mag at " + t, expected, magnitude, 0.5);
        }
    }

    @Test
    public void magneticFieldRotatesWithHeading() {
        float[] north = new float[3];
        float[] east = new float[3];
        float[] west = new float[3];
        GaitAttitude.magneticField(0, 0, 0, 0, north);
        GaitAttitude.magneticField(90, 0, 0, 0, east);
        GaitAttitude.magneticField(270, 0, 0, 0, west);
        // 朝北时水平分量在设备 y 轴；朝东/朝西时转到 x 轴且方向相反
        assertTrue("north x=" + north[0], Math.abs(north[0]) < 2.0);
        assertTrue("north y=" + north[1], north[1] > 8.0);
        assertTrue("east x=" + east[0], east[0] < -20.0);
        assertTrue("west x=" + west[0], west[0] > 20.0);
        assertEquals(-east[0], west[0], 0.5);
    }

    @Test
    public void gravityDeviceMagnitudeIsEarthLike() {
        float[] gravity = new float[3];
        for (int i = 0; i < 50; i++) {
            GaitAttitude.gravityDevice(123, 170, i * 0.05, 3.5, gravity);
            double magnitude = Math.sqrt(gravity[0] * gravity[0]
                    + gravity[1] * gravity[1] + gravity[2] * gravity[2]);
            assertEquals(ImuWaveform.GRAVITY, magnitude, 0.01);
        }
    }

    @Test
    public void accelMinusGravityEqualsLinearAcceleration() {
        float[] accel = new float[3];
        float[] gravity = new float[3];
        float[] linear = new float[3];
        double phase = 0.7;
        ImuWaveform.accelerometerWithAttitude(200, 170, phase, 3.5, 1.0, accel);
        ImuWaveform.gravityWithAttitude(200, 170, phase, 3.5, 1.0, gravity);
        ImuWaveform.linearAccelerationWithAttitude(200, 170, phase, 3.5, 0.0, linear);
        // 加速度 − 重力 ≈ 线加速度（噪声种子不同，留容差）
        assertEquals(linear[2], accel[2] - gravity[2], 0.25);
    }

    @Test
    public void gravityTiltsWithAttitude() {
        float[] flat = new float[3];
        float[] tilted = new float[3];
        GaitAttitude.gravityDevice(0, 0, 0, 3.5, flat);
        GaitAttitude.gravityDevice(0, 0, Math.PI / 2, 3.5, tilted);
        // 摆动到另一相位时，重力在坐标轴上的分布应变化
        double diff = Math.abs(flat[2] - tilted[2]) + Math.abs(flat[1] - tilted[1]);
        assertTrue("gravity did not tilt: " + diff, diff > 0.5);
    }

    @Test
    public void gameRotationVectorIsUnitAndOffsetFromAbsolute() {
        float[] game = new float[4];
        float[] absolute = new float[4];
        GaitAttitude.gameRotationVector(90, 170, 0.4, 3.5, 37.0, game);
        GaitAttitude.rotationVector(90, 170, 0.4, 3.5, absolute);
        assertEquals(1.0, GaitAttitude.norm(game), 1e-5);
        double diff = Math.abs(game[0] - absolute[0]) + Math.abs(game[1] - absolute[1])
                + Math.abs(game[2] - absolute[2]);
        assertTrue("game RV should differ from absolute: " + diff, diff > 0.05);
    }

    @Test
    public void orientationAzimuthFollowsRoute() {
        float[] values = new float[3];
        for (double heading : new double[]{0, 90, 180, 270}) {
            GaitAttitude.orientation(heading, 170, 0.3, 3.5, values);
            double diff = Math.abs(values[0] - heading);
            diff = Math.min(diff, 360 - diff);
            assertTrue("heading " + heading + " -> azimuth " + values[0], diff < 15.0);
            assertTrue("pitch out of range: " + values[1],
                    values[1] > -180 && values[1] < 180);
            assertTrue("roll out of range: " + values[2],
                    values[2] > -90 && values[2] < 90);
        }
    }

    @Test
    public void orientationSwingsWithGait() {
        float[] a = new float[3];
        float[] b = new float[3];
        GaitAttitude.orientation(90, 170, Math.PI / 2, 3.5, a);
        GaitAttitude.orientation(90, 170, -Math.PI / 2, 3.5, b);
        assertTrue("pitch did not swing: " + a[1] + " vs " + b[1],
                Math.abs(a[1] - b[1]) > 2.0);
    }

    @Test
    public void pitchAmplitudeGrowsWithSpeed() {
        assertTrue(GaitAttitude.pitchAmplitude(3.5) > GaitAttitude.pitchAmplitude(1.4));
        assertEquals(0.0, GaitAttitude.pitchAmplitude(0.0), 1e-9);
        assertTrue(GaitAttitude.pitchAmplitude(3.5) <= 22.0);
    }
}
