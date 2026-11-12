package com.newtube.mobile.ui.common;

import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * MEETUBE(nav hide, issue #37): YouTube's bottom bar. Scrolling a list down slides the floating nav
 * pill off the bottom; scrolling back up, or reaching the top, brings it back. Only a translation:
 * the pill keeps its layout, so nothing under it moves and its taps and gestures stay as they were.
 */
public final class NavHider extends RecyclerView.OnScrollListener {
    private static final long DURATION_MS = 220;

    private final View mBar;
    /** MEETUBE(issue #181): a bar along the top (the top pills) slides up and away instead of down. */
    private final boolean mTop;
    private final int mSlopPx;
    private boolean mHidden;
    private boolean mHeld;
    private int mTravel; // signed scroll distance since the last change of direction
    @Nullable private java.util.function.Consumer<Float> mOnSlide;
    @Nullable private Morph mMorph;

    /**
     * MEETUBE(search fab): plays the bar's going and coming itself (the bar shrinking into a
     * Search bubble and back); false = not this time, the usual slide runs.
     */
    public interface Morph {
        boolean hide(float travel);

        boolean show();
    }

    public NavHider(View bar) {
        this(bar, false);
    }

    public NavHider(View bar, boolean top) {
        mBar = bar;
        mTop = top;
        mSlopPx = Math.round(24 * bar.getResources().getDisplayMetrics().density);
    }

    @Override
    public void onScrolled(@NonNull RecyclerView list, int dx, int dy) {
        if (!list.canScrollVertically(-1)) {
            show();
            return;
        }
        if (dy == 0) {
            return;
        }
        mTravel = Integer.signum(dy) == Integer.signum(mTravel) ? mTravel + dy : dy;
        if (mTravel > mSlopPx) {
            hide();
        } else if (mTravel < -mSlopPx) {
            show();
        }
    }

    /** MEETUBE(issue #125): told where the bar slides to (0 back, its travel away), for what rides on it. */
    public void setOnSlide(@Nullable java.util.function.Consumer<Float> onSlide) {
        mOnSlide = onSlide;
    }

    public void setMorph(@Nullable Morph morph) {
        mMorph = morph;
    }

    /** MEETUBE(shorts, issue #107): held, the bar stays down whatever the list does. */
    public void hold(boolean held) {
        mHeld = held;
        if (held) {
            hide();
        } else {
            show();
        }
    }

    /** A new page, or the list back at its top: the bar again. */
    public void show() {
        mTravel = 0;
        if (mHeld || !mHidden) {
            return;
        }
        mHidden = false;
        mBar.animate().cancel();
        if (mMorph == null || !mMorph.show()) {
            mBar.animate().translationY(0f).alpha(1f).setDuration(DURATION_MS)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        if (mOnSlide != null) {
            mOnSlide.accept(0f);
        }
    }

    private void hide() {
        if (mHidden) {
            return;
        }
        mHidden = true;
        int margin = mBar.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                ? ((ViewGroup.MarginLayoutParams) mBar.getLayoutParams()).bottomMargin : 0;
        float travel = mTop ? -mBar.getBottom() : mBar.getHeight() + margin;
        mBar.animate().cancel();
        if (mMorph == null || !mMorph.hide(travel)) {
            mBar.animate().translationY(travel).alpha(0f).setDuration(DURATION_MS)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        if (mOnSlide != null) {
            mOnSlide.accept(travel);
        }
    }
}
