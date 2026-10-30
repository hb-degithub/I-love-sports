package com.vloc.motion.service;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 系统级注入的数据契约：模块在 system_server 里按这个 JSON 改写所有 App 的定位，
 * 字段名与含义不能随便改。
 */
public class LocationSessionTest {

    @Test
    public void inactiveByDefault() throws Exception {
        LocationSession session = new LocationSession();
        assertFalse(session.active);
        JSONObject json = new JSONObject(session.toJson());
        assertFalse(json.getBoolean("active"));
        assertEquals(0L, json.getLong("updatedAt"));
    }

    @Test
    public void updateKeepsAllFields() throws Exception {
        LocationSession session = new LocationSession();
        session.update(38.8947, 121.5286, 50.5, 3.33f, 167.5f, 5f, 42);

        JSONObject json = new JSONObject(session.toJson());
        assertTrue(json.getBoolean("active"));
        assertEquals(38.8947, json.getDouble("lat"), 1e-7);
        assertEquals(121.5286, json.getDouble("lon"), 1e-7);
        assertEquals(50.5, json.getDouble("alt"), 1e-6);
        assertEquals(3.33, json.getDouble("speed"), 1e-3);
        assertEquals(167.5, json.getDouble("bearing"), 1e-3);
        assertEquals(5.0, json.getDouble("acc"), 1e-3);
        assertEquals(42, json.getInt("satellites"));
        assertTrue(json.getLong("updatedAt") > 0L);
    }

    @Test
    public void clearStopsInjection() throws Exception {
        LocationSession session = new LocationSession();
        session.update(1, 2, 3, 4, 5, 6, 7);
        session.clear();
        JSONObject json = new JSONObject(session.toJson());
        assertFalse("停止模拟后模块必须透传真实定位", json.getBoolean("active"));
        assertEquals(0L, json.getLong("updatedAt"));
    }

    @Test
    public void coordinatesKeepSevenDecimalsPrecision() throws Exception {
        // 0.0000001° ≈ 1.1cm，系统级注入不能丢精度
        LocationSession session = new LocationSession();
        session.update(38.8947001, 121.5286001, 50, 0, 0, 5, 0);
        JSONObject json = new JSONObject(session.toJson());
        assertEquals(38.8947001, json.getDouble("lat"), 1e-7);
        assertEquals(121.5286001, json.getDouble("lon"), 1e-7);
    }
}
