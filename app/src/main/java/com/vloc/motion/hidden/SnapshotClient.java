package com.vloc.motion.hidden;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XposedBridge;

/**
 * 快照通道客户端：读主 App 的 <b>Unix 抽象套接字</b>。
 *
 * <p><b>为什么不用 ContentProvider</b>：模块运行在 <b>system_server（uid 1000）</b> 时，
 * Android 17 上读 {@code content://com.vloc.motion.env/*} 会抛 {@code SecurityException}
 * （logcat 另有 {@code Isolated process not allowed to call getContentProvider}），
 * 系统级定位替换因此拿不到快照。抽象套接字不经过 Provider 的权限校验，
 * 也不受调用方 uid 的限制，是更稳的通道。
 *
 * <p><b>协议</b>（与主 App 侧严格一致，<b>单次连接一次请求即关闭</b>）：
 * <pre>
 * 请求：一行 ASCII —— {@code LOC} / {@code GNSS} / {@code TARGETS}，以 {@code \n} 结尾
 * 响应：一行 JSON + {@code \n}；<b>空行 = 主 App 明确表示“当前没有数据”</b>
 *   LOC     {"active":bool,"lat":..,"lon":..,"alt":..,"speed":..,"bearing":..,
 *            "acc":..,"satellites":..,"updatedAt":..}   （与 /location 同款）
 *   GNSS    [{"svid":..,"const":..,...}]                （与 /gnss 同款数组）
 *   TARGETS {"enabled":bool,"packages":["pkg1",...]}    （与 /targets 同款）
 * </pre>
 *
 * <p><b>{@link #readLine(String, int)} 的三种结果</b>：
 * <ul>
 *   <li>非空字符串：一行响应；</li>
 *   <li>{@code ""}：通道可用，但主 App 回了空行（无数据）——调用方不要回退；</li>
 *   <li>{@code null}：连接 / 写 / 读失败或超时——调用方回退 ContentProvider。</li>
 * </ul>
 *
 * <p><b>失败绝不抛出、绝不拖住系统进程</b>：
 * <ul>
 *   <li>读超时用 {@link LocalSocket#setSoTimeout(int)}，并扣掉 {@code connect()} 已用掉的时间，
 *       保证一次交换的总等待 ≤ {@code timeoutMs}；</li>
 *   <li>每进程<b>每类</b>异常只记一条 {@code socket read failed: <异常类名> (<请求>)}；</li>
 *   <li>第一次失败时额外记一条切换原因
 *       {@code snapshot via socket failed: <异常类名>，fallback to ContentProvider}（每进程一次）；</li>
 *   <li>每类请求<b>首次成功</b>记一条 {@code snapshot via socket: <请求> ok}，便于现场确认通道已生效。</li>
 * </ul>
 *
 * <p>线程安全：只有静态状态（去重用的 set / 日志标记），投递线程并发调用也只会多打一条日志，
 * 不会抛异常；连接对象是每次调用独享的局部变量。
 */
final class SnapshotClient {

    /** 抽象命名空间名称（主 App 侧 {@code LocalServerSocket} 同名；不带前导 '@'）。 */
    static final String SOCKET_NAME = "com.vloc.motion.snapshot";

    /** 请求行（契约固定，一行一个请求）。 */
    static final String REQ_LOCATION = "LOC";
    static final String REQ_GNSS = "GNSS";
    static final String REQ_TARGETS = "TARGETS";

    /** 默认超时：整次交换（连接 + 写 + 读一行）的上限，绝不阻塞系统进程更久。 */
    static final int DEFAULT_TIMEOUT_MS = 300;

    /** 一行响应的上限；超过即截断（正常响应只有几十到几百字节）。 */
    private static final int MAX_LINE_BYTES = 8 * 1024;

    /** 失败日志最多记几种异常类（每种一条），防止极端情况下刷屏。 */
    private static final int MAX_ERROR_KINDS = 16;

    private static final String TAG = "[VLocHidden] ";

    /** 已记过日志的异常类名（每进程每类错误一条）。 */
    private static final Set<String> sLoggedErrors =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** 第一次“套接字失败 → 回退 Provider”的原因，每进程只记一条。 */
    private static final AtomicBoolean sLoggedFallback = new AtomicBoolean();

    /** “首成功一次”日志标记（按请求各一条，最多 3 条/进程）。 */
    private static final AtomicBoolean sLoggedLocationOk = new AtomicBoolean();
    private static final AtomicBoolean sLoggedGnssOk = new AtomicBoolean();
    private static final AtomicBoolean sLoggedTargetsOk = new AtomicBoolean();

