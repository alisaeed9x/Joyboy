package com.newtube.mobile.ui.playback;

import android.content.SharedPreferences;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * MEETUBE(lock, issue #124): the fullscreen lock and its Locked pill float - a drag puts them
 * anywhere over the video, a tap is still a tap. Both sit at one spot, kept in the player's UI
 * prefs as fractions of the room the video leaves them, so a rotation keeps the same corner.
 * The lock never covers a shown control: a drag slides along one, and a kept spot that a
 * control now covers (another orientation, the actions row) moves up or down until clear.
 */
final class FloatingLock {
    private static final String KEY_X = "lock_x";
    private static final String KEY_Y = "lock_y";
    private static final float DEFAULT_X = 0f; // the left edge, halfway down, until moved
    private static final float DEFAULT_Y = 0.5f;
    private static final float STEP = 0.05f;

    private final SharedPreferences mPrefs;
    private final View[] mObstacles;
    private final View[] mViews;
    private final int mMargin;
    private final int mGap;
    private final int mSlop;
    private final Rect mInsets = new Rect();
    private final Rect mLockRect = new Rect();
    private final Rect mOtherRect = new Rect();
    private final int[] mLoc = new int[2];
    /** Where the person put it... */
    private float mX;
    private float mY;
    /** ...and where it is, clear of the controls. */
    private float mPlaceX;
    private float mPlaceY;

    /** {@code views}: the lock button first (its size is the one kept clear), then the pill. */
    FloatingLock(SharedPreferences prefs, View[] obstacles, View... views) {
        mPrefs = prefs;
        mObstacles = obstacles;
        mViews = views;
        float density = views[0].getResources().getDisplayMetrics().density;
        mMargin = Math.round(16 * density);
        mGap = Math.round(4 * density);
        mSlop = ViewConfiguration.get(views[0].getContext()).getScaledTouchSlop();
        mX = prefs.getFloat(KEY_X, DEFAULT_X);
        mY = prefs.getFloat(KEY_Y, DEFAULT_Y);
        View.OnLayoutChangeListener relayout = (v, l, t, r, b, ol, ot, or, ob) -> placeAll();
        for (View view : views) {
            view.setOnTouchListener(new Drag());
            view.addOnLayoutChangeListener(relayout); // the pill's width once shown
            ((View) view.getParent()).addOnLayoutChangeListener(relayout); // a rotation, a control shown
        }
    }

    /** The bars and cutout the video box reaches under: the lock stays clear of them. */
    void setInsets(int left, int top, int right, int bottom) {
        mInsets.set(left, top, right, bottom);
        placeAll();
    }

    private void placeAll() {
        resolve();
        for (View view : mViews) {
            View parent = (View) view.getParent();
            view.setTranslationX(left(view, mPlaceX) - parent.getPaddingLeft());
            view.setTranslationY(top(view, mPlaceY) - parent.getPaddingTop());
        }
    }

    /** The clear spot nearest the kept one: up or down from it, else the default. */
    private void resolve() {
        mPlaceX = mX;
        mPlaceY = mY;
        if (!blocked(mX, mY)) {
            return;
        }
        for (float d = STEP; d <= 1f; d += STEP) {
            for (float y : new float[] {mY - d, mY + d}) {
                if (y >= 0f && y <= 1f && !blocked(mX, y)) {
                    mPlaceY = y;
                    return;
                }
            }
        }
        mPlaceX = DEFAULT_X;
        mPlaceY = DEFAULT_Y;
    }

    /** Whether the lock button at (x, y) would touch a shown control. */
    private boolean blocked(float x, float y) {
        View lock = mViews[0];
        ((View) lock.getParent()).getLocationInWindow(mLoc);
        int left = mLoc[0] + left(lock, x);
        int top = mLoc[1] + top(lock, y);
        mLockRect.set(left - mGap, top - mGap, left + lock.getWidth() + mGap, top + lock.getHeight() + mGap);
        for (View obstacle : mObstacles) {
            if (obstacle.getVisibility() != View.VISIBLE || obstacle.getWidth() == 0) {
                continue;
            }
            obstacle.getLocationInWindow(mLoc);
            mOtherRect.set(mLoc[0], mLoc[1], mLoc[0] + obstacle.getWidth(), mLoc[1] + obstacle.getHeight());
            if (Rect.intersects(mLockRect, mOtherRect)) {
                return true;
            }
        }
        return false;
    }

    private int left(View view, float x) {
        int width = ((View) view.getParent()).getWidth() - mInsets.left - mInsets.right;
        return Math.round(mInsets.left + mMargin + x * Math.max(0, width - 2 * mMargin - view.getWidth()));
    }

    private int top(View view, float y) {
        int height = ((View) view.getParent()).getHeight() - mInsets.top - mInsets.bottom;
        return Math.round(mInsets.top + mMargin + y * Math.max(0, height - 2 * mMargin - view.getHeight()));
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private final class Drag implements View.OnTouchListener {
        private float mDownX;
        private float mDownY;
        private float mStartX;
        private float mStartY;
        private boolean mDragging;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownX = event.getRawX();
                    mDownY = event.getRawY();
                    mStartX = mPlaceX;
                    mStartY = mPlaceY;
                    mDragging = false;
                    view.getParent().requestDisallowInterceptTouchEvent(true); // no swipe or pinch
                    view.setPressed(true);
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - mDownX;
                    float dy = event.getRawY() - mDownY;
                    if (!mDragging && Math.hypot(dx, dy) > mSlop) {
                        mDragging = true;
                        view.setPressed(false);
                    }
                    if (mDragging) {
                        float roomX = Math.max(1, left(view, 1f) - left(view, 0f));
                        float roomY = Math.max(1, top(view, 1f) - top(view, 0f));
                        float x = clamp(mStartX + dx / roomX);
                        float y = clamp(mStartY + dy / roomY);
                        // straight there if clear, else slide along the control in the way
                        if (!blocked(x, y)) {
                            mX = x;
                            mY = y;
                        } else if (!blocked(x, mPlaceY)) {
                            mX = x;
                            mY = mPlaceY;
                        } else if (!blocked(mPlaceX, y)) {
                            mX = mPlaceX;
                            mY = y;
                        } else {
                            break;
                        }
                        placeAll();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    view.setPressed(false);
                    if (mDragging) {
                        mPrefs.edit().putFloat(KEY_X, mX).putFloat(KEY_Y, mY).apply();
                    } else {
                        view.performClick();
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    break;
                default:
                    break;
            }
            return true;
        }
    }
}
