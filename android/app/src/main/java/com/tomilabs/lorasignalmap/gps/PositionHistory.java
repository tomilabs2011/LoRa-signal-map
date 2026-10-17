package com.tomilabs.lorasignalmap.gps;

import java.util.ArrayList;
import java.util.List;

/**
 * Recent GPS fixes, used to find where the phone was at an arbitrary past instant
 * (beacons are reported a few seconds after they were transmitted). Thread-safe.
 */
public final class PositionHistory {
    /** Result of a lookup. */
    public static final class Position {
        public final double lat;
        public final double lon;
        public final Fix nearest;
        public final String method; // "interp" or "nearest"
        public final long fixAgeMs;

        Position(double lat, double lon, Fix nearest, String method, long fixAgeMs) {
            this.lat = lat;
            this.lon = lon;
            this.nearest = nearest;
            this.method = method;
            this.fixAgeMs = fixAgeMs;
        }
    }

    private static final long KEEP_NS = 180_000_000_000L;
    private static final long MAX_INTERP_GAP_NS = 6_000_000_000L;
    /** A fix further than this from the requested instant is not used at all. */
    public static final long MAX_FIX_AGE_MS = 30_000;

    private final List<Fix> fixes = new ArrayList<>();

    public synchronized void add(Fix f) {
        // keep sorted by time; fixes nearly always arrive in order
        int i = fixes.size();
        while (i > 0 && fixes.get(i - 1).elapsedNanos > f.elapsedNanos) {
            i--;
        }
        fixes.add(i, f);
        long cutoff = fixes.get(fixes.size() - 1).elapsedNanos - KEEP_NS;
        while (fixes.size() > 2 && fixes.get(0).elapsedNanos < cutoff) {
            fixes.remove(0);
        }
    }

    public synchronized Fix latest() {
        return fixes.isEmpty() ? null : fixes.get(fixes.size() - 1);
    }

    public synchronized void clear() {
        fixes.clear();
    }

    /** Position at the given SystemClock.elapsedRealtimeNanos() instant, or null if unknown. */
    public synchronized Position at(long elapsedNanos) {
        if (fixes.isEmpty()) {
            return null;
        }
        Fix before = null;
        Fix after = null;
        for (Fix f : fixes) {
            if (f.elapsedNanos <= elapsedNanos) {
                before = f;
            } else {
                after = f;
                break;
            }
        }
        if (before != null && after != null && after.elapsedNanos - before.elapsedNanos <= MAX_INTERP_GAP_NS) {
            double k = (double) (elapsedNanos - before.elapsedNanos) / (after.elapsedNanos - before.elapsedNanos);
            double lat = before.lat + (after.lat - before.lat) * k;
            double lon = before.lon + (after.lon - before.lon) * k;
            Fix nearest = k < 0.5 ? before : after;
            long age = Math.abs(elapsedNanos - nearest.elapsedNanos) / 1_000_000L;
            return new Position(lat, lon, nearest, "interp", age);
        }
        Fix nearest;
        if (before == null) {
            nearest = after;
        } else if (after == null) {
            nearest = before;
        } else {
            nearest = (elapsedNanos - before.elapsedNanos) <= (after.elapsedNanos - elapsedNanos) ? before : after;
        }
        long age = Math.abs(elapsedNanos - nearest.elapsedNanos) / 1_000_000L;
        if (age > MAX_FIX_AGE_MS) {
            return null;
        }
        return new Position(nearest.lat, nearest.lon, nearest, "nearest", age);
    }
}
