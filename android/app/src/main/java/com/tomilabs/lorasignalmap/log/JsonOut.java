package com.tomilabs.lorasignalmap.log;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Minimal JSON object writer with stable key order and controlled number formatting,
 * so log lines look the same on every phone and are easy to diff.
 */
public final class JsonOut {
    private final StringBuilder sb = new StringBuilder(320);
    private boolean first = true;

    public JsonOut() {
        sb.append('{');
    }

    private void key(String k) {
        if (!first) {
            sb.append(',');
        }
        first = false;
        quote(sb, k);
        sb.append(':');
    }

    public JsonOut str(String k, String v) {
        key(k);
        if (v == null) {
            sb.append("null");
        } else {
            quote(sb, v);
        }
        return this;
    }

    public JsonOut num(String k, long v) {
        key(k);
        sb.append(v);
        return this;
    }

    public JsonOut num(String k, Integer v) {
        key(k);
        sb.append(v == null ? "null" : v.toString());
        return this;
    }

    public JsonOut num(String k, Long v) {
        key(k);
        sb.append(v == null ? "null" : v.toString());
        return this;
    }

    /** Decimal number rounded to at most {@code decimals} places, trailing zeros removed. */
    public JsonOut dec(String k, Double v, int decimals) {
        key(k);
        sb.append(formatDecimal(v, decimals));
        return this;
    }

    public JsonOut bool(String k, boolean v) {
        key(k);
        sb.append(v ? "true" : "false");
        return this;
    }

    /** Embeds an already serialised JSON value (object, array or literal). */
    public JsonOut raw(String k, String json) {
        key(k);
        sb.append(json == null ? "null" : json);
        return this;
    }

    @Override
    public String toString() {
        return sb.toString() + "}";
    }

    public static String formatDecimal(Double v, int decimals) {
        if (v == null || v.isNaN() || v.isInfinite()) {
            return "null";
        }
        BigDecimal bd = BigDecimal.valueOf(v).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros();
        if (bd.signum() == 0) {
            return "0";
        }
        return bd.toPlainString();
    }

    public static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        out.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
