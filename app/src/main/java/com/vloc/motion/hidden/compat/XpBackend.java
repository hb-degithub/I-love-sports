package com.vloc.motion.hidden.compat;

import android.content.SharedPreferences;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

/**
 * {@link Xp} 的后端 SPI：把同一组语义分别落到旧 Xposed API
 * 或新 API（{@code io.github.libxposed.api}）。
 *
 * <p>实现类必须<b>互不引用</b>：
 * <ul>
 *   <li>{@link LegacyBackend} 只 import 旧 Xposed API；</li>
 *   <li>{@link NewBackend} 只 import {@code io.github.libxposed.api.*}。</li>
 * </ul>
 * 这样旧框架进程里只需要加载 {@link LegacyBackend} 的 class，不会因为缺少
 * {@code io.github.libxposed.*} 而在类校验阶段失败；反之亦然。
 * {@link Xp} 本身只依赖本接口，不出现任何一方的类型。
 */
public interface XpBackend {

    /** 后端名，仅用于日志（{@code legacy} / {@code libxposed-102}）。 */
    String name();

    // ---------------- 日志 / 进程身份 / 偏好 ----------------

    /** 对应 {@code XposedBridge.log(String)} / {@code XposedInterface.log(INFO, ...)}。 */
    void log(String message);

    /**
     * 对应 {@code XposedBridge.log(Throwable)}（带完整堆栈）。
     *
     * <p>迁移前 {@code HookEntry#logError} 用它把异常堆栈单独打一条；新 API 没有等价重载，
     * 由 {@link NewBackend} 用 {@code Log.getStackTraceString} 拼成同格式的一条消息。
     */
    void log(Throwable throwable);

    /** 当前进程名。 */
    String processName();

    /** 是否 system_server。 */
    boolean isSystemServer();

    /**
     * 读模块自有偏好：
     * <ul>
     *   <li>旧 API：{@code new XSharedPreferences(模块包名, name)}（LSPosed API 93+ 的
     *       world-readable 通道）；</li>
     *   <li>新 API：{@code XposedInterface.getRemotePreferences(name)}（<b>只读</b>）。</li>
     * </ul>
     */
    SharedPreferences getPrefs(String name);

    // ---------------- hook ----------------

    /** 对应 {@code XposedBridge.hookMethod(Member, XC_MethodHook)}。 */
    Xp.Unhook hook(Member member, Callback callback);

    /**
     * 迁移过渡用的宽松版本：{@code callback} 既可以是 {@link Callback}，
     * 也可以是<b>尚未迁移</b>的旧 API {@code XC_MethodHook}（只可能在旧后端里被接受）。
     *
     * <p>为什么要它：{@code hidden/} 是逐个文件迁移的，像 {@code SystemHooks} 这类
     * 还没迁移的调用方仍会把旧 {@code XC_MethodHook} 传给已经迁好的
     * {@code HookCompat.hookFirst}。旧后端直接把它交给 {@code XposedBridge.hookMethod}
     * （行为与迁移前完全一致）；新后端进程里根本没有旧 Xposed API 的类，
     * 无法也不应该识别它，只记一条日志并返回空 {@link Xp.Unhook}（跳过这条 hook）。
     */
    Xp.Unhook hookAny(Member member, Object callback);

    /**
     * 对应旧 API 的 {@code XposedHelpers.findAndHookMethod(clazz, name, params..., callback)}：
     * 按<b>精确签名</b>找方法再挂上（不遍历同名重载）。
     *
     * @throws NoSuchMethodError 方法不存在（与旧 API 一致；调用方自己的 {@code catch (Throwable)} 兜住）
     */
    Xp.Unhook hookMethodExact(Class<?> clazz, String name, Class<?>[] parameterTypes,
                              Callback callback);

    /**
     * 对应 {@code XposedBridge.hookAllMethods(Class, String, XC_MethodHook)}，
     * 返回<b>实际装上的重载数</b>（现有代码用 {@code .size()} 记日志/心跳）。
     */
    int hookAllMethods(Class<?> clazz, String methodName, Callback callback);

    /** 对应 {@code XposedBridge.hookAllConstructors(Class, XC_MethodHook)}，返回装上的构造器数。 */
    int hookAllConstructors(Class<?> clazz, Callback callback);

    // ---------------- 反射助手（旧 API 由 XposedHelpers 提供，新 API 需自建） ----------------

    Class<?> findClass(String className, ClassLoader classLoader) throws ClassNotFoundException;

    Class<?> findClassIfExists(String className, ClassLoader classLoader);

    Method findMethodExact(Class<?> clazz, String name, Class<?>... parameterTypes);

    Method findMethodExactIfExists(Class<?> clazz, String name, Class<?>... parameterTypes);

    Field findFieldIfExists(Class<?> clazz, String name);

    Object callMethod(Object obj, String name, Object... args);

    Object callStaticMethod(Class<?> clazz, String name, Object... args);

    Object newInstance(Class<?> clazz, Object... args);

    Object getObjectField(Object obj, String name);

    void setObjectField(Object obj, String name, Object value);

    int getIntField(Object obj, String name);

    void setIntField(Object obj, String name, int value);

    boolean getBooleanField(Object obj, String name);

    void setBooleanField(Object obj, String name, boolean value);

    Object getStaticObjectField(Class<?> clazz, String name);

    void setStaticObjectField(Class<?> clazz, String name, Object value);
}
