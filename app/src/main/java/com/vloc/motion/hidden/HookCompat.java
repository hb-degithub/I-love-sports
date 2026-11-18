package com.vloc.motion.hidden;

import com.vloc.motion.hidden.compat.Xp;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 多版本兼容助手：<b>候选类表解析</b> + <b>统一候选日志</b> + <b>缺失即跳过</b>。
 *
 * <p><b>为什么需要它</b>：Android 8 ~ 17 的框架类名/方法名一直在搬（典型：A17 把定位投递搬到
 * {@code com.android.server.location.*.LocationProviderManager} 的内部 Transport 类、
 * GNSS 下发搬到 {@code GnssStatusProvider#onReportSvStatus}），代码里原本散落着
 * {@code findClass("A") / findClass("B")} 的写法，日志也是各写各的。这里把三件事收敛成一份：
 * <ol>
 *   <li>{@link #find}：单个类名解析，<b>任何 Throwable 都只返回 null</b>（类名写错、类被裁掉、
 *       OEM 改名都算“该版本没有”）；</li>
 *   <li>{@link #hookFirst}：候选类表 + 方法名表一次装完，<b>每个候选类固定一行日志</b>：
 *       {@code hook candidate <fqcn>: found=<bool> hooked=<n>}（附匹配到的方法与失败原因）；</li>
 *   <li>{@link #logCandidate}：单个候选的同一格式日志，给“自己写循环”的调用方复用。</li>
 * </ol>
 *
 * <p><b>与 Xposed API 的关系</b>：本类已迁移到 {@link Xp} 门面，不 import 任何
 * 旧 Xposed API 类型；旧/新后端由 {@code Xp.install(...)} 在入口处注入。
 *
 * <p><b>安全契约</b>：本类所有方法都以 {@code catch (Throwable)} 兜底，<b>绝不</b>向
 * Xposed 框架层 / 宿主进程抛异常。类或方法找不到时只记一条日志，返回 0 —— 调用方据此
 * 降级为“该版本没有这个入口”，其余 hook 照常安装。
 *
 * <p>线程安全：无状态（只有 {@code static} 方法），可在任意线程调用。
 */
final class HookCompat {

    private HookCompat() {
    }

    // ================================================================ 单类解析

    /**
     * 解析一个类名；<b>任何失败都返回 null</b>，不抛异常。
     *
     * <p>{@code classLoader} 为 null 时退回 {@code Class.forName}（boot classpath），
     * 这样极早期 / 拿不到 ClassLoader 的调用点也不会变成硬失败。
     */
    static Class<?> find(ClassLoader classLoader, String fqcn) {
        if (fqcn == null || fqcn.isEmpty()) {
            return null;
        }
        try {
            if (classLoader != null) {
                return Xp.findClassIfExists(fqcn, classLoader);
            }
            return Class.forName(fqcn);
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================ 候选类表 + 挂载

    /**
     * 按候选类表逐个尝试，在“方法名命中（可选返回类型校验）”的方法上挂 hook。
     *
     * <p>每个候选类<b>固定记一行</b> {@code hook candidate <fqcn>: found=… hooked=…}：
     * 类不存在记 {@code found=false hooked=0}，类在但没有目标方法记
     * {@code found=true hooked=0 (no method match […])}，全部成功记 {@code found=true hooked=n}。
     * 这样现场只看日志就能判断某个版本到底走的是哪条链路、少了哪个入口。
     *
     * @param classLoader    目标进程 ClassLoader（null 时走 {@code Class.forName}）
     * @param candidates     候选类名（按优先级排序；旧版本入口通常在前）
     * @param methodNames    要挂的方法名（同名重载全部挂上）
     * @param hook           回调：{@code compat.Callback}；迁移过渡期也接受尚未迁移的旧
     *                       {@code XC_MethodHook}（由 {@link Xp#hookAny} 分派，旧后端原样安装）
     * @param requiredReturn 返回类型过滤；null = 不校验
     * @param stopOnFirst    true = 命中一个类就停（避免同一条链路被挂两层）
     * @return 实际挂上的方法数；0 = 本版本没有这些入口（调用方降级）
     */
    static int hookFirst(ClassLoader classLoader, String tag, String[] candidates,
                         String[] methodNames, Object hook, Class<?> requiredReturn,
                         boolean stopOnFirst) {
        if (candidates == null || candidates.length == 0 || methodNames == null) {
            return 0;
        }
        final List<String> names = Arrays.asList(methodNames);
        int total = 0;
        for (String fqcn : candidates) {
            int hooked = 0;
            final List<String> matched = new ArrayList<>();
            boolean found = false;
            String reason = null;
            try {
                final Class<?> clazz = find(classLoader, fqcn);
                found = clazz != null;
                if (clazz != null) {
                    for (Method method : clazz.getDeclaredMethods()) {
                        if (!names.contains(method.getName())) {
                            continue;
                        }
                        if (requiredReturn != null && method.getReturnType() != requiredReturn) {
                            continue;
                        }
                        matched.add(method.getName());
                        try {
                            Xp.hookAny(method, hook);
                            hooked++;
                        } catch (Throwable t) {
                            if (reason == null) {
                                reason = tag + " hook failed " + method.getName() + ": " + t;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                found = false;
                reason = tag + " class lookup failed: " + t;
            }
            if (!found && reason == null) {
                // 类在本版本不存在（ROM 改名 / 类被裁掉 / 老 Android）：只记这一行，调用方跳过
                reason = tag + " class absent";
            }
            if (found && reason == null && matched.isEmpty()) {
                reason = tag + " no method match " + names;
            }
            logCandidate(fqcn, found, hooked,
                    matched.isEmpty() ? null : "methods=" + matched,
                    reason);
            total += hooked;
            if (stopOnFirst && hooked > 0) {
                break;
            }
        }
        return total;
    }

    // ================================================================ 统一候选日志

    /** {@code hook candidate <fqcn>: found=… hooked=…}（无附注）。 */
    static void logCandidate(String fqcn, boolean found, int hooked) {
        logCandidate(fqcn, found, hooked, null, null);
    }

    /**
     * {@code hook candidate <fqcn>: found=… hooked=…}（带附注）。
     *
     * @param detail 附加信息（如 {@code methods=[…]}）；null = 不显示
     * @param reason 失败原因；null = 不显示
     */
    static void logCandidate(String fqcn, boolean found, int hooked, String detail, String reason) {
        try {
            final StringBuilder sb = new StringBuilder(96);
            sb.append("hook candidate ").append(fqcn)
                    .append(": found=").append(found)
                    .append(" hooked=").append(hooked);
            if (detail != null && !detail.isEmpty()) {
                sb.append(" (").append(detail).append(')');
            }
            if (reason != null && !reason.isEmpty()) {
                sb.append(" (").append(reason).append(')');
            }
            log(sb.toString());
        } catch (Throwable ignored) {
            // 日志绝不抛出
        }
    }

    /** 统一前缀日志（{@code [VLocHidden] } 前缀由 {@link Xp} 后端统一加）。 */
    static void log(String message) {
        try {
            Xp.log(message);
        } catch (Throwable ignored) {
            // 日志本身失败只能吞掉
        }
    }
}
