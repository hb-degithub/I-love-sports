package com.vloc.motion.hidden;

import android.app.AppOpsManager;
import android.content.ContentResolver;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 入口：在目标 App 进程内清除 {@link Location} 上的“模拟位置”标记。
 *
 * <p>做十组遮蔽（0.4.x 起，GNSS 卫星回放为 v0.18.x 新增）：
 * <ol>
 *   <li><b>Location 标记</b>：isFromMockProvider()/isMock() 恒 false，setMock/setIsFromMockProvider
 *       空操作，getExtras() 清掉 mock 类 key；</li>
 *   <li><b>Settings 旁证</b>：Secure/System/Global 读取 "mock_location" 时，getInt/getLong 返回 0、
 *       getString 返回 null（Android 6+ 真机该设置不存在），并兜底 hook @hide 的 NameValueCache；</li>
 *   <li><b>AppOps 自查</b>：8 个 checkOp/unsafeCheckOp 系列方法对 mock op 表现为“无权限”
 *       （MODE_ERRORED，throwing 变体抛 SecurityException）；</li>
 *   <li><b>AppOps 枚举与 noteOp 旁路</b>：getPackagesForOps/getOpsForPackage 把 mock op 从入参剔除，
 *       noteOp/startOp 抛 SecurityException、*NoThrow 返回 MODE_ERRORED、finishOp 空操作；</li>
 *   <li><b>ContentProvider 直查</b>：ContentResolver#query/call 对 settings provider 的
 *       mock_location 查询返回“等价于该设置不存在”的结果（不自己造 Cursor，避免列不匹配崩溃）；</li>
 *   <li><b>Parcel 序列化</b>：Location#writeToParcel 写之前临时清掉 mock 位，写完只恢复这一位，
 *       其它掩码位不受影响；</li>
 *   <li><b>基站 / Wi-Fi 环境回放</b>：按主 App 的快照（content://com.vloc.motion.env/snapshot）
 *       回放 CellInfo / GsmCellLocation / ScanResult / WifiInfo，或按 hide 模式清空，
 *       见 {@link EnvReplay} 与 {@link EnvSnapshot}；</li>
 *   <li><b>计步传感器回放</b>：按主 App 的 steps 快照改写 TYPE_STEP_COUNTER / 合成
 *       TYPE_STEP_DETECTOR 事件，让步数 / 步频与虚拟路线一致，见 {@link StepReplay}；</li>
 *   <li><b>IMU / 姿态伪造</b>：按同一份快照 + 主 App 的 ImuWaveform / GaitAttitude 以 50Hz 合成
 *       TYPE_ACCELEROMETER / TYPE_LINEAR_ACCELERATION / TYPE_GYROSCOPE / TYPE_GRAVITY /
 *       TYPE_ROTATION_VECTOR / TYPE_MAGNETIC_FIELD，让目标 App 自己用 IMU 算步频、看姿态时
 *       也与虚拟路线一致（按跑步摆动，而非静止假设；重力/加速度按同一姿态旋转，
 *       与旋转矢量反推的倾角自洽），见 {@link ImuReplay}。</li>
 *   <li><b>GNSS 卫星状态回放</b>：按同一条路线快照的 {@code "gnss":[...]} 数组，通过
 *       {@link GnssReplay} 以 1Hz 向目标 App 的 {@code GnssStatus.Callback} 投递与虚拟路线自洽的
 *       卫星（星座 / svid / 仰角 / 方位角 / 载噪比 / 基带载噪比），并改写 {@code GnssStatus} 的
 *       全部读取方法，消除“有坐标却没有卫星”的矛盾证据；快照缺失 / 未回放时全透传。</li>
 * </ol>
 *
 * <p>设计要点：
 * <ul>
 *   <li>只修改“标记”与两类旁证查询的结果，<b>不</b>修改坐标 / 速度 / 海拔 / 时间 / 精度 / provider
 *       等定位数据本身；坐标由主 App（com.vloc.motion）通过 root + test provider（含拟真模式）注入。</li>
 *   <li>目标机型的 Android 版本差异很大（8.0 ~ 14），所以每个 hook 都是
 *       “独立 try/catch + 找不到就跳过”，任何一个 hook 失败都不影响其余 hook。</li>
 *   <li>所有 hook 都只做“读取路径上的遮蔽 + 写入路径上的空操作”，不会抛出异常给目标 App。</li>
 *   <li>框架类只有一份（boot classloader），因此用的是进程内的 Location 类，
 *       主 App 注入的 Location 对象与目标 App 收到的是同一个类的实例，getter 会被拦下。</li>
 *   <li>已核对 AOSP 源码（Android 8.1 ~ 14）：模拟标记是 {@code mFieldsMask} 里的
 *       HAS_MOCK_PROVIDER_MASK 位（=16）。读取路径靠遮蔽 getter 覆盖；序列化路径靠
 *       writeToParcel 前后“只清这一位 / 只恢复这一位”，不整体改写掩码，
 *       避免把精度 / 速度等正常标记位改坏。</li>
 * </ul>
 *
 * <p>不参与、也无法解决的检测手段见 InfoActivity 说明页与交付报告。
 */
