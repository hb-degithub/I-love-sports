package com.vloc.motion.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RouteTest {

    private Route twoPointRoute() {
        Route r = new Route();
        r.add(new Route.Point(39.9000, 116.4000));
        r.add(new Route.Point(39.9100, 116.4000));
        return r;
    }

    @Test
    public void totalMetersMatchesHaversine() {
        Route r = twoPointRoute();
        double expected = GeoMath.distanceMeters(39.9, 116.4, 39.91, 116.4);
        assertEquals(expected, r.totalMeters(), 1e-6);
    }

    @Test
    public void sampleAtStartMiddleEnd() {
        Route r = twoPointRoute();
        double total = r.totalMeters();

        Route.Sample start = r.sampleAt(0);
        assertEquals(39.9, start.lat, 1e-9);
        assertEquals(116.4, start.lon, 1e-9);
        assertEquals(0.0, start.traveledMeters, 1e-9);
        assertEquals(total, start.totalMeters, 1e-6);

        Route.Sample mid = r.sampleAt(total / 2);
        assertTrue(Math.abs(mid.lat - 39.905) < 1e-4);
        assertEquals(116.4, mid.lon, 1e-6);
        assertEquals(total / 2, mid.traveledMeters, 1e-6);

        Route.Sample end = r.sampleAt(total);
        assertEquals(39.91, end.lat, 1e-9);
        assertEquals(total, end.traveledMeters, 1e-6);
    }

    @Test
    public void bearingPointsNorthForNorthboundRoute() {
        Route.Sample mid = twoPointRoute().sampleAt(twoPointRoute().totalMeters() / 2);
        // 纬度增大、经度不变 => 正北
        assertEquals(0.0, mid.bearingDeg, 1.0);
    }

    @Test
    public void singlePointRouteIsZeroLength() {
        Route r = new Route();
        r.add(new Route.Point(30, 120));
        assertEquals(0.0, r.totalMeters(), 0.0);
        assertNotNull(r.sampleAt(0));
        assertEquals(30, r.sampleAt(0).lat, 1e-9);
    }

    @Test
    public void emptyRouteSampleIsNull() {
        assertNull(new Route().sampleAt(0));
    }

    @Test
    public void encodeDecodeRoundTrip() {
        Route r = twoPointRoute();
        Route decoded = Route.decode(r.encode());
        assertEquals(2, decoded.size());
        assertEquals(39.9, decoded.get(0).lat, 1e-6);
        assertEquals(116.4, decoded.get(1).lon, 1e-6);
    }

    @Test
    public void decodeSkipsBadTokens() {
        Route decoded = Route.decode("39.9,116.4;bad;40.0,116.5;");
        assertEquals(2, decoded.size());
    }

    @Test
    public void removeUpdatesTotal() {
        Route r = twoPointRoute();
        r.add(new Route.Point(39.92, 116.4));
        double total3 = r.totalMeters();
        r.remove(2);
        assertTrue(r.totalMeters() < total3);
        assertEquals(2, r.size());
    }

    @Test
    public void altitudeIsInterpolatedAlongSegment() {
        Route r = new Route();
        r.add(new Route.Point(39.9000, 116.4000, 100.0));
        r.add(new Route.Point(39.9100, 116.4000, 200.0));
        assertTrue(r.hasAltitude());
        Route.Sample mid = r.sampleAt(r.totalMeters() / 2);
        assertEquals(150.0, mid.altitude, 1.0);
    }

    @Test
    public void missingAltitudeStaysNaN() {
        Route r = new Route();
        r.add(new Route.Point(39.9, 116.4));
        assertTrue(!r.hasAltitude());
        assertTrue(Double.isNaN(r.sampleAt(0).altitude));
    }

    @Test
    public void encodeDecodeKeepsAltitude() {
        Route r = new Route();
        r.add(new Route.Point(39.9, 116.4, 55.5));
        r.add(new Route.Point(39.91, 116.4));
        Route decoded = Route.decode(r.encode());
        assertEquals(2, decoded.size());
        assertEquals(55.5, decoded.get(0).altitude, 1e-6);
        assertTrue(Double.isNaN(decoded.get(1).altitude));
    }

    @Test
    public void copyIsIndependent() {
        Route r = twoPointRoute();
        Route copy = r.copy();
        copy.add(new Route.Point(1, 1));
        assertEquals(2, r.size());
        assertEquals(3, copy.size());
    }
}
