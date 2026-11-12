package com.vloc.motion.hidden;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 自报心跳：模块在被注入的每个进程里，主动向主 App 写一条“我在这里装上了 N 个 hook”。
 *
 * <p>为什么需要它：用户的机器上常装 HideMyAppList 一类隐藏模块，主 App 查不到
 * {@code org.lsposed.manager} 包名，于是「模块状态」误报“LSP 框架没有生效”——而模块其实
 * 工作正常（LSPosed 日志里有 {@code hooks installed in ...}）。心跳让主 App 不依赖
 * “能否看见 LSPosed 管理器包”就能判定模块是否生效。
 *
 * <p>数据契约（主 App 侧同步实现，见 {@code EnvProvider} 的 {@code hookbeat} 路径）：
 * <pre>
 * ContentResolver#insert("content://com.vloc.motion.env/hookbeat", values)
 *   process (String)  进程名：com.android.location.fused / android / 目标应用包名
 *   hooks   (int)     本次在该进程安装成功的 hook 数量
 *   kind    (String)  system（系统 uid 段的系统进程）/ app（目标应用进程）
 *   at      (long)    System.currentTimeMillis()
 *   sdk     (int)     Build.VERSION.SDK_INT
 * </pre>
 *
 * <p>约束：
 * <ul>
 *   <li><b>每进程成功一次即可</b>：{@code sReported} 只在 {@code insert} 返回非 null Uri
 *       之后才置位；失败（拿不到 Context / insert 返回 null / 抛异常）<b>不</b>置位，
 *       因此后续还有补报机会。进程之间天然各有一份静态状态，“每个被注入的进程各报一次”
 *       仍是预期行为，主 App 按 {@code process} 分别记录（同名进程重复上报是覆盖写，幂等）。</li>
 *   <li><b>有界重试</b>：失败后最多再补 4 次（总计 {@value #MAX_ATTEMPTS} 次尝试），
 *       间隔 2s / 5s / 10s / 30s，用主线程 {@code Handler#postDelayed} 排队
 *       （一次性任务，不建常驻线程、不轮询），另外 {@link #retryIfPending()} 允许既有 hook
 *       回调“顺手”补一次。全部失败后放弃并只记一条日志。</li>
 *   <li><b>绝不影响宿主进程</b>：Provider 未启动 / 没有权限 / 任何 Throwable 全部吞掉；
 *       每次进入只做几次 volatile 读 + 一个 AtomicBoolean 的 CAS，不分配对象、不加锁、
 *       不 {@code Thread.sleep}、不 while 循环，真正的上报动作每次尝试最多 1 次。</li>
 *   <li>只在“hook 真的装上了”之后调用（被作用域 skip 的进程不报，见 {@link HookEntry}）。</li>
 * </ul>
 *
 * <p>为什么必须“成功才占坑”（0.42.2 修复的致命缺陷）：模块随 system_server /
 * {@code com.android.location.fused} 等在软重启时一起加载，那一刻主 App 进程往往还没起来，
 * {@code ContentResolver.insert} 会失败（Provider 拉不起 / DeadObject / 返回 null）。
 * 旧实现先 {@code compareAndSet(false, true)} 再上报，闸门提前关死 → 该进程此后永不重试
 * → 心跳永久丢失，主 App 只能退回“旧版心跳，无进程详情”的结论。
 */
final class HookHeartbeat {

    private static final String TAG = "[VLocHidden] ";

    /** 主 App 的心跳接收 URI（主 App 侧 EnvProvider 的 {@code hookbeat} 路径）。 */
    private static final String HEARTBEAT_URI = "content://com.vloc.motion.env/hookbeat";

    /** ContentValues 字段名，必须与主 App 侧逐字一致。 */
    private static final String COL_PROCESS = "process";
    private static final String COL_HOOKS = "hooks";
    private static final String COL_KIND = "kind";
    private static final String COL_AT = "at";
    private static final String COL_SDK = "sdk";

    /** kind：系统进程（system_server / com.android.location.fused / settings / telecom 等系统 uid 进程）。 */
    static final String KIND_SYSTEM = "system";

    /** kind：目标应用进程。 */
    static final String KIND_APP = "app";

    /** 本进程总尝试次数上限（首次 + 4 次补试）。到顶后放弃，绝不再排队。 */
    private static final int MAX_ATTEMPTS = 5;

    /** 任意两次尝试之间的最小间隔：hook 回调里的“顺手补试”靠它限频，不会变成忙等。 */
    private static final long MIN_ATTEMPT_INTERVAL_MS = 2_000L;

    /**
     * 第 n 次尝试失败后，下一次尝试的等待时间（毫秒）。
     * 长度 = {@link #MAX_ATTEMPTS} - 1，正好覆盖“首次之外的每一次补试”。
     */
    private static final long[] RETRY_DELAYS_MS = {2_000L, 5_000L, 10_000L, 30_000L};

    /**
     * 每进程“已成功上报”的闸门。
     *
     * <p><b>只在 {@code insert} 返回非 null Uri 之后才置位</b>（旧实现提前占坑是致命缺陷）。
     * 失败不置位，配合 {@link #sAttempts} 的次数上限做有界重试。
     */
    private static final AtomicBoolean sReported = new AtomicBoolean(false);

    /** 已发起的尝试次数（含首次），封顶 {@link #MAX_ATTEMPTS}。 */
    private static final AtomicInteger sAttempts = new AtomicInteger(0);

    /** 同一时刻只允许一个线程真的去 insert，避免重复上报 / 并发刷 Provider。 */
    private static final AtomicBoolean sInFlight = new AtomicBoolean(false);

    /** 只允许挂一条待执行的延迟补试，避免重试链自我复制。 */
    private static final AtomicBoolean sRetryScheduled = new AtomicBoolean(false);

    /** 各类失败日志每进程最多一条（诊断用，不刷屏）。 */
    private static final AtomicBoolean sLoggedNoContext = new AtomicBoolean(false);
    private static final AtomicBoolean sLoggedInsertNull = new AtomicBoolean(false);
    private static final AtomicBoolean sLoggedThrowable = new AtomicBoolean(false);
    private static final AtomicBoolean sLoggedAbandoned = new AtomicBoolean(false);

    /** 上次真正尝试上报的时刻（{@link SystemClock#elapsedRealtime()}，单调钟，不受改时间影响）。 */
    private static volatile long sLastAttemptAt;

    /** 待上报参数：首次失败后保留，供延迟补试与 hook 回调补试复用（进程内不会变）。 */
    private static volatile String sPendingProcess;
    private static volatile int sPendingHooks;
    private static volatile String sPendingKind;

    /** 进程内复用的 Context（同一个进程里 ContentResolver 不会变，省掉每进程一次的反射）。 */
    private static volatile Context sContext;

    /** 延迟补试用的 Handler（主线程 Looper 懒加载，创建一次后复用；拿不到就退化为回调补试）。 */
    private static volatile Handler sRetryHandler;

    private HookHeartbeat() {
    }

    // ================================================================ 上报入口

    /**
     * 上报一次心跳。每进程最多成功一次，任何异常都被吞掉。
     *
     * <p>参数先挂到 {@link #sPendingProcess} 等静态字段上，再尝试一次；这次失败不会关闸，
     * 后续由 {@link #scheduleRetry()} 排队的延迟任务或 {@link #retryIfPending()}
     * （既有 hook 回调里顺手调用）继续补试，最多 {@link #MAX_ATTEMPTS} 次。
     *
     * @param ctx         可用的 Context；传 {@code null} 时按 {@link #context()} 的三级回退自取
     * @param processName 进程名（{@code lpparam.packageName}）；为空时退回当前进程包名
     * @param hooks       本进程实际安装成功的 hook 数（真实计数，不做估算）
     * @param kind        {@link #KIND_SYSTEM} 或 {@link #KIND_APP}，见 {@link #kindOfCurrentProcess()}
     */
    static void report(Context ctx, String processName, int hooks, String kind) {
        try {
            if (sReported.get()) {
                return;  // 本进程已经成功报过
            }
            // 先记待上报参数：即使这一秒 Provider 拉不起来，后面补试也能用同一份参数
            sPendingProcess = resolveProcessName(processName);
            sPendingHooks = hooks;
            sPendingKind = kind == null ? KIND_APP : kind;
            attemptOnce(ctx);
        } catch (Throwable ignored) {
            // 模块绝不影响宿主：任何 Throwable 都在这里终止
        }
    }

    /**
     * “顺手补试”入口：供既有 hook 回调调用（见 {@link HookEntry}），每次调用只做
     * 几次 volatile 读，未成功且到了补试时机才真的去 insert 一次。
     *
     * <p>开销：{@code sReported} 与 {@code sPendingKind} 两次读 + 一次时间比较即返回；
     * 真正上报最多每 {@link #MIN_ATTEMPT_INTERVAL_MS} 毫秒一次，总次数封顶 {@link #MAX_ATTEMPTS}。
     */
    static void retryIfPending() {
        try {
            if (sReported.get() || sPendingKind == null) {
                return;  // 已成功，或本进程压根没有待上报请求：立即返回
            }
            attemptOnce(null);
        } catch (Throwable ignored) {
            // 补试失败绝不影响宿主
        }
    }

    /**
     * 真正尝试上报一次（唯一会碰 ContentResolver 的地方）。
     *
     * @param ctxHint 首次调用传入调用方给的 Context；补试传 {@code null}，走 {@link #context()}
     */
    private static void attemptOnce(Context ctxHint) {
        if (sReported.get()) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        final long last = sLastAttemptAt;
        if (last != 0L && now - last < MIN_ATTEMPT_INTERVAL_MS) {
            return;  // 限频：hook 回调可能很密，2s 内不重复尝试
        }
        if (sAttempts.get() >= MAX_ATTEMPTS) {
            return;  // 次数用完：不再尝试（放弃日志由 scheduleRetry 记）
        }
        if (!sInFlight.compareAndSet(false, true)) {
            return;  // 已有线程在报，本次跳过
        }
        final String name = sPendingProcess;
        final int hooks = sPendingHooks;
        final String kind = sPendingKind;
        try {
            sLastAttemptAt = now;
            sAttempts.incrementAndGet();
            final Context context = ctxHint != null ? ctxHint : context();
            if (context == null) {
                // 三级回退全失败：不放弃，挂起待上报参数，等延迟补试 / 后续 hook 回调
                logNoContextOnce();
                return;
            }
            final ContentResolver resolver = context.getContentResolver();
            if (resolver == null) {
                logNoContextOnce();
                return;
            }
            final ContentValues values = new ContentValues(5);
            values.put(COL_PROCESS, name);
            values.put(COL_HOOKS, Integer.valueOf(hooks));
            values.put(COL_KIND, kind == null ? KIND_APP : kind);
            values.put(COL_AT, Long.valueOf(System.currentTimeMillis()));
            values.put(COL_SDK, Integer.valueOf(Build.VERSION.SDK_INT));
            final Uri inserted = resolver.insert(Uri.parse(HEARTBEAT_URI), values);
            if (inserted == null) {
                // 主 App 进程未就绪 / Provider 拉不起来 / 旧版未实现 hookbeat：都算失败，稍后补试
                logInsertNullOnce();
                return;
            }
            // 只有真的拿到 Uri 才算成功：成功之后才关闸
            sReported.set(true);
            sPendingProcess = null;
            sPendingKind = null;
            log("heartbeat reported: " + name + " hooks=" + hooks);
        } catch (Throwable t) {
            logThrowableOnce(t);
        } finally {
            sInFlight.set(false);
            if (!sReported.get()) {
                // 失败（含异常 / 无 Context / insert 返回 null）：按退避排一次补试
                scheduleRetry();
            }
        }
    }

    /**
     * 排一次延迟补试（一次性任务，不建线程、不轮询）。
     *
     * <p>只允许一条待执行任务（{@link #sRetryScheduled}）；到 {@link #MAX_ATTEMPTS} 次后
     * 放弃并记一条日志。拿不到主线程 Looper（极早期）时不排队，退化为只靠
     * {@link #retryIfPending()} 的 hook 回调补试。
     */
    private static void scheduleRetry() {
        try {
            if (sReported.get()) {
                return;
            }
            final int attempts = sAttempts.get();
            if (attempts >= MAX_ATTEMPTS) {
                logAbandonedOnce();
                sPendingProcess = null;  // 彻底放弃：后续 retryIfPending 立即返回
                sPendingKind = null;
                return;
            }
            if (!sRetryScheduled.compareAndSet(false, true)) {
                return;  // 已经排过一条，等它跑
            }
            final Handler handler = retryHandler();
            if (handler == null) {
                sRetryScheduled.set(false);
                return;  // 没有主线程 Looper：只保留 hook 回调补试
            }
            final int index = Math.min(Math.max(attempts - 1, 0), RETRY_DELAYS_MS.length - 1);
            final long delay = RETRY_DELAYS_MS[index];
            final boolean posted = handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    sRetryScheduled.set(false);
                    attemptOnce(null);
                }
            }, delay);
            if (!posted) {
                sRetryScheduled.set(false);
            }
        } catch (Throwable ignored) {
            sRetryScheduled.set(false);
            // 排队失败只影响补试，不影响宿主
        }
    }

    /** 主线程 Handler，懒加载一次后复用；Looper 不可用时返回 {@code null}。 */
    private static Handler retryHandler() {
        Handler handler = sRetryHandler;
        if (handler != null) {
            return handler;
        }
        try {
            final Looper main = Looper.getMainLooper();
            if (main == null) {
                return null;
            }
            handler = new Handler(main);
            sRetryHandler = handler;
            return handler;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 本进程属于哪一类，供心跳的 {@code kind} 字段使用。
     *
     * <p>判据是<b>真实可得的 uid</b>：系统进程跑在系统 uid 段
     * （1000 = system_server / com.android.location.fused / settings / telecom 等），
     * 普通应用与隔离进程都是 10000 以上。
     *
     * @return {@link #KIND_SYSTEM} 或 {@link #KIND_APP}；判定失败按 app 上报（心跳照常发出）
     */
    static String kindOfCurrentProcess() {
        try {
            final int uid = Process.myUid();
            return uid >= Process.SYSTEM_UID && uid <= Process.LAST_APPLICATION_UID
                    ? KIND_SYSTEM
                    : KIND_APP;
        } catch (Throwable t) {
            return KIND_APP;
        }
    }

    // ================================================================ Context / 进程名

    /** 进程名兜底：调用方没给就取 {@code ActivityThread.currentPackageName()}，再不行用 {@code "unknown"}。 */
    private static String resolveProcessName(String processName) {
        if (processName != null && !processName.isEmpty()) {
            return processName;
        }
        try {
            final Class<?> activityThread = XposedHelpers.findClassIfExists(
                    "android.app.ActivityThread", HookHeartbeat.class.getClassLoader());
            if (activityThread != null) {
                final Object value =
                        XposedHelpers.callStaticMethod(activityThread, "currentPackageName");
                if (value instanceof String && !((String) value).isEmpty()) {
                    return (String) value;
                }
            }
        } catch (Throwable ignored) {
            // 取不到就叫 unknown，绝不放弃上报本身
        }
        return "unknown";
    }

    /**
     * 拿一个可用的 Context，三级回退（与 {@link TargetScope} 的取法一致）：
     * <ol>
     *   <li>{@code ActivityThread.currentApplication()}：app 进程 attach 之后可用；</li>
     *   <li>{@code AndroidAppHelper.currentApplication()}：Xposed 提供的等价入口；</li>
     *   <li>{@code ActivityThread.currentActivityThread().getSystemContext()}：
     *       {@code handleLoadPackage} 早期（Application 还没 attach）与 system_server 里都能拿到，
     *       ContentResolver 解析主 App 的 Provider 不需要 Application 级别的东西。</li>
     * </ol>
     * 三级都拿不到返回 {@code null}：调用方<b>不放弃</b>，挂起待上报参数并走补试路径。
     */
    private static Context context() {
        Context context = sContext;
        if (context != null) {
            return context;
        }
        context = callStaticContext("android.app.ActivityThread", "currentApplication");
        if (context == null) {
            context = callStaticContext("android.app.AndroidAppHelper", "currentApplication");
        }
        if (context == null) {
            try {
                final Class<?> activityThread = XposedHelpers.findClassIfExists(
                        "android.app.ActivityThread", HookHeartbeat.class.getClassLoader());
                if (activityThread != null) {
                    final Object thread =
                            XposedHelpers.callStaticMethod(activityThread, "currentActivityThread");
                    if (thread != null) {
                        final Object value = XposedHelpers.callMethod(thread, "getSystemContext");
                        if (value instanceof Context) {
                            context = (Context) value;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 拿不到就交给补试路径（下一轮再试三级回退）
            }
        }
        if (context != null) {
            sContext = context;
        }
        return context;
    }

    private static Context callStaticContext(String className, String methodName) {
        try {
            final Class<?> clazz = XposedHelpers.findClassIfExists(className,
                    HookHeartbeat.class.getClassLoader());
            if (clazz == null) {
                return null;
            }
            final Object value = XposedHelpers.callStaticMethod(clazz, methodName);
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ================================================================ 日志

    /** 拿不到 Context（含 resolver 为空）：每进程一条。 */
    private static void logNoContextOnce() {
        if (sLoggedNoContext.compareAndSet(false, true)) {
            log("heartbeat failed: no context");
        }
    }

    /** insert 返回 null（主 App 进程未就绪 / Provider 拉不起 / 旧版未实现）：每进程一条。 */
    private static void logInsertNullOnce() {
        if (sLoggedInsertNull.compareAndSet(false, true)) {
            log("heartbeat failed: insert returned null");
        }
    }

    /** 抛异常（DeadObjectException / SecurityException / IllegalStateException ...）：每进程一条。 */
    private static void logThrowableOnce(Throwable t) {
        if (!sLoggedThrowable.compareAndSet(false, true)) {
            return;
        }
        String name = null;
        try {
            name = t == null ? null : t.getClass().getSimpleName();
            if (name == null || name.isEmpty()) {
                name = t == null ? "unknown" : t.getClass().getName();
            }
        } catch (Throwable ignored) {
            name = "unknown";
        }
        log("heartbeat failed: " + name);
    }

    /** 次数用完：放弃（每进程一条，之后不再有任何补试动作）。 */
    private static void logAbandonedOnce() {
        if (sLoggedAbandoned.compareAndSet(false, true)) {
            log("heartbeat abandoned after " + MAX_ATTEMPTS + " attempts");
        }
    }

    private static void log(String message) {
        try {
            XposedBridge.log(TAG + message);
        } catch (Throwable ignored) {
            // 日志通道异常时不能影响宿主进程
        }
    }
}
