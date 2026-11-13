package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.lang.ref.WeakReference;

/**
 * MEETUBE(glass): frosted sheets. A sheet lives in its own window, so it cannot sample the page live
 * the way {@link GlassView} does; instead the page's window is copied once as the sheet opens
 * (PixelCopy, video frame included), blurred small, and painted only inside the sheet - lined up
 * with the screen, so it stays put under the sheet while it is dragged. The page itself stays sharp.
 * Over the player, from API 31, the copy is taken again every frame or so while the sheet shows (see
 * {@link #liveOver}). Below API 26 the sheet keeps its plain see-through surface.
 */
public final class SheetGlass {
    /** The copy is this many times smaller than the page; scaling it back up adds to the blur. */
    private static final int SCALE = 8;
    private static final int BLUR_RADIUS = 3;
    private static final float CORNER_DP = 28f;
    /** A live sheet's copies: about the frame rate of most videos. */
    private static final long LIVE_FRAME_MS = 33;
    /**
     * A live copy's GPU blur, in page pixels: as soft as {@link #blur} makes a 1/8 copy (three box
     * passes of radius 3 are a sigma of 3.5 there, 28 on the page; RenderEffect's radius r is a sigma
     * of 0.577 r + 0.5).
     */
    private static final float LIVE_BLUR_PX = 47f;

    private static WeakReference<Activity> sBackdrop = new WeakReference<>(null);
    @Nullable
    private static Class<? extends Activity> sLivePage;

    /**
     * MEETUBE(glass): sheets over {@code page} (the player) keep copying it while they show, so the
     * video plays on under the frost instead of freezing on the frame it had when the sheet opened.
     * A live copy is not blurred on the CPU (16 ms a copy on a 2024 phone): the GPU blurs it as the
     * sheet draws (RenderEffect, API 31), so the main thread only asks for a 1/8 copy each frame.
     */
    public static void liveOver(Class<? extends Activity> page) {
        sLivePage = page;
    }

    private SheetGlass() {
    }

