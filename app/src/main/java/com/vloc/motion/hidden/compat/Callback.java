package com.vloc.motion.hidden.compat;

/**
 * 与 {@code XC_MethodHook} 语义等价的回调基类（框架无关）。
 *
 * <p>迁移时把 {@code new XC_MethodHook() { protected void beforeHookedMethod(p) {...} }}
 * 换成 {@code new Callback() { protected void before(Param p) {...} }} 即可，
 * 方法体里的 {@code p.args / p.thisObject / p.method / p.setResult / p.getResult /
 * p.hasThrowable / p.setThrowable} 全部保持一致。
 *
 * <p><b>执行顺序（与旧 API 一致）</b>：
 * <ol>
 *   <li>{@link #before(Param)}；</li>
 *   <li>若 before 已 {@code setResult}/{@code setThrowable} → 跳过原方法；
 *       否则调用原方法并把返回值/异常回填进 {@link Param}；</li>
 *   <li>{@link #after(Param)}；<b>无论上一步是否跳过原方法，after 都会执行</b>
 *       （旧 API 同样如此）；</li>
 *   <li>按 after 之后的最终状态返回/抛出。</li>
 * </ol>
 *
 * <p>因此现有代码“在 after 里 {@code setThrowable(null)} 吞掉异常”的写法可以原样保留。
 */
public abstract class Callback {

    /** 默认优先级，对应旧 API 的 50（{@code XCallback.PRIORITY_DEFAULT}）。 */
    public static final int PRIORITY_DEFAULT = 50;

    /** 原方法被调用前。默认空实现。 */
    protected void before(Param param) throws Throwable {
    }

    /** 原方法返回/抛出之后（即使 before 跳过了原方法也会执行）。默认空实现。 */
    protected void after(Param param) throws Throwable {
    }

    /**
     * 等价 {@code XC_MethodReplacement.returnConstant(value)}：
     * before 阶段设置返回值 → 永不调用原方法。
     */
    public static Callback replaceWith(final Object value) {
        return new Callback() {
            @Override
            protected void before(Param param) {
                param.setResult(value);
            }
        };
    }

    /**
     * 等价 {@code XC_MethodReplacement.DO_NOTHING}（用于 void 方法）：不调原方法、返回 null。
     */
    public static Callback doNothing() {
        return new Callback() {
            @Override
            protected void before(Param param) {
                param.setResult(null);
            }
        };
    }
}
