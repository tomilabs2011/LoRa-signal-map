package com.tomilabs.lorasignalmap.map;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

/** The repeater's position: a round badge with an antenna icon and a label. */
public final class RepeaterOverlay extends Overlay {
    private final float density;
    private final Drawable icon;
    private final String label;
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Point p = new Point();
    private GeoPoint position;

    public RepeaterOverlay(float density, Drawable icon, String label) {
        this.density = density;
        this.icon = icon;
        this.label = label;
        badge.setColor(0xFF0D47A1);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFFFFFFFF);
        ring.setStrokeWidth(2.5f * density);
        text.setColor(0xFF0D47A1);
        text.setTextSize(12 * density);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextAlign(Paint.Align.CENTER);
        halo.set(text);
        halo.setColor(0xFFFFFFFF);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(3.5f * density);
    }

    public void setPosition(GeoPoint gp) {
        position = gp;
    }

    public GeoPoint getPosition() {
        return position;
    }

    @Override
    public void draw(Canvas c, Projection pj) {
        if (position == null || !isEnabled()) {
            return;
        }
        pj.toPixels(position, p);
        float r = 15 * density;
        c.drawCircle(p.x, p.y, r, badge);
        c.drawCircle(p.x, p.y, r, ring);
        if (icon != null) {
            int s = (int) (11 * density);
            icon.setBounds(p.x - s, p.y - s, p.x + s, p.y + s);
            icon.draw(c);
        }
        float ty = p.y + r + 15 * density;
        c.drawText(label, p.x, ty, halo);
        c.drawText(label, p.x, ty, text);
    }
}
