package com.tomilabs.lorasignalmap.offline;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Vector map of an area (roads, buildings, land use, water, places) kept on the phone so the
 * map works without internet. Coordinates are Web Mercator scaled to 0..2^30 (int).
 * Immutable after construction; safe to read from several render threads.
 */
public final class OfflineMap {
    public static final int WORLD = 1 << 30;

    // Feature kinds, in drawing order (lower = drawn first).
    public static final byte RESIDENTIAL = 1;
    public static final byte INDUSTRIAL = 2;
    public static final byte FARM = 3;
    public static final byte FOREST = 4;
    public static final byte GREEN = 5;
    public static final byte CEMETERY = 6;
    public static final byte SPORT = 7;
    public static final byte PARKING = 8;
    public static final byte WETLAND = 9;
    public static final byte WATER = 10;
    public static final byte BUILDING = 11;
    public static final byte STREAM = 12;
    public static final byte RIVER = 13;
    public static final byte RAIL = 14;
    public static final byte PATH = 20;
    public static final byte SERVICE = 21;
    public static final byte MINOR = 22;
    public static final byte TERTIARY = 23;
    public static final byte SECONDARY = 24;
    public static final byte PRIMARY = 25;
    public static final byte MAJOR = 26;
    public static final byte PLACE_SMALL = 30;
    public static final byte PLACE_VILLAGE = 31;
    public static final byte PLACE_TOWN = 32;

    private static final byte[] MAGIC = {'L', 'S', 'M', 'M', 'A', 'P', 0, 1};
    private static final int FORMAT_VERSION = 1;
    /** Grid cell size of the spatial index in mercator units (~2 km at this latitude). */
    private static final int CELL_SHIFT = 17;

    /** One map object. Rings hold interleaved x,y mercator ints. */
    public static final class Feature {
        public final byte kind;
        public final boolean area;
        public final String name;
        public final int[][] rings;
        public final int minX;
        public final int minY;
        public final int maxX;
        public final int maxY;

        public Feature(byte kind, boolean area, String name, int[][] rings) {
            this.kind = kind;
            this.area = area;
            this.name = name == null || name.isEmpty() ? null : name;
            this.rings = rings;
            int x0 = Integer.MAX_VALUE;
            int y0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE;
            int y1 = Integer.MIN_VALUE;
            for (int[] r : rings) {
                for (int i = 0; i + 1 < r.length; i += 2) {
                    x0 = Math.min(x0, r[i]);
                    x1 = Math.max(x1, r[i]);
                    y0 = Math.min(y0, r[i + 1]);
                    y1 = Math.max(y1, r[i + 1]);
                }
            }
            minX = x0;
            minY = y0;
            maxX = x1;
            maxY = y1;
        }

        public boolean isRoad() {
            return kind >= PATH && kind <= MAJOR;
        }

        public boolean isPlace() {
            return kind >= PLACE_SMALL;
        }
    }

    public final double south;
    public final double west;
    public final double north;
    public final double east;
    public final long createdMs;
    public final String source;
    public final Feature[] features;

    // spatial index (compressed sparse rows over the bbox grid)
    private final int cellX0;
    private final int cellY0;
    private final int cellsW;
    private final int cellsH;
    private final int[] cellStart;
    private final int[] cellItems;

    public OfflineMap(double south, double west, double north, double east, long createdMs, String source,
            List<Feature> list) {
        this.south = south;
        this.west = west;
        this.north = north;
        this.east = east;
        this.createdMs = createdMs;
        this.source = source == null ? "" : source;
        Feature[] arr = list.toArray(new Feature[0]);
        // stable sort by kind = drawing order
        Arrays.sort(arr, new Comparator<Feature>() {
            @Override
            public int compare(Feature a, Feature b) {
                return a.kind - b.kind;
            }
        });
        features = arr;

        int fx0 = Integer.MAX_VALUE;
        int fy0 = Integer.MAX_VALUE;
        int fx1 = Integer.MIN_VALUE;
        int fy1 = Integer.MIN_VALUE;
        for (Feature f : arr) {
            fx0 = Math.min(fx0, f.minX);
            fy0 = Math.min(fy0, f.minY);
            fx1 = Math.max(fx1, f.maxX);
            fy1 = Math.max(fy1, f.maxY);
        }
        if (arr.length == 0) {
            fx0 = fy0 = fx1 = fy1 = 0;
        }
        cellX0 = fx0 >> CELL_SHIFT;
        cellY0 = fy0 >> CELL_SHIFT;
        cellsW = (fx1 >> CELL_SHIFT) - cellX0 + 1;
        cellsH = (fy1 >> CELL_SHIFT) - cellY0 + 1;
        int cells = cellsW * cellsH;
        int[] counts = new int[cells + 1];
        for (Feature f : arr) {
            for (int cy = (f.minY >> CELL_SHIFT) - cellY0; cy <= (f.maxY >> CELL_SHIFT) - cellY0; cy++) {
                for (int cx = (f.minX >> CELL_SHIFT) - cellX0; cx <= (f.maxX >> CELL_SHIFT) - cellX0; cx++) {
                    counts[cy * cellsW + cx + 1]++;
                }
            }
        }
        for (int i = 1; i <= cells; i++) {
            counts[i] += counts[i - 1];
        }
        cellStart = counts;
        cellItems = new int[counts[cells]];
        int[] fill = Arrays.copyOf(counts, cells);
        for (int fi = 0; fi < arr.length; fi++) {
            Feature f = arr[fi];
            for (int cy = (f.minY >> CELL_SHIFT) - cellY0; cy <= (f.maxY >> CELL_SHIFT) - cellY0; cy++) {
                for (int cx = (f.minX >> CELL_SHIFT) - cellX0; cx <= (f.maxX >> CELL_SHIFT) - cellX0; cx++) {
                    cellItems[fill[cy * cellsW + cx]++] = fi;
                }
            }
        }
    }

