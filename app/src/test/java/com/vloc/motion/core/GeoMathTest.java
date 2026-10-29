package com.vloc.motion.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GeoMathTest {

    @Test
    public void distanceOneDegreeOfLongitudeAtEquator() {
        double d = GeoMath.distanceMeters(0, 0, 0, 1);
        assertEquals(111195, d, 500);
    }

    @Test
    public void bearingNorthAndEast() {
        assertEquals(0.0, GeoMath.initialBearing(0, 0, 1, 0), 0.01);
        assertEquals(90.0, GeoMath.initialBearing(0, 0, 0, 1), 0.01);
        assertEquals(180.0, GeoMath.initialBearing(1, 0, 0, 0), 0.01);
    }

    @Test
    public void interpolateMidpoint() {
        double[] mid = GeoMath.interpolate(0, 0, 0, 1, 0.5);
        assertEquals(0.0, mid[0], 1e-9);
        assertEquals(0.5, mid[1], 1e-9);
    }

    @Test
    public void interpolateEndpoints() {
        double[] a = GeoMath.interpolate(10, 20, 30, 40, 0.0);
        assertEquals(10, a[0], 1e-9);
        assertEquals(20, a[1], 1e-9);
        double[] b = GeoMath.interpolate(10, 20, 30, 40, 1.0);
        assertEquals(30, b[0], 1e-9);
        assertEquals(40, b[1], 1e-9);
    }

    @Test
    public void midpointOfRouteIsHalfwayInMeters() {
        double total = GeoMath.distanceMeters(39.9, 116.4, 39.9, 116.41);
        double[] mid = GeoMath.interpolate(39.9, 116.4, 39.9, 116.41, 0.5);
        double d1 = GeoMath.distanceMeters(39.9, 116.4, mid[0], mid[1]);
        assertEquals(total / 2, d1, 1.0);
    }

    @Test
    public void normalizeLongitudeWrapsAround() {
        assertEquals(-170.0, GeoMath.normalizeLongitude(190.0), 1e-9);
        assertEquals(170.0, GeoMath.normalizeLongitude(-190.0), 1e-9);
        assertEquals(0.0, GeoMath.normalizeLongitude(360.0), 1e-9);
    }

    @Test
    public void validityCheck() {
        assertTrue(GeoMath.isValid(39.9, 116.4));
        assertTrue(!GeoMath.isValid(91, 0));
        assertTrue(!GeoMath.isValid(0, 181));
        assertTrue(!GeoMath.isValid(Double.NaN, 0));
    }
}
