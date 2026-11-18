package com.vloc.motion.hidden.compat;

import android.content.SharedPreferences;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

/**
 * 跨新旧 Xposed API 的门面（静态转发到已安装的 {@link XpBackend}）。
 *
 * <p><b>用法</b>：入口类在加载时注入后端，业务代码只调 {@code Xp.xxx(...)}：
 * <pre>
 *   // 旧入口（assets/xposed_init → HookEntry）
 *   Xp.install(new LegacyBackend());
 *   // 新入口（META-INF/xposed/java_init.list → NewApiEntry）
 *   Xp.install(new NewBackend(module, param.getProcessName(), param.isSystemServer()));
 * </pre>
 *
 * <p><b>为什么是静态门面而不是构造注入</b>：现有 12 个 hidden/*.java 全是静态方法 +
 * 静态 {@code XC_MethodHook} 常量，改成实例注入要重写全部调用点；门面方式让迁移可以
 * 逐个文件、逐条 hook 进行，未迁移的代码继续直连旧 Xposed API，两者在同一 APK 内共存。
 *
 * <p><b>类加载隔离</b>：本类只引用 {@link XpBackend}，<b>不</b> import 旧 Xposed API
 * 或 {@code io.github.libxposed.*}。旧框架进程只加载 {@link LegacyBackend}，
 * 新框架进程只加载 {@link NewBackend}，缺哪套 API 的类都不会被解析到。
 *
 * <p><b>必须 install 后才能用</b>：未注入时抛 {@link IllegalStateException}
 * （只会发生在“入口写错/框架加载了错误入口”的开发期，正常路径不会走到）。
 */
public final class Xp {

    /** 与现有日志前缀保持一致。 */
    public static final String TAG = "VLocHidden";

    private static volatile XpBackend backend;

    private Xp() {
    }

    /** 注入后端（重复调用覆盖）。 */
    public static void install(XpBackend impl) {
        if (impl == null) {
            throw new IllegalArgumentException("backend == null");
        }
        backend = impl;
        log("xp backend installed: " + impl.name());
    }

    /**
     * 只在未注入时注入。
     *
     * <p>用于“双入口同时被加载”的降级保护：先到者胜，后到者不覆盖，
     * 避免同一个进程里两套后端各记一份 hook 计数。
     *
     * @return true = 本次完成注入；false = 已有后端，忽略
     */
    public static synchronized boolean installIfAbsent(XpBackend impl) {
        if (backend != null) {
            log("xp backend already installed (" + backend.name() + "), ignore " + impl.name());
            return false;
        }
        install(impl);
        return true;
    }

    public static boolean isInstalled() {
        return backend != null;
    }

    /** 未安装时返回 null（探测用），业务路径请用其它方法。 */
    public static XpBackend backendOrNull() {
        return backend;
    }

    private static XpBackend b() {
        final XpBackend current = backend;
        if (current == null) {
            throw new IllegalStateException("Xp backend not installed: 入口类必须先调用 Xp.install(...)");
        }
        return current;
    }

    // ---------------- 日志 / 进程身份 / 偏好 ----------------

    public static void log(String message) {
        b().log(message);
    }

    /** 对应旧 API 的 {@code XposedBridge.log(Throwable)}：日志里带完整堆栈。 */
    public static void log(Throwable throwable) {
        b().log(throwable);
    }

    public static String processName() {
        return b().processName();
    }

    public static boolean isSystemServer() {
        return b().isSystemServer();
    }

    /** 见 {@link XpBackend#getPrefs(String)}：旧 API = XSharedPreferences，新 API = 只读远程偏好。 */
    public static SharedPreferences getPrefs(String name) {
        return b().getPrefs(name);
    }

    // ---------------- hook ----------------

    public static Unhook hook(Member member, Callback callback) {
        return b().hook(member, callback);
    }

    /**
     * 迁移过渡：{@code callback} 可以是 {@link Callback}，也可以是尚未迁移的旧
     * {@code XC_MethodHook}（旧后端按原语义安装，新后端只记日志并跳过）。
     *
     * @see XpBackend#hookAny(Member, Object)
     */
    public static Unhook hookAny(Member member, Object callback) {
        return b().hookAny(member, callback);
    }

    /** 见 {@link XpBackend#hookMethodExact}：精确签名查找 + 安装（方法不存在抛 {@link NoSuchMethodError}）。 */
    public static Unhook hookMethodExact(Class<?> clazz, String name, Class<?>[] parameterTypes,
                                         Callback callback) {
        return b().hookMethodExact(clazz, name, parameterTypes, callback);
    }

    /** @return 实际装上的重载数（0 = 方法不存在，调用方按既有逻辑跳过即可）。 */
    public static int hookAllMethods(Class<?> clazz, String methodName, Callback callback) {
        return b().hookAllMethods(clazz, methodName, callback);
    }

    /** @return 实际装上的构造器数。 */
    public static int hookAllConstructors(Class<?> clazz, Callback callback) {
        return b().hookAllConstructors(clazz, callback);
    }

    // ---------------- 反射助手 ----------------

    public static Class<?> findClass(String className, ClassLoader classLoader)
            throws ClassNotFoundException {
        return b().findClass(className, classLoader);
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        return b().findClassIfExists(className, classLoader);
    }

    public static Method findMethodExact(Class<?> clazz, String name, Class<?>... parameterTypes) {
        return b().findMethodExact(clazz, name, parameterTypes);
    }

    public static Method findMethodExactIfExists(Class<?> clazz, String name,
                                                 Class<?>... parameterTypes) {
        return b().findMethodExactIfExists(clazz, name, parameterTypes);
    }

    public static Field findFieldIfExists(Class<?> clazz, String name) {
        return b().findFieldIfExists(clazz, name);
    }

    public static Object callMethod(Object obj, String name, Object... args) {
        return b().callMethod(obj, name, args);
    }

    public static Object callStaticMethod(Class<?> clazz, String name, Object... args) {
        return b().callStaticMethod(clazz, name, args);
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        return b().newInstance(clazz, args);
    }

    public static Object getObjectField(Object obj, String name) {
        return b().getObjectField(obj, name);
    }

    public static void setObjectField(Object obj, String name, Object value) {
        b().setObjectField(obj, name, value);
    }

    public static int getIntField(Object obj, String name) {
        return b().getIntField(obj, name);
    }

    public static void setIntField(Object obj, String name, int value) {
        b().setIntField(obj, name, value);
    }

    public static boolean getBooleanField(Object obj, String name) {
        return b().getBooleanField(obj, name);
    }

    public static void setBooleanField(Object obj, String name, boolean value) {
        b().setBooleanField(obj, name, value);
    }

    public static Object getStaticObjectField(Class<?> clazz, String name) {
        return b().getStaticObjectField(clazz, name);
    }

    public static void setStaticObjectField(Class<?> clazz, String name, Object value) {
        b().setStaticObjectField(clazz, name, value);
    }

    /** 对应旧 API 的 {@code XC_MethodHook.Unhook} / 新 API 的 {@code HookHandle}。 */
    public interface Unhook {
        void unhook();
    }
}
