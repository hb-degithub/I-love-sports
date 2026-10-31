package com.vloc.motion.core;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 反检测的纯逻辑：provider 列表规范化、NMEA 句子生成。
 *
 * <p>抽到 core（无 Android / Xposed 依赖）是为了能被单元测试覆盖 ——
 * 模块里的 hook 只能"替换返回值"，真正容易改坏的是"该返回什么"这些决策。
 */
public final class DetectionAvoidance {

    /** 真机上常见的 provider 组合与顺序。 */
    private static final String[] REAL_PROVIDERS = {"passive", "gps", "network", "fused"};

    private DetectionAvoidance() {
    }

    // ---------------------------------------------------------------- provider 列表

    /**
     * 规范化 provider 列表：去掉自定义 / 测试 provider 名，并按真机顺序排列。
     *
     * <p>为什么重要：{@code getAllProviders()} 里出现非系统名（如 {@code vloc_test}）
     * 是模拟位置最直接的指纹之一。
     *
     * @param actual 应用看到的原始列表（可为 null）
     * @return 规范化后的新列表；null 输入返回空列表
     */
    public static List<String> normalizeProviders(List<String> actual) {
        List<String> out = new ArrayList<>();
        if (actual == null) {
            return out;
        }
        for (String real : REAL_PROVIDERS) {
            if (containsIgnoreCase(actual, real)) {
                out.add(real);
            }
        }
        // 一个标准名都没有：说明列表被异常改动过，给出完整标准组合
        if (out.isEmpty()) {
            for (String real : REAL_PROVIDERS) {
                out.add(real);
            }
        }
        return out;
    }

    /** 列表里是否有自定义（非系统标准）provider 名。 */
    public static boolean hasCustomProvider(List<String> providers) {
        if (providers == null) {
            return false;
        }
        for (String name : providers) {
            if (name == null) {
                continue;
            }
            boolean known = false;
            for (String real : REAL_PROVIDERS) {
                if (real.equalsIgnoreCase(name)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIgnoreCase(List<String> list, String target) {
        for (String item : list) {
            if (item != null && item.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- NMEA

    /** {@code $GPGGA}：时间、经纬度、定位质量、卫星数、HDOP、海拔。 */
    public static String buildGga(double lat, double lon, long timeMs, int satellites,
                                  double altitudeMeters) {
        final int[] hms = utcHms(timeMs);
        final StringBuilder sb = new StringBuilder(96);
        sb.append(String.format(Locale.US, "GPGGA,%02d%02d%02d.00,", hms[0], hms[1], hms[2]));
        sb.append(nmeaCoord(lat, true)).append(',');
        sb.append(nmeaCoord(lon, false)).append(',');
        sb.append("1,").append(Math.max(0, satellites)).append(",0.9,");
        sb.append(String.format(Locale.US, "%.1f,M,0.0,M,,", altitudeMeters));
        return wrap(sb.toString());
    }

    /** {@code $GPRMC}：时间、状态、经纬度、速度、航向、日期。 */
    public static String buildRmc(double lat, double lon, long timeMs, double speedMps,
                                  double bearingDeg) {
        final int[] hms = utcHms(timeMs);
        final Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(timeMs);
        final StringBuilder sb = new StringBuilder(84);
        sb.append(String.format(Locale.US, "GPRMC,%02d%02d%02d.00,A,", hms[0], hms[1], hms[2]));
        sb.append(nmeaCoord(lat, true)).append(',');
        sb.append(nmeaCoord(lon, false)).append(',');
        // 速度单位：节（1 m/s = 1.943844 kn）
        sb.append(String.format(Locale.US, "%.2f,%.1f,", speedMps * 1.943844, bearingDeg));
        sb.append(String.format(Locale.US, "%02d%02d%02d,,,A",
                calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.YEAR) % 100));
        return wrap(sb.toString());
    }

    /**
     * 坐标 → NMEA 的 {@code ddmm.mmmm,N/S} / {@code dddmm.mmmm,E/W} 形式。
     *
     * <p>注意：纬度的度是 2 位、经度是 3 位，且度不足位要补 0。
     */
    public static String nmeaCoord(double value, boolean isLat) {
        final String hemisphere;
        if (isLat) {
            hemisphere = value >= 0 ? "N" : "S";
        } else {
            hemisphere = value >= 0 ? "E" : "W";
        }
        final double abs = Math.abs(value);
        final int degrees = (int) abs;
        final double minutes = (abs - degrees) * 60.0;
        // 纬度度数 2 位、经度 3 位（不足补 0）；分钟固定 4 位小数、整数部分 2 位
        // 例：38.8947 → 38°53.6820′ → "3853.6820,N"（纬度共 9 字符）
        final String degreePart = String.format(Locale.US, isLat ? "%02d" : "%03d", degrees);
        final String minutePart = String.format(Locale.US, "%05.4f", minutes);
        return degreePart + minutePart + "," + hemisphere;
    }

    /** 加 {@code $} 前缀与 XOR 校验和，组成完整 NMEA 句子。 */
    public static String wrap(String body) {
        int checksum = 0;
        for (int i = 0; i < body.length(); i++) {
            checksum ^= body.charAt(i);
        }
        return String.format(Locale.US, "$%s*%02X", body, checksum & 0xFF);
    }

    /** 校验一条 NMEA 句子的 XOR 校验和。 */
    public static boolean verifyChecksum(String sentence) {
        if (sentence == null) {
            return false;
        }
        final int star = sentence.lastIndexOf('*');
        if (star < 0 || sentence.length() < star + 3) {
            return false;
        }
        int checksum = 0;
        for (int i = 1; i < star; i++) {
            checksum ^= sentence.charAt(i);
        }
        try {
            final int declared = Integer.parseInt(sentence.substring(star + 1, star + 3), 16);
            return (checksum & 0xFF) == declared;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int[] utcHms(long timeMs) {
        final Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(timeMs);
        return new int[]{
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                calendar.get(Calendar.SECOND)
        };
    }
}
