package com.tomilabs.lorasignalmap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.tomilabs.lorasignalmap.gps.Fix;
import com.tomilabs.lorasignalmap.gps.PositionHistory;
import com.tomilabs.lorasignalmap.model.DeviceInfo;
import com.tomilabs.lorasignalmap.model.Measurement;
import com.tomilabs.lorasignalmap.proto.Fnv;
import com.tomilabs.lorasignalmap.service.Recorder;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/** Runs on Robolectric so it uses Android's own org.json. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class RecorderTest {
    private static final long S = 1_000_000_000L;

    private long elapsed = 1000 * S;
    private long wall = 1_792_222_000_000L;
    private final List<String> lines = new ArrayList<>();
    private final List<Measurement> measurements = new ArrayList<>();
    private final List<String> commands = new ArrayList<>();
    private final List<String> protocolPorts = new ArrayList<>();
    private DeviceInfo device;
    private PositionHistory history;
    private Recorder rec;

    @Before
    public void setUp() {
        history = new PositionHistory();
        rec = new Recorder(new Recorder.Clock() {
            @Override
            public long elapsedNanos() {
                return elapsed;
            }

            @Override
            public long wallMillis() {
                return wall;
            }
        }, history, new Recorder.LineSink() {
            @Override
            public void write(String jsonLine) {
                lines.add(jsonLine);
            }
        }, new Recorder.Output() {
            @Override
            public void onMeasurement(Measurement m) {
                measurements.add(m);
            }

            @Override
            public void onDevice(DeviceInfo d) {
                device = d;
            }

            @Override
            public void sendCommand(String json) {
                commands.add(json);
            }

            @Override
            public void onProtocolPort(String link, String port) {
                protocolPorts.add(link + "|" + port);
            }

            @Override
            public void onDeviceMessage(String text) {
            }

            @Override
            public void onWriteError(String message) {
                throw new AssertionError(message);
            }
        }, "lsm_test.jsonl");
    }

    private void advance(double seconds) {
        elapsed += (long) (seconds * S);
        wall += (long) (seconds * 1000);
    }

    private void fix(double lat, double lon) {
        history.add(new Fix(elapsed, wall, lat, lon, 300.0, 4.0, 1.5, 90.0, 12));
    }

    @Test
    public void resultIsGeotaggedAtTransmitTimeAndDeduplicated() throws Exception {
        // GPS track: moving east 0.0001 deg per second
        for (int i = 0; i <= 6; i++) {
            fix(48.1, 19.8 + i * 0.0001);
            if (i < 6) {
                advance(1);
            }
        }
        // beacon started 3.5 s ago (age=3500), echo after 1.9 s
        String res = "{\"lsm\":1,\"t\":\"res\",\"boot\":\"abcd1234\",\"seq\":5,\"pl\":\"Tomi05\",\"toa\":1188,"
                + "\"txms\":1190,\"ok\":1,\"rssi\":-97,\"snr\":-3.5,\"rrssi\":null,\"rsnr\":null,\"rtt\":1900,"
                + "\"crc\":0,\"other\":0,\"age\":3500}";
        rec.onLine("usb", "dev#2", res, elapsed);
        rec.onLine("wifi", "192.168.43.20", res, elapsed + S / 100); // same result via Wi-Fi
        assertEquals(1, measurements.size());
        assertEquals(1, rec.duplicates);
        Measurement m = measurements.get(0);
        assertEquals(wall - 3500, m.t);
        assertEquals("usb", m.via);
        assertTrue(m.ok);
        assertEquals(Integer.valueOf(-97), m.rssi);
        assertEquals("interp", m.pos);
        assertEquals(19.8 + 2.5 * 0.0001, m.lon, 1e-9); // 6 s track, beacon at t=2.5 s
        assertEquals(19.8 + 4.4 * 0.0001, m.rxLon, 1e-9);
        assertEquals("lsm_test.jsonl", m.session);
        assertTrue(protocolPorts.contains("usb|dev#2"));
        JSONObject logged = new JSONObject(lines.get(lines.size() - 1));
        assertEquals("m", logged.getString("type"));
        assertEquals(-100.5, logged.getDouble("signal"), 1e-9);
    }

    @Test
    public void failedBeaconWithoutGpsIsStillLogged() throws Exception {
        rec.onLine("wifi", "ip", "{\"lsm\":1,\"t\":\"res\",\"boot\":\"b\",\"seq\":1,\"ok\":0,\"rssi\":null,\"age\":4000}",
                elapsed);
        assertEquals(1, measurements.size());
        Measurement m = measurements.get(0);
        assertFalse(m.ok);
        assertNull(m.rssi);
        assertFalse(m.hasPosition());
        assertEquals("none", m.pos);
        assertEquals("wifi", m.via);
        assertEquals(1, rec.count);
        assertEquals(0, rec.okCount);
    }

    @Test
    public void staleGpsIsNotUsed() {
        fix(48.1, 19.8);
        advance(60);
        rec.onLine("usb", "p", "{\"lsm\":1,\"t\":\"res\",\"boot\":\"b\",\"seq\":1,\"ok\":1,\"rssi\":-80,\"age\":1000}",
                elapsed);
        assertFalse(measurements.get(0).hasPosition());
    }

    @Test
    public void statusIsLoggedOnChangeAndWifiConfigIsPushed() throws Exception {
        rec.setWifiConfig("Hotspot", "secret123", true);
        String st = "{\"lsm\":1,\"t\":\"st\",\"boot\":\"abcd\",\"fw\":\"2.0.0\",\"id\":\"Tomi\",\"radio\":\"ok\","
                + "\"usb\":\"data\",\"wifi\":\"off\",\"ip\":null,\"wcfg\":\"\"}";
        rec.onLine("usb", "p", st, elapsed);
        rec.onLine("usb", "p", st, elapsed);
        assertNotNull(device);
        assertEquals("2.0.0", device.fw);
        int deviceRecords = 0;
        for (String l : lines) {
            if (l.contains("\"type\":\"device\"")) {
                deviceRecords++;
            }
        }
        assertEquals(1, deviceRecords);
        assertEquals(1, commands.size()); // second status within 30 s: no repeat
        JSONObject cmd = new JSONObject(commands.get(0));
        assertEquals("set", cmd.getString("cmd"));
        assertEquals("Hotspot", cmd.getString("wifi_ssid"));
        assertEquals("secret123", cmd.getString("wifi_pass"));

        // device now reports the matching hash: nothing more is sent, even later
        advance(40);
        String st2 = st.replace("\"wcfg\":\"\"", "\"wcfg\":\"" + Fnv.wifiConfigHash("Hotspot", "secret123") + "\"")
                .replace("\"wifi\":\"off\"", "\"wifi\":\"up\"");
        rec.onLine("usb", "p", st2, elapsed);
        assertEquals(1, commands.size());
    }

    @Test
    public void invalidWifiConfigIsNeverPushed() {
        rec.setWifiConfig("Hotspot", "short", true);
        rec.onLine("usb", "p", "{\"lsm\":1,\"t\":\"st\",\"boot\":\"a\",\"wcfg\":\"\"}", elapsed);
        assertTrue(commands.isEmpty());
    }

    @Test
    public void fnvMatchesFirmware() {
        // values printed by Device/code.py fnv1a_hex()
        assertEquals("e40c292c", Fnv.hex("a"));
        assertEquals("7de48b26", Fnv.hex("Hotspot\nsecret123"));
        assertEquals("", Fnv.wifiConfigHash("", "x"));
    }

    @Test
    public void legacyFirmwareConsoleIsUnderstood() {
        fix(48.1, 19.8);
        rec.onLine("usb", "console", "kaposzta", elapsed);
        advance(0.5);
        rec.onLine("usb", "console", "Beacon sent", elapsed);
        advance(1.6);
        rec.onLine("usb", "console", "Received (ASCII): Tomi -87 dB", elapsed);
        assertEquals(1, measurements.size());
        Measurement ok = measurements.get(0);
        assertTrue(ok.ok);
        assertEquals(Integer.valueOf(-87), ok.rssi);
        assertEquals("legacy", ok.boot);
        assertEquals(Integer.valueOf(1188 + 1600), ok.rtt);
        assertNotNull(device);
        assertTrue(device.legacy);

        advance(1);
        rec.onLine("usb", "console", "Beacon sent", elapsed);
        advance(4.5);
        rec.tick();
        assertEquals(2, measurements.size());
        assertFalse(measurements.get(1).ok);
        assertEquals(2, measurements.get(1).seq);
    }

    @Test
    public void legacyParsingStopsOnceAPortSpeaksTheProtocol() {
        rec.onLine("usb", "console", "{\"lsm\":1,\"t\":\"st\",\"boot\":\"x\"}", elapsed);
        rec.onLine("usb", "console", "Beacon sent", elapsed);
        advance(5);
        rec.tick();
        assertTrue(measurements.isEmpty());
    }

    @Test
    public void junkIsIgnored() {
        rec.onLine("usb", "p", "", elapsed);
        rec.onLine("usb", "p", "{", elapsed);
        rec.onLine("usb", "p", "{\"lsm\":2,\"t\":\"res\",\"boot\":\"b\",\"seq\":1}", elapsed);
        rec.onLine("usb", "p", "{\"lsm\":1,\"t\":\"res\"}", elapsed);
        rec.onLine("wifi", "p", ">>> print('hi')", elapsed);
        rec.onLine("usb", "p", "Traceback (most recent call last):", elapsed);
        assertTrue(measurements.isEmpty());
    }
}
