package com.newtube.mobile.ui.common;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * MEETUBE(nav lens, issue #46): a tab switch as iOS 26's liquid glass tab bar plays it - the lit pill
 * swells into a clear lens taller than the bar, glides to the picked tab (stretching with its speed,
 * landing with a little overshoot) magnifying and bending the bar under it through
 * {@link GlassView}'s lens shader, unblurred, and settles back down into the pill.
 *
 * <p>It re-draws the bar into itself only while it moves, and is gone otherwise. Needs runtime
 * shaders (API 33+) and Liquid glass on ({@link #supported}); without them the bar only wiggles
 * ({@link #jiggle}), and with the setting off ({@link #enabled}) it just nudges.
 */
public final class NavLens extends View {
    private static final String PREFS = "meetube_app";
    private static final String KEY_MOTION = "tab_switch_motion";
    /** The lens swells in place this long before it glides (a switch's page swap lands then, not mid-glide). */
    private static final long GLIDE_DELAY_MS = 50;
    private static final float GROW_MS = 130f;
    private static final float SETTLE_MS = 200f;
    private static final float GLIDE_STIFFNESS = 480f;
    private static final float GLIDE_DAMPING = 0.82f;
    public static final long JIGGLE_MS = 700;
    private static final float JIGGLE_LEAN_DP = 6f;
    /** How much bigger the bar looks through the lens. */
    private static final float MAGNIFY = 1.12f;
    /** Past the tab: how much wider and taller than it the lens swells. */
    private static final float GROW_W_DP = 12f;
    private static final float GROW_H_DP = 6f;
    /** At full speed the lens is this much wider (and a third of it flatter). */
    private static final float STRETCH = 0.26f;
    private static final float REFRACTION_DP = 16f;
    private static final int TINT = 0x10FFFFFF;

    private final float mDensity;
    private final Path mBarClip = new Path();
    private final Path mLensClip = new Path();
    @Nullable private RenderNode mNode;
    @Nullable private Drawable mRim;
    @Nullable private GlassView mBar;
    @Nullable private ValueAnimator mAnimator;
    private int mEffectW;
    private int mEffectH;
    /** How much bigger the bar looks through it right now: MAGNIFY in flight, 1 at rest (no ghost as it fades). */
    private float mMagnify = 1f;
    /** The lens centre, in the bar's coordinates. */
    private float mCx;
    private float mCy;

    public NavLens(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getDisplayMetrics().density;
    }

    public static boolean supported(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && Glass.isLiquid(context);
    }

    /**
     * Settings > Customization > Tab switch animation: the lens and the bar's wiggle. On unless the
     * person turned it off, or, untouched, on a low-end phone ({@link #lowEnd}).
     */
    public static boolean enabled(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.contains(KEY_MOTION) ? prefs.getBoolean(KEY_MOTION, true) : !lowEnd(context);
    }

    public static void setEnabled(Context context, boolean on) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MOTION, on).apply();
    }

    /**
     * Android's own low-RAM flag, about 3 GB or less (a 4 GB phone reports ~3.7), or under six
     * cores. ponytail: a coarse guess - a measured frame-time check would catch a slow GPU too.
     */
    public static boolean lowEnd(Context context) {
        android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo memory = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(memory);
        return am.isLowRamDevice() || memory.totalMem < 3_500_000_000L || Runtime.getRuntime().availableProcessors() < 6;
    }

    /**
     * The bar's jelly wiggle on a switch: it leans toward the picked tab ({@code direction} -1 left,
     * 1 right) and wobbles - wider and flatter, then narrower and taller - settling in ~0.7 s.
     */
    public static void jiggle(View bar, int direction) {
        Object running = bar.getTag(com.liskovsoft.smartyoutubetv2.tv.R.id.mobile_jiggle_animator);
        if (running instanceof Animator) {
            ((Animator) running).cancel();
        }
        float fromX = bar.getScaleX(); // a dragged bar's pop (#90) settles into the wiggle
        float fromY = bar.getScaleY();
        float lean = JIGGLE_LEAN_DP * bar.getResources().getDisplayMetrics().density * direction;
        ValueAnimator jiggle = ValueAnimator.ofFloat(0f, 1f);
        jiggle.setDuration(JIGGLE_MS);
        jiggle.setInterpolator(new LinearInterpolator());
        jiggle.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float wobble = (float) (Math.exp(-4.2 * t) * Math.sin(2 * Math.PI * 2.25 * t));
            float popped = (float) Math.exp(-6 * t);
            bar.setScaleX((1f + (fromX - 1f) * popped) * (1f + 0.028f * wobble));
            bar.setScaleY((1f + (fromY - 1f) * popped) * (1f - 0.04f * wobble));
            bar.setTranslationX(lean * (float) (Math.exp(-3.5 * t) * Math.sin(2 * Math.PI * 1.4 * t)));
        });
        jiggle.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) { // also after cancel()
                bar.setScaleX(1f);
                bar.setScaleY(1f);
                bar.setTranslationX(0f);
            }
        });
        bar.setTag(com.liskovsoft.smartyoutubetv2.tv.R.id.mobile_jiggle_animator, jiggle);
        jiggle.start();
    }

    /**
     * Plays the switch from tab view {@code from} to {@code to}, both inside {@code bar};
     * {@code onSettled} runs as the lens starts settling into the pill, so the two cross-fade (not
     * when a newer switch takes over first).
     */
    public void play(GlassView bar, View from, View to, Runnable onSettled) {
        // The tabs' centres in the bar's own coordinates (their layout, untouched by any wiggle).
        play(bar, centreInBar(from, bar), to, false, onSettled);
    }

    /**
     * MEETUBE(#90): the lens under a finger dragging the lit pill along the bar, centred at {@code x}
     * (bar coordinates); {@link #play} with {@code grown} glides it on from there when it is let go.
     */
    public void drag(GlassView bar, View tab, float x) {
        mBar = bar;
        mCy = centreYInBar(tab, bar); // JOYBOY(v914): the tab's own row, not the bar's middle (the bar can also hold the Video/Music switch above the tabs)
        mCx = x;
        mMagnify = MAGNIFY;
        int w = fit(tab);
        int h = getLayoutParams().height;
        if (!mDragging) { // it swells out of the pill (or out of a glide it takes over)
            mDragging = true;
            stop();
            clipTo(bar);
            if (getVisibility() != VISIBLE) {
                setScaleX((tab.getWidth() - 4 * mDensity) / w);
                setScaleY((float) tab.getHeight() / h);
            }
            setAlpha(1f);
            setVisibility(VISIBLE);
            animate().scaleX(1f).scaleY(1f).setDuration((long) GROW_MS)
                    .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
        }
        place(bar, w, h);
        invalidate();
    }

    private boolean mDragging;

    private void clipTo(GlassView bar) {
        float corner = bar.corner();
        mBarClip.reset();
        mBarClip.addRoundRect(0, 0, bar.getWidth(), bar.getHeight(), corner, corner, Path.Direction.CW);
    }

    public void play(GlassView bar, float fromX, View to, boolean grown, Runnable onSettled) {
        stop();
        mDragging = false;
        mBar = bar;
        float toX = centreInBar(to, bar);
        mCy = centreYInBar(to, bar); // JOYBOY(v914): glide along the tabs row only
        int w = fit(to);
        int h = getLayoutParams().height;
        // The pill it grows out of and settles into: the tab, 2dp in on each side (bg_nav_item).
        float restX = (to.getWidth() - 4 * mDensity) / w;
        float restY = (float) to.getHeight() / h;
        clipTo(bar);

        // The glide is a quick spring (a ~1% overshoot as it lands); its stretch follows its real speed.
        Motion.Spring glide = new Motion.Spring(0f, 1f, 0f, GLIDE_STIFFNESS, GLIDE_DAMPING, 0.004f);
        long glideMs = Math.max(1, glide.durationMs);
        float peakSpeed = 1e-3f;
        for (int i = 1; i < 40; i++) {
            peakSpeed = Math.max(peakSpeed, speed(glide, i / 40f));
        }
        float topSpeed = peakSpeed;
        // MEETUBE(nav drag): stretched by the distance covered too. A drag let go on its tab glides
        // nowhere; stretched in full it poked past the bar's rounded end on Home or You.
        float reach = Math.min(1f, Math.abs(toX - fromX) / Math.max(1, to.getWidth()));
        long settleAt = GLIDE_DELAY_MS + Math.round(glideMs * 0.7f);
        long total = settleAt + (long) SETTLE_MS;

        setVisibility(VISIBLE);
        boolean[] settled = {false};
        ValueAnimator animator = ValueAnimator.ofFloat(0f, total);
        animator.setDuration(total);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            float ms = (float) a.getAnimatedValue();
            float grow = grown ? 1f : Motion.EMPHASIZED_DECELERATE.getInterpolation(clamp(ms / GROW_MS));
            float travel = clamp((ms - GLIDE_DELAY_MS) / glideMs);
            float settle = Motion.STANDARD.getInterpolation(clamp((ms - settleAt) / SETTLE_MS));
            float presence = grow * (1f - settle);
            float speed = travel <= 0f || travel >= 1f ? 0f : Math.min(1f, speed(glide, travel) / topSpeed) * reach;
            mMagnify = lerp(1f, MAGNIFY, presence);
            if (settle > 0f && !settled[0]) {
                settled[0] = true;
                onSettled.run();
            }
            mCx = fromX + (toX - fromX) * glide.getInterpolation(travel);
            setScaleX(lerp(restX, 1f, presence) * (1f + STRETCH * speed));
            setScaleY(lerp(restY, 1f, presence) * (1f - STRETCH / 3f * speed));
            setAlpha(clamp(presence * 1.6f));
            place(bar, w, h);
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setVisibility(GONE);
                mAnimator = null;
                if (!settled[0]) {
                    onSettled.run();
                }
            }
        });
        mAnimator = animator;
        animator.start();
    }

    private void stop() {
        animate().cancel();
        if (mAnimator != null) {
            mAnimator.removeAllListeners();
            mAnimator.cancel();
            mAnimator = null;
        }
    }

    /** Sizes the lens to {@code tab} swollen; returns its width (the height is in its layout params). */
    private int fit(View tab) {
        int w = Math.round(tab.getWidth() + GROW_W_DP * mDensity);
        int h = Math.round(tab.getHeight() + GROW_H_DP * mDensity);
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            setLayoutParams(lp);
        }
        return w;
    }

    /** Over the point (mCx, mCy) of the bar as it is now, the bar's wiggle (scale about its centre) included. */
    private void place(View bar, int w, int h) {
        float px = bar.getPivotX();
        float py = bar.getPivotY();
        setTranslationX(bar.getLeft() + bar.getTranslationX() + px + (mCx - px) * bar.getScaleX() - getLeft() - w / 2f);
        setTranslationY(bar.getTop() + bar.getTranslationY() + py + (mCy - py) * bar.getScaleY() - getTop() - h / 2f);
    }

    /** JOYBOY(v914): the vertical centre of {@code tab} in {@code bar}'s layout coordinates (no transforms). */
    public static float centreYInBar(View tab, View bar) {
        float y = tab.getHeight() / 2f;
        View view = tab;
        while (view != bar && view.getParent() instanceof View) {
            y += view.getTop() - ((View) view.getParent()).getScrollY();
            view = (View) view.getParent();
        }
        return y;
    }

    /** The horizontal centre of {@code tab} in {@code bar}'s layout coordinates (no transforms). */
    public static float centreInBar(View tab, View bar) {
        float x = tab.getWidth() / 2f;
        View view = tab;
        while (view != bar && view.getParent() instanceof View) {
            x += view.getLeft() - ((View) view.getParent()).getScrollX();
            view = (View) view.getParent();
        }
        return x;
    }

    /** How fast the spring moves at {@code u} of its run (any unit: it is divided by its peak). */
    private static float speed(Motion.Spring spring, float u) {
        float du = 0.01f;
        return Math.abs(spring.getInterpolation(Math.min(1f, u + du)) - spring.getInterpolation(Math.max(0f, u - du)));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float r = Math.min(w, h) / 2f;
        mLensClip.reset();
        mLensClip.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
        mRim = GlassView.rim(r, mDensity);
        mRim.setBounds(0, 0, w, h);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (mBar == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !canvas.isHardwareAccelerated()) {
            return;
        }
        drawLens(canvas);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private void drawLens(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (mNode == null) {
            mNode = new RenderNode("MeeTube nav lens");
        }
        mNode.setPosition(0, 0, w, h);
        if (w != mEffectW || h != mEffectH) {
            mEffectW = w;
            mEffectH = h;
            RuntimeShader lens = new RuntimeShader(GlassView.REFRACTION_AGSL);
            lens.setFloatUniform("size", w, h);
            lens.setFloatUniform("radius", Math.min(w, h) / 2f);
            lens.setFloatUniform("refractionHeight", REFRACTION_DP * mDensity);
            lens.setFloatUniform("refractionAmount", -REFRACTION_DP * mDensity);
            lens.setFloatUniform("depthEffect", 1f);
            lens.setFloatUniform("chromaticAberration", 1f);
            mNode.setRenderEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"));
        }
        // The bar as it sits under the lens, magnified about the lens centre. The view's own scale
        // (the stretch) is divided out, so the bar is magnified evenly whatever shape the lens is.
        Canvas recording = mNode.beginRecording();
        recording.translate(w / 2f, h / 2f);
        recording.scale(mMagnify * mBar.getScaleX() / getScaleX(), mMagnify * mBar.getScaleY() / getScaleY());
        recording.translate(-mCx, -mCy);
        recording.clipPath(mBarClip);
        mBar.draw(recording);
        mNode.endRecording();

        canvas.save();
        canvas.clipPath(mLensClip);
        canvas.drawRenderNode(mNode);
        canvas.drawColor(TINT);
        canvas.restore();
        if (mRim != null) {
            mRim.draw(canvas);
        }
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
