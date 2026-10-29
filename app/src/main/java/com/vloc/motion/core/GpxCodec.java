package com.vloc.motion.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GPX 轨迹文件读写（含海拔），纯 Java（正则实现，无 XML 依赖）。 */
public final class GpxCodec {

    /** 匹配 <trkpt .../> 或 <trkpt ...>...</trkpt>（也兼容 wpt）。 */
    private static final Pattern TRACKPOINT = Pattern.compile(
            "<(trkpt|wpt)\\b([^>]*?)(?:/>|>(.*?)</\\1>)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LAT_ATTR = Pattern.compile("\\blat\\s*=\\s*[\"']([-+0-9.eE]+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LON_ATTR = Pattern.compile("\\blon\\s*=\\s*[\"']([-+0-9.eE]+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ELE = Pattern.compile("<ele>\\s*([-+0-9.eE]+)\\s*</ele>",
            Pattern.CASE_INSENSITIVE);

    private GpxCodec() {
    }

    /** 导出为 GPX 1.1 轨迹（有海拔时写 <ele>）。 */
    public static String toGpx(Route route, String name, String creator) {
        String safeName = name == null ? "VirtualMotion" : escape(name);
        String safeCreator = creator == null ? "VirtualMotion" : escape(creator);
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gpx version=\"1.1\" creator=\"").append(safeCreator)
                .append("\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        sb.append("  <trk>\n    <name>").append(safeName).append("</name>\n    <trkseg>\n");
        for (Route.Point p : route.points()) {
            if (Double.isNaN(p.altitude)) {
                sb.append(String.format(Locale.US,
                        "      <trkpt lat=\"%.7f\" lon=\"%.7f\"></trkpt>\n", p.lat, p.lon));
            } else {
                sb.append(String.format(Locale.US,
                        "      <trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%.1f</ele></trkpt>\n",
                        p.lat, p.lon, p.altitude));
            }
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n");
        return sb.toString();
    }

    /** 从 GPX 文本解析路径点（含海拔）；解析失败返回空 Route。 */
    public static Route fromGpx(String xml) {
        Route route = new Route();
        if (xml == null || xml.isEmpty()) {
            return route;
        }
        Matcher block = TRACKPOINT.matcher(xml);
        while (block.find()) {
            String attrs = block.group(2) == null ? "" : block.group(2);
            String body = block.group(3) == null ? "" : block.group(3);
            Matcher latM = LAT_ATTR.matcher(attrs);
            Matcher lonM = LON_ATTR.matcher(attrs);
            if (!latM.find() || !lonM.find()) {
                continue;
            }
            try {
                double lat = Double.parseDouble(latM.group(1));
                double lon = Double.parseDouble(lonM.group(1));
                double alt = Double.NaN;
                Matcher eleM = ELE.matcher(body);
                if (eleM.find()) {
                    alt = Double.parseDouble(eleM.group(1));
                }
                route.add(new Route.Point(lat, lon, alt));
            } catch (NumberFormatException ignored) {
                // 跳过坏点
            }
        }
        return route;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
