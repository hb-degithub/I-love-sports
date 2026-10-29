package com.vloc.motion.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * GNSS 天空模型：真实 TLE 星表 + 位置/时间驱动的可见卫星。
 *
 * <p>单元测试从 {@code app/src/main/assets/gnss_tle.txt} 读真实星表；找不到时直接失败，
 * 避免"用空星表跑过测试"的假绿。
 */
public class GnssSkyTest {

    /** 2026-10-29T00:40:00Z，白天北京上空 GPS/北斗都可见 */
    private static final long UTC = 1793234400000L;
    private static final double BJ_LAT = 39.9042;
    private static final double BJ_LON = 116.4074;
    private static final double GZ_LAT = 23.1291;
    private static final double GZ_LON = 113.2644;

    // ---------------------------------------------------------------- 星表解析

    @Test
    public void catalogParsesRealConstellations() {
        List<GnssTle.Sat> catalog = loadCatalog();
        assertTrue("星表太小: " + catalog.size(), catalog.size() >= 130);
        Set<Integer> constellations = new HashSet<>();
        for (GnssTle.Sat sat : catalog) {
            constellations.add(sat.constellation);
            assertTrue("svid 非法: " + sat.svid, sat.svid > 0);
            assertTrue("平运动非法: " + sat.meanMotionRevPerDay,
                    sat.meanMotionRevPerDay > 0.5 && sat.meanMotionRevPerDay < 2.5);
            assertTrue("倾角非法: " + sat.inclinationDeg,
                    sat.inclinationDeg >= 0 && sat.inclinationDeg <= 90);
            assertTrue("偏心率非法: " + sat.eccentricity,
                    sat.eccentricity >= 0 && sat.eccentricity < 0.3); // Galileo E01/E02 为偏心试验轨道(e≈0.17)
            assertTrue("历元非法", sat.epochUtcMs > 946684800000L); // > 2000-01-01
        }
        assertTrue("缺 GPS", constellations.contains(GnssTle.CONST_GPS));
        assertTrue("缺 GLONASS", constellations.contains(GnssTle.CONST_GLONASS));
        assertTrue("缺北斗", constellations.contains(GnssTle.CONST_BEIDOU));
        assertTrue("缺 Galileo", constellations.contains(GnssTle.CONST_GALILEO));
    }

    @Test
    public void knownSatellitesGetRealSvid() {
        List<GnssTle.Sat> catalog = loadCatalog();
        // GPS BIIR-5 (PRN 22) = NORAD 26407
        GnssTle.Sat gps = findByNorad(catalog, 26407);
        assertNotNull("找不到 NORAD 26407", gps);
        assertEquals(22, gps.svid);
        assertEquals(GnssTle.CONST_GPS, gps.constellation);
        // 北斗 (C06) → 200 + 6
        GnssTle.Sat bds = null;
        for (GnssTle.Sat sat : catalog) {
            if (sat.name.contains("(C06)")) {
                bds = sat;
                break;
            }
        }
        assertNotNull("找不到北斗 C06", bds);
        assertEquals(206, bds.svid);
        assertEquals(GnssTle.CONST_BEIDOU, bds.constellation);
    }

    // ---------------------------------------------------------------- 可见性

    @Test
    public void visibilityDependsOnLocation() {
        GnssSky beijing = skyAt(BJ_LAT, BJ_LON, UTC);
        GnssSky guangzhou = skyAt(GZ_LAT, GZ_LON, UTC);
        assertTrue("北京看不到卫星", beijing.count() >= 15);
        assertTrue("广州看不到卫星", guangzhou.count() >= 15);
        Set<Integer> bj = svidSet(beijing);
        Set<Integer> gz = svidSet(guangzhou);
        // 相距 2000 km：可见集合应该相似但不同（真实观测几何不同）
        assertFalse("北京/广州可见卫星完全相同，说明没用位置", bj.equals(gz));
        Set<Integer> common = new HashSet<>(bj);
        common.retainAll(gz);
        assertTrue("两地重叠太少，可能是坐标没生效: " + common.size(), common.size() >= 8);
    }

