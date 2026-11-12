package com.newtube.mobile.ui.common;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import androidx.core.content.ContextCompat;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * MEETUBE(glass switch): the iOS 26 switch, moving as liquidglass.theatom.lk's does. A 64x28dp
 * capsule (accent on, grey off) and a wide white pill knob with a soft shadow. Held, the knob
 * swells (a spring, with a wobble as it lets go) into a clear lens with a bright ring and the track
 * opens a see-through gap behind it. Position, swell, track colour and the lens's stretch are each
 * a spring: it overshoots and settles, and stretches wider and flatter the faster it travels, like
 * jelly. Dragged past an end it rubber-bands. Let go past the hysteresis line (30% from on, 70%
 * from off) and it flips, through the click of whatever owns the switch.
 * Drawn here, not with the switch's drawables (SwitchCompat doubles a pill knob's track).
 */
public class GlassSwitch extends SwitchMaterial {
    private static final float TRACK_W = 64f;
    private static final float TRACK_H = 28f;
    /** The lens at full size; the knob at rest is this at REST_SCALE. */
    static final float LENS_W = 58f;
    static final float LENS_H = 38f;
    static final float REST_SCALE = 0.655f;
    /** The knob's centre sits this far in from the track's end when off; it travels TRAVEL. */
    private static final float CENTRE_INSET = 21f;
    private static final float TRAVEL = 22f;
    /** Room each side for the lens and its shadow, which reach past the track. */
    private static final float SIDE_ROOM = 22f;
    /** The jelly: at most 14% stretch, saturating around 5 track-lengths a second. */
    static final float JELLY_MAX = 0.14f;
    static final float JELLY_SPEED = 5f;

    // Springs as (response s, damping fraction), from the reference.
    static final float[] SNAP = {0.36f, 0.72f};
    static final float[] DRAG = {0.12f, 0.92f};
    static final float[] PRESS = {0.26f, 0.78f};
    static final float[] SETTLE = {0.36f, 0.68f};
    static final float[] JELLY = {0.30f, 0.50f};
    private static final float[] FADE = {0.25f, 1.00f};

    private final float mDp;
    /** An old or weak phone: a plain flat switch, no lens, no shadow, no offscreen layer. */
    private final boolean mSimple;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
    private final int mOn;
    private final int mOff;
    private final int mSlop;

    private final FloatValueHolder mPosition = new FloatValueHolder(0f);
    private final FloatValueHolder mLift = new FloatValueHolder(0f);
    private final FloatValueHolder mColor = new FloatValueHolder(0f);
    private final FloatValueHolder mStretch = new FloatValueHolder(0f);
    private final SpringAnimation mPositionSpring;
    private final SpringAnimation mLiftSpring;
    private final SpringAnimation mColorSpring;
    private final SpringAnimation mJellySpring;
    private final Runnable mDrop = new Runnable() {
        @Override
        public void run() {
            spring(mLiftSpring, 0f, SETTLE);
        }
    };

    private boolean mGesture;
    private boolean mMoved;
    private boolean mFrom;
    private boolean mLastOn;
    private float mDownX;
    private long mDownAt;

    public GlassSwitch(Context context) {
        this(context, null);
    }

