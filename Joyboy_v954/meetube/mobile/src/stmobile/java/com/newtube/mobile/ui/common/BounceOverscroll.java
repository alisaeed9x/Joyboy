package com.newtube.mobile.ui.common;

import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EdgeEffect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * MEETUBE(bounce): iOS-style overscroll on every list in the app, in place of Android 12's stretch.
 * Pulled past its start or end a list follows the finger with growing resistance and stretches a
 * little from the pulled edge; let go, it springs back with a bounce, and a fling into the end
 * bounces off it. A list set to never overscroll keeps that (RecyclerView asks for no effect).
 *
 * <p>The same pass also turns off every view's scroll bars (the thin bar at the side of a list).</p>
 *
 * <p>Installed per window ({@link #install}): every RecyclerView laid out in it gets it, as
 * FocusRing finds its clickables. ponytail: RecyclerViews only - ScrollViews keep the system's
 * stretch; give them a wrapper if they should bounce too.</p>
 */
public final class BounceOverscroll extends RecyclerView.EdgeEffectFactory {
    /** Share of the finger's travel the list follows at first; less the further it has gone. */
    private static final float PULL = 0.5f;
    /** The list goes at most this share of its length past the end. */
    private static final float MAX_SHARE = 0.2f;
    /** Share of a fling's speed (px/s) it hits the end with. */
    private static final float FLING = 0.3f;
    /** At the farthest pull the list is this much longer. */
    private static final float STRETCH = 0.06f;
    private static final float DAMPING = 0.55f;
    private static final float STIFFNESS = 380f;
    private static final BounceOverscroll FACTORY = new BounceOverscroll();

    public static void install(@Nullable Window window) {
        View decor = window != null ? window.peekDecorView() : null;
        if (decor == null || decor.getTag(R.id.bounce_overscroll) != null) {
            return;
        }
        decor.setTag(R.id.bounce_overscroll, FACTORY);
        decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> applyTo(decor));
    }

    private static void applyTo(View view) {
        // MEETUBE(scrollbars): no scroll indicator anywhere either - the same pass, every view.
        view.setVerticalScrollBarEnabled(false);
        view.setHorizontalScrollBarEnabled(false);
        if (view instanceof RecyclerView && view.getTag(R.id.bounce_overscroll) == null) {
            view.setTag(R.id.bounce_overscroll, FACTORY);
            ((RecyclerView) view).setEdgeEffectFactory(FACTORY);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTo(group.getChildAt(i));
            }
        }
    }

    @NonNull
    @Override
    protected EdgeEffect createEdgeEffect(@NonNull RecyclerView list, int direction) {
        return new Bounce(list, direction);
    }

    /** One edge of one list: the list's translation (and stretch) along its axis is the effect. */
    private static final class Bounce extends EdgeEffect {
        private final RecyclerView mList;
        private final boolean mVertical;
        /** 1 at the start edge (the list moves toward the end), -1 at the end edge. */
        private final float mSign;
        private final SpringAnimation mSpring;
        /** The list's own translation before the effect moved it. */
        private float mBase;
        private float mOffset;

        Bounce(RecyclerView list, int direction) {
            super(list.getContext());
            mList = list;
            mVertical = direction == DIRECTION_TOP || direction == DIRECTION_BOTTOM;
            mSign = direction == DIRECTION_TOP || direction == DIRECTION_LEFT ? 1f : -1f;
            mSpring = new SpringAnimation(new FloatValueHolder())
                    .setSpring(new SpringForce(0f).setDampingRatio(DAMPING).setStiffness(STIFFNESS));
            mSpring.addUpdateListener((animation, value, velocity) -> show(value));
            mSpring.addEndListener((animation, canceled, value, velocity) -> {
                if (!canceled) {
                    show(0f); // exactly at rest, unstretched
                }
            });
        }

        @Override
        public void onPull(float deltaDistance) {
            pull(deltaDistance);
        }

        @Override
        public void onPull(float deltaDistance, float displacement) {
            pull(deltaDistance);
        }

        @Override
        public float onPullDistance(float deltaDistance, float displacement) {
            float before = Math.abs(mOffset);
            pull(deltaDistance);
            // Pushed back toward rest it gives back only what it had (the rest scrolls the list).
            return deltaDistance >= 0f ? deltaDistance : (Math.abs(mOffset) - before) / (length() * PULL);
        }

        @Override
        public float getDistance() {
            return Math.abs(mOffset) / length();
        }

        @Override
        public void onRelease() {
            if (mOffset != 0f) {
                mSpring.setStartValue(mOffset);
                mSpring.setStartVelocity(0f);
                mSpring.start();
            }
        }

        @Override
        public void onAbsorb(int velocity) {
            begin();
            mSpring.setStartValue(mOffset);
            mSpring.setStartVelocity(mSign * velocity * FLING);
            mSpring.start();
        }

        @Override
        public boolean isFinished() {
            return mOffset == 0f && !mSpring.isRunning();
        }

        @Override
        public boolean draw(Canvas canvas) {
            return false; // nothing drawn: the list itself moves
        }

        private void pull(float deltaDistance) {
            begin();
            float max = length() * MAX_SHARE;
            // Harder the further out it goes; pushed back toward rest it follows the finger freely.
            float resistance = deltaDistance < 0f ? 1f : 1f - Math.min(1f, Math.abs(mOffset) / max);
            float next = mOffset + mSign * deltaDistance * length() * PULL * resistance;
            show(mSign * Math.max(0f, Math.min(max, next * mSign))); // never past rest, nor the limit on a fast pull
        }

        /** Takes over from a bounce still playing, or notes where the list sits at rest. */
        private void begin() {
            if (mSpring.isRunning()) {
                mSpring.cancel();
            } else if (mOffset == 0f) {
                mBase = mVertical ? mList.getTranslationY() : mList.getTranslationX();
            }
        }

        private void show(float offset) {
            mOffset = offset;
            float stretch = 1f + STRETCH * Math.min(1f, Math.abs(offset) / (length() * MAX_SHARE));
            if (mVertical) {
                mList.setPivotY(mSign > 0f ? 0f : mList.getHeight());
                mList.setTranslationY(mBase + offset);
                mList.setScaleY(stretch);
            } else {
                mList.setPivotX(mSign > 0f ? 0f : mList.getWidth());
                mList.setTranslationX(mBase + offset);
                mList.setScaleX(stretch);
            }
        }

        private float length() {
            return Math.max(1f, mVertical ? mList.getHeight() : mList.getWidth());
        }
    }
}
