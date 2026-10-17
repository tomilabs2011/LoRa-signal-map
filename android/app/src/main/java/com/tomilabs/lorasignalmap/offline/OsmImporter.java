package com.tomilabs.lorasignalmap.offline;

import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds an {@link OfflineMap} from OpenStreetMap data: Overpass API JSON ("out geom") or
 * OSM XML (openstreetmap.org "Export", JOSM, overpass-turbo). Only what the map draws is kept.
 */
public final class OsmImporter {
    public interface Progress {
        /** @return false to cancel */
        boolean onProgress(int elements);
    }

    public static final class Cancelled extends IOException {
        public Cancelled() {
            super("cancelled");
        }
    }

    private final List<OfflineMap.Feature> features = new ArrayList<>();
    private int elements;
    private double south = 90;
    private double west = 180;
    private double north = -90;
    private double east = -180;

    // ------------------------------------------------------------------ classification

    /** Feature kind for the tags, or 0 if the object is not drawn. */
    static byte classify(Map<String, String> t, boolean closed, boolean relation) {
        String building = t.get("building");
        if (building != null && !"no".equals(building) && (closed || relation)) {
            return OfflineMap.BUILDING;
        }
        String hw = t.get("highway");
        if (hw != null && !relation && !"yes".equals(t.get("area"))) {
            switch (hw) {
                case "motorway":
                case "trunk":
                case "motorway_link":
                case "trunk_link":
                    return OfflineMap.MAJOR;
                case "primary":
                case "primary_link":
                    return OfflineMap.PRIMARY;
                case "secondary":
                case "secondary_link":
                    return OfflineMap.SECONDARY;
                case "tertiary":
                case "tertiary_link":
                    return OfflineMap.TERTIARY;
                case "residential":
                case "unclassified":
                case "living_street":
                case "road":
                    return OfflineMap.MINOR;
                case "service":
                    return OfflineMap.SERVICE;
                case "pedestrian":
                case "footway":
                case "path":
                case "cycleway":
                case "steps":
                case "track":
                case "bridleway":
                    return OfflineMap.PATH;
                default:
                    return 0;
            }
        }
        String rail = t.get("railway");
        if (rail != null && !relation && ("rail".equals(rail) || "light_rail".equals(rail)
                || "narrow_gauge".equals(rail) || "tram".equals(rail) || "subway".equals(rail))) {
            return OfflineMap.RAIL;
        }
        String ww = t.get("waterway");
        if (ww != null && !relation) {
            if ("river".equals(ww) || "canal".equals(ww)) {
                return OfflineMap.RIVER;
            }
            if ("stream".equals(ww) || "ditch".equals(ww) || "drain".equals(ww) || "brook".equals(ww)) {
                return OfflineMap.STREAM;
            }
            if ("riverbank".equals(ww) && closed) {
                return OfflineMap.WATER;
            }
            return 0;
        }
        if (!closed && !relation) {
            return 0; // everything below is an area
        }
        String natural = t.get("natural");
        String landuse = t.get("landuse");
        String leisure = t.get("leisure");
        String amenity = t.get("amenity");
        if ("water".equals(natural) || "reservoir".equals(landuse) || "basin".equals(landuse)) {
            return OfflineMap.WATER;
        }
        if ("wood".equals(natural) || "forest".equals(landuse)) {
            return OfflineMap.FOREST;
        }
        if ("wetland".equals(natural)) {
            return OfflineMap.WETLAND;
        }
        if ("cemetery".equals(landuse) || "grave_yard".equals(amenity)) {
            return OfflineMap.CEMETERY;
        }
        if ("pitch".equals(leisure) || "stadium".equals(leisure) || "sports_centre".equals(leisure)
                || "playground".equals(leisure) || "track".equals(leisure)) {
            return OfflineMap.SPORT;
        }
        if ("parking".equals(amenity)) {
            return OfflineMap.PARKING;
        }
        if ("park".equals(leisure) || "garden".equals(leisure) || "grassland".equals(natural)
                || "heath".equals(natural) || "scrub".equals(natural) || "grass".equals(landuse)
                || "meadow".equals(landuse) || "village_green".equals(landuse)
                || "recreation_ground".equals(landuse)) {
            return OfflineMap.GREEN;
        }
        if (landuse != null) {
            switch (landuse) {
                case "residential":
                    return OfflineMap.RESIDENTIAL;
                case "industrial":
                case "commercial":
                case "retail":
                case "railway":
                case "quarry":
                case "construction":
                case "brownfield":
                case "landfill":
                case "garages":
                    return OfflineMap.INDUSTRIAL;
                case "farmland":
                case "orchard":
                case "vineyard":
                case "allotments":
                case "farmyard":
                case "plant_nursery":
                    return OfflineMap.FARM;
                default:
                    return 0;
            }
        }
        return 0;
    }

