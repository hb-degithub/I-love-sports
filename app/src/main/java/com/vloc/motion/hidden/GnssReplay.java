package com.vloc.motion.hidden;

import android.location.GnssStatus;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * GNSS 卫星状态回放（v0.18.x）：让目标 App 从 {@code GnssStatus} / {@code LocationManager} 看到的
 * 卫星数、星座、方位角 / 仰角 / 载噪比与虚拟路线自洽，消除“有坐标却没有卫星”的矛盾证据。
 *
 * <p>数据来源：复用 {@link StepReplay} 的 steps 快照（{@code content://com.vloc.motion.env/steps}，
 * 1 秒缓存，<b>不重复查询 provider</b>）。主 App 把 JSON 的 {@code version} 升到 2 并新增
 * {@code "gnss":[{svid,const,ele,azi,cn0,fix,alm,eph,cf,base}, ...]}；{@link StepReplay} 解析时
 * 调用 {@link #parseInto(List, JSONObject)} 把卫星数组放进 {@link StepReplay.StepsSnapshot#gnss}。
 *
 * <p>降级约定：字段缺失（老版本主 App）/ {@code mode=off} / {@code active=false} / 查询失败 /
 * 解析失败 → 卫星列表为空，<b>所有 hook 一律透传原值</b>，目标 App 行为与未装模块完全一致。
 *
 * <p>实现：
 * <ul>
 *   <li><b>读取拦截</b>：hook {@code GnssStatus} 的 getSatelliteCount / getSvid / getConstellationType /
 *       getCn0DbHz / getElevationDegrees / getAzimuthDegrees / getCarrierFrequencyHz /
 *       hasCarrierFrequencyHz / usedInFix / hasAlmanacData / hasEphemerisData，外加 API 30+ 的
 *       getBasebandCn0DbHz / hasBasebandCn0DbHz（找不到自动跳过）。afterHookedMethod + setResult
 *       改写返回值；索引越界时保持原实现结果（原实现抛异常时回落 0/false，绝不把异常抛给 App）。</li>
 *   <li><b>回调节奏</b>：hook {@code LocationManager#registerGnssStatusCallback} 的全部重载，把 App 的
 *       {@code GnssStatus.Callback} 换成 {@link CallbackWrapper}（弱引用原回调，卸载/回收自动失效），
 *       并按注册时的 Handler / Executor（没有则主线程）以 <b>1Hz</b> 投递
 *       {@code onSatelliteStatusChanged(status)}；回放期间吞掉系统真实回调，避免真假卫星混播，
 *       非回放期间包装器把真实回调原样透传。</li>
 *   <li><b>GnssStatus 实例</b>：优先用 API 30+ 的 {@code GnssStatus$Builder} 反射构造一颗与快照
 *       完全一致的“真对象”（getSatelliteCount / writeToParcel / equals 都自洽）；不可用时退回
 *       系统真实回调里捕获的实例（getter 已 hook，读到就是假值）；再退回包内反射构造的空实例；
 *       最后用 {@code sun.misc.Unsafe.allocateInstance}。全部失败则只保留 getter hook（日志说明）。</li>
 *   <li><b>卸载</b>：hook {@code unregisterGnssStatusCallback}，把“注销 App 原回调”翻译成
 *       “注销包装器”，避免包装器继续回调已被 App 丢弃的 listener；不 setResult，让原调用继续
 *       （用未注册的原回调注销是 no-op）。</li>
 * </ul>
 *
 * <p>约束：模块进程不申请权限、不联网、不新增依赖；每个 hook 独立 try/catch，任何异常都不得影响目标
 * App 启动或运行。仍无法覆盖的检测面见 InfoActivity 说明与交付报告（GnssMeasurementsEvent、
 * NMEA、GnssNavigationMessage、system_server / HAL 层卫星状态、真实射频信号强度等）。
 */
final class GnssReplay {

    private static final String TAG = "[VLocHidden] ";

    // ------------------------------------------------------------------
    // JSON 契约（主 App 写入 steps 快照的 json 列，version=2）
    // ------------------------------------------------------------------

    private static final String KEY_GNSS = "gnss";
    private static final String KEY_SVID = "svid";
    private static final String KEY_CONSTELLATION = "const";
    private static final String KEY_ELEVATION = "ele";
    private static final String KEY_AZIMUTH = "azi";
    private static final String KEY_CN0 = "cn0";
    private static final String KEY_USED_IN_FIX = "fix";
    private static final String KEY_HAS_ALMANAC = "alm";
    private static final String KEY_HAS_EPHEMERIS = "eph";
    private static final String KEY_CARRIER_FREQUENCY = "cf";
    private static final String KEY_BASEBAND_CN0 = "base";

    /** 真实天空一次可见 30-70 颗（GPS+GLONASS+北斗+Galileo，含低仰角）；
     *  这里只做上限保护，防止异常快照把目标 App 刷爆。 */
    private static final int MAX_SATELLITES = 96;

    /**
     * 合法 Svid 上限。
     *
     * <p>按 AOSP 的编号习惯，可见卫星 Svid 最高会到 Galileo 的 336（301–336），
     * 所以不能按"≤255 的字节"来校验，否则整族 Galileo 卫星会被当非法项丢掉。
     */
    private static final int MAX_SVID = 512;

    // 星座常量（与 android.location.GnssStatus.* 数值一致，避免依赖 API 级别）。
    private static final int CONSTELLATION_GPS = 1;
    private static final int CONSTELLATION_GLONASS = 3;
    private static final int CONSTELLATION_BEIDOU = 5;
    private static final int CONSTELLATION_GALILEO = 6;
    private static final int CONSTELLATION_QZSS = 4;

    // ------------------------------------------------------------------
    // 回调节奏
    // ------------------------------------------------------------------

    /** 1Hz：与真机 GNSS 状态上报节奏一致（真实系统通常也是 1Hz）。 */
    private static final long TICK_MS = 1000L;

    private static final Object LOCK = new Object();

    private static final List<Registration> sRegistrations = new ArrayList<>();

    private static HandlerThread sTickerThread;
    private static Handler sTickerHandler;
    private static Handler sMainHandler;

    /**
     * 系统真实回调里捕获的最近一个 GnssStatus。getter 已被 hook，所以把这个实例回传给 App
     * 时读到的是假值；只在 Builder 不可用时才用它。
     */
    private static volatile GnssStatus sLatestRealStatus;

    /** 反射构造出来的“0 颗卫星”真实 GnssStatus（getter 已 hook，对外表现为假卫星）。 */
    private static volatile GnssStatus sEmptyStatus;
    private static volatile boolean sEmptyStatusFailed;

    /** Builder 构造的“与快照完全一致”的 GnssStatus，按卫星列表实例缓存（快照 1 秒一换）。 */
    private static volatile GnssStatus sBuiltStatus;
    private static volatile List<Satellite> sBuiltFor;

    private static boolean sLoggedRegister;
    private static boolean sLoggedActive;
    private static boolean sLoggedUnregister;
    private static boolean sLoggedParseFail;
    private static boolean sLoggedTickerFail;
    private static boolean sLoggedStatusUnavailable;
    private static boolean sLoggedBuilderFail;
    private static boolean sLoggedBuilt;

    private GnssReplay() {
    }

    // ==================================================================
    // 安装
    // ==================================================================

    /** getter hook 期望条数（11 个基础 + API 30+ 的 2 个基带 Cn0）。 */
    private static final int GETTER_HOOK_COUNT = 13;
    /** 期望总条数：13 个 getter + register + unregister。 */
    private static final int TOTAL_HOOK_COUNT = GETTER_HOOK_COUNT + 2;

    static int install(ClassLoader classLoader) {
        int hooked = 0;

        // ---- GnssStatus 读取方法 ----
        int getters = 0;
        getters += hookGetter("getSatelliteCount", KIND_COUNT, FIELD_NONE) ? 1 : 0;
        getters += hookGetter("getSvid", KIND_INT, FIELD_SVID) ? 1 : 0;
        getters += hookGetter("getConstellationType", KIND_INT, FIELD_CONSTELLATION) ? 1 : 0;
        getters += hookGetter("getCn0DbHz", KIND_FLOAT, FIELD_CN0) ? 1 : 0;
        getters += hookGetter("getElevationDegrees", KIND_FLOAT, FIELD_ELEVATION) ? 1 : 0;
        getters += hookGetter("getAzimuthDegrees", KIND_FLOAT, FIELD_AZIMUTH) ? 1 : 0;
        getters += hookGetter("getCarrierFrequencyHz", KIND_FLOAT, FIELD_CARRIER_FREQUENCY) ? 1 : 0;
        getters += hookGetter("hasCarrierFrequencyHz", KIND_BOOL, FIELD_HAS_CARRIER_FREQUENCY) ? 1 : 0;
        getters += hookGetter("usedInFix", KIND_BOOL, FIELD_USED_IN_FIX) ? 1 : 0;
        getters += hookGetter("hasAlmanacData", KIND_BOOL, FIELD_HAS_ALMANAC) ? 1 : 0;
        getters += hookGetter("hasEphemerisData", KIND_BOOL, FIELD_HAS_EPHEMERIS) ? 1 : 0;
        // API 30+ 才有：尽力而为，低版本 findAndHookMethod 抛错后自动跳过。
        getters += hookGetter("getBasebandCn0DbHz", KIND_FLOAT, FIELD_BASEBAND_CN0) ? 1 : 0;
        getters += hookGetter("hasBasebandCn0DbHz", KIND_BOOL, FIELD_HAS_BASEBAND_CN0) ? 1 : 0;
        hooked += getters;

        // ---- 回调注册 / 注销 ----
        try {
            final int count = XposedBridge.hookAllMethods(
                    LocationManager.class, "registerGnssStatusCallback", REGISTER_HOOK).size();
            if (count > 0) {
                hooked++;
                log("hooked LocationManager#registerGnssStatusCallback (" + count + " overload(s))");
            }
        } catch (Throwable t) {
            log("skip LocationManager#registerGnssStatusCallback: " + t);
        }
        try {
            final int count = XposedBridge.hookAllMethods(
                    LocationManager.class, "unregisterGnssStatusCallback", UNREGISTER_HOOK).size();
            if (count > 0) {
                hooked++;
                log("hooked LocationManager#unregisterGnssStatusCallback (" + count + " overload(s))");
            }
        } catch (Throwable t) {
            log("skip LocationManager#unregisterGnssStatusCallback: " + t);
        }

        log("gnss hooks=" + hooked + "/" + TOTAL_HOOK_COUNT
                + " (GnssStatus getters=" + getters + "/" + GETTER_HOOK_COUNT + ")");
        return hooked;
    }

    // ==================================================================
    // GnssStatus 读取方法（after + setResult 改写返回值）
    // ==================================================================

    private static boolean hookGetter(String name, int kind, int field) {
        try {
            final XC_MethodHook hook = new GetterHook(kind, field);
            if (kind == KIND_COUNT) {
                XposedHelpers.findAndHookMethod(GnssStatus.class, name, hook);
            } else {
                XposedHelpers.findAndHookMethod(GnssStatus.class, name, int.class, hook);
            }
            return true;
        } catch (Throwable t) {
            log("skip GnssStatus#" + name + "(): " + t);
            return false;
        }
    }

    /**
     * 单个 getter 的改写器：快照无效 / 索引越界时<b>不</b> setResult，保持原实现表现；
     * 只有在原实现抛异常（例如真实 GnssStatus 的内部数组比我们声明的卫星数短）时才回落 0/false，
     * 保证“越界不崩”。
     */
    private static final class GetterHook extends XC_MethodHook {

        private final int kind;
        private final int field;

        GetterHook(int kind, int field) {
            this.kind = kind;
            this.field = field;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                final List<Satellite> satellites = activeSatellites();
                if (satellites == null || satellites.isEmpty()) {
                    return; // 无快照 / 未回放 / 老版本主 App：完全透传
                }
                if (kind == KIND_COUNT) {
                    param.setResult(Integer.valueOf(satellites.size()));
                    return;
                }
                final int index = readIntArg(param.args);
                if (index < 0 || index >= satellites.size()) {
                    // 越界：保持原实现结果（不 setResult）；若原实现已经抛异常，回落中性值，绝不崩溃。
                    if (param.hasThrowable()) {
                        param.setResult(neutralResult());
                    }
                    return;
                }
                final Satellite satellite = satellites.get(index);
                switch (field) {
                    case FIELD_SVID:
                        param.setResult(Integer.valueOf(satellite.svid));
                        break;
                    case FIELD_CONSTELLATION:
                        param.setResult(Integer.valueOf(satellite.constellation));
                        break;
                    case FIELD_CN0:
                        param.setResult(Float.valueOf(satellite.cn0));
                        break;
                    case FIELD_ELEVATION:
                        param.setResult(Float.valueOf(satellite.elevation));
                        break;
                    case FIELD_AZIMUTH:
                        param.setResult(Float.valueOf(satellite.azimuth));
                        break;
                    case FIELD_CARRIER_FREQUENCY:
                        param.setResult(Float.valueOf(satellite.carrierFrequencyHz));
                        break;
                    case FIELD_BASEBAND_CN0:
                        param.setResult(Float.valueOf(satellite.basebandCn0));
                        break;
                    case FIELD_USED_IN_FIX:
                        param.setResult(Boolean.valueOf(satellite.usedInFix));
                        break;
                    case FIELD_HAS_ALMANAC:
                        param.setResult(Boolean.valueOf(satellite.hasAlmanac));
                        break;
                    case FIELD_HAS_EPHEMERIS:
                        param.setResult(Boolean.valueOf(satellite.hasEphemeris));
                        break;
                    case FIELD_HAS_CARRIER_FREQUENCY:
                        param.setResult(Boolean.valueOf(satellite.hasCarrierFrequency));
                        break;
                    case FIELD_HAS_BASEBAND_CN0:
                        param.setResult(Boolean.TRUE);
                        break;
                    default:
                        break;
                }
            } catch (Throwable ignored) {
                // 任何改写失败都退回原实现结果，绝不把异常抛给目标 App
            }
        }

        private Object neutralResult() {
            switch (kind) {
                case KIND_INT:
                    return Integer.valueOf(0);
                case KIND_FLOAT:
                    return Float.valueOf(0f);
                case KIND_BOOL:
                    return Boolean.FALSE;
                default:
                    return null;
            }
        }
    }

    private static int readIntArg(Object[] args) {
        if (args == null || args.length < 1 || !(args[0] instanceof Integer)) {
            return Integer.MIN_VALUE;
        }
        return ((Integer) args[0]).intValue();
    }

    /** 有效快照（mode=replay && active=true && 有 gnss 数组）时返回卫星列表，否则 null。 */
    static List<Satellite> activeSatellites() {
        try {
            final StepReplay.StepsSnapshot snapshot = StepReplay.currentSnapshot();
            if (snapshot == null || !snapshot.active) {
                return null;
            }
            final List<Satellite> satellites = snapshot.gnss;
            if (satellites == null || satellites.isEmpty()) {
                return null;
            }
            logActiveOnce(satellites.size());
            return satellites;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================================================================
    // steps 快照里的 gnss JSON 解析（由 StepReplay 在 1 秒缓存刷新时调用一次）
    // ==================================================================

    /**
     * 解析 {@code "gnss":[...]} 到 {@code out}（先 clear）。字段缺失 / 格式异常 → 列表为空，
     * 上层据此全透传。单颗卫星数据非法只跳过该颗，不影响其它卫星。
     */
    static void parseInto(List<Satellite> out, JSONObject root) {
        if (out == null) {
            return;
        }
        out.clear();
        if (root == null) {
            return;
        }
        try {
            final JSONArray array = root.optJSONArray(KEY_GNSS);
            if (array == null) {
                return; // 老版本主 App 不下发 gnss 字段：静默降级
            }
            final int length = Math.min(array.length(), MAX_SATELLITES);
            for (int i = 0; i < length; i++) {
                final JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                final Satellite satellite = Satellite.parse(item);
                if (satellite != null) {
                    out.add(satellite);
                }
            }
        } catch (Throwable t) {
            out.clear();
            logParseFailOnce(t);
        }
    }

    private static void logParseFailOnce(Throwable t) {
        if (sLoggedParseFail) {
            return;
        }
        sLoggedParseFail = true;
        log("gnss json parse failed (passthrough): " + t);
    }

    /** 一颗卫星的只读快照。数值已做物理范围裁剪，避免异常 JSON 让目标 App 看到不可能的值。 */
    static final class Satellite {

        final int svid;
        final int constellation;
        final float elevation;
        final float azimuth;
        final float cn0;
        final float carrierFrequencyHz;
        final float basebandCn0;
        final boolean usedInFix;
        final boolean hasAlmanac;
        final boolean hasEphemeris;
        final boolean hasCarrierFrequency;

        private Satellite(int svid, int constellation, float elevation, float azimuth, float cn0,
                          float carrierFrequencyHz, float basebandCn0, boolean usedInFix,
                          boolean hasAlmanac, boolean hasEphemeris, boolean hasCarrierFrequency) {
            this.svid = svid;
            this.constellation = constellation;
            this.elevation = elevation;
            this.azimuth = azimuth;
            this.cn0 = cn0;
            this.carrierFrequencyHz = carrierFrequencyHz;
            this.basebandCn0 = basebandCn0;
            this.usedInFix = usedInFix;
            this.hasAlmanac = hasAlmanac;
            this.hasEphemeris = hasEphemeris;
            this.hasCarrierFrequency = hasCarrierFrequency;
        }

        static Satellite parse(JSONObject object) {
            try {
                final int svid = object.optInt(KEY_SVID, 0);
                if (svid <= 0 || svid > MAX_SVID) {
                    return null; // 契约里 svid 必须有效；非法项直接跳过
                }
                int constellation = object.optInt(KEY_CONSTELLATION, 0);
                if (!isKnownConstellation(constellation)) {
                    constellation = inferConstellation(svid);
                }
                final float elevation = clampFloat(object.optDouble(KEY_ELEVATION, 0d), 0d, 90d);
                final float azimuth = normalizeAzimuth(object.optDouble(KEY_AZIMUTH, 0d));
                final float cn0 = clampFloat(object.optDouble(KEY_CN0, 0d), 0d, 63d);
                final float carrierFrequencyHz = clampFloat(
                        object.optDouble(KEY_CARRIER_FREQUENCY, 0d), 0d, 1.0e12d);
                // base 缺失时用 cn0 近似（基带载噪比通常略低于 cn0，差 0~2dB，这里取等，保证自洽）
                final float basebandCn0 = clampFloat(
                        object.optDouble(KEY_BASEBAND_CN0, cn0), 0d, 63d);
                final boolean usedInFix = object.optBoolean(KEY_USED_IN_FIX, true);
                final boolean hasAlmanac = object.optBoolean(KEY_HAS_ALMANAC, true);
                final boolean hasEphemeris = object.optBoolean(KEY_HAS_EPHEMERIS, true);
                final boolean hasCarrierFrequency = carrierFrequencyHz > 0f;
                return new Satellite(svid, constellation, elevation, azimuth, cn0,
                        carrierFrequencyHz, basebandCn0, usedInFix, hasAlmanac, hasEphemeris,
                        hasCarrierFrequency);
            } catch (Throwable t) {
                return null;
            }
        }

        private static boolean isKnownConstellation(int constellation) {
            switch (constellation) {
                case CONSTELLATION_GPS:
                case CONSTELLATION_GLONASS:
                case CONSTELLATION_BEIDOU:
                case CONSTELLATION_GALILEO:
                case CONSTELLATION_QZSS:
                    return true;
                default:
                    return false;
            }
        }

        /** 快照里 const 缺失或不合法时，按 AOSP 的 svid 编号段推断星座。 */
        private static int inferConstellation(int svid) {
            if (svid >= 1 && svid <= 32) {
                return CONSTELLATION_GPS;
            }
            if (svid >= 65 && svid <= 96) {
                return CONSTELLATION_GLONASS;
            }
            if (svid >= 193 && svid <= 200) {
                return CONSTELLATION_QZSS;
            }
            if (svid >= 201 && svid <= 263) {
                return CONSTELLATION_BEIDOU;
            }
            if (svid >= 301 && svid <= 336) {
                return CONSTELLATION_GALILEO;
            }
            return 0; // CONSTELLATION_UNKNOWN
        }

        private static float normalizeAzimuth(double degrees) {
            if (Double.isNaN(degrees) || Double.isInfinite(degrees)) {
                return 0f;
            }
            double value = degrees % 360d;
            if (value < 0d) {
                value += 360d;
            }
            return (float) value;
        }

        private static float clampFloat(double value, double min, double max) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return (float) min;
            }
            double clamped = value;
            if (clamped < min) {
                clamped = min;
            } else if (clamped > max) {
                clamped = max;
            }
            return (float) clamped;
        }
    }

    // ==================================================================
    // LocationManager#registerGnssStatusCallback / unregisterGnssStatusCallback
    // ==================================================================

    private static final XC_MethodHook REGISTER_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!(param.thisObject instanceof LocationManager)) {
                    return;
                }
                final GnssStatus.Callback target = findCallback(param.args);
                if (target == null || target instanceof CallbackWrapper) {
                    return; // 不是 GNSS 状态回调，或我们自己发起的内部调用
                }
                final LocationManager manager = (LocationManager) param.thisObject;

                // 同一个 callback 对同一个 LocationManager 重复注册：复用已有 wrapper 保持幂等
                final Registration existing = findRegistration(manager, target);
                if (existing != null) {
                    replaceCallback(param.args, target, existing.wrapper);
                    return;
                }

                final Registration registration = new Registration(
                        manager,
                        target,
                        new CallbackWrapper(target),
                        findHandler(param.args),
                        findExecutor(param.args));
                synchronized (LOCK) {
                    sRegistrations.add(registration);
                }
                replaceCallback(param.args, target, registration.wrapper);
                ensureTicker();
                logRegisterOnce(registration);
            } catch (Throwable t) {
                log("registerGnssStatusCallback hook failed: " + t);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                // 框架内部常见实现：registerGnssStatusCallback(callback) 会再调用带 handler /
                // packageName 的重载。内层看到的是我们的 wrapper，不会再包一层；这里通过 wrapper
                // 反查注册记录，只在注册失败 / 抛异常时移除，避免“App 以为注册成功但我们没接上”。
                final GnssStatus.Callback wrapped = findCallback(param.args);
                if (!(wrapped instanceof CallbackWrapper)) {
                    return;
                }
                final Object result = param.hasThrowable() ? null : param.getResult();
                final boolean failed = param.hasThrowable()
                        || (result instanceof Boolean && !((Boolean) result).booleanValue());
                if (!failed) {
                    return;
                }
                final Registration registration = findRegistrationByWrapper((CallbackWrapper) wrapped);
                if (registration != null) {
                    removeRegistration(registration);
                    log("registerGnssStatusCallback returned false/throw, gnss wrapper removed");
                }
            } catch (Throwable t) {
                log("registerGnssStatusCallback after-hook failed: " + t);
            }
        }
    };

    private static final XC_MethodHook UNREGISTER_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                final GnssStatus.Callback target = findCallback(param.args);
                if (target == null || target instanceof CallbackWrapper) {
                    return; // 我们自己发起的注销：放行给原始实现
                }
                final LocationManager manager = param.thisObject instanceof LocationManager
                        ? (LocationManager) param.thisObject : null;
                final List<Registration> matches = takeRegistrations(manager, target);
                for (int i = 0; i < matches.size(); i++) {
                    final Registration registration = matches.get(i);
                    try {
                        if (registration.manager != null) {
                            registration.manager.unregisterGnssStatusCallback(registration.wrapper);
                        }
                    } catch (Throwable t) {
                        log("unregister gnss wrapper failed: " + t);
                    }
                }
                logUnregisterOnce(matches.size());
                // 注意：不 setResult —— 原调用用 App 的原回调继续执行，系统里没有这个注册是 no-op，
                // 让其它模块的 unregister hook 也能看到本次调用。
            } catch (Throwable t) {
                log("unregisterGnssStatusCallback hook failed: " + t);
            }
        }
    };

    private static GnssStatus.Callback findCallback(Object[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof GnssStatus.Callback) {
                return (GnssStatus.Callback) args[i];
            }
        }
        return null;
    }

    private static void replaceCallback(Object[] args, GnssStatus.Callback from, GnssStatus.Callback to) {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] == from) {
                args[i] = to;
                return;
            }
        }
    }

    private static Handler findHandler(Object[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Handler) {
                return (Handler) args[i];
            }
        }
        return null;
    }

    private static Executor findExecutor(Object[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Executor) {
                return (Executor) args[i];
            }
        }
        return null;
    }

    private static Registration findRegistration(LocationManager manager, GnssStatus.Callback target) {
        synchronized (LOCK) {
            for (int i = 0; i < sRegistrations.size(); i++) {
                final Registration registration = sRegistrations.get(i);
                if (registration.target.get() == target
                        && (manager == null || registration.manager == manager)) {
                    return registration;
                }
            }
        }
        return null;
    }

    private static Registration findRegistrationByWrapper(CallbackWrapper wrapper) {
        synchronized (LOCK) {
            for (int i = 0; i < sRegistrations.size(); i++) {
                final Registration registration = sRegistrations.get(i);
                if (registration.wrapper == wrapper) {
                    return registration;
                }
            }
        }
        return null;
    }

    private static List<Registration> takeRegistrations(LocationManager manager,
                                                        GnssStatus.Callback target) {
        final List<Registration> matches = new ArrayList<>();
        synchronized (LOCK) {
            for (int i = sRegistrations.size() - 1; i >= 0; i--) {
                final Registration registration = sRegistrations.get(i);
                if (registration.target.get() != target) {
                    continue;
                }
                if (manager != null && registration.manager != manager) {
                    continue;
                }
                sRegistrations.remove(i);
                matches.add(registration);
            }
        }
        return matches;
    }

    private static void removeRegistration(Registration registration) {
        synchronized (LOCK) {
            sRegistrations.remove(registration);
        }
    }

    private static void unregisterWrapper(Registration registration) {
        try {
            if (registration.manager != null) {
                registration.manager.unregisterGnssStatusCallback(registration.wrapper);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ==================================================================
    // 1Hz 合成线程
    // ==================================================================

    private static void ensureTicker() {
        synchronized (LOCK) {
            if (sTickerHandler != null) {
                return;
            }
            try {
                final HandlerThread thread = new HandlerThread("VLocHidden-gnss");
                thread.start();
                sTickerThread = thread;
                sTickerHandler = new Handler(thread.getLooper());
                sTickerHandler.postDelayed(TICK_TASK, TICK_MS);
            } catch (Throwable t) {
                sTickerThread = null;
                sTickerHandler = null;
                logTickerFailOnce(t);
            }
        }
    }

    private static void stopTicker() {
        synchronized (LOCK) {
            if (sTickerHandler != null) {
                try {
                    sTickerHandler.removeCallbacks(TICK_TASK);
                } catch (Throwable ignored) {
                    // ignore
                }
                sTickerHandler = null;
            }
            if (sTickerThread != null) {
                try {
                    sTickerThread.quitSafely();
                } catch (Throwable ignored) {
                    // ignore
                }
                sTickerThread = null;
            }
        }
    }

    private static void logTickerFailOnce(Throwable t) {
        if (sLoggedTickerFail) {
            return;
        }
        sLoggedTickerFail = true;
        log("gnss ticker start failed (getter hooks only): " + t);
    }

    private static final Runnable TICK_TASK = new Runnable() {
        @Override
        public void run() {
            try {
                onTick();
            } catch (Throwable t) {
                log("gnss tick failed: " + t);
            }
            final Handler handler = sTickerHandler;
            if (handler != null) {
                handler.postDelayed(this, TICK_MS);
            }
        }
    };

    private static void onTick() {
        final List<Registration> registrations;
        synchronized (LOCK) {
            if (sRegistrations.isEmpty()) {
                stopTicker();
                return;
            }
            registrations = new ArrayList<>(sRegistrations);
        }

        final List<Satellite> satellites = activeSatellites();
        if (satellites == null) {
            return; // 未回放：系统真实回调照常透传给 App，这里什么都不做
        }
        final GnssStatus status = obtainStatus(satellites);
        if (status == null) {
            return; // 拿不到可投递的 GnssStatus 实例：只保留 getter hook（已在 obtainStatus 里打过日志）
        }

        for (int i = 0; i < registrations.size(); i++) {
            final Registration registration = registrations.get(i);
            if (registration.target.get() == null) {
                // App 丢掉了 callback 却没注销：把 wrapper 也注销掉，避免空转
                removeRegistration(registration);
                unregisterWrapper(registration);
                continue;
            }
            deliverSynthetic(registration, status);
        }
    }

    private static void deliverSynthetic(final Registration registration, final GnssStatus status) {
        final Runnable delivery = new Runnable() {
            @Override
            public void run() {
                registration.wrapper.deliverSynthetic(status);
            }
        };
        // 优先用 App 注册时指定的 Executor（保持回调线程语义），其次 Handler，最后主线程
        if (registration.executor != null) {
            try {
                registration.executor.execute(delivery);
                return;
            } catch (Throwable ignored) {
                // Executor 已关停：退回 Handler
            }
        }
        final Handler handler = registration.handler != null ? registration.handler : mainHandler();
        try {
            handler.post(delivery);
        } catch (Throwable t) {
            try {
                mainHandler().post(delivery);
            } catch (Throwable ignored) {
                // 目标线程已退出：丢弃这一次回调
            }
        }
    }

    private static Handler mainHandler() {
        synchronized (LOCK) {
            if (sMainHandler == null) {
                sMainHandler = new Handler(Looper.getMainLooper());
            }
            return sMainHandler;
        }
    }

    // ==================================================================
    // GnssStatus 实例获取（Builder → 真实回调捕获 → 空实例 → Unsafe）
    // ==================================================================

    private static GnssStatus obtainStatus(List<Satellite> satellites) {
        try {
            // 1) API 30+ Builder：构造一颗与快照完全一致的真 GnssStatus（最自洽）
            final GnssStatus built = buildStatusFromSnapshot(satellites);
            if (built != null) {
                return built;
            }
            // 2) 系统真实回调里捕获的实例（getter 已 hook，读到就是假值）
            final GnssStatus real = sLatestRealStatus;
            if (real != null) {
                return real;
            }
            // 3) 反射构造的空 GnssStatus（内部是 0 颗卫星，但对象结构合法）
            final GnssStatus empty = sEmptyStatus;
            if (empty != null) {
                return empty;
            }
            if (sEmptyStatusFailed) {
                return null;
            }
            final GnssStatus created = buildEmptyStatus();
            if (created != null) {
                sEmptyStatus = created;
                return created;
            }
            sEmptyStatusFailed = true;
            logStatusUnavailableOnce();
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 用 {@code android.location.GnssStatus$Builder}（API 30+）反射构造一个“真对象”：它的内部卫星
     * 数据与快照完全一致，因此 getSatelliteCount / writeToParcel / equals 都自洽，不依赖 getter hook。
     * Builder 在部分 ROM / App 进程里可能因隐藏 API 限制不可用 → 返回 null，走后面的兜底。
     */
    private static GnssStatus buildStatusFromSnapshot(List<Satellite> satellites) {
        if (satellites == null || satellites.isEmpty()
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null;
        }
        final GnssStatus cached = sBuiltStatus;
        if (cached != null && sBuiltFor == satellites) {
            return cached;
        }
        try {
            final Class<?> builderClass = Class.forName("android.location.GnssStatus$Builder");
            final Constructor<?> constructor = builderClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            final Object builder = constructor.newInstance();
            final Method addSatellite = builderClass.getMethod("addSatellite",
                    int.class, int.class, float.class, float.class, float.class,
                    boolean.class, boolean.class, boolean.class,
                    boolean.class, float.class, boolean.class, float.class);
            final Method build = builderClass.getMethod("build");
            for (int i = 0; i < satellites.size(); i++) {
                final Satellite satellite = satellites.get(i);
                addSatellite.invoke(builder,
                        Integer.valueOf(satellite.constellation),
                        Integer.valueOf(satellite.svid),
                        Float.valueOf(satellite.cn0),
                        Float.valueOf(satellite.elevation),
                        Float.valueOf(satellite.azimuth),
                        Boolean.valueOf(satellite.hasEphemeris),
                        Boolean.valueOf(satellite.hasAlmanac),
                        Boolean.valueOf(satellite.usedInFix),
                        Boolean.valueOf(satellite.hasCarrierFrequency),
                        Float.valueOf(satellite.carrierFrequencyHz),
                        Boolean.TRUE,
                        Float.valueOf(satellite.basebandCn0));
            }
            final Object status = build.invoke(builder);
            if (status instanceof GnssStatus) {
                sBuiltFor = satellites;
                sBuiltStatus = (GnssStatus) status;
                logBuiltOnce(satellites.size());
                return (GnssStatus) status;
            }
        } catch (Throwable t) {
            logBuilderFailOnce(t);
        }
        return null;
    }

    /**
     * 反射构造一个“0 颗卫星”的真实 GnssStatus：
     * <ul>
     *   <li>Android 8.0 ~ 13：包私有构造器 {@code GnssStatus(GnssSatelliteStatus[])} /
     *       {@code GnssStatus(List<GnssSatelliteStatus>)}，传空数组 / 空 List 即可；</li>
     *   <li>Android 14+：包私有构造器 {@code GnssStatus()}；</li>
     *   <li>都失败时用 {@code sun.misc.Unsafe.allocateInstance} 兜底（getter 已被 hook，只要求对象非 null）。</li>
     * </ul>
     */
    private static GnssStatus buildEmptyStatus() {
        try {
            final Constructor<?>[] constructors = GnssStatus.class.getDeclaredConstructors();
            // 单参数构造器（数组 / List）
            for (int i = 0; i < constructors.length; i++) {
                final Constructor<?> constructor = constructors[i];
                final Class<?>[] parameterTypes = constructor.getParameterTypes();
                if (parameterTypes.length != 1) {
                    continue;
                }
                final Object argument = emptyArgument(parameterTypes[0]);
                if (argument == null) {
                    continue;
                }
                try {
                    constructor.setAccessible(true);
                    final Object instance = constructor.newInstance(argument);
                    if (instance instanceof GnssStatus) {
                        return (GnssStatus) instance;
                    }
                } catch (Throwable ignored) {
                    // 换下一个构造器
                }
            }
            // 无参构造器
            for (int i = 0; i < constructors.length; i++) {
                final Constructor<?> constructor = constructors[i];
                if (constructor.getParameterTypes().length != 0) {
                    continue;
                }
                try {
                    constructor.setAccessible(true);
                    final Object instance = constructor.newInstance();
                    if (instance instanceof GnssStatus) {
                        return (GnssStatus) instance;
                    }
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        } catch (Throwable ignored) {
            // 落到 Unsafe
        }
        return unsafeStatus();
    }

    /** 为构造器参数类型造一个“空”实例：List → 空 ArrayList；数组 → 长度 0 的数组。 */
    private static Object emptyArgument(Class<?> type) {
        try {
            if (List.class.isAssignableFrom(type)) {
                return new ArrayList<Object>(0);
            }
            if (type.isArray()) {
                return Array.newInstance(type.getComponentType(), 0);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    private static GnssStatus unsafeStatus() {
        try {
            final Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            final Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            final Object unsafe = field.get(null);
            if (unsafe == null) {
                return null;
            }
            final Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            final Object instance = allocateInstance.invoke(unsafe, GnssStatus.class);
            if (instance instanceof GnssStatus) {
                return (GnssStatus) instance;
            }
        } catch (Throwable t) {
            log("sun.misc.Unsafe GnssStatus fallback failed: " + t);
        }
        return null;
    }

    // ==================================================================
    // callback 包装器
    // ==================================================================

    /**
     * 包目标 App 的 {@code GnssStatus.Callback}：回放期间吞掉系统真实状态回调，改由 1Hz 合成线程
     * 投递假卫星；非回放期间原样透传。onStarted / onStopped / onFirstFix 始终透传。
     */
    private static final class CallbackWrapper extends GnssStatus.Callback {

        private final WeakReference<GnssStatus.Callback> target;

        CallbackWrapper(GnssStatus.Callback target) {
            this.target = new WeakReference<>(target);
        }

        @Override
        public void onStarted() {
            final GnssStatus.Callback callback = target.get();
            if (callback == null) {
                return;
            }
            try {
                callback.onStarted();
            } catch (Throwable ignored) {
                // 目标 App 自己的回调异常：吞掉，不能影响系统回调线程
            }
        }

        @Override
        public void onStopped() {
            final GnssStatus.Callback callback = target.get();
            if (callback == null) {
                return;
            }
            try {
                callback.onStopped();
            } catch (Throwable ignored) {
                // ignore
            }
        }

        @Override
        public void onFirstFix(int ttffMillis) {
            final GnssStatus.Callback callback = target.get();
            if (callback == null) {
                return;
            }
            try {
                callback.onFirstFix(ttffMillis);
            } catch (Throwable ignored) {
                // ignore
            }
        }

        @Override
        public void onSatelliteStatusChanged(GnssStatus status) {
            try {
                if (status != null) {
                    sLatestRealStatus = status; // 捕获真实实例，Builder 不可用时拿它回传（getter 已 hook）
                }
                final GnssStatus.Callback callback = target.get();
                if (callback == null) {
                    return;
                }
                final List<Satellite> satellites = activeSatellites();
                if (satellites != null && obtainStatus(satellites) != null) {
                    // 回放中：吞掉真实状态，由 1Hz 合成线程投递与快照一致的假卫星
                    return;
                }
                callback.onSatelliteStatusChanged(status);
            } catch (Throwable ignored) {
                // ignore
            }
        }

        /** 1Hz 合成投递入口（已按注册时的 Handler / Executor 投递到正确线程）。 */
        void deliverSynthetic(GnssStatus status) {
            final GnssStatus.Callback callback = target.get();
            if (callback == null) {
                return;
            }
            try {
                if (activeSatellites() == null) {
                    return; // 投递前回放已结束：丢弃这一个合成状态，真实回调会继续透传
                }
                callback.onSatelliteStatusChanged(status);
            } catch (Throwable ignored) {
                // 目标 App 自己的回调异常：吞掉
            }
        }
    }

    /** 一次成功注册的记录。target 用弱引用，App 丢掉 callback 后自动失效。 */
    private static final class Registration {
        final LocationManager manager;
        final WeakReference<GnssStatus.Callback> target;
        final CallbackWrapper wrapper;
        final Handler handler;
        final Executor executor;

        Registration(LocationManager manager, GnssStatus.Callback target, CallbackWrapper wrapper,
                     Handler handler, Executor executor) {
            this.manager = manager;
            this.target = new WeakReference<>(target);
            this.wrapper = wrapper;
            this.handler = handler;
            this.executor = executor;
        }
    }

    // ==================================================================
    // 字段 / 返回值类型标记
    // ==================================================================

    private static final int KIND_COUNT = -2;
    private static final int KIND_INT = 0;
    private static final int KIND_FLOAT = 1;
    private static final int KIND_BOOL = 2;

    private static final int FIELD_NONE = -1;
    private static final int FIELD_SVID = 0;
    private static final int FIELD_CONSTELLATION = 1;
    private static final int FIELD_ELEVATION = 2;
    private static final int FIELD_AZIMUTH = 3;
    private static final int FIELD_CN0 = 4;
    private static final int FIELD_USED_IN_FIX = 5;
    private static final int FIELD_HAS_ALMANAC = 6;
    private static final int FIELD_HAS_EPHEMERIS = 7;
    private static final int FIELD_CARRIER_FREQUENCY = 8;
    private static final int FIELD_BASEBAND_CN0 = 9;
    private static final int FIELD_HAS_CARRIER_FREQUENCY = 10;
    private static final int FIELD_HAS_BASEBAND_CN0 = 11;

    // ==================================================================
    // 日志
    // ==================================================================

    private static void logRegisterOnce(Registration registration) {
        if (sLoggedRegister) {
            return;
        }
        sLoggedRegister = true;
        log("gnss callback wrapped: delivery="
                + (registration.executor != null ? "executor"
                        : registration.handler != null ? "handler" : "main"));
    }

    private static void logActiveOnce(int satelliteCount) {
        if (sLoggedActive) {
            return;
        }
        sLoggedActive = true;
        log("gnss replay active: satellites=" + satelliteCount + ", rate=1Hz");
    }

    private static void logUnregisterOnce(int count) {
        if (sLoggedUnregister) {
            return;
        }
        sLoggedUnregister = true;
        log("gnss wrapper unregistered (" + count + " registration(s))");
    }

    private static void logBuiltOnce(int count) {
        if (sLoggedBuilt) {
            return;
        }
        sLoggedBuilt = true;
        log("gnss status built via GnssStatus$Builder: satellites=" + count);
    }

    private static void logBuilderFailOnce(Throwable t) {
        if (sLoggedBuilderFail) {
            return;
        }
        sLoggedBuilderFail = true;
        log("GnssStatus$Builder unavailable (fallback to captured/empty status): " + t);
    }

    private static void logStatusUnavailableOnce() {
        if (sLoggedStatusUnavailable) {
            return;
        }
        sLoggedStatusUnavailable = true;
        log("no GnssStatus instance available (Builder / reflect ctor / Unsafe all failed),"
                + " only getter hooks active");
    }

    private static void log(String message) {
        try {
            XposedBridge.log(TAG + message);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
