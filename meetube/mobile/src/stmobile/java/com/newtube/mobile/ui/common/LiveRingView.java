package com.newtube.mobile.ui.common;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import androidx.annotation.Nullable;

/**
 * MEETUBE(issue #73): a live channel's avatar ring - YouTube's red-to-pink gradient, turning, with a
 * soft red glow that breathes around it. It lies over the avatar, the same size; {@link #bind}
 * pads the avatar in by the ring's width, so nothing draws past the avatar's own bounds. Its sizes
 * scale with the avatar (a 36dp card avatar to the 76dp channel header). Runs only while shown.
 */
public final class LiveRingView extends View {
    private static final long LOOP_MS = 2400;
    private static final int RED = 0xFFFF0033;
    private static final int PINK = 0xFFFF2D8A;

    /**
     * Channels seen live this session (a live video of theirs in a feed, the watch page, a channel
     * header), for the avatars whose own data can't say: the Subscriptions strip, channel rows.
     * Main thread only.
     * ponytail: session-long - a stream that ends keeps its channel's ring until the app restarts;
     * drop entries when a fresher feed no longer shows the channel live, if that matters.
     */
    private static final java.util.Set<String> sLiveChannels = new java.util.HashSet<>();

    /** Remembers {@code channelId} as live; true when it wasn't yet. */
    public static boolean markLive(@Nullable String channelId) {
        return channelId != null && sLiveChannels.add(channelId);
    }

    public static boolean isLive(@Nullable String channelId) {
        return channelId != null && sLiveChannels.contains(channelId);
    }

    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mSpin = new Matrix();
    private final float mDensity;
    @Nullable private SweepGradient mSweep;
    @Nullable private ValueAnimator mLoop;
    private float mPhase;
    private float mRingRadius;

    public LiveRingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getDisplayMetrics().density;
        mRing.setStyle(Paint.Style.STROKE);
    }

    private static float glow(float size, float density) {
        return Math.max(1.5f * density, 0.05f * size);
    }

    private static float stroke(float size, float density) {
        return Math.max(1.5f * density, 0.032f * size);
    }

    /** How far in from the ring's edge the avatar starts: glow, ring and a gap. */
    private static int inset(float size, float density) {
        return Math.round(glow(size, density) + stroke(size, density) + Math.max(density, 0.03f * size));
    }

    /**
     * Shows or hides the ring over {@code avatar} (same size, same place), padding the avatar in
     * by the ring when live and back to {@code restPadding} when not.
     */
    public static void bind(@Nullable LiveRingView ring, ImageView avatar, boolean live, int restPadding) {
        if (ring == null) {
            return;
        }
        int size = avatar.getLayoutParams().width > 0 ? avatar.getLayoutParams().width : avatar.getWidth();
        int pad = live ? inset(size, ring.mDensity) : restPadding;
        if (avatar.getPaddingLeft() != pad) {
            avatar.setPadding(pad, pad, pad, pad);
        }
        ring.setVisibility(live ? VISIBLE : GONE);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float cx = w / 2f;
        float cy = h / 2f;
        float size = Math.min(w, h);
        float outer = size / 2f;
        float glow = glow(size, mDensity);
        float stroke = stroke(size, mDensity);
        mRingRadius = outer - glow - stroke / 2f;
        mRing.setStrokeWidth(stroke);
        mSweep = new SweepGradient(cx, cy, new int[] {RED, PINK, RED, PINK, RED}, null);
        mRing.setShader(mSweep);
        // The glow: red at the ring, fading out to the edge and a little way in.
        float inner = Math.max(0f, (mRingRadius - 2 * glow) / outer);
        mGlow.setShader(new RadialGradient(cx, cy, outer, new int[] {0x00FF0033, 0xAAFF0033, 0x00FF0033},
                new float[] {inner, mRingRadius / outer, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mSweep == null) {
            return;
        }
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        // Breathes twice per turn: 0.35 .. 1.
        mGlow.setAlpha(Math.round(255 * (0.675f + 0.325f * (float) Math.sin(mPhase * 4 * Math.PI))));
        canvas.drawCircle(cx, cy, Math.min(getWidth(), getHeight()) / 2f, mGlow);
        mSpin.setRotate(mPhase * 360f, cx, cy);
        mSweep.setLocalMatrix(mSpin);
        canvas.drawCircle(cx, cy, mRingRadius, mRing);
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible && mLoop == null) {
            mLoop = ValueAnimator.ofFloat(0f, 1f);
            mLoop.setDuration(LOOP_MS);
            mLoop.setRepeatCount(ValueAnimator.INFINITE);
            mLoop.setInterpolator(new LinearInterpolator());
            mLoop.addUpdateListener(a -> {
                mPhase = (float) a.getAnimatedValue();
                invalidate();
            });
            mLoop.start();
        } else if (!isVisible && mLoop != null) {
            mLoop.cancel();
            mLoop = null;
        }
    }
}
