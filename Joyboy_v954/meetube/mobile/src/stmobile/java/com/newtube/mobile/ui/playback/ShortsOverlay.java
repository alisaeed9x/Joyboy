package com.newtube.mobile.ui.playback;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.player.Media3PlayerController;
import com.newtube.mobile.player.ShortsPeekPlayer;
import com.newtube.mobile.ui.common.GlassDrawable;
import com.newtube.mobile.ui.common.ShortsSeen;

import java.util.List;
import java.util.Locale;

/**
 * MEETUBE(shorts): the TikTok/YouTube-style vertical player, layered on the ordinary one. A video
 * from the Shorts tab ({@link Video#belongsToShorts()}) switches the watch screen into shorts mode:
 * the video fills the screen (the host resizes it), this overlay shows the channel row, title and
 * actions like YouTube's Shorts, and a vertical drag pages to the next / previous short. Tap
 * pauses, double tap likes (with a heart), hold is the player's own 2x. Looping and preloading
 * the next one come from the shared playback controllers.
 *
 * <p>Paging follows the finger 1:1 with the neighbour's portrait thumbnail (oar2, preloaded into
 * Glide's memory cache a moment after each short opens) sliding in behind it, as YouTube's pager
 * does; the thumbnail then covers the screen until the new short is bound, and the host's loading
 * still (same image, same cache key - {@link #portrait}) holds it until the first frame. With no
 * neighbour queued yet the drag only rubber-bands.</p>
 */
final class ShortsOverlay {
    interface Host {
        void onShortsModeChanged(boolean on);

        void playNextShort();

        void playPreviousShort();

        /** Hold (or drop) the playing short's last frame over the video while the next one opens. */
        void freezeShortFrame(boolean freeze);

        void openShortsComments();

        void openShortsMore();

        void openShortsDescription();

        void seekShortTo(long positionMs);

        void exitShorts();

        /** Mutes the shorts (or unmutes them); true when they are now muted. */
        boolean toggleShortsMute();

        void openShortsSearch();
    }

    /** The watch page's views the overlay mirrors and forwards to. */
    static final class Watch {
        View like;
        View dislike;
        View share;
        View download;
        View subscribe;
        View channel;
        TextView likeCount;
        TextView commentsCount;
        TextView meta;
        TextView subscribeLabel;
        ImageView avatar;
    }

    private static final long OUT_MS = 220;
    private static final long IN_MS = 240;
    private static final float FLING_PX_PER_S = 1200f;
    /** No neighbour to page to: the drag gives a quarter of the finger's travel. */
    private static final float RUBBER_BAND = 0.25f;
    /**
     * MEETUBE(shorts): at once - a swipe inside the old 1.2 s found no neighbour warmed. The
     * thumbnails are small next to the short's first media chunks.
     */
    private static final long NEIGHBOUR_PRELOAD_DELAY_MS = 0;
    /** MEETUBE(shorts-rest): this long in Shorts without a break, and the next swipe gets a nudge. */
    private static final long REST_NUDGE_MS = 30 * 60 * 1000;
    /** The peek stays over a paged-to short until it is bound, or this long at most. */
    private static final long PEEK_COVER_MAX_MS = 2500;
    private static final int PORTRAIT_W = 720;
    private static final int PORTRAIT_H = 1280;
    /** Shorts left in the queue when the next page is asked for. */
    private static final int QUEUE_LOW = 8;

