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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.vloc.motion.hidden.compat.Xp;

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
 *   gnss    (String)  可选：GNSS 注入结果（built:builder / built:ctor / built:unsafe /
 *                     force:mutated / skip:no-satellites / skip:null）。
 *                     <b>缺失 = 该进程还没确定结果</b>（老版本模块也不带该字段），
 *                     主 App 侧必须兼容为 null（{@code HookHeartbeatStore} 读不到就当 null）。
 *   ver     (int)     <b>模块代码版本号</b>（{@code BuildConfig.VERSION_CODE}）。
 *                     老版本模块不带该列 → 主 App 侧读成 0 = "未知"，不据此判定版本失配。
 *   vn      (String)  可选的模块版本名（{@code BuildConfig.VERSION_NAME}），仅用于界面显示。
 * </pre>
 *
 * <p><b>为什么心跳要带版本</b>：模块被 LSPosed 加载进 {@code system_server} 与各目标进程后，
 * 代码就一直留在那个进程里。用户更新 APK 后，<b>已运行进程里跑的还是旧代码</b>，
 * 必须重启（或重启 zygote）才会换成新模块。心跳里的 {@code ver} 是"那一刻在那个进程里
 * 真正跑着的模块版本"，主 App 拿它和自己的 {@code BuildConfig.VERSION_CODE} 比对，
 * 就能判断"模块已更新但系统没重启"（见 {@code ui/MainActivity#maybePromptModuleRestart}）。
 * 注意 {@code VERSION_CODE} 是 {@code public static final int} 常量，javac 会把值内联进
 * 调用点，所以老进程里套的还是老常量 —— 这正是我们要的证据。
 *
 * <p><b>为什么会有第二条心跳</b>（{@link #reportGnss(String)}）：{@code install()} 之后立刻
 * 发的第一次心跳发生在本进程第一次 satellite 回调之前，那时 {@code gnss} 还确定不了；等
 * {@code SystemHooks} 在 GNSS hook 里首次确定结果后再补一条带 {@code gnss} 的心跳
 * （同进程覆盖写、每进程最多一条），设置面板才能显示「卫星表注入」而不必翻 logcat。
 *
 * <p>约束：
 * <ul>
 *   <li><b>每进程成功一次即可</b>：{@code sReported} 只在 {@code insert} 返回非 null Uri
 *       之后才置位；失败（拿不到 Context / insert 返回 null / 抛异常）<b>不</b>置位，
 *       因此后续还有补报机会。进程之间天然各有一份静态状态，“每个被注入的进程各报一次”
 *       仍是预期行为，主 App 按 {@code process} 分别记录（同名进程重复上报是覆盖写，幂等）。
 *       <b>唯一例外</b>：GNSS 结果晚于第一条心跳时才允许再补一条（{@link #reportGnss(String)}，
 *       由 {@link #sGnssSynced} 保证每进程最多一次），同样是覆盖写。</li>
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

    /** 主 App 的心跳接收 URI（主 App 侧 EnvProvider 的 {@code hookbeat} 路径）。 */
    private static final String HEARTBEAT_URI = "content://com.vloc.motion.env/hookbeat";

    /** ContentValues 字段名，必须与主 App 侧逐字一致。 */
    private static final String COL_PROCESS = "process";
    private static final String COL_HOOKS = "hooks";
    private static final String COL_KIND = "kind";
    private static final String COL_AT = "at";
    private static final String COL_SDK = "sdk";
    /** 可选：GNSS 注入结果（{@code built:*} / {@code force:mutated} / {@code skip:*}）。 */
    private static final String COL_GNSS = "gnss";
    /** 模块代码版本号（{@code BuildConfig.VERSION_CODE}）：主 App 用它判断"跑的是不是旧模块"。 */
    private static final String COL_VER = "ver";
    /** 模块版本名（{@code BuildConfig.VERSION_NAME}）：只用于界面显示，缺失不影响判定。 */
    private static final String COL_VN = "vn";

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
     * Context 四级回退的来源名（日志里逐字出现，下一轮可直接 grep 定位是哪一步在炸）。
     *
     * <p>顺序即优先级：system 进程（system_server / {@code com.android.location.fused}）里
     * 第 1 步是<b>真机已验证可用</b>的那条（与 {@link HookEntry} 的 AppOps 自身豁免同一取法）；
     * app 进程里第 1 步通常也能拿到，第 2 步是 Xposed 的等价入口。
     */
    private static final String SOURCE_APP_GLOBALS = "AppGlobals.getInitialApplication";
    private static final String SOURCE_ANDROID_APP_HELPER = "AndroidAppHelper.currentApplication";
    private static final String SOURCE_CURRENT_APPLICATION = "ActivityThread.currentApplication";
    private static final String SOURCE_SYSTEM_CONTEXT =
            "ActivityThread.currentActivityThread().getSystemContext";

    /** 异常日志里最多带几层堆栈：够定位到调用点，又不会把 LSPosed 日志刷爆。 */
    private static final int STACK_FRAME_LIMIT = 5;

    /** 单条异常 message 最多保留多少字符（Android 的 NPE message 可能很长）。 */
    private static final int MESSAGE_LIMIT = 200;

    /** “各来源为什么都失败”的诊断整体上限，防止日志无限增长。 */
    private static final int DIAGNOSIS_LIMIT = 700;

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

    /** “本进程最终的 Context 来自哪条来源”只记一条（下一轮排查先看它）。 */
    private static final AtomicBoolean sLoggedContextSource = new AtomicBoolean(false);

    /** 某条来源失败、但后续来源顶上：只记一条，用来定位“哪一步在炸”。 */
    private static final AtomicBoolean sLoggedSourceFailure = new AtomicBoolean(false);

    /** 上次真正尝试上报的时刻（{@link SystemClock#elapsedRealtime()}，单调钟，不受改时间影响）。 */
    private static volatile long sLastAttemptAt;

    /** 待上报参数：首次失败后保留，供延迟补试与 hook 回调补试复用（进程内不会变）。 */
    private static volatile String sPendingProcess;
    private static volatile int sPendingHooks;
    private static volatile String sPendingKind;

    /**
     * 待上报的 GNSS 结果（{@code built:*} / {@code force:mutated} / {@code skip:*}）；
     * {@code null} = 还没确定 / 不是系统进程 → 本次心跳不带 {@code gnss} 字段（老契约兼容）。
     */
    private static volatile String sPendingGnss;

    /** 已成功上报那条心跳的参数：供 {@link #reportGnss(String)} 补发一条带 gnss 的心跳。 */
    private static volatile String sReportedProcess;
    private static volatile int sReportedHooks;
    private static volatile String sReportedKind;
    /** 已成功上报的那条心跳带的 gnss（去重用：同一个值不再补发，避免无意义的多一条心跳）。 */
    private static volatile String sReportedGnss;

    /**
     * GNSS 结果只补报一次（每进程）。
     *
     * <p>没有这个闸门时，GNSS 回调 1Hz 会让 {@link #reportGnss(String)} 每次都把
     * {@link #sReported} 放开再重发，等于每秒一次跨进程 insert。
     */
    private static final AtomicBoolean sGnssSynced = new AtomicBoolean(false);

    /** 进程内复用的 Context（同一个进程里 ContentResolver 不会变，省掉每进程一次的反射）。 */
    private static volatile Context sContext;

    /** 当前 {@link #sContext} 来自哪条来源（{@link #SOURCE_APP_GLOBALS} 等），供日志使用。 */
    private static volatile String sContextSource;

    /**
     * 四个来源全部失败时，各步的失败原因（含异常 message 与前 {@value #STACK_FRAME_LIMIT} 层堆栈）。
     * 只由 {@link #logNoContextOnce()} 一次性打进日志，之后清空。
     */
    private static volatile String sContextDiagnosis;

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
     * @param ctx         可用的 Context；传 {@code null} 时按 {@link #context()} 的**四级回退**自取
     * @param processName 进程名（{@code lpparam.packageName}）；为空时退回当前进程包名
     * @param hooks       本进程实际安装成功的 hook 数（真实计数，不做估算）
     * @param kind        {@link #KIND_SYSTEM} 或 {@link #KIND_APP}，见 {@link #kindOfCurrentProcess()}
     */
    static void report(Context ctx, String processName, int hooks, String kind) {
        // 兼容 4 参调用（HookEntry 不改）：gnss 结果从 SystemHooks 的静态字段取，
        // 那时通常还是 null（卫星回调还没来过），随后 reportGnss 会补一条带值的。
        report(ctx, processName, hooks, kind, SystemHooks.gnssState());
    }

    /**
     * 带 GNSS 结果的心跳（{@code gnss} 可空；空 = 本次心跳不带该字段，老 App 侧完全兼容）。
     *
     * @param gnss {@code built:builder} / {@code built:ctor} / {@code built:unsafe} /
     *             {@code force:mutated} / {@code skip:no-satellites} / {@code skip:null}
     */
    static void report(Context ctx, String processName, int hooks, String kind, String gnss) {
        try {
            if (sReported.get()) {
                return;  // 本进程已经成功报过
            }
            // 先记待上报参数：即使这一秒 Provider 拉不起来，后面补试也能用同一份参数
            sPendingProcess = resolveProcessName(processName);
            sPendingHooks = hooks;
            sPendingKind = kind == null ? KIND_APP : kind;
            if (gnss != null && !gnss.isEmpty()) {
                sPendingGnss = gnss;   // null 不覆盖已经补报上来的结果
            }
            attemptOnce(ctx);
        } catch (Throwable ignored) {
            // 模块绝不影响宿主：任何 Throwable 都在这里终止
        }
    }

    /**
     * GNSS 结果确定后补报一条系统心跳（<b>每进程最多一次</b>）。
     *
     * <p>调用时机：{@code SystemHooks} 在 GNSS hook 里首次确定结果（构造成功 / 就地改写 /
     * 跳过）之后。为什么需要它：{@code install()} 之后立刻发的第一条心跳早于第一次卫星回调，
     * 不带 {@code gnss}；不补这一条，设置面板的「卫星表注入」永远是「未知」。
     *
     * <p>实现上<b>复用既有管道</b>（限频 2s、退避 2s/5s/10s/30s、次数上限
     * {@value #MAX_ATTEMPTS}）：重新武装同一份参数后再走一次 {@link #attemptOnce(Context)}，
     * 所以不会新增线程、不会轮询，失败也不会把进程拖住。{@link #sGnssSynced} 保证只补一次。
     *
     * @param gnss 结果串；null / 空直接忽略
     */
    static void reportGnss(String gnss) {
        try {
            if (gnss == null || gnss.isEmpty()) {
                return;
            }
            sPendingGnss = gnss;   // 仍在补试中的心跳也带上最新结果
            if (!sGnssSynced.compareAndSet(false, true)) {
                return;   // 每进程只补发一次
            }
            if (!sReported.get()) {
                return;   // 主心跳还没成功：它自己会带上（上面已写 sPendingGnss）
            }
            if (gnss.equals(sReportedGnss)) {
                return;   // 已经报过这个值（例如首次心跳就带上了）：不重复发
            }
            final String name = sReportedProcess;
            final String kind = sReportedKind;
            if (name == null || name.isEmpty() || kind == null) {
                return;
            }
            sPendingProcess = name;
            sPendingHooks = sReportedHooks;
            sPendingKind = kind;
            sReported.set(false);   // 重新武装：下面这次 insert 成功后会再关上
            attemptOnce(null);
            if (!sReported.get()) {
                // 可能被 2s 限频挡下（主心跳刚发完）：交给既有退避链补发，不在这里忙等
                scheduleRetry();
            }
        } catch (Throwable ignored) {
            // 补报失败绝不影响宿主进程
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
        final String gnss = sPendingGnss;   // 可空：空就不带这个字段（老 App 侧兼容）
        final int ver = moduleVersionCode();
        final String vn = moduleVersionName();
        try {
            sLastAttemptAt = now;
            sAttempts.incrementAndGet();
            // Context 只从一个地方来：hint 可用就用 hint，否则走四级回退。两条路径都做了判空，
            // 任何一步的 NPE 都已在各自 try/catch 里被吃掉，这里拿到的要么是可用 Context，要么是 null。
            Context context = ctxHint;
            if (!hasResolver(context)) {
                context = context();
            }
            final ContentResolver resolver = resolverOf(context);
            if (context == null || resolver == null) {
                // 四个来源全部 null / 全部抛异常（或被缓存下来的 Context 已不可用）：
                // 统一记为“没有 Context”，绝不写成 NullPointerException；
                // 不置位成功闸门，由 finally 里的补试继续（次数 / 退避都不变）。
                sContext = null;  // 下一轮重新探测（AppGlobals 优先），别复用坏 Context
                logNoContextOnce();
                return;
            }
            sContext = context;  // 已验证有 ContentResolver：进程内复用，省掉后续反射
            final ContentValues values = new ContentValues(8);
            values.put(COL_PROCESS, name);
            values.put(COL_HOOKS, Integer.valueOf(hooks));
            values.put(COL_KIND, kind == null ? KIND_APP : kind);
            values.put(COL_AT, Long.valueOf(System.currentTimeMillis()));
            values.put(COL_SDK, Integer.valueOf(Build.VERSION.SDK_INT));
            if (gnss != null && !gnss.isEmpty()) {
                values.put(COL_GNSS, gnss);   // 可选字段：老 App 侧读不到就是 null
            }
            // 模块版本：固定上报（0 也写），老 App 侧多一列不影响解析；
            // 主 App 靠它判断"system_server 里跑的模块是不是更新前的旧代码"。
            values.put(COL_VER, Integer.valueOf(ver));
            if (vn != null && !vn.isEmpty()) {
                values.put(COL_VN, vn);   // 可选：只用于显示，缺了不影响版本判定
            }
            final Uri inserted = resolver.insert(Uri.parse(HEARTBEAT_URI), values);
            if (inserted == null) {
                // 主 App 进程未就绪 / Provider 拉不起来 / 旧版未实现 hookbeat：都算失败，稍后补试
                logInsertNullOnce();
                return;
            }
            // 只有真的拿到 Uri 才算成功：成功之后才关闸。
            // 记下这份参数：GNSS 结果晚到时 reportGnss 用它补发一条（同进程覆盖写，幂等）。
            sReportedProcess = name;
            sReportedHooks = hooks;
            sReportedKind = kind == null ? KIND_APP : kind;
            sReportedGnss = gnss;
            sReported.set(true);
            sPendingProcess = null;
            sPendingKind = null;
            log("heartbeat reported: " + name + " hooks=" + hooks + " ver=" + ver
                    + (gnss == null || gnss.isEmpty() ? "" : " gnss=" + gnss));
        } catch (Throwable t) {
            // 刚用过的 Context 可能已失效（例如 insert 内部 NPE）：丢掉缓存，下一轮重新探测；
            // 异常本身只在这里被“记录 + 吞掉”，绝不冒到宿主进程，也不再让 NPE 打满 5 次无信息日志。
            sContext = null;
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

    // ================================================================ 模块版本（心跳栏位）

    /**
     * 本进程里真正在跑的模块代码版本号（{@code BuildConfig.VERSION_CODE}）。
     *
     * <p>{@code VERSION_CODE} 是编译期常量，javac 会把数值直接内联进这里，
     * 因此<b>老 APK 的代码报的是老版本号</b>——主 App 更新后，system_server 里那份没重启的
     * 旧模块仍然报旧值，正是"模块已更新但系统未重启"的判据。
     *
     * @return 版本号；取不到（异常 / 类加载环境异常）返回 0 = "未知"，主 App 侧不会据此提示
     */
    static int moduleVersionCode() {
        try {
            return com.vloc.motion.BuildConfig.VERSION_CODE;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 本进程里模块的版本名（{@code BuildConfig.VERSION_NAME}），仅用于界面显示。
     *
     * @return 例如 {@code "0.46.5"}；取不到返回 {@code null}（调用方退回数字版本号）
     */
    static String moduleVersionName() {
        try {
            final String name = com.vloc.motion.BuildConfig.VERSION_NAME;
            return name == null ? null : name;
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================ Context / 进程名

    /** 进程名兜底：调用方没给就取 {@code ActivityThread.currentPackageName()}，再不行用 {@code "unknown"}。 */
    private static String resolveProcessName(String processName) {
        if (processName != null && !processName.isEmpty()) {
            return processName;
        }
        try {
            final Class<?> activityThread = Xp.findClassIfExists(
                    "android.app.ActivityThread", HookHeartbeat.class.getClassLoader());
            if (activityThread != null) {
                final Object value =
                        Xp.callStaticMethod(activityThread, "currentPackageName");
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
     * 拿一个可用的 Context，<b>四级回退</b>，每一步各自独立 try/catch、独立判 null：
     * <ol>
     *   <li>{@link #fromAppGlobals(StringBuilder) AppGlobals.getInitialApplication()}（反射）：
     *       system 进程首选。<b>真机已验证</b>——同进程 uid 1000 的
     *       {@code com.android.location.fused} 里，{@link HookEntry} 的 AppOps 自身豁免
     *       用同一条取法成功（{@code appops self-exempt: uid=10574}）；</li>
     *   <li>{@link #fromAndroidAppHelper(StringBuilder) AndroidAppHelper.currentApplication()}：
     *       目标 App 进程首选（Xposed 提供的等价入口）；</li>
     *   <li>{@link #fromCurrentApplication(StringBuilder) ActivityThread.currentApplication()}（反射）：
     *       app 进程 attach 之后可用；</li>
     *   <li>{@link #fromSystemContext(StringBuilder) currentActivityThread().getSystemContext()}（反射）：
     *       {@code handleLoadPackage} 早期也能拿到系统 Context；两步分别判 null，绝不链式调用。</li>
     * </ol>
     *
     * <p><b>为什么不会再冒出 NPE</b>：每一小步（含中间的反射调用、返回值、resolver 校验）都在
     * 自己的 {@code catch (Throwable)} 里，出错只写进诊断串并继续下一步；四个来源全部失败返回
     * {@code null}，调用方统一记 {@code heartbeat failed: no context}。因此任何来源的 NPE
     * 既到不了 {@link #attemptOnce(Context)} 的 catch，也不可能被误报成
     * {@code heartbeat failed: NullPointerException}。
     *
     * <p>只有真正的尝试路径才会走到这里（每进程最多 {@value #MAX_ATTEMPTS} 次），探测期间只分配
     * 一个小的诊断 {@link StringBuilder}，不影响 {@link #retryIfPending()} 的快路径。
     */
    private static Context context() {
        final Context cached = sContext;
        if (cached != null) {
            if (hasResolver(cached)) {
                return cached;  // 缓存且仍然可用
            }
            sContext = null;  // 缓存里的 Context 已不可用：丢掉重探，绝不把它的异常抛出去
            sContextSource = null;
        }
        final StringBuilder diagnosis = new StringBuilder(64);
        Context context = adopt(SOURCE_APP_GLOBALS, fromAppGlobals(diagnosis), diagnosis);
        if (context == null) {
            context = adopt(SOURCE_ANDROID_APP_HELPER, fromAndroidAppHelper(diagnosis), diagnosis);
        }
        if (context == null) {
            context = adopt(SOURCE_CURRENT_APPLICATION, fromCurrentApplication(diagnosis), diagnosis);
        }
        if (context == null) {
            context = adopt(SOURCE_SYSTEM_CONTEXT, fromSystemContext(diagnosis), diagnosis);
        }
        if (context == null) {
            // 四个来源都没给出可用 Context：交给 no-context 日志（每进程一条），不在这里打异常日志。
            // 已经记过那条日志之后就不再攒诊断串，后续补试不产生多余分配。
            if (!sLoggedNoContext.get()) {
                sContextDiagnosis = diagnosis.toString();
            }
            return null;
        }
        sContext = context;
        sContextDiagnosis = null;
        logSourceFailureOnce(diagnosis.length() == 0 ? null : diagnosis.toString());
        logContextSourceOnce(sContextSource);
        return context;
    }

    /**
     * 第 1 步：{@code AppGlobals.getInitialApplication()}（反射；system 进程首选，真机已验证可用）。
     *
     * <p>取法与 {@link HookEntry} 的 AppOps 自身豁免完全一致（{@code Class.forName} +
     * {@code getMethod} + {@code invoke}）；任何 Throwable（含目标方法自己的 NPE）只记录不抛出。
     *
     * @return 可用的 Application，或 {@code null}（这一步失败，交给下一步）
     */
    private static Context fromAppGlobals(StringBuilder diagnosis) {
        try {
            final Class<?> appGlobals = Class.forName("android.app.AppGlobals");
            if (appGlobals == null) {
                return null;
            }
            final Method getInitialApplication = appGlobals.getMethod("getInitialApplication");
            if (getInitialApplication == null) {
                return null;
            }
            final Object application = getInitialApplication.invoke(null);
            if (application == null) {
                return null;  // 系统还没初始化：正常的空，不是异常
            }
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable t) {
            noteDiagnosis(diagnosis, SOURCE_APP_GLOBALS + " " + inlineDetail(t));
            return null;
        }
    }

    /**
     * 第 2 步：{@code AndroidAppHelper.currentApplication()}（Xposed 的入口，目标 App 进程首选）。
     *
     * <p>该类由 Xposed 运行时注入，模块编译期不存在，只能反射；类不存在 / 方法不存在 /
     * 目标方法抛 NPE 全部只记录不抛出。
     *
     * @return 可用的 Application，或 {@code null}（这一步失败，交给下一步）
     */
    private static Context fromAndroidAppHelper(StringBuilder diagnosis) {
        try {
            final Class<?> helper = Class.forName("android.app.AndroidAppHelper");
            if (helper == null) {
                return null;
            }
            final Method currentApplication = helper.getMethod("currentApplication");
            if (currentApplication == null) {
                return null;
            }
            final Object application = currentApplication.invoke(null);
            if (application == null) {
                return null;
            }
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable t) {
            noteDiagnosis(diagnosis, SOURCE_ANDROID_APP_HELPER + " " + inlineDetail(t));
            return null;
        }
    }

    /**
     * 第 3 步：{@code ActivityThread.currentApplication()}（反射）。
     *
     * @return 可用的 Application，或 {@code null}（这一步失败，交给下一步）
     */
    private static Context fromCurrentApplication(StringBuilder diagnosis) {
        try {
            final Class<?> activityThread = Class.forName("android.app.ActivityThread");
            if (activityThread == null) {
                return null;
            }
            final Method currentApplication = activityThread.getMethod("currentApplication");
            if (currentApplication == null) {
                return null;
            }
            final Object application = currentApplication.invoke(null);
            if (application == null) {
                return null;
            }
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable t) {
            noteDiagnosis(diagnosis, SOURCE_CURRENT_APPLICATION + " " + inlineDetail(t));
            return null;
        }
    }

    /**
     * 第 4 步：{@code ActivityThread.currentActivityThread().getSystemContext()}（反射）。
     *
     * <p>两次反射拆成两行、<b>各自判 null</b>（不写
     * {@code currentActivityThread().getSystemContext()} 这种链式调用）：ActivityThread 还没
     * 建立时第一步就是 null，直接失败，不会在 null 上调用方法。
     *
     * @return 系统 Context，或 {@code null}（这一步失败，交给调用方）
     */
    private static Context fromSystemContext(StringBuilder diagnosis) {
        try {
            final Class<?> activityThread = Class.forName("android.app.ActivityThread");
            if (activityThread == null) {
                return null;
            }
            final Method currentActivityThread = activityThread.getMethod("currentActivityThread");
            if (currentActivityThread == null) {
                return null;
            }
            final Object thread = currentActivityThread.invoke(null);
            if (thread == null) {
                return null;  // 进程里还没有 ActivityThread：直接失败，不在 null 上继续调用
            }
            final Method getSystemContext = activityThread.getMethod("getSystemContext");
            if (getSystemContext == null) {
                return null;
            }
            final Object systemContext = getSystemContext.invoke(thread);
            if (systemContext == null) {
                return null;
            }
            return systemContext instanceof Context ? (Context) systemContext : null;
        } catch (Throwable t) {
            noteDiagnosis(diagnosis, SOURCE_SYSTEM_CONTEXT + " " + inlineDetail(t));
            return null;
        }
    }

    /**
     * 采纳一条来源的结果：先判 null，再要求 {@code getContentResolver()} 不为 null（抛异常也算不可用）。
     *
     * <p>这里是“拿到 Context 但 ContentResolver 不可用”的最后一道闸：这类 Context 会被丢弃并
     * 继续下一步，而不是拿去 insert 再炸出一个 NPE。
     *
     * @param source   来源名（日志用）
     * @param candidate 各 {@code fromXxx} 的返回值，可能为 {@code null}
     * @param diagnosis 诊断串，失败原因写在这里
     * @return 可用的 Context，或 {@code null}（继续下一步）
     */
    private static Context adopt(String source, Context candidate, StringBuilder diagnosis) {
        if (candidate == null) {
            return null;
        }
        try {
            if (candidate.getContentResolver() == null) {
                noteDiagnosis(diagnosis, source + " content resolver is null");
                return null;
            }
        } catch (Throwable t) {
            noteDiagnosis(diagnosis, source + ".getContentResolver " + inlineDetail(t));
            return null;
        }
        sContextSource = source;
        return candidate;
    }

    /** Context 是否真的可用：{@code getContentResolver()} 既不为 null 也不抛异常。 */
    private static boolean hasResolver(Context context) {
        if (context == null) {
            return false;
        }
        try {
            return context.getContentResolver() != null;
        } catch (Throwable t) {
            return false;  // 坏 Context：当成“没有”，由四级回退重探
        }
    }

    /** 取 ContentResolver；{@code null} 或抛异常都返回 {@code null}，异常绝不给调用方。 */
    private static ContentResolver resolverOf(Context context) {
        if (context == null) {
            return null;
        }
        try {
            return context.getContentResolver();
        } catch (Throwable t) {
            // 缓存的 Context 已失效：记下原因（若还没有），并让调用方按“无 Context”处理
            if (sContextDiagnosis == null) {
                sContextDiagnosis = "getContentResolver " + inlineDetail(t);
            }
            return null;
        }
    }

    // ================================================================ 日志

    /**
     * 拿不到 Context（四个来源全 null / 全抛异常，或 resolver 不可用）：每进程一条。
     *
     * <p>第一行固定 {@code heartbeat failed: no context}；若能拿到各来源的失败原因，第二行起
     * 逐个附上（含异常 message 与前 {@value #STACK_FRAME_LIMIT} 层堆栈）。这样“没有 Context”
     * 和“某一步抛了 NPE”在日志里是两件事，不会再混成一条 {@code NullPointerException}。
     */
    private static void logNoContextOnce() {
        if (!sLoggedNoContext.compareAndSet(false, true)) {
            return;
        }
        final String diagnosis = sContextDiagnosis;
        sContextDiagnosis = null;
        if (diagnosis == null || diagnosis.isEmpty()) {
            log("heartbeat failed: no context");
            return;
        }
        log("heartbeat failed: no context\n  sources: " + diagnosis);
    }

    /** insert 返回 null（主 App 进程未就绪 / Provider 拉不起 / 旧版未实现）：每进程一条。 */
    private static void logInsertNullOnce() {
        if (sLoggedInsertNull.compareAndSet(false, true)) {
            log("heartbeat failed: insert returned null");
        }
    }

    /**
     * 抛异常（DeadObjectException / SecurityException / NullPointerException ...）：每进程一条。
     *
     * <p>第一行仍是原有摘要 {@code heartbeat failed: <简单类名>}（老日志的匹配口径不变）；
     * 第二行起是 message 与前 {@value #STACK_FRAME_LIMIT} 层堆栈，信息量与
     * {@code android.util.Log.w(TAG, msg, throwable)} 等价。这里仍走 {@link Xp#log(String)}，
     * 因为真机证据（{@code [VLocHidden] heartbeat failed: ...}）就来自这条通道。
     */
    private static void logThrowableOnce(Throwable t) {
        if (!sLoggedThrowable.compareAndSet(false, true)) {
            return;
        }
        final Throwable real = unwrapReflective(t);
        final StringBuilder out = new StringBuilder(192);
        out.append("heartbeat failed: ").append(simpleNameOf(real));
        final String message = messageOf(real);
        if (message != null && !message.isEmpty()) {
            out.append("\n  message: ").append(clip(message));
        }
        final StackTraceElement[] frames = stackOf(real);
        if (frames != null) {
            final int limit = Math.min(frames.length, STACK_FRAME_LIMIT);
            for (int i = 0; i < limit; i++) {
                out.append("\n  at ").append(frames[i]);
            }
        }
        log(out.toString());
    }

    /** 本进程最终用的是哪条 Context 来源：每进程一条（{@code AppGlobals...} / {@code ActivityThread...}）。 */
    private static void logContextSourceOnce(String source) {
        if (source == null || !sLoggedContextSource.compareAndSet(false, true)) {
            return;
        }
        log("heartbeat context: " + source);
    }

    /**
     * 某条来源失败、但后续来源顶上：每进程一条。
     *
     * <p>正是这条日志回答“NPE 到底炸在哪一步”：例如 system 进程里
     * {@code ActivityThread.currentApplication} 抛 NPE、最后由
     * {@code AppGlobals.getInitialApplication} 顶上时，这条会把类名、message
     * 和前几层堆栈一起留下，同时心跳照常成功。
     */
    private static void logSourceFailureOnce(String diagnosis) {
        if (diagnosis == null || diagnosis.isEmpty()) {
            return;
        }
        if (!sLoggedSourceFailure.compareAndSet(false, true)) {
            return;
        }
        log("heartbeat source failed (fell back): " + diagnosis);
    }

    /** 次数用完：放弃（每进程一条，之后不再有任何补试动作）。 */
    private static void logAbandonedOnce() {
        if (sLoggedAbandoned.compareAndSet(false, true)) {
            log("heartbeat abandoned after " + MAX_ATTEMPTS + " attempts");
        }
    }

    /** 统一日志：{@code [VLocHidden] } 前缀由 {@link Xp} 后端统一加。 */
    private static void log(String message) {
        try {
            Xp.log(message);
        } catch (Throwable ignored) {
            // 日志通道异常时不能影响宿主进程
        }
    }

    // ================================================================ 异常格式化（日志用，绝不抛出）

    /**
     * 反射调用的目标方法抛出的异常被包在 {@link InvocationTargetException} 里：
     * 取里面的真身，日志里才会是 {@code NullPointerException} 而不是包装类名。
     */
    private static Throwable unwrapReflective(Throwable t) {
        Throwable current = t;
        for (int depth = 0; depth < 4 && current != null; depth++) {
            if (!(current instanceof InvocationTargetException)) {
                break;
            }
            final Throwable cause = current.getCause();
            if (cause == null) {
                break;
            }
            current = cause;
        }
        return current;
    }

    /** 简单类名；取不到退回全名，再不行 {@code "unknown"}（日志里永远不空白）。 */
    private static String simpleNameOf(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        try {
            final String simple = t.getClass().getSimpleName();
            if (simple != null && !simple.isEmpty()) {
                return simple;
            }
            final String full = t.getClass().getName();
            return full == null || full.isEmpty() ? "unknown" : full;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String messageOf(Throwable t) {
        if (t == null) {
            return null;
        }
        try {
            return t.getMessage();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static StackTraceElement[] stackOf(Throwable t) {
        if (t == null) {
            return null;
        }
        try {
            return t.getStackTrace();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 单行诊断：简单类名[: message] + 前 {@value #STACK_FRAME_LIMIT} 层堆栈，供各来源失败记录用。 */
    private static String inlineDetail(Throwable t) {
        final Throwable real = unwrapReflective(t);
        final StringBuilder out = new StringBuilder(96);
        out.append(simpleNameOf(real));
        final String message = messageOf(real);
        if (message != null && !message.isEmpty()) {
            out.append(": ").append(clip(message));
        }
        final StackTraceElement[] frames = stackOf(real);
        if (frames != null) {
            final int limit = Math.min(frames.length, STACK_FRAME_LIMIT);
            for (int i = 0; i < limit; i++) {
                out.append(i == 0 ? " @ " : "; ").append(frames[i]);
            }
        }
        return out.toString();
    }

    /** message 压成一行并封顶：Android 的 NPE message 可能很长，日志里只要够定位。 */
    private static String clip(String message) {
        if (message == null) {
            return "";
        }
        final String flat = message.indexOf('\n') < 0 ? message : message.replace('\n', ' ');
        return flat.length() <= MESSAGE_LIMIT ? flat : flat.substring(0, MESSAGE_LIMIT) + "...";
    }

    /** 往诊断串里追加一条，单条与整体都封顶，绝不无限增长（每进程最多 5 次尝试）。 */
    private static void noteDiagnosis(StringBuilder diagnosis, String text) {
        if (diagnosis == null || text == null || text.isEmpty()) {
            return;
        }
        if (diagnosis.length() >= DIAGNOSIS_LIMIT) {
            return;
        }
        if (diagnosis.length() > 0) {
            diagnosis.append("; ");
        }
        final int room = DIAGNOSIS_LIMIT - diagnosis.length();
        if (room <= 0) {
            return;
        }
        diagnosis.append(text.length() <= room ? text : text.substring(0, room));
    }
}
