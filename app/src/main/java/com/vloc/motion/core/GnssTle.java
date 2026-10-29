package com.vloc.motion.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GNSS 星表：解析 CelesTrak 公布的 TLE（两行根数），并给出每颗卫星的星座与 Svid。
 *
 * <p>数据来自公开的 GP/TLE 快照（随 APK 内置在 {@code assets/gnss_tle.txt}），
 * 覆盖 GPS / GLONASS / Galileo / 北斗。TLE 星历的有效期通常为数周，过期后位置误差会缓慢增大；
 * 项目里带 {@code scripts/update_gnss_tle.py} 可重新抓取。
 *
 * <p>Svid 口径（国产 ROM / Android 常见编号）：
 * <ul>
 *   <li>GPS：PRN（1–32），TLE 名称里直接带 {@code (PRN 22)}；</li>
 *   <li>北斗：200 + PRN（201–263），TLE 名称里带 {@code (C06)}；</li>
 *   <li>GLONASS：64 + 槽位（65–96）——TLE 名称只给 GLONASS 号（如 720），拿不到轨道槽位，
 *       这里按 NORAD 编号做稳定映射（同一颗星永远同一 Svid）；</li>
 *   <li>Galileo：300 + PRN（301–336）——TLE 名称是 GSAT 编号，PRN 映射同样按 NORAD 稳定派生。</li>
 * </ul>
 * 几何（可见性 / 仰角 / 方位角）完全由真实 TLE 决定，Svid 只是编号。
 */
public final class GnssTle {

    public static final int CONST_GPS = 1;
    public static final int CONST_GLONASS = 3;
    public static final int CONST_BEIDOU = 5;
    public static final int CONST_GALILEO = 6;

    /** 一颗卫星的轨道根数（TLE 平均根数，角度单位：度；时间：UTC 毫秒）。 */
    public static final class Sat {
        public final String name;
        public final int noradId;
        public final int constellation;
        public final int svid;
        /** 轨道倾角（度） */
        public final double inclinationDeg;
        /** 升交点赤经（度） */
        public final double raanDeg;
        /** 偏心率 */
        public final double eccentricity;
        /** 近地点幅角（度） */
        public final double argPerigeeDeg;
        /** 平近点角（度） */
        public final double meanAnomalyDeg;
        /** 平运动（圈/天） */
        public final double meanMotionRevPerDay;
        /** 根数历元（UTC 毫秒） */
        public final long epochUtcMs;

        public Sat(String name, int noradId, int constellation, int svid, double inclinationDeg,
                   double raanDeg, double eccentricity, double argPerigeeDeg,
                   double meanAnomalyDeg, double meanMotionRevPerDay, long epochUtcMs) {
            this.name = name;
            this.noradId = noradId;
            this.constellation = constellation;
            this.svid = svid;
            this.inclinationDeg = inclinationDeg;
            this.raanDeg = raanDeg;
            this.eccentricity = eccentricity;
            this.argPerigeeDeg = argPerigeeDeg;
            this.meanAnomalyDeg = meanAnomalyDeg;
            this.meanMotionRevPerDay = meanMotionRevPerDay;
            this.epochUtcMs = epochUtcMs;
        }
    }

    private GnssTle() {
    }

    /** 星表里最新的 TLE 历元（UTC 毫秒）；空表返回 0。 */
    public static long newestEpochMs(List<Sat> sats) {
        long newest = 0L;
        for (Sat sat : sats) {
            newest = Math.max(newest, sat.epochUtcMs);
        }
        return newest;
    }

    /** 解析整份 TLE 文本（允许 # 注释行、空行）。无法识别的条目跳过，不影响其它卫星。 */
    public static List<Sat> parse(String text) {
        List<Sat> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String[] lines = text.split("\n");
        for (int i = 0; i + 2 < lines.length; i++) {
            String name = lines[i].trim();
            String l1 = lines[i + 1].trim();
            String l2 = lines[i + 2].trim();
            if (name.isEmpty() || name.startsWith("#")) {
                continue;
            }
            if (!l1.startsWith("1 ") || !l2.startsWith("2 ")) {
                continue; // 不是完整的 3 行组，继续往下找
            }
            try {
                Sat sat = parseOne(name, l1, l2);
                if (sat != null) {
                    out.add(sat);
                }
            } catch (RuntimeException ignored) {
                // 单条解析失败只跳过该条
            }
            i += 2;
        }
        assignDerivedSvids(out);
        return out;
    }

    /**
     * GLONASS / Galileo 的 PRN 拿不到（TLE 名称里没有 PRN），按 NORAD 编号排序后顺序分配，
     * 保证<b>同一星座内 Svid 不重复</b>、且落在 Android 惯用区间：
     * GLONASS 65–96（64 + 槽位 1–32）、Galileo 301–336（300 + PRN 1–36）。
     *
     * <p>说明：这并不是真实的 GLONASS 槽位 / Galileo PRN（需要额外的映射表），但几何
     * （可见性 / 仰角 / 方位角）完全由真实 TLE 决定，Svid 只是编号。
     */
    private static void assignDerivedSvids(List<Sat> sats) {
        assignByConstellation(sats, CONST_GLONASS, 64, 32);
        assignByConstellation(sats, CONST_GALILEO, 300, 36);
    }

