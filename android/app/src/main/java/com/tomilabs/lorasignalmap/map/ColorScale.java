package com.tomilabs.lorasignalmap.map;

/** Continuous red -> orange -> yellow -> green scale between a worst and a best value. */
public final class ColorScale {
    public static final int RED = 0xFFD32F2F;
    public static final int ORANGE = 0xFFF57C00;
    public static final int YELLOW = 0xFFFBC02D;
    public static final int GREEN = 0xFF2E9E44;
    /** Ring color of beacons that got no reply. */
    public static final int FAILED = 0xFF8E0000;
    /** Successful beacon without a usable value (should not happen with current firmware). */
    public static final int UNKNOWN = 0xFF78909C;

    private static final int[] STOPS = {RED, ORANGE, YELLOW, GREEN};

    public final double worst;
    public final double best;

    public ColorScale(double worst, double best) {
        if (best < worst) {
            double t = best;
            best = worst;
            worst = t;
        }
        if (best - worst < 1e-6) {
            best = worst + 1;
        }
        this.worst = worst;
        this.best = best;
    }

    /** 0 = worst, 1 = best. */
    public double fraction(double v) {
        double f = (v - worst) / (best - worst);
        return f < 0 ? 0 : (f > 1 ? 1 : f);
    }

    public int color(Double v) {
        if (v == null || v.isNaN()) {
            return UNKNOWN;
        }
        return colorAt(fraction(v));
    }

    public static int colorAt(double f) {
        double x = f * (STOPS.length - 1);
        int i = (int) Math.floor(x);
        if (i >= STOPS.length - 1) {
            return STOPS[STOPS.length - 1];
        }
        if (i < 0) {
            return STOPS[0];
        }
        return lerp(STOPS[i], STOPS[i + 1], x - i);
    }

    public static int lerp(int a, int b, double t) {
        int aa = (a >>> 24) & 0xff;
        int ar = (a >> 16) & 0xff;
        int ag = (a >> 8) & 0xff;
        int ab = a & 0xff;
        int ba = (b >>> 24) & 0xff;
        int br = (b >> 16) & 0xff;
        int bg = (b >> 8) & 0xff;
        int bb = b & 0xff;
        int ra = (int) Math.round(aa + (ba - aa) * t);
        int rr = (int) Math.round(ar + (br - ar) * t);
        int rg = (int) Math.round(ag + (bg - ag) * t);
        int rb = (int) Math.round(ab + (bb - ab) * t);
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }
}
