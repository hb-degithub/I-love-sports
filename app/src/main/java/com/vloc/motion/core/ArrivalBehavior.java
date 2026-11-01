package com.vloc.motion.core;

/**
 * 到达终点后的行为决策（纯 Java，可单元测试）。
 *
 * <p>背景：跑到目标距离后**不应该立刻结束模拟定位** —— 否则刚跑完就被拉回真实位置，
 * 目标 App 会看到一次跳变，轨迹记录也被截断。默认行为是：
 * <ul>
 *   <li><b>继续停留在终点</b>，按 1Hz 持续上报同一坐标（模拟"站在原地"）；</li>
 *   <li>只有用户在主界面点「彻底停止」才真正结束并恢复真实定位。</li>
 * </ul>
 *
 * <p>想要"跑完自动恢复真实定位"的用户可以在设置里打开对应开关。
 */
public final class ArrivalBehavior {

    /** 默认是否在到达后自动彻底停止并恢复真实定位：**否**。 */
    public static final boolean DEFAULT_AUTO_STOP = false;

    /** 自动停止前的停顿（毫秒）：留出时间让用户看到「已到达」。 */
    public static final long AUTO_STOP_DELAY_MS = 2500L;

    private ArrivalBehavior() {
    }

    /** 到达后是否应当自动结束模拟（由设置项决定）。 */
    public static boolean shouldAutoStop(boolean autoStopSetting) {
        return autoStopSetting;
    }

    /** 到达后悬浮窗的状态文案。 */
    public static String overlayLabel(boolean autoStop) {
        return autoStop ? "已到达终点 · 即将自动停止" : "已到达终点 · 定位保持（继续上报）";
    }

    /** 到达后通知栏的状态文案。 */
    public static String notificationLabel(boolean autoStop) {
        return autoStop ? "已到达 · 即将停止" : "已到达 · 定位保持";
    }

    /** 到达时的即时提示。 */
    public static String toastText(boolean autoStop) {
        return autoStop
                ? "已到达目标，正在自动停止…"
                : "已到达终点 · 定位保持在终点继续上报（点「彻底停止」才恢复真实位置）";
    }
}
