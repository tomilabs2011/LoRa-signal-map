package com.tomilabs.lorasignalmap.map;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.MotionEvent;

import com.tomilabs.lorasignalmap.model.Measurement;
import com.tomilabs.lorasignalmap.model.SignalMath;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Draws every beacon as a colored dot (failed beacons as dark red rings) and, optionally, the
 * walked track with colors blending smoothly from one beacon to the next.
 */
public final class SignalOverlay extends Overlay {
    public interface Listener {
        void onPointsTapped(List<Measurement> hits);

        void onEmptyTap();
    }

    private static final long CONNECT_MAX_GAP_MS = 60_000;
    private static final double CONNECT_MAX_DIST_M = 400;

    private static final class Pt {
        final Measurement m;
        final GeoPoint gp;
        int color;
        boolean connect;

        Pt(Measurement m) {
            this.m = m;
            this.gp = new GeoPoint(m.lat, m.lon);
        }
    }

    private final float density;
    private final List<Pt> history = new ArrayList<>();
    private final List<Pt> live = new ArrayList<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint failFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint failRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Point pa = new Point();
    private final Point pb = new Point();
    private final Point touch = new Point();
    private final Rect view = new Rect();

    private ColorScale scale = new ColorScale(-135, -80);
    private boolean bySnr;
    private int direction = SignalMath.DIR_WEAKEST;
    private boolean showTrack = true;
    private boolean showFailed = true;
    private GeoPoint selection;
    private Listener listener;

