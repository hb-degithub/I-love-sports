package com.vloc.motion.hidden;

import android.content.Context;
import android.location.GnssStatus;
import android.location.Location;
import android.net.Uri;
import android.os.Binder;
import android.os.IInterface;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 系统级注入：在 <b>system_server</b>（LSPosed 作用域「系统框架 / android」）内改写定位与 GNSS。
 *
 * <p>与进程内注入的区别：
 * <ul>
 *   <li>默认所有 App 都生效，不需要逐个勾选作用域；</li>
 *   <li>Location 由我们自己 new 出来，<b>不带 mock 标记</b>，所以 isMock/isFromMockProvider
 *       天然为 false，也不再需要 appops 授权；</li>
 *   <li>数据来源（<b>套接字优先</b>）：主 App 的 Unix 抽象套接字
 *       {@code com.vloc.motion.snapshot}（{@link SnapshotClient}），请求 {@code LOC} / {@code GNSS}；
 *       失败才回退 ContentProvider {@code content://com.vloc.motion.env/location}（定位）与
 *       {@code /gnss}（卫星）——Android 17 的 system_server（uid 1000）读 Provider 会抛
 *       {@code SecurityException}（{@code Isolated process not allowed to call getContentProvider}），
 *       所以 Provider 只作为老版本主 App 的兜底。</li>
 * </ul>
 *
 * <p><b>作用域（目标应用）</b>：主 App 开启「仅对选定应用生效」后，系统层也不再无差别改写：
 * <ul>
 *   <li>投递链：注册时把 <b>transport / listener binder → 接收方身份（包名 + uid）</b>记进表，
 *       投递时从 {@code param.thisObject} 反查接收方，不在名单就不改写
 *       （见 {@link #REGISTRATION_HOOK}、{@link #LOCATION_HOOK}）；</li>
 *   <li>同步方法（{@code getLastLocation}）：用 {@code Binder.getCallingUid()} 反查调用方包名；</li>
 *   <li><b>系统/框架身份不按名单过滤</b>：接收方是 {@code android}、
 *       {@code com.android.location.fused}、{@code com.android.providers.*}，或 uid 为
 *       {@link Process#SYSTEM_UID} 时，system_server 侧看到的不是最终 App（本 ROM 的定位经
 *       {@code com.android.location.fused} 中转），按名单过滤必然全挡，因此<b>退化为全局生效</b>
 *       并每进程记一条日志；</li>
 *   <li>查不到接收方 → 按契约“保持现状”（全局生效）并每个进程记一次日志；</li>
 *   <li>GNSS 卫星表（{@code onReportSvStatus}）没有接收方信息，<b>保持全局生效</b>。</li>
 * </ul>
 *
 * <p>计步 / IMU / 摄像头<b>不在此处</b>：传感器事件由 native SensorService 分发、
 * 相机在 HAL/相机服务层，Java 层拦不到系统级，仍然走目标进程内 Hook。
 *
 * <p>安全约束：所有 hook 独立 try/catch；套接字 / Provider 都读不到、或未在模拟时一律透传真实定位，
 * 绝不阻断系统服务（套接字总超时 ≤300ms、带缓存，见 {@link #currentLocation()}）。
 */
final class SystemHooks {

    private static final String TAG = "[VLocHidden] ";

    private static final String LOCATION_URI = "content://com.vloc.motion.env/location";
    private static final String GNSS_URI = "content://com.vloc.motion.env/gnss";

    /** 定位快照缓存：异常走套接字 / Provider 有 IPC 开销，缓存 120ms 足够跟上 10Hz 注入。 */
    private static final long LOCATION_CACHE_MS = 120L;
    private static final long GNSS_CACHE_MS = 900L;

    private static volatile Context sContext;
    private static volatile long sLocationAt;
    private static volatile Snapshot sLocation;
    private static volatile long sGnssAt;
    private static volatile List<Satellite> sSatellites;

    private static volatile int sHookCount;
    private static volatile boolean sLoggedFirstReplace;
    private static volatile boolean sLoggedFirstGnss;

    // ================================================================== 作用域（目标应用）

    /**
     * Android 17（SDK 37）的 Provider 管理类。它的嵌套类里：
     * {@code Registration} / {@code LocationListenerRegistration} / {@code LocationPendingIntentRegistration} /
     * {@code GetCurrentLocationListenerRegistration} / {@code LocationTransport} / {@code LocationListenerTransport}。
     */
    private static final String LOCATION_PROVIDER_MANAGER =
            "com.android.server.location.provider.LocationProviderManager";

    /** 调用方身份（包名从这里取：system_server 侧算出来的，客户端伪造不了）。 */
    private static final String CALLER_IDENTITY_CLASS = "android.location.util.identity.CallerIdentity";

    /** 应用注册的定位回调（AIDL，@hide，只能按类名/反射判定，不能直接 import）。 */
    private static final String LOCATION_LISTENER_CLASS = "android.location.ILocationListener";

    /** d2.txt 里核实过的注册类（{@code LocationProviderManager} 的嵌套类）。 */
    private static final String[] REGISTRATION_CLASS_NAMES = {
            "Registration",
            "LocationListenerRegistration",
            "LocationPendingIntentRegistration",
            "GetCurrentLocationListenerRegistration",
    };

    /**
     * 投递目标 transport 实例 → 接收方身份（包名 + uid）：<b>注册时写入，投递时查表</b>。
     * 用弱键，注册被移除后自然回收，不会在 system_server 里长期堆积。
     */
    private static final Map<Object, Target> sTransportTargets =
            Collections.synchronizedMap(new WeakHashMap<Object, Target>());

    /** ILocationListener binder → 接收方身份（transport 查不到时的兜底路径）。 */
    private static final Map<Object, Target> sBinderTargets =
            Collections.synchronizedMap(new WeakHashMap<Object, Target>());

    private static volatile Class<?> sManagerClass;
    private static volatile Class<?> sIdentityClass;
    private static volatile Class<?> sTransportClass;
    private static volatile Class<?> sListenerClass;

    /** 字段缺失的占位（避免对没有 mListener 的 transport 反复 getDeclaredField 抛异常）。 */
    private static final Object NO_LISTENER_FIELD = new Object();

    /** 每个 transport 类的 {@code mListener} 字段缓存（dexdump: private final ILocationListener）。 */
    private static final Map<Class<?>, Object> sListenerFields =
            Collections.synchronizedMap(new WeakHashMap<Class<?>, Object>());

    private static volatile boolean sLoggedFirstScopeSkip;
    private static volatile boolean sLoggedFirstCallerSkip;
    /** 每进程只记一次：系统/框架身份无法按应用隔离，已降级为全局生效。 */
    private static volatile boolean sLoggedSystemIdentity;

    private static final String[] EMPTY_PACKAGES = new String[0];

    private SystemHooks() {
    }

    /** 主 App 推来的一帧定位。 */
    private static final class Snapshot {
        final double lat;
        final double lon;
        final double alt;
        final float speed;
        final float bearing;
        final float accuracy;
        final int satellites;
        final long updatedAt;

        Snapshot(double lat, double lon, double alt, float speed, float bearing,
                 float accuracy, int satellites, long updatedAt) {
            this.lat = lat;
            this.lon = lon;
            this.alt = alt;
            this.speed = speed;
            this.bearing = bearing;
            this.accuracy = accuracy;
            this.satellites = satellites;
            this.updatedAt = updatedAt;
        }
    }

    /** 一颗卫星（与主 App gnss 数组字段一致）。 */
    private static final class Satellite {
        int svid;
        int constellation;
        float elevation;
        float azimuth;
        float cn0;
        float carrierHz;
        boolean usedInFix;
        boolean hasAlmanac;
        boolean hasEphemeris;
    }

    /**
     * 注册时记下的接收方身份：包名 + uid（任意一项解析不到时为 {@code null} / {@code -1}）。
     *
     * <p>为什么连 uid 一起记：部分 ROM 的定位投递经 {@code com.android.location.fused} 中转，
     * {@code CallerIdentity} 里只有系统身份，而 uid 在注册那一刻就能拿到，投递时不必再反查。
     */
    private static final class Target {
        /** 反查到的包名；null = 解析不到。 */
        final String pkg;
        /** 反查到的 uid；-1 = 解析不到。 */
        final int uid;

        Target(String pkg, int uid) {
            this.pkg = pkg;
            this.uid = uid;
        }

        /**
         * 系统/框架身份：system_server 侧看到的不是最终接收 App，按名单过滤必然全挡，
         * 只能退化为全局生效（见类注释「作用域」）。
         */
        boolean isSystem() {
            return uid == Process.SYSTEM_UID || isSystemPackage(pkg);
        }

        /** 日志用：包名优先，其次 uid。 */
        String describe() {
            if (pkg != null) {
                return pkg;
            }
            return uid > 0 ? "uid " + uid : "<unknown>";
        }
    }

    /** 反查不到接收方时的占位：包名 null + uid -1 → 交给 TargetScope 的“解析不到 → 放行”契约。 */
    private static final Target UNKNOWN_TARGET = new Target(null, -1);

    /**
     * 系统/框架包名：{@code android}、{@code com.android.location.fused}、
     * {@code com.android.providers.*}。
     *
     * <p>这些包在 system_server 侧代表不了最终 App（本 ROM 的定位投递经 fused 中转，
     * 反查到的身份是 {@code android}），因此不能按用户名单过滤。
     */
    private static boolean isSystemPackage(String pkg) {
        return pkg != null && ("android".equals(pkg)
                || "com.android.location.fused".equals(pkg)
                || pkg.startsWith("com.android.providers."));
    }

    // ================================================================== 安装

    static int install(ClassLoader classLoader) {
        int hooked = 0;
        // 先建「注册者 → 接收方身份（包名 + uid）」映射，投递 hook 才能查到接收方
        hooked += hookRegistrationTargets(classLoader);
        hooked += hookLocationDelivery(classLoader);
        hooked += hookLastLocation(classLoader);
        hooked += hookGnssDelivery(classLoader);
        sHookCount = hooked;
        log("system hooks=" + hooked + " (system_server 级定位/GNSS 注入)");
        log("target scope: " + TargetScope.summary());
        // GNSS 卫星表没有“接收方”信息：按契约保持全局生效，这里说明一次
        log("gnss sv status: no target pkg info -> always applies globally");
        report(hooked);
        return hooked;
    }

    // ================================================================== 作用域：注册时记录接收方

    /**
     * 注册时记录「投递目标 → 接收方包名」，供投递时判定这一次到底该不该改写。
     *
     * <p>Android 17（SDK 37）真机 dexdump（services.jar/classes2.dex，
     * {@code .toolchain/tmp/verify/a17/d2.txt}）里核实到的注册链：
     * <ul>
     *   <li>{@code LocationProviderManager$Registration.<init>(LocationProviderManager,
     *       LocationRequest, CallerIdentity, Executor, LocationTransport, int)} —— d2.txt:40929a；
     *       三个具体注册类都先调它，<b>一条 hook 覆盖全部注册方式</b>；</li>
     *   <li>{@code LocationListenerRegistration.<init>(..., LocationRequest, CallerIdentity,
     *       LocationListenerTransport, int)} —— d2.txt:40925c；</li>
     *   <li>{@code LocationPendingIntentRegistration.<init>(..., CallerIdentity,
     *       LocationPendingIntentTransport, int)} —— d2.txt:40e54e；</li>
     *   <li>{@code GetCurrentLocationListenerRegistration.<init>(..., CallerIdentity,
     *       LocationTransport, int)} —— d2.txt:408bd0；</li>
     *   <li>{@code LocationProviderManager.registerLocationRequest(LocationRequest, CallerIdentity,
     *       int, ILocationListener)} —— d2.txt:40e568（transport 在这里现建）。</li>
     * </ul>
     *
     * <p>因此这里<b>不写死方法签名</b>，按「构造参数里同时出现 CallerIdentity 与 LocationTransport」
     * 的形状挂载；将来签名再变也只是退化成“查不到 → 放行”，不会让系统服务出错。
     */
    private static int hookRegistrationTargets(ClassLoader classLoader) {
        try {
            sManagerClass = XposedHelpers.findClass(LOCATION_PROVIDER_MANAGER, classLoader);
            sIdentityClass = XposedHelpers.findClass(CALLER_IDENTITY_CLASS, classLoader);
            sTransportClass = XposedHelpers.findClass(
                    LOCATION_PROVIDER_MANAGER + "$LocationTransport", classLoader);
            sListenerClass = XposedHelpers.findClassIfExists(LOCATION_LISTENER_CLASS, classLoader);
        } catch (Throwable t) {
            log("scope: registration map unavailable (" + t + "), delivery applies globally");
            return 0;
        }

        int hooked = 0;
        // 先按 d2.txt 里核实过的名字显式找一遍（嵌套类列表拿不到时也不会全军覆没），
        // 再扫一遍 LocationProviderManager 的全部嵌套类兜住 OEM 改名。
        final Set<Class<?>> candidates = new LinkedHashSet<>();
        for (String simpleName : REGISTRATION_CLASS_NAMES) {
            try {
                candidates.add(XposedHelpers.findClass(
                        LOCATION_PROVIDER_MANAGER + "$" + simpleName, classLoader));
            } catch (Throwable ignored) {
                // 个别类在 OEM ROM 上不存在属正常
            }
        }
        candidates.add(sManagerClass);
        try {
            Collections.addAll(candidates, sManagerClass.getDeclaredClasses());
        } catch (Throwable ignored) {
            // 嵌套类列表拿不到也不影响上面显式找到的那些
        }
        for (Class<?> clazz : candidates) {
            if (clazz == null) {
                continue;
            }
            final String simpleName;
            try {
                simpleName = clazz.getSimpleName();
            } catch (Throwable t) {
                continue;
            }
            if (simpleName == null || !simpleName.endsWith("Registration")) {
                continue;
            }
            for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                if (!hasParameter(constructor.getParameterTypes(), sIdentityClass)) {
                    continue;
                }
                if (!hasParameter(constructor.getParameterTypes(), sTransportClass)) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(constructor, REGISTRATION_HOOK);
                    hooked++;
                } catch (Throwable t) {
                    log("scope: hook " + simpleName + ".<init> failed: " + t);
                }
            }
        }

        // 兜底：注册方法上同时有 CallerIdentity 与 ILocationListener → 记 binder → 包名
        if (sListenerClass != null) {
            for (Method method : sManagerClass.getDeclaredMethods()) {
                if (!"registerLocationRequest".equals(method.getName())) {
                    continue;
                }
                if (!hasParameter(method.getParameterTypes(), sIdentityClass)) {
                    continue;
                }
                if (!hasParameter(method.getParameterTypes(), sListenerClass)) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(method, LISTENER_REGISTER_HOOK);
                    hooked++;
                } catch (Throwable t) {
                    log("scope: hook registerLocationRequest failed: " + t);
                }
            }
        }

        log("scope: registration hooks=" + hooked + " (transport -> package map)");
        return hooked;
    }

    private static boolean hasParameter(Class<?>[] types, Class<?> target) {
        if (types == null || target == null) {
            return false;
        }
        for (Class<?> type : types) {
            if (target.equals(type)) {
                return true;
            }
        }
        return false;
    }

    /** 注册时：从参数里认出 CallerIdentity + LocationTransport，绑定 transport / binder → 接收方身份。 */
    private static final XC_MethodHook REGISTRATION_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                final Object[] args = param.args;
                if (args == null) {
                    return;
                }
                Object identity = null;
                Object transport = null;
                for (Object arg : args) {
                    if (arg == null) {
                        continue;
                    }
                    if (identity == null && sIdentityClass != null && sIdentityClass.isInstance(arg)) {
                        identity = arg;
                    } else if (transport == null && sTransportClass != null
                            && sTransportClass.isInstance(arg)) {
                        transport = arg;
                    }
                }
                if (identity == null || transport == null) {
                    return;
                }
                final Target target = targetOf(identity);
                if (target == null) {
                    return;
                }
                sTransportTargets.put(transport, target);
                final Object binder = binderOf(listenerOf(transport));
                if (binder != null) {
                    sBinderTargets.put(binder, target);
                }
            } catch (Throwable ignored) {
                // 绝不能因为这里异常影响系统注册流程
            }
        }
    };

    /** 兜底：{@code registerLocationRequest(Request, CallerIdentity, int, ILocationListener)}。 */
    private static final XC_MethodHook LISTENER_REGISTER_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                final Object[] args = param.args;
                if (args == null) {
                    return;
                }
                Object identity = null;
                Object listener = null;
                for (Object arg : args) {
                    if (arg == null) {
                        continue;
                    }
                    if (identity == null && sIdentityClass != null && sIdentityClass.isInstance(arg)) {
                        identity = arg;
                    } else if (listener == null && sListenerClass != null
                            && sListenerClass.isInstance(arg)) {
                        listener = arg;
                    }
                }
                if (identity == null || listener == null) {
                    return;
                }
                final Target target = targetOf(identity);
                if (target == null) {
                    return;
                }
                final Object binder = binderOf(listener);
                if (binder != null) {
                    sBinderTargets.put(binder, target);
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
    };

    /** CallerIdentity → 接收方身份；包名和 uid 都拿不到时返回 null（投递时按“解析不到”放行）。 */
    private static Target targetOf(Object identity) {
        final String pkg = packageOf(identity);
        final int uid = uidOf(identity);
        return pkg == null && uid < 0 ? null : new Target(pkg, uid);
    }

    /** CallerIdentity#getPackageName()（d2.txt:40e5d4 实测调用点）。 */
    private static String packageOf(Object identity) {
        try {
            final Object value = XposedHelpers.callMethod(identity, "getPackageName");
            if (value instanceof String) {
                final String pkg = (String) value;
                return pkg.isEmpty() ? null : pkg;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    /** CallerIdentity#getUid()（@SystemApi）；解析不到返回 -1。 */
    private static int uidOf(Object identity) {
        try {
            final Object value = XposedHelpers.callMethod(identity, "getUid");
            if (value instanceof Integer) {
                return (Integer) value;
            }
        } catch (Throwable ignored) {
            // 老版本 / 方法改名：uid 未知，退回按包名判定
        }
        return -1;
    }

    /**
     * ILocationListener（AIDL）→ binder。
     *
     * <p>所有 AIDL 接口都继承 {@code android.os.IInterface}（公开 API，可安全强转），
     * 所以这里不用反射调 {@code asBinder()}，避免 Xposed 在接口层找不到方法的兼容问题。
     */
    private static Object binderOf(Object listener) {
        if (!(listener instanceof IInterface)) {
            return null;
        }
        try {
            return ((IInterface) listener).asBinder();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 反射取 transport 的 {@code mListener} 字段；字段缺失/类型不符返回 null（按类缓存，避免热路径反复反射）。 */
    private static Object listenerOf(Object transport) {
        if (transport == null) {
            return null;
        }
        try {
            final Class<?> clazz = transport.getClass();
            Object cached = sListenerFields.get(clazz);
            if (cached == null) {
                try {
                    final Field field = clazz.getDeclaredField("mListener");
                    field.setAccessible(true);
                    cached = field;
                } catch (Throwable noField) {
                    cached = NO_LISTENER_FIELD;
                }
                sListenerFields.put(clazz, cached);
            }
            if (cached == NO_LISTENER_FIELD) {
                return null;
            }
            return ((Field) cached).get(transport);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 投递侧反查接收方身份；查不到返回 {@link #UNKNOWN_TARGET}
     * （调用方按 TargetScope 的契约“解析不到 → 放行”处理）。
     */
    private static Target resolveTarget(Object transport) {
        if (transport == null) {
            return UNKNOWN_TARGET;
        }
        try {
            final Target direct = sTransportTargets.get(transport);
            if (direct != null) {
                return direct;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        final Object binder = binderOf(listenerOf(transport));
        if (binder == null) {
            return UNKNOWN_TARGET;
        }
        try {
            final Target target = sBinderTargets.get(binder);
            return target != null ? target : UNKNOWN_TARGET;
        } catch (Throwable ignored) {
            return UNKNOWN_TARGET;
        }
    }

    /**
     * 调用方（{@code Binder.getCallingUid()}）若是系统/框架身份，返回用于日志的包名；
     * 能确定是第三方应用时返回 null。
     *
     * <p>uid 1000 = system_server / {@code android} / {@code com.android.location.fused} /
     * {@code com.android.providers.*}：这些身份代表不了最终 App，按名单过滤必然误杀。
     */
    private static String systemCallerPackage() {
        final int uid;
        try {
            uid = Binder.getCallingUid();
        } catch (Throwable ignored) {
            return "unknown uid";   // 非 binder 线程：TargetScope 本来也会放行，这里兜底
        }
        if (uid <= 0) {
            return "unknown uid";
        }
        if (uid == Process.SYSTEM_UID) {
            return "android";
        }
        for (String pkg : packagesForUid(uid)) {
            if (isSystemPackage(pkg)) {
                return pkg;
            }
        }
        return null;
    }

    /**
     * uid → 包名。system_server 里 {@code Context#getPackageManager()} 背后的 IPackageManager
     * 是进程内对象（不走 binder），这里用公开 API 避免反射；只在名单未命中时调用一次。
     */
    private static String[] packagesForUid(int uid) {
        try {
            final Context context = systemContext();
            if (context != null) {
                final String[] packages = context.getPackageManager().getPackagesForUid(uid);
                if (packages != null) {
                    return packages;
                }
            }
        } catch (Throwable ignored) {
            // 解析失败：按“不是系统身份”处理，仍交给 TargetScope 的名单判定
        }
        return EMPTY_PACKAGES;
    }

    /** 每进程只记一次：系统/框架身份无法按应用隔离，已降级为全局生效。 */
    private static void logSystemIdentityOnce(String pkg) {
        if (sLoggedSystemIdentity) {
            return;
        }
        sLoggedSystemIdentity = true;
        log("target scope: system caller (" + pkg + "), applying globally "
                + "(ROM routes location via fused)");
    }

    // ================================================================== 诊断日志（每进程一次）

    /*
     * 「hook 没触发」与「触发了但读不到快照」是两种完全不同的故障：
     * 前者查候选类 / 方法名（见 hookCandidates 的逐类日志），后者查主 App 的 Provider。
     * 每条日志各自一个 AtomicBoolean，CAS 保证并发投递下每进程也只打一条，绝不刷屏。
     */
    private static final AtomicBoolean sLoggedDeliveryFired = new AtomicBoolean();
    private static final AtomicBoolean sLoggedLastLocationFired = new AtomicBoolean();
    private static final AtomicBoolean sLoggedNoSnapshot = new AtomicBoolean();
    private static final AtomicBoolean sLoggedLastLocationNoSnapshot = new AtomicBoolean();
    private static final AtomicBoolean sLoggedSnapshotUnavailable = new AtomicBoolean();

    /** 日志用「方法名 args=参数个数」；任何异常都退化成占位，绝不影响 hook。 */
    private static String describeCall(XC_MethodHook.MethodHookParam param) {
        String name = "?";
        int args = 0;
        try {
            if (param != null) {
                if (param.method instanceof Method) {
                    name = ((Method) param.method).getName();
                }
                if (param.args != null) {
                    args = param.args.length;
                }
            }
        } catch (Throwable ignored) {
            // 日志用，失败退化为占位
        }
        return name + " args=" + args;
    }

    /** 投递 hook 首次被调用：每进程一行；{@code path} 非空时用来区分同步兜底路径。 */
    private static void logHookFiredOnce(AtomicBoolean flag, XC_MethodHook.MethodHookParam param,
                                         String path) {
        try {
            if (flag.compareAndSet(false, true)) {
                log("delivery hook fired: " + describeCall(param)
                        + (path == null ? "" : " (" + path + ")"));
            }
        } catch (Throwable ignored) {
            // 日志绝不抛出
        }
    }

    /** 快照为空（主 App 未发布 / Provider 不可用）：每进程一行。 */
    private static void logNoSnapshotOnce(AtomicBoolean flag, String path) {
        try {
            if (flag.compareAndSet(false, true)) {
                log("delivery skipped: no snapshot (app not publishing / provider unavailable)"
                        + (path == null ? "" : " (" + path + ")"));
            }
        } catch (Throwable ignored) {
            // 日志绝不抛出
        }
    }

    /** Provider 读取异常 / 空游标：每进程一行，区分「应用没发布」与「Provider 读失败」。 */
    private static void logSnapshotUnavailableOnce(String reason) {
        try {
            if (sLoggedSnapshotUnavailable.compareAndSet(false, true)) {
                log("snapshot unavailable: " + reason);
            }
        } catch (Throwable ignored) {
            // 日志绝不抛出
        }
    }

    // ================================================================== 定位下发

    /**
     * 定位投递方法名：Android 7~13 为 {@code onLocationChanged(Location)}；
     * Android 17（SDK 37，真机 dexdump 实测）为 {@code deliverOnLocationChanged(LocationResult, IRemoteCallback)}。
     */
    private static final List<String> LOCATION_DELIVERY_METHODS =
            Arrays.asList("onLocationChanged", "deliverOnLocationChanged");

    /**
     * 候选类：旧版本在前（互相独立，全部尝试），Android 17 的 Transport 在后。
     *
     * <p>SDK 37 真机 dexdump（services.jar/classes2.dex）确认：
     * {@code com.android.server.location.LocationListenerTransport}、
     * {@code LocationManagerService$LocationListenerTransport}、
     * {@code GnssStatusListenerTransport} 均<b>已不存在</b>，投递入口搬到了
     * {@code com.android.server.location.{子包}.LocationProviderManager} 的内部 Transport 类。
     */
    private static final String[] LOCATION_DELIVERY_CLASSES = {
            "com.android.server.location.LocationListenerTransport",
            "com.android.server.location.LocationManagerService$LocationListenerTransport",
            "com.android.server.location.provider.LocationProviderManager$LocationRegistration",
            "com.android.server.location.provider.LocationProviderManager$LocationRegistration$1",
            // Android 17（SDK 37）：三个 Transport 各自 deliverOnLocationChanged(LocationResult, IRemoteCallback)
            "com.android.server.location.provider.LocationProviderManager$LocationListenerTransport",
            "com.android.server.location.provider.LocationProviderManager$LocationPendingIntentTransport",
            "com.android.server.location.provider.LocationProviderManager$GetCurrentLocationTransport"
    };

    private static int hookLocationDelivery(ClassLoader classLoader) {
        final int count = hookCandidates(classLoader, LOCATION_DELIVERY_CLASSES,
                LOCATION_DELIVERY_METHODS, LOCATION_HOOK, "location", false);
        if (count == 0) {
            log("no location transport class found (版本差异)，改用 getLastLocation 兜底");
        }
        return count;
    }

    /**
     * 在候选类上挂载「方法名命中」的 hook，逐类记一行日志（成功几个 / 类不存在也记）。
     *
     * <p>每行格式：{@code hook candidate <fqcn>: found=<bool> hooked=<n> (methods=[...]) (失败原因)}，
     * 装完直接能看出某个方法（如 {@code deliverOnLocationChanged}）到底挂上了几处。
     *
     * @param stopOnFirst 命中一个类就停（旧版本优先，避免同一条投递链被挂两层）
     * @return 实际挂上的方法数
     */
    private static int hookCandidates(ClassLoader classLoader, String[] classes, List<String> methodNames,
                                      XC_MethodHook hook, String tag, boolean stopOnFirst) {
        int total = 0;
        for (String name : classes) {
            int hooked = 0;
            int matched = 0;
            boolean found = false;
            String reason = null;
            final List<String> matchedNames = new ArrayList<>();
            try {
                final Class<?> clazz = XposedHelpers.findClass(name, classLoader);
                found = true;
                // 逐个重载挂：既兼容 (Location)，也兼容 (LocationResult)
                for (Method method : clazz.getDeclaredMethods()) {
                    if (!methodNames.contains(method.getName())) {
                        continue;
                    }
                    matched++;
                    matchedNames.add(method.getName());
                    try {
                        XposedBridge.hookMethod(method, hook);
                        hooked++;
                    } catch (Throwable t) {
                        if (reason == null) {
                            reason = tag + " hook failed " + method.getName() + ": " + t;
                        }
                    }
                }
            } catch (Throwable notFound) {
                reason = tag + " class not found: " + notFound;
            }
            if (reason == null && matched == 0) {
                reason = tag + " no method match " + methodNames;
            }
            // 每个候选类固定一行：found=类是否解析到，hooked=真正挂上的方法数，
            // 这样能直接看出 deliverOnLocationChanged 到底挂上了几个。
            log("hook candidate " + name + ": found=" + found + " hooked=" + hooked
                    + (matched > 0 ? " (methods=" + matchedNames + ")" : "")
                    + (reason == null ? "" : " (" + reason + ")"));
            total += hooked;
            if (stopOnFirst && hooked > 0) {
                break;
            }
        }
        return total;
    }

    // ============================================================== 替换诊断（每进程每类一条）

    /*
     * 替换有没有生效，日志必须自己说清楚，绝不再被 catch 吞掉：
     *   replace skipped: arg type unknown: <类名>                     ← 参数既不是 Location 也不是 LocationResult
     *   replace failed: <异常类名> at <方法名> (<message 首行>) ...    ← 反射 / setter 失败，附前 3 层堆栈
     *   system location replaced: lat,lon (argType=..., items=N)      ← 只有真的改写了 N 个 Location 才打
     * 投递是 10Hz 高频路径，所以每进程每类只打一条（sLoggedReplaceNotes 去重），绝不刷屏。
     */
    private static final Set<String> sLoggedReplaceNotes =
            Collections.synchronizedSet(new LinkedHashSet<String>());

    /** LocationResult 取列表的反射句柄缓存：Method / Field / Method[]{size,get} / NO_LOCATIONS_ACCESSOR。 */
    private static final Map<Class<?>, Object> sLocationsAccessors =
            Collections.synchronizedMap(new WeakHashMap<Class<?>, Object>());
    private static final Object NO_LOCATIONS_ACCESSOR = new Object();

    /**
     * 替换投递给 App 的 Location / LocationResult；未在模拟时不改动。
     *
     * <p>作用域门控：先用 {@code param.thisObject}（本次投递的 transport 实例）反查接收方身份，
     * 只有名单里的应用才改写；<b>系统/框架身份（uid 1000 / android / fused / providers.*）不按名单
     * 过滤</b>，因为这类身份代表不了最终 App（见类注释）；查不到包名时按“保持现状”放行，
     * 每个进程只记一次日志。
     *
     * <p>替换策略：<b>原地改写</b>参数里的 Location 字段（{@code LocationResult} 交给
     * {@link #replaceLocationResult} 逐个 Location 改，不新建对象）。Android 17 的
     * {@code LocationListenerTransport#deliverOnLocationChanged} 是在 hook 之后才调用
     * {@code LocationResult.asList()} / {@code getLastLocation()} 取位置再下发
     * （d2.txt:409570、4098d2），所以改同一批 Location 对象对投递必然可见；反射新建
     * LocationResult 只作为“取不到列表”时的兜底，并且成功/失败都会留下日志。
     */
    private static final XC_MethodHook LOCATION_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                // 诊断：先证明 hook 到底有没有被调用（每进程一行），再谈快照
                logHookFiredOnce(sLoggedDeliveryFired, param, null);
                if (TargetScope.isScoped()) {
                    // 只在开启「仅对选定应用生效」时才反查接收方（关闭时保持旧行为、零额外开销）
                    final Target target = resolveTarget(param.thisObject);
                    if (target.isSystem()) {
                        // 系统/框架身份：本 ROM 的定位经 com.android.location.fused 中转，
                        // system_server 侧只能看到“系统”，按名单过滤必然全挡 → 降级为全局生效
                        logSystemIdentityOnce(target.describe());
                    } else if (!TargetScope.allowsDelivery(target.pkg)) {
                        // 能确定是第三方应用包名 → 仍然按名单过滤
                        // （解析不到包名时 allowsDelivery 按契约放行，并自带一条每进程日志）
                        if (!sLoggedFirstScopeSkip) {
                            sLoggedFirstScopeSkip = true;
                            log("skip delivery: " + target.pkg + " not in scope");
                        }
                        return;   // 不改写，让原逻辑继续（App 收到真实定位）
                    }
                }
                final Snapshot snapshot = currentLocation();
                if (snapshot == null) {
                    // 诊断：hook 触发了但读不到快照（主 App 未发布 / Provider 不可用）
                    logNoSnapshotOnce(sLoggedNoSnapshot, null);
                    return;
                }
                if (param.args == null) {
                    return;
                }
                final String methodName = methodNameOf(param);
                String argType = null;
                int replacedArgs = 0;
                int replacedItems = 0;
                for (int i = 0; i < param.args.length; i++) {
                    final Object arg = param.args[i];
                    if (arg == null) {
                        continue;
                    }
                    try {
                        if (arg instanceof Location) {
                            // 参数是 Location：原地改字段（不新建对象，provider / mock 标记都留在原对象上）
                            if (applySnapshot((Location) arg, snapshot, methodName)) {
                                replacedArgs++;
                                replacedItems++;
                                argType = "Location";
                            }
                        } else if (isLocationResult(arg)) {
                            // 参数是 LocationResult：优先原地改里面每个 Location，避免反射新建对象
                            final Replace replace = replaceLocationResult(arg, snapshot, methodName);
                            if (replace.items > 0) {
                                if (replace.arg != null) {
                                    // 只有“取不到列表 → 新建 LocationResult”这一兜底才需要写回参数
                                    param.args[i] = replace.arg;
                                }
                                replacedArgs++;
                                replacedItems += replace.items;
                                argType = replace.argType;
                            }
                        } else {
                            // 不是定位载体（如 A17 签名里的 IRemoteCallback）：每进程一行，别让它静默
                            logReplaceSkippedOnce(arg.getClass().getName());
                        }
                    } catch (Throwable t) {
                        // 单个参数失败不牵连其它参数，但必须留下可见诊断
                        logReplaceFailureOnce(argTypeName(arg), methodName, t);
                    }
                }
                if (replacedArgs > 0 && !sLoggedFirstReplace) {
                    sLoggedFirstReplace = true;
                    log("system location replaced: " + snapshot.lat + "," + snapshot.lon
                            + " (argType=" + (argType == null ? "?" : argType)
                            + ", items=" + replacedItems + ")");
                }
            } catch (Throwable t) {
                // 出错就透传真实定位 —— 但先让失败可见（每进程每类一次）
                logReplaceFailureOnce("hook", methodNameOf(param), t);
            }
        }
    };

    /** getLastLocation / getCurrentLocation 的返回值也换掉（App 冷启动常先读 last known）。 */
    private static int hookLastLocation(ClassLoader classLoader) {
        final String[] classes = {
                "com.android.server.location.LocationManagerService",
                "com.android.server.location.provider.LocationProviderManager",
                "com.android.server.location.LocationManagerService$LocalService"
        };
        int count = 0;
        for (String name : classes) {
            int classHooked = 0;
            boolean found = false;
            String reason = null;
            final List<String> matchedNames = new ArrayList<>();
            try {
                final Class<?> clazz = XposedHelpers.findClass(name, classLoader);
                found = true;
                for (Method method : clazz.getDeclaredMethods()) {
                    final String methodName = method.getName();
                    if (!"getLastLocation".equals(methodName) && !"getCurrentLocation".equals(methodName)) {
                        continue;
                    }
                    if (method.getReturnType() != Location.class) {
                        continue;
                    }
                    matchedNames.add(methodName);
                    try {
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    // 诊断：先证明这条同步兜底路径有没有被调用（每进程一行）
                                    logHookFiredOnce(sLoggedLastLocationFired, param, "last-location");
                                    // 作用域门控：这类同步方法没有 transport 可查，
                                    // 用 Binder.getCallingUid() 反查调用方包名（见 TargetScope）。
                                    if (!TargetScope.allowsCaller()) {
                                        // 名单未命中 → 再确认是不是系统/框架身份（uid 1000 /
                                        // android / fused / providers.*）：这类身份代表不了最终
                                        // App，按名单过滤必然误杀，按契约退化为全局生效。
                                        final String systemCaller = systemCallerPackage();
                                        if (systemCaller == null) {
                                            if (!sLoggedFirstCallerSkip) {
                                                sLoggedFirstCallerSkip = true;
                                                log("skip getLastLocation: caller not in scope");
                                            }
                                            return;
                                        }
                                        logSystemIdentityOnce(systemCaller);
                                    }
                                    final Snapshot snapshot = currentLocation();
                                    if (snapshot == null) {
                                        // 诊断：hook 触发了但读不到快照（每进程一行）
                                        logNoSnapshotOnce(sLoggedLastLocationNoSnapshot, "last-location");
                                        return;
                                    }
                                    final Object result = param.getResult();
                                    final String provider = result instanceof Location
                                            ? ((Location) result).getProvider() : "gps";
                                    param.setResult(buildLocation(snapshot, provider));
                                } catch (Throwable t) {
                                    // 保留真实结果
                                }
                            }
                        });
                        classHooked++;
                    } catch (Throwable t) {
                        if (reason == null) {
                            reason = "hook failed " + methodName + ": " + t;
                        }
                    }
                }
            } catch (Throwable notFound) {
                reason = "class not found: " + notFound;
            }
            if (reason == null && matchedNames.isEmpty()) {
                reason = "no getLastLocation/getCurrentLocation(Location) match";
            }
            // 每个候选类一行，格式与投递链一致
            log("hook candidate " + name + ": found=" + found + " hooked=" + classHooked
                    + (matchedNames.isEmpty() ? "" : " (methods=" + matchedNames + ")")
                    + (reason == null ? "" : " (" + reason + ")"));
            count += classHooked;
        }
        if (count > 0) {
            log("hooked getLastLocation/getCurrentLocation x" + count);
        }
        return 0;   // 不单独计数，避免日志夸大
    }

    // ============================================================== LocationResult 原地替换

    /** 判断参数是否是 android.location.LocationResult（@SystemApi，不能用 instanceof）；子类也认。 */
    private static boolean isLocationResult(Object arg) {
        if (arg == null) {
            return false;
        }
        for (Class<?> c = arg.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if ("android.location.LocationResult".equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    /** 一次参数替换的结果。 */
    private static final class Replace {
        /** 实际改写的 Location 个数；0 = 没改成。 */
        final int items;
        /** 日志里的 argType（Location / LocationResult）。 */
        final String argType;
        /** 要写回 param.args[i] 的新对象；null = 原地改好了，不必写回。 */
        final Object arg;

        Replace(int items, String argType, Object arg) {
            this.items = items;
            this.argType = argType;
            this.arg = arg;
        }
    }

    private static final Replace NO_REPLACE = new Replace(0, null, null);

    /**
     * 替换 LocationResult：<b>先原地改里面每个 Location 的字段</b>，不新建 LocationResult。
     *
     * <p>只有列表取不到 / 是深拷贝（改完再读还是旧值）才退回“反射新建 LocationResult”的旧方案，
     * 并且失败原因一定通过 {@code replace failed: ...} 留痕（每条每进程一次）。
     *
     * @return items&gt;0 表示成功；arg 非 null 时要写回 {@code param.args[i]}
     */
    private static Replace replaceLocationResult(Object result, Snapshot snapshot, String methodName) {
        List<Location> locations = null;
        try {
            locations = locationsOf(result);
        } catch (Throwable t) {
            // 取列表失败：可见 + 仍然尝试新建兜底
            logReplaceFailureOnce("LocationResult", methodName, t);
        }
        int items = 0;
        if (locations != null) {
            for (Location location : locations) {
                if (location == null) {
                    continue;
                }
                if (applySnapshot(location, snapshot, methodName)) {
                    items++;
                }
            }
        }
        if (items > 0) {
            if (isSnapshotVisible(result, snapshot)) {
                return new Replace(items, "LocationResult", null);
            }
            // 改完再读还是旧值：getLocations/asList 返回的是深拷贝，原地改对投递不可见
            logReplaceFailureOnce("LocationResult", methodName, new IllegalStateException(
                    "in-place update not visible on re-read (items=" + items + ")"));
            items = 0;
        }
        // 兜底：新建一个 LocationResult 塞回参数（A17 上 create/wrap 是否可用不假设）
        final List<Location> fresh = new ArrayList<>(1);
        fresh.add(buildLocation(snapshot, "gps"));
        try {
            final Object rebuilt = createLocationResult(fresh);
            return rebuilt == null ? NO_REPLACE : new Replace(1, "LocationResult", rebuilt);
        } catch (Throwable t) {
            logReplaceFailureOnce("LocationResult", methodName, t);
            return NO_REPLACE;
        }
    }

    /**
     * 原地改写一个 Location 的位置字段（<b>不新建对象</b>）。
     *
     * <p>只动公开 setter：provider / mock 标记 / 对象身份都保留，投递链路后续无论怎么读都拿到新值。
     * setter 失败不再静默，统一走 {@code replace failed: ...}。
     *
     * @return true = 位置本体（经纬度等）已改写；false = 本体没改成（调用方走新建兜底）
     */
    private static boolean applySnapshot(Location location, Snapshot snapshot, String methodName) {
        final float accuracy = snapshot.accuracy > 0 ? snapshot.accuracy : 5f;
        try {
            location.setLatitude(snapshot.lat);
            location.setLongitude(snapshot.lon);
            location.setAltitude(snapshot.alt);
            location.setTime(System.currentTimeMillis());
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            location.setAccuracy(accuracy);
            location.setSpeed(snapshot.speed);
            location.setBearing(snapshot.bearing);
        } catch (Throwable t) {
            logReplaceFailureOnce("Location.core", methodName, t);
            return false;
        }
        try {
            location.setVerticalAccuracyMeters(Math.max(1f, accuracy * 1.6f));
            location.setSpeedAccuracyMetersPerSecond(Math.max(0.2f, snapshot.speed * 0.05f));
            location.setBearingAccuracyDegrees(3f);
        } catch (Throwable t) {
            // 精度三项只是附属信息：失败不影响位置本体，但同样要可见
            logReplaceFailureOnce("Location.accuracy", methodName, t);
        }
        applySatellitesExtra(location, snapshot.satellites, methodName);
        return true;
    }

    /** 保留 satellites extras（与主 App 快照一致）：合并而不是覆盖原有 extras。 */
    private static void applySatellitesExtra(Location location, int satellites, String methodName) {
        if (satellites <= 0) {
            return;
        }
        try {
            final android.os.Bundle existing = location.getExtras();
            final android.os.Bundle extras =
                    existing == null ? new android.os.Bundle() : new android.os.Bundle(existing);
            extras.putInt("satellites", satellites);
            location.setExtras(extras);
        } catch (Throwable t) {
            // 位置本体已经改好，extras 只影响卫星数显示 → 记一条即可
            logReplaceFailureOnce("Location.extras", methodName, t);
        }
    }

    /**
     * 反射取 LocationResult 里的 {@code List<Location>}（元素一律用 {@code instanceof Location} 判定）。
     *
     * <p>Android 17 真机 dexdump（services.jar/classes2.dex）实测的取法是
     * {@code LocationResult.asList:()Ljava/util/List;}、{@code get:(I)}、{@code size:()}，
     * <b>没有</b> {@code getLocations()} 的引用。所以按四级兜底：
     * <ol>
     *   <li>{@code getLocations()}：Android 7~14 的 @SystemApi；</li>
     *   <li>{@code getDeclaredMethods()} 里名字包含 {@code getLocations} 的无参方法（OEM 改名）；</li>
     *   <li>{@code asList()}：Android 17 的取列表方法；</li>
     *   <li>{@code mLocations} 字段，或 {@code size() + get(int)} 逐项取。</li>
     * </ol>
     *
     * @throws Throwable 取不到 / 不是 {@code List<Location>}；调用方负责记录可见日志并走新建兜底
     */
    private static List<Location> locationsOf(Object result) throws Throwable {
        if (result == null) {
            throw new IllegalStateException("LocationResult is null");
        }
        final Class<?> clazz = result.getClass();
        final Object accessor = resolveLocationsAccessor(clazz);
        if (accessor == null) {
            throw new IllegalStateException("no list accessor on " + clazz.getName());
        }
        if (accessor instanceof Method) {
            return toLocationList(invokeAccessor((Method) accessor, result), clazz);
        }
        if (accessor instanceof Field) {
            try {
                return toLocationList(((Field) accessor).get(result), clazz);
            } catch (Throwable t) {
                throw unwrapReflective(t);
            }
        }
        // Method[]{size(), get(int)}：A17 的最后兜底
        final Method[] pair = (Method[]) accessor;
        final int size;
        try {
            final Object value = pair[0].invoke(result);
            size = value instanceof Integer ? ((Integer) value).intValue() : 0;
        } catch (Throwable t) {
            throw unwrapReflective(t);
        }
        final List<Location> list = new ArrayList<>(Math.max(0, size));
        for (int i = 0; i < size; i++) {
            try {
                final Object item = pair[1].invoke(result, i);
                if (item instanceof Location) {
                    list.add((Location) item);
                }
            } catch (Throwable t) {
                throw unwrapReflective(t);
            }
        }
        return list;
    }

    /** 反射句柄调用：把 InvocationTargetException 里的真实原因抛出来，日志才看得懂。 */
    private static Object invokeAccessor(Method method, Object instance) throws Throwable {
        try {
            return method.invoke(instance);
        } catch (Throwable t) {
            throw unwrapReflective(t);
        }
    }

    /** 把反射拿到的 List / Location[] 统一成 List&lt;Location&gt;；不是列表就抛（原因可见）。 */
    private static List<Location> toLocationList(Object value, Class<?> owner) {
        if (value instanceof List) {
            final List<?> raw = (List<?>) value;
            final List<Location> out = new ArrayList<>(raw.size());
            for (Object item : raw) {
                if (item instanceof Location) {
                    out.add((Location) item);
                }
            }
            return out;
        }
        if (value instanceof Location[]) {
            return new ArrayList<>(Arrays.asList((Location[]) value));
        }
        if (value instanceof Object[]) {
            final List<Location> out = new ArrayList<>();
            for (Object item : (Object[]) value) {
                if (item instanceof Location) {
                    out.add((Location) item);
                }
            }
            return out;
        }
        throw new IllegalStateException("not a List<Location> from " + owner.getName() + ": "
                + (value == null ? "null" : value.getClass().getName()));
    }

    /** 句柄解析结果按类缓存（投递每 10Hz 一次，不能每次都扫方法表）。 */
    private static Object resolveLocationsAccessor(Class<?> clazz) {
        final Object cached = sLocationsAccessors.get(clazz);
        if (cached != null) {
            return cached == NO_LOCATIONS_ACCESSOR ? null : cached;
        }
        Object accessor = null;
        try {
            accessor = findLocationsAccessor(clazz);
        } catch (Throwable ignored) {
            // 扫描失败按“没找到”处理；调用方会打出可见的 replace failed
        }
        sLocationsAccessors.put(clazz, accessor == null ? NO_LOCATIONS_ACCESSOR : accessor);
        return accessor;
    }

    private static Object findLocationsAccessor(Class<?> clazz) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            Method method = findListMethod(c, "getLocations", true);
            if (method != null) {
                return method;
            }
            method = findListMethod(c, "getLocations", false);
            if (method != null) {
                return method;
            }
            method = findListMethod(c, "asList", true);
            if (method != null) {
                return method;
            }
            final Field field = findListField(c);
            if (field != null) {
                return field;
            }
        }
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            final Method size = findIndexMethod(c, "size", true);
            final Method get = findIndexMethod(c, "get", false);
            if (size != null && get != null) {
                return new Method[]{size, get};
            }
        }
        return null;
    }

    /** 无参、返回 List / Location[] 的方法：getLocations() / 名字含 getLocations / asList()。 */
    private static Method findListMethod(Class<?> clazz, String name, boolean exact) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getParameterTypes().length != 0) {
                continue;
            }
            final String methodName = method.getName();
            if (exact ? !name.equals(methodName) : methodName.indexOf(name) < 0) {
                continue;
            }
            final Class<?> type = method.getReturnType();
            if (!List.class.isAssignableFrom(type)
                    && !(type.isArray() && Location.class.isAssignableFrom(type.getComponentType()))) {
                continue;
            }
            trySetAccessible(method);
            return method;
        }
        return null;
    }

    /** List 类型的字段（A17 内部就是 mLocations；名字不认识就不碰）。 */
    private static Field findListField(Class<?> clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            if (!List.class.isAssignableFrom(field.getType())) {
                continue;
            }
            if (field.getName().indexOf("ocation") < 0) {
                continue;
            }
            trySetAccessible(field);
            return field;
        }
        return null;
    }

    /** size() / get(int) 兜底用；返回类型必须对得上，避免误抓同名方法。 */
    private static Method findIndexMethod(Class<?> clazz, String name, boolean noArgs) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (!name.equals(method.getName())) {
                continue;
            }
            final Class<?>[] types = method.getParameterTypes();
            if (noArgs) {
                if (types.length != 0 || method.getReturnType() != int.class) {
                    continue;
                }
            } else {
                if (types.length != 1 || types[0] != int.class
                        || !Location.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
            }
            trySetAccessible(method);
            return method;
        }
        return null;
    }

    private static void trySetAccessible(java.lang.reflect.AccessibleObject object) {
        try {
            object.setAccessible(true);
        } catch (Throwable ignored) {
            // 公开成员不需要；隐藏成员被拦时留给 invoke 抛出来记录
        }
    }

    /** 原地改完后重新读一次：确认真的改了（防 getLocations/asList 返回深拷贝却不自知）。 */
    private static boolean isSnapshotVisible(Object result, Snapshot snapshot) {
        try {
            final List<Location> check = locationsOf(result);
            if (check == null || check.isEmpty()) {
                return true;   // 读不到就别否定已经改好的结果
            }
            final Location first = check.get(0);
            return first == null
                    || (first.getLatitude() == snapshot.lat && first.getLongitude() == snapshot.lon);
        } catch (Throwable t) {
            return true;   // 校验本身失败：不回滚已改好的字段
        }
    }

    /**
     * 反射新建 LocationResult（<b>兜底用</b>）：A17 实测有 {@code create(Location[])} 与
     * {@code wrap(Location[])}，Android 12/13 是 {@code create(List)}。
     *
     * @throws Throwable 全部尝试都失败时抛最后一个原因（调用方记一条可见日志）
     */
    private static Object createLocationResult(List<Location> locations) throws Throwable {
        final Class<?> clazz = Class.forName("android.location.LocationResult");
        final Location[] array = locations.toArray(new Location[0]);
        final List<Location> copy = new ArrayList<>(locations);
        Throwable last = null;
        for (String factory : new String[]{"create", "wrap"}) {
            try {
                return XposedHelpers.callStaticMethod(clazz, factory, (Object) array);
            } catch (Throwable t) {
                last = unwrapReflective(t);
            }
            try {
                return XposedHelpers.callStaticMethod(clazz, factory, (Object) copy);
            } catch (Throwable t) {
                last = unwrapReflective(t);
            }
        }
        throw last == null ? new IllegalStateException("no create/wrap on LocationResult") : last;
    }

    // ============================================================== 替换诊断

    /** 参数类型不是 Location / LocationResult：每进程每类型一条（下一轮判定靠它）。 */
    private static void logReplaceSkippedOnce(String argClass) {
        try {
            if (sLoggedReplaceNotes.add("skip:" + argClass)) {
                log("replace skipped: arg type unknown: " + argClass);
            }
        } catch (Throwable ignored) {
            // 日志绝不抛出
        }
    }

    /** 替换失败：每进程每（位置类型 + 异常类）一条，附前 3 层堆栈。 */
    private static void logReplaceFailureOnce(String argType, String methodName, Throwable t) {
        try {
            final Throwable cause = unwrapReflective(t == null
                    ? new IllegalStateException("unknown") : t);
            final String exceptionName = cause.getClass().getName();
            if (!sLoggedReplaceNotes.add("fail:" + argType + ":" + exceptionName)) {
                return;
            }
            final StringBuilder message = new StringBuilder("replace failed: ")
                    .append(exceptionName)
                    .append(" at ").append(methodName == null || methodName.isEmpty() ? "?" : methodName)
                    .append(" (").append(firstLine(cause.getMessage())).append(')');
            appendStackFrames(message, cause, 3);
            log(message.toString());
        } catch (Throwable ignored) {
            // 日志绝不抛出
        }
    }

    /** 反射异常必然是 InvocationTargetException 包一层，取里面真正的原因。 */
    private static Throwable unwrapReflective(Throwable t) {
        Throwable cause = t;
        for (int depth = 0; depth < 3; depth++) {
            if (cause == null || !(cause instanceof InvocationTargetException)
                    || cause.getCause() == null) {
                break;
            }
            cause = cause.getCause();
        }
        return cause == null ? new IllegalStateException("unknown") : cause;
    }

    private static String firstLine(String message) {
        if (message == null || message.isEmpty()) {
            return "no message";
        }
        final int newline = message.indexOf('\n');
        final String line = newline >= 0 ? message.substring(0, newline) : message;
        return line.length() > 160 ? line.substring(0, 160) + "…" : line;
    }

    /** 附前 limit 层堆栈：只用于诊断，任何异常都忽略。 */
    private static void appendStackFrames(StringBuilder message, Throwable t, int limit) {
        try {
            final StackTraceElement[] frames = t.getStackTrace();
            if (frames == null || frames.length == 0) {
                return;
            }
            message.append(" stack=[");
            for (int i = 0; i < frames.length && i < limit; i++) {
                if (i > 0) {
                    message.append(" <- ");
                }
                message.append(frames[i]);
            }
            message.append(']');
        } catch (Throwable ignored) {
            // 日志绝不抛出
        }
    }

    private static String methodNameOf(XC_MethodHook.MethodHookParam param) {
        try {
            if (param != null && param.method instanceof Method) {
                return ((Method) param.method).getName();
            }
        } catch (Throwable ignored) {
            // 日志用，失败退化成占位
        }
        return "?";
    }

    private static String argTypeName(Object arg) {
        try {
            if (arg == null) {
                return "null";
            }
            final String simple = arg.getClass().getSimpleName();
            return simple == null || simple.isEmpty() ? arg.getClass().getName() : simple;
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** 用快照造一个不带 mock 标记的 Location。 */
    private static Location buildLocation(Snapshot snapshot, String provider) {
        final Location location = new Location(provider == null ? "gps" : provider);
        location.setLatitude(snapshot.lat);
        location.setLongitude(snapshot.lon);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        location.setAccuracy(snapshot.accuracy > 0 ? snapshot.accuracy : 5f);
        location.setAltitude(snapshot.alt);
        location.setSpeed(snapshot.speed);
        location.setBearing(snapshot.bearing);
        try {
            location.setVerticalAccuracyMeters(Math.max(1f, (snapshot.accuracy > 0
                    ? snapshot.accuracy : 5f) * 1.6f));
            location.setSpeedAccuracyMetersPerSecond(Math.max(0.2f, snapshot.speed * 0.05f));
            location.setBearingAccuracyDegrees(3f);
        } catch (Throwable ignored) {
            // 低版本没有这些 setter
        }
        if (snapshot.satellites > 0) {
            try {
                final android.os.Bundle extras = new android.os.Bundle();
                extras.putInt("satellites", snapshot.satellites);
                location.setExtras(extras);
            } catch (Throwable ignored) {
                // ignore
            }
        }
        return location;
    }

    // ================================================================== GNSS 卫星下发

    /**
     * 卫星投递方法名：旧版本为 {@code onSatelliteStatusChanged(GnssStatus)}；
     * Android 17 真机 dexdump 确认 {@code GnssStatusProvider#onReportSvStatus(GnssStatus)} 是下发入口。
     */
    private static final List<String> GNSS_DELIVERY_METHODS =
            Arrays.asList("onSatelliteStatusChanged", "onReportSvStatus");

    private static final String[] GNSS_DELIVERY_CLASSES = {
            "com.android.server.location.GnssStatusListenerTransport",
            "com.android.server.location.gnss.GnssStatusListenerTransport",
            "android.location.GnssStatus$GnssStatusListenerTransport",
            // Android 17（SDK 37）：GnssStatusProvider 继承 GnssListenerMultiplexer，
            // onReportSvStatus(GnssStatus) 直接把卫星表下发给注册者（参数就是 GnssStatus）
            "com.android.server.location.gnss.GnssStatusProvider"
    };

    private static int hookGnssDelivery(ClassLoader classLoader) {
        return hookCandidates(classLoader, GNSS_DELIVERY_CLASSES, GNSS_DELIVERY_METHODS,
                GNSS_HOOK, "gnss", true);
    }

    private static final XC_MethodHook GNSS_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (currentLocation() == null) {
                    return;   // 未在模拟：透传真实卫星
                }
                final List<Satellite> satellites = currentSatellites();
                if (satellites == null || satellites.isEmpty() || param.args == null) {
                    return;
                }
                final GnssStatus status = buildGnssStatus(satellites);
                if (status == null) {
                    return;
                }
                for (int i = 0; i < param.args.length; i++) {
                    if (param.args[i] instanceof GnssStatus) {
                        param.args[i] = status;
                    }
                }
                if (!sLoggedFirstGnss) {
                    sLoggedFirstGnss = true;
                    log("system gnss replaced: satellites=" + satellites.size());
                }
            } catch (Throwable t) {
                // 出错透传
            }
        }
    };

    /** 用反射的 GnssStatus$Builder 造一套与主 App 一致的卫星（API 30+）。 */
    private static GnssStatus buildGnssStatus(List<Satellite> satellites) {
        try {
            final Class<?> builderClass = XposedHelpers.findClass(
                    "android.location.GnssStatus$Builder", GnssStatus.class.getClassLoader());
            final Object builder = XposedHelpers.newInstance(builderClass);
            for (Satellite sat : satellites) {
                XposedHelpers.callMethod(builder, "addSatellite",
                        sat.svid, sat.constellation, sat.cn0, sat.elevation, sat.azimuth,
                        sat.carrierHz, sat.usedInFix, sat.hasAlmanac, sat.hasEphemeris);
            }
            return (GnssStatus) XposedHelpers.callMethod(builder, "build");
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================== 快照读取（套接字优先，Provider 兜底）

    /*
     * Android 17 的 system_server（uid 1000）读 content://com.vloc.motion.env/* 会抛
     * SecurityException（logcat: Isolated process not allowed to call getContentProvider），
     * 所以主通道换成 Unix 抽象套接字（SnapshotClient）：
     *   1. 套接字有答复（含「空行 = 无数据」）→ 直接采用，不再碰 Provider；
     *   2. 套接字失败（未装新版主 App / 连接被拒 / 超时）→ 回退原来的 Provider 路径，行为不变；
     *   3. 两条通道都没有数据 → 返回 null（透传真实定位），与旧行为一致。
     * 缓存判断放在方法入口：命中缓存不建连接（10Hz 投递不会变成 10Hz 连接）。
     */

    private static Snapshot currentLocation() {
        final long now = System.currentTimeMillis();
        if (now - sLocationAt < LOCATION_CACHE_MS) {
            return sLocation;   // 缓存命中：不建连
        }
        sLocationAt = now;
        final String line = SnapshotClient.readLine(SnapshotClient.REQ_LOCATION,
                SnapshotClient.DEFAULT_TIMEOUT_MS);
        final Snapshot parsed;
        if (line != null) {
            // 通道已答复；"" = 主 App 明确表示当前没有数据（不再花一次 Provider 读取）
            parsed = line.isEmpty() ? null : parseLocationJson(line);
        } else {
            parsed = readProviderSnapshot();   // 套接字不可用：回退 ContentProvider
        }
        sLocation = parsed;
        return parsed;
    }

    /** 解析套接字返回的 /location 同款 JSON；{@code active=false} 与解析失败都返回 null。 */
    private static Snapshot parseLocationJson(String json) {
        try {
            final JSONObject root = new JSONObject(json);
            if (!root.optBoolean("active", false)) {
                logSnapshotUnavailableOnce("inactive snapshot (socket)");
                return null;
            }
            return new Snapshot(
                    root.optDouble("lat"), root.optDouble("lon"),
                    root.optDouble("alt"), (float) root.optDouble("speed"),
                    (float) root.optDouble("bearing"),
                    (float) root.optDouble("acc"),
                    root.optInt("satellites", 0),
                    root.optLong("updatedAt", 0L));
        } catch (Throwable t) {
            logSnapshotUnavailableOnce("socket json: " + t.getClass().getName());
            return null;
        }
    }

    /**
     * 兜底通道：主 App 的 ContentProvider（原逻辑，行为不变）。
     *
     * <p>Android 17 的 system_server 里这条路会抛 {@code SecurityException}；
     * 只在新版主 App 的套接字不可用时才会走到（老版本主 App / 套接字被拒）。
     */
    private static Snapshot readProviderSnapshot() {
        Snapshot parsed = null;
        String unavailable = null;
        try {
            final Context context = systemContext();
            if (context == null) {
                unavailable = "no system context";
            } else {
                try (android.database.Cursor cursor = context.getContentResolver()
                        .query(Uri.parse(LOCATION_URI), new String[]{"json"}, null, null, null)) {
                    if (cursor == null) {
                        unavailable = "null cursor";
                    } else if (!cursor.moveToFirst()) {
                        unavailable = "empty cursor";
                    } else {
                        int column = cursor.getColumnIndex("json");
                        if (column < 0) {
                            column = 1;
                        }
                        final JSONObject root = new JSONObject(cursor.getString(column));
                        if (root.optBoolean("active", false)) {
                            parsed = new Snapshot(
                                    root.optDouble("lat"), root.optDouble("lon"),
                                    root.optDouble("alt"), (float) root.optDouble("speed"),
                                    (float) root.optDouble("bearing"),
                                    (float) root.optDouble("acc"),
                                    root.optInt("satellites", 0),
                                    root.optLong("updatedAt", 0L));
                        } else {
                            unavailable = "inactive snapshot";
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // 读取失败：视为未模拟；原因只进诊断日志（每进程一条）
            unavailable = t.getClass().getName();
        }
        if (parsed == null) {
            logSnapshotUnavailableOnce(unavailable == null ? "unknown" : unavailable);
        }
        return parsed;
    }

    private static List<Satellite> currentSatellites() {
        final long now = System.currentTimeMillis();
        if (now - sGnssAt < GNSS_CACHE_MS) {
            return sSatellites;   // 缓存命中：不建连
        }
        sGnssAt = now;
        final String line = SnapshotClient.readLine(SnapshotClient.REQ_GNSS,
                SnapshotClient.DEFAULT_TIMEOUT_MS);
        final List<Satellite> parsed;
        if (line != null) {
            // 通道已答复；"" = 主 App 当前没有卫星数据
            parsed = line.isEmpty() ? null : parseSatellitesJson(line);
        } else {
            parsed = readProviderSatellites();   // 套接字不可用：回退 ContentProvider
        }
        sSatellites = parsed;
        return parsed;
    }

    /** 解析套接字返回的 /gnss 同款数组；空 / 非法内容返回 null（= 不注入卫星表）。 */
    private static List<Satellite> parseSatellitesJson(String json) {
        try {
            final JSONArray array = new JSONArray(json);
            final List<Satellite> list = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                final JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                Satellite sat = new Satellite();
                sat.svid = item.optInt("svid", 0);
                if (sat.svid <= 0) {
                    continue;
                }
                sat.constellation = item.optInt("const", 1);
                sat.elevation = (float) item.optDouble("ele", 0);
                sat.azimuth = (float) item.optDouble("azi", 0);
                sat.cn0 = (float) item.optDouble("cn0", 30);
                sat.usedInFix = item.optBoolean("fix", true);
                sat.hasAlmanac = item.optBoolean("alm", true);
                sat.hasEphemeris = item.optBoolean("eph", true);
                sat.carrierHz = (float) item.optDouble("cf", 1575.42e6);
                list.add(sat);
            }
            return list.isEmpty() ? null : list;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 兜底通道：主 App 的 ContentProvider（原逻辑，行为不变）。
     *
     * <p>只有 {@code GNSS} 套接字不可用时才会走到（老版本主 App / 套接字被拒）。
     */
    private static List<Satellite> readProviderSatellites() {
        List<Satellite> parsed = null;
        try {
            final Context context = systemContext();
            if (context != null) {
                try (android.database.Cursor cursor = context.getContentResolver()
                        .query(Uri.parse(GNSS_URI), new String[]{"json"}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int column = cursor.getColumnIndex("json");
                        if (column < 0) {
                            column = 1;
                        }
                        parsed = parseSatellitesJson(cursor.getString(column));
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return parsed;
    }

    /** system_server 里的系统 Context（ActivityThread.systemMain().getSystemContext()）。 */
    static Context systemContext() {
        Context context = sContext;
        if (context != null) {
            return context;
        }
        try {
            final Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = XposedHelpers.callStaticMethod(activityThread, "systemMain");
            if (thread == null) {
                thread = XposedHelpers.callStaticMethod(activityThread, "currentActivityThread");
            }
            if (thread != null) {
                context = (Context) XposedHelpers.callMethod(thread, "getSystemContext");
            }
        } catch (Throwable ignored) {
            // ignore
        }
        if (context != null) {
            sContext = context;
        }
        return context;
    }

    // ================================================================== 状态回报

    /** 把"系统级已生效"回报给主 App（复用相机状态通道的通用字段）。 */
    private static void report(int hooks) {
        // 仅记日志：系统级 hook 的状态通过 LSPosed 日志排查，
        // 主 App 不再需要相机/系统状态的独立上报通道（相机功能已移除）。
        log("system-level hooks active: " + hooks);
    }

    private static void log(String message) {
        try {
            XposedBridge.log(TAG + message);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
