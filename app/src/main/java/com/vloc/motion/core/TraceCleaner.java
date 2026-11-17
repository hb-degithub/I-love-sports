package com.vloc.motion.core;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.vloc.motion.location.RootShell;
import com.vloc.motion.service.SnapshotPrefs;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 会话结束后清理「注入痕迹」。
 *
 * <p><b>为什么需要它</b>：{@code LocationService.stopInternal()} 已经做了运行态收尾
 * （停 ticker、摘套接字、移除测试 Provider、回收 appop、释放 WakeLock、清会话单例、撤通知），
 * 但有几样东西是<b>落盘</b>的，不随会话状态消失：
 * <ul>
 *   <li>{@code shared_prefs/vloc_snapshot.xml}（{@link SnapshotPrefs} 写的 world-readable
 *       快照）里留着<b>最后一次坐标 / 卫星表</b>——任何能读该文件的东西都能直接看到位置；</li>
 *   <li>{@code vloc_prefs} 里的 {@code gnss_snapshot}（保存下来的卫星表）；</li>
 *   <li>{@code /data/local/tmp/vloc/state.bin}（Zygisk 模块每 100ms 复制过去的那份传感器状态，
 *       见 {@code VlocStateFile} 的链路说明）；</li>
 *   <li>logcat 里的 {@code VLoc*} 行（<b>可选</b>，只有 root 能清，默认关闭）。</li>
 * </ul>
 *
 * <p><b>设计要点</b>：
 * <ol>
 *   <li><b>逐项执行、单项失败不影响其它项</b>：每项都在自己的 {@code try/catch} 里，
 *       结果各自记录为 {@link Status#OK} / {@link Status#SKIPPED} / {@link Status#FAILED}；</li>
 *   <li><b>不阻塞调用线程</b>：{@link #cleanup} 把全部工作丢到后台线程
 *       （{@code "vloc-trace-cleanup"}，daemon），回调 post 回主线程；</li>
 *   <li><b>root 调用全部带超时</b>：{@link Options#rootTimeoutMs} 逐条传给
 *       {@link RootShell#su(String, long)}，没有 root 时降级为 skipped 而不是 failed；</li>
 *   <li><b>结构化结果</b>：{@link Result#summary()} 形如
 *       {@code "snapshot_xml=ok gnss_snapshot=ok overlay=ok notification=ok appop=ok
 *       logcat=skipped(disabled) tmp_files=skipped(no root)"}，
 *       供「使用记录」与「设置页状态」直接展示，也用于日志
 *       {@code Log.i("VLocCleanup", "cleanup: ...")}。</li>
 * </ol>
 *
 * <p><b>调用时机</b>：{@code LocationService.stopInternal()} 的收尾阶段（会话已经停下、
 * {@link SnapshotPrefs#stop()} 已调用之后）。{@link Options#protectActiveSession}（默认开）
 * 会在检测到<b>新会话已经启动</b>（{@link SnapshotPrefs#isRunning()}）时跳过两个 prefs 项：
 * 用户"停止后立刻重新开始"时，清理线程绝不能删掉新会话正在写的快照。
 *
 * <p><b>已知边界</b>：
 * <ul>
 *   <li>删 {@code .xml} 只是删文件，已打开的 {@link android.content.SharedPreferences} 实例
 *       仍在内存里缓存着旧值，下一次 {@code apply()} 会把文件重新写出来——所以必须在会话
 *       停止后调用（{@link Options#protectActiveSession} 就是为此）；</li>
 *   <li>{@link Options#clearLogcat} 会清空<b>整个</b> logcat 缓冲区（不只是本应用的日志），
 *       属于破坏性操作，默认关闭，只在用户明确选择后才执行；</li>
 *   <li>appop 一项只做<b>只读校验</b>，不重复回收：回收由
 *       {@code LocationService.revokeMockAppopIfGranted()} 在另一条线程做，这里等一次
 *       短重试后仍为 {@code allow} 才记 failed。</li>
 * </ul>
 */
public final class TraceCleaner {

    /** 清理日志统一 tag（与 {@code LocationService} 的 {@code VLocService} 区分开，便于抓取）。 */
    public static final String TAG = "VLocCleanup";

    // ---------------------------------------------------------------- 项名（结果里的 key）
    /** world-readable 快照文件（{@code shared_prefs/vloc_snapshot.xml}）。 */
    public static final String ITEM_SNAPSHOT_XML = "snapshot_xml";
    /** 保存的卫星表（{@code vloc_prefs/gnss_snapshot}）。 */
    public static final String ITEM_GNSS_SNAPSHOT = "gnss_snapshot";
    /** 悬浮窗是否已移除。 */
    public static final String ITEM_OVERLAY = "overlay";
    /** 前台服务 / 告警通知是否已撤销。 */
    public static final String ITEM_NOTIFICATION = "notification";
    /** {@code android:mock_location} appop 是否已回到 default（只读校验）。 */
    public static final String ITEM_APPOP = "appop";
    /** logcat 缓冲区（可选）。 */
    public static final String ITEM_LOGCAT = "logcat";
    /** {@code /data/local/tmp/vloc*} 残留。 */
    public static final String ITEM_TMP_FILES = "tmp_files";

    /** {@link SnapshotPrefs#PREFS_NAME} 对应的磁盘文件名（{@code shared_prefs/<name>.xml}）。 */
    private static final String SHARED_PREFS_DIR = "shared_prefs";

    /**
     * 与 {@code SnapshotPrefs.PREFS_NAME} 一起尝试删除的后缀。
     *
     * <p>{@code -wal}/{@code -shm} 是 WAL 模式的旁路文件（条目本身还是写在 xml 里，
     * 但那两个文件也可能含有未 checkpoint 的键值）；{@code .bak} 是部分 ROM / 旧版本
     * 备份写法；{@code -journal} 是旧实现留下的。
     */
    private static final String[] PREFS_FILE_SUFFIXES = {"", ".bak", "-wal", "-shm", "-journal"};

    /**
     * {@code /data/local/tmp/vloc*} 的清理脚本。
     *
     * <p>先 {@code ls} 判存在（不带匹配时 {@code ls} 会报错，所以整段重定向到 /dev/null），
     * 存在才 {@code rm -rf}：{@code /data/local/tmp/vloc} 是<b>目录</b>（Zygisk 模块的
     * {@code state.bin}），{@code -f} 删不掉目录，必须 {@code -r}。
     * 输出只有 {@code NONE} / {@code REMOVED} / {@code RMFAILED} 三种，便于解析。
     */
    private static final String TMP_CLEAN_SCRIPT =
            "if ls /data/local/tmp/vloc* >/dev/null 2>&1; then "
                    + "rm -rf /data/local/tmp/vloc* && echo REMOVED || echo RMFAILED; "
                    + "else echo NONE; fi";

    /** 上次清理结束后到现在的结果（供设置页 / 使用记录读取；null = 还没清理过）。 */
    private static volatile Result lastResult;

    /** 上次清理结束的墙钟时间（毫秒；0 = 还没清理过）。 */
    private static volatile long lastFinishedAtMs;

    private TraceCleaner() {
    }

    // ---------------------------------------------------------------- 结果类型

    /** 单项结果状态。 */
    public enum Status {
        /** 做完了（文件已删 / 通知已撤 / 校验通过 …）。 */
        OK("ok"),
        /** 本来就没有要清的东西，或按配置跳过（没有 root、功能关闭…）。 */
        SKIPPED("skipped"),
        /** 尝试清理但没成功（删除失败 / su 超时 / 校验仍是 allow …）。 */
        FAILED("failed");

        public final String label;

        Status(String label) {
            this.label = label;
        }
    }

    /** 一项清理的结果（不可变）。 */
    public static final class Item {
        /** 项名，取 {@code ITEM_*} 常量。 */
        public final String name;
        /** 结果状态。 */
        public final Status status;
        /** 补充说明（"deleted 1 file(s)" / "no root" / "still allow" …；可为空串）。 */
        public final String detail;

        Item(String name, Status status, String detail) {
            this.name = name;
            this.status = status;
            this.detail = detail == null ? "" : detail;
        }

        /** 单项成功。 */
        static Item ok(String name, String detail) {
            return new Item(name, Status.OK, detail);
        }

        /** 本来就没有可清的东西 / 按配置跳过。 */
        static Item skipped(String name, String detail) {
            return new Item(name, Status.SKIPPED, detail);
        }

        /** 尝试清理但没成功。 */
        static Item failed(String name, String detail) {
            return new Item(name, Status.FAILED, detail);
        }

        /** {@code ok} / {@code ok(deleted 1 file(s))} 这样的紧凑记法。 */
        public String token() {
            return detail.isEmpty() ? status.label : status.label + "(" + detail + ")";
        }

        @Override
        public String toString() {
            return name + "=" + token();
        }
    }

    /** 一次清理的完整结果。 */
    public static final class Result {
        /** 逐项结果（顺序与执行顺序一致；不可修改）。 */
        public final List<Item> items;
        /** 开始时刻（墙钟毫秒）。 */
        public final long startedAtMs;
        /** 总耗时（毫秒）。 */
        public final long durationMs;

        Result(List<Item> items, long startedAtMs, long durationMs) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.startedAtMs = startedAtMs;
            this.durationMs = durationMs;
        }

        /** 没有任何一项 failed。 */
        public boolean allOk() {
            for (Item item : items) {
                if (item.status == Status.FAILED) {
                    return false;
                }
            }
            return true;
        }

        /** 指定状态的项数。 */
        public int count(Status status) {
            int n = 0;
            for (Item item : items) {
                if (item.status == status) {
                    n++;
                }
            }
            return n;
        }

        /** 按项名取结果；不存在返回 null。 */
        public Item item(String name) {
            for (Item item : items) {
                if (item.name.equals(name)) {
                    return item;
                }
            }
            return null;
        }

        /** 一行紧凑汇总：{@code "snapshot_xml=ok gnss_snapshot=skipped(absent) ..."}。 */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            for (Item item : items) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(item.name).append('=').append(item.token());
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return summary() + " (" + durationMs + "ms)";
        }
    }

    /** 清理完成回调（在主线程触发；没有主 Looper 时在清理线程直接触发）。 */
    public interface Callback {
        void onFinished(Result result);
    }

    // ---------------------------------------------------------------- 选项

    /**
     * 清理选项。默认值是"最小痕迹清理"：只清本应用一定会留下的落盘痕迹，
     * 破坏性操作（清 logcat）默认关闭。
     */
    public static final class Options {
        /**
         * 是否清 logcat 缓冲区（默认 <b>false</b>）。
         *
         * <p>需要 root；会清掉<b>整个</b>缓冲区（含其它应用日志），属于破坏性操作，
         * 只在用户明确开启时执行。
         */
        public boolean clearLogcat;

        /** 是否删除 {@code /data/local/tmp/vloc*}（默认 true；没有 root 时自动 skipped）。 */
        public boolean removeTempFiles = true;

        /** 是否做 appop 只读校验（默认 true）。 */
        public boolean checkAppop = true;

        /**
         * 检测到<b>新会话已经启动</b>时跳过两个 prefs 项（默认 true）。
         *
         * <p>防止"停止 → 立刻重新开始"时，后台清理线程把新会话正在写的
         * {@code vloc_snapshot.xml} / {@code gnss_snapshot} 删掉。
         */
        public boolean protectActiveSession = true;

        /** 前台服务通知 id（&lt;=0 = 不撤）。 */
        public int notificationId;

        /** 告警通知 id（&lt;=0 = 不撤）。 */
        public int alertNotificationId;

        /**
         * 移除悬浮窗的动作（由 {@code LocationService} 注入 {@code hideOverlay()}）。
         *
         * <p>不能在这里直接 {@code new FloatingWindow(...)}：悬浮窗是服务的成员、
         * 必须复用同一实例；{@code null} 时这一项记 skipped。动作会被
         * post 到<b>主线程</b>执行（{@code WindowManager.removeView} 有线程亲和性）。
         */
        public Runnable hideOverlay;

        /** 单条 su 命令的超时（毫秒，最小 1000）。 */
        public long rootTimeoutMs = 4000L;

        /** 悬浮窗移除动作等待主线程的上限（毫秒）。 */
        public long mainThreadTimeoutMs = 1000L;
    }

    // ---------------------------------------------------------------- 对外入口

    /**
     * 异步清理：在后台线程逐项执行，完成后回调（主线程）。
     *
     * <p>立刻返回，调用方（{@code stopInternal} 在主线程）不会被文件 IO / su 拖住；
     * 清理线程是 daemon，即使 su 卡在超时上也不会阻止进程退出。
     *
     * @param context  任意 Context（内部取 application context）
     * @param options  选项；null = 全默认
     * @param callback 完成回调；null = 只记日志（结果仍可用 {@link #lastResult()} 读）
     */
    public static void cleanup(final Context context, final Options options,
                               final Callback callback) {
        final Context app = context == null ? null
                : (context.getApplicationContext() != null
                ? context.getApplicationContext() : context);
        Thread thread = new Thread(() -> {
            Result result = cleanupBlocking(app, options);
            if (callback != null) {
                postToMain(() -> callback.onFinished(result));
            }
        }, "vloc-trace-cleanup");
        thread.setDaemon(true);
        try {
            thread.start();
        } catch (Throwable t) {
            // 线程起不来（极端情况）：退化成同步执行，至少别把痕迹留着
            Log.w(TAG, "cleanup thread start failed, run inline: " + t);
            Result result = cleanupBlocking(app, options);
            if (callback != null) {
                callback.onFinished(result);
            }
        }
    }

    /**
     * 同步清理（<b>不要在主线程序里调用</b>：含文件 IO 与 su）。
     *
     * <p>给单元测试 / 设置页的"立即清理"按钮（须自己放到后台线程）用。
     *
     * @return 逐项结果（永不为 null）
     */
    public static Result cleanupBlocking(Context context, Options options) {
        final Options opts = options == null ? new Options() : options;
        final long started = System.currentTimeMillis();
        final List<Item> items = new ArrayList<>();

        // 顺序有讲究：先清"能读到位置"的 prefs，再处理界面（悬浮窗 / 通知），
        // 最后才是需要 root 的项（appop 校验、logcat、/data/local/tmp）。
        items.add(runItem(ITEM_SNAPSHOT_XML, () -> removeSnapshotPrefs(context, opts)));
        items.add(runItem(ITEM_GNSS_SNAPSHOT, () -> clearGnssSnapshot(context, opts)));
        items.add(runItem(ITEM_OVERLAY, () -> hideOverlay(opts)));
        items.add(runItem(ITEM_NOTIFICATION, () -> cancelNotifications(context, opts)));
        items.add(runItem(ITEM_APPOP, () -> checkMockAppop(context, opts)));
        items.add(runItem(ITEM_LOGCAT, () -> clearLogcat(opts)));
        items.add(runItem(ITEM_TMP_FILES, () -> removeTempFiles(opts)));

        final long finished = System.currentTimeMillis();
        Result result = new Result(items, finished, finished - started);
        lastResult = result;
        lastFinishedAtMs = finished;
        // 停止流程里唯一的一条清理日志：项=结果 逐项列出，事后可从 logcat 复盘
        Log.i(TAG, "cleanup: " + result.summary());
        for (Item item : result.items) {
            if (item.status == Status.FAILED) {
                Log.w(TAG, "cleanup item failed: " + item);
            }
        }
        return result;
    }

    /** 上次清理结果（null = 本次进程还没清理过）；设置页 / 使用记录用。 */
    public static Result lastResult() {
        return lastResult;
    }

    /** 上次清理结束时间（墙钟毫秒；0 = 还没清理过）。 */
    public static long lastFinishedAtMs() {
        return lastFinishedAtMs;
    }

    // ---------------------------------------------------------------- 单项实现

    /**
     * 1. 删除 world-readable 快照文件（{@code shared_prefs/vloc_snapshot.xml} 及旁路文件）。
     *
     * <p>路径不用 {@code context.getFilesDir().getParent()} 拼，直接用
     * {@link android.content.pm.ApplicationInfo#dataDir}：前者在部分机型上会因
     * {@code files} 是符号链接而解析错，后者就是 prefs 的父目录。
     */
    private static Item removeSnapshotPrefs(Context context, Options opts) {
        if (context == null) {
            return Item.failed(ITEM_SNAPSHOT_XML, "no context");
        }
        if (opts.protectActiveSession && SnapshotPrefs.INSTANCE.isRunning()) {
            // 新会话已经接上：它的 start() 会重新写这份快照，删了会打断模块的 prefs 通道
            return Item.skipped(ITEM_SNAPSHOT_XML, "active session");
        }
        final String dataDir = dataDirOf(context);
        if (dataDir == null || dataDir.isEmpty()) {
            return Item.failed(ITEM_SNAPSHOT_XML, "dataDir unavailable");
        }
        final File dir = new File(dataDir, SHARED_PREFS_DIR);
        final String base = SnapshotPrefs.PREFS_NAME + ".xml";
        int removed = 0;
        StringBuilder failed = new StringBuilder();
        for (String suffix : PREFS_FILE_SUFFIXES) {
            final File file = new File(dir, base + suffix);
            try {
                if (!file.exists()) {
                    continue;
                }
                if (file.delete()) {
                    removed++;
                } else {
                    if (failed.length() > 0) {
                        failed.append(',');
                    }
                    failed.append(file.getName());
                }
            } catch (Throwable t) {
                if (failed.length() > 0) {
                    failed.append(',');
                }
                failed.append(file.getName());
            }
        }
        if (failed.length() > 0) {
            return Item.failed(ITEM_SNAPSHOT_XML, "cannot delete: " + failed);
        }
        if (removed == 0) {
            return Item.skipped(ITEM_SNAPSHOT_XML, "absent");
        }
        return Item.ok(ITEM_SNAPSHOT_XML, "deleted " + removed + " file(s)");
    }

    /** 2. 清掉保存的卫星表（{@code vloc_prefs/gnss_snapshot}）。 */
    private static Item clearGnssSnapshot(Context context, Options opts) {
        if (context == null) {
            return Item.failed(ITEM_GNSS_SNAPSHOT, "no context");
        }
        if (opts.protectActiveSession && SnapshotPrefs.INSTANCE.isRunning()) {
            return Item.skipped(ITEM_GNSS_SNAPSHOT, "active session");
        }
        final boolean had = RouteStore.loadGnssSnapshot(context) != null;
        RouteStore.clearGnssSnapshot(context);
        return had
                ? Item.ok(ITEM_GNSS_SNAPSHOT, "removed")
                : Item.skipped(ITEM_GNSS_SNAPSHOT, "absent");
    }

    /**
     * 3. 移除悬浮窗。
     *
     * <p>复用服务自己的 {@code hideOverlay()}（内部 {@code FloatingWindow.hide()} 自带
     * {@code shown} 判空 + 幂等），这里不能再 {@code new FloatingWindow}：换成另一个实例
     * 反而删不掉真正挂在 WindowManager 上的那个 View。
     */
    private static Item hideOverlay(Options opts) {
        final Runnable hide = opts.hideOverlay;
        if (hide == null) {
            return Item.skipped(ITEM_OVERLAY, "not provided");
        }
        if (!runOnMainAndWait(hide, opts.mainThreadTimeoutMs)) {
            return Item.failed(ITEM_OVERLAY, "main thread timeout");
        }
        return Item.ok(ITEM_OVERLAY, "removed");
    }

    /** 4. 撤销前台服务通知 / 告警通知。 */
    private static Item cancelNotifications(Context context, Options opts) {
        final int[] ids = {opts.notificationId, opts.alertNotificationId};
        boolean any = false;
        for (int id : ids) {
            if (id > 0) {
                any = true;
                break;
            }
        }
        if (!any) {
            return Item.skipped(ITEM_NOTIFICATION, "disabled");
        }
        if (context == null) {
            return Item.failed(ITEM_NOTIFICATION, "no context");
        }
        NotificationManager nm;
        try {
            nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        } catch (Throwable t) {
            return Item.failed(ITEM_NOTIFICATION, "service unavailable: " + t);
        }
        if (nm == null) {
            return Item.failed(ITEM_NOTIFICATION, "service unavailable");
        }
        StringBuilder cancelled = new StringBuilder();
        for (int id : ids) {
            if (id <= 0) {
                continue;
            }
            try {
                nm.cancel(id);
                if (cancelled.length() > 0) {
                    cancelled.append(',');
                }
                cancelled.append(id);
            } catch (Throwable t) {
                return Item.failed(ITEM_NOTIFICATION, "cancel(" + id + ") failed: " + t);
            }
        }
        return Item.ok(ITEM_NOTIFICATION, "cancelled " + cancelled);
    }

    /**
     * 5. 校验 {@code android:mock_location} appop 是否已经回到 default（<b>只读</b>）。
     *
     * <p>回收动作在 {@code LocationService.revokeMockAppopIfGranted()} 的另一条线程里，
     * 与本项并发：第一次查到 {@code allow} 时等 500ms 再查一次（给回收线程留时间），
     * 仍为 {@code allow} 才记 failed——避免把"回收正在进行"误报成失败。
     */
    private static Item checkMockAppop(Context context, Options opts) {
        if (!opts.checkAppop) {
            return Item.skipped(ITEM_APPOP, "disabled");
        }
        if (context == null) {
            return Item.failed(ITEM_APPOP, "no context");
        }
        final String pkg = context.getPackageName();
        if (pkg == null || pkg.isEmpty()) {
            return Item.failed(ITEM_APPOP, "package unknown");
        }
        RootShell.Result first = RootShell.getMockAppop(pkg);
        if (isNoRoot(first)) {
            return Item.skipped(ITEM_APPOP, "no root");
        }
        if (first == null || !first.ok()) {
            return Item.failed(ITEM_APPOP, "query failed " + describe(first));
        }
        if (!RootShell.isMockAppopAllowed(first)) {
            return Item.ok(ITEM_APPOP, "default");
        }
        // 仍是 allow：可能是回收线程还没跑完，等一次短重试
        sleepQuietly(500L);
        RootShell.Result second = RootShell.getMockAppop(pkg);
        if (second == null || !second.ok()) {
            return Item.failed(ITEM_APPOP, "still allow, recheck failed");
        }
        if (RootShell.isMockAppopAllowed(second)) {
            return Item.failed(ITEM_APPOP, "still allow");
        }
        return Item.ok(ITEM_APPOP, "default");
    }

    /** 6. 清 logcat（可选；默认关闭）。 */
    private static Item clearLogcat(Options opts) {
        if (!opts.clearLogcat) {
            return Item.skipped(ITEM_LOGCAT, "disabled");
        }
        RootShell.Result result = su("logcat -c", opts);
        if (isNoRoot(result)) {
            return Item.skipped(ITEM_LOGCAT, "no root");
        }
        if (result != null && result.ok()) {
            return Item.ok(ITEM_LOGCAT, "cleared");
        }
        return Item.failed(ITEM_LOGCAT, describe(result));
    }

    /** 7. 删除 {@code /data/local/tmp/vloc*} 残留（root 判存在再删）。 */
    private static Item removeTempFiles(Options opts) {
        if (!opts.removeTempFiles) {
            return Item.skipped(ITEM_TMP_FILES, "disabled");
        }
        RootShell.Result result = su(TMP_CLEAN_SCRIPT, opts);
        if (isNoRoot(result)) {
            return Item.skipped(ITEM_TMP_FILES, "no root");
        }
        if (result == null || !result.ok()) {
            return Item.failed(ITEM_TMP_FILES, describe(result));
        }
        final String out = result.stdout == null ? "" : result.stdout.trim();
        if (out.contains("REMOVED")) {
            return Item.ok(ITEM_TMP_FILES, "removed");
        }
        if (out.contains("NONE")) {
            return Item.skipped(ITEM_TMP_FILES, "absent");
        }
        if (out.contains("RMFAILED")) {
            return Item.failed(ITEM_TMP_FILES, "rm failed");
        }
        return Item.failed(ITEM_TMP_FILES, "unexpected output: " + abbreviate(out));
    }

    // ---------------------------------------------------------------- 小工具

    /** 一项的执行体（允许抛异常：{@link #runItem} 统一兜底）。 */
    private interface Step {
        Item run() throws Exception;
    }

    /** 执行一项：任何 Throwable 都收敛成 failed，绝不外泄打断其它项。 */
    private static Item runItem(String name, Step step) {
        try {
            Item item = step.run();
            return item != null ? item : Item.failed(name, "no result");
        } catch (Throwable t) {
            return Item.failed(name, "exception: " + t);
        }
    }

    /** 执行一条 root 命令（带本选项里的超时）。 */
    private static RootShell.Result su(String command, Options opts) {
        try {
            return RootShell.su(command, Math.max(1000L, opts.rootTimeoutMs));
        } catch (Throwable t) {
            Log.w(TAG, "su threw: " + t);
            return null;
        }
    }

    /**
     * 是否"这台设备没有 root"（而不是"root 命令失败"）。
     *
     * <p>{@link RootShell#su(String, long)} 在找不到任何 {@code su} 二进制时返回
     * {@code exitCode=-1 / timedOut=false}：这种情况记 skipped 更准确，用户不该看到
     * 一堆"清理失败"。超时（timedOut=true）另算——那是真的没做完。
     */
    private static boolean isNoRoot(RootShell.Result result) {
        return result != null && !result.ok() && !result.timedOut && result.exitCode == -1;
    }

    /** 命令结果的一行描述（给 failed 的 detail 用）。 */
    private static String describe(RootShell.Result result) {
        if (result == null) {
            return "su unavailable";
        }
        if (result.timedOut) {
            return "su timeout";
        }
        String text = result.combined();
        return "exit=" + result.exitCode + (text.isEmpty() ? "" : " " + abbreviate(text));
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String one = text.replace('\n', ' ').trim();
        return one.length() <= 60 ? one : one.substring(0, 60) + "…";
    }

    /** ApplicationInfo#dataDir（API 24+）；取不到时回退到 {@code files} 的父目录。 */
    private static String dataDirOf(Context context) {
        try {
            if (context.getApplicationInfo() != null) {
                String dir = context.getApplicationInfo().dataDir;
                if (dir != null && !dir.isEmpty()) {
                    return dir;
                }
            }
        } catch (Throwable ignored) {
            // 落到下面的回退
        }
        try {
            File files = context.getFilesDir();
            if (files != null && files.getParentFile() != null) {
                return files.getParentFile().getAbsolutePath();
            }
        } catch (Throwable ignored) {
            // 没有可用的目录
        }
        return null;
    }

    /**
     * 在主线程执行并等待完成（最多 {@code timeoutMs}）。
     *
     * <p>用于必须主线程的操作（悬浮窗 {@code WindowManager.removeView}）。
     * 当前就在主线程 / 没有主 Looper 时直接同步执行。
     *
     * @return true = 执行完成；false = 等超时（动作可能稍后才跑）
     */
    private static boolean runOnMainAndWait(final Runnable action, long timeoutMs) {
        if (action == null) {
            return true;
        }
        Looper main;
        try {
            main = Looper.getMainLooper();
        } catch (Throwable t) {
            main = null;
        }
        if (main == null || Looper.myLooper() == main) {
            action.run();
            return true;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            new Handler(main).post(() -> {
                try {
                    action.run();
                } finally {
                    latch.countDown();
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "post to main failed, run inline: " + t);
            action.run();
            return true;
        }
        try {
            return latch.await(Math.max(100L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 回调投递到主线程（没有主 Looper 时原地执行）。 */
    private static void postToMain(final Runnable action) {
        if (action == null) {
            return;
        }
        Looper main;
        try {
            main = Looper.getMainLooper();
        } catch (Throwable t) {
            main = null;
        }
        if (main == null) {
            action.run();
            return;
        }
        try {
            new Handler(main).post(action);
        } catch (Throwable t) {
            action.run();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
