package com.vloc.motion.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 一条可播放的路径：若干个途经点（含可选海拔），支持按里程采样（位置 + 朝向 + 海拔）。
 * 纯 Java，无 Android 依赖。
 */
public final class Route {

    /** 途经点。altitude 为 NaN 表示未指定海拔（播放时使用全局基础海拔）。 */
    public static final class Point {
        public final double lat;
        public final double lon;
        public final double altitude;

        public Point(double lat, double lon) {
            this(lat, lon, Double.NaN);
        }

        public Point(double lat, double lon, double altitude) {
            this.lat = lat;
            this.lon = lon;
            this.altitude = altitude;
        }

        @Override
        public String toString() {
            if (Double.isNaN(altitude)) {
                return String.format(Locale.US, "%.6f,%.6f", lat, lon);
            }
            return String.format(Locale.US, "%.6f,%.6f,%.1f", lat, lon, altitude);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Point)) {
                return false;
            }
            Point p = (Point) o;
            return Double.compare(p.lat, lat) == 0
                    && Double.compare(p.lon, lon) == 0
                    && Double.compare(p.altitude, altitude) == 0;
        }

        @Override
        public int hashCode() {
            return Double.hashCode(lat) * 31 + Double.hashCode(lon) * 17 + Double.hashCode(altitude);
        }
    }

    /** 采样结果。 */
    public static final class Sample {
        public final double lat;
        public final double lon;
        /** 海拔（米）；NaN 表示该段未指定海拔。 */
        public final double altitude;
        public final double bearingDeg;
        public final double traveledMeters;
        public final double totalMeters;
        public final int segmentIndex;

        public Sample(double lat, double lon, double altitude, double bearingDeg,
                      double traveledMeters, double totalMeters, int segmentIndex) {
            this.lat = lat;
            this.lon = lon;
            this.altitude = altitude;
            this.bearingDeg = bearingDeg;
            this.traveledMeters = traveledMeters;
            this.totalMeters = totalMeters;
            this.segmentIndex = segmentIndex;
        }
    }

    private final List<Point> points = new ArrayList<>();
    private double[] cumulative;
    private boolean dirty = true;

    public Route() {
    }

    public Route(List<Point> initial) {
        if (initial != null) {
            for (Point p : initial) {
                if (GeoMath.isValid(p.lat, p.lon)) {
                    points.add(p);
                }
            }
        }
    }

    public void add(Point p) {
        if (p == null || !GeoMath.isValid(p.lat, p.lon)) {
            return;
        }
        points.add(p);
        dirty = true;
    }

    public void add(int index, Point p) {
        if (p == null || !GeoMath.isValid(p.lat, p.lon)) {
            return;
        }
        int i = Math.max(0, Math.min(index, points.size()));
        points.add(i, p);
        dirty = true;
    }

    public Point remove(int index) {
        if (index < 0 || index >= points.size()) {
            return null;
        }
        Point p = points.remove(index);
        dirty = true;
        return p;
    }

    public void clear() {
        points.clear();
        dirty = true;
    }

    public int size() {
        return points.size();
    }

    public Point get(int i) {
        return points.get(i);
    }

    public List<Point> points() {
        return Collections.unmodifiableList(points);
    }

    /** 总里程（米）。 */
    public double totalMeters() {
        ensureCumulative();
        return points.size() < 2 ? 0.0 : cumulative[points.size() - 1];
    }

    private void ensureCumulative() {
        if (!dirty && cumulative != null && cumulative.length == points.size()) {
            return;
        }
        cumulative = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            Point a = points.get(i - 1);
            Point b = points.get(i);
            cumulative[i] = cumulative[i - 1] + GeoMath.distanceMeters(a.lat, a.lon, b.lat, b.lon);
        }
        dirty = false;
    }

    /** 找到 meters 落在的线段下标 i，满足 cum[i] <= meters <= cum[i+1]。 */
    private int segmentIndex(double meters) {
        ensureCumulative();
        int last = points.size() - 2;
        if (last <= 0) {
            return 0;
        }
        int lo = 0;
        int hi = last;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (cumulative[mid] <= meters) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** 在里程 meters 处采样；路径为空返回 null。 */
    public Sample sampleAt(double meters) {
        if (points.isEmpty()) {
            return null;
        }
        if (points.size() == 1) {
            Point p = points.get(0);
            return new Sample(p.lat, p.lon, p.altitude, 0.0, 0.0, 0.0, 0);
        }
        ensureCumulative();
        double total = totalMeters();
        if (total <= 1e-9) {
            Point p = points.get(0);
            return new Sample(p.lat, p.lon, p.altitude, 0.0, 0.0, 0.0, 0);
        }
        double d = Math.max(0.0, Math.min(meters, total));
        int i = segmentIndex(d);
        Point a = points.get(i);
        Point b = points.get(i + 1);
        double seg = cumulative[i + 1] - cumulative[i];
        double t = seg <= 1e-9 ? 0.0 : (d - cumulative[i]) / seg;
        double[] pos = GeoMath.interpolate(a.lat, a.lon, b.lat, b.lon, t);

        // 海拔：两端都有值时线性插值，只有一端有值时沿用该值
        double altitude = Double.NaN;
        if (!Double.isNaN(a.altitude) && !Double.isNaN(b.altitude)) {
            altitude = a.altitude + (b.altitude - a.altitude) * t;
        } else if (!Double.isNaN(a.altitude)) {
            altitude = a.altitude;
        } else if (!Double.isNaN(b.altitude)) {
            altitude = b.altitude;
        }

        // 朝向：取前方 5 米处的点计算方位角，过弯更平滑
        double ahead = Math.min(total, d + 5.0);
        int j = segmentIndex(ahead);
        Point c = points.get(j);
        Point e = points.get(j + 1);
        double seg2 = cumulative[j + 1] - cumulative[j];
        double t2 = seg2 <= 1e-9 ? 0.0 : (ahead - cumulative[j]) / seg2;
        double[] posAhead = GeoMath.interpolate(c.lat, c.lon, e.lat, e.lon, t2);

        double bearing = GeoMath.initialBearing(pos[0], pos[1], posAhead[0], posAhead[1]);
        if (Double.isNaN(bearing)
                || (Math.abs(posAhead[0] - pos[0]) < 1e-9 && Math.abs(posAhead[1] - pos[1]) < 1e-9)) {
            bearing = GeoMath.initialBearing(a.lat, a.lon, b.lat, b.lon);
        }
        return new Sample(pos[0], pos[1], altitude, bearing, d, total, i);
    }

    /** 是否所有点都带海拔。 */
    public boolean hasAltitude() {
        if (points.isEmpty()) {
            return false;
        }
        for (Point p : points) {
            if (Double.isNaN(p.altitude)) {
                return false;
            }
        }
        return true;
    }

    /** 序列化为 "lat,lon,alt;lat,lon,alt"（无海拔时省略第三段）。 */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        for (Point p : points) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            if (Double.isNaN(p.altitude)) {
                sb.append(String.format(Locale.US, "%.7f,%.7f", p.lat, p.lon));
            } else {
                sb.append(String.format(Locale.US, "%.7f,%.7f,%.1f", p.lat, p.lon, p.altitude));
            }
        }
        return sb.toString();
    }

    /** 反序列化，非法数据跳过。 */
    public static Route decode(String encoded) {
        Route route = new Route();
        if (encoded == null || encoded.trim().isEmpty()) {
            return route;
        }
        for (String token : encoded.split(";")) {
            String s = token.trim();
            if (s.isEmpty()) {
                continue;
            }
            String[] parts = s.split(",");
            if (parts.length < 2) {
                continue;
            }
            try {
                double lat = Double.parseDouble(parts[0].trim());
                double lon = Double.parseDouble(parts[1].trim());
                double alt = Double.NaN;
                if (parts.length >= 3 && !parts[2].trim().isEmpty()) {
                    alt = Double.parseDouble(parts[2].trim());
                }
                route.add(new Point(lat, lon, alt));
            } catch (NumberFormatException ignored) {
                // 跳过坏点
            }
        }
        return route;
    }

    /** 深拷贝。 */
    public Route copy() {
        Route r = new Route();
        for (Point p : points) {
            r.add(new Point(p.lat, p.lon, p.altitude));
        }
        return r;
    }
}
