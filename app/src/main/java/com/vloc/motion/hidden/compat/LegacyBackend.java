package com.vloc.motion.hidden.compat;

import android.content.SharedPreferences;

import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 旧 API 后端：把 {@link Xp} 的调用转成 {@code de.robv.android.xposed.*}。
 *
 * <p>只 import 旧 API，<b>不</b> 出现任何 {@code io.github.libxposed.*} 类型 ——
 * 旧框架进程里本类可以独立加载（新 API 的 jar 只参与编译，不进 APK）。
 *
 * <p>与 {@link NewBackend} 的差别只有三处，其余全是薄转发：
 * <ol>
 *   <li>日志：{@code XposedBridge.log(String)}（新 API 是 {@code log(int, tag, msg)}）；</li>
 *   <li>偏好：{@code new XSharedPreferences(pkg, name)}（LSPosed API 93+ 的 world-readable
 *       通道；新 API 是 {@code getRemotePreferences(name)}，<b>只读</b>）；</li>
 *   <li>反射助手：{@code XposedHelpers}（新 API 没有这一层，见 {@link Reflect}）。</li>
 * </ol>
 *
 * <p>{@link Callback} → {@code XC_MethodHook} 的桥接：直接复用旧 API 自带的
 * before/after 语义（旧 API 的 {@code MethodHookParam.returnEarly} 就是“跳过原方法”位，
 * 我们的 {@link Param#isResultSet()} 与它一一对应）。
 */
public final class LegacyBackend implements XpBackend {

    /** 主 App 包名（单 APK 双身份），与 {@code PrefsClient.MODULE_PACKAGE} 必须一致。 */
    private static final String MODULE_PACKAGE = "com.vloc.motion";

    private volatile String processName;

    @Override
    public String name() {
        return "legacy(de.robv.api82)";
    }

    // ---------------- 日志 / 进程身份 / 偏好 ----------------

    @Override
    public void log(String message) {
        try {
            XposedBridge.log("[" + Xp.TAG + "] " + message);
        } catch (Throwable ignored) {
            // 日志通道异常不能影响宿主
        }
    }

    @Override
    public void log(Throwable throwable) {
        try {
            XposedBridge.log(throwable);
        } catch (Throwable ignored) {
            // 日志通道异常不能影响宿主
        }
    }

    /**
     * 旧 API 没有“当前进程名”的正式回调参数（只有 {@code LoadPackageParam.processName}）。
     * 这里先用 {@code ActivityThread.currentProcessName()}，失败再读 {@code /proc/self/cmdline}。
     * 迁移完成后入口可以直接把 {@code lpparam.processName} 塞进来，届时更准。
     */
    @Override
    public String processName() {
        final String cached = processName;
        if (cached != null) {
            return cached;
        }
        String resolved = resolveProcessName();
        processName = resolved;
        return resolved;
    }

    private static String resolveProcessName() {
        try {
            final Class<?> activityThread =
                    XposedHelpers.findClassIfExists("android.app.ActivityThread", null);
            if (activityThread != null) {
                final Object name = XposedHelpers.callStaticMethod(activityThread, "currentProcessName");
                if (name instanceof String && !((String) name).isEmpty()) {
                    return (String) name;
                }
            }
        } catch (Throwable ignored) {
            // 落到 /proc
        }
        try (FileInputStream in = new FileInputStream("/proc/self/cmdline")) {
            final byte[] buf = new byte[256];
            final int read = in.read(buf);
            if (read > 0) {
                int end = 0;
                while (end < read && buf[end] != 0) {
                    end++;
                }
                if (end > 0) {
                    return new String(buf, 0, end, "UTF-8");
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return "";
    }

    /**
     * 旧 API 没有 {@code isSystemServer} 标志位，只能按进程名判断
     * （system_server 的 cmdline 是 {@code system_server}，LSPosed 传的 packageName 是
     * {@code android}）。新 API 的 {@code ModuleLoadedParam.isSystemServer()} 才是权威值。
     */
    @Override
    public boolean isSystemServer() {
        final String p = processName();
        return "android".equals(p) || "system_server".equals(p);
    }

    @Override
    public SharedPreferences getPrefs(String name) {
        // 与 PrefsClient 完全一致的通道（New XSharedPreferences，API 93+）
        return new XSharedPreferences(MODULE_PACKAGE, name);
    }

    // ---------------- hook ----------------

    @Override
    public Xp.Unhook hook(Member member, Callback callback) {
        final XC_MethodHook.Unhook unhook =
                XposedBridge.hookMethod(member, new Bridge(callback));
        return unhook::unhook;
    }

    /**
     * 旧后端能识别两种回调：已迁移的 {@link Callback}（走 {@link Bridge}）与
     * 尚未迁移的 {@code XC_MethodHook}（原样交给 {@code XposedBridge}，行为与迁移前逐字一致）。
     */
    @Override
    public Xp.Unhook hookAny(Member member, Object callback) {
        if (callback instanceof XC_MethodHook) {
            final XC_MethodHook.Unhook unhook =
                    XposedBridge.hookMethod(member, (XC_MethodHook) callback);
            return unhook == null ? NOOP_UNHOOK : unhook::unhook;
        }
        if (callback instanceof Callback) {
            return hook(member, (Callback) callback);
        }
        throw new IllegalArgumentException("unsupported hook callback: "
                + (callback == null ? "null" : callback.getClass().getName()));
    }

    /** 极端情况下框架返回 null Unhook 时的占位，调用方无需判空。 */
    private static final Xp.Unhook NOOP_UNHOOK = new Xp.Unhook() {
        @Override
        public void unhook() {
            // 没有装上的 hook：unhook 是空操作
        }
    };

    @Override
    public Xp.Unhook hookMethodExact(Class<?> clazz, String name, Class<?>[] parameterTypes,
                                     Callback callback) {
        // 旧 API 的形参是 Object...：显式转成 Object[]，避免“不精确的 varargs”编译警告
        final Method method =
                XposedHelpers.findMethodExactIfExists(clazz, name, (Object[]) parameterTypes);
        if (method == null) {
            throw new NoSuchMethodError(clazz.getName() + "#" + name);
        }
        return hook(method, callback);
    }

    @Override
    public int hookAllMethods(Class<?> clazz, String methodName, Callback callback) {
        return XposedBridge.hookAllMethods(clazz, methodName, new Bridge(callback)).size();
    }

    @Override
    public int hookAllConstructors(Class<?> clazz, Callback callback) {
        return XposedBridge.hookAllConstructors(clazz, new Bridge(callback)).size();
    }

    /** {@link Callback} → {@code XC_MethodHook}。 */
    private static final class Bridge extends XC_MethodHook {

        private final Callback callback;

        Bridge(Callback callback) {
            this.callback = callback;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            final com.vloc.motion.hidden.compat.Param p =
                    new com.vloc.motion.hidden.compat.Param(param.method, param.thisObject, param.args);
            callback.before(p);
            if (p.args != param.args) {
                param.args = p.args;
            }
            if (p.isResultSet()) {
                // 旧 API 的 setResult → returnEarly，原方法不再执行（与 Param 文档一致）
                param.setResult(p.getResult());
            } else if (p.hasThrowable()) {
                param.setThrowable(p.getThrowable());
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            final com.vloc.motion.hidden.compat.Param p =
                    new com.vloc.motion.hidden.compat.Param(param.method, param.thisObject, param.args);
            p.seedResult(param.getResult());
            p.seedThrowable(param.getThrowable());
            callback.after(p);
            if (p.args != param.args) {
                param.args = p.args;
            }
            if (p.isResultSet()) {
                param.setResult(p.getResult());
                p.clearThrowableFlag();
            } else if (p.isThrowableSet()) {
                // 含 setThrowable(null)：现有代码靠它吞掉原方法抛出的异常
                param.setThrowable(p.getThrowable());
            }
        }
    }

    // ---------------- 反射助手（薄转发 XposedHelpers） ----------------

    @Override
    public Class<?> findClass(String className, ClassLoader classLoader)
            throws ClassNotFoundException {
        return XposedHelpers.findClass(className, classLoader);
    }

    @Override
    public Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        return XposedHelpers.findClassIfExists(className, classLoader);
    }

    @Override
    public Method findMethodExact(Class<?> clazz, String name, Class<?>... parameterTypes) {
        return XposedHelpers.findMethodExact(clazz, name, parameterTypes);
    }

    @Override
    public Method findMethodExactIfExists(Class<?> clazz, String name, Class<?>... parameterTypes) {
        return XposedHelpers.findMethodExactIfExists(clazz, name, (Object[]) parameterTypes);
    }

    @Override
    public Field findFieldIfExists(Class<?> clazz, String name) {
        return XposedHelpers.findFieldIfExists(clazz, name);
    }

    @Override
    public Object callMethod(Object obj, String name, Object... args) {
        return XposedHelpers.callMethod(obj, name, args);
    }

    @Override
    public Object callStaticMethod(Class<?> clazz, String name, Object... args) {
        return XposedHelpers.callStaticMethod(clazz, name, args);
    }

    @Override
    public Object newInstance(Class<?> clazz, Object... args) {
        return XposedHelpers.newInstance(clazz, args);
    }

    @Override
    public Object getObjectField(Object obj, String name) {
        return XposedHelpers.getObjectField(obj, name);
    }

    @Override
    public void setObjectField(Object obj, String name, Object value) {
        XposedHelpers.setObjectField(obj, name, value);
    }

    @Override
    public int getIntField(Object obj, String name) {
        return XposedHelpers.getIntField(obj, name);
    }

    @Override
    public void setIntField(Object obj, String name, int value) {
        XposedHelpers.setIntField(obj, name, value);
    }

    @Override
    public boolean getBooleanField(Object obj, String name) {
        return XposedHelpers.getBooleanField(obj, name);
    }

    @Override
    public void setBooleanField(Object obj, String name, boolean value) {
        XposedHelpers.setBooleanField(obj, name, value);
    }

    @Override
    public Object getStaticObjectField(Class<?> clazz, String name) {
        return XposedHelpers.getStaticObjectField(clazz, name);
    }

    @Override
    public void setStaticObjectField(Class<?> clazz, String name, Object value) {
        XposedHelpers.setStaticObjectField(clazz, name, value);
    }
}