    private SnapshotClient() {
    }

    /**
     * 连接抽象套接字 → 发一行请求 → 读一行响应 → 关闭。
     *
     * @param request   请求行内容（{@link #REQ_LOCATION} / {@link #REQ_GNSS} / {@link #REQ_TARGETS}）
     * @param timeoutMs 整次交换的超时上限；≤0 时用 {@link #DEFAULT_TIMEOUT_MS}
     * @return 非空 = 一行响应；{@code ""} = 主 App 回空行（无数据）；{@code null} = 失败（调用方回退）
     */
    static String readLine(String request, int timeoutMs) {
        if (request == null || request.isEmpty()) {
            return null;
        }
        final int timeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        final long startedAt = SystemClock.elapsedRealtime();
        LocalSocket socket = null;
        try {
            socket = new LocalSocket();
            socket.connect(new LocalSocketAddress(SOCKET_NAME,
                    LocalSocketAddress.Namespace.ABSTRACT));
            // 读超时只约束 read/write：扣掉 connect 已花掉的时间，整次交换仍 ≤ timeout。
            // 若 connect 已吃满预算，这里只剩 1ms，read 会立刻抛 SocketTimeoutException。
            final long spent = SystemClock.elapsedRealtime() - startedAt;
            socket.setSoTimeout((int) Math.max(1L, timeout - spent));
            final OutputStream out = socket.getOutputStream();
            if (out == null) {
                return null;   // 不会发生，兜底避免 NPE
            }
            out.write((request + "\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            final String line = readOneLine(socket.getInputStream());
            noteSuccess(request);
            return line;
        } catch (Throwable t) {
            // 失败一律静默：只记一条低频日志，返回 null 让调用方回退 Provider
            noteFailure(request, t);
            return null;
        } finally {
            closeQuietly(socket);
        }
    }

    // ================================================================ 读写细节

    /**
     * 读一行：读到 {@code \n} 或对端关闭即停，最多 {@link #MAX_LINE_BYTES} 字节。
     * 兼容 {@code \r\n}（丢掉 {@code \r}），返回值去掉首尾空白。
     */
    private static String readOneLine(InputStream raw) throws IOException {
        if (raw == null) {
            return "";
        }
        final InputStream in = raw instanceof BufferedInputStream
                ? raw : new BufferedInputStream(raw, 128);
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        while (buffer.size() < MAX_LINE_BYTES) {
            final int value = in.read();
            if (value < 0) {
                break;      // 对端关闭：已读到的内容就是这一行（空 = 空行）
            }
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                buffer.write(value);
            }
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    /** 关闭连接；失败无所谓（连接一次性，进程退出时内核回收）。 */
    private static void closeQuietly(LocalSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ================================================================ 日志（每进程低频）

    /** 每类请求首次成功：一行，用于现场确认“套接字通道已生效”。 */
    private static void noteSuccess(String request) {
        try {
            final AtomicBoolean flag = flagFor(request);
            if (flag != null && flag.compareAndSet(false, true)) {
                log("snapshot via socket: " + request + " ok");
            }
        } catch (Throwable ignored) {
            // 日志绝不抛出
        }
    }

    /** 失败：每类异常一条；另加一条（每进程一次）切换原因。 */
    private static void noteFailure(String request, Throwable t) {
        try {
            final String kind = t == null ? "unknown" : t.getClass().getName();
            if (sLoggedErrors.size() < MAX_ERROR_KINDS && sLoggedErrors.add(kind)) {
                log("socket read failed: " + kind + " (" + request + ")");
            }
            if (sLoggedFallback.compareAndSet(false, true)) {
                log("snapshot via socket failed: " + kind
                        + "，fallback to provider");
            }
        } catch (Throwable ignored) {
            // 日志绝不抛出
        }
    }

    /** 请求 → “首成功日志”标记（未知请求归到 TARGETS，实际不会被调用）。 */
    private static AtomicBoolean flagFor(String request) {
        if (REQ_LOCATION.equals(request)) {
            return sLoggedLocationOk;
        }
        if (REQ_GNSS.equals(request)) {
            return sLoggedGnssOk;
        }
        if (REQ_TARGETS.equals(request)) {
            return sLoggedTargetsOk;
        }
        return null;
    }

    private static void log(String message) {
        try {
            XposedBridge.log(TAG + message);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
