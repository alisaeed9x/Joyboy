package com.newtube.mobile.player;

import android.content.Context;
import android.graphics.Matrix;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

/**
 * MEETUBE(shorts peek): the next short, decoded and paused on its first frame in the swipe peek -
 * a swipe pulls in the real video under the finger (Instagram's way), not a thumbnail, and it
 * covers the paged-to short until the main player's own first frame of it lands (the same picture,
 * so the hand-off does not show). A second player, video only, a small buffer, its source built
 * like the main player's (same transports and media cache, so it mostly reads what the next-preload
 * already fetched).
 *
 * <p>ponytail: one direction (the next short) and a paused frame - the main player still opens the
 * short on the swipe (~150 ms with its preload). Swapping players, as Instagram does, would make it
 * zero; that means two of everything MobilePlaybackActivity hangs on its one player.
 */
public final class ShortsPeekPlayer {
    public interface Listener {
        /** The peek holds {@code videoId}'s first frame. */
        void onPeekFrame(@NonNull String videoId);
    }

    /** First-frame quality is plenty at this rate; a cap also keeps a cache miss small. */
    private static final int MAX_VIDEO_BITRATE = 1_500_000;

    private final Context mContext;
    private final TextureView mView;
    private final Listener mListener;
    @Nullable private ExoPlayer mPlayer;
    @Nullable private String mVideoId;
    private boolean mHasFrame;
    private int mGeneration;
    private boolean mCrop = true;
    private int mVideoW;
    private int mVideoH;

    public ShortsPeekPlayer(@NonNull Context context, @NonNull TextureView view, @NonNull Listener listener) {
        mContext = context.getApplicationContext();
        mView = view;
        mListener = listener;
        mView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyTransform());
    }

    /** Decode {@code formatInfo}'s first frame into the peek (replacing whatever it held). */
    public void prepare(@NonNull MediaItemFormatInfo formatInfo, @NonNull Media3PlayerController controller) {
        String videoId = formatInfo.getVideoId();
        if (videoId == null || videoId.equals(mVideoId)) {
            return;
        }
        int generation = ++mGeneration;
        mVideoId = videoId;
        mHasFrame = false;
        controller.buildSourceAsync(formatInfo, source -> {
            if (generation != mGeneration || source == null) {
                return;
            }
            ExoPlayer player = player();
            player.setMediaSource(source);
            player.prepare();
            NetPath.log("shorts-peek prepare video=" + videoId);
        });
    }

    public boolean hasFrame(@Nullable String videoId) {
        return mHasFrame && videoId != null && videoId.equals(mVideoId);
    }

    /** Crop to fill (as a short plays on a phone held upright) or fit whole. */
    public void setCrop(boolean crop) {
        if (mCrop != crop) {
            mCrop = crop;
            applyTransform();
        }
    }

    /** Frees the decoder; the next {@link #prepare} builds a new player. */
    public void release() {
        mGeneration++;
        mVideoId = null;
        mHasFrame = false;
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
    }

    private ExoPlayer player() {
        if (mPlayer == null) {
            DefaultTrackSelector selector = new DefaultTrackSelector(mContext);
            selector.setParameters(selector.buildUponParameters()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .setMaxVideoBitrate(MAX_VIDEO_BITRATE));
            mPlayer = new ExoPlayer.Builder(mContext)
                    .setTrackSelector(selector)
                    .setLoadControl(new DefaultLoadControl.Builder()
                            .setBufferDurationsMs(1000, 1000, 500, 500).build())
                    .build();
            mPlayer.setPlayWhenReady(false); // paused: ready means the first frame is on the texture
            mPlayer.setVideoTextureView(mView);
            mPlayer.addListener(new Player.Listener() {
                @Override
                public void onRenderedFirstFrame() {
                    if (mVideoId != null && !mHasFrame) {
                        mHasFrame = true;
                        NetPath.log("shorts-peek frame video=" + mVideoId);
                        mListener.onPeekFrame(mVideoId);
                    }
                }

                @Override
                public void onVideoSizeChanged(@NonNull VideoSize size) {
                    mVideoW = Math.round(size.width * size.pixelWidthHeightRatio);
                    mVideoH = size.height;
                    applyTransform();
                }

                @Override
                public void onPlayerError(@NonNull PlaybackException error) {
                    // The thumbnail stays the peek; never retry the same link (a 403 is routine).
                    NetPath.log("shorts-peek error video=" + mVideoId + " code=" + error.getErrorCodeName());
                    mVideoId = null;
                    mHasFrame = false;
                }
            });
        }
        return mPlayer;
    }

    /** A TextureView stretches the frame to its box: scale it back to crop (or fit) centred. */
    private void applyTransform() {
        int w = mView.getWidth();
        int h = mView.getHeight();
        if (w == 0 || h == 0 || mVideoW == 0 || mVideoH == 0) {
            return;
        }
        float sx = (float) w / mVideoW;
        float sy = (float) h / mVideoH;
        float scale = mCrop ? Math.max(sx, sy) : Math.min(sx, sy);
        Matrix matrix = new Matrix();
        matrix.setScale(mVideoW * scale / w, mVideoH * scale / h, w / 2f, h / 2f);
        mView.setTransform(matrix);
    }
}
