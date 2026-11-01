package com.vloc.motion.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 到达终点后的行为：**默认不结束模拟定位**。
 *
 * <p>这是修复过的行为（曾经到达后 2.5 秒自动恢复真实定位，导致轨迹被截断、
 * 目标 App 看到一次跳变）。这组测试防止默认值被改回 true。
 */
public class ArrivalBehaviorTest {

    @Test
    public void defaultIsKeepLocationNotAutoStop() {
        assertFalse("默认必须保持定位（不自动结束模拟）", ArrivalBehavior.DEFAULT_AUTO_STOP);
        assertFalse(ArrivalBehavior.shouldAutoStop(ArrivalBehavior.DEFAULT_AUTO_STOP));
    }

    @Test
    public void autoStopOnlyWhenExplicitlyEnabled() {
        assertTrue("用户明确打开时才自动停止", ArrivalBehavior.shouldAutoStop(true));
        assertFalse(ArrivalBehavior.shouldAutoStop(false));
    }

    @Test
    public void overlayLabelDistinguishesBothModes() {
        String keep = ArrivalBehavior.overlayLabel(false);
        String stop = ArrivalBehavior.overlayLabel(true);
        assertTrue("保持模式下应说明继续上报: " + keep, keep.contains("保持"));
        assertTrue("自动停止模式下应预告即将停止: " + stop, stop.contains("自动停止"));
        assertFalse("两种文案不能相同", keep.equals(stop));
    }

    @Test
    public void notificationLabelDistinguishesBothModes() {
        String keep = ArrivalBehavior.notificationLabel(false);
        String stop = ArrivalBehavior.notificationLabel(true);
        assertTrue("保持模式: " + keep, keep.contains("定位保持"));
        assertTrue("停止模式: " + stop, stop.contains("停止"));
        assertFalse(keep.equals(stop));
        // 到达后不应再显示"已暂停"（那是暂停语义，不是到达）
        assertFalse("到达不等于暂停: " + keep, keep.contains("已暂停"));
    }

    @Test
    public void toastTellsUserFakeLocationPersists() {
        String keep = ArrivalBehavior.toastText(false);
        assertTrue("必须告诉用户定位还在: " + keep, keep.contains("定位保持"));
        assertTrue("必须说明如何真正结束: " + keep, keep.contains("彻底停止"));
        String stop = ArrivalBehavior.toastText(true);
        assertTrue("自动停止模式应给出预告: " + stop, stop.contains("自动停止"));
    }

    @Test
    public void autoStopDelayIsEnoughToSeeArrival() {
        // 太短用户看不到「已到达」，太长会显得卡住
        assertTrue("延迟应 >= 1s", ArrivalBehavior.AUTO_STOP_DELAY_MS >= 1000L);
        assertTrue("延迟应 <= 5s", ArrivalBehavior.AUTO_STOP_DELAY_MS <= 5000L);
    }
}
