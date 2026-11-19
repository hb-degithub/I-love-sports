package com.vloc.motion.hidden;

import android.location.GnssStatus;

import com.vloc.motion.core.VLog;
import com.vloc.motion.hidden.compat.Xp;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 系统级（system_server）{@code GnssStatus} 工厂：<b>Builder → 反射构造器 → Unsafe</b> 三级兜底。
 *
 * <p><b>为什么需要它</b>：系统级 GNSS 注入要在 system_server 里造一个 {@code GnssStatus}，
 * 替换 {@code GnssStatusProvider#onReportSvStatus(GnssStatus)} 的实参。旧实现只反射
 * {@code GnssStatus$Builder}，且传参与真实签名不符（只有 10 个实参、svid 与星座位置互换、
 * float 占住 boolean 形参），任何 Android 11+ 都会抛错 → 返回 null → 卫星表从不替换，
 * 室内表现为“坐标在动但 0 颗卫星”。Android 17 上 Builder 是否还在也无法保证，必须有多级兜底。
 *
 * <p><b>三级链路</b>（按顺序尝试，第一个“造出来并通过校验”的锁定到本进程，不重复探测）：
 * <ol>
 *   <li><b>Builder</b>（API 30+ 公开 API）：12 参数（API 31+，带基带载噪比）优先，
 *       退 10 参数；先造两颗探测卫星并读回，签名/参数顺序不对立刻放弃；</li>
 *   <li><b>反射构造器</b>：扫 {@code GnssStatus.getDeclaredConstructors()}，挑
 *       “1 个 {@code int[]} + N 个 {@code float[]}”形状（AOSP 形态
 *       {@code (int svCount, int[] svidWithFlags, float[] cn0DbHzs, float[] elevations,
 *       float[] azimuths, float[] carrierFrequencies[, float[] basebandCn0DbHzs])}，
 *       兼容 {@code int ttff} 变体与末尾带合成 marker 参数的副本）；
 *       <b>float[] 的语义顺序不靠猜</b>：拿互不相同的探测值构造一次，再按 getter 读回
 *       反推“第几个 float[] 是 cn0/仰角/方位角/载波/基带”，之后按真实顺序填；</li>
 *   <li><b>Unsafe</b>：{@code sun.misc.Unsafe.theUnsafe} + {@code allocateInstance} +
 *       字段偏移写入。现行字段名 {@code mSvCount/mSvidWithFlags/mCn0DbHzs/mElevations/
 *       mAzimuths/mCarrierFrequencies/mBasebandCn0DbHzs} 优先，兼容老字段名
 *       {@code mCn0DbHz/mSvElevationDegrees/mSvAzimuthDegrees/mSvCarrierFrequencyHz/
 *       mSvCarrierFreqHz/mBasebandCn0DbHz/mTtffMillis}；{@code Field.set} 失败
 *       （final / hidden API 拦截）时用 Unsafe 偏移写。</li>
 * </ol>
 *
 * <p><b>svidWithFlags 编码</b>：{@code mSvidWithFlags} 把“卫星号 + 星座 + 五个 bit 标志”
 * 压进一个 int，各版本 shift/mask 不公开。这里不写死：先尝试从 {@code GnssStatus} 自身的
 * {@code SVID_SHIFT_WIDTH / CONSTELLATION_TYPE_SHIFT_WIDTH / SVID_FLAGS_*} 常量读布局，
 * 再逐条尝试候选编码；<b>只有 getter 读回完全一致的那条才会被采用</b>，并在本进程内锁定。
 * 一条都不匹配时退回第一候选，方案名标 {@code [enc?]} 并单独记一条日志——绝不把“猜错了”当成功。
 *
 * <p><b>校验</b>：
 * <ul>
 *   <li>探测阶段：{@code getSatelliteCount()}、{@code getSvid}、{@code getConstellationType}、
 *       {@code getCn0DbHz}、{@code getElevationDegrees}、{@code getAzimuthDegrees}、
 *       {@code usedInFix}、{@code hasAlmanacData}、{@code hasEphemerisData} 全部与输入一致；</li>
 *   <li>每次产出：{@code getSatelliteCount()==N} 且前 3 颗的 cn0/仰角/方位角与输入一致；
 *       不一致就返回 null（宁可透传真实卫星，也不投递一颗“读出来是错的”对象）。</li>
 * </ul>
 *
 * <p><b>日志</b>（每进程各一条；tag {@code VLocHidden}，同时转发 Xposed 日志）：
 * <pre>
 *   gnss build: ctor(I[I[F[F[F[F[F) n=27 meanCn0=36.4
 *   gnss build failed: ctors=[(I[I[F[F[F[F[F)] fields=[svid=mSvidWithFlags,…] err=… (tried: …)
 * </pre>
 *
 * <p><b>安全契约</b>：所有反射 / Unsafe 调用都包在 {@code try/catch(Throwable)} 内，
 * 任何失败只返回 {@code null}（调用方据此“不替换、透传真实卫星”），绝不让异常冒泡到
 * system_server；不构造 {@code Parcel}、不申请权限、不新增依赖。
 */
public final class GnssStatusFactory {

    /** 日志 tag：与 hidden 包其它日志保持一致。 */
    public static final String TAG = "VLocHidden";

    /** 卫星上限：与 {@code GnssReplay.MAX_SATELLITES} 一致，异常快照不许把数组刷爆。 */
    private static final int MAX_SATELLITES = 96;

    // ---------------- 策略编号 ----------------
    private static final int STRAT_NONE = 0;
    private static final int STRAT_BUILDER = 1;
    private static final int STRAT_CTOR = 2;
    private static final int STRAT_UNSAFE = 3;

    // ---------------- float[] 的逻辑槽位 ----------------
    private static final int SLOT_NONE = -1;
    private static final int SLOT_CN0 = 0;
    private static final int SLOT_ELEVATION = 1;
    private static final int SLOT_AZIMUTH = 2;
    private static final int SLOT_CARRIER = 3;
    private static final int SLOT_BASEBAND = 4;
    private static final int SLOT_COUNT = 5;

    /** Builder.addSatellite：API 31+ 的 12 参数形态（多出基带载噪比）。 */
    private static final Class<?>[] BUILDER_ARGS_12 = {
            int.class, int.class, float.class, float.class, float.class,
            boolean.class, boolean.class, boolean.class,
            boolean.class, float.class, boolean.class, float.class};

    /** Builder.addSatellite：API 30 的 10 参数形态。 */
    private static final Class<?>[] BUILDER_ARGS_10 = {
            int.class, int.class, float.class, float.class, float.class,
            boolean.class, boolean.class, boolean.class, boolean.class, float.class};

    private static final String BUILDER_CLASS = "android.location.GnssStatus$Builder";

    private static final Object LOCK = new Object();

    /** 锁定下来的构造方案；null = 还没探测过。{@code strategy == STRAT_NONE} = 已探测且全失败。 */
    private static volatile Plan sPlan;

    private static final AtomicBoolean sLoggedSuccess = new AtomicBoolean();
    private static final AtomicBoolean sLoggedFailure = new AtomicBoolean();

    /** svidWithFlags 候选编码（第一次需要时构建，内部会读类自身的常量）。 */
    private static volatile List<Packing> sPackingCandidates;

    private GnssStatusFactory() {
    }

    // ================================================================ 输入模型

    /**
     * 一颗卫星的输入（与 {@code SystemHooks.Satellite} / 主 App {@code gnss} 数组字段一一对应）。
     *
     * <p>刻意不引用 {@code SystemHooks.Satellite}：本工厂要能单独编译、单独跑 JVM harness
     * （不加载 system_server 那套 hook 代码）。
     */
    public static final class Sat {

        public final int svid;
        public final int constellation;
        public final float cn0;
        public final float elevation;
        public final float azimuth;
        public final float carrierHz;
        public final boolean usedInFix;
        public final boolean hasAlmanac;
        public final boolean hasEphemeris;

        public Sat(int svid, int constellation, float cn0, float elevation, float azimuth,
                   float carrierHz, boolean usedInFix, boolean hasAlmanac, boolean hasEphemeris) {
            this.svid = svid;
            this.constellation = constellation;
            this.cn0 = cn0;
            this.elevation = elevation;
            this.azimuth = azimuth;
            this.carrierHz = carrierHz;
            this.usedInFix = usedInFix;
            this.hasAlmanac = hasAlmanac;
            this.hasEphemeris = hasEphemeris;
        }
    }

    // ================================================================ 对外入口

    /**
     * 按快照造一个 {@code GnssStatus}。
     *
     * @param satellites 卫星列表（顺序即 {@code getSvid(0..n-1)} 的顺序）
     * @return 校验通过的实例；{@code null} = 本进程没有任何可用构造路径 / 校验失败
     *         （调用方据此保持真实卫星，不替换）
     */
    public static GnssStatus create(List<Sat> satellites) {
        try {
            if (satellites == null || satellites.isEmpty()) {
                return null;
            }
            final List<Sat> input = satellites.size() > MAX_SATELLITES
                    ? satellites.subList(0, MAX_SATELLITES) : satellites;
            final Plan plan = resolvePlan();
            if (plan.strategy == STRAT_NONE) {
                logFailureOnce(plan.diag);
                return null;
            }
            final GnssStatus status;
            try {
                status = plan.build(input);
            } catch (Throwable t) {
                plan.diag.lastError = "build:" + describe(t);
                logFailureOnce(plan.diag);
                return null;
            }
            if (!verifySnapshot(status, input)) {
                plan.diag.lastError = "build verify failed (count/cn0/elev/azi)";
                logFailureOnce(plan.diag);
                return null;
            }
            logSuccessOnce(plan, input);
            return status;
        } catch (Throwable t) {
            // 绝不把异常抛进 system_server
            return null;
        }
    }

    // ================================================================ 探测（每进程一次）

    private static Plan resolvePlan() {
        final Plan cached = sPlan;
        if (cached != null) {
            return cached;
        }
        synchronized (LOCK) {
            if (sPlan != null) {
                return sPlan;
            }
            final Diag diag = new Diag();
            Plan plan = null;
            // 1) Builder（API 30+；A17 上可能不存在）
            try {
                plan = probeBuilder(diag);
            } catch (Throwable t) {
                diag.error("builder:" + describe(t));
            }
            // 2) 反射构造器
            if (plan == null) {
                try {
                    plan = probeCtor(diag);
                } catch (Throwable t) {
                    diag.error("ctor:" + describe(t));
                }
            }
            // 3) Unsafe
            if (plan == null) {
                try {
                    plan = probeUnsafe(diag);
                } catch (Throwable t) {
                    diag.error("unsafe:" + describe(t));
                }
            }
            if (plan == null) {
                plan = Plan.none(diag);
            }
            sPlan = plan;
            return plan;
        }
    }

    // ---------------------------------------------------------------- 1) Builder

    private static Plan probeBuilder(Diag diag) {
        final Class<?> builderClass;
        try {
            builderClass = Class.forName(BUILDER_CLASS, false, safeLoader());
        } catch (Throwable t) {
            diag.error("builder:absent(" + describe(t) + ")");
            return null;
        }
        Method addSatellite = findMethod(builderClass, "addSatellite", BUILDER_ARGS_12);
        boolean withBaseband = addSatellite != null;
        if (addSatellite == null) {
            addSatellite = findMethod(builderClass, "addSatellite", BUILDER_ARGS_10);
        }
        if (addSatellite == null) {
            diag.error("builder:no addSatellite(int,int,float...)");
            return null;
        }
        final Method buildMethod = findMethod(builderClass, "build", new Class<?>[0]);
        if (buildMethod == null) {
            diag.error("builder:no build()");
            return null;
        }
        final Constructor<?> builderCtor = findConstructor(builderClass);
        if (builderCtor == null) {
            diag.error("builder:no usable Builder()");
            return null;
        }
        final Plan plan = Plan.builder(builderCtor, addSatellite, buildMethod, withBaseband, diag);
        // 参数顺序 / 个数不对的话，这里立刻失败
        if (!verifyProbe(plan, probeSats())) {
            diag.error("builder:probe verify mismatch");
            return null;
        }
        diag.step("builder=ok(" + (withBaseband ? 12 : 10) + "args)");
        return plan;
    }

    // ---------------------------------------------------------------- 2) 反射构造器

    private static Plan probeCtor(Diag diag) {
        final Constructor<?>[] declared;
        try {
            declared = GnssStatus.class.getDeclaredConstructors();
        } catch (Throwable t) {
            diag.error("ctor:getDeclaredConstructors(" + describe(t) + ")");
            return null;
        }
        diag.ctors = signatures(declared);
        final List<Constructor<?>> candidates = new ArrayList<>();
        for (int i = 0; i < declared.length; i++) {
            if (CtorShape.isCandidate(declared[i].getParameterTypes())) {
                candidates.add(declared[i]);
            }
        }
        if (candidates.isEmpty()) {
            diag.error("ctor:none matches (int[] + float[]xN)");
            return null;
        }
        // 参数最少优先：先试“干净”的 7 参数构造器，再试带合成 marker 的副本
        Collections.sort(candidates, BY_PARAM_COUNT);
        for (int i = 0; i < candidates.size(); i++) {
            final Plan plan = probeCtorCandidate(candidates.get(i), diag);
            if (plan != null) {
                return plan;
            }
        }
        return null;
    }

    private static Plan probeCtorCandidate(Constructor<?> ctor, Diag diag) {
        final String sig = signature(ctor);
        final CtorShape shape = CtorShape.of(ctor.getParameterTypes());
        if (shape == null) {
            return null;
        }
        try {
            ctor.setAccessible(true);
        } catch (Throwable t) {
            diag.error("ctor" + sig + ":setAccessible(" + describe(t) + ")");
            return null;
        }
        // (a) 标量 int 形参（svCount / ttff）：逐个位置试 n=1，用卫星数读回校验
        final int[] scalarTemplate = pickScalarTemplate(ctor, shape, sig, diag);
        if (scalarTemplate == null) {
            return null;
        }
        final boolean withBaseband = shape.floatCount >= 5;
        // (b) svidWithFlags 编码：逐条候选构造 + getter 读回
        Packing packing = null;
        final List<Packing> packingCandidates = packingCandidates();
        for (int i = 0; i < packingCandidates.size(); i++) {
            if (packingProbe(ctor, shape, scalarTemplate, packingCandidates.get(i), withBaseband)) {
                packing = packingCandidates.get(i);
                break;
            }
        }
        final boolean packingVerified = packing != null;
        if (packing == null) {
            packing = packingCandidates.get(0);
        }
        // (c) float[] 位置 → 逻辑槽（互不相同的探测值反推，不猜顺序）
        final int[] slotOfPos = orderProbe(ctor, shape, scalarTemplate, packing, withBaseband);
        if (slotOfPos == null) {
            diag.error("ctor" + sig + ":float[] order probe failed");
            return null;
        }
        // (d) 终检：两颗代表卫星必须原样读回
        final Plan plan = Plan.ctor(ctor, shape, scalarTemplate, slotOfPos, packing,
                packingVerified, diag);
        if (!verifyProbe(plan, probeSats())) {
            diag.error("ctor" + sig + ":verify mismatch");
            return null;
        }
        if (!packingVerified) {
            diag.step("ctor" + sig + ":svid/flags encoding unverified");
        }
        diag.step("ctor" + sig + "=ok");
        return plan;
    }

    /** 标量 int 形参的取值模板：-1 = 用卫星数 n，0 = 常量 0（ttff 之类）。 */
    private static int[] pickScalarTemplate(Constructor<?> ctor, CtorShape shape, String sig,
                                            Diag diag) {
        final int count = shape.intParamCount;
        if (count == 0) {
            return new int[0];
        }
        // n 落在第 i 个 int 形参（其余 0）：覆盖 (int svCount,…) 与 (int ttff, int svCount,…)
        for (int i = 0; i < count; i++) {
            final int[] template = new int[count];
            Arrays.fill(template, 0);
            template[i] = -1;
            if (countProbe(ctor, shape, template)) {
                return template;
            }
        }
        // 卫星数可能来自数组长度：全 0 再试一次
        final int[] zero = new int[count];
        if (countProbe(ctor, shape, zero)) {
            return zero;
        }
        diag.error("ctor" + sig + ":satelliteCount probe failed");
        return null;
    }

    /** 用 1 颗卫星构造一次，确认 {@code getSatelliteCount()==1}。 */
    private static boolean countProbe(Constructor<?> ctor, CtorShape shape, int[] template) {
        try {
            final float[][] floats = new float[shape.floatCount][1];
            final int[] packed = new int[1];
            return newCtorInstance(ctor, shape, template, packed, floats, 1)
                    .getSatelliteCount() == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 用候选编码构造 1 颗“标志部分开启”的探测卫星，读回 svid/星座/标志是否一致。 */
    private static boolean packingProbe(Constructor<?> ctor, CtorShape shape, int[] template,
                                        Packing packing, boolean withBaseband) {
        try {
            final float[][] floats = new float[shape.floatCount][1];
            for (int pos = 0; pos < shape.floatCount; pos++) {
                floats[pos][0] = 1.0f + pos;
            }
            final int[] packed = new int[]{packing.pack(PROBE_SAT, true, withBaseband)};
            final GnssStatus status = newCtorInstance(ctor, shape, template, packed, floats, 1);
            return decodeMatches(status, PROBE_SAT, withBaseband);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 逐个 {@code float[]} 形参放一个互不相同的探测值，再按 getter 读回反查
     * “第几个位置装的是 cn0 / 仰角 / 方位角 / 载波 / 基带”。
     *
     * @return 位置 → 逻辑槽（缺的槽为 {@link #SLOT_NONE}）；cn0/仰角/方位角缺一即返回 null
     */
    private static int[] orderProbe(Constructor<?> ctor, CtorShape shape, int[] template,
                                    Packing packing, boolean withBaseband) {
        final float[] probeValues = {11.5f, 22.5f, 33.5f, 44.5f, 55.5f, 66.5f};
        try {
            final float[][] floats = new float[shape.floatCount][1];
            for (int pos = 0; pos < shape.floatCount; pos++) {
                floats[pos][0] = pos < probeValues.length ? probeValues[pos] : 100f + pos;
            }
            final int[] packed = new int[]{packing.pack(PROBE_SAT, true, withBaseband)};
            final GnssStatus status = newCtorInstance(ctor, shape, template, packed, floats, 1);
            if (status.getSatelliteCount() != 1) {
                return null;
            }
            final float[] observed = new float[SLOT_COUNT];
            Arrays.fill(observed, Float.NaN);
            // 每个 getter 单独兜底：某个 getter 抛异常只丢这一个槽，不毁掉整个探测
            try {
                observed[SLOT_CN0] = status.getCn0DbHz(0);
            } catch (Throwable ignored) {
                // 丢 cn0 槽 → 下面的必要槽校验会拒绝这个构造器
            }
            try {
                observed[SLOT_ELEVATION] = status.getElevationDegrees(0);
            } catch (Throwable ignored) {
                // 同上
            }
            try {
                observed[SLOT_AZIMUTH] = status.getAzimuthDegrees(0);
            } catch (Throwable ignored) {
                // 同上
            }
            try {
                observed[SLOT_CARRIER] = status.getCarrierFrequencyHz(0);
            } catch (Throwable ignored) {
                // 载波槽缺失可以接受：填 0，并把 hasCarrierFrequencyHz 的 bit 关掉
            }
            final Float baseband = callFloat(status, "getBasebandCn0DbHz", 0);
            if (baseband != null) {
                observed[SLOT_BASEBAND] = baseband.floatValue();
            }
            final int[] slotOfPos = new int[shape.floatCount];
            Arrays.fill(slotOfPos, SLOT_NONE);
            for (int pos = 0; pos < shape.floatCount; pos++) {
                for (int slot = 0; slot < SLOT_COUNT; slot++) {
                    if (Float.isNaN(observed[slot]) || observed[slot] != floats[pos][0]) {
                        continue;
                    }
                    boolean taken = false;
                    for (int prev = 0; prev < pos; prev++) {
                        if (slotOfPos[prev] == slot) {
                            taken = true;
                        }
                    }
                    if (!taken) {
                        slotOfPos[pos] = slot;
                        break;
                    }
                }
            }
            if (hasSlot(slotOfPos, SLOT_CN0) && hasSlot(slotOfPos, SLOT_ELEVATION)
                    && hasSlot(slotOfPos, SLOT_AZIMUTH)) {
                return slotOfPos;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 3) Unsafe

    private static Plan probeUnsafe(Diag diag) {
        // 先把字段名解析出来，这样即使 Unsafe 打不开，失败日志里也能看到字段现状
        final Fields names = Fields.resolve();
        diag.fields = names.describe();
        if (!names.isNamed()) {
            diag.error("unsafe:required field missing");
            return null;
        }
        final UnsafeAccess access;
        try {
            access = UnsafeAccess.open();
        } catch (Throwable t) {
            diag.error("unsafe:open(" + describe(t) + ")");
            return null;
        }
        final Fields fields = names.withOffsets(access);
        if (!fields.isWritable()) {
            diag.error("unsafe:no objectFieldOffset");
            return null;
        }
        // 先用第一候选编码试：字段名对但编码不对时，verif 会失败 → 再逐条换编码
        final List<Packing> candidates = packingCandidates();
        Packing matched = null;
        for (int i = 0; i < candidates.size(); i++) {
            final Plan candidatePlan = Plan.unsafe(access, fields, candidates.get(i), diag);
            if (verifyProbe(candidatePlan, probeSats())) {
                matched = candidates.get(i);
                diag.step("unsafe=ok(encoding#" + (i + 1) + ")");
                return Plan.unsafe(access, fields, matched, diag);
            }
        }
        diag.error("unsafe:field/flag probe verify mismatch");
        return null;
    }

    // ================================================================ 探测用样本

    /** svid/星座/标志都有区分度的样本（编码探测只看这几个字段）。 */
    private static final Sat PROBE_SAT =
            new Sat(17, 1, 11.5f, 22.5f, 33.5f, 1575.42e6f, true, true, false);

    /** 两颗坐标/载噪比不同的样本：任何“顺序 / 长度 / 编码”错误都会在读回校验里暴露。 */
    private static List<Sat> probeSats() {
        final List<Sat> list = new ArrayList<>(2);
        list.add(new Sat(17, 1, 11.5f, 22.5f, 33.5f, 1575.42e6f, true, true, false));
        list.add(new Sat(3, 1, 41.25f, 5.5f, 290.25f, 0f, false, false, true));
        return list;
    }

    // ================================================================ 校验

    /** 探测期校验：数量 + svid/星座/标志 + cn0/仰角/方位角 全部一致。 */
    private static boolean verifyProbe(Plan plan, List<Sat> sats) {
        try {
            final GnssStatus status = plan.build(sats);
            if (status == null || status.getSatelliteCount() != sats.size()) {
                return false;
            }
            for (int i = 0; i < sats.size(); i++) {
                final Sat expected = sats.get(i);
                if (status.getSvid(i) != expected.svid
                        || status.getConstellationType(i) != expected.constellation
                        || status.usedInFix(i) != expected.usedInFix
                        || status.hasAlmanacData(i) != expected.hasAlmanac
                        || status.hasEphemerisData(i) != expected.hasEphemeris) {
                    return false;
                }
                if (Math.abs(status.getCn0DbHz(i) - expected.cn0) > 0.05f
                        || Math.abs(status.getElevationDegrees(i) - expected.elevation) > 0.05f
                        || Math.abs(status.getAzimuthDegrees(i) - expected.azimuth) > 0.05f) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** svid/星座/标志是否读回一致（编码探测用；前提是数量已对）。 */
    private static boolean decodeMatches(GnssStatus status, Sat expected, boolean withBaseband) {
        try {
            if (status == null || status.getSatelliteCount() != 1) {
                return false;
            }
            if (status.getSvid(0) != expected.svid
                    || status.getConstellationType(0) != expected.constellation
                    || status.usedInFix(0) != expected.usedInFix
                    || status.hasAlmanacData(0) != expected.hasAlmanac
                    || status.hasEphemerisData(0) != expected.hasEphemeris
                    || status.hasCarrierFrequencyHz(0) != (expected.carrierHz > 0f)) {
                return false;
            }
            final Boolean hasBaseband = callBoolean(status, "hasBasebandCn0DbHz", 0);
            return hasBaseband == null || hasBaseband.booleanValue() == withBaseband;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 每次产出都要过的最小校验：数量一致 + 前 3 颗 cn0/仰角/方位角与输入一致。 */
    private static boolean verifySnapshot(GnssStatus status, List<Sat> sats) {
        try {
            if (status == null || status.getSatelliteCount() != sats.size()) {
                return false;
            }
            final int probes = Math.min(3, sats.size());
            for (int i = 0; i < probes; i++) {
                final Sat expected = sats.get(i);
                if (Math.abs(status.getCn0DbHz(i) - expected.cn0) > 0.5f
                        || Math.abs(status.getElevationDegrees(i) - expected.elevation) > 1f
                        || Math.abs(status.getAzimuthDegrees(i) - expected.azimuth) > 1f) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================ 构造方案

    /** 已锁定的构造方案（不可变；探测一次后全进程复用）。 */
    private static final class Plan {

        final int strategy;
        /** 日志里的方案名：{@code builder} / {@code ctor(签名)} / {@code unsafe} / {@code none}。 */
        final String kind;
        final Packing packing;
        final Diag diag;

        // Builder
        final Constructor<?> builderCtor;
        final Method addSatellite;
        final Method buildMethod;
        final boolean builderWithBaseband;

        // 反射构造器
        final Constructor<?> ctor;
        final CtorShape shape;
        final int[] scalarTemplate;
        final int[] slotOfPos;

        // Unsafe
        final UnsafeAccess access;
        final Fields fields;

        private Plan(int strategy, String kind, Packing packing, Diag diag,
                     Constructor<?> builderCtor, Method addSatellite, Method buildMethod,
                     boolean builderWithBaseband, Constructor<?> ctor, CtorShape shape,
                     int[] scalarTemplate, int[] slotOfPos, UnsafeAccess access, Fields fields) {
            this.strategy = strategy;
            this.kind = kind;
            this.packing = packing;
            this.diag = diag;
            this.builderCtor = builderCtor;
            this.addSatellite = addSatellite;
            this.buildMethod = buildMethod;
            this.builderWithBaseband = builderWithBaseband;
            this.ctor = ctor;
            this.shape = shape;
            this.scalarTemplate = scalarTemplate;
            this.slotOfPos = slotOfPos;
            this.access = access;
            this.fields = fields;
        }

        static Plan none(Diag diag) {
            return new Plan(STRAT_NONE, "none", null, diag, null, null, null, false,
                    null, null, null, null, null, null);
        }

        static Plan builder(Constructor<?> builderCtor, Method addSatellite, Method buildMethod,
                            boolean withBaseband, Diag diag) {
            return new Plan(STRAT_BUILDER, "builder", null, diag, builderCtor, addSatellite,
                    buildMethod, withBaseband, null, null, null, null, null, null);
        }

        static Plan ctor(Constructor<?> ctor, CtorShape shape, int[] scalarTemplate,
                         int[] slotOfPos, Packing packing, boolean verified, Diag diag) {
            final String kind = "ctor" + signature(ctor) + (verified ? "" : "[enc?]");
            return new Plan(STRAT_CTOR, kind, packing, diag, null, null, null, false,
                    ctor, shape, scalarTemplate, slotOfPos, null, null);
        }

        static Plan unsafe(UnsafeAccess access, Fields fields, Packing packing, Diag diag) {
            return new Plan(STRAT_UNSAFE, "unsafe", packing, diag, null, null, null, false,
                    null, null, null, null, access, fields);
        }

        GnssStatus build(List<Sat> sats) throws Throwable {
            switch (strategy) {
                case STRAT_BUILDER:
                    return buildViaBuilder(sats);
                case STRAT_CTOR:
                    return buildViaCtor(sats);
                case STRAT_UNSAFE:
                    return buildViaUnsafe(sats);
                default:
                    return null;
            }
        }

        // -------------------------------------------------------- Builder

        private GnssStatus buildViaBuilder(List<Sat> sats) throws Throwable {
            final Object builder = builderCtor.newInstance();
            for (int i = 0; i < sats.size(); i++) {
                final Sat sat = sats.get(i);
                final boolean hasCarrier = sat.carrierHz > 0f;
                // 参数顺序按 AOSP 公开签名：星座、svid、cn0、仰角、方位角、星历、历书、
                // 参与定位、有无载波、载波频率[, 有无基带载噪比、基带载噪比]
                if (builderWithBaseband) {
                    addSatellite.invoke(builder,
                            Integer.valueOf(sat.constellation), Integer.valueOf(sat.svid),
                            Float.valueOf(sat.cn0), Float.valueOf(sat.elevation),
                            Float.valueOf(sat.azimuth),
                            Boolean.valueOf(sat.hasEphemeris), Boolean.valueOf(sat.hasAlmanac),
                            Boolean.valueOf(sat.usedInFix), Boolean.valueOf(hasCarrier),
                            Float.valueOf(sat.carrierHz), Boolean.TRUE, Float.valueOf(sat.cn0));
                } else {
                    addSatellite.invoke(builder,
                            Integer.valueOf(sat.constellation), Integer.valueOf(sat.svid),
                            Float.valueOf(sat.cn0), Float.valueOf(sat.elevation),
                            Float.valueOf(sat.azimuth),
                            Boolean.valueOf(sat.hasEphemeris), Boolean.valueOf(sat.hasAlmanac),
                            Boolean.valueOf(sat.usedInFix), Boolean.valueOf(hasCarrier),
                            Float.valueOf(sat.carrierHz));
                }
            }
            return (GnssStatus) buildMethod.invoke(builder);
        }

        // -------------------------------------------------------- 反射构造器

        private GnssStatus buildViaCtor(List<Sat> sats) throws Throwable {
            final int n = sats.size();
            final boolean withCarrier = hasSlot(slotOfPos, SLOT_CARRIER);
            final boolean withBaseband = hasSlot(slotOfPos, SLOT_BASEBAND);
            final int[] packed = new int[n];
            for (int i = 0; i < n; i++) {
                packed[i] = packing.pack(sats.get(i), withCarrier, withBaseband);
            }
            final float[][] floats = new float[shape.floatCount][n];
            for (int pos = 0; pos < shape.floatCount; pos++) {
                final int slot = slotOfPos[pos];
                for (int i = 0; i < n; i++) {
                    floats[pos][i] = floatValue(slot, sats.get(i));
                }
            }
            return newCtorInstance(ctor, shape, scalarTemplate, packed, floats, n);
        }

        // -------------------------------------------------------- Unsafe

        private GnssStatus buildViaUnsafe(List<Sat> sats) throws Throwable {
            final int n = sats.size();
            final boolean withCarrier = fields.carrier != null;
            final boolean withBaseband = fields.baseband != null;
            final int[] packed = new int[n];
            final float[] cn0 = new float[n];
            final float[] elevation = new float[n];
            final float[] azimuth = new float[n];
            final float[] carrier = new float[n];
            final float[] baseband = new float[n];
            for (int i = 0; i < n; i++) {
                final Sat sat = sats.get(i);
                packed[i] = packing.pack(sat, withCarrier, withBaseband);
                cn0[i] = sat.cn0;
                elevation[i] = sat.elevation;
                azimuth[i] = sat.azimuth;
                carrier[i] = sat.carrierHz;
                // 快照里没有基带载噪比：用 cn0 近似（与 GnssReplay 一致，保证自洽）
                baseband[i] = sat.cn0;
            }
            final Object instance = access.allocateInstance();
            access.write(instance, fields.svid, fields.svidOffset, packed);
            access.write(instance, fields.cn0, fields.cn0Offset, cn0);
            access.write(instance, fields.elevation, fields.elevationOffset, elevation);
            access.write(instance, fields.azimuth, fields.azimuthOffset, azimuth);
            if (fields.carrier != null) {
                access.write(instance, fields.carrier, fields.carrierOffset, carrier);
            }
            if (fields.baseband != null) {
                access.write(instance, fields.baseband, fields.basebandOffset, baseband);
            }
            if (fields.count != null) {
                access.writeInt(instance, fields.count, fields.countOffset, n);
            }
            if (fields.ttff != null) {
                access.writeInt(instance, fields.ttff, fields.ttffOffset, 0);
            }
            return (GnssStatus) instance;
        }
    }

    // ================================================================ 构造器形状

    /** “1 个 int[] + N 个 float[]（+ 可选标量 int / 合成 marker）”形状分析。 */
    private static final class CtorShape {

        /** 标量 int 形参个数（svCount / ttff……）。 */
        final int intParamCount;
        /** float[] 形参个数。 */
        final int floatCount;

        private CtorShape(int intParamCount, int floatCount) {
            this.intParamCount = intParamCount;
            this.floatCount = floatCount;
        }

        static boolean isCandidate(Class<?>[] types) {
            return of(types) != null;
        }

        static CtorShape of(Class<?>[] types) {
            if (types == null || types.length < 3) {
                return null;
            }
            int intParams = 0;
            int intArrays = 0;
            int floatArrays = 0;
            for (int i = 0; i < types.length; i++) {
                final Class<?> type = types[i];
                if (type == int.class) {
                    intParams++;
                } else if (type == int[].class) {
                    intArrays++;
                } else if (type == float[].class) {
                    floatArrays++;
                } else if (type.isPrimitive() || type.isArray()) {
                    return null; // long/boolean 标量、其它数组：本工厂不支持，交给下一级兜底
                } else if (!isSyntheticMarker(type)) {
                    return null; // Parcel / String 之类：不是可填的卫星表构造器
                }
            }
            if (intArrays != 1 || floatArrays < 2 || intParams > 2) {
                return null;
            }
            return new CtorShape(intParams, floatArrays);
        }

        /** 合成访问构造器多出来的占位类（{@code GnssStatus$1} 一类）。 */
        private static boolean isSyntheticMarker(Class<?> type) {
            try {
                return type.getName().startsWith(GnssStatus.class.getName() + "$");
            } catch (Throwable t) {
                return false;
            }
        }
    }

    /** 用已解析的形状实例化（参数按形参类型原位填充）。 */
    private static GnssStatus newCtorInstance(Constructor<?> ctor, CtorShape shape,
                                              int[] scalarTemplate, int[] packed,
                                              float[][] floats, int satelliteCount)
            throws Throwable {
        final Class<?>[] types = ctor.getParameterTypes();
        final Object[] args = new Object[types.length];
        int scalar = 0;
        int floatPos = 0;
        for (int i = 0; i < types.length; i++) {
            final Class<?> type = types[i];
            if (type == int.class) {
                final int template = scalar < scalarTemplate.length ? scalarTemplate[scalar] : 0;
                args[i] = Integer.valueOf(template < 0 ? satelliteCount : template);
                scalar++;
            } else if (type == int[].class) {
                args[i] = packed;
            } else if (type == float[].class) {
                args[i] = floats[floatPos++];
            } else {
                args[i] = null; // 合成 marker
            }
        }
        return (GnssStatus) ctor.newInstance(args);
    }

    private static final Comparator<Constructor<?>> BY_PARAM_COUNT =
            new Comparator<Constructor<?>>() {
                @Override
                public int compare(Constructor<?> left, Constructor<?> right) {
                    return Integer.compare(left.getParameterCount(), right.getParameterCount());
                }
            };

    // ================================================================ Unsafe 访问

    /** {@code sun.misc.Unsafe} 的最小封装（全部反射获取，编译期不依赖该类型）。 */
    private static final class UnsafeAccess {

        private final Object unsafe;
        private final Method allocateInstance;
        private final Method objectFieldOffset;
        private final Method putObject;
        private final Method putInt;

        private UnsafeAccess(Object unsafe, Method allocateInstance, Method objectFieldOffset,
                             Method putObject, Method putInt) {
            this.unsafe = unsafe;
            this.allocateInstance = allocateInstance;
            this.objectFieldOffset = objectFieldOffset;
            this.putObject = putObject;
            this.putInt = putInt;
        }

        static UnsafeAccess open() throws Throwable {
            final Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            final Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            final Object unsafe = theUnsafe.get(null);
            if (unsafe == null) {
                throw new IllegalStateException("theUnsafe == null");
            }
            return new UnsafeAccess(
                    unsafe,
                    unsafeClass.getMethod("allocateInstance", Class.class),
                    unsafeClass.getMethod("objectFieldOffset", Field.class),
                    unsafeClass.getMethod("putObject", Object.class, long.class, Object.class),
                    unsafeClass.getMethod("putInt", Object.class, long.class, int.class));
        }

        GnssStatus allocateInstance() throws Throwable {
            final Object instance = allocateInstance.invoke(unsafe, GnssStatus.class);
            if (!(instance instanceof GnssStatus)) {
                throw new IllegalStateException("allocateInstance returned " + instance);
            }
            return (GnssStatus) instance;
        }

        long offsetOf(Field field) {
            if (field == null) {
                return -1L;
            }
            try {
                return ((Number) objectFieldOffset.invoke(unsafe, field)).longValue();
            } catch (Throwable t) {
                return -1L;
            }
        }

        /** 写引用字段：先 {@code Field.set}（Android 上对 final 也有效），失败再走 Unsafe 偏移。 */
        void write(Object instance, Field field, long offset, Object value) throws Throwable {
            if (field != null) {
                try {
                    field.setAccessible(true);
                    field.set(instance, value);
                    return;
                } catch (Throwable ignored) {
                    // final / hidden API 拦截：落到 Unsafe
                }
            }
            if (offset < 0L) {
                throw new IllegalStateException("no offset for "
                        + (field == null ? "?" : field.getName()));
            }
            putObject.invoke(unsafe, instance, Long.valueOf(offset), value);
        }

        /** 写 int 字段（卫星数 / ttff）：同上，先反射后 Unsafe。 */
        void writeInt(Object instance, Field field, long offset, int value) throws Throwable {
            if (field != null) {
                try {
                    field.setAccessible(true);
                    field.setInt(instance, value);
                    return;
                } catch (Throwable ignored) {
                    // 落到 Unsafe
                }
            }
            if (offset < 0L) {
                throw new IllegalStateException("no offset for "
                        + (field == null ? "?" : field.getName()));
            }
            putInt.invoke(unsafe, instance, Long.valueOf(offset), Integer.valueOf(value));
        }
    }

    /** 字段名 / 偏移解析结果（现行 A17 字段名优先，兼容 AOSP 老名字）。 */
    private static final class Fields {

        final Field svid;
        final Field cn0;
        final Field elevation;
        final Field azimuth;
        final Field carrier;
        final Field baseband;
        final Field count;
        final Field ttff;

        final long svidOffset;
        final long cn0Offset;
        final long elevationOffset;
        final long azimuthOffset;
        final long carrierOffset;
        final long basebandOffset;
        final long countOffset;
        final long ttffOffset;

        private Fields(Field svid, Field cn0, Field elevation, Field azimuth, Field carrier,
                       Field baseband, Field count, Field ttff, long svidOffset, long cn0Offset,
                       long elevationOffset, long azimuthOffset, long carrierOffset,
                       long basebandOffset, long countOffset, long ttffOffset) {
            this.svid = svid;
            this.cn0 = cn0;
            this.elevation = elevation;
            this.azimuth = azimuth;
            this.carrier = carrier;
            this.baseband = baseband;
            this.count = count;
            this.ttff = ttff;
            this.svidOffset = svidOffset;
            this.cn0Offset = cn0Offset;
            this.elevationOffset = elevationOffset;
            this.azimuthOffset = azimuthOffset;
            this.carrierOffset = carrierOffset;
            this.basebandOffset = basebandOffset;
            this.countOffset = countOffset;
            this.ttffOffset = ttffOffset;
        }

        /** 只解析字段名，不算偏移（Unsafe 打不开时也能出现在失败日志里）。 */
        static Fields resolve() {
            return new Fields(
                    find("mSvidWithFlags"),
                    find("mCn0DbHzs", "mCn0DbHz"),
                    find("mElevations", "mSvElevationDegrees", "mElevationDegrees"),
                    find("mAzimuths", "mSvAzimuthDegrees", "mAzimuthDegrees"),
                    find("mCarrierFrequencies", "mSvCarrierFrequencyHz", "mSvCarrierFreqHz",
                            "mCarrierFrequencyHz"),
                    find("mBasebandCn0DbHzs", "mBasebandCn0DbHz"),
                    find("mSvCount", "mSatelliteCount", "mNumSats"),
                    find("mTtffMillis", "mTtff", "mTimeToFirstFix"),
                    -1L, -1L, -1L, -1L, -1L, -1L, -1L, -1L);
        }

        /** 补上字段偏移（需要在 {@code sun.misc.Unsafe} 可用之后调用）。 */
        Fields withOffsets(UnsafeAccess access) {
            return new Fields(svid, cn0, elevation, azimuth, carrier, baseband, count, ttff,
                    access.offsetOf(svid), access.offsetOf(cn0), access.offsetOf(elevation),
                    access.offsetOf(azimuth), access.offsetOf(carrier), access.offsetOf(baseband),
                    access.offsetOf(count), access.offsetOf(ttff));
        }

        /** svid/cn0/仰角/方位角缺一不可（载波/基带/卫星数允许缺，老版本名字不同）。 */
        boolean isNamed() {
            return svid != null && cn0 != null && elevation != null && azimuth != null;
        }

        boolean isWritable() {
            return isNamed() && svidOffset >= 0L && cn0Offset >= 0L
                    && elevationOffset >= 0L && azimuthOffset >= 0L;
        }

        String describe() {
            final StringBuilder sb = new StringBuilder("[");
            append(sb, "svid", svid, "mSvidWithFlags");
            append(sb, "cn0", cn0, "mCn0DbHzs|mCn0DbHz");
            append(sb, "elev", elevation, "mElevations|mSvElevationDegrees");
            append(sb, "azi", azimuth, "mAzimuths|mSvAzimuthDegrees");
            append(sb, "car", carrier, "mCarrierFrequencies|mSvCarrierFrequencyHz");
            append(sb, "base", baseband, "mBasebandCn0DbHzs|mBasebandCn0DbHz");
            append(sb, "count", count, "mSvCount");
            append(sb, "ttff", ttff, "mTtffMillis");
            return sb.append(']').toString();
        }

        private static void append(StringBuilder sb, String slot, Field field, String candidates) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append(slot).append('=');
            if (field == null) {
                sb.append("absent(").append(candidates).append(')');
            } else {
                sb.append(field.getName());
            }
        }

        /** 沿类层次按候选名找字段（这里不 setAccessible，避免过早触发 hidden API 限制）。 */
        private static Field find(String... names) {
            for (int i = 0; i < names.length; i++) {
                Class<?> current = GnssStatus.class;
                while (current != null) {
                    try {
                        return current.getDeclaredField(names[i]);
                    } catch (NoSuchFieldException ignored) {
                        current = current.getSuperclass();
                    } catch (Throwable ignored) {
                        break; // hidden API 拦截：换下一个候选名
                    }
                }
            }
            return null;
        }
    }

    // ================================================================ svidWithFlags 编码

    /** 把“卫星号 + 星座 + 标志位”压进 {@code mSvidWithFlags} 的编码方案。 */
    private static final class Packing {

        private final int svidShift;
        private final int constellationShift;
        private final int constellationMask;
        private final int flagsShift;
        private final int almanacBit;
        private final int ephemerisBit;
        private final int usedInFixBit;
        private final int carrierBit;
        private final int basebandBit;

        private Packing(int svidShift, int constellationShift, int constellationMask, int flagsShift,
                        int almanacBit, int ephemerisBit, int usedInFixBit, int carrierBit,
                        int basebandBit) {
            this.svidShift = svidShift;
            this.constellationShift = constellationShift;
            this.constellationMask = constellationMask;
            this.flagsShift = flagsShift;
            this.almanacBit = almanacBit;
            this.ephemerisBit = ephemerisBit;
            this.usedInFixBit = usedInFixBit;
            this.carrierBit = carrierBit;
            this.basebandBit = basebandBit;
        }

        int pack(Sat sat, boolean withCarrier, boolean withBaseband) {
            int flags = 0;
            if (sat.hasAlmanac) {
                flags |= almanacBit;
            }
            if (sat.hasEphemeris) {
                flags |= ephemerisBit;
            }
            if (sat.usedInFix) {
                flags |= usedInFixBit;
            }
            if (withCarrier && sat.carrierHz > 0f) {
                flags |= carrierBit;
            }
            if (withBaseband) {
                flags |= basebandBit;
            }
            int value = sat.svid << svidShift;
            if (constellationMask != 0) {
                value |= (sat.constellation & constellationMask) << constellationShift;
            }
            value |= flags << flagsShift;
            return value;
        }
    }

    /**
     * 候选编码表（按优先级）。跟上一级不同，这里<b>先用 getter 读回校验再采用</b>，
     * 所以候选只要“区间互不重叠”即可，不必猜中真实实现。
     *
     * <p>第 1 条读 {@code GnssStatus} 自身的常量（{@code SVID_SHIFT_WIDTH /
     * CONSTELLATION_TYPE_SHIFT_WIDTH / SVID_FLAGS_*}），A17 若保留这些字段就直接对；
     * 其余覆盖历史实现与个别 ROM 变体。
     */
    private static List<Packing> packingCandidates() {
        List<Packing> cached = sPackingCandidates;
        if (cached != null) {
            return cached;
        }
        final int svidShift = intConstant("SVID_SHIFT_WIDTH", 8);
        final int constellationShift = intConstant("CONSTELLATION_TYPE_SHIFT_WIDTH", 16);
        final int constellationMask = intConstant("CONSTELLATION_TYPE_MASK", 0xff);
        final int almanacBit = intConstant("SVID_FLAGS_HAS_ALMANAC_DATA", 1 << 0);
        final int ephemerisBit = intConstant("SVID_FLAGS_HAS_EPHEMERIS_DATA", 1 << 1);
        final int usedInFixBit = intConstant("SVID_FLAGS_USED_IN_FIX", 1 << 2);
        final int carrierBit = intConstant("SVID_FLAGS_HAS_CARRIER_FREQUENCY", 1 << 3);
        final int basebandBit = intConstant("SVID_FLAGS_HAS_BASEBAND_CN0", 1 << 4);
        final List<Packing> list = new ArrayList<>(5);
        // 1) 类自身常量（拿不到就是 AOSP 现行默认：标志低 5 位、svid 在 bit8、星座在 bit16）
        list.add(new Packing(svidShift, constellationShift, constellationMask, 0,
                almanacBit, ephemerisBit, usedInFixBit, carrierBit, basebandBit));
        // 2) 紧凑形态：svid 低 8 位、星座 bit8、标志 bit16（区间互不重叠，不会互相踩）
        list.add(new Packing(0, 8, 0xff, 16, 1, 2, 4, 8, 16));
        // 3) 标志放最高字节：svid 低 8 位、星座 bit8、标志 bit24（个别 ROM 变体）
        list.add(new Packing(0, 8, 0xff, 24, 1, 2, 4, 8, 16));
        // 4) 星座由类按 svid 段自行推断：只打包 svid + 标志
        list.add(new Packing(8, 0, 0, 0, 1, 2, 4, 8, 16));
        // 5) 同上但 svid 在低位、标志在 bit8
        list.add(new Packing(0, 0, 0, 8, 1, 2, 4, 8, 16));
        cached = Collections.unmodifiableList(list);
        sPackingCandidates = cached;
        return cached;
    }

    private static int intConstant(String name, int fallback) {
        try {
            final Field field = GnssStatus.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 逻辑槽 → 该卫星的取值（{@link #SLOT_NONE} 填 0，保证数组长度与卫星数一致）。 */
    private static float floatValue(int slot, Sat sat) {
        switch (slot) {
            case SLOT_CN0:
                return sat.cn0;
            case SLOT_ELEVATION:
                return sat.elevation;
            case SLOT_AZIMUTH:
                return sat.azimuth;
            case SLOT_CARRIER:
                return sat.carrierHz;
            case SLOT_BASEBAND:
                return sat.cn0; // 快照无基带载噪比：用 cn0 近似（与 GnssReplay 一致）
            default:
                return 0f;
        }
    }

    private static boolean hasSlot(int[] slotOfPos, int slot) {
        if (slotOfPos == null) {
            return false;
        }
        for (int i = 0; i < slotOfPos.length; i++) {
            if (slotOfPos[i] == slot) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ 反射小工具

    private static ClassLoader safeLoader() {
        try {
            return GnssStatus.class.getClassLoader();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>[] parameterTypes) {
        try {
            return clazz.getMethod(name, parameterTypes);
        } catch (Throwable ignored) {
            // 继续试 declared（个别 ROM 把方法降成包私有）
        }
        try {
            final Method method = clazz.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Constructor<?> findConstructor(Class<?> clazz) {
        try {
            final Constructor<?> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** API 30+ 才有的 getter：反射调用；方法不存在返回 null（老版本直接调用会 NoSuchMethodError）。 */
    private static Float callFloat(GnssStatus status, String name, int index) {
        try {
            final Method method = status.getClass().getMethod(name, int.class);
            return (Float) method.invoke(status, Integer.valueOf(index));
        } catch (Throwable t) {
            return null;
        }
    }

    private static Boolean callBoolean(GnssStatus status, String name, int index) {
        try {
            final Method method = status.getClass().getMethod(name, int.class);
            return (Boolean) method.invoke(status, Integer.valueOf(index));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String signature(Constructor<?> ctor) {
        final StringBuilder sb = new StringBuilder("(");
        final Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            sb.append(shortType(types[i]));
        }
        return sb.append(')').toString();
    }

    private static String shortType(Class<?> type) {
        if (type == int.class) {
            return "I";
        }
        if (type == long.class) {
            return "J";
        }
        if (type == float.class) {
            return "F";
        }
        if (type == boolean.class) {
            return "Z";
        }
        if (type.isArray()) {
            return "[" + shortType(type.getComponentType());
        }
        final String name = type.getName();
        return "L" + name.substring(name.lastIndexOf('.') + 1) + ";";
    }

    private static String signatures(Constructor<?>[] ctors) {
        final StringBuilder sb = new StringBuilder("[");
        int shown = 0;
        for (int i = 0; i < ctors.length && shown < 8; i++, shown++) {
            if (shown > 0) {
                sb.append(',');
            }
            sb.append(signature(ctors[i]));
        }
        if (ctors.length > shown) {
            sb.append(",…");
        }
        return sb.append(']').toString();
    }

    private static String describe(Throwable t) {
        Throwable current = t;
        if (current instanceof InvocationTargetException && current.getCause() != null) {
            current = current.getCause();
        }
        final String name = current.getClass().getSimpleName();
        final String message = current.getMessage();
        return message == null || message.isEmpty() ? name : name + ": " + message;
    }

    // ================================================================ 诊断 / 日志

    /** 探测过程的诊断信息：失败时拼进唯一的一条 {@code gnss build failed} 日志。 */
    private static final class Diag {

        String ctors = "[]";
        String fields = "[]";
        String lastError = "-";
        private final StringBuilder steps = new StringBuilder();

        void step(String text) {
            if (steps.length() >= 420) {
                return;
            }
            if (steps.length() > 0) {
                steps.append("; ");
            }
            steps.append(text);
        }

        void error(String text) {
            lastError = text;
            step(text);
        }

        String summary() {
            return lastError + (steps.length() > 0 ? " (tried: " + steps + ")" : "");
        }
    }

    private static void logSuccessOnce(Plan plan, List<Sat> sats) {
        if (!sLoggedSuccess.compareAndSet(false, true)) {
            return;
        }
        float sum = 0f;
        for (int i = 0; i < sats.size(); i++) {
            sum += sats.get(i).cn0;
        }
        final float mean = sum / sats.size();
        emit(false, "gnss build: " + plan.kind + " n=" + sats.size()
                + " meanCn0=" + String.format(Locale.US, "%.1f", mean));
    }

    private static void logFailureOnce(Diag diag) {
        if (!sLoggedFailure.compareAndSet(false, true)) {
            return;
        }
        emit(true, "gnss build failed: ctors=" + diag.ctors + " fields=" + diag.fields
                + " err=" + diag.summary());
    }

    /** 日志同时走 VLog（tag {@code VLocHidden}）与 Xposed 日志，两边都能看到。 */
    private static void emit(boolean error, String message) {
        try {
            if (error) {
                VLog.e(TAG, message);
            } else {
                VLog.i(TAG, message);
            }
        } catch (Throwable ignored) {
            // 日志失败不影响业务
        }
        try {
            Xp.log(message);
        } catch (Throwable ignored) {
            // 没装 Xposed 后端（单测 / 宿主进程）：忽略
        }
    }
}
