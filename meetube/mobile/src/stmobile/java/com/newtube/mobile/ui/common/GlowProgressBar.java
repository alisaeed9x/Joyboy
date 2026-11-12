package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.SeekBar;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * MEETUBE(progress glow): a progress bar whose head glows, a soft light where the played part ends.
 * The thin bars (a card's watched line, the mini player's) draw it past their own few dp, over
 * what is above them, so their parent draws them unclipped. Settings > Customization > Progress
 * glow turns it off everywhere; the player's seek bar (PlayerTimeBar) draws the same {@link Head}.
 */
public class GlowProgressBar extends ProgressBar {
    private static final String PREFS = "meetube_app";
    private static final String KEY_ON = "progress_glow";
    /** The glow's heart, ~70% of the bar's colour, fading out at its radius. */
    private static final int HEART_ALPHA = 0xB3;
    private static int sOn = -1;

    private final Head mHead = new Head();

    public GlowProgressBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs, android.R.attr.progressBarStyleHorizontal);
    }

    public static boolean enabled(Context context) {
        if (sOn < 0) {
            sOn = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true) ? 1 : 0;
        }
        return sOn == 1;
    }

    public static void setEnabled(Context context, boolean on) {
        sOn = on ? 1 : 0;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).setClipChildren(false); // the glow reaches past the bar
        }
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        mHead.drawOn(this, canvas);
        super.onDraw(canvas);
        mHead.capOn(canvas);
    }

    /** The Shorts scrub line: a SeekBar, with the same glow under its thumb. */
    public static class Seek extends SeekBar {
        private final Head mHead = new Head();

        public Seek(Context context, @Nullable AttributeSet attrs) {
            super(context, attrs);
        }

        @Override
        protected synchronized void onDraw(Canvas canvas) {
            mHead.drawOn(this, canvas);
            super.onDraw(canvas);
            mHead.capOn(canvas);
        }
    }

    /**
     * The glow itself: an oval of the head's own colour (wider than tall, so it hugs the line), fading
     * out softly, and a round cap that ends the line instead of its square edge.
     */
    public static final class Head {
        /** The fade from the heart out, a bell rather than a straight ramp: no visible edge. */
        private static final float[] STOPS = {0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f};
        private static final float[] FALLOFF = {1f, 0.78f, 0.45f, 0.2f, 0.06f, 0f};
        private static final float FLATTEN = 0.6f;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mCapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int mColor;
        private float mRadius;
        private float mX;
        private float mCy;
        private float mTrack;

        /** Before {@code bar} draws: where its played end is, and the glow under it. */
        void drawOn(ProgressBar bar, Canvas canvas) {
            mTrack = 0f;
            int range = bar.getMax(); // every bar here starts at 0 (getMin is API 26)
            int progress = bar.getProgress();
            if (range <= 0 || progress <= 0 || progress >= range || bar.isIndeterminate()) {
                return; // nothing played, or all of it: no head to light
            }
            int left = bar.getPaddingLeft();
            mX = left + (bar.getWidth() - left - bar.getPaddingRight()) * (float) progress / range;
            mCy = bar.getHeight() / 2f;
            float density = bar.getResources().getDisplayMetrics().density;
            // A SeekBar's box is taller than its line (Shorts': 24dp around a 3dp one).
            mTrack = bar instanceof SeekBar ? 3 * density : bar.getHeight();
            mColor = colorOf(bar);
            draw(bar.getContext(), canvas, mX, mCy, Math.max(6 * density, Math.min(10 * density, mTrack * 2.7f)), mColor);
        }

        /** After {@code bar} drew: its square end rounded off. */
        void capOn(Canvas canvas) {
            if (mTrack > 0f) {
                cap(canvas, mX, mCy, mTrack, mColor);
            }
        }

        /** The played part's colour: its tint, else its drawable's (Shorts' white line has no tint). */
        private static int colorOf(ProgressBar bar) {
            ColorStateList tint = bar.getProgressTintList();
            if (tint != null) {
                return tint.getDefaultColor();
            }
            android.graphics.drawable.Drawable d = bar.getProgressDrawable();
            if (d instanceof android.graphics.drawable.LayerDrawable) {
                d = ((android.graphics.drawable.LayerDrawable) d).findDrawableByLayerId(android.R.id.progress);
            }
            if (d instanceof android.graphics.drawable.ClipDrawable) {
                d = ((android.graphics.drawable.ClipDrawable) d).getDrawable();
            }
            if (d instanceof android.graphics.drawable.GradientDrawable
                    && ((android.graphics.drawable.GradientDrawable) d).getColor() != null) {
                return ((android.graphics.drawable.GradientDrawable) d).getColor().getDefaultColor();
            }
            return ContextCompat.getColor(bar.getContext(), R.color.mobile_color_progress);
        }

        /** Lights ({@code x}, {@code cy}) unless the setting is off. */
        public void draw(Context context, Canvas canvas, float x, float cy, float radius, int color) {
            if (!enabled(context) || radius <= 0f) {
                return;
            }
            if (color != mPaint.getColor() || radius != mRadius || mPaint.getShader() == null) {
                mPaint.setColor(color);
                mRadius = radius;
                int rgb = color & 0x00FFFFFF;
                int alpha = Color.alpha(color) * HEART_ALPHA / 255;
                int[] colors = new int[FALLOFF.length];
                for (int i = 0; i < colors.length; i++) {
                    colors[i] = rgb | Math.round(alpha * FALLOFF[i]) << 24;
                }
                mPaint.setShader(new RadialGradient(0f, 0f, radius, colors, STOPS, Shader.TileMode.CLAMP));
            }
            int save = canvas.save();
            canvas.translate(x, cy);
            canvas.scale(1f, FLATTEN);
            canvas.drawCircle(0f, 0f, radius, mPaint);
            canvas.restoreToCount(save);
        }

        /** A half-round end for a {@code track}-thick line whose played part stops at {@code x}. */
        public void cap(Canvas canvas, float x, float cy, float track, int color) {
            mCapPaint.setColor(color);
            canvas.drawCircle(x, cy, track / 2f, mCapPaint);
        }
    }
}
