package com.vloc.motion.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GNSS 星表在线更新 / 加载：从 CelesTrak GP 拉取 TLE 快照，校验后原子写入
 * {@code getFilesDir()/gnss_tle.txt}；加载时「已更新文件优先，内置资产兜底」。
 *
 * <p>数据源与 {@code scripts/update_gnss_tle.py} 完全一致：4 个分组
 * （gps-ops / glo-ops / galileo / beidou）+ {@code FORMAT=tle}，拼接成一份星表。
 *
 * <p>约束：
 * <ul>
 *   <li>一次任务一个后台线程，不新增常驻线程；结果统一抛回主线程（{@link Callback}）；</li>
 *   <li>每个分组 connect / read 各 10 秒超时；任一分组失败即整体失败；</li>
 *   <li>校验不通过（空表 / 卫星数不足 / 历元离谱）就不落盘，<b>已有星表保持原样</b>；</li>
 *   <li>落盘先写 {@code gnss_tle.txt.tmp} 再 rename，避免进程被杀时留下半截文件。</li>
 * </ul>
 */
public final class TleUpdater {

    private static final String TAG = "VLocTle";

    /** 星表来源：应用内已更新（{@code files/gnss_tle.txt}）。 */
    public static final String SOURCE_UPDATED = "updated";
    /** 星表来源：APK 内置（{@code assets/gnss_tle.txt}）。 */
    public static final String SOURCE_ASSET = "asset";
    /** 星表来源：都没有（不产出卫星数据）。 */
    public static final String SOURCE_NONE = "none";

    /** 星表超过这么多天就算"过期"：启动提醒与设置里的展示共用同一阈值。 */
    public static final int STALE_DAYS = 30;
    /** 启动提醒的最小间隔（同一份星表 7 天内只提醒一次）。 */
    public static final long REMIND_INTERVAL_MS = 7L * 86400000L;

    private static final String ASSET_NAME = "gnss_tle.txt";
    private static final String FILE_NAME = "gnss_tle.txt";
    /** CelesTrak GP 分组，顺序与 {@code scripts/update_gnss_tle.py} 一致。 */
    private static final String[] GROUPS = {"gps-ops", "glo-ops", "galileo", "beidou"};
    private static final String GP_URL =
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=%s&FORMAT=tle";
    /** 礼貌 UA（CelesTrak 是公益服务，便于对方按来源统计/限流）。 */
    private static final String USER_AGENT = "vloc-motion/1.0 (+gnss-tle-updater)";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    /** 星表至少要有这么多颗星，否则视为"没拉到有效数据"（GPS 一组就 30+）。 */
    private static final int MIN_SATELLITES = 20;
    /** 历元下限：2020-01-01T00:00:00Z——比这更早的星表必然是脏数据 / 解析错位。 */
    private static final long MIN_EPOCH_MS = 1577836800000L;
    /** 允许历元比本机时间超前 7 天（设备时钟偏慢 / UTC 误差的余量）。 */
    private static final long MAX_EPOCH_SKEW_MS = 7L * 86400000L;

    /** 同一时刻只允许一个更新任务：重复点击直接返回失败，不重复下载/写盘。 */
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private TleUpdater() {
    }

    /** 更新结果（回调在主线程，只会被调用一次）。 */
    public static final class Result {
        /** 是否成功：只有"4 组都拉到 + 校验通过 + 写入成功"才算成功。 */
        public final boolean ok;
        /** 新星表的卫星数（成功时有效）。 */
        public final int satCount;
        /** 新星表最新历元（UTC 毫秒，成功时有效）。 */
        public final long newestEpochMs;
        /** 新星表过期天数（成功时有效，-1 = 未知）。 */
        public final int ageDays;
        /** 失败原因（可直接展示给用户；成功时为 null）。 */
        public final String error;

        Result(boolean ok, int satCount, long newestEpochMs, int ageDays, String error) {
            this.ok = ok;
            this.satCount = satCount;
            this.newestEpochMs = newestEpochMs;
            this.ageDays = ageDays;
            this.error = error;
        }

        static Result failed(String error) {
            return new Result(false, 0, 0L, -1, error);
        }
    }

    /** 当前生效星表的只读快照（加载来源 + 卫星数 + 历元年龄）。 */
    public static final class Catalog {
        /** 星表文本；null = 文件与内置资产都不可用。 */
        public final String text;
        /** {@link #SOURCE_UPDATED} / {@link #SOURCE_ASSET} / {@link #SOURCE_NONE}。 */
        public final String source;
        public final int satCount;
        public final long newestEpochMs;
        /** 过期天数；-1 = 没有可用的历元（或已未来历元为负）。 */
        public final int ageDays;

