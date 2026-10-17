package com.tomilabs.lorasignalmap.offline;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Draws one map tile from an {@link OfflineMap}. Thread-safe: all state is per call. */
public final class VectorTileRenderer {
    public static final int LAND = 0xFFF2EFE9;

    private VectorTileRenderer() {
    }

    private static int fill(byte kind) {
        switch (kind) {
            case OfflineMap.RESIDENTIAL:
                return 0xFFE8E4DF;
            case OfflineMap.INDUSTRIAL:
                return 0xFFE7E1EA;
            case OfflineMap.FARM:
                return 0xFFEEF0D9;
            case OfflineMap.FOREST:
                return 0xFFCDE2C3;
            case OfflineMap.GREEN:
                return 0xFFDCEDCF;
            case OfflineMap.CEMETERY:
                return 0xFFD5E6CC;
            case OfflineMap.SPORT:
                return 0xFFD5EDD2;
            case OfflineMap.PARKING:
                return 0xFFECE9E4;
            case OfflineMap.WETLAND:
                return 0xFFD6E9E4;
            case OfflineMap.WATER:
                return 0xFFAAD3DF;
            case OfflineMap.BUILDING:
                return 0xFFDCD3CB;
            case OfflineMap.MAJOR:
                return 0xFFF3CF9F;
            case OfflineMap.PRIMARY:
                return 0xFFF6DEAE;
            case OfflineMap.SECONDARY:
                return 0xFFF8EBC4;
            default:
                return 0xFFFFFFFF;
        }
    }

    /** Smallest zoom at which a kind is drawn. */
    static int minZoom(byte kind) {
        switch (kind) {
            case OfflineMap.BUILDING:
            case OfflineMap.SERVICE:
                return 14;
            case OfflineMap.PATH:
                return 15;
            case OfflineMap.MINOR:
            case OfflineMap.PARKING:
            case OfflineMap.SPORT:
                return 12;
            case OfflineMap.TERTIARY:
            case OfflineMap.RAIL:
                return 11;
            case OfflineMap.STREAM:
                return 13;
            default:
                return 0;
        }
    }

    /** Road width in dp at zoom 16. */
    private static float roadDp(byte kind) {
        switch (kind) {
            case OfflineMap.MAJOR:
                return 9f;
            case OfflineMap.PRIMARY:
                return 8f;
            case OfflineMap.SECONDARY:
                return 7f;
            case OfflineMap.TERTIARY:
                return 6f;
            case OfflineMap.MINOR:
                return 5f;
            case OfflineMap.SERVICE:
                return 3f;
            default:
                return 1.6f;
        }
    }

    private static final class Ctx {
        final int z;
        final long x0;
        final long y0;
        final double scale;
        final int size;
        final float d;
        final float zf;

        Ctx(int z, int tx, int ty, int size, float density) {
            this.z = z;
            long span = 1L << (30 - Math.min(z, 30));
            this.x0 = tx * span;
            this.y0 = ty * span;
            this.scale = size / (double) span;
            this.size = size;
            this.d = density;
            this.zf = (float) Math.max(0.22, Math.min(2.2, Math.pow(2, (z - 16) * 0.75)));
        }

        float px(int x) {
            return (float) ((x - x0) * scale);
        }

        float py(int y) {
            return (float) ((y - y0) * scale);
        }
    }

    /** Renders tile (z, x, y) into a size x size bitmap. {@code map} may be null (blank land). */
    public static Bitmap render(OfflineMap map, int z, int tx, int ty, int size, float density) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(LAND);
        if (map == null || map.size() == 0 || z > 30) {
            return bmp;
        }
        Ctx k = new Ctx(z, tx, ty, size, density);
        long span = 1L << (30 - z);
        long margin = span / 8 + 1;
        int[] idx = map.query((int) Math.max(0, k.x0 - margin), (int) Math.max(0, k.y0 - margin),
                (int) Math.min(OfflineMap.WORLD - 1, k.x0 + span + margin),
                (int) Math.min(OfflineMap.WORLD - 1, k.y0 + span + margin));

        Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
        area.setStyle(Paint.Style.FILL);
        Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        outline.setStyle(Paint.Style.STROKE);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        Path path = new Path();

