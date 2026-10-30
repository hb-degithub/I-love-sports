package com.vloc.motion.core;

/**
 * 主界面「运动」页底部的交互状态决策（v0.35.0 交互重构的核心）。
 *
 * <p>把"当前该显示哪些控件、主按钮该是什么动作"从 Activity 里抽出来，
 * 这样这些规则可以被单元测试覆盖，不必依赖模拟器肉眼看。
 *
 * <p>设计原则：
 * <ul>
 *   <li><b>按能做什么显示</b>：运行中不允许改轨迹/速度，所以选点与设置控件隐藏；</li>
 *   <li><b>主按钮一键到底</b>：同一个按钮在 空闲/待开始/运行/暂停/到达 下分别是
 *       开始/开始移动/暂停/继续/重新开始；</li>
 *   <li><b>目标配速模式</b>只有一个速度来源，隐藏手动速度与循环开关。</li>
 * </ul>
 *
 * 纯 Java，可单元测试。
 */
public final class MotionUiState {

    /** 底部主按钮的动作。 */
    public enum Action {
        /** 空闲：开始（定位到起点并接管定位）。 */
        START,
        /** 已在起点待开始：开始路线移动。 */
        START_MOVE,
        /** 运行中：暂停。 */
        PAUSE,
        /** 已暂停：继续。 */
        RESUME,
        /** 已到达：重新开始。 */
        RESTART,
        /** 临时定位中：按钮显示"临时定位"但不可再点。 */
        SPOT
    }

    /** 当前会话模式（与 service.Session.Mode 对应，避免 UI 层依赖 Android 类）。 */
    public enum Mode {
        IDLE, ROUTE, SPOT
    }

    public final Action primaryAction;
    /** 选点类按钮（撤销 / 清空 / 临时定位）是否可见。 */
    public final boolean editButtonsVisible;
    /** 速度滑块 / 循环开关所在的设置区是否可见。 */
    public final boolean settingsVisible;
    /** 速度滑块是否可拖动。 */
    public final boolean speedEnabled;
    /** 循环开关是否可切换。 */
    public final boolean loopEnabled;
    /** 「彻底停止」是否可点。 */
    public final boolean stopEnabled;

    private MotionUiState(Action primaryAction, boolean editButtonsVisible,
                          boolean settingsVisible, boolean speedEnabled,
                          boolean loopEnabled, boolean stopEnabled) {
        this.primaryAction = primaryAction;
        this.editButtonsVisible = editButtonsVisible;
        this.settingsVisible = settingsVisible;
        this.speedEnabled = speedEnabled;
        this.loopEnabled = loopEnabled;
        this.stopEnabled = stopEnabled;
    }

    /**
     * 计算当前应呈现的交互状态。
     *
     * @param mode            会话模式
     * @param paused          是否暂停
     * @param held            是否"停止移动但定位保持"
     * @param arrived         是否已到达终点
     * @param traveledMeters  已走里程（用于区分"还在起点"与"已走一段"）
     * @param goalMode        本次是否按目标配速运行
     * @param goalConfigured  设置里是否启用了目标配速（启用后手动速度不可见）
     */
    public static MotionUiState compute(Mode mode, boolean paused, boolean held,
                                        boolean arrived, double traveledMeters,
                                        boolean goalMode, boolean goalConfigured) {
        final boolean idle = mode == Mode.IDLE;
        final boolean hideManualSpeed = goalConfigured || goalMode;
        final boolean settingsVisible = idle && !hideManualSpeed;

        final Action action;
        if (mode == Mode.ROUTE) {
            if (held && traveledMeters <= 0.5) {
                action = Action.START_MOVE;
            } else if (arrived) {
                action = Action.RESTART;
            } else if (paused) {
                action = Action.RESUME;
            } else {
                action = Action.PAUSE;
            }
        } else if (mode == Mode.SPOT) {
            action = Action.SPOT;
        } else {
            action = Action.START;
        }

        return new MotionUiState(
                action,
                // 只有空闲时才允许改轨迹
                idle,
                settingsVisible,
                settingsVisible,
                settingsVisible,
                // 会话进行中才能"彻底停止"
                !idle);
    }

    /** 主按钮是否可点击（临时定位模式下不可再点）。 */
    public boolean primaryEnabled() {
        return primaryAction != Action.SPOT;
    }
}
