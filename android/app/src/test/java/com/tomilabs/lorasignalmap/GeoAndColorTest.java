package com.tomilabs.lorasignalmap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.tomilabs.lorasignalmap.gps.Fix;
import com.tomilabs.lorasignalmap.gps.PositionHistory;
import com.tomilabs.lorasignalmap.map.ColorScale;
import com.tomilabs.lorasignalmap.model.Measurement;
import com.tomilabs.lorasignalmap.model.SignalMath;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Runs on Robolectric so it uses Android's own org.json. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class GeoAndColorTest {
    private static final long S = 1_000_000_000L;

    private static Fix fix(long t, double lat, double lon) {
        return new Fix(t, 0, lat, lon, null, 5.0, null, null, null);
    }

    @Test
    public void interpolatesBetweenFixes() {
        PositionHistory h = new PositionHistory();
        h.add(fix(10 * S, 48.0, 19.0));
        h.add(fix(12 * S, 48.2, 19.4));
        PositionHistory.Position p = h.at(11 * S);
        assertEquals("interp", p.method);
        assertEquals(48.1, p.lat, 1e-12);
        assertEquals(19.2, p.lon, 1e-12);
        assertEquals(1000, p.fixAgeMs);
    }

    @Test
    public void outOfOrderFixesAreSorted() {
        PositionHistory h = new PositionHistory();
        h.add(fix(12 * S, 48.2, 19.4));
        h.add(fix(10 * S, 48.0, 19.0));
        assertEquals(48.1, h.at(11 * S).lat, 1e-12);
    }

    @Test
    public void usesNearestFixAtTheEdgesAndRejectsStale() {
        PositionHistory h = new PositionHistory();
        h.add(fix(10 * S, 48.0, 19.0));
        PositionHistory.Position p = h.at(12 * S);
        assertEquals("nearest", p.method);
        assertEquals(2000, p.fixAgeMs);
        assertNull(h.at(10 * S + 31 * S));
        assertNull(new PositionHistory().at(5));
    }

    @Test
    public void bigGapIsNotInterpolated() {
        PositionHistory h = new PositionHistory();
        h.add(fix(10 * S, 48.0, 19.0));
        h.add(fix(30 * S, 48.2, 19.4));
        PositionHistory.Position p = h.at(11 * S);
        assertEquals("nearest", p.method);
        assertEquals(48.0, p.lat, 0);
    }

    @Test
    public void colorScaleEndpointsAndOrder() {
        ColorScale c = new ColorScale(-135, -80);
        assertEquals(ColorScale.RED, c.color(-140.0));
        assertEquals(ColorScale.RED, c.color(-135.0));
        assertEquals(ColorScale.GREEN, c.color(-80.0));
        assertEquals(ColorScale.GREEN, c.color(-50.0));
        assertEquals(ColorScale.UNKNOWN, c.color(null));
        // one third of the way: orange, two thirds: yellow
        assertEquals(ColorScale.ORANGE, ColorScale.colorAt(1 / 3.0));
        assertEquals(ColorScale.YELLOW, ColorScale.colorAt(2 / 3.0));
        // swapped bounds are fixed up
        ColorScale s = new ColorScale(-80, -135);
        assertEquals(-135, s.worst, 0);
        // green channel rises monotonically from red to yellow
        int prevG = -1;
        for (int i = 0; i <= 20; i++) {
            int g = (ColorScale.colorAt(i / 30.0) >> 8) & 0xff;
            assertEquals(true, g >= prevG);
            prevG = g;
        }
    }

    @Test
    public void signalUsesSnrBelowZeroAndWeakerDirection() {
        assertEquals(-90.0, SignalMath.strength(-90, 5.0), 0);
        assertEquals(-95.5, SignalMath.strength(-90, -5.5), 0);
        assertNull(SignalMath.strength(null, 3.0));
        Measurement m = new Measurement();
        m.ok = true;
        m.rssi = -90;
        m.snr = 2.0;
        assertEquals(-90.0, SignalMath.linkSignal(m), 0);
        m.rrssi = -100;
        m.rsnr = -4.0;
        assertEquals(-104.0, SignalMath.linkSignal(m), 0);
        assertEquals(-90.0, SignalMath.signal(m, SignalMath.DIR_DOWN), 0);
        assertEquals(-104.0, SignalMath.signal(m, SignalMath.DIR_UP), 0);
        assertEquals(-4.0, SignalMath.snr(m, SignalMath.DIR_WEAKEST), 0);
        m.ok = false;
        assertNull(SignalMath.linkSignal(m));
    }

    @Test
    public void distance() {
        // 0.01 deg latitude ~ 1112 m
        assertEquals(1112, SignalMath.distanceM(48.0, 19.8, 48.01, 19.8), 2);
    }
}
