package com.vloc.motion.service;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import com.vloc.motion.core.CameraClips;
import com.vloc.motion.core.CameraPreset;
import com.vloc.motion.core.RouteStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 预置视频安装与时长校验。
 *
 * <p>素材放在 APK 的 {@code assets/camera_presets/*.mp4}；首次启动复制到
 * {@code files/camera_presets/} 并加入视频列表（名字前缀「预置·」，带时长与要求说明）。
 */
public final class PresetVideos {

    private PresetVideos() {
    }

    /** 后台把内置素材装进视频列表（幂等；素材缺失时静默跳过）。 */
    public static void sync(Context context) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                List<CameraClips.Clip> clips = RouteStore.loadCameraClips(app);
                boolean changed = false;
                for (CameraPreset.Preset preset : CameraPreset.presets()) {
                    if (containsPreset(clips, preset.id)) {
                        continue;
                    }
                    try (InputStream in = app.getAssets().open(preset.asset)) {
                        File dir = new File(app.getFilesDir(), "camera_presets");
                        if (!dir.exists() && !dir.mkdirs()) {
                            continue;
                        }
                        File out = new File(dir, preset.id + ".mp4");
                        try (OutputStream os = new FileOutputStream(out)) {
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = in.read(buffer)) > 0) {
                                os.write(buffer, 0, read);
                            }
                        }
                        long duration = durationMs(app, Uri.fromFile(out));
                        String name = preset.name + "（" + preset.requirement + " · "
                                + CameraPreset.formatDuration(duration)
                                + (CameraPreset.durationOk(duration) ? "" : " ⚠ 时长需 60–120 秒") + "）";
                        clips.add(0, new CameraClips.Clip(name, Uri.fromFile(out).toString(),
                                System.currentTimeMillis()));
                        changed = true;
                    } catch (Exception ignored) {
                        // 素材缺失/复制失败：跳过，不影响其它视频
                    }
                }
                if (changed) {
                    RouteStore.saveCameraClips(app, clips);
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }, "VLocPresetVideos").start();
    }

    private static boolean containsPreset(List<CameraClips.Clip> clips, String id) {
        for (CameraClips.Clip clip : clips) {
            if (clip.uri != null && clip.uri.contains("/camera_presets/" + id)) {
                return true;
            }
        }
        return false;
    }

    /** 读取视频时长（毫秒）；失败返回 0。 */
    public static long durationMs(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0L : Long.parseLong(value);
        } catch (Throwable t) {
            return 0L;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }
}
