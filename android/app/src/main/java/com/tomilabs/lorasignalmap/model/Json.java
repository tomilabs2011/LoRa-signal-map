package com.tomilabs.lorasignalmap.model;

import org.json.JSONObject;

/** Null-tolerant readers for org.json objects: a missing key and JSON null both give null. */
public final class Json {
    private Json() {
    }

    public static Double dbl(JSONObject o, String key) {
        if (o == null || o.isNull(key)) {
            return null;
        }
        Object v = o.opt(key);
        double d;
        if (v instanceof Number) {
            d = ((Number) v).doubleValue();
        } else if (v instanceof String) {
            try {
                d = Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return null;
        }
        return d;
    }

    public static Integer integer(JSONObject o, String key) {
        Double d = dbl(o, key);
        if (d == null || d > Integer.MAX_VALUE || d < Integer.MIN_VALUE) {
            return null;
        }
        return (int) Math.round(d);
    }

    public static Long lng(JSONObject o, String key) {
        if (o == null || o.isNull(key)) {
            return null;
        }
        Object v = o.opt(key);
        if (v instanceof Integer || v instanceof Long) {
            return ((Number) v).longValue();
        }
        Double d = dbl(o, key);
        return d == null ? null : Math.round(d);
    }

    public static String str(JSONObject o, String key) {
        if (o == null || o.isNull(key)) {
            return null;
        }
        Object v = o.opt(key);
        return v == null ? null : String.valueOf(v);
    }

    public static boolean bool(JSONObject o, String key, boolean def) {
        if (o == null || o.isNull(key)) {
            return def;
        }
        Object v = o.opt(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue() != 0;
        }
        if (v instanceof String) {
            return "true".equalsIgnoreCase((String) v) || "1".equals(v);
        }
        return def;
    }

    public static int intOr(JSONObject o, String key, int def) {
        Integer v = integer(o, key);
        return v != null ? v : def;
    }
}
