package com.vloc.motion.service;

/**
 * 虚拟摄像头运行时状态（模块在目标 App 进程内上报，主 App 控制页展示）。
 *
 * <p>用来在不开 logcat 的情况下判断接管走到哪一步：
 * 模块是否注入 → hook 装了几个 → 是否接管到相机会话 → 是否开始渲染 → 最后错误。
 */
public final class CameraSession {

    public static final CameraSession INSTANCE = new CameraSession();

    /** 模块上报时间（0 = 从未上报，说明模块没注入或 Provider 读不到）。 */
    public volatile long reportAt;
    /** 装上的 camera hook 数。 */
    public volatile int hooks;
    /** 最近一次接管相机会话的时间（0 = 没有）。 */
    public volatile long sessionAt;
    /** 最近一次开始渲染视频的时间（0 = 没有）。 */
    public volatile long renderAt;
    /** 最近一次错误（空 = 无错误）。 */
    public volatile String lastError = "";
    /** 目标 App 包名 / 进程名（模块上报）。 */
    public volatile String packageName = "";
    /** 最近一次事件（例如 "opened gps" / "camera1 open"），用于区分"相机打开了但会话没拦住"。 */
    public volatile String lastEvent = "";
    /** 视频播放进度（毫秒）/ 总时长（毫秒），模块每秒上报。 */
    public volatile long positionMs;
    public volatile long durationMs;

    private CameraSession() {
    }

    public void report(String packageName, int hooks, boolean session, boolean render,
                       String error) {
        report(packageName, hooks, session, render, error, null);
    }

    public void report(String packageName, int hooks, boolean session, boolean render,
                       String error, String event) {
        this.packageName = packageName == null ? "" : packageName;
        this.hooks = hooks;
        long now = System.currentTimeMillis();
        this.reportAt = now;
        if (session) {
            this.sessionAt = now;
        }
        if (render) {
            this.renderAt = now;
        }
        if (error != null && !error.isEmpty()) {
            this.lastError = error;
        }
        if (event != null && !event.isEmpty()) {
            this.lastEvent = event;
        }
    }

    /** 模块上报播放进度（不改变接管/错误状态）。 */
    public void reportProgress(long positionMs, long durationMs) {
        this.positionMs = Math.max(0L, positionMs);
        this.durationMs = Math.max(0L, durationMs);
        this.reportAt = System.currentTimeMillis();
    }

    public void clear() {
        reportAt = 0;
        hooks = 0;
        sessionAt = 0;
        renderAt = 0;
        lastError = "";
        lastEvent = "";
        packageName = "";
        positionMs = 0L;
        durationMs = 0L;
    }

    /** 供主 App 查询的 JSON。 */
    public String toJson() {
        return String.format(java.util.Locale.US,
                "{\"pkg\":\"%s\",\"hooks\":%d,\"reportAt\":%d,\"sessionAt\":%d,"
                        + "\"renderAt\":%d,\"error\":\"%s\",\"event\":\"%s\","
                        + "\"positionMs\":%d,\"durationMs\":%d}",
                jsonEscape(packageName), hooks, reportAt, sessionAt, renderAt,
                jsonEscape(lastError), jsonEscape(lastEvent), positionMs, durationMs);
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