        List<OfflineMap.Feature> roads = new ArrayList<>();
        List<OfflineMap.Feature> places = new ArrayList<>();
        for (int fi : idx) {
            OfflineMap.Feature f = map.features[fi];
            if (z < minZoom(f.kind)) {
                continue;
            }
            if (f.isRoad()) {
                roads.add(f);
                continue;
            }
            if (f.isPlace()) {
                places.add(f);
                continue;
            }
            double w = (f.maxX - f.minX) * k.scale;
            double h = (f.maxY - f.minY) * k.scale;
            if (w < 1.2 && h < 1.2) {
                continue; // smaller than a pixel
            }
            if (f.area) {
                buildPath(path, f, k, true);
                area.setColor(fill(f.kind));
                c.drawPath(path, area);
                if (f.kind == OfflineMap.BUILDING && z >= 16) {
                    outline.setColor(0xFFC6BAB0);
                    outline.setStrokeWidth(Math.max(1f, 0.6f * density));
                    c.drawPath(path, outline);
                } else if ((f.kind == OfflineMap.SPORT || f.kind == OfflineMap.PARKING) && z >= 15) {
                    outline.setColor(f.kind == OfflineMap.SPORT ? 0xFFB9D8B5 : 0xFFDDD8D0);
                    outline.setStrokeWidth(Math.max(1f, 0.6f * density));
                    c.drawPath(path, outline);
                }
            } else if (f.kind == OfflineMap.RIVER || f.kind == OfflineMap.STREAM) {
                buildPath(path, f, k, false);
                line.setPathEffect(null);
                line.setColor(0xFFA0CBDC);
                float wdp = f.kind == OfflineMap.RIVER ? 4.5f : 1.8f;
                line.setStrokeWidth(Math.max(1f, wdp * density * k.zf));
                c.drawPath(path, line);
            } else if (f.kind == OfflineMap.RAIL) {
                buildPath(path, f, k, false);
                float wpx = Math.max(1.2f, 2.2f * density * Math.min(1f, k.zf));
                line.setPathEffect(null);
                line.setColor(0xFF9A9A9A);
                line.setStrokeWidth(wpx);
                c.drawPath(path, line);
                if (z >= 14) {
                    line.setColor(0xFFFFFFFF);
                    line.setStrokeWidth(wpx * 0.45f);
                    line.setPathEffect(new DashPathEffect(new float[]{6 * density, 6 * density}, 0));
                    c.drawPath(path, line);
                    line.setPathEffect(null);
                }
            }
        }

        // roads: all casings first, then fills, so junctions merge cleanly
        line.setPathEffect(null);
        for (OfflineMap.Feature f : roads) {
            if (f.kind == OfflineMap.PATH || z < 13) {
                continue;
            }
            buildPath(path, f, k, false);
            float wpx = roadWidth(f.kind, k);
            line.setColor(0xFFBFB7AB);
            line.setStrokeWidth(wpx + 2 * Math.max(1f, 0.9f * density * Math.min(1f, k.zf)));
            c.drawPath(path, line);
        }
        for (OfflineMap.Feature f : roads) {
            buildPath(path, f, k, false);
            if (f.kind == OfflineMap.PATH) {
                line.setColor(0xFFB08D72);
                line.setStrokeWidth(Math.max(1f, 1.4f * density * Math.min(1f, k.zf)));
                line.setPathEffect(new DashPathEffect(new float[]{4 * density, 3 * density}, 0));
                c.drawPath(path, line);
                line.setPathEffect(null);
                continue;
            }
            int col = fill(f.kind);
            if (z < 13 && col == 0xFFFFFFFF) {
                col = 0xFFD8D2C8; // thin white roads vanish on land at low zoom
            }
            line.setColor(col);
            line.setStrokeWidth(roadWidth(f.kind, k));
            c.drawPath(path, line);
        }