    static byte classifyPlace(Map<String, String> t) {
        String place = t.get("place");
        if (place == null || t.get("name") == null) {
            return 0;
        }
        switch (place) {
            case "city":
            case "town":
                return OfflineMap.PLACE_TOWN;
            case "village":
            case "suburb":
                return OfflineMap.PLACE_VILLAGE;
            case "quarter":
            case "neighbourhood":
            case "hamlet":
                return OfflineMap.PLACE_SMALL;
            default:
                return 0;
        }
    }

    private static boolean keepsName(byte kind) {
        return kind >= OfflineMap.PATH || kind == OfflineMap.RIVER;
    }

    // ------------------------------------------------------------------ building features

    private void expand(double lat, double lon) {
        south = Math.min(south, lat);
        north = Math.max(north, lat);
        west = Math.min(west, lon);
        east = Math.max(east, lon);
    }

    private void addNode(Map<String, String> tags, double lat, double lon) {
        byte kind = classifyPlace(tags);
        if (kind == 0) {
            return;
        }
        features.add(new OfflineMap.Feature(kind, false, tags.get("name"),
                new int[][]{{OfflineMap.mercX(lon), OfflineMap.mercY(lat)}}));
    }

    private void addWay(Map<String, String> tags, int[] coords) {
        if (coords == null || coords.length < 4) {
            return;
        }
        boolean closed = coords.length >= 8 && coords[0] == coords[coords.length - 2]
                && coords[1] == coords[coords.length - 1];
        byte kind = classify(tags, closed, false);
        if (kind == 0) {
            return;
        }
        boolean area = closed && !(kind >= OfflineMap.STREAM && kind <= OfflineMap.MAJOR);
        features.add(new OfflineMap.Feature(kind, area, keepsName(kind) ? tags.get("name") : null,
                new int[][]{coords}));
    }

    private void addMultipolygon(Map<String, String> tags, List<int[]> outer, List<int[]> inner) {
        if (!"multipolygon".equals(tags.get("type")) || outer.isEmpty()) {
            return;
        }
        byte kind = classify(tags, true, true);
        if (kind == 0 || kind >= OfflineMap.STREAM) {
            return;
        }
        List<int[]> rings = assembleRings(outer);
        rings.addAll(assembleRings(inner));
        if (rings.isEmpty()) {
            return;
        }
        features.add(new OfflineMap.Feature(kind, true, null, rings.toArray(new int[0][])));
    }

    /** Joins way pieces into closed rings by matching end points. */
    static List<int[]> assembleRings(List<int[]> pieces) {
        List<int[]> open = new ArrayList<>();
        for (int[] p : pieces) {
            if (p != null && p.length >= 4) {
                open.add(p);
            }
        }
        List<int[]> rings = new ArrayList<>();
        while (!open.isEmpty()) {
            int[] cur = open.remove(open.size() - 1);
            boolean grown = true;
            while (!isClosed(cur) && grown) {
                grown = false;
                int ex = cur[cur.length - 2];
                int ey = cur[cur.length - 1];
                for (int i = 0; i < open.size(); i++) {
                    int[] p = open.get(i);
                    if (p[0] == ex && p[1] == ey) {
                        cur = concat(cur, p, false);
                    } else if (p[p.length - 2] == ex && p[p.length - 1] == ey) {
                        cur = concat(cur, p, true);
                    } else {
                        continue;
                    }
                    open.remove(i);
                    grown = true;
                    break;
                }
            }
            if (cur.length >= 6) {
                rings.add(cur); // unclosed leftovers are closed implicitly when drawn
            }
        }
        return rings;
    }

