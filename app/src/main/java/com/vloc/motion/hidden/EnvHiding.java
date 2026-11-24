package com.vloc.motion.hidden;

import android.content.pm.PackageManager;

import com.vloc.motion.hidden.compat.Callback;
import com.vloc.motion.hidden.compat.Param;
import com.vloc.motion.hidden.compat.Xp;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 启动期「静态环境」遮蔽（v0.46.6）：目标 App 往往在 {@code Application.onCreate} 之前
 * （甚至 {@code ContentProvider.onCreate} 里）就把「本机是不是干净」查一遍，命中即判
 * “数据异常”。本类只做<b>进程内</b>遮蔽，所有改写都发生在目标 App 进程里，<b>绝不</b>进入
 * system_server（安装点只有 {@link HookEntry#installHooks}，而它对
 * {@code com.vloc.motion / com.vloc.motion.hidden / android} 三个进程直接 return）。
 *
 * <p>与 {@link AntiDetect} 的分工：{@code AntiDetect} 处理「正在模拟定位时」的坐标指纹，
 * 用 {@code StepReplay.snapshotActive()} 门控；本类处理的是「还没开始模拟、甚至永远不会模拟」
 * 时的环境指纹，因此<b>不</b>看快照状态，装上即生效。
 *
 * <p>覆盖项（与 docs/mock-detection.md §2.8 对应）：
 * <ol>
 *   <li><b>包名 / 框架痕迹</b>（A）：{@code PackageManager} 的安装包列表过滤 + 包查询抛
 *       {@link PackageManager.NameNotFoundException}，隐藏本模块、LSPosed 管理器、
 *       Hide My Applist；</li>
 *   <li><b>开发者选项 / 模拟位置开关</b>（B）：{@code Settings.Global} 的
 *       {@code development_settings_enabled}、{@code Settings.Secure} 的
 *       {@code mock_location} 一律表现为 0 / "0"，其余 key 原样透传；</li>
 *   <li><b>AppOps 的 mock op 痕迹</b>（C）：{@code checkOp*} 命中 mock op 且原值为
 *       {@code MODE_ALLOWED} 时降级为 {@code MODE_ERRORED}（真机“未授予”的原值），
 *       {@code getOpsForPackage / getPackagesForOps} 的入参和结果都剔除 mock op；</li>
 *   <li><b>调试器 / ADB</b>（D）：{@code Debug.isDebuggerConnected()} → false，
 *       {@code Settings.Global.adb_enabled} → 0；</li>
 * </ol>
 *
 * <p><b>安全契约</b>：
 * <ul>
 *   <li>每条 hook 独立 {@code try/catch}，安装失败只记一条 {@code hook candidate …} 日志；</li>
 *   <li>改写只针对「命中名单的包名 / 指定的 key / mock op」，其余调用<b>完全不碰</b>
 *       （不调 {@code setResult}，原方法照常执行），避免误伤目标 App 的其它逻辑；</li>
 *   <li>列表过滤失败（元素取不到包名、返回 null 等）一律回退原值，绝不把 null 传给调用方。</li>
 * </ul>
 *
 * <p><b>为什么 AppOps 返回 {@code MODE_ERRORED} 而不是任务书里的 {@code MODE_DEFAULT(0)}</b>：
 * {@code MODE_ALLOWED=0 / MODE_IGNORED=1 / MODE_ERRORED=2 / MODE_DEFAULT=3}（android-35
 * {@code android.jar} 的 javap 常量）。0 恰好是「root 测试 Provider 被授予 mock 权限」时
 * {@code checkOp} 的原值，返回 0 等于把痕迹主动亮给检测方；真机未授予时框架解析出的就是
 * {@code MODE_ERRORED(2)}，且 {@code LocationManagerService} 的默认值也是 MODE_ERRORED
 * （见 {@code scripts/root_grant_mock.sh} 注释）。{@link HookEntry} 现有的 8 槽 AppOps 自查
 * 同样用 MODE_ERRORED，这里保持一致，避免同一个进程里两套 hook 互相回退。
 */
final class EnvHiding {

    private EnvHiding() {
    }

    // ================================================================ 常量

    /** 需要从目标 App 眼里“消失”的包名。 */
    private static final String[] HIDDEN_PACKAGES = {
            "com.vloc.motion",           // 主 App（单 APK 双身份时同时是模块）
            "com.vloc.motion.hidden",    // 旧版独立模块 APK（hidden/ 子工程）
            "org.lsposed.manager",       // LSPosed 管理器
            "io.github.lsposed.manager", // LSPosed 管理器（部分分支/旧名）
            "com.tsng.hidemyapplist",    // Hide My Applist
    };

    private static final Set<String> HIDDEN_PACKAGE_SET = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(HIDDEN_PACKAGES)));

    /** 遮蔽分类数（安装日志里的分母）。 */
    private static final int CATEGORY_COUNT = 7;

    // ---- Settings key（用字面值，避免跨版本常量被裁掉） ----

    /** {@code Settings.Global.DEVELOPMENT_SETTINGS_ENABLED}。 */
    private static final String KEY_DEVELOPMENT_SETTINGS = "development_settings_enabled";
    /** {@code Settings.Secure.ALLOW_MOCK_LOCATION}（Android 6 起废弃，检测方仍在读）。 */
    private static final String KEY_MOCK_LOCATION = "mock_location";
    /** {@code Settings.Global.ADB_ENABLED}。 */
    private static final String KEY_ADB_ENABLED = "adb_enabled";

    /** {@code AppOpsManager.OPSTR_MOCK_LOCATION}；用字面值避免在极老 ROM 上加载该类。 */
    private static final String OPSTR_MOCK_LOCATION = "android:mock_location";

    /** {@code AppOpsManager.OP_MOCK_LOCATION} 的 AOSP 值（8 ~ 16 都是 58，反射不到时兜底）。 */
    private static final int FALLBACK_OP_MOCK_LOCATION = 58;

    /** mock op 被“授予”时的模式值（= {@code AppOpsManager.MODE_ALLOWED}）。 */
    private static final int MODE_ALLOWED = 0;

    /** “仅前台允许”（= {@code AppOpsManager.MODE_FOREGROUND}，API 29+）：对 mock op 同样是“已授予”。 */
    private static final int MODE_FOREGROUND = 4;

    /** mock op “未授予”时应表现出的模式（= {@code AppOpsManager.MODE_ERRORED}，真机原值）。 */
    private static final int MODE_ERRORED = 2;

    /** {@link #mockOpCode()} 尚未解析。 */
    private static final int OP_CODE_UNSET = -1;

    // ---- AppOps check 家族（与 HookEntry.APP_OPS_CHECK_METHODS 同一批名字） ----

    private static final String[] APP_OPS_CHECK_METHODS = {
            "checkOp", "checkOpNoThrow", "checkOpRawNoThrow",
            "unsafeCheckOp", "unsafeCheckOpNoThrow", "unsafeCheckOpRaw", "unsafeCheckOpRawNoThrow",
    };

    /** 这些名字在 MODE_ERRORED 时本来就会抛 SecurityException，改写时保持一致。 */
    private static final String[] APP_OPS_THROWING_METHODS = {"checkOp", "unsafeCheckOp"};

    // ---- 每进程一次的首命日志开关 ----

    private static volatile boolean sLoggedPackageList;
    private static volatile boolean sLoggedPackageMissing;
    private static volatile boolean sLoggedDevSetting;
    private static volatile boolean sLoggedMockSetting;
    private static volatile boolean sLoggedAdbSetting;
    private static volatile boolean sLoggedOpsCheck;
    private static volatile boolean sLoggedOpsEnum;
    private static volatile boolean sLoggedDebugger;

    /** mock op 的 int 值（解析一次后缓存；失败退回 {@link #FALLBACK_OP_MOCK_LOCATION}）。 */
    private static volatile int sMockOpCode = OP_CODE_UNSET;

    // ================================================================ 安装入口

    /**
     * 安装全部启动期环境遮蔽。
     *
     * <p>只应由 {@code HookEntry.installHooks}（目标 App 进程）调用 —— system_server 里装这些
     * 会改到全局行为，是本模块明确禁止的。
     *
     * @return 实际装上的分类数（0 ~ {@value #CATEGORY_COUNT}，用于心跳与安装日志）
     */
    static int install(ClassLoader classLoader) {
        int installed = 0;
        installed += installPackageManager(classLoader) ? 1 : 0;
        installed += installGlobalDevelopmentSetting(classLoader) ? 1 : 0;
        installed += installSecureMockLocationSetting(classLoader) ? 1 : 0;
        installed += installGlobalAdbSetting(classLoader) ? 1 : 0;
        installed += installAppOpsChecks(classLoader) ? 1 : 0;
        installed += installAppOpsEnumeration(classLoader) ? 1 : 0;
        installed += installDebugger(classLoader) ? 1 : 0;
        log("env hiding installed=" + installed + "/" + CATEGORY_COUNT
                + " (packages, dev-options, mock-location, adb, appops-check, appops-enum, debugger)");
        return installed;
    }

    // ================================================================ A. 包名 / 框架痕迹

    /**
     * hook {@code android.app.ApplicationPackageManager}（{@code PackageManager} 的框架实现；
     * 抽象类 {@code PackageManager} 的方法没有方法体，挂上去永远不会触发，因此这里只挂实现类）。
     *
     * @return true = 至少有一条方法挂上
     */
    private static boolean installPackageManager(ClassLoader classLoader) {
        final String tag = "android.app.ApplicationPackageManager";
        try {
            final Class<?> impl = HookCompat.find(classLoader, tag);
            if (impl == null) {
                HookCompat.logCandidate(tag, false, 0, null,
                        "env-hiding packages: class absent");
                return false;
            }
            int count = 0;
            final List<String> methods = new ArrayList<>();

            // 列表类：after 过滤（List<PackageInfo> / List<ApplicationInfo> / List<ModuleInfo>）
            for (String name : new String[]{
                    "getInstalledPackages", "getInstalledPackagesAsUser",
                    "getInstalledApplications", "getInstalledApplicationsAsUser",
                    "getInstalledModules"}) {
                try {
                    final int n = Xp.hookAllMethods(impl, name, PACKAGE_LIST_CALLBACK);
                    if (n > 0) {
                        count += n;
                        methods.add(name + "x" + n);
                    }
                } catch (Throwable t) {
                    log("env-hiding skip " + tag + "#" + name + ": " + t);
                }
            }

            // 单包查询：before 抛 NameNotFoundException（与「未安装」完全一致）
            // 含 getPackageUid：不然检测方仍可用 uid 反查本模块是否存在。
            for (String name : new String[]{
                    "getPackageInfo", "getPackageInfoAsUser",
                    "getApplicationInfo", "getApplicationInfoAsUser",
                    "getPackageUid", "getPackageUidAsUser"}) {
                try {
                    final int n = Xp.hookAllMethods(impl, name, PACKAGE_LOOKUP_CALLBACK);
                    if (n > 0) {
                        count += n;
                        methods.add(name + "x" + n);
                    }
                } catch (Throwable t) {
                    log("env-hiding skip " + tag + "#" + name + ": " + t);
                }
            }

            if (count == 0) {
                HookCompat.logCandidate(tag, true, 0, null,
                        "env-hiding packages: no method matched");
                return false;
            }
            HookCompat.logCandidate(tag, true, count,
                    "env-hiding packages=" + methods, null);
            return true;
        } catch (Throwable t) {
            HookCompat.logCandidate(tag, false, 0, null, "env-hiding packages failed: " + t);
            return false;
        }
    }

    /**
     * 从 {@code List<PackageInfo/ApplicationInfo/ModuleInfo>} 里剔除隐藏包。
     *
     * @return 过滤后的新列表；{@code null} = 没有需要剔除的项（调用方保持原对象不动）
     */
    static List<Object> filterHiddenPackages(List<?> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        boolean hidden = false;
        for (Object item : source) {
            if (isHiddenPackageName(packageNameOf(item))) {
                hidden = true;
                break;
            }
        }
        if (!hidden) {
            return null;
        }
        final List<Object> kept = new ArrayList<>(source.size());
        for (Object item : source) {
            if (!isHiddenPackageName(packageNameOf(item))) {
                kept.add(item);
            }
        }
        return kept;
    }

    /** 列表类 hook 的 after：过滤失败一律回退原值（不 setResult）。 */
    static void applyPackageListFilter(Param param) {
        try {
            final Object result = param.getResult();
            if (!(result instanceof List)) {
                return;
            }
            final List<Object> kept = filterHiddenPackages((List<?>) result);
            if (kept == null) {
                return;
            }
            param.setResult(kept);
            logPackageListOnce(param.method);
        } catch (Throwable ignored) {
            // 过滤失败：目标 App 继续用原列表
        }
    }

    /** 单包查询 hook 的 before：隐藏包一律当作「未安装」。 */
    static void applyPackageNotFound(Param param) {
        try {
            final Object[] args = param.args;
            if (args == null || args.length == 0 || args[0] == null) {
                return;
            }
            // getPackageInfo(String, …) 与 getPackageInfo(VersionedPackage, …) 两种首参
            final String pkg = args[0] instanceof String
                    ? (String) args[0] : packageNameOf(args[0]);
            if (!isHiddenPackageName(pkg)) {
                return;
            }
            param.setThrowable(new PackageManager.NameNotFoundException(pkg));
            logPackageMissingOnce(param.method);
        } catch (Throwable ignored) {
            // 构造异常失败（极端 ROM）：放行原逻辑，宁可少藏也不抛模块异常
        }
    }

    /** 包名是否在隐藏名单。 */
    static boolean isHiddenPackageName(String packageName) {
        return packageName != null && HIDDEN_PACKAGE_SET.contains(packageName);
    }

    /**
     * 反射取一个「包条目」的包名：
     * <ul>
     *   <li>{@code PackageInfo} / {@code ApplicationInfo}：public 字段 {@code packageName}；</li>
     *   <li>{@code ModuleInfo} / {@code VersionedPackage}：public 方法 {@code getPackageName()}。</li>
     * </ul>
     * 两种都没有时返回 null（调用方按“不隐藏”处理）。
     */
    static String packageNameOf(Object item) {
        if (item == null) {
            return null;
        }
        if (item instanceof String) {
            return (String) item;
        }
        try {
            final Field field = item.getClass().getField("packageName");
            final Object value = field.get(item);
            if (value instanceof String) {
                return (String) value;
            }
        } catch (Throwable ignored) {
            // 没有该字段：试 getPackageName()
        }
        try {
            final Method method = item.getClass().getMethod("getPackageName");
            final Object value = method.invoke(item);
            if (value instanceof String) {
                return (String) value;
            }
        } catch (Throwable ignored) {
            // 取不到包名
        }
        return null;
    }

    // ================================================================ B. 开发者选项 / 模拟位置开关

    /**
     * {@code Settings.Global.getInt/getLong/getString(*)}：只改
     * {@code development_settings_enabled} 一个 key。
     */
    private static boolean installGlobalDevelopmentSetting(ClassLoader classLoader) {
        return installSettingsGetters(classLoader, "android.provider.Settings$Global",
                GLOBAL_SETTING_CALLBACK, "development_settings_enabled");
    }

    /**
     * {@code Settings.Secure.getInt/getLong/getString(*)}：只改 {@code mock_location} 一个 key。
     *
     * <p>注意与 {@link HookEntry} 现有 hook 的叠加顺序：HookEntry 的 settings 旁路先装、
     * 对 {@code mock_location} 的 String 返回 null；本类后装，在 before 里把结果覆盖成 "0"
     * （任务要求）。两者都只在命中该 key 时改写，其它 key 互不影响。
     */
    private static boolean installSecureMockLocationSetting(ClassLoader classLoader) {
        return installSettingsGetters(classLoader, "android.provider.Settings$Secure",
                SECURE_SETTING_CALLBACK, "mock_location");
    }

    /**
     * {@code Settings.Global.adb_enabled} → 0 / "0"（独立安装，失败不影响上面两条）。
     *
     * <p>任务里标为“可选、风险中等”：它只影响「ADB 是否打开」这一项读取，
     * 目标 App 的其它设置逻辑不经过这里；用户自己在开发者选项里看到的仍是真实值
     * （本 hook 只在目标 App 进程内）。
     */
    private static boolean installGlobalAdbSetting(ClassLoader classLoader) {
        return installSettingsGetters(classLoader, "android.provider.Settings$Global",
                GLOBAL_ADB_CALLBACK, "adb_enabled");
    }

    /** 在指定 Settings 命名空间上挂 getInt/getLong/getString（含 *ForUser）并记一条分类日志。 */
    private static boolean installSettingsGetters(ClassLoader classLoader, String className,
                                                  Callback callback, String what) {
        try {
            final Class<?> clazz = HookCompat.find(classLoader, className);
            if (clazz == null) {
                HookCompat.logCandidate(className, false, 0, null,
                        "env-hiding " + what + ": class absent");
                return false;
            }
            int count = 0;
            final List<String> methods = new ArrayList<>();
            for (String name : new String[]{
                    "getInt", "getIntForUser", "getLong", "getLongForUser",
                    "getString", "getStringForUser"}) {
                try {
                    // hookAllMethods 会覆盖同名重载；方法不存在时返回 0 而不是抛异常
                    final int n = Xp.hookAllMethods(clazz, name, callback);
                    if (n > 0) {
                        count += n;
                        methods.add(name);
                    }
                } catch (Throwable t) {
                    log("env-hiding skip " + className + "#" + name + ": " + t);
                }
            }
            if (count == 0) {
                HookCompat.logCandidate(className, true, 0, null,
                        "env-hiding " + what + ": no getter matched");
                return false;
            }
            HookCompat.logCandidate(className, true, count,
                    "env-hiding " + what + "=" + methods, null);
            return true;
        } catch (Throwable t) {
            HookCompat.logCandidate(className, false, 0, null,
                    "env-hiding " + what + " failed: " + t);
            return false;
        }
    }

    /** {@code Settings.Global}：命中 development_settings_enabled 时返回 0 / 0L / "0"。 */
    static void applyGlobalSetting(Param param) {
        try {
            if (!KEY_DEVELOPMENT_SETTINGS.equals(settingNameOf(param.args))) {
                return;
            }
            final Object replacement = hiddenSettingValue(returnTypeOf(param.method));
            if (replacement == null) {
                return; // 未知返回类型：放行
            }
            param.setResult(replacement);
            logDevSettingOnce(param.method);
        } catch (Throwable ignored) {
            // 判断失败：放行原逻辑
        }
    }

    /** {@code Settings.Secure}：命中 mock_location 时返回 0 / 0L / "0"。 */
    static void applySecureSetting(Param param) {
        try {
            if (!KEY_MOCK_LOCATION.equals(settingNameOf(param.args))) {
                return;
            }
            final Object replacement = hiddenSettingValue(returnTypeOf(param.method));
            if (replacement == null) {
                return;
            }
            param.setResult(replacement);
            logMockSettingOnce(param.method);
        } catch (Throwable ignored) {
            // 判断失败：放行原逻辑
        }
    }

    /** {@code Settings.Global}：命中 adb_enabled 时返回 0 / "0"（独立 hook，互不影响）。 */
    static void applyAdbSetting(Param param) {
        try {
            if (!KEY_ADB_ENABLED.equals(settingNameOf(param.args))) {
                return;
            }
            final Object replacement = hiddenSettingValue(returnTypeOf(param.method));
            if (replacement == null) {
                return;
            }
            param.setResult(replacement);
            logAdbSettingOnce(param.method);
        } catch (Throwable ignored) {
            // 判断失败：放行原逻辑
        }
    }

    /** Settings getter 的第 2 个参数是 key（所有重载一致：cr, name[, user]）。 */
    private static String settingNameOf(Object[] args) {
        if (args == null || args.length < 2 || !(args[1] instanceof String)) {
            return null;
        }
        return (String) args[1];
    }

    /** 设置的“关闭”值：int → 0，long → 0，String → "0"；其它类型返回 null（放行）。 */
    private static Object hiddenSettingValue(Class<?> returnType) {
        if (returnType == int.class) {
            return Integer.valueOf(0);
        }
        if (returnType == long.class) {
            return Long.valueOf(0L);
        }
        if (returnType == String.class) {
            return "0";
        }
        return null;
    }

    // ================================================================ C. AppOps

    /**
     * hook {@code AppOpsManager.checkOp} 家族：<b>只在原值确实是 {@code MODE_ALLOWED} 时</b>
     * 降级为 {@code MODE_ERRORED}（throwing 变体改写为 SecurityException），其它 op / 其它模式
     * 一律不动。
     *
     * <p>为什么用 after 而不是 before：{@link HookEntry} 的 8 槽自查已经在 before 里把
     * mock op 改写为 MODE_ERRORED / SecurityException；本类在 after 里读原值，只在
     * “某条路径没被 HookEntry 覆盖、且确实被授予”时补刀，正常情况是严格的 no-op，
     * 不会把 HookEntry 的结果覆盖成别的语义。
     */
    private static boolean installAppOpsChecks(ClassLoader classLoader) {
        final String tag = "android.app.AppOpsManager";
        try {
            final Class<?> clazz = HookCompat.find(classLoader, tag);
            if (clazz == null) {
                HookCompat.logCandidate(tag, false, 0, null,
                        "env-hiding mock-op check: class absent");
                return false;
            }
            int count = 0;
            final List<String> methods = new ArrayList<>();
            for (String name : APP_OPS_CHECK_METHODS) {
                try {
                    final int n = Xp.hookAllMethods(clazz, name, APP_OPS_CHECK_CALLBACK);
                    if (n > 0) {
                        count += n;
                        methods.add(name);
                    }
                } catch (Throwable t) {
                    log("env-hiding skip " + tag + "#" + name + ": " + t);
                }
            }
            if (count == 0) {
                HookCompat.logCandidate(tag, true, 0, null,
                        "env-hiding mock-op check: no check method matched");
                return false;
            }
            HookCompat.logCandidate(tag, true, count,
                    "env-hiding mock-op check=" + methods, null);
            return true;
        } catch (Throwable t) {
            HookCompat.logCandidate(tag, false, 0, null,
                    "env-hiding mock-op check failed: " + t);
            return false;
        }
    }

    /** mock op 检查的 after：只把 ALLOWED 降级。 */
    static void applyAppOpsCheck(Param param) {
        try {
            if (!(param.method instanceof Method)) {
                return;
            }
            final Object[] args = param.args;
            if (args == null || args.length == 0 || !isMockOp(args[0])) {
                return;
            }
            if (param.hasThrowable()) {
                return; // 原方法（或前面的 hook）已经抛了：保持异常语义
            }
            final Object result = param.getResult();
            if (!(result instanceof Integer)) {
                return; // boolean 活动态自查 / 其它返回类型不归这条 hook
            }
            final int mode = ((Integer) result).intValue();
            if (mode != MODE_ALLOWED && mode != MODE_FOREGROUND) {
                return; // 已经不是“已授予”，不需要动
            }
            // 一律只改返回值，绝不主动抛异常：目标 App 调 checkOp（throwing 变体）
            // 时通常不写 try/catch，我们凭空抛 SecurityException 会直接把对方弄崩。
            // 返回 MODE_ERRORED 与"未授予"语义一致，且不会改变调用方的控制流。
            param.setResult(Integer.valueOf(MODE_ERRORED));
            logOpsCheckOnce(param.method);
        } catch (Throwable ignored) {
            // 判断失败：放行原逻辑
        }
    }

    /**
     * hook {@code getOpsForPackage / getPackagesForOps}：入参数组剔除 mock op，结果里的
     * mock op 条目也剔除（ops 传 null = 全量查询时只能靠结果过滤）。
     */
    private static boolean installAppOpsEnumeration(ClassLoader classLoader) {
        final String tag = "android.app.AppOpsManager";
        try {
            final Class<?> clazz = HookCompat.find(classLoader, tag);
            if (clazz == null) {
                HookCompat.logCandidate(tag, false, 0, null,
                        "env-hiding mock-op enumeration: class absent");
                return false;
            }
            int count = 0;
            final List<String> methods = new ArrayList<>();
            for (String name : new String[]{"getOpsForPackage", "getPackagesForOps"}) {
                try {
                    // before：重建入参数组；after：重建结果列表
                    final int before = Xp.hookAllMethods(clazz, name, APP_OPS_REQUEST_CALLBACK);
                    final int after = Xp.hookAllMethods(clazz, name, APP_OPS_RESULT_CALLBACK);
                    if (before + after > 0) {
                        count += before + after;
                        methods.add(name + "x" + (before + after));
                    }
                } catch (Throwable t) {
                    log("env-hiding skip " + tag + "#" + name + ": " + t);
                }
            }
            if (count == 0) {
                HookCompat.logCandidate(tag, true, 0, null,
                        "env-hiding mock-op enumeration: no method matched");
                return false;
            }
            HookCompat.logCandidate(tag, true, count,
                    "env-hiding mock-op enumeration=" + methods, null);
            return true;
        } catch (Throwable t) {
            HookCompat.logCandidate(tag, false, 0, null,
                    "env-hiding mock-op enumeration failed: " + t);
            return false;
        }
    }

    /**
     * 入参过滤（before）：把 ops 请求数组里的 mock op 去掉，<b>重建一个新数组</b>再写回
     * {@code param.args}（原地改会污染调用方持有的数组）。全部被过滤掉时直接返回空 List，
     * 与框架“没有任何包使用该 op”的返回一致。
     */
    static void filterOpsRequest(Param param) {
        try {
            final Object[] args = param.args;
            if (args == null || args.length == 0) {
                return;
            }
            final int index = lastOpsArrayIndex(args);
            if (index < 0) {
                return; // 没有 op 数组（null = 全量查询）：交给 after 的结果过滤
            }
            final Object opsArg = args[index];
            if (opsArg instanceof int[]) {
                final int[] ops = (int[]) opsArg;
                int keptCount = 0;
                for (int op : ops) {
                    if (!isMockOpCode(op)) {
                        keptCount++;
                    }
                }
                if (keptCount == ops.length) {
                    return;
                }
                if (keptCount == 0) {
                    param.setResult(Collections.emptyList());
                    logOpsEnumOnce(param.method);
                    return;
                }
                final int[] filtered = new int[keptCount];
                int at = 0;
                for (int op : ops) {
                    if (!isMockOpCode(op)) {
                        filtered[at++] = op;
                    }
                }
                args[index] = filtered; // 重建新数组，绝不把 null 传回去
                logOpsEnumOnce(param.method);
            } else if (opsArg instanceof String[]) {
                final String[] ops = (String[]) opsArg;
                int keptCount = 0;
                for (String op : ops) {
                    if (!isMockOpName(op)) {
                        keptCount++;
                    }
                }
                if (keptCount == ops.length) {
                    return;
                }
                if (keptCount == 0) {
                    param.setResult(Collections.emptyList());
                    logOpsEnumOnce(param.method);
                    return;
                }
                final String[] filtered = new String[keptCount];
                int at = 0;
                for (String op : ops) {
                    if (!isMockOpName(op)) {
                        filtered[at++] = op;
                    }
                }
                args[index] = filtered;
                logOpsEnumOnce(param.method);
            }
        } catch (Throwable ignored) {
            // 过滤失败：放行原调用
        }
    }

    /**
     * 结果过滤（after）：{@code List<PackageOps>} 里每个 PackageOps 的 ops 去掉 mock 条目，
     * 用反射重建 PackageOps（{@code (String packageName, int uid, List<OpEntry>)}）。
     * 重建失败时整条 PackageOps 丢弃 —— 宁可少报，也不把 mock op 泄露出去。
     */
    static void stripOpsResult(Param param) {
        try {
            final Object result = param.getResult();
            if (!(result instanceof List)) {
                return;
            }
            final List<Object> rebuilt = stripMockFromPackageOps((List<?>) result);
            if (rebuilt == null) {
                return;
            }
            param.setResult(rebuilt);
            logOpsEnumOnce(param.method);
        } catch (Throwable ignored) {
            // 过滤失败：放行原结果
        }
    }

    /**
     * @return 过滤后的新列表；{@code null} = 没有任何 mock 条目（调用方保持原对象）
     */
    static List<Object> stripMockFromPackageOps(List<?> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        boolean changed = false;
        final List<Object> kept = new ArrayList<>(source.size());
        for (Object pkgOps : source) {
            if (pkgOps == null) {
                kept.add(null);
                continue;
            }
            List<?> ops;
            try {
                ops = (List<?>) callNoArg(pkgOps, "getOps");
            } catch (Throwable t) {
                kept.add(pkgOps); // 结构不认识：原样保留
                continue;
            }
            final List<Object> filtered = stripMockOps(ops);
            if (filtered == null) {
                kept.add(pkgOps);
                continue;
            }
            changed = true;
            final Object replacement = rebuildPackageOps(pkgOps, filtered);
            if (replacement != null) {
                kept.add(replacement);
            }
            // 重建失败（极老 / OEM 改了构造器）：丢弃该条，避免 mock op 泄露
        }
        return changed ? kept : null;
    }

    /**
     * @return 过滤后的 ops 列表；{@code null} = 没有 mock 条目
     */
    static List<Object> stripMockOps(List<?> ops) {
        if (ops == null || ops.isEmpty()) {
            return null;
        }
        boolean changed = false;
        final List<Object> kept = new ArrayList<>(ops.size());
        for (Object entry : ops) {
            if (isMockOpEntry(entry)) {
                changed = true;
                continue;
            }
            kept.add(entry);
        }
        return changed ? kept : null;
    }

    /** OpEntry：先看 int op，再看 getOpStr()（部分版本 int 编码不同）。 */
    static boolean isMockOpEntry(Object entry) {
        if (entry == null) {
            return false;
        }
        try {
            final Object op = callNoArg(entry, "getOp");
            if (op instanceof Integer && isMockOpCode((Integer) op)) {
                return true;
            }
        } catch (Throwable ignored) {
            // 没有 getOp：试 getOpStr
        }
        try {
            final Object opStr = callNoArg(entry, "getOpStr");
            return opStr instanceof String && isMockOpName((String) opStr);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 用反射重建 {@code AppOpsManager.PackageOps}；结构不认识返回 null。 */
    private static Object rebuildPackageOps(Object source, List<Object> filteredOps) {
        try {
            final String packageName = (String) callNoArg(source, "getPackageName");
            final Object uidValue = callNoArg(source, "getUid");
            if (!(uidValue instanceof Integer)) {
                return null;
            }
            final int uid = (Integer) uidValue;
            for (Constructor<?> ctor : source.getClass().getDeclaredConstructors()) {
                final Class<?>[] types = ctor.getParameterTypes();
                if (types.length == 3 && types[0] == String.class && types[1] == int.class
                        && List.class.isAssignableFrom(types[2])) {
                    ctor.setAccessible(true);
                    return ctor.newInstance(packageName, uid, filteredOps);
                }
            }
        } catch (Throwable ignored) {
            // 重建失败：调用方丢弃该条
        }
        return null;
    }

    // ================================================================ D. 调试器

    /** {@code Debug.isDebuggerConnected()} → false（独立 hook，失败不影响其它分类）。 */
    private static boolean installDebugger(ClassLoader classLoader) {
        final String tag = "android.os.Debug";
        try {
            final Class<?> clazz = HookCompat.find(classLoader, tag);
            if (clazz == null) {
                HookCompat.logCandidate(tag, false, 0, null,
                        "env-hiding debugger: class absent");
                return false;
            }
            final int count = Xp.hookAllMethods(clazz, "isDebuggerConnected", DEBUGGER_CALLBACK);
            HookCompat.logCandidate(tag, count > 0, count,
                    count > 0 ? "env-hiding isDebuggerConnected" : null,
                    count > 0 ? null : "isDebuggerConnected absent");
            return count > 0;
        } catch (Throwable t) {
            HookCompat.logCandidate(tag, false, 0, null,
                    "env-hiding debugger failed: " + t);
            return false;
        }
    }

    /** {@code isDebuggerConnected()} 恒定 false。 */
    static void applyDebuggerHidden(Param param) {
        try {
            param.setResult(Boolean.FALSE);
            logDebuggerOnce(param.method);
        } catch (Throwable ignored) {
            // 设置失败：返回真实值（原方法继续执行）
        }
    }

    // ================================================================ 判定助手

    /**
     * 是否 mock op：字符串按“包含 MOCK_LOCATION”判断（`android:mock_location`，
     * 也覆盖 OEM 加前缀的写法），int 按 {@code OP_MOCK_LOCATION} 判断（解析不到用 58 兜底）。
     */
    static boolean isMockOp(Object op) {
        if (op instanceof String) {
            return isMockOpName((String) op);
        }
        if (op instanceof Integer) {
            return isMockOpCode((Integer) op);
        }
        return false;
    }

    /** op 名字里是否含 {@code MOCK_LOCATION}（大小写不敏感）。 */
    static boolean isMockOpName(String op) {
        return op != null
                && op.toUpperCase(Locale.ROOT).contains("MOCK_LOCATION");
    }

    /** int op 是否 {@code OP_MOCK_LOCATION}（反射解出的值或 AOSP 的 58 任一命中即可）。 */
    static boolean isMockOpCode(int op) {
        return op == FALLBACK_OP_MOCK_LOCATION || op == mockOpCode();
    }

    /** 解出（并缓存）{@code AppOpsManager.OP_MOCK_LOCATION}；失败返回 58。 */
    static int mockOpCode() {
        int code = sMockOpCode;
        if (code != OP_CODE_UNSET) {
            return code;
        }
        int resolved = FALLBACK_OP_MOCK_LOCATION;
        try {
            final Class<?> appOps = HookCompat.find(null, "android.app.AppOpsManager");
            if (appOps != null) {
                final Field field = appOps.getDeclaredField("OP_MOCK_LOCATION");
                field.setAccessible(true);
                final int value = field.getInt(null);
                if (value > 0) {
                    resolved = value;
                }
            }
        } catch (Throwable ignored) {
            // 字段可能被 @hide 裁掉：用 AOSP 的 58
        }
        sMockOpCode = resolved;
        return resolved;
    }

    /** checkOp 家族里哪些方法在 MODE_ERRORED 时必须抛异常。 */
    private static boolean isThrowingCheck(String methodName) {
        for (String name : APP_OPS_THROWING_METHODS) {
            if (name.equals(methodName)) {
                return true;
            }
        }
        return false;
    }

    private static String tagForCheck(String methodName) {
        return "android.app.AppOpsManager." + methodName;
    }

    /** 入参数组里最后一个 int[]/String[] 的下标（ops 参数）；没有返回 -1。 */
    private static int lastOpsArrayIndex(Object[] args) {
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof int[] || args[i] instanceof String[]) {
                return i;
            }
        }
        return -1;
    }

    private static Class<?> returnTypeOf(Member member) {
        return member instanceof Method ? ((Method) member).getReturnType() : null;
    }

    private static Object callNoArg(Object target, String name) throws Exception {
        final Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    // ================================================================ 回调

    private static final Callback PACKAGE_LIST_CALLBACK = new Callback() {
        @Override
        protected void after(Param param) {
            applyPackageListFilter(param);
        }
    };

    private static final Callback PACKAGE_LOOKUP_CALLBACK = new Callback() {
        @Override
        protected void before(Param param) {
            applyPackageNotFound(param);
        }
    };

    private static final Callback GLOBAL_SETTING_CALLBACK = new Callback() {
        @Override
        protected void before(Param param) {
            applyGlobalSetting(param);
        }
    };

    private static final Callback SECURE_SETTING_CALLBACK = new Callback() {
        @Override
        protected void before(Param param) {
            applySecureSetting(param);
        }
    };

    private static final Callback GLOBAL_ADB_CALLBACK = new Callback() {
        @Override
        protected void before(Param param) {
            applyAdbSetting(param);
        }
    };

    private static final Callback APP_OPS_CHECK_CALLBACK = new Callback() {
        @Override
        protected void after(Param param) {
            applyAppOpsCheck(param);
        }
    };

    private static final Callback APP_OPS_REQUEST_CALLBACK = new Callback() {
        @Override
        protected void before(Param param) {
            filterOpsRequest(param);
        }
    };

    private static final Callback APP_OPS_RESULT_CALLBACK = new Callback() {
        @Override
        protected void after(Param param) {
            stripOpsResult(param);
        }
    };

    private static final Callback DEBUGGER_CALLBACK = new Callback() {
        @Override
        protected void before(Param param) {
            applyDebuggerHidden(param);
        }
    };

    // ================================================================ 日志（每类每进程一条）

    private static void logPackageListOnce(Member method) {
        if (sLoggedPackageList) {
            return;
        }
        sLoggedPackageList = true;
        log("env-hiding package list filtered (first: " + method + ")");
    }

    private static void logPackageMissingOnce(Member method) {
        if (sLoggedPackageMissing) {
            return;
        }
        sLoggedPackageMissing = true;
        log("env-hiding hidden package -> NameNotFoundException (first: " + method + ")");
    }

    private static void logDevSettingOnce(Member method) {
        if (sLoggedDevSetting) {
            return;
        }
        sLoggedDevSetting = true;
        log("env-hiding " + KEY_DEVELOPMENT_SETTINGS + " -> 0/\"0\" (first: " + method + ")");
    }

    private static void logMockSettingOnce(Member method) {
        if (sLoggedMockSetting) {
            return;
        }
        sLoggedMockSetting = true;
        log("env-hiding " + KEY_MOCK_LOCATION + " -> 0/\"0\" (first: " + method + ")");
    }

    private static void logAdbSettingOnce(Member method) {
        if (sLoggedAdbSetting) {
            return;
        }
        sLoggedAdbSetting = true;
        log("env-hiding " + KEY_ADB_ENABLED + " -> 0 (first: " + method + ")");
    }

    private static void logOpsCheckOnce(Member method) {
        if (sLoggedOpsCheck) {
            return;
        }
        sLoggedOpsCheck = true;
        log("env-hiding appops " + OPSTR_MOCK_LOCATION
                + " MODE_ALLOWED -> MODE_ERRORED (first: " + method + ")");
    }

    private static void logOpsEnumOnce(Member method) {
        if (sLoggedOpsEnum) {
            return;
        }
        sLoggedOpsEnum = true;
        log("env-hiding appops enumeration had mock op stripped (first: " + method + ")");
    }

    private static void logDebuggerOnce(Member method) {
        if (sLoggedDebugger) {
            return;
        }
        sLoggedDebugger = true;
        log("env-hiding Debug.isDebuggerConnected -> false (first: " + method + ")");
    }

    private static void log(String message) {
        try {
            Xp.log(message);
        } catch (Throwable ignored) {
            // 日志通道失败不能影响宿主
        }
    }
}
