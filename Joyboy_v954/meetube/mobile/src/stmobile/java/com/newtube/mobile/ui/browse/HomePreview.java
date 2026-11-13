package com.newtube.mobile.ui.browse;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.player.HomePreviewPlayer;

import java.util.function.BooleanSupplier;

/**
 * MEETUBE(home autoplay): YouTube's Home preview. When the feed settles, the card nearest the
 * middle of the screen plays muted right in its thumbnail, with a mute and a captions button on
 * it and a thin progress line; scroll it away and it stops, the next centred card takes over.
 * Tapping the card still opens the video (the overlay only swallows its two buttons).
 * Settings > Customization > Home autoplay picks Off / Wi-Fi only / Always.
 */
public final class HomePreview implements HomePreviewPlayer.Callback {
    public static final int OFF = 0;
    public static final int WIFI = 1;
    public static final int ALWAYS = 2;

    private static final String PREFS = "meetube_app";
    private static final long SETTLE_MS = 400;
    private static final float MIN_VISIBLE = 0.65f;

    public static int mode(Context context) {
        return prefs(context).getInt("home_autoplay", WIFI);
    }

    public static void setMode(Context context, int mode) {
        prefs(context).edit().putInt("home_autoplay", mode).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private final Context mContext;
    private final RecyclerView mGrid;
    private final BooleanSupplier mAllowed;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final float mDp;
    private final FrameLayout mOverlay;
    private final TextureView mTexture;
    private final TextView mCues;
    private final View mProgress;
    private final ImageView mMute;
    private final ImageView mCc;
    private HomePreviewPlayer mPlayer;
    private final Rect mRect = new Rect();
    @Nullable private VideoCardAdapter.VideoViewHolder mHolder;
    private boolean mMuted;
    @Nullable private android.graphics.drawable.Drawable mBadgeBg;
    @Nullable private CharSequence mBadgeText; // the badge as bound, put back when the preview ends
    private boolean mCaptions;

    private final Runnable mPick = this::pick;
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (mHolder != null) {
                View frame = mHolder.thumbnailFrame();
                mProgress.getLayoutParams().width = Math.round(frame.getWidth() * mPlayer.progress());
                mProgress.requestLayout();
                long left = mPlayer.remainingMs();
                TextView badge = mHolder.badge();
                if (left >= 0 && badge.getVisibility() == View.VISIBLE) { // the duration counts down, as on YouTube
                    long s = (left + 999) / 1000;
                    badge.setText(s >= 3600 ? String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                            : String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60));
                }
                mMain.postDelayed(this, 250);
            }
        }
    };

    HomePreview(@NonNull RecyclerView grid, @NonNull BooleanSupplier allowed) {
        mContext = grid.getContext();
        mGrid = grid;
        mAllowed = allowed;
        mDp = mContext.getResources().getDisplayMetrics().density;
        mMuted = prefs(mContext).getBoolean("home_preview_muted", true);
        mCaptions = prefs(mContext).getBoolean("home_preview_cc", false);

        mOverlay = new FrameLayout(mContext);
        mTexture = new TextureView(mContext);
        mTexture.setAlpha(0f);
        mOverlay.addView(mTexture, new FrameLayout.LayoutParams(-1, -1));
        mCues = new TextView(mContext);
        mCues.setTextColor(Color.WHITE);
        mCues.setTextSize(13);
        mCues.setGravity(Gravity.CENTER);
        mCues.setBackgroundColor(0x99000000);
        mCues.setPadding(dp(6), dp(2), dp(6), dp(2));
        mCues.setVisibility(View.GONE);
        FrameLayout.LayoutParams cues = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cues.bottomMargin = dp(10);
        cues.leftMargin = cues.rightMargin = dp(40);
        mOverlay.addView(mCues, cues);
        mProgress = new View(mContext);
        mProgress.setBackgroundColor(0xFFFF0033);
        mProgress.setVisibility(View.GONE);
        mOverlay.addView(mProgress, new FrameLayout.LayoutParams(0, dp(3), Gravity.BOTTOM | Gravity.START));
        mMute = button(0);
        mMute.setOnClickListener(v -> {
            mMuted = !mMuted;
            prefs(mContext).edit().putBoolean("home_preview_muted", mMuted).apply();
            mPlayer.setMuted(mMuted);
            syncButtons();
        });
        mCc = button(1);
        mCc.setOnClickListener(v -> {
            mCaptions = !mCaptions;
            prefs(mContext).edit().putBoolean("home_preview_cc", mCaptions).apply();
            mPlayer.setCaptions(mCaptions);
            syncButtons();
        });

        mPlayer = new HomePreviewPlayer(mContext, mTexture, this);
        mPlayer.setMuted(mMuted);
        mPlayer.setCaptions(mCaptions);

        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) {
                    schedule();
                } else {
                    mMain.removeCallbacks(mPick);
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (mHolder != null && !(visibleFraction(mHolder) >= MIN_VISIBLE / 2)) {
                    stop(); // scrolled mostly out of sight
                }
            }
        });
    }

    /** Looks again for the card to play, once the screen has been still for a moment. */
    void schedule() {
        mMain.removeCallbacks(mPick);
        mMain.postDelayed(mPick, SETTLE_MS);
    }

    /** The feed changed under us: drop a preview whose card now shows another video, else look again. */
    void onDataChanged() {
        if (mHolder != null && (mHolder.video() == null || !mHolder.video().videoId.equals(mPlayer.videoId()))) {
            stop();
        }
        schedule();
    }

    void stop() {
        mMain.removeCallbacks(mPick);
        mMain.removeCallbacks(mTick);
        mPlayer.stop();
        detach();
    }

    void release() {
        stop();
        mPlayer.release();
    }

    private void pick() {
        if (!enabled()) {
            stop();
            return;
        }
        VideoCardAdapter.VideoViewHolder best = null;
        float bestDistance = Float.MAX_VALUE;
        mGrid.getGlobalVisibleRect(mRect);
        float center = mRect.exactCenterY();
        boolean atTop = !mGrid.canScrollVertically(-1);
        for (int i = 0; i < mGrid.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = mGrid.getChildViewHolder(mGrid.getChildAt(i));
            if (!(holder instanceof VideoCardAdapter.VideoViewHolder)) {
                continue;
            }
            VideoCardAdapter.VideoViewHolder card = (VideoCardAdapter.VideoViewHolder) holder;
            Video video = card.video();
            if (video == null || video.videoId == null || video.isLive || video.isUpcoming || video.isShorts
                    || video.hasPlaylist() || visibleFraction(card) < MIN_VISIBLE) {
                continue;
            }
            card.thumbnailFrame().getGlobalVisibleRect(mRect);
            // At the top of the feed the first card plays, not the one nearest the middle.
            float distance = atTop ? mRect.top : Math.abs(mRect.exactCenterY() - center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = card;
            }
        }
        if (best == null) {
            stop();
        } else if (best != mHolder || !best.video().videoId.equals(mPlayer.videoId())) {
            start(best);
        }
    }

    private boolean enabled() {
        int mode = mode(mContext);
        if (mode == OFF || !mAllowed.getAsBoolean()) {
            return false;
        }
        ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        return mode == ALWAYS || cm == null || !cm.isActiveNetworkMetered();
    }

    private void start(VideoCardAdapter.VideoViewHolder card) {
        stop();
        mHolder = card;
        mBadgeText = card.badge().getText();
        mBadgeBg = card.badge().getBackground();
        GradientDrawable grey = new GradientDrawable(); // not the frosted thumbnail: the video's colours would show through
        grey.setCornerRadius(dp(6));
        grey.setColor(0x99383838);
        card.badge().setBackground(grey);
        mTexture.setAlpha(0f);
        mProgress.setVisibility(View.GONE);
        mMute.setVisibility(View.GONE);
        mCc.setVisibility(View.GONE);
        ViewGroup frame = (ViewGroup) card.thumbnailFrame();
        frame.addView(mOverlay, 1, new FrameLayout.LayoutParams(-1, -1)); // under the duration badge
        mPlayer.play(card.video().videoId);
    }

    private void detach() {
        if (mHolder != null) {
            if (mBadgeText != null) {
                mHolder.badge().setText(mBadgeText);
                mHolder.badge().setBackground(mBadgeBg);
                mBadgeText = null;
            }
            ViewGroup parent = (ViewGroup) mOverlay.getParent();
            if (parent != null) {
                parent.removeView(mOverlay);
            }
            mHolder = null;
        }
    }

    private float visibleFraction(VideoCardAdapter.VideoViewHolder card) {
        View frame = card.thumbnailFrame();
        if (frame.getHeight() == 0 || !frame.getGlobalVisibleRect(mRect)) {
            return 0f;
        }
        return (float) mRect.height() / frame.getHeight();
    }

    @Override
    public void onFrame() {
        mTexture.animate().alpha(1f).setDuration(200).start();
        mProgress.setVisibility(View.VISIBLE);
        mMute.setVisibility(View.VISIBLE);
        syncButtons();
        mMain.removeCallbacks(mTick);
        mMain.post(mTick);
    }

    @Override
    public void onCues(@NonNull CharSequence text) {
        mCues.setText(text);
        mCues.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onCaptionsAvailable(boolean available) {
        mCc.setVisibility(available ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onEnded() {
        stop();
    }

    private void syncButtons() {
        mMute.setImageResource(mMuted ? R.drawable.ic_player_volume_off : R.drawable.ic_player_volume_up);
        mCc.setImageResource(mCaptions ? R.drawable.ic_player_cc : R.drawable.ic_player_cc_off);
    }

    /** A round dark button in the card's top right corner; {@code row} 0 is the top one. */
    private ImageView button(int row) {
        ImageView view = new ImageView(mContext);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x66000000);
        view.setBackground(bg);
        view.setPadding(dp(7), dp(7), dp(7), dp(7));
        view.setColorFilter(Color.WHITE);
        view.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP | Gravity.END);
        lp.topMargin = dp(8) + row * dp(42);
        lp.rightMargin = dp(8);
        mOverlay.addView(view, lp);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * mDp);
    }
}
