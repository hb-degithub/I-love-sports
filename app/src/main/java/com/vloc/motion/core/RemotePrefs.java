package com.vloc.motion.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 应用侧 <b>libxposed remote preferences</b> 通道：把快照写进框架（LSPosed/新 API）
 * 维护的远程偏好处，供模块在新 API 后端（{@code io.github.libxposed.api.XposedInterface
 * #getRemotePreferences}）里读取。
 *
 * <p><b>为什么需要它</b>：{@link com.vloc.motion.service.SnapshotPrefs} 走的
 * {@code MODE_WORLD_READABLE} + {@code vloc_snapshot} 只有 <b>旧</b> LSPosed（New
 * XSharedPreferences）读得到；新 API 后端下模块读的是
 * {@code module.getRemotePreferences(group)}，那份数据<b>只能</b>由应用侧通过
 * {@link XposedService#getRemotePreferences(String)} 写入。两条通道并存：
 * world-readable 保留给旧后端，本类供新后端使用。
 *
 * <p><b>数据契约</b>（与模块侧 {@code hidden/compat/NewBackend.getPrefs(name)} →
 * {@code hidden/PrefsClient} 逐字一致）：
 * <pre>
 *   group          vloc_snapshot                        ← {@link #GROUP}
 *   loc            {"active":bool,...}                  ← SnapshotPrefs.KEY_LOC
 *   gnss           [{"svid":..,...}]                    ← SnapshotPrefs.KEY_GNSS
 *   targets        {"enabled":bool,"packages":[..]}     ← SnapshotPrefs.KEY_TARGETS
 *   socket         vloc-&lt;16 hex&gt;                         ← SnapshotPrefs.KEY_SOCKET
 * </pre>
 * 值全部是字符串，key 由调用方（{@link com.vloc.motion.service.SnapshotPrefs}）传入，
 * 本类不重复定义，避免两侧常量漂移。
 *
 * <p><b>绑定流程</b>（应用侧是"服务客户端"）：
 * <ol>
 *   <li>{@link #start(Context)} 调用一次
 *       {@link XposedServiceHelper#registerListener}（幂等，框架要求进程内只注册一次）；</li>
 *   <li>框架通过 {@code content://<pkg>.XposedService} 把 binder 送进来，回调
 *       {@code onServiceBind(service)}；</li>
 *   <li>绑定回调里<b>只</b>缓存 service，能力位探测与首次取 prefs 都排到后台线程
 *       （见下"线程"），主线程零 IPC；</li>
 *   <li>能力位 {@code service.getFrameworkProperties() & XposedService.PROP_CAP_REMOTE}
 *       为 0 → 通道判死，只记一条日志；</li>
 *   <li>{@code onServiceDied} 清空 service / 能力位 / 缓存的 prefs。</li>
 * </ol>
 *
 * <p><b>每次写入前重新取 prefs</b>：{@link XposedService#getRemotePreferences(String)}
 * 返回的是<b>调用时刻</b>的快照（内部一次 {@code requestRemotePreferences} binder 调用，
 * 拿到的是当时那份 map 的拷贝），复用绑定时刻的实例会一直读写陈旧内容。所以
 * {@link #write} 每次都重新取一次；绑定时刻那次只用于"通道是否可用"的自检。
 *
 * <p><b>线程</b>（为什么不直接写）：
 * {@code javap} 显示 {@code getRemotePreferences} 是 {@code synchronized} 方法且内部走
 * binder，{@code commit()} 也是同步 binder。而快照写入点在
 * {@link com.vloc.motion.service.SnapshotPrefs#onTick()} 里，跑在<b>主线程</b>的 250ms
 * 定时器上——在主线程里做 binder 会掉帧。因此：
 * <ul>
 *   <li>所有真正的读写都在本类的<b>单线程</b>后台执行器里按提交顺序（FIFO）执行；</li>
 *   <li>{@link #put} / {@link #remove} / {@link #write} <b>不阻塞</b>：只做几次 volatile
 *       读 + 一次任务提交就返回。</li>
 * </ul>
 * 写入用的是 {@code Editor#apply()}：libxposed 的实现把 binder 调用丢到它自己的静态单线程
 * 池（{@code RemotePreferences.apply()} → {@code EXECUTOR.execute(doCommit)}），
 * 调用线程不会等 IPC；{@code commit()} 才是同步 binder。
 *
 * <p><b>降级契约</b>（任何一条都"绝不抛给调用方"）：
 * <ul>
 *   <li>未绑定 / 能力位缺失 / 提交任务被拒 → 返回 false，只记<b>一条</b>日志（每进程一次）；</li>
 *   <li>取 prefs / 写入抛 Throwable → 返回 false，只记<b>一条</b>日志（每进程一次）；</li>
 *   <li>日志不包含任何 key 值（loc 里有坐标），只带异常类名。</li>
 * </ul>
 * 主流程（定位注入）完全依赖 world-readable 那条既有通道，本类全程可选。
 */
public final class RemotePrefs {

    /**
     * 远程偏好组名，必须与模块侧
     * {@code hidden/compat/NewBackend.getPrefs(name)} 收到的 name 逐字一致 —— 也就是
     * {@code hidden/PrefsClient.PREFS_NAME} = {@code "vloc_snapshot"}。
     */
    public static final String GROUP = "vloc_snapshot";

    private static final String TAG = "VLocRemotePrefs";

    /** {@link #start(Context)} 幂等标记。 */
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    /** "通道不可用"日志：每进程一条。 */
    private static final AtomicBoolean LOGGED_UNAVAILABLE = new AtomicBoolean();

    /** "写入失败"日志：每进程一条（不按 key 分别记，避免刷屏）。 */
    private static final AtomicBoolean LOGGED_WRITE_FAILURE = new AtomicBoolean();

    /** "通道就绪"日志：每进程一条，现场据此确认新后端通道已生效。 */
    private static final AtomicBoolean LOGGED_READY = new AtomicBoolean();

    /** 单线程执行器：所有远程 prefs 的 IPC 都按提交顺序在这里跑，保证写序与调用序一致。 */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "vloc-remote-prefs");
        thread.setDaemon(true);   // 不能因为这条通道拖住进程退出
        return thread;
    });

    /** 当前绑定的框架服务；null = 未连接（新后端读不到任何东西）。 */
    private static volatile XposedService sService;

    /** 框架能力位 {@code PROP_CAP_REMOTE} 是否置位（后台探测后写入）。 */
    private static volatile boolean sCapRemote;

    /**
     * 最近一次取到的远程 prefs（绑定自检 / 每次写入后刷新）。
     *
     * <p><b>不</b>用它做写入：它是"取那一刻"的快照，写入前必须重新
     * {@code service.getRemotePreferences(GROUP)}，见类注释。
     */
    private static volatile SharedPreferences sCachedPrefs;

    private RemotePrefs() {
    }

    // ---------------------------------------------------------------- 生命周期

    /**
     * 注册框架服务监听（进程启动时调一次即可，重复调用安全）。
     *
     * <p>只向 {@link XposedServiceHelper} 登记回调，不做任何 IPC、不排队任务；
     * 可以在 {@code Application#onCreate()} 里直接调用，不会阻塞启动。
     *
     * <p>注册本身失败（框架侧实现异常 / 类缺失）只记一条日志，应用其余功能照常。
     *
     * @param context 任意 Context（当前实现只用于日志上下文，可为 null）
     */
    public static void start(Context context) {
        if (!STARTED.compareAndSet(false, true)) {
            return;   // 幂等：框架要求进程内只注册一次
        }
        try {
            XposedServiceHelper.registerListener(new Listener());
        } catch (Throwable t) {
            // 旧 LSPosed / 纯旧 API 环境本来就不会送 binder：记一条即可，不重试、不抛
            noteUnavailable(t);
        }
    }

    /**
     * 当前是否"已绑定且框架声明支持远程偏好"。
     *
     * <p>只读 volatile 字段，零 IPC，可在主线程调用。能力位由后台探测写入，
     * 所以绑定后极短时间内可能仍返回 false（此时写入会被丢弃，下一拍自然补上）。
     *
     * @return true = 可以尝试写入
     */
    public static boolean available() {
        try {
            return sService != null && sCapRemote;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 是否已经绑定到框架服务（不判断能力位，供诊断展示）。
     *
     * @return true = 收到了 {@code onServiceBind}
     */
    public static boolean isBound() {
        try {
            return sService != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 框架送来的 binder 回调：绑定 / 失联。所有 IPC 都转给执行器。 */
    private static final class Listener implements XposedServiceHelper.OnServiceListener {

        @Override
        public void onServiceBind(XposedService service) {
            try {
                if (service == null) {
                    return;
                }
                // 先记下 service（available() 立刻能反映"已连接"），能力位与首次取 prefs
                // 都放到后台线程：绑定是在框架的 binder 线程上回调的，但 registerListener
                // 也可能在主线程同步回调，不能在这里做 IPC。
                sService = service;
                sCapRemote = false;
                submit("bind", () -> probe(service));
            } catch (Throwable t) {
                // 绑定回调异常绝不能冒泡回框架
                noteUnavailable(t);
            }
        }

        @Override
        public void onServiceDied(XposedService service) {
            try {
                if (service != null && sService != service) {
                    return;   // 别的框架实例失联，不影响当前这个
                }
                sService = null;
                sCapRemote = false;
                sCachedPrefs = null;
            } catch (Throwable ignored) {
                // 清理失败也不能抛
            }
        }
    }

    /**
     * 后台探测：读能力位，并在支持时取一次 prefs 自检（不写入）。
     *
     * <p>只在执行器线程调用。任何异常都只把通道判为不可用，不影响主流程。
     */
    private static void probe(XposedService service) {
        try {
            final long properties = service.getFrameworkProperties();
            final boolean cap = (properties & XposedService.PROP_CAP_REMOTE) != 0L;
            sCapRemote = cap;
            if (!cap) {
                noteUnavailable("PROP_CAP_REMOTE not set");
                return;
            }
            // 绑定时刻的这一次只是自检：确认组可读。真正的写入每次都会重新取。
            sCachedPrefs = service.getRemotePreferences(GROUP);
            fireOnBound();   // 通知写方：此前因未绑定而没送达的 key 可以重发了
            if (LOGGED_READY.compareAndSet(false, true)) {
                VLog.i(TAG, "remote prefs ready: " + GROUP + " (libxposed service)");
            }
        } catch (Throwable t) {
            sCapRemote = false;
            noteUnavailable(t);
        }
    }

    // ---------------------------------------------------------------- 写入

    /**
     * 写一个字符串键（不阻塞调用线程）。
     *
     * @param key   与模块侧约定的 key（{@code loc} / {@code gnss} / {@code targets} / {@code socket}）
     * @param value 字符串值；null 等价于 {@link #remove(String)}
     * @return true = 已提交到后台写入队列；false = 通道不可用 / 参数非法 / 队列拒绝
     */
    /** 绑定完成（能力位已确认可用）后的回调：用于把此前未送达的 key 全量重发。 */
    private static volatile Runnable sOnBound;

    /** 注册"已可用"回调（幂等覆盖）。回调在该工具的后台线程上执行，调用方需自行切主线程。 */
    public static void setOnBound(Runnable callback) {
        sOnBound = callback;
    }

    private static void fireOnBound() {
        final Runnable cb = sOnBound;
        if (cb != null) {
            try {
                cb.run();
            } catch (Throwable t) {
                VLog.w(TAG, "onBound callback failed: " + t);
            }
        }
    }

    public static boolean put(String key, String value) {
        if (value == null) {
            return remove(key);
        }
        return write(java.util.Collections.singletonMap(key, value), null);
    }

    /**
     * 删除一个键（对应 {@code SharedPreferences.Editor#remove}，不阻塞调用线程）。
     *
     * @param key 要删除的 key；null / 空 → 直接 false
     * @return true = 已提交；false = 通道不可用 / 参数非法 / 队列拒绝
     */
    public static boolean remove(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        return write(null, java.util.Collections.singletonList(key));
    }

    /**
     * 批量写 + 删（一次 binder 提交完成，例如会话结束时的
     * {@code loc=inactive, 删 gnss, 删 socket}）。不阻塞调用线程。
     *
     * @param puts    要写入的键值；null 或空表示不写
     * @param removes 要删除的键；null 或空表示不删
     * @return true = 已提交；false = 通道不可用 / 无有效操作 / 队列拒绝
     */
    public static boolean write(Map<String, String> puts, Collection<String> removes) {
        try {
            final boolean hasPut = puts != null && !puts.isEmpty();
            final boolean hasRemove = removes != null && !removes.isEmpty();
            if (!hasPut && !hasRemove) {
                return false;
            }
            if (!guard()) {
                return false;
            }
            // 拷贝快照：调用方之后改自己的 map 也不影响已排队的任务
            final Map<String, String> putCopy = hasPut
                    ? new java.util.LinkedHashMap<>(puts) : java.util.Collections.emptyMap();
            final java.util.List<String> removeCopy = hasRemove
                    ? new java.util.ArrayList<>(removes) : java.util.Collections.emptyList();
            return submit("write", () -> doWrite(putCopy, removeCopy));
        } catch (Throwable t) {
            noteWriteFailure(t);
            return false;
        }
    }

    /** 通道可用性检查（不阻塞）：不可用时记一条日志。 */
    private static boolean guard() {
        if (sService == null) {
            noteUnavailable("service not bound");
            return false;
        }
        if (!sCapRemote) {
            noteUnavailable("PROP_CAP_REMOTE not set");
            return false;
        }
        return true;
    }

    /**
     * 真正的写入（只在执行器线程跑）。
     *
     * <p><b>每次重新取 prefs</b>：绑定时刻的实例是快照，复用会一直写陈旧内容。
     * 用 {@code apply()} 提交，libxposed 内部把 binder 调用放到它自己的单线程池，
     * 这里不会阻塞。
     */
    private static boolean doWrite(Map<String, String> puts, Collection<String> removes) {
        try {
            final XposedService service = sService;
            if (service == null || !sCapRemote) {
                noteUnavailable("service gone");
                return false;
            }
            final SharedPreferences prefs = service.getRemotePreferences(GROUP);
            if (prefs == null) {
                noteWriteFailure(null);
                return false;
            }
            sCachedPrefs = prefs;
            final SharedPreferences.Editor editor = prefs.edit();
            if (editor == null) {
                noteWriteFailure(null);
                return false;
            }
            for (Map.Entry<String, String> entry : puts.entrySet()) {
                editor.putString(entry.getKey(), entry.getValue());
            }
            for (String key : removes) {
                editor.remove(key);
            }
            editor.apply();
            return true;
        } catch (Throwable t) {
            // HOME 里最常见的是 binder 死亡（RemoteException 被包成 RuntimeException）
            noteWriteFailure(t);
            return false;
        }
    }

    /**
     * 提交一个任务到后台执行器。
     *
     * @return true = 已入队；false = 线程池拒绝（进程正在退出等）
     */
    private static boolean submit(String name, Runnable task) {
        try {
            EXECUTOR.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    noteWriteFailure(t);   // 兜底：任务内部漏网的异常也不能冒到线程外
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            noteUnavailable(e);
            return false;
        } catch (Throwable t) {
            noteUnavailable(t);
            return false;
        }
    }

    // ---------------------------------------------------------------- 日志（每进程一次）

    /** "通道不可用"：每进程一条，之后所有写入静默返回 false。 */
    private static void noteUnavailable(Throwable t) {
        noteUnavailable(t == null ? "unknown" : t.getClass().getName());
    }

    /** @param reason 只放原因（类名 / 字段名），<b>绝不</b>放 key 值（loc 里有坐标）。 */
    private static void noteUnavailable(String reason) {
        try {
            if (LOGGED_UNAVAILABLE.compareAndSet(false, true)) {
                VLog.w(TAG, "remote prefs unavailable: " + reason + "，fallback to world-readable prefs");
            }
        } catch (Throwable ignored) {
            // 日志绝不抛出
        }
    }

    /** "写入失败"：每进程一条（首次失败时记），后续静默。 */
    private static void noteWriteFailure(Throwable t) {
        try {
            if (LOGGED_WRITE_FAILURE.compareAndSet(false, true)) {
                final String kind = t == null ? "null prefs/editor" : t.getClass().getName();
                VLog.w(TAG, "remote prefs write failed: " + kind);
            }
        } catch (Throwable ignored) {
            // 日志绝不抛出
        }
    }
}
