package com.vloc.motion.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 环境快照：设备周围基站（CellInfo）与 Wi-Fi 扫描结果，用于在目标 App 进程内回放，
 * 让基站/Wi-Fi 指纹与虚拟 GPS 位置保持一致。纯 Java（org.json），可单元测试。
 */
public final class EnvSnapshot {

    /** 一个基站/小区。type = gsm / lte / nr / wcdma / cdma。 */
    public static final class Cell {
        public String type = "gsm";
        public int mcc;
        public int mnc;
        public int lac;
        public int cid;
        public int tac;
        public int ci;
        public int pci;
        public int earfcn;
        public int bsic;
        public long nci;
        public int rssi;
        public int rsrp;
        public int rsrq;
        public int rssnr;
        public int level;
    }

    /** 一个 Wi-Fi 热点。 */
    public static final class Wifi {
        public String bssid = "";
        public String ssid = "";
        public int rssi;
        public int frequency;
    }

    public double lat;
    public double lon;
    public long updatedAt;
    public int mcc;
    public int mnc;
    public final List<Cell> cells = new ArrayList<>();
    public final List<Wifi> wifi = new ArrayList<>();

    public int cellCount() {
        return cells.size();
    }

    public int wifiCount() {
        return wifi.size();
    }

    public String toJson() {
        JSONObject root = new JSONObject();
        try {
            root.put("version", 1);
            root.put("lat", lat);
            root.put("lon", lon);
            root.put("updatedAt", updatedAt);
            root.put("mcc", mcc);
            root.put("mnc", mnc);

            JSONArray cellArray = new JSONArray();
            for (Cell c : cells) {
                JSONObject o = new JSONObject();
                o.put("type", c.type);
                o.put("mcc", c.mcc);
                o.put("mnc", c.mnc);
                putIfPositive(o, "lac", c.lac);
                putIfPositive(o, "cid", c.cid);
                putIfPositive(o, "tac", c.tac);
                putIfPositive(o, "ci", c.ci);
                putIfPositive(o, "pci", c.pci);
                putIfPositive(o, "earfcn", c.earfcn);
                putIfPositive(o, "bsic", c.bsic);
                if (c.nci > 0) {
                    o.put("nci", c.nci);
                }
                o.put("rssi", c.rssi);
                o.put("rsrp", c.rsrp);
                o.put("rsrq", c.rsrq);
                o.put("rssnr", c.rssnr);
                o.put("level", c.level);
                cellArray.put(o);
            }
            root.put("cells", cellArray);

            JSONArray wifiArray = new JSONArray();
            for (Wifi w : wifi) {
                JSONObject o = new JSONObject();
                o.put("bssid", w.bssid);
                o.put("ssid", w.ssid);
                o.put("rssi", w.rssi);
                o.put("frequency", w.frequency);
                wifiArray.put(o);
            }
            root.put("wifi", wifiArray);
        } catch (JSONException e) {
            // 字段都是基本类型，理论上不会发生
        }
        return root.toString();
    }

    private static void putIfPositive(JSONObject o, String key, int value) throws JSONException {
        if (value > 0) {
            o.put(key, value);
        }
    }

    /** 解析 JSON，任何字段缺失都按 0/空处理；非法输入返回 null。 */
    public static EnvSnapshot fromJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(raw);
            EnvSnapshot snapshot = new EnvSnapshot();
            snapshot.lat = root.optDouble("lat", 0);
            snapshot.lon = root.optDouble("lon", 0);
            snapshot.updatedAt = root.optLong("updatedAt", 0);
            snapshot.mcc = root.optInt("mcc", 0);
            snapshot.mnc = root.optInt("mnc", 0);

            JSONArray cellArray = root.optJSONArray("cells");
            if (cellArray != null) {
                for (int i = 0; i < cellArray.length(); i++) {
                    JSONObject o = cellArray.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    Cell c = new Cell();
                    c.type = o.optString("type", "gsm");
                    c.mcc = o.optInt("mcc", snapshot.mcc);
                    c.mnc = o.optInt("mnc", snapshot.mnc);
                    c.lac = o.optInt("lac");
                    c.cid = o.optInt("cid");
                    c.tac = o.optInt("tac");
                    c.ci = o.optInt("ci");
                    c.pci = o.optInt("pci");
                    c.earfcn = o.optInt("earfcn");
                    c.bsic = o.optInt("bsic");
                    c.nci = o.optLong("nci");
                    c.rssi = o.optInt("rssi");
                    c.rsrp = o.optInt("rsrp");
                    c.rsrq = o.optInt("rsrq");
                    c.rssnr = o.optInt("rssnr");
                    c.level = o.optInt("level");
                    snapshot.cells.add(c);
                }
            }

            JSONArray wifiArray = root.optJSONArray("wifi");
            if (wifiArray != null) {
                for (int i = 0; i < wifiArray.length(); i++) {
                    JSONObject o = wifiArray.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    Wifi w = new Wifi();
                    w.bssid = o.optString("bssid", "");
                    w.ssid = o.optString("ssid", "");
                    w.rssi = o.optInt("rssi");
                    w.frequency = o.optInt("frequency");
                    snapshot.wifi.add(w);
                }
            }
            return snapshot;
        } catch (JSONException e) {
            return null;
        }
    }
}
