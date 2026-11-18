package com.vloc.motion.hidden.compat;

import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 新 API 后端：把 {@link Xp} 的调用转成 {@code io.github.libxposed.api.*}（API 102）。
 *
 * <p>只 import 新 API，<b>不</b> 出现任何旧 Xposed API 类型 —— 纯新框架环境下
 * 本类不会去解析旧 API 的类。
 *
 * <h3>before/after → Chain 的映射（本轮最关键的设计结论）</h3>
 * 旧 API：before 回调 → 框架调原方法 → after 回调；在 before 里
 * {@code setResult/setThrowable} 会置 {@code returnEarly}，<b>原方法被跳过</b>。
 *
 * <p>新 API 只有一层：{@code Hooker#intercept(Chain)}，必须自己调 {@code chain.proceed()}，
 * 返回值就是“这个方法的返回值”。所以映射成：
 * <pre>
 *   Param p = new Param(chain.getExecutable(), chain.getThisObject(), chain.getArgs().toArray());
 *   callback.before(p);
 *   if (p.isResultSet() || p.hasThrowable()) {
 *       // ← 等价 returnEarly：不调用 proceed，原方法不执行
 *   } else {
 *       try { p.seedResult(chain.proceed(p.args)); }   // ← 原方法在这里跑
 *       catch (Throwable t) { p.seedThrowable(t); }
 *   }
 *   callback.after(p);                                  // ← 与旧 API 一样，永远执行
 *   if (p.hasThrowable()) throw p.getThrowable();
 *   return p.getResult();
 * </pre>
 *
 * <b>逐条结论</b>：
 * <ul>
 *   <li><b>before 里 setResult ⇒ 跳过 proceed</b>（旧 API 的 returnEarly 语义），
 *       直接返回该值；</li>
 *   <li><b>after 里 setResult ⇒ proceed 已经跑过</b>，返回值被覆盖；</li>
 *   <li><b>setThrowable(t) ⇒ 不 proceed、从 intercept 抛出</b>（等价旧 API）；
 *       after 里 {@code setThrowable(null)} 依然能吞掉原方法异常，因为异常先 seed 进
 *       {@link Param}、after 之后再统一判断；</li>
 *   <li><b>proceed 只能调用一次</b>：新 API 的 Chain 不可重入，所以“跳过”必须靠不调用，
 *       而不是“先 proceed 再丢弃”；旧 API 的 {@code XposedBridge.invokeOriginalMethod}
 *       那种“hook 内部再调原方法”的写法在新 API 里要改用 {@code getInvoker(...)}。</li>
 * </ul>
 *
 * <h3>args 的写回</h3>
 * {@code chain.getArgs()} 返回的是<b>列表快照</b>，对 {@link Param#args} 的原地改写
 * （现有代码常见的 {@code param.args[i] = x}）不会自动反映到原方法。因此
 * <b>无条件用 {@code chain.proceed(p.args)}</b>，参数按调用方看到的数组走。
 */
public final class NewBackend implements XpBackend {

    /** 模块 tag，与 {@code Log.i("VLocHidden", ...)} 一致。 */
    private static final String LOG_TAG = Xp.TAG;

    private final XposedInterface module;
    private final String processName;
    private final boolean systemServer;

    /**
     * @param module       {@code XposedModule} 实例本身（它就是 {@link XposedInterface}）
     * @param processName  {@code ModuleLoadedParam.getProcessName()}
     * @param systemServer {@code ModuleLoadedParam.isSystemServer()}
     */
    public NewBackend(XposedInterface module, String processName, boolean systemServer) {
        this.module = module;
        this.processName = processName == null ? "" : processName;
        this.systemServer = systemServer;
    }

    @Override
    public String name() {
        String api;
        try {
            api = String.valueOf(module.getApiVersion());
        } catch (Throwable t) {
            api = "?";
        }
        return "libxposed(api" + api + ")";
    }

    // ---------------- 日志 / 进程身份 / 偏好 ----------------

    @Override
    public void log(String message) {
        try {
            // 新 API 的签名是 log(int priority, String tag, String message)
            module.log(Log.INFO, LOG_TAG, message);
        } catch (Throwable ignored) {
            // 日志通道异常不能影响宿主
        }
    }

    @Override
    public void log(Throwable throwable) {
        try {
            // 新 API 没有 Throwable 重载：等价于旧 API 的 XposedBridge.log(t)（带完整堆栈）
            module.log(Log.ERROR, LOG_TAG, Log.getStackTraceString(throwable));
        } catch (Throwable ignored) {
            // 日志通道异常不能影响宿主
        }
    }

    @Override
    public String processName() {
        return processName;
    }

    @Override
    public boolean isSystemServer() {
        return systemServer;
    }

    /**
     * 新 API 的远程偏好：{@code getRemotePreferences(group)}，<b>hooked 进程内只读</b>
     * （官方文档明确；App 侧要写必须走 {@code XposedService} 绑定，见报告 D2）。
     *
     * <p>框架没有 {@code PROP_CAP_REMOTE} 能力位时这里会抛异常，按“不可用”返回 null，
     * 由调用方沿用现有降级路径（旧通道 / 空快照）。
     */
    @Override
    public SharedPreferences getPrefs(String name) {
        try {
            final long caps = module.getFrameworkProperties();
            if ((caps & XposedInterface.PROP_CAP_REMOTE) == 0) {
                log("remote prefs unsupported by framework, getPrefs(" + name + ") -> null");
                return null;
            }
            return module.getRemotePreferences(name);
        } catch (Throwable t) {
            log("getRemotePreferences(" + name + ") failed: " + t.getClass().getSimpleName());
            return null;
        }
    }

    // ---------------- hook ----------------

    @Override
    public Xp.Unhook hook(Member member, Callback callback) {
        if (!(member instanceof Executable)) {
            throw new IllegalArgumentException("not an Executable: " + member);
        }
        final XposedInterface.HookHandle handle =
                module.hook((Executable) member).intercept(new BridgingHooker(callback));
        return handle::unhook;
    }

    /**
     * 只接受 {@link Callback}：旧 API 的 {@code XC_MethodHook} 在新框架进程里根本不存在
     * （本类不能 import 旧 Xposed API，也没有任何办法让新框架执行它）。
     *
     * <p>遇到未迁移的旧回调时<b>不抛异常</b> —— 迁移期间这类调用点是“本进程少一条 hook”，
     * 不能让整条旁路安装失败；只记一条日志并返回空 handle。
     */
    @Override
    public Xp.Unhook hookAny(Member member, Object callback) {
        if (callback instanceof Callback) {
            return hook(member, (Callback) callback);
        }
        log("legacy hook callback not supported on libxposed backend, skip "
                + (member == null ? "null" : member.getName())
                + " (" + (callback == null ? "null" : callback.getClass().getName()) + ")");
        return NOOP_UNHOOK;
    }

    /** 被跳过的 hook 的占位 handle：unhook 是空操作。 */
    private static final Xp.Unhook NOOP_UNHOOK = new Xp.Unhook() {
        @Override
        public void unhook() {
            // 没有装上的 hook
        }
    };

    @Override
    public Xp.Unhook hookMethodExact(Class<?> clazz, String name, Class<?>[] parameterTypes,
                                     Callback callback) {
        final Method method = Reflect.findMethodExactIfExists(clazz, name, parameterTypes);
        if (method == null) {
            throw new NoSuchMethodError(clazz.getName() + "#" + name);
        }
        return hook(method, callback);
    }

    /**
     * 与旧 API 的 {@code hookAllMethods} 对齐：只看本类 {@code getDeclaredMethods()}
     * （旧实现同样不遍历父类），返回真正装上的重载数。
     *
     * <p>某个重载装不上（native / 抽象 / 框架拒绝）只跳过并计数失败，不抛给调用方 ——
     * 现有代码依赖“hook 失败不炸”的语义。
     */
    @Override
    public int hookAllMethods(Class<?> clazz, String methodName, Callback callback) {
        int installed = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            if (method.isBridge()) {
                continue; // 桥方法由框架/ART 生成，hook 它没有意义
            }
            try {
                module.hook(method).intercept(new BridgingHooker(callback));
                installed++;
            } catch (Throwable t) {
                log("hookAllMethods skip " + clazz.getName() + "#" + methodName
                        + methodSignature(method) + ": " + t.getClass().getSimpleName());
            }
        }
        return installed;
    }

    @Override
    public int hookAllConstructors(Class<?> clazz, Callback callback) {
        int installed = 0;
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            try {
                module.hook(ctor).intercept(new BridgingHooker(callback));
                installed++;
            } catch (Throwable t) {
                log("hookAllConstructors skip " + clazz.getName() + methodSignature(ctor)
                        + ": " + t.getClass().getSimpleName());
            }
        }
        return installed;
    }

    private static String methodSignature(Executable executable) {
        final Class<?>[] types = executable.getParameterTypes();
        final StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(types[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    /** {@link Callback} → {@code XposedInterface.Hooker}。 */
    private static final class BridgingHooker implements XposedInterface.Hooker {

        private final Callback callback;

        BridgingHooker(Callback callback) {
            this.callback = callback;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            final Executable executable = chain.getExecutable();
            final Object thisObject = chain.getThisObject();
            final java.util.List<Object> chainArgs = chain.getArgs();
            final Object[] args = chainArgs == null
                    ? new Object[0]
                    : chainArgs.toArray(new Object[0]);

            final Param param = new Param(executable, thisObject, args);
            callback.before(param);

            // 关键映射：before 里 setResult/setThrowable == 旧 API 的 returnEarly == 不 proceed
            if (!param.isResultSet() && !param.hasThrowable()) {
                try {
                    // 无条件传 param.args：chain.getArgs() 是快照，原地改写必须显式写回
                    param.seedResult(chain.proceed(param.args));
                } catch (Throwable original) {
                    // 等价旧 API：把原方法的异常存进 param，让 after 仍能看到/改写它
                    param.seedThrowable(original);
                }
            }

            callback.after(param);

            if (param.hasThrowable()) {
                throw param.getThrowable();
            }
            return param.getResult();
        }
    }

    // ---------------- 反射助手（新 API 无 XposedHelpers，落纯反射） ----------------

    @Override
    public Class<?> findClass(String className, ClassLoader classLoader)
            throws ClassNotFoundException {
        return Reflect.findClass(className, classLoader);
    }

    @Override
    public Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        return Reflect.findClassIfExists(className, classLoader);
    }

    @Override
    public Method findMethodExact(Class<?> clazz, String name, Class<?>... parameterTypes) {
        return Reflect.findMethodExact(clazz, name, parameterTypes);
    }

    @Override
    public Method findMethodExactIfExists(Class<?> clazz, String name, Class<?>... parameterTypes) {
        return Reflect.findMethodExactIfExists(clazz, name, parameterTypes);
    }

    @Override
    public Field findFieldIfExists(Class<?> clazz, String name) {
        return Reflect.findFieldIfExists(clazz, name);
    }

    @Override
    public Object callMethod(Object obj, String name, Object... args) {
        return Reflect.callMethod(obj, name, args);
    }

    @Override
    public Object callStaticMethod(Class<?> clazz, String name, Object... args) {
        return Reflect.callStaticMethod(clazz, name, args);
    }

    @Override
    public Object newInstance(Class<?> clazz, Object... args) {
        return Reflect.newInstance(clazz, args);
    }

    @Override
    public Object getObjectField(Object obj, String name) {
        final Field f = requireField(obj == null ? null : obj.getClass(), name);
        try {
            return f.get(obj);
        } catch (Throwable t) {
            throw new IllegalStateException("getObjectField " + name + " failed", t);
        }
    }

    @Override
    public void setObjectField(Object obj, String name, Object value) {
        final Field f = requireField(obj == null ? null : obj.getClass(), name);
        try {
            f.set(obj, value);
        } catch (Throwable t) {
            throw new IllegalStateException("setObjectField " + name + " failed", t);
        }
    }

    @Override
    public int getIntField(Object obj, String name) {
        final Field f = requireField(obj == null ? null : obj.getClass(), name);
        try {
            return f.getInt(obj);
        } catch (Throwable t) {
            throw new IllegalStateException("getIntField " + name + " failed", t);
        }
    }

    @Override
    public void setIntField(Object obj, String name, int value) {
        final Field f = requireField(obj == null ? null : obj.getClass(), name);
        try {
            f.setInt(obj, value);
        } catch (Throwable t) {
            throw new IllegalStateException("setIntField " + name + " failed", t);
        }
    }

    @Override
    public boolean getBooleanField(Object obj, String name) {
        final Field f = requireField(obj == null ? null : obj.getClass(), name);
        try {
            return f.getBoolean(obj);
        } catch (Throwable t) {
            throw new IllegalStateException("getBooleanField " + name + " failed", t);
        }
    }

    @Override
    public void setBooleanField(Object obj, String name, boolean value) {
        final Field f = requireField(obj == null ? null : obj.getClass(), name);
        try {
            f.setBoolean(obj, value);
        } catch (Throwable t) {
            throw new IllegalStateException("setBooleanField " + name + " failed", t);
        }
    }

    @Override
    public Object getStaticObjectField(Class<?> clazz, String name) {
        final Field f = requireField(clazz, name);
        try {
            return f.get(null);
        } catch (Throwable t) {
            throw new IllegalStateException("getStaticObjectField " + name + " failed", t);
        }
    }

    @Override
    public void setStaticObjectField(Class<?> clazz, String name, Object value) {
        final Field f = requireField(clazz, name);
        try {
            f.set(null, value);
        } catch (Throwable t) {
            throw new IllegalStateException("setStaticObjectField " + name + " failed", t);
        }
    }

    private static Field requireField(Class<?> clazz, String name) {
        final Field f = clazz == null ? null : Reflect.findFieldIfExists(clazz, name);
        if (f == null) {
            throw new NoSuchFieldError((clazz == null ? "null" : clazz.getName()) + "#" + name);
        }
        return f;
    }
}
