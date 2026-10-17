package com.tomilabs.lorasignalmap.log;

import com.tomilabs.lorasignalmap.model.Json;
import com.tomilabs.lorasignalmap.model.Measurement;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/** Tolerant reader for session logs: bad or half-written lines are skipped, never fatal. */
public final class SessionReader {
    private static final int MAX_LINE = 64 * 1024;

    private SessionReader() {
    }

    public static Session read(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            Session s = read(in, file.getName());
            s.fileLength = file.length();
            s.fileModified = file.lastModified();
            return s;
        } finally {
            in.close();
        }
    }

    public static Session read(InputStream in, String name) throws IOException {
        Session s = new Session();
        s.fileName = name;
        BufferedReader r = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")), 16384);
        String line;
        boolean firstRecord = true;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.length() > MAX_LINE || line.charAt(0) != '{') {
                s.badLines++;
                continue;
            }
            JSONObject o;
            try {
                o = new JSONObject(line);
            } catch (JSONException e) {
                s.badLines++;
                continue;
            }
            String type = o.optString("type", "");
            Long t = Json.lng(o, "t");
            if (firstRecord) {
                firstRecord = false;
                if ("header".equals(type) && LogRecords.FORMAT.equals(o.optString("format"))) {
                    s.validHeader = true;
                    s.sessionId = Json.str(o, "session");
                    s.startT = t != null ? t : 0;
                    JSONObject app = o.optJSONObject("app");
                    s.appVersion = Json.str(app, "version");
                    JSONObject rep = o.optJSONObject("repeater");
                    s.repeaterLat = Json.dbl(rep, "lat");
                    s.repeaterLon = Json.dbl(rep, "lon");
                    continue;
                }
            }
            if (t != null && t > s.lastT) {
                s.lastT = t;
            }
            if ("m".equals(type)) {
                Measurement m = parseMeasurement(o);
                if (m != null) {
                    m.session = name;
                    s.measurements.add(m);
                    if (s.startT == 0) {
                        s.startT = m.t;
                    }
                }
            } else if ("device".equals(type)) {
                String fw = Json.str(o, "fw");
                if (fw != null) {
                    s.deviceFw = fw;
                }
            } else if ("end".equals(type)) {
                s.ended = true;
            }
        }
        return s;
    }

    public static Measurement parseMeasurement(JSONObject o) {
        Long t = Json.lng(o, "t");
        if (t == null) {
            return null;
        }
        Measurement m = new Measurement();
        m.t = t;
        m.boot = Json.str(o, "boot");
        m.seq = Json.intOr(o, "seq", 0);
        m.payload = Json.str(o, "pl");
        m.ok = Json.bool(o, "ok", false);
        m.rssi = Json.integer(o, "rssi");
        m.snr = Json.dbl(o, "snr");
        m.rrssi = Json.integer(o, "rrssi");
        m.rsnr = Json.dbl(o, "rsnr");
        m.rtt = Json.integer(o, "rtt");
        m.crc = Json.intOr(o, "crc", 0);
        m.other = Json.intOr(o, "other", 0);
        m.toa = Json.integer(o, "toa");
        m.txms = Json.integer(o, "txms");
        m.lat = Json.dbl(o, "lat");
        m.lon = Json.dbl(o, "lon");
        if (m.lat != null && (m.lat < -90 || m.lat > 90)) {
            m.lat = null;
        }
        if (m.lon != null && (m.lon < -180 || m.lon > 180)) {
            m.lon = null;
        }
        if (m.lat == null || m.lon == null) {
            m.lat = null;
            m.lon = null;
        }
        m.alt = Json.dbl(o, "alt");
        m.acc = Json.dbl(o, "acc");
        m.spd = Json.dbl(o, "spd");
        m.brg = Json.dbl(o, "brg");
        m.sats = Json.integer(o, "sats");
        m.pos = Json.str(o, "pos");
        m.fixAge = Json.lng(o, "fix_age");
        m.rxLat = Json.dbl(o, "rx_lat");
        m.rxLon = Json.dbl(o, "rx_lon");
        m.via = Json.str(o, "via");
        return m;
    }

    /** Cheap check used when importing: is the first non-empty line our header? */
    public static boolean looksLikeSessionLog(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")));
        String line;
        int read = 0;
        while ((line = r.readLine()) != null && read < 5) {
            read++;
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                JSONObject o = new JSONObject(line);
                return "header".equals(o.optString("type")) && LogRecords.FORMAT.equals(o.optString("format"));
            } catch (JSONException e) {
                return false;
            }
        }
        return false;
    }
}
