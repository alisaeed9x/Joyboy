package com.newtube.mobile.ui.settings;

import android.animation.ValueAnimator;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

/**
 * MEETUBE(about): the About card leans with the phone - it tilts a few degrees and the photo floats
 * over it (and every button on it), like a card held in the hand. Reads gravity, not the gyroscope (no drift), and only while
 * the card is on screen. The neutral pose follows how the phone is held, so a new grip re-centres it.
 */
public final class TiltParallax implements SensorEventListener, View.OnAttachStateChangeListener {
    private static final float MAX_DEGREES = 7f;
    private static final float FLOAT_DP = 10f;
    /** m/s² of lean (about 20 degrees) for the full effect. */
    private static final float FULL_TILT = 3.5f;

    private final View mCard;
    private final View[] mFloating;
    private final SensorManager mSensors;
    private final Sensor mGravity;
    private boolean mHasBase;
    private float mBaseX;
    private float mBaseY;
    private float mX;
    private float mY;
    private final float mStrength;

    private TiltParallax(View card, View[] floating, SensorManager sensors, Sensor gravity, float strength) {
        mStrength = strength;
        mCard = card;
        mFloating = floating;
        mSensors = sensors;
        mGravity = gravity;
        card.setCameraDistance(8000 * card.getResources().getDisplayMetrics().density);
    }

    /** No gravity sensor, or animations turned off in the phone's settings: the card stays still. */
    public static void attach(ViewGroup card, View... floating) {
        attach(card, 1f, floating);
    }

    /** {@code strength} scales the lean and the float: 1 is the About card, less is subtler. */
    public static void attach(ViewGroup card, float strength, View... floating) {
        SensorManager sensors = (SensorManager) card.getContext().getSystemService(Context.SENSOR_SERVICE);
        Sensor gravity = sensors != null ? sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) : null;
        if (gravity == null || (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())) {
            return;
        }
        // What floats moves into the card's padding: drawn there, not cut at its edge.
        card.setClipToPadding(false);
        card.setClipChildren(false);
        card.addOnAttachStateChangeListener(new TiltParallax(card, floating, sensors, gravity, strength));
    }

    @Override
    public void onViewAttachedToWindow(View v) {
        mHasBase = false;
        mSensors.registerListener(this, mGravity, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        mSensors.unregisterListener(this);
        mX = mY = 0f;
        apply();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float gx = event.values[0];
        float gy = event.values[1];
        if (!mHasBase) {
            mHasBase = true;
            mBaseX = gx;
            mBaseY = gy;
        }
        mBaseX += (gx - mBaseX) * 0.01f;
        mBaseY += (gy - mBaseY) * 0.01f;
        mX += (clamp((gx - mBaseX) / FULL_TILT) - mX) * 0.2f;
        mY += (clamp((gy - mBaseY) / FULL_TILT) - mY) * 0.2f;
        apply();
    }

    private void apply() {
        mCard.setRotationY(-mX * MAX_DEGREES * mStrength);
        mCard.setRotationX(-mY * MAX_DEGREES * mStrength);
        float px = FLOAT_DP * mStrength * mCard.getResources().getDisplayMetrics().density;
        for (View view : mFloating) {
            view.setTranslationX(-mX * px);
            view.setTranslationY(mY * px);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private static float clamp(float value) {
        return Math.max(-1f, Math.min(1f, value));
    }
}
