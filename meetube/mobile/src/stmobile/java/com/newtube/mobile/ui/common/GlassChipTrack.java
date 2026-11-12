package com.newtube.mobile.ui.common;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.ArrayList;
import java.util.List;

/**
 * MEETUBE(glass segmented): liquidglass.theatom.lk's segmented control as a scrolling chip row (Home's
 * topic chips, the search result filters). One glass track holds the chips; the picked one sits on a
 * light pill that springs to a newly picked chip. Press the pill and it swells into a clear glass
 * lens you drag across the chips, stretching with its speed like jelly; let go on a chip to pick it.
 * A press anywhere else scrolls the row or taps a chip. A plain pill, no lens, on a weak phone.
 */
public class GlassChipTrack extends HorizontalScrollView {
    public interface Picked {
        void onPicked(int index);
    }

    private static final float PAD = 4f;
    private static final float CHIP_H = 36f;
    private static final float CHIP_PAD = 14f;
    private static final float SWELL_W = 14f;
    private static final float SWELL_H = 0.18f;
    private static final int PILL = 0xF2FFFFFF;

    private final float mDp;
    private final boolean mSimple;
    private final int mSlop;
    private final Row mRow;
    private final List<CharSequence> mLabels = new ArrayList<>();
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();

    private final FloatValueHolder mLeft = new FloatValueHolder(0f);
    private final FloatValueHolder mRight = new FloatValueHolder(0f);
    private final FloatValueHolder mLift = new FloatValueHolder(0f);
    private final FloatValueHolder mStretch = new FloatValueHolder(0f);
    private final SpringAnimation mLeftSpring;
    private final SpringAnimation mRightSpring;
    private final SpringAnimation mLiftSpring;
    private final SpringAnimation mJellySpring;

    @Nullable private Picked mPicked;
    private boolean mGlassed;
    private boolean mPlaced;
    private int mSelected = -1;
    private int mHover = -1;
    private boolean mArmed;
    private boolean mDragging;
    private float mDownX;
    private final Runnable mDrop = new Runnable() {
        @Override
        public void run() {
            if (!mDragging) {
                GlassSwitch.spring(mLiftSpring, 0f, GlassSwitch.SETTLE);
            }
        }
    };

    public GlassChipTrack(Context context) {
        this(context, null);
    }

