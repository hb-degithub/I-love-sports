package com.vloc.motion.core;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 预置视频的动作要求与时长规则（跳绳 ≥100 个、开合跳 >20 个、每段 60–120 秒）。 */
public class CameraPresetTest {

    @Test
    public void durationWindowIsSixtyTo120Seconds() {
        assertFalse("59 秒太短", CameraPreset.durationOk(59_000));
        assertTrue("60 秒刚好达标", CameraPreset.durationOk(60_000));
        assertTrue("90 秒正常", CameraPreset.durationOk(90_000));
        assertTrue("120 秒刚好达标", CameraPreset.durationOk(120_000));
        assertFalse("121 秒超长", CameraPreset.durationOk(121_000));
        assertFalse("时长未知(0)不算达标", CameraPreset.durationOk(0));
    }

    @Test
    public void formatDurationShowsMinutesAndSeconds() {
        assertEquals("时长未知", CameraPreset.formatDuration(0));
        assertEquals("1:00", CameraPreset.formatDuration(60_000));
        assertEquals("1:28", CameraPreset.formatDuration(88_000));
        assertEquals("2:00", CameraPreset.formatDuration(120_000));
    }

    @Test
    public void presetsCoverJumpRopeAndJumpingJacks() {
        List<CameraPreset.Preset> presets = CameraPreset.presets();
        assertEquals("预置两组动作", 2, presets.size());
        CameraPreset.Preset rope = presets.get(0);
        CameraPreset.Preset jacks = presets.get(1);
        assertTrue(rope.asset.contains("jump_rope"));
        assertTrue(jacks.asset.contains("jumping_jacks"));
        assertEquals("跳绳要求 100 个", 100, rope.minReps);
        assertEquals("开合跳要求 20 个", 20, jacks.minReps);
        // 要求文案必须带上"分钟"，否则用户不知道时长下限
        assertTrue(rope.requirement.contains("分钟"));
        assertTrue(jacks.requirement.contains("分钟"));
    }
}