    @Test
    public void visibilityDependsOnTime() {
        GnssSky now = skyAt(BJ_LAT, BJ_LON, UTC);
        GnssSky later = skyAt(BJ_LAT, BJ_LON, UTC + 3 * 3600_000L);
        assertFalse("3 小时后卫星集合没变，说明没用时间",
                svidSet(now).equals(svidSet(later)));
    }

    @Test
    public void sameInputIsDeterministic() {
        GnssSky a = skyAt(BJ_LAT, BJ_LON, UTC);
        GnssSky b = skyAt(BJ_LAT, BJ_LON, UTC);
        assertEquals(a.count(), b.count());
        List<GnssSky.Satellite> sa = a.satellites();
        List<GnssSky.Satellite> sb = b.satellites();
        for (int i = 0; i < sa.size(); i++) {
            assertEquals(sa.get(i).svid, sb.get(i).svid);
            assertEquals(sa.get(i).elevationDeg, sb.get(i).elevationDeg, 1e-9);
            assertEquals(sa.get(i).azimuthDeg, sb.get(i).azimuthDeg, 1e-9);
        }
    }

    @Test
    public void satellitesComeAndGoOverHours() {
        GnssSky now = skyAt(BJ_LAT, BJ_LON, UTC);
        Set<Integer> nowSet = svidSet(now);
        // 12 小时后应有卫星落下/升起
        Set<Integer> later = svidSet(skyAt(BJ_LAT, BJ_LON, UTC + 12 * 3600_000L));
        Set<Integer> gone = new HashSet<>(nowSet);
        gone.removeAll(later);
        assertTrue("12 小时内没有任何卫星落下: " + nowSet.size(), gone.size() >= 3);
    }

    @Test
    public void valuesArePhysicallyPlausible() {
        GnssSky sky = skyAt(BJ_LAT, BJ_LON, UTC);
        List<GnssSky.Satellite> sats = sky.satellites();
        assertTrue("可见卫星太少: " + sats.size(), sats.size() >= 15);
        for (GnssSky.Satellite sat : sats) {
            assertTrue("ele=" + sat.elevationDeg,
                    sat.elevationDeg >= GnssSky.MIN_ELEVATION_DEG && sat.elevationDeg <= 90);
            assertTrue("azi=" + sat.azimuthDeg, sat.azimuthDeg >= 0 && sat.azimuthDeg < 360);
            assertTrue("cn0=" + sat.cn0DbHz, sat.cn0DbHz >= 20 && sat.cn0DbHz <= 45);
            assertTrue("baseband<=rf", sat.basebandCn0DbHz <= sat.cn0DbHz);
            assertTrue("carrier>1GHz", sat.carrierHz > 1.0e9);
            assertTrue("低仰角不该参与定位", sat.usedInFix
                    || sat.elevationDeg < GnssSky.FIX_ELEVATION_DEG);
        }
        // 排序稳定：先星座后 Svid
        for (int i = 1; i < sats.size(); i++) {
            int prev = sats.get(i - 1).constellation * 1000 + sats.get(i - 1).svid;
            int cur = sats.get(i).constellation * 1000 + sats.get(i).svid;
            assertTrue("输出顺序不稳定", cur > prev);
        }
    }

    @Test
    public void aggregatesMatchPerSatelliteData() {
        GnssSky sky = skyAt(BJ_LAT, BJ_LON, UTC);
        double max = 0;
        double sum = 0;
        int used = 0;
        for (GnssSky.Satellite sat : sky.satellites()) {
            if (sat.usedInFix) {
                max = Math.max(max, sat.cn0DbHz);
                sum += sat.cn0DbHz;
                used++;
            }
        }
        assertTrue("没有参与定位的卫星", used > 0);
        assertEquals(used, sky.usedInFixCount());
        assertEquals(max, sky.maxCn0(), 1e-6);
        assertEquals(sum / used, sky.meanCn0(), 1e-6);
    }

