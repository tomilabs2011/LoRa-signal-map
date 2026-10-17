package com.tomilabs.lorasignalmap.model;

/** Derived link-quality values. Kept in one place so the map, the log and the docs agree. */
public final class SignalMath {
    public static final int DIR_WEAKEST = 0;
    public static final int DIR_DOWN = 1; // repeater -> phone side device
    public static final int DIR_UP = 2; // phone side device -> repeater

    private SignalMath() {
    }

    /**
     * Packet signal strength as defined by the SX1276 datasheet: below 0 dB SNR the RSSI
     * register only reflects the noise floor, so the (negative) SNR is added.
     */
    public static Double strength(Integer rssi, Double snr) {
        if (rssi == null) {
            return null;
        }
        double s = rssi;
        if (snr != null && snr < 0) {
            s += snr;
        }
        return s;
    }

    /** The "signal" field of the log: weaker of both directions, or downlink only. */
    public static Double linkSignal(Measurement m) {
        return signal(m, DIR_WEAKEST);
    }

    public static Double signal(Measurement m, int direction) {
        if (!m.ok) {
            return null;
        }
        Double down = strength(m.rssi, m.snr);
        Double up = strength(m.rrssi, m.rsnr);
        switch (direction) {
            case DIR_DOWN:
                return down != null ? down : up;
            case DIR_UP:
                return up != null ? up : down;
            default:
                if (down == null) {
                    return up;
                }
                if (up == null) {
                    return down;
                }
                return Math.min(down, up);
        }
    }

    public static Double snr(Measurement m, int direction) {
        if (!m.ok) {
            return null;
        }
        switch (direction) {
            case DIR_DOWN:
                return m.snr != null ? m.snr : m.rsnr;
            case DIR_UP:
                return m.rsnr != null ? m.rsnr : m.snr;
            default:
                if (m.snr == null) {
                    return m.rsnr;
                }
                if (m.rsnr == null) {
                    return m.snr;
                }
                return Math.min(m.snr, m.rsnr);
        }
    }

    /** Great-circle distance in metres. */
    public static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371008.8;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
