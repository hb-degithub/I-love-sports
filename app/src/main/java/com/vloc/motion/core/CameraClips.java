package com.vloc.motion.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 虚拟摄像头的视频列表模型。
 *
 * <p>用户在相册里挑若干视频（SAF 持久化授权），悬浮窗折叠状态里可切换"当前播放哪个"。
 * 列表本身要能序列化进 SharedPreferences，也要通过 ContentProvider 交给 LSPosed 模块。
 *
 * <p>纯 Java（只依赖 org.json），可单元测试。
 */
public final class CameraClips {

    /** 一条视频。 */
    public static final class Clip {
        public final String name;
        public final String uri;
        public final long addedAt;

        public Clip(String name, String uri, long addedAt) {
            this.name = name == null ? "" : name;
            this.uri = uri == null ? "" : uri;
            this.addedAt = addedAt;
        }
    }

    private CameraClips() {
    }

    /** 解析列表；损坏的条目跳过，不抛异常。 */
    public static List<Clip> fromJson(String raw) {
        List<Clip> clips = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return clips;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String uri = o.optString("uri", "");
                if (uri.isEmpty()) {
                    continue;
                }
                clips.add(new Clip(o.optString("name", ""), uri, o.optLong("addedAt", 0L)));
            }
        } catch (JSONException ignored) {
            // 损坏则丢弃全部
        }
        return clips;
    }

    /** 序列化列表。 */
    public static String toJson(List<Clip> clips) {
        JSONArray array = new JSONArray();
        if (clips != null) {
            for (Clip clip : clips) {
                JSONObject o = new JSONObject();
                try {
                    o.put("name", clip.name);
                    o.put("uri", clip.uri);
                    o.put("addedAt", clip.addedAt);
                } catch (JSONException ignored) {
                    continue;
                }
                array.put(o);
            }
        }
        return array.toString();
    }

    /** 当前索引是否有效（列表为空或索引越界时返回 false）。 */
    public static boolean isValidIndex(List<Clip> clips, int index) {
        return clips != null && index >= 0 && index < clips.size();
    }

    /** 环形前进：返回"下一个"索引（列表为空时返回 0）。 */
    public static int nextIndex(List<Clip> clips, int index) {
        if (clips == null || clips.isEmpty()) {
            return 0;
        }
        int next = index + 1;
        return next % clips.size();
    }

    /** 把越界/负数索引收敛到合法区间（列表为空时返回 0）。 */
    public static int clampIndex(List<Clip> clips, int index) {
        if (clips == null || clips.isEmpty()) {
            return 0;
        }
        if (index < 0) {
            return (index % clips.size() + clips.size()) % clips.size();
        }
        return index % clips.size();
    }

    /** 当前视频；索引非法或列表为空时返回 null。 */
    public static Clip current(List<Clip> clips, int index) {
        return isValidIndex(clips, index) ? clips.get(index) : null;
    }

    /** 供 ContentProvider 下发的 JSON（含开关、当前索引与视频列表）。 */
    /**
     * 独立悬浮窗上的状态文案。
     *
     * <p>例：{@code 开 · 1/2 视频A}；关闭 {@code 关 · 1/2 视频A}；无视频 {@code 未添加视频}。
     *
     * @param enabled 虚拟摄像头是否开启
     * @param index   当前视频下标（0 基，越界自动收敛）
     * @param clips   视频列表
     */
    public static String overlayLabel(boolean enabled, int index, List<Clip> clips) {
        int count = clips == null ? 0 : clips.size();
        if (count == 0) {
            return "未添加视频";
        }
        int safe = clampIndex(clips, index);
        Clip clip = current(clips, safe);
        String name = clip == null ? "" : clip.name;
        String title = name == null || name.trim().isEmpty() ? "视频 " + (safe + 1) : name.trim();
        if (title.length() > 18) {
            title = title.substring(0, 17) + "…";
        }
        return String.format(java.util.Locale.US, "%s · %d/%d %s",
                enabled ? "开" : "关", safe + 1, count, title);
    }

    public static String toProviderJson(boolean enabled, boolean playing, int index,
                                        List<Clip> clips) {
        return toProviderJson(enabled, playing, index, clips, true);
    }

    /** 含 IMU 模式（相机接管期间是否固定竖直）的版本。 */
    public static String toProviderJson(boolean enabled, boolean playing, int index,
                                        List<Clip> clips, boolean imuUpright) {
        JSONObject root = new JSONObject();
        JSONArray array = new JSONArray();
        try {
            root.put("version", 1);
            root.put("enabled", enabled);
            root.put("playing", playing);
            root.put("imuUpright", imuUpright);
            root.put("index", clampIndex(clips, index));
            root.put("count", clips == null ? 0 : clips.size());
            if (clips != null) {
                for (Clip clip : clips) {
                    JSONObject o = new JSONObject();
                    o.put("name", clip.name);
                    o.put("uri", clip.uri);
                    array.put(o);
                }
            }
            root.put("clips", array);
            root.put("updatedAt", System.currentTimeMillis());
        } catch (JSONException ignored) {
            // ignore
        }
        return root.toString();
    }
}
