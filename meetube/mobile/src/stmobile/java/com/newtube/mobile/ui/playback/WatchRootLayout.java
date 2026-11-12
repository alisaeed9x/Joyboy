package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.NestedScrollingParent2;
import androidx.core.view.ViewCompat;

/**
 * NEWTUBE(seek bar): the watch page's column - the video box, then the page under it - drawing the
 * video box LAST. The seek bar's dot sits on the video's bottom edge like YouTube's, so half of it
 * hangs over the top of the page; drawn first, the box had that half painted over by the page's
 * opaque background. This and the video box leave their children unclipped (layout XML).
 *
 * <p>The two children never overlap, so touches go to one or the other - except the seek bar's
 * touch band, which straddles the video's edge: a {@link TouchRouter} claims those touches here,
 * before either child hit-tests them.</p>
 *
 * <p>MEETUBE(issue #36): it also sees the page's scrolls first, so a {@link PageScroll} can take
 * them to shrink or grow the video box before the page moves.</p>
 */
public class WatchRootLayout extends LinearLayout implements NestedScrollingParent2 {

    /**
     * Claims a whole touch before any child sees it (the seek bar's band, half of it over the page
     * that the video box never gets a touch from). Coordinates are this layout's.
     */
    public interface TouchRouter {
        boolean claimDown(MotionEvent down);

        void route(MotionEvent event);
    }

    /** Takes some of a page scroll before the page gets it. */
    public interface PageScroll {
        /** {@code dy} as the page would scroll it (positive = content up); returns what it took. */
        int onPageScroll(View page, int dy);
    }

    @Nullable
    private TouchRouter mTouchRouter;
    @Nullable
    private PageScroll mPageScroll;
    private boolean mRouting;

    public WatchRootLayout(Context context) {
        this(context, null);
    }

    public WatchRootLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setChildrenDrawingOrderEnabled(true);
    }

    public void setTouchRouter(@Nullable TouchRouter router) {
        mTouchRouter = router;
    }

    public void setPageScroll(@Nullable PageScroll pageScroll) {
        mPageScroll = pageScroll;
    }

    @Override
    public boolean onStartNestedScroll(@NonNull View child, @NonNull View target, int axes, int type) {
        return mPageScroll != null && (axes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
    }

    @Override
    public void onNestedScrollAccepted(@NonNull View child, @NonNull View target, int axes, int type) {
    }

    @Override
    public void onStopNestedScroll(@NonNull View target, int type) {
    }

    @Override
    public void onNestedScroll(@NonNull View target, int dxConsumed, int dyConsumed,
            int dxUnconsumed, int dyUnconsumed, int type) {
    }

    @Override
    public void onNestedPreScroll(@NonNull View target, int dx, int dy, @NonNull int[] consumed, int type) {
        if (mPageScroll != null && dy != 0) {
            consumed[1] = mPageScroll.onPageScroll(target, dy);
        }
    }

    @Override
    protected int getChildDrawingOrder(int childCount, int drawingPosition) {
        // 1, 2, ..., n-1, then 0: the first child (the video box) on top of the rest.
        return childCount < 2 ? drawingPosition : (drawingPosition + 1) % childCount;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mRouting = mTouchRouter != null && mTouchRouter.claimDown(ev);
        }
        // Once claimed, the rest of the touch comes straight to onTouchEvent.
        return mRouting || super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mRouting && mTouchRouter != null) {
            mTouchRouter.route(ev);
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mRouting = false;
            }
            return true;
        }
        return super.onTouchEvent(ev);
    }
}
