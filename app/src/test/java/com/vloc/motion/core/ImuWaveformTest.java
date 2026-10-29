package com.vloc.motion.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ImuWaveformTest {

    @Test
    public void amplitudeGrowsWithSpeed() {
        assertTrue(ImuWaveform.accelAmplitude(1.4) > 2.0);
        assertTrue(ImuWaveform.accelAmplitude(3.5) > 5.0);
        assertTrue(ImuWaveform.accelAmplitude(3.5) > ImuWaveform.accelAmplitude(1.4));
        assertEquals(0.0, ImuWaveform.accelAmplitude(0.0), 1e-9);
    }

    @Test
    public void accelerometerOscillatesAroundGravity() {
        float[] values = new float[3];
        double amp = ImuWaveform.accelAmplitude(1.4);
        double cadence = 105;
        double max = -1e9;
        double min = 1e9;
        for (int i = 0; i < 200; i++) {
            double t = i * 0.02;
            ImuWaveform.accelerometer(amp, ImuWaveform.phase(cadence, t), t, values);
            max = Math.max(max, values[2]);
            min = Math.min(min, values[2]);
        }
        assertEquals(ImuWaveform.GRAVITY + amp, max, 0.35);
        assertEquals(ImuWaveform.GRAVITY - amp, min, 0.35);
    }

    @Test
    public void stationaryHasNoOscillation() {
        float[] values = new float[3];
        double amp = ImuWaveform.accelAmplitude(0.0);
        double max = -1e9;
        double min = 1e9;
        for (int i = 0; i < 100; i++) {
            ImuWaveform.accelerometer(amp, 0, i * 0.02, values);
            max = Math.max(max, values[2]);
            min = Math.min(min, values[2]);
        }
        assertTrue("range too large: " + (max - min), max - min < 0.25);
    }

    @Test
    public void linearAccelerationRemovesGravity() {
        float[] values = new float[3];
        double amp = ImuWaveform.accelAmplitude(3.0);
        for (int i = 0; i < 100; i++) {
            double t = i * 0.02;
            ImuWaveform.linearAcceleration(amp, ImuWaveform.phase(165, t), t, values);
            assertTrue("z should stay small: " + values[2], Math.abs(values[2]) <= amp + 0.4);
        }
    }

    @Test
    public void gyroscopeCarriesYawRate() {
        float[] values = new float[3];
        double amp = ImuWaveform.accelAmplitude(2.0);
        ImuWaveform.gyroscope(amp, 0.5, 0.42, 1.0, values);
        assertEquals(0.42, values[2], 0.05);
    }

    @Test
    public void phaseAdvancesWithCadence() {
        double p1 = ImuWaveform.phase(120, 0.25);
        assertEquals(Math.PI, p1, 1e-9);   // 120 spm = 2 步/秒 → 0.25s = 半周期
    }
}
