package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

/**
 * The video-area container that recognizes YouTube's pinch-zoom gesture (pinch out = fill the
 * screen, pinch in = back to original) on top of whatever children are showing.
 *
 * <p>Detection lives HERE, on the container, and not inside
 * {@link com.github.vkay94.dtpv3.DoubleTapPlayerViewImpl}: when the controls overlay is visible it
 * is clickable and consumes every touch before the player view sees it, so a pinch must be caught
 * at the parent level to work in both overlay states (like the official app). The layout only
 * takes over the gesture once a SECOND finger lands ({@code ACTION_POINTER_DOWN}); interception
 * then cancels the children automatically, so a pending single-tap (controls toggle), double-tap
 * (seek) or pressed state is dropped cleanly. Single-finger gestures are never touched.</p>
 *
 * <p>The listener gets a discrete event each time the cumulative pinch span crosses the trigger
 * ratio, and the accumulator resets, so one long gesture can toggle fill -> original -> fill,
 * matching YouTube's mid-gesture snapping.</p>
 *
 * <p>MEETUBE(issue #55): past the snap (or where there is none: the portrait player, Shorts) the
 * pinch zooms the {@link #setZoomTarget target} freely, up to {@link #MAX_ZOOM}, around the
 * fingers, and two fingers pan it; it never shows more than the picture's own edges. Pinching
 * back to 1x lets a further pinch in snap to original again.</p>
 */
public class PinchZoomLayout extends FrameLayout {

    public interface PinchListener {
        /**
         * {@code zoomIn} true = pinched out (fill), false = pinched in (original).
         *
         * @return false when there was nothing to snap to (already there): a pinch out then zooms
         *         freely
         */
        boolean onPinchZoom(boolean zoomIn);

        /** The free zoom changed ({@code zoom} 1 = none). */
        void onZoomChanged(float zoom);
    }

    private static final float MAX_ZOOM = 5f;

    /** Span change (vs the reference watermark) that triggers a zoom event. */
    private static final float PINCH_TRIGGER_RATIO = 1.15f;

    private final ScaleGestureDetector mScaleDetector;
    @Nullable
    private PinchListener mListener;
    private boolean mPinchEnabled;
    private float mAccumulatedSpan = 1f;
    /** Watermark the trigger ratio is measured against: gesture-start span, then the extreme
     *  reached after the last fire, so reversing direction needs a REAL 15% back-track and
     *  continuing in the fired direction never re-fires (no chip flicker mid-pinch). */
    private float mSpanRef = 1f;
    /** Direction fired last in this gesture: 0 none, 1 zoom-in, -1 zoom-out. */
    private int mLastDirection;
    /** The free zoom's view (the video's content frame) and its zoom, 1 = none. */
    @Nullable
    private View mZoomTarget;
    private float mZoom = 1f;
    private float mFocusX;
    private float mFocusY;
    private final Rect mZoomBox = new Rect();
    private boolean mSnapEnabled;

    /** MEETUBE(shorts): the child whose picture's edges fade into this box's background. */
    @Nullable
    private View mFadeChild;
    /** The picture inside it (the video's frame), whose edges fade; null: the child's own. */
    @Nullable
    private View mFadePicture;
    private int mFadePx;
    private final Paint mFadePaint = new Paint();
    /** Where the fades were last laid out: the picture's box clipped to the child's. */
    private final Rect mFadeEdges = new Rect();
    private final Rect mFadeAt = new Rect();
    private final RectF mFadePictureAt = new RectF();
    private LinearGradient mFadeTopShader;
    private LinearGradient mFadeBottomShader;
    private LinearGradient mFadeLeftShader;
    private LinearGradient mFadeRightShader;
    private final OnLayoutChangeListener mFadeRelayout = (v, l, t, r, b, ol, ot, or, ob) -> invalidate();

    public PinchZoomLayout(Context context) {
        this(context, null);
    }

