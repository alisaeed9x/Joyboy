package com.newtube.mobile.ui.playback;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;

import com.newtube.mobile.ui.common.Haptics;

/**
 * MEETUBE(mini-float): the docked mini card floats free, like YouTube's Shorts mini player. Drag it
 * anywhere and it follows the finger; let go and it springs to the nearest corner of the screen,
 * the throw's direction counted. Flung hard sideways, or let go mostly past a side edge, it flies
 * off that side and the host closes the session (the old swipe to close). Taps are untouched: the
 * drag only claims a touch once it has moved past the touch slop, and then cancels the card's
 * pending click so the release does not also expand it.
 *
 * <p>The card's laid-out place is its home corner (bottom, end); the other corners are
 * translations from it. Every host puts the card home when it shows it again.</p>
 *
 * <p>Shared by Home's card (MobileBrowseActivity) and the Search/Channel one
 * (MobileMiniPlayerController); each host does its own closing in {@link Callback}.</p>
 */
public final class MiniCardSwipe implements View.OnTouchListener {
    public interface Callback {
        /** False while the card must not move (closing, entering, no session). */
        boolean canSwipe();

        /** Committed: fly the card to {@code toTranslationX} over {@code durationMs}, then close. */
        void onSwipedAway(float toTranslationX, long durationMs);

        /** A finger came down on the card (MEETUBE(mini-controls): its buttons come back). */
        default void onTouched() {
        }
    }

    private static final float FLING_VELOCITY_DP = 450f;
    /** A sideways throw this fast (and mostly sideways) closes the card instead of moving it. */
    private static final float CLOSE_VELOCITY_DP = 1800f;
    /** How far a throw carries when picking the corner, in seconds of its speed. */
    private static final float THROW_S = 0.18f;
    /** The corners keep clear of the top bar's pills. */
    private static final float TOP_CLEARANCE_DP = 80f;
    /** SystemUI's snap-back spring for a notification that did not go. */
    private static final float SNAP_STIFFNESS = 550f;
    private static final float SNAP_DAMPING = 0.75f;
    /** MEETUBE(issue #125): riding the nav bar, a bouncier spring - a small overshoot, then rest. */
    private static final float FOLLOW_STIFFNESS = 500f;
    private static final float FOLLOW_DAMPING = 0.55f;
    private static final long FLY_MIN_MS = 110;
    private static final long FLY_MAX_MS = 240;

    private final View mCard;
    private final Callback mCallback;
    private final int mTouchSlop;
    private final float mDensity;
    private final SpringAnimation mSnapX;
    private final SpringAnimation mSnapY;
    private float mDownRawX;
    private float mDownRawY;
    private float mStartX;
    private float mStartY;
    private boolean mDragging;
    /** MEETUBE(issue #125): home's translation, below its laid-out place while the nav bar is away. */
    private float mHomeY;
    /** The touch stopped being a drag (a second finger): consume it to the end. */
    private boolean mIgnoring;
    /** MEETUBE(issue #188): two fingers on the default card resize it (MiniSize.pinch). */
    private final ScaleGestureDetector mPinch;
    /** Resting at the top: it follows Home's top bar in and out (followTop). */
    private boolean mAtTop;
    private float mTopY = Float.NaN;
    /** Where it was let go (above home): Home's top bar coming back pushes it below the chips. */
    private float mRestY = Float.NaN;
    @Nullable
    private VelocityTracker mVelocity;

    /** Returns the drag, so the host can {@link #stop} it before moving the card itself. */
    public static MiniCardSwipe attach(View card, Callback callback, View... touchTargets) {
        MiniCardSwipe swipe = new MiniCardSwipe(card, callback);
        for (View target : touchTargets) {
            if (target != null) {
                target.setOnTouchListener(swipe);
            }
        }
        return swipe;
    }

