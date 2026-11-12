package com.newtube.mobile.ui.common;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.lang.reflect.Field;

/**
 * NEWTUBE(haptics): pull-to-refresh that you can feel - the faint grain while the spinner is pulled
 * ({@link Haptics#tension}), and one click the moment the pull passes the point where letting go
 * refreshes, the same click if it comes back under ({@link Haptics#threshold}); the platform names
 * pull-to-refresh as the example of exactly this (HapticFeedbackConstants.GESTURE_THRESHOLD_*).
 *
 * <p>SwipeRefreshLayout keeps how far it is pulled to itself, so this reads its two private fields
 * after each step of the pull (swiperefreshlayout 1.2.0: {@code mTotalUnconsumed} for a pull that
 * comes through the list's nested scrolling, {@code mInitialMotionY} for one it catches itself).
 * If a later version renames them, the pull just goes back to being silent.</p>
 *
 * <p>MEETUBE(loader): the circle that comes down shows the loader picked in Settings (the theme's
 * {@code meetubeLoader}, as every other spinner) on a see-through glass disc, in place of the stock arrow;
 * SwipeRefreshLayout still animates its own arrow drawable, which is just no longer drawn.</p>
 */
public class HapticSwipeRefreshLayout extends SwipeRefreshLayout {
    /** SwipeRefreshLayout.DRAG_RATE: its own touch pull moves the spinner at half the finger. */
    private static final float DRAG_RATE = 0.5f;
    /** Clicks closer together than this are the finger trembling on the line, not two crossings. */
    private static final long CLICK_GAP_MS = 120;

    private static boolean sLookedUp;
    @Nullable
    private static Field sTotalUnconsumed;
    @Nullable
    private static Field sTotalDragDistance;
    @Nullable
    private static Field sIsBeingDragged;
    @Nullable
    private static Field sInitialMotionY;
    @Nullable
    private static Field sActivePointerId;

    /** The pull is past the refresh point: letting go now refreshes. */
    private boolean mEngaged;
    private long mLastClickAt;

    public HapticSwipeRefreshLayout(@NonNull Context context) {
        this(context, null);
    }

    public HapticSwipeRefreshLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        useLoader(context);
    }

    /** Puts the picked loader into SwipeRefreshLayout's circle; left as the arrow if that fails. */
    private void useLoader(Context context) {
        TypedArray a = context.obtainStyledAttributes(new int[] {R.attr.meetubeLoader});
        Drawable loader = a.getDrawable(0);
        a.recycle();
        Field circleField = field("mCircleView");
        if (loader == null || circleField == null) {
            return;
        }
        try {
            ImageView circle = (ImageView) circleField.get(this);
            int pad = Math.round(4 * getResources().getDisplayMetrics().density);
            circle.setPadding(pad, pad, pad, pad);
            circle.setImageDrawable(loader);
            circle.setBackground(glass());
            circle.setElevation(0); // a shadow under see-through glass shows through it as a grey blot
            if (loader instanceof Animatable) {
                ((Animatable) loader).start(); // loops forever; drawn only while the circle shows
            }
        } catch (IllegalAccessException | RuntimeException e) {
            // keep the stock arrow
        }
    }

    @Override
    public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {
        super.onNestedPreScroll(target, dx, dy, consumed);
        follow(nestedPull());
    }

    @Override
    public void onNestedScroll(@NonNull View target, int dxConsumed, int dyConsumed, int dxUnconsumed,
            int dyUnconsumed, int type, @NonNull int[] consumed) {
        super.onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, consumed);
        follow(nestedPull());
    }

    @Override
    public void onStopNestedScroll(View target) {
        super.onStopNestedScroll(target);
        mEngaged = false; // released: it refreshes or springs back on its own, silently
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean handled = super.onTouchEvent(ev);
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mEngaged = false;
        } else if (action == MotionEvent.ACTION_MOVE) {
            follow(touchPull(ev));
        }
        return handled;
    }

    private void follow(float pull) {
        float distance = readFloat(sTotalDragDistance);
        if (pull < 0f || distance <= 0f || isRefreshing()) {
            return;
        }
        // The line itself is SwipeRefreshLayout's (it decides the refresh), so no dead zone around
        // it; a finger resting right on it clicks at most once per CLICK_GAP_MS instead.
        boolean engaged = pull > distance;
        if (engaged != mEngaged) {
            mEngaged = engaged;
            long now = SystemClock.uptimeMillis();
            if (now - mLastClickAt >= CLICK_GAP_MS) {
                mLastClickAt = now;
                Haptics.threshold(this, engaged);
            }
        } else if (!engaged) {
            Haptics.tension(this, pull / distance);
        }
    }

    /**
     * The disc as glass: a smoky tint (so the white loaders still read over a light page), a gloss
     * fading down from the top, and a thin bright rim. No blur: a view can't blur what is behind it.
     */
    private Drawable glass() {
        float dp = getResources().getDisplayMetrics().density;
        GradientDrawable smoke = new GradientDrawable();
        smoke.setShape(GradientDrawable.OVAL);
        smoke.setColor(0x47000000);
        GradientDrawable gloss = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x40FFFFFF, 0x00FFFFFF});
        gloss.setShape(GradientDrawable.OVAL);
        gloss.setStroke(Math.max(1, Math.round(dp)), 0x66FFFFFF);
        return new LayerDrawable(new Drawable[] {smoke, gloss});
    }

    /** How far a nested-scroll pull has gone (px), or -1 when unknown. */
    private float nestedPull() {
        lookUp();
        return readFloat(sTotalUnconsumed);
    }

    /**
     * How far a pull caught by this layout itself has gone (px), or -1 when it is not dragging.
     * Measured on the finger SwipeRefreshLayout follows: a second finger takes the pull over.
     */
    private float touchPull(MotionEvent ev) {
        lookUp();
        try {
            if (sIsBeingDragged == null || sInitialMotionY == null || sActivePointerId == null
                    || !sIsBeingDragged.getBoolean(this)) {
                return -1f;
            }
            int index = ev.findPointerIndex(sActivePointerId.getInt(this));
            if (index < 0) {
                return -1f;
            }
            return (ev.getY(index) - sInitialMotionY.getFloat(this)) * DRAG_RATE;
        } catch (IllegalAccessException e) {
            return -1f;
        }
    }

    private float readFloat(@Nullable Field field) {
        lookUp();
        if (field == null) {
            return -1f;
        }
        try {
            return field.getFloat(this);
        } catch (IllegalAccessException e) {
            return -1f;
        }
    }

    private static void lookUp() {
        if (sLookedUp) {
            return;
        }
        sLookedUp = true;
        sTotalUnconsumed = field("mTotalUnconsumed");
        sTotalDragDistance = field("mTotalDragDistance");
        sIsBeingDragged = field("mIsBeingDragged");
        sInitialMotionY = field("mInitialMotionY");
        sActivePointerId = field("mActivePointerId");
    }

    @Nullable
    private static Field field(String name) {
        try {
            Field field = SwipeRefreshLayout.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException | RuntimeException e) {
            return null;
        }
    }
}
