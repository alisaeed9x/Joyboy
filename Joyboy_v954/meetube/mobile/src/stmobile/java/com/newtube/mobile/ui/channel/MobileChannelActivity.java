package com.newtube.mobile.ui.channel;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.tabs.TabLayout;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelView;
import com.liskovsoft.smartyoutubetv2.common.utils.LoadFailure;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.VideoCardAdapter;
import com.newtube.mobile.ui.common.FilteredPageTopUp;
import com.newtube.mobile.ui.common.Glass;
import com.newtube.mobile.ui.common.GlassView;
import com.newtube.mobile.ui.common.MobileActivity;
import com.newtube.mobile.ui.common.Motion;
import com.newtube.mobile.ui.common.ShortsFilter;
import com.newtube.mobile.ui.common.SkeletonReveal;
import com.newtube.mobile.ui.playback.MiniPlayerBridge;
import com.newtube.mobile.ui.playback.MobileMiniPlayerController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Touch Channel page (Wave 4a; tabbed 2026-07-09).
 *
 * <p>The touch replacement for the Leanback {@code ChannelFragment}/{@code ChannelActivity}.
 * {@link ChannelView} delivers MULTIPLE {@link VideoGroup}s (one per channel section:
 * Videos, Live, Playlists, Shorts, ...). YouTube-style but pure Material: each section is a
 * {@link TabLayout} tab, and the selected section renders below as the standard full-width
 * card feed (the same {@link VideoCardAdapter} as Home/Search) — no nested carousels, no
 * interleaved mega-list.</p>
 *
 * <p>Drives the unchanged {@link ChannelPresenter} via the standard MVP seam. Routing is
 * fully natural: tapping a plain video plays it ({@code MobilePlaybackActivity}); tapping a
 * playlist/sub-channel item opens {@code MobileChannelUploadsActivity}/this screen again
 * (via {@code VideoActionPresenter.apply()}); long-press/⋮ shows the context menu through
 * the Wave-3 {@code MobileAppDialogActivity}.</p>
 */