    private static void assignByConstellation(List<Sat> sats, int constellation,
                                              int svidBase, int slots) {
        List<Sat> group = new ArrayList<>();
        for (Sat sat : sats) {
            if (sat.constellation == constellation) {
                group.add(sat);
            }
        }
        if (group.isEmpty()) {
            return;
        }
        group.sort((a, b) -> Integer.compare(a.noradId, b.noradId));
        for (int i = 0; i < group.size(); i++) {
            Sat old = group.get(i);
            int svid = svidBase + (i % slots) + 1;
            int index = sats.indexOf(old);
            if (index >= 0) {
                sats.set(index, new Sat(old.name, old.noradId, old.constellation, svid,
                        old.inclinationDeg, old.raanDeg, old.eccentricity, old.argPerigeeDeg,
                        old.meanAnomalyDeg, old.meanMotionRevPerDay, old.epochUtcMs));
            }
        }
    }

    private static Sat parseOne(String name, String l1, String l2) {
        final int noradId = parseInt(l1.substring(2, 7));
        final double inclination = parseDouble(l2.substring(8, 16));
        final double raan = parseDouble(l2.substring(17, 25));
        final double ecc = Double.parseDouble("0." + l2.substring(26, 33).trim());
        final double argp = parseDouble(l2.substring(34, 42));
        final double ma = parseDouble(l2.substring(43, 51));
        final double mm = parseDouble(l2.substring(52, 63));
        final long epochMs = parseEpochMs(l1.substring(18, 32));

        final String upper = name.toUpperCase(Locale.US);
        final int constellation;
        final int svid;
        if (upper.startsWith("GPS")) {
            int prn = extractParenNumber(upper, "(PRN ");
            if (prn < 1 || prn > 32) {
                prn = stableRange(noradId, 1, 32);
            }
            constellation = CONST_GPS;
            svid = prn;
        } else if (upper.startsWith("BEIDOU") || upper.contains("(C")) {
            int prn = extractBeiDouPrn(upper);
            if (prn < 1 || prn > 63) {
                prn = stableRange(noradId, 1, 63);
            }
            constellation = CONST_BEIDOU;
            svid = 200 + prn;
        } else if (upper.startsWith("GSAT") || upper.contains("GALILEO")) {
            constellation = CONST_GALILEO;
            svid = 300 + stableRange(noradId, 1, 36);
        } else if (upper.startsWith("COSMOS") || upper.startsWith("GLONASS")) {
            constellation = CONST_GLONASS;
            svid = 64 + stableRange(noradId, 1, 24);
        } else {
            return null; // 不认识的卫星（SBAS / QZSS / 实验星）
        }
        return new Sat(name, noradId, constellation, svid, inclination, raan, ecc,
                argp, ma, mm, epochMs);
    }

    /** 从 "…(PRN 22)" 里取 22。 */
    private static int extractParenNumber(String upperName, String marker) {
        int idx = upperName.indexOf(marker);
        if (idx < 0) {
            return -1;
        }
        int start = idx + marker.length();
        int end = start;
        while (end < upperName.length() && Character.isDigit(upperName.charAt(end))) {
            end++;
        }
        if (end == start) {
            return -1;
        }
        return parseInt(upperName.substring(start, end));
    }

    /** 从 "BEIDOU-2 IGSO-1 (C06)" 里取 6。 */
    private static int extractBeiDouPrn(String upperName) {
        int idx = upperName.lastIndexOf("(C");
        if (idx < 0 || idx + 3 > upperName.length()) {
            return -1;
        }
        int start = idx + 2;
        int end = start;
        while (end < upperName.length() && Character.isDigit(upperName.charAt(end))) {
            end++;
        }
        if (end == start) {
            return -1;
        }
        return parseInt(upperName.substring(start, end));
    }

    /** NORAD 编号 → [min,max] 的稳定映射（同一颗星永远得到同一个值）。 */
    private static int stableRange(int noradId, int min, int max) {
        final int span = max - min + 1;
        final int mixed = (int) ((noradId * 2654435761L) >>> 8);
        return min + Math.floorMod(mixed, span);
    }

    private static int parseInt(String text) {
        return Integer.parseInt(text.trim());
    }

    private static double parseDouble(String text) {
        return Double.parseDouble(text.trim());
    }

    /**
     * TLE 历元："YYDDD.DDDDDDDD" → UTC 毫秒。YY 40–99 视为 1940–1999，00–39 视为 2000–2039。
     */
    private static long parseEpochMs(String field) {
        final String t = field.trim();
        final int yy = Integer.parseInt(t.substring(0, 2));
        final double dayOfYear = Double.parseDouble(t.substring(2));
        int year = yy >= 57 ? 1900 + yy : 2000 + yy;   // TLE 行业惯例分界 1957
        long base = utcMs(year, 1, 1) - 86400000L * 1L; // 1 月 1 日 00:00 之前一天
        return base + (long) (dayOfYear * 86400000.0);
    }

    /** 简化 UTC 时间戳（不考虑闰秒）。 */
    static long utcMs(int year, int month, int day) {
        long days = daysFromCivil(year, month, day);
        return days * 86400000L;
    }

    private static long daysFromCivil(int year, int month, int day) {
        int y = year - (month <= 2 ? 1 : 0);
        int era = (y >= 0 ? y : y - 399) / 400;
        int yoe = y - era * 400;
        int doy = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
        int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097L + doe - 719468L;
    }
}