    /**
     * The host is about to move the card itself (close, fold, show, enter): stop the springs,
     * which a card.animate().cancel() does not reach, and ignore the rest of a touch still on it.
     */
    public void stop() {
        if (mDragging) {
            mDragging = false;
            mIgnoring = true;
            recycleVelocity();
        }
        mSnapX.cancel();
        mSnapY.cancel();
        mAtTop = false; // the host moves it now
        mRestY = Float.NaN;
    }

    public float homeY() {
        return mHomeY;
    }

    /**
     * MEETUBE(issue #125): home moves with the nav bar under it (slid off by a scroll, NavHider). A
     * card resting at a bottom corner rides along; one at the top, held, or closing stays put.
     */
    public void setHomeY(float homeY) {
        mHomeY = homeY;
        View parent = (View) mCard.getParent();
        if (mDragging || !Float.isNaN(mRestY) || parent == null || mCard.getVisibility() != View.VISIBLE || !mCallback.canSwipe()
                || mCard.getTop() + mCard.getHeight() / 2f + mCard.getTranslationY() <= parent.getHeight() / 2f) {
            return;
        }
        mSnapY.getSpring().setStiffness(FOLLOW_STIFFNESS).setDampingRatio(FOLLOW_DAMPING);
        mSnapY.animateToFinalPosition(homeY);
    }

