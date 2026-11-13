package com.newtube.mobile.ui.playback;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.VideoCardAdapter;
import com.newtube.mobile.ui.common.Motion;

import java.util.ArrayList;
import java.util.List;

/**
 * MEETUBE(more videos, issue #24): YouTube's "More videos" in the landscape player. A swipe up on
 * the fullscreen video raises a row of the watch page's Up next cards over its lower part,
 * following the finger; a card plays in this player. A swipe down, a tap on the video or Back
 * puts it away.
 */
final class MoreVideosPanel {
    /** Cards across the panel: the fifth one peeks, so the row reads as one that scrolls. */
    private static final float CARDS_ACROSS = 4.5f;
    /** A flick this fast (dp/s) settles the panel its way, however far it was dragged. */
    private static final float FLICK_DP = 600f;

    private final View mPanel;
    private final RecyclerView mList;
    private final VideoCardAdapter mAdapter;
    private final float mFlickPx;
    private float mDragFrom;

    MoreVideosPanel(@NonNull View panel, @NonNull VideoCardAdapter.OnVideoClickListener onClick) {
        mPanel = panel;
        mList = panel.findViewById(R.id.mobile_more_videos_list);
        android.util.DisplayMetrics metrics = panel.getResources().getDisplayMetrics();
        mFlickPx = FLICK_DP * metrics.density;
        // ponytail: sized for a full-screen landscape window; a split-screen one gets wider cards.
        int cardWidth = Math.round(Math.max(metrics.widthPixels, metrics.heightPixels) / CARDS_ACROSS);
        mAdapter = new VideoCardAdapter(video -> {
            close();
            onClick.onVideoClick(video);
        }) {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                RecyclerView.ViewHolder holder = super.onCreateViewHolder(parent, viewType);
                holder.itemView.getLayoutParams().width = cardWidth;
                // Light text on the dark panel, in the light theme too.
                TextView title = holder.itemView.findViewById(R.id.video_title);
                TextView meta = holder.itemView.findViewById(R.id.video_meta);
                if (title != null) {
                    title.setTextColor(0xFFFFFFFF);
                }
                if (meta != null) {
                    meta.setTextColor(0xB3FFFFFF);
                }
                return holder;
            }
        };
        mList.setLayoutManager(new LinearLayoutManager(panel.getContext(), LinearLayoutManager.HORIZONTAL, false));
        mList.setAdapter(mAdapter);
    }

    /** On screen, or on its way (a drag or a settle). */
    boolean isShown() {
        return mPanel.getVisibility() == View.VISIBLE;
    }

    /**
     * The watch page's Up next, as it loads. Laid out while hidden, so the panel has its height
     * (and its first thumbnails) before the first swipe.
     */
    void setVideos(@NonNull List<Video> videos) {
        mAdapter.submitList(new ArrayList<>(videos));
    }

    /** A swipe began: the panel follows it from where it is. False when there is nothing to show. */
    boolean begin() {
        if (!isShown()) {
            if (mAdapter.getItemCount() == 0 || mPanel.getHeight() == 0) {
                return false;
            }
            mList.scrollToPosition(0);
            mPanel.setTranslationY(mPanel.getHeight());
            mPanel.setVisibility(View.VISIBLE);
        }
        mPanel.animate().cancel();
        mDragFrom = mPanel.getTranslationY();
        return true;
    }

    /** {@code dy}: the finger's travel since the swipe began (up is negative). */
    void drag(float dy) {
        mPanel.setTranslationY(Math.max(0f, Math.min(mPanel.getHeight(), mDragFrom + dy)));
    }

    void release(float dy, float yVelocity) {
        drag(dy);
        boolean open = Math.abs(yVelocity) > mFlickPx ? yVelocity < 0 : mPanel.getTranslationY() < mPanel.getHeight() / 2f;
        settle(open);
    }

    /** A tap on the toggle: begin() first, then this slides the panel up. */
    void open() {
        settle(true);
    }

    void close() {
        if (isShown()) {
            settle(false);
        }
    }

    /** At once, without the slide (the player left landscape, or a new video is opening). */
    void hide() {
        mPanel.animate().cancel();
        mPanel.setVisibility(View.INVISIBLE);
    }

    private void settle(boolean open) {
        mPanel.animate().cancel();
        mPanel.animate().translationY(open ? 0f : mPanel.getHeight())
                .setDuration(open ? Motion.ENTER_MS : Motion.EXIT_MS)
                .setInterpolator(Motion.STANDARD_DECELERATE)
                .withEndAction(open ? null : () -> mPanel.setVisibility(View.INVISIBLE))
                .start();
    }
}
