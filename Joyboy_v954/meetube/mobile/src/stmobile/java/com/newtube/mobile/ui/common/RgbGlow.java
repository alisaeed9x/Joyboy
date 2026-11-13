package com.newtube.mobile.ui.common;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * MEETUBE(rgb glow): a soft light in the top left corner whose colour walks the hue wheel, behind
 * the You page's header. Done the way Gemini lights its pure black page: one
 * saturated colour at about a third of its strength at most, faded out on a long eased curve, so
 * the black stays true black past it. Two lights a third of the wheel apart make it multi colour.
 *
 * <p>The page is padded clear of the status bar, so while the glow shows the same light is also
 * painted behind the bar (the content's top padding), lined up so the two read as one.</p>
 */
public final class RgbGlow extends View {
    private static final long CYCLE_MS = 14_000;
    /** About 36%: Gemini's brightest glow on black is rgb(19,35,92), its blue at that strength. */
    private static final float PEAK = 0.36f;
    /** (1 - t)^2 at these stops: the light thins fast, then lingers before it ends. */
    private static final float[] STOPS = {0f, 0.25f, 0.5f, 0.75f, 1f};
    private static final float[] FALLOFF = {1f, 0.56f, 0.25f, 0.06f, 0f};

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] mHsv = {0f, 0.85f, 1f};
    private final int[] mColors = new int[STOPS.length];
    @Nullable private ValueAnimator mCycle;
    private float mHue = 220f; // Gemini's blue, where it rests when animations are off
    @Nullable private View mBandHost;
    private final int[] mGlowAt = new int[2];
    private final int[] mHostAt = new int[2];
    /** How much of the page colour covers the status bar strip (the You page's bar turning solid). */
    private float mBandCover;

    /** The status bar strip: the host's top padding, the lights placed where this view has them. */
    private final Drawable mBand = new Drawable() {
        @Override
        public void draw(@NonNull Canvas canvas) {
            View host = mBandHost;
            if (host == null || host.getPaddingTop() <= 0) {
                return;
            }
            getLocationInWindow(mGlowAt);
            host.getLocationInWindow(mHostAt);
            canvas.save();
            canvas.clipRect(0, 0, host.getWidth(), host.getPaddingTop());
            canvas.translate(mGlowAt[0] - mHostAt[0], mGlowAt[1] - mHostAt[1]);
            drawLights(canvas);
            canvas.restore();
            if (mBandCover > 0f) {
                int page = androidx.core.content.ContextCompat.getColor(getContext(), com.liskovsoft.smartyoutubetv2.tv.R.color.mobile_color_background);
                canvas.save();
                canvas.clipRect(0, 0, host.getWidth(), host.getPaddingTop());
                canvas.drawColor((page & 0xFFFFFF) | (Math.round(255 * mBandCover) << 24));
                canvas.restore();
            }
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
    };

    public RgbGlow(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            return;
        }
        mCycle = ValueAnimator.ofFloat(0f, 360f);
        mCycle.setDuration(CYCLE_MS);
        mCycle.setRepeatCount(ValueAnimator.INFINITE);
        mCycle.setInterpolator(new LinearInterpolator());
        mCycle.addUpdateListener(a -> {
            mHue = (220f + (float) a.getAnimatedValue()) % 360f;
            invalidate();
            mBand.invalidateSelf(); // every frame: also follows the page as it scrolls
        });
        mCycle.start();
    }

    /** 0..1: the page colour over the status bar strip, to match a bar below it turning solid. */
    public void setBandCover(float cover) {
        if (cover != mBandCover) {
            mBandCover = cover;
            mBand.invalidateSelf();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mCycle != null) {
            mCycle.cancel();
            mCycle = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible && mBandHost == null) {
            mBandHost = getRootView().findViewById(android.R.id.content);
            if (mBandHost != null) {
                mBandHost.setBackground(mBand);
            }
        } else if (!isVisible && mBandHost != null) {
            mBandHost.setBackground(null);
            mBandHost = null;
        }
        if (mCycle != null) {
            if (isVisible) {
                mCycle.resume();
            } else {
                mCycle.pause(); // off screen (the You panel hidden, the app behind): no frames
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawLights(canvas);
    }

    private void drawLights(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float r = Math.max(w, h);
        light(canvas, w * 0.1f, h * 0.05f, r * 0.85f, mHue);
        light(canvas, w * 0.55f, -h * 0.1f, r * 0.6f, mHue + 120f);
    }

    private void light(Canvas canvas, float cx, float cy, float radius, float hue) {
        mHsv[0] = hue % 360f;
        int rgb = Color.HSVToColor(mHsv) & 0xFFFFFF;
        for (int i = 0; i < STOPS.length; i++) {
            mColors[i] = Math.round(255 * PEAK * FALLOFF[i]) << 24 | rgb;
        }
        //noinspection DrawAllocation - a shader per frame for two lights; the colour changes every frame
        mPaint.setShader(new RadialGradient(cx, cy, radius, mColors, STOPS, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, mPaint);
    }
}
