package com.vloc.motion.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 预设模拟路线：以给定坐标为中心生成常见形状的路线（跑道/直线/环线等）。
 *
 * <p>局部坐标以米为单位：x 向东、y 向北，再换算成经纬度。
 * 纯 Java，可单元测试。
 */
public final class PresetRoutes {

    /** 一个预设。 */
    public static final class Preset {
        public final String id;
        public final String name;
        public final String description;
        /** 标称长度（米）。 */
        public final double nominalMeters;

        public Preset(String id, String name, String description, double nominalMeters) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.nominalMeters = nominalMeters;
        }
    }

    private static final double METERS_PER_DEG_LAT = 111320.0;
    private static final double SAMPLE_STEP_METERS = 4.0;

    private PresetRoutes() {
    }

    public static List<Preset> all() {
        return Arrays.asList(
                new Preset("track_400", "标准 400m 跑道", "两直道 + 两半圆，约 398m（田径场）", 398.0),
                new Preset("circle_800", "800m 圆形环线", "半径约 127m 的圆环", 800.0),
                new Preset("rect_2000", "2km 矩形环线", "600m × 400m 矩形", 2000.0),
                new Preset("rect_3000", "3km 矩形环线", "1000m × 500m 矩形", 3000.0),
                new Preset("line_1000", "1km 直线", "向北 1000m 直线", 1000.0),
                new Preset("out_back_2000", "2km 直线往返", "向北 1000m 原路返回", 2000.0)
        );
    }

    public static Preset byId(String id) {
        for (Preset preset : all()) {
            if (preset.id.equals(id)) {
                return preset;
            }
        }
        return all().get(0);
    }

    /** 以 (centerLat, centerLon) 为中心生成指定预设路线。 */
    public static Route create(String id, double centerLat, double centerLon) {
        List<double[]> local = buildLocal(id);
        return toRoute(centerLat, centerLon, local);
    }

    private static List<double[]> buildLocal(String id) {
        switch (id == null ? "" : id) {
            case "track_400":
                return track();
            case "circle_800":
                return circle(800.0);
            case "rect_2000":
                return rectangle(600.0, 400.0);
            case "rect_3000":
                return rectangle(1000.0, 500.0);
            case "line_1000":
                return line(1000.0);
            case "out_back_2000":
                return outAndBack(1000.0);
            default:
                return track();
        }
    }

    /** 标准跑道：直道 84.39m、弯道半径 36.5m。 */
    private static List<double[]> track() {
        double straight = 84.39;
        double radius = 36.5;
        List<double[]> points = new ArrayList<>();
        // 右侧直道：由南向北
        addSegment(points, radius, -straight / 2, radius, straight / 2);
        // 顶部半圆：东 → 西
        int arcSteps = (int) Math.ceil(Math.PI * radius / SAMPLE_STEP_METERS);
        for (int i = 1; i <= arcSteps; i++) {
            double angle = Math.PI * i / arcSteps;
            points.add(new double[]{radius * Math.cos(angle), straight / 2 + radius * Math.sin(angle)});
        }
        // 左侧直道：由北向南
        addSegment(points, -radius, straight / 2, -radius, -straight / 2);
        // 底部半圆：西 → 东
        for (int i = 1; i <= arcSteps; i++) {
            double angle = Math.PI + Math.PI * i / arcSteps;
            points.add(new double[]{radius * Math.cos(angle), -straight / 2 + radius * Math.sin(angle)});
        }
        return points;
    }

    private static List<double[]> circle(double circumference) {
        double radius = circumference / (2 * Math.PI);
        int steps = Math.max(24, (int) Math.ceil(circumference / SAMPLE_STEP_METERS));
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i <= steps; i++) {
            double angle = 2 * Math.PI * i / steps;
            points.add(new double[]{radius * Math.cos(angle), radius * Math.sin(angle)});
        }
        return points;
    }

    private static List<double[]> rectangle(double width, double height) {
        double halfW = width / 2;
        double halfH = height / 2;
        List<double[]> points = new ArrayList<>();
        addSegment(points, -halfW, -halfH, halfW, -halfH);
        addSegment(points, halfW, -halfH, halfW, halfH);
        addSegment(points, halfW, halfH, -halfW, halfH);
        addSegment(points, -halfW, halfH, -halfW, -halfH);
        return points;
    }

    private static List<double[]> line(double length) {
        List<double[]> points = new ArrayList<>();
        addSegment(points, 0, -length / 2, 0, length / 2);
        return points;
    }

    private static List<double[]> outAndBack(double oneWay) {
        List<double[]> points = new ArrayList<>();
        addSegment(points, 0, -oneWay / 2, 0, oneWay / 2);
        addSegment(points, 0, oneWay / 2, 0, -oneWay / 2);
        return points;
    }

    private static void addSegment(List<double[]> points, double x1, double y1,
                                   double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double length = Math.hypot(dx, dy);
        int steps = Math.max(1, (int) Math.ceil(length / SAMPLE_STEP_METERS));
        boolean empty = points.isEmpty();
        int start = empty ? 0 : 1;
        for (int i = start; i <= steps; i++) {
            double t = (double) i / steps;
            points.add(new double[]{x1 + dx * t, y1 + dy * t});
        }
    }

    private static Route toRoute(double centerLat, double centerLon, List<double[]> local) {
        Route route = new Route();
        double cosLat = Math.max(0.05, Math.cos(Math.toRadians(centerLat)));
        for (double[] point : local) {
            double lat = centerLat + point[1] / METERS_PER_DEG_LAT;
            double lon = centerLon + point[0] / (METERS_PER_DEG_LAT * cosLat);
            route.add(new Route.Point(lat, lon));
        }
        return route;
    }
}
