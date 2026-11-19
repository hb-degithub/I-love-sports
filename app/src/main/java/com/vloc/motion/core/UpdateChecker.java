package com.vloc.motion.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.vloc.motion.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 应用内更新检查：从 GitHub Releases 拉「最新版本」，与 {@link BuildConfig#VERSION_NAME}
 * 逐段数字比较，判断是否需要提示升级。
 *
 * <p>约束：
 * <ul>
 *   <li>网络全部在一次性后台线程（{@code vloc-update-check}）里做，connect / read 各 8 秒，
 *       绝不阻塞调用方（主线程只负责弹窗）；</li>
 *   <li>任何失败（无网络 / HTTP 非 200 / JSON 解析失败 / 没有 APK 附件）都只回调
 *       {@code error}，不抛异常、不弹任何提示；</li>
 *   <li>版本比较按 {@code X.Y.Z} 逐段 <b>数字</b>比较（不能按字符串比，
 *       否则 0.46.10 会被当成小于 0.46.9）；只有远端严格更大才算有新版本；</li>
 *   <li>自动检查 24 小时内只做一次（{@link #shouldAutoCheck} / {@link #markChecked}），
 *       手动检查不受此限制。</li>
 * </ul>
 *
 * <p>结果回调固定在主线程（内部 {@link Handler} 转发），Activity 被销毁后回调里
 * 自行判 {@code isFinishing() / isDestroyed()} 即可，本类不做 UI 相关处理。
 */
public final class UpdateChecker {

    private static final String TAG = "VLocUpdate";

    /** 发布页：{@code DownloadManager} 不可用 / 下载失败时用浏览器兜底打开。 */
    public static final String RELEASES_URL =
            "https://github.com/hb-degithub/I-love-sports/releases";

    /** GitHub Releases「最新」接口（不含 draft / prerelease，正是要发布的正式版）。 */
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/hb-degithub/I-love-sports/releases/latest";

    /** 发布脚本产出的附件名：{@code virtual-motion-<versionName>.apk}（见 release.yml）。 */
    private static final String APK_PREFIX = "virtual-motion-";
    private static final String APK_SUFFIX = ".apk";
    private static final String SHA_SUFFIX = ".sha256";

    /** 自动检查的最小间隔：24 小时。手动检查不走这个节流。 */
    public static final long AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    private static final String PREFS = "vloc_update";
    /** 上次「自动检查」的时间戳（毫秒）。 */
    private static final String KEY_LAST_CHECK_AT = "last_auto_check_at";

    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 8_000;

    /**
     * GitHub API 要求带 User-Agent（不带会直接 403）；用应用自身标识，
     * 方便对方按来源统计/限流。
     */
    private static final String USER_AGENT =
            "vloc-motion/" + BuildConfig.VERSION_NAME + " (+android; update-check)";
    /** GitHub 官方推荐的 API 版本头：固定值，行为不随后续改版漂移。 */
    private static final String GITHUB_API_VERSION = "2022-11-28";

    private UpdateChecker() {
    }

    /**
     * 检查结果回调（保证在主线程、且只会被调用一次）。
     *
     * @param hasUpdate 远端版本是否<b>严格大于</b>当前 {@code BuildConfig.VERSION_NAME}
     * @param latestTag 远端 tag（如 {@code v0.46.3}；失败时为 null）
     * @param notes     Release 正文（可能为空串；失败时为 null）
     * @param apkUrl    {@code virtual-motion-*.apk} 的 browser_download_url（失败时为 null）
     * @param sha256Url 同名 {@code .sha256} 附件的 URL（没发这个附件时为 null 或空串）
     * @param error     失败原因（可直接展示给用户）；成功时为 null
     */
    public interface Callback {
        void onResult(boolean hasUpdate, String latestTag, String notes,
                      String apkUrl, String sha256Url, String error);
    }

    // ------------------------------------------------------------ 对外 API

    /**
     * 后台拉取 GitHub 最新 Release 并比对版本；结果回调在主线程。
     *
     * <p>调用后立即返回，UI 不需要 loading；失败只通过 {@code error} 告知，绝不抛异常。
     * 手动检查（设置里的「检查更新」）也应调用本方法——节流只作用于
     * {@link #shouldAutoCheck}，{@link #check} 本身不限次数。
     */
    public static void check(final Context context, final Callback callback) {
        if (callback == null) {
            return;
        }
        final Context app = appContext(context);
        if (app == null) {
            post(callback, false, null, null, null, null, "缺少 Context");
            return;
        }
        try {
            new Thread(() -> {
                boolean hasUpdate = false;
                String tag = null;
                String notes = null;
                String apkUrl = null;
                String sha256Url = null;
                String error = null;
                try {
                    final Release release = fetchLatestRelease();
                    tag = release.tag;
                    notes = release.notes;
                    apkUrl = release.apkUrl;
                    sha256Url = release.sha256Url;
                    final int[] remote = parseVersion(tag);
                    if (remote == null) {
                        throw new IOException("无法解析版本号：" + tag);
                    }
                    int[] local = parseVersion(BuildConfig.VERSION_NAME);
                    if (local == null) {
                        // 理论上不会发生（gradle 里写死的版本号）；按 0.0.0 处理至少不会漏掉升级
                        Log.w(TAG, "本机版本号无法解析：" + BuildConfig.VERSION_NAME);
                        local = new int[]{0, 0, 0};
                    }
                    hasUpdate = compareVersions(remote, local) > 0;
                    Log.i(TAG, "update check: local=" + BuildConfig.VERSION_NAME
                            + " remote=" + tag + " hasUpdate=" + hasUpdate
                            + " apkSize=" + release.apkSize);
                } catch (Throwable t) {
                    // 失败一律清空结果，只回 error：调用方不需要区分"半成功"
                    hasUpdate = false;
                    tag = null;
                    notes = null;
                    apkUrl = null;
                    sha256Url = null;
                    error = reason(t);
                    Log.w(TAG, "update check failed: " + error);
                }
                post(callback, hasUpdate, tag, notes, apkUrl, sha256Url, error);
            }, "vloc-update-check").start();
        } catch (Throwable t) {
            // 线程创建失败（极端情况）也要走回调，绝不让异常冒到调用方
            post(callback, false, null, null, null, null, "无法启动检查线程：" + reason(t));
        }
    }

    /**
     * 是否该做启动时的自动检查：距上次自动检查 ≥ 24 小时（从未检查过也算）。
     *
     * <p>时间戳回退（用户改系统时间 / 从未来时间戳恢复）时直接允许检查，
     * 否则会永远卡在"24 小时内"。
     */
    public static boolean shouldAutoCheck(Context context) {
        try {
            final SharedPreferences prefs = prefs(context);
            if (prefs == null) {
                return true;
            }
            final long last = prefs.getLong(KEY_LAST_CHECK_AT, 0L);
            if (last <= 0L) {
                return true;
            }
            final long elapsed = System.currentTimeMillis() - last;
            return elapsed < 0L || elapsed >= AUTO_CHECK_INTERVAL_MS;
        } catch (Throwable t) {
            Log.w(TAG, "shouldAutoCheck failed", t);
            return true;
        }
    }

    /** 记录一次自动检查的时间（调用时机：真正发起检查之前，失败也算"查过了"）。 */
    public static void markChecked(Context context) {
        try {
            final SharedPreferences prefs = prefs(context);
            if (prefs == null) {
                return;
            }
            prefs.edit().putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis()).apply();
        } catch (Throwable t) {
            Log.w(TAG, "markChecked failed", t);
        }
    }

    /** 当前安装版本（{@code BuildConfig.VERSION_NAME}，设置页与更新对话框共用）。 */
    public static String currentVersionName() {
        try {
            final String name = BuildConfig.VERSION_NAME;
            return name == null || name.trim().isEmpty() ? "未知" : name.trim();
        } catch (Throwable t) {
            return "未知";
        }
    }

    // ------------------------------------------------------------ 版本比较（纯算法，包内可见便于本地验证）

    /**
     * 解析 {@code v0.46.3} / {@code 0.46} 形式的版本号为 [major, minor, patch]。
     *
     * <p>只取前 3 段数字，遇到 {@code -rc1} / {@code +build} 之类后缀即停止；
     * 缺段补 0（{@code 0.46} 等价于 {@code 0.46.0}）。任一段没有数字（{@code v} / {@code abc}）
     * 返回 null——调用方按"无法解析"处理，绝不把垃圾 tag 当成新版本。
     */
    static int[] parseVersion(String raw) {
        if (raw == null) {
            return null;
        }
        final String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        int i = 0;
        final char first = text.charAt(0);
        if (first == 'v' || first == 'V') {
            i++;
        }
        final int[] parts = new int[3];
        int segments = 0;
        while (i < text.length() && segments < parts.length) {
            final int start = i;
            long value = 0L;
            while (i < text.length() && Character.isDigit(text.charAt(i))) {
                // 用 long 累加并按 int 上限夹紧：绝不因为超长数字溢出成负数
                value = Math.min(value * 10L + (text.charAt(i) - '0'), Integer.MAX_VALUE);
                i++;
            }
            if (i == start) {
                return null;   // 段内没有数字（v / vx.1）
            }
            parts[segments++] = (int) value;
            if (i < text.length() && text.charAt(i) == '.') {
                i++;
                continue;
            }
            break;   // 后缀（-beta / +build）不再解析
        }
        return segments == 0 ? null : parts;
    }

    /** 逐段数字比较（a > b 返回正数，a < b 返回负数，相等返回 0）。 */
    static int compareVersions(int[] a, int[] b) {
        final int[] left = a == null ? new int[3] : a;
        final int[] right = b == null ? new int[3] : b;
        for (int i = 0; i < 3; i++) {
            final int x = left[i];
            final int y = right[i];
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------ 网络 / 解析

    /** 拉最新 Release 并解析（调用方保证在后台线程）。 */
    private static Release fetchLatestRelease() throws IOException {
        final HttpURLConnection conn =
                (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setUseCaches(false);
            // GitHub 要求 UA（否则 403）；Accept 指定新版 API 的媒体类型
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION);
            conn.setRequestProperty("Cache-Control", "no-cache");
            final int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException(httpMessage(code));
            }
            final String body;
            try (InputStream in = conn.getInputStream()) {
                body = readText(in);
            }
            try {
                return parseRelease(body);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                // JSONException 等：统一折成可展示的 IOException（check 里只认 message）
                throw new IOException("发布信息解析失败：" + reason(e), e);
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 解析 GitHub Release JSON：{@code tag_name}、{@code body}，以及 {@code assets[]} 里
     * {@code virtual-motion-*.apk} 的下载地址 / 大小和同名 {@code .sha256} 附件的地址。
     *
     * <p>包内可见：不做网络、不碰 Android API，便于本地用固定 JSON 验证。
     */
    static Release parseRelease(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) {
            throw new IOException("接口返回为空");
        }
        final JSONObject root = new JSONObject(json);
        final String tag = root.optString("tag_name", "").trim();
        if (tag.isEmpty()) {
            throw new IOException("发布信息缺少 tag_name");
        }
        final String notes = root.optString("body", "");
        String apkName = null;
        String apkUrl = null;
        String sha256Url = null;
        long apkSize = -1L;

        final JSONArray assets = root.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                final JSONObject asset = assets.optJSONObject(i);
                if (asset == null) {
                    continue;
                }
                final String name = asset.optString("name", "");
                if (!isApkAsset(name)) {
                    continue;
                }
                final String url = asset.optString("browser_download_url", "").trim();
                if (url.isEmpty()) {
                    continue;
                }
                apkName = name;
                apkUrl = url;
                apkSize = asset.optLong("size", -1L);
                break;
            }
            if (apkName != null) {
                // 发布脚本是 sha256sum 输出，附件名就是 <apk>.sha256，按名字精确配对
                final String shaName = apkName + SHA_SUFFIX;
                for (int i = 0; i < assets.length(); i++) {
                    final JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) {
                        continue;
                    }
                    if (shaName.equals(asset.optString("name", ""))) {
                        sha256Url = asset.optString("browser_download_url", "").trim();
                        break;
                    }
                }
            }
        }
        if (apkUrl == null) {
            throw new IOException("Release 中没有找到 " + APK_PREFIX + "*" + APK_SUFFIX + " 附件");
        }
        if (apkSize <= 0L) {
            // 只记录：size 缺失不影响下载（DownloadManager 自己会按实际字节数校验）
            Log.w(TAG, "APK 附件未提供有效 size：" + apkName);
        }
        return new Release(tag, notes, apkUrl, sha256Url, apkSize);
    }

    /** @return 是否为发布脚本产出的 APK 附件名（{@code virtual-motion-*.apk}）。 */
    private static boolean isApkAsset(String name) {
        return name != null && name.startsWith(APK_PREFIX) && name.endsWith(APK_SUFFIX);
    }

    /** HTTP 错误码 → 可展示的中文原因。 */
    private static String httpMessage(int code) {
        if (code == HttpURLConnection.HTTP_FORBIDDEN || code == 429) {
            return "GitHub 访问被限制（HTTP " + code + "），请稍后再试";
        }
        if (code == HttpURLConnection.HTTP_NOT_FOUND) {
            return "未找到发布信息（HTTP 404）";
        }
        return "HTTP " + code;
    }

    private static String readText(InputStream in) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(16 * 1024);
        final byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, n);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 失败原因：优先异常 message（超时 / HTTP 码 / 解析错误），没有就退回类名。 */
    private static String reason(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        final String message = t.getMessage();
        if (message != null && !message.trim().isEmpty()) {
            return message.trim();
        }
        return t.getClass().getSimpleName();
    }

    private static Context appContext(Context context) {
        if (context == null) {
            return null;
        }
        final Context app = context.getApplicationContext();
        return app != null ? app : context;
    }

    private static SharedPreferences prefs(Context context) {
        final Context app = appContext(context);
        if (app == null) {
            return null;
        }
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 回调固定回主线程；回调里抛异常只记日志，不能让后台线程崩掉进程。 */
    private static void post(final Callback callback, final boolean hasUpdate, final String tag,
                             final String notes, final String apkUrl, final String sha256Url,
                             final String error) {
        if (callback == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                callback.onResult(hasUpdate, tag, notes, apkUrl, sha256Url, error);
            } catch (Throwable t) {
                Log.w(TAG, "update callback failed", t);
            }
        });
    }

    /** GitHub Release 解析结果（内部用；回调只带必要的几个字段）。 */
    static final class Release {
        final String tag;
        final String notes;
        final String apkUrl;
        final String sha256Url;
        /** 附件字节数；-1 = 接口没给。 */
        final long apkSize;

        Release(String tag, String notes, String apkUrl, String sha256Url, long apkSize) {
            this.tag = tag;
            this.notes = notes;
            this.apkUrl = apkUrl;
            this.sha256Url = sha256Url;
            this.apkSize = apkSize;
        }
    }
}
