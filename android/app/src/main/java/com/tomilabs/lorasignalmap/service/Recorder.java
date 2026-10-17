package com.tomilabs.lorasignalmap.service;

import com.tomilabs.lorasignalmap.gps.Fix;
import com.tomilabs.lorasignalmap.gps.PositionHistory;
import com.tomilabs.lorasignalmap.link.LinkListener;
import com.tomilabs.lorasignalmap.log.JsonOut;
import com.tomilabs.lorasignalmap.log.LogRecords;
import com.tomilabs.lorasignalmap.model.DeviceInfo;
import com.tomilabs.lorasignalmap.model.Json;
import com.tomilabs.lorasignalmap.model.Measurement;
import com.tomilabs.lorasignalmap.proto.Fnv;
import com.tomilabs.lorasignalmap.proto.LegacyParser;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Turns lines from the device into log records. Pure logic, no Android service code, so it can
 * be unit tested. Not thread-safe: call everything from one thread (the service worker).
 */
public final class Recorder {
    public interface Clock {
        long elapsedNanos();

        long wallMillis();
    }

    public interface LineSink {
        void write(String jsonLine) throws java.io.IOException;
    }

    public interface Output {
        void onMeasurement(Measurement m);

        void onDevice(DeviceInfo d);

        /** A command for the device; the service sends it over USB if possible, else Wi-Fi. */
        void sendCommand(String json);

        void onProtocolPort(String link, String port);

        void onDeviceMessage(String text);

        void onWriteError(String message);
    }

    private static final long WIFI_PUSH_INTERVAL_NS = 30_000_000_000L;

