package com.tomilabs.lorasignalmap.proto;

import java.nio.charset.Charset;
import java.util.Locale;

/** FNV-1a 32 bit, identical to fnv1a_hex() in Device/code.py. */
public final class Fnv {
    private Fnv() {
    }

    public static String hex(String s) {
        int h = 0x811C9DC5;
        for (byte b : s.getBytes(Charset.forName("UTF-8"))) {
            h ^= (b & 0xff);
            h *= 0x01000193;
        }
        return String.format(Locale.US, "%08x", h);
    }

    /** The "wcfg" value the device reports for a given hotspot config ("" when none). */
    public static String wifiConfigHash(String ssid, String password) {
        if (ssid == null || ssid.isEmpty()) {
            return "";
        }
        return hex(ssid + "\n" + (password == null ? "" : password));
    }
}
