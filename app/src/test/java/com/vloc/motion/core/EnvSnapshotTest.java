package com.vloc.motion.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EnvSnapshotTest {

    @Test
    public void jsonRoundTripKeepsCellsAndWifi() {
        EnvSnapshot snapshot = new EnvSnapshot();
        snapshot.lat = 39.9042;
        snapshot.lon = 116.4074;
        snapshot.updatedAt = 1730000000000L;
        snapshot.mcc = 460;
        snapshot.mnc = 0;

        EnvSnapshot.Cell gsm = new EnvSnapshot.Cell();
        gsm.type = "gsm";
        gsm.mcc = 460;
        gsm.mnc = 0;
        gsm.lac = 1234;
        gsm.cid = 5678;
        gsm.bsic = 12;
        gsm.rssi = -70;
        snapshot.cells.add(gsm);

        EnvSnapshot.Wifi wifi = new EnvSnapshot.Wifi();
        wifi.bssid = "aa:bb:cc:dd:ee:ff";
        wifi.ssid = "CMCC-Free";
        wifi.rssi = -60;
        wifi.frequency = 2412;
        snapshot.wifi.add(wifi);

        EnvSnapshot parsed = EnvSnapshot.fromJson(snapshot.toJson());
        assertNotNull(parsed);
        assertEquals(39.9042, parsed.lat, 1e-9);
        assertEquals(116.4074, parsed.lon, 1e-9);
        assertEquals(460, parsed.mcc);
        assertEquals(1, parsed.cellCount());
        assertEquals(1, parsed.wifiCount());
        assertEquals("gsm", parsed.cells.get(0).type);
        assertEquals(1234, parsed.cells.get(0).lac);
        assertEquals(5678, parsed.cells.get(0).cid);
        assertEquals("aa:bb:cc:dd:ee:ff", parsed.wifi.get(0).bssid);
    }

    @Test
    public void invalidJsonReturnsNull() {
        assertNull(EnvSnapshot.fromJson(null));
        assertNull(EnvSnapshot.fromJson(""));
        assertNull(EnvSnapshot.fromJson("not json"));
    }

    @Test
    public void missingOptionalFieldsDefaultToZero() {
        EnvSnapshot parsed = EnvSnapshot.fromJson(
                "{\"version\":1,\"lat\":1.5,\"lon\":2.5,\"cells\":[{\"type\":\"lte\"}],\"wifi\":[]}");
        assertNotNull(parsed);
        assertEquals(1, parsed.cellCount());
        assertEquals(0, parsed.cells.get(0).ci);
        assertEquals(0, parsed.cells.get(0).rsrp);
        assertTrue(parsed.wifi.isEmpty());
    }

    @Test
    public void emptySnapshotSerializes() {
        EnvSnapshot snapshot = new EnvSnapshot();
        assertNotNull(EnvSnapshot.fromJson(snapshot.toJson()));
    }
}