    private final Clock clock;
    private final PositionHistory positions;
    private final LineSink sink;
    private final Output out;
    private final String sessionName;
    private final Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>(512, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 4096;
        }
    };
    private final Map<String, LegacyParser> legacy = new HashMap<>();
    private final Set<String> protocolPorts = new HashSet<>();

    private DeviceInfo device;
    private String deviceSignature;
    private int legacySeq;
    private boolean writeErrorReported;
    private String wifiSsid = "";
    private String wifiPass = "";
    private boolean wifiPush;
    private long lastWifiPushNanos = Long.MIN_VALUE / 2;

    public int count;
    public int okCount;
    public int duplicates;
    public int viaUsb;
    public int viaWifi;

    public Recorder(Clock clock, PositionHistory positions, LineSink sink, Output out, String sessionName) {
        this.clock = clock;
        this.positions = positions;
        this.sink = sink;
        this.out = out;
        this.sessionName = sessionName;
    }

    /** Cheap to call repeatedly; only a real change re-arms the push. */
    public void setWifiConfig(String ssid, String password, boolean push) {
        String s = ssid == null ? "" : ssid.trim();
        String p = password == null ? "" : password;
        if (s.equals(wifiSsid) && p.equals(wifiPass) && push == wifiPush) {
            return;
        }
        wifiSsid = s;
        wifiPass = p;
        wifiPush = push;
        lastWifiPushNanos = Long.MIN_VALUE / 2;
    }

    public DeviceInfo getDevice() {
        return device;
    }

    // ------------------------------------------------------------------ input

    public void onLine(String link, String port, String line, long rxNanos) {
        String s = line.trim();
        int brace = s.indexOf('{');
        if (brace >= 0) {
            JSONObject o = null;
            try {
                o = new JSONObject(s.substring(brace));
            } catch (JSONException ignored) {
                // not JSON after all
            }
            if (o != null && Json.intOr(o, "lsm", 0) == 1 && o.has("t")) {
                if (protocolPorts.add(link + "|" + port)) {
                    out.onProtocolPort(link, port);
                }
                handle(o, link, rxNanos);
                return;
            }
        }
        if (LinkListener.USB.equals(link) && !protocolPorts.contains(link + "|" + port)) {
            LegacyParser p = legacy.get(port);
            if (p == null) {
                p = new LegacyParser();
                legacy.put(port, p);
            }
            p.onLine(s, rxNanos, legacySink);
        }
    }

    /** Call about once a second. */
    public void tick() {
        long now = clock.elapsedNanos();
        for (LegacyParser p : legacy.values()) {
            p.tick(now, legacySink);
        }
    }

    public void onLinkState(String link, boolean up, String detail) {
        write(LogRecords.link(clock.wallMillis(), link, up, detail));
    }

    public void onGpsState(String state, Double acc) {
        write(LogRecords.gps(clock.wallMillis(), state, acc));
    }

    public void finish() {
        write(LogRecords.end(clock.wallMillis(), count, okCount));
    }

    // ------------------------------------------------------------------ messages

    private void handle(JSONObject o, String link, long rxNanos) {
        String t = o.optString("t", "");
        if ("res".equals(t)) {
            handleResult(o, link, rxNanos);
        } else if ("st".equals(t)) {
            handleStatus(o);
        } else if ("ack".equals(t)) {
            String cmd = o.optString("cmd", "?");
            boolean ok = Json.bool(o, "ok", false);
            String msg = Json.str(o, "msg");
            if ("set".equals(cmd)) {
                out.onDeviceMessage(ok ? "Wi-Fi settings saved on the device"
                        : "Device rejected Wi-Fi settings" + (msg != null ? ": " + msg : ""));
            }
        } else if ("log".equals(t)) {
            String msg = Json.str(o, "msg");
            if (msg != null) {
                out.onDeviceMessage("Device: " + msg);
            }
        }
    }

    private void handleResult(JSONObject o, String link, long rxNanos) {
        String boot = Json.str(o, "boot");
        Integer seq = Json.integer(o, "seq");
        if (boot == null || seq == null) {
            return;
        }
        String key = boot + ":" + seq;
        if (seen.containsKey(key)) {
            duplicates++;
            return;
        }
        seen.put(key, Boolean.TRUE);
        Long age = Json.lng(o, "age");
        long ageMs = (age != null && age >= 0 && age < 120_000) ? age : 0;
        long txNanos = rxNanos - ageMs * 1_000_000L;

        Measurement m = new Measurement();
        m.boot = boot;
        m.seq = seq;
        m.payload = Json.str(o, "pl");
        m.ok = Json.bool(o, "ok", false);
        if (m.ok) {
            m.rssi = Json.integer(o, "rssi");
            m.snr = Json.dbl(o, "snr");
            m.rrssi = Json.integer(o, "rrssi");
            m.rsnr = Json.dbl(o, "rsnr");
            m.rtt = Json.integer(o, "rtt");
        }
        m.crc = Json.intOr(o, "crc", 0);
        m.other = Json.intOr(o, "other", 0);
        m.toa = Json.integer(o, "toa");
        m.txms = Json.integer(o, "txms");
        m.via = link;
        Long rxEchoNanos = (m.ok && m.rtt != null && m.rtt >= 0) ? txNanos + m.rtt * 1_000_000L : null;
        complete(m, txNanos, rxEchoNanos);
    }

    private final LegacyParser.Sink legacySink = new LegacyParser.Sink() {
        @Override
        public void onLegacyResult(boolean ok, Integer rssi, String payload, long txStartNanos, Integer rttMs) {
            if (device == null || !device.legacy) {
                DeviceInfo d = DeviceInfo.legacy();
                acceptDevice(d);
            }
            Measurement m = new Measurement();
            m.boot = "legacy";
            m.seq = ++legacySeq;
            m.payload = payload;
            m.ok = ok;
            m.rssi = ok ? rssi : null;
            m.rtt = ok ? rttMs : null;
            m.toa = 1188;
            m.via = LinkListener.USB;
            Long rx = (ok && rttMs != null) ? txStartNanos + rttMs * 1_000_000L : null;
            complete(m, txStartNanos, rx);
        }
    };

    private void complete(Measurement m, long txNanos, Long rxEchoNanos) {
        long nowNanos = clock.elapsedNanos();
        m.t = clock.wallMillis() - (nowNanos - txNanos) / 1_000_000L;
        m.session = sessionName;
        PositionHistory.Position p = positions.at(txNanos);
        if (p != null) {
            Fix f = p.nearest;
            m.lat = p.lat;
            m.lon = p.lon;
            m.alt = f.alt;
            m.acc = f.acc;
            m.spd = f.spd;
            m.brg = f.brg;
            m.sats = f.sats;
            m.pos = p.method;
            m.fixAge = p.fixAgeMs;
        } else {
            m.pos = "none";
        }
        if (rxEchoNanos != null) {
            PositionHistory.Position q = positions.at(rxEchoNanos);
            if (q != null) {
                m.rxLat = q.lat;
                m.rxLon = q.lon;
            }
        }
        count++;
        if (m.ok) {
            okCount++;
        }
        if (LinkListener.WIFI.equals(m.via)) {
            viaWifi++;
        } else {
            viaUsb++;
        }
        write(LogRecords.measurement(m));
        out.onMeasurement(m);
    }

    private void handleStatus(JSONObject o) {
        DeviceInfo d = DeviceInfo.fromStatus(o);
        acceptDevice(d);
        maybePushWifi(d);
    }

    private void acceptDevice(DeviceInfo d) {
        String sig = d.signature();
        if (!sig.equals(deviceSignature)) {
            deviceSignature = sig;
            write(LogRecords.device(clock.wallMillis(), d));
        }
        device = d;
        out.onDevice(d);
    }

    private void maybePushWifi(DeviceInfo d) {
        if (!wifiPush || wifiSsid.isEmpty() || d.legacy) {
            return;
        }
        if (wifiSsid.length() > 32 || (!wifiPass.isEmpty() && (wifiPass.length() < 8 || wifiPass.length() > 63))) {
            return; // invalid config; the settings screen tells the user
        }
        String expected = Fnv.wifiConfigHash(wifiSsid, wifiPass);
        if (expected.equals(d.wcfg)) {
            return;
        }
        long now = clock.elapsedNanos();
        if (now - lastWifiPushNanos < WIFI_PUSH_INTERVAL_NS) {
            return;
        }
        lastWifiPushNanos = now;
        out.sendCommand(new JsonOut().num("lsm", 1L).str("cmd", "set").str("wifi_ssid", wifiSsid)
                .str("wifi_pass", wifiPass).toString());
        out.onDeviceMessage("Sending hotspot settings to the device");
    }

    private void write(String line) {
        try {
            sink.write(line);
        } catch (java.io.IOException | RuntimeException e) {
            if (!writeErrorReported) {
                writeErrorReported = true;
                out.onWriteError("Cannot write the log: " + e.getMessage());
            }
        }
    }
}
