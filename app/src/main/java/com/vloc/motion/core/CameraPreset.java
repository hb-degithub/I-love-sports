package com.vloc.motion.core;

import java.util.Arrays;
import java.util.List;

/** 内置预置视频的元数据（素材放在 assets/camera_presets/ 下，文件名固定）。 */
public final class CameraPreset {

    public static final class Preset {
        public final String id;
        public final String asset;
        public final String name;
        /** 动作要求，例如 "≥100 个 / >1 分钟" */
        public final String requirement;
        public final int minReps;

        Preset(String id, String asset, String name, String requirement, int minReps) {
            this.id = id;
            this.asset = asset;
            this.name = name;
            this.requirement = requirement;
            this.minReps = minReps;
        }
    }

    /** 要求：每个视频 60–120 秒。 */
    public static final long MIN_DURATION_MS = 60_000L;
    public static final long MAX_DURATION_MS = 120_000L;

    private static final List<Preset> PRESETS = Arrays.asList(
            new Preset("jump_rope", "camera_presets/jump_rope.mp4",
                    "预置·跳绳", "≥100 个 / >1 分钟", 100),
            new Preset("jumping_jacks", "camera_presets/jumping_jacks.mp4",
                    "预置·开合跳", "≥20 个 / >1 分钟", 20)
    );

    private CameraPreset() {
    }

    public static List<Preset> presets() {
        return PRESETS;
    }

    /** 时长是否满足 60–120 秒。 */
    public static boolean durationOk(long durationMs) {
        return durationMs >= MIN_DURATION_MS && durationMs <= MAX_DURATION_MS;
    }

    public static String formatDuration(long durationMs) {
        if (durationMs <= 0) {
            return "时长未知";
        }
        long seconds = durationMs / 1000L;
        return String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
}
