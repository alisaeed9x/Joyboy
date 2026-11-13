package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * MEETUBE(tv): the remote's highlight (issue #21). Out of touch mode - a D-pad, a TV remote, a keyboard -
 * the focused view of a window gets a ring around it, drawn in the window's overlay so no screen,
 * card or sheet row needs a focused state of its own. It follows the view as lists scroll and goes
 * the moment the screen is touched, so a phone used by touch never shows it. Every screen
 * (MobileActivity) and every sheet and dialog (Haptics.watch) installs one.
 *
 * <p>On a TV it also makes every clickable view focusable as the window lays out: phone screens set
 * click listeners on plain views (icons, rows, chips' arrow) that a touch reaches and a remote never
 * would.
 */
public final class FocusRing {
    private static final float STROKE_DP = 3f;
    /** Space between the view's edge and the ring. */
    private static final float GAP_DP = 3f;
    /** For a view whose outline has no radius of its own. */
    private static final float RADIUS_DP = 8f;

    private final ViewGroup mDecor;
    private final GradientDrawable mRing = new GradientDrawable();
    private final Outline mOutline = new Outline();
    private final Rect mBounds = new Rect();
    private final int[] mAt = new int[2];
    private final int[] mDecorAt = new int[2];
    private final float mDensity;
    @Nullable
    private View mFocused;
    private boolean mShown;
    @Nullable
    private static Boolean sTv;

    private FocusRing(ViewGroup decor) {
        mDecor = decor;
        mDensity = decor.getResources().getDisplayMetrics().density;
        mRing.setStroke(Math.round(STROKE_DP * mDensity),
                ContextCompat.getColor(decor.getContext(), R.color.mobile_color_on_surface));
        ViewTreeObserver observer = decor.getViewTreeObserver();
        observer.addOnGlobalFocusChangeListener((oldFocus, newFocus) -> place());
        observer.addOnTouchModeChangeListener(touch -> place());
        observer.addOnPreDrawListener(() -> {
            if (mShown || mFocused != null) {
                place(); // lists scroll and sheets slide: the ring keeps up
            }
            return true;
        });
        if (isTv(decor.getContext())) {
            observer.addOnGlobalLayoutListener(() -> makeClickablesFocusable(decor));
        }
    }

    /**
     * A sheet or dialog opens with the focus on its frame, so the ring went round the whole sheet and
     * the first press only reached its first row: on a TV the first row takes it at once.
     */
    public static void focusFirstRow(@Nullable Window window) {
        View decor = window != null ? window.peekDecorView() : null;
        if (decor == null || !isTv(decor.getContext())) {
            return;
        }
        focusFirstRow(decor, 10);
    }

    /** Tried again for a few frames: the rows have no size until the sheet's first layout. */
    private static void focusFirstRow(View decor, int tries) {
        decor.postDelayed(() -> {
            View focus = decor.findFocus();
            if (focus != null && focus.getWidth() > 0 && !hasFocusableInside(focus)) {
                return; // already on a row
            }
            for (View view : decor.getFocusables(View.FOCUS_DOWN)) {
                if (view.isShown() && view.getWidth() > 0 && !hasFocusableInside(view)
                        && view.requestFocus()) {
                    return;
                }
            }
            if (tries > 1) {
                focusFirstRow(decor, tries - 1);
            }
        }, 50);
    }

    private static boolean hasFocusableInside(View view) {
        if (!(view instanceof ViewGroup)) {
            return false;
        }
        java.util.ArrayList<View> inside = view.getFocusables(View.FOCUS_DOWN);
        return inside.size() > 1 || inside.size() == 1 && inside.get(0) != view;
    }

    /** An Android TV or a box: a TV feature, or no touchscreen at all. */
    public static boolean isTv(Context context) {
        if (sTv == null) {
            sTv = Helpers.isAndroidTV(context)
                    || !context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        }
        return sTv;
    }

    private static void makeClickablesFocusable(View view) {
        if (view.getVisibility() == View.GONE) {
            return;
        }
        // A sheet's touch_outside fills the screen: it closes the sheet on a tap, never a row to land on.
        if (view.isClickable() && !view.isFocusable()
                && view.getId() != com.google.android.material.R.id.touch_outside) {
            view.setFocusable(true);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                makeClickablesFocusable(group.getChildAt(i));
            }
        }
    }

    public static void install(@Nullable Window window) {
        View decor = window != null ? window.peekDecorView() : null;
        if (!(decor instanceof ViewGroup) || decor.getTag(R.id.focus_ring) != null) {
            return;
        }
        decor.setTag(R.id.focus_ring, new FocusRing((ViewGroup) decor));
    }

    private void place() {
        View focused = mDecor.isInTouchMode() ? null : mDecor.findFocus();
        mFocused = focused;
        if (focused == null || !focused.isShown() || focused.getWidth() == 0 || focused.getHeight() == 0) {
            hide();
            return;
        }
        int inset = Math.round((GAP_DP + STROKE_DP) * mDensity);
        focused.getLocationOnScreen(mAt);
        mDecor.getLocationOnScreen(mDecorAt);
        int left = mAt[0] - mDecorAt[0] - inset;
        int top = mAt[1] - mDecorAt[1] - inset;
        int right = left + focused.getWidth() + 2 * inset;
        int bottom = top + focused.getHeight() + 2 * inset;
        if (!mShown) {
            mDecor.getOverlay().add(mRing);
            mShown = true;
        }
        mBounds.set(left, top, right, bottom);
        if (!mRing.getBounds().equals(mBounds)) {
            mRing.setCornerRadius(radius(focused) + inset);
            mRing.setBounds(mBounds);
            mRing.invalidateSelf();
        }
    }

    /** The corner radius the view clips or draws its background with. */
    private float radius(View view) {
        ViewOutlineProvider provider = view.getOutlineProvider();
        if (provider != null) {
            mOutline.setEmpty();
            provider.getOutline(view, mOutline);
            float radius = mOutline.getRadius();
            if (radius > 0) {
                return radius;
            }
        }
        return RADIUS_DP * mDensity;
    }

    private void hide() {
        if (mShown) {
            mDecor.getOverlay().remove(mRing);
            mShown = false;
        }
    }
}
