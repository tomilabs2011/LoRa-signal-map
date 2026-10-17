package com.tomilabs.lorasignalmap.log;

import com.tomilabs.lorasignalmap.model.DeviceInfo;
import com.tomilabs.lorasignalmap.model.Measurement;
import com.tomilabs.lorasignalmap.model.SignalMath;

/** Serialises records exactly as described in docs/LOG_FORMAT.md. */
public final class LogRecords {
    public static final String FORMAT = "lora-signal-map";
    public static final int VERSION = 1;

    private LogRecords() {
    }

    public static String header(String sessionId, long t, String tz, int tzOffsetMin, String appId,
            String appVersion, int appBuild, String make, String model, String android, int sdk,
            Double repLat, Double repLon) {
        String app = new JsonOut().str("id", appId).str("version", appVersion).num("build", (long) appBuild).toString();
        String phone = new JsonOut().str("make", make).str("model", model).str("android", android)
                .num("sdk", (long) sdk).toString();
        String repeater = (repLat != null && repLon != null)
                ? new JsonOut().dec("lat", repLat, 7).dec("lon", repLon, 7).toString()
                : null;
        return new JsonOut()
                .str("type", "header")
                .str("format", FORMAT)
                .num("version", (long) VERSION)
                .str("session", sessionId)
                .num("t", t)
                .str("tz", tz)
                .num("tz_offset_min", (long) tzOffsetMin)
                .raw("app", app)
                .raw("phone", phone)
                .raw("repeater", repeater)
                .toString();
    }

    public static String measurement(Measurement m) {
        return new JsonOut()
                .str("type", "m")
                .num("t", m.t)
                .str("boot", m.boot)
                .num("seq", (long) m.seq)
                .str("pl", m.payload)
                .bool("ok", m.ok)
                .num("rssi", m.rssi)
                .dec("snr", m.snr, 2)
                .num("rrssi", m.rrssi)
                .dec("rsnr", m.rsnr, 2)
                .dec("signal", SignalMath.linkSignal(m), 2)
                .num("rtt", m.rtt)
                .num("crc", (long) m.crc)
                .num("other", (long) m.other)
                .num("toa", m.toa)
                .num("txms", m.txms)
                .dec("lat", m.lat, 7)
                .dec("lon", m.lon, 7)
                .dec("alt", m.alt, 1)
                .dec("acc", m.acc, 1)
                .dec("spd", m.spd, 2)
                .dec("brg", m.brg, 1)
                .num("sats", m.sats)
                .str("pos", m.pos)
                .num("fix_age", m.fixAge)
                .dec("rx_lat", m.rxLat, 7)
                .dec("rx_lon", m.rxLon, 7)
                .str("via", m.via)
                .toString();
    }

    public static String device(long t, DeviceInfo d) {
        return new JsonOut()
                .str("type", "device")
                .num("t", t)
                .str("boot", d.boot)
                .str("fw", d.fw)
                .str("id", d.id)
                .dec("freq", d.freq, 3)
                .num("sf", d.sf)
                .num("bw", d.bw)
                .num("cr", d.cr)
                .num("txp", d.txp)
                .num("rxw", d.rxw)
                .str("usb", d.usb)
                .str("wifi", d.wifi)
                .str("ip", d.ip)
                .str("radio", d.radio)
                .bool("legacy", d.legacy)
                .toString();
    }

    public static String link(long t, String link, boolean up, String detail) {
        return new JsonOut()
                .str("type", "link")
                .num("t", t)
                .str("link", link)
                .str("state", up ? "up" : "down")
                .str("detail", detail)
                .toString();
    }

    public static String gps(long t, String state, Double acc) {
        return new JsonOut()
                .str("type", "gps")
                .num("t", t)
                .str("state", state)
                .dec("acc", acc, 1)
                .toString();
    }

    public static String end(long t, int count, int ok) {
        return new JsonOut()
                .str("type", "end")
                .num("t", t)
                .num("count", (long) count)
                .num("ok", (long) ok)
                .toString();
    }
}
