package com.newtube.mobile.ui.common;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.HorizontalScrollView;

/**
 * MEETUBE(watch actions): a row whose fading edge is its full length once a few dp are hidden on
 * that side, by a scroll or by the bounce that pulls the pills past the edge. The stock fade scales
 * with the distance scrolled and ignores the bounce, so the edge showed a hard cut, not a shade.
 */
public class ShadeScrollView extends HorizontalScrollView {
    public ShadeScrollView(Context context) {
        super(context);
    }

    public ShadeScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected float getLeftFadingEdgeStrength() {
        View row = getChildAt(0);
        float hidden = getScrollX() - (row != null ? row.getTranslationX() : 0f);
        return ramp(hidden);
    }

    @Override
    protected float getRightFadingEdgeStrength() {
        View row = getChildAt(0);
        if (row == null) {
            return 0f;
        }
        float hidden = row.getRight() + row.getTranslationX() - getScrollX() - (getWidth() - getPaddingRight());
        return ramp(hidden);
    }

    /** Full strength once a few dp are hidden (scrolled, or the row's bounce pulling it past the edge). */
    private float ramp(float hiddenPx) {
        float full = 8 * getResources().getDisplayMetrics().density;
        return Math.max(0f, Math.min(1f, hiddenPx / full));
    }
}
