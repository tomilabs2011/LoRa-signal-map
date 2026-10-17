package com.tomilabs.lorasignalmap.offline;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Downloads OpenStreetMap data for an area from the Overpass API and turns it into an offline
 * map. Blocking: call from a background thread.
 */
public final class OverpassDownloader {
    public interface Progress {
        /** @return false to cancel */
        boolean onProgress(String stage, long amount);
    }

    private static final String[] SERVERS = {
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
    };

    private OverpassDownloader() {
    }

    /** The Overpass QL query for everything the offline map draws inside the box. */
    public static String query(double s, double w, double n, double e) {
        String bb = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", s, w, n, e);
        return "[out:json][timeout:180][maxsize:536870912];\n("
                + "way[\"highway\"](" + bb + ");"
                + "way[\"building\"](" + bb + ");"
                + "way[\"landuse\"](" + bb + ");"
                + "way[\"natural\"~\"^(water|wood|scrub|heath|grassland|wetland)$\"](" + bb + ");"
                + "way[\"leisure\"~\"^(park|garden|pitch|playground|sports_centre|stadium|track)$\"](" + bb + ");"
                + "way[\"waterway\"](" + bb + ");"
                + "way[\"railway\"~\"^(rail|light_rail|tram|narrow_gauge|subway)$\"](" + bb + ");"
                + "way[\"amenity\"~\"^(parking|grave_yard)$\"](" + bb + ");"
                + "relation[\"type\"=\"multipolygon\"][\"landuse\"](" + bb + ");"
                + "relation[\"type\"=\"multipolygon\"][\"natural\"~\"^(water|wood|scrub|heath|grassland|wetland)$\"](" + bb + ");"
                + "relation[\"type\"=\"multipolygon\"][\"leisure\"~\"^(park|garden)$\"](" + bb + ");"
                + "relation[\"type\"=\"multipolygon\"][\"building\"](" + bb + ");"
                + "node[\"place\"~\"^(city|town|village|suburb|quarter|neighbourhood|hamlet)$\"](" + bb + ");"
                + ");\nout geom qt;";
    }

    public static OfflineMap download(double s, double w, double n, double e, File tmp, String userAgent,
            final Progress progress) throws IOException {
        String body = "data=" + URLEncoder.encode(query(s, w, n, e), "UTF-8");
        IOException last = null;
        for (String server : SERVERS) {
            try {
                fetch(server, body, tmp, userAgent, progress);
                last = null;
                break;
            } catch (OsmImporter.Cancelled c) {
                throw c;
            } catch (IOException ex) {
                last = ex;
            }
        }
        if (last != null) {
            throw last;
        }
        if (!progress.onProgress("parse", 0)) {
            throw new OsmImporter.Cancelled();
        }
        OsmImporter imp = new OsmImporter();
        InputStream in = new FileInputStream(tmp);
        try {
            imp.readOverpassJson(in, new OsmImporter.Progress() {
                @Override
                public boolean onProgress(int elements) {
                    return progress.onProgress("parse", elements);
                }
            });
        } finally {
            in.close();
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        if (imp.featureCount() == 0) {
            throw new IOException("the server returned no map data for this area");
        }
        return imp.build("OpenStreetMap via Overpass API", new double[]{s, w, n, e});
    }

    private static void fetch(String server, String body, File tmp, String userAgent, Progress progress)
            throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(server).openConnection();
        try {
            con.setConnectTimeout(20000);
            con.setReadTimeout(240000);
            con.setDoOutput(true);
            con.setRequestMethod("POST");
            con.setRequestProperty("User-Agent", userAgent);
            con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            byte[] payload = body.getBytes("UTF-8");
            con.setFixedLengthStreamingMode(payload.length);
            OutputStream os = con.getOutputStream();
            os.write(payload);
            os.close();
            int code = con.getResponseCode();
            if (code != 200) {
                String hint = code == 429 ? " (server busy, try again in a minute)"
                        : code == 504 ? " (server overloaded, try again later)" : "";
                throw new IOException("HTTP " + code + hint);
            }
            InputStream in = con.getInputStream();
            OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16);
            long total = 0;
            try {
                byte[] buf = new byte[1 << 16];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    total += r;
                    if (!progress.onProgress("download", total)) {
                        throw new OsmImporter.Cancelled();
                    }
                }
            } finally {
                out.close();
                in.close();
            }
        } finally {
            con.disconnect();
        }
    }
}