    /** Remembers the last resumed screen that is not a {@code sheetActivity}: what such a sheet frosts. */
    public static void track(Application app, Class<? extends Activity> sheetActivity) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                if (!sheetActivity.isInstance(activity)) {
                    sBackdrop = new WeakReference<>(activity);
                }
            }

            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) {
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
            }
        });
    }

    /** The screen under a sheet that has its own activity, if still around. */
    @Nullable
    public static Activity backdrop() {
        return sBackdrop.get();
    }

    /** A bottom sheet dialog that just showed: frosts its content with its activity's page. */
    static void frost(@NonNull Dialog dialog) {
        frost(dialog, true);
    }

    /** As above; square corners for a sheet that is a full-height column (the landscape live chat). */
    public static void frost(@NonNull Dialog dialog, boolean rounded) {
        View frame = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        Activity activity = activityOf(dialog.getContext());
        if (frame instanceof ViewGroup && ((ViewGroup) frame).getChildCount() > 0 && activity != null) {
            // The frame pads its content for the system bars (the sides in landscape, the top once
            // expanded to full height): the glass covers the whole frame, padding included, so the
            // frame's plain tint never shows beside it.
            ViewGroup sheet = (ViewGroup) frame;
            sheet.setClipToPadding(false);
            sheet.setClipChildren(false);
            // Live only for a sheet in its own window: a copy of a page holding the sheet would
            // blur the sheet into itself.
            boolean live = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && sLivePage != null && sLivePage.isInstance(activity);
            frost(sheet.getChildAt(0), activity.getWindow(), sheet,
                    rounded ? CORNER_DP * sheet.getResources().getDisplayMetrics().density : 0f, live);
        }
    }

    /** Paints {@code target}'s background with {@code page}, blurred, behind the sheet's tint. */
    public static void frost(@NonNull View target, @Nullable Window page) {
        frost(target, page, CORNER_DP * target.getResources().getDisplayMetrics().density);
    }

    /**
     * MEETUBE(glass): a sheet in its own activity over {@code page} (the picker screen) - live over
     * the player like the player's own sheets: a copy of another window never holds the sheet.
     */
    public static void frost(@NonNull View target, @Nullable Activity page) {
        boolean live = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && sLivePage != null && sLivePage.isInstance(page);
        frost(target, page != null ? page.getWindow() : null, target,
                CORNER_DP * target.getResources().getDisplayMetrics().density, live);
    }

    /** As above, rounded to {@code cornerPx} (a popup menu's corners, not a sheet's). */
    public static void frost(@NonNull View target, @Nullable Window page, float cornerPx) {
        frost(target, page, target, cornerPx, false, 0);
    }

    /** As above, under {@code tint} instead of the sheets' (the message pill's thinner glass). */
    public static void frost(@NonNull View target, @Nullable Window page, float cornerPx, int tint) {
        frost(target, page, target, cornerPx, false, tint);
    }

    /** As above, filling {@code cover} - {@code target} itself or its direct parent. */
    private static void frost(@NonNull View target, @Nullable Window page, @NonNull View cover, float cornerPx,
            boolean live) {
        frost(target, page, cover, cornerPx, live, 0);
    }

    /** {@code tint} 0: the sheets' own. */
    private static void frost(@NonNull View target, @Nullable Window page, @NonNull View cover, float cornerPx,
            boolean live, int tint) {
        if (cornerPx > 0) {
            // The message pill (its own tint) takes the hairline alone: the sheen is a sheet's 72dp
            // top light, and cut down to a 48dp capsule it left two painted-on arcs at the ends.
            rim(cover, cornerPx, tint == 0);
        }
        View decor = page != null ? page.peekDecorView() : null;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || decor == null) {
            return;
        }
        new Feed(target, page, decor, cover, cornerPx, live, tint).copy();
    }

    /**
     * The frosted edge, over everything the sheet holds: a hairline rim lit along the rounded corners
     * and a faint sheen fading down from the top. An overlay, not a foreground, so the frame's
     * padding for the system bars does not inset it from the glass.
     */
    private static void rim(@NonNull View cover, float cornerPx, boolean sheenToo) {
        if (cover.getTag(R.id.mobile_sheet_rim) != null) {
            return;
        }
        Context context = cover.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable sheen = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {ContextCompat.getColor(context, R.color.meetube_glass_sheen), 0});
        sheen.setCornerRadii(new float[] {cornerPx, cornerPx, cornerPx, cornerPx, 0, 0, 0, 0});
        android.graphics.drawable.GradientDrawable line = new android.graphics.drawable.GradientDrawable();
        line.setCornerRadius(cornerPx);
        // the message pill: the player pills' white hairline (GlassDrawable), dark in both themes
        int lineColor = ContextCompat.getColor(context, sheenToo ? R.color.meetube_glass_rim : R.color.meetube_toast_rim);
        line.setStroke(Math.max(1, Math.round(density)), lineColor);
        android.graphics.drawable.LayerDrawable rim = new android.graphics.drawable.LayerDrawable(
                sheenToo ? new Drawable[] {sheen, line} : new Drawable[] {line});
        if (sheenToo) {
            rim.setLayerHeight(0, Math.round(72 * density));
            rim.setLayerGravity(0, android.view.Gravity.TOP | android.view.Gravity.FILL_HORIZONTAL);
        }
        rim.setBounds(0, 0, cover.getWidth(), cover.getHeight());
        cover.getOverlay().add(rim);
        cover.setTag(R.id.mobile_sheet_rim, rim);
        cover.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                rim.setBounds(0, 0, right - left, bottom - top));
    }

    /**
     * Copies the page into a sheet's backdrop: once, blurred here, or for a live sheet again each
     * frame until the sheet goes, left for the GPU to blur. Three bitmaps take turns, so the one
     * written is never the one drawn or one a frame in flight reads.
     */
    private static final class Feed {
        private final View mTarget;
        private final Window mPage;
        private final View mDecor;
        private final View mCover;
        private final float mCorner;
        private final boolean mLive;
        private final int mTint;
        private final Handler mMain = new Handler(Looper.getMainLooper());
        private final Bitmap[] mRing = new Bitmap[3];
        private int mNext;
        @Nullable
        private Backdrop mBackdrop;

        Feed(View target, Window page, View decor, View cover, float corner, boolean live, int tint) {
            mTint = tint;
            mTarget = target;
            mPage = page;
            mDecor = decor;
            mCover = cover;
            mCorner = corner;
            mLive = live;
        }

        void copy() {
            int pageW = mDecor.getWidth();
            int pageH = mDecor.getHeight();
            if (pageW == 0 || pageH == 0) {
                return;
            }
            int w = Math.max(1, pageW / SCALE);
            int h = Math.max(1, pageH / SCALE);
            Bitmap copy = mRing[mNext];
            if (copy == null || copy.getWidth() != w || copy.getHeight() != h) {
                copy = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                mRing[mNext] = copy;
            }
            mNext = (mNext + 1) % mRing.length;
            int[] origin = new int[2];
            mDecor.getLocationOnScreen(origin);
            Bitmap page = copy;
            try {
                PixelCopy.request(mPage, page, result -> {
                    boolean copied = result == PixelCopy.SUCCESS;
                    if (copied && !mLive) {
                        // The message pill is frosted, not see-through: twice the blur, shapes gone.
                        blur(page, mTint != 0 ? BLUR_RADIUS * 2 : BLUR_RADIUS);
                    }
                    show(copied ? page : null, origin, pageW, pageH);
                }, mMain);
            } catch (IllegalArgumentException e) {
                // The page's window has no surface (already gone): keep what the sheet shows.
            }
        }

        private void show(@Nullable Bitmap page, int[] origin, int pageW, int pageH) {
            if (!mTarget.isAttachedToWindow()) {
                if (mBackdrop == null && page != null) {
                    // Not there yet, not gone: a message pill queued behind the one still showing
                    // (like, undo, like) attaches after its copy lands - frost it then, not never.
                    mTarget.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                        @Override
                        public void onViewAttachedToWindow(@NonNull View v) {
                            v.removeOnAttachStateChangeListener(this);
                            show(page, origin, pageW, pageH);
                        }

                        @Override
                        public void onViewDetachedFromWindow(@NonNull View v) {
                        }
                    });
                }
                return; // the sheet is gone, or not shown yet
            }
            if (mBackdrop != null && mTarget.getBackground() != mBackdrop) {
                return; // the sheet has another surface now
            }
            if (page != null) {
                if (mBackdrop == null) {
                    mBackdrop = new Backdrop(mTarget, mCover, mCorner, mLive, mTint);
                    mBackdrop.setPage(page, origin, pageW, pageH);
                    mTarget.setBackground(mBackdrop);
                } else {
                    mBackdrop.setPage(page, origin, pageW, pageH);
                }
            }
            if (mLive) {
                mMain.postDelayed(this::copy, LIVE_FRAME_MS);
            }
        }
    }

    @Nullable
    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** Three box passes each way approximate a gaussian; on a 1/8 copy that is a wide, soft blur. */
    static void blur(Bitmap bitmap, int radius) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] a = new int[w * h];
        int[] b = new int[w * h];
        bitmap.getPixels(a, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxPass(a, b, w, h, radius, true);
            boxPass(b, a, w, h, radius, false);
        }
        bitmap.setPixels(a, 0, w, 0, 0, w, h);
    }

    private static void boxPass(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w;
        int len = horizontal ? w : h;
        int step = horizontal ? 1 : w;
        for (int line = 0; line < lines; line++) {
            int start = horizontal ? line * w : line;
            for (int i = 0; i < len; i++) {
                int sa = 0;
                int sr = 0;
                int sg = 0;
                int sb = 0;
                int n = 0;
                for (int k = Math.max(0, i - r); k <= Math.min(len - 1, i + r); k++) {
                    int c = src[start + k * step];
                    sa += c >>> 24;
                    sr += (c >> 16) & 0xFF;
                    sg += (c >> 8) & 0xFF;
                    sb += c & 0xFF;
                    n++;
                }
                dst[start + i * step] = (sa / n) << 24 | (sr / n) << 16 | (sg / n) << 8 | (sb / n);
            }
        }
    }

    /** The blurred page under a sheet, screen-aligned, saturated a little like {@link GlassView}, then tinted. */
    private static final class Backdrop extends Drawable {
        private final View mTarget;
        private final View mCover;
        private int[] mOrigin;
        private float mScaleX;
        private float mScaleY;
        private final Paint mPage = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint mTint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private BitmapShader mShader;
        private final Matrix mMatrix = new Matrix();
        private final RectF mRect = new RectF();
        private final float mCorner;
        private final int[] mLoc = new int[2];
        private int mDrawnX = Integer.MIN_VALUE;
        private int mDrawnY = Integer.MIN_VALUE;
        /** A live sheet's page comes unblurred: drawn into this node, which blurs it on the GPU. */
        @Nullable
        private final android.graphics.RenderNode mBlurNode;
        private final android.graphics.Path mClip = new android.graphics.Path();

        Backdrop(View target, View cover, float corner, boolean live, int tint) {
            mTarget = target;
            mCover = cover;
            if (live && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mBlurNode = new android.graphics.RenderNode("MeeTube sheet glass");
                mBlurNode.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        LIVE_BLUR_PX / SCALE, LIVE_BLUR_PX / SCALE, Shader.TileMode.CLAMP));
            } else {
                mBlurNode = null;
            }
            ColorMatrix saturate = new ColorMatrix();
            saturate.setSaturation(1.4f);
            mPage.setColorFilter(new ColorMatrixColorFilter(saturate));
            mTint.setColor(tint != 0 ? tint : ContextCompat.getColor(target.getContext(), R.color.meetube_sheet_glass));
            mCorner = corner;
            // A drag moves the sheet without redrawing it: redraw so the page stays put underneath.
            target.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (target.getBackground() != Backdrop.this) {
                        target.getViewTreeObserver().removeOnPreDrawListener(this);
                        return true;
                    }
                    target.getLocationOnScreen(mLoc);
                    if (mLoc[0] != mDrawnX || mLoc[1] != mDrawnY) {
                        invalidateSelf();
                    }
                    return true;
                }
            });
        }

        /** The page to show (blurred already, unless live), and where on screen it was copied from. */
        void setPage(Bitmap page, int[] origin, int pageW, int pageH) {
            mOrigin = origin;
            mScaleX = pageW / (float) page.getWidth();
            mScaleY = pageH / (float) page.getHeight();
            mShader = new BitmapShader(page, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            mPage.setShader(mShader);
            invalidateSelf();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            mTarget.getLocationOnScreen(mLoc);
            mDrawnX = mLoc[0];
            mDrawnY = mLoc[1];
            mMatrix.setScale(mScaleX, mScaleY);
            mMatrix.postTranslate(mOrigin[0] - mLoc[0], mOrigin[1] - mLoc[1]);
            mShader.setLocalMatrix(mMatrix);
            if (mCover == mTarget) {
                mRect.set(getBounds());
            } else { // the parent's box, in the target's coordinates
                mRect.set(-mTarget.getLeft(), -mTarget.getTop(),
                        mCover.getWidth() - mTarget.getLeft(), mCover.getHeight() - mTarget.getTop());
            }
            if (mBlurNode != null && canvas.isHardwareAccelerated()) {
                // The node holds the sheet's box unrounded, so the blur's edges clamp to the page
                // instead of fading out; the rounding is a clip around the blurred result. It is
                // 1/SCALE the size, scaled back up: the GPU blurs a copy-sized layer, not the screen.
                mBlurNode.setPosition(0, 0, (int) Math.ceil(mRect.width() / SCALE),
                        (int) Math.ceil(mRect.height() / SCALE));
                mBlurNode.setPivotX(0f);
                mBlurNode.setPivotY(0f);
                mBlurNode.setScaleX(SCALE);
                mBlurNode.setScaleY(SCALE);
                Canvas recording = mBlurNode.beginRecording();
                recording.scale(1f / SCALE, 1f / SCALE);
                recording.translate(-mRect.left, -mRect.top);
                recording.drawRect(mRect, mPage);
                mBlurNode.endRecording();
                mClip.rewind();
                mClip.addRoundRect(mRect, mCorner, mCorner, android.graphics.Path.Direction.CW);
                int saved = canvas.save();
                canvas.clipPath(mClip);
                canvas.translate(mRect.left, mRect.top);
                canvas.drawRenderNode(mBlurNode);
                canvas.restoreToCount(saved);
            } else {
                canvas.drawRoundRect(mRect, mCorner, mCorner, mPage);
            }
            canvas.drawRoundRect(mRect, mCorner, mCorner, mTint);
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
    }
}
