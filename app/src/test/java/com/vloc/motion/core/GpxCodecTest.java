package com.vloc.motion.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GpxCodecTest {

    @Test
    public void exportThenImportRoundTrip() {
        Route route = new Route();
        route.add(new Route.Point(39.9, 116.4));
        route.add(new Route.Point(39.91, 116.41));
        String gpx = GpxCodec.toGpx(route, "test", "unit");
        assertTrue(gpx.contains("<trkpt"));
        Route parsed = GpxCodec.fromGpx(gpx);
        assertEquals(2, parsed.size());
        assertEquals(39.9, parsed.get(0).lat, 1e-6);
        assertEquals(116.41, parsed.get(1).lon, 1e-6);
    }

    @Test
    public void parsesAttributesInAnyOrder() {
        String xml = "<gpx><trk><trkseg>"
                + "<trkpt lon=\"116.4\" lat=\"39.9\"></trkpt>"
                + "<trkpt lat=\"39.91\" lon=\"116.41\" />"
                + "</trkseg></trk></gpx>";
        Route parsed = GpxCodec.fromGpx(xml);
        assertEquals(2, parsed.size());
        assertEquals(39.91, parsed.get(1).lat, 1e-9);
    }

    @Test
    public void altitudeRoundTripThroughGpx() {
        Route route = new Route();
        route.add(new Route.Point(39.9, 116.4, 123.4));
        route.add(new Route.Point(39.91, 116.41, 130.0));
        String gpx = GpxCodec.toGpx(route, "alt", "unit");
        assertTrue(gpx.contains("<ele>123.4</ele>"));
        Route parsed = GpxCodec.fromGpx(gpx);
        assertEquals(2, parsed.size());
        assertEquals(123.4, parsed.get(0).altitude, 1e-6);
        assertEquals(130.0, parsed.get(1).altitude, 1e-6);
    }

    @Test
    public void malformedInputYieldsEmptyRoute() {
        assertEquals(0, GpxCodec.fromGpx("not xml at all").size());
        assertEquals(0, GpxCodec.fromGpx(null).size());
    }
}
