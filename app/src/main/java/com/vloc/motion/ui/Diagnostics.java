package com.vloc.motion.ui;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import com.vloc.motion.core.TileSources;
import com.vloc.motion.location.RootShell;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.modules.NetworkAvailabliltyCheck;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 自带诊断：地图瓦片可达性 & Root 通道。用于用户界面里一项项排查。 */
public final class Diagnostics {

    private Diagnostics() {
    }

    /** 地图诊断报告。 */
    public static String mapReport(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append(networkLine(context)).append('\n')
                .append("osmdroid UA：")
                .append(Configuration.getInstance().getUserAgentValue()).append('\n')
                .append("osmdroid 网络检查：").append(osmdroidNetworkCheck(context))
                .append("（已放宽检查=").append(TileProviderTuner.isApplied()).append("）\n")
                .append("瓦片缓存目录：")
                .append(Configuration.getInstance().getOsmdroidTileCache()).append('\n')
                .append("本地瓦片缓存：").append(countCacheFiles(context)).append(" 个文件\n")
                .append("系统时间：").append(new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US)
                        .format(new java.util.Date())).append("\n\n");
        int zoom = 16;
        int x = tileX(116.4074, zoom);
        int y = tileY(39.9042, zoom);
        for (TileSources.Source source : TileSources.all()) {
            String url = source.probeUrl
                    .replace("{x}", String.valueOf(x))
                    .replace("{y}", String.valueOf(y))
                    .replace("{z}", String.valueOf(zoom));
            sb.append("· ").append(source.label).append('\n')
                    .append("  ").append(probe(url, context)).append('\n');
        }
        return sb.toString();
    }

    private static String networkLine(Context context) {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return "网络：无法获取状态";
        }
        Network active = cm.getActiveNetwork();
        if (active == null) {
            return "网络：未连接（设备当前没有可用网络）";
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        boolean internet = caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        boolean validated = caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        return "网络：已连接"
                + (internet ? " · 声明有互联网" : " · 无互联网能力")
                + (validated ? " · 已验证可上网" : " · 未通过联网验证（可能被墙/需认证）");
    }

    private static int probeCode(String url, Context context, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", context.getPackageName() + " diag");
            return connection.getResponseCode();
        } catch (Exception e) {
            return -1;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String probe(String url, Context context) {
        HttpURLConnection connection = null;
        try {
            long start = System.currentTimeMillis();
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", context.getPackageName() + " diag");
            int code = connection.getResponseCode();
            String type = connection.getContentType();
            InputStream stream = code >= 400 ? connection.getErrorStream()
                    : connection.getInputStream();
            int total = 0;
            if (stream != null) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = stream.read(buffer)) > 0 && total < 200000) {
                    total += read;
                }
                stream.close();
            }
            return "HTTP " + code + (type == null ? "" : " · " + type) + " · " + total
                    + " B · " + (System.currentTimeMillis() - start) + " ms";
        } catch (Exception e) {
            return "失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 指定源是否可达（2xx）。 */
    public static boolean isSourceReachable(Context context, String sourceId) {
        TileSources.Source source = TileSources.byId(sourceId);
        int zoom = 10;
        String url = source.probeUrl
                .replace("{x}", String.valueOf(tileX(116.4074, zoom)))
                .replace("{y}", String.valueOf(tileY(39.9042, zoom)))
                .replace("{z}", String.valueOf(zoom));
        int code = probeCode(url, context, 3500);
        return code >= 200 && code < 300;
    }

    /** 依次探测各瓦片源，返回第一个 2xx 的源 id；全部失败返回 null。 */
    public static String pickReachableSource(Context context) {
        int zoom = 10;
        int x = tileX(116.4074, zoom);
        int y = tileY(39.9042, zoom);
        for (TileSources.Source source : TileSources.all()) {
            String url = source.probeUrl
                    .replace("{x}", String.valueOf(x))
                    .replace("{y}", String.valueOf(y))
                    .replace("{z}", String.valueOf(zoom));
            int code = probeCode(url, context, 3500);
            if (code >= 200 && code < 300) {
                return source.id;
            }
        }
        return null;
    }

    /** 当前瓦片缓存文件数（用于判断"探测可达但实际没下载到瓦片"）。 */
    public static int cachedTileCount(Context context) {
        return countCacheFiles(context);
    }

    private static String osmdroidNetworkCheck(Context context) {
        try {
            return String.valueOf(new NetworkAvailabliltyCheck(context).getNetworkAvailable());
        } catch (Throwable t) {
            return "异常：" + t;
        }
    }

    private static int countCacheFiles(Context context) {
        java.io.File dir = new java.io.File(context.getCacheDir(), "osmdroid/tiles");
        if (!dir.isDirectory()) {
            return 0;
        }
        int count = 0;
        java.util.ArrayDeque<java.io.File> queue = new java.util.ArrayDeque<>();
        queue.add(dir);
        while (!queue.isEmpty() && count < 5000) {
            java.io.File file = queue.poll();
            java.io.File[] children = file.listFiles();
            if (children == null) {
                continue;
            }
            for (java.io.File child : children) {
                if (child.isDirectory()) {
                    queue.add(child);
                } else {
                    count++;
                }
            }
        }
        return count;
    }

    /** Root 诊断报告。 */
    public static String rootReport(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("机型：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" · Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");

        String[][] managers = {
                {"com.topjohnwu.magisk", "Magisk"},
                {"io.github.vvb2060.magisk", "Magisk Alpha"},
                {"me.weishu.kernelsu", "KernelSU"},
                {"me.bmax.apatch", "APatch"}
        };
        StringBuilder found = new StringBuilder();
        for (String[] manager : managers) {
            try {
                context.getPackageManager().getPackageInfo(manager[0], 0);
                if (found.length() > 0) {
                    found.append(", ");
                }
                found.append(manager[1]);
            } catch (Exception ignored) {
                // 未安装
            }
        }
        sb.append("Root 管理器：")
                .append(found.length() == 0 ? "未检测到（可能未安装或包名被隐藏）" : found)
                .append('\n');

        String[] paths = {"su", "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su"};
        sb.append("su 路径：");
        boolean any = false;
        for (String path : paths) {
            if (new File(path).exists()) {
                sb.append(path).append(' ');
                any = true;
            }
        }
        if (!any) {
            sb.append("均不可见（PATH 中的 su 无法用 File 判断，以下方实际执行结果为准）");
        }
        sb.append('\n');

        RootShell.Result result = RootShell.su("id", 30000L);
        sb.append("su -c id：exit=").append(result.exitCode)
                .append(result.timedOut ? "（超时，可能授权弹窗未响应）" : "")
                .append("\nstdout：").append(result.stdout.trim().isEmpty()
                        ? "(空)" : result.stdout.trim())
                .append("\nstderr：").append(result.stderr.trim().isEmpty()
                        ? "(空)" : result.stderr.trim());
        return sb.toString();
    }

    private static int tileX(double lon, int zoom) {
        return (int) Math.floor((lon + 180.0) / 360.0 * (1 << zoom));
    }

    private static int tileY(double lat, int zoom) {
        double rad = Math.toRadians(lat);
        return (int) Math.floor((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI)
                / 2 * (1 << zoom));
    }
}
