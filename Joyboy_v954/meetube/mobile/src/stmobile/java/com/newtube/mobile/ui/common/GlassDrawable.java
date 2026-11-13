package com.newtube.mobile.ui.common;

import android.content.res.ColorStateList;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * MEETUBE(glass): {@link GlassView}'s frost as a background drawable, for controls whose visibility,
 * alpha and size the code already drives (the player's buttons) - it follows them with no wrapper
 * view. A disc (a pill, on a wide view) inset into the bounds: the {@link GlassSource} under it
 * (the video) saturated and blurred, a dark tint, grain and a light rim, with a ripple on press.
 * No lens: on a 40dp disc it would only magnify. Below API 31 there is nothing to sample, so the
 * tint alone, darker. With no source (the player's controls) the light tint alone: see-through.
 * With Liquid glass off ({@link Glass#isLiquid}) no grain or rim, as GlassView.
 */
public final class GlassDrawable extends Drawable {
    private static final float BLUR_DP = 12f;
    private static final float PILL_BLUR_DP = 28f;
    private static final float SATURATION = 1.5f;
    private static final int TINT = 0x47000000;
    private static final int FALLBACK_TINT = 0x8C000000;
    private static final int NOISE_ALPHA = 10;

    private final View mHost;
    private final GlassSource mSource;
    private final int mInset;
    private final float mDensity;
    private final boolean mLiquid;
    private final Paint mTint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mNoise = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mShape = new RectF();
    private final int[] mLoc = new int[2];
    private final int[] mSourceLoc = new int[2];
    private int mRecordedX = Integer.MIN_VALUE;
    private int mRecordedY = Integer.MIN_VALUE;
    @Nullable
    private final RenderNode mNode;
    private boolean mPaused;
    private boolean mSampled = true;
    /** 0: {@link #TINT}. */
    private final int mTintColor;

    /**
     * Off, {@code host}'s glass (from {@link #apply}) stops sampling its source and shows the tint
     * alone. Every disc re-blurs its part of the video each frame: ten of them made 13% of the
     * player's frames late on a vivo T3 Pro while a sheet covered them (#28).
     */
    public static void setLive(@NonNull View host, boolean live) {
        Drawable background = host.getBackground();
        if (background instanceof RippleDrawable
                && ((RippleDrawable) background).getDrawable(0) instanceof GlassDrawable) {
            GlassDrawable glass = (GlassDrawable) ((RippleDrawable) background).getDrawable(0);
            if (glass.mPaused == live) {
                glass.mPaused = !live;
                glass.invalidateSelf();
            }
        }
    }

    /** Makes {@code host}'s background the glass, inset {@code insetPx} from its edges, with a ripple. */
    public static void apply(@NonNull View host, @Nullable GlassSource source, int insetPx) {
        apply(host, source, insetPx, 0);
    }

    /**
     * MEETUBE(minimal buttons): a player, Shorts or mini player control - its disc as {@link #apply},
     * or with Player buttons on Minimal no disc: a round ripple under a press, and over the video
     * ({@code shadow}) a soft dark halo around the icon so it reads on a bright frame, YouTube's look.
     * The halo is a RenderEffect: API 31+, the bare icon below.
     */
    public static void applyButton(@NonNull View host, @Nullable GlassSource source, int insetPx, boolean shadow) {
        if (Glass.playerButtons(host.getContext()) != Glass.BUTTONS_MINIMAL) {
            apply(host, source, insetPx);
            return;
        }
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(Float.MAX_VALUE / 2);
        mask.setColor(0xFFFFFFFF);
        int start = host.getPaddingStart();
        int top = host.getPaddingTop();
        int end = host.getPaddingEnd();
        int bottom = host.getPaddingBottom();
        host.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, new InsetDrawable(mask, insetPx)));
        host.setPaddingRelative(start, top, end, bottom);
        if (shadow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            float blur = 3 * host.getResources().getDisplayMetrics().density;
            RenderEffect halo = RenderEffect.createBlurEffect(blur, blur, RenderEffect.createColorFilterEffect(
                    new android.graphics.PorterDuffColorFilter(0x99000000, android.graphics.PorterDuff.Mode.SRC_IN)),
                    Shader.TileMode.DECAL);
            // the icon (an offset of nothing: the view's own content) over its halo
            host.setRenderEffect(RenderEffect.createBlendModeEffect(halo, RenderEffect.createOffsetEffect(0f, 0f),
                    android.graphics.BlendMode.SRC_OVER));
        }
    }

    /** As above, under {@code tint} (0: the buttons' own) - the player's message pills, milkier. */
    public static void apply(@NonNull View host, @Nullable GlassSource source, int insetPx, int tint) {
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(Float.MAX_VALUE / 2); // a disc or a pill, whatever the bounds
        mask.setColor(0xFFFFFFFF);
        int start = host.getPaddingStart();
        int top = host.getPaddingTop();
        int end = host.getPaddingEnd();
        int bottom = host.getPaddingBottom();
        host.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                new GlassDrawable(host, source, insetPx, tint), new InsetDrawable(mask, insetPx)));
        host.setPaddingRelative(start, top, end, bottom); // the inset is not padding: the icon stays its size
    }

    private GlassDrawable(View host, @Nullable GlassSource source, int insetPx, int tint) {
        mTintColor = tint;
        mHost = host;
        mSource = source;
        mInset = insetPx;
        mDensity = host.getResources().getDisplayMetrics().density;
        mLiquid = Glass.isLiquid(host.getContext());
        boolean live = GlassSource.HARDWARE && source != null;
        mNode = live ? new RenderNode("MeeTube glass button") : null;
        mNoise.setShader(new BitmapShader(GlassView.noise(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        mNoise.setAlpha(NOISE_ALPHA);
        mRim.setStyle(Paint.Style.STROKE);
        mRim.setStrokeWidth(mDensity);
        if (live) {
            ColorMatrix saturate = new ColorMatrix();
            saturate.setSaturation(SATURATION);
            // a message pill: frosted as heavily as the app's (SheetGlass), the video only colour under it
            float blur = (tint != 0 ? PILL_BLUR_DP : BLUR_DP) * mDensity;
            mNode.setRenderEffect(RenderEffect.createBlurEffect(blur, blur,
                    RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(saturate)), Shader.TileMode.CLAMP));
            mNode.setClipToOutline(true);
            // re-sample when the button moves over the video (a rotation re-centres the transport)
            // ...and when the source starts or stops sampling (HDR output)
            host.getViewTreeObserver().addOnPreDrawListener(() -> {
                if (host.isShown() && (mSampled != mSource.sampling || moved())) {
                    invalidateSelf();
                }
                return true;
            });
        }
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        mShape.set(bounds.left + mInset, bounds.top + mInset, bounds.right - mInset, bounds.bottom - mInset);
        if (mTintColor != 0) {
            // a message pill: the app message pill's flat hairline (SheetGlass), not the buttons' light
            mRim.setColor(androidx.core.content.ContextCompat.getColor(mHost.getContext(), com.liskovsoft.smartyoutubetv2.tv.R.color.meetube_toast_rim));
            mRecordedX = Integer.MIN_VALUE;
            return;
        }
        // light from above, a faint bounce at the bottom (GlassView's rim without runtime shaders)
        mRim.setShader(new LinearGradient(0, mShape.top, 0, mShape.bottom,
                new int[] {0x66FFFFFF, 0x14FFFFFF, 0x0AFFFFFF, 0x2EFFFFFF},
                new float[] {0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        mRecordedX = Integer.MIN_VALUE;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (mShape.isEmpty()) {
            return;
        }
        float r = Math.min(mShape.width(), mShape.height()) / 2f;
        mSampled = mSource != null && mSource.sampling;
        boolean live = mNode != null && !mPaused && mSampled && canvas.isHardwareAccelerated()
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        mTint.setColor(live || mSource == null || mPaused || !mSampled
                ? (mTintColor != 0 ? mTintColor : TINT) : FALLBACK_TINT);
        if (live) {
            moved();
            mRecordedX = mLoc[0] - mSourceLoc[0];
            mRecordedY = mLoc[1] - mSourceLoc[1];
            int w = Math.round(mShape.width());
            int h = Math.round(mShape.height());
            mNode.setPosition(0, 0, w, h);
            Outline outline = new Outline();
            outline.setRoundRect(0, 0, w, h, r);
            mNode.setOutline(outline);
            Canvas recording = mNode.beginRecording();
            recording.translate(-mRecordedX, -mRecordedY);
            recording.drawRenderNode(mSource.node);
            mNode.endRecording();
            int save = canvas.save();
            canvas.translate(mShape.left, mShape.top);
            canvas.drawRenderNode(mNode);
            canvas.restoreToCount(save);
        }
        canvas.drawRoundRect(mShape, r, r, mTint);
        if (mTintColor != 0) {
            canvas.drawRoundRect(mShape, r, r, mRim); // a message pill: as the app's, Liquid glass or not
        } else if (mLiquid) {
            canvas.drawRoundRect(mShape, r, r, mNoise);
            canvas.drawRoundRect(mShape, r, r, mRim);
        }
    }

    /** Refreshes where the glass sits on the source; true when that differs from the last recording. */
    private boolean moved() {
        mHost.getLocationInWindow(mLoc);
        mSource.getLocationInWindow(mSourceLoc);
        mLoc[0] += Math.round(mShape.left);
        mLoc[1] += Math.round(mShape.top);
        return mLoc[0] - mSourceLoc[0] != mRecordedX || mLoc[1] - mSourceLoc[1] != mRecordedY;
    }

    @Override
    public void setAlpha(int alpha) {
        // the host's own alpha fades it (the controls' show / hide)
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