        Catalog(String text, String source, int satCount, long newestEpochMs, int ageDays) {
            this.text = text;
            this.source = source;
            this.satCount = satCount;
            this.newestEpochMs = newestEpochMs;
            this.ageDays = ageDays;
        }

        static Catalog missing() {
            return new Catalog(null, SOURCE_NONE, 0, 0L, -1);
        }

        /** 设置里展示的来源文案：内置 / 已更新 / 无。 */
        public String sourceLabel() {
            if (SOURCE_UPDATED.equals(source)) {
                return "已更新";
            }
            if (SOURCE_ASSET.equals(source)) {
                return "内置";
            }
            return "无";
        }
    }

    /** 更新结果回调：由 {@link #update} 保证在主线程调用。 */
    public interface Callback {
        void onResult(Result result);
    }

    /**
     * 后台拉取并更新星表；结果回调在主线程。
     *
     * <p>任何失败都不会破坏现有星表（校验通过才写文件），调用方继续用当前星表即可。
     */
    public static void update(Context context, Callback callback) {
        final Context app = context == null ? null : context.getApplicationContext();
        final Context target = app != null ? app : context;
        if (target == null) {
            post(callback, Result.failed("缺少 Context"));
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            post(callback, Result.failed("正在更新，请稍候"));
            return;
        }
        new Thread(() -> {
            Result result;
            try {
                result = run(target);
            } catch (Throwable t) {
                result = Result.failed("更新异常：" + reason(t));
            } finally {
                RUNNING.set(false);
            }
            post(callback, result);
        }, "vloc-tle-update").start();
    }

    /**
     * 加载当前星表：先读 {@code files/gnss_tle.txt}（应用内已更新），
     * 不存在 / 为空 / 解析不出卫星时退回 {@code assets/gnss_tle.txt}。
     *
     * <p>线程安全（只读文件 + 纯解析），可在主线程或后台线程调用。
     */
    public static Catalog load(Context context) {
        if (context == null) {
            return Catalog.missing();
        }
        final long now = System.currentTimeMillis();
        final File dir = context.getFilesDir();
        String text = dir == null ? null : readFile(new File(dir, FILE_NAME));
        String source = SOURCE_UPDATED;
        List<GnssTle.Sat> sats = GnssTle.parse(text);
        if (sats.isEmpty()) {
            // 已更新星表缺失/损坏/为空：退回内置资产，保证功能不受影响
            if (text != null) {
                Log.w(TAG, "files gnss_tle.txt 无法解析，回退内置星表");
            }
            text = readAsset(context);
            source = SOURCE_ASSET;
            sats = GnssTle.parse(text);
        }
        if (sats.isEmpty()) {
            return Catalog.missing();
        }
        final long newest = GnssTle.newestEpochMs(sats);
        if (sats.size() < MIN_SATELLITES) {
            Log.w(TAG, "gnss catalog 卫星数偏少: " + sats.size() + "（source=" + source + "）");
        }
        return new Catalog(text, source, sats.size(), newest, ageDays(newest, now));
    }

    /** 距历元的天数（向下取整）；历元缺失返回 -1。未来历元返回负数。 */
    public static int ageDays(long epochMs, long nowMs) {
        if (epochMs <= 0L) {
            return -1;
        }
        return (int) ((nowMs - epochMs) / 86400000L);
    }

    /**
     * 是否该弹「星表过期」提醒：过期超过 {@link #STALE_DAYS} 天，且距上次提醒 ≥
     * {@link #REMIND_INTERVAL_MS}（{@code lastRemindAtMs <= 0} 表示从未提醒过）。
     */
    public static boolean shouldRemind(int ageDays, long lastRemindAtMs, long nowMs) {
        if (ageDays <= STALE_DAYS) {
            return false;
        }
        return lastRemindAtMs <= 0L || nowMs - lastRemindAtMs >= REMIND_INTERVAL_MS;
    }

    /** 历元的 UTC 文案（例：{@code 2026-11-17 05:12Z}）；没有历元返回 {@code "未知"}。 */
    public static String formatEpochUtc(long epochMs) {
        if (epochMs <= 0L) {
            return "未知";
        }
        return utcFormat("yyyy-MM-dd HH:mm").format(new Date(epochMs)) + "Z";
    }

    // ---------------------------------------------------------------- 实现

