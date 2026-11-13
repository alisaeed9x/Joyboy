package com.newtube.mobile.ui.browse;

import android.annotation.SuppressLint;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.newtube.mobile.ui.playback.PlayerTransitionBridge;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.utils.ClickbaitRemover;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Touch video card adapter (port of the gist of
 * {@code com.liskovsoft.smartyoutubetv2.tv.presenter.VideoCardPresenter}, minus
 * focus-scale/d-pad concerns; ripple/elevation instead via the card's own foreground
 * and {@code MaterialCardView} elevation).
 */
public class VideoCardAdapter extends ListAdapter<Video, RecyclerView.ViewHolder> {
    /** Regular video card (full-width thumbnail + title/meta). */
    private static final int VIEW_TYPE_VIDEO = 0;
    /** Channel result row (round avatar + name + subs) — e.g. channels in search results. */
    private static final int VIEW_TYPE_CHANNEL = 1;
    /** MEETUBE(shorts): a channel Shorts tab's tile (item_mobile_shorts_tile), see {@link #shortsTiles}. */
    private static final int VIEW_TYPE_SHORT_TILE = 2;
    /** MEETUBE(list layout, issue #82): a video as a compact row (item_video_row), see {@link #setListRows}. */
    private static final int VIEW_TYPE_VIDEO_ROW = 3;
    /** MEETUBE(issue #43): Home's Shorts row, one card of sideways tiles ({@link Video#shortsShelf}). */
    private static final int VIEW_TYPE_SHORTS_SHELF = 4;
    /** A shelf tile's width: two and a bit show on a phone, so the row reads as one to swipe. */
    static final int SHELF_TILE_WIDTH_DP = 156;

    public interface OnVideoClickListener {
        void onVideoClick(Video video);
    }

    /** Long-press = the touch equivalent of the TV D-pad OK-long-press context-menu shortcut
     *  (ARCHITECTURE.md section 3's input contract: {@code onVideoItemLongClicked}). Routes to
     *  {@code BrowsePresenter.onVideoItemLongClicked()}, which builds an OptionCategory menu via
     *  {@code VideoMenuPresenter}/{@code AppDialogPresenter} and shows it through the Wave 3
     *  {@code MobileAppDialogActivity} renderer. */
    public interface OnVideoLongClickListener {
        boolean onVideoLongClick(Video video);
    }

    private final OnVideoClickListener mClickListener;
    private final OnVideoLongClickListener mLongClickListener;
    private boolean mShowAvatars;

    /**
     * MEETUBE(card avatars, issue #6): a round channel avatar beside each card's title, and the
     * avatar or the channel name in the meta line opens the channel. Off on a channel's own page.
     */
    public boolean showsAvatars() {
        return mShowAvatars;
    }

    public VideoCardAdapter showAvatars() {
        mShowAvatars = true;
        return this;
    }

    private boolean mShortsTiles;

    /** MEETUBE(shorts): Shorts as YouTube's channel Shorts tab draws them, not as search's cards. */
    public VideoCardAdapter shortsTiles() {
        mShortsTiles = true;
        return this;
    }

    private boolean mStrip;

    /** MEETUBE(issue #43): cards in one sideways row - a Short spaced evenly, not as half of a two-up row. */
    public VideoCardAdapter strip() {
        mStrip = true;
        return this;
    }

    private boolean mListRows;

    /** MEETUBE(list layout, issue #82): videos as rows, not cards (Settings > Video layout); Shorts stay tiles. */
    public VideoCardAdapter setListRows(boolean listRows) {
        if (mListRows != listRows) {
            mListRows = listRows;
            notifyDataSetChanged();
        }
        return this;
    }

    public VideoCardAdapter(OnVideoClickListener clickListener) {
        this(clickListener, null);
    }

    public VideoCardAdapter(OnVideoClickListener clickListener, OnVideoLongClickListener longClickListener) {
        super(DIFF_CALLBACK);
        mClickListener = clickListener;
        mLongClickListener = longClickListener;
    }

    private static final DiffUtil.ItemCallback<Video> DIFF_CALLBACK = new DiffUtil.ItemCallback<Video>() {
        @Override
        public boolean areItemsTheSame(@NonNull Video oldItem, @NonNull Video newItem) {
            // Video#equals/hashCode are content-based (videoId/playlistId/channelId/...).
            return oldItem.equals(newItem);
        }

        @Override
        @SuppressLint("DiffUtilEquals")
        public boolean areContentsTheSame(@NonNull Video oldItem, @NonNull Video newItem) {
            // Video.equals() is identity-like (IDs only). A new instance with the same ID can
            // carry refreshed metadata and must be rebound; the same instance needs no rebind.
            return oldItem == newItem;
        }
    };

