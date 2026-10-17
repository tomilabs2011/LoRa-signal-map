package com.tomilabs.lorasignalmap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.location.Location;
import android.location.LocationManager;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import com.tomilabs.lorasignalmap.log.LogRecords;
import com.tomilabs.lorasignalmap.log.Session;
import com.tomilabs.lorasignalmap.log.SessionReader;
import com.tomilabs.lorasignalmap.log.SessionStore;
import com.tomilabs.lorasignalmap.map.SignalOverlay;
import com.tomilabs.lorasignalmap.model.Measurement;
import com.tomilabs.lorasignalmap.service.MappingService;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.Random;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h873dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppRobolectricTest {
    private static final double REP_LAT = 48.1046;
    private static final double REP_LON = 19.8086;

    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        LiveState.get().reset();
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS);
        Prefs.get(app).edit()
                .putString(Prefs.REPEATER_LAT, Double.toString(REP_LAT))
                .putString(Prefs.REPEATER_LON, Double.toString(REP_LON))
                .putString(Prefs.MAP_LAT, "48.1003")
                .putString(Prefs.MAP_LON, "19.8030")
                .putString(Prefs.MAP_ZOOM, "15.4")
                .putBoolean(Prefs.BATTERY_ASKED, true)
                .commit();
    }

    @After
    public void tearDown() {
        LiveState s = LiveState.get();
        if (s.running) {
            s.endSession();
        }
    }

    /** Writes a synthetic walk through Salgotarjan: signal fades with distance, some beacons fail. */
    private File seedSession(String name, long t0, int n, long seed) throws Exception {
        File f = new File(SessionStore.dir(app), name);
        Writer w = new OutputStreamWriter(new FileOutputStream(f), Charset.forName("UTF-8"));
        w.write(LogRecords.header("seed-" + seed, t0, "Europe/Budapest", 120, "pkg", "1.0.0", 1, "Xiaomi",
                "M2007J3SG", "15", 35, REP_LAT, REP_LON) + "\n");
        Random r = new Random(seed);
        double lat = 48.0935;
        double lon = 19.7925;
        for (int i = 0; i < n; i++) {
            lat += 0.00012 + r.nextGaussian() * 0.00003;
            lon += 0.00009 * Math.sin(i / 9.0) + 0.00010;
            Measurement m = new Measurement();
            m.t = t0 + i * 4000L;
            m.boot = "seed" + seed;
            m.seq = i + 1;
            m.payload = "Tomi" + i;
            double dist = com.tomilabs.lorasignalmap.model.SignalMath.distanceM(lat, lon, REP_LAT, REP_LON);
            double level = -70 - dist / 28 + r.nextGaussian() * 4;
            m.ok = level > -128 && r.nextDouble() > 0.08;
            if (m.ok) {
                m.rssi = (int) Math.round(Math.max(level, -124));
                m.snr = Math.round((level + 118) * 4 / 2.0) / 4.0;
                m.rrssi = m.rssi - 3;
                m.rsnr = m.snr - 1.5;
                m.rtt = 1800 + r.nextInt(500);
            }
            m.lat = lat;
            m.lon = lon;
            m.acc = 4.0;
            m.spd = 1.4;
            m.sats = 13;
            m.pos = "interp";
            m.fixAge = 200L;
            m.via = i % 7 == 0 ? "wifi" : "usb";
            w.write(LogRecords.measurement(m) + "\n");
        }
        w.write(LogRecords.end(t0 + n * 4000L, n, n) + "\n");
        w.close();
        return f;
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void idleFor(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private static void waitUntil(String what, Condition c, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle();
            if (c.ok()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    interface Condition {
        boolean ok() throws Exception;
    }

    static File screenshot(Activity a, String name) throws Exception {
        View root = a.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        File dir = new File(System.getProperty("lsm.screenshots", "build/screenshots"));
        dir.mkdirs();
        File f = new File(dir, name + ".png");
        FileOutputStream out = new FileOutputStream(f);
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.close();
        return f;
    }

    @Test
    public void mainScreenShowsSessionsAndTapDetails() throws Exception {
        seedSession("lsm_20261016_180000.jsonl", 1792170000000L, 140, 7);
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        final MainActivity a = c.get();
        final SignalOverlay overlay = (SignalOverlay) field(a, "signals");
        waitUntil("history loaded", new Condition() {
            @Override
            public boolean ok() {
                return overlay.visibleCount() == 140;
            }
        }, 10000);
        idleFor(100);
        File shot = screenshot(a, "01_main_idle");
        assertTrue(shot.length() > 0);

        // tap the 60th beacon
        MapView map = a.findViewById(R.id.map);
        List<GeoPoint> pts = overlay.allPoints();
        Point p = map.getProjection().toPixels(pts.get(60), null);
        MotionEvent ev = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, p.x, p.y, 0);
        assertTrue(overlay.onSingleTapConfirmed(ev, map));
        idleFor(100);
        View details = a.findViewById(R.id.details);
        assertEquals(View.VISIBLE, details.getVisibility());
        TextView title = a.findViewById(R.id.details_title);
        assertTrue(title.getText().length() > 0);
        screenshot(a, "02_main_details");

        // back closes the sheet
        a.onBackPressed();
        assertEquals(View.GONE, details.getVisibility());

        // close-up: track segments blend from one beacon's color to the next
        map.getController().setZoom(17.6);
        map.getController().setCenter(pts.get(60));
        idleFor(200);
        screenshot(a, "01b_main_zoomed");

        // layers dialog opens
        a.findViewById(R.id.btn_layers).performClick();
        idleFor(100);
        c.pause().stop().destroy();
    }

    @Test
    public void serviceRecordsBeaconsReceivedOverWifiAndPushesHotspotConfig() throws Exception {
        Prefs.get(app).edit().putString(Prefs.WIFI_SSID, "Hotspot").putString(Prefs.WIFI_PASS, "secret123").commit();
        LocationManager lm = (LocationManager) app.getSystemService(Application.LOCATION_SERVICE);
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true);

        ActivityController<MainActivity> ac = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = ac.get();

        ServiceController<MappingService> sc = Robolectric.buildService(MappingService.class,
                new Intent(app, MappingService.class).setAction(MappingService.ACTION_START));
        sc.create().startCommand(0, 1);
        idleFor(10);
        assertTrue(LiveState.get().running);
        final String sessionFile = LiveState.get().sessionFile;
        assertNotNull(sessionFile);
        assertNotNull(shadowOf(sc.get()).getLastForegroundNotification());

        DatagramSocket pico = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        pico.setSoTimeout(200);
        try {
            InetAddress phone = InetAddress.getByName("127.0.0.1");
            // GPS fixes every second while walking north
            double lat = 48.0990;
            for (int i = 0; i < 4; i++) {
                deliverFix(lm, lat + i * 0.0001, 19.8030);
                idleFor(1000);
            }
            String st = "{\"lsm\":1,\"t\":\"st\",\"boot\":\"cafe0001\",\"fw\":\"2.0.0\",\"up\":5000,\"id\":\"Tomi\","
                    + "\"seq\":0,\"radio\":\"ok\",\"usb\":\"data\",\"wifi\":\"up\",\"ip\":\"127.0.0.1\",\"wcfg\":\"\"}";
            sendUdp(pico, phone, st);
            waitUntil("device status", new Condition() {
                @Override
                public boolean ok() {
                    return LiveState.get().device != null && LiveState.get().wifiUp;
                }
            }, 10000);

            // the app must answer with the hotspot config (wcfg mismatch)
            JSONObject set = null;
            long end = System.currentTimeMillis() + 5000;
            while (set == null && System.currentTimeMillis() < end) {
                try {
                    DatagramPacket in = new DatagramPacket(new byte[2048], 2048);
                    pico.receive(in);
                    String txt = new String(in.getData(), 0, in.getLength(), "UTF-8").trim();
                    JSONObject o = new JSONObject(txt);
                    if ("set".equals(o.optString("cmd"))) {
                        set = o;
                    }
                } catch (SocketTimeoutException e) {
                    shadowOf(Looper.getMainLooper()).idle();
                }
            }
            assertNotNull("hotspot config was not pushed", set);
            assertEquals("Hotspot", set.getString("wifi_ssid"));

            for (int seq = 1; seq <= 5; seq++) {
                deliverFix(lm, lat + (3 + seq) * 0.0001, 19.8030);
                idleFor(1000);
                boolean ok = seq != 3;
                String res = "{\"lsm\":1,\"t\":\"res\",\"boot\":\"cafe0001\",\"seq\":" + seq + ",\"pl\":\"Tomi0" + seq
                        + "\",\"toa\":1188,\"txms\":1190,\"ok\":" + (ok ? 1 : 0) + ",\"rssi\":" + (ok ? "-9" + seq : "null")
                        + ",\"snr\":" + (ok ? "-2.5" : "null") + ",\"rrssi\":null,\"rsnr\":null,\"rtt\":"
                        + (ok ? "1900" : "null") + ",\"crc\":0,\"other\":0,\"age\":800}";
                sendUdp(pico, phone, res);
                sendUdp(pico, phone, res); // duplicate (as if it also came over USB)
            }
            waitUntil("5 beacons", new Condition() {
                @Override
                public boolean ok() {
                    return LiveState.get().count == 5;
                }
            }, 10000);
            Thread.sleep(300);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(5, LiveState.get().count);
            assertEquals(4, LiveState.get().okCount);
            assertEquals(0, LiveState.get().noFixCount);
            screenshot(activity, "03_main_recording");
        } finally {
            pico.close();
        }

        sc.startCommand(0, 2); // not a stop yet: same START intent is ignored while running
        assertTrue(LiveState.get().running);
        Intent stop = new Intent(app, MappingService.class).setAction(MappingService.ACTION_STOP);
        sc.withIntent(stop).startCommand(0, 3);
        waitUntil("session stopped", new Condition() {
            @Override
            public boolean ok() {
                return !LiveState.get().running;
            }
        }, 10000);
        sc.destroy();

        File f = SessionStore.find(app, sessionFile);
        assertNotNull(f);
        Session s = SessionReader.read(f);
        assertTrue(s.validHeader);
        assertTrue(s.ended);
        assertEquals(0, s.badLines);
        assertEquals(5, s.count());
        assertEquals(4, s.okCount());
        assertEquals(5, s.withPosition());
        Measurement third = s.measurements.get(2);
        assertFalse(third.ok);
        assertEquals("wifi", third.via);
        assertEquals("2.0.0", s.deviceFw);
        ac.pause().stop().destroy();
    }

    private static void deliverFix(LocationManager lm, double lat, double lon) {
        Location l = new Location(LocationManager.GPS_PROVIDER);
        l.setLatitude(lat);
        l.setLongitude(lon);
        l.setAccuracy(3.5f);
        l.setSpeed(1.4f);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        shadowOf(lm).simulateLocation(l);
    }

    private static void sendUdp(DatagramSocket s, InetAddress to, String line) throws Exception {
        byte[] b = (line + "\n").getBytes("UTF-8");
        s.send(new DatagramPacket(b, b.length, to, 47820));
    }

    @Test
    public void sessionsAndSettingsScreensOpen() throws Exception {
        seedSession("lsm_20261015_101500.jsonl", 1792008900000L, 30, 3);
        seedSession("lsm_20261016_180000.jsonl", 1792170000000L, 140, 7);
        ActivityController<SessionsActivity> sc = Robolectric.buildActivity(SessionsActivity.class).setup();
        final SessionsActivity sa = sc.get();
        waitUntil("session list", new Condition() {
            @Override
            public boolean ok() {
                android.widget.ListView lv = sa.findViewById(R.id.list);
                return lv.getAdapter() != null && lv.getAdapter().getCount() == 2 && lv.getChildCount() > 0;
            }
        }, 10000);
        idleFor(100);
        screenshot(sa, "04_sessions");
        // tapping a session opens the map focused on it
        android.widget.ListView lv = sa.findViewById(R.id.list);
        lv.performItemClick(lv.getChildAt(0), 0, 0);
        Intent next = shadowOf(sa).getNextStartedActivity();
        assertEquals("lsm_20261016_180000.jsonl", next.getStringExtra(MainActivity.EXTRA_FOCUS_SESSION));
        sc.pause().stop().destroy();

        ActivityController<MainActivity> mc = Robolectric.buildActivity(MainActivity.class, next).setup();
        final MainActivity ma = mc.get();
        final MapView map = ma.findViewById(R.id.map);
        waitUntil("zoom to session", new Condition() {
            @Override
            public boolean ok() throws Exception {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
                double lat = map.getMapCenter().getLatitude();
                // started at zoom 15.4 (setUp); the seeded walk spans lat 48.0935..48.111
                return field(ma, "focusSession") == null && Math.abs(map.getZoomLevelDouble() - 15.4) > 0.1
                        && lat > 48.095 && lat < 48.110;
            }
        }, 10000);
        mc.pause().stop().destroy();

        ActivityController<SettingsActivity> st = Robolectric.buildActivity(SettingsActivity.class).setup();
        idleFor(100);
        screenshot(st.get(), "05_settings");
        st.pause().stop().destroy();
    }
}
