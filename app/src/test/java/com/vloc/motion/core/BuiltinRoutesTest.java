package com.vloc.motion.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BuiltinRoutesTest {

    @Test
    public void trackIsClosedLoop() {
        Route route = BuiltinRoutes.track();
        assertEquals(6, route.size());
        Route.Point first = route.get(0);
        Route.Point last = route.get(route.size() - 1);
        double gap = GeoMath.distanceMeters(first.lat, first.lon, last.lat, last.lon);
        assertTrue("gap=" + gap, gap < 0.5);
    }

    @Test
    public void trackCoordinatesMatchUserData() {
        Route route = BuiltinRoutes.track();
        // 存储的是 WGS-84（原始地图坐标转换而来），转回 GCJ 应回到用户给的 lat/lon
        double[] first = ChinaCoord.wgs84ToGcj02(route.get(0).lat, route.get(0).lon);
        assertEquals(38.895602, first[0], 1e-5);
        assertEquals(121.53375, first[1], 1e-5);
        double[] last = ChinaCoord.wgs84ToGcj02(route.get(4).lat, route.get(4).lon);
        assertEquals(38.895702, last[0], 1e-5);
        assertEquals(121.534518, last[1], 1e-5);
    }

    @Test
    public void trackLengthIsPlausible() {
        double length = BuiltinRoutes.track().totalMeters();
        assertTrue("length=" + length, length > 300 && length < 500);
    }

    @Test
    public void anchorIsNearRoute() {
        Route route = BuiltinRoutes.track();
        double distance = GeoMath.distanceMeters(BuiltinRoutes.anchorLat(),
                BuiltinRoutes.anchorLon(), route.get(0).lat, route.get(0).lon);
        assertTrue("anchor distance=" + distance, distance < 500);
    }
}