    private static boolean isClosed(int[] r) {
        return r.length >= 8 && r[0] == r[r.length - 2] && r[1] == r[r.length - 1];
    }

    /** a + b (b reversed if asked), dropping b's first point which equals a's last. */
    private static int[] concat(int[] a, int[] b, boolean reverse) {
        int[] out = new int[a.length + b.length - 2];
        System.arraycopy(a, 0, out, 0, a.length);
        int k = a.length;
        int pts = b.length / 2;
        for (int i = 1; i < pts; i++) {
            int src = reverse ? (pts - 1 - i) : i;
            out[k++] = b[src * 2];
            out[k++] = b[src * 2 + 1];
        }
        return out;
    }

    private void tick(Progress progress) throws Cancelled {
        elements++;
        if (progress != null && (elements & 1023) == 0 && !progress.onProgress(elements)) {
            throw new Cancelled();
        }
    }

    public OfflineMap build(String source, double[] requestedBbox) {
        double s = south;
        double w = west;
        double n = north;
        double e = east;
        if (requestedBbox != null) {
            s = requestedBbox[0];
            w = requestedBbox[1];
            n = requestedBbox[2];
            e = requestedBbox[3];
        }
        return new OfflineMap(s, w, n, e, System.currentTimeMillis(), source, features);
    }

    public int featureCount() {
        return features.size();
    }

    // ------------------------------------------------------------------ Overpass JSON

    /** Parses Overpass API JSON produced with "out geom". */
    public void readOverpassJson(InputStream in, Progress progress) throws IOException {
        JsonReader r = new JsonReader(new InputStreamReader(new BufferedInputStream(in, 1 << 16),
                Charset.forName("UTF-8")));
        r.setLenient(true);
        r.beginObject();
        while (r.hasNext()) {
            String key = r.nextName();
            if ("elements".equals(key)) {
                r.beginArray();
                while (r.hasNext()) {
                    readElement(r);
                    tick(progress);
                }
                r.endArray();
            } else if ("remark".equals(key)) {
                String remark = r.nextString();
                if (remark != null && remark.contains("error")) {
                    throw new IOException("Overpass: " + remark);
                }
            } else {
                r.skipValue();
            }
        }
        r.endObject();
    }