    public SignalOverlay(float density) {
        this.density = density;
        fill.setStyle(Paint.Style.FILL);
        outline.setStyle(Paint.Style.STROKE);
        outline.setColor(0xFFFFFFFF);
        outline.setStrokeWidth(1.5f * density);
        failFill.setStyle(Paint.Style.FILL);
        failFill.setColor(0xD9FFFFFF);
        failRing.setStyle(Paint.Style.STROKE);
        failRing.setColor(ColorScale.FAILED);
        failRing.setStrokeWidth(2.4f * density);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeWidth(4f * density);
        highlight.setStyle(Paint.Style.STROKE);
        highlight.setColor(0xFF1565C0);
        highlight.setStrokeWidth(3f * density);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void configure(ColorScale scale, boolean bySnr, int direction, boolean showTrack, boolean showFailed) {
        this.scale = scale;
        this.bySnr = bySnr;
        this.direction = direction;
        this.showTrack = showTrack;
        this.showFailed = showFailed;
        recolor(history);
        recolor(live);
    }

    /** Value used for coloring, or null for failed beacons. */
    public Double valueOf(Measurement m) {
        return bySnr ? SignalMath.snr(m, direction) : SignalMath.signal(m, direction);
    }

    public int colorOf(Measurement m) {
        if (!m.ok) {
            return ColorScale.FAILED;
        }
        return scale.color(valueOf(m));
    }

    public void setHistory(List<Measurement> ms) {
        history.clear();
        Pt prev = null;
        for (Measurement m : ms) {
            if (!m.hasPosition()) {
                continue;
            }
            Pt p = new Pt(m);
            p.connect = connects(prev, p);
            history.add(p);
            prev = p;
        }
        recolor(history);
    }

    public void setLive(List<Measurement> ms) {
        live.clear();
        for (Measurement m : ms) {
            addLive(m);
        }
    }

    public void addLive(Measurement m) {
        if (!m.hasPosition()) {
            return;
        }
        Pt p = new Pt(m);
        p.connect = connects(live.isEmpty() ? null : live.get(live.size() - 1), p);
        p.color = colorOf(m);
        live.add(p);
    }

    public int visibleCount() {
        return history.size() + live.size();
    }

    /** All plotted points, for zoom-to-fit. */
    public List<GeoPoint> allPoints() {
        List<GeoPoint> out = new ArrayList<>(history.size() + live.size());
        for (Pt p : history) {
            out.add(p.gp);
        }
        for (Pt p : live) {
            out.add(p.gp);
        }
        return out;
    }

    public void setSelection(GeoPoint gp) {
        selection = gp;
    }

    private static boolean connects(Pt prev, Pt cur) {
        if (prev == null) {
            return false;
        }
        Measurement a = prev.m;
        Measurement b = cur.m;
        if (!eq(a.session, b.session) || !eq(a.boot, b.boot)) {
            return false;
        }
        long dt = b.t - a.t;
        if (dt <= 0 || dt > CONNECT_MAX_GAP_MS) {
            return false;
        }
        return SignalMath.distanceM(a.lat, a.lon, b.lat, b.lon) <= CONNECT_MAX_DIST_M;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private void recolor(List<Pt> pts) {
        for (Pt p : pts) {
            p.color = colorOf(p.m);
        }
    }

    private float radius(double zoom) {
        float dp;
        if (zoom >= 17) {
            dp = 7f;
        } else if (zoom >= 16) {
            dp = 6f;
        } else if (zoom >= 15) {
            dp = 5f;
        } else if (zoom >= 14) {
            dp = 4.2f;
        } else if (zoom >= 13) {
            dp = 3.4f;
        } else {
            dp = 2.6f;
        }
        return dp * density;
    }

    @Override
    public void draw(Canvas c, Projection pj) {
        if (!isEnabled()) {
            return;
        }
        double zoom = pj.getZoomLevel();
        float r = radius(zoom);
        view.set(pj.getIntrinsicScreenRect());
        int margin = (int) (r * 3);
        view.inset(-margin, -margin);
        if (showTrack) {
            line.setStrokeWidth((zoom >= 15 ? 4f : 3f) * density);
            drawTrack(c, pj, history);
            drawTrack(c, pj, live);
        }
        drawPoints(c, pj, history, r);
        drawPoints(c, pj, live, r);
        if (selection != null) {
            pj.toPixels(selection, pa);
            c.drawCircle(pa.x, pa.y, r + 6 * density, highlight);
        }
    }

    private void drawTrack(Canvas c, Projection pj, List<Pt> pts) {
        Pt prev = null;
        for (Pt p : pts) {
            if (p.connect && prev != null) {
                pj.toPixels(prev.gp, pa);
                pj.toPixels(p.gp, pb);
                if (segmentVisible(pa, pb)) {
                    int ca = prev.m.ok ? prev.color : ColorScale.RED;
                    int cb = p.m.ok ? p.color : ColorScale.RED;
                    drawGradientLine(c, pa.x, pa.y, pb.x, pb.y, ca, cb);
                }
            }
            prev = p;
        }
    }

    private boolean segmentVisible(Point a, Point b) {
        return !(Math.max(a.x, b.x) < view.left || Math.min(a.x, b.x) > view.right
                || Math.max(a.y, b.y) < view.top || Math.min(a.y, b.y) > view.bottom);
    }

    private void drawGradientLine(Canvas c, float x0, float y0, float x1, float y1, int ca, int cb) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        double len = Math.hypot(dx, dy);
        if (len < 0.5) {
            return;
        }
        int n = ca == cb ? 1 : (int) Math.max(1, Math.min(14, len / (5 * density)));
        for (int k = 0; k < n; k++) {
            float t0 = (float) k / n;
            float t1 = (float) (k + 1) / n;
            int col = ColorScale.lerp(ca, cb, (t0 + t1) / 2);
            line.setColor((col & 0x00FFFFFF) | 0xC8000000);
            c.drawLine(x0 + dx * t0, y0 + dy * t0, x0 + dx * t1, y0 + dy * t1, line);
        }
    }

    private void drawPoints(Canvas c, Projection pj, List<Pt> pts, float r) {
        for (Pt p : pts) {
            if (!p.m.ok && !showFailed) {
                continue;
            }
            pj.toPixels(p.gp, pa);
            if (!view.contains(pa.x, pa.y)) {
                continue;
            }
            if (p.m.ok) {
                fill.setColor(p.color);
                c.drawCircle(pa.x, pa.y, r, fill);
                c.drawCircle(pa.x, pa.y, r, outline);
            } else {
                float rr = r * 0.8f;
                c.drawCircle(pa.x, pa.y, rr, failFill);
                c.drawCircle(pa.x, pa.y, rr, failRing);
            }
        }
    }

    /** Measurements drawn within a finger's reach of the given screen point, newest first. */
    public List<Measurement> hitTest(float x, float y, Projection pj) {
        pj.rotateAndScalePoint((int) x, (int) y, touch);
        float r = Math.max(22 * density, radius(pj.getZoomLevel()) * 1.8f);
        float r2 = r * r;
        List<Measurement> hits = new ArrayList<>();
        collect(history, pj, r2, hits);
        collect(live, pj, r2, hits);
        Collections.sort(hits, new Comparator<Measurement>() {
            @Override
            public int compare(Measurement a, Measurement b) {
                return Long.compare(b.t, a.t);
            }
        });
        return hits;
    }

    private void collect(List<Pt> pts, Projection pj, float r2, List<Measurement> out) {
        for (Pt p : pts) {
            if (!p.m.ok && !showFailed) {
                continue;
            }
            pj.toPixels(p.gp, pa);
            float dx = pa.x - touch.x;
            float dy = pa.y - touch.y;
            if (dx * dx + dy * dy <= r2) {
                out.add(p.m);
            }
        }
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e, MapView mapView) {
        List<Measurement> hits = hitTest(e.getX(), e.getY(), mapView.getProjection());
        if (!hits.isEmpty()) {
            Measurement first = hits.get(0);
            selection = new GeoPoint(first.lat, first.lon);
            mapView.invalidate();
            if (listener != null) {
                listener.onPointsTapped(hits);
            }
            return true;
        }
        if (selection != null) {
            selection = null;
            mapView.invalidate();
        }
        if (listener != null) {
            listener.onEmptyTap();
        }
        return false;
    }
}