    /** 拉取 4 组 → 拼接 → 校验 → 原子落盘；每一步失败都带着可展示的原因返回。 */
    private static Result run(Context context) {
        final long now = System.currentTimeMillis();
        final StringBuilder merged = new StringBuilder(96 * 1024);
        merged.append("# GNSS TLE snapshot (CelesTrak GP, public data)\n");
        merged.append("# updated: ").append(utcStamp(now)).append('\n');
        for (String group : GROUPS) {
            final String text;
            try {
                text = fetchGroup(group);
            } catch (Exception e) {
                return Result.failed("拉取 " + group + " 失败：" + reason(e));
            }
            final String body = text == null ? "" : text.trim();
            if (body.isEmpty()) {
                return Result.failed("分组 " + group + " 返回空数据");
            }
            merged.append(body).append('\n');
        }

        final String text = merged.toString();
        final Result check = validate(text, now);
        if (!check.ok) {
            return check;
        }

        final File dir = context.getFilesDir();
        if (dir == null) {
            return Result.failed("应用内部目录不可用");
        }
        try {
            writeAtomic(new File(dir, FILE_NAME), text);
        } catch (Exception e) {
            return Result.failed("写入星表失败：" + reason(e));
        }
        Log.i(TAG, "gnss tle updated: sats=" + check.satCount
                + " newest=" + formatEpochUtc(check.newestEpochMs)
                + " ageDays=" + check.ageDays);
        return new Result(true, check.satCount, check.newestEpochMs, check.ageDays, null);
    }

    /**
     * 校验一份待落盘的星表：解析结果非空、卫星数 ≥ 20、最新历元落在
     * [2020-01-01, 现在 + 7 天] 之间。
     *
     * <p>包内可见：只做纯解析/算术，不发网络、不碰 Android API，便于本地验证。
     */
    static Result validate(String text, long nowMs) {
        final List<GnssTle.Sat> sats = GnssTle.parse(text);
        if (sats.isEmpty()) {
            return Result.failed("星表解析为空");
        }
        if (sats.size() < MIN_SATELLITES) {
            return Result.failed("卫星数不足（" + sats.size() + " < " + MIN_SATELLITES + "）");
        }
        final long newest = GnssTle.newestEpochMs(sats);
        if (newest < MIN_EPOCH_MS) {
            return Result.failed("历元过早（" + formatEpochUtc(newest) + "）");
        }
        if (newest > nowMs + MAX_EPOCH_SKEW_MS) {
            return Result.failed("历元超前（" + formatEpochUtc(newest) + "）");
        }
        return new Result(true, sats.size(), newest, ageDays(newest, nowMs), null);
    }

    /** 拉一个分组的 TLE 文本；非 200 或超时都抛 IOException（connect / read 各 10 秒）。 */
    private static String fetchGroup(String group) throws IOException {
        final URL url = new URL(String.format(Locale.US, GP_URL, group));
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "text/plain");
            final int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = conn.getInputStream()) {
                return readText(in);
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 原子写：先写同目录临时文件并 {@code fsync}，再 rename 覆盖目标文件。
     *
     * <p>写盘中途失败 / 进程被杀时目标文件保持不变，绝不会出现"半截星表"。
     */
    static void writeAtomic(File target, String text) throws IOException {
        final File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("无法创建目录 " + parent);
        }
        final File tmp = new File(parent, target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            // 个别文件系统上 rename 不覆盖已有文件：删掉目标再试一次
            if (!target.delete() || !tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IOException("替换星表文件失败");
            }
        }
    }

    private static String readFile(File file) {
        if (file == null || !file.isFile() || file.length() <= 0L) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            return readText(in);
        } catch (Exception e) {
            Log.w(TAG, "读取 " + file.getName() + " 失败：" + e);
            return null;
        }
    }

    private static String readAsset(Context context) {
        try (InputStream in = context.getAssets().open(ASSET_NAME)) {
            return readText(in);
        } catch (Exception e) {
            Log.w(TAG, "读取内置星表失败：" + e);
            return null;
        }
    }

    private static String readText(InputStream in) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(96 * 1024);
        final byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, n);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void post(final Callback callback, final Result result) {
        if (callback == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> callback.onResult(result));
    }

    /** 失败原因：优先异常 message（超时 / HTTP 码），没有就退回类名。 */
    private static String reason(Throwable t) {
        final String message = t == null ? null : t.getMessage();
        if (message != null && !message.trim().isEmpty()) {
            return message.trim();
        }
        return t == null ? "未知错误" : t.getClass().getSimpleName();
    }

    private static String utcStamp(long epochMs) {
        return utcFormat("yyyy-MM-dd'T'HH:mm").format(new Date(epochMs)) + "Z";
    }

    private static SimpleDateFormat utcFormat(String pattern) {
        final SimpleDateFormat fmt = new SimpleDateFormat(pattern, Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt;
    }
}
