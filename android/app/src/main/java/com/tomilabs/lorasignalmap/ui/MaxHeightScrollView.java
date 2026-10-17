package com.tomilabs.lorasignalmap.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

/** ScrollView that wraps its content but never grows past a maximum height. */
public final class MaxHeightScrollView extends ScrollView {
    private int maxHeight = Integer.MAX_VALUE;

    public MaxHeightScrollView(Context context) {
        super(context);
    }

    public MaxHeightScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setMaxHeight(int px) {
        if (px != maxHeight) {
            maxHeight = px;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int mode = MeasureSpec.getMode(heightMeasureSpec);
        int size = MeasureSpec.getSize(heightMeasureSpec);
        int limit = mode == MeasureSpec.UNSPECIFIED ? maxHeight : Math.min(size, maxHeight);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
    }
}