    @Override
    public int getItemViewType(int position) {
        // isChannel() alone also matches playlists delivered in the playlist-as-channel shape
        // (videoId/playlistId null + a VL... channelId — the standard shape of the user
        // Playlists section): those must stay thumbnail cards, not round-avatar channel rows.
        Video item = getItem(position);
        if (item.shortsShelf != null) {
            return VIEW_TYPE_SHORTS_SHELF;
        }
        if (mShortsTiles && item.belongsToShorts()) {
            return VIEW_TYPE_SHORT_TILE;
        }
        // MEETUBE(channels): the Channels section's entries (no video behind them) are channel cards too.
        if ((item.isChannel() && !item.isPlaylistAsChannel()) || (item.belongsToChannelUploads() && item.videoId == null)) {
            return VIEW_TYPE_CHANNEL;
        }
        return mListRows && !item.belongsToShorts() ? VIEW_TYPE_VIDEO_ROW : VIEW_TYPE_VIDEO;
    }

    /** Full-span rows (channel results) for the grid's SpanSizeLookup in multi-column layouts. */
    public boolean isFullSpan(int position) {
        if (position < 0 || position >= getItemCount()) {
            return false;
        }
        int type = getItemViewType(position);
        return type == VIEW_TYPE_CHANNEL || type == VIEW_TYPE_SHORTS_SHELF;
    }

    /**
     * MEETUBE(shorts): which of a two-up Shorts row this card is in (0 left, 1 right), counted along
     * its run of shorts, or -1 for any other card.
     * ponytail: assumes two shorts per row (portrait phone); a wider grid gets the same margins.
     */
    private int shortsColumn(int position) {
        if (!isShort(position)) {
            return -1;
        }
        if (mStrip) {
            return 2;
        }
        int run = 0;
        while (isShort(position - 1 - run)) {
            run++;
        }
        return run % 2;
    }