    public GlassChipTrack(Context context, @Nullable android.util.AttributeSet attrs) {
        super(context, attrs);
        mDp = getResources().getDisplayMetrics().density;
        mSimple = NavLens.lowEnd(context);
        mSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setHorizontalScrollBarEnabled(false);
        setFillViewport(true); // a few chips share the bar's width (weights below); many still scroll
        setOverScrollMode(OVER_SCROLL_NEVER);
        setHorizontalFadingEdgeEnabled(true); // the chips melt away at a side that has more to scroll to
        setFadingEdgeLength(Math.round(28 * mDp));
        setElevation(4 * mDp);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
            }
        });
        setClipToOutline(true);
        mRow = new Row(context);
        mRow.setOrientation(LinearLayout.HORIZONTAL);
        mRow.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(PAD * mDp);
        mRow.setPadding(pad, pad, pad, pad);
        addView(mRow, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mLeftSpring = newSpring(mLeft, true);
        mRightSpring = newSpring(mRight, false);
        mLiftSpring = newSpring(mLift, false);
        mJellySpring = newSpring(mStretch, false);
    }

    private SpringAnimation newSpring(FloatValueHolder holder, boolean feedsJelly) {
        SpringAnimation spring = new SpringAnimation(holder);
        spring.setSpring(new SpringForce(holder.getValue()));
        spring.setMinimumVisibleChange(0.001f);
        spring.addUpdateListener((a, value, velocity) -> {
            if (feedsJelly && !mSimple) {
                GlassSwitch.spring(mJellySpring, (float) (GlassSwitch.JELLY_MAX
                        * Math.tanh(Math.abs(velocity) / (GlassSwitch.JELLY_SPEED * 220 * mDp))), GlassSwitch.JELLY);
            }
            recolor();
            mRow.invalidate();
        });
        return spring;
    }

    /** The glass under the track: what it frosts (the feed, the results). Once. */
    public void setGlassSource(@Nullable GlassSource source) {
        if (!mGlassed) {
            mGlassed = true;
            GlassDrawable.apply(this, source, 0);
        }
    }

    public void setOnPicked(@Nullable Picked picked) {
        mPicked = picked;
    }

    /** The chips, left to right; the same labels again change nothing. */
    public void setChips(List<? extends CharSequence> labels) {
        if (labels.equals(mLabels)) {
            return;
        }
        mLabels.clear();
        mLabels.addAll(labels);
        mRow.removeAllViews();
        mPlaced = false;
        for (int i = 0; i < mLabels.size(); i++) {
            TextView chip = new TextView(getContext());
            chip.setText(mLabels.get(i));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine();
            int pad = Math.round(CHIP_PAD * mDp);
            chip.setPadding(pad, 0, pad, 0);
            int index = i;
            chip.setOnClickListener(v -> pick(index));
            mRow.addView(chip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(CHIP_H * mDp), 1f));
        }
        recolor();
    }

    /** The chip on the pill, -1 for none. */
    public void setSelected(int index) {
        if (index == mSelected) {
            return;
        }
        mSelected = index;
        glideToSelected(true);
        recolor();
    }

    private void pick(int index) {
        if (index == mSelected) {
            return;
        }
        setSelected(index);
        // A swell and settle as it lands, as the lens does when let go.
        GlassSwitch.spring(mLiftSpring, 1f, GlassSwitch.PRESS);
        removeCallbacks(mDrop);
        postDelayed(mDrop, 140);
        if (mPicked != null) {
            mPicked.onPicked(index);
        }
    }

    private void glideToSelected(boolean animate) {
        View chip = mSelected >= 0 ? mRow.getChildAt(mSelected) : null;
        if (chip == null || chip.getWidth() == 0) {
            return;
        }
        moveTo(chip.getLeft(), chip.getRight(), animate && mPlaced && !mSimple || animate && mPlaced);
        mPlaced = true;
        int visibleLeft = getScrollX();
        int visibleRight = visibleLeft + getWidth();
        if (chip.getLeft() < visibleLeft) {
            smoothScrollTo(chip.getLeft() - Math.round(PAD * mDp), 0);
        } else if (chip.getRight() > visibleRight) {
            smoothScrollTo(chip.getRight() - getWidth() + Math.round(PAD * mDp), 0);
        }
    }

    private void moveTo(float left, float right, boolean animate) {
        if (animate) {
            GlassSwitch.spring(mLeftSpring, left, GlassSwitch.DRAG);
            GlassSwitch.spring(mRightSpring, right, GlassSwitch.DRAG);
        } else {
            mLeftSpring.cancel();
            mRightSpring.cancel();
            mLeft.setValue(left);
            mRight.setValue(right);
            mLeftSpring.getSpring().setFinalPosition(left);
            mRightSpring.getSpring().setFinalPosition(right);
            mRow.invalidate();
        }
    }

    private float pillAlpha() {
        if (mSelected < 0 && !mDragging) {
            return 0f;
        }
        return mSimple ? 1f : Math.max(0f, Math.min(1f, 1f - 2.2f * mLift.getValue()));
    }

    /** Black on the light pill, white on the glass or the lens. */
    private void recolor() {
        int on = mDragging ? mHover : mSelected;
        int color = blend(Color.WHITE, Color.BLACK, pillAlpha());
        for (int i = 0; i < mRow.getChildCount(); i++) {
            ((TextView) mRow.getChildAt(i)).setTextColor(i == on ? color : Color.WHITE);
        }
    }

    private static int blend(int from, int to, float t) {
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.rgb(r, g, b);
    }

    // ---- Touch: a press on the pill lifts it into the lens; a drag moves it; anything else is the row's.

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                float x = ev.getX() + getScrollX();
                mDownX = ev.getX();
                mArmed = !mSimple && mSelected >= 0 && x >= mLeft.getValue() && x <= mRight.getValue();
                if (mArmed) {
                    removeCallbacks(mDrop);
                    GlassSwitch.spring(mLiftSpring, 1f, GlassSwitch.PRESS);
                    Haptics.tick(this);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mArmed && !mDragging && Math.abs(ev.getX() - mDownX) > mSlop) {
                    mDragging = true;
                    mHover = mSelected;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mArmed && !mDragging) {
                    mArmed = false;
                    GlassSwitch.spring(mLiftSpring, 0f, GlassSwitch.SETTLE);
                }
                break;
            default:
                break;
        }
        return !mDragging ? super.onInterceptTouchEvent(ev) : true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mDragging) {
            return super.onTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                drag(ev.getX());
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                drop(ev.getActionMasked() == MotionEvent.ACTION_UP);
                break;
            default:
                break;
        }
        return true;
    }

    private void drag(float viewX) {
        int count = mRow.getChildCount();
        if (count == 0) {
            return;
        }
        float edge = 36 * mDp;
        if (viewX < edge) {
            scrollBy(Math.round(-10 * mDp), 0);
        } else if (viewX > getWidth() - edge) {
            scrollBy(Math.round(10 * mDp), 0);
        }
        float x = viewX + getScrollX();
        int hover = 0;
        for (int i = 0; i < count; i++) {
            View chip = mRow.getChildAt(i);
            if (x >= chip.getLeft()) {
                hover = i;
            }
        }
        View chip = mRow.getChildAt(hover);
        float width = chip.getWidth();
        float min = mRow.getChildAt(0).getLeft();
        float max = mRow.getChildAt(count - 1).getRight() - width;
        float left = Math.max(min, Math.min(max, x - width / 2f));
        GlassSwitch.spring(mLeftSpring, left, GlassSwitch.DRAG);
        GlassSwitch.spring(mRightSpring, left + width, GlassSwitch.DRAG);
        if (hover != mHover) {
            mHover = hover;
            Haptics.tick(this);
            recolor();
        }
    }

    private void drop(boolean up) {
        mDragging = false;
        mArmed = false;
        int target = up ? mHover : mSelected;
        GlassSwitch.spring(mLiftSpring, 0f, GlassSwitch.SETTLE);
        boolean changed = target >= 0 && target != mSelected;
        mSelected = target;
        glideToSelected(true);
        recolor();
        if (changed && mPicked != null) {
            mPicked.onPicked(target);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mDrop);
        mLeftSpring.cancel();
        mRightSpring.cancel();
        mLiftSpring.cancel();
        mJellySpring.cancel();
        super.onDetachedFromWindow();
    }

    /** Draws the pill under the chips and the lens over them, in the scrolling row's own coordinates. */
    private final class Row extends LinearLayout {
        Row(Context context) {
            super(context);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (!mDragging) {
                glideToSelected(false);
                if (!mPlaced) {
                    recolor();
                }
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            float alpha = pillAlpha();
            float pad = PAD * mDp;
            float top = pad;
            float bottom = getHeight() - pad;
            if (alpha > 0f) {
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setColor(Color.argb(Math.round(Color.alpha(PILL) * alpha), 255, 255, 255));
                mRect.set(mLeft.getValue(), top, mRight.getValue(), bottom);
                canvas.drawRoundRect(mRect, (bottom - top) / 2f, (bottom - top) / 2f, mPaint);
            }
            super.dispatchDraw(canvas);
            float lift = mSimple ? 0f : Math.max(0f, Math.min(1f, mLift.getValue()));
            if (lift > 0f && (mSelected >= 0 || mDragging)) {
                drawLens(canvas, lift, top, bottom);
            }
        }

        private void drawLens(Canvas canvas, float lift, float top, float bottom) {
            float stretch = mStretch.getValue();
            float cx = (mLeft.getValue() + mRight.getValue()) / 2f;
            float cy = (top + bottom) / 2f;
            float kw = (mRight.getValue() - mLeft.getValue() + SWELL_W * mDp * lift) * (1f + stretch);
            float kh = (bottom - top) * (1f + SWELL_H * lift) * (1f - stretch * 0.5f);
            mRect.set(cx - kw / 2f, cy - kh / 2f, cx + kw / 2f, cy + kh / 2f);
            float r = kh / 2f;
            mShadow.setStyle(Paint.Style.FILL);
            mShadow.setColor(Color.TRANSPARENT);
            mShadow.setShadowLayer((3 + 5 * lift) * mDp, 0, (1 + 2 * lift) * mDp,
                    Color.argb(Math.round(255 * 0.22f * lift), 0, 0, 0));
            canvas.drawRoundRect(mRect, r, r, mShadow);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(Color.argb(Math.round(40 * lift), 255, 255, 255));
            canvas.drawRoundRect(mRect, r, r, mPaint);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(0.9f * mDp);
            mPaint.setColor(Color.argb(Math.round(235 * lift), 255, 255, 255));
            canvas.drawRoundRect(mRect, r, r, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
        }
    }
}
