package com.tomilabs.lorasignalmap.model;

/**
 * One beacon of the mobile device: an "m" record of the log (docs/LOG_FORMAT.md).
 * Nullable fields use boxed types; null means "not available".
 */
public final class Measurement {
    /** Session file name this measurement belongs to. Not written to the log. */
    public String session;

    public long t;
    public String boot;
    public int seq;
    public String payload;
    public boolean ok;

    public Integer rssi;
    public Double snr;
    public Integer rrssi;
    public Double rsnr;

    public Integer rtt;
    public int crc;
    public int other;
    public Integer toa;
    public Integer txms;

    public Double lat;
    public Double lon;
    public Double alt;
    public Double acc;
    public Double spd;
    public Double brg;
    public Integer sats;
    public String pos;
    public Long fixAge;
    public Double rxLat;
    public Double rxLon;

    public String via;

    public boolean hasPosition() {
        return lat != null && lon != null;
    }

    /** True when the repeater reported its side of the link (repeater firmware v2). */
    public boolean hasRepeaterSide() {
        return rrssi != null;
    }
}
