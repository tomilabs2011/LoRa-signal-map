package com.tomilabs.lorasignalmap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.os.Looper;

import com.tomilabs.lorasignalmap.offline.OfflineMap;
import com.tomilabs.lorasignalmap.offline.OfflineMapStore;
import com.tomilabs.lorasignalmap.offline.OsmImporter;
import com.tomilabs.lorasignalmap.offline.VectorTileRenderer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.osmdroid.views.MapView;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h873dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class OfflineMapTest {
    static final double LAT0 = 48.0930;
    static final double LON0 = 19.7930;

    /** A small synthetic town in Overpass "out geom" JSON. */
    static String syntheticOverpassJson() {
        StringBuilder sb = new StringBuilder("{\"version\":0.6,\"elements\":[");
        boolean[] first = {true};
        // street grid
        for (int i = 0; i < 14; i++) {
            double lat = LAT0 + i * 0.0011;
            way(sb, first, "{\"highway\":\"" + (i == 6 ? "secondary" : "residential")
                    + "\",\"name\":\"" + (i == 6 ? "Bartók Béla út" : "Utca " + (i + 1)) + "\"}",
                    line(lat, LON0, lat + 0.0006, LON0 + 0.021, 8, 0.00015));
            double lon = LON0 + i * 0.0016;
            way(sb, first, "{\"highway\":\"" + (i == 7 ? "tertiary" : "residential") + "\",\"name\":\""
                    + (i == 7 ? "Kossuth Lajos út" : "Köz " + (i + 1)) + "\"}",
                    line(LAT0, lon, LAT0 + 0.0145, lon + 0.0004, 8, 0.0001));
        }
        // main diagonal road
        way(sb, first, "{\"highway\":\"primary\",\"name\":\"Rákóczi út\"}",
                line(LAT0 - 0.002, LON0 - 0.003, LAT0 + 0.017, LON0 + 0.024, 14, 0.0006));
        way(sb, first, "{\"highway\":\"footway\"}", line(LAT0 + 0.0035, LON0 + 0.004, LAT0 + 0.0052, LON0 + 0.009, 5, 0.0002));
        // buildings in the blocks
        for (int i = 0; i < 13; i++) {
            for (int j = 0; j < 13; j++) {
                if ((i * 7 + j * 3) % 5 == 0) {
                    continue;
                }
                double la = LAT0 + i * 0.0011 + 0.0003;
                double lo = LON0 + j * 0.0016 + 0.0004;
                way(sb, first, "{\"building\":\"yes\"}", rect(la, lo, la + 0.00035, lo + 0.0007));
                way(sb, first, "{\"building\":\"house\"}", rect(la + 0.0005, lo + 0.0002, la + 0.0008, lo + 0.0006));
            }
        }
        // stream, rail, park, water, residential area
        way(sb, first, "{\"waterway\":\"stream\",\"name\":\"Tarján-patak\"}",
                line(LAT0 - 0.003, LON0 + 0.006, LAT0 + 0.018, LON0 + 0.012, 30, 0.0012));
        way(sb, first, "{\"railway\":\"rail\"}", line(LAT0 - 0.003, LON0 + 0.018, LAT0 + 0.018, LON0 + 0.016, 10, 0.0003));
        way(sb, first, "{\"leisure\":\"park\",\"name\":\"Park\"}", rect(LAT0 + 0.0058, LON0 + 0.0081, LAT0 + 0.0074, LON0 + 0.0109));
        way(sb, first, "{\"natural\":\"water\"}", rect(LAT0 + 0.0061, LON0 + 0.0087, LAT0 + 0.0068, LON0 + 0.0099));
        way(sb, first, "{\"landuse\":\"residential\"}", rect(LAT0 - 0.0005, LON0 - 0.0008, LAT0 + 0.0150, LON0 + 0.0215));
        way(sb, first, "{\"leisure\":\"pitch\"}", rect(LAT0 + 0.0122, LON0 + 0.0033, LAT0 + 0.0128, LON0 + 0.0044));
        // forest multipolygon: outer made of two pieces, one hole
        sb.append(",{\"type\":\"relation\",\"id\":1,\"tags\":{\"type\":\"multipolygon\",\"landuse\":\"forest\"},\"members\":[");
        sb.append("{\"type\":\"way\",\"ref\":10,\"role\":\"outer\",\"geometry\":")
                .append(points(new double[]{LAT0 + 0.016, LON0 - 0.010, LAT0 + 0.016, LON0 + 0.030, LAT0 + 0.030, LON0 + 0.030}))
                .append("},");
        sb.append("{\"type\":\"way\",\"ref\":11,\"role\":\"outer\",\"geometry\":")
                .append(points(new double[]{LAT0 + 0.016, LON0 - 0.010, LAT0 + 0.030, LON0 - 0.010, LAT0 + 0.030, LON0 + 0.030}))
                .append("},");
        sb.append("{\"type\":\"way\",\"ref\":12,\"role\":\"inner\",\"geometry\":")
                .append(rect(LAT0 + 0.020, LON0 + 0.004, LAT0 + 0.024, LON0 + 0.012)).append("}]}");
        // places
        sb.append(",{\"type\":\"node\",\"id\":2,\"lat\":").append(LAT0 + 0.0074).append(",\"lon\":").append(LON0 + 0.0105)
                .append(",\"tags\":{\"place\":\"town\",\"name\":\"Salgótarján\"}}");
        sb.append(",{\"type\":\"node\",\"id\":3,\"lat\":").append(LAT0 + 0.0125).append(",\"lon\":").append(LON0 + 0.0170)
                .append(",\"tags\":{\"place\":\"suburb\",\"name\":\"Zagyvaróna\"}}");
        sb.append(",{\"type\":\"node\",\"id\":4,\"lat\":").append(LAT0).append(",\"lon\":").append(LON0)
                .append(",\"tags\":{\"amenity\":\"bench\"}}");
        sb.append("]}");
        return sb.toString();
    }

    private static void way(StringBuilder sb, boolean[] first, String tags, String geometry) {
        if (!first[0]) {
            sb.append(',');
        }
        first[0] = false;
        sb.append("{\"type\":\"way\",\"id\":1,\"tags\":").append(tags).append(",\"geometry\":").append(geometry).append('}');
    }

    private static String line(double la0, double lo0, double la1, double lo1, int n, double wiggle) {
        double[] p = new double[(n + 1) * 2];
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            p[i * 2] = la0 + (la1 - la0) * t + Math.sin(t * 6.3) * wiggle;
            p[i * 2 + 1] = lo0 + (lo1 - lo0) * t;
        }
        return points(p);
    }

    private static String rect(double la0, double lo0, double la1, double lo1) {
        return points(new double[]{la0, lo0, la0, lo1, la1, lo1, la1, lo0, la0, lo0});
    }

    private static String points(double[] p) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < p.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.US, "{\"lat\":%.7f,\"lon\":%.7f}", p[i], p[i + 1]));
        }
        return sb.append(']').toString();
    }

    static OfflineMap syntheticMap() throws Exception {
        OsmImporter imp = new OsmImporter();
        imp.readOverpassJson(new ByteArrayInputStream(syntheticOverpassJson().getBytes(Charset.forName("UTF-8"))), null);
        return imp.build("test", new double[]{LAT0 - 0.01, LON0 - 0.015, LAT0 + 0.03, LON0 + 0.035});
    }

    @Test
    public void overpassJsonIsClassifiedAndMultipolygonsAssembled() throws Exception {
        OfflineMap m = syntheticMap();
        assertEquals(30, m.count(OfflineMap.PATH, OfflineMap.MAJOR));
        assertEquals(1, m.count(OfflineMap.PATH, OfflineMap.PATH));
        assertTrue(m.count(OfflineMap.BUILDING, OfflineMap.BUILDING) > 200);
        assertEquals(2, m.count(OfflineMap.PLACE_SMALL, OfflineMap.PLACE_TOWN)); // bench dropped
        OfflineMap.Feature forest = null;
        for (OfflineMap.Feature f : m.features) {
            if (f.kind == OfflineMap.FOREST) {
                forest = f;
            }
        }
        assertNotNull(forest);
        assertEquals(2, forest.rings.length); // outer joined from two pieces + inner
        int[] outer = forest.rings[0].length > forest.rings[1].length ? forest.rings[0] : forest.rings[1];
        assertEquals(outer[0], outer[outer.length - 2]);
        assertEquals(outer[1], outer[outer.length - 1]);
        // drawing order
        for (int i = 1; i < m.features.length; i++) {
            assertTrue(m.features[i - 1].kind <= m.features[i].kind);
        }
    }

    @Test
    public void fileRoundTripAndQuery() throws Exception {
        OfflineMap m = syntheticMap();
        File f = File.createTempFile("map", ".bin");
        m.save(f);
        OfflineMap back = OfflineMap.load(f);
        assertEquals(m.size(), back.size());
        assertEquals(m.south, back.south, 0);
        for (int i = 0; i < m.size(); i++) {
            assertEquals(m.features[i].kind, back.features[i].kind);
            assertEquals(m.features[i].name, back.features[i].name);
            assertEquals(m.features[i].rings.length, back.features[i].rings.length);
        }
        // a box around one block finds its buildings but not the far forest
        int x0 = OfflineMap.mercX(LON0 + 0.0020); // block (0,1) has buildings
        int x1 = OfflineMap.mercX(LON0 + 0.0027);
        int y0 = OfflineMap.mercY(LAT0 + 0.0007);
        int y1 = OfflineMap.mercY(LAT0 + 0.0003);
        int buildings = 0;
        for (int fi : back.query(x0, y0, x1, y1)) {
            assertTrue(back.features[fi].kind != OfflineMap.FOREST);
            if (back.features[fi].kind == OfflineMap.BUILDING) {
                buildings++;
            }
        }
        assertTrue(buildings >= 1);
    }

    @Test
    public void osmXmlImportMatchesJson() throws Exception {
        StringBuilder x = new StringBuilder("<?xml version='1.0' encoding='UTF-8'?>\n<osm version=\"0.6\">\n");
        x.append("<bounds minlat=\"48.09\" minlon=\"19.79\" maxlat=\"48.11\" maxlon=\"19.82\"/>\n");
        double[][] pts = {{48.100, 19.800}, {48.101, 19.801}, {48.100, 19.802}, {48.102, 19.800}, {48.102, 19.802},
                {48.104, 19.802}, {48.104, 19.800}};
        for (int i = 0; i < pts.length; i++) {
            x.append(String.format(Locale.US, "<node id=\"%d\" lat=\"%.4f\" lon=\"%.4f\"/>\n", i + 1, pts[i][0], pts[i][1]));
        }
        x.append("<node id=\"99\" lat=\"48.101\" lon=\"19.801\"><tag k=\"place\" v=\"village\"/><tag k=\"name\" v=\"Falu\"/></node>\n");
        x.append("<way id=\"1\"><nd ref=\"1\"/><nd ref=\"2\"/><nd ref=\"3\"/><tag k=\"highway\" v=\"residential\"/><tag k=\"name\" v=\"Fő utca\"/></way>\n");
        x.append("<way id=\"2\"><nd ref=\"4\"/><nd ref=\"5\"/><nd ref=\"6\"/></way>\n");
        x.append("<way id=\"3\"><nd ref=\"6\"/><nd ref=\"7\"/><nd ref=\"4\"/></way>\n");
        x.append("<way id=\"4\"><nd ref=\"1\"/><nd ref=\"3\"/><nd ref=\"2\"/><nd ref=\"1\"/><tag k=\"building\" v=\"yes\"/></way>\n");
        x.append("<relation id=\"1\"><member type=\"way\" ref=\"2\" role=\"outer\"/><member type=\"way\" ref=\"3\" role=\"outer\"/>"
                + "<tag k=\"type\" v=\"multipolygon\"/><tag k=\"natural\" v=\"water\"/></relation>\n</osm>\n");
        OsmImporter imp = new OsmImporter();
        imp.readOsmXml(new ByteArrayInputStream(x.toString().getBytes(Charset.forName("UTF-8"))), null);
        OfflineMap m = imp.build("xml", null);
        assertEquals(1, m.count(OfflineMap.MINOR, OfflineMap.MINOR));
        assertEquals(1, m.count(OfflineMap.BUILDING, OfflineMap.BUILDING));
        assertEquals(1, m.count(OfflineMap.WATER, OfflineMap.WATER));
        assertEquals(1, m.count(OfflineMap.PLACE_VILLAGE, OfflineMap.PLACE_VILLAGE));
        assertEquals(48.09, m.south, 1e-9);
        for (OfflineMap.Feature f : m.features) {
            if (f.kind == OfflineMap.WATER) {
                assertEquals(1, f.rings.length);
                assertEquals(10, f.rings[0].length); // 4 corners + closing point
            }
            if (f.kind == OfflineMap.MINOR) {
                assertEquals("Fő utca", f.name);
            }
        }
    }

    @Test
    public void tilesRenderAtAllZooms() throws Exception {
        OfflineMap m = syntheticMap();
        float density = 2.75f;
        File dir = new File(System.getProperty("lsm.screenshots", "build/screenshots"));
        dir.mkdirs();
        VectorTileRenderer.render(m, 15, 0, 0, 256, density); // warm up fonts / graphics
        for (int z : new int[]{11, 12, 13, 14, 15, 16, 17, 18}) {
            double lat = LAT0 + 0.0065;
            double lon = LON0 + 0.0095;
            int n = 1 << z;
            int tx = (int) ((lon + 180) / 360 * n);
            double latR = Math.toRadians(lat);
            int ty = (int) ((1 - Math.log(Math.tan(latR) + 1 / Math.cos(latR)) / Math.PI) / 2 * n);
            long t0 = System.nanoTime();
            Bitmap b = VectorTileRenderer.render(m, z, tx, ty, 704, density);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(704, b.getWidth());
            FileOutputStream out = new FileOutputStream(new File(dir, "tile_z" + z + ".png"));
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
            System.err.println("tile z" + z + " rendered in " + ms + " ms");
        }
        // empty map and far-away tile are plain land
        Bitmap blank = VectorTileRenderer.render(null, 15, 1, 1, 256, 1f);
        assertEquals(VectorTileRenderer.LAND, blank.getPixel(10, 10));
    }

    @Test
    public void mainScreenUsesTheOfflineMap() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        LiveState.get().reset();
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        Prefs.get(app).edit()
                .putString(Prefs.MAP_LAT, Double.toString(LAT0 + 0.0072))
                .putString(Prefs.MAP_LON, Double.toString(LON0 + 0.0100))
                .putString(Prefs.MAP_ZOOM, "16.2")
                .remove(Prefs.MAP_STYLE)
                .commit();
        OfflineMapStore.install(app, syntheticMap());
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        MapView map = a.findViewById(R.id.map);
        screenshot(a, "06_main_offline_map"); // the first draw requests the tiles
        for (int i = 0; i < 60; i++) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
            Thread.sleep(50);
            map.invalidate();
        }
        assertEquals("OfflineVector", map.getTileProvider().getTileSource().name());
        screenshot(a, "06_main_offline_map");

        // layers dialog shows the offline map section
        a.findViewById(R.id.btn_layers).performClick();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        android.app.AlertDialog d = (android.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(d);
        android.view.View root = d.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new android.graphics.Canvas(b));
        File dir = new File(System.getProperty("lsm.screenshots", "build/screenshots"));
        FileOutputStream out = new FileOutputStream(new File(dir, "07_layers_dialog.png"));
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.close();
        d.dismiss();
        c.pause().stop().destroy();
    }

    private static void screenshot(Activity a, String name) throws Exception {
        AppRobolectricTest.screenshot(a, name);
    }
}
