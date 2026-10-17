package com.tomilabs.lorasignalmap.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import com.tomilabs.lorasignalmap.map.ColorScale;

/** Color bar from worst (red) to best (green), plus the "no reply" marker. */
public final class LegendView extends View {
    private final float d;
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint failFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint failRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private String worstLabel = "−135";
    private String bestLabel = "−80";
    private String unit = "dBm";
    private String failLabel = "no reply";
    private int lastWidth = -1;

    public LegendView(Context context) {
        this(context, null);
    }

    public LegendView(Context context, AttributeSet attrs) {
        super(context, attrs);
        d = getResources().getDisplayMetrics().density;
        text.setColor(0xFF5B6676);
        text.setTextSize(11 * d);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        failFill.setColor(0xD9FFFFFF);
        failRing.setStyle(Paint.Style.STROKE);
        failRing.setStrokeWidth(2.2f * d);
        failRing.setColor(ColorScale.FAILED);
    }

    public void setScale(double worst, double best, String unit) {
        this.worstLabel = Fmt.num(worst, 1);
        this.bestLabel = Fmt.num(best, 1);
        this.unit = unit;
        invalidate();
    }

    public void setFailLabel(String label) {
        failLabel = label;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float padH = 12 * d;
        float failW = text.measureText(failLabel) + 18 * d;
        float barLeft = padH;
        float barRight = getWidth() - padH - failW - 10 * d;
        if (barRight - barLeft < 40 * d) {
            barRight = getWidth() - padH;
            failW = 0;
        }
        float barTop = 9 * d;
        float barH = 9 * d;
        if (lastWidth != getWidth() || bar.getShader() == null) {
            lastWidth = getWidth();
            int[] colors = new int[9];
            for (int i = 0; i < colors.length; i++) {
                colors[i] = ColorScale.colorAt(i / (double) (colors.length - 1));
            }
            bar.setShader(new LinearGradient(barLeft, 0, barRight, 0, colors, null, Shader.TileMode.CLAMP));
        }
        rect.set(barLeft, barTop, barRight, barTop + barH);
        c.drawRoundRect(rect, barH / 2, barH / 2, bar);
        float ty = barTop + barH + 15 * d;
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(worstLabel, barLeft, ty, text);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(bestLabel + " " + unit, barRight, ty, text);
        if (failW > 0) {
            float cx = barRight + 10 * d + 7 * d;
            float cy = barTop + barH / 2;
            c.drawCircle(cx, cy, 5.5f * d, failFill);
            c.drawCircle(cx, cy, 5.5f * d, failRing);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText(failLabel, barRight + 10 * d, ty, text);
        }
    }
}
