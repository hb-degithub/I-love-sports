package com.vloc.motion.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 反检测里可离线验证的决策逻辑：provider 列表规范化、NMEA 生成与校验和。
 *
 * <p>hook 本身（替换返回值）只能在运行中验证，但"该返回什么"在这里被固定住。
 */
public class DetectionAvoidanceTest {

    // ---------------------------------------------------------------- provider 列表

    @Test
    public void dropsCustomTestProvider() {
        List<String> actual = new ArrayList<>(Arrays.asList(
                "passive", "gps", "network", "vloc_test", "fused"));
        List<String> normalized = DetectionAvoidance.normalizeProviders(actual);
        assertFalse("自定义 provider 名是天然指纹，必须去掉", normalized.contains("vloc_test"));
        assertEquals(Arrays.asList("passive", "gps", "network", "fused"), normalized);
    }

    @Test
    public void keepsRealProvidersInCanonicalOrder() {
        List<String> actual = Arrays.asList("fused", "network", "gps", "passive");
        assertEquals(Arrays.asList("passive", "gps", "network", "fused"),
                DetectionAvoidance.normalizeProviders(actual));
    }

    @Test
    public void doesNotInventProvidersThatAreAbsent() {
        List<String> normalized = DetectionAvoidance.normalizeProviders(
                new ArrayList<>(Arrays.asList("passive", "gps")));
        assertEquals(Arrays.asList("passive", "gps"), normalized);
    }

    @Test
    public void emptyOrBogusListFallsBackToStandardSet() {
        assertEquals(4, DetectionAvoidance.normalizeProviders(new ArrayList<>()).size());
        assertEquals(4, DetectionAvoidance.normalizeProviders(
                new ArrayList<>(Arrays.asList("vloc_only"))).size());
        assertTrue(DetectionAvoidance.normalizeProviders(null).isEmpty());
    }

    @Test
    public void detectsCustomProviderNames() {
        assertTrue(DetectionAvoidance.hasCustomProvider(
                new ArrayList<>(Arrays.asList("gps", "vloc_test"))));
        assertFalse(DetectionAvoidance.hasCustomProvider(
                new ArrayList<>(Arrays.asList("gps", "network"))));
        assertFalse(DetectionAvoidance.hasCustomProvider(null));
    }

    // ---------------------------------------------------------------- NMEA

    @Test
    public void nmeaCoordUsesCorrectHemispheres() {
        assertTrue("北纬", DetectionAvoidance.nmeaCoord(38.8947, true).startsWith("3853."));
        assertTrue(DetectionAvoidance.nmeaCoord(38.8947, true).endsWith(",N"));
        assertTrue("南纬", DetectionAvoidance.nmeaCoord(-33.8688, true).endsWith(",S"));
        assertTrue("东经度数为 3 位", DetectionAvoidance.nmeaCoord(121.5286, false).startsWith("12131."));
        assertTrue(DetectionAvoidance.nmeaCoord(121.5286, false).endsWith(",E"));
        assertTrue("西经", DetectionAvoidance.nmeaCoord(-0.1278, false).endsWith(",W"));
    }

    @Test
    public void ggaSentenceHasValidStructureAndChecksum() {
        String gga = DetectionAvoidance.buildGga(38.8947, 121.5286, 1700000000000L, 10, 50.0);
        assertTrue("必须以 $GPGGA 开头: " + gga, gga.startsWith("$GPGGA,"));
        assertTrue("定位质量应为 1（GPS fix）", gga.contains(",1,10,"));
        assertTrue("校验和必须正确", DetectionAvoidance.verifyChecksum(gga));
    }

    @Test
    public void rmcSentenceHasValidStructureAndChecksum() {
        String rmc = DetectionAvoidance.buildRmc(38.8947, 121.5286, 1700000000000L, 3.33, 167.4);
        assertTrue("必须以 $GPRMC 开头: " + rmc, rmc.startsWith("$GPRMC,"));
        assertTrue("状态应为 A（有效）", rmc.contains(",A,"));
        assertTrue(DetectionAvoidance.verifyChecksum(rmc));
        // 3.33 m/s ≈ 6.47 节
        assertTrue("速度应按节换算: " + rmc, rmc.contains("6.47,"));
    }

    @Test
    public void checksumIsXorOfBody() {
        String wrapped = DetectionAvoidance.wrap("GPXXX,1,2,3");
        int expected = 0;
        for (char c : "GPXXX,1,2,3".toCharArray()) {
            expected ^= c;
        }
        assertEquals(String.format("$GPXXX,1,2,3*%02X", expected & 0xFF), wrapped);
        assertTrue(DetectionAvoidance.verifyChecksum(wrapped));
    }

    @Test
    public void tamperedSentenceFailsChecksum() {
        String gga = DetectionAvoidance.buildGga(38.8947, 121.5286, 1700000000000L, 10, 50.0);
        // 改一个字符后校验和应当对不上
        String tampered = gga.replace("3853.", "3854.");
        assertFalse(DetectionAvoidance.verifyChecksum(tampered));
        assertFalse(DetectionAvoidance.verifyChecksum("not-nmea"));
        assertFalse(DetectionAvoidance.verifyChecksum(null));
    }
}
