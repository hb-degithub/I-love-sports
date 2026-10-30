package com.vloc.motion.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 交互重构的核心规则：什么状态下显示什么控件、主按钮是什么动作。
 *
 * <p>这些规则原来散落在 Activity 里只能靠肉眼验证，现在被固定为可回归的测试。
 */
public class MotionUiStateTest {

    private static MotionUiState idle() {
        return MotionUiState.compute(MotionUiState.Mode.IDLE,
                false, false, false, 0, false, false);
    }

    @Test
    public void idleShowsEditAndSettings() {
        MotionUiState state = idle();
        assertEquals(MotionUiState.Action.START, state.primaryAction);
        assertTrue("空闲时应该能选点", state.editButtonsVisible);
        assertTrue("空闲时应该能调速度/循环", state.settingsVisible);
        assertTrue(state.speedEnabled);
        assertTrue(state.loopEnabled);
        assertFalse("空闲没有可停止的会话", state.stopEnabled);
        assertTrue(state.primaryEnabled());
    }

    @Test
    public void waitingAtStartOffersStartMove() {
        MotionUiState state = MotionUiState.compute(MotionUiState.Mode.ROUTE,
                false, true, false, 0.0, false, false);
        assertEquals(MotionUiState.Action.START_MOVE, state.primaryAction);
        assertFalse("运行中不该能改轨迹", state.editButtonsVisible);
        assertFalse("运行中不该能改速度", state.settingsVisible);
        assertTrue(state.stopEnabled);
    }

    @Test
    public void runningOffersPause() {
        MotionUiState state = MotionUiState.compute(MotionUiState.Mode.ROUTE,
                false, false, false, 120.0, false, false);
        assertEquals(MotionUiState.Action.PAUSE, state.primaryAction);
        assertFalse(state.editButtonsVisible);
        assertFalse(state.settingsVisible);
        assertTrue(state.stopEnabled);
    }

    @Test
    public void pausedOffersResume() {
        MotionUiState state = MotionUiState.compute(MotionUiState.Mode.ROUTE,
                true, false, false, 120.0, false, false);
        assertEquals(MotionUiState.Action.RESUME, state.primaryAction);
    }

    @Test
    public void arrivedOffersRestart() {
        MotionUiState state = MotionUiState.compute(MotionUiState.Mode.ROUTE,
                false, false, true, 400.0, false, false);
        assertEquals(MotionUiState.Action.RESTART, state.primaryAction);
    }

    @Test
    public void spotModeDisablesPrimary() {
        MotionUiState state = MotionUiState.compute(MotionUiState.Mode.SPOT,
                false, false, false, 0, false, false);
        assertEquals(MotionUiState.Action.SPOT, state.primaryAction);
        assertFalse("临时定位模式下按钮不可再点", state.primaryEnabled());
        assertTrue(state.stopEnabled);
    }

    @Test
    public void goalConfiguredHidesManualSpeedEvenWhenIdle() {
        MotionUiState state = MotionUiState.compute(MotionUiState.Mode.IDLE,
                false, false, false, 0, false, true);
        assertFalse("目标配速模式下只有一个速度来源", state.settingsVisible);
        assertFalse(state.speedEnabled);
        assertFalse(state.loopEnabled);
        // 但选点仍然可用
        assertTrue(state.editButtonsVisible);
    }

    @Test
    public void goalModeRunningHidesSettings() {
        MotionUiState state = MotionUiState.compute(MotionUiState.Mode.ROUTE,
                false, false, false, 500.0, true, false);
        assertFalse(state.settingsVisible);
        assertEquals(MotionUiState.Action.PAUSE, state.primaryAction);
    }

    /** 运行中必须隐藏所有"会破坏轨迹/速度"的入口。 */
    @Test
    public void runningNeverExposesEditableControls() {
        for (double traveled : new double[]{0.0, 0.4, 0.6, 50.0, 999.0}) {
            for (boolean paused : new boolean[]{true, false}) {
                MotionUiState state = MotionUiState.compute(MotionUiState.Mode.ROUTE,
                        paused, false, false, traveled, false, false);
                assertFalse("运行中不应可编辑轨迹: traveled=" + traveled,
                        state.editButtonsVisible);
                assertFalse("运行中不应可改速度: traveled=" + traveled,
                        state.settingsVisible);
                assertTrue("运行中应能彻底停止", state.stopEnabled);
            }
        }
    }
}
