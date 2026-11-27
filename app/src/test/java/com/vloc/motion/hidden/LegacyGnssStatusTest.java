package com.vloc.motion.hidden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LegacyGnssStatusTest {
    @Test
    public void distinguishesLegacyCallbackSignatures() {
        Object[] android8to10 = {0, new int[0], new float[0], new float[0],
                new float[0], new float[0]};
        Object[] android11 = {0, new int[0], new float[0], new float[0],
                new float[0], new float[0], new float[0]};
        assertTrue(LegacyGnssStatus.matches(android8to10, false));
        assertTrue(LegacyGnssStatus.matches(android11, true));
        assertFalse(LegacyGnssStatus.matches(android8to10, true));
        assertFalse(LegacyGnssStatus.matches(android11, false));
        android11[6] = new int[0];
        assertFalse(LegacyGnssStatus.matches(android11, true));
    }

    @Test
    public void packsSvidConstellationAndFlagsForBothFormats() {
        assertEquals(0x134d, LegacyGnssStatus.pack(false, 19, 4, true, false, true, true));
        assertEquals(0x1340d, LegacyGnssStatus.pack(true, 19, 4, true, false, true, true));
        assertEquals(0x13402, LegacyGnssStatus.pack(true, 19, 4, false, true, false, false));
    }
}