public final class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "[VLocHidden] ";

    /** 框架类名，走目标进程的 ClassLoader 取（framework 类由 boot classloader 加载）。 */
    private static final String LOCATION_CLASS_NAME = "android.location.Location";

    /** 主 App：它自己注入位置，不需要（也不应该）在它进程里遮蔽标记。 */
    private static final String MAIN_APP_PACKAGE = "com.vloc.motion";

    /** 模块自身：说明页进程，跳过。 */
    private static final String MODULE_PACKAGE = "com.vloc.motion.hidden";

    /** system_server 进程名。系统服务内部逻辑不应被本模块影响，保守跳过。 */
    private static final String SYSTEM_SERVER_PACKAGE = "android";

    /** extra 中只要 key 含 "mock"（不区分大小写）就视为模拟位置残留，例如 "mockLocation"。 */
    private static final String MOCK_EXTRA_KEY_TOKEN = "mock";

    // ------------------------------------------------------------------
    // 旁证遮蔽 1：Settings 中历史的 "mock_location" 开关
    // ------------------------------------------------------------------

    /**
     * 历史设置项 Settings.Secure.ALLOW_MOCK_LOCATION 的 key。
     * Android 4.x 的语义是 1 = 允许模拟定位、0 = 不允许；Android 6 之后该设置在真机上通常
     * 已经不存在。因此这里按真机表现实现：getInt/getLong 返回 0，getString 返回 null
     * （与“设置缺失”时 getString 的真实返回值一致）。
     */
    private static final String MOCK_LOCATION_SETTING = "mock_location";

    /** Settings 的 Secure / System / Global 三个命名空间（部分 ROM / 检测库会跨命名空间乱查）。 */
    private static final String[] SETTINGS_CLASS_NAMES = {
            "android.provider.Settings$Secure",
            "android.provider.Settings$System",
            "android.provider.Settings$Global",
    };

    /** @hide 内部缓存类：Secure/System/Global 的读取最终都落到它的 getStringForUser，用来兜底。 */
    private static final String SETTINGS_CACHE_CLASS_NAME = "android.provider.Settings$NameValueCache";

    /** 需要核对的读取方法名（*ForUser 为 @hide，检测库常反射调用）。 */
    private static final String[] SETTINGS_GETTER_METHODS = {
            "getString", "getStringForUser",
            "getInt", "getIntForUser",
            "getLong", "getLongForUser",
    };

    // ------------------------------------------------------------------
    // 旁证遮蔽 2：AppOpsManager 对 android:mock_location 的自查
    // ------------------------------------------------------------------

    private static final String APP_OPS_CLASS_NAME = "android.app.AppOpsManager";

    /** 8 个自查方法（checkOpRaw / checkOpRawNoThrow 在部分版本是 @hide，缺失时自动跳过）。 */
    private static final String[] APP_OPS_CHECK_METHODS = {
            "checkOp", "checkOpNoThrow", "checkOpRaw", "checkOpRawNoThrow",
            "unsafeCheckOp", "unsafeCheckOpNoThrow", "unsafeCheckOpRaw", "unsafeCheckOpRawNoThrow",
    };

    /**
     * 命中 mock_location 之后对外表现出的模式。真机上“没有模拟位置权限”时框架默认就是
     * MODE_ERRORED(=2)，这里用常量引用（编译期内联为 2，低版本设备也不会去读新字段）。
     */
    private static final int MOCK_OP_MODE = AppOpsManager.MODE_ERRORED;

    /** 每个进程只打一次“已命中”日志，避免检测库轮询时刷爆 logcat。 */
    private static boolean sLoggedSettingsHit;
    private static boolean sLoggedAppOpsHit;
    private static boolean sLoggedQueryHit;
    private static boolean sLoggedCallHit;
    private static boolean sLoggedAppOpsEnumHit;
    private static boolean sLoggedAppOpsNoteHit;
    private static boolean sLoggedParcelHit;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (lpparam == null || lpparam.packageName == null) {
                return;
            }
            final String packageName = lpparam.packageName;
            if (MAIN_APP_PACKAGE.equals(packageName) || MODULE_PACKAGE.equals(packageName)) {
                return;
            }
            if (SYSTEM_SERVER_PACKAGE.equals(packageName)) {
                // 系统框架进程：安装系统级注入（定位 / GNSS 全 App 生效、无 mock 标记）。
                // 模块必须被手动加入「系统框架」作用域才会走到这里。
                installSystemHooks(lpparam.classLoader);
                return;
            }
            installHooks(lpparam.classLoader, packageName);
        } catch (Throwable t) {
            // 顶层兜底：模块绝不能因为自身异常影响目标 App 启动
            logError("handleLoadPackage failed", t);
        }
    }

    /**
     * system_server 内的系统级注入（v0.33.0）。
     *
     * <p>只做定位与 GNSS：传感器由 native SensorService 分发、相机在 HAL/相机服务层，
     * Java 层无法覆盖，仍由目标进程内 Hook 处理。
     */
    private void installSystemHooks(ClassLoader classLoader) {
        try {
            final int hooks = SystemHooks.install(classLoader);
            log("system-level hooks installed in system_server: " + hooks);
        } catch (Throwable t) {
            logError("installSystemHooks failed", t);
        }
    }

    /** 安装全部 hook，每个 hook 内部各自兜底，互不影响。 */
    private void installHooks(ClassLoader classLoader, String packageName) {
        final Class<?> locationClass;
        try {
            locationClass = XposedHelpers.findClass(LOCATION_CLASS_NAME, classLoader);
        } catch (Throwable t) {
            logError("findClass " + LOCATION_CLASS_NAME + " failed, skip process " + packageName, t);
            return;
        }

        int installed = 0;
        if (hookIsFromMockProvider(locationClass)) {
            installed++;
        }
        if (hookIsMock(locationClass)) {
            installed++;
        }
        if (hookMockSetter(locationClass, "setMock")) {                 // Android 12+ 公开 API
            installed++;
        }
        if (hookMockSetter(locationClass, "setIsFromMockProvider")) {   // Android 8+ @SystemApi，12 起废弃
            installed++;
        }
        if (hookGetExtras(locationClass)) {
            installed++;
        }

        // 旁证 1：Settings 里的 mock_location
        final int settingsHooks = hookSettings(classLoader);
        // 旁证 2：AppOpsManager 的 mock_location 自查（8 个 check 方法）
        final int appOpsHooks = hookAppOps(classLoader);
        // 旁路 3：AppOps 枚举 / noteOp / startOp / finishOp
        final int appOpsExtraHooks = hookAppOpsExtras(classLoader);
        // 旁路 4：ContentResolver 直查 settings provider
        final int resolverHooks = hookContentResolver(classLoader);
        // 旁路 5：Location 序列化时临时清掉 mock 位
        final int parcelHooks = hookLocationParcel(locationClass);
        // 旁路 6：基站 / Wi-Fi 环境快照回放（v0.4.0）
        final int envHooks = EnvReplay.install(classLoader);
        // 旁路 7：计步传感器回放（v0.4.x）
        final int stepHooks = StepReplay.install(classLoader);
        // 旁路 8：IMU（加速度计 / 线加速度 / 陀螺仪 / 重力）伪造
        final int imuHooks = ImuReplay.install(classLoader);
        // 旁路 9：GNSS 卫星状态回放（GnssStatus / 1Hz 回调，v0.18.x）
        final int gnssHooks = GnssReplay.install(classLoader);
        // 旁路 10：反检测补强（provider 列表 / 时间戳一致性 / 精度抖动 / NMEA，v0.38）
        final int antiDetectHooks = AntiDetect.install(classLoader);

        // 安装条数随 Android 版本自然变化（如 Android 11 上没有 isMock/setMock，4/5 属正常）
        log("hooks installed in " + packageName + " (sdk=" + Build.VERSION.SDK_INT
                + ", location=" + installed + "/5, settings=" + settingsHooks
                + ", appops_check=" + appOpsHooks + "/8, appops_extra=" + appOpsExtraHooks
                + ", resolver=" + resolverHooks + "/2, parcel=" + parcelHooks + "/1"
                + ", env=" + envHooks + "/5, step=" + stepHooks + "/2"
                + ", imu=" + imuHooks + "/2, gnss=" + gnssHooks + "/15"
                + ", anti_detect=" + antiDetectHooks + "/4)");

        // 注入成功：回报一次心跳，供主 App 区分“模块未注入 / 已注入但目标 App 不读传感器 / 正在读取”
        StepReplay.sendHeartbeatOnce();
    }

    /** Location#isFromMockProvider() → false（API 18+，Android 12+ 已废弃但大量 App 仍在用）。 */
    private boolean hookIsFromMockProvider(Class<?> locationClass) {
        try {
            XposedHelpers.findAndHookMethod(locationClass, "isFromMockProvider",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            return Boolean.FALSE;
                        }
                    });
            log("hooked Location#isFromMockProvider() -> false");
            return true;
        } catch (Throwable t) {
            // 部分精简 ROM 可能没有该方法，忽略
            log("skip Location#isFromMockProvider(): " + t);
            return false;
        }
    }

    /** Location#isMock() → false（Android 12 / API 31 起新增，Android 8~11 不存在则忽略）。 */
    private boolean hookIsMock(Class<?> locationClass) {
        try {
            XposedHelpers.findAndHookMethod(locationClass, "isMock",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            return Boolean.FALSE;
                        }
                    });
            log("hooked Location#isMock() -> false");
            return true;
        } catch (Throwable t) {
            log("skip Location#isMock() (API<31 正常): " + t);
            return false;
        }
    }

    /**
     * 把设置模拟标记的方法变成空操作：进程内任何代码（含 SDK）调用 setMock/setIsFromMockProvider
     * 都无法再把标记置为 true。方法不存在时忽略。
     */
    private boolean hookMockSetter(Class<?> locationClass, String methodName) {
        try {
            XposedHelpers.findAndHookMethod(locationClass, methodName, boolean.class,
                    XC_MethodReplacement.DO_NOTHING);
            log("hooked Location#" + methodName + "(boolean) -> no-op");
            return true;
        } catch (Throwable t) {
            log("skip Location#" + methodName + "(boolean): " + t);
            return false;
        }
    }

    /**
     * 加固：Location#getExtras() 返回的 Bundle 里若残留 "mockLocation" 一类 key，返回清洗后的副本。
     *
     * <p>说明：已核对 AOSP 8.1 ~ 14，框架本身不会把模拟标记写进 extras（标记在 mFieldsMask 位里），
     * 所以这个 hook 只针对第三方 SDK / 旧版本 / OEM 定制可能塞进 extras 的 mock 类 key，属防御性加固。
     *
     * <p>安全约束（避免给目标 App 制造 NPE）：
     * <ul>
     *   <li>原返回值为 null → 原样返回 null，绝不 new 一个空 Bundle 顶替；</li>
     *   <li>先复制 Bundle 再删 key，不修改框架持有的原对象（避免影响同一 Location 的其他读者）；</li>
     *   <li>只在确实命中 mock key 时才替换返回值，未命中时返回原对象，行为零变化；</li>
     *   <li>整个过程 try/catch，异常时保留原返回值。</li>
     * </ul>
     */
    private boolean hookGetExtras(Class<?> locationClass) {
        try {
            XposedHelpers.findAndHookMethod(locationClass, "getExtras", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        final Object result = param.getResult();
                        if (!(result instanceof Bundle)) {
                            return; // null 或非 Bundle：保持原样
                        }
                        final Bundle original = (Bundle) result;
                        final List<String> mockKeys = collectMockKeys(original);
                        if (mockKeys.isEmpty()) {
                            return;
                        }
                        final Bundle cleaned = new Bundle(original);
                        for (String key : mockKeys) {
                            cleaned.remove(key);
                        }
                        param.setResult(cleaned);
                    } catch (Throwable ignored) {
                        // 清洗失败时保留原返回值：宁可少隐藏一点，也不能让目标 App 崩
                    }
                }
            });
            log("hooked Location#getExtras() -> strip mock extras");
            return true;
        } catch (Throwable t) {
            log("skip Location#getExtras(): " + t);
            return false;
        }
    }

    private static List<String> collectMockKeys(Bundle bundle) {
        final List<String> keys = new ArrayList<>();
        if (bundle.isEmpty()) {
            return keys;
        }
        // 只读遍历（不在遍历时删除），拿到 key 列表后再对副本删除
        for (String key : bundle.keySet()) {
            if (isMockExtraKey(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static boolean isMockExtraKey(String key) {
        return key != null
                && key.toLowerCase(Locale.ROOT).contains(MOCK_EXTRA_KEY_TOKEN);
    }


    // ==================================================================
    // Settings 旁证：mock_location 读取一律表现为“不允许”(0 / "0")
    // ==================================================================

    /**
     * Settings 读取拦截回调。全 App 高频调用路径，因此只对 name 命中的那一次改写返回值，
     * 其余 key / 其余参数一律放行；任何异常都静默吞掉，绝不把模块异常抛给目标 App。
     */
    private static final XC_MethodHook SETTINGS_MOCK_CALLBACK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                final Object[] args = param.args;
                if (args == null || args.length < 2) {
                    return;
                }
                final Object nameArg = args[1];
                if (!(nameArg instanceof String)
                        || !MOCK_LOCATION_SETTING.equalsIgnoreCase((String) nameArg)) {
                    return;
                }
                if (!(param.method instanceof Method)) {
                    return;
                }
                final Class<?> returnType = ((Method) param.method).getReturnType();
                if (returnType == String.class) {
                    // Android 6+ 这条设置在真机上通常已经不存在，getString 的真实返回值就是 null；
                    // 返回 "0" 反而像“老设备上被显式关掉”，对“设置是否存在”型启发式更不自然。
                    param.setResult(null);
                    logSettingsHitOnce(param.method);
                    return;
                }
                final Object replacement = mockSettingValue(returnType);
                if (replacement == null) {
                    return; // 未知返回类型：宁可放行，也不猜
                }
                param.setResult(replacement);
                logSettingsHitOnce(param.method);
            } catch (Throwable ignored) {
                // 拦截判断失败时目标 App 继续走原逻辑
            }
        }
    };

    /**
     * 安装 Settings 相关 hook。
     *
     * @return 成功 hook 到的方法名数量（用于日志汇总）
     */
    private int hookSettings(ClassLoader classLoader) {
        int hookedMethods = 0;
        for (String className : SETTINGS_CLASS_NAMES) {
            hookedMethods += hookSettingsGetters(classLoader, className);
        }
        // 兜底：某些版本 / ROM 的公开 getter 调用链不同，内部缓存仍会被这里拦住
        hookedMethods += hookSettingsCache(classLoader);
        log("settings hooks=" + hookedMethods + " (mock_location -> 0 for int/long, null for string)");
        return hookedMethods;
    }

    /** 挂一个 Settings 命名空间（Secure / System / Global）上的全部读取方法。 */
    private int hookSettingsGetters(ClassLoader classLoader, String className) {
        final Class<?> settingsClass;
        try {
            settingsClass = XposedHelpers.findClassIfExists(className, classLoader);
        } catch (Throwable t) {
            log("skip " + className + ": " + t);
            return 0;
        }
        if (settingsClass == null) {
            log("skip " + className + " (class not found)");
            return 0;
        }

        int hookedNames = 0;
        for (String methodName : SETTINGS_GETTER_METHODS) {
            try {
                // hookAllMethods 会覆盖同名重载，且方法不存在时返回空集合而不是抛异常
                final int overloads = XposedBridge.hookAllMethods(
                        settingsClass, methodName, SETTINGS_MOCK_CALLBACK).size();
                if (overloads > 0) {
                    hookedNames++;
                    log("hooked " + className + "#" + methodName + " (" + overloads + " overload(s))");
                }
            } catch (Throwable t) {
                log("skip " + className + "#" + methodName + ": " + t);
            }
        }
        if (hookedNames == 0) {
            log("skip " + className + " (no getter matched)");
        }
        return hookedNames;
    }

    /** @hide 的 Settings$NameValueCache#getStringForUser，作最后一道兜底。 */
    private int hookSettingsCache(ClassLoader classLoader) {
        try {
            final Class<?> cacheClass =
                    XposedHelpers.findClassIfExists(SETTINGS_CACHE_CLASS_NAME, classLoader);
            if (cacheClass == null) {
                log("skip " + SETTINGS_CACHE_CLASS_NAME + " (class not found)");
                return 0;
            }
            final int overloads = XposedBridge.hookAllMethods(
                    cacheClass, "getStringForUser", SETTINGS_MOCK_CALLBACK).size();
            if (overloads > 0) {
                log("hooked " + SETTINGS_CACHE_CLASS_NAME + "#getStringForUser (" + overloads + ")");
                return 1;
            }
            log("skip " + SETTINGS_CACHE_CLASS_NAME + "#getStringForUser (absent)");
        } catch (Throwable t) {
            log("skip " + SETTINGS_CACHE_CLASS_NAME + ": " + t);
        }
        return 0;
    }

    /**
     * 数值型读取方法的等价值：getInt/getLong 返回 0（历史语义 0 = 不允许模拟；缺失时常见调用
     * def=0 的真机返回值同样是 0）。String 类型不在这里处理——调用方直接返回 null，与真机
     * “设置不存在”一致。未知返回类型返回 null，由调用方放行原逻辑。
     */
    private static Object mockSettingValue(Class<?> returnType) {
        if (returnType == int.class) {
            return Integer.valueOf(0);
        }
        if (returnType == long.class) {
            return Long.valueOf(0L);
        }
        if (returnType == float.class) {
            return Float.valueOf(0F);
        }
        if (returnType == boolean.class) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static void logSettingsHitOnce(Member method) {
        if (sLoggedSettingsHit) {
            return;
        }
        sLoggedSettingsHit = true;
        log("settings " + MOCK_LOCATION_SETTING + " read hidden -> "
                + (method == null ? "?" : "0/null") + " (first hit: " + method + ")");
    }

    // ==================================================================
    // AppOps 旁证：android:mock_location 的自查一律表现为“无权限”
    // ==================================================================

    /**
     * 安装 AppOpsManager 的 mock_location 自查 hook。
     *
     * <p>只拦 mock_location 这一个 op，其它 op（精确位置、相机……）完全放行。
     * NoThrow / Raw 变体返回 MODE_ERRORED；checkOp / unsafeCheckOp 按框架语义抛 SecurityException
     * （真机上“没有 mock 权限”时它们本来就会抛），这样 try/catch 风格的检测库也会得到同样的结论。
     */
    private int hookAppOps(ClassLoader classLoader) {
        final Class<?> appOpsClass;
        try {
            appOpsClass = XposedHelpers.findClassIfExists(APP_OPS_CLASS_NAME, classLoader);
        } catch (Throwable t) {
            log("skip " + APP_OPS_CLASS_NAME + ": " + t);
            return 0;
        }
        if (appOpsClass == null) {
            log("skip " + APP_OPS_CLASS_NAME + " (class not found)");
            return 0;
        }

        // 老版本把 op 编码成 int，顺手解出 OP_MOCK_LOCATION 以覆盖 int 重载（解析失败则 -1）
        final int mockOpInt = resolveMockOpInt(appOpsClass);
        final XC_MethodHook callback = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    final Object[] args = param.args;
                    if (args == null || args.length < 1 || !(param.method instanceof Method)) {
                        return;
                    }
                    final Method method = (Method) param.method;
                    if (method.getReturnType() != int.class) {
                        return;
                    }
                    final Object op = args[0];
                    final boolean isMockOp;
                    if (op instanceof String) {
                        isMockOp = AppOpsManager.OPSTR_MOCK_LOCATION.equals(op);
                    } else if (op instanceof Integer) {
                        isMockOp = mockOpInt >= 0 && ((Integer) op).intValue() == mockOpInt;
                    } else {
                        isMockOp = false;
                    }
                    if (!isMockOp) {
                        return;
                    }
                    if (isThrowingCheck(method.getName())) {
                        // 与真机“没有 mock 权限”一致：抛 SecurityException 而不是返回
                        param.setThrowable(new SecurityException(
                                APP_OPS_CLASS_NAME + "." + method.getName()
                                        + ": op=" + op + " mode=" + MOCK_OP_MODE));
                    } else {
                        param.setResult(Integer.valueOf(MOCK_OP_MODE));
                    }
                    logAppOpsHitOnce(method);
                } catch (Throwable ignored) {
                    // 拦截失败就放行原逻辑
                }
            }
        };

        int hooked = 0;
        for (String methodName : APP_OPS_CHECK_METHODS) {
            try {
                final int overloads = XposedBridge.hookAllMethods(appOpsClass, methodName, callback).size();
                if (overloads > 0) {
                    hooked++;
                    log("hooked " + APP_OPS_CLASS_NAME + "#" + methodName + " (" + overloads + ")");
                } else {
                    log("skip " + APP_OPS_CLASS_NAME + "#" + methodName + " (absent on this Android)");
                }
            } catch (Throwable t) {
                log("skip " + APP_OPS_CLASS_NAME + "#" + methodName + ": " + t);
            }
        }
        log("appops hooks=" + hooked + "/8 (mock_location -> MODE_ERRORED)");
        return hooked;
    }

    /** checkOp / unsafeCheckOp 在 MODE_ERRORED 时本来就会抛 SecurityException，需保持一致。 */
    private static boolean isThrowingCheck(String methodName) {
        return "checkOp".equals(methodName) || "unsafeCheckOp".equals(methodName);
    }

    /** 解出 @hide 的 AppOpsManager.OP_MOCK_LOCATION，用于兼容以 int op 调用自查的老代码。 */
    private static int resolveMockOpInt(Class<?> appOpsClass) {
        try {
            final Field field = XposedHelpers.findFieldIfExists(appOpsClass, "OP_MOCK_LOCATION");
            if (field == null) {
                return -1;
            }
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Throwable t) {
            log("OP_MOCK_LOCATION not readable: " + t);
            return -1;
        }
    }

    private static void logAppOpsHitOnce(Member method) {
        if (sLoggedAppOpsHit) {
            return;
        }
        sLoggedAppOpsHit = true;
        log("appops " + AppOpsManager.OPSTR_MOCK_LOCATION + " check -> MODE_ERRORED (first: " + method + ")");
    }

    // ==================================================================
    // ContentProvider 旁路：绕过 Settings 封装、直查 settings provider
    // ==================================================================

    /** Settings provider 的 authority（个别 ROM 会带包名前缀形式，一并处理）。 */
    private static final String[] SETTINGS_AUTHORITIES = {"settings", "com.android.providers.settings"};

    /** settings provider 里会被本模块处理的命名空间。 */
    private static final String[] SETTINGS_NAMESPACES = {"secure", "system", "global"};

    /**
     * 直查单项 URI（content://settings/secure/mock_location）时，把设置名换成这个一定不存在的值，
     * 由系统自己返回空 Cursor —— 不自己造 Cursor，避免列 / 投影不匹配把目标 App 搞崩。
     */
    private static final String ABSENT_SETTING_NAME = "__vloc_absent_setting__";

    /**
     * ContentResolver#query 拦截：只处理 settings provider 的 secure/system/global，
     * 且条件（selection / selectionArgs / Bundle 查询参数）里点名 mock_location 的查询。
     * 做法是把条件改成恒假 "0" 并清空参数，让 provider 自然返回空 Cursor（等价于“该设置不存在”）。
     */
    private static final XC_MethodHook QUERY_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                final Object[] args = param.args;
                if (args == null || args.length < 3 || !(args[0] instanceof Uri)) {
                    return;
                }
                final Uri uri = (Uri) args[0];
                if (!isSettingsNamespaceUri(uri)) {
                    return;
                }

                // 1) 直查单项 URI：content://settings/{secure|system|global}/mock_location
                if (isMockLocationItemUri(uri)) {
                    args[0] = rewriteToAbsentSetting(uri);
                    logQueryHitOnce("item-uri");
                    return;
                }

                // 2) Bundle 形式的查询参数（query-args 变体）
                if (args[2] instanceof Bundle) {
                    final Bundle queryArgs = (Bundle) args[2];
                    final String selection = queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SELECTION);
                    final String[] selectionArgs =
                            queryArgs.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS);
                    if (!mentionsMockLocation(selection) && !mentionsMockLocation(selectionArgs)) {
                        return;
                    }
                    final Bundle copy = new Bundle(queryArgs);
                    copy.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "0");
                    copy.remove(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS);
                    args[2] = copy;
                    logQueryHitOnce("bundle");
                    return;
                }

                // 3) 传统 (selection, selectionArgs) 形式：名字可能写在 selection 里，也可能只在参数里
                final String selection = args[2] instanceof String ? (String) args[2] : null;
                final String[] selectionArgs = args.length > 3 && args[3] instanceof String[]
                        ? (String[]) args[3] : null;
                if (!mentionsMockLocation(selection) && !mentionsMockLocation(selectionArgs)) {
                    return;
                }
                args[2] = "0";   // 恒假条件 → 空 Cursor
                args[3] = null;  // 同时清空参数，避免占位符与参数个数不匹配
                logQueryHitOnce("selection");
            } catch (Throwable ignored) {
                // 拦截失败就放行原查询
            }
        }
    };

    /**
     * ContentResolver#call 拦截：settings provider 的 GET_* 且参数是 mock_location 时返回 null
     * （真机缺该设置时 provider 无值可取）。
     */
    private static final XC_MethodHook CALL_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                final Object[] args = param.args;
                if (args == null || args.length < 3) {
                    return;
                }
                final String authority;
                if (args[0] instanceof Uri) {
                    authority = ((Uri) args[0]).getAuthority();
                } else if (args[0] instanceof String) {
                    authority = (String) args[0];
                } else {
                    return;
                }
                if (!isSettingsAuthority(authority)) {
                    return;
                }
                if (!(args[1] instanceof String) || !(args[2] instanceof String)) {
                    return;
                }
                if (!((String) args[1]).startsWith("GET_")) {
                    return;
                }
                if (!MOCK_LOCATION_SETTING.equalsIgnoreCase((String) args[2])) {
                    return;
                }
                // 真机 SettingsProvider.call 对不存在的设置返回 Bundle{value=null}，
                // 返回 null 会让"不判空直接 getString"的检测代码 NPE，反而暴露异常行为
                final Bundle missingValue = new Bundle();
                missingValue.putString("value", null);
                param.setResult(missingValue);
                logCallHitOnce();
            } catch (Throwable ignored) {
                // 拦截失败就放行原调用
            }
        }
    };

    /** 安装 ContentResolver 的 query / call hook。 */
    private int hookContentResolver(ClassLoader classLoader) {
        final Class<?> resolverClass;
        try {
            resolverClass = XposedHelpers.findClass("android.content.ContentResolver", classLoader);
        } catch (Throwable t) {
            log("skip ContentResolver: " + t);
            return 0;
        }
        int hooked = 0;
        try {
            final int queryOverloads = XposedBridge.hookAllMethods(resolverClass, "query", QUERY_HOOK).size();
            if (queryOverloads > 0) {
                hooked++;
                log("hooked ContentResolver#query (" + queryOverloads + " overload(s))");
            }
        } catch (Throwable t) {
            log("skip ContentResolver#query: " + t);
        }
        try {
            final int callOverloads = XposedBridge.hookAllMethods(resolverClass, "call", CALL_HOOK).size();
            if (callOverloads > 0) {
                hooked++;
                log("hooked ContentResolver#call (" + callOverloads + " overload(s))");
            }
        } catch (Throwable t) {
            log("skip ContentResolver#call: " + t);
        }
        log("resolver hooks=" + hooked + "/2 (settings provider mock_location hidden)");
        return hooked;
    }

    private static boolean isSettingsAuthority(String authority) {
        if (authority == null) {
            return false;
        }
        for (String candidate : SETTINGS_AUTHORITIES) {
            if (candidate.equalsIgnoreCase(authority)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSettingsNamespaceUri(Uri uri) {
        if (uri == null || !isSettingsAuthority(uri.getAuthority())) {
            return false;
        }
        try {
            final List<String> segments = uri.getPathSegments();
            if (segments == null || segments.isEmpty()) {
                return false;
            }
            final String namespace = segments.get(0);
            for (String candidate : SETTINGS_NAMESPACES) {
                if (candidate.equalsIgnoreCase(namespace)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isMockLocationItemUri(Uri uri) {
        try {
            final List<String> segments = uri.getPathSegments();
            return segments != null && segments.size() >= 2
                    && MOCK_LOCATION_SETTING.equalsIgnoreCase(segments.get(1));
        } catch (Throwable t) {
            return false;
        }
    }

    private static Uri rewriteToAbsentSetting(Uri uri) {
        final List<String> segments = uri.getPathSegments();
        final String namespace = segments.get(0);
        return uri.buildUpon().path(namespace + "/" + ABSENT_SETTING_NAME).build();
    }

    private static boolean mentionsMockLocation(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(MOCK_LOCATION_SETTING);
    }

    private static boolean mentionsMockLocation(String[] values) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (mentionsMockLocation(value)) {
                return true;
            }
        }
        return false;
    }

    private static void logQueryHitOnce(String via) {
        if (sLoggedQueryHit) {
            return;
        }
        sLoggedQueryHit = true;
        log("settings provider query hidden (via " + via + ")");
    }

    private static void logCallHitOnce() {
        if (sLoggedCallHit) {
            return;
        }
        sLoggedCallHit = true;
        log("settings provider call(" + MOCK_LOCATION_SETTING + ") -> null (first hit)");
    }

    // ==================================================================
    // AppOps 旁路：枚举（getPackagesForOps / getOpsForPackage）与 noteOp 系列
    // ==================================================================

    private static final String[] APP_OPS_ENUM_METHODS = {"getPackagesForOps", "getOpsForPackage"};

    private static final String[] APP_OPS_NOTE_METHODS = {
            "noteOp", "noteOpNoThrow", "startOp", "startOpNoThrow", "finishOp",
    };

    /**
     * 枚举旁路：把调用方请求里的 mock op 从入参数组剔除；只剩 mock op 时直接返回空 List。
     *
     * @return 成功 hook 到的方法名数量
     */
    private int hookAppOpsEnumeration(Class<?> appOpsClass, int mockOpInt) {
        final XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    final Object[] args = param.args;
                    if (args == null || args.length == 0) {
                        return;
                    }
                    int arrayIndex = -1;
                    for (int i = args.length - 1; i >= 0; i--) {
                        if (args[i] instanceof int[] || args[i] instanceof String[]) {
                            arrayIndex = i;
                            break;
                        }
                    }
                    if (arrayIndex < 0) {
                        return; // 没有 op 数组：ops 为 null 表示“全量查询”，保持原样
                    }
                    final Object opsArg = args[arrayIndex];
                    if (opsArg == null) {
                        return;
                    }
                    if (opsArg instanceof int[]) {
                        if (mockOpInt < 0) {
                            return; // 解不出 OP_MOCK_LOCATION：只处理 String 重载
                        }
                        final int[] ops = (int[]) opsArg;
                        int keptCount = 0;
                        for (int op : ops) {
                            if (op != mockOpInt) {
                                keptCount++;
                            }
                        }
                        if (keptCount == ops.length) {
                            return;
                        }
                        if (keptCount == 0) {
                            param.setResult(Collections.emptyList());
                            logAppOpsEnumHitOnce(param.method);
                            return;
                        }
                        final int[] filtered = new int[keptCount];
                        int index = 0;
                        for (int op : ops) {
                            if (op != mockOpInt) {
                                filtered[index++] = op;
                            }
                        }
                        args[arrayIndex] = filtered;
                        logAppOpsEnumHitOnce(param.method);
                    } else {
                        final String[] ops = (String[]) opsArg;
                        int keptCount = 0;
                        for (String op : ops) {
                            if (!AppOpsManager.OPSTR_MOCK_LOCATION.equals(op)) {
                                keptCount++;
                            }
                        }
                        if (keptCount == ops.length) {
                            return;
                        }
                        if (keptCount == 0) {
                            param.setResult(Collections.emptyList());
                            logAppOpsEnumHitOnce(param.method);
                            return;
                        }
                        final String[] filtered = new String[keptCount];
                        int index = 0;
                        for (String op : ops) {
                            if (!AppOpsManager.OPSTR_MOCK_LOCATION.equals(op)) {
                                filtered[index++] = op;
                            }
                        }
                        args[arrayIndex] = filtered;
                        logAppOpsEnumHitOnce(param.method);
                    }
                } catch (Throwable ignored) {
                    // 拦截失败就放行原调用
                }
            }
        };

        int hooked = 0;
        for (String methodName : APP_OPS_ENUM_METHODS) {
            try {
                final int overloads = XposedBridge.hookAllMethods(appOpsClass, methodName, hook).size();
                if (overloads > 0) {
                    hooked++;
                    log("hooked " + APP_OPS_CLASS_NAME + "#" + methodName + " (" + overloads + ")");
                } else {
                    log("skip " + APP_OPS_CLASS_NAME + "#" + methodName + " (absent on this Android)");
                }
            } catch (Throwable t) {
                log("skip " + APP_OPS_CLASS_NAME + "#" + methodName + ": " + t);
            }
        }
        log("appops enumeration hooks=" + hooked + "/2 (mock op stripped from request)");
        return hooked;
    }

    /**
     * noteOp / startOp / finishOp 旁路：mock op 时按“无权限”处理——
     * throwing 变体抛 SecurityException，*NoThrow 返回 MODE_ERRORED，finishOp 直接忽略。
     *
     * @return 成功 hook 到的方法名数量
     */
    private int hookAppOpsNoteAndStart(Class<?> appOpsClass, int mockOpInt) {
        final XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    final Object[] args = param.args;
                    if (args == null || args.length == 0 || !(param.method instanceof Method)) {
                        return;
                    }
                    if (!isMockOpArg(args[0], mockOpInt)) {
                        return;
                    }
                    final Method method = (Method) param.method;
                    final String methodName = method.getName();
                    if ("finishOp".equals(methodName)) {
                        param.setResult(null); // 空操作
                    } else if (methodName.endsWith("NoThrow")) {
                        param.setResult(Integer.valueOf(MOCK_OP_MODE));
                    } else {
                        param.setThrowable(new SecurityException(
                                APP_OPS_CLASS_NAME + "." + methodName
                                        + ": op=" + args[0] + " mode=" + MOCK_OP_MODE));
                    }
                    logAppOpsNoteHitOnce(method);
                } catch (Throwable ignored) {
                    // 拦截失败就放行原调用
                }
            }
        };

        int hooked = 0;
        for (String methodName : APP_OPS_NOTE_METHODS) {
            try {
                final int overloads = XposedBridge.hookAllMethods(appOpsClass, methodName, hook).size();
                if (overloads > 0) {
                    hooked++;
                    log("hooked " + APP_OPS_CLASS_NAME + "#" + methodName + " (" + overloads + ")");
                } else {
                    log("skip " + APP_OPS_CLASS_NAME + "#" + methodName + " (absent on this Android)");
                }
            } catch (Throwable t) {
                log("skip " + APP_OPS_CLASS_NAME + "#" + methodName + ": " + t);
            }
        }
        log("appops note/start/finish hooks=" + hooked + "/5");
        return hooked;
    }

    /** 安装 AppOps 的枚举与 noteOp 旁路（与 8 个 check hook 分开安装，互不影响）。 */
    private int hookAppOpsExtras(ClassLoader classLoader) {
        final Class<?> appOpsClass;
        try {
            appOpsClass = XposedHelpers.findClassIfExists(APP_OPS_CLASS_NAME, classLoader);
        } catch (Throwable t) {
            log("skip " + APP_OPS_CLASS_NAME + " extras: " + t);
            return 0;
        }
        if (appOpsClass == null) {
            log("skip " + APP_OPS_CLASS_NAME + " extras (class not found)");
            return 0;
        }
        final int mockOpInt = resolveMockOpInt(appOpsClass);
        return hookAppOpsEnumeration(appOpsClass, mockOpInt)
                + hookAppOpsNoteAndStart(appOpsClass, mockOpInt);
    }

    private static boolean isMockOpArg(Object op, int mockOpInt) {
        if (op instanceof String) {
            return AppOpsManager.OPSTR_MOCK_LOCATION.equals(op);
        }
        if (op instanceof Integer) {
            return mockOpInt >= 0 && ((Integer) op).intValue() == mockOpInt;
        }
        return false;
    }

    private static void logAppOpsEnumHitOnce(Member method) {
        if (sLoggedAppOpsEnumHit) {
            return;
        }
        sLoggedAppOpsEnumHit = true;
        log("appops enumeration request had mock op stripped (first: " + method + ")");
    }

    private static void logAppOpsNoteHitOnce(Member method) {
        if (sLoggedAppOpsNoteHit) {
            return;
        }
        sLoggedAppOpsNoteHit = true;
        log("appops " + AppOpsManager.OPSTR_MOCK_LOCATION + " note/start/finish hidden (first: "
                + method + ")");
    }

    // ==================================================================
    // Parcel 旁路：Location 序列化时临时清掉 mock 位
    // ==================================================================

    /** AOSP 8.1~14 的 HAS_MOCK_PROVIDER_MASK 都是 1<<4=16；反射不到常量时用这个兜底。 */
    private static final int FALLBACK_HAS_MOCK_PROVIDER_MASK = 1 << 4;

    private static final String PARCEL_MASK_FIELD = "mFieldsMask";
    private static final String PARCEL_LEGACY_MOCK_FIELD = "mIsFromMockProvider";

    /** 暂存“写之前 mock 位是否为 1”，写完只把这一位恢复，避免覆盖并发修改的其它掩码位。 */
    private static final ThreadLocal<Boolean> PARCEL_MASK_WAS_SET = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PARCEL_LEGACY_WAS_SET = new ThreadLocal<>();

    /**
     * Location#writeToParcel 旁路：序列化之前临时把 mock 位清掉，序列化完成立刻只恢复这一位。
     * 其它掩码位（精度 / 速度 / 海拔……）全程不动；即使原方法抛异常，after 钩子也会恢复。
     *
     * @return 成功 hook 的数量（0 或 1）
     */
    private int hookLocationParcel(Class<?> locationClass) {
        Field maskField = null;
        try {
            maskField = XposedHelpers.findFieldIfExists(locationClass, PARCEL_MASK_FIELD);
            if (maskField != null) {
                maskField.setAccessible(true);
            }
        } catch (Throwable t) {
            log("skip " + PARCEL_MASK_FIELD + ": " + t);
        }
        Field legacyField = null;
        try {
            legacyField = XposedHelpers.findFieldIfExists(locationClass, PARCEL_LEGACY_MOCK_FIELD);
            if (legacyField != null) {
                legacyField.setAccessible(true);
            }
        } catch (Throwable t) {
            log("skip " + PARCEL_LEGACY_MOCK_FIELD + ": " + t);
        }
        if (maskField == null && legacyField == null) {
            log("skip Location#writeToParcel (no mock flag field found)");
            return 0;
        }

        final Field fMask = maskField;
        final Field fLegacy = legacyField;
        final int mockMask = resolveMockMask(locationClass);
        try {
            XposedHelpers.findAndHookMethod(locationClass, "writeToParcel", Parcel.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                final Object location = param.thisObject;
                                if (location == null) {
                                    return;
                                }
                                if (fMask != null) {
                                    final int mask = fMask.getInt(location);
                                    final boolean wasSet = (mask & mockMask) != 0;
                                    PARCEL_MASK_WAS_SET.set(Boolean.valueOf(wasSet));
                                    if (wasSet) {
                                        fMask.setInt(location, mask & ~mockMask);
                                    }
                                }
                                if (fLegacy != null) {
                                    final boolean wasSet = fLegacy.getBoolean(location);
                                    PARCEL_LEGACY_WAS_SET.set(Boolean.valueOf(wasSet));
                                    if (wasSet) {
                                        fLegacy.setBoolean(location, false);
                                    }
                                }
                            } catch (Throwable ignored) {
                                // 清位失败就按原值序列化，不影响目标 App
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            final Boolean maskWasSet = PARCEL_MASK_WAS_SET.get();
                            PARCEL_MASK_WAS_SET.remove();
                            final Boolean legacyWasSet = PARCEL_LEGACY_WAS_SET.get();
                            PARCEL_LEGACY_WAS_SET.remove();
                            try {
                                final Object location = param.thisObject;
                                if (location == null) {
                                    return;
                                }
                                if (fMask != null && Boolean.TRUE.equals(maskWasSet)) {
                                    // 只把 mock 位加回去，其它位以当前值为准（不覆盖并发写入）
                                    fMask.setInt(location, fMask.getInt(location) | mockMask);
                                }
                                if (fLegacy != null && Boolean.TRUE.equals(legacyWasSet)) {
                                    fLegacy.setBoolean(location, true);
                                }
                                if (Boolean.TRUE.equals(maskWasSet) || Boolean.TRUE.equals(legacyWasSet)) {
                                    logParcelHitOnce();
                                }
                            } catch (Throwable ignored) {
                                // 恢复失败也不能抛出：序列化已经完成
                            }
                        }
                    });
            log("hooked Location#writeToParcel (mock bit hidden during serialization)");
            return 1;
        } catch (Throwable t) {
            log("skip Location#writeToParcel: " + t);
            return 0;
        }
    }

    private static int resolveMockMask(Class<?> locationClass) {
        try {
            final Field field = XposedHelpers.findFieldIfExists(locationClass, "HAS_MOCK_PROVIDER_MASK");
            if (field != null) {
                field.setAccessible(true);
                return field.getInt(null);
            }
        } catch (Throwable t) {
            log("HAS_MOCK_PROVIDER_MASK not readable: " + t);
        }
        return FALLBACK_HAS_MOCK_PROVIDER_MASK;
    }

    private static void logParcelHitOnce() {
        if (sLoggedParcelHit) {
            return;
        }
        sLoggedParcelHit = true;
        log("Location writeToParcel hidden mock bit (first hit)");
    }

    // ==================================================================
    // 日志
    // ==================================================================

    private static void log(String message) {
        try {
            XposedBridge.log(TAG + message);
        } catch (Throwable ignored) {
            // 日志通道异常时不能影响目标 App
        }
    }

    private static void logError(String message, Throwable t) {
        log(message + ": " + t);
        try {
            XposedBridge.log(t);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
