package com.vloc.motion.hidden;

import android.app.AppOpsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.vloc.motion.hidden.compat.Callback;
import com.vloc.motion.hidden.compat.Param;
import com.vloc.motion.hidden.compat.Xp;

/**
 * LSPosed 入口：在目标 App 进程内清除 {@link Location} 上的“模拟位置”标记。
 *
 * <p>做十组遮蔽（0.4.x 起，GNSS 卫星回放为 v0.18.x 新增）：
 * <ol>
 *   <li><b>Location 标记</b>：isFromMockProvider()/isMock() 恒 false，setMock/setIsFromMockProvider
 *       空操作，getExtras() 清掉 mock 类 key；</li>
 *   <li><b>Settings 旁证</b>：Secure/System/Global 读取 "mock_location" 时，getInt/getLong 返回 0、
 *       getString 返回 null（Android 6+ 真机该设置不存在），并兜底 hook @hide 的 NameValueCache；</li>
 *   <li><b>AppOps 自查</b>：8 个槽位的 checkOp / unsafeCheckOp 家族
 *       （含 {@code *RawNoThrow} 与 {@code isOpActive} 活动态自查）对 mock op 表现为“无权限”
 *       （MODE_ERRORED / false，throwing 变体抛 SecurityException）；</li>
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
 *   <li><b>作用域（目标应用）</b>：主 App 开启「仅对选定应用生效」后，本进程不在名单内就
 *       <b>一个 hook 都不装</b>（见 {@link TargetScope}），日志为 {@code skip &lt;pkg&gt;: not in scope}；
 *       关闭时仍对全机进程生效（旧行为）。</li>
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
public final class HookEntry {

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

    /** 注入过程抛异常、成功条数不可知：{@link #installSystemHooks} 返回该值，调用方据此不报心跳（不做虚报）。 */
    private static final int HOOKS_UNKNOWN = -1;

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

    /** ContentResolver 直查 settings 的类名（API 1 起就有；走候选日志只是为了统一格式）。 */
    private static final String CONTENT_RESOLVER_CLASS_NAME = "android.content.ContentResolver";

    /**
     * 自查方法名（每个名字下的<b>全部重载</b>都会挂上，含 {@code checkOp(String,int,String,String)}
     * 这类带 message 的变体、以及 {@code (int op,int uid,String pkg)} 的 int 重载）。
     *
     * <p><b>为什么删掉了 {@code checkOpRaw}</b>：Android 17 真机 framework.jar 反汇编
     * （{@code .toolchain/tmp/verify/a17}，dexdump 全量方法表）里 {@code AppOpsManager} 的 check 家族
     * 只有下面这 7 个名字 —— <b>没有</b> {@code checkOpRaw}（只有 {@code checkOpRawNoThrow}）。
     * 旧列表里那个名字在任何版本都匹配不到，正是 {@code appops_check=7/8} 的唯一缺口；
     * 第 8 个槽位改成下面 {@link #APP_OPS_ACTIVE_METHODS} 的“活动态自查”。
     */
    private static final String[] APP_OPS_CHECK_METHODS = {
            "checkOp", "checkOpNoThrow", "checkOpRawNoThrow",
            "unsafeCheckOp", "unsafeCheckOpNoThrow", "unsafeCheckOpRaw", "unsafeCheckOpRawNoThrow",
    };

    /**
     * 第 8 槽：mock op 是否“正在使用”的自查。
     *
     * <p>检测库除了 checkOp 家族，还会直接问“这个 op 现在有没有在跑”：
     * {@code isOpActive(String op,int uid,String pkg)}（API 30+ 公开）与 @hide 的
     * {@code isOperationActive(int op,int uid,String pkg)}（A17 framework 里确有）。两者命中
     * mock op 时一律表现为 {@code false}，与 {@code MODE_ERRORED} 的语义一致。
     *
     * <p>两个名字彼此独立、任一命中即算该槽位装上（低版本 Android 8~10 上两者都不存在，
     * 这时日志自然是 {@code 7/8}，属版本差异，不算失败）。
     */
    private static final String[] APP_OPS_ACTIVE_METHODS = {"isOpActive", "isOperationActive"};

    /** 自查槽位总数：7 个 check 方法名 + 1 个活动态槽位。 */
    private static final int APP_OPS_CHECK_SLOTS = 8;

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

    // ------------------------------------------------------------------
    // 多进程 / 共享 uid 去重
    // ------------------------------------------------------------------

    /**
     * 已经装过 hook 的<b>进程</b>（键见 {@link #processKey}）。
     *
     * <p>为什么必须去重：LSPosed 对<b>每个</b>包都会回调一次 {@code handleLoadPackage}，而共享 uid
     * （{@code android:sharedUserId}）或同一进程里先后加载多个包时，同一个进程会收到多次回调；
     * 落点相同的 {@code Xp.hook} 会被挂两层 —— 定位改写执行两次（精度抖动 ×2、
     * 快照读取 ×2），心跳也会重复上报。
     *
     * <p>hook 是<b>进程级</b>的（框架类来自 boot classloader，与具体 APK 的 ClassLoader 无关），
     * 所以去重键用 {@code processName}：同一进程只装一次，第 2 次起只记一条日志。
     * 有界（{@value #MAX_TRACKED_PROCESSES} 条）：进程内见过的进程名远少于这个数，
     * 万一超了就不再记录（宁可少去重，也不让集合无界增长）。
     */
    private static final Set<String> sInstalledProcesses =
            Collections.synchronizedSet(new LinkedHashSet<String>());

    /** 去重集合上限：只是防无界增长，正常进程（含共享 uid 的几个子进程）远达不到。 */
    private static final int MAX_TRACKED_PROCESSES = 64;

    /** 去重集合已满（只记一条日志，之后不再记）。 */
    private static boolean sLoggedProcessTableFull;

    /**
     * 本进程是否已经装过 hook（第一次调用登记并返回 false，之后返回 true）。
     *
     * <p>纯粹的内存判断，不涉及 Context / prefs：即使极早期调用也安全；任何异常都按“没装过”
     * 处理（宁可重复装一次，也不能让目标 App 漏注入）。
     */
    private static boolean alreadyInstalled(String processKey) {
        try {
            if (processKey == null || processKey.isEmpty()) {
                return false;
            }
            final Set<String> set = sInstalledProcesses;
            synchronized (set) {
                if (set.contains(processKey)) {
                    return true;
                }
                if (set.size() >= MAX_TRACKED_PROCESSES) {
                    if (!sLoggedProcessTableFull) {
                        sLoggedProcessTableFull = true;
                        log("process dedupe table full (" + MAX_TRACKED_PROCESSES
                                + "), further installs are not deduplicated");
                    }
                    return false;
                }
                set.add(processKey);
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 去重键：优先 {@code processName}（多进程 / 共享 uid 下唯一标识一个进程），
     * 拿不到才退回 {@code packageName}。{@code isFirstPackage} 不参与键计算——它只表示
     * “这是本进程第一个加载的包”，同一进程第二个包也有自己的 ClassLoader，但 hook 是进程级的。
     */
    private static String processKey(String processName, String packageName) {
        if (processName != null && !processName.isEmpty()) {
            return processName;
        }
        return packageName;
    }

    /**
     * 诊断串：{@code isFirstApplication=…, isFirstPackage=…}。
     *
     * <p>只用于日志，<b>不参与</b>任何判定：
     * <ul>
     *   <li>{@code isFirstApplication} 是 xposed-api 82 就有的字段，直接读；</li>
     *   <li>{@code isFirstPackage} 是<b>新版框架</b>（LSPosed 的扩展）才追加的字段，
     *       本模块编译用的 {@code xposed-api-82.jar} 里<b>没有</b>，只能反射读 ——
     *       读不到就记 {@code n/a}，不影响去重（去重用 {@link #processKey}）。</li>
     * </ul>
     *
     * <p>参数是 {@link Object}：旧参数对象的类型属于旧 Xposed API，本类迁移后不再引用它，
     * 只按 public 字段名反射读取（字段名与旧 API 逐字一致）。
     *
     * @return 读不到时各项为 {@code ?} / {@code n/a}，绝不影响安装流程
     */
    private static String firstFlagsOf(Object lpparam) {
        String firstApplication = "?";
        try {
            final Field field = lpparam == null ? null
                    : lpparam.getClass().getField("isFirstApplication");
            if (field != null) {
                firstApplication = String.valueOf(field.get(lpparam));
            }
        } catch (Throwable ignored) {
            // 字段不可读：保持 "?"
        }
        String firstPackage;
        try {
            final Field field = lpparam == null ? null : lpparam.getClass().getField("isFirstPackage");
            firstPackage = field == null ? "n/a" : String.valueOf(field.get(lpparam));
        } catch (Throwable t) {
            firstPackage = "n/a";
        }
        return "isFirstApplication=" + firstApplication + ", isFirstPackage=" + firstPackage;
    }

    /** 反射读 public 字段（旧参数对象的 public 字段；读不到返回 null）。 */
    private static Object fieldValue(Object target, String name) {
        try {
            if (target == null) {
                return null;
            }
            return target.getClass().getField(name).get(target);
        } catch (Throwable t) {
            return null;   // 字段不可读：按“没有”处理，绝不影响安装流程
        }
    }

    /** 反射读 public 字符串字段；不是 String / 读不到都返回 null。 */
    private static String stringField(Object target, String name) {
        final Object value = fieldValue(target, name);
        return value instanceof String ? (String) value : null;
    }

    /** 反射读 public ClassLoader 字段；类型不符 / 读不到都返回 null。 */
    private static ClassLoader classLoaderField(Object target) {
        final Object value = fieldValue(target, "classLoader");
        return value instanceof ClassLoader ? (ClassLoader) value : null;
    }

    /**
     * 旧入口（{@code assets/xposed_init → com.vloc.motion.hidden.HookEntry}）的兼容方法。
     *
     * <p><b>为什么参数是 {@link Object}</b>：本类已迁移到 {@link Xp} 门面，不再 import
     * 旧 Xposed API，因此不能把参数声明成旧 API 的 {@code LoadPackageParam}。
     * 旧参数对象参与逻辑的只有 3 个 public 字段（{@code packageName / classLoader /
     * processName}），其余只用于日志，所以这里按字段名反射读取，判定逻辑与迁移前逐字一致。
     *
     * <p>真正的 {@code IXposedHookLoadPackage} 实现留在 {@code NewApiEntry} 的嵌套桥接类里
     * （那个文件允许出现旧 API 类型）：它把参数摊平成
     * {@link #onPackage(String, ClassLoader, String)} 的 3 个实参后调本类。
     */
    public void handleLoadPackage(Object lpparam) {
        try {
            if (lpparam == null) {
                return;
            }
            final String packageName = stringField(lpparam, "packageName");
            if (packageName == null) {
                return;
            }
            onPackage(packageName, classLoaderField(lpparam), stringField(lpparam, "processName"),
                    firstFlagsOf(lpparam));
        } catch (Throwable t) {
            // 顶层兜底：模块绝不能因为自身异常影响目标 App 启动
            logError("handleLoadPackage failed", t);
        }
    }

    /**
     * 包加载入口（<b>框架无关</b>）：旧入口与新入口（libxposed API 102）共用这一份逻辑。
     *
     * @param packageName 本次加载的包名（非空）
     * @param classLoader 该包的 ClassLoader
     * @param processName 进程名（旧 API 的 {@code LoadPackageParam#processName}）；
     *                    空 = 退回包名做进程去重
     */
    public static void onPackage(String packageName, ClassLoader classLoader, String processName) {
        onPackage(packageName, classLoader, processName, null);
    }

    /** @param firstFlags 诊断串，null = 本入口拿不到（只影响日志） */
    private static void onPackage(String packageName, ClassLoader classLoader, String processName,
                                  String firstFlags) {
        try {
            if (packageName == null) {
                return;
            }
            if (MAIN_APP_PACKAGE.equals(packageName) || MODULE_PACKAGE.equals(packageName)) {
                return;
            }
            // 多进程 / 共享 uid 去重：同一进程只装一次（见 sInstalledProcesses 注释）。
            // 幂等重入（LSPosed 派生进程、同 uid 多包）在此拦下，不再重复挂 hook。
            final String procKey = processKey(processName, packageName);
            if (alreadyInstalled(procKey)) {
                log("skip " + packageName + ": hooks already installed in process " + procKey);
                return;
            }
            if (SYSTEM_SERVER_PACKAGE.equals(packageName)) {
                // 系统框架进程：安装系统级注入（定位 / GNSS，无 mock 标记）。
                // 模块必须被手动加入「系统框架」作用域才会走到这里。
                // 注意：系统层「只对选定应用生效」在 SystemHooks 内部做（投递时按接收方包名判定），
                // 这里不能整进程跳过，否则名单里也会全部失效。
                final int systemHooks = new HookEntry().installSystemHooks(classLoader);
                if (systemHooks >= 0) {
                    // 心跳（每进程一次，由 HookHeartbeat 保证）：让主 App 不依赖
                    // “能否看见 org.lsposed.manager” 也能知道系统层已注入、装上了几个 hook
                    HookHeartbeat.report(null, packageName, systemHooks, HookHeartbeat.KIND_SYSTEM);
                }
                return;
            }
            if (!TargetScope.isAllowed(packageName)) {
                // 「仅对选定应用生效」已开启且本进程不在名单内：一个 hook 都不装，直接退出。
                // 未开启 / 读不到名单时 isAllowed 一律返回 true（保持旧行为）。
                // 这里<b>不上报心跳</b>：被作用域 skip = “不该注入”，不是“注入成功”。
                log("skip " + packageName + ": not in scope");
                return;
            }
            // 诊断：多进程/共享 uid 场景下，把真实的 processName 与 isFirstPackage 记一行，
            // 便于区分“同一进程被回调多次（已去重）”与“不同进程各装一次（正常）”。
            log("install in process=" + procKey + " pkg=" + packageName + " "
                    + (firstFlags == null
                    ? "isFirstApplication=?, isFirstPackage=n/a" : firstFlags));
            final int appHooks = new HookEntry().installHooks(classLoader, packageName);
            // 心跳：kind 由本进程 uid 判定（系统 uid 段如 com.android.location.fused → system，
            // 普通目标应用 → app），hooks 是下面 installHooks 里各旁路实际安装数的总和
            HookHeartbeat.report(null, packageName, appHooks, HookHeartbeat.kindOfCurrentProcess());
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
     *
     * @return {@link SystemHooks#install(ClassLoader)} 实际安装成功的 hook 数；
     *         抛异常时返回 {@link #HOOKS_UNKNOWN}（安装数不可知，心跳就不报，避免虚报）
     */
    private int installSystemHooks(ClassLoader classLoader) {
        try {
            final int hooks = SystemHooks.install(classLoader);
            log("system-level hooks installed in system_server: " + hooks);
            return hooks;
        } catch (Throwable t) {
            logError("installSystemHooks failed", t);
            return HOOKS_UNKNOWN;
        }
    }

    /** 单条旁路的安装入口（{@code int install(ClassLoader)}）。 */
    private interface BypassInstaller {
        int install(ClassLoader classLoader);
    }

    /**
     * <b>缺失即跳过</b>的统一兜底：一条旁路无论因为什么抛出，都只记一条日志并返回 0。
     *
     * <p>为什么需要它：各 {@code *Replay.install} 内部已经逐 hook try/catch，但它们自身的
     * “入口代码”（拿 ClassLoader、算常量、初始化表）也可能在某个 Android 版本上抛出；
     * 一旦抛出就会把 {@code installHooks} 打断，<b>后面所有旁路都不会安装</b>，
     * 而且心跳不会上报（被当成“注入失败”）。这里把每条旁路隔离成独立单元。
     *
     * @param tag      日志用的旁路名
     * @param installer 旁路安装函数（可为 null = 本版本没有该旁路）
     * @return 该类旁路实际安装数；异常 / 缺失一律 0
     */
    private static int safeInstall(String tag, BypassInstaller installer, ClassLoader classLoader) {
        if (installer == null) {
            return 0;
        }
        try {
            return installer.install(classLoader);
        } catch (Throwable t) {
            log("skip " + tag + " hooks (unsupported on this Android): " + t);
            return 0;
        }
    }

    /**
     * 安装全部 hook，每个 hook 内部各自兜底，互不影响。
     *
     * @return 本进程实际安装成功的 hook 总数（各旁路安装方法的返回值之和，用于自报心跳）
     */
    private int installHooks(ClassLoader classLoader, String packageName) {
        // 候选表只有一项（android.location.Location 从 API 1 就存在），但同样走统一解析 +
        // 统一候选日志：ClassLoader 异常 / 类被裁掉时只记日志并跳过本进程，绝不抛到框架层。
        final Class<?> locationClass = HookCompat.find(classLoader, LOCATION_CLASS_NAME);
        if (locationClass == null) {
            HookCompat.logCandidate(LOCATION_CLASS_NAME, false, 0, null,
                    "skip process " + packageName);
            return 0;
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

        // 每条旁路独立 try/catch（见 safeInstall）：任一条在某个 Android 版本上抛出
        // （类被裁掉、方法签名变了、ClassLoader 变了）都只记一条日志，绝不连累后面的旁路，
        // 也绝不把异常抛给 Xposed 框架层 / 宿主进程。
        // 旁证 1：Settings 里的 mock_location
        final int settingsHooks = safeInstall("settings", this::hookSettings, classLoader);
        // 旁证 2：AppOpsManager 的 mock_location 自查（8 个槽位：7 个 check 名字 + 活动态自查）
        final int appOpsHooks = safeInstall("appops_check", this::hookAppOps, classLoader);
        // 旁路 3：AppOps 枚举 / noteOp / startOp / finishOp
        final int appOpsExtraHooks = safeInstall("appops_extra", this::hookAppOpsExtras, classLoader);
        // 旁路 4：ContentResolver 直查 settings provider
        final int resolverHooks = safeInstall("resolver", this::hookContentResolver, classLoader);
        // 旁路 5：Location 序列化时临时清掉 mock 位
        final int parcelHooks = safeInstall("parcel", cl -> hookLocationParcel(locationClass), classLoader);
        // 旁路 6：基站 / Wi-Fi 环境快照回放（v0.4.0）
        final int envHooks = safeInstall("env", EnvReplay::install, classLoader);
        // 旁路 7：计步传感器回放（v0.4.x）
        final int stepHooks = safeInstall("step", StepReplay::install, classLoader);
        // 旁路 8：IMU（加速度计 / 线加速度 / 陀螺仪 / 重力）伪造
        final int imuHooks = safeInstall("imu", ImuReplay::install, classLoader);
        // 旁路 9：GNSS 卫星状态回放（GnssStatus / 1Hz 回调，v0.18.x）
        final int gnssHooks = safeInstall("gnss", GnssReplay::install, classLoader);
        // 旁路 10：反检测补强（provider 列表 / 时间戳一致性 / 精度抖动 / NMEA，v0.38）
        final int antiDetectHooks = safeInstall("anti_detect", AntiDetect::install, classLoader);
        // 旁路 11：启动期静态环境遮蔽（包名 / 开发者选项 / AppOps / 调试器，v0.46.6）
        final int envHidingHooks = safeInstall("env_hiding", EnvHiding::install, classLoader);

        // 安装条数随 Android 版本自然变化（如 Android 11 上没有 isMock/setMock，4/5 属正常）
        final int total = installed + settingsHooks + appOpsHooks + appOpsExtraHooks
                + resolverHooks + parcelHooks + envHooks + stepHooks + imuHooks + gnssHooks
                + antiDetectHooks + envHidingHooks;
        log("hooks installed in " + packageName + " (sdk=" + Build.VERSION.SDK_INT
                + ", location=" + installed + "/5, settings=" + settingsHooks
                + ", appops_check=" + appOpsHooks + "/" + APP_OPS_CHECK_SLOTS
                + ", appops_extra=" + appOpsExtraHooks
                + ", resolver=" + resolverHooks + "/2, parcel=" + parcelHooks + "/1"
                + ", env=" + envHooks + "/5, step=" + stepHooks + "/2"
                + ", imu=" + imuHooks + "/2, gnss=" + gnssHooks + "/15"
                + ", anti_detect=" + antiDetectHooks + "/4"
                + ", env_hiding=" + envHidingHooks + "/7, total=" + total + ")");

        // 注入成功：回报一次心跳，供主 App 区分“模块未注入 / 已注入但目标 App 不读传感器 / 正在读取”
        StepReplay.sendHeartbeatOnce();
        // 心跳的 hooks 字段由调用方用本方法的返回值上报（见 handleLoadPackage），这里不重复上报
        return total;
    }

    /** Location#isFromMockProvider() → false（API 18+，Android 12+ 已废弃但大量 App 仍在用）。 */
    private boolean hookIsFromMockProvider(Class<?> locationClass) {
        try {
            hookExact(locationClass, "isFromMockProvider", NO_PARAMS,
                    new Callback() {
                        @Override
                        protected void before(Param param) {
                            // 心跳补试（极轻量：两次 volatile 读，未成功且距上次 >2s 才真的去 insert）：
                            // 开机早期主 App 未就绪导致首次心跳失败时，靠这里在既有回调里补上，
                            // 不需要常驻线程或轮询，见 HookHeartbeat#retryIfPending。
                            HookHeartbeat.retryIfPending();
                            param.setResult(Boolean.FALSE);   // 等价 XC_MethodReplacement：不调原方法
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
            hookExact(locationClass, "isMock", NO_PARAMS,
                    new Callback() {
                        @Override
                        protected void before(Param param) {
                            param.setResult(Boolean.FALSE);   // 等价 XC_MethodReplacement：不调原方法
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
            hookExact(locationClass, methodName, new Class<?>[]{boolean.class},
                    Callback.doNothing());
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
            hookExact(locationClass, "getExtras", NO_PARAMS, new Callback() {
                @Override
                protected void after(Param param) {
                    try {
                        // 心跳补试（与 isFromMockProvider 里同一入口，开销仅为两次 volatile 读）：
                        // 目标 App 读 Location 时顺手补一次开机早期失败的心跳。
                        HookHeartbeat.retryIfPending();
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

    /** 无参方法的参数类型表（{@link #hookExact} 用；避免每处都 new 一个空数组）。 */
    private static final Class<?>[] NO_PARAMS = new Class<?>[0];

    /**
     * 对应旧 API 的 {@code XposedHelpers.findAndHookMethod(clazz, name, params..., callback)}：
     * 按<b>精确签名</b>找方法再挂（不遍历其它重载），方法不存在时抛 {@link NoSuchMethodError} ——
     * 与迁移前一致，调用方的 {@code catch (Throwable)} 会记一条 {@code skip ...} 日志。
     */
    private static void hookExact(Class<?> clazz, String name, Class<?>[] parameterTypes,
                                  Callback callback) {
        Xp.hookMethodExact(clazz, name, parameterTypes, callback);
    }

    // ==================================================================
    // Settings 旁证：mock_location 读取一律表现为“不允许”(0 / "0")
    // ==================================================================

    /**
     * Settings 读取拦截回调。全 App 高频调用路径，因此只对 name 命中的那一次改写返回值，
     * 其余 key / 其余参数一律放行；任何异常都静默吞掉，绝不把模块异常抛给目标 App。
     */
    private static final Callback SETTINGS_MOCK_CALLBACK = new Callback() {
        @Override
        protected void before(Param param) {
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
        final Class<?> settingsClass = HookCompat.find(classLoader, className);
        if (settingsClass == null) {
            HookCompat.logCandidate(className, false, 0, null, "settings namespace absent");
            return 0;
        }

        int hookedNames = 0;
        for (String methodName : SETTINGS_GETTER_METHODS) {
            try {
                // hookAllMethods 会覆盖同名重载，且方法不存在时返回空集合而不是抛异常
                final int overloads = Xp.hookAllMethods(
                        settingsClass, methodName, SETTINGS_MOCK_CALLBACK);
                if (overloads > 0) {
                    hookedNames++;
                    log("hooked " + className + "#" + methodName + " (" + overloads + " overload(s))");
                }
            } catch (Throwable t) {
                log("skip " + className + "#" + methodName + ": " + t);
            }
        }
        // 统一候选日志：hooked=命中的 getter 名字数（0 = 该 ROM 换过读取链，靠 NameValueCache 兜底）
        HookCompat.logCandidate(className, true, hookedNames,
                hookedNames > 0 ? "getters=" + hookedNames : null,
                hookedNames > 0 ? null : "no getter matched");
        return hookedNames;
    }

    /** @hide 的 Settings$NameValueCache#getStringForUser，作最后一道兜底。 */
    private int hookSettingsCache(ClassLoader classLoader) {
        final Class<?> cacheClass = HookCompat.find(classLoader, SETTINGS_CACHE_CLASS_NAME);
        if (cacheClass == null) {
            HookCompat.logCandidate(SETTINGS_CACHE_CLASS_NAME, false, 0, null, "cache class absent");
            return 0;
        }
        int overloads = 0;
        try {
            overloads = Xp.hookAllMethods(
                    cacheClass, "getStringForUser", SETTINGS_MOCK_CALLBACK);
        } catch (Throwable t) {
            HookCompat.logCandidate(SETTINGS_CACHE_CLASS_NAME, true, 0,
                    null, "hook failed: " + t);
            return 0;
        }
        HookCompat.logCandidate(SETTINGS_CACHE_CLASS_NAME, true, overloads > 0 ? 1 : 0,
                overloads > 0 ? "getStringForUser overloads=" + overloads : null,
                overloads > 0 ? null : "getStringForUser absent");
        return overloads > 0 ? 1 : 0;
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
        final Class<?> appOpsClass = HookCompat.find(classLoader, APP_OPS_CLASS_NAME);
        if (appOpsClass == null) {
            // AppOpsManager 从 API 19 起就有：找不到只可能是个别 ROM 裁掉，记日志后跳过
            HookCompat.logCandidate(APP_OPS_CLASS_NAME, false, 0, null, "class absent");
            return 0;
        }

        // 老版本把 op 编码成 int，顺手解出 OP_MOCK_LOCATION 以覆盖 int 重载（解析失败则 -1）
        final int mockOpInt = resolveMockOpInt(appOpsClass);
        final Callback callback = new Callback() {
            @Override
            protected void before(Param param) {
                try {
                    final Object[] args = param.args;
                    if (args == null || args.length < 1 || !(param.method instanceof Method)) {
                        return;
                    }
                    final Method method = (Method) param.method;
                    final Class<?> returnType = method.getReturnType();
                    // int：checkOp 家族；boolean：isOpActive / isOperationActive（第 8 槽）
                    final boolean booleanResult = returnType == boolean.class;
                    if (returnType != int.class && !booleanResult) {
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
                    if (isSelfAppOpsSubject(args)) {
                        // 主体是主 App 自己：系统（system_server）替它做的 appop 自查必须按真实结果返回，
                        // 否则 addTestProvider 会被本模块自己拦死。目标 App 的调用仍走下面两条拒绝分支。
                        logAppOpsSelfExemptOnce(method);
                        return;
                    }
                    if (booleanResult) {
                        // 活动态自查：mock op 表现为“没有在使用”，与 MODE_ERRORED 的语义一致
                        param.setResult(Boolean.FALSE);
                    } else if (isThrowingCheck(method.getName())) {
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
                final int overloads = Xp.hookAllMethods(appOpsClass, methodName, callback);
                if (overloads > 0) {
                    hooked++;
                    HookCompat.logCandidate(APP_OPS_CLASS_NAME + "#" + methodName,
                            true, overloads, "overloads=" + overloads, null);
                } else {
                    HookCompat.logCandidate(APP_OPS_CLASS_NAME + "#" + methodName,
                            false, 0, null, "absent on this Android");
                }
            } catch (Throwable t) {
                HookCompat.logCandidate(APP_OPS_CLASS_NAME + "#" + methodName,
                        true, 0, null, "hook failed: " + t);
            }
        }
        // 第 8 槽：活动态自查（任一名字命中即算装上）
        final int activeSlot = hookAppOpsActiveState(appOpsClass, callback);
        final int slots = hooked + activeSlot;
        log("appops hooks=" + slots + "/" + APP_OPS_CHECK_SLOTS
                + " (mock_location -> MODE_ERRORED"
                + (activeSlot > 0 ? "; active-state self-check included" : "")
                + ")");
        return slots;
    }

    /**
     * 第 8 槽：挂 {@code isOpActive} / {@code isOperationActive}（命中 mock op → {@code false}）。
     *
     * @return 1 = 至少一个名字挂上了；0 = 本版本两个都没有（Android 8~10 正常）
     */
    private int hookAppOpsActiveState(Class<?> appOpsClass, Callback callback) {
        int matched = 0;
        for (String methodName : APP_OPS_ACTIVE_METHODS) {
            try {
                final int overloads = Xp.hookAllMethods(appOpsClass, methodName, callback);
                if (overloads > 0) {
                    matched++;
                    HookCompat.logCandidate(APP_OPS_CLASS_NAME + "#" + methodName,
                            true, overloads, "overloads=" + overloads, null);
                } else {
                    HookCompat.logCandidate(APP_OPS_CLASS_NAME + "#" + methodName,
                            false, 0, null, "absent on this Android");
                }
            } catch (Throwable t) {
                HookCompat.logCandidate(APP_OPS_CLASS_NAME + "#" + methodName,
                        true, 0, null, "hook failed: " + t);
            }
        }
        return matched > 0 ? 1 : 0;
    }

    /** checkOp / unsafeCheckOp 在 MODE_ERRORED 时本来就会抛 SecurityException，需保持一致。 */
    private static boolean isThrowingCheck(String methodName) {
        return "checkOp".equals(methodName) || "unsafeCheckOp".equals(methodName);
    }

    /** 解出 @hide 的 AppOpsManager.OP_MOCK_LOCATION，用于兼容以 int op 调用自查的老代码。 */
    private static int resolveMockOpInt(Class<?> appOpsClass) {
        try {
            final Field field = Xp.findFieldIfExists(appOpsClass, "OP_MOCK_LOCATION");
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
    private static final Callback QUERY_HOOK = new Callback() {
        @Override
        protected void before(Param param) {
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
    private static final Callback CALL_HOOK = new Callback() {
        @Override
        protected void before(Param param) {
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
        final Class<?> resolverClass = HookCompat.find(classLoader, CONTENT_RESOLVER_CLASS_NAME);
        if (resolverClass == null) {
            HookCompat.logCandidate(CONTENT_RESOLVER_CLASS_NAME, false, 0, null, "class absent");
            return 0;
        }
        int hooked = 0;
        try {
            final int queryOverloads = Xp.hookAllMethods(resolverClass, "query", QUERY_HOOK);
            if (queryOverloads > 0) {
                hooked++;
                log("hooked ContentResolver#query (" + queryOverloads + " overload(s))");
            }
        } catch (Throwable t) {
            log("skip ContentResolver#query: " + t);
        }
        try {
            final int callOverloads = Xp.hookAllMethods(resolverClass, "call", CALL_HOOK);
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
     * <p>这里<b>不做</b>自身的 uid 豁免：枚举重载没有可靠的“调用主体 uid”
     * （getPackagesForOps 隐式查询调用方，getOpsForPackage 的 uid 是查询目标而非主体），
     * 且主 App 的 addTestProvider 注入链路不经过枚举接口，因此维持原拦截行为。
     *
     * @return 成功 hook 到的方法名数量
     */
    private int hookAppOpsEnumeration(Class<?> appOpsClass, int mockOpInt) {
        final Callback hook = new Callback() {
            @Override
            protected void before(Param param) {
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
                final int overloads = Xp.hookAllMethods(appOpsClass, methodName, hook);
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
     * <p>例外：调用主体是主 App 自己时整体放行（见 {@link #isSelfAppOpsSubject}）。
     * 主 App 的 addTestProvider 会由 system_server 里的 LocationManagerService 触发
     * noteOp(OP_MOCK_LOCATION)，若在这里拒绝，应用将永远无法接管测试 Provider。
     *
     * @return 成功 hook 到的方法名数量
     */
    private int hookAppOpsNoteAndStart(Class<?> appOpsClass, int mockOpInt) {
        final Callback hook = new Callback() {
            @Override
            protected void before(Param param) {
                try {
                    final Object[] args = param.args;
                    if (args == null || args.length == 0 || !(param.method instanceof Method)) {
                        return;
                    }
                    if (!isMockOpArg(args[0], mockOpInt)) {
                        return;
                    }
                    if (isSelfAppOpsSubject(args)) {
                        // 主 App 自己触发的 mock op（system_server 代其执行）：不修改结果、不抛异常，
                        // 交给框架原逻辑，否则 addTestProvider / setTestProviderLocation 会被自己拦死。
                        logAppOpsSelfExemptOnce(param.method);
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
                final int overloads = Xp.hookAllMethods(appOpsClass, methodName, hook);
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
        final Class<?> appOpsClass = HookCompat.find(classLoader, APP_OPS_CLASS_NAME);
        if (appOpsClass == null) {
            HookCompat.logCandidate(APP_OPS_CLASS_NAME, false, 0, "extras", "class absent");
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

    // ------------------------------------------------------------------
    // AppOps 自身豁免：主 App 自己触发的 mock op 必须放行
    //
    // 本模块会被 LSPosed 注入 system_server。主 App 调 addTestProvider 时，
    // LocationManagerService 在 system_server 里执行 noteOp(OP_MOCK_LOCATION)；
    // 若这里也按“无权限”拒绝，主 App 就永远拿不到测试 Provider
    // （真机表现：op=58 mode=2，SecurityException 来自本模块）。
    // 因此 mock op 的拦截必须先判断调用主体是不是主 App 自己。
    // ------------------------------------------------------------------

    /** 主 App uid 尚未解析。 */
    private static final int SELF_UID_UNSET = -2;

    /** 本进程解析不出主 App uid（拿不到 Context / PackageManager）：永不豁免，保持原拦截行为。 */
    private static final int SELF_UID_UNAVAILABLE = -1;

    /** 解析失败的最大尝试次数：既避免高频路径每次都查 PackageManager，也避免一次失败就永久放弃。 */
    private static final int SELF_UID_MAX_ATTEMPTS = 4;

    /** 主 App uid 缓存（每进程一份，解析成功后不再查 PackageManager）。 */
    private static volatile int sSelfUid = SELF_UID_UNSET;
    private static int sSelfUidAttempts;
    /** 豁免日志每进程只打一条，避免检测库轮询时刷爆 logcat。 */
    private static boolean sLoggedAppOpsSelfExempt;
    private static boolean sLoggedSelfUidFailure;

    /**
     * 调用主体是否就是主 App 自己（是则 mock op 的拦截必须整体放行）。
     *
     * <p>主体 uid 取值顺序：① 被 hook 方法的 uid 参数（noteOp / checkOp 系列固定在 {@code args[1]}）；
     * ② 没有 uid 参数时退回 {@link Binder#getCallingUid()}；
     * ③ 两者都拿不到（或解析不出自己的 uid）按“不是自己”处理，保持原有拦截。
     */
    private static boolean isSelfAppOpsSubject(Object[] args) {
        final int selfUid = selfUid();
        if (selfUid < 0) {
            return false;
        }
        return subjectUidOrUnknown(args) == selfUid;
    }

    /** 取调用主体 uid；没有 uid 参数的重载用 Binder 的调用方 uid，取不到返回 -1。 */
    private static int subjectUidOrUnknown(Object[] args) {
        if (args != null && args.length > 1 && args[1] instanceof Integer) {
            return ((Integer) args[1]).intValue();
        }
        try {
            return Binder.getCallingUid();
        } catch (Throwable t) {
            return SELF_UID_UNAVAILABLE;
        }
    }

    /** 主 App 的 uid（按进程缓存）；解析不出来返回 {@link #SELF_UID_UNAVAILABLE}。 */
    private static int selfUid() {
        int uid = sSelfUid;
        if (uid != SELF_UID_UNSET) {
            return uid;
        }
        synchronized (HookEntry.class) {
            uid = sSelfUid;
            if (uid != SELF_UID_UNSET) {
                return uid;
            }
            uid = resolveSelfUid();
            if (uid >= 0) {
                sSelfUid = uid;
                return uid;
            }
            if (++sSelfUidAttempts >= SELF_UID_MAX_ATTEMPTS) {
                sSelfUid = SELF_UID_UNAVAILABLE;
                if (!sLoggedSelfUidFailure) {
                    sLoggedSelfUidFailure = true;
                    log("self uid unavailable, appops self-exemption disabled");
                }
            }
            return SELF_UID_UNAVAILABLE;
        }
    }

    /**
     * 解析主 App 的 uid。
     *
     * <p>system_server 里拿不到 Xposed 的 Activity 上下文，用 @hide 的
     * {@code AppGlobals.getInitialApplication()}（反射；system_server 里返回系统 Application）
     * 取 PackageManager 再查 {@link #MAIN_APP_PACKAGE}；任何一步失败都退化为“不豁免”。
     */
    private static int resolveSelfUid() {
        try {
            final Class<?> appGlobals = Class.forName("android.app.AppGlobals");
            final Object app = appGlobals.getMethod("getInitialApplication").invoke(null);
            if (!(app instanceof Context)) {
                return SELF_UID_UNAVAILABLE;
            }
            final PackageManager pm = ((Context) app).getPackageManager();
            if (pm == null) {
                return SELF_UID_UNAVAILABLE;
            }
            final ApplicationInfo info = pm.getApplicationInfo(MAIN_APP_PACKAGE, 0);
            return info == null ? SELF_UID_UNAVAILABLE : info.uid;
        } catch (Throwable t) {
            return SELF_UID_UNAVAILABLE;
        }
    }

    private static void logAppOpsSelfExemptOnce(Member method) {
        if (sLoggedAppOpsSelfExempt) {
            return;
        }
        sLoggedAppOpsSelfExempt = true;
        log("appops self-exempt: uid=" + sSelfUid + " (first: " + method + ")");
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
            maskField = Xp.findFieldIfExists(locationClass, PARCEL_MASK_FIELD);
            if (maskField != null) {
                maskField.setAccessible(true);
            }
        } catch (Throwable t) {
            log("skip " + PARCEL_MASK_FIELD + ": " + t);
        }
        Field legacyField = null;
        try {
            legacyField = Xp.findFieldIfExists(locationClass, PARCEL_LEGACY_MOCK_FIELD);
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
            hookExact(locationClass, "writeToParcel", new Class<?>[]{Parcel.class, int.class},
                    new Callback() {
                        @Override
                        protected void before(Param param) {
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
                        protected void after(Param param) {
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
            final Field field = Xp.findFieldIfExists(locationClass, "HAS_MOCK_PROVIDER_MASK");
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

    /** 统一日志：{@code [VLocHidden] } 前缀由 {@link Xp} 后端统一加。 */
    private static void log(String message) {
        try {
            Xp.log(message);
        } catch (Throwable ignored) {
            // 日志通道异常时不能影响目标 App
        }
    }

    private static void logError(String message, Throwable t) {
        log(message + ": " + t);
        try {
            Xp.log(t);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