    private MiniCardSwipe(View card, Callback callback) {
        mCard = card;
        mCallback = callback;
        mTouchSlop = ViewConfiguration.get(card.getContext()).getScaledTouchSlop();
        mDensity = card.getResources().getDisplayMetrics().density;
        mSnapX = spring(DynamicAnimation.TRANSLATION_X);
        mSnapY = spring(DynamicAnimation.TRANSLATION_Y);
        mPinch = new ScaleGestureDetector(card.getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                MiniSize.pinch(mCard, detector.getScaleFactor());
                return true;
            }
        });
        card.getViewTreeObserver().addOnPreDrawListener(() -> {
            followTop();
            return true;
        });
    }

    private SpringAnimation spring(DynamicAnimation.ViewProperty property) {
        SpringAnimation spring = new SpringAnimation(mCard, property, 0f);
        spring.getSpring().setStiffness(SNAP_STIFFNESS).setDampingRatio(SNAP_DAMPING);
        return spring;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View view, MotionEvent event) {
        boolean pinchable = MiniSize.type(mCard.getContext()) == MiniSize.DEFAULT;
        if (pinchable) {
            mPinch.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mCallback.onTouched();
                mDownRawX = event.getRawX();
                mDownRawY = event.getRawY();
                mDragging = false;
                mIgnoring = false;
                recycleVelocity();
                mVelocity = VelocityTracker.obtain();
                trackVelocity(event);
                return false; // the view still sees the press (ripple, click)
            case MotionEvent.ACTION_MOVE: {
                if (mIgnoring) {
                    return true;
                }
                trackVelocity(event);
                float dx = event.getRawX() - mDownRawX;
                float dy = event.getRawY() - mDownRawY;
                if (!mDragging) {
                    if (Math.hypot(dx, dy) <= mTouchSlop || !mCallback.canSwipe()) {
                        return false;
                    }
                    mDragging = true;
                    mAtTop = false;
                    mRestY = Float.NaN;
                    // Drop the press and its pending click: this touch is a drag now.
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    view.onTouchEvent(cancel);
                    cancel.recycle();
                    ViewParent parent = mCard.getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                    mCard.animate().cancel();
                    mSnapX.cancel();
                    mSnapY.cancel();
                    mStartX = mCard.getTranslationX();
                    mStartY = mCard.getTranslationY();
                }
                mCard.setTranslationX(mStartX + dx);
                mCard.setTranslationY(mStartY + dy);
                mCard.setAlpha(alphaFor());
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (mIgnoring) {
                    mIgnoring = false;
                    return true;
                }
                if (!mDragging) {
                    recycleVelocity();
                    return false;
                }
                trackVelocity(event);
                float velocityX = 0f;
                float velocityY = 0f;
                if (mVelocity != null) {
                    mVelocity.computeCurrentVelocity(1000);
                    velocityX = mVelocity.getXVelocity();
                    velocityY = mVelocity.getYVelocity();
                }
                recycleVelocity();
                mDragging = false;
                release(velocityX, velocityY);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                // A second finger: not a drag any more. The card settles; the rest is ignored.
                if (mDragging) {
                    mDragging = false;
                    mIgnoring = true;
                    recycleVelocity();
                    snapToCorner(0f, 0f);
                } else if (pinchable && !mIgnoring) {
                    mIgnoring = true; // a pinch, not a tap: drop the press so letting go does not expand
                    recycleVelocity();
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    view.onTouchEvent(cancel);
                    cancel.recycle();
                }
                return mIgnoring;
            case MotionEvent.ACTION_CANCEL:
                recycleVelocity();
                mIgnoring = false;
                if (mDragging) {
                    mDragging = false;
                    snapToCorner(0f, 0f);
                    return true;
                }
                return false;
            default:
                return mDragging;
        }
    }

    private void release(float velocityX, float velocityY) {
        float overhang = sideOverhang();
        boolean thrownAway = Math.abs(velocityX) > CLOSE_VELOCITY_DP * mDensity
                && Math.abs(velocityX) > Math.abs(velocityY) * 1.5f;
        if ((thrownAway || Math.abs(overhang) > mCard.getWidth() / 2f) && mCallback.canSwipe()) {
            float direction = thrownAway ? Math.signum(velocityX) : Math.signum(overhang);
            Haptics.threshold(mCard, true);
            // Off that side of the screen: at least the card's width plus its distance to the edge.
            float target = direction * mCard.getResources().getDisplayMetrics().widthPixels;
            float remaining = Math.abs(target - mCard.getTranslationX());
            float speed = Math.max(Math.abs(velocityX), FLING_VELOCITY_DP * mDensity * 2f);
            long durationMs = Math.round(1000f * remaining / speed);
            mCallback.onSwipedAway(target, Math.max(FLY_MIN_MS, Math.min(FLY_MAX_MS, durationMs)));
            return;
        }
        snapToCorner(velocityX, velocityY);
    }

    /**
     * To the corner nearest where the throw would carry the card. Home is its laid-out place; the
     * other side and the top are translations, measured in its parent.
     */
    private void snapToCorner(float velocityX, float velocityY) {
        View parent = (View) mCard.getParent();
        if (parent == null) {
            return;
        }
        int margin = mCard.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                ? Math.max(((ViewGroup.MarginLayoutParams) mCard.getLayoutParams()).rightMargin, 0) : 0;
        // Home is at one side; the other side sits the same margin in from the other edge.
        float homeLeft = mCard.getLeft();
        float otherSide = homeLeft + mCard.getWidth() / 2f < parent.getWidth() / 2f
                ? parent.getWidth() - margin - mCard.getRight() : margin - homeLeft;
        float top = topClearance(parent) - mCard.getTop();

        float centerX = homeLeft + mCard.getWidth() / 2f + mCard.getTranslationX() + velocityX * THROW_S;
        float homeCenterX = homeLeft + mCard.getWidth() / 2f;
        float x = Math.abs(centerX - homeCenterX) < Math.abs(centerX - (homeCenterX + otherSide)) ? 0f : otherSide;
        // MEETUBE(issue #188): it rests where it is let go (thrown a little further), anywhere from
        // the top spot down to home; near either end it snaps there.
        float y = Math.max(top, Math.min(mHomeY, mCard.getTranslationY() + velocityY * THROW_S));
        float snap = 48 * mDensity;
        if (top >= 0f || mHomeY - y < snap) {
            y = mHomeY;
        } else if (y - top < snap) {
            y = top;
        }
        mAtTop = y == top;
        mRestY = y == mHomeY ? Float.NaN : y; // NaN: at home, riding with the nav bar

        mCard.setAlpha(1f);
        mSnapX.setStartVelocity(velocityX);
        mSnapX.animateToFinalPosition(x);
        mSnapY.getSpring().setStiffness(SNAP_STIFFNESS).setDampingRatio(SNAP_DAMPING); // after a nav ride's bounce
        mSnapY.setStartVelocity(velocityY);
        mSnapY.animateToFinalPosition(y);
        mTopY = y;
    }

    /**
     * MEETUBE(issue #188): a card resting above home moves with Home's top bar: the top spot follows
     * it (under the chips, or under the status bar once it scrolls away), and one left higher than
     * the chips' bottom is pushed below them while they show.
     */
    private void followTop() {
        View parent = (View) mCard.getParent();
        if (mDragging || parent == null || mCard.getVisibility() != View.VISIBLE || Float.isNaN(mRestY)) {
            return;
        }
        float top = topClearance(parent) - mCard.getTop();
        float y = mAtTop ? top : Math.max(mRestY, top);
        if (Math.abs(y - mTopY) < 1f) {
            return;
        }
        mTopY = y;
        mSnapY.getSpring().setStiffness(FOLLOW_STIFFNESS).setDampingRatio(FOLLOW_DAMPING);
        mSnapY.animateToFinalPosition(y);
    }

    /**
     * MEETUBE(issue #188): the top spot sits under Home's chips when they show (a bar or a big card
     * at a fixed 80dp landed on them), right under the status bar once Home's top bar has scrolled
     * away, and TOP_CLEARANCE_DP down on other screens.
     */
    private float topClearance(View parent) {
        float fallback = TOP_CLEARANCE_DP * mDensity;
        View chips = parent.getRootView().findViewById(com.liskovsoft.smartyoutubetv2.tv.R.id.mobile_chip_track);
        if (chips == null) {
            return fallback; // not Home: under the screen's own top bar
        }
        int[] chipsAt = new int[2];
        int[] parentAt = new int[2];
        chips.getLocationInWindow(chipsAt);
        parent.getLocationInWindow(parentAt);
        android.view.WindowInsets insets = parent.getRootWindowInsets();
        int status = insets != null ? insets.getSystemWindowInsetTop() : 0;
        float topmost = status - parentAt[1] + 8 * mDensity; // Home's top bar scrolled away: right under the status bar
        float chipsBottom = chipsAt[1] + chips.getHeight();
        if (!chips.isShown() || chips.getAlpha() == 0f || chipsBottom <= status) {
            return topmost;
        }
        return Math.max(topmost, chipsBottom - parentAt[1] + 8 * mDensity);
    }

    /** How far past a side edge of its parent the card hangs (negative: the start side). */
    private float sideOverhang() {
        View parent = (View) mCard.getParent();
        if (parent == null) {
            return 0f;
        }
        float left = mCard.getLeft() + mCard.getTranslationX();
        float right = left + mCard.getWidth();
        if (left < 0f) {
            return left;
        }
        return Math.max(0f, right - parent.getWidth());
    }

    /** The card fades as it hangs past a side edge, a hint that letting go there closes it. */
    private float alphaFor() {
        float fraction = Math.min(1f, Math.abs(sideOverhang()) / Math.max(1, mCard.getWidth()));
        return 1f - 0.85f * fraction;
    }

    private void trackVelocity(MotionEvent event) {
        if (mVelocity == null) {
            return;
        }
        // Raw coordinates: the card moves under the finger, so view-local ones would lag.
        MotionEvent raw = MotionEvent.obtain(event);
        raw.setLocation(event.getRawX(), event.getRawY());
        mVelocity.addMovement(raw);
        raw.recycle();
    }

    private void recycleVelocity() {
        if (mVelocity != null) {
            mVelocity.recycle();
            mVelocity = null;
        }
    }
}
