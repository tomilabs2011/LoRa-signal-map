package com.tomilabs.lorasignalmap.gps;

import android.location.Location;
import android.os.SystemClock;

/** Immutable copy of a GPS fix, timestamped on the elapsedRealtime clock. */
public final class Fix {
    public final long elapsedNanos;
    public final long utcMillis;
    public final double lat;
    public final double lon;
    public final Double alt;
    public final Double acc;
    public final Double spd;
    public final Double brg;
    public final Integer sats;

    public Fix(long elapsedNanos, long utcMillis, double lat, double lon, Double alt, Double acc, Double spd,
            Double brg, Integer sats) {
        this.elapsedNanos = elapsedNanos;
        this.utcMillis = utcMillis;
        this.lat = lat;
        this.lon = lon;
        this.alt = alt;
        this.acc = acc;
        this.spd = spd;
        this.brg = brg;
        this.sats = sats;
    }

    public static Fix from(Location l, Integer sats) {
        Double brg = l.hasBearing() ? (double) l.getBearing() : null;
        Double spd = l.hasSpeed() ? (double) l.getSpeed() : null;
        Double acc = l.hasAccuracy() ? (double) l.getAccuracy() : null;
        Double alt = l.hasAltitude() ? l.getAltitude() : null;
        Integer usedSats = sats;
        if (usedSats == null && l.getExtras() != null && l.getExtras().containsKey("satellites")) {
            usedSats = l.getExtras().getInt("satellites");
        }
        long elapsed = l.getElapsedRealtimeNanos();
        if (elapsed <= 0) {
            elapsed = SystemClock.elapsedRealtimeNanos();
        }
        return new Fix(elapsed, l.getTime(), l.getLatitude(), l.getLongitude(), alt, acc, spd, brg, usedSats);
    }
}