        drawLabels(c, k, roads, places);
        return bmp;
    }

    private static float roadWidth(byte kind, Ctx k) {
        return Math.max(1f, roadDp(kind) * k.d * k.zf);
    }

    private static void buildPath(Path path, OfflineMap.Feature f, Ctx k, boolean close) {
        path.rewind();
        path.setFillType(Path.FillType.EVEN_ODD);
        for (int[] r : f.rings) {
            int n = r.length / 2;
            if (n == 0) {
                continue;
            }
            float lx = k.px(r[0]);
            float ly = k.py(r[1]);
            path.moveTo(lx, ly);
            for (int i = 1; i < n; i++) {
                float x = k.px(r[i * 2]);
                float y = k.py(r[i * 2 + 1]);
                if (i < n - 1 && Math.abs(x - lx) + Math.abs(y - ly) < 0.7f) {
                    continue; // sub-pixel step
                }
                path.lineTo(x, y);
                lx = x;
                ly = y;
            }
            if (close) {
                path.close();
            }
        }
    }

    // ------------------------------------------------------------------ labels

    private static void drawLabels(Canvas c, Ctx k, List<OfflineMap.Feature> roads,
            List<OfflineMap.Feature> places) {
        List<RectF> taken = new ArrayList<>();
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(0xFF2F3437);
        Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        halo.setColor(0xF2FFFFFF);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeJoin(Paint.Join.ROUND);

        // places first: they matter most for orientation
        for (int pass = 0; pass < 3; pass++) {
            byte want = pass == 0 ? OfflineMap.PLACE_TOWN : (pass == 1 ? OfflineMap.PLACE_VILLAGE : OfflineMap.PLACE_SMALL);
            for (OfflineMap.Feature f : places) {
                if (f.kind != want || f.name == null) {
                    continue;
                }
                float sp;
                boolean bold;
                if (want == OfflineMap.PLACE_TOWN) {
                    if (k.z > 15) {
                        continue;
                    }
                    sp = 17;
                    bold = true;
                } else if (want == OfflineMap.PLACE_VILLAGE) {
                    if (k.z < 11 || k.z > 16) {
                        continue;
                    }
                    sp = 14;
                    bold = true;
                } else {
                    if (k.z < 13 || k.z > 17) {
                        continue;
                    }
                    sp = 12;
                    bold = false;
                }
                text.setTextSize(sp * k.d);
                text.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
                text.setColor(want == OfflineMap.PLACE_SMALL ? 0xFF55595E : 0xFF2A2D30);
                halo.setTextSize(text.getTextSize());
                halo.setTypeface(text.getTypeface());
                halo.setStrokeWidth(3.5f * k.d);
                float x = k.px(f.rings[0][0]);
                float y = k.py(f.rings[0][1]);
                float w = text.measureText(f.name);
                float h = text.getTextSize();
                RectF box = new RectF(x - w / 2 - 3 * k.d, y - h, x + w / 2 + 3 * k.d, y + h * 0.35f);
                if (!inside(box, k.size, 2) || overlaps(box, taken)) {
                    continue;
                }
                taken.add(box);
                text.setTextAlign(Paint.Align.CENTER);
                halo.setTextAlign(Paint.Align.CENTER);
                c.drawText(f.name, x, y, halo);
                c.drawText(f.name, x, y, text);
            }
        }

        // street names along the road
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        text.setTextAlign(Paint.Align.LEFT);
        halo.setTextAlign(Paint.Align.LEFT);
        text.setColor(0xFF3E4246);
        Map<String, Integer> perName = new HashMap<>();
        int maxPerName = k.z >= 17 ? 2 : 1;
        // major roads first
        for (int i = roads.size() - 1; i >= 0; i--) {
            OfflineMap.Feature f = roads.get(i);
            if (f.name == null || f.kind == OfflineMap.PATH) {
                continue;
            }
            int minZ = f.kind >= OfflineMap.SECONDARY ? 14 : (f.kind == OfflineMap.SERVICE ? 17 : 15);
            if (k.z < minZ) {
                continue;
            }
            Integer used = perName.get(f.name);
            if (used != null && used >= maxPerName) {
                continue;
            }
            float sp = f.kind >= OfflineMap.SECONDARY ? 12.5f : 11.5f;
            text.setTextSize(sp * k.d);
            halo.setTextSize(text.getTextSize());
            halo.setTypeface(text.getTypeface());
            halo.setStrokeWidth(3f * k.d);
            if (placeRoadLabel(c, k, f, text, halo, taken)) {
                perName.put(f.name, used == null ? 1 : used + 1);
            }
        }
    }

    private static boolean placeRoadLabel(Canvas c, Ctx k, OfflineMap.Feature f, Paint text, Paint halo,
            List<RectF> taken) {
        int[] r = f.rings[0];
        int n = r.length / 2;
        if (n < 2) {
            return false;
        }
        Path fwd = new Path();
        Path rev = new Path();
        fwd.moveTo(k.px(r[0]), k.py(r[1]));
        rev.moveTo(k.px(r[(n - 1) * 2]), k.py(r[(n - 1) * 2 + 1]));
        for (int i = 1; i < n; i++) {
            fwd.lineTo(k.px(r[i * 2]), k.py(r[i * 2 + 1]));
            int j = n - 1 - i;
            rev.lineTo(k.px(r[j * 2]), k.py(r[j * 2 + 1]));
        }
        PathMeasure pm = new PathMeasure(fwd, false);
        float len = pm.getLength();
        float tw = text.measureText(f.name);
        float pad = 6 * k.d;
        if (len < tw + 2 * pad) {
            return false;
        }
        // candidate positions: centred in each stretch of the road that lies inside this tile
        float[] pos = new float[2];
        float[] tan = new float[2];
        float inner = text.getTextSize();
        float step = Math.max(6f, len / 3000f);
        List<float[]> runs = new ArrayList<>();
        float runStart = -1;
        for (float d = 0; d <= len + step; d += step) {
            float at = Math.min(d, len);
            pm.getPosTan(at, pos, null);
            boolean in = pos[0] >= inner && pos[1] >= inner && pos[0] <= k.size - inner && pos[1] <= k.size - inner;
            if (in && runStart < 0) {
                runStart = at;
            } else if ((!in || at >= len) && runStart >= 0) {
                runs.add(new float[]{runStart, at});
                runStart = -1;
            }
            if (at >= len) {
                break;
            }
        }
        java.util.Collections.sort(runs, new java.util.Comparator<float[]>() {
            @Override
            public int compare(float[] a, float[] b) {
                return Float.compare(b[1] - b[0], a[1] - a[0]);
            }
        });
        List<Float> starts = new ArrayList<>();
        for (float[] run : runs) {
            float room = run[1] - run[0];
            if (room < tw + 2 * pad) {
                continue;
            }
            starts.add(run[0] + (room - tw) / 2);
            starts.add(run[0] + pad);
            starts.add(run[1] - pad - tw);
        }
        for (float start : starts) {
            if (start < 0 || start + tw > len) {
                continue;
            }
            // sample the stretch: inside the tile, not too curvy
            boolean ok = true;
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            double firstAngle = 0;
            for (int s = 0; s <= 6 && ok; s++) {
                pm.getPosTan(start + tw * s / 6f, pos, tan);
                double a = Math.atan2(tan[1], tan[0]);
                if (s == 0) {
                    firstAngle = a;
                } else {
                    double diff = Math.abs(angleDiff(a, firstAngle));
                    if (diff > Math.toRadians(28)) {
                        ok = false;
                    }
                }
                minX = Math.min(minX, pos[0]);
                maxX = Math.max(maxX, pos[0]);
                minY = Math.min(minY, pos[1]);
                maxY = Math.max(maxY, pos[1]);
            }
            if (!ok) {
                continue;
            }
            float h = text.getTextSize() * 0.75f;
            RectF box = new RectF(minX - h, minY - h, maxX + h, maxY + h);
            if (!inside(box, k.size, 1) || overlaps(box, taken)) {
                continue;
            }
            // keep text upright: run it along whichever direction points to the right
            float[] p0 = new float[2];
            float[] p1 = new float[2];
            pm.getPosTan(start, p0, null);
            pm.getPosTan(start + tw, p1, null);
            Path use = fwd;
            float off = start;
            if (p1[0] < p0[0]) {
                use = rev;
                off = len - start - tw;
            }
            float v = text.getTextSize() * 0.35f;
            c.drawTextOnPath(f.name, use, off, v, halo);
            c.drawTextOnPath(f.name, use, off, v, text);
            taken.add(box);
            return true;
        }
        return false;
    }

    private static double angleDiff(double a, double b) {
        double d = a - b;
        while (d > Math.PI) {
            d -= 2 * Math.PI;
        }
        while (d < -Math.PI) {
            d += 2 * Math.PI;
        }
        // a road segment pointing backwards is the same line
        if (d > Math.PI / 2) {
            d -= Math.PI;
        } else if (d < -Math.PI / 2) {
            d += Math.PI;
        }
        return d;
    }

    private static boolean inside(RectF r, int size, float margin) {
        return r.left >= margin && r.top >= margin && r.right <= size - margin && r.bottom <= size - margin;
    }

    private static boolean overlaps(RectF r, List<RectF> taken) {
        for (RectF t : taken) {
            if (RectF.intersects(r, t)) {
                return true;
            }
        }
        return false;
    }
}