    public GlassSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDp = getResources().getDisplayMetrics().density;
        mSimple = NavLens.lowEnd(context);
        mSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mOn = ContextCompat.getColor(context, R.color.meetube_switch_on);
        mOff = ContextCompat.getColor(context, R.color.meetube_switch_off);
        setThumbDrawable(new ColorDrawable(Color.TRANSPARENT));
        setTrackDrawable(new ColorDrawable(Color.TRANSPARENT));
        setBackground(null);
        float on = isChecked() ? 1f : 0f;
        mPosition.setValue(on);
        mColor.setValue(on);
        mPositionSpring = newSpring(mPosition, on, true);
        mLiftSpring = newSpring(mLift, 0f, false);
        mColorSpring = newSpring(mColor, on, false);
        mJellySpring = newSpring(mStretch, 0f, false);
    }

    private SpringAnimation newSpring(FloatValueHolder holder, float rest, boolean feedsJelly) {
        SpringAnimation spring = new SpringAnimation(holder);
        spring.setSpring(new SpringForce(rest));
        spring.setMinimumVisibleChange(0.001f);
        spring.addUpdateListener((a, value, velocity) -> {
            if (feedsJelly && !mSimple) {
                spring(mJellySpring, (float) (JELLY_MAX * Math.tanh(Math.abs(velocity) / JELLY_SPEED)), JELLY);
            }
            invalidate();
        });
        return spring;
    }

    /** Retarget a spring: stiffness and damping from (response, damping fraction). */
    static void spring(SpringAnimation spring, float target, float[] config) {
        double w = 2 * Math.PI / config[0];
        SpringForce force = spring.getSpring();
        force.setStiffness((float) (w * w));
        force.setDampingRatio(config[1]);
        force.setFinalPosition(target);
        if (!spring.isRunning()) {
            spring.start();
        }
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(Math.round((TRACK_W + 2 * SIDE_ROOM) * mDp) + getPaddingLeft() + getPaddingRight(),
                Math.round(52 * mDp) + getPaddingTop() + getPaddingBottom());
    }

    /**
     * Touches on the switch itself: a tap clicks whatever owns the switch (the row it sits in, which
     * does the toggling and its own bookkeeping); a drag carries the knob with the finger and, let
     * go past the line, does the same click; let go short of it, the knob springs back.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        float travel = TRAVEL * mDp;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mGesture = true;
                mMoved = false;
                mFrom = isChecked();
                mLastOn = mFrom;
                mDownX = event.getX();
                mDownAt = event.getEventTime();
                removeCallbacks(mDrop);
                spring(mLiftSpring, 1f, PRESS);
                Haptics.tick(this);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!mMoved && Math.abs(event.getX() - mDownX) > Math.min(mSlop, 3 * mDp)) {
                    mMoved = true;
                    ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                }
                if (mMoved) {
                    float raw = (mFrom ? 1f : 0f) + (event.getX() - mDownX) / travel;
                    spring(mPositionSpring, rubberband(raw), DRAG);
                    boolean on = flips(mFrom, raw);
                    if (on != mLastOn) {
                        mLastOn = on;
                        Haptics.tick(this);
                    }
                    spring(mColorSpring, on ? 1f : 0f, FADE);
                }
                return true;
            case MotionEvent.ACTION_UP:
                finish(event.getX(), event.getEventTime());
                return true;
            case MotionEvent.ACTION_CANCEL:
                mGesture = false;
                settleTo(isChecked());
                postDelayed(mDrop, 0);
                return true;
            default:
                return true;
        }
    }

    private void finish(float x, long now) {
        mGesture = false;
        boolean next = mMoved ? flips(mFrom, (mFrom ? 1f : 0f) + (x - mDownX) / (TRAVEL * mDp)) : !isChecked();
        if (!mMoved || next != isChecked()) {
            clickOwner(); // toggles, and its setChecked springs the knob across
        } else {
            settleTo(isChecked());
        }
        postDelayed(mDrop, mMoved ? 0 : Math.max(0, 200 - (now - mDownAt)));
    }

    /** From on: stays on past 30%; from off: turns on past 70%. */
    private static boolean flips(boolean from, float raw) {
        return from ? raw > 0.3f : raw > 0.7f;
    }

    /** Past either end the finger drags the knob against a rubber band. */
    private static float rubberband(float raw) {
        if (raw >= 0f && raw <= 1f) {
            return raw;
        }
        float over = raw < 0f ? raw : raw - 1f;
        float eased = 0.35f * Math.signum(over) * (1f - 1f / (Math.abs(over) * 0.6f + 1f));
        return raw < 0f ? eased : 1f + eased;
    }

    private void settleTo(boolean on) {
        spring(mPositionSpring, on ? 1f : 0f, SNAP);
        spring(mColorSpring, on ? 1f : 0f, FADE);
    }

    /** The view whose click toggles this switch: itself if it takes clicks, else the nearest row above. */
    private void clickOwner() {
        View owner = this;
        while (!owner.isClickable() && owner.getParent() instanceof View) {
            owner = (View) owner.getParent();
        }
        owner.performClick();
    }

    /** A row's press reaches a child that is not clickable itself: the held knob swells. */
    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        if (mDp == 0f || mGesture) {
            return;
        }
        removeCallbacks(mDrop);
        spring(mLiftSpring, pressed ? 1f : 0f, pressed ? PRESS : SETTLE);
    }

    @Override
    public void setChecked(boolean checked) {
        boolean changed = checked != isChecked();
        super.setChecked(checked);
        if (mDp == 0f) {
            return; // the super constructor sets the state before this class is ready
        }
        float target = checked ? 1f : 0f;
        if (!changed || !isAttachedToWindow() || !isLaidOut()) {
            mPositionSpring.cancel();
            mColorSpring.cancel();
            mJellySpring.cancel();
            mPosition.setValue(target); // a bound or recycled row jumps, a tapped one springs
            mColor.setValue(target);
            mStretch.setValue(0f);
            invalidate();
            return;
        }
        settleTo(checked);
        if (!mGesture && !isPressed()) { // a slide with no finger on it still swells and lets go
            removeCallbacks(mDrop);
            spring(mLiftSpring, 1f, PRESS);
            postDelayed(mDrop, 220);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mDrop);
        mPositionSpring.cancel();
        mLiftSpring.cancel();
        mColorSpring.cancel();
        mJellySpring.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = TRACK_W * mDp;
        float h = TRACK_H * mDp;
        float left = getPaddingLeft() + SIDE_ROOM * mDp;
        float top = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - h) / 2f;
        float color = Math.max(0f, Math.min(1f, mColor.getValue()));
        int track = blend(mOff, mOn, color);

        // The knob: 58x38 at a scale that grows from rest to full with the lift (it may overshoot, the
        // wobble), stretched wider and flatter by the jelly.
        float lift = mSimple ? 0f : mLift.getValue();
        float glass = Math.max(0f, Math.min(1f, lift));
        float stretch = mSimple ? 0f : mStretch.getValue();
        float s = REST_SCALE + (1f - REST_SCALE) * lift;
        float kw = LENS_W * mDp * s * (1f + stretch);
        float kh = LENS_H * mDp * s * (1f - stretch * 0.5f);
        float cx = left + (CENTRE_INSET + TRAVEL * mPosition.getValue()) * mDp;
        float cy = top + h / 2f;

        if (mSimple) {
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(track);
            mRect.set(left, top, left + w, top + h);
            canvas.drawRoundRect(mRect, h / 2f, h / 2f, mPaint);
            mPaint.setColor(Color.WHITE);
            mRect.set(cx - kw / 2f, cy - kh / 2f, cx + kw / 2f, cy + kh / 2f);
            canvas.drawRoundRect(mRect, kh / 2f, kh / 2f, mPaint);
            return;
        }

        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(track);
        mRect.set(left, top, left + w, top + h);
        canvas.drawRoundRect(mRect, h / 2f, h / 2f, mPaint);

        mRect.set(cx - kw / 2f, cy - kh / 2f, cx + kw / 2f, cy + kh / 2f);
        float r = kh / 2f;

        // Soft shadow under the knob, deeper as it lifts.
        mShadow.setStyle(Paint.Style.FILL);
        mShadow.setColor(Color.TRANSPARENT);
        mShadow.setShadowLayer((3 + 5 * glass) * mDp, 0, (1 + 2 * glass) * mDp,
                Color.argb(Math.round(255 * (0.14f + 0.08f * glass)), 0, 0, 0));
        canvas.drawRoundRect(mRect, r, r, mShadow);

        // White at rest; clear glass with a faint milky fill as it lifts.
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(Color.argb(Math.round(255 - 215 * glass), 255, 255, 255));
        canvas.drawRoundRect(mRect, r, r, mPaint);
        if (glass > 0f) {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(0.9f * mDp);
            mPaint.setColor(Color.argb(Math.round(235 * glass), 255, 255, 255));
            canvas.drawRoundRect(mRect, r, r, mPaint);
            // a glint along the top edge, the glass catching light
            mPaint.setStrokeWidth(1.6f * mDp);
            mPaint.setColor(Color.argb(Math.round(110 * glass), 255, 255, 255));
            mRect.inset(3 * mDp, 3 * mDp);
            canvas.drawArc(mRect, 200, 80, false, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
        }
    }

    private static int blend(int from, int to, float t) {
        return Color.argb(
                Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t),
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t));
    }
}
