package com.vloc.motion.hidden;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 旧版 Xposed API 的入口桥（{@code assets/xposed_init} 指向本类）。
 *
 * <p><b>为什么需要这一层</b>：钩子逻辑已经迁到框架无关的 {@link HookEntry#onPackage}，
 * 而 {@code HookEntry} 本身不再 {@code implements IXposedHookLoadPackage}（否则会把
 * {@code de.robv.*} 依赖带进新 API 模块，导致新框架下类加载失败）。旧框架只认
 * {@code xposed_init} 里声明、且实现了该接口的类，所以这里保留一个**只有旧框架才会
 * 加载**的薄桥：
 *
 * <ul>
 *   <li>旧框架（LSPosed 1.x / 2.x legacy 模式）：读 {@code xposed_init} → 本类 →
 *       转调 {@link HookEntry#onPackage}，行为与迁移前完全一致；</li>
 *   <li>新框架（libxposed API 102）：读 {@code META-INF/xposed/java_init.list} →
 *       {@code NewApiEntry}，本类不会被加载（因此其中的 {@code de.robv.*} 引用也不会
 *       触发类解析失败）。</li>
 * </ul>
 *
 * <p>本类必须保持**极薄**：不做任何判断、不吞异常以外的事情，所有逻辑都在
 * {@link HookEntry#onPackage}。这样两套入口共享同一份钩子实现，不会出现行为分叉。
 */
public final class LegacyEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null) {
            return;
        }
        // 桥接层不抛异常：旧框架里抛出去会被记成模块错误，而 onPackage 内部已逐段兜底。
        try {
            HookEntry.onPackage(lpparam.packageName, lpparam.classLoader, lpparam.processName);
        } catch (Throwable t) {
            android.util.Log.e("VLocHidden", "legacy entry failed: " + t);
        }
    }
}