    public int size() {
        return features.length;
    }

    public int count(byte kindFrom, byte kindTo) {
        int n = 0;
        for (Feature f : features) {
            if (f.kind >= kindFrom && f.kind <= kindTo) {
                n++;
            }
        }
        return n;
    }

    /** Indices (ascending = drawing order) of features whose bbox meets the given mercator box. */
    public int[] query(int x0, int y0, int x1, int y1) {
        if (features.length == 0) {
            return new int[0];
        }
        int cx0 = Math.max(0, (x0 >> CELL_SHIFT) - cellX0);
        int cy0 = Math.max(0, (y0 >> CELL_SHIFT) - cellY0);
        int cx1 = Math.min(cellsW - 1, (x1 >> CELL_SHIFT) - cellX0);
        int cy1 = Math.min(cellsH - 1, (y1 >> CELL_SHIFT) - cellY0);
        if (cx0 > cx1 || cy0 > cy1) {
            return new int[0];
        }
        boolean[] seen = new boolean[features.length];
        int[] out = new int[64];
        int n = 0;
        for (int cy = cy0; cy <= cy1; cy++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                int c = cy * cellsW + cx;
                for (int k = cellStart[c]; k < cellStart[c + 1]; k++) {
                    int fi = cellItems[k];
                    if (seen[fi]) {
                        continue;
                    }
                    seen[fi] = true;
                    Feature f = features[fi];
                    if (f.maxX < x0 || f.minX > x1 || f.maxY < y0 || f.minY > y1) {
                        continue;
                    }
                    if (n == out.length) {
                        out = Arrays.copyOf(out, n * 2);
                    }
                    out[n++] = fi;
                }
            }
        }
        int[] res = Arrays.copyOf(out, n);
        Arrays.sort(res);
        return res;
    }

    // ------------------------------------------------------------------ projection

    public static int mercX(double lon) {
        double x = (lon + 180.0) / 360.0;
        return clamp(x);
    }

    public static int mercY(double lat) {
        double l = Math.max(-85.05112878, Math.min(85.05112878, lat));
        double s = Math.sin(Math.toRadians(l));
        double y = 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
        return clamp(y);
    }

    public static double lonOf(int x) {
        return x / (double) WORLD * 360.0 - 180.0;
    }

    public static double latOf(int y) {
        double n = Math.PI - 2.0 * Math.PI * y / (double) WORLD;
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    private static int clamp(double unit) {
        double v = unit * WORLD;
        if (v < 0) {
            return 0;
        }
        if (v > WORLD - 1) {
            return WORLD - 1;
        }
        return (int) Math.round(v);
    }

    // ------------------------------------------------------------------ file format

    public void save(File file) throws IOException {
        File tmp = new File(file.getPath() + ".tmp");
        OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16);
        try {
            DataOutputStream out = new DataOutputStream(os);
            out.write(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeLong(createdMs);
            out.writeDouble(south);
            out.writeDouble(west);
            out.writeDouble(north);
            out.writeDouble(east);
            out.writeUTF(source);
            out.writeInt(features.length);
            for (Feature f : features) {
                out.writeByte(f.kind);
                out.writeByte(f.area ? 1 : 0);
                out.writeUTF(f.name == null ? "" : f.name);
                out.writeShort(f.rings.length);
                for (int[] r : f.rings) {
                    out.writeInt(r.length / 2);
                    for (int v : r) {
                        out.writeInt(v);
                    }
                }
            }
            out.flush();
        } finally {
            os.close();
        }
        if (file.exists() && !file.delete()) {
            throw new IOException("cannot replace " + file);
        }
        if (!tmp.renameTo(file)) {
            throw new IOException("cannot write " + file);
        }
    }

    public static OfflineMap load(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            return read(in);
        } finally {
            in.close();
        }
    }

    public static OfflineMap read(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 1 << 16));
        byte[] magic = new byte[MAGIC.length];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("not an offline map file");
        }
        int version = in.readInt();
        if (version != FORMAT_VERSION) {
            throw new IOException("unsupported offline map version " + version);
        }
        long created = in.readLong();
        double s = in.readDouble();
        double w = in.readDouble();
        double n = in.readDouble();
        double e = in.readDouble();
        String source = in.readUTF();
        int count = in.readInt();
        if (count < 0 || count > 10_000_000) {
            throw new IOException("corrupt offline map");
        }
        List<Feature> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte kind = in.readByte();
            boolean area = in.readByte() != 0;
            String name = in.readUTF();
            int ringCount = in.readShort() & 0xffff;
            int[][] rings = new int[ringCount][];
            for (int r = 0; r < ringCount; r++) {
                int pts = in.readInt();
                if (pts < 0 || pts > 5_000_000) {
                    throw new IOException("corrupt offline map");
                }
                int[] ring = new int[pts * 2];
                for (int k = 0; k < ring.length; k++) {
                    ring[k] = in.readInt();
                }
                rings[r] = ring;
            }
            list.add(new Feature(kind, area, name, rings));
        }
        return new OfflineMap(s, w, n, e, created, source, Collections.unmodifiableList(list));
    }
}