    private void readElement(JsonReader r) throws IOException {
        String type = null;
        double lat = Double.NaN;
        double lon = Double.NaN;
        Map<String, String> tags = new HashMap<>();
        int[] geometry = null;
        List<int[]> outer = new ArrayList<>();
        List<int[]> inner = new ArrayList<>();
        r.beginObject();
        while (r.hasNext()) {
            String key = r.nextName();
            switch (key) {
                case "type":
                    type = r.nextString();
                    break;
                case "lat":
                    lat = r.nextDouble();
                    break;
                case "lon":
                    lon = r.nextDouble();
                    break;
                case "tags":
                    readTags(r, tags);
                    break;
                case "geometry":
                    geometry = readGeometry(r);
                    break;
                case "members":
                    readMembers(r, outer, inner);
                    break;
                default:
                    r.skipValue();
            }
        }
        r.endObject();
        if ("node".equals(type)) {
            if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
                expand(lat, lon);
                addNode(tags, lat, lon);
            }
        } else if ("way".equals(type)) {
            addWay(tags, geometry);
        } else if ("relation".equals(type)) {
            addMultipolygon(tags, outer, inner);
        }
    }

    private static void readTags(JsonReader r, Map<String, String> tags) throws IOException {
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if (r.peek() == JsonToken.NULL) {
                r.nextNull();
            } else {
                tags.put(k, r.nextString());
            }
        }
        r.endObject();
    }

    private int[] readGeometry(JsonReader r) throws IOException {
        if (r.peek() == JsonToken.NULL) {
            r.nextNull();
            return null;
        }
        int[] buf = new int[64];
        int n = 0;
        r.beginArray();
        while (r.hasNext()) {
            if (r.peek() == JsonToken.NULL) {
                r.nextNull(); // node outside the requested area
                continue;
            }
            double lat = Double.NaN;
            double lon = Double.NaN;
            r.beginObject();
            while (r.hasNext()) {
                String k = r.nextName();
                if ("lat".equals(k)) {
                    lat = r.nextDouble();
                } else if ("lon".equals(k)) {
                    lon = r.nextDouble();
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
            if (Double.isNaN(lat) || Double.isNaN(lon)) {
                continue;
            }
            if (n + 2 > buf.length) {
                buf = java.util.Arrays.copyOf(buf, buf.length * 2);
            }
            expand(lat, lon);
            buf[n++] = OfflineMap.mercX(lon);
            buf[n++] = OfflineMap.mercY(lat);
        }
        r.endArray();
        return java.util.Arrays.copyOf(buf, n);
    }

    private void readMembers(JsonReader r, List<int[]> outer, List<int[]> inner) throws IOException {
        r.beginArray();
        while (r.hasNext()) {
            String type = null;
            String role = "";
            int[] geometry = null;
            r.beginObject();
            while (r.hasNext()) {
                String k = r.nextName();
                if ("type".equals(k)) {
                    type = r.nextString();
                } else if ("role".equals(k)) {
                    role = r.nextString();
                } else if ("geometry".equals(k)) {
                    geometry = readGeometry(r);
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
            if ("way".equals(type) && geometry != null) {
                if ("inner".equals(role)) {
                    inner.add(geometry);
                } else {
                    outer.add(geometry);
                }
            }
        }
        r.endArray();
    }

    // ------------------------------------------------------------------ OSM XML

    /** Parses OSM XML (nodes, then ways, then relations, as every OSM tool writes it). */
    public void readOsmXml(InputStream in, Progress progress) throws IOException {
        XmlPullParser p = Xml.newPullParser();
        LongIntMap nodeIndex = new LongIntMap(1 << 16);
        int[] nodeXY = new int[1 << 17];
        int nodeCount = 0;
        Map<Long, int[]> wayCoords = new HashMap<>();
        try {
            p.setInput(new BufferedInputStream(in, 1 << 16), null);
            Map<String, String> tags = new HashMap<>();
            String element = null;
            long id = 0;
            double lat = Double.NaN;
            double lon = Double.NaN;
            int[] nds = new int[64];
            int ndCount = 0;
            List<long[]> members = new ArrayList<>(); // {wayId, inner?1:0}
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String name = p.getName();
                    if ("node".equals(name) || "way".equals(name) || "relation".equals(name)) {
                        element = name;
                        tags.clear();
                        ndCount = 0;
                        members.clear();
                        id = parseLong(p.getAttributeValue(null, "id"));
                        lat = parseDouble(p.getAttributeValue(null, "lat"));
                        lon = parseDouble(p.getAttributeValue(null, "lon"));
                    } else if ("tag".equals(name) && element != null) {
                        String k = p.getAttributeValue(null, "k");
                        String v = p.getAttributeValue(null, "v");
                        if (k != null && v != null) {
                            tags.put(k, v);
                        }
                    } else if ("nd".equals(name) && "way".equals(element)) {
                        int idx = nodeIndex.get(parseLong(p.getAttributeValue(null, "ref")));
                        if (idx >= 0) {
                            if (ndCount + 2 > nds.length) {
                                nds = java.util.Arrays.copyOf(nds, nds.length * 2);
                            }
                            nds[ndCount++] = nodeXY[idx * 2];
                            nds[ndCount++] = nodeXY[idx * 2 + 1];
                        }
                    } else if ("member".equals(name) && "relation".equals(element)) {
                        if ("way".equals(p.getAttributeValue(null, "type"))) {
                            members.add(new long[]{parseLong(p.getAttributeValue(null, "ref")),
                                    "inner".equals(p.getAttributeValue(null, "role")) ? 1 : 0});
                        }
                    } else if ("bounds".equals(name)) {
                        double s = parseDouble(p.getAttributeValue(null, "minlat"));
                        double w = parseDouble(p.getAttributeValue(null, "minlon"));
                        double n = parseDouble(p.getAttributeValue(null, "maxlat"));
                        double e = parseDouble(p.getAttributeValue(null, "maxlon"));
                        if (!Double.isNaN(s) && !Double.isNaN(w) && !Double.isNaN(n) && !Double.isNaN(e)) {
                            expand(s, w);
                            expand(n, e);
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String name = p.getName();
                    if ("node".equals(name) && "node".equals(element)) {
                        if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
                            if ((nodeCount + 1) * 2 > nodeXY.length) {
                                nodeXY = java.util.Arrays.copyOf(nodeXY, nodeXY.length * 2);
                            }
                            nodeXY[nodeCount * 2] = OfflineMap.mercX(lon);
                            nodeXY[nodeCount * 2 + 1] = OfflineMap.mercY(lat);
                            nodeIndex.put(id, nodeCount);
                            nodeCount++;
                            expand(lat, lon);
                            if (!tags.isEmpty()) {
                                addNode(tags, lat, lon);
                            }
                        }
                        element = null;
                        tick(progress);
                    } else if ("way".equals(name) && "way".equals(element)) {
                        int[] coords = java.util.Arrays.copyOf(nds, ndCount);
                        wayCoords.put(id, coords);
                        addWay(tags, coords);
                        element = null;
                        tick(progress);
                    } else if ("relation".equals(name) && "relation".equals(element)) {
                        List<int[]> outer = new ArrayList<>();
                        List<int[]> inner = new ArrayList<>();
                        for (long[] m : members) {
                            int[] c = wayCoords.get(m[0]);
                            if (c != null) {
                                (m[1] == 1 ? inner : outer).add(c);
                            }
                        }
                        addMultipolygon(tags, outer, inner);
                        element = null;
                        tick(progress);
                    }
                }
                ev = p.next();
            }
        } catch (XmlPullParserException e) {
            throw new IOException("Not a valid .osm file: " + e.getMessage());
        }
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDouble(String s) {
        try {
            return s == null ? Double.NaN : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Open-addressing long -> int map (OSM node id -> index), far lighter than HashMap<Long,..>. */
    static final class LongIntMap {
        private long[] keys;
        private int[] values;
        private boolean[] used;
        private int size;

        LongIntMap(int capacity) {
            int cap = Integer.highestOneBit(Math.max(16, capacity) * 2);
            keys = new long[cap];
            values = new int[cap];
            used = new boolean[cap];
        }

        private static int hash(long k) {
            k ^= (k >>> 33);
            k *= 0xff51afd7ed558ccdL;
            k ^= (k >>> 33);
            return (int) k;
        }

        void put(long key, int value) {
            if ((size + 1) * 2 > keys.length) {
                grow();
            }
            int mask = keys.length - 1;
            int i = hash(key) & mask;
            while (used[i] && keys[i] != key) {
                i = (i + 1) & mask;
            }
            if (!used[i]) {
                used[i] = true;
                keys[i] = key;
                size++;
            }
            values[i] = value;
        }

        int get(long key) {
            int mask = keys.length - 1;
            int i = hash(key) & mask;
            while (used[i]) {
                if (keys[i] == key) {
                    return values[i];
                }
                i = (i + 1) & mask;
            }
            return -1;
        }

        private void grow() {
            long[] ok = keys;
            int[] ov = values;
            boolean[] ou = used;
            keys = new long[ok.length * 2];
            values = new int[ok.length * 2];
            used = new boolean[ok.length * 2];
            size = 0;
            for (int i = 0; i < ok.length; i++) {
                if (ou[i]) {
                    put(ok[i], ov[i]);
                }
            }
        }
    }
}
