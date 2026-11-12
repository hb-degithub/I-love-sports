package com.vloc.motion.hidden;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Process;

import java.util.concurrent.atomic.AtomicBoolean;

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
 *   <li><b>每进程只上报一次</b>：{@link AtomicBoolean} 抢一次机会，抢不到直接返回；
 *       进程之间天然各有一份静态状态，所以“每个被注入的进程各报一次”是预期行为，
 *       主 App 按 {@code process} 分别记录。</li>
 *   <li><b>绝不影响宿主进程</b>：Provider 未启动 / 没有权限 / 任何 Throwable 全部静默吞掉
 *       （不重试、不排队、不创建线程、不抛给调用方）；拿不到 Context 就放弃并只记一条日志。</li>
 *   <li>只在“hook 真的装上了”之后调用（被作用域 skip 的进程不报，见 {@link HookEntry}）。</li>
 * </ul>
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

    /**
     * 每进程只上报一次的闸门。
     *
     * <p>{@code compareAndSet} 在“上一个 hook 安装线程 + 任何并发调用”之间唯一地选出一个上报者；
     * 抢到之后即使后续失败也不会重试（契约：每进程一次，不刷屏）。
     */
    private static final AtomicBoolean sReported = new AtomicBoolean(false);

    /** “拿不到 Context 就放弃”的日志也只记一条。 */
    private static final AtomicBoolean sLoggedNoContext = new AtomicBoolean(false);

    /** 进程内复用的 Context（同一个进程里 ContentResolver 不会变，省掉每进程一次的反射）。 */
    private static volatile Context sContext;

    private HookHeartbeat() {
    }

    // ================================================================ 上报入口

    /**
     * 上报一次心跳。每进程最多生效一次，任何异常都被吞掉。
     *
     * @param ctx         可用的 Context；传 {@code null} 时按 {@link #context()} 的三级回退自取
     * @param processName 进程名（{@code lpparam.packageName}）；为空时退回当前进程包名
     * @param hooks       本进程实际安装成功的 hook 数（真实计数，不做估算）
     * @param kind        {@link #KIND_SYSTEM} 或 {@link #KIND_APP}，见 {@link #kindOfCurrentProcess()}
     */
    static void report(Context ctx, String processName, int hooks, String kind) {
        if (!sReported.compareAndSet(false, true)) {
            return;  // 本进程已经报过（见类注释：每进程一次）
        }
        try {
            final String name = resolveProcessName(processName);
            final Context context = ctx != null ? ctx : context();
            if (context == null) {
                logNoContextOnce();
                return;  // 拿不到 Context：放弃，绝不阻塞 / 重试
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
                // 旧版主 App 尚未实现 hookbeat（insert 返回 null）：静默放弃，不重试
                return;
            }
            log("heartbeat reported: " + name + " hooks=" + hooks);
        } catch (Throwable ignored) {
            // Provider 未启动 / 无权限 / 跨进程异常：全部静默吞掉，绝不影响宿主进程
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
     * 三级都拿不到返回 {@code null}，由调用方放弃并记一条日志。
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
                // 拿不到就走“放弃 + 记日志”的分支
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

    private static void logNoContextOnce() {
        if (!sLoggedNoContext.compareAndSet(false, true)) {
            return;
        }
        log("heartbeat skipped: no context available");
    }

    private static void log(String message) {
        try {
            XposedBridge.log(TAG + message);
        } catch (Throwable ignored) {
            // 日志通道异常时不能影响宿主进程
        }
    }
}
