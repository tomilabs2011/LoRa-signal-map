package com.tomilabs.lorasignalmap.model;

import org.json.JSONObject;

/** Last status reported by the Pico ("st" message), or a synthetic one for the legacy firmware. */
public final class DeviceInfo {
    public String boot;
    public String fw;
    public String id;
    public boolean legacy;
    public String radio;
    public String radioError;
    public Double freq;
    public Integer sf;
    public Integer bw;
    public Integer cr;
    public Integer txp;
    public Integer rxw;
    public String usb;
    public String wifi;
    public String ip;
    public String ssid;
    public String wcfg;
    public String wifiError;
    public String prevError;
    public long uptimeMs;

    public static DeviceInfo fromStatus(JSONObject o) {
        DeviceInfo d = new DeviceInfo();
        d.boot = Json.str(o, "boot");
        d.fw = Json.str(o, "fw");
        d.id = Json.str(o, "id");
        d.radio = Json.str(o, "radio");
        d.radioError = Json.str(o, "rerr");
        d.freq = Json.dbl(o, "freq");
        d.sf = Json.integer(o, "sf");
        d.bw = Json.integer(o, "bw");
        d.cr = Json.integer(o, "cr");
        d.txp = Json.integer(o, "txp");
        d.rxw = Json.integer(o, "rxw");
        d.usb = Json.str(o, "usb");
        d.wifi = Json.str(o, "wifi");
        d.ip = Json.str(o, "ip");
        d.ssid = Json.str(o, "ssid");
        d.wcfg = Json.str(o, "wcfg");
        d.wifiError = Json.str(o, "werr");
        d.prevError = Json.str(o, "prev");
        Long up = Json.lng(o, "up");
        d.uptimeMs = up != null ? up : 0;
        return d;
    }

    public static DeviceInfo legacy() {
        DeviceInfo d = new DeviceInfo();
        d.boot = "legacy";
        d.fw = "original";
        d.legacy = true;
        d.radio = "ok";
        d.usb = "console";
        d.wifi = "off";
        return d;
    }

    /** Fields worth a new "device" log record when they change. */
    public String signature() {
        return boot + "|" + fw + "|" + id + "|" + radio + "|" + usb + "|" + wifi + "|" + ip + "|" + legacy;
    }
}
