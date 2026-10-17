package com.tomilabs.lorasignalmap.proto;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Understands the console output of the ORIGINAL device code.py:
 * "Beacon sent" (printed when the transmission ended) and
 * "Received (ASCII): Tomi -87 dB".
 */
public final class LegacyParser {
    public interface Sink {
        void onLegacyResult(boolean ok, Integer rssi, String payload, long txStartNanos, Integer rttMs);
    }

    private static final Pattern RECEIVED = Pattern.compile("^Received \\(ASCII\\): (.*) (-?\\d+(?:\\.\\d+)?) dB$");
    /** Time on air of "Tomi" at SF12/BW125/CR4-8: the line is printed after the transmission. */
    static final long TOA_NS = 1_188_000_000L;
    static final long TIMEOUT_NS = 4_000_000_000L;

    private long pendingNanos = -1;
    private boolean active;

    /** True once this port produced a legacy line. */
    public boolean isActive() {
        return active;
    }

    public void onLine(String line, long nowNanos, Sink sink) {
        String s = line.trim();
        if (s.equals("Beacon sent")) {
            active = true;
            failPending(sink);
            pendingNanos = nowNanos;
            return;
        }
        Matcher m = RECEIVED.matcher(s);
        if (m.matches() && pendingNanos >= 0) {
            active = true;
            Integer rssi;
            try {
                rssi = (int) Math.round(Double.parseDouble(m.group(2)));
            } catch (NumberFormatException e) {
                rssi = null;
            }
            long tx = pendingNanos - TOA_NS;
            int rtt = (int) ((nowNanos - tx) / 1_000_000L);
            pendingNanos = -1;
            sink.onLegacyResult(true, rssi, m.group(1), tx, rtt);
        }
    }

    /** Call about once a second: a beacon without an echo becomes a failed result. */
    public void tick(long nowNanos, Sink sink) {
        if (pendingNanos >= 0 && nowNanos - pendingNanos > TIMEOUT_NS) {
            failPending(sink);
        }
    }

    private void failPending(Sink sink) {
        if (pendingNanos >= 0) {
            long tx = pendingNanos - TOA_NS;
            pendingNanos = -1;
            sink.onLegacyResult(false, null, null, tx, null);
        }
    }
}
