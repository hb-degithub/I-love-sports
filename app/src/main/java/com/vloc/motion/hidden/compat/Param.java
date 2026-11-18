package com.vloc.motion.hidden.compat;

import java.lang.reflect.Member;

/**
 * 与 {@code XC_MethodHook.MethodHookParam} 语义等价的回调参数（框架无关）。
 *
 * <p>设计目标：把现有 {@code HookEntry}/{@code SystemHooks}/… 里对
 * 旧 API 的 {@code XC_MethodHook.MethodHookParam} 的用法
 * （{@code args} / {@code thisObject} / {@code method} / {@code getResult} /
 * {@code setResult} / {@code hasThrowable} / {@code setThrowable}）
 * 一对一搬过来，<b>不改业务写法</b>，只把类型换成本类。
 *
 * <p><b>setResult / setThrowable 的“跳过原方法”语义</b>（这是两套 API 最大的差别）：
 * <ul>
 *   <li>旧 API：在 {@code beforeHookedMethod} 里调 {@code setResult(...)} /
 *       {@code setThrowable(...)} 会置内部 {@code returnEarly}，XposedBridge
 *       <b>不再调用原方法</b>，直接跳到 after 回调；</li>
 *   <li>新 API：没有 {@code returnEarly}，等价物是<b>“不调用 {@code Chain#proceed()}”</b>。
 *       {@link NewBackend} 因此按“是否已 setResult / 是否已 setThrowable”决定是否 proceed，
 *       详见 {@link NewBackend} 的类注释。</li>
 * </ul>
 *
 * <p>{@code setThrowable(null)} 与旧 API 一致：清掉异常，{@link #hasThrowable()} 变 false
 * （现有代码在 after 里用它把原方法的异常吞掉，见 {@code EnvReplay}）。
 */
public final class Param {

    /** 方法实参，可原地改写；整体替换（{@code param.args = new Object[]{...}}）同样支持。 */
    public Object[] args;

    /** 被 hook 的成员：{@link java.lang.reflect.Method} 或 {@link java.lang.reflect.Constructor}。 */
    public final Member method;

    /** 实例方法/构造器的 this；静态方法为 null。 */
    public final Object thisObject;

    /** 当前结果（after 阶段由原方法返回值填充；before 阶段通常为 null）。 */
    private Object result;

    /** 是否被 {@link #setResult(Object)} 显式设置过（用于映射“跳过原方法”）。 */
    private boolean resultSet;

    /** 当前异常；null 表示正常返回。 */
    private Throwable throwable;

    /** 是否被 {@link #setThrowable(Throwable)} 显式改过（back-end 据此把异常回写给框架）。 */
    private boolean throwableSet;

    public Param(Member method, Object thisObject, Object[] args) {
        this.method = method;
        this.thisObject = thisObject;
        this.args = args;
    }

    /** 便捷构造：用现有 {@code MethodHookParam} 的四个字段初始化（不置 resultSet/throwableSet）。 */
    public static Param of(Member method, Object thisObject, Object[] args) {
        return new Param(method, thisObject, args);
    }

    public Object getResult() {
        return result;
    }

    /**
     * 设置返回值。
     *
     * <p>在 before 阶段调用 = 跳过原方法并返回该值（旧 API 的 returnEarly）；
     * 在 after 阶段调用 = 覆盖原方法的返回值。
     */
    public void setResult(Object value) {
        this.result = value;
        this.resultSet = true;
        // 与旧 API 一致：setResult 会清掉异常（getResultOrThrowable 只抛 throwable 或返回 result）
        this.throwable = null;
        this.throwableSet = false;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public boolean hasThrowable() {
        return throwable != null;
    }

    /** {@code null} = 清除异常（原方法抛出的异常被吞掉）。 */
    public void setThrowable(Throwable t) {
        this.throwable = t;
        this.throwableSet = true;
    }

    /** 旧 API 同名方法：有异常就抛，否则返回结果。 */
    public Object getResultOrThrowable() throws Throwable {
        if (throwable != null) {
            throw throwable;
        }
        return result;
    }

    // ---- 仅供 back-end 使用的状态查询/回填（业务代码不要用） ----

    /** 是否在 before 阶段显式 setResult 过 —— 新 API 据此决定不 proceed。 */
    public boolean isResultSet() {
        return resultSet;
    }

    /** 是否显式 setThrowable 过（含置 null）。 */
    boolean isThrowableSet() {
        return throwableSet;
    }

    /** back-end 回填“原方法的返回值”，不算调用方 setResult。 */
    void seedResult(Object value) {
        this.result = value;
    }

    /** back-end 回填“原方法抛出的异常”，不算调用方 setThrowable。 */
    void seedThrowable(Throwable t) {
        this.throwable = t;
    }

    /** after 阶段回写前清标记，避免把 back-end 自己的回填误判成“调用方为异常做过处理”。 */
    void clearThrowableFlag() {
        this.throwableSet = false;
    }
}
