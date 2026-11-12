package com.newtube.mobile.player;

import android.content.Context;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.youtubeapi.service.YouTubeMediaItemService;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;

/**
 * MEETUBE(home autoplay): the muted preview that plays on the centred Home card, as YouTube's does.
 * One small ExoPlayer on a TextureView: it resolves the video's /player answer, builds the same DASH
 * source the main player would (same transports and media cache), and plays it capped to 480p.
 * Muted by default; captions off by default, drawn by the caller from {@link Callback#onCues}.
 *
 * <p>ponytail: a failure (a 403, SABR-only, a live stream) just leaves the thumbnail; nothing
 * retries the same link.
 */
public final class HomePreviewPlayer {
    public interface Callback {
        /** The first frame is on the texture: fade the video in. */
        void onFrame();

        /** The caption line to show now, or empty. */
        void onCues(@NonNull CharSequence text);

        /** The video has captions to switch on. */
        void onCaptionsAvailable(boolean available);

        void onEnded();
    }

    private static final ExecutorService BUILD = Executors.newSingleThreadExecutor();
    private static final int MAX_BITRATE = 2_500_000;

    private final Context mContext;
    private final TextureView mView;
    private final Callback mCallback;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    @Nullable private Media3SourceFactory mFactory;
    @Nullable private ExoPlayer mPlayer;
    @Nullable private DefaultTrackSelector mSelector;
    @Nullable private Disposable mFetch;
    @Nullable private String mVideoId;
    private int mGeneration;
    private boolean mMuted = true;
    private boolean mCaptions;
    private int mVideoW;
    private int mVideoH;

