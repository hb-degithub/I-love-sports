package com.vloc.motion.hidden;

import android.util.Log;

import com.vloc.motion.hidden.compat.NewBackend;
import com.vloc.motion.hidden.compat.Xp;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed API 102 入口（新 API）。
 *
 * <p>由 {@code META-INF/xposed/java_init.list} 声明；旧入口
 * {@code assets/xposed_init → com.vloc.motion.hidden.HookEntry} <b>保持不动</b>，
 * 两者共存，由框架按自身版本选择其中一条路径。
 *
 * <p><b>本轮只做骨架</b>：
 * <ul>
 *   <li>注入 {@link NewBackend} 到 {@link Xp} 门面（已完成）；</li>
 *   <li>{@link #onPackageReady} / {@link #onSystemServerStarting} 只写“如何调用现有
 *       HookEntry 逻辑”的空壳 + TODO，<b>不迁移任何业务 hook</b>；</li>
 *   <li>真正的迁移顺序见文件末尾 TODO。</li>
 * </ul>
 *
 * <p><b>类加载隔离</b>：本类只引用 {@code io.github.libxposed.*}；正文不出现
 * 旧 API 依赖只存在于 LegacyEntry/LegacyBackend。新入口本身不含
 * {@link LegacyEntryBridge} 里 —— 嵌套类是独立的 .class，只有
 * {@link #LEGACY_ENTRY_BRIDGE_ENABLED} 打开时才会被解析，旧框架进程/纯新框架进程
 * 都不会因缺少 de.robv 类而失败。
 */
public final class NewApiEntry extends XposedModule {

    /** 与 logcat 过滤一致：{@code adb logcat -s VLocHidden}。 */
    private static final String TAG = "VLocHidden";

    /**
     * 旧入口桥接开关。
     *
     * <p>本轮保持 {@code false}：不改 {@link HookEntry}（另一 agent 正在改），也不在同一个
     * 进程里同时跑“新入口 + 旧入口逻辑”，否则 hook 会装两遍、心跳计数会翻倍。
     * 打开前必须先确认框架到底加载了哪条入口（见报告 D1 的真机验证方法）。
     */

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        final String processName = param == null ? "" : param.getProcessName();
        final boolean systemServer = param != null && param.isSystemServer();

        // 注入后端：业务代码之后只调 Xp.xxx(...)，不用关心底层是新 API 还是旧 API。
        // 用 installIfAbsent：万一同一进程里旧入口先装了 LegacyBackend，这里不覆盖。
        Xp.installIfAbsent(new NewBackend(this, processName, systemServer));

        Log.i(TAG, "new-api entry loaded: " + processName);
    }

    /**
     * 包就绪（目标 App 进程）。等价旧 API 的 {@code handleLoadPackage(lpparam)}。
     *
     * <p>与旧 API 的差异（迁移时要注意）：
     * <ul>
     *   <li>新 API 先 {@code onPackageLoaded}（packages 已加入 ClassLoader）再
     *       {@code onPackageReady}（Application 尚未创建）；旧 API 只有一次
     *       {@code handleLoadPackage}。现有 hook 全装在 {@code onPackageReady} 里即可对应；</li>
     *   <li>一个进程可能加载多个包（sharedUserId / createPackageContext），
     *       旧 API 用 {@code isFirstApplication} 判断，新 API 用 {@link
     *       XposedModuleInterface.PackageReadyParam#isFirstPackage()}；</li>
     *   <li>{@code param.getApplicationInfo()} 取代旧 {@code lpparam.appInfo}。</li>
     * </ul>
     */
    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (param == null) {
            return;
        }
        // system_server 进程里框架也会对每个 LoadedApk 派发 onPackageReady；
        // 若在这里转调 onPackage 会先用包名占掉 processName 去重键，
        // 导致 onSystemServerStarting 的 SystemHooks 永远装不上。系统层只走那条回调。
        if (Xp.isSystemServer()) {
            return;
        }
        final String packageName = param.getPackageName();
        final ClassLoader classLoader = param.getClassLoader();

        Log.i(TAG, "new-api onPackageReady: " + packageName
                + " first=" + param.isFirstPackage()
                + " loader=" + (classLoader == null ? "null" : classLoader.getClass().getSimpleName()));

        // 与旧入口完全共用同一份钩子实现：钩子层已迁到 compat.Xp（框架无关），
        // 这里只需转调 HookEntry.onPackage，行为与 LegacyEntry 一致。
        // isFirstPackage 只用于日志：多包进程（sharedUserId / createPackageContext）由
        // HookEntry 内部按 processName 去重，不依赖该标志。
        try {
            HookEntry.onPackage(packageName, classLoader, Xp.processName());
        } catch (Throwable t) {
            Log.e(TAG, "new-api onPackage failed: " + t);
        }
    }

    /**
     * system_server 启动完成（等价“旧 API 里作用域勾了系统框架 + packageName=android”）。
     *
     * <p>现有 {@link HookEntry#handleLoadPackage} 在 {@code SYSTEM_SERVER_PACKAGE} 分支里
     * 调 {@link SystemHooks#install(ClassLoader)}；新 API 下同样的调用写在这里。
     */
    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        final ClassLoader classLoader = param == null ? null : param.getClassLoader();
        Log.i(TAG, "new-api onSystemServerStarting: system_server"
                + " loader=" + (classLoader == null ? "null" : classLoader.getClass().getSimpleName()));

        // 与旧入口共用：HookEntry.onPackage 在 SYSTEM_SERVER_PACKAGE 分支里会调
        // SystemHooks.install(classLoader) 并上报 kind=system 心跳。
        try {
            HookEntry.onPackage("android", classLoader, "system_server");
        } catch (Throwable t) {
            Log.e(TAG, "new-api system hooks failed: " + t);
        }
    }

    /**
     * 旧入口桥接（<b>默认关闭</b>）。
     *
     * <p>为什么要反射：{@code XC_LoadPackage.LoadPackageParam} 的构造器是包级私有
     * （{@code javap -p} 已确认：{@code XC_LoadPackage$LoadPackageParam();} 无 public 修饰），
     * 而它的 5 个字段是 public。所以“不修改 HookEntry、只从新入口调用它”的最小路径是：
     * 反射 new 一个 param → 填 {@code packageName/classLoader/processName/isFirstApplication}
     * → 调 {@code new HookEntry().handleLoadPackage(param)}。
     *
     * <p>只填这 4 个字段：全文 grep 确认现有 {@code handleLoadPackage} 只用
     * {@code packageName / classLoader / processName}（{@code appInfo} 一次都没读），
     * 而 {@code android.app.ActivityThread} 是 @hide，编译期拿不到，不去碰它。
     *
     * <p>这属于过渡手段：同一个 APK 若两条入口都被框架加载，会重复装 hook。
     * 正式方案是 TODO 里的第 2 步（把入口逻辑抽成包内静态方法，两个入口共用）。
     */
}