    /** MEETUBE(shorts): a tall Shorts card, half a column wide in the search grid. */
    public boolean isShort(int position) {
        return position >= 0 && position < getItemCount() && getItem(position).belongsToShorts();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_CHANNEL) {
            return new ChannelViewHolder(inflater.inflate(R.layout.item_mobile_channel_card, parent, false), mClickListener);
        }
        if (viewType == VIEW_TYPE_SHORT_TILE) {
            return new ShortTileHolder(inflater.inflate(R.layout.item_mobile_shorts_tile, parent, false),
                    mClickListener, mLongClickListener);
        }
        if (viewType == VIEW_TYPE_SHORTS_SHELF) {
            return new ShortsShelfHolder(parent.getContext(), mClickListener, mLongClickListener);
        }
        VideoViewHolder holder = new VideoViewHolder(inflater.inflate(viewType == VIEW_TYPE_VIDEO_ROW
                ? R.layout.item_video_row : R.layout.item_video_card, parent, false), mClickListener);
        holder.mShowAvatar = mShowAvatars;
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof ChannelViewHolder) {
            ((ChannelViewHolder) holder).bind(getItem(position), mLongClickListener);
        } else if (holder instanceof ShortTileHolder) {
            ((ShortTileHolder) holder).bind(getItem(position));
        } else if (holder instanceof ShortsShelfHolder) {
            ((ShortsShelfHolder) holder).bind(getItem(position));
        } else {
            ((VideoViewHolder) holder).bind(getItem(position), mLongClickListener);
            ((VideoViewHolder) holder).bindShortsColumn(shortsColumn(position));
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof VideoViewHolder) {
            ((VideoViewHolder) holder).unbind();
        } else if (holder instanceof ChannelViewHolder) {
            ((ChannelViewHolder) holder).unbind();
        } else if (holder instanceof ShortTileHolder) {
            ((ShortTileHolder) holder).unbind();
        }
    }

    /** MEETUBE(issue #43): the shelf's title over a sideways strip of Shorts tiles. */
    static class ShortsShelfHolder extends RecyclerView.ViewHolder {
        private final TextView mTitle;
        private final VideoCardAdapter mTiles;

        ShortsShelfHolder(Context context, OnVideoClickListener clickListener,
                @Nullable OnVideoLongClickListener longClickListener) {
            super(new android.widget.LinearLayout(context));
            float density = context.getResources().getDisplayMetrics().density;
            android.widget.LinearLayout root = (android.widget.LinearLayout) itemView;
            root.setOrientation(android.widget.LinearLayout.VERTICAL);
            root.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            root.setPadding(0, Math.round(8 * density), 0, Math.round(16 * density));

            mTitle = new TextView(context);
            mTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
            mTitle.setTextColor(ContextCompat.getColor(context, R.color.mobile_color_on_surface));
            mTitle.setTypeface(mTitle.getTypeface(), android.graphics.Typeface.BOLD);
            mTitle.setSingleLine();
            int side = Math.round(16 * density);
            mTitle.setPadding(side, 0, side, Math.round(8 * density));
            // MEETUBE(shorts): the red Shorts mark before the title, as on YouTube.
            mTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_shorts_logo, 0, 0, 0);
            mTitle.setCompoundDrawablePadding(Math.round(10 * density));
            root.addView(mTitle);

            int tileWidth = Math.round(SHELF_TILE_WIDTH_DP * density);
            // Search's Shorts card (rounded, title and views under it), not the Shorts tab's tile.
            mTiles = new VideoCardAdapter(clickListener, longClickListener) {
                @NonNull
                @Override
                public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                    RecyclerView.ViewHolder tile = super.onCreateViewHolder(parent, viewType);
                    tile.itemView.getLayoutParams().width = tileWidth;
                    return tile;
                }
            }.strip();
            RecyclerView strip = new RecyclerView(context);
            strip.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(context, RecyclerView.HORIZONTAL, false));
            strip.setNestedScrollingEnabled(false);
            strip.setClipToPadding(false);
            int edge = Math.round(13 * density); // + a card's 3dp margin: in line with the title
            strip.setPadding(edge, 0, edge, 0);
            strip.setAdapter(mTiles);
            root.addView(strip, new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void bind(Video shelf) {
            String title = shelf.getGroupTitle();
            mTitle.setText(title != null && !title.trim().isEmpty() ? title : itemView.getContext().getString(R.string.header_shorts));
            mTiles.submitList(new java.util.ArrayList<>(shelf.shortsShelf));
        }
    }

    /** MEETUBE(shorts): see item_mobile_shorts_tile. Tap plays, the corner menu is the long-press menu. */
    static class ShortTileHolder extends RecyclerView.ViewHolder {
        private final ImageView mThumbnail;
        private final TextView mViews;
        private Video mVideo;

        ShortTileHolder(@NonNull View itemView, OnVideoClickListener clickListener,
                @Nullable OnVideoLongClickListener longClickListener) {
            super(itemView);
            itemView.setClipToOutline(true); // the rounded background (the XML attribute is API 31+)
            mThumbnail = itemView.findViewById(R.id.shorts_tile_thumbnail);
            mViews = itemView.findViewById(R.id.shorts_tile_views);
            itemView.setOnClickListener(v -> {
                if (mVideo != null && clickListener != null) {
                    PlayerTransitionBridge.clear();
                    clickListener.onVideoClick(mVideo);
                }
            });
            View more = itemView.findViewById(R.id.shorts_tile_more);
            more.setVisibility(longClickListener == null ? View.GONE : View.VISIBLE);
            View.OnLongClickListener menu = v -> mVideo != null && longClickListener != null
                    && longClickListener.onVideoLongClick(mVideo);
            itemView.setOnLongClickListener(menu);
            more.setOnClickListener(menu::onLongClick);
        }

        void bind(Video video) {
            mVideo = video;
            itemView.setContentDescription(video.getTitle());
            mViews.setText(viewsOf(video.getSecondTitle()));
            Context context = itemView.getContext();
            // The 720x1280 portrait, decoded at tile size (the cards' request crops it to 16:9).
            com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> fallback = Glide.with(context)
                    .load("https://i.ytimg.com/vi/" + video.videoId + "/hq720.jpg")
                    .format(DecodeFormat.PREFER_RGB_565).override(TILE_W, TILE_H).centerCrop();
            Glide.with(context)
                    .load(VideoViewHolder.thumbnailUrl(context, video))
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .override(TILE_W, TILE_H)
                    .centerCrop()
                    .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(150))
                    .error(fallback)
                    .into(mThumbnail);
        }

        void unbind() {
            mVideo = null;
            Glide.with(itemView.getContext().getApplicationContext()).clear(mThumbnail);
        }

        private static final int TILE_W = 360;
        private static final int TILE_H = 640;

        /**
         * "1.2M views" out of the info line ("Channel · 1.2M views · 3 days ago"): its first part
         * with a digit that isn't the @handle (the TV Shorts tile's whole line), else nothing.
         */
        static CharSequence viewsOf(@Nullable CharSequence info) {
            if (info == null) {
                return null;
            }
            for (String part : info.toString().split("[•·]")) {
                String trimmed = part.trim();
                if (!trimmed.startsWith("@") && trimmed.matches(".*\\d.*")) {
                    return trimmed;
                }
            }
            return null;
        }
    }

    /**
     * Force a re-bind of the given items in place.
     *
     * Needed for {@code VideoGroup.ACTION_SYNC} (e.g. DeArrow / unlocalized-title overrides):
     * those processors MUTATE the existing {@link Video} instances in place
     * ({@code video.deArrowTitle} / {@code video.altCardImageUrl}) rather than emitting new
     * ones, so {@code submitList()} can't detect the change - {@link #DIFF_CALLBACK}'s
     * {@code areContentsTheSame} sees the same object reference on both sides and skips the
     * bind. We locate each item by content identity ({@link Video#equals}) in the currently
     * displayed list and rebind that row so the crowd-sourced title/thumbnail actually shows.
     */
    public void refreshItems(java.util.List<Video> items) {
        java.util.List<Video> current = getCurrentList();
        for (Video item : items) {
            for (int i = 0; i < current.size(); i++) {
                if (current.get(i).equals(item)) {
                    // Payloaded change: RecyclerView reuses the bound holder (no change-animation
                    // cross-fade of the whole card) and the partial bind below skips the Glide
                    // reload unless the thumbnail URL actually changed. A bare notifyItemChanged
                    // here made the just-watched card visibly blink on every Browse resume (the
                    // resume sync only carries a percentWatched update).
                    notifyItemChanged(i, PAYLOAD_SYNC);
                }
            }
        }
    }

    /** Marker payload for in-place item mutations (watch progress, DeArrow overrides). */
    private static final Object PAYLOAD_SYNC = new Object();

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position,
            @NonNull java.util.List<Object> payloads) {
        if (!payloads.isEmpty() && holder instanceof VideoViewHolder) {
            ((VideoViewHolder) holder).bindPartial(getItem(position));
        } else {
            super.onBindViewHolder(holder, position, payloads);
        }
    }

    /**
     * Public for reuse by other screens that render the standard video card (kept public
     * after the channel page's old heterogeneous adapter was replaced by tabs, 2026-07-09).
     */
    public static class VideoViewHolder extends RecyclerView.ViewHolder {
        private final ImageView mThumbnail;
        private final View mThumbnailFrame;
        private final TextView mBadge;
        private final android.graphics.drawable.Drawable mBadgeGlass;
        private final ProgressBar mWatchProgress;
        private final TextView mTitle;
        private final TextView mMeta;
        private final View mOverflow;
        private final ImageView mAvatar;
        private final ImageView mAvatar2;
        private final TextView mShortTitle;
        @Nullable private final com.newtube.mobile.ui.common.LiveRingView mLiveRing;
        private Video mVideo;
        private String mBoundThumbUrl;
        boolean mShowAvatar;
        /** Where the last touch landed in the meta line: on the channel name or not. */
        private boolean mMetaTouchOnChannel;

        Video video() { return mVideo; }
        View thumbnailFrame() { return mThumbnailFrame; }
        TextView badge() { return mBadge; }
        public VideoViewHolder(@NonNull View itemView, OnVideoClickListener clickListener) {
            super(itemView);

            mThumbnail = itemView.findViewById(R.id.video_thumbnail);
            mThumbnailFrame = itemView.findViewById(R.id.video_thumbnail_frame);
            mBadge = itemView.findViewById(R.id.video_badge);
            // MEETUBE(glass): the duration pill frosts the thumbnail, as the Home chips frost the feed.
            com.newtube.mobile.ui.common.GlassDrawable.apply(mBadge, itemView.findViewById(R.id.video_thumbnail_glass), 0);
            mBadgeGlass = mBadge.getBackground();
            mWatchProgress = itemView.findViewById(R.id.video_watch_progress);
            mTitle = itemView.findViewById(R.id.video_title);
            mMeta = itemView.findViewById(R.id.video_meta);
            mOverflow = itemView.findViewById(R.id.video_overflow);
            mAvatar = itemView.findViewById(R.id.video_avatar);
            mShortTitle = itemView.findViewById(R.id.video_short_title);
            mAvatar2 = itemView.findViewById(R.id.video_avatar_2);
            mLiveRing = itemView.findViewById(R.id.video_avatar_live);

            if (mAvatar != null) {
                mAvatar.setOnClickListener(v -> openChannel());
            }
            // MEETUBE(issue #6): the channel name at the start of the meta line opens the channel;
            // the rest of the line (views, age) still opens the video.
            mMeta.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    int offset = mMeta.getOffsetForPosition(event.getX(), event.getY());
                    mMetaTouchOnChannel = mShowAvatar && offset >= 0 && offset < channelNameLength();
                }
                return false;
            });
            mMeta.setOnClickListener(v -> {
                if (mMetaTouchOnChannel) {
                    openChannel();
                } else {
                    itemView.performClick();
                }
            });
            mMeta.setOnLongClickListener(v -> itemView.performLongClick());

            itemView.setOnClickListener(v -> {
                if (mVideo != null && clickListener != null) {
                    if (mVideo.videoId != null) {
                        PlayerTransitionBridge.prepare(mThumbnailFrame, mBadge, mWatchProgress);
                    } else {
                        PlayerTransitionBridge.clear();
                    }
                    clickListener.onVideoClick(mVideo);
                }
            });
        }

        /** Length of the channel name that leads the meta line, or 0 when it doesn't lead it. */
        private int channelNameLength() {
            CharSequence text = mMeta.getText();
            String author = mVideo != null ? mVideo.getAuthor() : null;
            return author != null && !author.isEmpty() && text != null
                    && text.toString().startsWith(author) ? author.length() : 0;
        }

        private void openChannel() {
            if (mVideo != null && com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter.canOpenChannel(mVideo)) {
                PlayerTransitionBridge.clear();
                com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.chooseChannelPresenter(
                        itemView.getContext(), mVideo);
            }
        }

        private void bindAvatar(Context context, Video video) {
            if (mAvatar == null) {
                return;
            }
            // Not for mixes and playlists: YouTube shows none there either (many channels, no one).
            boolean show = mShowAvatar && video.videoId != null && !video.isMix()
                    && !video.belongsToShorts() && video.getAuthor() != null;
            mAvatar.setVisibility(show ? View.VISIBLE : View.GONE);
            String[] collab = show ? collabNames(video.getAuthor()) : null;
            bindCollab(collab != null);
            // MEETUBE(issue #73): live now - the glowing red ring around the channel.
            if (video.isLive) {
                com.newtube.mobile.ui.common.LiveRingView.markLive(video.channelId);
            }
            com.newtube.mobile.ui.common.LiveRingView.bind(mLiveRing, mAvatar, show && collab == null && video.isLive, 0);
            if (!show) {
                return;
            }
            mAvatar.setContentDescription(video.getAuthor());
            if (collab == null) {
                loadAvatar(context, video, mAvatar, video.channelId, video.getAuthor());
            } else {
                // The TV tile's channel id is the first collaborator's; the second is known by name.
                loadAvatar(context, video, mAvatar, video.channelId, collab[0]);
                loadAvatar(context, video, mAvatar2, null, collab[1]);
            }
        }

        private void loadAvatar(Context context, Video video, ImageView view, @Nullable String channelId, String name) {
            android.graphics.drawable.Drawable letter = com.newtube.mobile.ui.common.ChannelAvatars.placeholder(name);
            String url = com.newtube.mobile.ui.common.ChannelAvatars.get(context, channelId, name);
            if (url == null) {
                Glide.with(context.getApplicationContext()).clear(view);
                view.setImageDrawable(letter);
                com.newtube.mobile.ui.common.ChannelAvatars.fetch(context, channelId, name,
                        () -> mVideo == video, found -> {
                    if (mVideo == video && view.getVisibility() == View.VISIBLE) {
                        Glide.with(itemView.getContext()).load(found).circleCrop().placeholder(letter)
                                .transition(com.newtube.mobile.ui.common.ChannelAvatars.FADE).into(view);
                    }
                }, false);
            } else {
                Glide.with(context).load(url).circleCrop().placeholder(letter).error(letter)
                        .transition(com.newtube.mobile.ui.common.ChannelAvatars.FADE).into(view);
            }
        }

        /**
         * MEETUBE(card avatars): look up and decode the avatar(s) of a card about to scroll in, so
         * it arrives with them (FeedThumbnailPreloader). Same request shape as the bind's.
         */
        public static void prefetchAvatars(Context context, Video video) {
            if (video.videoId == null || video.isMix() || video.belongsToShorts() || video.getAuthor() == null) {
                return;
            }
            String[] collab = collabNames(video.getAuthor());
            if (collab == null) {
                prefetchAvatar(context, video.channelId, video.getAuthor());
            } else {
                prefetchAvatar(context, video.channelId, collab[0]);
                prefetchAvatar(context, null, collab[1]);
            }
        }

        private static void prefetchAvatar(Context context, @Nullable String channelId, String name) {
            int size = Math.round(36 * context.getResources().getDisplayMetrics().density);
            Context app = context.getApplicationContext();
            String url = com.newtube.mobile.ui.common.ChannelAvatars.get(context, channelId, name);
            if (url != null) {
                Glide.with(app).load(url).circleCrop().preload(size, size);
            } else {
                com.newtube.mobile.ui.common.ChannelAvatars.fetch(context, channelId, name, () -> true,
                        found -> Glide.with(app).load(found).circleCrop().preload(size, size), false);
            }
        }

        /** MEETUBE(collab avatars): two overlapping circles, the first shrunk to the top-left. */
        private void bindCollab(boolean collab) {
            if (mAvatar2 == null) {
                return;
            }
            mAvatar2.setVisibility(collab ? View.VISIBLE : View.GONE);
            if (!collab) {
                Glide.with(itemView.getContext().getApplicationContext()).clear(mAvatar2);
            }
            float scale = collab ? 26f / 36f : 1f;
            mAvatar.setPivotX(0f);
            mAvatar.setPivotY(0f);
            mAvatar.setScaleX(scale);
            mAvatar.setScaleY(scale);
        }

        /**
         * MEETUBE(collab avatars): a two-channel collab's byline, "A and B" (YouTube's form), split
         * into the two names, or null for one channel (or for "A and 2 more").
         * ponytail: English "and" only, and a channel whose own name has " and " in it reads as a
         * collab - its second "avatar" then stays a placeholder unless a channel has that exact name.
         */
        @Nullable
        static String[] collabNames(String author) {
            int at = author.indexOf(" and ");
            if (at <= 0 || author.indexOf(" and ", at + 1) >= 0 || author.endsWith(" more")) {
                return null;
            }
            return new String[] {author.substring(0, at), author.substring(at + 5)};
        }

        public void bind(Video video, OnVideoLongClickListener longClickListener) {
            mVideo = video;
            Context context = itemView.getContext();
            if (android.text.TextUtils.isEmpty(video.getTitle())) {
                // MEETUBE(issue #41): a bare video id from the feed - its title and channel are asked for.
                com.newtube.mobile.ui.common.MissingTitles.fill(video, () -> {
                    if (mVideo == video) {
                        bindAvatar(itemView.getContext(), video);
                        bindPartial(video);
                    }
                });
            }
            bindAvatar(context, video);

            itemView.setOnLongClickListener(v ->
                    mVideo != null && longClickListener != null && longClickListener.onVideoLongClick(mVideo));

            // ⋮ = the exact long-press context menu, just discoverable (Not interested, Open
            // channel, playlists...). Hidden when the host screen has no menu wired.
            if (mOverflow != null) {
                mOverflow.setVisibility(longClickListener == null ? View.GONE : View.VISIBLE);
                mOverflow.setOnClickListener(v -> {
                    if (mVideo != null && longClickListener != null) {
                        longClickListener.onVideoLongClick(mVideo);
                    }
                });
                // NEWTUBE(a11y): one of 40 identical "More options" otherwise.
                mOverflow.setContentDescription(video.getTitle() != null
                        ? context.getString(R.string.mobile_more_options_for, video.getTitle())
                        : context.getString(R.string.mobile_player_more));
            }

            mTitle.setText(video.getTitle());

            // "Channel · 1.2M views · 3 weeks ago" like YouTube; author-only when the
            // service didn't build the info line (e.g. some playlist rows).
            CharSequence meta = video.getSecondTitle();
            mMeta.setText(meta == null || meta.length() == 0 ? video.getAuthor() : meta);

            bindBadge(context, video);
            bindProgress(video);
            bindReadiness(video);
            bindShape(video);
            bindThumbnail(context, video);
        }

        /**
         * MEETUBE(shorts): a Short in the search grid is compact like YouTube's - title on the
         * thumbnail, one meta line under it, narrow gutters (column 0/1; -1 = a normal card).
         * The ⋮ menu stays on long-press.
         */
        void bindShortsColumn(int column) {
            boolean shortCard = column >= 0;
            mShortTitle.setVisibility(shortCard ? View.VISIBLE : View.GONE);
            mShortTitle.setText(shortCard ? mVideo.getTitle() : null);
            mTitle.setVisibility(shortCard ? View.GONE : View.VISIBLE);
            if (shortCard && mOverflow != null) {
                mOverflow.setVisibility(View.GONE);
            }
            float dp = itemView.getResources().getDisplayMetrics().density;
            int edge = itemView.getResources().getDimensionPixelSize(R.dimen.mobile_card_spacing);
            int inner = Math.round(3 * dp);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) itemView.getLayoutParams();
            // left/right, not start/end: RecyclerView lays out with the resolved margins.
            int left = !shortCard ? edge : column == 0 ? edge : inner;
            int right = !shortCard ? edge : column == 1 ? edge : inner; // 2: in a sideways strip
            int vertical = shortCard ? Math.round(4 * dp) : edge;
            if (lp.leftMargin != left || lp.rightMargin != right || lp.topMargin != vertical) {
                lp.setMargins(left, vertical, right, vertical);
                itemView.setLayoutParams(lp);
            }
        }

        /** MEETUBE(shorts): Shorts-tab cards are tall (9:16), everything else 16:9. */
        private void bindShape(Video video) {
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams lp =
                    (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) mThumbnailFrame.getLayoutParams();
            String ratio = video.belongsToShorts() ? "9:16" : "16:9";
            if (!ratio.equals(lp.dimensionRatio)) {
                lp.dimensionRatio = ratio;
                mThumbnailFrame.setLayoutParams(lp);
            }
        }

        /**
         * Sync rebind for an item mutated in place (ACTION_SYNC): refresh the text/progress
         * views and only touch the thumbnail if its resolved URL changed (DeArrow override
         * arriving). Keeping the ImageView untouched for a same-URL sync is what stops the
         * just-watched card from blinking when Browse resumes after playback.
         */
        void bindPartial(Video video) {
            mVideo = video;
            Context context = itemView.getContext();

            mTitle.setText(video.getTitle());
            CharSequence meta = video.getSecondTitle();
            mMeta.setText(meta == null || meta.length() == 0 ? video.getAuthor() : meta);

            bindBadge(context, video);
            bindProgress(video);
            bindReadiness(video);

            String thumbnailUrl = thumbnailUrl(context, video);
            if (thumbnailUrl == null ? mBoundThumbUrl != null : !thumbnailUrl.equals(mBoundThumbUrl)) {
                bindThumbnail(context, video);
            }
        }

        private void bindBadge(Context context, Video video) {
            String badgeText;
            if (video.hasNewContent) {
                badgeText = context.getString(R.string.badge_new_content);
            } else if (video.isLive) {
                badgeText = context.getString(R.string.badge_live);
            } else if (video.isShorts && !video.belongsToShorts()) { // the Shorts tab needs no label
                badgeText = context.getString(R.string.header_shorts).toUpperCase();
            } else {
                badgeText = video.badge;
            }

            if (badgeText == null || badgeText.isEmpty()) {
                mBadge.setVisibility(View.GONE);
            } else {
                mBadge.setText(badgeText);
                // a live / upcoming badge stays a solid red pill; everything else is the glass
                if (video.isLive || video.isUpcoming) {
                    android.graphics.drawable.GradientDrawable live = (android.graphics.drawable.GradientDrawable)
                            ContextCompat.getDrawable(context, R.drawable.bg_video_badge).mutate();
                    live.setColor(ContextCompat.getColor(context, R.color.mobile_color_badge_live_bg));
                    mBadge.setBackground(live);
                } else if (mBadge.getBackground() != mBadgeGlass) {
                    mBadge.setBackground(mBadgeGlass);
                }
                mBadge.setVisibility(View.VISIBLE);
            }
        }

        /**
         * NEWTUBE(downloads): a download that is not playable yet (queued, fetching, finishing,
         * failed) keeps its thumbnail dimmed so it never reads as a ready video - the badge and
         * the meta line say what it is doing.
         */
        private void bindReadiness(Video video) {
            mThumbnail.setAlpha(video.isPendingDownload() ? 0.4f : 1f);
        }

        private void bindProgress(Video video) {
            // Count progress that's very close to zero (e.g. user closed the video immediately).
            int progress = video.percentWatched > 0 && video.percentWatched < 1 ? 1 : Math.round(video.percentWatched);

            if (progress > 0 && progress <= 100 && com.newtube.mobile.ui.common.WatchedBar.isShown(itemView.getContext())) {
                mWatchProgress.setProgress(progress);
                mWatchProgress.setVisibility(View.VISIBLE);
            } else {
                mWatchProgress.setVisibility(View.GONE);
            }
        }

        /**
         * SCROLL-JANK FIX: decode thumbnails downsampled to the card's real display size (not the
         * layout-determined size Glide would otherwise wait a measure pass for) and in RGB_565.
         * Thumbnails are opaque JPEGs, so 565 halves both decode work and the GPU texture upload
         * per card - the biggest per-frame cost while fling-scrolling the grid. Sized for the
         * portrait single-column feed (the largest card = full screen width); landscape splits
         * the wider dimension across 2+ columns, so this stays a correct downsample bound there.
         */
        private static int sThumbW;
        private static int sThumbH;

        private static int thumbWidth(Context context) {
            if (sThumbW == 0) {
                android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
                sThumbW = Math.min(dm.widthPixels, dm.heightPixels);
                sThumbH = sThumbW * 9 / 16;
            }
            return sThumbW;
        }

        /**
         * The card's primary thumbnail request, without its view-only options (transition, error
         * fallback). NEWTUBE(scroll): shared with {@link com.newtube.mobile.ui.common.FeedThumbnailPreloader}
         * so a preloaded image has exactly the memory-cache key the bind will ask for (same URL,
         * size, transformation and decode format) and the card binds it with no network wait and
         * no fade.
         */
        public static com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> thumbnailRequest(
                com.bumptech.glide.RequestManager glide, Context context, Video video) {
            int w = thumbWidth(context);
            return glide
                    .load(thumbnailUrl(context, video))
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .override(w, sThumbH)
                    .centerCrop();
        }

        public static String thumbnailUrl(Context context, Video video) {
            // NEWTUBE(ux): entries saved without an image (local history, link opens) get the
            // i.ytimg.com fallback here, so the preloader and the bind ask for the same URL.
            com.newtube.mobile.ui.common.LocalThumbnails.fill(video);
            // MEETUBE(shorts): the feed's own Shorts images are tiny; oar2 is the 720x1280 portrait.
            if (video.belongsToShorts() && video.videoId != null) {
                return "https://i.ytimg.com/vi/" + video.videoId + "/oar2.jpg";
            }
            return ClickbaitRemover.updateThumbnail(video, MainUIData.instance(context).getThumbQuality());
        }

        private void bindThumbnail(Context context, Video video) {
            String thumbnailUrl = thumbnailUrl(context, video);
            mBoundThumbUrl = thumbnailUrl;

            int w = thumbWidth(context);
            com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> request =
                    thumbnailRequest(Glide.with(context), context, video)
                    // Network loads fade in over the placeholder-colored card instead of popping;
                    // memory-cache hits skip the transition entirely, so scrolling stays crisp.
                    .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(150));

            // NEWTUBE(startup): release-visible "first thumbnail on screen" milestone. The listener
            // is attached only until it has fired once per process.
            if (com.newtube.mobile.LaunchMilestones.wantsFirstThumb()) {
                request = request.addListener(FIRST_THUMB_LISTENER);
            }

            // At the default thumb quality the primary URL IS the card URL, so a fallback request
            // would be identical - only pay the second RequestBuilder when it can actually differ.
            String fallbackUrl = video.belongsToShorts() && video.videoId != null
                    ? "https://i.ytimg.com/vi/" + video.videoId + "/hq720.jpg" // no portrait image
                    : video.getCardImageUrl();
            if (fallbackUrl != null && !fallbackUrl.equals(thumbnailUrl)) {
                request = request.error(Glide.with(context)
                        .load(fallbackUrl)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .override(w, sThumbH)
                        .centerCrop());
            }

            request.into(mThumbnail);
        }

        private static final com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> FIRST_THUMB_LISTENER =
                new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                    @Override
                    public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e, Object model,
                            com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                            boolean isFirstResource) {
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                            com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                            com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                        com.newtube.mobile.LaunchMilestones.onFirstThumb(dataSource);
                        return false;
                    }
                };

        public void unbind() {
            mVideo = null;
            mBoundThumbUrl = null;
            Glide.with(itemView.getContext().getApplicationContext()).clear(mThumbnail);
        }
    }

    /**
     * Channel result row: round avatar (the channel item's cardImageUrl IS the avatar) +
     * name + "subscribers · N videos". Tap routes through the same click listener — the
     * presenters' onVideoItemClicked already opens the mobile channel screen for
     * {@code Video.isChannel()} items; long-press/⋮-less row keeps the context menu too.
     */
    static class ChannelViewHolder extends RecyclerView.ViewHolder {
        private final ImageView mAvatar;
        private final TextView mName;
        private final TextView mMeta;
        @Nullable private final com.newtube.mobile.ui.common.LiveRingView mLiveRing;
        private Video mVideo;

        ChannelViewHolder(@NonNull View itemView, OnVideoClickListener clickListener) {
            super(itemView);

            mAvatar = itemView.findViewById(R.id.channel_avatar);
            mName = itemView.findViewById(R.id.channel_name);
            mMeta = itemView.findViewById(R.id.channel_meta);
            mLiveRing = itemView.findViewById(R.id.channel_avatar_live);

            itemView.setOnClickListener(v -> {
                if (mVideo != null && clickListener != null) {
                    PlayerTransitionBridge.clear();
                    clickListener.onVideoClick(mVideo);
                }
            });
        }

        void bind(Video video, OnVideoLongClickListener longClickListener) {
            mVideo = video;

            itemView.setOnLongClickListener(v ->
                    mVideo != null && longClickListener != null && longClickListener.onVideoLongClick(mVideo));

            mName.setText(video.getTitle());

            // secondTitle = subscriber count, badge = "N videos" (see MediaItemImpl mapping).
            CharSequence subs = video.getSecondTitle();
            String videosCount = video.badge;
            StringBuilder meta = new StringBuilder();
            if (subs != null && subs.length() > 0) {
                meta.append(subs);
            }
            if (videosCount != null && !videosCount.isEmpty()) {
                if (meta.length() > 0) {
                    meta.append(com.newtube.mobile.ui.common.MetaSeparator.DOT);
                }
                meta.append(videosCount);
            }
            mMeta.setText(meta);
            mMeta.setVisibility(meta.length() == 0 ? View.GONE : View.VISIBLE);

            com.newtube.mobile.ui.common.ChannelAvatars.put(itemView.getContext(), video.channelId,
                    video.getTitle(), video.getCardImageUrl()); // MEETUBE(card avatars)
            Glide.with(itemView.getContext())
                    .load(video.getCardImageUrl())
                    .circleCrop()
                    .placeholder(R.drawable.ic_watch_channel_placeholder)
                    .error(R.drawable.ic_watch_channel_placeholder)
                    .into(mAvatar);
            com.newtube.mobile.ui.common.LiveRingView.bind(mLiveRing, mAvatar,
                    com.newtube.mobile.ui.common.LiveRingView.isLive(video.channelId), 0); // MEETUBE(issue #73)
        }

        void unbind() {
            mVideo = null;
            Glide.with(itemView.getContext().getApplicationContext()).clear(mAvatar);
        }
    }
}