public class MobileChannelActivity extends MobileActivity
        implements ChannelView, MiniPlayerBridge.MiniHost {
    private static final int SCROLL_END_THRESHOLD_ITEMS = 6;

    /** A channel section, keyed by its {@link VideoGroup#getId()} so continuations/replacements
     *  for the same row merge into it instead of creating a duplicate tab. */
    private static final class Section {
        final int id;
        String title;
        final List<Video> videos = new ArrayList<>();
        /** NEWTUBE(shorts): pages this tab fetches itself when its dropped Shorts left it short. */
        final FilteredPageTopUp topUp = new FilteredPageTopUp();
        /**
         * NEWTUBE(shorts): the group this tab pages - its last video (a Short, maybe) is the anchor
         * for the next page, so a tab whose cards were all Shorts can still continue.
         */
        VideoGroup group;
        /** MEETUBE(shorts): the channel's Shorts tab - its shorts stay, as tall cards. */
        boolean shorts;
        /** Issue #50: where the tab was left; picking it again goes back there. */
        @Nullable
        Parcelable scroll;

        Section(int id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    private ChannelPresenter mPresenter;

    private RecyclerView mGrid;
    private GridLayoutManager mLayoutManager;
    private VideoCardAdapter mAdapter;
    private TabLayout mTabs;
    /** The tabs' place in the app bar; the tabs themselves float over the page (setupGlassBars). */
    private View mTabsSpace;
    private TextView mSubscribe;
    private ProgressBar mProgressBar;
    private TextView mTitleView;
    /** The header holds a real channel name (from the card, or from the loaded videos). */
    private boolean mTitleFromCard;
    private ImageButton mBackButton;
    /** MEETUBE(channel sort, issue #5): ChannelPresenter's sorted page arrives as this group id. */
    private static final int SORTED_GROUP_ID = 144;
    /** The tab the sort was asked from: the sorted videos replace its contents. */
    private int mSortTargetId = -1;
    private MobileMiniPlayerController mMiniPlayer;
    private boolean mAnimateMiniFromPlayer;
    private SwipeRefreshLayout mSwipe;
    /** First page failed / came back empty (see {@link #showLoadFailure}). */
    private PageLoadState mLoadState;
    /** "Couldn't load more" row under the section whose next page failed. */
    private LoadMoreFailureAdapter mLoadMoreFooter;
    /** Section whose next page failed; its scroll-end paging is suspended until Try again. */
    private int mLoadMoreFailedSectionId = -1;
    /** Section the last next-page request was made for (a failure is reported without it). */
    private int mPagingSectionId = -1;
    /** Pull-to-refresh: re-select this tab when a section with this title comes back. */
    private String mRestoreSectionTitle;

    /** Sections in delivery order; iteration order == tab order. */
    private final Map<Integer, Section> mSections = new LinkedHashMap<>();
    /** NEWTUBE(shorts): sections arrived but the Shorts filter left nothing of them (#63). */
    private boolean mShortsEmptied;
    /** Group id of the section shown in the grid (its tab is selected). */
    private int mActiveSectionId = -1;
    private int mLastPaginationTriggerCount = -1;
    /** Guards against the tab-selected listener reacting to programmatic tab sync. */
    private boolean mSuppressTabCallback;
    /** NEWTUBE(shorts): onDestroy has begun - the presenter's teardown must not page on. */
    private boolean mTornDown;
    /** The neighbouring tab's cards, slid in beside the grid during a sideways swipe. */
    private RecyclerView mPeek;
    private VideoCardAdapter mPeekAdapter;
    /** Tab index {@link #mPeek} shows, -1 = none yet this swipe. */
    private int mPeekTab = -1;
    /** A swipe just selected a tab: {@link #mPeek} covers the grid until its cards are drawn. */
    private boolean mPeekSwapPending;
    private ValueAnimator mSwipeAnim;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_mobile_channel);

        registerBackHandler(this::handleBack);

        bindViews();
        mMiniPlayer = new MobileMiniPlayerController(this);
        mAnimateMiniFromPlayer = MiniPlayerBridge.completePendingNavigation();
        if (mAnimateMiniFromPlayer) {
            // The live card supplies the spatial transition from the watch page; suppress the
            // channel window's generic alpha so the two motions do not stack.
            overridePendingTransition(0, 0);
        }
        setupGrid();
        setupTabs();
        setupSwipeRefresh();
        setupGlassBars();
        setupNavPill();

        mBackButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.mobile_channel_sort).setOnClickListener(v -> {
            if (mPresenter != null && mActiveSectionId != -1) {
                mSortTargetId = mActiveSectionId;
                mPresenter.onSearchSettingsClicked(sortChannelId()); // the Latest / Popular / Oldest picker
            }
        });

        mPresenter = ChannelPresenter.instance(this);

        applyChannelTitle();
        loadHeader();

        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    /** MEETUBE(channel header, issue #10): the channel's UC id the header shows, once asked for. */
    @Nullable
    private String mHeaderChannelId;
    /** A link-opened channel's "@handle" and, once resolved, its UC id. */
    @Nullable
    private String mHandle;
    @Nullable
    private String mHandleChannelId;

    /** Fill the header once the channel's UC id is known (a link-opened one: from its videos). */
    private void loadHeader() {
        String id = sortChannelId();
        if (id != null && id.startsWith("@") && !id.equals(mHandle)) {
            mHandle = id;
            ChannelHeader.resolveHandle(id, channelId -> {
                if (!isDestroyed() && id.equals(mHandle)) {
                    mHandleChannelId = channelId;
                    loadHeader();
                    for (Section section : mSections.values()) {
                        if (section.shorts) {
                            withShortsViews(section.videos);
                        }
                    }
                }
            });
        }
        if (id == null || !id.startsWith("UC") || id.equals(mHeaderChannelId)) {
            return;
        }
        mHeaderChannelId = id;
        findViewById(R.id.mobile_channel_header).setVisibility(View.GONE);
        // Its place is kept (no jump) until this channel's state is known; signed out, no pill.
        mSubscribe.animate().cancel();
        mSubscribe.setVisibility(com.liskovsoft.youtubeapi.service.YouTubeSignInService.instance().isSigned()
                ? View.INVISIBLE : View.GONE);
        mSubscribeStateFor = null;
        mSubscribeTried.clear();
        // A link's channel is named (resolveHandle) after its videos may have arrived: ask with them.
        loadSubscribeState(mSections.isEmpty() ? null : mSections.values().iterator().next().videos);
        ChannelHeader.fetch(id, info -> {
            if (!isDestroyed() && id.equals(mHeaderChannelId)) {
                bindHeader(id, info);
            }
        });
    }

    private void bindHeader(String channelId, ChannelHeader.Info info) {
        View header = findViewById(R.id.mobile_channel_header);
        ((TextView) findViewById(R.id.mobile_channel_name)).setText(info.title);
        setTextOrGone(R.id.mobile_channel_handle, info.handle);
        setTextOrGone(R.id.mobile_channel_stats, info.stats);
        TextView description = setTextOrGone(R.id.mobile_channel_description, info.description);
        description.setMaxLines(2);
        description.setOnClickListener(v -> description.setMaxLines(
                description.getMaxLines() == 2 ? Integer.MAX_VALUE : 2)); // tap: the rest, in place

        com.bumptech.glide.RequestManager glide = com.bumptech.glide.Glide.with(this);
        com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions fade =
                com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(250);
        android.widget.ImageView avatar = findViewById(R.id.mobile_channel_avatar);
        avatar.setImageDrawable(com.newtube.mobile.ui.common.ChannelAvatars.placeholder(info.title));
        if (info.avatarUrl != null) {
            com.newtube.mobile.ui.common.ChannelAvatars.put(this, channelId, info.title, info.avatarUrl);
            glide.load(info.avatarUrl).circleCrop().placeholder(avatar.getDrawable()).transition(fade).into(avatar);
        }
        // MEETUBE(issue #73): live now - YouTube's red ring and badge; a tap on the avatar watches the stream.
        boolean live = info.liveVideoId != null;
        if (live) {
            com.newtube.mobile.ui.common.LiveRingView.markLive(channelId);
        }
        com.newtube.mobile.ui.common.LiveRingView.bind(findViewById(R.id.mobile_channel_avatar_ring), avatar, live, 0);
        setTextOrGone(R.id.mobile_channel_avatar_live, info.liveBadge);
        avatar.setOnClickListener(live ? v -> com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter
                .instance(this).openVideo(info.liveVideoId) : null);
        avatar.setClickable(live);
        findViewById(R.id.mobile_channel_banner_frame).setVisibility(info.bannerUrl != null ? View.VISIBLE : View.GONE);
        if (info.bannerUrl != null) {
            glide.load(info.bannerUrl).transition(fade).into((android.widget.ImageView) findViewById(R.id.mobile_channel_banner));
        }
        // The wash: the banner (or avatar) decoded tiny and stretched - the upscale is the blur.
        String wash = info.bannerUrl != null ? info.bannerUrl : info.avatarUrl;
        android.widget.ImageView washView = findViewById(R.id.mobile_channel_header_wash);
        if (wash != null) {
            glide.load(wash).override(24).transition(fade).into(washView);
        } else {
            glide.clear(washView);
        }
        if (!mTitleFromCard) {
            mTitleFromCard = true;
            mTitleView.setText(info.title);
        }
        if (header.getVisibility() != View.VISIBLE) {
            header.setVisibility(View.VISIBLE);
            header.setAlpha(0f);
            header.animate().alpha(1f).setDuration(220).start();
        }
    }

    /** MEETUBE(channel subscribe): the channel whose subscribed state was asked for, once. */
    @Nullable
    private String mSubscribeStateFor;
    @Nullable
    private io.reactivex.rxjava3.disposables.Disposable mSubscribeAction;
    /** Videos whose watch data named another channel (collabs), for {@link #mSubscribeStateFor}. */
    private final java.util.Set<String> mSubscribeTried = new java.util.HashSet<>();
    private static final int SUBSCRIBE_STATE_TRIES = 3;

    /**
     * The header's data (anonymous) has no subscribed state, but a video's watch data does. Opened
     * from the watch page or Shorts, that video already knows it; else the video it was opened
     * from, or the channel's first upload, is asked - as the card menu's Subscribe learns it. A
     * collab's watch data may be the other channel's: then the next upload is asked.
     */
    private void loadSubscribeState(@Nullable List<Video> videos) {
        String id = mHeaderChannelId;
        if (id == null || id.equals(mSubscribeStateFor) || mSubscribeTried.size() >= SUBSCRIBE_STATE_TRIES
                || !com.liskovsoft.youtubeapi.service.YouTubeSignInService.instance().isSigned()) {
            return;
        }
        Video from = mPresenter.getChannel();
        if (from != null && id.equals(from.channelId) && from.isSynced) {
            mSubscribeStateFor = id;
            showSubscribed(from.isSubscribed);
            return;
        }
        String videoId = from != null && id.equals(from.channelId) && !mSubscribeTried.contains(from.videoId)
                ? from.videoId : null;
        for (int i = 0; videoId == null && videos != null && i < videos.size(); i++) {
            String candidate = videos.get(i).videoId;
            videoId = mSubscribeTried.contains(candidate) ? null : candidate;
        }
        if (videoId == null) {
            return;
        }
        mSubscribeStateFor = id;
        String asked = videoId;
        com.liskovsoft.sharedutils.rx.RxHelper.execute(mediaItems().getMetadataObserve(videoId),
                metadata -> runOnUiThread(() -> {
                    if (!id.equals(mHeaderChannelId)) {
                        return;
                    }
                    if (id.equals(metadata.getChannelId())) {
                        showSubscribed(metadata.isSubscribed());
                    } else {
                        mSubscribeTried.add(asked);
                        mSubscribeStateFor = null;
                        loadSubscribeState(videos);
                    }
                }),
                error -> runOnUiThread(() -> mSubscribeStateFor = null));
    }

    private void showSubscribed(boolean on) {
        if (mSubscribe.getVisibility() != View.VISIBLE) {
            mSubscribe.setAlpha(0f);
            mSubscribe.setVisibility(View.VISIBLE);
            mSubscribe.animate().alpha(1f).setDuration(150).start();
        }
        mSubscribe.setSelected(on); // bg_watch_subscribe: solid pill, glass once subscribed
        mSubscribe.setText(on ? R.string.mobile_watch_subscribed : R.string.mobile_watch_subscribe);
        mSubscribe.setTextColor(getResources().getColor(on
                ? R.color.mobile_color_on_surface : R.color.mobile_color_on_inverse_surface));
    }

    /** The watch page's Subscribe: flips at once with its splash, put back if YouTube refuses. */
    private void onSubscribeTapped() {
        String id = mHeaderChannelId;
        if (id == null) {
            return;
        }
        boolean subscribe = !mSubscribe.isSelected();
        showSubscribed(subscribe);
        com.newtube.mobile.ui.common.Haptics.click(mSubscribe);
        if (subscribe) {
            com.newtube.mobile.ui.common.Burst.play(mSubscribe, getResources().getColor(R.color.mobile_color_on_surface));
            Motion.pop(mSubscribe);
        }
        com.liskovsoft.sharedutils.rx.RxHelper.disposeActions(mSubscribeAction);
        mSubscribeAction = com.liskovsoft.sharedutils.rx.RxHelper.execute(
                subscribe ? mediaItems().subscribeObserve(id) : mediaItems().unsubscribeObserve(id),
                error -> runOnUiThread(() -> {
                    if (id.equals(mHeaderChannelId)) {
                        showSubscribed(!subscribe);
                    }
                    com.newtube.mobile.ui.common.MobileSnackbar.show(this,
                            subscribe ? R.string.subscribe_failed : R.string.unsubscribe_failed);
                }),
                () -> runOnUiThread(() -> {
                    Video from = mPresenter.getChannel();
                    if (from != null && id.equals(from.channelId)) {
                        from.isSubscribed = subscribe; // or reopening from it shows the old state
                    }
                    com.newtube.mobile.ui.playback.WatchActionFeedback.confirmSubscription(
                            this, subscribe, mTitleView.getText().toString(), () -> {
                                if (id.equals(mHeaderChannelId) && mSubscribe.isSelected() == subscribe) {
                                    onSubscribeTapped();
                                }
                            });
                }));
        // NEWTUBE(reopen-related): kept watch-page documents carry the old subscribe state.
        com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.SuggestionsController.onUserStateChanged();
    }

    private static com.liskovsoft.mediaserviceinterfaces.MediaItemService mediaItems() {
        return com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().getMediaItemService();
    }

    private TextView setTextOrGone(int id, @Nullable String text) {
        TextView view = findViewById(id);
        view.setText(text);
        view.setVisibility(text != null ? View.VISIBLE : View.GONE);
        return view;
    }

    /**
     * Prefer the channel/author name (e.g. "Ibai") over the source item's own title (which, when
     * a channel is opened from a video, is that video's title). Mirrors the TV ChannelFragment
     * header (Helpers.firstNonNull(author, title)). NEWTUBE: a channel opened from a link has no
     * card; keep the default header until {@link #update} names it from the loaded videos.
     */
    private void applyChannelTitle() {
        Video channel = mPresenter != null ? mPresenter.getChannel() : null;
        String name = channel != null ? Helpers.firstNonNull(channel.getAuthor(), channel.getTitle()) : null;
        mTitleFromCard = name != null;
        mTitleView.setText(name != null ? name : getString(R.string.header_channels));
    }

    /** The channel's UC id: the presenter's, or (opened from an @handle link) its videos'. */
    @Nullable
    private String sortChannelId() {
        String id = mPresenter.getChannelId();
        if (id != null && id.startsWith("UC")) {
            return id;
        }
        if (id != null && id.equals(mHandle) && mHandleChannelId != null) {
            return mHandleChannelId; // a link's @handle, resolved (loadHeader)
        }
        // The id most of its videos carry: the first may be a collab, named for the other channel.
        Map<String, Integer> counts = new HashMap<>();
        String best = null;
        for (Section section : mSections.values()) {
            for (Video video : section.videos) {
                if (video.channelId != null && video.channelId.startsWith("UC")) {
                    int count = counts.merge(video.channelId, 1, Integer::sum);
                    if (best == null || count > counts.get(best)) {
                        best = video.channelId;
                    }
                }
            }
        }
        return best != null ? best : id;
    }

    /** NEWTUBE: name a link-opened channel once its first videos arrive. */
    private void maybeNameFromVideos(@Nullable List<Video> videos) {
        if (mTitleFromCard || videos == null) {
            return;
        }
        List<String> authors = new ArrayList<>();
        for (Video video : videos) {
            if (video != null) {
                authors.add(video.getAuthor());
            }
        }
        String name = ChannelTitle.fromAuthors(authors);
        if (name != null) {
            mTitleFromCard = true;
            mTitleView.setText(name);
        }
    }

    private void bindViews() {
        mGrid = findViewById(R.id.mobile_channel_grid);
        // NEWTUBE(mini-inset): the last row can scroll clear of the docked mini-player card.
        com.newtube.mobile.ui.playback.MiniPlayerListInset.attach(findViewById(R.id.mobile_mini_player), mGrid);
        mTabs = findViewById(R.id.mobile_channel_tabs);
        mTabsSpace = findViewById(R.id.mobile_channel_tabs_space);
        mSubscribe = findViewById(R.id.mobile_channel_subscribe);
        mSubscribe.setOnClickListener(v -> onSubscribeTapped());
        mPeek = findViewById(R.id.mobile_channel_peek);
        mProgressBar = findViewById(R.id.mobile_channel_progress);
        mTitleView = findViewById(R.id.mobile_channel_title);
        mBackButton = findViewById(R.id.mobile_channel_back);
        mSwipe = findViewById(R.id.mobile_channel_swipe);
        mLoadState = new PageLoadState(findViewById(R.id.mobile_page_load_state), this::retryFirstPage);
    }

    private void setupGrid() {
        mLayoutManager = new GridLayoutManager(this, computeSpanCount());
        mAdapter = new VideoCardAdapter(this::onVideoClicked, this::onVideoLongClicked).shortsTiles()
                .setListRows(com.newtube.mobile.ui.browse.HomeLayout.isVideoList(this));
        mLoadMoreFooter = new LoadMoreFailureAdapter(this::retryLoadMore);

        // Channel rows (rare here) span the whole grid width in multi-column layouts, and so does
        // the "Couldn't load more" row after the cards.
        mLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position >= mAdapter.getItemCount() || mAdapter.isFullSpan(position)
                        ? mLayoutManager.getSpanCount() : cardSpan(mAdapter, position);
            }
        });

        mGrid.setHasFixedSize(true);
        mGrid.setItemViewCacheSize(8);
        mGrid.setLayoutManager(mLayoutManager);
        // Cards first, so grid positions below the footer are card positions.
        mGrid.setAdapter(new ConcatAdapter(mAdapter, mLoadMoreFooter));
        // Next cards' thumbnails decoded before they scroll in (no grey card + fade on a fling).
        com.newtube.mobile.ui.common.FeedThumbnailPreloader.attach(mGrid, mAdapter);
        mGrid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                // dy == 0: a layout pass changed the visible range, not the user.
                maybeTriggerPagination(dy != 0);
            }
        });
    }

    private void setupTabs() {
        mTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (mSuppressTabCallback || !(tab.getTag() instanceof Integer)) {
                    return;
                }
                Section leaving = mSections.get(mActiveSectionId);
                if (leaving != null) {
                    leaving.scroll = mLayoutManager.onSaveInstanceState();
                }
                mActiveSectionId = (Integer) tab.getTag();
                mRestoreSectionTitle = null; // the user picked a tab; a refresh must not undo it
                showActiveSection(true);
                Section picked = mSections.get(mActiveSectionId);
                if (picked != null) {
                    picked.topUp.onUserAction(); // NEWTUBE(shorts)
                    picked.topUp.onShown();
                    topUpActiveIfShort();
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) { }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                mGrid.scrollToPosition(0);
            }
        });
        setupTabSwipe();
    }

    /**
     * Sideways swipe on the feed moves to the neighbouring tab, like YouTube's channel page: the
     * grid follows the finger with the next tab's cards ({@link #mPeek}) beside it and the tab
     * indicator in step. Past a quarter of the width (or on a fling) it settles on the next tab,
     * otherwise it springs back. A claimed swipe cancels the card's press, so it never plays.
     */
    private void setupTabSwipe() {
        mPeekAdapter = new VideoCardAdapter(video -> { }).shortsTiles().setListRows(com.newtube.mobile.ui.browse.HomeLayout.isVideoList(this));
        GridLayoutManager peekLayout = new GridLayoutManager(this, computeSpanCount());
        peekLayout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return mPeekAdapter.isFullSpan(position) ? peekLayout.getSpanCount() : cardSpan(mPeekAdapter, position);
            }
        });
        mPeek.setLayoutManager(peekLayout);
        mPeek.setAdapter(mPeekAdapter);

        ViewConfiguration config = ViewConfiguration.get(this);
        int slop = config.getScaledTouchSlop();
        int minFling = config.getScaledMinimumFlingVelocity() * 8;
        mGrid.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            private float mDownX;
            private float mDownY;
            private boolean mSwiping;
            private VelocityTracker mVelocity;

            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    mDownX = e.getX();
                    mDownY = e.getY();
                    mSwiping = false;
                    if (mVelocity != null) {
                        mVelocity.recycle();
                    }
                    mVelocity = VelocityTracker.obtain();
                }
                if (mVelocity != null) {
                    mVelocity.addMovement(e);
                }
                if (e.getActionMasked() == MotionEvent.ACTION_MOVE && mTabs.getTabCount() > 1
                        && rv.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) {
                    float dx = Math.abs(e.getX() - mDownX);
                    if (dx > slop && dx > 2 * Math.abs(e.getY() - mDownY)) {
                        mSwiping = true;
                        if (mSwipeAnim != null) {
                            mSwipeAnim.end(); // a settle still running: finish it first
                        }
                        mDownX = e.getX(); // start the grid from the finger, no jump of one slop
                        rv.getParent().requestDisallowInterceptTouchEvent(true); // no pull-to-refresh
                    }
                }
                return mSwiping;
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                mVelocity.addMovement(e);
                float dx = e.getX() - mDownX;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE:
                        setSwipeOffset(dx);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mVelocity.computeCurrentVelocity(1000);
                        float vx = e.getActionMasked() == MotionEvent.ACTION_UP ? mVelocity.getXVelocity() : 0;
                        int target = mTabs.getSelectedTabPosition() + (dx < 0 ? 1 : -1);
                        boolean fling = Math.abs(vx) > minFling && (vx < 0) == (dx < 0);
                        boolean commit = dx != 0 && target >= 0 && target < mTabs.getTabCount()
                                && (fling || Math.abs(dx) > rv.getWidth() / 4f);
                        settleSwipe(dx, commit ? Math.copySign(rv.getWidth(), dx) : 0, commit ? target : -1, vx);
                        mSwiping = false;
                        break;
                }
            }
        });
    }

    /** Grid at {@code dx}, the neighbouring tab's cards beside it, the tab indicator in step. */
    private void setSwipeOffset(float dx) {
        int width = mGrid.getWidth();
        int current = mTabs.getSelectedTabPosition();
        int target = current + (dx < 0 ? 1 : -1);
        if (dx == 0 || width == 0 || target < 0 || target >= mTabs.getTabCount()) {
            mGrid.setTranslationX(dx / 4); // rubber band at the ends
            mPeek.setVisibility(View.GONE);
            mTabs.setScrollPosition(current, 0, false);
            return;
        }

        if (mPeekTab != target) {
            TabLayout.Tab tab = mTabs.getTabAt(target);
            Section section = tab != null && tab.getTag() instanceof Integer ? mSections.get((Integer) tab.getTag()) : null;
            mPeek.animate().cancel(); // a hand-over fade from the last swipe
            mPeek.setAlpha(1f);
            mPeekAdapter.submitList(null); // null first: the next list lands at once, no async diff
            mPeekAdapter.submitList(section != null ? new ArrayList<>(section.videos) : new ArrayList<>());
            if (section != null && section.scroll != null) {
                mPeek.getLayoutManager().onRestoreInstanceState(section.scroll); // as the grid will land
            } else {
                mPeek.scrollToPosition(0);
            }
            mPeekTab = target;
        }
        mPeek.setVisibility(View.VISIBLE);
        mGrid.setTranslationX(dx);
        mPeek.setTranslationX(dx < 0 ? dx + width : dx - width);
        float fraction = Math.abs(dx) / width;
        mTabs.setScrollPosition(dx < 0 ? current : target, dx < 0 ? fraction : 1 - fraction, false);
    }

    /**
     * Glide from {@code from} to {@code to} at the finger's speed. On a new tab, mPeek (already
     * showing it, in place) covers the grid until the grid has drawn the same cards under it.
     */
    private void settleSwipe(float from, float to, int targetTab, float velocity) {
        float distance = Math.abs(to - from);
        long duration = (long) Math.min(Motion.ENTER_MS, Math.max(120, distance * 1000 / Math.max(Math.abs(velocity), 2000)));
        mSwipeAnim = ValueAnimator.ofFloat(from, to);
        mSwipeAnim.setDuration(duration);
        mSwipeAnim.setInterpolator(Motion.STANDARD_DECELERATE);
        mSwipeAnim.addUpdateListener(animation -> setSwipeOffset((float) animation.getAnimatedValue()));
        mSwipeAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mSwipeAnim = null;
                mPeekTab = -1;
                mGrid.setTranslationX(0);
                TabLayout.Tab tab = targetTab >= 0 ? mTabs.getTabAt(targetTab) : null;
                if (tab != null) {
                    mPeekSwapPending = true;
                    tab.select(); // -> showActiveSection, which hands over from mPeek
                } else {
                    mPeek.setVisibility(View.GONE);
                }
            }
        });
        mSwipeAnim.start();
    }

    private void onVideoClicked(Video video) {
        if (mPresenter == null) {
            return;
        }
        Section active = mSections.get(mActiveSectionId);
        if (video.belongsToShorts() && active != null && active.shorts) {
            ShortsFilter.queueAround(new ArrayList<>(active.videos), video); // swipe on through the tab
        }
        mPresenter.onVideoItemClicked(video);
    }

    private boolean onVideoLongClicked(Video video) {
        if (mPresenter == null) {
            return false;
        }

        mPresenter.onVideoItemLongClicked(video);
        return true;
    }

    private void setupSwipeRefresh() {
        mSwipe.setOnRefreshListener(() -> {
            com.newtube.mobile.ui.common.RefreshFeedback.play(mSwipe);
            boolean hasContent = hasContent();
            Section active = mSections.get(mActiveSectionId);
            // The refresh rebuilds the tabs; land back on the one being read.
            mRestoreSectionTitle = hasContent && active != null ? active.title : null;

            if (mPresenter == null || !mPresenter.reload(hasContent)) {
                mSwipe.setRefreshing(false);
            }
        });
    }

    /** MEETUBE(channel nav): Browse's floating tab bar over the channel, as Search has it. None without a Browse. */
    private void setupNavPill() {
        View pill = findViewById(R.id.mobile_nav_glass);
        com.google.android.material.bottomnavigation.BottomNavigationView bar = findViewById(R.id.mobile_bottom_nav);
        if (!com.newtube.mobile.ui.browse.MobileBrowseActivity.copyNavTabs(this, bar, false, () -> { })) {
            return;
        }
        Glass.blur(pill, findViewById(R.id.mobile_channel_glass_source));
        // The pill floats above the system bar already: the bar must not pad itself by it too.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(bar, (v, insets) -> insets);
        int clearance = getResources().getDimensionPixelSize(R.dimen.meetube_nav_clearance);
        for (RecyclerView list : new RecyclerView[] {mGrid, findViewById(R.id.mobile_channel_peek)}) {
            list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(), clearance);
        }
        ((android.view.ViewGroup.MarginLayoutParams) findViewById(R.id.mobile_mini_player).getLayoutParams())
                .bottomMargin = clearance;
        com.newtube.mobile.ui.common.NavHider navHider = new com.newtube.mobile.ui.common.NavHider(pill);
        navHider.setOnSlide(navY -> { // MEETUBE(issue #125): the mini card rides the nav bar, as on Home
            if (mMiniPlayer != null) { // null-safe, as Search's (whose pill comes first)
                mMiniPlayer.followNav(navY);
            }
        });
        mGrid.addOnScrollListener(navHider);
        pill.setVisibility(View.VISIBLE);
    }

    /**
     * MEETUBE(glass): the top bar and the tabs frost the page scrolling under them. The tabs float
     * outside the page (a GlassSource can't hold its own glass), so they follow their place in the
     * app bar; the header fades over its last stretch under the top bar, or its bottom strip would
     * stay there, blurred, over the cards going by.
     */
    private void setupGlassBars() {
        View source = findViewById(R.id.mobile_channel_glass_source);
        GlassView bar = findViewById(R.id.mobile_channel_bar);
        GlassView tabsBar = findViewById(R.id.mobile_channel_tabs_bar);
        Glass.blur(bar, source);
        Glass.blur(tabsBar, source);
        View header = findViewById(R.id.mobile_channel_header);
        float barHeight = getResources().getDimension(R.dimen.mobile_title_bar_height);
        int reach = Math.round(GlassView.BLUR_REACH_DP * getResources().getDisplayMetrics().density);
        ((AppBarLayout) findViewById(R.id.mobile_channel_appbar)).addOnOffsetChangedListener((appBar, offset) -> {
            float tabsY = mTabsSpace.getTop() + offset;
            tabsBar.setTranslationY(tabsY);
            header.setAlpha(Math.min(1f, (appBar.getTotalScrollRange() + offset) / barHeight));
            // Pinned, the two bars touch: each blurs into the other so they read as one, no line.
            boolean touching = tabsY <= 0 && mTabsSpace.getVisibility() == View.VISIBLE;
            bar.setBlurReach(0, touching ? reach : 0);
            tabsBar.setBlurReach(touching ? reach : 0, 0);
        });
        // The cards start 104dp (bar + tabs) up, under the glass: so does the refresh spinner.
        int under = Math.round(104 * getResources().getDisplayMetrics().density);
        mSwipe.setProgressViewOffset(false, mSwipe.getProgressViewStartOffset() + under,
                mSwipe.getProgressViewEndOffset() + under);
    }

    /** Failure state's Try again: the same first load, from a blank page. */
    private void retryFirstPage() {
        if (mPresenter != null) {
            mPresenter.reload(false);
        }
    }

    /** Footer's Try again: re-ask for the page that failed (its continuation key is unchanged). */
    private void retryLoadMore() {
        mLoadMoreFailedSectionId = -1;
        syncLoadMoreFooter();

        Section active = mSections.get(mActiveSectionId);
        Video anchor = active != null ? anchorOf(active) : null;
        if (anchor == null || mPresenter == null) {
            return;
        }

        mPagingSectionId = active.id;
        mLastPaginationTriggerCount = mAdapter.getItemCount();
        mPresenter.onScrollEnd(anchor);
    }

    /** The footer belongs to the section whose page failed; other tabs page normally. */
    private void syncLoadMoreFooter() {
        mLoadMoreFooter.setFailed(mLoadMoreFailedSectionId != -1 && mLoadMoreFailedSectionId == mActiveSectionId);
    }

    private boolean hasContent() {
        for (Section section : mSections.values()) {
            if (!section.videos.isEmpty()) {
                return true;
            }
        }

        return false;
    }

    private void hideLoadState() {
        mLoadState.hide();
        mGrid.setVisibility(View.VISIBLE);
    }

    private void maybeTriggerPagination(boolean userScroll) {
        Section active = mSections.get(mActiveSectionId);
        Video anchor = active != null ? anchorOf(active) : null;
        if (active == null || active.videos.isEmpty() || anchor == null || mPresenter == null) {
            return;
        }

        // Its next page failed: wait for Try again instead of re-firing on every scroll frame.
        if (mLoadMoreFailedSectionId == active.id) {
            return;
        }

        int lastVisible = mLayoutManager.findLastVisibleItemPosition();
        int itemCount = mAdapter.getItemCount();

        if (lastVisible == RecyclerView.NO_POSITION || itemCount == 0) {
            return;
        }

        if (lastVisible >= itemCount - SCROLL_END_THRESHOLD_ITEMS && itemCount != mLastPaginationTriggerCount) {
            if (userScroll) {
                active.topUp.onUserAction(); // NEWTUBE(shorts): the user asked for more
            } else if (!active.topUp.takeAutomatic(hasMore(anchor))) {
                return; // a layout pass, not the user: only within the top-up budget
            }
            mLastPaginationTriggerCount = itemCount;
            mPagingSectionId = active.id;
            // Continues the ACTIVE section; the continuation arrives as an ACTION_APPEND
            // VideoGroup with the same id and merges back into it.
            mPresenter.onScrollEnd(anchor);
        }
    }

    /** The video the section's next page continues from: the last of its group, Shorts included. */
    private static Video anchorOf(Section section) {
        List<Video> raw = section.group != null ? section.group.getVideos() : null;
        if (raw != null && !raw.isEmpty()) {
            return raw.get(raw.size() - 1);
        }
        return section.videos.isEmpty() ? null : section.videos.get(section.videos.size() - 1);
    }

    private static boolean hasMore(Video anchor) {
        return anchor != null && anchor.getGroup() != null && anchor.getGroup().getNextPageKey() != null;
    }

    /**
     * NEWTUBE(shorts): with its Shorts dropped the tab on screen may be too short to scroll, and
     * then nothing would ever ask for its next page: fetch it now, within the tab's
     * {@link FilteredPageTopUp} budget - only right after one of its pages landed (not after a
     * cancel or a failure) or when its tab is picked. Returns whether a page was asked for.
     */
    private boolean topUpActiveIfShort() {
        Section active = mSections.get(mActiveSectionId);
        Video anchor = active != null ? anchorOf(active) : null;
        if (active == null || anchor == null || mPresenter == null || mTornDown || isFinishing()
                || isDestroyed() || mLoadMoreFailedSectionId == active.id) {
            return false;
        }
        if (!active.topUp.take(active.videos.size(), hasMore(anchor))) {
            return false;
        }
        mPagingSectionId = active.id;
        com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log("channel-topup section=" + active.id
                + " page=" + active.topUp.pages() + " items=" + active.videos.size());
        mPresenter.onScrollEnd(anchor);
        return true;
    }

    /**
     * MEETUBE(shorts): the channel's Shorts tab - its shorts open the Shorts player, as YouTube's
     * tab does (a tile that only opens a Shorts list still goes).
     */
    private static List<Video> shortsOf(@Nullable List<Video> videos) {
        List<Video> shorts = new ArrayList<>();
        if (videos != null) {
            for (Video video : videos) {
                if (video.isShorts && video.hasVideo()) {
                    video.markShorts();
                    shorts.add(video);
                }
            }
        }
        return shorts;
    }

    /** MEETUBE(shorts): the Shorts tab's view counts by video id, for {@link #mShortsViewsFor}. */
    private final Map<String, String> mShortsViews = new HashMap<>();
    @Nullable
    private String mShortsViewsFor;

    /** The tiles show "26K views" (their info line); the counts come from ChannelHeader once per channel. */
    private void withShortsViews(List<Video> shorts) {
        String id = sortChannelId();
        if (id != null && id.startsWith("UC") && !id.equals(mShortsViewsFor)) {
            mShortsViewsFor = id;
            mShortsViews.clear();
            ChannelHeader.fetchShortsViews(id, views -> {
                if (!id.equals(mShortsViewsFor)) {
                    return;
                }
                mShortsViews.putAll(views);
                for (Section section : mSections.values()) {
                    if (section.shorts) {
                        applyShortsViews(section.videos);
                        mAdapter.refreshItems(section.videos);
                    }
                }
            });
        }
        applyShortsViews(shorts);
    }

    private void applyShortsViews(List<Video> shorts) {
        for (Video video : shorts) {
            String views = mShortsViews.get(video.videoId);
            if (views != null) {
                video.secondTitle = views;
            }
        }
    }

    /** MEETUBE(shorts): every column is three spans wide, so the Shorts tab's tiles sit three to a column. */
    private static final int SHORTS_PER_COLUMN = 3;

    private static int cardSpan(VideoCardAdapter adapter, int position) {
        return adapter.isShort(position) ? 1 : SHORTS_PER_COLUMN;
    }

    private int computeSpanCount() {
        return SHORTS_PER_COLUMN * com.newtube.mobile.ui.common.MobileGrid.computeSpanCount(this);
    }

    /** Sync the TabLayout with the current sections (delivery order), keeping the selection. */
    private void rebuildTabs() {
        mSuppressTabCallback = true;

        // Add missing tabs / fix titles in place; section order only ever grows by append.
        int index = 0;
        for (Section section : mSections.values()) {
            String title = section.title != null && !section.title.isEmpty() ? section.title
                    : getString(ChannelTabShortcuts.isShortcutRow(section.videos) // NEWTUBE(#63)
                            ? R.string.mobile_channel_tab_more : R.string.mobile_channel_tab_videos);

            TabLayout.Tab tab = index < mTabs.getTabCount() ? mTabs.getTabAt(index) : null;
            if (tab == null) {
                tab = mTabs.newTab();
                mTabs.addTab(tab, false);
            }
            if (!(tab.getTag() instanceof Integer) || (Integer) tab.getTag() != section.id) {
                tab.setTag(section.id);
            }
            if (!title.contentEquals(tab.getText() != null ? tab.getText() : "")) {
                tab.setText(title);
            }

            if (section.id == mActiveSectionId && !tab.isSelected()) {
                tab.select();
            }
            index++;
        }
        while (mTabs.getTabCount() > mSections.size()) {
            mTabs.removeTabAt(mTabs.getTabCount() - 1);
        }

        // Tab bar only earns its space with 2+ sections; a single section shows as a plain feed.
        mTabs.setVisibility(mSections.size() > 1 ? View.VISIBLE : View.GONE);
        mTabsSpace.setVisibility(mTabs.getVisibility());

        mSuppressTabCallback = false;
    }

    /** Restored once the picked tab's cards are in the adapter (a layout before would drop it). */
    @Nullable
    private Parcelable mPendingScroll;

    /**
     * Push the active section's videos into the grid; {@code restore}: at the place the tab was
     * left, the top if it never was.
     */
    private void showActiveSection(boolean restore) {
        Section active = mSections.get(mActiveSectionId);
        mLastPaginationTriggerCount = -1;
        syncLoadMoreFooter();
        if (restore) {
            mPendingScroll = active != null ? active.scroll : null;
        }
        mAdapter.submitList(active != null ? new ArrayList<>(active.videos) : new ArrayList<>(), () -> {
            if (mPendingScroll != null) {
                mLayoutManager.onRestoreInstanceState(mPendingScroll);
                mPendingScroll = null;
            }
            if (mPeekSwapPending) {
                mPeekSwapPending = false;
                SkeletonReveal.fadeOverCards(mPeek, mGrid, () -> true, () -> {
                    mPeek.setVisibility(View.GONE);
                    mPeek.setAlpha(1f);
                });
            }
        });
        if (restore && mPendingScroll == null) {
            mGrid.scrollToPosition(0);
        }
    }

    // ---------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();

        // REORDER_TO_FRONT may reuse an existing channel Activity, bypassing onCreate entirely.
        // Complete a watch-page channel handoff here as well so that path still becomes mini mode.
        if (MiniPlayerBridge.completePendingNavigation()) {
            mAnimateMiniFromPlayer = true;
            overridePendingTransition(0, 0);
        }

        if (mPresenter != null) {
            mPresenter.onViewResumed();
        }
        // Last-resumed host wins: a video opened from this channel minimizes back onto it.
        MiniPlayerBridge.registerMiniHost(this);
        if (mMiniPlayer != null) {
            mMiniPlayer.sync(mAnimateMiniFromPlayer);
            mAnimateMiniFromPlayer = false;
        }
    }

    @Override
    public boolean prepareMiniPlayerForHandoff(Runnable onDrawn) {
        return mMiniPlayer != null && mMiniPlayer.prepareForHandoff(onDrawn);
    }

    @Override
    public Class<?> getMiniHostViewClass() {
        return ChannelView.class;
    }

    @Override
    public int getMiniCardBottomOffsetPx() {
        // Overlay card (mobile_mini_player_overlay.xml): its 12dp margin, the morph's own, or clear of
        // the nav pill (setupNavPill), as Search's. Flush, the swipe-down aimed the video under the
        // pill and it jumped up on landing.
        View card = findViewById(R.id.mobile_mini_player);
        int margin = ((android.view.ViewGroup.MarginLayoutParams) card.getLayoutParams()).bottomMargin;
        // MEETUBE(issue #125): lower by the card's home offset while a scroll has the nav away.
        float homeY = mMiniPlayer != null ? mMiniPlayer.homeY() : 0f;
        return Math.max(0, margin - Math.round(12 * getResources().getDisplayMetrics().density + homeY));
    }

    @Override
    protected void onPause() {
        // NEWTUBE(motion): a docked card stays up frozen until the player covers it.
        if (mMiniPlayer != null) {
            mMiniPlayer.onHostPause();
        }
        super.onPause();

        if (mPresenter != null) {
            mPresenter.onViewPaused();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mMiniPlayer != null) {
            mMiniPlayer.onHostStop();
        }
    }

    @Override
    protected void onDestroy() {
        MiniPlayerBridge.unregisterMiniHost(this);
        mTornDown = true;

        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onViewDestroyed();
        }

        super.onDestroy();
    }

    private void handleBack() {
        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onFinish();
        }

        finish();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (mLayoutManager != null) {
            int spans = SHORTS_PER_COLUMN * com.newtube.mobile.ui.common.MobileGrid.computeSpanCount(newConfig);
            mLayoutManager.setSpanCount(spans);
            ((GridLayoutManager) mPeek.getLayoutManager()).setSpanCount(spans);
        }
    }

    // ---------------------------------------------------------------------------------
    // ChannelView
    // ---------------------------------------------------------------------------------

    @Override
    public void update(VideoGroup group) {
        if (group == null) {
            return;
        }

        runOnUiThread(() -> {
            int id = group.getId();
            if (id == SORTED_GROUP_ID && group.getAction() != VideoGroup.ACTION_REPLACE
                    && mSections.containsKey(mSortTargetId)) {
                id = mSortTargetId; // MEETUBE(channel sort): the sorted list's next pages
            }
            Section section = mSections.get(id);
            boolean isNewSection = section == null;
            Section owner = section != null ? section : id == SORTED_GROUP_ID ? mSections.get(mSortTargetId) : null;
            boolean shortsTab = owner != null ? owner.shorts
                    : ShortsFilter.isShortsSection(group.getTitle(), getString(R.string.header_shorts), group.getVideos());
            List<Video> shown = shortsTab ? shortsOf(group.getVideos())
                    : ShortsFilter.withoutShorts(group.getVideos()); // NEWTUBE(shorts)
            if (id == SORTED_GROUP_ID && group.getAction() == VideoGroup.ACTION_REPLACE) {
                Section target = mSections.get(mSortTargetId);
                if (target != null && shown != null) {
                    target.videos.clear();
                    target.videos.addAll(shown);
                    target.group = group; // further pages continue the sorted list
                    target.scroll = null; // a new order starts at the top
                    mActiveSectionId = target.id;
                    rebuildTabs();
                    showActiveSection(true);
                }
                return;
            }
            boolean content = group.getAction() != VideoGroup.ACTION_REMOVE && group.getAction() != VideoGroup.ACTION_SYNC;
            if (content) {
                loadSubscribeState(shown);
                if (shortsTab) {
                    withShortsViews(shown);
                }
            }
            int shortsDropped = (group.getVideos() != null ? group.getVideos().size() : 0)
                    - (shown != null ? shown.size() : 0);
            mShortsEmptied |= content && shortsDropped > 0;
            // The channel's Shorts section keeps its shorts (MEETUBE: the Shorts tab), unless the
            // service already emptied it under a stored "Hide shorts from a channel". Any other
            // section keeps its tab while it has more to load, even if this page was all Shorts
            // (its last Short is the next page's anchor); one with nothing now and nothing more to
            // load, or nothing to continue from, is left out.
            if (isNewSection && content && (shown == null || shown.isEmpty())
                    && (ShortsFilter.isShortsSection(group.getTitle(), getString(R.string.header_shorts), group.getVideos())
                        || group.getNextPageKey() == null || group.isEmpty())) {
                return;
            }

            if (content) {
                maybeNameFromVideos(group.getVideos());
                mGrid.post(this::loadHeader); // a link-opened channel's id comes with its videos
            }

            switch (group.getAction()) {
                case VideoGroup.ACTION_REPLACE:
                    section = new Section(id, group.getTitle());
                    section.videos.addAll(shown);
                    mSections.put(id, section);
                    break;
                case VideoGroup.ACTION_REMOVE:
                    if (section != null) {
                        section.videos.removeAll(group.getVideos());
                    }
                    break;
                case VideoGroup.ACTION_SYNC:
                    if (section != null) {
                        syncVideos(section, group.getVideos());
                    }
                    // NEWTUBE(#45): a SYNC item was mutated in place (Original titles, DeArrow),
                    // so submitList() sees the same objects and never rebinds - rebind it here.
                    mAdapter.refreshItems(group.getVideos());
                    break;
                case VideoGroup.ACTION_PREPEND:
                    if (section == null) {
                        section = new Section(id, group.getTitle());
                        mSections.put(id, section);
                    }
                    section.videos.addAll(0, shown);
                    break;
                case VideoGroup.ACTION_APPEND:
                default:
                    if (section == null) {
                        section = new Section(id, group.getTitle());
                        mSections.put(id, section);
                    } else if ((section.title == null || section.title.isEmpty()) && group.getTitle() != null) {
                        section.title = group.getTitle();
                    }
                    appendNew(section, shown);
                    break;
            }

            if (content && section != null) {
                section.shorts = shortsTab;
                section.group = group; // NEWTUBE(shorts): the next page's anchor
                section.topUp.onPageLanded(shortsDropped);
            }

            // A SYNC/REMOVE for an unknown section (e.g. the just-watched video's position
            // sync arriving on resume before any section loaded) must not touch the map —
            // guard the empty case or iterator().next() throws.
            if (mSections.isEmpty()) {
                return;
            }

            // Pull-to-refresh rebuilt the sections: the tab that was being read wins again.
            if (isNewSection && mRestoreSectionTitle != null && mSections.containsKey(id)
                    && mRestoreSectionTitle.equals(mSections.get(id).title)) {
                mActiveSectionId = id;
                mRestoreSectionTitle = null;
            }

            // First section to arrive becomes the visible one.
            if (mActiveSectionId == -1 || !mSections.containsKey(mActiveSectionId)) {
                mActiveSectionId = mSections.keySet().iterator().next();
            }

            if (hasContent()) {
                hideLoadState();
            }

            rebuildTabs();

            if (id == mActiveSectionId) {
                showActiveSection(false);
            }
        });
    }

    private void appendNew(Section section, List<Video> videos) {
        for (Video video : videos) {
            if (!section.videos.contains(video)) {
                section.videos.add(video);
            }
        }
    }

    private void syncVideos(Section section, List<Video> videos) {
        for (Video video : videos) {
            int idx = section.videos.indexOf(video);
            if (idx >= 0) {
                section.videos.set(idx, video);
            }
        }
    }

    @Override
    public void setPosition(int index) {
        runOnUiThread(() -> {
            if (index >= 0 && index < mTabs.getTabCount()) {
                TabLayout.Tab tab = mTabs.getTabAt(index);
                if (tab != null) {
                    tab.select(); // routes through the listener -> shows that section
                }
            }
        });
    }

    @Override
    public void clear() {
        runOnUiThread(() -> {
            mSections.clear();
            mPendingScroll = null;
            mShortsEmptied = false;
            mActiveSectionId = -1;
            mLastPaginationTriggerCount = -1;
            mLoadMoreFailedSectionId = -1;
            mPagingSectionId = -1;
            if (!mSwipe.isRefreshing()) {
                mRestoreSectionTitle = null; // only a refresh's own clear carries the tab over
                applyChannelTitle(); // NEWTUBE: another channel opened into this page
                mHeaderChannelId = null;
                ((com.google.android.material.appbar.AppBarLayout) findViewById(R.id.mobile_channel_appbar))
                        .setExpanded(true, false);
                loadHeader();
            }
            mSuppressTabCallback = true;
            mTabs.removeAllTabs();
            mTabs.setVisibility(View.GONE);
            mTabsSpace.setVisibility(View.GONE);
            mSuppressTabCallback = false;
            syncLoadMoreFooter();
            mAdapter.submitList(new ArrayList<>());
            hideLoadState();
        });
    }

    @Override
    public void showProgressBar(boolean show) {
        runOnUiThread(() -> {
            if (!show && topUpActiveIfShort()) {
                return; // NEWTUBE(shorts): another page is on its way - still loading
            }
            // Pull-to-refresh draws its own spinner; don't stack the centered one over it.
            mProgressBar.setVisibility(show && !mSwipe.isRefreshing() ? View.VISIBLE : View.GONE);
            if (!show) {
                mSwipe.setRefreshing(false);
                showEmptyIfShortsLeftNothing();
            }
        });
    }

    /**
     * NEWTUBE(shorts): the page loaded, but the Shorts filter left no card in any tab (a channel of
     * only Shorts, or a Shorts list reached some other way) - the empty state, never a blank page
     * (#63). ShortsFilter drops the tiles that lead to a Shorts list; this is the safety net.
     */
    private void showEmptyIfShortsLeftNothing() {
        if (mShortsEmptied && !hasContent() && !mLoadState.isShowing()) {
            mGrid.setVisibility(View.GONE);
            mLoadState.show(LoadFailure.EMPTY);
        }
    }

    /**
     * NEWTUBE(page-load-errors): the first load put nothing on screen. Rows still on screen (a
     * pull-to-refresh that failed) stay - stale rows beat a full-page error, as on Home - with a
     * snackbar saying the refresh failed. Otherwise the grid gives way to the Home-style empty
     * state with Try again.
     */
    @Override
    public void showLoadFailure(int state) {
        runOnUiThread(() -> {
            mSwipe.setRefreshing(false);
            mProgressBar.setVisibility(View.GONE);
            mRestoreSectionTitle = null;

            if (hasContent()) {
                if (state != LoadFailure.EMPTY) {
                    com.newtube.mobile.ui.common.MobileSnackbar.show(this, state == LoadFailure.NO_CONNECTION ? R.string.mobile_empty_no_connection : R.string.mobile_refresh_error);
                }
                return;
            }

            mGrid.setVisibility(View.GONE);
            mLoadState.show(state);
        });
    }

    /** NEWTUBE(page-load-errors): a next page failed - footer row with Try again, rows stay. */
    @Override
    public void showLoadMoreFailure() {
        runOnUiThread(() -> {
            mLoadMoreFailedSectionId = mPagingSectionId != -1 ? mPagingSectionId : mActiveSectionId;
            syncLoadMoreFooter();
        });
    }
}
