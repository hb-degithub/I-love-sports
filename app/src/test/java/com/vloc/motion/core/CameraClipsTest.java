package com.vloc.motion.core;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 虚拟摄像头视频列表模型：序列化、索引环绕、provider JSON 契约。 */
public class CameraClipsTest {

    private static CameraClips.Clip clip(String name, String uri) {
        return new CameraClips.Clip(name, uri, 1700000000000L);
    }

    @Test
    public void roundTripKeepsAllFields() {
        List<CameraClips.Clip> clips = new ArrayList<>();
        clips.add(clip("前摄.mp4", "content://media/external/video/media/11"));
        clips.add(clip("后摄.mp4", "content://media/external/video/media/22"));
        String json = CameraClips.toJson(clips);

        List<CameraClips.Clip> parsed = CameraClips.fromJson(json);
        assertEquals(2, parsed.size());
        assertEquals("前摄.mp4", parsed.get(0).name);
        assertEquals("content://media/external/video/media/11", parsed.get(0).uri);
        assertEquals(1700000000000L, parsed.get(0).addedAt);
    }

    @Test
    public void corruptedJsonDegradesToEmpty() {
        assertTrue(CameraClips.fromJson(null).isEmpty());
        assertTrue(CameraClips.fromJson("").isEmpty());
        assertTrue(CameraClips.fromJson("not-json").isEmpty());
        assertTrue(CameraClips.fromJson("[{\"name\":\"没有uri\"}]").isEmpty());
    }

    @Test
    public void nextIndexWrapsAndHandlesEmpty() {
        List<CameraClips.Clip> clips = new ArrayList<>();
        assertEquals(0, CameraClips.nextIndex(clips, 5));
        clips.add(clip("a", "u1"));
        clips.add(clip("b", "u2"));
        clips.add(clip("c", "u3"));
        assertEquals(1, CameraClips.nextIndex(clips, 0));
        assertEquals(2, CameraClips.nextIndex(clips, 1));
        assertEquals(0, CameraClips.nextIndex(clips, 2));
    }

    @Test
    public void clampIndexKeepsLegalRange() {
        List<CameraClips.Clip> clips = new ArrayList<>();
        clips.add(clip("a", "u1"));
        clips.add(clip("b", "u2"));
        assertEquals(0, CameraClips.clampIndex(clips, 4));
        assertEquals(1, CameraClips.clampIndex(clips, 3));
        assertEquals(1, CameraClips.clampIndex(clips, -1));
        assertEquals(0, CameraClips.clampIndex(new ArrayList<>(), 7));
        assertFalse(CameraClips.isValidIndex(clips, 2));
        assertTrue(CameraClips.isValidIndex(clips, 1));
    }

    @Test
    public void currentReturnsSelectedClipOnly() {
        List<CameraClips.Clip> clips = new ArrayList<>();
        clips.add(clip("a", "u1"));
        assertNotNull(CameraClips.current(clips, 0));
        assertNull(CameraClips.current(clips, 1));
        assertNull(CameraClips.current(new ArrayList<>(), 0));
    }

    /** 模块侧按这个 JSON 契约读取配置，字段不能改名。 */
    @Test
    public void providerJsonHasStableContract() throws Exception {
        List<CameraClips.Clip> clips = new ArrayList<>();
        clips.add(clip("a.mp4", "content://x/1"));
        clips.add(clip("b.mp4", "content://x/2"));
        String json = CameraClips.toProviderJson(true, false, 3, clips);
        JSONObject root = new JSONObject(json);
        assertEquals(1, root.getInt("version"));
        assertTrue(root.getBoolean("enabled"));
        assertFalse(root.getBoolean("playing"));
        assertTrue(root.getBoolean("imuUpright"));   // 默认接管期间 IMU 竖直
        assertEquals(1, root.getInt("index"));     // 3 环绕到 1
        assertEquals(2, root.getInt("count"));
        assertEquals(2, root.getJSONArray("clips").length());
        assertEquals("a.mp4", root.getJSONArray("clips").getJSONObject(0).getString("name"));
        assertEquals("content://x/1", root.getJSONArray("clips").getJSONObject(0).getString("uri"));
    }

    @Test
    public void overlayLabel_formatsEnabledStateAndCount() {
        java.util.List<CameraClips.Clip> clips = new java.util.ArrayList<>();
        clips.add(new CameraClips.Clip("视频A", "content://a", 1L));
        clips.add(new CameraClips.Clip("视频B", "content://b", 2L));

        org.junit.Assert.assertEquals("开 · 1/2 视频A",
                CameraClips.overlayLabel(true, 0, clips));
        org.junit.Assert.assertEquals("关 · 2/2 视频B",
                CameraClips.overlayLabel(false, 1, clips));
        // 越界下标收敛到最后一条
        org.junit.Assert.assertEquals("开 · 2/2 视频B",
                CameraClips.overlayLabel(true, 99, clips));
    }

    @Test
    public void overlayLabel_handlesEmptyAndBlankNames() {
        org.junit.Assert.assertEquals("未添加视频",
                CameraClips.overlayLabel(true, 0, java.util.Collections.emptyList()));

        java.util.List<CameraClips.Clip> clips = new java.util.ArrayList<>();
        clips.add(new CameraClips.Clip("", "content://x", 0L));
        org.junit.Assert.assertEquals("开 · 1/1 视频 1",
                CameraClips.overlayLabel(true, 0, clips));
    }

    @Test
    public void overlayLabel_truncatesLongNames() {
        java.util.List<CameraClips.Clip> clips = new java.util.ArrayList<>();
        clips.add(new CameraClips.Clip("这是一个非常非常非常长的视频文件名.mp4", "content://long", 0L));
        String label = CameraClips.overlayLabel(true, 0, clips);
        org.junit.Assert.assertTrue("过长名称应截断：" + label, label.contains("…"));
        org.junit.Assert.assertTrue("截断后仍应保留状态与序号：" + label, label.startsWith("开 · 1/1 "));
    }
}
