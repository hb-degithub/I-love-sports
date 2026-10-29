package com.vloc.motion.core;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PresetRoutesTest {

    private static final double LAT = 39.9042;
    private static final double LON = 116.4074;

    @Test
    public void everyPresetHasEnoughPointsAndPlausibleLength() {
        for (PresetRoutes.Preset preset : PresetRoutes.all()) {
            Route route = PresetRoutes.create(preset.id, LAT, LON);
            assertTrue(preset.id + " points=" + route.size(), route.size() >= 10);
            double length = route.totalMeters();
            assertTrue(preset.id + " length=" + length,
                    Math.abs(length - preset.nominalMeters) / preset.nominalMeters < 0.03);
        }
    }

    @Test
    public void trackIsAboutFourHundredMeters() {
        Route route = PresetRoutes.create("track_400", LAT, LON);
        assertEquals(398.0, route.totalMeters(), 8.0);
    }

    @Test
    public void loopsReturnNearStart() {
        for (String id : new String[]{"track_400", "circle_800", "rect_2000", "rect_3000"}) {
            Route route = PresetRoutes.create(id, LAT, LON);
            Route.Point first = route.get(0);
            Route.Point last = route.get(route.size() - 1);
            double gap = GeoMath.distanceMeters(first.lat, first.lon, last.lat, last.lon);
            assertTrue(id + " gap=" + gap, gap < 6.0);
        }
    }

    @Test
    public void presetsAreAnchoredAtGivenCenter() {
        Route route = PresetRoutes.create("circle_800", LAT, LON);
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (Route.Point point : route.points()) {
            minLat = Math.min(minLat, point.lat);
            maxLat = Math.max(maxLat, point.lat);
            minLon = Math.min(minLon, point.lon);
            maxLon = Math.max(maxLon, point.lon);
        }
        assertTrue(Math.abs((minLat + maxLat) / 2 - LAT) < 0.01);
        assertTrue(Math.abs((minLon + maxLon) / 2 - LON) < 0.01);
    }

    @Test
    public void unknownIdFallsBackToTrack() {
        Route route = PresetRoutes.create("nope", LAT, LON);
        assertEquals(398.0, route.totalMeters(), 8.0);
    }

    @Test
    public void presetsListIsNotEmpty() {
        List<PresetRoutes.Preset> presets = PresetRoutes.all();
        assertTrue(presets.size() >= 5);
    }
}
