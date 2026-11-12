package com.vloc.motion.service;

import android.content.Context;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.util.Log;

import com.vloc.motion.core.RouteStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 快照套接字服务：把定位 / 卫星 / 作用域快照经 <b>Unix 抽象命名空间套接字</b>发给 LSPosed 模块。
 *
 * <p>为什么需要它：模块在 {@code system_server}（uid 1000）里读
 * {@code content://com.vloc.motion.env/location} 时，Android 17 会抛
 * {@code java.lang.SecurityException}（logcat 另有
 * {@code Isolated process not allowed to call getContentProvider}），
 * 快照送不进去，系统级注入自然失效。抽象套接字不经过 ContentProvider 的
 * {@code exported} / 权限模型，是这条链路的独立通道；{@link EnvProvider} 保持原样，
 * 仍作为备用通道存在。
 *
 * <p><b>协议</b>（极简、单次请求-应答、UTF-8、以 {@code \n} 结尾）：
 * <pre>
 *   客户端 → "LOC\n"      服务端 → 一行 JSON（{@link LocationSession#toJson()} 原样）
 *   客户端 → "GNSS\n"     服务端 → 一行 JSON（{@link GnssSession#json} 原样）
 *   客户端 → "TARGETS\n"  服务端 → 一行 JSON {"enabled":bool,"packages":[...]}
 *   任何异常 / 不可用     服务端 → 一个空行（客户端据此判定"无数据"）
 * </pre>
 * 连上只处理一次请求即关闭；超时 / 异常一律静默关闭，绝不崩溃、绝不阻塞主线程。
 *
 * <p><b>线程模型</b>：一个守护线程（{@code vloc-snapshot}）内 {@code accept()}，
 * 每条连接<b>串行</b>处理——快照读写都是内存操作，不需要线程池；这也保证任意时刻
 * 只有一条连接被服务，不会因为客户端洪泛把应用拖垮。
 *
 * <p><b>生命周期</b>：{@link LocationService} 在会话启动时 {@link #start(Context)}、
 * 在 {@code stopInternal} 收尾时 {@link #stop()}；进程被杀时 socket 随进程一起释放，
 * 不需要额外处理。{@link #start(Context)} / {@link #stop()} 都是幂等的。
 */
public final class SnapshotServer {

    /**
     * 抽象命名空间套接字名。
     *
     * <p><b>契约</b>：模块侧必须用
     * {@code new LocalSocketAddress("com.vloc.motion.snapshot", Namespace.ABSTRACT)}
     * 连接；改名等于换协议。
     */
    public static final String SOCKET_NAME = "com.vloc.motion.snapshot";

    /** 唯一实例：与 {@link LocationSession}、{@link GnssSession} 等通道单例并列。 */
    public static final SnapshotServer INSTANCE = new SnapshotServer();

    private static final String TAG = "VLocSnapshot";

    private static final String CMD_LOC = "LOC";
    private static final String CMD_GNSS = "GNSS";
    private static final String CMD_TARGETS = "TARGETS";

    /**
     * 单行请求的字节上限。
     *
     * <p>最长命令 {@code TARGETS} 只有 7 字节，留 64 字节余量足够容错；
     * 超限仍未见到换行的一律当非法请求（回空行），避免客户端灌数据把线程/内存拖住。
     */
    private static final int MAX_REQUEST_BYTES = 64;

    /** 读请求的超时（毫秒）：连上却不发请求的客户端必须自己断开，不能把服务卡死。 */
    private static final int READ_TIMEOUT_MS = 2_000;

    /** {@link #stop()} 等待守护线程退出的上限（毫秒）：收尾在主线程，绝不能无限等。 */
    private static final long STOP_JOIN_MS = 500L;

    /** 串行化 start / stop / accept 循环对共享字段的读写。 */
    private final Object lock = new Object();

    /** 仅用于 {@code /targets} 的 SharedPreferences 读取；{@link #start(Context)} 时换成 application context。 */
    private Context appContext;

    /** 启动代次：每次 start / stop 自增，守护线程只认自己启动时拿到的那一代（受 {@link #lock} 保护）。 */
    private volatile int epoch;

    private boolean running;
    private LocalServerSocket server;
    private Thread worker;

    /** 正在处理的那条连接：{@link #stop()} 关掉它，让阻塞中的 {@code read} 立刻返回。 */
    private volatile LocalSocket current;

    private SnapshotServer() {
    }

    // ---------------------------------------------------------------- 生命周期

    /**
     * 启动快照套接字（幂等）。
     *
     * <p>只做"置标记 + 起守护线程"：真正的绑定发生在守护线程里，主线程不会阻塞在
     * socket / bind 上；绑定失败也不影响会话（见 {@link #acceptLoop(int)}）。
     *
     * @param context 任意 Context（内部取 application context，只用于读作用域配置）
     */
    public void start(Context context) {
        final Context app = context == null ? null : context.getApplicationContext();
        final Thread thread;
        synchronized (lock) {
            if (app != null) {
                appContext = app;
            }
            if (running) {
                return;   // 已在运行：重复 start 安全（"重新开始"不会重复绑定同名 socket）
            }
            final int myEpoch = ++epoch;
            running = true;
            thread = new Thread(() -> acceptLoop(myEpoch), "vloc-snapshot");
            thread.setDaemon(true);   // 守护线程：应用退出时不会因为它残留
            worker = thread;
        }
        thread.start();
    }

    /**
     * 停止快照套接字（幂等）：打断当前连接、叫醒 {@code accept()}、关闭 server socket、
     * 有界等待守护线程退出。
     *
     * <p>能在主线程调用：关闭 socket 后 {@code read()} 会立刻抛 {@link IOException}，
     * {@code join} 还有 {@link #STOP_JOIN_MS} 上限，最坏只停 500ms。
     */
    public void stop() {
        final LocalServerSocket serverToClose;
        final LocalSocket clientToClose;
        final Thread thread;
        synchronized (lock) {
            if (!running && server == null && worker == null) {
                return;   // 未启动 / 已停止：重复 stop 安全
            }
            running = false;
            epoch++;              // 让仍在跑的守护线程不再回写共享状态
            serverToClose = server;
            server = null;
            clientToClose = current;
            current = null;
            thread = worker;
            worker = null;
        }
        closeQuietly(clientToClose);   // 先打断正在处理的那条连接（阻塞在 read 上）
        wakeAccept();                  // 再叫醒可能卡在 accept() 的线程（必须在 close 之前）
        closeQuietly(serverToClose);   // 关闭监听 socket：不再接受任何新连接
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(STOP_JOIN_MS);   // 有界等待：主线程只在这里最多停 500ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 把可能卡在 {@code accept()} 的守护线程叫醒（自连一次）。
     *
     * <p>Linux 上"在另一个线程里 close 监听 socket"<b>不保证</b>唤醒阻塞中的
     * {@code accept()}（那次系统调用还引用着旧 fd），只 close 可能留下一个永远退不出的
     * 守护线程。这里先自连一次：这条连接会被 {@code accept()} 立刻拿到，循环看到代次已过期
     * 就直接关闭并退出，<b>不会</b>进入 {@link #serve}（不会多等一次读超时）。
     *
     * <p>失败（未绑定 / 已关闭 / 权限不足）静默忽略，随后的 close 仍是主要退出手段。
     */
    private static void wakeAccept() {
        LocalSocket wake = null;
        try {
            wake = new LocalSocket();
            wake.connect(new LocalSocketAddress(SOCKET_NAME,
                    LocalSocketAddress.Namespace.ABSTRACT));
        } catch (Throwable ignored) {
            // 没有监听者时 connect 直接失败：无需唤醒
        } finally {
            closeQuietly(wake);
        }
    }

    // ---------------------------------------------------------------- 守护线程

    /**
     * {@code accept()} 循环：绑定抽象名称 → 串行服务每条连接。
     *
     * @param myEpoch 本次启动的代次；与当前代次不一致（已被 {@link #stop()} 或下一次
     *                {@link #start(Context)} 取代）时立即退出且不碰共享状态
     */
    private void acceptLoop(int myEpoch) {
        final LocalServerSocket socket;
        synchronized (lock) {
            if (epoch != myEpoch) {
                return;   // 启动后立刻被 stop()：什么都不用做
            }
            try {
                // LocalServerSocket(String) 绑定的就是抽象命名空间（Namespace.ABSTRACT）
                socket = new LocalServerSocket(SOCKET_NAME);
            } catch (Throwable t) {
                // 绑定失败（名称被占用 / SELinux 拒绝 / 其它 IO 错误）：只记一次 Log.w，
                // 会话继续跑，模块会回退到 ContentProvider（EnvProvider 行为不变）
                Log.w(TAG, "bind abstract socket " + SOCKET_NAME + " failed: " + t
                        + " (snapshot socket disabled for this session)");
                if (epoch == myEpoch) {
                    running = false;
                    worker = null;
                }
                return;
            }
            if (epoch != myEpoch) {
                closeQuietly(socket);   // 绑定期内被 stop()：别留下没人负责的 socket
                return;
            }
            server = socket;
        }
        Log.i(TAG, "snapshot socket ready: " + SOCKET_NAME + " (abstract)");
        try {
            while (true) {
                final LocalSocket client;
                try {
                    client = socket.accept();
                } catch (Throwable t) {
                    break;   // stop() 关掉了 server socket（或 IO 错误）：正常退出
                }
                if (client == null) {
                    continue;
                }
                if (!isCurrent(myEpoch)) {
                    closeQuietly(client);
                    break;
                }
                current = client;
                try {
                    serve(client);
                } catch (Throwable ignored) {
                    // 单条连接的任何异常都不能拖垮服务：静默关闭，继续 accept
                } finally {
                    if (current == client) {
                        current = null;
                    }
                    closeQuietly(client);
                }
            }
        } finally {
            synchronized (lock) {
                if (epoch == myEpoch) {   // 自己是当前代次才回写状态（否则是被取代的线程）
                    running = false;
                    if (worker == Thread.currentThread()) {
                        worker = null;
                    }
                    server = null;
                }
            }
            closeQuietly(socket);
        }
    }

    /** 本次启动的代次是否仍是当前代次（stop / 下一次 start 之后为 false）。 */
    private boolean isCurrent(int myEpoch) {
        synchronized (lock) {
            return running && epoch == myEpoch;
        }
    }

    // ---------------------------------------------------------------- 单条连接

    /**
     * 处理一条连接：设读超时 → 读一行请求 → 回一行应答 → 关闭（由调用方 finally 关闭）。
     *
     * <p>本方法在守护线程内串行执行，全程不碰主线程；任何 IO 异常都只静默关闭。
     */
    private void serve(LocalSocket client) {
        try {
            client.setSoTimeout(READ_TIMEOUT_MS);
        } catch (Throwable ignored) {
            // 个别实现不支持 SO_TIMEOUT：继续处理（客户端关闭同样会让 read 返回）
        }
        String request = null;
        try {
            request = readRequestLine(client.getInputStream());
        } catch (Throwable ignored) {
            // 超时 / 对端提前断开：按"无数据"处理
        }
        if (request != null) {
            markPeerRead(client);
        }
        final String reply = oneLine(replyFor(request));
        try {
            final OutputStream out = client.getOutputStream();
            out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable ignored) {
            // 对端已断开：静默（模块侧按"无数据"处理，不影响会话）
        }
    }

    /**
     * 记录"system_server 里的模块读过本快照"（只认 uid 1000）。
     *
     * <p>与 {@link EnvProvider} 里 {@code Binder.getCallingUid() == Process.SYSTEM_UID} 的判定
     * 等价：模块改用套接字后不再走 ContentProvider，若不在这里补记这条痕迹，
     * {@link LocationService} 的"系统级注入是否真的在接管"判定（{@code isSystemModuleOnline}）
     * 会一直认为模块不在线，进而拒绝开始会话。
     *
     * <p>身份用 {@code SO_PEERCRED} 卡死，避免本机任意应用连一下就被当成"系统级接管已生效"
     * （那正是旧版本"界面在跑、实际一帧没注入"的假成功）。
     */
    private void markPeerRead(LocalSocket client) {
        try {
            final Credentials peer = client.getPeerCredentials();
            if (peer != null && peer.getUid() == Process.SYSTEM_UID) {
                StepSession.INSTANCE.markModuleSystemRead();
            }
        } catch (Throwable ignored) {
            // 拿不到对端身份（SELinux / 已断开）：不记痕迹，绝不影响应答
        }
    }

    /**
     * 读一行请求（ASCII，以 {@code \n} 结尾，{@code \r} 容忍并丢弃）。
     *
     * <p>上限 {@link #MAX_REQUEST_BYTES} 字节：读满仍未见到换行则按非法请求处理（返回
     * {@code null} → 回空行），客户端无法用超长请求把线程拖住。
     *
     * @return 去空白后的命令；空请求 / 非法请求返回 {@code null}
     */
    private static String readRequestLine(InputStream in) throws IOException {
        final StringBuilder sb = new StringBuilder(16);
        boolean terminated = false;
        for (int i = 0; i < MAX_REQUEST_BYTES; i++) {
            final int b = in.read();
            if (b < 0) {
                terminated = true;   // 对端写完就关：按已收到的内容处理
                break;
            }
            if (b == '\n') {
                terminated = true;
                break;
            }
            if (b != '\r') {
                sb.append((char) b);
            }
        }
        if (!terminated) {
            return null;   // 超过上限仍没有换行：非法请求
        }
        final String line = sb.toString().trim();
        return line.isEmpty() ? null : line;
    }

    // ---------------------------------------------------------------- 应答内容

    /**
     * 命令 → 一行 JSON；未知命令 / 不可用 / 组包失败一律空串（客户端据此判"无数据"）。
     *
     * <p>数据源全部是内存里的既有单例，与 ContentProvider 完全同源：
     * {@link LocationSession#toJson()}、{@link GnssSession#json}、
     * {@link RouteStore#loadScopeEnabled}/{@link RouteStore#loadTargetPackages}。
     */
    private String replyFor(String request) {
        if (request == null || request.isEmpty()) {
            return "";
        }
        try {
            switch (request.toUpperCase(Locale.US)) {
                case CMD_LOC:
                    return locationJson();
                case CMD_GNSS:
                    return gnssJson();
                case CMD_TARGETS:
                    return targetsJson();
                default:
                    return "";   // 未知命令：不带日志（可能是高频路径），回空行即"无数据"
            }
        } catch (Throwable t) {
            // 组包失败（脏数据 / prefs 损坏）：回空行，绝不让模块拿到半截 JSON
            Log.w(TAG, "build reply for " + request + " failed: " + t);
            return "";
        }
    }

    /**
     * {@code LOC}：与 {@code content://com.vloc.motion.env/location} 完全相同的 JSON。
     *
     * <p>未在模拟（{@code active == false}）时回空行：这与 {@link EnvProvider} 返回 0 行
     * 的语义一致——模块据此透传真实定位，<b>不会</b>把上一场模拟的陈旧坐标继续注入。
     */
    private static String locationJson() {
        final LocationSession location = LocationSession.INSTANCE;
        if (!location.active) {
            return "";
        }
        return location.toJson();
    }

    /** {@code GNSS}：与 {@code content://com.vloc.motion.env/gnss} 相同的 JSON（无数据回空行）。 */
    private static String gnssJson() {
        final String json = GnssSession.INSTANCE.json;
        return json == null ? "" : json;
    }

    /**
     * {@code TARGETS}：{@code {"enabled":bool,"packages":[...]}}。
     *
     * <p>与 {@link EnvProvider} 的 {@code /targets} 一致：<b>永远</b>返回一行合法 JSON
     * （读不到 context 时就是 {@code enabled=false} + 空名单），模块不用处理"无数据"。
     */
    private String targetsJson() {
        final Context context = appContext;
        final JSONObject json = new JSONObject();
        try {
            json.put("enabled", context != null && RouteStore.loadScopeEnabled(context));
            final JSONArray packages = new JSONArray();
            if (context != null) {
                for (String pkg : RouteStore.loadTargetPackages(context)) {
                    packages.put(pkg);
                }
            }
            json.put("packages", packages);
        } catch (JSONException ignored) {
            // 已知 key 不会失败；兜底返回已构造部分（与 EnvProvider.targetsCursor 一致）
        }
        return json.toString();
    }

    /** 协议是"一行 JSON"：负载里若混入换行（脏数据）就地替换，避免客户端把应答切成两行。 */
    private static String oneLine(String payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        if (payload.indexOf('\n') < 0 && payload.indexOf('\r') < 0) {
            return payload;
        }
        return payload.replace('\r', ' ').replace('\n', ' ');
    }

    private static void closeQuietly(Closeable target) {
        if (target == null) {
            return;
        }
        try {
            target.close();
        } catch (Throwable ignored) {
            // 关闭失败无需处理：连接已经不可用
        }
    }
}
