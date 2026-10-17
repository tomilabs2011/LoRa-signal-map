package com.tomilabs.lorasignalmap;

import android.content.Context;
import android.content.SharedPreferences;

import com.tomilabs.lorasignalmap.model.SignalMath;

import java.util.HashSet;
import java.util.Set;

/** Typed access to settings. Keys match res/xml/preferences.xml. */
public final class Prefs {
    public static final String MAP_STYLE = "map_style";
    public static final String COLOR_METRIC = "color_metric";
    public static final String COLOR_DIRECTION = "color_direction";
    public static final String SIGNAL_BEST = "signal_best";
    public static final String SIGNAL_WORST = "signal_worst";
    public static final String SNR_BEST = "snr_best";
    public static final String SNR_WORST = "snr_worst";
    public static final String SHOW_TRACK = "show_track";
    public static final String SHOW_FAILED = "show_failed";
    public static final String SHOW_HISTORY = "show_history";
    public static final String HIDDEN_SESSIONS = "hidden_sessions";
    public static final String REPEATER_LAT = "repeater_lat";
    public static final String REPEATER_LON = "repeater_lon";
    public static final String WIFI_SSID = "wifi_ssid";
    public static final String WIFI_PASS = "wifi_pass";
    public static final String WIFI_PUSH = "wifi_push";
    public static final String KEEP_SCREEN_ON = "keep_screen_on";
    public static final String SOUND = "sound_feedback";
    public static final String MAP_LAT = "map_lat";
    public static final String MAP_LON = "map_lon";
    public static final String MAP_ZOOM = "map_zoom";
    public static final String BATTERY_ASKED = "battery_asked";

    public static final String METRIC_SIGNAL = "signal";
    public static final String METRIC_SNR = "snr";

    public static final double DEF_SIGNAL_BEST = -80;
    public static final double DEF_SIGNAL_WORST = -135;
    public static final double DEF_SNR_BEST = 10;
    public static final double DEF_SNR_WORST = -20;

    /** Salgotarjan city centre: initial map position. */
    public static final double HOME_LAT = 48.0987;
    public static final double HOME_LON = 19.8036;

    private Prefs() {
    }

    public static SharedPreferences get(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(ctx.getPackageName() + "_preferences",
                Context.MODE_PRIVATE);
    }

    public static double number(SharedPreferences p, String key, double def) {
        try {
            String s = p.getString(key, null);
            if (s == null || s.trim().isEmpty()) {
                return def;
            }
            double v = Double.parseDouble(s.trim().replace(',', '.'));
            return Double.isNaN(v) || Double.isInfinite(v) ? def : v;
        } catch (RuntimeException e) {
            return def;
        }
    }

    public static Double optNumber(SharedPreferences p, String key) {
        double v = number(p, key, Double.NaN);
        return Double.isNaN(v) ? null : v;
    }

    public static String mapStyle(SharedPreferences p) {
        return p.getString(MAP_STYLE, "auto");
    }

    public static boolean colorBySnr(SharedPreferences p) {
        return METRIC_SNR.equals(p.getString(COLOR_METRIC, METRIC_SIGNAL));
    }

    public static int direction(SharedPreferences p) {
        String d = p.getString(COLOR_DIRECTION, "weakest");
        if ("down".equals(d)) {
            return SignalMath.DIR_DOWN;
        }
        if ("up".equals(d)) {
            return SignalMath.DIR_UP;
        }
        return SignalMath.DIR_WEAKEST;
    }

    /** {worst, best} for the current color metric; always worst < best. */
    public static double[] scale(SharedPreferences p) {
        double worst;
        double best;
        if (colorBySnr(p)) {
            worst = number(p, SNR_WORST, DEF_SNR_WORST);
            best = number(p, SNR_BEST, DEF_SNR_BEST);
            if (best - worst < 1) {
                worst = DEF_SNR_WORST;
                best = DEF_SNR_BEST;
            }
        } else {
            worst = number(p, SIGNAL_WORST, DEF_SIGNAL_WORST);
            best = number(p, SIGNAL_BEST, DEF_SIGNAL_BEST);
            if (best - worst < 1) {
                worst = DEF_SIGNAL_WORST;
                best = DEF_SIGNAL_BEST;
            }
        }
        return new double[]{worst, best};
    }

    public static Double[] repeater(SharedPreferences p) {
        Double lat = optNumber(p, REPEATER_LAT);
        Double lon = optNumber(p, REPEATER_LON);
        if (lat == null || lon == null || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return null;
        }
        return new Double[]{lat, lon};
    }

    public static Set<String> hiddenSessions(SharedPreferences p) {
        return new HashSet<>(p.getStringSet(HIDDEN_SESSIONS, new HashSet<String>()));
    }

    public static void setSessionHidden(SharedPreferences p, String name, boolean hidden) {
        Set<String> s = hiddenSessions(p);
        if (hidden) {
            s.add(name);
        } else {
            s.remove(name);
        }
        p.edit().putStringSet(HIDDEN_SESSIONS, s).apply();
    }
}
