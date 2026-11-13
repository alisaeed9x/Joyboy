package com.newtube.mobile.ui.browse;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarItemView;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.misc.VideoDownloads;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Motion;
import com.newtube.mobile.ui.common.NavLens;

/**
 * MEETUBE(nav bloom, issue #47): a soft glow behind the lit tab's icon, the bar's background. On a
 * switch it slides to the new tab, dimming as it travels and changing colour on the way. The lit
 * icon and its name take the glow's colour. Settings > Customization > Tab glow picks the colour
 * (plain white unless changed) or turns it off; it runs with or without Liquid glass.
 */
public final class NavBloom extends Drawable {
    public static final int MODE_OFF = 0;
    public static final int MODE_TABS = 1;
    public static final int MODE_ACCENT = 2;
    public static final int MODE_WHITE = 3;
    private static final String PREFS = "meetube_app";
    private static final String KEY_MODE = "nav_bloom";
    private static final long GLIDE_MS = 360;
    private static final float RADIUS_DP = 30f;
    /** The glow is an ellipse this much flatter than wide: it stays inside the bar. */
    private static final float FLATTEN = 0.8f;
    /** The glow's heart, ~55% of its colour. */
    private static final int HEART_ALPHA = 0x8C;
    /** The glow's strength at evenly spaced steps out from its heart. */
    private static final float[] FALLOFF = {1f, 0.92f, 0.74f, 0.52f, 0.32f, 0.17f, 0.07f, 0.02f, 0f};
    private static int sMode = -1;

    private final BottomNavigationView mBar;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mRect = new Rect();
    private final float mRadius;
    @Nullable private ValueAnimator mAnimator;
    private int mItemId;
    private int mTintedMode = -1;
    private boolean mTintDirty = true;
    private float mCx = Float.NaN;
    private float mCy;
    private int mColor;
    /** The glow's size, 1 at rest; it dims to a smaller glow mid-glide. */
    private float mSwell = 1f;

    private NavBloom(BottomNavigationView bar) {
        mBar = bar;
        mRadius = RADIUS_DP * bar.getResources().getDisplayMetrics().density;
        mPaint.setDither(true); // the faint outer alphas band into rings without it
    }

