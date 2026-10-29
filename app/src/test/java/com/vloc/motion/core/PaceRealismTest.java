package com.vloc.motion.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 配速自然度回归测试：真人配速不是一条直线。
 *
 * <p>旧实现只有秒级快抖动（±8%，τ≈2s），目标 App 的平滑窗口会直接抹平，配速曲线看起来
 * 是一条直线（实测每公里分段标准差 ±3s，明显可判为合成轨迹）。现在叠加了
 * 分钟级慢变、公里级地形、累积疲劳与事件式加减速，本测试守住这些特性不被改回去。
 */
public class PaceRealismTest {

    /** 8'17"/km */
    private static final double TARGET_KMH = 60.0 / 8.283;

    @Test
    public void kilometerSplitsVaryLikeARealRunner() {
        List<Double> splits = simulateSplits(5);
        assertTrue("分段太少: " + splits.size(), splits.size() >= 4);

        double mean = 0;
        for (double split : splits) {
            mean += split;
        }
        mean /= splits.size();
        double variance = 0;
        for (double split : splits) {
            variance += (split - mean) * (split - mean);
        }
        double sdSeconds = Math.sqrt(variance / splits.size()) * 60.0;

        // 真实跑者 5 km：分段标准差通常 10–30 秒；低于 8 秒就是"配速直线"，一眼假
        assertTrue("每公里分段太稳（合成感）: ±" + sdSeconds + " 秒", sdSeconds > 8.0);
        assertTrue("每公里分段抖动过大（不像跑步）: ±" + sdSeconds + " 秒", sdSeconds < 60.0);
        // 平均配速不能被波动带偏太多
        assertEquals("平均配速偏离目标", 60.0 / TARGET_KMH, mean, 0.5);
    }

    @Test
    public void paceDriftsOnMinuteScaleNotJustSeconds() {
        RoutePlayer player = newPlayer();
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        List<Double> speeds = new ArrayList<>();
        long now = 1000;
        for (int i = 0; i < 9000; i++) {          // 50 分钟 @5Hz
            now += 230;
            player.advance(now);
            if (i % 25 != 0) {
                continue;                          // 每 5 秒取一个点
            }
            double kmh = player.getCurrentSpeedMps() * 3.6;
            speeds.add(kmh);
            min = Math.min(min, kmh);
            max = Math.max(max, kmh);
        }
        double mean = speeds.stream().mapToDouble(x -> x).average().orElse(0);
        assertTrue("速度几乎没有慢变，配速会显示成直线: " + min + ".." + max,
                max - min > 0.6);                  // > 0.6 km/h ≈ 25 秒/公里
        assertTrue("平均速度偏离目标: " + mean, Math.abs(mean - TARGET_KMH) < 1.2);
    }

    /** 跑 {@code kilometers} 公里，返回每公里配速（分/公里）。 */
    private static List<Double> simulateSplits(int kilometers) {
        RoutePlayer player = newPlayer();
        List<Double> splits = new ArrayList<>();
        long now = 1000;
        double distance = 0;
        double lat = 39.9042;
        double lon = 116.4074;
        double nextKm = 1000;
        double lastKmTime = 0;
        for (int i = 0; i < kilometers * 4000; i++) {
            now += 250;
            player.advance(now);
            Route.Sample sample = player.currentSample();
            if (sample == null) {
                continue;
            }
            double d = GeoMath.distanceMeters(lat, lon, sample.lat, sample.lon);
            distance += d;
            lat = sample.lat;
            lon = sample.lon;
            double seconds = (now - 1000) / 1000.0;
            while (distance >= nextKm) {
                splits.add((seconds - lastKmTime) / 60.0);
                lastKmTime = seconds;
                nextKm += 1000;
            }
        }
        return splits;
    }

    private static RoutePlayer newPlayer() {
        return newPlayer(new Random(20261029L));
    }

    private static RoutePlayer newPlayer(Random random) {
        Route route = new Route();
        double lat = 39.9042;
        double lon = 116.4074;
        // 30 km 直道，保证不会跑完
        for (int i = 0; i <= 400; i++) {
            route.add(new Route.Point(lat + i * 0.0009, lon + i * 0.0012, 50));
        }
        RoutePlayer player = new RoutePlayer(new Random(20261029L));
        player.setRoute(route);
        player.setSpeedKmh(TARGET_KMH);
        player.setSpeedVariance(true);
        player.setRampSeconds(2.0);
        player.start();
        return player;
    }
}
