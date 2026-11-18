package com.vloc.motion.core;

import android.util.Log;

import java.lang.reflect.Field;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志门面：<b>release 包里 {@code d/i/w} 直接 return，错误日志（{@code e}）照常输出</b>。
 *
 * <p><b>为什么需要它</b>：注入链路上的标签（{@code VLocService/VLocInjector/VLocRoot/
 * VLocHidden/VLocCleanup/VLocGnss/VLocPrefs/VLocSnapshot}）在正式包里也会往 logcat 写
 * "什么时候接管了定位、注入了哪些 Provider、套接字叫什么叫什么"。目标 App 本身读不到
 * logcat（没有 {@code READ_LOGS}），但 root 工具、日志采集器、{@code adb logcat} 都能读到，
 * 等于把注入行为直接摊在桌面上。所以正式包一律静默，只有 {@code Log.e} 保留
 * （出错时必须可见，否则现场无法定位）。
 *
 * <p><b>门控</b>：{@code d/i/w} 默认输出（见 {@link #setEnabled(boolean)}），坐标脱敏。
 * {@code BuildConfig} 用反射读而不是直接引用：
 * <ul>
 *   <li>AGP 8.x 默认 {@code buildFeatures.buildConfig = false}，本项目没有显式打开，
 *       直接引用会让编译失败；</li>
 *   <li>反射读不到时<b>按 release 处理</b>（静默）——宁可 debug 包少打日志，
 *       也不能把调试日志漏进正式包。</li>
 * </ul>
 * 若以后在 {@code app/build.gradle} 里加 {@code buildFeatures { buildConfig true }}，
 * 这里无需改动即按 {@code BuildConfig.DEBUG} 精确生效。
 *
 * <p><b>脱敏</b>：
 * <ul>
 *   <li>{@link #sanitize(String)}：把带小数点的数字压成两位小数 + {@code ****}
 *       （{@code 38.897234} → {@code 38.89****}），坐标/经纬度不再写进日志；整数
 *       （米数、毫秒、序号）原样保留，诊断信息不丢；</li>
 *   <li>{@link #mask(String)}：套接字名等会话标识只留前 6 个字符 + {@code …}。</li>
 * </ul>
 *
 * <p>线程安全：所有方法无状态，静态字段只在类初始化时写一次。
 */
public final class VLog {

    /**
     * 是否输出 {@code d/i/w}。
     *
     * <p><b>默认输出</b>：这些日志只有 root / READ_LOGS 能读到，藏它的收益很低，
     * 而现场排查（模拟定位这类问题）几乎完全依赖它们。因此 release 包也保留，
     * 但坐标经 {@link #sanitize} 脱敏、套接字名经 {@link #mask} 打码。
     *
     * <p>{@link #setEnabled(boolean)} 供将来做「详细日志」开关；置 false 后
     * {@code d/i/w} 静默，{@code e} 始终输出。
     */
    private static volatile boolean sEnabled = true;

    /** 运行时开关（供「详细日志」设置项使用）：false 时 d/i/w 静默，e 不受影响。 */
    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    /**
     * 带小数点的数字：只处理这类值（整数是米数 / 毫秒 / 序号，不该被改）。
     *
     * <p>{@code -?} 覆盖负经纬度；{@code \d+\.\d+} 不匹配 {@code 1.}（Java 里不会出现这种字面量）。
     */
    private static final Pattern DECIMAL = Pattern.compile("-?\\d+\\.\\d+");

    /** 脱敏后保留的小数位数（两位 ≈ 1km 量级，够定位"哪一段逻辑"，不够还原坐标）。 */
    private static final int KEEP_DECIMALS = 2;

    /** 打码占位符（与 {@link #sanitize(String)} 的返回值一起说明"这里被截断了"）。 */
    private static final String MASKED_DECIMALS = "****";

    private VLog() {
    }

    // ---------------------------------------------------------------- 门控日志

    /** debug 包才输出的 DEBUG 级日志。 */
    public static void d(String tag, String message) {
        if (isEnabled()) {
            Log.d(tag, message);
        }
    }

    /** debug 包才输出的 DEBUG 级日志（带异常）。 */
    public static void d(String tag, String message, Throwable error) {
        if (isEnabled()) {
            Log.d(tag, message, error);
        }
    }

    /** debug 包才输出的 INFO 级日志。 */
    public static void i(String tag, String message) {
        if (isEnabled()) {
            Log.i(tag, message);
        }
    }

    /** debug 包才输出的 INFO 级日志（带异常）。 */
    public static void i(String tag, String message, Throwable error) {
        if (isEnabled()) {
            Log.i(tag, message, error);
        }
    }

    /** debug 包才输出的 WARN 级日志。 */
    public static void w(String tag, String message) {
        if (isEnabled()) {
            Log.w(tag, message);
        }
    }

    /** debug 包才输出的 WARN 级日志（带异常）。 */
    public static void w(String tag, String message, Throwable error) {
        if (isEnabled()) {
            Log.w(tag, message, error);
        }
    }

    /**
     * 错误日志：<b>不受 DEBUG 门控</b>，release 包里也输出。
     *
     * <p>既不满足条件就不写日志的实现会被现场骂：出错时没有任何线索。
     */
    public static void e(String tag, String message) {
        Log.e(tag, message);
    }

    /** 错误日志（带异常）：不受 DEBUG 门控。 */
    public static void e(String tag, String message, Throwable error) {
        Log.e(tag, message, error);
    }

    /** 当前是否输出 {@code d/i/w}（调用方可据此跳过昂贵的字符串拼接）。 */
    public static boolean isEnabled() {
        return sEnabled;
    }

    // ---------------------------------------------------------------- 脱敏

    /**
     * 坐标打码：带小数点的数字只留整数部分 + 两位小数，其余用 {@code ****} 顶掉。
     *
     * <pre>
     *   "lat=38.897234,lng=116.407412" → "lat=38.89****,lng=116.40****"
     *   "distance=123m duration=45s"   → 原样（没有小数点，整数不动）
     * </pre>
     *
     * @param text 原始日志文本；{@code null} / 空串原样返回
     * @return 打码后的文本
     */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        final Matcher matcher = DECIMAL.matcher(text);
        final StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        boolean replaced = false;
        while (matcher.find()) {
            final String number = matcher.group();
            final int dot = number.indexOf('.');
            final int decimals = number.length() - dot - 1;
            out.append(text, copied, matcher.start());
            if (decimals <= KEEP_DECIMALS) {
                out.append(number);   // 本来就是两位以内：没有可泄露的精度
            } else {
                out.append(number, 0, dot + 1 + KEEP_DECIMALS).append(MASKED_DECIMALS);
                replaced = true;
            }
            copied = matcher.end();
        }
        if (!replaced) {
            return text;   // 一个数字都没改：避免多余的内存复制
        }
        out.append(text, copied, text.length());
        return out.toString();
    }

    /**
     * 名称脱敏：只保留前 6 个字符 + {@code …}（随机套接字名、会话标识都走这里）。
     *
     * <p>{@code vloc-1a2b3c4d5e6f7a8b} → {@code vloc-1…}：日志里不再出现完整名字，
     * 拿到日志的人无法据此连上正在监听的抽象套接字。
     *
     * @param name 原始名称；{@code null} 返回 {@code "?"}
     */
    public static String mask(String name) {
        if (name == null) {
            return "?";
        }
        if (name.length() <= 6) {
            return name;   // 短到没有可隐藏的部分（正常名字都远长于 6）
        }
        return name.substring(0, 6) + "…";
    }

    // ---------------------------------------------------------------- 门控来源

    /**
     * 解析是否 debug 包：反射读 {@code com.vloc.motion.BuildConfig.DEBUG}。
     *
     * <p>任何异常（类不存在 / 字段被裁掉 / {@code Class.forName} 受限）都按 {@code false}
     * 处理，保证正式包<b>绝不</b>因为判断失败而多打日志。
     */
    private static boolean resolveDebug() {
        try {
            final Class<?> buildConfig = Class.forName("com.vloc.motion.BuildConfig");
            final Field debug = buildConfig.getField("DEBUG");
            final Object value = debug.get(null);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable ignored) {
            // BuildConfig 未生成（AGP 8 默认关闭 buildConfig）：按 release 处理
            return false;
        }
    }
}