    /** Puts the glow on {@code bar}; it follows the bar's selection and the setting from then on. */
    public static void attach(BottomNavigationView bar) {
        NavBloom bloom = new NavBloom(bar);
        bar.setBackground(bloom);
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> bloom.mTintDirty = true); // a rebuilt menu has new item views
        bar.getViewTreeObserver().addOnPreDrawListener(() -> {
            bloom.track();
            return true;
        });
    }

    /** Until the user picks one: the accent (red) in the light theme, the plain white in the dark. */
    public static int mode(Context context) {
        if (sMode == -1) {
            sMode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, UNSET);
        }
        if (sMode == UNSET) {
            return com.newtube.mobile.ui.common.ThemeMode.isLight(context) ? MODE_ACCENT : MODE_WHITE;
        }
        return sMode;
    }

    private static final int UNSET = -2;

    public static void setMode(Context context, int mode) {
        sMode = mode;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MODE, mode).apply();
    }

    /** Each frame: re-tints after a rebuild or a new setting, and glides to a newly lit tab. */
    private void track() {
        int mode = mode(mBar.getContext());
        if (mode != mTintedMode || mTintDirty) {
            tint(mode);
        }
        if (mode == MODE_OFF || mFollowing) {
            return;
        }
        int itemId = mBar.getSelectedItemId();
        View tab = mBar.findViewById(itemId);
        View icon = tab != null ? tab.findViewById(com.google.android.material.R.id.navigation_bar_item_icon_view) : null;
        if (icon == null || icon.getWidth() == 0) {
            return;
        }
        icon.getDrawingRect(mRect);
        mBar.offsetDescendantRectToMyCoords(icon, mRect);
        float cx = mRect.exactCenterX();
        float cy = mRect.exactCenterY();
        int color = colorFor(mBar.getContext(), mode, itemId);
        if (itemId != mItemId && !Float.isNaN(mCx) && NavLens.enabled(mBar.getContext())) {
            glide(cx, cy, color);
        } else if (mAnimator == null && (cx != mCx || cy != mCy || color != mColor)) {
            mCx = cx; // first draw, a re-layout, or no motion wanted: no glide
            mCy = cy;
            mColor = color;
            invalidateSelf();
        }
        mItemId = itemId;
    }

    /**
     * MEETUBE(#90): the glow under a finger dragging along the bar, at {@code x} (bar coordinates) in
     * the colour of the tab it is over; NaN lets go, and it glides to the lit tab from there.
     */
    void follow(float x, int itemId) {
        if (Float.isNaN(x)) {
            mFollowing = false;
            mItemId = View.NO_ID; // the next frame glides it home (or on to the newly picked tab)
            return;
        }
        if (mAnimator != null) {
            mAnimator.cancel();
            mAnimator = null;
        }
        mFollowing = true;
        mCx = x;
        mColor = colorFor(mBar.getContext(), mode(mBar.getContext()), itemId);
        mSwell = 1f;
        invalidateSelf();
    }

    private boolean mFollowing;

    private void glide(float toX, float toY, int toColor) {
        if (mAnimator != null) {
            mAnimator.cancel();
        }
        float fromX = mCx;
        float fromY = mCy;
        int fromColor = mColor;
        ArgbEvaluator argb = new ArgbEvaluator();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(GLIDE_MS);
        animator.setInterpolator(Motion.STANDARD);
        animator.addUpdateListener(a -> {
            float t = a.getAnimatedFraction();
            float u = (float) a.getAnimatedValue();
            mCx = fromX + (toX - fromX) * u;
            mCy = fromY + (toY - fromY) * u;
            mColor = (int) argb.evaluate(u, fromColor, toColor);
            mSwell = 1f - 0.35f * (float) Math.sin(Math.PI * t);
            if (t >= 1f) {
                mAnimator = null;
            }
            invalidateSelf();
        });
        mAnimator = animator;
        animator.start();
    }

    /** The lit icon and name in the glow's colour, or back to the bar's own colours with it off. */
    private void tint(int mode) {
        mTintedMode = mode;
        mTintDirty = false;
        ColorStateList idle = mBar.getItemIconTintList();
        int rest = idle != null ? idle.getDefaultColor() : 0;
        for (int i = 0; i < mBar.getMenu().size(); i++) {
            View child = mBar.findViewById(mBar.getMenu().getItem(i).getItemId());
            if (!(child instanceof NavigationBarItemView)) {
                continue;
            }
            NavigationBarItemView item = (NavigationBarItemView) child;
            ColorStateList list = mode == MODE_OFF ? idle : new ColorStateList(
                    new int[][] {{android.R.attr.state_checked}, {}},
                    new int[] {colorFor(mBar.getContext(), mode, item.getId()), rest});
            item.setIconTintList(list);
            item.setTextColor(mode == MODE_OFF ? mBar.getItemTextColor() : list);
        }
        invalidateSelf();
    }

    /** MEETUBE(issue #130): the glow's colour for a tab, or 0 with the glow off (the held tab's hint). */
    public static int colorOf(Context context, int itemId) {
        int mode = mode(context);
        return mode == MODE_OFF ? 0 : colorFor(context, mode, itemId);
    }

    /** The tab's colour; per tab, Apple's system palette, which reads on the dark and the light glass alike. */
    private static int colorFor(Context context, int mode, int itemId) {
        if (mode == MODE_ACCENT) {
            return ContextCompat.getColor(context, R.color.mobile_color_primary);
        }
        if (mode == MODE_WHITE) { // white on the dark glass; on the light glass white would vanish, so grey
            return ContextCompat.getColor(context, R.color.meetube_nav_bloom_plain);
        }
        if (itemId == MobileBrowseActivity.YOU_ITEM_ID) {
            return 0xFFBF5AF2; // purple
        }
        if (itemId == MobileBrowseActivity.SEARCH_ITEM_ID) {
            return 0xFF40C8E0; // teal
        }
        switch (itemId - MobileBrowseActivity.ITEM_ID_OFFSET) {
            case MediaGroup.TYPE_HOME:
                return ContextCompat.getColor(context, R.color.mobile_color_primary);
            case MediaGroup.TYPE_SUBSCRIPTIONS:
                return 0xFF0A84FF; // blue
            case MediaGroup.TYPE_SHORTS:
                return 0xFFFF375F; // pink
            case MediaGroup.TYPE_HISTORY:
                return 0xFFFF9F0A; // orange
            case VideoDownloads.SECTION_ID:
                return 0xFF30D158; // green
            case com.liskovsoft.smartyoutubetv2.common.misc.LocalMedia.SECTION_ID:
                return 0xFFBF5AF2; // purple: the Media tab
            default:
                return 0xFF5E5CE6; // indigo: any other section
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (Float.isNaN(mCx) || mode(mBar.getContext()) == MODE_OFF) {
            return;
        }
        float r = mRadius * (0.75f + 0.25f * mSwell);
        int alpha = Math.round(HEART_ALPHA * mSwell);
        int rgb = mColor & 0x00FFFFFF;
        // An eased (Gaussian-like) falloff: two straight ramps met in a ring the eye picks out.
        int[] colors = new int[FALLOFF.length];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = rgb | (Math.round(alpha * FALLOFF[i]) << 24);
        }
        mPaint.setShader(new RadialGradient(mCx, mCy, r, colors, null, Shader.TileMode.CLAMP));
        canvas.save();
        canvas.scale(1f, FLATTEN, mCx, mCy);
        canvas.drawCircle(mCx, mCy, r, mPaint);
        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
