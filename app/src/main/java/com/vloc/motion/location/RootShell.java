package com.vloc.motion.location;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 以 root 身份执行 shell 命令。
 * 兼容 Magisk / KernelSU / APatch：统一使用 `su -c "<cmd>"`，不依赖任何 Manager 私有接口。
 */
public final class RootShell {

    private static final String TAG = "VLocRoot";

    /** 命令执行结果。 */
    public static final class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final boolean timedOut;

        Result(int exitCode, String stdout, String stderr, boolean timedOut) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.timedOut = timedOut;
        }

        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }

        public String combined() {
            return (stdout + " " + stderr).trim();
        }

        @Override
        public String toString() {
            return "exit=" + exitCode + " out=" + stdout.trim() + " err=" + stderr.trim()
                    + (timedOut ? " (timeout)" : "");
        }
    }

    private RootShell() {
    }

    /** 执行 root 命令，默认 20 秒超时（Magisk 首次授权可能弹窗）。 */
    public static Result su(String command) {
        return su(command, 20000L);
    }

    /** 不同的 root 方案可能把 su 放在不同路径，依次尝试。 */
    private static final String[] SU_CANDIDATES = {
            "su", "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su"
    };

    /** 执行 root 命令（自动尝试多个 su 路径）。 */
    public static Result su(String command, long timeoutMs) {
        IOException lastError = null;
        for (String candidate : SU_CANDIDATES) {
            try {
                return exec(candidate, command, timeoutMs);
            } catch (IOException e) {
                lastError = e;
            }
        }
        Log.w(TAG, "su unavailable: " + (lastError == null ? "unknown" : lastError.getMessage()));
        return new Result(-1, "", lastError == null ? "su not found" : lastError.getMessage(), false);
    }

    private static Result exec(String suBinary, String command, long timeoutMs) throws IOException {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(suBinary, "-c", command);
            pb.redirectErrorStream(false);
            process = pb.start();

            OutputStream stdin = process.getOutputStream();
            stdin.close();

            StreamGobbler out = new StreamGobbler(process.getInputStream());
            StreamGobbler err = new StreamGobbler(process.getErrorStream());
            out.start();
            err.start();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                return new Result(-1, out.await(), err.await(), true);
            }
            int code = process.exitValue();
            return new Result(code, out.await(), err.await(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "", "interrupted", false);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /** 结果是否代表"已获得 root"。 */
    public static boolean isRootResult(Result result) {
        return result != null && result.ok() && result.stdout.contains("uid=0");
    }

    /** 主动申请一次 root（30 秒，等待授权弹窗）。 */
    public static Result checkRoot() {
        return su("id", 30000L);
    }

    /** 是否拿到 root（uid=0）。 */
    public static boolean isRooted() {
        return isRootResult(su("id", 12000L));
    }

    /** 查询 mock_location appop 状态。 */
    public static Result getMockAppop(String pkg) {
        Result r = su("appops get " + pkg + " android:mock_location");
        if (!r.ok()) {
            // 兼容部分 ROM：改用 cmd appops
            r = su("cmd appops get " + pkg + " android:mock_location");
        }
        return r;
    }

    /** 通过 root 给本应用授予 android:mock_location appop（无需打开开发者选项）。 */
    public static Result grantMockAppop(String pkg) {
        Result r = su("appops set " + pkg + " android:mock_location allow");
        if (!r.ok()) {
            r = su("cmd appops set " + pkg + " android:mock_location allow");
        }
        return r;
    }

    /** 撤回 mock_location appop。 */
    public static Result revokeMockAppop(String pkg) {
        Result r = su("appops set " + pkg + " android:mock_location default");
        if (!r.ok()) {
            r = su("cmd appops set " + pkg + " android:mock_location default");
        }
        return r;
    }

    /** 判断 appop 输出是否已经是 allow。 */
    public static boolean isMockAppopAllowed(Result r) {
        return r != null && r.ok() && r.combined().toLowerCase().contains("allow");
    }

    private static final class StreamGobbler extends Thread {
        private final InputStream in;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private volatile boolean done;

        StreamGobbler(InputStream in) {
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] buf = new byte[4096];
            try {
                int n;
                while ((n = in.read(buf)) != -1) {
                    buffer.write(buf, 0, n);
                }
            } catch (IOException ignored) {
                // 忽略读取中断
            } finally {
                done = true;
            }
        }

        String await() {
            try {
                join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (buffer) {
                return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }
}