    private final View mOverlay;
    private final View mVideo;
    private final TextView mChannel;
    private final TextView mTitle;
    private final TextView mMeta;
    private final TextView mLikes;
    private final TextView mComments;
    private final TextView mSubscribe;
    private final TextView mSeekTime;
    private final ImageView mLikeIcon;
    private final ImageView mAvatar;
    private final ImageView mFlash;
    private final ImageView mHeart;
    private final ImageView mPeek;
    /** MEETUBE(shorts peek): the next short's real first frame, over its thumbnail (ShortsPeekPlayer). */
    private final TextureView mPeekFrame;
    private final ShortsPeekPlayer mPeekPlayer;
    /** The paged-to short whose first frame the peek still shows, until the player's own lands. */
    @Nullable
    private String mPeekCoverId;
    private final Runnable mUncover = this::uncover;
    /** Where the peek's frame waits while unused: far off screen, but laid out and drawn. */
    private static final float PARKED = 100_000f;
    private final SeekBar mProgress;
    private final Watch mWatch;
    private final Host mHost;
    private boolean mAvatarKnown;
    private Drawable mAvatarOrb;
    private boolean mInPip;
    private final Runnable mPreloadNeighbours = this::preloadNeighbours;
    private final Runnable mHidePeek = this::hidePeek;
    @Nullable
    private Video mCurrent;
    /** The queue's last short when the next page was asked for. */
    @Nullable
    private Video mPagedFrom;
    @Nullable
    private Video mPeekVideo;
    private int mPeekDir;
    private boolean mActive;
    /** MEETUBE(shorts-rest): when this run of Shorts began (or the last nudge). */
    private long mShortsSince;
    private boolean mCommentsOpen;
    private boolean mScrubbing;
    private long mDurationMs;
    /** A swipe committed: the old short slides away while the new one already opens under it. */
    private boolean mPaging;
    private boolean mPagingNext;
    private boolean mPagedOpen;
    private final Runnable mOpenPaged = this::openPaged;
    /** When in the slide the paged-to short opens (share of the slide's duration). */
    private static final float OPEN_AT_SLIDE = 0.4f;
    /** The new short's bind, held until the old one has slid away (its text slides with it). */
    @Nullable
    private Video mPendingBind;

