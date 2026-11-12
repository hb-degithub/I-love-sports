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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

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
 *   <li>数据来源：主 App 的 ContentProvider
 *       {@code content://com.vloc.motion.env/location}（定位）与 {@code /gnss}（卫星）。</li>
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
 * <p>安全约束：所有 hook 独立 try/catch；读 Provider 失败 / 未在模拟时一律透传真实定位，
 * 绝不阻断系统服务。
 */
final class SystemHooks {

    private static final String TAG = "[VLocHidden] ";

    private static final String LOCATION_URI = "content://com.vloc.motion.env/location";
    private static final String GNSS_URI = "content://com.vloc.motion.env/gnss";

    /** 定位快照缓存：Pro**vider 读取有 binder 开销，缓存 120ms 足够跟上 10Hz 注入。 */
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
     * @param stopOnFirst 命中一个类就停（旧版本优先，避免同一条投递链被挂两层）
     * @return 实际挂上的方法数
     */
    private static int hookCandidates(ClassLoader classLoader, String[] classes, List<String> methodNames,
                                      XC_MethodHook hook, String tag, boolean stopOnFirst) {
        int total = 0;
        for (String name : classes) {
            int hooked = 0;
            try {
                final Class<?> clazz = XposedHelpers.findClass(name, classLoader);
                // 逐个重载挂：既兼容 (Location)，也兼容 (LocationResult)
                for (Method method : clazz.getDeclaredMethods()) {
                    if (!methodNames.contains(method.getName())) {
                        continue;
                    }
                    try {
                        XposedBridge.hookMethod(method, hook);
                        hooked++;
                    } catch (Throwable t) {
                        log(tag + " hook failed " + name + "#" + method.getName() + ": " + t);
                    }
                }
                log(tag + " " + name + " hooks=" + hooked);
            } catch (Throwable notFound) {
                log(tag + " " + name + " not found (skip)");
            }
            total += hooked;
            if (stopOnFirst && hooked > 0) {
                break;
            }
        }
        return total;
    }

    /**
     * 替换投递给 App 的 Location / LocationResult；未在模拟时不改动。
     *
     * <p>作用域门控：先用 {@code param.thisObject}（本次投递的 transport 实例）反查接收方身份，
     * 只有名单里的应用才改写；<b>系统/框架身份（uid 1000 / android / fused / providers.*）不按名单
     * 过滤</b>，因为这类身份代表不了最终 App（见类注释）；查不到包名时按“保持现状”放行，
     * 每个进程只记一次日志。
     */
    private static final XC_MethodHook LOCATION_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
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
                if (snapshot == null || param.args == null) {
                    return;
                }
                for (int i = 0; i < param.args.length; i++) {
                    final Object arg = param.args[i];
                    if (arg instanceof Location) {
                        param.args[i] = buildLocation(snapshot, ((Location) arg).getProvider());
                    } else if (isLocationResult(arg)) {
                        // LocationResult 是 @SystemApi（不在公开 SDK 里），只能反射构造
                        final List<Location> list = new ArrayList<>();
                        list.add(buildLocation(snapshot, "gps"));
                        final Object replaced = createLocationResult(list);
                        if (replaced != null) {
                            param.args[i] = replaced;
                        }
                    }
                }
                if (!sLoggedFirstReplace) {
                    sLoggedFirstReplace = true;
                    log("system location replaced: " + snapshot.lat + "," + snapshot.lon);
                }
            } catch (Throwable t) {
                // 出错就透传真实定位
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
            try {
                final Class<?> clazz = XposedHelpers.findClass(name, classLoader);
                for (Method method : clazz.getDeclaredMethods()) {
                    final String methodName = method.getName();
                    if (!"getLastLocation".equals(methodName) && !"getCurrentLocation".equals(methodName)) {
                        continue;
                    }
                    if (method.getReturnType() != Location.class) {
                        continue;
                    }
                    try {
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
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
                        count++;
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
        if (count > 0) {
            log("hooked getLastLocation/getCurrentLocation x" + count);
        }
        return 0;   // 不单独计数，避免日志夸大
    }

    /** 判断参数是否是 android.location.LocationResult（@SystemApi，不能用 instanceof）。 */
    private static boolean isLocationResult(Object arg) {
        return arg != null && "android.location.LocationResult".equals(arg.getClass().getName());
    }

    /** 反射构造 LocationResult：SDK 37 为 {@code create(Location...)}，旧版本为 {@code create(List)}。 */
    private static Object createLocationResult(List<Location> locations) {
        try {
            final Class<?> clazz = Class.forName("android.location.LocationResult");
            final Location[] array = locations.toArray(new Location[0]);
            try {
                // Android 14+（含 SDK 37 真机 dexdump：create:([Landroid/location/Location;)）
                return XposedHelpers.callStaticMethod(clazz, "create", (Object) array);
            } catch (Throwable notVarargs) {
                // Android 12/13：create(List<Location>)
                return XposedHelpers.callStaticMethod(clazz, "create", locations);
            }
        } catch (Throwable t) {
            return null;
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

    // ================================================================== Provider 读取（带缓存）

    private static Snapshot currentLocation() {
        final long now = System.currentTimeMillis();
        if (now - sLocationAt < LOCATION_CACHE_MS) {
            return sLocation;
        }
        sLocationAt = now;
        Snapshot parsed = null;
        try {
            final Context context = systemContext();
            if (context != null) {
                try (android.database.Cursor cursor = context.getContentResolver()
                        .query(Uri.parse(LOCATION_URI), new String[]{"json"}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
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
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 读取失败：视为未模拟
        }
        sLocation = parsed;
        return parsed;
    }

    private static List<Satellite> currentSatellites() {
        final long now = System.currentTimeMillis();
        if (now - sGnssAt < GNSS_CACHE_MS) {
            return sSatellites;
        }
        sGnssAt = now;
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
                        final JSONArray array = new JSONArray(cursor.getString(column));
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
                        if (!list.isEmpty()) {
                            parsed = list;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        sSatellites = parsed;
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
