package com.tomilabs.lorasignalmap.ui;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Display formatting. Uses a real minus sign for negative values. */
public final class Fmt {
    private Fmt() {
    }

    public static String num(double v, int decimals) {
        String s = String.format(Locale.US, "%." + decimals + "f", Math.abs(v));
        if (decimals > 0) {
            while (s.endsWith("0")) {
                s = s.substring(0, s.length() - 1);
            }
            if (s.endsWith(".")) {
                s = s.substring(0, s.length() - 1);
            }
        }
        boolean zero = s.matches("0(\\.0*)?");
        return (v < 0 && !zero ? "−" : "") + s;
    }

    public static String dbm(double v) {
        return num(v, 1) + " dBm";
    }

    public static String db(double v) {
        return num(v, 2) + " dB";
    }

    public static String percent(int part, int total) {
        if (total <= 0) {
            return "–";
        }
        return Math.round(100.0 * part / total) + "%";
    }

    public static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        }
        return String.format(Locale.US, "%d:%02d", m, sec);
    }

    public static String distance(double meters) {
        if (meters < 1000) {
            return Math.round(meters) + " m";
        }
        return String.format(Locale.US, "%.2f km", meters / 1000.0);
    }

    public static String time(long t) {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(t));
    }

    /** "14:03:22" for today, otherwise "2026-10-17 14:03". */
    public static String when(long t) {
        Calendar now = Calendar.getInstance();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)) {
            return time(t);
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(t));
    }

    public static String dateTime(long t) {
        return new SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault()).format(new Date(t));
    }

    public static String ago(long ms) {
        long s = ms / 1000;
        if (s < 60) {
            return s + " s ago";
        }
        if (s < 3600) {
            return (s / 60) + " min ago";
        }
        return (s / 3600) + " h ago";
    }
}