    ShortsOverlay(@NonNull View root, @NonNull View video, @NonNull Host host, @NonNull Watch watch) {
        mOverlay = root.findViewById(R.id.mobile_shorts_overlay);
        mVideo = video;
        mHost = host;
        mWatch = watch;
        mChannel = root.findViewById(R.id.mobile_shorts_channel);
        mTitle = root.findViewById(R.id.mobile_shorts_title);
        mMeta = root.findViewById(R.id.mobile_shorts_meta);
        mLikes = root.findViewById(R.id.mobile_shorts_like_count);
        mComments = root.findViewById(R.id.mobile_shorts_comments_count);
        mSubscribe = root.findViewById(R.id.mobile_shorts_subscribe);
        mSeekTime = root.findViewById(R.id.mobile_shorts_seek_time);
        mLikeIcon = root.findViewById(R.id.mobile_shorts_like);
        mAvatar = root.findViewById(R.id.mobile_shorts_avatar);
        mFlash = root.findViewById(R.id.mobile_shorts_flash);
        mHeart = root.findViewById(R.id.mobile_shorts_heart);
        mPeek = root.findViewById(R.id.mobile_shorts_peek);
        mPeekFrame = root.findViewById(R.id.mobile_shorts_peek_video);
        mPeekFrame.setTranslationY(PARKED);
        mPeekPlayer = new ShortsPeekPlayer(root.getContext(), mPeekFrame, this::onPeekFrame);
        mProgress = root.findViewById(R.id.mobile_shorts_progress);
        ShortsSeen.load(root.getContext());

        forward(R.id.mobile_shorts_like, watch.like);
        forward(R.id.mobile_shorts_dislike, watch.dislike);
        forward(R.id.mobile_shorts_share, watch.share);
        forward(R.id.mobile_shorts_download, watch.download);
        forward(R.id.mobile_shorts_subscribe, watch.subscribe);
        // The avatar and the name open the channel (#12: with no click of their own the tap fell
        // through to the video, where a double tap likes).
        forward(R.id.mobile_shorts_avatar, watch.channel);
        forward(R.id.mobile_shorts_channel, watch.channel);
        mOverlay.findViewById(R.id.mobile_shorts_comments).setOnClickListener(v -> mHost.openShortsComments());
        mOverlay.findViewById(R.id.mobile_shorts_more).setOnClickListener(v -> mHost.openShortsMore());
        mOverlay.findViewById(R.id.mobile_shorts_back).setOnClickListener(v -> mHost.exitShorts());
        mOverlay.findViewById(R.id.mobile_shorts_search).setOnClickListener(v -> mHost.openShortsSearch());
        mOverlay.findViewById(R.id.mobile_shorts_mute).setOnClickListener(v -> showMuted(mHost.toggleShortsMute()));
        // MEETUBE(glass): as on the player, the buttons over the short (and the play / pause flash)
        // sit on see-through tinted discs (frosted ones of the short with Frosted player buttons on);
        // the actions keep their 48dp discs, the labels their shadows.
        com.newtube.mobile.ui.common.GlassSource glass = com.newtube.mobile.ui.common.Glass.isPlayerFrost(
                root.getContext()) ? root.findViewById(R.id.mobile_player_glass) : null;
        for (int id : new int[] {R.id.mobile_shorts_like, R.id.mobile_shorts_dislike, R.id.mobile_shorts_comments,
                R.id.mobile_shorts_share, R.id.mobile_shorts_more, R.id.mobile_shorts_flash}) {
            GlassDrawable.applyButton(mOverlay.findViewById(id), glass, 0, true);
        }
        for (int id : new int[] {R.id.mobile_shorts_back, R.id.mobile_shorts_download, R.id.mobile_shorts_mute,
                R.id.mobile_shorts_search}) {
            GlassDrawable.applyButton(mOverlay.findViewById(id), glass,
                    Math.round(4 * root.getResources().getDisplayMetrics().density), true); // the player's minimize disc
        }
        // MEETUBE(shorts header): no pill; the word itself is glass, frosted white that thins toward
        // its foot (a vertical gradient over one line), with a soft shadow to read over any short.
        android.widget.TextView header = mOverlay.findViewById(R.id.mobile_shorts_header);
        float line = header.getLineHeight();
        header.getPaint().setShader(new android.graphics.LinearGradient(0, 0, 0, line,
                new int[] {0xFFFFFFFF, 0xE6FFFFFF, 0x80FFFFFF}, new float[] {0f, 0.45f, 1f},
                android.graphics.Shader.TileMode.CLAMP));
        header.setShadowLayer(6 * root.getResources().getDisplayMetrics().density, 0, 1, 0x66000000);
        // MEETUBE(shorts description, issue #33): the title opens the Description panel, as YouTube's.
        mTitle.setOnClickListener(v -> mHost.openShortsDescription());

        round(mAvatar);
        mProgress.getThumb().mutate().setAlpha(0);
        mProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    mSeekTime.setText(time(progress * mDurationMs / 1000) + " / " + time(mDurationMs));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                mScrubbing = true;
                bar.getThumb().setAlpha(255);
                mSeekTime.setVisibility(View.VISIBLE);
                showChrome(false); // YouTube clears the text while you scrub
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                mScrubbing = false;
                bar.getThumb().setAlpha(0);
                mSeekTime.setVisibility(View.GONE);
                showChrome(true);
                if (mDurationMs > 0) {
                    mHost.seekShortTo(bar.getProgress() * mDurationMs / 1000);
                }
            }
        });

        CountUp likes = new CountUp(mLikes);
        CountUp comments = new CountUp(mComments);
        CountUp meta = new CountUp(mMeta);
        mirror(watch.likeCount, text -> likes.show(isCount(text) ? text
                : mLikes.getContext().getString(R.string.mobile_watch_like), currentId()));
        mirror(watch.commentsCount, text -> comments.show(isCount(text) ? text
                : mComments.getContext().getString(R.string.mobile_comments_title), currentId()));
        mirror(watch.meta, text -> meta.show(text, currentId()));
        mirror(watch.subscribeLabel, mSubscribe::setText);
    }

    /** The portrait still of a short - one request shape for the peek, its preload and the host's still. */
    static RequestBuilder<Drawable> portrait(@NonNull Context context, @NonNull String videoId) {
        // fitCenter, not centerCrop: oar2 is the short's own aspect, and the views showing it crop
        // it into the video's box as the player crops the video - cropped twice, it jumped when
        // the video replaced it.
        return Glide.with(context.getApplicationContext())
                .load("https://i.ytimg.com/vi/" + videoId + "/oar2.jpg")
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .format(DecodeFormat.PREFER_RGB_565)
                .override(PORTRAIT_W, PORTRAIT_H)
                .fitCenter();
    }

    /**
     * A short fills a phone's screen (zoomed, YouTube's way), cropping a little off a 9:16 short's
     * sides. A tablet's screen is shaped too unlike a short for that: there a short follows the
     * zoom setting, so Default shows all of it - zoomed, a 4:5 short lost a fifth of its width (#25).
     * So does a phone held sideways, where filling would leave a band of the short's middle (#57).
     */
    static boolean fillsScreen(@NonNull Context context) {
        return context.getApplicationContext().getResources().getConfiguration().smallestScreenWidthDp < 600
                && context.getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE;
    }

    /**
     * The peek takes the video's own box (between the system bars), so the still a swipe lands on
     * is framed exactly like the video that replaces it (both crop to fill where shorts play
     * zoomed, {@link #fillsScreen}).
     */
    void alignPeek(int top, int bottom) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mPeek.getLayoutParams();
        if (lp.topMargin != top || lp.bottomMargin != bottom) {
            lp.topMargin = top;
            lp.bottomMargin = bottom;
            mPeek.setLayoutParams(lp);
            FrameLayout.LayoutParams frame = (FrameLayout.LayoutParams) mPeekFrame.getLayoutParams();
            frame.topMargin = top;
            frame.bottomMargin = bottom;
            mPeekFrame.setLayoutParams(frame);
        }
    }

    /** MEETUBE(shorts peek): decode the next short's first frame into the peek, ahead of the swipe. */
    void preparePeek(@NonNull MediaItemFormatInfo formatInfo, @NonNull Media3PlayerController controller) {
        if (mActive) {
            uncover();
            mPeekPlayer.prepare(formatInfo, controller);
        }
    }

    /**
     * The player's own first frame of {@code videoId} is on screen: the peek's stand-in goes.
     * True when it stood in (the caller drops its thumbnail at once, not in a fade over the video).
     */
    boolean onPictureVisible(@Nullable String videoId) {
        if (mPeekCoverId != null && mPeekCoverId.equals(videoId)) {
            uncover();
            return true;
        }
        return false;
    }

    /** Frees the peek's decoder (the app left, or Shorts did). */
    void releasePeek() {
        uncover();
        mPeekPlayer.release();
    }

    boolean isActive() {
        return mActive;
    }

    /** MEETUBE(tv): one of the short's buttons has the remote's focus (Up and Down then move between them). */
    boolean holdsFocus() {
        View focus = mOverlay.findFocus();
        return focus != null && focus != mOverlay;
    }

    /** Shorts mode with the video filling the screen (not shrunk above the comments). */
    boolean isFullscreen() {
        return mActive && !mCommentsOpen;
    }

    boolean isCommentsOpen() {
        return mActive && mCommentsOpen;
    }

    /** The comments sheet takes the lower part of the screen; the short shrinks above it. */
    void setCommentsOpen(boolean open) {
        mCommentsOpen = open;
        mOverlay.setVisibility(mActive && !open && !mInPip ? View.VISIBLE : View.GONE);
    }

    /** MEETUBE(shorts-pip): the PiP window shows only the short; the buttons come back on expand. */
    void setInPip(boolean pip) {
        mInPip = pip;
        uncover();
        hidePeek();
        mOverlay.setVisibility(mActive && !mCommentsOpen && !pip ? View.VISIBLE : View.GONE);
    }

    /** Every setVideo: shorts mode follows the video that is playing now. */
    void bind(@Nullable Video item) {
        boolean on = item != null && item.belongsToShorts();
        if (on && mPaging && !item.equals(mCurrent)) {
            mPendingBind = item;
            return;
        }
        if (!on) {
            // Left Shorts mid-slide: nothing to open or bind.
            mPagedOpen = true;
            mPendingBind = null;
            endPaging();
        }
        if (on) {
            boolean fresh = mCurrent == null || !mCurrent.equals(item);
            mCurrent = item;
            ShortsSeen.add(item.videoId);
            mChannel.setText(item.getAuthor());
            mTitle.setText(item.getTitle());
            if (fresh) {
                mTitle.setMaxLines(2);
                com.bumptech.glide.Glide.with(mAvatar).clear(mAvatar);
                mAvatarOrb = com.newtube.mobile.ui.common.ChannelAvatars.placeholder(item.getAuthor());
                mAvatar.setImageDrawable(mAvatarOrb); // the Home cards' breathing orb until it lands
                mAvatarKnown = false;
                showKnownAvatar(item);
                maybeNudgeRest();
                mOverlay.removeCallbacks(mPreloadNeighbours);
                mOverlay.postDelayed(mPreloadNeighbours, NEIGHBOUR_PRELOAD_DELAY_MS);
                pageQueueIfLow();
                if (mPeek.getVisibility() == View.VISIBLE && mPeekDir == 0) {
                    mOverlay.removeCallbacks(mHidePeek);
                    mOverlay.postDelayed(mHidePeek, 60); // the host's still has drawn by then
                }
            }
            // The watch page loads the channel avatar on its own schedule: copy it as it lands
            // (not at once for a new short - the watch page still shows the last one's then).
            if (!fresh) {
                copyAvatar();
            }
            mAvatar.postDelayed(this::copyAvatar, 400);
            mAvatar.postDelayed(this::copyAvatar, 1200);
            mAvatar.postDelayed(this::copyAvatar, 2500);
        }
        if (on != mActive) {
            mActive = on;
            mShortsSince = android.os.SystemClock.elapsedRealtime();
            mCommentsOpen = false;
            mOverlay.setVisibility(on && !mInPip ? View.VISIBLE : View.GONE);
            if (!on) {
                mCurrent = null;
                releasePeek();
                hidePeek();
                mVideo.setTranslationY(0f);
                mVideo.setAlpha(1f);
            }
            mHost.onShortsModeChanged(on);
        }
    }

    // ponytail: counts from entering Shorts, app in the background included; track resumed time if that nags.
    private void maybeNudgeRest() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!mActive || now - mShortsSince < REST_NUDGE_MS) {
            return;
        }
        mShortsSince = now; // once per half hour
        com.newtube.mobile.ui.common.Haptics.watch(new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                mOverlay.getContext(), R.style.MobileAlertDialog)
                .setTitle(R.string.mobile_shorts_rest_title)
                .setMessage(R.string.mobile_shorts_rest_message)
                .setPositiveButton(R.string.mobile_shorts_rest_ok, null)
                .show());
    }

    void setLiked(boolean liked) {
        mLikeIcon.setImageResource(liked ? R.drawable.ic_watch_thumb_up : R.drawable.ic_watch_thumb_up_outline);
    }

    void setSubscribed(boolean subscribed) {
        mSubscribe.setSelected(subscribed);
    }

    /** MEETUBE(burst): where a like or a subscribe made in Shorts splashes. */
    View likeIcon() {
        return mLikeIcon;
    }

    View subscribeButton() {
        return mSubscribe;
    }

    void setProgress(long positionMs, long durationMs) {
        if (mActive && !mScrubbing && !mPaging) {
            mDurationMs = durationMs;
            mProgress.setProgress(durationMs > 0 ? (int) (positionMs * 1000 / durationMs) : 0);
        }
    }

    /** The tap's answer: a play or pause glyph that pops up in the middle and fades. */
    void flashPlayState(boolean playing) {
        mFlash.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play); // paused shows play
        mFlash.animate().cancel();
        mFlash.setAlpha(1f);
        // MEETUBE(issue #216): a quick pop that keeps up with the finger (was 150 + 450 held + 200 ms).
        mFlash.setScaleX(0.85f);
        mFlash.setScaleY(0.85f);
        mFlash.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(90).withEndAction(() ->
                mFlash.animate().alpha(0f).setStartDelay(220).setDuration(140).start()).start();
    }

    /** Double tap: a big heart springs in and floats off. */
    void popHeart() {
        mHeart.animate().cancel();
        mHeart.setAlpha(1f);
        mHeart.setScaleX(0.4f);
        mHeart.setScaleY(0.4f);
        mHeart.setRotation((float) (Math.random() * 24 - 12));
        mHeart.animate().scaleX(1f).scaleY(1f).setInterpolator(new OvershootInterpolator(2.5f)).setDuration(260)
                .withEndAction(() -> mHeart.animate().scaleX(1.4f).scaleY(1.4f).alpha(0f).setStartDelay(250)
                        .setInterpolator(new DecelerateInterpolator()).setDuration(400).start()).start();
    }

    /** The short follows the finger; the neighbour's thumbnail comes in behind it. */
    void drag(float dy) {
        int dir = dy < 0 ? 1 : dy > 0 ? -1 : 0; // finger up: the next short comes up from below
        if (mPaging) {
            mVideo.animate().cancel(); // a new swipe inside the slide: land the last one first
            endPaging();
        }
        if (dir != mPeekDir) {
            showPeek(dir);
        }
        mVideo.animate().cancel();
        mVideo.setTranslationY(mPeekVideo != null ? dy : dy * RUBBER_BAND);
    }

    void release(float dy, float yVelocity) {
        float height = Math.max(1, mVideo.getHeight());
        boolean far = Math.abs(dy) > height * 0.18f;
        boolean fling = Math.abs(yVelocity) > FLING_PX_PER_S && Math.signum(yVelocity) == Math.signum(dy);
        if (dy == 0 || mPeekVideo == null || !(far || fling)) {
            mVideo.animate().translationY(0f).setDuration(IN_MS).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(this::hidePeek).start();
            return;
        }
        boolean next = dy < 0;
        float left = 1f - Math.min(1f, Math.abs(mVideo.getTranslationY()) / height);
        long duration = Math.max(90, (long) (OUT_MS * left));
        // The next short opens while the old one is still sliding away (it used to start only once
        // the slide was over, so every swipe waited the slide plus the open). Not at the very lift:
        // the open's main-thread work would stutter the slide where it moves fastest. At 40% of the
        // slide (~65% of its travel) the rest is the slow tail, where a dropped frame does not show.
        mPaging = true;
        mPagingNext = next;
        mPagedOpen = false;
        mOverlay.postDelayed(mOpenPaged, (long) (duration * OPEN_AT_SLIDE));
        mVideo.animate().translationY(next ? -height : height).setDuration(duration)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(this::endPaging).start();
    }

    /**
     * MEETUBE(issue #134): the short ended with Loop shorts off - the next one slides up as if
     * swiped (it opens from the slide). False with no next short queued: the caller opens it.
     */
    boolean pageNext() {
        if (mPaging) {
            return true;
        }
        showPeek(1);
        if (mPeekVideo == null) {
            return false;
        }
        release(-Math.max(1, mVideo.getHeight()), 0);
        return true;
    }

    /** The paged-to short opens, under the paged-away one's frozen last frame. */
    private void openPaged() {
        if (!mPaging || mPagedOpen) {
            return;
        }
        mPagedOpen = true;
        mHost.freezeShortFrame(true);
        if (mPagingNext) {
            mHost.playNextShort();
        } else {
            mHost.playPreviousShort();
        }
    }

    /** The slide is over (or cut short): the new short takes the screen. */
    private void endPaging() {
        if (!mPaging) {
            return;
        }
        mOverlay.removeCallbacks(mOpenPaged);
        openPaged(); // a slide cut short before its open: open now
        mPaging = false;
        mProgress.setProgress(0);
        // The thumbnail now fills the screen: it stays there, over the new short's opening video,
        // until the new one is bound (bind hides it) - by now usually already playing under it.
        mPeekDir = 0;
        mPeek.setTranslationY(0f);
        // MEETUBE(shorts peek): a real frame that came in with the swipe stays over the paged-to
        // short until the player's own first frame of it (onPictureVisible). A frame still standing
        // in for the LAST paged-to short (a swipe inside its open) is not this one: park it.
        boolean frame = mPeekCoverId == null && mPeekVideo != null && mPeekFrame.getTranslationY() != PARKED;
        mOverlay.removeCallbacks(mUncover);
        mPeekCoverId = frame ? mPeekVideo.videoId : null;
        mPeekFrame.setTranslationY(frame ? 0f : PARKED);
        if (frame) {
            mOverlay.postDelayed(mUncover, PEEK_COVER_MAX_MS);
        }
        mVideo.setTranslationY(0f);
        mOverlay.removeCallbacks(mHidePeek);
        mOverlay.postDelayed(mHidePeek, PEEK_COVER_MAX_MS);
        mHost.freezeShortFrame(false);
        Video pending = mPendingBind;
        mPendingBind = null;
        if (pending != null) {
            bind(pending);
            // Its still (or already its video) has been under the frozen frame since the open.
            hidePeek();
        }
    }

    /** MEETUBE(shorts): a held short shows only itself - every button and line fades until the finger lifts. */
    void setClean(boolean clean) {
        mOverlay.animate().cancel();
        mOverlay.animate().alpha(clean ? 0f : 1f).setDuration(150).start();
    }

    private void showChrome(boolean show) {
        for (int id : new int[] {R.id.mobile_shorts_info, R.id.mobile_shorts_actions}) {
            mOverlay.findViewById(id).animate().alpha(show ? 1f : 0f).setDuration(150).start();
        }
    }

    private void showPeek(int dir) {
        mPeekDir = dir;
        mPeekVideo = dir != 0 ? neighbour(dir) : null;
        if (mPeekVideo == null || mPeekVideo.videoId == null) {
            mPeekVideo = null;
            mPeek.setVisibility(View.GONE);
            syncPeekFrame();
            return;
        }
        mOverlay.removeCallbacks(mHidePeek);
        // Whole where the video is whole (#25), and the phone can turn under a short (#57).
        boolean fill = fillsScreen(mPeek.getContext());
        mPeek.setScaleType(fill ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        mPeekPlayer.setCrop(fill);
        float height = mVideo.getHeight();
        mPeek.setTranslationY(dir > 0 ? height : -height);
        mPeek.setVisibility(View.VISIBLE);
        portrait(mPeek.getContext(), mPeekVideo.videoId).into(mPeek);
        syncPeekFrame();
    }

    /** The neighbour's real frame rides in over its thumbnail when the peek player holds it. */
    private void syncPeekFrame() {
        if (mPeekCoverId != null) {
            return; // still standing in for the short on screen (the same picture as under it)
        }
        boolean show = mPeekVideo != null && mPeekPlayer.hasFrame(mPeekVideo.videoId);
        mPeekFrame.setTranslationY(show ? mPeek.getTranslationY() : PARKED);
    }

    /** A frame landed mid-swipe: it takes over from the thumbnail at once. */
    private void onPeekFrame(@NonNull String videoId) {
        if (mPeekDir != 0 && mPeekVideo != null && videoId.equals(mPeekVideo.videoId)) {
            syncPeekFrame();
        }
    }

    private void uncover() {
        mOverlay.removeCallbacks(mUncover);
        if (mPeekCoverId != null) {
            mPeekCoverId = null;
            syncPeekFrame();
        }
    }

    private void hidePeek() {
        mOverlay.removeCallbacks(mHidePeek);
        mPeekDir = 0;
        mPeekVideo = null;
        mPeek.setVisibility(View.GONE);
        syncPeekFrame(); // parks the frame, unless it stands in for the paged-to short
    }

    /** The short {@code step} places from this one in the queue the Shorts tab built, or null. */
    @Nullable
    private Video neighbour(int step) {
        List<Video> queue = Playlist.instance().getAll();
        int at = mCurrent != null ? queue.indexOf(mCurrent) : -1;
        int to = at + step;
        return at >= 0 && to >= 0 && to < queue.size() ? queue.get(to) : null;
    }

    /**
     * MEETUBE(shorts feed): this close to the queue's end, the Shorts tab fetches its next page, which
     * joins the queue (MobileBrowseActivity.extendShortsQueue) well before the swipe gets there.
     * Before, the queue only grew while the hidden grid behind the player was short of cards.
     */
    private void pageQueueIfLow() {
        List<Video> queue = Playlist.instance().getAll();
        Video last = queue.isEmpty() ? null : queue.get(queue.size() - 1);
        if (last != null && last != mPagedFrom && last.belongsToShorts() && neighbour(QUEUE_LOW) == null) {
            mPagedFrom = last; // one request per page
            BrowsePresenter.instance(mOverlay.getContext().getApplicationContext()).onScrollEnd(last);
        }
    }

    /** Warm Glide's memory cache with the shorts a swipe can reach, so the peek is there at once. */
    private void preloadNeighbours() {
        if (!mActive) {
            return;
        }
        for (int step : new int[] {1, -1, 2, 3}) {
            Video video = neighbour(step);
            if (video != null && video.videoId != null) {
                portrait(mOverlay.getContext(), video.videoId).preload();
            }
        }
    }

    /** MEETUBE(card avatars): the channel's avatar at once, from ChannelAvatars, not the watch page. */
    private void showKnownAvatar(Video item) {
        android.content.Context context = mAvatar.getContext();
        String url = com.newtube.mobile.ui.common.ChannelAvatars.get(context, item.channelId, item.getAuthor());
        if (url != null) {
            loadAvatar(url);
        } else {
            com.newtube.mobile.ui.common.ChannelAvatars.fetch(context, item.channelId, item.getAuthor(),
                    () -> item.equals(mCurrent), found -> {
                // MEETUBE(issue #172): the lookup can return after the player is destroyed.
                if (item.equals(mCurrent) && mAvatar.isAttachedToWindow()) {
                    loadAvatar(found);
                }
            }, true);
        }
    }

    private void loadAvatar(String url) {
        mAvatarKnown = true;
        com.bumptech.glide.Glide.with(mAvatar).load(url).circleCrop().placeholder(mAvatarOrb)
                .error(mAvatarOrb).transition(com.newtube.mobile.ui.common.ChannelAvatars.FADE).into(mAvatar);
    }

    private void copyAvatar() {
        if (mAvatarKnown) {
            return; // already the channel's own picture
        }
        Drawable avatar = mWatch.avatar != null ? mWatch.avatar.getDrawable() : null;
        if (avatar != null && avatar.getConstantState() != null) {
            mAvatar.setImageDrawable(avatar.getConstantState().newDrawable().mutate());
        }
    }

    private static void round(View view) {
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
    }

    private static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** A count has digits; the placeholder and "N/A" (counts hidden by the channel) don't. */
    private static boolean isCount(@Nullable CharSequence text) {
        return text != null && text.toString().matches(".*\\d.*");
    }

    private interface OnText {
        void set(CharSequence text);
    }

    @Nullable
    private String currentId() {
        return mCurrent != null ? mCurrent.videoId : null;
    }

    /**
     * MEETUBE(shorts counts): a short's count runs up from 0 to itself (fast, easing out) the first
     * time it shows for that short, "2.7K" as 0.0K..2.7K; later changes (a like) just change.
     */
    private static final class CountUp {
        private static final long DURATION_MS = 600;
        private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("\\d+(?:[.,]\\d+)*");

        private final TextView mView;
        @Nullable
        private String mShownFor;
        @Nullable
        private ValueAnimator mRun;
        @Nullable
        private CharSequence mTarget;

        CountUp(TextView view) {
            mView = view;
        }

        void show(@Nullable CharSequence text, @Nullable String videoId) {
            // the player re-sets the like count with the same text (bind, metadata, rating): keep counting
            if (mRun != null && mRun.isRunning() && TextUtils.equals(text, mTarget)) {
                return;
            }
            mTarget = text;
            if (mRun != null) {
                mRun.cancel();
                mRun = null;
            }
            java.util.regex.Matcher m = text != null ? NUMBER.matcher(text) : null;
            if (m == null || !m.find() || videoId == null || videoId.equals(mShownFor)) {
                mView.setText(text);
                return;
            }
            mShownFor = videoId;
            String num = m.group();
            // the last separator is a decimal mark unless three digits follow it ("1,234" vs "2,7")
            int sep = Math.max(num.lastIndexOf('.'), num.lastIndexOf(','));
            int decimals = sep >= 0 && num.length() - sep - 1 != 3 ? num.length() - sep - 1 : 0;
            char point = decimals > 0 ? num.charAt(sep) : '.';
            char group = decimals == 0 && sep >= 0 ? num.charAt(sep) : point == '.' ? ',' : '.';
            double target = Double.parseDouble(num.replace(String.valueOf(group), "").replace(point, '.'));
            String format = (num.indexOf(group) >= 0 ? "%,." : "%.") + decimals + "f";
            CharSequence before = text.subSequence(0, m.start());
            CharSequence after = text.subSequence(m.end(), text.length());
            mRun = ValueAnimator.ofFloat(0f, 1f).setDuration(DURATION_MS);
            mRun.setInterpolator(new DecelerateInterpolator(2f));
            mRun.addUpdateListener(a -> {
                if (a.getAnimatedFraction() >= 1f) {
                    mView.setText(text); // exactly the source's text at the end
                    return;
                }
                char[] digits = String.format(Locale.US, format, target * a.getAnimatedFraction()).toCharArray();
                for (int i = 0; i < digits.length; i++) {
                    digits[i] = digits[i] == ',' ? group : digits[i] == '.' ? point : digits[i];
                }
                mView.setText(before + new String(digits) + after);
            });
            mRun.start();
        }
    }

    private static void mirror(@Nullable TextView source, OnText target) {
        if (source == null) {
            return;
        }
        target.set(source.getText());
        source.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                target.set(s);
            }
        });
    }

    /** The mute button's look: crossed out while the shorts are muted. */
    void showMuted(boolean muted) {
        ImageView mute = mOverlay.findViewById(R.id.mobile_shorts_mute);
        mute.setImageResource(muted ? R.drawable.ic_player_volume_off : R.drawable.ic_player_volume_up);
        mute.setContentDescription(mute.getContext().getString(
                muted ? R.string.mobile_shorts_unmute : R.string.mobile_shorts_mute));
    }

    private void forward(int id, @Nullable View target) {
        View button = mOverlay.findViewById(id);
        if (target == null) {
            button.setVisibility(View.GONE);
        } else {
            button.setOnClickListener(v -> target.performClick());
        }
    }
}