    // ---------------------------------------------------------------- JSON 契约

    @Test
    public void jsonArrayIsParseableAndMatchesCount() throws Exception {
        GnssSky sky = skyAt(BJ_LAT, BJ_LON, UTC);
        JSONArray array = new JSONArray(sky.toJsonArray());
        assertEquals(sky.count(), array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject sat = array.getJSONObject(i);
            assertTrue(sat.getInt("svid") > 0);
            assertTrue(sat.getInt("const") > 0);
            assertTrue(sat.has("ele"));
            assertTrue(sat.has("azi"));
            assertTrue(sat.has("cn0"));
            assertTrue(sat.has("fix"));
            assertTrue(sat.has("cf"));
            assertTrue(sat.has("base"));
        }
    }

    @Test
    public void emptyCatalogProducesNoSatellites() {
        GnssSky sky = new GnssSky();
        sky.update(0, BJ_LAT, BJ_LON, UTC);
        assertEquals(0, sky.count());
        assertEquals("[]", sky.toJsonArray());
        assertEquals(0.0, sky.meanCn0(), 1e-9);
    }

    @Test
    public void realismEngineLoadsTleAndReplaysByLocation() throws Exception {
        RealismEngine beijing = new RealismEngine();
        beijing.setTleData(loadTleText());
        Fix bjFix = beijing.apply(BJ_LAT, BJ_LON, 50, 3.0, 90.0, 5f);
        assertTrue("北京可见卫星太少: " + beijing.gnssSatellites().size(),
                beijing.gnssSatellites().size() >= 15);
        assertTrue("extras 卫星数应 = 参与定位颗数",
                bjFix.satellites == beijing.usedInFixCount());
        assertTrue(bjFix.maxCn0 >= bjFix.meanCn0);

        RealismEngine guangzhou = new RealismEngine();
        guangzhou.setTleData(loadTleText());
        guangzhou.apply(GZ_LAT, GZ_LON, 50, 3.0, 90.0, 5f);
        assertFalse("两地 gnss 数据相同，说明没有用位置",
                beijing.gnssJson().equals(guangzhou.gnssJson()));

        beijing.setEnabled(false);
        assertEquals("[]", beijing.gnssJson());
    }

    // ---------------------------------------------------------------- 辅助

    private static GnssSky skyAt(double lat, double lon, long utcMs) {
        GnssSky sky = new GnssSky();
        sky.setCatalog(loadCatalog());
        sky.update(0, lat, lon, utcMs);
        return sky;
    }

    private static Set<Integer> svidSet(GnssSky sky) {
        Set<Integer> set = new HashSet<>();
        for (GnssSky.Satellite sat : sky.satellites()) {
            set.add(sat.constellation * 1000 + sat.svid);
        }
        return set;
    }

    private static GnssTle.Sat findByNorad(List<GnssTle.Sat> catalog, int noradId) {
        for (GnssTle.Sat sat : catalog) {
            if (sat.noradId == noradId) {
                return sat;
            }
        }
        return null;
    }

    private static List<GnssTle.Sat> loadCatalog() {
        List<GnssTle.Sat> catalog = GnssTle.parse(loadTleText());
        assertFalse("TLE 星表解析为空", catalog.isEmpty());
        return catalog;
    }

    private static String loadTleText() {
        for (String path : new String[]{
                "src/main/assets/gnss_tle.txt",
                "app/src/main/assets/gnss_tle.txt"}) {
            File file = new File(path);
            if (file.isFile()) {
                try {
                    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new IllegalStateException("读取 TLE 失败: " + path, e);
                }
            }
        }
        throw new IllegalStateException("找不到 assets/gnss_tle.txt（测试需要真实星表）");
    }
}
