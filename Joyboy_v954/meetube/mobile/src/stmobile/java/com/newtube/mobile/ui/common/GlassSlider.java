package com.newtube.mobile.ui.common;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.SeekBar;

import androidx.core.content.ContextCompat;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * MEETUBE(glass slider): liquidglass.theatom.lk's slider on a SeekBar (its touch handling, listener
 * and accessibility stay the platform's). A 6dp rail with the accent filling it up to a wide white
 * pill knob; held, the knob swells into the same clear glass lens as {@link GlassSwitch}'s, on a
 * spring, and stretches with its speed. The knob trails the finger on a spring rather than
 * jumping. A plain flat knob on an old or weak phone.
 */
public class GlassSlider extends SeekBar {
    private static final float RAIL_H = 6f;
    /** The knob's centre keeps this far from the rail's ends (half the 38dp slot). */
    private static final float END_INSET = 19f;
    /** Room each side for the swollen lens, whose half width is 29dp. */
    private static final float SIDE_ROOM = 29f;
    private static final float HEIGHT = 52f;

    private final float mDp;
    private final boolean mSimple;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final int mRail;
    private final int mFill;

    private final FloatValueHolder mPosition = new FloatValueHolder(0f);
    private final FloatValueHolder mLift = new FloatValueHolder(0f);
    private final FloatValueHolder mStretch = new FloatValueHolder(0f);
    private final SpringAnimation mPositionSpring;
    private final SpringAnimation mLiftSpring;
    private final SpringAnimation mJellySpring;
    private boolean mPlaced;
    private float mTarget = -1f;

    public GlassSlider(Context context) {
        this(context, null);
    }

    public GlassSlider(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDp = getResources().getDisplayMetrics().density;
        mSimple = NavLens.lowEnd(context);
        mRail = ContextCompat.getColor(context, R.color.meetube_switch_off);
        mFill = ContextCompat.getColor(context, R.color.meetube_switch_on);
        setBackground(null);
        setThumb(new ColorDrawable(Color.TRANSPARENT));
        setProgressDrawable(new ColorDrawable(Color.TRANSPARENT));
        // The touch range is the padded width: the knob's centre runs between these two edges.
        setPadding(Math.round(SIDE_ROOM * mDp), 0, Math.round(SIDE_ROOM * mDp), 0);
        mPositionSpring = newSpring(mPosition, true);
        mLiftSpring = newSpring(mLift, false);
        mJellySpring = newSpring(mStretch, false);
    }

    private SpringAnimation newSpring(FloatValueHolder holder, boolean feedsJelly) {
        SpringAnimation spring = new SpringAnimation(holder);
        spring.setSpring(new SpringForce(holder.getValue()));
        spring.setMinimumVisibleChange(0.001f);
        spring.addUpdateListener((a, value, velocity) -> {
            if (feedsJelly && !mSimple) {
                GlassSwitch.spring(mJellySpring,
                        (float) (GlassSwitch.JELLY_MAX * Math.tanh(Math.abs(velocity) / (GlassSwitch.JELLY_SPEED * 2))),
                        GlassSwitch.JELLY);
            }
            invalidate();
        });
        return spring;
    }

    @Override
    protected synchronized void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(getDefaultSize(0, widthMeasureSpec), Math.round(HEIGHT * mDp));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = super.onTouchEvent(event);
        if (isEnabled()) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    GlassSwitch.spring(mLiftSpring, 1f, GlassSwitch.PRESS);
                    Haptics.tick(this);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    GlassSwitch.spring(mLiftSpring, 0f, GlassSwitch.SETTLE);
                    break;
                default:
                    break;
            }
        }
        return handled;
    }

    @Override
    protected void onDetachedFromWindow() {
        mPositionSpring.cancel();
        mLiftSpring.cancel();
        mJellySpring.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        float width = getWidth();
        float cy = getHeight() / 2f;
        float railLeft = SIDE_ROOM * mDp - END_INSET * mDp;
        float railRight = width - railLeft;
        float railH = RAIL_H * mDp;
        float span = Math.max(1, getMax() - getMin());
        float fraction = Math.max(0f, Math.min(1f, (getProgress() - getMin()) / span));

        // The knob trails the finger on a spring; the first draw (or a rebind) just places it.
        if (!mPlaced) {
            mPlaced = true;
            mPosition.setValue(fraction);
            mTarget = fraction;
        } else if (fraction != mTarget) {
            mTarget = fraction;
            GlassSwitch.spring(mPositionSpring, fraction, GlassSwitch.DRAG);
        }

        float travel = width - 2 * SIDE_ROOM * mDp;
        float cx = SIDE_ROOM * mDp + travel * mPosition.getValue();

        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(mRail);
        mRect.set(railLeft, cy - railH / 2f, railRight, cy + railH / 2f);
        canvas.drawRoundRect(mRect, railH / 2f, railH / 2f, mPaint);
        mPaint.setColor(isEnabled() ? mFill : Color.argb(110, Color.red(mFill), Color.green(mFill), Color.blue(mFill)));
        mRect.set(railLeft, cy - railH / 2f, cx, cy + railH / 2f);
        canvas.drawRoundRect(mRect, railH / 2f, railH / 2f, mPaint);

        float lift = mSimple ? 0f : mLift.getValue();
        float glass = Math.max(0f, Math.min(1f, lift));
        float stretch = mSimple ? 0f : mStretch.getValue();
        float s = GlassSwitch.REST_SCALE + (1f - GlassSwitch.REST_SCALE) * lift;
        float kw = GlassSwitch.LENS_W * mDp * s * (1f + stretch);
        float kh = GlassSwitch.LENS_H * mDp * s * (1f - stretch * 0.5f);
        mRect.set(cx - kw / 2f, cy - kh / 2f, cx + kw / 2f, cy + kh / 2f);
        float r = kh / 2f;

        if (mSimple) {
            mPaint.setColor(Color.WHITE);
            canvas.drawRoundRect(mRect, r, r, mPaint);
            return;
        }

        mShadow.setStyle(Paint.Style.FILL);
        mShadow.setColor(Color.TRANSPARENT);
        mShadow.setShadowLayer((3 + 5 * glass) * mDp, 0, (1 + 2 * glass) * mDp,
                Color.argb(Math.round(255 * (0.14f + 0.08f * glass)), 0, 0, 0));
        canvas.drawRoundRect(mRect, r, r, mShadow);

        // White at rest; a clear glass lens with a bright ring and a glint as it lifts.
        mPaint.setColor(Color.argb(Math.round(255 - 215 * glass), 255, 255, 255));
        canvas.drawRoundRect(mRect, r, r, mPaint);
        if (glass > 0f) {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(0.9f * mDp);
            mPaint.setColor(Color.argb(Math.round(235 * glass), 255, 255, 255));
            canvas.drawRoundRect(mRect, r, r, mPaint);
            mPaint.setStrokeWidth(1.6f * mDp);
            mPaint.setColor(Color.argb(Math.round(110 * glass), 255, 255, 255));
            mRect.inset(3 * mDp, 3 * mDp);
            canvas.drawArc(mRect, 200, 80, false, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
        }
    }
}