    public PinchZoomLayout(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PinchZoomLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mFadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        mScaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        mAccumulatedSpan = 1f;
                        mSpanRef = 1f;
                        mLastDirection = 0;
                        mFocusX = detector.getFocusX();
                        mFocusY = detector.getFocusY();
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        if (mZoom < 1.05f && mZoom != 1f) {
                            resetZoom(); // a hair over 1x is a pinch that meant none
                        }
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float factor = detector.getScaleFactor();
                        // After a snap to fill the gesture stays the snap's (its grow animation
                        // runs); the next pinch zooms on.
                        if (mZoomTarget != null && (mZoom > 1f || !mSnapEnabled)) {
                            zoomBy(factor, detector.getFocusX(), detector.getFocusY());
                            if (mZoom == 1f) { // back at 1x: a further pinch in may snap again
                                mAccumulatedSpan = 1f;
                                mSpanRef = 1f;
                                mLastDirection = 0;
                            }
                            return true;
                        }
                        mFocusX = detector.getFocusX();
                        mFocusY = detector.getFocusY();
                        mAccumulatedSpan *= factor;
                        float ratio = mAccumulatedSpan / mSpanRef;
                        if (ratio >= PINCH_TRIGGER_RATIO) {
                            if (mLastDirection != 1 && mListener != null && !mListener.onPinchZoom(true)
                                    && mZoomTarget != null) {
                                zoomBy(ratio, mFocusX, mFocusY); // already filled: zoom on from here
                            }
                            mLastDirection = 1;
                            mSpanRef = mAccumulatedSpan;
                        } else if (ratio <= 1f / PINCH_TRIGGER_RATIO) {
                            if (mLastDirection != -1 && mListener != null) {
                                mListener.onPinchZoom(false);
                            }
                            mLastDirection = -1;
                            mSpanRef = mAccumulatedSpan;
                        } else if (mLastDirection == 1) {
                            mSpanRef = Math.max(mSpanRef, mAccumulatedSpan);
                        } else if (mLastDirection == -1) {
                            mSpanRef = Math.min(mSpanRef, mAccumulatedSpan);
                        }
                        return true;
                    }
                });
        // No press-and-hold delay before scaling starts: a pinch should track the fingers at once.
        mScaleDetector.setQuickScaleEnabled(false);
    }

    /**
     * NEWTUBE(motion): claims a whole touch for the player's tap detector before any child can
     * hit-test it - the second tap of a double tap whose first tap just revealed the controls, which
     * would otherwise land on whatever control appeared under it (back, gear, the seek bar).
     */
    public interface TapRouter {
        boolean claimDown(MotionEvent down);

        void route(MotionEvent event);
    }

    @Nullable
    private TapRouter mTapRouter;
    private boolean mRoutingTap;

    public void setTapRouter(@Nullable TapRouter router) {
        mTapRouter = router;
    }

    public void setPinchListener(@Nullable PinchListener listener) {
        mListener = listener;
    }

    public void setPinchEnabled(boolean enabled) {
        mPinchEnabled = enabled;
    }

    /** Whether a pinch first snaps between fill and original (fullscreen) before it zooms. */
    public void setSnapEnabled(boolean enabled) {
        mSnapEnabled = enabled;
    }

    public void setZoomTarget(@Nullable View target) {
        resetZoom();
        mZoomTarget = target;
    }

    /** Back to 1x, centred: a new video, a turn of the phone, a new resize mode. */
    public void resetZoom() {
        if (mZoom == 1f) {
            return;
        }
        mZoom = 1f;
        if (mZoomTarget != null) {
            mZoomTarget.setScaleX(1f);
            mZoomTarget.setScaleY(1f);
            mZoomTarget.setTranslationX(0f);
            mZoomTarget.setTranslationY(0f);
        }
        invalidate(); // the edge fade follows the picture
    }

    /**
     * Zooms by {@code factor} keeping the picture under the fingers' focus there, and pans by how
     * far the focus moved since the last step. The target scales about its centre; it may only
     * move as far as keeps its zoomed picture over its box (the player view), or centred in it
     * where the picture is the smaller.
     */
    private void zoomBy(float factor, float focusX, float focusY) {
        View target = mZoomTarget;
        View box = (View) target.getParent();
        if (target.getWidth() == 0 || box == null) {
            return;
        }
        float zoom = Math.max(1f, Math.min(MAX_ZOOM, mZoom * factor));
        float f = zoom / mZoom;
        mZoomBox.set(0, 0, box.getWidth(), box.getHeight());
        offsetDescendantRectToMyCoords(box, mZoomBox);
        float cx = mZoomBox.left + target.getLeft() + target.getWidth() / 2f;
        float cy = mZoomBox.top + target.getTop() + target.getHeight() / 2f;
        float tx = (focusX - cx) - f * (focusX - cx - target.getTranslationX()) + (focusX - mFocusX);
        float ty = (focusY - cy) - f * (focusY - cy - target.getTranslationY()) + (focusY - mFocusY);
        float maxX = Math.max(0f, (zoom * target.getWidth() - box.getWidth()) / 2f);
        float maxY = Math.max(0f, (zoom * target.getHeight() - box.getHeight()) / 2f);
        mFocusX = focusX;
        mFocusY = focusY;
        target.animate().cancel();
        target.setScaleX(zoom);
        target.setScaleY(zoom);
        target.setTranslationX(zoom == 1f ? 0f : Math.max(-maxX, Math.min(maxX, tx)));
        target.setTranslationY(zoom == 1f ? 0f : Math.max(-maxY, Math.min(maxY, ty)));
        invalidate(); // the edge fade follows the picture
        if (zoom != mZoom) {
            mZoom = zoom;
            if (mListener != null) {
                mListener.onZoomChanged(zoom);
            }
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mRoutingTap = mTapRouter != null && mTapRouter.claimDown(ev);
            if (mRoutingTap) {
                return true; // the rest of this touch comes to onTouchEvent
            }
        }
        if (!mPinchEnabled || mListener == null) {
            return super.onInterceptTouchEvent(ev);
        }
        // Every event passes through exactly one of intercept/onTouch, so feeding the detector
        // from both keeps it on a single consistent stream.
        mScaleDetector.onTouchEvent(ev);
        // Second finger down = this is a pinch; take the gesture (children get ACTION_CANCEL).
        return ev.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mRoutingTap && mTapRouter != null) {
            mTapRouter.route(ev);
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mRoutingTap = false;
            }
            return true;
        }
        if (!mPinchEnabled || mListener == null) {
            return super.onTouchEvent(ev);
        }
        mScaleDetector.onTouchEvent(ev);
        return true;
    }
    /**
     * MEETUBE(shorts): fade the edges of {@code picture} (inside {@code child}) {@code px} into this
     * box's background (the ambient fill), like Instagram's reels - no hard line where the video
     * meets the blur. A picture as big as the child (a zoomed short on a phone) fades at the
     * child's top and bottom; one narrower (a whole short on a tablet) fades at its sides too.
     * Null switches it off. Only that child fades; the overlays above it stay sharp.
     */
    public void setEdgeFade(@Nullable View child, @Nullable View picture, int px) {
        if (child != mFadeChild || picture != mFadePicture || px != mFadePx) {
            mFadeChild = child;
            if (mFadePicture != null) {
                mFadePicture.removeOnLayoutChangeListener(mFadeRelayout);
            }
            mFadePicture = picture;
            if (picture != null) {
                picture.addOnLayoutChangeListener(mFadeRelayout); // its shape is known late
            }
            mFadePx = px;
            mFadeEdges.setEmpty();
            invalidate();
        }
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (child != mFadeChild || mFadePx <= 0 || child.getHeight() <= 0) {
            return super.drawChild(canvas, child, drawingTime);
        }
        int left = child.getLeft();
        int top = child.getTop();
        int right = child.getRight();
        int bottom = child.getBottom();
        mFadeAt.set(left, top, right, bottom);
        if (mFadePicture != null && mFadePicture.getWidth() > 0 && mFadePicture.isAttachedToWindow()) {
            // MEETUBE(issue #117): through each view's matrix, so a zoomed or panned picture fades
            // at its real edges (offsetDescendantRectToMyCoords ignores scale and translation).
            RectF picture = mFadePictureAt;
            picture.set(0, 0, mFadePicture.getWidth(), mFadePicture.getHeight());
            for (View v = mFadePicture; v != this && v.getParent() instanceof View; v = (View) v.getParent()) {
                v.getMatrix().mapRect(picture);
                picture.offset(v.getLeft() - v.getScrollX(), v.getTop() - v.getScrollY());
            }
            if (!mFadeAt.intersect(Math.round(picture.left), Math.round(picture.top),
                    Math.round(picture.right), Math.round(picture.bottom))) {
                mFadeAt.set(left, top, right, bottom);
            }
        }
        int fadeY = Math.min(mFadePx, mFadeAt.height() / 8);
        int fadeX = Math.min(mFadePx, mFadeAt.width() / 8);
        if (!mFadeAt.equals(mFadeEdges)) {
            mFadeEdges.set(mFadeAt);
            Rect e = mFadeEdges;
            mFadeTopShader = fade(0, e.top, 0, e.top + fadeY);
            mFadeBottomShader = fade(0, e.bottom, 0, e.bottom - fadeY);
            mFadeLeftShader = fade(e.left, 0, e.left + fadeX, 0);
            mFadeRightShader = fade(e.right, 0, e.right - fadeX, 0);
        }
        Rect e = mFadeEdges;
        int save = canvas.saveLayer(left, top, right, bottom, null);
        boolean more = super.drawChild(canvas, child, drawingTime);
        mFadePaint.setShader(mFadeTopShader);
        canvas.drawRect(e.left, e.top, e.right, e.top + fadeY, mFadePaint);
        mFadePaint.setShader(mFadeBottomShader);
        canvas.drawRect(e.left, e.bottom - fadeY, e.right, e.bottom, mFadePaint);
        if (e.left > left) {
            mFadePaint.setShader(mFadeLeftShader);
            canvas.drawRect(e.left, e.top, e.left + fadeX, e.bottom, mFadePaint);
        }
        if (e.right < right) {
            mFadePaint.setShader(mFadeRightShader);
            canvas.drawRect(e.right - fadeX, e.top, e.right, e.bottom, mFadePaint);
        }
        canvas.restoreToCount(save);
        return more;
    }

    /** Erases fully at the edge (x0, y0) and not at all at (x1, y1), eased at both ends so no line shows. */
    private static LinearGradient fade(float x0, float y0, float x1, float y1) {
        return new LinearGradient(x0, y0, x1, y1,
                new int[] {0xFF000000, 0xD8000000, 0x80000000, 0x28000000, 0},
                new float[] {0f, 0.25f, 0.5f, 0.75f, 1f}, Shader.TileMode.CLAMP);
    }
}