    public HomePreviewPlayer(@NonNull Context context, @NonNull TextureView view, @NonNull Callback callback) {
        mContext = context.getApplicationContext();
        mView = view;
        mCallback = callback;
        mView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyTransform());
    }

    @Nullable
    public String videoId() {
        return mVideoId;
    }

    public void play(@NonNull String videoId) {
        stop();
        mVideoId = videoId;
        final int generation = mGeneration;
        mFetch = YouTubeMediaItemService.instance().getFormatInfoObserve(videoId)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(info -> build(info, generation), error -> fail(generation, error));
    }

    private void build(@Nullable MediaItemFormatInfo info, int generation) {
        if (generation != mGeneration) {
            return;
        }
        if (info == null || info.isUnplayable() || info.isLive() || info.isLiveContent()) {
            fail(generation, null);
            return;
        }
        if (mFactory == null) {
            mFactory = new Media3SourceFactory(mContext);
        }
        final Media3SourceFactory factory = mFactory;
        BUILD.execute(() -> {
            MediaSource source = null;
            try {
                source = SabrSourcePreference.isPreferred(mContext) ? null : factory.fromDashFormatInfo(info);
            } catch (Throwable e) { // never kill the build thread
                NetPath.log("home-preview build failed: " + e);
            }
            final MediaSource result = source;
            mMain.post(() -> {
                if (generation != mGeneration) {
                    return;
                }
                if (result == null) {
                    fail(generation, null);
                    return;
                }
                ExoPlayer player = player();
                player.setMediaSource(result);
                player.setVolume(mMuted ? 0f : 1f);
                player.prepare();
                player.setPlayWhenReady(true);
                NetPath.log("home-preview play video=" + mVideoId);
            });
        });
    }

    private void fail(int generation, @Nullable Throwable error) {
        if (generation == mGeneration) {
            NetPath.log("home-preview skip video=" + mVideoId + (error != null ? " " + error : ""));
            mVideoId = null;
            mCallback.onEnded();
        }
    }

    public void setMuted(boolean muted) {
        mMuted = muted;
        if (mPlayer != null) {
            mPlayer.setVolume(muted ? 0f : 1f);
        }
    }

    public void setCaptions(boolean on) {
        mCaptions = on;
        if (mSelector != null) {
            mSelector.setParameters(mSelector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on));
        }
        if (!on) {
            mCallback.onCues("");
        }
    }

    /** Milliseconds left, or -1 until the duration is known. */
    public long remainingMs() {
        return mPlayer == null || mPlayer.getDuration() == C.TIME_UNSET
                ? -1 : Math.max(0, mPlayer.getDuration() - mPlayer.getCurrentPosition());
    }

    /** 0..1 of the video played so far. */
    public float progress() {
        if (mPlayer == null || mPlayer.getDuration() <= 0) {
            return 0f;
        }
        return Math.min(1f, (float) mPlayer.getCurrentPosition() / mPlayer.getDuration());
    }

    /** Ends the current preview but keeps the decoder for the next one. */
    public void stop() {
        mGeneration++;
        mVideoId = null;
        mVideoW = 0;
        if (mFetch != null) {
            mFetch.dispose();
            mFetch = null;
        }
        if (mPlayer != null) {
            mPlayer.stop();
            mPlayer.clearMediaItems();
        }
        mCallback.onCues("");
    }

    public void release() {
        stop();
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
            mSelector = null;
        }
    }

    private ExoPlayer player() {
        if (mPlayer == null) {
            mSelector = new DefaultTrackSelector(mContext);
            mSelector.setParameters(mSelector.buildUponParameters()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !mCaptions)
                    .setPreferredTextLanguage(Locale.getDefault().getLanguage())
                    .setSelectUndeterminedTextLanguage(true)
                    .setMaxVideoSize(854, 480)
                    .setMaxVideoBitrate(MAX_BITRATE));
            mPlayer = new ExoPlayer.Builder(mContext)
                    .setTrackSelector(mSelector)
                    .setLoadControl(new DefaultLoadControl.Builder()
                            .setBufferDurationsMs(5000, 15000, 1000, 1500).build())
                    .build();
            mPlayer.setVideoTextureView(mView);
            mPlayer.addListener(new Player.Listener() {
                @Override
                public void onRenderedFirstFrame() {
                    mCallback.onFrame();
                }

                @Override
                public void onVideoSizeChanged(@NonNull VideoSize size) {
                    mVideoW = Math.round(size.width * size.pixelWidthHeightRatio);
                    mVideoH = size.height;
                    applyTransform();
                }

                @Override
                public void onTracksChanged(@NonNull Tracks tracks) {
                    TrackGroup first = null;
                    boolean selected = false;
                    for (Tracks.Group group : tracks.getGroups()) {
                        if (group.getType() == C.TRACK_TYPE_TEXT) {
                            if (first == null) {
                                first = group.getMediaTrackGroup();
                            }
                            selected |= group.isSelected();
                        }
                    }
                    mCallback.onCaptionsAvailable(first != null);
                    if (mCaptions && first != null && !selected && mSelector != null) {
                        mSelector.setParameters(mSelector.buildUponParameters()
                                .setOverrideForType(new TrackSelectionOverride(first, 0)));
                    }
                }

                @Override
                public void onCues(@NonNull CueGroup cueGroup) {
                    StringBuilder text = new StringBuilder();
                    for (Cue cue : cueGroup.cues) {
                        if (cue.text != null) {
                            text.append(text.length() > 0 ? "\n" : "").append(cue.text);
                        }
                    }
                    mCallback.onCues(mCaptions ? text : "");
                }

                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_ENDED) {
                        mCallback.onEnded();
                    }
                }

                @Override
                public void onPlayerError(@NonNull PlaybackException error) {
                    NetPath.log("home-preview error video=" + mVideoId + " code=" + error.getErrorCodeName());
                    mVideoId = null;
                    mCallback.onEnded();
                }
            });
        }
        return mPlayer;
    }

    /** The picture fills the card like its thumbnail (centre-crop); a texture alone would stretch it. */
    private void applyTransform() {
        int w = mView.getWidth();
        int h = mView.getHeight();
        if (w == 0 || h == 0 || mVideoW == 0 || mVideoH == 0) {
            return;
        }
        float videoAspect = (float) mVideoW / mVideoH;
        float viewAspect = (float) w / h;
        float sx = videoAspect > viewAspect ? videoAspect / viewAspect : 1f;
        float sy = videoAspect > viewAspect ? 1f : viewAspect / videoAspect;
        Matrix m = new Matrix();
        m.setScale(sx, sy, w / 2f, h / 2f);
        mView.setTransform(m);
    }
}
