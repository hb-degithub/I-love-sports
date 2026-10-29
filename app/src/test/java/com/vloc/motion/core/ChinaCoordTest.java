package com.vloc.motion.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChinaCoordTest {

    @Test
    public void userJsonLatLonIsOffsetFromMapCoordinates() {
        // 用户 JSON：lat/lon 比 glat/glon（GCJ-02，叠高德卫星图正中操场）多出约 0.0061/0.0065
        double[][] gcj = BuiltinRoutes.gcjPoints();
        double[][] latLon = {
                {38.901678, 121.540241},
                {38.900981, 121.540439},
                {38.901118, 121.541769},
                {38.901778, 121.541894},
                {38.901792, 121.541005}
        };
        for (int i = 0; i < gcj.length; i++) {
            double dLat = latLon[i][0] - gcj[i][0];
            double dLon = latLon[i][1] - gcj[i][1];
            assertEquals("point " + i + " dLat", 0.00608, dLat, 0.0003);
            assertEquals("point " + i + " dLon", 0.00649, dLon, 0.0003);
        }
    }

    @Test
    public void builtinRouteWgsIsSouthWestOfMapCoordinate() {
        // GCJ-02 → WGS-84 应向左下偏移约 400-600 米
        double[] gcj = BuiltinRoutes.gcjPoints()[0];
        double[] wgs = ChinaCoord.gcj02ToWgs84(gcj[0], gcj[1]);
        assertTrue(wgs[0] < gcj[0]);
        assertTrue(wgs[1] < gcj[1]);
        double shift = GeoMath.distanceMeters(gcj[0], gcj[1], wgs[0], wgs[1]);
        assertTrue("shift=" + shift, shift > 200 && shift < 800);
    }

    @Test
    public void builtinRouteRoundTripsToOriginalMapCoordinates() {
        double[][] gcj = BuiltinRoutes.gcjPoints();
        for (int i = 0; i < gcj.length; i++) {
            double[] wgs = ChinaCoord.gcj02ToWgs84(gcj[i][0], gcj[i][1]);
            double[] back = ChinaCoord.wgs84ToGcj02(wgs[0], wgs[1]);
            double error = GeoMath.distanceMeters(gcj[i][0], gcj[i][1], back[0], back[1]);
            assertTrue("point " + i + " error=" + error, error < 1.0);
        }
    }

    @Test
    public void roundTripIsStable() {
        double lat = 39.9042;
        double lon = 116.4074;
        for (int i = 0; i < 5; i++) {
            double[] gcj = ChinaCoord.wgs84ToGcj02(lat, lon);
            double[] back = ChinaCoord.gcj02ToWgs84(gcj[0], gcj[1]);
            assertEquals(lat, back[0], 1e-6);
            assertEquals(lon, back[1], 1e-6);
        }
    }

    @Test
    public void shiftIsHundredsOfMetersInChina() {
        double[] gcj = ChinaCoord.wgs84ToGcj02(39.9042, 116.4074);
        double shift = GeoMath.distanceMeters(39.9042, 116.4074, gcj[0], gcj[1]);
        assertTrue("shift=" + shift, shift > 100 && shift < 1200);
    }

    @Test
    public void outsideChinaIsUnchanged() {
        double[] same = ChinaCoord.wgs84ToGcj02(35.6762, 139.6503); // Tokyo
        assertEquals(35.6762, same[0], 1e-9);
        assertEquals(139.6503, same[1], 1e-9);
        double[] back = ChinaCoord.gcj02ToWgs84(35.6762, 139.6503);
        assertEquals(35.6762, back[0], 1e-9);
    }
}
