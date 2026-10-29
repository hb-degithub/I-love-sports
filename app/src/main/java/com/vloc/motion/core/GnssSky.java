package com.vloc.motion.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * GNSS 卫星天空模型：按<b>真实星座星历</b> + <b>目标位置</b> + <b>当前 UTC 时间</b>
 * 计算此刻该位置真实可见的卫星。
 *
 * <p>与旧版（凭空随机抽一套天顶图）的区别：
 * <ul>
 *   <li>卫星来自内置的 CelesTrak TLE 快照（GPS / GLONASS / Galileo / 北斗共百余颗真实卫星）；</li>
 *   <li>每颗卫星的 ECEF 位置由 TLE 根数 + J2 长期摄动推算（{@link GnssOrbit}）；</li>
 *   <li>只上报仰角 &gt; 3° 的卫星，所以北京和广州看到的集合不同、卫星会随时间升落、数量自然变化；</li>
 *   <li>同一位置 + 同一时刻可复现；不需要随机数决定"天上有哪几颗星"。</li>
 * </ul>
 *
 * <p>产出两层数据：
 * <ul>
 *   <li>聚合值（参与定位的卫星数 / 平均 / 最大载噪比）：与 AOSP {@code GnssLocationProvider}
 *       写进 Location extras 的口径一致；</li>
 *   <li>逐颗卫星列表：通过 {@code content://com.vloc.motion.env/steps} 下发给 LSPosed 模块，
 *       用于在目标 App 进程内回放 {@code GnssStatus}。</li>
 * </ul>
 *
 * <p>纯 Java，可单元测试；不依赖 Android 类。
 */
public final class GnssSky {

    public static final int CONST_GPS = GnssTle.CONST_GPS;
    public static final int CONST_GLONASS = GnssTle.CONST_GLONASS;
    public static final int CONST_BEIDOU = GnssTle.CONST_BEIDOU;
    public static final int CONST_GALILEO = GnssTle.CONST_GALILEO;

    public static final int CONST_QZSS = 4;

    /** 低仰角卫星：真实设备能"看见"但不参与定位。 */
    public static final double MIN_ELEVATION_DEG = 3.0;
    /** 参与定位的最低仰角。 */
    public static final double FIX_ELEVATION_DEG = 10.0;

    private static final double GPS_L1_HZ = 1575.42e6;
    private static final double GLONASS_L1_HZ = 1602.0e6;
    private static final double BEIDOU_B1_HZ = 1561.098e6;
    private static final double GALILEO_E1_HZ = 1575.42e6;

    /** 一颗卫星的只读快照。 */
    public static final class Satellite {
        public final int svid;
        public final int constellation;
        public final double elevationDeg;
        public final double azimuthDeg;
        public final double cn0DbHz;
        public final double basebandCn0DbHz;
        public final double carrierHz;
        public final boolean usedInFix;
        public final boolean hasAlmanac;
        public final boolean hasEphemeris;

        Satellite(int svid, int constellation, double elevationDeg, double azimuthDeg,
                  double cn0DbHz, double carrierHz, boolean usedInFix) {
            this.svid = svid;
            this.constellation = constellation;
            this.elevationDeg = elevationDeg;
            this.azimuthDeg = azimuthDeg;
            this.cn0DbHz = cn0DbHz;
            // 基带载噪比比射频端低 0.5–2 dB，真实设备就是这个关系
            this.basebandCn0DbHz = Math.max(0.0, cn0DbHz - (0.6 + Math.abs(svid % 7) * 0.2));
            this.carrierHz = carrierHz;
            this.usedInFix = usedInFix;
            this.hasAlmanac = true;
            this.hasEphemeris = usedInFix || elevationDeg > 15;
        }
    }

    private final Random random = new Random();
    private final List<GnssTle.Sat> catalog = new ArrayList<>();
    private List<Satellite> visible = Collections.emptyList();
    private double lastCn0DbHz;

    /**
     * 设置星表（由 {@link GnssTle#parse(String)} 解析出的真实卫星）。
     *
     * <p>星表为空时本类不产出任何卫星：模块会据此透传真实 GNSS 状态。
     */
    public synchronized void setCatalog(List<GnssTle.Sat> satellites) {
        catalog.clear();
        visible = Collections.emptyList();
        if (satellites != null) {
            catalog.addAll(satellites);
        }
    }

    /** 星表里有多少颗卫星（不代表此刻可见）。 */
    public synchronized int catalogSize() {
        return catalog.size();
    }

    /** 开始一次新的模拟：清掉上一轮结果，下一帧按新位置/时间重算。 */
    public synchronized void start() {
        visible = Collections.emptyList();
    }

    /**
     * 按当前位置与 UTC 时间重算可见卫星。
     *
     * @param dtSeconds 距上一帧的秒数（本实现不需要积分，保留参数以兼容调用方）
     * @param latDeg    观测点纬度（WGS-84）
     * @param lonDeg    观测点经度（WGS-84）
     * @param utcMs     当前 UTC 时间（毫秒）
     */
    public synchronized void update(double dtSeconds, double latDeg, double lonDeg, long utcMs) {
        if (catalog.isEmpty()) {
            visible = Collections.emptyList();
            return;
        }
        final List<Satellite> out = new ArrayList<>(32);
        for (int i = 0; i < catalog.size(); i++) {
            final GnssTle.Sat sat = catalog.get(i);
            final double[] azEl = GnssOrbit.azEl(sat, latDeg, lonDeg, 0.0, utcMs);
            final double elevation = azEl[1];
            if (elevation < MIN_ELEVATION_DEG) {
                continue; // 地平线以下 / 贴地：真实设备也不上报
            }
            final double cn0 = cn0ForElevation(elevation, sat.svid);
            final boolean used = elevation >= FIX_ELEVATION_DEG && cn0 > 21.0;
            out.add(new Satellite(sat.svid, sat.constellation, elevation, azEl[0], cn0,
                    carrierFor(sat.constellation), used));
        }
        // 排序：先按星座，再按 Svid —— 同一时刻输出顺序稳定，方便对比与调试
        out.sort(Comparator
                .comparingInt((Satellite s) -> s.constellation)
                .thenComparingInt(s -> s.svid));
        visible = out;
    }

    /** 当前可见卫星数（仰角 &gt; 3°）。 */
    public synchronized int count() {
        return visible.size();
    }

    /** 参与定位的卫星数。 */
    public synchronized int usedInFixCount() {
        int used = 0;
        for (Satellite sat : visible) {
            if (sat.usedInFix) {
                used++;
            }
        }
        return used;
    }

    /**
     * 参与定位卫星的平均载噪比（dB-Hz）。
     *
     * <p>真实 {@code GnssLocationProvider} 写进 Location extras 的 {@code satellites / meanCn0 /
     * maxCn0} 都是"参与定位的卫星"口径，所以这里同样只统计 usedInFix 的卫星；
     * 一颗都没参与定位时退回全体卫星，避免出现 0。
     */
    public synchronized double meanCn0() {
        if (visible.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        int used = 0;
        for (Satellite sat : visible) {
            if (sat.usedInFix) {
                sum += sat.cn0DbHz;
                used++;
            }
        }
        if (used > 0) {
            return sum / used;
        }
        for (Satellite sat : visible) {
            sum += sat.cn0DbHz;
        }
        return sum / visible.size();
    }

    /** 参与定位卫星的最大载噪比（dB-Hz），口径同上。 */
    public synchronized double maxCn0() {
        double max = 0.0;
        boolean anyUsed = false;
        for (Satellite sat : visible) {
            if (sat.usedInFix) {
                anyUsed = true;
                max = Math.max(max, sat.cn0DbHz);
            }
        }
        if (anyUsed) {
            return max;
        }
        for (Satellite sat : visible) {
            max = Math.max(max, sat.cn0DbHz);
        }
        return max;
    }

    /** 逐颗卫星快照。 */
    public synchronized List<Satellite> satellites() {
        return new ArrayList<>(visible);
    }

    /**
     * 逐颗卫星 JSON 数组，供 ContentProvider 下发给 LSPosed 模块。
     *
     * <p>格式：{@code [{"svid":5,"const":1,"ele":42.3,"azi":128.7,"cn0":34.2,
     * "fix":true,"alm":true,"eph":true,"cf":1575420000,"base":33.1}, ...]}
     */
    public synchronized String toJsonArray() {
        final List<Satellite> list = visible;
        final StringBuilder sb = new StringBuilder(list.size() * 96 + 2);
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            final Satellite sat = list.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.US,
                    "{\"svid\":%d,\"const\":%d,\"ele\":%.1f,\"azi\":%.1f,\"cn0\":%.1f,"
                            + "\"fix\":%s,\"alm\":%s,\"eph\":%s,\"cf\":%.0f,\"base\":%.1f}",
                    sat.svid, sat.constellation, sat.elevationDeg, sat.azimuthDeg,
                    sat.cn0DbHz, sat.usedInFix, sat.hasAlmanac, sat.hasEphemeris,
                    sat.carrierHz, sat.basebandCn0DbHz));
        }
        sb.append(']');
        return sb.toString();
    }

    /** 上次调用 update 时可见卫星的最大载噪比（调试用）。 */
    public synchronized double lastCn0() {
        return lastCn0DbHz;
    }

    private double cn0ForElevation(double elevationDeg, int svid) {
        // 仰角 10° ≈ 27 dB-Hz，仰角 80° ≈ 41 dB-Hz；叠加一点抖动模拟多路径
        final double base = 25.0 + elevationDeg * 0.20;
        final double noise = random.nextGaussian() * 0.8;
        final double value = clamp(base + noise, 20.0, 45.0);
        lastCn0DbHz = Math.max(lastCn0DbHz, value);
        return value;
    }

    private static double carrierFor(int constellation) {
        switch (constellation) {
            case CONST_GPS:
                return GPS_L1_HZ;
            case CONST_GLONASS:
                return GLONASS_L1_HZ;
            case CONST_BEIDOU:
                return BEIDOU_B1_HZ;
            case CONST_GALILEO:
                return GALILEO_E1_HZ;
            default:
                return GPS_L1_HZ;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
