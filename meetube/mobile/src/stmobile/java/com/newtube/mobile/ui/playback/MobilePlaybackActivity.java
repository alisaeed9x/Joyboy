package com.newtube.mobile.ui.playback;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.app.PendingIntent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Rational;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.OneShotPreDrawListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.mediaserviceinterfaces.LiveChatService;
import com.liskovsoft.mediaserviceinterfaces.data.CommentItem;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import io.reactivex.rxjava3.disposables.Disposable;

import com.github.vkay94.dtpv3.DoubleTapPlayerView;
import com.github.vkay94.dtpv3.DoubleTapPlayerViewImpl;
import com.github.vkay94.dtpv3.youtube.YouTubeOverlay;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.TimeBar;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.mediaserviceinterfaces.data.ChatItem;
import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.QueuePlaybackMode;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.State;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.ChatReceiver;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.SeekBarSegment;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.SubtitleSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.SubtitleTrack;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.newtube.mobile.casting.CastPickerLauncher;
import com.newtube.mobile.casting.CastSessionManager;
import com.newtube.mobile.casting.CastTarget;
import com.newtube.mobile.casting.CastVolumeKeys;
import com.newtube.mobile.player.Media3DebugInfoManager;
import com.newtube.mobile.player.Media3PlayerController;
import com.newtube.mobile.player.Media3PlayerInitializer;
import com.newtube.mobile.player.Media3SubtitleManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;
import com.liskovsoft.smartyoutubetv2.common.utils.ClickbaitRemover;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.common.misc.VideoDownloads;
import com.newtube.mobile.downloads.DownloadFolder;
import com.newtube.mobile.downloads.DownloadItem;
import com.newtube.mobile.downloads.DownloadMenu;
import com.newtube.mobile.downloads.DownloadOption;
import com.newtube.mobile.downloads.DownloadRegistry;
import com.newtube.mobile.SessionWarmup;
import com.newtube.mobile.ui.common.Burst;
import com.newtube.mobile.ui.common.FrameGate;
import com.newtube.mobile.ui.common.Haptics;
import com.newtube.mobile.ui.common.MagneticDrag;
import com.newtube.mobile.ui.common.MobileActivity;
import com.newtube.mobile.ui.common.MobileSheets;
import com.newtube.mobile.ui.common.Motion;
import com.newtube.mobile.ui.common.ThemeMode;
import com.newtube.mobile.ui.common.ThemeRefresh;
import com.newtube.mobile.ui.dialog.MaxHeightRecyclerView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Touch player - PLAYER POLISH wave.
 *
 * <p>Built the same way as the {@code EmbedPlayerView} template (ARCHITECTURE.md, section 6): a
 * plain media3 {@code PlayerView} (here the {@link DoubleTapPlayerViewImpl} subclass, still no
 * Leanback) wired straight to {@link Media3PlayerController} and {@link Media3PlayerInitializer}, with
 * this Activity itself implementing {@link PlaybackView} and being handed to
 * {@link PlaybackPresenter#setView}. The 11 playback controllers owned by {@code PlaybackPresenter}
 * (VideoLoader, VideoState, Suggestions, ErrorFixer, PlayerUI, ...) are reused completely unchanged;
 * this class is only the touch View layer. The engine (open calls, position, duration, play-pause,
 * speed, formats, resize) is untouched - this wave adds a polished custom control surface, open/close
 * transitions, swipe-down-to-dismiss and buffer tuning on top.</p>
 *
 * <h3>Controls</h3>
 * The stock {@code PlaybackControlView} is disabled ({@code use_controller="false"}); instead a
 * custom overlay (top back + title, large center play/pause/replay, bottom {@link PlayerTimeBar}
 * with current/total time + fullscreen toggle) is shown/hidden on a single tap and auto-hidden after
 * {@link #AUTO_HIDE_MS}. Double-tap left/right seeks +/-10s via the {@code doubletapplayerview}
 * module's {@link YouTubeOverlay}, wired to the live player. Position/buffer are polled and seeking
 * is wired straight to {@link Media3PlayerController}.
 */
public class MobilePlaybackActivity extends MobileActivity
        implements PlaybackView, PlayerContainerLayout.SwipeListener, LiveChatSheet.Host {

    private static final long AUTO_HIDE_MS = 3_500;
    private static final long PROGRESS_UPDATE_MS = 500;
    /** Live-edge jump target: mirrors the shared VideoStateController's ~15s park behind the edge. */
    private static final long LIVE_EDGE_OFFSET_MS = 15_000;
    /** Within this of the edge counts as "watching live" (the park offset plus segment slack). */
    private static final long LIVE_EDGE_THRESHOLD_MS = 20_000;
    /** Trigger related-list paging when the content is scrolled within this many px of the bottom. */
    private static final int SUGGESTIONS_PAGE_THRESHOLD_PX = 800;

    private PlayerContainerLayout mContainer;
    private PinchZoomLayout mVideoArea;
    private TextView mZoomHintView;
    private DoubleTapPlayerViewImpl mPlayerView;
    private YouTubeOverlay mYouTubeOverlay;
    private View mControlsRoot;
    @Nullable private View mTopScrim;
    @Nullable private View mBottomScrim;
    /** NEWTUBE(issue #9): the last known video display aspect (pixel aspect included), 0 = none yet. */
    private float mVideoAspect;
    private TextView mTitleView;
    /** MEETUBE(fullscreen): the channel, under the title in the vertical fullscreen. */
    private TextView mPlayerAuthor;
    private ImageButton mBackButton;
    private ImageButton mPlayPauseButton;
    private ImageButton mFullscreenButton;
    private TextView mPositionView;
    private TextView mDurationView;
    private TextView mLiveChip;
    /**
     * NEWTUBE(seek bar): YouTube's seek bar. Outside mControlsRoot: in portrait it stays on the
     * video's bottom edge as a thin progress line while the controls are hidden.
     */
    private PlayerTimeBar mTimeBar;
    /** The row on the bar: time, current chapter, fullscreen. */
    @Nullable private View mBottomRow;
    /**
     * MEETUBE(fullscreen-actions): like, dislike, comments, download and share on a pill under the
     * bar in fullscreen, and the chevron that hides or shows it. Shown until the chevron hides it, and
     * the choice is kept.
     */
    @Nullable private View mActionsRow;
    @Nullable private ImageButton mActionsToggle;
    @Nullable private ImageButton mPlayerLike;
    @Nullable private ImageButton mPlayerDislike;
    @Nullable private ImageButton mPlayerDownload;
    @Nullable private View mPlayerComments;
    @Nullable private View mPlayerChat; // MEETUBE(live chat, issue #32): chat from the fullscreen pill
    private boolean mActionsShown;
    /** The pill's comments button left fullscreen: open the panel once the page is back. */
    private boolean mCommentsAfterFullscreen;
    /** The comments were opened from the vertical fullscreen: closing them goes back to it. */
    private boolean mFullAfterComments;
    // MEETUBE(issue #217): the description from the vertical fullscreen, as the comments: the page
    // comes back with the panel under the video, and the fullscreen returns when it closes.
    private boolean mDescriptionAfterFullscreen;
    private boolean mFullAfterDescription;
    // MEETUBE(issue #219): the Chapters list does the same from the vertical fullscreen.
    private boolean mChaptersAfterFullscreen;
    private boolean mFullAfterChapters;
    /** Landscape comments: the panel in a column beside the video (layOutCommentsSide). */
    private boolean mCommentsSide;
    private boolean mChatSide; // MEETUBE(live chat, issue #32): the landscape video beside the chat column
    private int mChatMargin; // the room the video gives the chat column (its end margin, once slid)
    @Nullable private android.animation.ValueAnimator mChatSlide;
    private static final String PLAYER_UI_PREFS = "meetube_player_ui";
    private static final String KEY_ACTIONS_SHOWN = "fullscreen_actions";
    private static final String KEY_TIME_REMAINING = "time_remaining";
    private boolean mTimeRemaining; // MEETUBE(issue #67): the time pill counts down, toggled by a tap
    /** Previous / play-pause / next, and the top-right options: they step aside while scrubbing. */
    @Nullable private View mTransport;
    @Nullable private View mOptionsRow;
    /** Over the top of the video: "Release to cancel" (seek bar). */
    @Nullable private TextView mTopPill;
    /** NEWTUBE(gestures): brightness / volume while a side swipe sets it (SwipeLevels). */
    @Nullable private View mLevelPill;
    @Nullable private ImageView mLevelIcon;
    @Nullable private LevelBar mLevelBar;
    /** The controls faded aside for a seek-bar drag (setScrubChrome). */
    private boolean mScrubChromeHidden;
    private ProgressBar mProgressBar;
    /** First-run only: "one-time setup" line under the spinner while the session is cold. */
    private TextView mSetupHint;
    /** Persistent "why playback stopped" line (see {@link #showPlaybackNotice}). */
    private TextView mNoticeView;
    /** MEETUBE(sponsorblock): "Skip Sponsor" by the fullscreen button (showSkipSegment). */
    private TextView mSkipSegmentPill;
    private final Runnable mHideSkipSegment = this::hideSkipSegment;
    /** MEETUBE(up-next): the "Up next in N" card over an ended video. */
    private NextCountdown mNextCountdown;
    private ImageButton mSubtitlesButton;
    private ImageButton mMoreButton;
    /** The controls on a frosted disc of the video (GlassDrawable), when Frosted player buttons is on. */
    private View[] mGlassControls = new View[0];
    private ImageButton mPrevButton;
    private ImageButton mNextButton;
    private ViewGroup mDebugViewGroup;

    // Casting (Route B scaffolding): cast button + the "Playing on TV" remote panel. The panel is
    // self-contained (own seek bar driven by CastEvents) so the local transport stays untouched.
    private ImageButton mCastButton;
    private View mCastOverlay;
    private TextView mCastOverlayTitle;
    private ImageButton mCastPlayPause;
    private TextView mCastLiveChip;
    private View mCastTimeline;
    private TextView mCastPosition;
    private TextView mCastDuration;
    private SeekBar mCastSeekBar;
    private CastSessionManager mCastSessionManager;
    private boolean mCastScrubbing;
    /** Optimistic receiver-caption selection; Lounge doesn't echo full track metadata. */
    @Nullable
    private String mCastSubtitleVssId;
    @Nullable
    private String mCastSubtitleLabel;

    // Subtitle styling + debug overlay. Both mirror the TV PlaybackFragment wiring: the subtitle
    // manager applies the user's stored SubtitleStyle to the PlayerView's built-in SubtitleView;
    // the debug manager drives the "stats for nerds" overlay. Created lazily once the player exists.
    private Media3SubtitleManager mSubtitleManager;
    private Media3DebugInfoManager mDebugInfoManager;

    // Watch page (portrait content column under the video).
    private View mWatchRoot;
    private NestedScrollView mWatchScroll;
    private View mWatchContent;
    private TextView mWatchTitle;
    private TextView mWatchMeta;
    /** MEETUBE(ambient): the YouTube-style glow behind the watch page. */
    private AmbientGlow mAmbient;
    /** MEETUBE(shorts): the vertical player mode for videos from the Shorts tab. */
    private ShortsOverlay mShorts;
    private TextView mWatchDescription;
    private View mWatchLike;
    private ImageView mWatchLikeIcon;
    private TextView mWatchLikeCount;
    private View mWatchDislike;
    private ImageView mWatchDislikeIcon;
    private TextView mWatchDislikeCount;
    private View mWatchShare;
    /** MEETUBE(music): shown only once {@link MusicLink} says the video is in YouTube Music. */
    private View mWatchMusic;
    /** MEETUBE(lyrics): the open lyrics panel, closed when another video starts (its lines are the last song's). */
    @Nullable
    private BottomSheetDialog mLyricsSheet;
    /** MEETUBE(transcript): the open transcript, and the open video's caption links it is made from. */
    private BottomSheetDialog mTranscriptSheet;
    @Nullable
    private MediaItemFormatInfo mCaptionInfo;
    @Nullable private String mMusicPillVideoId;
    private View mWatchSave;
    private ImageView mWatchSaveIcon;
    private TextView mWatchSaveLabel;
    private View mWatchDownload;
    private ImageView mWatchDownloadIcon;
    private TextView mWatchDownloadLabel;
    private ImageView mWatchAvatar;
    private TextView mWatchChannelName;
    private TextView mWatchSubs;
    private TextView mWatchSubscribe;
    /**
     * MEETUBE(watch-look): on the compact page Subscribe shrinks to the app's mark while the actions
     * beside it are swiped, so they have the row (morphSubscribe). 0 = the label, 1 = the mark.
     */
    @Nullable
    private ImageView mSubscribeIcon;
    private int mSubscribeTextColor;
    private float mSubscribeMorph;
    private boolean mSubscribeCollapsed;
    @Nullable
    private android.animation.ValueAnimator mSubscribeAnimator;
    private TextView mWatchRelatedLabel;
    private View mRelatedSkeleton;
    private RecyclerView mWatchRelated;
    private RelatedVideoAdapter mRelatedAdapter;
    /**
     * MEETUBE(issue #74): the chips over the related list. "All" is the related list itself; "From
     * <channel>" shows that channel's uploads instead. YouTube's own topic chips come only to a
     * signed-in WEB /next, and WEB refuses the TV sign-in (see issue #79), so these are built here.
     * The chip's videos never enter mRelatedVideos: autoplay, the controller and the More videos
     * panel keep reading the real suggestions.
     */
    private View mRelatedChips;
    /** MEETUBE(glass segmented): the chips as a segment bar, in place of mRelatedChips. */
    private com.newtube.mobile.ui.common.GlassChipTrack mRelatedTrack;
    private final List<TextView> mTrackChips = new ArrayList<>();
    private TextView mAllChip;
    private TextView mChannelChip;
    private TextView mNewChip;
    private TextView mWatchedChip;
    private TextView[] mRelatedChipViews;
    /** The Up next chip whose videos show (mAllChip: the related list itself). */
    private TextView mPickedChip;
    @Nullable private MediaItemMetadata mChipMetadata;
    /** The channel chip's videos; null while "All" is on. */
    @Nullable private List<Video> mChipVideos;
    @Nullable private com.liskovsoft.mediaserviceinterfaces.data.MediaGroup mChipPage;
    @Nullable private Disposable mChipAction;

    // Comments + live chat entries. Keys come from the loaded metadata. Comments open the panel
    // over the watch page (CommentsPanel); live chat opens its bottom sheet.
    private View mWatchCommentsEntry;
    private TextView mWatchCommentsCount;
    private CommentsPanel mCommentsPanel;
    // MEETUBE(lyrics-panel): portrait side panels share the comments panel's frame and motion.
    private CommentsPanelLayout mDescriptionPanel;
    private CommentsPanelLayout mLyricsPanel;
    private CommentsPanelLayout mTranscriptPanel;
    private CommentsPanelLayout[] mSidePanels = new CommentsPanelLayout[0];
    private final Map<CommentsPanelLayout, Runnable> mSidePanelBindings = new LinkedHashMap<>();
    private androidx.activity.OnBackPressedCallback mSidePanelBackCallback;
    /** NEWTUBE(comments-panel): best effort, a little after the video starts, so the panel opens full. */
    private static final long COMMENTS_PREFETCH_DELAY_MS = 2_000;
    private final Runnable mPrefetchComments = this::prefetchComments;
    /**
     * MEETUBE(watch-look): the compact watch page (WatchLook) - its comments card shows the top
     * comments one at a time, the next every TOP_COMMENT_MS.
     */
    private boolean mWatchCompact;
    /** Between the compact line's views and date: a middle dot spaced like the meta_dot before them. */
    private static final String COMPACT_DOT = " · ";
    private View mCommentTeaser;
    private ImageView mCommentTeaserAvatar;
    private TextView mCommentTeaserText;
    private final List<CommentItem> mTopComments = new ArrayList<>();
    private int mTopCommentIndex;
    private static final long TOP_COMMENT_MS = 10_000;
    private final Runnable mNextTopComment = this::showNextTopComment;
    /** Chapters of the current video (Video.isChapter items from the suggestions pipeline). */
    private final List<Video> mChapterVideos = new ArrayList<>();
    /** Shown above the seek bar ONLY while scrubbing: a pill with the time and chapter under the finger. */
    private TextView mScrubChapterView;
    private SeekFilmstrip mSeekFilmstrip;
    /**
     * NEWTUBE(chapters, issue #13): above the seek bar while the controls are up and the bar is not
     * being dragged - the playing chapter's title; a tap opens the Chapters list (ChaptersSheet).
     */
    private TextView mChapterButton;
    /** The chapter mChapterButton shows (-1 = hidden), so the progress tick only writes on a change. */
    private int mChapterButtonIndex = -1;
    /** The open Chapters list, closed when the chapters change (autoplay): its rows would seek the next video. */
    @Nullable private BottomSheetDialog mChaptersSheet;
    private View mWatchChatEntry;
    private String mCommentsKey;
    private String mLiveChatKey;

    // Live chat stream: the reused ChatController pushes a ChatReceiver here; incoming messages
    // accumulate into a bounded buffer that the LiveChatSheet seeds from and observes live.
    private static final int MAX_CHAT_ITEMS = 250;
    private final List<ChatItem> mChatItems = new ArrayList<>();
    private ChatReceiver mChatReceiver;
    private LiveChatSheet.Observer mChatObserver;
    private Disposable mLiveChatAction;
    // NEWTUBE(mobile-ttff): the watch header binds from the SINGLE metadata document that
    // SuggestionsController already loads (delivered via PlaybackView.onWatchMetadata), instead of a
    // 2nd getMetadataObserve. Header binding is deferred until the first frame has rendered so it never
    // competes with first-frame render: metadata arriving early is stashed here and applied when
    // the selected video's loading still lifts. Accessed on the UI thread only.
    private final WatchMetadataGate mWatchMetadataGate = new WatchMetadataGate();
    private final DeferredPlaybackUi mRelatedRenderGate = new DeferredPlaybackUi();
    // Separate from the image hold: onStop and sheet openings cancel that hold, but must not strand
    // metadata when an unavailable stream never renders a frame. This one-shot survives onStop.
    private static final long WATCH_METADATA_TIMEOUT_MS = 6_000;
    private final Runnable mReleaseWatchMetadata = this::releaseWatchMetadata;

    // Suggestions store: id -> accumulated videos (LinkedHashMap keeps delivery/row order).
    private final LinkedHashMap<Integer, List<Video>> mSuggestionVideos = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, VideoGroup> mSuggestionGroups = new LinkedHashMap<>();
    private final List<Video> mRelatedVideos = new ArrayList<>();
    private Video mLastPagedVideo;

    // Queue card ("Playing from <playlist>"). The queue row is one of the suggestion groups: the
    // one that contains the CURRENTLY PLAYING video (see findQueueGroupId). Its videos are shown
    // here instead of being flattened into Up next.
    private View mQueueCard;
    private View mQueueHeader;
    private TextView mQueueTitle;
    private TextView mQueueSubtitle;
    private ImageView mQueueChevron;
    private MaxHeightRecyclerView mQueueList;
    private RelatedVideoAdapter mQueueAdapter;
    /** Collapsed by default, like YouTube's queue. Survives video changes within a session. */
    private boolean mQueueExpanded;
    private final List<Video> mQueueVideos = new ArrayList<>();

    // Like/Dislike/Subscribe visual state, keyed by R.id.action_*.
    private final SparseIntArray mButtonStates = new SparseIntArray();

    private Video mWatchVideo;
    private String mWatchVideoId;
    /** MEETUBE(description): the Description sheet's views and calendar date, from the metadata. */
    @Nullable
    private String mDescriptionViews;
    @Nullable
    private String mDescriptionDate;
    /** MEETUBE(exact counts): the likes as a whole number, -1 when YouTube sent only "144K". */
    private long mLikeCountExact = -1;

    /**
     * Keep the portrait video box tied to the width the watch page actually receives. Display
     * metrics can still describe the old landscape window inside onConfigurationChanged (observed
     * on Pixel), which used to leave a 2251 * 9 / 16 tall black box after rotating back.
     */
    private final View.OnLayoutChangeListener mWatchRootLayoutListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                int width = right - left;
                // Not in PiP: a portrait layout override (a landscape tablet that left
                // fullscreen in place) outlives the PiP window, which must stay video-only.
                // MEETUBE(pip): a square or vertical video's PiP window is itself "portrait";
                // the 16:9 box here left it half black, the fullscreen FAB in the black.
                if (width > 0 && !this.mIsInPip && !this.mPipEnterPending
                        && layoutOrientation()
                        == Configuration.ORIENTATION_PORTRAIT) {
                    applyPortraitVideoHeight(width);
                }
            };

    private PlaybackPresenter mPresenter;
    private Media3PlayerInitializer mPlayerInitializer;
    private Media3PlayerController mExoPlayerController;
    private ExoPlayer mPlayer;
    // NEWTUBE(bench): position/seek evidence for the in-app source benchmark; null unless
    // debug.arc.bench=1 on a debug or benchmark build (see BenchTicker).
    @Nullable
    private com.newtube.mobile.player.BenchTicker mBenchTicker;
    private boolean mIsEngineBlocked;

    private boolean mControlsVisible;
    private boolean mScrubbing;
    private boolean mIsEnded;
    private boolean mIsInPip;
    /** True between onStop and onStart; distinguishes PiP-dismiss orderings (see onPictureInPictureModeChanged). */
    private boolean mIsStopped;
    /** True between onResume and onPause. Gates auto-enter PiP (see {@link #shouldAutoEnterPip()}). */
    private boolean mIsResumed;
    /**
     * Set when PiP mode ends, cleared when the fullscreen UI actually resumes. If onStop arrives
     * with it still set, the PiP window was DISMISSED (the X / swipe-away), not expanded: Android 16's
     * pip2 shell doesn't finish the activity on dismiss (it delivers modeChanged(false) then onStop
     * and parks the task at the bottom), so without this the "closed" video kept playing audio
     * forever and the stopped player lingered as a zombie task that hijacked the next video open.
     */
    private boolean mPipDismissPending;
    /**
     * Requested orientation captured when a PiP stint began, restored when it ends;
     * {@link #ORIENTATION_NONE} when there was nothing to restore.
     *
     * <p>A landscape lock must never ride into the pinned task. {@link #toggleFullscreen()} sets
     * SCREEN_ORIENTATION_SENSOR_LANDSCAPE and nothing used to clear it, so going fullscreen and then
     * leaving the app produced a pinned task whose activity still demanded landscape. The expand
     * transition then could not complete at all: the window stayed {@code mode=pinned} with
     * {@code requestedOrientation=SCREEN_ORIENTATION_SENSOR_LANDSCAPE} forever, ignoring both taps
     * and an explicit relaunch, while rendering sideways against a portrait display. Reproduced on a
     * Pixel 9 (Android 16) as: fullscreen -&gt; home -&gt; the PiP window can no longer be opened.</p>
     */
    private int mPrePipOrientation = ORIENTATION_NONE;
    /**
     * The player's orientation request at rest: follow the phone, within the system auto-rotate
     * setting. USER rather than UNSPECIFIED because the player window is translucent: an
     * UNSPECIFIED translucent activity has no say, so the display took its orientation from
     * whatever was behind it - fine over Browse, but a video opened from another app sits over
     * that app or the launcher (portrait-only on Pixel), and never rotated. Same value as the
     * manifest's screenOrientation.
     */
    private static final int FREE_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_USER;
    /** Pending hand-back of the fullscreen button's forced orientation (see toggleFullscreen). */
    private final OrientationHandBack mOrientationHandBack = new OrientationHandBack();
    @Nullable
    private android.view.OrientationEventListener mOrientationListener;
    private int mLastPhoneDegrees = android.view.OrientationEventListener.ORIENTATION_UNKNOWN;
    private final Runnable mOrientationSettleCheck = () -> onPhoneOrientation(mLastPhoneDegrees);
    /** True while in true background audio-only playback (video renderer dropped); see setBackgroundAudioMode. */
    private boolean mBackgroundAudioMode;
    /** MEETUBE(audio-only): the headphones button is on - the video track stays dropped on screen too. */
    private boolean mAudioOnly;
    /** MEETUBE(audio-only): the live session's state, for the mini card's headphones button. */
    static volatile boolean sAudioOnly;
    static final String EXTRA_AUDIO_TOGGLE = "toggle";
    /** MEETUBE(shorts): onStop paused the short (no background play); onStart plays it again. */
    private boolean mShortPausedByLeave;
    /** The PiP headphones closed the window: its exit is not a dismiss, the audio plays on. */
    private boolean mPipToAudio;
    private ImageButton mAudioButton;
    /** The thumbnail shown in the video's place while {@link #mAudioOnly}; built on first use. */
    @Nullable private View mAudioCover;
    @Nullable private ImageView mAudioCoverArt;

    // Background-playback foreground service (reuses THIS Activity's player; see MobilePlaybackService).
    private MobilePlaybackService mPlaybackService;
    private boolean mServiceBound;

    // Picture-in-Picture play/pause RemoteAction wiring.
    static final String ACTION_PIP_TOGGLE = "com.newtube.mobile.action.PIP_TOGGLE";
    /** MEETUBE(audio-only): the PiP window's headphones - close the window, keep the sound. */
    static final String ACTION_PIP_AUDIO = "com.newtube.mobile.action.PIP_AUDIO";
    private static final int PIP_REQUEST_TOGGLE = 700;
    private static final int PIP_REQUEST_AUDIO = 701;
    /** MEETUBE(shorts-pip): a short's PiP window walks the feed like YouTube's - previous / next. */
    static final String ACTION_PIP_PREV = "com.newtube.mobile.action.PIP_PREV";
    static final String ACTION_PIP_NEXT = "com.newtube.mobile.action.PIP_NEXT";
    private static final int PIP_REQUEST_PREV = 702;
    private static final int PIP_REQUEST_NEXT = 703;
    /** What the PiP window was last given (play state + aspect); null outside PiP. */
    @Nullable private String mPipParamsShown;
    /** Sentinel for {@link #mPrePipOrientation}: no orientation was captured. */
    private static final int ORIENTATION_NONE = Integer.MIN_VALUE;
    private BroadcastReceiver mPipReceiver;

    private final StringBuilder mFormatBuilder = new StringBuilder();
    private final Formatter mFormatter = new Formatter(mFormatBuilder, Locale.getDefault());

    private final Runnable mHideControlsRunnable = this::onAutoHideTick;
    private final Runnable mProgressUpdateRunnable = this::onProgressTick;
    private final Runnable mLineUpdateRunnable = this::onLineTick;

    // ---------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.newtube.mobile.ui.common.PlayerTrace.mark("OPEN onCreate (" + getClass().getSimpleName() + ")"); // JOYBOY(v952)

        SystemPipBridge.attach(this);
        sCurrent = new java.lang.ref.WeakReference<>(this);

        // A tapped card supplies its own geometry-driven open below. Suppress Android's whole-
        // window animation so it cannot fade/scale the custom thumbnail morph a second time.
        if (PlayerTransitionBridge.hasPending()) {
            overridePendingTransition(0, 0);
        }

        // NOTE: buffer tuning is applied locally around createPlayer() (see createPlayerObjects),
        // NOT here - forcing PlayerData.setVideoBufferType() on every onCreate permanently
        // overwrote the user's persisted global buffer preference. See createPlayerObjects().

        setContentView(R.layout.activity_mobile_playback);
        // MEETUBE(watch-look): the watch page's top half, compact or classic, before any view is found.
        mWatchCompact = !WatchLook.isClassic(this);
        android.view.ViewStub details = findViewById(R.id.mobile_watch_details);
        details.setLayoutResource(mWatchCompact
                ? R.layout.include_watch_details_compact : R.layout.include_watch_details_classic);
        details.inflate();

        // NEWTUBE(motion): Back minimizes like YouTube, and the back gesture previews it (the video
        // shrinks toward the mini card with the finger). Added here, before the comments panel's
        // callback, so an open panel still takes Back first.
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackStarted(@NonNull androidx.activity.BackEventCompat backEvent) {
                onBackGestureStarted();
            }

            @Override
            public void handleOnBackProgressed(@NonNull androidx.activity.BackEventCompat backEvent) {
                onBackGestureProgressed(backEvent.getProgress());
            }

            @Override
            public void handleOnBackCancelled() {
                onBackGestureCancelled();
            }

            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });

        bindViews();
        setupVideoSurface();
        setupControls();
        setupWatchContent();

        int orientation = layoutOrientation();
        applyWatchLayoutForOrientation(orientation);
        applySystemBarsForOrientation(orientation);
        updateFullscreenIcon(orientation);

        // NOTE: position matters! Mirrors EmbedPlayerView.initPlayer()/PlaybackFragment.onCreate():
        // create the controller objects and hand the presenter our view BEFORE building the actual
        // player, then call onViewInitialized() to (re-)init all 11 playback controllers.
        // NEWTUBE(media3): the engine behind this activity is androidx.media3; the controller
        // mirrors ExoPlayerController's surface, so everything below it is unchanged.
        mPresenter = PlaybackPresenter.instance(this);
        mPlayerInitializer = new Media3PlayerInitializer(this);
        mExoPlayerController = new Media3PlayerController(this, mPresenter);

        mPresenter.setView(this);
        mPresenter.onViewInitialized();

        createPlayerObjects();

        registerPipReceiver();

        // Casting: observe the (process-wide) session so the "Playing on TV" panel follows
        // whatever session connects/updates/ends while this player is open. A session that
        // already exists (activity recreated mid-cast) restores the panel immediately.
        mCastSessionManager = CastSessionManager.instance(this);
        mCastSessionManager.addListener(mCastListener);
        if (mCastSessionManager.isConnected()) {
            showCastOverlay();
        }
        updateCastIconTint();

        finishIfNothingToPlay(savedInstanceState);
    }

    /**
     * NEWTUBE(dead-player): this Activity can be brought up by something OTHER than a video open,
     * and when it is, nothing downstream ever gives it a video - so it renders the full watch page
     * parked at 00:00 with an empty title/duration, no error and no way forward. Observed on the
     * Pixel 9 after an in-place update (2026-09-07): the install killed the process
     * ("Force removing ActivityRecord{...MobilePlaybackActivity}: app died, no saved state") and
     * SystemUI relaunched the component directly - {@code START u0 {cmp=.../MobilePlaybackActivity}
     * with LAUNCH_SINGLE_TOP from uid 10244 (com.android.systemui)} - a bare Intent with no video.
     * The process then logged nothing at all beyond auto-hide timer ticks for 19 minutes. The same
     * shape applies to any task restore after process death.
     *
     * <p>The video always precedes the Activity on a real open: {@code PlaybackPresenter.openVideo}
     * runs {@code onNewVideo} (which strongly parks the item in {@code Playlist.instance()}, so the
     * presenter's WeakReference cannot be collected out from under us) BEFORE
     * {@code startView(PlaybackView.class)}. So "presenter has no video" at the end of onCreate is
     * a reliable "nobody asked for a video", not a race. A pending card morph is treated as a real
     * open too, belt-and-braces, and a configuration recreate is exempt (it has saved state and the
     * presenter still holds the video).
     *
     * <p>Deliberately at the END of onCreate rather than an early return: the Activity is fully
     * constructed, so every later lifecycle callback stays safe on the way out. Building a player
     * we immediately discard costs a few hundred ms on a path that only happens after an app
     * update - far cheaper than guarding seven lifecycle methods against a half-built screen.
     */
    private void finishIfNothingToPlay(@Nullable Bundle savedInstanceState) {
        if (mPresenter == null) {
            return;
        }

        boolean presenterHasVideo = mPresenter.getVideo() != null;

        // NEWTUBE(process-death-restore): saved state WITHOUT a presenter video is not a configuration
        // recreate (the presenter is process-wide and would still hold it) but a task restore after the
        // OS killed us in the background - a video paused ~20 min on a realme came back as a frozen
        // watch page (state=IDLE, video=?) until a manual refresh. Reopen what was on screen.
        if (savedInstanceState != null && !presenterHasVideo && !PlayerTransitionBridge.hasPending()) {
            Video restored = Video.fromString(savedInstanceState.getString(STATE_VIDEO));
            if (restored != null && restored.hasVideo()) {
                restored.pendingPosMs = savedInstanceState.getLong(STATE_POSITION_MS, -1);
                NetPath.log("playback-activity restore=process-death video=" + restored.videoId
                        + " posMs=" + restored.pendingPosMs);
                mPresenter.onNewVideo(restored); // loads now, or pends until the engine initializes
                return;
            }
        }

        // Saved state only proves a config recreate when the presenter still has the video; a
        // process-death restore we could not reopen is as dead as a bare launch.
        if (!shouldFinishWithoutVideo(savedInstanceState != null && presenterHasVideo,
                PlayerTransitionBridge.hasPending(), presenterHasVideo)) {
            return;
        }

        NetPath.log("playback-activity abort=no-video reason=bare-launch");

        // Land the user on Home instead of a dead player: startDefaultView opens the root (Browse).
        getViewManager().startDefaultView();
        finishReally();
    }

    /**
     * Decision half of {@link #finishIfNothingToPlay}, split out so the exact conditions are
     * unit-testable without an Activity. Bail out ONLY on a fresh launch that carries no video
     * anywhere: saved state means a configuration recreate (the presenter still owns the video),
     * and a pending card morph means a tap is in flight.
     */
    static boolean shouldFinishWithoutVideo(boolean hasSavedState, boolean hasPendingTransition,
            boolean presenterHasVideo) {
        return !hasSavedState && !hasPendingTransition && !presenterHasVideo;
    }

    private static final String STATE_VIDEO = "meetube.playback.video";
    private static final String STATE_POSITION_MS = "meetube.playback.positionMs";

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        Video video = mPresenter != null ? mPresenter.getVideo() : null;
        // Local downloads carry their file URI outside toString(), so they cannot be rebuilt from it.
        if (video != null && video.hasVideo() && !video.isLocal()) {
            outState.putString(STATE_VIDEO, video.toString());
            outState.putLong(STATE_POSITION_MS, getPositionMs());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        com.newtube.mobile.ui.common.PlayerTrace.mark("OPEN/MAXIMIZE new-intent (" + getClass().getSimpleName() + ")"); // JOYBOY(v952)
        cancelClose();
        super.onNewIntent(intent);
        // REORDER_TO_FRONT reuses this instance after mini mode. The pending card snapshot means
        // this is a new feed selection, not a plain mini-card expansion.
        if (PlayerTransitionBridge.hasPending()) {
            overridePendingTransition(0, 0);
        }
        // NEWTUBE(link-while-playing): see mRoutedInWhileLeaving.
        if (routedInWhileLeaving(mIsResumed, mIsInPip, SystemPipBridge.isRestoreIntent(intent))) {
            mRoutedInWhileLeaving = true;
            logPip("routed-in while-leaving");
        }
    }

    /**
     * NEWTUBE(link-while-playing): a share link opened while this player plays in the foreground
     * starts the external-intent router in its OWN task ({@code :router}, see the manifest). That
     * task switch pauses us with the Android 12+ auto-enter flag armed, so the system starts moving
     * this task into PiP; the router then routes the new video back here (onNewIntent), but the PiP
     * shell ignores a relaunch while the entry is still animating - the new video ended up playing
     * in a PiP window over the launcher (Pixel 9, r3a; 1.9.0 has the same router and auto-enter, so
     * the same race). A later link expanded it again, because a relaunch of an ENTERED PiP task
     * expands. So: a video routed in while we are leaving (paused, not yet in PiP) marks the PiP
     * that follows as unwanted, and onPictureInPictureModeChanged expands it straight back.
     * Cleared by onResume (the normal case: the routed-in open simply brings us to the front).
     */
    private boolean mRoutedInWhileLeaving;
    private int mRoutedInRestoreAttempts;
    private final Runnable mRoutedInRestore = this::restoreRoutedInPip;

    /**
     * Expand a PiP the routed-in link started. Our mode-change callback can arrive while the PiP
     * shell is still animating the entry, and it ignores a relaunch until that ends - so re-check
     * shortly after, a bounded number of times.
     */
    private void restoreRoutedInPip() {
        if (!mIsInPip || isFinishing() || isDestroyed() || mRoutedInRestoreAttempts >= 3) {
            return;
        }
        mRoutedInRestoreAttempts++;
        logPip("restore reason=routed-in-during-pip-entry attempt=" + mRoutedInRestoreAttempts);
        SystemPipBridge.restore(this);
        Utils.postDelayed(mRoutedInRestore, 500);
    }

    private boolean isInPipModeNow() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode();
    }

    /** NEWTUBE(theme): the one player instance, for {@link #isCoveringScreens}. */
    private static java.lang.ref.WeakReference<MobilePlaybackActivity> sCurrent = new java.lang.ref.WeakReference<>(null);

    /**
     * NEWTUBE(theme): the full-window player is on screen (started, not minimized, not in PiP), so
     * the screen under it is paused but visible. Relaunching that screen there is not safe: the
     * relaunch passes through onResume, whose ViewManager.addTop drops the player from the view
     * stack, and the next minimize never completed (a frozen morph over a blank page). A theme
     * change waits for that screen's own onResume instead (MobileActivity.onThemeChanged).
     */
    public static boolean isCoveringScreens() {
        MobilePlaybackActivity player = sCurrent.get();
        return player != null && !player.mIsStopped && !player.isFinishing() && !player.isDestroyed()
                && !player.isInPipModeNow();
    }

    /** Pinned as far as the launcher restore is concerned (see {@link #mPipStateStale}). */
    boolean isPinnedForRestore() {
        return pinnedForRestore(isInPipModeNow(), mPipStateStale);
    }

    static boolean pinnedForRestore(boolean platformSaysPinned, boolean stateStale) {
        return platformSaysPinned && !stateStale;
    }

    /** Decision half of {@link #mRoutedInWhileLeaving}; our own expand request never counts. */
    static boolean routedInWhileLeaving(boolean resumed, boolean inPip, boolean ownRestoreRequest) {
        return !resumed && !inPip && !ownRestoreRequest;
    }

    private void bindViews() {
        mContainer = findViewById(R.id.mobile_player_container);
        mVideoArea = findViewById(R.id.mobile_video_area);
        mZoomHintView = findViewById(R.id.mobile_player_zoom_hint);
        mPlayerView = findViewById(R.id.mobile_player_view);
        // NEWTUBE(seek bar): the video box leaves its children unclipped so that the seek bar's dot
        // can hang over the page - but the picture must stay in the box: zoomed to fill, a vertical
        // video's frame is far taller than the portrait box and spilled over the page.
        mPlayerView.setOutlineProvider(android.view.ViewOutlineProvider.BOUNDS);
        mPlayerView.setClipToOutline(true);
        mYouTubeOverlay = findViewById(R.id.mobile_player_yt_overlay);
        mControlsRoot = findViewById(R.id.mobile_controls_root);
        mTopScrim = findViewById(R.id.mobile_player_top_scrim);
        mBottomScrim = findViewById(R.id.mobile_player_bottom_scrim);
        mTitleView = findViewById(R.id.mobile_player_title);
        mTitleView.setOnClickListener(v -> showDescriptionSheet()); // MEETUBE(issue #164)
        mPlayerAuthor = findViewById(R.id.mobile_player_author);
        mBackButton = findViewById(R.id.mobile_player_back);
        mAudioButton = findViewById(R.id.mobile_player_audio);
        mPlayPauseButton = findViewById(R.id.mobile_player_play_pause);
        mFullscreenButton = findViewById(R.id.mobile_player_fullscreen);
        mPositionView = findViewById(R.id.mobile_player_position);
        mDurationView = findViewById(R.id.mobile_player_duration);
        mLiveChip = findViewById(R.id.mobile_player_live);
        mLiveChip.setOnClickListener(v -> jumpToLiveEdge());
        mTimeBar = findViewById(R.id.mobile_player_time_bar);
        mBottomRow = findViewById(R.id.mobile_player_bottom_row);
        mActionsRow = findViewById(R.id.mobile_player_actions);
        mActionsToggle = findViewById(R.id.mobile_player_actions_toggle);
        mPlayerLike = findViewById(R.id.mobile_player_like);
        mPlayerDislike = findViewById(R.id.mobile_player_dislike);
        mPlayerDownload = findViewById(R.id.mobile_player_download);
        mPlayerComments = findViewById(R.id.mobile_player_comments);
        mPlayerChat = findViewById(R.id.mobile_player_chat);
        mPlayerLock = findViewById(R.id.mobile_player_lock);
        mPlayerUnlock = findViewById(R.id.mobile_player_unlock);
        mPlayerLock.setOnClickListener(v -> setPlayerLocked(true));
        mPlayerUnlock.setOnClickListener(v -> setPlayerLocked(false));
        // the lock floats anywhere but over a control: every one in the overlay, and the seek bar
        java.util.List<View> lockObstacles = new java.util.ArrayList<>();
        lockObstacles.add(mTimeBar);
        ViewGroup controls = (ViewGroup) mControlsRoot;
        for (int i = 0; i < controls.getChildCount(); i++) {
            View child = controls.getChildAt(i);
            if (child != mTopScrim && child != mBottomScrim && child != mPlayerLock) {
                lockObstacles.add(child);
            }
        }
        mFloatingLock = new FloatingLock(getSharedPreferences(PLAYER_UI_PREFS, MODE_PRIVATE),
                lockObstacles.toArray(new View[0]), mPlayerLock, mPlayerUnlock);
        mActionsShown = getSharedPreferences(PLAYER_UI_PREFS, MODE_PRIVATE).getBoolean(KEY_ACTIONS_SHOWN, true);
        mTimeRemaining = getSharedPreferences(PLAYER_UI_PREFS, MODE_PRIVATE).getBoolean(KEY_TIME_REMAINING, false);
        findViewById(R.id.mobile_player_time).setOnClickListener(v -> {
            mTimeRemaining = !mTimeRemaining;
            getSharedPreferences(PLAYER_UI_PREFS, MODE_PRIVATE).edit().putBoolean(KEY_TIME_REMAINING, mTimeRemaining).apply();
            if (mExoPlayerController != null) {
                setTextIfChanged(mPositionView, positionText(mExoPlayerController.getPositionMs(), getDurationMs()));
            }
        });
        mTransport = findViewById(R.id.mobile_player_transport);
        mOptionsRow = findViewById(R.id.mobile_player_options);
        mTopPill = findViewById(R.id.mobile_player_top_pill);
        mLevelPill = findViewById(R.id.mobile_player_level_pill);
        mLevelIcon = findViewById(R.id.mobile_player_level_icon);
        mLevelBar = findViewById(R.id.mobile_player_level_bar);
        mProgressBar = findViewById(R.id.mobile_player_progress);
        mSpinnerShown = mProgressBar != null && mProgressBar.getVisibility() == View.VISIBLE;
        syncPlayPauseWithSpinner();
        mSetupHint = findViewById(R.id.mobile_player_setup_hint);
        mNoticeView = findViewById(R.id.mobile_player_notice);
        mSkipSegmentPill = findViewById(R.id.mobile_player_skip_segment);
        mNextCountdown = new NextCountdown(findViewById(R.id.mobile_next_countdown), this::isPlaying, () -> {
            setPositionMs(0);
            setPlayWhenReady(true);
        }, () -> showControls(true));
        mCastButton = findViewById(R.id.mobile_player_cast);
        mMoreVideos = new MoreVideosPanel(findViewById(R.id.mobile_more_videos), this::onRelatedClicked);
        mCastOverlay = findViewById(R.id.mobile_cast_overlay);
        mCastOverlayTitle = findViewById(R.id.mobile_cast_overlay_title);
        mCastPlayPause = findViewById(R.id.mobile_cast_play_pause);
        mCastLiveChip = findViewById(R.id.mobile_cast_live);
        mCastTimeline = findViewById(R.id.mobile_cast_timeline);
        mCastPosition = findViewById(R.id.mobile_cast_position);
        mCastDuration = findViewById(R.id.mobile_cast_duration);
        mCastSeekBar = findViewById(R.id.mobile_cast_seekbar);
        mSubtitlesButton = findViewById(R.id.mobile_player_subtitles);
        mMoreButton = findViewById(R.id.mobile_player_more);
        mPrevButton = findViewById(R.id.mobile_player_previous);
        mNextButton = findViewById(R.id.mobile_player_next);
        // MEETUBE(glass): every control over the video sits on a see-through tinted disc (time: a
        // pill); with Frosted player buttons on, a frosted disc of the video instead. Over HDR's
        // SurfaceView (#35) there is nothing to frost, so the tint alone. MEETUBE(minimal buttons):
        // with Player buttons on Minimal, no discs at all (applyButton).
        com.newtube.mobile.ui.common.GlassSource videoGlass =
                com.newtube.mobile.ui.common.Glass.isPlayerFrost(this) ? findViewById(R.id.mobile_player_glass) : null;
        for (View control : new View[] {mBackButton, mAudioButton, mCastButton, mSubtitlesButton, mMoreButton,
                mPrevButton, mNextButton, mFullscreenButton, mActionsToggle}) {
            com.newtube.mobile.ui.common.GlassDrawable.applyButton(control, videoGlass, dp(4), true);
        }
        // MEETUBE(toasts): the player's own message pills (zoom, 2x, brightness/volume, chapter,
        // notices, skip) frosted like the app's message pill: the video under them, blurred, always -
        // each only costs while it shows.
        int pillTint = ContextCompat.getColor(this, R.color.meetube_toast_glass);
        for (View pill : new View[] {mZoomHintView, mTopPill, mLevelPill, mNoticeView,
                findViewById(R.id.mobile_player_scrub_chapter), mSkipSegmentPill}) {
            if (pill != null) {
                com.newtube.mobile.ui.common.GlassDrawable.apply(pill,
                        findViewById(R.id.mobile_player_glass), 0, pillTint);
            }
        }
        View time = findViewById(R.id.mobile_player_time);
        for (View control : new View[] {mPlayPauseButton, mActionsRow, mPlayerLock, mPlayerUnlock}) {
            com.newtube.mobile.ui.common.GlassDrawable.applyButton(control, videoGlass, 0, true);
        }
        // the time keeps its see-through pill in every style, Minimal too
        com.newtube.mobile.ui.common.GlassDrawable.apply(time, videoGlass, 0);
        if (videoGlass != null) {
            mGlassControls = new View[] {mBackButton, mAudioButton, mCastButton, mSubtitlesButton, mMoreButton,
                    mPrevButton, mNextButton, mFullscreenButton, mActionsToggle, mPlayPauseButton, time, mActionsRow,
                    mPlayerLock, mPlayerUnlock};
        }
        mDebugViewGroup = findViewById(R.id.mobile_player_debug);

        // Watch page content column.
        mWatchRoot = findViewById(R.id.mobile_watch_root);
        mWatchRoot.addOnLayoutChangeListener(mWatchRootLayoutListener);
        mWatchScroll = findViewById(R.id.mobile_watch_scroll);
        mWatchFab = findViewById(R.id.mobile_watch_fullscreen_fab);
        if (isJoyLocalPage()) { // JOYBOY(v950): local file / direct link / browser video — no watch page at all (no subscribe/next skeleton, no FAB), black from the first frame
            mWatchRoot.setBackgroundColor(Color.BLACK);
            if (mWatchScroll != null) { mWatchScroll.setVisibility(View.GONE); }
            if (mWatchFab != null) { mWatchFab.setVisibility(View.GONE); }
        }
        if (mWatchFab != null) {
            mWatchFab.setOnClickListener(v -> {
                if (v.getAlpha() > 0.5f) {
                    toggleFullscreen();
                }
            });
        }
        mWatchContent = findViewById(R.id.mobile_watch_content);
        mWatchTitle = findViewById(R.id.mobile_watch_title);
        mWatchMeta = findViewById(R.id.mobile_watch_meta);
        mWatchDescription = findViewById(R.id.mobile_watch_description);
        mWatchLike = findViewById(R.id.mobile_watch_like);
        mWatchLikeIcon = findViewById(R.id.mobile_watch_like_icon);
        mWatchLikeCount = findViewById(R.id.mobile_watch_like_count);
        mWatchDislike = findViewById(R.id.mobile_watch_dislike);
        mWatchDislikeIcon = findViewById(R.id.mobile_watch_dislike_icon);
        mWatchDislikeCount = findViewById(R.id.mobile_watch_dislike_count);
        mWatchShare = findViewById(R.id.mobile_watch_share);
        mWatchMusic = findViewById(R.id.mobile_watch_music);
        mWatchMusic.setOnClickListener(v -> showLyricsSheet());
        mWatchSave = findViewById(R.id.mobile_watch_save);
        mWatchSaveIcon = findViewById(R.id.mobile_watch_save_icon);
        mWatchSaveLabel = findViewById(R.id.mobile_watch_save_label);
        mWatchDownload = findViewById(R.id.mobile_watch_download);
        mWatchDownloadIcon = findViewById(R.id.mobile_watch_download_icon);
        mWatchDownloadLabel = findViewById(R.id.mobile_watch_download_label);
        mWatchAvatar = findViewById(R.id.mobile_watch_avatar);
        mWatchChannelName = findViewById(R.id.mobile_watch_channel_name);
        mWatchSubs = findViewById(R.id.mobile_watch_subs);
        mWatchSubscribe = findViewById(R.id.mobile_watch_subscribe);
        mSubscribeTextColor = mWatchSubscribe.getCurrentTextColor();
        mSubscribeIcon = findViewById(R.id.mobile_watch_subscribe_icon);
        View actionsScroll = findViewById(R.id.mobile_watch_actions_scroll);
        if (actionsScroll != null) {
            // MEETUBE(issue #158): a light tick where the actions run out, either way, either layout.
            actionsScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
                if (x != oldX && (!v.canScrollHorizontally(1) || !v.canScrollHorizontally(-1))) {
                    Haptics.tick(v);
                }
            });
        }
        if (actionsScroll != null && mSubscribeIcon != null) {
            // By the finger's way, not the scroll position: the pill's own resize moves the scroll (a
            // narrow Subscribe can leave nothing to scroll), which flipped it back and forth.
            int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
            // A press on a pill (most of the row) keeps the down from this listener: the row only
            // hears the drag once it takes it over, so the first event it hears is the start.
            float[] downX = {Float.NaN};
            // MEETUBE(issue #158): one swipe shrinks Subscribe and scrolls the row with it (it used to
            // hold the row still while it shrank, so reaching the end took two swipes).
            actionsScroll.setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                if (Float.isNaN(downX[0])) {
                    downX[0] = event.getRawX();
                }
                float dx = event.getRawX() - downX[0];
                if (dx < -slop) {
                    morphSubscribe(true); // swiping the actions in
                } else if (dx > slop && v.getScrollX() == 0) {
                    morphSubscribe(false); // pulled back to the start
                }
                // MEETUBE(watch-look): the bounce - where the row has nothing to scroll that way, its
                // pills follow the finger a little (less the further it goes) and spring back on release.
                View pills = ((ViewGroup) v).getChildAt(0);
                boolean end = action == android.view.MotionEvent.ACTION_UP
                        || action == android.view.MotionEvent.ACTION_CANCEL;
                if (!end && pills != null && !v.canScrollHorizontally(dx < 0 ? 1 : -1)) {
                    float max = dp(28);
                    pills.animate().cancel();
                    pills.setTranslationX(max * dx / (Math.abs(dx) + max * 3));
                }
                if (end) {
                    if (pills != null && pills.getTranslationX() != 0f) {
                        pills.animate().translationX(0f).setDuration(420)
                                .setInterpolator(new android.view.animation.OvershootInterpolator(2.4f)).start();
                    }
                    downX[0] = Float.NaN;
                }
                return false;
            });
        }
        mWatchRelatedLabel = findViewById(R.id.mobile_watch_related_label);
        mRelatedSkeleton = findViewById(R.id.mobile_watch_related_skeleton);
        mWatchRelated = findViewById(R.id.mobile_watch_related);
        mRelatedChips = findViewById(R.id.mobile_watch_related_chips);
        mRelatedTrack = findViewById(R.id.mobile_watch_related_track);
        mRelatedTrack.setGlassSource(null);
        mRelatedTrack.setOnPicked(index -> {
            if (index < mTrackChips.size()) {
                onRelatedChip(mTrackChips.get(index));
            }
        });
        mAllChip = findViewById(R.id.mobile_watch_chip_all);
        mChannelChip = findViewById(R.id.mobile_watch_chip_channel);
        mNewChip = findViewById(R.id.mobile_watch_chip_new);
        mWatchedChip = findViewById(R.id.mobile_watch_chip_watched);
        mAllChip.setText(R.string.mobile_watch_chip_all);
        mNewChip.setText(R.string.mobile_watch_chip_new);
        mWatchedChip.setText(R.string.mobile_watch_chip_watched);
        mRelatedChipViews = new TextView[] {mAllChip, mChannelChip, mNewChip, mWatchedChip};
        mPickedChip = mAllChip;
        for (TextView chip : mRelatedChipViews) {
            // MEETUBE(issue #105): glass, as Search's chips; the page's own colours behind it.
            com.newtube.mobile.ui.common.GlassDrawable.apply(chip, null, 0);
            chip.setTag(R.id.mobile_watch_related_chips, chip.getBackground());
            chip.setOnClickListener(v -> onRelatedChip((TextView) v));
        }
        syncRelatedChips();
        mQueueCard = findViewById(R.id.mobile_watch_queue_card);
        mQueueHeader = findViewById(R.id.mobile_watch_queue_header);
        mQueueTitle = findViewById(R.id.mobile_watch_queue_title);
        mQueueSubtitle = findViewById(R.id.mobile_watch_queue_subtitle);
        mQueueChevron = findViewById(R.id.mobile_watch_queue_chevron);
        mQueueList = findViewById(R.id.mobile_watch_queue_list);
        mWatchCommentsEntry = findViewById(R.id.mobile_watch_comments_entry);
        View shortsOverlay = findViewById(R.id.mobile_shorts_overlay);
        ViewCompat.setOnApplyWindowInsetsListener(shortsOverlay, (v, insets) -> {
            // MEETUBE(shorts): the content is edge to edge only while a short fills the screen.
            Insets bars = isShortsMode() ? insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()) : Insets.NONE;
            insetShortsOverlay((ViewGroup) v, bars.top, bars.bottom);
            // The short fills the screen between the bars (cropped, as before); the ambient fill
            // takes the bars' strips and the video's edges fade into it.
            View glass = findViewById(R.id.mobile_player_glass); // the video's box (MEETUBE(glass))
            FrameLayout.LayoutParams video = (FrameLayout.LayoutParams) glass.getLayoutParams();
            if (video.topMargin != bars.top || video.bottomMargin != bars.bottom) {
                video.topMargin = bars.top;
                video.bottomMargin = bars.bottom;
                glass.setLayoutParams(video);
            }
            if (mShorts != null) {
                mShorts.alignPeek(bars.top, bars.bottom);
            }
            mVideoArea.setEdgeFade(isShortsMode() ? glass : null,
                    mPlayerView != null ? mPlayerView.getContentFrame() : null,
                    dp(24));
            return insets;
        });
        mShorts = new ShortsOverlay(getWindow().getDecorView(), mVideoArea, new ShortsOverlay.Host() {
            @Override
            public void onShortsModeChanged(boolean on) {
                // No double-tap seek ripple over a short: double tap likes it instead.
                mPlayerView.controller(on ? null : mYouTubeOverlay);
                applyShortsLayout();
                if (on) {
                    hideControls();
                } else if (mShortsMuted) {
                    toggleShortsMute(); // a long video after the shorts plays with its sound
                    mShorts.showMuted(false);
                }
            }

            @Override
            public void playNextShort() {
                if (mPresenter != null) {
                    mPresenter.onNextClicked();
                }
            }

            @Override
            public void playPreviousShort() {
                if (mPresenter != null) {
                    mPresenter.onPreviousClicked();
                }
            }

            @Override
            public void freezeShortFrame(boolean freeze) {
                setShortFreeze(freeze);
            }

            @Override
            public void openShortsComments() {
                if (mCommentsKey == null || mCommentsPanel == null) {
                    return; // comments still loading (or off for this short)
                }
                mShorts.setCommentsOpen(true);
                if (isLandscape()) { // #57: beside the short, as beside a fullscreen video
                    setCommentsSide(true);
                    OneShotPreDrawListener.add(mWatchRoot, MobilePlaybackActivity.this::onCommentsEntryClicked);
                    return;
                }
                applyShortsLayout();
                onCommentsEntryClicked();
            }

            @Override
            public void openShortsMore() {
                openPlayerMenu(); // quality, speed, captions... the gear sheet
            }

            @Override
            public void openShortsDescription() {
                showDescriptionSheet();
            }

            @Override
            public void seekShortTo(long positionMs) {
                if (mExoPlayerController != null) {
                    mExoPlayerController.setPositionMs(positionMs);
                }
            }

            @Override
            public void exitShorts() {
                getOnBackPressedDispatcher().onBackPressed();
            }

            @Override
            public boolean toggleShortsMute() {
                return MobilePlaybackActivity.this.toggleShortsMute();
            }

            @Override
            public void openShortsSearch() {
                com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter
                        .instance(MobilePlaybackActivity.this).startSearch(null);
            }
        }, shortsWatchViews());
        mWatchCommentsCount = findViewById(R.id.mobile_watch_comments_count);
        mCommentTeaser = findViewById(R.id.mobile_watch_comment_teaser);
        mCommentTeaserAvatar = findViewById(R.id.mobile_watch_comment_avatar);
        mCommentTeaserText = findViewById(R.id.mobile_watch_comment_text);
        mWatchChatEntry = findViewById(R.id.mobile_watch_chat_entry);
        mScrubChapterView = findViewById(R.id.mobile_player_scrub_chapter);
        mSeekFilmstrip = findViewById(R.id.mobile_player_seek_filmstrip);
        mChapterButton = findViewById(R.id.mobile_player_chapter);
        mChapterButton.setOnClickListener(v -> showChaptersSheet());
    }

    private void setupControls() {
        mContainer.setSwipeListener(this);
        // Only let a swipe begin over the video box, so the watch content scrolls freely.
        mContainer.setDragStartBoundView(mVideoArea);
        mSwipeLevels = new SwipeLevels(this, getWindow(), mContainer, new SwipeLevels.Pill() {
            /**
             * Shown or fading in. Every move of the finger updates the level, and a fade restarted
             * that often never gets past its first frame: the pill stayed invisible until the
             * finger stopped.
             */
            private boolean mShown;

            @Override
            public void showLevel(int iconRes, float level) {
                if (mLevelIcon != null && mLevelBar != null) {
                    mLevelIcon.setImageResource(iconRes);
                    mLevelBar.setLevel(level);
                }
                if (!mShown) {
                    mShown = true;
                    fadePill(mLevelPill, true);
                }
            }

            @Override
            public void hideLevel() {
                mShown = false;
                fadePill(mLevelPill, false);
            }
        });

        // Pinch on the video = YouTube's zoom-to-fill toggle in landscape/fullscreen
        // (applyWatchLayoutForOrientation), like the official app; past it, and everywhere else
        // (the portrait player, Shorts), a free zoom of the picture (issue #55).
        mVideoArea.setPinchListener(new PinchZoomLayout.PinchListener() {
            @Override
            public boolean onPinchZoom(boolean zoomIn) {
                return MobilePlaybackActivity.this.onPinchZoom(zoomIn);
            }

            @Override
            public void onZoomChanged(float zoom) {
                showZoomHint(zoom == 1f ? getString(R.string.mobile_player_zoom_original)
                        : String.format(Locale.US, "%.1fx", zoom));
            }
        });
        mVideoArea.setZoomTarget(mPlayerView.getContentFrame());
        mVideoArea.setPinchEnabled(true);

        // PLAYER LAYOUT POLISH. The controls overlay fills the video box.
        //  * LANDSCAPE/fullscreen: the video is full-bleed to the screen edges, so inset the whole
        //    overlay by the system bars (notch/status/nav) so the back/title and the seek row never
        //    hide under, or get their taps eaten by, the status/navigation bars.
        //  * PORTRAIT: the decor fits the system windows (see applyMobileSystemBars) - the video box
        //    starts below a solid status bar, YouTube-style, so the window insets are already
        //    consumed and the controls just anchor FLUSH to the video box (no extra padding).
        ViewCompat.setOnApplyWindowInsetsListener(mControlsRoot, (v, insets) -> {
            applyControlsInsets();
            return insets;
        });
        // Re-run on every size change too: the insets pass alone proved unreliable across the
        // fullscreen rotation (it can fire before the window has its landscape size, leaving the
        // controls pinned to the far screen corners).
        mControlsRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> {
            if ((r - l) != (or_ - ol) || (b - t) != (ob - ot)) {
                applyControlsInsets();
            }
            fitChapterButton();
        });
        // ...and on the video box's, which the controls fill: hidden controls are GONE, never laid
        // out, so a rotation while they were away (the phone turned; a fullscreen swipe puts them
        // away first) reached neither pass with the new size. The seek bar kept the other
        // orientation's place - inset and lifted inside the portrait video, or a fullscreen strip
        // measured on the portrait box (seen on the emulator 2026-10-02, swipe or not).
        mVideoArea.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> {
            if ((r - l) != (or_ - ol) || (b - t) != (ob - ot)) {
                applyControlsInsets();
            }
            if (mShortsCommentsGone >= 0f) {
                followShortsComments(mShortsCommentsGone); // MEETUBE(shorts): the box's new size
            }
        });

        // No extra system-bar padding on the watch page itself: in portrait the Activity content
        // container already applies the top/bottom safe insets (MobileActivity.installContentInsets;
        // under enforced edge-to-edge the insets arrive UNconsumed, so padding here again doubled
        // the bottom margin), and in landscape the content column is GONE and the video is
        // deliberately full-bleed.

        // Single tap on the video surface toggles the overlay. DoubleTapPlayerViewImpl routes a
        // single (non-double) tap to performClick() on the view captured at construction (itself,
        // since it isn't attached yet), so an OnClickListener here is exactly that single tap.
        mPlayerView.setOnClickListener(v -> {
            if (isShortsMode()) {
                mVideoArea.removeCallbacks(mShortsSingleTap);
                // MEETUBE(issue #216): from the release, about the rest of the system's 300 ms
                // double-tap window (counted from the touch down), so a pause follows the finger.
                mVideoArea.postDelayed(mShortsSingleTap, 200);
                return;
            }
            if (mMoreVideos != null && mMoreVideos.isShown()) {
                mMoreVideos.close(); // MEETUBE(more videos): a tap on the video above it puts it away
                return;
            }
            boolean reveal = !mControlsVisible;
            toggleControls();
            mInstantRevealAt = reveal ? android.os.SystemClock.uptimeMillis() : 0L;
        });
        // NEWTUBE(motion): ...on the tap's release, like YouTube - it used to wait out the double-tap
        // timeout (~300 ms of a tap doing nothing). A second tap makes it a double tap after all:
        // a seek puts the controls away (the seek ripple takes the screen), anything else undoes
        // the first tap's toggle.
        mPlayerView.setInstantSingleTap(true);
        // MEETUBE(haptics): the video's own taps stay silent (a tap shows the controls); its
        // double-tap seeks click per seek (YouTubeOverlay), its hold buzzes (beginHoldSpeed).
        mPlayerView.setHapticFeedbackEnabled(false);
        // NEWTUBE(hold-speed): press and hold the video = 2x for as long as the finger stays,
        // YouTube's gesture, with its firm buzz and a "2x" pill; letting go returns to the speed
        // that was chosen. Never saved as a speed.
        mPlayerView.setHoldListener(new DoubleTapPlayerViewImpl.HoldListener() {
            @Override
            public boolean onHoldStart(float x, float y) {
                if (isShortsMode() && mShorts != null) {
                    // MEETUBE(shorts): a held short shows only itself; held in its top-right
                    // quarter, also at 2x (where it can).
                    mShorts.setClean(true);
                    mShortsStayClean = !(x > mPlayerView.getWidth() / 2f && y < mPlayerView.getHeight() / 2f);
                    if (!mShortsStayClean) {
                        beginHoldSpeed();
                    }
                    return true;
                }
                return beginHoldSpeed();
            }

            @Override
            public void onHoldEnd() {
                endHoldSpeed();
                if (mShorts != null && !mShortsStayClean) { // a clean hold stays clean until the next touch
                    mShorts.setClean(false);
                }
            }
        });
        mPlayerView.setDoubleTapBeganListener(posX -> {
            if (isShortsMode()) {
                mVideoArea.removeCallbacks(mShortsSingleTap);
                likeShort();
                return;
            }
            if (mPlayer != null && doubleTapSeekForward(mPlayer, posX) != null) {
                hideControls();
            } else {
                toggleControls();
            }
        });

        // Tap on empty overlay space hides the controls (buttons/seek bar consume their own taps).
        mControlsRoot.setOnClickListener(v -> hideControls());
        mControlsRoot.setHapticFeedbackEnabled(false); // MEETUBE(haptics): empty overlay space is the video's
        // NEWTUBE(motion): empty overlay space passes its touches to the player's tap detector (the
        // two views cover the same box), so the first tap's controls do not swallow the second tap
        // of a double tap, and a double tap seeks with the controls up too. A single tap still
        // hides them (the detector's click toggles). The click listener above stays for TalkBack.
        mControlsRoot.setOnTouchListener((v, event) -> mPlayerView.onTouchEvent(event));
        // A tap right after one that revealed the controls may be the second of a double tap: it
        // goes to the detector whole, not to a control that just appeared under the finger. Except
        // play/pause, like YouTube (measured 21.18): a quick double tap on the middle of a hidden
        // player pauses or plays it; the sides still seek.
        mVideoArea.setTapRouter(new PinchZoomLayout.TapRouter() {
            private final int[] mAreaAt = new int[2];
            private final int[] mButtonAt = new int[2];

            @Override
            public boolean claimDown(android.view.MotionEvent down) {
                return isSecondTapOfReveal(down) && !isOnPlayPause(down);
            }

            private boolean isOnPlayPause(android.view.MotionEvent down) {
                if (mPlayPauseButton == null || !mPlayPauseButton.isShown()) {
                    return false;
                }
                mVideoArea.getLocationInWindow(mAreaAt);
                mPlayPauseButton.getLocationInWindow(mButtonAt);
                float x = down.getX() + mAreaAt[0] - mButtonAt[0];
                float y = down.getY() + mAreaAt[1] - mButtonAt[1];
                return x >= 0 && x < mPlayPauseButton.getWidth()
                        && y >= 0 && y < mPlayPauseButton.getHeight();
            }

            @Override
            public void route(android.view.MotionEvent event) {
                mPlayerView.onTouchEvent(event);
            }
        });
        // NEWTUBE(seek bar): the seek bar's touch band - YouTube's, ~20 dp above the track to 16 dp
        // below it, over the page in portrait - is hit-tested on the watch column, which holds both
        // the video box and the page; the bar's own 18 dp view takes no touches directly.
        mTimeBar.setTouchRouted(true);
        if (mWatchRoot instanceof WatchRootLayout) {
            ((WatchRootLayout) mWatchRoot).setTouchRouter(new SeekBandRouter());
            ((WatchRootLayout) mWatchRoot).setPageScroll(this::collapseTallBox);
        }

        mBackButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        mAudioButton.setOnClickListener(v -> setAudioOnly(!mAudioOnly));
        mPlayPauseButton.setOnClickListener(v -> togglePlayPause());
        mFullscreenButton.setOnClickListener(v -> toggleFullscreen());

        // Manual previous/next skip (auto-advance already handled by the controllers).
        if (mPrevButton != null) {
            mPrevButton.setOnClickListener(v -> {
                if (mPresenter != null) {
                    stepVideo(-1); // JOYBOY(v937)
                }
                armAutoHide();
            });
        }
        if (mNextButton != null) {
            mNextButton.setOnClickListener(v -> {
                if (mPresenter != null) {
                    stepVideo(1); // JOYBOY(v937)
                }
                armAutoHide();
            });
        }

        // Gear menu: quality/speed/PiP plus the long tail of SmartTube player actions
        // (see openPlayerMenu). The top-right row stays a YouTube-style trio: cast, CC, gear.
        if (mMoreButton != null) {
            mMoreButton.setOnClickListener(v -> openPlayerMenu());
        }

        // Cast picker (Route B). The button stays visible even while the sender implementation is
        // pending in the submodule - the picker degrades to browse-only with a toast on connect.
        if (mCastButton != null) {
            mCastButton.setOnClickListener(v -> openCastPicker());
        }
        setupCastOverlay();
        // CC tap toggles captions like the official app (last-used track <-> off; first use falls
        // through to the picker). The full track picker is the native captions sheet, reachable via
        // long-press here and the gear menu's Subtitles row - see showCaptionsSheet().
        mSubtitlesButton.setOnClickListener(v -> toggleCaptions());
        mSubtitlesButton.setOnLongClickListener(v -> {
            showCaptionsSheet();
            return true;
        });

        mTimeBar.addListener(new TimeBar.OnScrubListener() {
            @Override
            public void onScrubStart(TimeBar timeBar, long position) {
                mScrubbing = true;
                // Grabbing the bar definitively ends any double-tap seek burst: make sure the
                // release seek below resolves with the bounded default, not a leaked directional
                // NEXT/PREVIOUS_SYNC (see the seek-burst watchdog doc).
                endUserSeekBurst();
                cancelAutoHide();
                updateScrubLabel(position);
                setScrubChrome(true);
            }

            @Override
            public void onScrubMove(TimeBar timeBar, long position) {
                updateScrubLabel(position);
            }

            @Override
            public void onScrubStop(TimeBar timeBar, long position, boolean canceled) {
                mScrubbing = false;
                setScrubChrome(false);
                if (!canceled) {
                    setTextIfChanged(mPositionView, positionText(position, getDurationMs()));
                    updateChapterButton(position);
                }
                if (!canceled && mExoPlayerController != null) {
                    // Plain seek -> the player's mobile default, the bounded 5s/1s tolerance
                    // set at player creation (see that comment for the EXACT-vs-PREVIOUS_SYNC
                    // measurements this replaces).
                    mExoPlayerController.setPositionMs(position);
                }
                armAutoHide();
            }
        });
        // Dragged back onto where playback was: letting go cancels (the bar snaps there with a click).
        mTimeBar.setCancelListener(armed ->
                showTopPill(armed ? getString(R.string.mobile_player_release_to_cancel) : null));

        // Start with the controls visible so the back button / title are immediately reachable on
        // open; the auto-hide timer takes them away once playback is actually running.
        mControlsVisible = true;
        mControlsRoot.setVisibility(View.VISIBLE);
        mControlsRoot.setAlpha(1f);
        armAutoHide();
    }

    private void setupWatchContent() {
        // NEWTUBE(touch-prefetch, experiment): off unless a debug/benchmark build sets
        // debug.arc.touch_prefetch_ms (see SwitchExperiments); release builds always pass 0.
        long touchPrefetchMs = com.newtube.mobile.player.SwitchExperiments.touchPrefetchStillMs();
        mRelatedAdapter = new RelatedVideoAdapter(this::onRelatedClicked,
                touchPrefetchMs > 0 ? this::onRelatedPressed : null, touchPrefetchMs);
        mWatchRelated.setLayoutManager(new LinearLayoutManager(this));
        mWatchRelated.setNestedScrollingEnabled(false);
        mWatchRelated.setHasFixedSize(false);
        // NEWTUBE(watch-jump): no add/remove fade. The rows' first frame was an alpha-0 fade-in
        // start drawn in the same pass that hid the skeleton, and on a busy main thread (binding a
        // dozen rows while the video starts) that empty frame stayed up ~1 s.
        mWatchRelated.setItemAnimator(null);
        // MEETUBE(list layout): Up next follows Customization > Video layout, like every feed.
        mRelatedAdapter.setCards(!com.newtube.mobile.ui.browse.HomeLayout.isVideoList(this));
        mWatchRelated.setAdapter(mRelatedAdapter);

        // Queue list: same row layout and same click routing as Up next, but it scrolls INSIDE the
        // card (nested scrolling on + a max height) so a long playlist can't push the rest of the
        // watch page off the bottom. Rows are re-bound on every video change, so no fixed size.
        mQueueAdapter = new RelatedVideoAdapter(this::onRelatedClicked);
        mQueueList.setLayoutManager(new LinearLayoutManager(this));
        mQueueList.setHasFixedSize(false);
        mQueueList.setAdapter(mQueueAdapter);
        mQueueList.setMaxHeight(Math.round(getResources().getDisplayMetrics().heightPixels * 0.5f));
        mQueueHeader.setOnClickListener(v -> toggleQueueExpanded());

        // Expandable description: tap the title or the views/date row (MEETUBE: no chevron).
        findViewById(R.id.mobile_watch_header).setOnClickListener(v -> showDescriptionSheet());

        // Actions row. Like/Dislike/Subscribe go through the presenter's onButtonClicked vocabulary
        // (R.id.action_*); the controller flips the visual state back via setButtonState. Share fires
        // a plain ACTION_SEND of the video url (per brief), independent of the presenter.
        // NEWTUBE(snackbar): signed out, Like/Dislike/Save explain themselves with a Sign in action
        // (WatchActionFeedback); Subscribe confirms with the channel's name and Undo.
        mWatchLike.setOnClickListener(v -> {
            if (!WatchActionFeedback.blockIfSignedOut(this, R.string.mobile_sign_in_to_rate)) {
                onRateTapped(R.id.action_thumbs_up);
            }
        });
        mWatchDislike.setOnClickListener(v -> {
            if (!WatchActionFeedback.blockIfSignedOut(this, R.string.mobile_sign_in_to_rate)) {
                onRateTapped(R.id.action_thumbs_down);
            }
        });
        mWatchSubscribe.setOnClickListener(v -> onSubscribeTapped());
        mWatchShare.setOnClickListener(v -> shareCurrentVideo(false));
        mWatchShare.setOnLongClickListener(v -> shareCurrentVideo(true)); // from this second (#68)
        // Save opens the same add/remove-from-playlist sheet as gear -> More -> Save to playlist.
        mWatchSave.setOnClickListener(v -> {
            if (!WatchActionFeedback.blockIfSignedOut(this, R.string.msg_sign_in_to_save)) {
                openPlayerOption(R.id.action_playlist_add, false);
            }
        });
        // Download: the quality picker, or the download's own menu once it is on the device.
        mWatchDownload.setOnClickListener(v -> onDownloadTapped());
        mWatchDownload.setOnLongClickListener(v -> onDownloadLongPressed());
        DownloadRegistry.instance(this).addListener(mDownloadsListener);
        // MEETUBE(fullscreen-actions): the fullscreen pill taps the page's own buttons. Comments open
        // beside the landscape video; the vertical fullscreen has no room, so it leaves for the page.
        mPlayerLike.setOnClickListener(v -> mWatchLike.performClick());
        mPlayerDislike.setOnClickListener(v -> mWatchDislike.performClick());
        mPlayerDownload.setOnClickListener(v -> onDownloadTapped());
        mPlayerDownload.setOnLongClickListener(v -> onDownloadLongPressed());
        View playerShare = findViewById(R.id.mobile_player_share);
        playerShare.setOnClickListener(v -> shareCurrentVideo(false));
        playerShare.setOnLongClickListener(v -> shareCurrentVideo(true));
        mPlayerComments.setOnClickListener(v -> {
            if (isLandscape()) { // beside the video, YouTube's
                hideControls();
                setCommentsSide(true);
                OneShotPreDrawListener.add(mWatchRoot, this::onCommentsEntryClicked);
            } else {
                // Upright, straight back to the page: toggleFullscreen turns a 16:9 video sideways.
                mCommentsAfterFullscreen = true;
                mFullAfterComments = true;
                setPortraitFull(false);
            }
        });
        mPlayerChat.setOnClickListener(v -> {
            hideControls();
            onChatEntryClicked();
        });
        mActionsToggle.setOnClickListener(v -> {
            mActionsShown = !mActionsShown;
            getSharedPreferences(PLAYER_UI_PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIONS_SHOWN, mActionsShown).apply();
            android.transition.TransitionManager.beginDelayedTransition(mVideoArea);
            applyControlsInsets();
            armAutoHide();
        });

        // Channel row (avatar + name/subs) opens the channel page; the Subscribe button inside
        // the row keeps its own click. ChannelPresenter resolves the channelId from metadata
        // when the Video doesn't carry one yet, so this works right after a cold open too.
        View channelRow = findViewById(R.id.mobile_watch_channel_row);
        if (channelRow != null) {
            channelRow.setOnClickListener(v -> openCurrentChannel());
        }

        // Comments open the panel over the watch page; live chat opens its bottom sheet. The
        // panel is created here, after the player's back handler, so its own is asked first.
        mCommentsPanel = new CommentsPanel(this, findViewById(R.id.mobile_comments_panel), mCommentsHost);
        setupSidePanels();
        if (mWatchCommentsEntry != null) {
            mWatchCommentsEntry.setOnClickListener(v -> onCommentsEntryClicked());
        }
        if (mWatchChatEntry != null) {
            mWatchChatEntry.setOnClickListener(v -> onChatEntryClicked());
        }

        // Related-list paging: when the content is scrolled near the bottom, page the last row.
        mWatchScroll.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener)
                (v, scrollX, scrollY, oldX, oldY) -> {
                    View child = v.getChildAt(0);
                    if (child == null) {
                        return;
                    }
                    int distanceToBottom = child.getBottom() - (v.getHeight() + scrollY);
                    if (distanceToBottom <= SUGGESTIONS_PAGE_THRESHOLD_PX) {
                        maybePageSuggestions();
                    }
                });

        // Reflect the initial (empty) button states.
        updateButtonVisual(R.id.action_thumbs_up, BUTTON_OFF);
        updateButtonVisual(R.id.action_thumbs_down, BUTTON_OFF);
        updateButtonVisual(R.id.action_subscribe, BUTTON_OFF);
    }

    /**
     * MEETUBE(player): the page's small portrait player gets smaller buttons (40dp; the play / pause
     * keeps its size) so they cover less of its picture; fullscreen keeps the full-size ones.
     */
    private void sizePlayerControls(boolean compact) {
        int icon = compact ? dp(40) : getResources().getDimensionPixelSize(R.dimen.mobile_player_icon_button);
        int pad = compact ? dp(10) : dp(12);
        for (View button : new View[] {mBackButton, mAudioButton, mCastButton, mSubtitlesButton, mMoreButton,
                mPrevButton, mNextButton, mFullscreenButton}) {
            if (button != null) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) button.getLayoutParams();
                lp.width = icon;
                lp.height = icon;
                // JOYBOY(v954): the headphones now sit inside the options row beside the gear (no start margin)
                button.setLayoutParams(lp);
                button.setPadding(pad, pad, pad, pad);
            }
        }
    }

    /**
     * Portrait: 16:9 video box pinned at the top, scrollable watch content filling the rest.
     * Landscape: hide the content and let the video fill the whole screen (PLAYER POLISH immersive).
     */
    private void applyWatchLayoutForOrientation(int orientation) {
        com.newtube.mobile.ui.common.PlayerTrace.mark("layout for orientation=" + (orientation == Configuration.ORIENTATION_LANDSCAPE ? "LAND" : "PORT") + " tall=" + isTallMode()); // JOYBOY(v952)
        if (mVideoArea == null) {
            return;
        }

        if (isJoyLocalPage() && orientation != Configuration.ORIENTATION_LANDSCAPE && !mPortraitFull && !mIsInPip && !mPipEnterPending) {
            mPortraitFull = true; // JOYBOY(v950): upright local video = the vertical fullscreen from the first layout
            refreshContentInsets();
        }
        // MEETUBE(lyrics-panel): fullscreen and Shorts close the panels; returning shows the page.
        if (orientation == Configuration.ORIENTATION_LANDSCAPE
                || isTallMode() || (mShorts != null && mShorts.isActive())) {
            closeSidePanelsImmediately();
        }
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mVideoArea.getLayoutParams();

        // The fill / original snap is a fullscreen gesture, as YouTube's; the free zoom (#55) is
        // everywhere, and starts over at 1x when the phone turns.
        mVideoArea.setSnapEnabled(orientation == Configuration.ORIENTATION_LANDSCAPE);
        mVideoArea.resetZoom();
        sizePlayerControls(orientation != Configuration.ORIENTATION_LANDSCAPE && !mPortraitFull);

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (mPortraitFull) { // the landscape fullscreen takes over; upright again is the page
                mPortraitFull = false;
                setResizeMode(PlayerData.instance(this).getResizeMode()); // the pinch's choice again
            }
            if (mAmbient != null) {
                mAmbient.setFill(null);
            }
            lp.height = LinearLayout.LayoutParams.MATCH_PARENT;
            layOutCommentsSide(lp, mCommentsSide);
            mVideoArea.setLayoutParams(lp);
            if (mWatchScroll != null) {
                mWatchScroll.setVisibility(View.GONE);
            }
            if (mWatchFab != null && mCommentsSide) {
                mWatchFab.setVisibility(View.GONE);
            }
            // Fullscreen hides an open comments panel and keeps its place for portrait, unless
            // the pill opened it beside the video.
            if (mCommentsPanel != null) {
                mCommentsPanel.setSuspended(!mCommentsSide);
            }
            // The watch-page title is hidden with the content in fullscreen, so retain the compact
            // title in the player chrome there.
            if (mTitleView != null) {
                mTitleView.setVisibility(View.VISIBLE);
            }
        } else {
            // Configuration width is already updated when display metrics/window views can still
            // have the previous landscape bounds. The layout listener above then makes this exact
            // for split-screen, inset and resize changes once the new window has been laid out.
            Configuration config = getResources().getConfiguration();
            int width = Math.round(config.screenWidthDp
                    * getResources().getDisplayMetrics().density);
            if (mCommentsSide) { // an open panel goes back under the video
                mCommentsSide = false;
                setResizeMode(PlayerData.instance(this).getResizeMode());
            }
            layOutCommentsSide(lp, false);
            if (mChatSide) { // upright, the chat goes back under the video
                mChatSide = false;
                mChatMargin = 0;
                if (mChatSlide != null) {
                    mChatSlide.cancel();
                }
                lp.setMarginEnd(0);
                setResizeMode(PlayerData.instance(this).getResizeMode());
            }
            if (width > 0) {
                // MEETUBE(shorts): a short fills the portrait screen; the watch page steps aside.
                // So does any video in the vertical fullscreen (setPortraitFull).
                lp.height = isTallMode() ? LinearLayout.LayoutParams.MATCH_PARENT : inlineBoxHeight(width);
                lp.weight = 0;
                mVideoArea.setLayoutParams(lp);
            }
            // MEETUBE(shorts): a short's comments sit over a black page, not the watch page - so
            // sliding them down never shows the short as an ordinary video.
            boolean shorts = mShorts != null && mShorts.isActive();
            if (mWatchScroll != null) {
                mWatchScroll.setVisibility(isTallMode() || shorts ? View.GONE : View.VISIBLE);
            }
            if (mWatchFab != null) {
                mWatchFab.setVisibility(shorts || isJoyLocalPage() ? View.GONE : View.VISIBLE); // JOYBOY(v950)
            }
            if (mCommentsPanel != null) {
                mCommentsPanel.setSuspended(isTallMode());
                if (mCommentsAfterFullscreen && !isTallMode()) {
                    mCommentsAfterFullscreen = false; // MEETUBE(fullscreen-actions)
                    OneShotPreDrawListener.add(mWatchRoot, this::onCommentsEntryClicked);
                }
            }
            if (mDescriptionAfterFullscreen && !isTallMode()) { // MEETUBE(issue #217)
                OneShotPreDrawListener.add(mWatchRoot, this::showDescriptionSheet);
            }
            if (mChaptersAfterFullscreen && !isTallMode()) { // MEETUBE(issue #219)
                OneShotPreDrawListener.add(mWatchRoot, this::showChaptersSheet);
            }
            // Portrait already presents the complete title immediately below the video. Repeating
            // it in the overlay squeezes five useful controls into half the top bar and makes the
            // player look crowded, especially for two-line titles. The vertical fullscreen has no
            // page: the title shows there (placeTitle).
            if (mTitleView != null) {
                mTitleView.setVisibility(mPortraitFull ? View.VISIBLE : View.GONE);
            }
        }
    }

    /**
     * MEETUBE(fullscreen-actions): YouTube's landscape comments. While {@code side}, the page is a
     * row: the video on the left and, on the right, the watch area as a column that holds the
     * comments panel. Otherwise the usual column (in fullscreen the video takes all of it).
     */
    private void layOutCommentsSide(LinearLayout.LayoutParams video, boolean side) {
        View area = findViewById(R.id.mobile_watch_area);
        if (!(mWatchRoot instanceof LinearLayout) || area == null) {
            return;
        }
        ((LinearLayout) mWatchRoot).setOrientation(side ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        video.width = side ? 0 : LinearLayout.LayoutParams.MATCH_PARENT;
        video.weight = side ? 1 : 0;
        int width = mWatchRoot.getWidth() > 0 ? mWatchRoot.getWidth() : getResources().getDisplayMetrics().widthPixels;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) area.getLayoutParams();
        lp.width = side ? Math.round(width * 0.4f) : LinearLayout.LayoutParams.MATCH_PARENT;
        lp.height = side ? LinearLayout.LayoutParams.MATCH_PARENT : 0;
        lp.weight = side ? 0 : 1;
        // MEETUBE(live chat, issue #32): beside the video the comments float as a rounded card, like
        // the live chat's, a margin in from the edges and the video.
        int margin = side ? LiveChatSheet.cardMarginPx(area) : 0;
        lp.setMargins(margin, margin, margin, margin);
        area.setLayoutParams(lp);
        LiveChatSheet.roundCard(area, side);
        // MEETUBE(issue #214): behind a dragged panel, the video's own black, not a grey block.
        area.setBackgroundColor(side ? Color.BLACK : Color.TRANSPARENT);
    }

    /** The comments column beside the fullscreen video, or the video alone again. */
    private void setCommentsSide(boolean side) {
        if (mCommentsSide != side && (!side || isLandscape())) {
            mCommentsSide = side;
            // MEETUBE(issue #214): the picture glides aside as the panel comes up (and back as it
            // goes), as it does for the description card, instead of jumping first.
            ViewGroup frame = mPlayerView != null ? mPlayerView.getContentFrame() : null;
            int[] at = new int[2];
            float oldW = 0f, oldCx = 0f, oldCy = 0f;
            if (frame != null && frame.getWidth() > 0) {
                frame.animate().cancel();
                frame.getLocationOnScreen(at);
                oldW = frame.getWidth() * frame.getScaleX();
                oldCx = at[0] + oldW / 2f;
                oldCy = at[1] + frame.getHeight() * frame.getScaleY() / 2f;
                frame.setScaleX(1f);
                frame.setScaleY(1f);
                frame.setTranslationX(0f);
                frame.setTranslationY(0f);
            }
            applyWatchLayoutForOrientation(Configuration.ORIENTATION_LANDSCAPE);
            setResizeMode(PlayerData.instance(this).getResizeMode()); // fit beside the comments
            if (frame != null && oldW > 0f) {
                float w = oldW, cx = oldCx, cy = oldCy;
                OneShotPreDrawListener.add(frame, () -> {
                    if (frame.getWidth() == 0) {
                        return;
                    }
                    frame.getLocationOnScreen(at);
                    frame.setScaleX(w / frame.getWidth());
                    frame.setScaleY(w / frame.getWidth());
                    frame.setTranslationX(cx - (at[0] + frame.getWidth() / 2f));
                    frame.setTranslationY(cy - (at[1] + frame.getHeight() / 2f));
                    frame.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                            .setDuration(Motion.ENTER_MS).setInterpolator(Motion.STANDARD).start();
                });
            }
        }
    }

    /**
     * A pinch crossed the trigger ratio: snap between "fill the screen" (crop) and "original"
     * (fit). Writes the same PlayerData pair as the overflow "Zoom / aspect ratio" dialog
     * (AppDialogUtil.createVideoZoomCategory), so the gesture, the dialog selection and the
     * PlayerUIController restore-on-init all stay one setting.
     */
    private boolean onPinchZoom(boolean zoomIn) {
        int mode = zoomIn ? RESIZE_MODE_FIT_BOTH : RESIZE_MODE_DEFAULT;
        if (mode == getResizeMode()) {
            return false; // already there: a pinch out zooms on (issue #55)
        }
        // NEWTUBE(haptics): the pinch snapped into the other zoom - a tick as it crosses over.
        Haptics.tick(mVideoArea);
        PlayerData playerData = PlayerData.instance(this);
        playerData.setResizeMode(mode);
        playerData.setZoomPercents(-1);
        animateResizeMode(mode);
        showZoomHint(getString(zoomIn ? R.string.mobile_player_zoom_fill : R.string.mobile_player_zoom_original));
        return true;
    }

    /**
     * Apply a resize mode with YouTube's smooth grow/shrink instead of a one-frame snap: capture
     * the content frame's current VISUAL size, switch the mode, then on the first pre-draw of the
     * new layout start scaled to the old size and animate to 1. Aspect is preserved in both fit
     * and zoom modes, so a single uniform factor is exact.
     */
    private void animateResizeMode(int mode) {
        ViewGroup contentFrame = mPlayerView != null ? mPlayerView.getContentFrame() : null;
        mVideoArea.resetZoom(); // a new mode starts unzoomed (issue #55)
        if (contentFrame == null || contentFrame.getWidth() == 0 || getResizeMode() == mode) {
            setResizeMode(mode);
            return;
        }
        final float visualWidth = contentFrame.getWidth() * contentFrame.getScaleX();
        contentFrame.animate().cancel();
        setResizeMode(mode);
        OneShotPreDrawListener.add(contentFrame, () -> {
            if (contentFrame.getWidth() == 0) {
                return;
            }
            float startScale = visualWidth / contentFrame.getWidth();
            contentFrame.setScaleX(startScale);
            contentFrame.setScaleY(startScale);
            contentFrame.animate().scaleX(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator()).start();
        });
    }

    private final Runnable mHideZoomHint = new Runnable() {
        @Override
        public void run() {
            mZoomHintView.animate().alpha(0f).setDuration(250)
                    .withEndAction(() -> mZoomHintView.setVisibility(View.GONE)).start();
        }
    };

    /** Show the YouTube-style zoom chip, re-arming the fade-out if a pinch fires again mid-show. */
    private void showZoomHint(CharSequence text) {
        if (mZoomHintView == null) {
            return;
        }
        mZoomHintView.removeCallbacks(mHideZoomHint);
        mZoomHintView.animate().cancel();
        mZoomHintView.setText(text);
        if (mZoomHintView.getVisibility() != View.VISIBLE) {
            mZoomHintView.setAlpha(0f);
            mZoomHintView.setVisibility(View.VISIBLE);
        }
        mZoomHintView.animate().alpha(1f).setDuration(120).start();
        mZoomHintView.postDelayed(mHideZoomHint, 900);
    }

    private void createPlayerObjects() {
        // NEWTUBE(media3): the initializer owns the mobile tuning that used to be scattered here
        // (ABR 5s up-switch, 1080p Auto ceiling, 50/75s buffer + TTFF start gate + 120s back-buffer);
        // the buffer numbers are baked in, so the old PlayerData.setVideoBufferType() juggling is gone.
        // Native track selection replaces RestoreTrackSelector + the custom renderers factory: media3
        // ABR under app-level constraints, decoder fallback instead of the codec blacklist.
        DefaultTrackSelector trackSelector = mPlayerInitializer.createTrackSelector();

        mExoPlayerController.setTrackSelector(trackSelector);

        // An engine restart (error fix, network flap) while backgrounded builds a FRESH selector
        // with video re-enabled; re-apply the audio-only drop so the restart doesn't resume video.
        if (mBackgroundAudioMode || mAudioOnly) {
            mExoPlayerController.setVideoTrackDisabled(true);
        }

        mPlayer = mPlayerInitializer.createPlayer(
                trackSelector, mExoPlayerController.getMediaSourceFactory().getBandwidthMeter());
        // NEWTUBE(diagnostics): media3's stock per-event logcat tap (tag EventLogger) - track/ABR
        // switches, renderer state, loadError. Debug builds only, kept permanently.
        // NOTE: stock EventLogger does NOT emit loadStarted/loadCompleted lines, so per-chunk
        // network timing is invisible with it alone - NetPathLoadListener (tag NetPath) fills
        // exactly that gap with one dense line per load event.
        // NEWTUBE(quality-start): video rung switches, every build (a user's diagnostic log too).
        mPlayer.addAnalyticsListener(new com.newtube.mobile.player.VideoRungLog());
        if (BuildConfig.DEBUG) {
            mPlayer.addAnalyticsListener(new androidx.media3.exoplayer.util.EventLogger());
            mPlayer.addAnalyticsListener(new NetPathLoadListener(this));
        } else if (BuildConfig.BENCHMARK) {
            // Release-like timing evidence without verbose per-chunk/debug transport logging.
            mPlayer.addListener(new Player.Listener() {
                private boolean tracksLogged;

                @Override public void onMediaItemTransition(androidx.media3.common.MediaItem item, int reason) {
                    tracksLogged = false;
                }

                @Override public void onPlaybackStateChanged(int state) {
                    String name = state == Player.STATE_READY ? "READY"
                            : state == Player.STATE_BUFFERING ? "BUFFERING"
                            : state == Player.STATE_ENDED ? "ENDED" : "IDLE";
                    com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log("benchmark-state state=" + name
                            + " position-ms=" + (mPlayer != null ? mPlayer.getCurrentPosition() : -1)
                            + " elapsed-realtime-ms=" + android.os.SystemClock.elapsedRealtime());
                }

                @Override public void onTracksChanged(androidx.media3.common.Tracks tracks) {
                    if (tracksLogged || tracks.getGroups().isEmpty()) return;
                    tracksLogged = true;
                    for (androidx.media3.common.Tracks.Group group : tracks.getGroups()) {
                        for (int index = 0; index < group.length; index++) {
                            if (!group.isTrackSelected(index)) continue;
                            androidx.media3.common.Format format = group.getTrackFormat(index);
                            com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log("benchmark-track type="
                                    + group.getType() + " width=" + format.width + " height=" + format.height
                                    + " bitrate=" + format.bitrate + " mime=" + format.sampleMimeType);
                        }
                    }
                }
            });
        }
        mPlayer.setPlayWhenReady(true);
        // MEETUBE(sleep-timer): "End of video" pauses where autoplay would open the next one.
        mPlayer.setPauseAtEndOfMediaItems(SleepTimer.isEndOfVideo());
        mPlayer.addListener(mSleepEndListener);
        SleepTimer.attach(mSleepPause);
        mBenchTicker = com.newtube.mobile.player.BenchTicker.startIfEnabled(
                BuildConfig.DEBUG || BuildConfig.BENCHMARK, mPlayer, () -> {
                    Video video = getVideo();
                    return video != null ? video.videoId : null;
                });

        // Bounded-tolerance seeking as the player-wide default (scrub release, position restore -
        // every plain seekTo); see MOBILE_SEEK_PARAMETERS for the measurements behind it.
        // Double-tap bursts override per-direction (setUserSeekDirection) and restore to this.
        mPlayer.setSeekParameters(MOBILE_SEEK_PARAMETERS);

        mExoPlayerController.setPlayer(mPlayer);
        mExoPlayerController.attachPreloader(mPlayerInitializer.getPreloadManagerBuilder(),
                mPlayerInitializer.getPreloadTrackSelector());
        mPlayerView.setPlayer(mPlayer);
        // JOYBOY(v890): translation add-on (overlay above the video + button in the options row).
        JoyboyPlayerHost.attach(this, mPlayer, mVideoArea, mOptionsRow, new JoyboyPlayerHost.VideoInfo() {
            @Override public String videoId() { Video v = getVideo(); return v != null ? v.videoId : null; }
            @Override public String title() { Video v = getVideo(); return v != null ? v.title : null; }
            @Override public String localUri() { Video v = getVideo(); return v != null ? v.localUri : null; }
            @Override public java.util.Map<String, String> headers() { Video v = getVideo(); return v != null && v.headers != null ? v.headers : java.util.Collections.<String, String>emptyMap(); } // JOYBOY(v934)
            @Override public String audioUrl() { Video v = getVideo(); return v != null ? v.audioUrl : null; } // JOYBOY(v935)
            @Override public boolean endOfVideoSleep() { return SleepTimer.isEndOfVideo(); } // JOYBOY(v937)
            @Override public int playbackMode() { return PlayerData.instance(MobilePlaybackActivity.this).getPlaybackMode(); } // JOYBOY(v939): stored end-of-video mode so the add-on never auto-advances over PAUSE/CLOSE/ONE
        });

        // JOYBOY(v942): the add-on's full button bar (MtBar) lives in the bottom controls container, so it hides/shows with MeeTube's controls.
        if (mControlsRoot instanceof ViewGroup) {
            JoyboyPlayerHost.extraControls(this, (ViewGroup) mControlsRoot);
        }

        // Persistent surface: PlayerView owns no surface (surface_type="none"); hand the
        // session-long Surface to each new player instance. On the very first open the texture
        // may not exist yet - the SurfaceTextureListener attaches it on availability.
        if (mSessionSurface != null) {
            mPlayer.setVideoSurface(mSessionSurface);
        }
        mHdrOutput = false; // a new player starts on the texture; its tracks switch it (#35)
        finishHdrSwap();
        // The stock black shutter only lifts on a "rendered first frame" event tied to
        // PlayerView-owned surfaces; with the external surface it would sit over the loading
        // still forever. The still + black video area do its job now.
        mPlayerView.setShutterBackgroundColor(Color.TRANSPARENT);

        // Wire the YouTube-style double-tap seek overlay to the live player.
        // NOTE: PerformListener.shouldForward() compiles to an abstract method (the Kotlin default
        // body lives in DefaultImpls, invisible to Java), so it must be implemented here. We use the
        // ExoPlayer-correct version: left third rewinds, right third forwards, middle is ignored.
        mYouTubeOverlay
                .performListener(new YouTubeOverlay.PerformListener() {
                    @Override
                    public void onAnimationStart() {
                        // FAST-SEEK: the overlay seeks the raw player during the animation; the
                        // directional keyframe-snap is installed per tap in shouldForward.
                        mYouTubeOverlay.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void onAnimationEnd() {
                        endUserSeekBurst();
                        mYouTubeOverlay.setVisibility(View.GONE);
                    }

                    @Override
                    public Boolean shouldForward(Player player, DoubleTapPlayerView playerView, float posX) {
                        // MEETUBE(double-tap seek, issue #6): Settings > Player picks the jump.
                        mYouTubeOverlay.seekSeconds(Math.max(1,
                                PlayerData.instance(MobilePlaybackActivity.this).getSeekIncrementMs() / 1000));
                        Boolean forward = doubleTapSeekForward(player, posX);
                        if (forward != null) {
                            setUserSeekDirection(forward);
                        }
                        return forward;
                    }
                })
                .player(mPlayer)
                .playerView(mPlayerView);
        mPlayerView.controller(mYouTubeOverlay);

        // Our own lightweight UI listener (separate from ExoPlayerController's): drives the
        // buffering spinner, the play/pause/replay icon and the end-of-video state.
        mPlayer.addListener(mUiPlayerListener);

        // Apply the user's subtitle style to the PlayerView's built-in SubtitleView (see gap #2).
        // Registered AFTER setPlayer() so our (styled) SubtitleManager is the last TextOutput and
        // wins over PlayerView's default component that would otherwise render with embedded styles.
        createSubtitleManager();

        mPresenter.onEngineInitialized(); // VideoLoaderController picks up the pending video here

        // Attach the reused player to the background-playback service (media session + notification).
        // If already bound (e.g. after restartEngine) re-attach directly; otherwise start+bind now
        // while this Activity is in the foreground so startForeground is reached from the foreground.
        if (mServiceBound && mPlaybackService != null) {
            mPlaybackService.attachPlayer(mPlayer, mPresenter, buildContentIntent());
        } else {
            bindPlaybackService();
        }

        // NEWTUBE(perf): the progress loop only feeds the overlay time bar / labels, so it runs ONLY
        // while the controls are visible (started in showControlsInternal, stopped in hideControls);
        // while they are hidden, a slower loop feeds just the seek bar's line in portrait
        // (NEWTUBE(seek bar)). startProgressUpdates picks. The buffering spinner is driven
        // separately by mUiPlayerListener, so it keeps working either way.
        startProgressUpdates();
        updatePlayPauseIcon();
    }

    private void destroyPlayerObjects() {
        if (mPlayer == null) {
            return;
        }

        stopProgressUpdates();
        mPlayer.removeListener(mUiPlayerListener);

        // Tear down the debug overlay (removes its Player listener) and drop the subtitle manager
        // before the player is released. SubtitleManager registers on PlayerData via a WeakHashSet,
        // so nulling the reference is enough to let it be collected.
        if (mDebugInfoManager != null) {
            mDebugInfoManager.show(false);
            mDebugInfoManager = null;
        }
        mSubtitleManager = null;

        // Detach the player from the media session/notification BEFORE releasing it, so the service
        // never references a released player. The service (and any audio) stops here on real finish.
        if (mPlaybackService != null) {
            mPlaybackService.detachPlayer();
        }

        // Don't release a different (e.g. embed) player's engine state.
        if (mPresenter.getView() == null || mPresenter.getView() == this) {
            mPresenter.onEngineReleased();
        }

        if (mBenchTicker != null) {
            mBenchTicker.stop();
            mBenchTicker = null;
        }
        JoyboyPlayerHost.detach(this); // JOYBOY(v890)
        mPlayerView.setPlayer(null);
        mExoPlayerController.release();
        mPlayer = null;
    }

    private boolean isActivelyPlaying() {
        if (mPlayer == null || !mPlayer.getPlayWhenReady()) {
            return false;
        }
        int state = mPlayer.getPlaybackState();
        return state == Player.STATE_READY || state == Player.STATE_BUFFERING;
    }

    /**
     * SCREEN-ON (#50): drives the WINDOW flag directly. It used to be {@code View#setKeepScreenOn}
     * on the PlayerView, which the window only reads back when a view-attribute recompute runs;
     * after a minimize/restore the window kept KEEP_SCREEN_ON through every later pause. The window
     * flag has no such indirection: on while playing/buffering, off otherwise and while stopped.
     */
    private void applyKeepScreenOn(boolean on) {
        Window window = getWindow();
        if (window == null) {
            return;
        }
        if (on && !mIsStopped) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (mPlayerView != null && mPlayerView.getKeepScreenOn()) {
            mPlayerView.setKeepScreenOn(false); // never a second, view-level holder
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mIsStopped = false;
        if (mShortPausedByLeave) { // MEETUBE(shorts): back in the app - the short plays on
            mShortPausedByLeave = false;
            if (mShorts != null && mShorts.isActive() && mExoPlayerController != null) {
                mExoPlayerController.setPlayWhenReady(true);
            }
        }
        applyKeepScreenOn(isActivelyPlaying());
        startProgressUpdates();
        if (mControlsVisible) {
            armAutoHide();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        PlayerTransitionBridge.LaunchSnapshot launch = PlayerTransitionBridge.take();

        mIsResumed = true;
        applyAmbientSetting(); // MEETUBE(ambient): the Settings switch, read on every resume
        if (mControlsVisible && !isPlayerSheetShown()) {
            // NEWTUBE(sheet-landscape): back from a picker screen (Repeat, Zoom...) that held the
            // controls up. It only paused this screen, so onStart's countdown does not run.
            armAutoHide();
        }
        if (!isPlayerSheetShown()) {
            setControlsGlassLive(true); // a picker screen that a sheet handed over to is gone
        }
        // In the foreground again: auto-PiP behaves normally from here on.
        mSuppressAutoPip = false;
        mDismissDragActive = false;
        if (mPipEnterPending) {
            // NEWTUBE(menu-pip): resumed while a PiP entry is still pending (accepted, no mode
            // callback yet), so the entry was undone - an activity in PiP is paused, not resumed.
            // When the player is expanded right away (the menu PiP bounce, 1.11.0) the platform
            // may deliver neither mode callback, and isInPictureInPictureMode() still said pinned
            // here (API 35). enterPipMode had already stripped the window to video, and only the
            // exit callback puts the watch page and controls back: the player came back as video
            // over a black page with no controls.
            logPip("enter-aborted-restored inPip=" + (isInPipModeNow() ? "y" : "n"));
            mPipStateStale = isInPipModeNow();
            mPipEnterPending = false; // before showControlsInternal, which refuses a pending entry
            int orientation = layoutOrientation();
            applyWatchLayoutForOrientation(orientation);
            applySystemBarsForOrientation(orientation);
            showControlsInternal(false);
            SystemPipBridge.onPipEnded();
        }
        mPipEnterPending = false;
        updateSwipeBrightness(); // NEWTUBE(gestures): after an undone PiP entry is cleared above
        if (!mIsInPip && mTimeBar != null && mTimeBar.getVisibility() != View.VISIBLE) {
            // NEWTUBE(seek bar): PiP hid the bar (applyPipVideoOnlyLayout); an entry undone before
            // any mode callback (above) came back without it.
            mTimeBar.setVisibility(View.VISIBLE);
            updateSeekBarLine();
        }
        // The PiP exit ended in the fullscreen UI, so it was an expand, not a dismiss.
        mPipDismissPending = false;
        mPipToAudio = false;
        mPipLayoutHold = mIsInPip || mPipEnterPending; // the real window's config is in now
        mRoutedInWhileLeaving = false; // NEWTUBE(link-while-playing): the routed-in open is in front
        // Re-enable the video track BEFORE any texture reattach below, so the first frame comes
        // back promptly (true background audio-only mode dropped the whole video renderer).
        setBackgroundAudioMode(false);
        if (!mIsInPip && mExoPlayerController != null) {
            // Back from the mini card / PiP: full size again, fetch full resolution (VideoViewportCap).
            mExoPlayerController.clearSmallWindowViewport("resume");
        }
        updatePipActions(); // re-arm the Android 12+ auto-enter flag cleared by the minimize hand-off

        // Back from the mini-player (expand tap, new video, notification, recents): the Browse
        // card displayed the session texture until Browse's onPause detached it (guaranteed to
        // run before this). Re-parent it into our content frame - the codec kept decoding into
        // it the whole time, so no surface change, no codec re-init, no frozen frames. The card
        // captured its last frame for us; it covers the 1-2 frames until the texture paints.
        boolean fromMini = MiniPlayerBridge.isActive();
        Rect miniBounds = fromMini ? MiniPlayerBridge.takeMiniBounds() : null;
        if (fromMini) {
            Bitmap handoff = MiniPlayerBridge.takeHandoffStill();
            if (handoff != null) {
                showHandoffStill(handoff);
            } else {
                mStillAwaitFrame = true; // minimize-time capture is showing; lift on first frame
            }
            reattachVideoTexture();
        }
        MiniPlayerBridge.deactivate();
        updateHdrOutput(); // back from the card (#35)

        if (launch != null) {
            // A normal feed/search/channel tap: cover the video with the exact tapped thumbnail,
            // place it over the source rect, then grow it into the watch page while Browse remains
            // visible through the rest of our window.
            showHandoffStill(launch.frame);
            mStillAwaitFrame = false; // do not lift the source image before the morph completes
            if (fromMini) {
                // The old stream is still producing frames after its texture was re-parented.
                // Keep those frames behind the selected video's thumbnail until the new load wins.
                armStillForReady();
            }
            startOpenMorph(launch.sourceBounds, 300);
            // A docked card elsewhere on the host (another video was tapped): it goes once the
            // player's first frame is up.
            FrameGate.afterNextFrame(mContainer, HOST_CARD_FOLD_WAIT_MS,
                    () -> MiniPlayerBridge.foldHostCard(null));
        } else if (fromMini && mContainer != null) {
            // Plain mini-card expansion: exact reverse of minimize, from the card rectangle.
            overridePendingTransition(0, 0);
            mContainer.setVisibility(View.INVISIBLE);
            mMorphStartPending = true;
            mContainer.post(() -> {
                mMorphStartPending = false;
                if (miniBounds != null) {
                    computeMorphTarget(miniBounds);
                    fitMorphInto(miniBounds);
                } else {
                    computeMorphTarget();
                }
                applyMorph(1f);
                mContainer.setVisibility(View.VISIBLE);
                // NEWTUBE(motion): the host kept its card up, frozen (MiniPlayerBridge
                // .setPendingCardFold). Our frame over it first, then the card folds, then the video
                // grows: no frame without either. Each step waits at most HOST_CARD_FOLD_WAIT_MS.
                FrameGate.afterNextFrame(mContainer, HOST_CARD_FOLD_WAIT_MS,
                        () -> MiniPlayerBridge.foldHostCard(() -> {
                            if (!isFinishing() && !isDestroyed() && mMorphAnimator == null) {
                                animateMorph(0f, 250, Motion.EMPHASIZED, this::resetMorph);
                            }
                        }));
            });
        } else {
            MiniPlayerBridge.foldHostCard(null);
        }

        if (mPresenter != null) {
            mPresenter.onViewResumed();
        }

        // Foreground recovery: if a background startForeground was rejected (API 31+, see
        // MobilePlaybackService.onNotificationPosted), re-run the promotion now that we're visible.
        if (mPlaybackService != null) {
            mPlaybackService.ensureForeground();
        }

        applySystemBarsForOrientation(layoutOrientation());
        if (mMorphStartPending) {
            // NEWTUBE(motion): the morph's first step runs after the window's first frame(s), and
            // the line above just painted the backdrop solid again: the page stays clear until
            // then, or those frames were a blank black (dark) or white (light) screen over Home.
            clearBackdropForMorphStart();
        }
        updateOrientationHandBackListener();
    }

    private void clearBackdropForMorphStart() {
        if (mWatchScroll != null && mWatchScroll.getBackground() != null) {
            mWatchScroll.getBackground().mutate().setAlpha(0);
        }
        setWindowBackdropAlpha(0f);
    }

    @Override
    protected void onPause() {
        if (mVideoArea != null) {
            mVideoArea.removeCallbacks(mShortsSingleTap); // MEETUBE(issue #172): no tap after leaving.
        }
        if (mPresenter != null) {
            mPresenter.onViewPaused();
        }

        mIsResumed = false;
        if (mAmbient != null) {
            mAmbient.stop();
        }
        updateOrientationHandBackListener();

        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();

        mIsStopped = true;
        MobileSheets.setPlayerShown(false, false); // onResume's bars pass sets it again
        applyKeepScreenOn(false); // a hidden window must not hold the screen; onStart re-applies
        stopProgressUpdates();
        cancelAutoHide();
        if (mShorts != null) {
            mShorts.releasePeek(); // MEETUBE(shorts peek): no idle decoder in the background
        }
        if (mExoPlayerController != null && mExoPlayerController.isHoldSpeedOn()) {
            endHoldSpeed(); // the finger's lift can't reach a stopped window
        }

        // Glide pauses the activity's request manager on its own here and resumes it on the next
        // onStart. Drop our hold flag (WITHOUT resuming - that would defeat Glide's background
        // pause) so the two never disagree about who paused what.
        Utils.removeCallbacks(mReleaseImageRequests);
        mImageRequestsHeld = false;

        // Going to the background right after leaving PiP mode, without the fullscreen UI ever
        // resuming: the user dismissed the PiP window. Close the video for real (see
        // mPipDismissPending) instead of falling through into background-audio mode.
        if (mPipDismissPending) {
            mPipDismissPending = false;
            finishFromPipDismiss();
            return;
        }

        // True background audio-only playback: the window is no longer visible AND we're neither in
        // system PiP nor the in-app Browse mini-player (both of which show live video), so the
        // service is keeping only the audio going. Drop the video renderer so VIDEO stops streaming
        // and decoding - onStop is not delivered while a PiP window stays visible, so reaching here
        // uninhibited means a real background (home without PiP, screen off, another screen on top).
        // Re-enabled at the top of onResume, before the texture reattach.
        if (mShorts != null && mShorts.isActive() && !mIsInPip && !isFinishing()) {
            // MEETUBE(shorts): no background play for Shorts - leaving pauses, coming back plays.
            // (Home with a short playing opens PiP instead; this is a paused short or the screen off.)
            if (mExoPlayerController != null && mExoPlayerController.getPlayWhenReady()) {
                mExoPlayerController.setPlayWhenReady(false);
                mShortPausedByLeave = true;
            }
        } else if (mPlayer != null && !mIsInPip && !mSuppressAutoPip && !isFinishing()) {
            setBackgroundAudioMode(true);
        }

        // The minimize drag left the video morphed onto the mini-card rect. Reset it once
        // this window is no longer visible (here, not in minimizeByDrag - resetting while our
        // window still shows behind the task switch would flash the fullscreen player back).
        // The expand path immediately re-applies the morph in onResume, so this never fights it.
        if (mContainer != null && mMorphFraction != 0f) {
            resetMorph();
            // Minimized with comments open: they went with the watch page, and the player comes
            // back without them (kept, so reopening is instant and in place).
            if (mCommentsPanel != null) {
                mFullAfterComments = false; // the player comes back as the page left it
                mCommentsPanel.closeImmediately();
            }
            closeSidePanelsImmediately();
        }
        Utils.removeCallbacks(mPrefetchComments);
    }

    /**
     * Enter/exit true background audio-only mode: enabled = drop the whole video renderer so VIDEO
     * neither downloads nor decodes while the service keeps only audio going; disabled = restore it.
     * PiP and the Browse mini-player both render live video, so they never enter this mode. The flag
     * is the single source of truth (createPlayerObjects re-applies it after an engine restart).
     */
    private void setBackgroundAudioMode(boolean enabled) {
        mBackgroundAudioMode = enabled;
        if (mExoPlayerController != null) {
            mExoPlayerController.setVideoTrackDisabled(enabled || mAudioOnly);
            // NEWTUBE(keep-codec): no decoder kept for the next open while nothing is on screen.
            mExoPlayerController.onBackgroundAudio(enabled);
        }

        // The Activity-owned live-chat poll keeps hitting the network (~700 req/hr) even with the
        // video renderer dropped. Stop it going audio-only in the background; if the chat sheet
        // survived the stint (mChatObserver != null - the sheet fragment outlives onStop/onResume)
        // revive the stream on return so the user doesn't come back to a frozen panel. The
        // ChatController receiver path (mChatReceiver) owns its own stream, so never touch it here.
        if (enabled) {
            if (mLiveChatAction != null) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                            "live-chat poll stop (background audio)");
                }
                stopLiveChatStream();
            }
        } else if (mChatObserver != null && mChatReceiver == null
                && mLiveChatAction == null && mLiveChatKey != null) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                        "live-chat poll resume (foreground)");
            }
            startLiveChatStream();
        }
    }

    @Override
    protected void onDestroy() {
        if (mShorts != null) {
            mShorts.releasePeek();
        }
        SleepTimer.detach(mSleepPause);
        DownloadRegistry.instance(this).removeListener(mDownloadsListener);
        MobileSheets.setPlayerShown(false, false);
        cancelAutoHide();
        mOrientationHandBack.disarm();
        updateOrientationHandBackListener();
        hideRelatedSkeleton(); // cancels the pulse animator + pending timeout
        Utils.removeCallbacks(mReleaseImageRequests);
        Utils.removeCallbacks(mReleaseWatchMetadata);
        Utils.removeCallbacks(mPrefetchComments);
        Utils.removeCallbacks(mNextTopComment);
        if (mCommentsPanel != null) {
            mCommentsPanel.release();
        }
        closeSidePanelsImmediately();
        for (CommentsPanelLayout panel : mSidePanels) {
            panel.setCallback(null);
        }
        if (mSidePanelBackCallback != null) {
            mSidePanelBackCallback.remove();
        }
        mRelatedRenderGate.cancelPending();
        // The loading still is loaded through the application request manager (see
        // maybeShowLoadingStill), which has no lifecycle of its own - clear it by hand or the
        // pending request keeps this activity's ImageView alive.
        if (mVideoStill != null) {
            Glide.with(getApplicationContext()).clear(mVideoStill);
        }
        if (mAudioCoverArt != null) {
            Glide.with(getApplicationContext()).clear(mAudioCoverArt);
        }

        // Casting: stop observing the session. The session itself (manager + foreground service)
        // deliberately outlives this activity - the phone is just a remote.
        if (mCastSessionManager != null) {
            mCastSessionManager.removeListener(mCastListener);
        }
        Utils.removeCallbacks(mCastProgressRunnable);

        if (mWatchRoot != null) {
            mWatchRoot.removeOnLayoutChangeListener(mWatchRootLayoutListener);
        }
        SystemPipBridge.detach(this);

        RxHelper.disposeActions(mLiveChatAction);

        // The only playback activity (singleInstance) is going away: no mini session can outlive it.
        MiniPlayerBridge.deactivate();

        // Fix situations when the engine wasn't properly destroyed (mirrors PlaybackFragment).
        destroyPlayerObjects();

        // The codec is gone (player released above): the session-long surface can die now.
        releaseSessionTexture();

        // Real finish: tear down the background-playback service and the PiP receiver.
        unbindPlaybackService();
        unregisterPipReceiver();
        sAudioOnly = false; // the session is over: the next one's card shows again

        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onViewDestroyed();
        }

        super.onDestroy();
    }

    /**
     * HOME pressed (or the app is otherwise being sent to the background) while a video is playing:
     * slip into Picture-in-Picture so the video keeps playing in a floating window. If PiP isn't
     * available the background-playback service keeps the audio going instead (see MobilePlaybackService).
     */
    /** Set while minimizing into the in-app mini-player, so auto-PiP keeps its hands off. */
    private boolean mSuppressAutoPip;

    /**
     * Set from the first pixel of a swipe-down minimize until the drag is cancelled or the player
     * has docked. {@link #mSuppressAutoPip} alone is too late: it is raised when the drag RELEASES,
     * and the system latches the standing auto-enter flag when the home gesture STARTS, so a home
     * gesture that overlaps the drag still pinned the task ~120ms after Browse had been reordered
     * into it - which is how the whole app ended up rendered inside the PiP window.
     */
    private boolean mDismissDragActive;

    /**
     * True between {@code enterPictureInPictureMode()} being accepted and
     * {@link #onPictureInPictureModeChanged} actually arriving (~15-80ms). The task is already on
     * its way to pinned in that window, so the minimize guards have to treat it as PiP.
     */
    private boolean mPipEnterPending;

    /**
     * NEWTUBE(menu-pip): an accepted PiP entry was undone without either mode callback, and
     * {@code isInPictureInPictureMode()} kept saying pinned for the fullscreen player (API 35). On
     * that word the launcher restore then "expanded" a player that was not in PiP on every later
     * Home focus, throwing it back to full screen right after a minimize. Cleared by the next real
     * mode callback. (The player's own {@link #mIsInPip} cannot stand in: a player recreated
     * while pinned - font size, locale - gets no callback either, yet must still be restored.)
     */
    private boolean mPipStateStale;

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();

        if (mSuppressAutoPip) {
            // Backgrounding into the Browse mini-player, not leaving the app: no system PiP.
            logPip("leave-skip reason=mini-handoff");
            return;
        }
        if (mDismissDragActive) {
            // A minimize swipe is in flight; mSuppressAutoPip is only raised when it RELEASES.
            // Entering PiP here pins the task, and the release then reorders Browse into it - the
            // whole app ends up drawn inside the PiP window. The drag wins: it docks in-app.
            logPip("leave-skip reason=minimize-drag");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || mIsInPip || isFinishing()) {
            logPip("leave-skip reason=state");
            return;
        }
        if (!Helpers.isPictureInPictureSupported(this)) {
            logPip("leave-skip reason=unsupported");
            return;
        }
        if (!MiniPip.isAutoEnabled(this)) { // MEETUBE(issue #103): onStop carries on as audio
            logPip("leave-skip reason=off");
            return;
        }
        // Only auto-enter PiP while actually playing (matches YouTube; avoids PiP on a paused pre-roll).
        if (mPlayer == null || !isPlaying()) {
            logPip("leave-skip reason=not-playing");
            return;
        }
        // Don't hijack navigation to one of our own screens (e.g. opening a dialog / channel).
        if (getViewManager() != null && getViewManager().isNewViewPending()) {
            logPip("leave-skip reason=internal-navigation");
            return;
        }
        // MEETUBE(audio-only): leaving a playing video always opens PiP, unless the headphones are
        // on - then onStop keeps the audio going.
        if (mAudioOnly) {
            logPip("leave-skip reason=audio-mode");
            return;
        }
        logPip("leave-enter");
        enterPipMode();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // PiP entry delivers a config change right AFTER onPictureInPictureModeChanged(true) hid
        // the watch UI; reapplying the portrait layout here un-hid it again, squeezing the whole
        // watch page into the tiny PiP window. PiP owns its video-only layout; the exit branch of
        // onPictureInPictureModeChanged restores everything below.
        if (mIsInPip) {
            // The follow-up of PiP entry, or a user resize of the PiP window: re-size the rung cap.
            if (mExoPlayerController != null) {
                mExoPlayerController.setSmallWindowViewport("pip", newConfig);
            }
            return;
        }

        refreshContentInsets();
        int orientation = layoutOrientation(newConfig.orientation);
        if (orientation != mSheetsOrientation) {
            // NEWTUBE(sheet-landscape): a sheet is sized and its window's bars set for the shape
            // it opened over - the fullscreen video, or the watch page - and the other one has
            // just taken its place.
            dismissPlayerSheets();
        }
        applyWatchLayoutForOrientation(orientation);
        if (mShorts != null && mShorts.isActive()) {
            applyShortsLayout(); // MEETUBE(issue #57): the short's zoom and glow for the new shape
        }
        applySystemBarsForOrientation(orientation);
        updateFullscreenIcon(orientation);
        onFullscreenSwipeConfigured();
        // The standing auto-enter params carry a sourceRectHint captured from the video box; after
        // a rotation that rect is stale (portrait box vs fullscreen), which degrades the
        // home-gesture shrink animation. Re-push with the post-rotation geometry.
        if (mVideoArea != null) {
            mVideoArea.post(this::updatePipActions);
        }
    }

    /** NEWTUBE(theme): the status bar is over the black video band in both themes. */
    @Override
    protected boolean isStatusBarOverDarkContent() {
        return true;
    }

    /**
     * NEWTUBE(theme): a theme change must not restart the video (recreating this screen reopened
     * it with a 0.5-0.8 s gap - why uiMode is in the manifest's configChanges), so the player
     * changes side in place. The video area is dark in both themes and is left alone; the watch
     * page under it - title, actions, description, cards, Up next, the comments panel - takes its
     * colours from a fresh inflation of this layout under the new theme (ThemeRefresh), its lists
     * rebuild their rows, and what code draws from state (thumbs, Save, Subscribe, Download) is
     * drawn again. A sheet that happens to be open (gear and its pickers, cast, live chat) closes:
     * it was built on the other side, and reopens on the new one.
     */
    @Override
    protected boolean onThemeChanged(int night) {
        dismissPlayerSheets();
        closeSidePanelsImmediately(); // MEETUBE(lyrics-panel): open again in the new theme
        for (androidx.fragment.app.Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment instanceof androidx.fragment.app.DialogFragment) {
                ((androidx.fragment.app.DialogFragment) fragment).dismissAllowingStateLoss();
            }
        }
        ThemeMode.syncResources(this, night);
        View liveArea = findViewById(R.id.mobile_watch_area);
        if (liveArea == null) {
            return true;
        }
        int scrollY = mWatchScroll != null ? mWatchScroll.getScrollY() : 0;
        View fresh = getLayoutInflater().inflate(R.layout.activity_mobile_playback, new FrameLayout(this), false);
        View freshArea = fresh.findViewById(R.id.mobile_watch_area);
        List<RecyclerView> lists = new ArrayList<>();
        int skipped = freshArea != null ? ThemeRefresh.copyColors(liveArea, freshArea, lists) : 1;
        if (mCommentsPanel != null) {
            mCommentsPanel.onThemeChanged(); // before its rows are rebound
        }
        for (RecyclerView list : lists) {
            ThemeRefresh.rebuildRows(list);
        }
        for (int id : new int[] {R.id.action_thumbs_up, R.id.action_thumbs_down, R.id.action_playlist_add,
                R.id.action_subscribe}) {
            updateButtonVisual(id, getButtonState(id));
        }
        updateDownloadPill();
        if (!mIsInPip) {
            applySystemBarsForOrientation(layoutOrientation());
        }
        if (mWatchScroll != null && scrollY > 0) {
            mWatchScroll.post(() -> mWatchScroll.scrollTo(0, scrollY));
        }
        NetPath.log("theme player in-place lists=" + lists.size() + " skipped=" + skipped);
        return true;
    }

    private void handleBack() {
        com.newtube.mobile.ui.common.PlayerTrace.mark("BACK pressed  portraitFull=" + mPortraitFull + " landscape=" + isLandscape() + " pip=" + mIsInPip); // JOYBOY(v952)
        if (mClosing) {
            return;
        }
        if (JoyboyPlayerHost.onBack(this)) { // JOYBOY(v946): the add-on asked something first (continue translating in the background?)
            onBackGestureCancelled();
            return;
        }
        if (mMoreVideos != null && mMoreVideos.isShown()) {
            mMoreVideos.close();
            return;
        }
        if (mControlsVisible && com.newtube.mobile.ui.common.FocusRing.isTv(this)) {
            hideControls(); // MEETUBE(tv): the remote's Back closes the controls before it leaves the video
            return;
        }
        if (!mBackPreview && mMorphAnimator == null && !mMorphStartPending && !mMagnetDragging
                && mMorphFraction != 0f) {
            // MEETUBE(fullscreen): a morph left half-way with nothing moving it locks out Back, the
            // drags and the controls; Back puts the page back instead of closing the player.
            resetMorph();
            return;
        }
        if (isJoyLocalPage() && !mIsInPip && !mPipEnterPending) { // JOYBOY(v950): local player — Back leaves straight (small PiP or close), upright or landscape, never the watch page
            mBackPreview = false;
            if (JoyboyPlayerHost.onMinimize(this)) {
                setPlayWhenReady(false);
                enterPipMode();
                if (mPipEnterPending) {
                    return;
                }
            }
            if (mPresenter != null) {
                mPresenter.onFinish();
            }
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        if (mPortraitFull && !isLandscape()) {
            setPortraitFull(false);
            return;
        }
        // NEWTUBE(motion): like YouTube - Back leaves fullscreen first, then minimizes.
        if (!mIsInPip && !mPipEnterPending
                && layoutOrientation() == Configuration.ORIENTATION_LANDSCAPE) {
            mBackPreview = false;
            // JOYBOY(v941): Back out of fullscreen for a non-YouTube source goes straight to a small
            // paused PiP (or closes outright if PiP is refused) — never the watch page.
            if (JoyboyPlayerHost.onMinimize(this)) {
                setPlayWhenReady(false);
                enterPipMode();
                if (mPipEnterPending) {
                    return;
                }
                if (mPresenter != null) {
                    mPresenter.onFinish();
                }
                finish();
                overridePendingTransition(0, 0);
                return;
            }
            toggleFullscreen();
            return;
        }
        if (mShorts != null && mShorts.isActive()) {
            if (mShorts.isCommentsOpen() && mCommentsPanel != null) {
                mCommentsPanel.close();
                return;
            }
            // MEETUBE(issue #107): a short slides down off the screen as a window animation - the
            // system composes it, so the tab opening beneath (sShortsClosed) can't drop its frames.
            com.newtube.mobile.ui.browse.MobileBrowseActivity.sShortsClosed = true;
            if (mPresenter != null) {
                mPresenter.onFinish();
            }
            finish();
            overridePendingTransition(0, R.anim.shorts_close_exit);
            return;
        }
        if (mBackPreview || canMinimizeByBack()) {
            minimizeByBack();
            return;
        }
        if (canAnimateClose()) {
            animateCloseThenFinish();
            return;
        }
        if (mPresenter != null) {
            mPresenter.onFinish();
        }

        finish();
    }

    // ---------------------------------------------------------------------------------
    // NEWTUBE(motion): Back closes the player in its own window. The window animation it replaces
    // cross-faded the whole watch page over Home, so for ~120 ms two screens of text sat on top of
    // each other (Pixel 9, frame by frame). Now the page's text goes first, as in the minimize,
    // and the video and the dark backdrop then sink and fade over Home, which is already drawn
    // beneath this translucent window - in the same 200 ms. Playback is muted at the press. Only
    // over a screen of ours in portrait: a deep-linked player (nothing beneath) or a landscape one
    // (rotation) keeps the window animation.
    // ---------------------------------------------------------------------------------

    // NEWTUBE(motion): Back = minimize. The back gesture previews the minimize morph up to
    // BACK_PREVIEW_FRACTION of the way (the page's text is gone by then, the video is visibly on its
    // way to the corner); letting go finishes it, cancelling springs it back.
    private static final float BACK_PREVIEW_FRACTION = 0.2f;
    private static final long BACK_MINIMIZE_MS = 300;
    private boolean mBackPreview;

    private boolean canMinimizeByBack() {
        return mPlayer != null && mContainer != null && mVideoArea != null && !mIsInPip && !mPipEnterPending
                && !mScrubbing && mMorphAnimator == null && mMorphFraction == 0f
                && layoutOrientation() == Configuration.ORIENTATION_PORTRAIT;
    }

    private void onBackGestureStarted() {
        mBackPreview = false;
        if (mClosing || !canMinimizeByBack() || (mShorts != null && mShorts.isActive())) { // Back closes Shorts
            return;
        }
        if (mPortraitFull && !isLandscape()) {
            // MEETUBE(fullscreen): Back here only leaves the vertical fullscreen (handleBack), so no
            // minimize preview - it was left mid-shrink with every gesture locked out.
            return;
        }
        beginMinimizeMorph();
        mBackPreview = true;
    }

    private void onBackGestureProgressed(float progress) {
        if (mBackPreview) {
            applyMorph(BACK_PREVIEW_FRACTION * Motion.STANDARD_DECELERATE.getInterpolation(progress));
        }
    }

    private void onBackGestureCancelled() {
        if (mBackPreview) {
            mBackPreview = false;
            animateMorph(0f, 180, Motion.STANDARD, this::resetMorph);
        }
    }

    private void minimizeByBack() {
        com.newtube.mobile.ui.common.PlayerTrace.mark("MINIMIZE by back (mini player)"); // JOYBOY(v952)
        boolean moving = mBackPreview;
        mBackPreview = false;
        if (!moving) {
            beginMinimizeMorph();
        }
        float remaining = Math.max(0f, 1f - mMorphFraction);
        long durationMs = Math.max(120L, Math.round(BACK_MINIMIZE_MS * remaining));
        // Already moving under the finger: continue decelerating. From rest (the button, a key):
        // the gentle-start curve, as the open does.
        animateMorph(1f, durationMs, moving ? Motion.EMPHASIZED_DECELERATE : Motion.EMPHASIZED,
                this::minimizeByDrag);
    }

    private static final long CLOSE_MS = 200;
    private boolean mClosing;
    @Nullable
    private ValueAnimator mCloseAnimator;
    private float mVolumeBeforeClose = 1f;

    private boolean canAnimateClose() {
        return mContainer != null && mVideoArea != null && !mIsInPip && !mPipEnterPending
                && mMorphAnimator == null && mMorphFraction == 0f
                && layoutOrientation() == Configuration.ORIENTATION_PORTRAIT
                && !isTaskRoot() && getViewManager().hasParentView(this)
                && !MiniPlayerBridge.isActive();
    }

    private void animateCloseThenFinish() {
        mClosing = true;
        if (mPlayer != null) {
            // NEWTUBE(loudness): through the controller, which keeps this apart from loudness
            // normalization - a direct ExoPlayer mute would be undone by a gain update mid-fade.
            mVolumeBeforeClose = mExoPlayerController.getVolume();
            mExoPlayerController.setVolume(0f);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        mVideoArea.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        final float drop = 0.08f * mContainer.getHeight();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        mCloseAnimator = animator;
        animator.setDuration(CLOSE_MS);
        animator.setInterpolator(Motion.EMPHASIZED_ACCELERATE);
        animator.addUpdateListener(a -> applyCloseProgress((float) a.getAnimatedValue(), drop));
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mCloseAnimator == animator) {
                    mCloseAnimator = null;
                }
                if (!mCancelled && !isFinishing() && !isDestroyed()) {
                    // The presenter learns only now: a video picked meanwhile (setVideo) cancelled this.
                    if (mPresenter != null) {
                        mPresenter.onFinish();
                    }
                    finish();
                    overridePendingTransition(0, 0);
                }
            }
        });
        animator.start();
    }

    private void applyCloseProgress(float p, float drop) {
        float content = Math.max(0f, 1f - p * 3f);
        if (mWatchContent != null) {
            mWatchContent.setAlpha(content);
        }
        if (mControlsRoot != null && mControlsRoot.getVisibility() == View.VISIBLE) {
            mControlsRoot.setAlpha(content);
        }
        if (mTimeBar != null) {
            mTimeBar.setAlpha(content);
        }
        if (mWatchFab != null) {
            mWatchFab.setAlpha(content); // MEETUBE(fullscreen): the floating button goes with the page
        }
        if (mCommentsPanel != null) {
            mCommentsPanel.setMorphAlpha(content);
        }
        for (CommentsPanelLayout panel : mSidePanels) {
            panel.setAlpha(content);
        }
        LyricsSheet.setMorphAlpha(mLyricsSheet, content); // MEETUBE(lyrics)
        LyricsSheet.setMorphAlpha(mTranscriptSheet, content); // MEETUBE(transcript)
        float backdrop = 1f - p;
        if (mWatchScroll != null && mWatchScroll.getBackground() != null) {
            mWatchScroll.getBackground().mutate().setAlpha(Math.round(255f * backdrop));
        }
        setWindowBackdropAlpha(backdrop);
        mVideoArea.setTranslationY(drop * p);
        mVideoArea.setAlpha(1f - p);
    }

    /**
     * A new video routed into this player while it was closing (a feed tap goes through while it
     * fades - touches pass to the screen below): stay, as it was. Reached from onNewIntent and
     * from setVideo, since a selection with the player still the logical top view never relaunches.
     */
    private void cancelClose() {
        if (!mClosing) {
            return;
        }
        mClosing = false;
        if (mCloseAnimator != null) {
            mCloseAnimator.cancel();
            mCloseAnimator = null;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        if (mPlayer != null) {
            mExoPlayerController.setVolume(mVolumeBeforeClose);
        }
        mVideoArea.setLayerType(View.LAYER_TYPE_NONE, null);
        mVideoArea.setAlpha(1f);
        resetMorph(); // puts back translation, backdrop, content, controls and comments alpha
    }

    /**
     * The user dismissed the system PiP window (X / swipe-away): close the video like YouTube does.
     * The player sits alone in its own (formerly pinned) task at this point, so
     * {@code finishAndRemoveTask()} kills exactly that task - the shared Browse task is untouched.
     * Bypasses the {@code MobileActivity.finish()} routing on purpose: its root-screen branches
     * (parent relaunch / task-to-back) are for screens the user is looking at, not for an invisible
     * dismissed player.
     */
    private void finishFromPipDismiss() {
        if (isFinishing() || isDestroyed()) {
            return; // the system already finished us (older PiP shell) - nothing to do
        }
        if (BuildConfig.DEBUG) {
            android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                    "pip dismissed -> closing playback");
        }
        if (mPresenter != null) {
            mPresenter.onFinish();
        }
        getViewManager().removeTop(this);
        finishAndRemoveTask();
    }

    /**
     * The player is the one mobile screen where landscape means TRUE immersive fullscreen, so the
     * {@code MobileActivity} override (always-standard bars) is re-specialized by orientation.
     */
    @Override
    protected void applyFullscreenModeIfNeeded() {
        applySystemBarsForOrientation(layoutOrientation());
    }

    @Override
    protected boolean shouldInsetContentForSystemBars() {
        // MEETUBE(shorts): a short runs under the status and navigation bars, edge to edge; its
        // overlay keeps clear of them itself (the listener on it in bindViews).
        // MEETUBE(fullscreen): so does the vertical fullscreen, its bars hidden.
        return layoutOrientation() != Configuration.ORIENTATION_LANDSCAPE && !isTallMode();
    }

    /**
     * MEETUBE(fullscreen): the bars' room whether they show yet or not (Android 11+). Leaving the
     * vertical fullscreen the page lays out while the bars are still hidden; their visible insets
     * put it at the very top, then dropped it by the status bar once they were back.
     */
    @Override
    protected Insets contentInsets(WindowInsetsCompat insets) {
        return Build.VERSION.SDK_INT >= 30 ? insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout())
                : super.contentInsets(insets);
    }

    /**
     * Landscape = edge-to-edge immersive fullscreen; portrait = normal with the status bar back.
     * Actual rotation is handled by the system (manifest {@code configChanges} keeps the live
     * ExoPlayer instance across rotation); this only follows it.
     */
    /**
     * PLAYER LAYOUT POLISH + REACH FIX. Portrait: the decor fits the system windows, controls
     * anchor flush to the video box (no padding). Landscape/fullscreen: inset by the system bars
     * AND by the pillarbox strip beside the video (see {@link #controlsStrip}), so the whole
     * overlay - especially the fullscreen-exit button in the bottom-right - aligns with the video
     * content edges ("where the black strips start", like YouTube) instead of the far screen corners.
     * The top/bottom scrims are exempt: they cover the whole video area (bleedScrim).
     */
    private void applyControlsInsets() {
        if (mControlsRoot == null) {
            return;
        }
        int left = 0, top = 0, right = 0, bottom = 0;
        if (isLandscape()) {
            WindowInsetsCompat rootInsets = ViewCompat.getRootWindowInsets(mControlsRoot);
            // The camera cutout too: a film wider than 16:9 has no pillarbox to keep the back
            // button and the time label off the punch hole any more (controlsStrip).
            Insets bars = rootInsets != null
                    ? rootInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout()) : Insets.NONE;
            // The video box's size: the controls fill it, but keep a stale one while they are GONE.
            View box = mVideoArea != null && mVideoArea.getWidth() > 0 ? mVideoArea : mControlsRoot;
            int width = box.getWidth() > 0
                    ? box.getWidth() : getResources().getDisplayMetrics().widthPixels;
            int height = box.getHeight() > 0
                    ? box.getHeight() : getResources().getDisplayMetrics().heightPixels;
            int strip = controlsStrip(width, height, mVideoAspect, getResizeMode());
            left = Math.max(bars.left, strip);
            right = Math.max(bars.right, strip);
            top = bars.top;
            bottom = bars.bottom;
        } else if (mPortraitFull) {
            // MEETUBE(fullscreen): the video runs under the hidden status bar, so the top row
            // drops below the camera's band (the bars' room, visible or not) instead of flanking it.
            WindowInsetsCompat rootInsets = ViewCompat.getRootWindowInsets(mControlsRoot);
            if (rootInsets != null) {
                top = Math.max(rootInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top,
                        rootInsets.getInsets(WindowInsetsCompat.Type.displayCutout()).top);
                // ...and the bottom rows stop over the gesture bar, YouTube's.
                bottom = rootInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()).bottom;
            }
        }
        if (mControlsRoot.getPaddingLeft() != left || mControlsRoot.getPaddingTop() != top
                || mControlsRoot.getPaddingRight() != right || mControlsRoot.getPaddingBottom() != bottom) {
            mControlsRoot.setPadding(left, top, right, bottom);
        }
        bleedScrim(mTopScrim, left, top, right, 0);
        bleedScrim(mBottomScrim, left, 0, right, bottom);
        applySeekBarLayout(left, top, right, bottom);
        placeTitle();
        // MEETUBE(lock, issue #98): fullscreen only, vertical or landscape; leaving it unlocks.
        boolean full = isLandscape() || mPortraitFull;
        if (mPlayerLock != null) {
            mPlayerLock.setVisibility(full ? View.VISIBLE : View.GONE);
            if (!full) {
                setPlayerLocked(false);
            }
            mFloatingLock.setInsets(left, top, right, bottom);
        }
    }

    /**
     * MEETUBE(fullscreen): the vertical fullscreen lays the top out like YouTube's: the title under
     * the top row, the screen's width, and the channel under it. Elsewhere the title sits between
     * the top-left buttons and the options (landscape), or is gone (portrait: the page has it).
     */
    private void placeTitle() {
        if (mTitleView == null || mPlayerAuthor == null) {
            return;
        }
        boolean full = mPortraitFull && !isLandscape();
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mTitleView.getLayoutParams();
        lp.setMarginStart(dp(full ? 16 : 104));
        lp.topMargin = dp(full ? 62 : 10);
        lp.setMarginEnd(dp(full ? 16 : 148));
        mTitleView.setLayoutParams(lp);
        // Landscape: centred on the top row's buttons (issue #165); upright: its first line at the top.
        // Both: TextView.setMinHeight leaves the View-level floor (the layout's minHeight) in place,
        // which kept a one-line title's box 48dp tall (issue #205).
        mTitleView.setMinHeight(full ? 0 : dp(48));
        mTitleView.setMinimumHeight(full ? 0 : dp(48));
        mTitleView.setGravity((full ? Gravity.TOP : Gravity.CENTER_VERTICAL) | Gravity.START);
        mPlayerAuthor.setVisibility(full ? View.VISIBLE : View.GONE);
    }

    /**
     * NEWTUBE(seek bar): portrait = the bar on the video's bottom edge, edge to edge (its dot hangs
     * over the page), and its played part stays there as a line while the controls are hidden;
     * fullscreen = the bar lifted off the bottom inside the controls' insets, hidden with them. The
     * row on it and the pills follow (they live in the unpadded video box, the row in the padded
     * controls root).
     */
    private void applySeekBarLayout(int left, int top, int right, int bottom) {
        if (mTimeBar == null) {
            return;
        }
        // MEETUBE(fullscreen): the vertical fullscreen floats the bar like landscape does, just over
        // the gesture bar (applyControlsInsets).
        boolean inline = !isLandscape() && !mPortraitFull;
        int side = inline ? 0 : dp(12);
        int lift = inline ? 0 : dp(8);
        // MEETUBE(fullscreen-actions): the actions pill takes the bar's place and the bar, with
        // everything over it, rises above the pill. The chevron beside fullscreen flips it.
        boolean actions = !inline && mActionsShown && !mLockedChrome;
        if (mActionsRow != null && mActionsToggle != null) {
            mActionsRow.setVisibility(actions ? View.VISIBLE : View.GONE);
            setMargins(mActionsRow, dp(8), 0, 0, lift);
            mActionsToggle.setVisibility(inline ? View.GONE : View.VISIBLE);
            mActionsToggle.setRotation(actions ? 0f : 180f);
            mActionsToggle.setContentDescription(getString(actions
                    ? R.string.mobile_player_hide_actions : R.string.mobile_player_show_actions));
        }
        updateMoreVideosHint();
        if (actions) {
            lift += dp(44);
        }
        setMargins(mTimeBar, left + side, 0, right + side, bottom + lift);
        mTimeBar.setTrackAtBottom(inline);
        updateSeekBarLine();
        setMargins(mBottomRow, 0, 0, 0,
                getResources().getDimensionPixelSize(R.dimen.mobile_player_bottom_row_margin) + lift);
        setMargins(mScrubChapterView, 0, 0, 0,
                getResources().getDimensionPixelSize(R.dimen.mobile_player_scrub_pill_margin) + bottom + lift);
        setMargins(mTopPill, 0, top + dp(12), 0, 0);
        setMargins(mLevelPill, 0, top + dp(12), 0, 0);
        // The playback notice sits just over the row (60 dp in portrait, as in the layout).
        setMargins(mNoticeView, 0, 0, 0, dp(60) + bottom + lift);
        // The skip pill: left of fullscreen (and its chevron outside portrait), centered on the row.
        setMargins(mSkipSegmentPill, 0, 0, right + dp(inline ? 56 : 96), bottom + lift
                + getResources().getDimensionPixelSize(R.dimen.mobile_player_bottom_row_margin) + dp(8));
    }

    /**
     * The seek bar's line (hidden controls): portrait only, not in PiP, not for a live stream (the
     * played part of a DVR window says nothing), not while casting (the local player is idle).
     */
    private void updateSeekBarLine() {
        if (mTimeBar == null) {
            return;
        }
        Video video = getVideo();
        boolean casting = mCastOverlay != null && mCastOverlay.getVisibility() == View.VISIBLE;
        boolean line = !isLandscape() && !mIsInPip && !mPipEnterPending && !casting
                && (video == null || !video.isLive)
                && !isShortsMode(); // MEETUBE(shorts): the overlay draws its own scrub line
        if (line != mTimeBar.isLineWhenHidden()) {
            mTimeBar.setLineWhenHidden(line);
            if (!mControlsVisible) {
                startProgressUpdates();
            }
        }
    }

    /**
     * NEWTUBE(motion): a touch right after the tap that revealed the controls may be the second tap
     * of a double tap: the video box hands it to the player's tap detector whole (setTapRouter), and
     * the seek bar's band leaves it alone too - else a double tap near the bottom edge would seek to
     * wherever its second tap landed.
     */
    private boolean isSecondTapOfReveal(android.view.MotionEvent down) {
        return mInstantRevealAt != 0L && mControlsVisible
                && down.getEventTime() - mInstantRevealAt
                        <= android.view.ViewConfiguration.getDoubleTapTimeout();
    }

    /**
     * NEWTUBE(seek bar): claims the touches in the seek bar's band for the bar
     * ({@link PlayerTimeBar#isInTouchBand}), on the watch column. The row's buttons inside the band
     * (fullscreen, the chapter, LIVE) keep their taps, and nothing is routed while the video box is
     * transformed (the minimize and Back animations).
     */
    private final class SeekBandRouter implements WatchRootLayout.TouchRouter {
        private final int[] mRootAt = new int[2];
        private final int[] mBarAt = new int[2];
        private final int[] mViewAt = new int[2];
        /** Column to bar coordinates, fixed for the whole touch. */
        private float mDx;
        private float mDy;

        @Override
        public boolean claimDown(android.view.MotionEvent down) {
            if (mTimeBar == null || mVideoArea == null || !mVideoArea.getMatrix().isIdentity()
                    || isSecondTapOfReveal(down)) {
                return false;
            }
            mWatchRoot.getLocationInWindow(mRootAt);
            mTimeBar.getLocationInWindow(mBarAt);
            mDx = mRootAt[0] - mBarAt[0];
            mDy = mRootAt[1] - mBarAt[1];
            if (!mTimeBar.isInTouchBand(down.getX() + mDx, down.getY() + mDy)) {
                return false;
            }
            float windowX = down.getX() + mRootAt[0];
            float windowY = down.getY() + mRootAt[1];
            return !isOn(mFullscreenButton, windowX, windowY) && !isOn(mChapterButton, windowX, windowY)
                    && !isOn(mLiveChip, windowX, windowY) && !isOn(mActionsToggle, windowX, windowY);
        }

        @Override
        public void route(android.view.MotionEvent event) {
            android.view.MotionEvent local = android.view.MotionEvent.obtain(event);
            local.offsetLocation(mDx, mDy);
            mTimeBar.onRoutedTouchEvent(local);
            local.recycle();
        }

        /** Whether a window point is on this view while it shows (a button in the band keeps its tap). */
        private boolean isOn(@Nullable View view, float windowX, float windowY) {
            if (view == null || !view.isShown() || view.getAlpha() <= 0f) {
                return false;
            }
            view.getLocationInWindow(mViewAt);
            return windowX >= mViewAt[0] && windowX < mViewAt[0] + view.getWidth()
                    && windowY >= mViewAt[1] && windowY < mViewAt[1] + view.getHeight();
        }
    }

    private static void setMargins(@Nullable View view, int left, int top, int right, int bottom) {
        if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (lp.leftMargin != left || lp.topMargin != top || lp.rightMargin != right || lp.bottomMargin != bottom) {
            lp.setMargins(left, top, right, bottom);
            view.setLayoutParams(lp);
        }
    }

    /**
     * NEWTUBE(issue #9): undo the controls root's padding for a scrim, so it spans the whole video
     * area. Padded with the root, the scrims stopped at the 16:9 box or at the cutout inset and left
     * a hard vertical edge on any picture that reaches the screen edge.
     */
    private static void bleedScrim(@Nullable View scrim, int left, int top, int right, int bottom) {
        if (scrim == null || !(scrim.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) scrim.getLayoutParams();
        if (lp.leftMargin != -left || lp.topMargin != -top
                || lp.rightMargin != -right || lp.bottomMargin != -bottom) {
            lp.setMargins(-left, -top, -right, -bottom);
            scrim.setLayoutParams(lp);
        }
    }

    /**
     * NEWTUBE(issue #9): width of the pillarbox strip beside a fitted video in a width x height
     * box - where the fullscreen controls start. It used to be the 16:9 strip for every video, so on
     * a 20:9 phone the controls of a film wider than 16:9 (2.35:1 fills the width) sat 272 px in,
     * over the picture, and their scrims (then padded with them) ended there as two hard vertical
     * edges. Capped at the 16:9 strip, so a narrower video (4:3, vertical)
     * keeps the 16:9 control box instead of a thin column; 0 when the video covers the width: a
     * wider film, or a fill/zoom resize mode. An unknown aspect (no video size yet) counts as 16:9.
     */
    static int controlsStrip(int width, int height, float videoAspect, int resizeMode) {
        int strip16x9 = Math.max(0, Math.round((width - height * 16f / 9f) / 2f));
        if (resizeMode != PlayerConstants.RESIZE_MODE_DEFAULT
                && resizeMode != PlayerConstants.RESIZE_MODE_FIT_HEIGHT) {
            return 0; // fixed width, fill and zoom all cover the full width
        }
        if (!(videoAspect > 0f)) {
            return strip16x9;
        }
        return Math.min(strip16x9, Math.max(0, Math.round((width - height * videoAspect) / 2f)));
    }

    private void applySystemBarsForOrientation(int orientation) {
        MobileSheets.setPlayerShown(!mIsStopped && !mIsInPip,
                orientation == Configuration.ORIENTATION_LANDSCAPE);
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            applyDisplayCutoutMode(true);
            // Immersive full-bleed video (PLAYER POLISH behaviour).
            applyImmersiveSystemBars();
            if (ThemeMode.isLight(this)) {
                // NEWTUBE(theme): all video - not the light portrait backdrop's white page band.
                getWindow().getDecorView().setBackgroundColor(Color.BLACK);
            }
        } else {
            applyDisplayCutoutMode(false);
            // Watch page: standard phone chrome (solid status bar, video box below it -
            // YouTube-style). Replaces the old transparent-status-bar-over-the-video design,
            // whose edge-to-edge flags survived the fullscreen round-trip and left the status
            // bar overlapping the video (and leaked into the other screens).
            applyMobileSystemBars();
            // Keep the safe status-bar inset, but paint it the same black as the video surface.
            // Otherwise the app's #0F0F0F window background reads as a stray top margin above
            // the #000000 player on cutout devices with a tall safe inset (notably Pixel). Modern
            // Android makes the status bar transparent for edge-to-edge apps, so its effective
            // color comes from the decor background rather than setStatusBarColor alone. The
            // minimize morph animates this drawable's alpha so it does not become an opaque black
            // wall between the shrinking player and the translucent window's live backdrop.
            Window window = getWindow();
            if (ThemeMode.isLight(this)) {
                // NEWTUBE(theme): black only behind the status bar; the navigation-bar band takes
                // the white page's colour instead of drawing a black strip under it.
                window.getDecorView().setBackground(new WatchBackdropDrawable(window.getDecorView(),
                        Color.BLACK, getColorInt(R.color.mobile_color_background)));
            } else {
                window.getDecorView().setBackgroundColor(Color.BLACK);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.setStatusBarContrastEnforced(false);
            }
            window.setStatusBarColor(Color.BLACK);
        }

        if (mControlsRoot != null) {
            ViewCompat.requestApplyInsets(mControlsRoot);
        }
        if (mWatchRoot != null) {
            ViewCompat.requestApplyInsets(mWatchRoot);
        }
    }

    /**
     * NEWTUBE(issue #68): fullscreen's hidden, sticky-immersive bars. A swipe from an edge shows
     * them over the video for a moment; a tap is the player's and never shows them.
     *
     * <p>Was {@code Helpers.makeActivityFullscreen2}, which only told the insets controller - and
     * on Android 11 that does not last. Android 11's ViewRootImpl rebuilds its window attributes
     * from the Window's own copy on EVERY attribute change (that helper's own translucent flags
     * right after, the cutout mode, KEEP_SCREEN_ON on play/pause) and drops the "behaviour set by
     * the controller" flag doing it; the behaviour is then re-derived from the legacy
     * systemUiVisibility flags, which held no IMMERSIVE_STICKY: BEHAVIOR_SHOW_BARS_BY_TOUCH. So
     * the system ate the first tap to show the navigation bar (gesture pill) and left it shown
     * for good (Mi A3; reproduced on an API 30 emulator: no {@code bhv=}, nav visible after one
     * tap). Android 12 keeps the flag across attribute copies, so there the controller is enough.
     * Android 11 gets the legacy flags too: the visibility is carried over every copy, so the
     * re-derived behaviour is the sticky one.</p>
     */
    private void applyImmersiveSystemBars() {
        Window window = getWindow();
        View decor = window.getDecorView();
        // Window flags first: on Android 11 a later attribute change resets the behaviour below.
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        int legacy = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            if (Build.VERSION.SDK_INT == 30) {
                decor.setSystemUiVisibility(legacy);
            }
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                // Bars swiped in over the video: light icons, whatever the page theme asked for.
                controller.setSystemBarsAppearance(0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            decor.setSystemUiVisibility(legacy);
        }
    }

    /**
     * Hiding the bars does not by itself let a window use the camera-cutout strip. Without this,
     * Android inset the whole landscape decor by the Pixel's 173px cutout and the 16:9 frame was
     * visibly shifted right by 86.5px. ALWAYS is the modern full-bleed mode; Android 9/10 use the
     * closest available SHORT_EDGES behavior. Portrait returns to the platform default.
     */
    private void applyDisplayCutoutMode(boolean fullscreen) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }

        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        int desired;
        if (!fullscreen) {
            desired = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            desired = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else {
            desired = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        if (attributes.layoutInDisplayCutoutMode != desired) {
            attributes.layoutInDisplayCutoutMode = desired;
            getWindow().setAttributes(attributes);
        }
    }

    private void applyPortraitVideoHeight(int width) {
        if (mVideoArea == null || width <= 0) {
            return;
        }
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mVideoArea.getLayoutParams();
        int height = isTallMode() ? LinearLayout.LayoutParams.MATCH_PARENT : inlineBoxHeight(width);
        if (lp.height != height || lp.weight != 0) {
            lp.height = height;
            lp.weight = 0;
            mVideoArea.setLayoutParams(lp);
        }
    }

    /**
     * The inline video box: the video's own shape across the window's width, like YouTube's
     * (MEETUBE, issue #13: a square music video sat in a 16:9 box with bars at its sides). Never
     * shorter than 16:9, so a wider film keeps the 16:9 box, and never taller than two thirds of
     * the window, YouTube's cap for a vertical video, so the title and buttons stay in sight. In a
     * landscape-shaped window (a tablet that left fullscreen without rotating), where a full-width
     * 16:9 box would be taller than the screen and bury the watch page, it stops at 60% of the
     * window height.
     */
    private int inlineBoxHeight(int width) {
        int height = Math.round(width * 9f / 16f);
        Configuration config = getResources().getConfiguration();
        if (config.screenHeightDp > 0) {
            // screenHeightDp is in system dp while this activity's density carries the UI scale
            // override: convert with the system density (VideoViewportCap.windowPixels).
            float systemDensity = getApplicationContext().getResources().getConfiguration()
                    .densityDpi / 160f;
            int windowHeight = Math.round(config.screenHeightDp * systemDensity);
            if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                return Math.min(height, Math.round(windowHeight * 0.6f));
            }
            if (mShorts != null && mShorts.isCommentsOpen()) {
                // MEETUBE(shorts): YouTube's split for a short's comments - the short in the top
                // third, the comments under it. The two-thirds cap below left them a third (#27).
                return windowHeight / 3;
            }
            if (mInlineAspect > 0f && mInlineAspect < 16f / 9f) {
                int tall = Math.min(Math.round(width / mInlineAspect), windowHeight * 2 / 3);
                height = Math.max(height, tall - mTallBoxCollapse); // the page scrolled it away (#36)
            }
        }
        return height;
    }

    /** MEETUBE(issue #13): the playing video's shape for the inline box; 0 = not known, 16:9. */
    private float mInlineAspect;

    /**
     * MEETUBE(issue #13): the inline box takes {@code aspect}, growing or shrinking in place while
     * the page is on screen. Called with the tracks' shape as a video opens, before its first
     * frame, then with the decoded size. Elsewhere (fullscreen, PiP) the box picks it up when the
     * page lays out again.
     */
    private void setInlineAspect(float aspect) {
        if (aspect <= 0f || Math.abs(aspect - mInlineAspect) < 0.01f) {
            return;
        }
        mInlineAspect = aspect;
        mTallBoxCollapse = 0;
        if (mWatchRoot == null || mWatchRoot.getWidth() <= 0 || mIsInPip || mPipEnterPending
                || layoutOrientation() != Configuration.ORIENTATION_PORTRAIT) {
            return;
        }
        if (mIsResumed && mMorphFraction == 0f && !mMorphStartPending && !isTallMode()) {
            android.transition.TransitionManager.beginDelayedTransition((ViewGroup) mWatchRoot,
                    new android.transition.ChangeBounds().setDuration(PORTRAIT_FULL_MS).setInterpolator(Motion.EMPHASIZED));
        }
        applyPortraitVideoHeight(mWatchRoot.getWidth());
    }

    /** MEETUBE(issue #36): how much of a tall video's box the page has scrolled away, px. */
    private int mTallBoxCollapse;

    /**
     * MEETUBE(issue #36): YouTube's page under a tall video - scrolled up, the page first shrinks
     * the box down to 16:9, then moves; back at its top, pulled down, it grows the box again
     * before anything else. Returns the part of {@code dy} the box took.
     */
    private int collapseTallBox(View page, int dy) {
        if (page != mWatchScroll || mWatchRoot == null || mVideoArea == null || isTallMode()
                || mIsInPip || mPipEnterPending || mMorphFraction != 0f
                || layoutOrientation() != Configuration.ORIENTATION_PORTRAIT
                || (dy < 0 && page.canScrollVertically(-1))) {
            return 0;
        }
        int width = mWatchRoot.getWidth();
        int height = mVideoArea.getLayoutParams().height;
        if (width <= 0 || height <= 0) {
            return 0;
        }
        int spare = height + mTallBoxCollapse - Math.round(width * 9f / 16f);
        int collapse = Math.max(0, Math.min(spare, mTallBoxCollapse + dy));
        int taken = collapse - mTallBoxCollapse;
        if (taken != 0) {
            mTallBoxCollapse = collapse;
            applyPortraitVideoHeight(width);
            // The page moves up with the box now, not at the next frame's layout: the scroll view
            // reads the finger against where the page stands, and one that moved a frame late took
            // a slow pull up for a push down, so the box shrank, grew back and shrank again.
            View root = mWatchRoot;
            if (root.getHeight() > 0) {
                root.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.EXACTLY));
                root.layout(root.getLeft(), root.getTop(), root.getRight(), root.getBottom());
            }
        }
        return taken;
    }

    /** MEETUBE(issue #13): the shape of the biggest video track, 0 without one. */
    private static float tracksAspect(androidx.media3.common.Tracks tracks) {
        androidx.media3.common.Format biggest = null;
        for (androidx.media3.common.Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != androidx.media3.common.C.TRACK_TYPE_VIDEO) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                androidx.media3.common.Format format = group.getTrackFormat(i);
                if (format.width > 0 && format.height > 0
                        && (biggest == null || format.width * format.height > biggest.width * biggest.height)) {
                    biggest = format;
                }
            }
        }
        if (biggest == null) {
            return 0f;
        }
        float ratio = biggest.pixelWidthHeightRatio > 0f ? biggest.pixelWidthHeightRatio : 1f;
        float aspect = biggest.width * ratio / biggest.height;
        return biggest.rotationDegrees % 180 != 0 ? 1f / aspect : aspect;
    }

    private boolean isLandscape() {
        return layoutOrientation() == Configuration.ORIENTATION_LANDSCAPE;
    }

    // ---------------------------------------------------------------------------------
    // Picture-in-Picture
    // ---------------------------------------------------------------------------------

    /**
     * The menu's Picture-in-picture row: an explicit request, so it works in every background mode
     * (with "Only audio" it is the one way into PiP). NEWTUBE(menu-pip): the screen under the player
     * stays on screen and regains focus, which must not read as a launcher return - see
     * {@code SystemPipBridge.sInAppPip}.
     */
    private void enterPipFromMenu() {
        // Read before the request: once it is accepted the player already sits in its own pinned
        // task, where it is always the root.
        boolean screenUnderPlayer = !isTaskRoot();
        enterPipMode();
        if (mPipEnterPending) { // accepted
            SystemPipBridge.onMenuPipEntered(screenUnderPlayer);
        }
    }

    /** Enter PiP: shrink the video into a floating window that keeps playing, with a play/pause action. */
    private void enterPipMode() {
        com.newtube.mobile.ui.common.PlayerTrace.mark("enter PiP (minimize to system window)"); // JOYBOY(v952)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || !Helpers.isPictureInPictureSupported(this)
                || mIsInPip) {
            logPip("enter-skip");
            return;
        }

        logPip("enter-request");
        // Strip the window down to video BEFORE handing it to the system: the shrink animation
        // captures the live window content, and waiting for onPictureInPictureModeChanged(true)
        // to hide things meant the whole watch page + controls stayed visible squeezed inside the
        // shrinking PiP window for its first ~300 ms (the "enter-animation flash").
        applyPipVideoOnlyLayout();

        boolean entered = false;
        try {
            entered = enterPictureInPictureMode(buildPipParams());
        } catch (Exception e) {
            // Device reported PiP support but refused (e.g. OEM restriction) - stay full-screen.
            logPip("enter-exception error=" + e.getClass().getSimpleName()
                    + ':' + com.liskovsoft.smartyoutubetv2.common.misc.NetPath.trunc(
                            e.getMessage(), 100));
        }
        if (!entered) {
            // Refused: undo the pre-stripped layout so the watch page comes back.
            int orientation = layoutOrientation();
            applyWatchLayoutForOrientation(orientation);
            applySystemBarsForOrientation(orientation);
            if (mTimeBar != null) {
                mTimeBar.setVisibility(View.VISIBLE);
            }
            showControlsInternal(false);
            if (mShorts != null) {
                mShorts.setInPip(false);
            }
            logPip("enter-refused-restored");
        } else {
            // onPictureInPictureModeChanged(true) lands a frame or two later; until it does,
            // mIsInPip is still false and the minimize guards would wave a hand-off through into
            // what is already becoming a pinned task. Treat "accepted" as "in PiP" from here.
            mPipEnterPending = true;
            mPipLayoutHold = true;
            logPip("enter-accepted");
        }
    }

    /** Video-only window: what PiP shows. Idempotent; onPictureInPictureModeChanged exit undoes it. */
    private void applyPipVideoOnlyLayout() {
        cancelAutoHide();
        hideControls();
        // MEETUBE(lyrics): a sheet's window belongs to this activity, so it shrank into the pinned
        // window too (the lyrics panel left open over the launcher, issue #30).
        dismissPlayerSheets();
        if (mShorts != null) {
            mShorts.setInPip(true); // MEETUBE(shorts-pip): only the short, no side buttons
        }
        // NEWTUBE(hold-speed): a hold does not follow the video into PiP (no touch ends it there).
        cancelHoldSpeed();
        // NEWTUBE(seek bar): the pills beside the controls go at once, not on a fade that the
        // pinned window would show.
        for (View pill : new View[] {mTopPill, mScrubChapterView}) {
            if (pill != null) {
                pill.animate().cancel();
                pill.setVisibility(View.GONE);
            }
        }
        // NEWTUBE(theme): the pinned window is all video - black behind it in both themes (the
        // light theme's portrait backdrop is white below the status band). The exit restores it
        // through applySystemBarsForOrientation.
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);
        // Defensive surface repair: a task/mini-player hand-off can leave the Activity-owned
        // TextureView detached for a frame. PiP must never snapshot the watch UI with no video
        // consumer. Do not steal a texture that is legitimately owned by an active mini card.
        if (!MiniPlayerBridge.isActive()) {
            reattachVideoTexture();
        }
        if (mVideoTexture != null) {
            mVideoTexture.setVisibility(View.VISIBLE);
        }
        if (mControlsRoot != null) {
            mControlsRoot.setVisibility(View.GONE);
        }
        if (mTimeBar != null) {
            mTimeBar.setVisibility(View.GONE); // not even its line: the pinned window is all video
        }
        if (mWatchScroll != null) {
            mWatchScroll.setVisibility(View.GONE);
        }
        if (mCommentsPanel != null) {
            mCommentsPanel.setSuspended(true);
        }
        closeSidePanelsImmediately();
        if (mVideoArea != null) {
            mVideoArea.setVisibility(View.VISIBLE);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mVideoArea.getLayoutParams();
            lp.height = LinearLayout.LayoutParams.MATCH_PARENT;
            mCommentsSide = false; // the window is all video
            layOutCommentsSide(lp, false);
            mVideoArea.setLayoutParams(lp);
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private PictureInPictureParams buildPipParams() {
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();

        builder.setAspectRatio(getVideoAspectRatio());

        // Smooth expand/collapse animation anchored on the current video box. Skip while already
        // pinned: the video area's global rect is then in PiP-window coordinates (observed pushing
        // an off-screen hint mid-PiP), and the entry hint the system captured stays valid anyway.
        if (mVideoArea != null && !mIsInPip) {
            Rect sourceRect = new Rect();
            mVideoArea.getGlobalVisibleRect(sourceRect);
            if (!sourceRect.isEmpty()) {
                builder.setSourceRectHint(sourceRect);
            }
        }

        if (mShorts != null && mShorts.isActive()) {
            builder.setActions(java.util.Arrays.asList(
                    buildPipAction(ACTION_PIP_PREV, PIP_REQUEST_PREV, R.drawable.ic_player_previous,
                            R.string.mobile_player_previous),
                    buildPlayPauseAction(),
                    buildPipAction(ACTION_PIP_NEXT, PIP_REQUEST_NEXT, R.drawable.ic_player_next,
                            R.string.mobile_player_next)));
        } else {
            builder.setActions(java.util.Arrays.asList(buildAudioOnlyAction(), buildPlayPauseAction()));
        }

        // Android 12+ gesture navigation does NOT deliver onUserLeaveHint in time for the home
        // gesture, so the manual enterPipMode() path never fires there (observed on the emulator:
        // KEYCODE_HOME entered PiP, the swipe-home gesture didn't). The modern mechanism - and the
        // one the official YouTube app uses for its seamless shrink-into-PiP - is auto-enter: the
        // params carry a standing "PiP me when the user leaves" flag, kept in sync with the play
        // state by updatePipActions() so a paused/ended video doesn't PiP (matches YouTube).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(shouldAutoEnterPip());
        }

        return builder.build();
    }

    /**
     * Auto-enter PiP is armed only while something is actually playing (or about to resume after a
     * rebuffer: playWhenReady covers both, so a home-press during buffering still PiPs, like
     * YouTube) and we're not mid-hand-off to the in-app mini-player.
     *
     * <p>{@link #mIsResumed} is what keeps auto-enter tied to a real user departure. The standing
     * flag is refreshed whenever the play state changes, so without it a video that reaches
     * playWhenReady while this Activity sits in the background arms auto-enter from the background
     * - and the system, seeing an already-departed activity, drops it straight into PiP. Observed on
     * a Pixel 9 as: open a video by intent while a PiP session is up, and ~7s later (the moment the
     * new video became ready) the freshly expanded player bounced back into a PiP window on its own,
     * which reads as "the video opened in a corner of the screen".</p>
     */
    private boolean shouldAutoEnterPip() {
        return !mSuppressAutoPip
                && !mDismissDragActive
                && mIsResumed
                && !isFinishing()
                && !mIsEnded
                && mExoPlayerController != null
                && mExoPlayerController.getPlayWhenReady()
                && !mAudioOnly // MEETUBE(audio-only): Home keeps the sound going, no PiP window
                && MiniPip.isAutoEnabled(this); // MEETUBE(issue #103)
    }

    /**
     * MEETUBE(audio-only): the headphones button. On: the video track is dropped (no video
     * downloaded or decoded, as in background audio), the thumbnail takes the video's place, and
     * Home keeps the sound playing with the media notification instead of opening a PiP window.
     * Lasts for this player session, so the next video plays as audio too.
     * ponytail: the in-app mini card shows the frozen frame meanwhile; draw the cover there if asked.
     */
    private void setAudioOnly(boolean audioOnly) {
        mAudioOnly = audioOnly;
        sAudioOnly = audioOnly;
        mAudioButton.setSelected(audioOnly);
        if (mExoPlayerController != null && !mBackgroundAudioMode) {
            mExoPlayerController.setVideoTrackDisabled(audioOnly);
        }
        showAudioCover(getVideo());
        updatePipActions();
        com.newtube.mobile.ui.common.MobileSnackbar.show(this, audioOnly ? R.string.mobile_player_audio_only : R.string.mobile_player_audio_off);
    }

    /** The audio-only cover: {@code video}'s thumbnail, dimmed, with the headphones over it. */
    private void showAudioCover(@Nullable Video video) {
        if (!mAudioOnly || video == null || mPlayerView == null) {
            if (mAudioCover != null) {
                mAudioCover.setVisibility(View.GONE);
            }
            return;
        }
        if (mAudioCover == null) {
            FrameLayout cover = new FrameLayout(this);
            cover.setBackgroundColor(Color.BLACK);
            mAudioCoverArt = new ImageView(this);
            mAudioCoverArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            mAudioCoverArt.setAlpha(0.45f);
            cover.addView(mAudioCoverArt, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ImageView glyph = new ImageView(this);
            glyph.setImageResource(R.drawable.ic_player_headphones);
            cover.addView(glyph, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER));
            mPlayerView.getContentFrame().addView(cover, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            mAudioCover = cover;
        }
        mAudioCover.setVisibility(View.VISIBLE);
        String thumb = ClickbaitRemover.updateThumbnail(video, MainUIData.instance(this).getThumbQuality());
        if (thumb == null && video.videoId != null) { // opened from a link: no card image yet
            thumb = "https://i.ytimg.com/vi/" + video.videoId + "/hqdefault.jpg";
        }
        if (thumb != null && !isDestroyed()) {
            // The application request manager: the activity's is held while a video opens, and
            // with the video track dropped that open never shows a frame to release it.
            Glide.with(getApplicationContext())
                    .load(thumb)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .into(mAudioCoverArt);
        }
    }

    /** Video aspect ratio for the PiP window, clamped to the range Android accepts (~0.42..2.39). */
    private Rational getVideoAspectRatio() {
        int width = 16;
        int height = 9;

        if (mPlayer != null && mPlayer.getVideoFormat() != null && mPlayer.getVideoFormat().height > 0) {
            width = mPlayer.getVideoFormat().width;
            height = mPlayer.getVideoFormat().height;
        } else if (mVideoAspect > 0) {
            // MEETUBE(shorts pip): between videos (PiP next) the format is gone for a moment; the
            // 16:9 default then turned the portrait window landscape. Keep the last known shape.
            width = Math.round(mVideoAspect * 1000);
            height = 1000;
        } else if (mShorts != null && mShorts.isActive()) {
            width = 9;
            height = 16;
        }

        float ratio = (float) width / height;
        if (ratio < 0.5f) {
            return new Rational(1, 2);
        }
        if (ratio > 2.3f) {
            return new Rational(23, 10);
        }
        return new Rational(width, height);
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private RemoteAction buildPlayPauseAction() {
        boolean playing = mExoPlayerController != null && mExoPlayerController.getPlayWhenReady() && !mIsEnded;

        int iconRes = playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play;
        int labelRes = playing ? R.string.mobile_player_pause : R.string.mobile_player_play;

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        PendingIntent intent = PendingIntent.getBroadcast(
                this,
                PIP_REQUEST_TOGGLE,
                new Intent(ACTION_PIP_TOGGLE).setPackage(getPackageName()),
                piFlags);

        Icon icon = Icon.createWithResource(this, iconRes);
        return new RemoteAction(icon, getString(labelRes), getString(labelRes), intent);
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private RemoteAction buildPipAction(String action, int requestCode, int iconRes, int labelRes) {
        PendingIntent intent = PendingIntent.getBroadcast(this, requestCode,
                new Intent(action).setPackage(getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String label = getString(labelRes);
        return new RemoteAction(Icon.createWithResource(this, iconRes), label, label, intent);
    }

    /** MEETUBE(audio-only): headphones in the PiP window - the window closes, the sound plays on. */
    @RequiresApi(Build.VERSION_CODES.O)
    private RemoteAction buildAudioOnlyAction() {
        PendingIntent intent = PendingIntent.getBroadcast(this, PIP_REQUEST_AUDIO,
                new Intent(ACTION_PIP_AUDIO).setPackage(getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String label = getString(R.string.mobile_player_audio_only);
        return new RemoteAction(Icon.createWithResource(this, R.drawable.ic_player_headphones), label, label, intent);
    }

    /**
     * Push fresh PiP params to the system. In PiP this updates the play/pause action icon; OUTSIDE
     * PiP it keeps the standing auto-enter flag + aspect ratio + source rect current, which is what
     * makes the Android 12+ home-gesture auto-PiP fire (the system reads these params at leave time
     * - they must already be set, there is no callback to set them in).
     */
    private void updatePipActions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !Helpers.isPictureInPictureSupported(this)) {
            return;
        }
        // A closed PiP finishes the player while play-state changes still arrive; the system
        // refuses params for a finishing activity (IllegalStateException), and there is nothing
        // left to arm.
        if (isFinishing() || isDestroyed()) {
            return;
        }
        // MEETUBE(pip-smooth): in PiP every push makes SystemUI reload the action icons on the thread
        // that moves the PiP window (137 ms stalls logged on wmshell.main), and buffering flips
        // pushed twice each. Only a new play/pause icon or shape is worth a push there.
        if (mIsInPip) {
            String shown = (mExoPlayerController != null && mExoPlayerController.getPlayWhenReady() && !mIsEnded)
                    + "/" + getVideoAspectRatio() + "/" + (mShorts != null && mShorts.isActive());
            if (shown.equals(mPipParamsShown)) {
                return;
            }
            mPipParamsShown = shown;
        } else {
            mPipParamsShown = null;
        }
        try {
            if (BuildConfig.DEBUG) {
                // Fires on every play-state change - debug only. This is the line that showed the
                // auto-enter flag being disarmed ~120ms AFTER the home gesture had already latched
                // it, which is what made the whole app render inside the PiP window.
                logPip("params autoEnter=" + (shouldAutoEnterPip() ? "y" : "n")
                        + " suppress=" + mSuppressAutoPip + " drag=" + mDismissDragActive
                        + " resumed=" + mIsResumed + " miniActive=" + MiniPlayerBridge.isActive());
            }
            setPictureInPictureParams(buildPipParams());
        } catch (Exception e) {
            logPip("params-error error=" + e.getClass().getSimpleName());
        }
    }

    private void registerPipReceiver() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || mPipReceiver != null) {
            return;
        }

        mPipReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null && ACTION_PIP_TOGGLE.equals(intent.getAction())) {
                    togglePlayPause();
                    updatePipActions();
                } else if (intent != null && ACTION_PIP_PREV.equals(intent.getAction()) && mPresenter != null) {
                    logPip("action prev");
                    stepVideo(-1); // JOYBOY(v937)
                } else if (intent != null && ACTION_PIP_NEXT.equals(intent.getAction()) && mPresenter != null) {
                    logPip("action next");
                    stepVideo(1); // JOYBOY(v937)
                } else if (intent != null && intent.getBooleanExtra(EXTRA_AUDIO_TOGGLE, false)) {
                    setAudioOnly(!mAudioOnly); // the mini card's headphones
                } else if (intent != null && ACTION_PIP_AUDIO.equals(intent.getAction()) && mIsInPip) {
                    // Sent behind Home like a dismissed window; the exit below keeps the player.
                    mPipToAudio = true;
                    if (!mAudioOnly) {
                        setAudioOnly(true);
                    }
                    moveTaskToBack(true);
                } else if (intent != null && ACTION_PIP_AUDIO.equals(intent.getAction()) && !mAudioOnly) {
                    setAudioOnly(true); // the mini card's PiP window (MiniPip) closes itself
                }
            }
        };

        // Internal-only broadcast; must be flagged not-exported on API 34+.
        IntentFilter filter = new IntentFilter(ACTION_PIP_TOGGLE);
        filter.addAction(ACTION_PIP_AUDIO);
        filter.addAction(ACTION_PIP_PREV);
        filter.addAction(ACTION_PIP_NEXT);
        ContextCompat.registerReceiver(this, mPipReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterPipReceiver() {
        if (mPipReceiver != null) {
            try {
                unregisterReceiver(mPipReceiver);
            } catch (Exception e) {
                // not registered
            }
            mPipReceiver = null;
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);

        mIsInPip = isInPictureInPictureMode;
        if (isInPictureInPictureMode && mMoreVideos != null) {
            mMoreVideos.hide(); // the PiP window shows only the video
        }
        mPipStateStale = false;
        mPipLayoutHold = true; // entering, or on the way out until onResume
        mPipEnterPending = false;
        logPip("mode-changed inPip=" + (isInPictureInPictureMode ? "y" : "n")
                + " stopped=" + (mIsStopped ? "y" : "n"));

        if (isInPictureInPictureMode && mRoutedInWhileLeaving) {
            // NEWTUBE(link-while-playing): this PiP was started by the task switch of a link that
            // routed a new video HERE - the user asked for the full player. Expand right back.
            mRoutedInWhileLeaving = false;
            mRoutedInRestoreAttempts = 0;
            restoreRoutedInPip();
        } else if (!isInPictureInPictureMode) {
            Utils.removeCallbacks(mRoutedInRestore);
        }

        if (mSwipeLevels != null) {
            if (isInPictureInPictureMode) {
                mSwipeLevels.cancel();
            }
            updateSwipeBrightness(); // NEWTUBE(gestures): only fullscreen in front keeps it
        }
        if (isInPictureInPictureMode) {
            mPipDismissPending = false;
            MobileSheets.setPlayerShown(false, false); // the screen under PiP keeps its bars
            // A forced orientation must not survive into the pinned task - it wedges the window
            // there permanently (see mPrePipOrientation). Done here rather than in enterPipMode()
            // because the Android 12+ home-gesture auto-enter never goes through that method.
            releaseOrientationLockForPip();
            // Video only: hide the controls overlay and the watch-page content, fill with the
            // video. Usually already done (enterPipMode pre-applies it; the auto-enter home
            // gesture is the path that arrives here without it).
            applyPipVideoOnlyLayout();
            // Fetch NEW chunks at the PiP window's rung; already-buffered chunks keep playing.
            if (mExoPlayerController != null) {
                mExoPlayerController.setSmallWindowViewport("pip", newConfig);
            }
            // The PiP window renders only video: an open chat sheet is invisible, so its poll is
            // pure waste (same rule as background audio in setBackgroundAudioMode).
            if (mLiveChatAction != null) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                            "live-chat poll stop (pip)");
                }
                stopLiveChatStream();
            }
            updatePipActions();
        } else {
            SystemPipBridge.onPipEnded();
            if (mExoPlayerController != null) {
                mExoPlayerController.clearSmallWindowViewport("pip-exit");
            }
            // Dismiss vs expand: an expand always ends with onResume (which clears the flag); a
            // dismiss ends with onStop. On the older PiP shell the dismissal onStop ran BEFORE this
            // callback, so if we're already stopped this exit can only be a dismissal - finish now.
            if (mPipToAudio) {
                // MEETUBE(audio-only): the PiP headphones, not a dismiss. Already stopped: onStop ran
                // while still pinned and skipped the background-audio switch, so make it here.
                if (mIsStopped) {
                    setBackgroundAudioMode(true);
                }
            } else if (mIsStopped) {
                mPrePipOrientation = ORIENTATION_NONE; // dismissed; nothing left to restore onto
                finishFromPipDismiss();
                return;
            }
            mPipDismissPending = !mPipToAudio;
            restoreOrientationLockAfterPip();

            // Mirror of the setBackgroundAudioMode(false) revive: the sheet fragment survives the
            // PiP stint, so bring its stream back when the full watch UI returns.
            if (mChatObserver != null && mChatReceiver == null
                    && mLiveChatAction == null && mLiveChatKey != null) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                            "live-chat poll resume (pip exit)");
                }
                startLiveChatStream();
            }
            // Restore the normal layout and controls. Explicitly showing them also resets the
            // GONE/alpha state left by PiP; on some task-reparent exits the first PlayerView tap
            // is swallowed by the system transition, otherwise leaving the restored player with
            // no apparent controls.
            int orientation = newConfig != null ? layoutOrientation(newConfig.orientation)
                    : layoutOrientation();
            logPip("exit-layout newConfig=" + (newConfig != null ? newConfig.orientation : -1)
                    + " resources=" + layoutOrientation()
                    + " chosen=" + orientation);
            if (mShorts != null) {
                mShorts.setInPip(false);
            }
            applyWatchLayoutForOrientation(orientation);
            applySystemBarsForOrientation(orientation);
            updatePlayPauseIcon();
            if (mTimeBar != null) {
                mTimeBar.setVisibility(View.VISIBLE);
                updateSeekBarLine();
            }
            if (mControlsRoot != null) {
                showControlsInternal(false);
            }
        }
    }

    /**
     * Drop any forced orientation for the duration of a PiP stint. A PiP window is sized by its
     * aspect ratio, never by the activity's orientation request, so nothing is lost while pinned -
     * but leaving the request in place wedges the task in {@code mode=pinned} (see
     * {@link #mPrePipOrientation}). The resting {@link #FREE_ORIENTATION} is dropped and put back
     * too, which keeps the pinned task exactly as it was when the rest state was UNSPECIFIED.
     */
    private void releaseOrientationLockForPip() {
        int requested = getRequestedOrientation();
        if (requested == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            mPrePipOrientation = ORIENTATION_NONE;
            return;
        }

        mPrePipOrientation = requested;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        logPip("orientation-released requested=" + requested);
    }

    /**
     * Put the pre-PiP orientation back once the watch UI returns, so expanding a video that was
     * left in fullscreen lands back in fullscreen. Deliberately posted: re-asserting the lock while
     * the PiP-to-fullscreen transition is still running makes the window rotate mid-animation.
     */
    private void restoreOrientationLockAfterPip() {
        if (mPrePipOrientation == ORIENTATION_NONE) {
            return;
        }

        final int restore = mPrePipOrientation;
        mPrePipOrientation = ORIENTATION_NONE;

        if (mVideoArea == null) {
            setRequestedOrientation(restore);
            return;
        }
        mVideoArea.post(() -> {
            // A second PiP entry (or a finish) may have overtaken this post.
            if (mIsInPip || isFinishing()) {
                return;
            }
            setRequestedOrientation(restore);
            logPip("orientation-restored requested=" + restore);
        });
    }

    /** Sparse, credential-free PiP/surface snapshot for OEM/system transition bug reports. */
    private void logPip(String event) {
        Video video = getVideo();
        boolean textureAttached = mVideoTexture != null && mVideoTexture.getParent() != null;
        boolean textureAvailable = mVideoTexture != null && mVideoTexture.isAvailable();
        boolean surfaceValid = mSessionSurface != null && mSessionSurface.isValid();
        int playbackState = mPlayer != null ? mPlayer.getPlaybackState() : -1;
        boolean playWhenReady = mExoPlayerController != null
                && mExoPlayerController.getPlayWhenReady();
        com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log(
                com.liskovsoft.smartyoutubetv2.common.misc.NetPath.context()
                        + " pip " + event
                        + " video=" + (video != null ? video.videoId : "?")
                        + " state=" + playbackState
                        + " pwr=" + (playWhenReady ? "y" : "n")
                        + " ended=" + (mIsEnded ? "y" : "n")
                        + " texture=" + (textureAttached ? "attached" : "detached")
                        + '/' + (textureAvailable ? "available" : "unavailable")
                        + " surface=" + (surfaceValid ? "valid" : "invalid")
                        + " mini=" + (MiniPlayerBridge.isActive() ? "y" : "n"));
    }

    // ---------------------------------------------------------------------------------
    // Background-playback service (media session + notification; reuses THIS player)
    // ---------------------------------------------------------------------------------

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mPlaybackService = ((MobilePlaybackService.LocalBinder) binder).getService();
            mServiceBound = true;
            mPlaybackService.setOnExternalPlay(MobilePlaybackActivity.this::onExternalPlay);
            if (mPlayer != null) {
                mPlaybackService.attachPlayer(mPlayer, mPresenter, buildContentIntent());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mPlaybackService = null;
            mServiceBound = false;
        }
    };

    private void bindPlaybackService() {
        BackgroundPlay.sPlayed = true; // back on Home, ask once for the battery exemption
        Intent intent = new Intent(this, MobilePlaybackService.class);
        // startService keeps it alive independently of the binding so audio survives backgrounding;
        // safe to call here because the player is created while this Activity is in the foreground.
        try {
            startService(intent);
        } catch (Exception e) {
            // Background start restrictions - fall back to bind-only (audio still survives while bound).
        }
        bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private void unbindPlaybackService() {
        if (mPlaybackService != null) {
            mPlaybackService.setOnExternalPlay(null);
        }
        if (mServiceBound) {
            try {
                unbindService(mServiceConnection);
            } catch (Exception e) {
                // not bound
            }
            mServiceBound = false;
        }
        try {
            stopService(new Intent(this, MobilePlaybackService.class));
        } catch (Exception e) {
            // ignore
        }
        mPlaybackService = null;
    }

    private PendingIntent buildContentIntent() {
        Intent intent = new Intent(this, getClass()); // JOYBOY(v947)
        // REORDER_TO_FRONT: the player may sit BELOW Browse in the shared task (mini-player);
        // a notification tap must surface the existing instance, not stack a duplicate.
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(this, 0, intent, piFlags);
    }

    // ---------------------------------------------------------------------------------
    // Casting (Route B scaffolding, CASTING.md): picker entry point + "Playing on TV" panel.
    //
    // The integration is deliberately tiny: the CastSessionManager singleton owns the session
    // (it outlives this activity - the phone is a remote); this activity only (1) opens the
    // picker, (2) pauses local playback while a session is active (never tears it down),
    // (3) mirrors CastEvents into the self-contained overlay panel, and (4) routes newly
    // selected videos to the TV (see the hooks in setVideo/handleUiStateChange).
    // ---------------------------------------------------------------------------------

    private void openCastPicker() {
        cancelAutoHide();
        // Permission gate + picker open live in CastPickerLauncher (shared with Browse);
        // presentation stays ours - showPlayerSheet is the immersive-safe presenter.
        CastPickerLauncher.open(this, this::showPlayerSheet);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        CastPickerLauncher.handlePermissionResult(this, requestCode, this::showPlayerSheet);
    }

    /**
     * Hardware volume keys drive the TV while casting (official-app behavior); everything else -
     * including volume keys with no session - falls through to normal dispatch.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (JoyboyPlayerHost.onKey(this, event)) { // JOYBOY(v939): TV add-on keys first (numeric seek)
            return true;
        }
        if (CastVolumeKeys.onDispatchKeyEvent(this, event)) {
            return true;
        }
        if (com.newtube.mobile.ui.common.FocusRing.isTv(this) && onRemoteKey(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * MEETUBE(tv): the remote on the player (issue #21). Hidden controls: OK plays or pauses and shows
     * them, Left and Right seek on the bar, Up and Down bring them up. Shown, the keys move between
     * them as focus does anywhere, and each one keeps them up. A short: Up and Down change it, OK
     * plays or pauses, unless one of its buttons has the focus.
     */
    private boolean onRemoteKey(KeyEvent event) {
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        boolean vertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean horizontal = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean press = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = press && event.getRepeatCount() == 0;
        if (mIsInPip || mPlayer == null || !(ok || vertical || horizontal)) {
            return false;
        }
        if (mShorts != null && mShorts.isActive()) {
            if (mShorts.holdsFocus() || horizontal) {
                return false;
            }
            if (first && ok) {
                togglePlayPause();
            } else if (first && mPresenter != null) {
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    stepVideo(1); // JOYBOY(v937)
                } else {
                    stepVideo(-1); // JOYBOY(v937)
                }
            }
            return true;
        }
        if (mControlsVisible) {
            if (press) {
                armAutoHide();
            }
            return false;
        }
        View focus = getCurrentFocus();
        if (focus != null && !isDescendant(mVideoArea, focus)) {
            return false; // on the watch page under the video: its own focus moves
        }
        if (press) {
            showControlsInternal(true);
            if (ok && first) {
                togglePlayPause();
            }
            if (horizontal) {
                // The double tap's jump (Settings > Player), not the bar's default 1/20 of the video.
                mTimeBar.setKeyTimeIncrement(PlayerData.instance(this).getSeekIncrementMs());
                mTimeBar.requestFocus();
                mTimeBar.onKeyDown(code, event);
            } else {
                mPlayPauseButton.requestFocus();
            }
        }
        return true;
    }

    private static boolean isDescendant(View parent, View view) {
        for (Object at = view; at instanceof View; at = ((View) at).getParent()) {
            if (at == parent) {
                return true;
            }
        }
        return false;
    }

    private void setupCastOverlay() {
        if (mCastOverlay == null) {
            return;
        }

        if (mCastPlayPause != null) {
            mCastPlayPause.setOnClickListener(v -> {
                if (mCastSessionManager != null) {
                    if (mCastSessionManager.isPlayingOnTv()) {
                        mCastSessionManager.pause();
                    } else {
                        mCastSessionManager.play();
                    }
                    updateCastOverlay();
                }
            });
        }

        View disconnect = findViewById(R.id.mobile_cast_disconnect);
        if (disconnect != null) {
            disconnect.setOnClickListener(v -> {
                if (mCastSessionManager != null) {
                    mCastSessionManager.disconnect(); // onCastSessionEnded resumes local playback
                }
            });
        }

        View options = findViewById(R.id.mobile_cast_options);
        if (options != null) {
            // These are playback capabilities, not generic device settings: Direct gets a real
            // quality cap; Lounge gets receiver subtitles and an honest TV-remote quality row.
            options.setOnClickListener(v -> showCastPlaybackOptions());
        }

        if (mCastSeekBar != null) {
            // Seconds-granularity bar (int progress can't overflow on any real duration).
            mCastSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && mCastPosition != null) {
                        mCastPosition.setText(formatTime(progress * 1_000L));
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    mCastScrubbing = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    mCastScrubbing = false;
                    if (mCastSessionManager != null && mCastSessionManager.isConnected()) {
                        mCastSessionManager.seekTo(seekBar.getProgress() * 1_000L);
                    }
                }
            });
        }
    }

    private final CastSessionManager.Listener mCastListener = new CastSessionManager.Listener() {
        @Override
        public void onCastSessionStarted(CastTarget target) {
            // Hand playback to the TV: pause the LOCAL player (keep it loaded so disconnect can
            // resume seamlessly), send the current video at the current position, show the panel.
            long resumeMs = Math.max(getPositionMs(), 0);
            if (mPlayer != null) {
                mPlayer.setPlayWhenReady(false);
            }
            Video video = getVideo();
            mCastSubtitleVssId = null;
            mCastSubtitleLabel = null;
            if (video != null && video.videoId != null) {
                mCastSessionManager.loadVideo(video.videoId, resumeMs);
            }
            showCastOverlay();
            updateCastIconTint();
        }

        @Override
        public void onCastSessionState(String videoId, long positionMs, long durationMs, boolean playing) {
            updateCastOverlay();
        }

        @Override
        public void onCastSessionEnded(String reason) {
            // Resume locally where the TV left off. Listeners fire before the manager resets its
            // state, so the last cast position is still readable here.
            long castPositionMs = mCastSessionManager != null ? mCastSessionManager.getPositionMs() : -1;
            hideCastOverlay();
            mCastSubtitleVssId = null;
            mCastSubtitleLabel = null;
            updateCastIconTint();
            if (castPositionMs > 0) {
                setPositionMs(castPositionMs);
            }
            setPlayWhenReady(true);
        }
    };

    /**
     * Connected-state affordance on the top-bar cast icon: tinted while a session is live,
     * stock white otherwise (matches the official app's colored connected icon). This is the
     * ONE coloured icon state left in the app - everything else states itself with a filled vs
     * outlined glyph - so it gets its own colour name rather than riding on the theme accent,
     * which is monochrome. Distinct from the playback red of the progress bar.
     */
    private void updateCastIconTint() {
        if (mCastButton == null) {
            return;
        }
        if (mCastSessionManager != null && mCastSessionManager.isConnected()) {
            // The player's own (fixed) blue: this icon is over the video in both themes.
            mCastButton.setColorFilter(getColorInt(R.color.mobile_player_cast_active));
        } else {
            mCastButton.clearColorFilter();
        }
    }

    private void showCastOverlay() {
        if (mCastOverlay == null) {
            return;
        }
        CastTarget target = mCastSessionManager != null ? mCastSessionManager.getTarget() : null;
        if (mCastOverlayTitle != null) {
            mCastOverlayTitle.setText(getString(R.string.mobile_cast_playing_on,
                    target != null ? target.getName() : ""));
        }
        mCastOverlay.setVisibility(View.VISIBLE);
        cancelHoldSpeed();
        updateSeekBarLine(); // the TV plays it now: no local progress line under the overlay
        hideControls();
        updateCastOverlay();
        // 1s remote ticker: CastEvents only arrive on changes; the position interpolates between
        // them (CastSessionManager.getPositionMs) so the bar moves like a normal player's.
        Utils.removeCallbacks(mCastProgressRunnable);
        Utils.postDelayed(mCastProgressRunnable, 1_000);
    }

    private void hideCastOverlay() {
        Utils.removeCallbacks(mCastProgressRunnable);
        if (mCastOverlay != null) {
            mCastOverlay.setVisibility(View.GONE);
        }
        updateSeekBarLine();
    }

    private void updateCastOverlay() {
        if (mCastOverlay == null || mCastOverlay.getVisibility() != View.VISIBLE
                || mCastSessionManager == null) {
            return;
        }

        long positionMs = Math.max(mCastSessionManager.getPositionMs(), 0);
        long durationMs = Math.max(mCastSessionManager.getDurationMs(), 0);
        Video currentVideo = getVideo();
        boolean isLive = currentVideo != null && currentVideo.isLive
                && Helpers.equals(currentVideo.videoId, mCastSessionManager.getVideoId());

        if (mCastPlayPause != null) {
            boolean playing = mCastSessionManager.isPlayingOnTv();
            mCastPlayPause.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
            mCastPlayPause.setContentDescription(
                    getString(playing ? R.string.mobile_player_pause : R.string.mobile_player_play));
        }
        // Lounge reports long-running livestream position/duration as timestamps from the stream's
        // original start (for example 1121:36:52), not a useful DVR window. Present the semantic
        // state instead and disable seeking; VOD keeps the normal elapsed/total timeline.
        if (mCastLiveChip != null) {
            mCastLiveChip.setVisibility(isLive ? View.VISIBLE : View.GONE);
        }
        if (mCastTimeline != null) {
            mCastTimeline.setVisibility(isLive ? View.GONE : View.VISIBLE);
        }
        if (isLive) {
            mCastScrubbing = false;
            return;
        }
        if (mCastDuration != null) {
            mCastDuration.setText(formatTime(durationMs));
        }
        if (mCastSeekBar != null) {
            mCastSeekBar.setMax((int) (durationMs / 1_000));
            if (!mCastScrubbing) {
                mCastSeekBar.setProgress((int) (positionMs / 1_000));
            }
        }
        if (mCastPosition != null && !mCastScrubbing) {
            mCastPosition.setText(formatTime(positionMs));
        }
    }

    private final Runnable mCastProgressRunnable = new Runnable() {
        @Override
        public void run() {
            if (mCastOverlay != null && mCastOverlay.getVisibility() == View.VISIBLE) {
                updateCastOverlay();
                Utils.postDelayed(this, 1_000);
            }
        }
    };

    /**
     * Casting has two intentionally different capability sets. Put the real controls in one
     * obvious sheet instead of sending users back through the device picker:
     * Direct = phone-side adaptive quality cap; Lounge = receiver-side subtitles, with quality
     * honestly delegated to the TV player UI.
     */
    private void showCastPlaybackOptions() {
        if (mCastSessionManager == null || !mCastSessionManager.isConnected()) {
            return;
        }

        BottomSheetDialog sheet = Haptics.watch(new BottomSheetDialog(this));
        LinearLayout content = createSheetContent();
        addCastSheetHeader(content, R.string.mobile_cast_controls_title,
                mCastSessionManager.isDirectRoute()
                        ? R.string.mobile_cast_direct_summary : R.string.mobile_cast_app_summary);

        if (mCastSessionManager.isDirectRoute()) {
            addMenuRow(content, sheet, R.drawable.ic_player_quality,
                    R.string.mobile_player_quality, currentDirectCastQualityLabel(), true,
                    this::showDirectCastQualitySheet);
            addMenuRow(content, sheet, R.drawable.ic_player_cc,
                    R.string.mobile_player_subtitles,
                    getString(R.string.mobile_cast_subtitles_need_tv_app), true,
                    this::confirmSwitchDirectCastForSubtitles);
        } else {
            addMenuRow(content, sheet, R.drawable.ic_player_quality,
                    R.string.mobile_player_quality,
                    getString(R.string.mobile_cast_quality_tv_remote), false,
                    () -> showCastSnackbar(R.string.mobile_cast_quality_receiver_help));
            addMenuRow(content, sheet, R.drawable.ic_player_cc,
                    R.string.mobile_player_subtitles, currentReceiverCaptionsLabel(), true,
                    this::showReceiverCaptionsSheet);
        }

        sheet.setContentView(sheetRoot(content));
        showPlayerSheet(sheet);
    }

    private void addCastSheetHeader(LinearLayout content, int titleRes, int summaryRes) {
        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextColor(getColorInt(R.color.mobile_color_on_surface));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(dp(20), dp(4), dp(20), dp(4));
        content.addView(title);

        TextView summary = new TextView(this);
        summary.setText(summaryRes);
        summary.setTextColor(getColorInt(R.color.mobile_color_on_surface_secondary));
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        summary.setPadding(dp(20), 0, dp(20), dp(10));
        content.addView(summary);
    }

    private String currentDirectCastQualityLabel() {
        int height = mCastSessionManager != null
                ? mCastSessionManager.getDirectQualityHeight() : 0;
        return height > 0
                ? getString(R.string.mobile_cast_quality_cap, height)
                : getString(R.string.mobile_cast_quality_auto_1080);
    }

    /** Direct Cast keeps every compatible rung up to this ceiling, preserving adaptive fallback. */
    private void showDirectCastQualitySheet() {
        if (mCastSessionManager == null || !mCastSessionManager.isDirectRoute()) {
            return;
        }

        BottomSheetDialog dialog = Haptics.watch(new BottomSheetDialog(this));
        View content = getLayoutInflater().inflate(R.layout.sheet_mobile_quality, null);
        dialog.setContentView(content);
        LinearLayout qualityList = content.findViewById(R.id.quality_sheet_quality_list);

        int selectedHeight = mCastSessionManager.getDirectQualityHeight();
        addQualityRow(qualityList, getString(R.string.mobile_cast_quality_auto_1080),
                selectedHeight == 0, () -> {
                    if (mCastSessionManager != null) {
                        mCastSessionManager.setDirectQualityHeight(0);
                    }
                    dialog.dismiss();
                });

        java.util.TreeSet<Integer> heights = new java.util.TreeSet<>(java.util.Collections.reverseOrder());
        List<FormatItem> formats = getVideoFormats();
        if (formats != null) {
            for (FormatItem item : formats) {
                if (item != null && item.getHeight() > 0
                        && item.getHeight() <= com.newtube.mobile.casting.proxy.MpdRewriter.MAX_VIDEO_HEIGHT) {
                    heights.add(item.getHeight());
                }
            }
        }
        for (int height : heights) {
            addQualityRow(qualityList,
                    getString(R.string.mobile_cast_quality_cap, height),
                    selectedHeight == height, () -> {
                        if (mCastSessionManager != null) {
                            mCastSessionManager.setDirectQualityHeight(height);
                        }
                        dialog.dismiss();
                    });
        }
        showPlayerSheet(dialog);
    }

    private String currentReceiverCaptionsLabel() {
        return mCastSubtitleLabel != null
                ? mCastSubtitleLabel : getString(R.string.mobile_menu_off);
    }

    /** Actual Lounge setSubtitlesTrack commands, backed by the current video's loaded track list. */
    private void showReceiverCaptionsSheet() {
        if (mCastSessionManager == null || mCastSessionManager.isDirectRoute()) {
            return;
        }

        BottomSheetDialog dialog = Haptics.watch(new BottomSheetDialog(this));
        View content = getLayoutInflater().inflate(R.layout.sheet_mobile_captions, null);
        dialog.setContentView(content);
        content.findViewById(R.id.captions_sheet_style_divider).setVisibility(View.GONE);
        content.findViewById(R.id.captions_sheet_style).setVisibility(View.GONE);
        LinearLayout trackList = content.findViewById(R.id.captions_sheet_track_list);

        addQualityRow(trackList, getString(R.string.mobile_captions_off),
                mCastSubtitleVssId == null, () -> {
                    applyReceiverCaption(null);
                    dialog.dismiss();
                });

        List<FormatItem> tracks = new ArrayList<>();
        List<FormatItem> formats = getSubtitleFormats();
        if (formats != null) {
            for (FormatItem item : formats) {
                if (isCaptionTrack(item)) {
                    tracks.add(item);
                }
            }
        }
        moveLastUsedCaptionsFirst(tracks);
        for (FormatItem item : tracks) {
            String vssId = item.getFormatId();
            addQualityRow(trackList, captionLabel(item),
                    Helpers.equals(vssId, mCastSubtitleVssId), () -> {
                        applyReceiverCaption(item);
                        dialog.dismiss();
                    });
        }
        if (tracks.isEmpty()) {
            content.findViewById(R.id.captions_sheet_empty).setVisibility(View.VISIBLE);
        }
        showPlayerSheet(dialog);
    }

    private void applyReceiverCaption(@Nullable FormatItem item) {
        String vssId = item != null ? item.getFormatId() : null;
        String languageCode = item != null
                ? castCaptionLanguageCode(item.getFormatId(), item.getLanguage()) : null;
        if (mCastSessionManager == null
                || !mCastSessionManager.setReceiverSubtitle(vssId, languageCode)) {
            showCastSnackbar(R.string.mobile_cast_subtitles_failed);
            return;
        }
        mCastSubtitleVssId = vssId;
        mCastSubtitleLabel = item != null ? captionLabel(item) : null;
        showCastSnackbar(item != null
                ? R.string.mobile_cast_subtitles_sent : R.string.mobile_captions_off_toast);
    }

    /** Extract BCP-47 from YouTube vss ids (.en / a.en); keep a code-shaped fallback only. */
    @Nullable
    static String castCaptionLanguageCode(@Nullable String vssId, @Nullable String fallback) {
        if (vssId != null && !vssId.isEmpty()) {
            int dot = vssId.lastIndexOf('.');
            String candidate = dot >= 0 && dot + 1 < vssId.length()
                    ? vssId.substring(dot + 1) : vssId;
            if (candidate.matches("(?i)[a-z]{2,3}([_-][a-z0-9]{2,8})*")) {
                return candidate.replace('_', '-');
            }
        }
        if (fallback != null && !fallback.isEmpty()
                && fallback.matches("(?i)[a-z]{2,3}([_-][a-z0-9]{2,8})*")) {
            return fallback.replace('_', '-');
        }
        return null;
    }

    private void confirmSwitchDirectCastForSubtitles() {
        Haptics.watch(new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                this, R.style.MobileAlertDialog)
                .setTitle(R.string.mobile_cast_switch_subtitles_title)
                .setMessage(R.string.mobile_cast_switch_subtitles_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.mobile_cast_switch_subtitles_positive,
                        (dialog, which) -> switchDirectCastToTvApp())
                .show());
    }

    private void switchDirectCastToTvApp() {
        if (mCastSessionManager == null || !mCastSessionManager.switchDirectSessionToTvApp()) {
            showCastSnackbar(R.string.mobile_cast_launch_failed);
        }
    }

    private void showCastSnackbar(int messageRes) {
        com.newtube.mobile.ui.common.MobileSnackbar.show(this, messageRes);
    }

    /**
     * setVideo hook: while a session is active a newly selected video (related tap, queue,
     * auto-advance) routes to the TV instead of playing locally. The local engine still loads it
     * paused underneath, so state/controllers stay consistent and disconnect resumes instantly.
     */
    private void maybeRouteVideoToCast(String videoId) {
        if (mCastSessionManager == null || !mCastSessionManager.isConnected() || videoId == null) {
            return;
        }
        if (!Helpers.equals(videoId, mCastSessionManager.getVideoId())) {
            mCastSubtitleVssId = null;
            mCastSubtitleLabel = null;
            mCastSessionManager.loadVideo(videoId, 0);
        }
        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(false);
        }
        showCastOverlay(); // refresh the "Playing on <TV>" panel over the new video
    }

    // ---------------------------------------------------------------------------------
    // Custom touch controls
    // ---------------------------------------------------------------------------------

    /**
     * Where a double tap at {@code posX} seeks: left third back (not in the first half second),
     * right third forward, null = no seek (the middle, or nothing playing).
     */
    @Nullable
    private Boolean doubleTapSeekForward(Player player, float posX) {
        int state = player.getPlaybackState();
        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
            return null;
        }
        int width = mPlayerView.getPlayerWidth();
        if (player.getCurrentPosition() > 500 && posX < width * 0.35f) {
            return false;
        }
        if (posX > width * 0.65f) {
            return true;
        }
        return null;
    }

    private void toggleControls() {
        if (mControlsVisible) {
            hideControls();
        } else {
            showControlsInternal(true);
        }
    }

    private void showControlsInternal(boolean animate) {
        if (isShortsMode()) {
            return; // MEETUBE(shorts): the overlay is the chrome
        }
        if (mIsInPip || mPipEnterPending) {
            // The pinned window is all video. Most callers checked mIsInPip on their own, the
            // presenter's showOverlay did not: right after a home-gesture PiP the full controls
            // came up inside the window until the auto-hide timer took them away.
            logPip("controls-blocked by=" + new Throwable().getStackTrace()[1]);
            return;
        }
        mControlsVisible = true;
        JoyboyPlayerHost.onControlsVisibility(this, true); // JOYBOY(v934)
        mControlsRoot.setVisibility(View.VISIBLE);
        mControlsRoot.animate().cancel();
        if (animate) {
            mControlsRoot.setAlpha(0f);
            mControlsRoot.animate().alpha(1f).setDuration(150).start();
        } else {
            mControlsRoot.setAlpha(1f);
        }
        if (mScrubChromeHidden && !mScrubbing) {
            setScrubChrome(false); // a drag that ended while hidden must not leave them invisible
        }
        setLockedChrome(mPlayerLocked);
        mTimeBar.setShown(true, animate);
        updatePlayPauseIcon();

        // NEWTUBE(perf): drive the 500ms progress loop only while controls are visible. startProgress
        // ticks once immediately (refresh on show) then reschedules every 500ms.
        startProgressUpdates();

        if (mPresenter != null) {
            mPresenter.onControlsShown(true);
        }

        armAutoHide();
    }

    private void hideControls() {
        if (!mControlsVisible) {
            return;
        }

        mControlsVisible = false;
        JoyboyPlayerHost.onControlsVisibility(this, false); // JOYBOY(v934): subtitle margin follows the controls
        cancelAutoHide();
        // NEWTUBE(perf): controls hidden -> the progress loop stops; in portrait the seek bar's line
        // keeps a slower one (startProgressUpdates picks).
        startProgressUpdates();
        mTimeBar.setShown(false, true);
        mControlsRoot.animate().cancel();
        mControlsRoot.animate().alpha(0f).setDuration(150)
                .withEndAction(() -> {
                    if (!mControlsVisible) {
                        mControlsRoot.setVisibility(View.GONE);
                    }
                }).start();

        if (mPresenter != null) {
            mPresenter.onControlsShown(false);
        }
    }

    private void armAutoHide() {
        cancelAutoHide();
        if (!mIsStopped) {
            Utils.postDelayed(mHideControlsRunnable, AUTO_HIDE_MS);
        }
    }

    /**
     * The mobile default seek resolution: snap to a known sync point when it is within 5s before /
     * 1s after the target, otherwise land at the window edge. Measured on the Norway repro video
     * (bnQI3v_MpOs, 1080p60 VP9):
     *  - EXACT everywhere froze the player in "buffering" ~5-7s per seek (decode from the previous
     *    keyframe up to the requested frame; sw-decode emulator numbers - shorter on hw decode but
     *    still a visible freeze), 13.5s after a rapid tap-around burst.
     *  - PREVIOUS_SYNC everywhere resumed in &lt;1s but could land up to 52s BEFORE the finger:
     *    ExoPlayer 2.10's seek adjustment sometimes consults a much coarser index than the ~5s
     *    container keyframes its own loader uses ("jumped back" feel).
     * With the bounded window, seeks resolved in ~0.9s and landed at most ~5s early - scrubbing
     * feels like YouTube's keyframe-aligned bar. 5s is ~2px on the portrait seekbar of a 30-min
     * video, well under scrub aim precision.
     */
    private static final SeekParameters MOBILE_SEEK_PARAMETERS =
            new SeekParameters(/* toleranceBeforeUs= */ 5_000_000, /* toleranceAfterUs= */ 1_000_000);

    /**
     * FAST-SEEK, scoped to the double-tap gesture only. Each +10s/-10s tap seeks with a
     * DIRECTIONAL keyframe snap - NEXT_SYNC going forward, PREVIOUS_SYNC going back - so a tap
     * always makes progress in the tapped direction at keyframe speed. The earlier CLOSEST_SYNC
     * could snap BACKWARD past a forward target: on sparse-keyframe streams repeated forward taps
     * kept landing on the same keyframe (video "stuck"/jumping - user-reported on device).
     *
     * <p>Restoring the default must NOT rely on the overlay's onAnimationEnd alone: shouldForward
     * installs the directional parameters on every double-tap detection, and a skipped animation
     * cycle used to leak PREVIOUS_SYNC as the permanent default (observed: later scrubs landing
     * ~20s early). Three layers now restore MOBILE_SEEK_PARAMETERS: onAnimationEnd (normal path),
     * a watchdog re-armed on every directional tap (covers missed/skipped animation ends), and
     * onScrubStart (a scrub definitively ends any double-tap burst).
     * setSeekParameters/seekTo post FIFO to the player's internal handler, so per-tap set -> seek
     * -> restore brackets exactly the burst's own seeks.</p>
     */
    private static final long SEEK_BURST_WATCHDOG_MS = 1_500;

    private final Runnable mSeekBurstWatchdog = this::endUserSeekBurst;

    /** Per-tap: snap to the next keyframe in the tapped direction. */
    private void setUserSeekDirection(boolean forward) {
        if (mPlayer != null) {
            mPlayer.setSeekParameters(forward ? SeekParameters.NEXT_SYNC : SeekParameters.PREVIOUS_SYNC);
            Utils.removeCallbacks(mSeekBurstWatchdog);
            Utils.postDelayed(mSeekBurstWatchdog, SEEK_BURST_WATCHDOG_MS);
        }
    }

    private void endUserSeekBurst() {
        Utils.removeCallbacks(mSeekBurstWatchdog);
        if (mPlayer != null) {
            mPlayer.setSeekParameters(MOBILE_SEEK_PARAMETERS);
        }
    }

    private void cancelAutoHide() {
        Utils.removeCallbacks(mHideControlsRunnable);
    }

    /**
     * NEWTUBE(seek bar): a finger on the seek bar, dragging or still within its slop - a drag only
     * starts (onScrubStart) once the finger has moved, and hiding the controls before that took the
     * bar out from under a finger about to drag.
     */
    private boolean isHoldingSeekBar() {
        return mScrubbing || (mTimeBar != null && mTimeBar.isHeld());
    }

    /**
     * MEETUBE(issue #121): a play from outside the screen (a headset or Bluetooth button, the lock
     * screen, the notification) brings the controls up, as a pause from outside does (issue #101),
     * so the resumed state is visible. They hide on the usual timer once it plays (onAutoHideTick).
     */
    private void onExternalPlay() {
        if (!mControlsVisible && !mIsInPip
                && (mCastSessionManager == null || !mCastSessionManager.isConnected())
                && getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            showControlsInternal(true);
        }
    }

    private void onAutoHideTick() {
        if (!mControlsVisible || mIsStopped || isPlayerSheetShown()) {
            return; // a sheet re-arms this when the last one closes (showPlayerSheet)
        }

        // Keep the controls up while the user is scrubbing or while paused/buffering/ended;
        // re-check shortly. Only auto-hide during steady playback (matches YouTube/PlayerUIController).
        if (isHoldingSeekBar() || mIsEnded || mPlayer == null || !isPlaying()) {
            armAutoHide();
            return;
        }

        hideControls();
    }

    private void togglePlayPause() {
        if (mExoPlayerController == null) {
            return;
        }

        if (mIsEnded) {
            // Replay from the start.
            mIsEnded = false;
            mExoPlayerController.setPositionMs(0);
            mExoPlayerController.setPlayWhenReady(true);
            if (mPresenter != null) {
                mPresenter.onPlayClicked();
            }
        } else {
            boolean play = !mExoPlayerController.getPlayWhenReady();
            mExoPlayerController.setPlayWhenReady(play);
            if (mPresenter != null) {
                if (play) {
                    mPresenter.onPlayClicked();
                } else {
                    mPresenter.onPauseClicked();
                }
            }
        }

        updatePlayPauseIcon();
        armAutoHide();
    }

    /**
     * NEWTUBE(issue #69): from a video change to that video's first frame, the player's size and
     * format may still be the previous video's, so its shape is unknown (and fullscreen rotates).
     */
    private boolean mVideoShapePending = true;

    /**
     * NEWTUBE(issue #69): whether the playing video is taller than wide; false while its shape is
     * not known yet. The video track's format, else the last decoded size.
     */
    private boolean isVerticalVideo() {
        if (mVideoShapePending) {
            return false;
        }
        float aspect = mVideoAspect;
        androidx.media3.common.Format format = mPlayer != null ? mPlayer.getVideoFormat() : null;
        if (format != null && format.width > 0 && format.height > 0) {
            float ratio = format.pixelWidthHeightRatio > 0f ? format.pixelWidthHeightRatio : 1f;
            aspect = format.width * ratio / format.height;
            if (format.rotationDegrees % 180 != 0) {
                aspect = 1f / aspect;
            }
        }
        return aspect > 0f && aspect < 1f;
    }

    /**
     * NEWTUBE(issue #69): the new video's first frame is up, so its shape is known. A landscape
     * video that an autoplay or a pick from Up next brought into a vertical one's upright
     * fullscreen goes to landscape fullscreen, as its own button press would have: the request
     * rotates the window, and that rotation drops the upright layout (layoutOrientation).
     */
    private void onVideoShapeKnown() {
        if (!mVideoShapePending) {
            return;
        }
        mVideoShapePending = false;
        try { Video jv = getVideo(); com.newtube.mobile.ui.common.PlayerTrace.mark("FIRST FRAME src=" + (jv == null ? "?" : jv.localUri != null && !jv.localUri.isEmpty() ? (jv.headers != null && !jv.headers.isEmpty() ? "browser/link" : "media/file") : "youtube") + " vertical=" + isVerticalVideo() + " orient=" + (isLandscape() ? "LAND" : "PORT")); } catch (RuntimeException ignored) { } // JOYBOY(v952)
        if (autoLandscapeIfLocal()) { // JOYBOY(v933): local file / direct link -> landscape fullscreen on open
            return;
        }
        boolean upright = mLayoutOverride == Configuration.ORIENTATION_LANDSCAPE
                && getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        if (!upright || isVerticalVideo() || mIsInPip || mPipEnterPending || isFinishing()
                || areOrientationRequestsIgnored()) {
            return;
        }
        float aspect = mVideoAspect;
        if (mPlayer != null && mPlayer.getVideoSize().width > 0 && mPlayer.getVideoSize().height > 0) {
            aspect = (float) mPlayer.getVideoSize().width / mPlayer.getVideoSize().height;
        }
        if (!(aspect >= 1f)) {
            return; // still unknown
        }
        if (BuildConfig.DEBUG) {
            NetPath.log("fullscreen upright-to-landscape aspect=" + aspect);
        }
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        armOrientationHandBack(Configuration.ORIENTATION_LANDSCAPE);
    }

    private void toggleFullscreen() {
        com.newtube.mobile.ui.common.PlayerTrace.mark("fullscreen toggle (button)  landscape=" + isLandscape() + " portraitFull=" + mPortraitFull); // JOYBOY(v952)
        int windowOrientation = getResources().getConfiguration().orientation;
        boolean toLandscape =
                layoutOrientation() != Configuration.ORIENTATION_LANDSCAPE;
        int target = toLandscape
                ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        if (mContainer != null) {
            mContainer.removeCallbacks(mRotationFallback);
        }
        if (target == windowOrientation) {
            // The window already has that shape: only a layout override kept the other layout
            // (fullscreen/inline without a rotation), so dropping it is the whole toggle - and
            // the request that toggle may have left (a rotation slower than the fallback, a
            // request split screen held back) goes too, or it would rotate the player back.
            releaseFullscreenRequest();
            setLayoutOverride(Configuration.ORIENTATION_UNDEFINED);
            armAutoHide();
            return;
        }
        if (windowOrientation == Configuration.ORIENTATION_PORTRAIT && !isShortsMode()
                && isVerticalVideo()) {
            // MEETUBE(issue NewTube#69): a vertical video's fullscreen is upright, filling the
            // screen (the pull-down's vertical fullscreen); rotating it only pillarboxed it.
            setPortraitFull(!mPortraitFull);
            armAutoHide();
            return;
        }
        requestOrientationFullscreen(toLandscape, target, windowOrientation);
        armAutoHide();
    }

    /**
     * JOYBOY(v933): the "ask for the orientation" half of {@link #toggleFullscreen()}, pulled out
     * unchanged so the fullscreen button and the local-file auto-landscape
     * ({@link #autoLandscapeIfLocal()}) take the very same path (tablet / multi-window / TV layout
     * override, else a forced orientation + hand-back + rotation fallback).
     */
    private void requestOrientationFullscreen(boolean toLandscape, int target, int windowOrientation) {
        if (areOrientationRequestsIgnored()) {
            // NEWTUBE(tablet): Android 16 ignores orientation requests on large screens (sw >=
            // 600dp, targetSdk 36+), and every Android ignores them in multi-window. Asking for
            // PORTRAIT there was a silent no-op, so a landscape tablet could never leave
            // fullscreen - neither the button nor Back (#44, #60). Fullscreen becomes a layout
            // of the current window instead.
            releaseFullscreenRequest();
            setLayoutOverride(target);
            return;
        }
        setRequestedOrientation(toLandscape
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        armOrientationHandBack(target);
        // Any other policy that drops the request (an OEM's large-screen setting, a freeform
        // window) leaves no rotation behind it: flip the layout in place instead.
        mRotationFallbackTarget = target;
        mRotationFallbackFrom = windowOrientation;
        if (mContainer != null) {
            mContainer.postDelayed(mRotationFallback, FULLSCREEN_ROTATION_WAIT_MS);
        }
    }

    /**
     * JOYBOY(v933, merge plan phase 1): a local file / direct link (Video.localUri, or anything the
     * Joyboy add-on asks for) opens landscape fullscreen by itself at its first frame, once the
     * shape is known. YouTube keeps its portrait page + fullscreen button; a vertical video keeps
     * MeeTube's upright fullscreen (issue #69), never landscape. Returns true if it asked for one.
     */
    private boolean autoLandscapeIfLocal() {
        try {
            Video v = getVideo();
            boolean local = v != null && v.localUri != null && !v.localUri.isEmpty();
            boolean tv = com.newtube.mobile.ui.common.FocusRing.isTv(this);
            if (!(local || tv || JoyboyPlayerHost.wantsLandscape(this))) {
                return false;
            }
            // JOYBOY(v949): MobileLocalPlaybackActivity locks landscape at onCreate; a vertical video gives it back (upright fullscreen stays MeeTube's own)
            // JOYBOY(v950): a vertical video opens upright FULLSCREEN from the first frame — locked to portrait (the 🔓 button frees it),
            // never back to USER (that left it on the portrait watch page).
            if (this instanceof MobileLocalPlaybackActivity && isVerticalVideo()) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                if (getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE) {
                    setPortraitFull(true);
                }
                return true; // when the window still rotates, applyWatchLayoutForOrientation(portrait) enters the upright fullscreen
            }
            if (isFinishing() || mIsInPip || mPipEnterPending || isShortsMode() || mPortraitFull
                    || isVerticalVideo() || layoutOrientation() == Configuration.ORIENTATION_LANDSCAPE) {
                return false;
            }
            float aspect = mVideoAspect;
            if (mPlayer != null && mPlayer.getVideoSize().width > 0 && mPlayer.getVideoSize().height > 0) {
                aspect = (float) mPlayer.getVideoSize().width / mPlayer.getVideoSize().height;
            }
            if (!(aspect >= 1f) && !tv) {
                return false; // shape still unknown: leave it as it is
            }
            if (BuildConfig.DEBUG) {
                NetPath.log("fullscreen auto-landscape local=" + local + " tv=" + tv + " aspect=" + aspect);
            }
            requestOrientationFullscreen(true, Configuration.ORIENTATION_LANDSCAPE,
                    getResources().getConfiguration().orientation);
            return true;
        } catch (RuntimeException e) {
            return false; // never break the player over a convenience
        }
    }

    /**
     * Drop a rotation the fullscreen button asked for and its pending hand-back, when the toggle
     * is reversed in place before (or instead of) that rotation happening.
     */
    private void releaseFullscreenRequest() {
        mOrientationHandBack.disarm();
        updateOrientationHandBackListener();
        if (mIsInPip || mPipEnterPending) {
            return; // PiP owns the request meanwhile (releaseOrientationLockForPip)
        }
        int requested = getRequestedOrientation();
        if (requested == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                || requested == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            setRequestedOrientation(FREE_ORIENTATION);
        }
    }

    private int mRotationFallbackTarget;
    private int mRotationFallbackFrom;
    private final Runnable mRotationFallback = () -> {
        if (isFinishing() || mIsInPip || mPipEnterPending
                || getResources().getConfiguration().orientation != mRotationFallbackFrom
                || layoutOrientation() == mRotationFallbackTarget) {
            return;
        }
        if (BuildConfig.DEBUG) {
            NetPath.log("fullscreen rotation-ignored target=" + mRotationFallbackTarget);
        }
        setLayoutOverride(mRotationFallbackTarget);
    };

    /**
     * NEWTUBE(tablet): the watch layout ("portrait" = inline, "landscape" = fullscreen) normally
     * IS the window's orientation. Where the window cannot rotate on request, the fullscreen
     * toggle overrides it: {@link #mLayoutOverride} is the layout shown while the window keeps
     * the orientation it had when the override was set; a real rotation drops the override.
     */
    private int mLayoutOverride = Configuration.ORIENTATION_UNDEFINED;
    private int mLayoutOverrideBase = Configuration.ORIENTATION_UNDEFINED;
    /**
     * From a PiP entry until the player resumes after it: the configurations seen meanwhile
     * describe the PiP window (and, right after the exit callback, may still), not a rotation.
     */
    private boolean mPipLayoutHold;

    private int layoutOrientation() {
        return layoutOrientation(getResources().getConfiguration().orientation);
    }

    private int layoutOrientation(int windowOrientation) {
        if (mLayoutOverride != Configuration.ORIENTATION_UNDEFINED) {
            // A PiP stint (entering, pinned, and until the expanded player resumes) reports the
            // PiP window's shape, not a rotation: keep the override through it, so a portrait
            // tablet's in-place fullscreen comes back as fullscreen.
            if (windowOrientation == mLayoutOverrideBase
                    || mIsInPip || mPipEnterPending || mPipLayoutHold) {
                return mLayoutOverride;
            }
            if (BuildConfig.DEBUG) {
                NetPath.log("fullscreen override-dropped window=" + windowOrientation
                        + " base=" + mLayoutOverrideBase + " override=" + mLayoutOverride);
            }
            mLayoutOverride = Configuration.ORIENTATION_UNDEFINED; // the window rotated
            mLayoutOverrideBase = Configuration.ORIENTATION_UNDEFINED;
        }
        return windowOrientation;
    }

    private void setLayoutOverride(int orientation) {
        int window = getResources().getConfiguration().orientation;
        if (orientation == window) {
            orientation = Configuration.ORIENTATION_UNDEFINED;
        }
        mLayoutOverride = orientation;
        mLayoutOverrideBase = orientation == Configuration.ORIENTATION_UNDEFINED
                ? Configuration.ORIENTATION_UNDEFINED : window;
        if (mIsInPip || mPipEnterPending) {
            return;
        }
        int layout = layoutOrientation();
        refreshContentInsets();
        applyWatchLayoutForOrientation(layout);
        applySystemBarsForOrientation(layout);
        updateFullscreenIcon(layout);
        onFullscreenSwipeConfigured();
        if (mVideoArea != null) {
            mVideoArea.post(this::updatePipActions);
        }
    }

    /**
     * Whether fullscreen is a layout of the current window rather than a rotation. On a large
     * screen (sw >= 600dp) it always is: Android 16 ignores the request there (targetSdk 36+), and
     * older tablets that honour it either letterbox the app into a portrait strip of the landscape
     * display or rotate it - and {@link OrientationHandBack}'s angles assume a portrait-natural
     * phone, so on a landscape-natural tablet the hand-back read "held upright" at 0 degrees and
     * flipped the player straight back into fullscreen (API 35 Pixel Tablet emulator).
     */
    private boolean areOrientationRequestsIgnored() {
        if (Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode()) {
            return true;
        }
        if (com.newtube.mobile.ui.common.FocusRing.isTv(this)) {
            return true; // MEETUBE(tv): a TV never turns - a portrait request only letterboxed the app
        }
        return getApplicationContext().getResources().getConfiguration()
                .smallestScreenWidthDp >= 600;
    }

    /**
     * NEWTUBE(fullscreen): the button's forced orientation goes back to the sensor once the phone
     * is held that way ({@link OrientationHandBack}), YouTube-style: after the button entered
     * fullscreen, turning the phone upright leaves it again, and after the button left it, laying
     * the phone sideways enters it again. Until this existed the force stuck for the activity's
     * whole life (the removed Rotate lock's "Off" was the only thing that reset it). With the
     * system auto-rotate setting off the phone never rotates the player anyway, so the force
     * simply stays, as before.
     */
    private void armOrientationHandBack(int target) {
        if (isAutoRotateOn()) {
            mOrientationHandBack.arm(target);
        } else {
            mOrientationHandBack.disarm();
        }
        updateOrientationHandBackListener();
    }

    /** Listen to the phone's angle only while a hand-back is pending and the player is in front. */
    private void updateOrientationHandBackListener() {
        boolean listen = mOrientationHandBack.isArmed() && mIsResumed && !mIsInPip;
        if (listen) {
            if (mOrientationListener == null) {
                mOrientationListener = new android.view.OrientationEventListener(this) {
                    @Override
                    public void onOrientationChanged(int degrees) {
                        onPhoneOrientation(degrees);
                    }
                };
            }
            if (mOrientationListener.canDetectOrientation()) {
                mOrientationListener.enable();
            }
        } else {
            Utils.removeCallbacks(mOrientationSettleCheck);
            mOrientationHandBack.pause(); // a pending target survives PiP/background, the hold not
            if (mOrientationListener != null) {
                mOrientationListener.disable();
            }
        }
    }

    private void onPhoneOrientation(int degrees) {
        mLastPhoneDegrees = degrees;
        long now = android.os.SystemClock.uptimeMillis();
        if (!mOrientationHandBack.onOrientation(degrees, now)) {
            long remaining = mOrientationHandBack.remainingMs(now);
            if (remaining >= 0) {
                Utils.postDelayed(mOrientationSettleCheck, remaining + 1); // re-posts, one pending
            } else {
                Utils.removeCallbacks(mOrientationSettleCheck);
            }
            return;
        }
        Utils.removeCallbacks(mOrientationSettleCheck);
        mOrientationHandBack.disarm();
        updateOrientationHandBackListener();

        // Only our own force goes back (a PiP stint may have replaced it meanwhile), and only
        // while auto-rotate is still on - otherwise freeing it would snap the player to the
        // phone's locked rotation.
        int requested = getRequestedOrientation();
        boolean ours = requested == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                || requested == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        if (ours && !mIsInPip && isAutoRotateOn()) {
            setRequestedOrientation(FREE_ORIENTATION);
        }
        if (BuildConfig.DEBUG) {
            android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                    "orientation hand-back degrees=" + degrees + " requested=" + requested
                            + " freed=" + (getRequestedOrientation() == FREE_ORIENTATION));
        }
    }

    private boolean isAutoRotateOn() {
        return android.provider.Settings.System.getInt(getContentResolver(),
                android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
    }

    /**
     * Open one of the player option sheets by dispatching its {@code R.id.action_*} through the
     * presenter, which fans it out to the reused SmartTube controllers; the matching controller
     * builds an {@code AppDialog} option list and shows it via {@link MobileAppDialogActivity}.
     *
     * <ul>
     *   <li>Quality → {@code R.id.lb_control_high_quality} (onButtonClicked): HQDialogController's
     *       playback-settings sheet (video formats/resolutions, audio formats/language, presets, ...).</li>
     *   <li>Speed → {@code R.id.action_video_speed} (onButtonLongClicked): the speed list
     *       (0.25x..2x+). Same reasoning - the plain click can just toggle the remembered speed.</li>
     * </ul>
     */
    private void openPlayerOption(int actionId, boolean asLongClick) {
        if (mPresenter == null) {
            return;
        }

        cancelAutoHide();

        int state = getButtonState(actionId);
        if (state == BUTTON_DISABLED) {
            state = BUTTON_OFF;
        }

        if (asLongClick) {
            mPresenter.onButtonLongClicked(actionId, state);
        } else {
            mPresenter.onButtonClicked(actionId, state);
        }
    }

    // ---------------------------------------------------------------------------------
    // Gear menu: every player option beyond the overlay's YouTube-style trio (cast/CC/gear)
    // + transport + fullscreen.
    //
    // The everyday actions (Quality / Speed / PiP) head the sheet; the long tail follows. Each
    // row dispatches an R.id.action_* through PlaybackPresenter (same vocabulary the TV
    // VideoPlayerGlue used) so the reused PlayerUIController does the real work: dialog-opening
    // actions (repeat/zoom/playlist/queue) show their AppDialog via the touch
    // MobileAppDialogActivity; simple toggles (stats/screen-off) flip and are reflected here.
    // Actions with no mobile meaning (AFR) are omitted. There is no rotate lock: rotation follows
    // the phone; the fullscreen button forces an orientation until the phone is held that way
    // (toggleFullscreen / OrientationHandBack).
    // ---------------------------------------------------------------------------------

    private void openPlayerMenu() {
        if (mPresenter == null) {
            return;
        }

        cancelAutoHide();

        BottomSheetDialog sheet = Haptics.watch(new BottomSheetDialog(this));
        LinearLayout content = createSheetContent();

        // Mirrors the official app's gear sheet: no title, a handful of everyday rows, icon +
        // current value on every everyday action; the long tail nests behind "More". Quality opens
        // the simple YouTube-style picker (Auto + resolutions) - the exhaustive TV HQ dialog stays
        // reachable for power users deeper in that sheet.
        addMenuRow(content, sheet, R.drawable.ic_player_quality, R.string.mobile_player_quality,
                currentQualityLabel(), true, this::showQualitySheet);
        // Audio track, like YouTube: only on videos that ship more than one language (dubs).
        List<AudioTrackChoices.Choice> audioChoices = audioTrackChoices();
        if (audioChoices.size() > 1) {
            AudioTrackChoices.Choice playing = AudioTrackChoices.selected(audioChoices);
            addMenuRow(content, sheet, R.drawable.ic_player_audio_track,
                    R.string.mobile_player_audio_track, playing != null ? playing.label : null,
                    true, this::showAudioTrackSheet);
        }
        // Captions: the native captions sheet (same target as long-pressing the overlay CC button).
        addMenuRow(content, sheet, R.drawable.ic_player_cc, R.string.mobile_player_subtitles,
                currentCaptionsLabel(), true, this::showCaptionsSheet);
        if (transcriptTrack() != null) {
            addMenuRow(content, sheet, R.drawable.ic_player_transcript, R.string.mobile_transcript,
                    null, true, this::showTranscriptSheet);
        }
        // Speed: the native preset sheet; the exhaustive TV dialog nests behind its "More speeds".
        addMenuRow(content, sheet, R.drawable.ic_player_speed, R.string.mobile_player_speed,
                currentSpeedLabel(), true, this::showSpeedSheet);
        addMenuRow(content, sheet, R.drawable.ic_player_autoplay, R.string.mobile_player_autoplay,
                stateLabel(isAutoplayOn()), false, this::toggleAutoplay);
        addMenuRow(content, sheet, R.drawable.ic_player_sleep_timer, R.string.mobile_player_sleep_timer,
                sleepTimerLabel(), true, this::showSleepTimerSheet);
        if (Helpers.isPictureInPictureSupported(this)) {
            addMenuRow(content, sheet, R.drawable.ic_player_pip, R.string.mobile_player_pip,
                    null, false, this::enterPipFromMenu);
        }
        // MEETUBE(ambient): the video's colours around it - on unless turned off here or in Settings.
        addMenuRow(content, sheet, R.drawable.ic_player_brightness_high, R.string.mobile_settings_ambient,
                stateLabel(AmbientGlow.isOn(this)), false, this::toggleAmbient);
        // MEETUBE(issue #108): this Short, out of the Shorts player into the regular one.
        if (mShorts != null && mShorts.isActive()) {
            addMenuRow(content, sheet, R.drawable.ic_player_fullscreen, R.string.mobile_menu_watch_regular,
                    null, false, this::openShortAsVideo);
            // MEETUBE(issue #134): Settings > Playback > When a short ends, here too.
            addMenuRow(content, sheet, R.drawable.ic_player_autoplay, R.string.mobile_shorts_autoscroll,
                    stateLabel(!PlayerTweaksData.instance(this).isLoopShortsEnabled()), false, this::toggleShortsAutoscroll);
        }
        addMenuRow(content, sheet, R.drawable.ic_mobile_settings, R.string.mobile_menu_more,
                null, true, this::openPlayerMoreMenu);

        sheet.setContentView(sheetRoot(content));
        showPlayerSheet(sheet);
    }

    /**
     * MEETUBE(ambient): start or drop the glow (and the vertical fullscreen's fill) as the Ambient
     * mode switch says - Settings, or the gear sheet's own row.
     */
    private void applyAmbientSetting() {
        boolean ambient = AmbientGlow.isOn(this);
        if (ambient && mAmbient == null && mVideoTexture != null && mWatchContent != null) {
            mAmbient = new AmbientGlow(mWatchContent, mVideoTexture,
                    androidx.core.content.ContextCompat.getColor(this, R.color.mobile_color_background));
            mAmbient.setBar(findViewById(android.R.id.content)); // the status bar glows too
        } else if (!ambient && mAmbient != null) {
            mAmbient.stop();
            mAmbient.setFill(null);
            mAmbient.setBar(null);
            mAmbient = null;
            mWatchContent.setBackground(null);
        }
        if (mAmbient != null) {
            mAmbient.start();
            mAmbient.setFill(isTallMode() ? mVideoArea : null);
        }
    }

    /** MEETUBE(autoplay): on unless "When a video ends" is Pause or Close the player. */
    private boolean isAutoplayOn() {
        int mode = PlayerData.instance(this).getPlaybackMode();
        return mode != PlayerConstants.PLAYBACK_MODE_PAUSE && mode != PlayerConstants.PLAYBACK_MODE_CLOSE;
    }

    private void toggleAutoplay() {
        PlayerData.instance(this).setPlaybackMode(isAutoplayOn()
                ? PlayerConstants.PLAYBACK_MODE_PAUSE : PlayerConstants.PLAYBACK_MODE_ALL);
    }

    /**
     * MEETUBE(issue #108): reopen the Short as a copy outside the Shorts tab (copy() drops
     * markShorts() too), so the player loads it again in the regular layout with Up next.
     */
    private void openShortAsVideo() {
        Video item = getVideo();
        if (item == null) {
            return;
        }
        Video regular = item.copy();
        regular.setGroup(null);
        mPresenter.openVideo(regular);
    }

    private void toggleShortsAutoscroll() {
        PlayerTweaksData tweaks = PlayerTweaksData.instance(this);
        tweaks.setLoopShortsEnabled(!tweaks.isLoopShortsEnabled());
    }

    private void toggleAmbient() {
        AmbientGlow.setOn(this, !AmbientGlow.isOn(this));
        applyAmbientSetting();
    }

    /** The gear sheet's "More" level: the long tail of SmartTube player actions. */
    private void openPlayerMoreMenu() {
        if (mPresenter == null) {
            return;
        }

        cancelAutoHide();

        BottomSheetDialog sheet = Haptics.watch(new BottomSheetDialog(this));
        LinearLayout content = createSheetContent();

        // Effective, not stored: a Shuffle started from the playlist page is scoped to that queue
        // (QueuePlaybackMode) and never reaches PlayerData, so reading the stored mode alone would
        // show "Off" while the queue is visibly shuffling.
        boolean shuffleOn = isShuffling();
        boolean statsOn = getButtonState(R.id.action_video_stats) == BUTTON_ON;

        // Repeat mode -> playback-mode dialog (long-click path always opens the picker; the plain
        // click just cycles). The dialog includes Shuffle among its radio options too.
        addMenuRow(content, sheet, R.drawable.ic_player_repeat, R.string.mobile_menu_repeat,
                null, true, () -> openPlayerOption(R.id.action_repeat, true));
        // Dedicated Shuffle toggle (SHUFFLE <-> ALL) for quick access.
        addMenuRow(content, sheet, R.drawable.ic_player_shuffle, R.string.mobile_menu_shuffle,
                stateLabel(shuffleOn), false, this::toggleShuffleMode);
        // Video zoom: how the picture fills the player (PlayerUIController.onVideoZoom).
        addMenuRow(content, sheet, R.drawable.ic_player_zoom, R.string.mobile_menu_zoom,
                null, true, () -> openPlayerOption(R.id.action_video_zoom, false));
        // (No screen-off/dimming row: the TV screensaver doesn't exist on mobile - power button +
        // background playback cover that use case.)
        // Add to playlist.
        addMenuRow(content, sheet, R.drawable.ic_player_playlist_add, R.string.mobile_menu_playlist_add,
                null, true, () -> openPlayerOption(R.id.action_playlist_add, false));
        // Download (same target as the watch-page pill), with its state as the trailing value.
        if (VideoDownloads.canDownload(getVideo()) || currentDownload() != null) {
            addMenuRow(content, sheet, R.drawable.ic_watch_download, R.string.dialog_download,
                    downloadStateLabel(), true, this::onDownloadTapped);
        }
        // Playback queue.
        addMenuRow(content, sheet, R.drawable.ic_player_queue, R.string.mobile_menu_queue,
                null, true, () -> openPlayerOption(R.id.action_playback_queue, false));
        // Stats for nerds (debug overlay) toggle.
        addMenuRow(content, sheet, R.drawable.ic_player_stats, R.string.mobile_menu_stats,
                stateLabel(statsOn), false, () -> openPlayerOption(R.id.action_video_stats, false));

        sheet.setContentView(sheetRoot(content));
        showPlayerSheet(sheet);
    }

    /**
     * Shared scaffold for the gear sheets: rounded background + drag handle, no title. Returns the
     * column the rows go in; the sheet's content view is {@link #sheetRoot} of it.
     *
     * <p>NEWTUBE(sheet-landscape): the rows scroll under a fixed handle. The column used to be the
     * sheet itself, so the More level - taller than a phone held sideways - ran over the top of the
     * screen with its last rows below the bottom, and a drag meant to reach them dragged the whole
     * sheet away instead. In a scroll view the sheet's own drag only takes over once the list is at
     * its top (nested scrolling), like any Material sheet.</p>
     */
    private LinearLayout createSheetContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_mobile_sheet);
        root.setPadding(0, dp(8), 0, 0);

        View handle = new View(this);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = dp(8);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_mobile_sheet_handle);
        root.addView(handle);

        NestedScrollView scroll = new NestedScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(scroll);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(16));
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return content;
    }

    /** The sheet view around a column from {@link #createSheetContent}. */
    private static View sheetRoot(LinearLayout content) {
        return content.getRootView(); // not attached yet: the topmost parent, the scaffold's root
    }

    /**
     * Trailing value for the Quality row, official-app style: "Auto (720p)" while adaptive
     * (the rung actually playing right now), the pinned rung's label otherwise.
     *
     * <p>The rung playing is the player's own video format. The controller's
     * {@code getVideoFormat()} answers with the format the app asked for, and in Auto that is the
     * ceiling preset: every video read "Auto (1080p60)", a 30 fps one whose list stops at 720p
     * too.</p>
     */
    /** The video quality asked for, in VideoStateController.restoreVideoFormat's order. */
    private FormatItem chosenVideoFormat() {
        PlayerData playerData = PlayerData.instance(this);
        Video video = getVideo();
        FormatItem pick = playerData.getVideoVideoFormat(video != null ? video.videoId : null);
        return playerData.getTempVideoFormat() != null ? playerData.getTempVideoFormat()
                : pick != null ? pick : playerData.getFormat(FormatItem.TYPE_VIDEO);
    }

    private String currentQualityLabel() {
        FormatItem chosen = chosenVideoFormat();

        if (!isAutoFormat(chosen) && chosen.getHeight() > 0) {
            return qualityLabel(chosen);
        }

        FormatItem playing = mExoPlayerController != null ? mExoPlayerController.getPlayingVideoFormat() : null;
        return playing != null && playing.getHeight() > 0
                ? getString(R.string.mobile_quality_auto_current, qualityLabel(playing))
                : getString(R.string.mobile_quality_auto_short);
    }

    /** Trailing value for the Speed row: "Normal", "1.5x", ... */
    private String currentSpeedLabel() {
        float speed = mExoPlayerController != null ? mExoPlayerController.getSpeed() : -1;
        return speedLabel(speed <= 0 ? 1f : speed);
    }

    /**
     * NEWTUBE(theme): the sheets shown over this screen, closed by a theme change (onThemeChanged)
     * and by a turn to the other orientation (onConfigurationChanged).
     */
    private final List<java.lang.ref.WeakReference<BottomSheetDialog>> mShownSheets = new ArrayList<>();

    /** The layout orientation the last sheet opened in. */
    private int mSheetsOrientation = Configuration.ORIENTATION_UNDEFINED;

    private void dismissPlayerSheets() {
        for (java.lang.ref.WeakReference<BottomSheetDialog> ref : mShownSheets) {
            BottomSheetDialog sheet = ref.get();
            if (sheet != null && sheet.isShowing()) {
                sheet.dismiss();
            }
        }
        mShownSheets.clear();
    }

    /**
     * A bare BottomSheetDialog over the player misbehaves two ways: it opens half-collapsed at the
     * default auto peek height (tall content ends up cut off below the screen edge), and in the
     * immersive landscape player its focusable window re-summons the system bars, shifting the
     * sheet's layout so it lands partly off-screen. Every in-player sheet must open through here:
     * expanded and never collapsible, shown focus-less first with the player's hidden bars asked
     * for on its own window too (focus is restored right after, per the standard immersive-dialog
     * recipe) so the bars stay hidden.
     */
    private void showPlayerSheet(BottomSheetDialog dialog) {
        showPlayerSheet(dialog, false);
    }

    /**
     * {@code side}: held sideways, the sheet is a card on the right and the video plays on beside it
     * (the live chat's column); upright it is the usual sheet.
     */
    private void showPlayerSheet(BottomSheetDialog dialog, boolean side) {
        pruneShownSheets();
        mShownSheets.add(new java.lang.ref.WeakReference<>(dialog));
        mSheetsOrientation = layoutOrientation();
        Window window = dialog.getWindow();
        // MEETUBE(fullscreen): landscape and vertical fullscreen both hide the bars.
        androidx.core.view.WindowInsetsCompat pageInsets =
                androidx.core.view.ViewCompat.getRootWindowInsets(getWindow().getDecorView());
        boolean landscape = mSheetsOrientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean immersive = landscape || (pageInsets != null
                && !pageInsets.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()));
        // MEETUBE(tv): not on a TV - no bars to keep away, and the window sometimes never got the
        // focus back, so the remote's keys went on to the player under the sheet.
        boolean focusLater = immersive && !com.newtube.mobile.ui.common.FocusRing.isTv(this);
        if (focusLater && window != null) {
            window.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        if (immersive && window != null) {
            // NEWTUBE(sheet-landscape): the sheet's own window decides the bars from Android 12 on;
            // copying the player's legacy flags kept nothing hidden and the sheet jumped up.
            MobileSheets.keepSystemBarsHidden(window);
        }
        side &= landscape;
        if (side) {
            if (window != null) {
                window.getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(@NonNull View v) {
                    }

                    @Override
                    public void onViewDetachedFromWindow(@NonNull View v) {
                        v.removeOnAttachStateChangeListener(this);
                        setChatColumn(0); // the video takes the screen back
                    }
                });
            }
        } else if (landscape) {
            // NEWTUBE(sheet-landscape): most of these sheets are taller than a phone held sideways.
            // Uncapped, a sheet that did not fit filled the screen up to its top edge; capped, it
            // stops short of it, the video stays in sight above, and its list scrolls.
            int cap = landscapeSheetMaxHeight();
            if (cap > 0) {
                MobileSheets.capHeight(dialog, cap);
            }
        }

        // NEWTUBE(sheet-landscape): the controls stay as they were for as long as a sheet is up -
        // neither timer pulls them out from under it (showOverlay, onAutoHideTick) - and their
        // countdown starts again when the last sheet closes. The shared UI timer hid them ~2 s
        // into a sheet, so the whole picture behind it changed while the person was choosing.
        // Hooked on the window, not a dismiss listener: callers (chapters, cast) keep their own.
        cancelAutoHide();
        setControlsGlassLive(false);
        if (window != null) {
            View decor = window.getDecorView();
            decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(@NonNull View v) {
                }

                @Override
                public void onViewDetachedFromWindow(@NonNull View v) {
                    v.removeOnAttachStateChangeListener(this);
                    // Posted: a row that opens the next sheet dismisses this one first.
                    Utils.post(MobilePlaybackActivity.this::onPlayerSheetClosed);
                }
            });
        }

        // NEWTUBE(sheet-landscape): expanded before the first layout. Asked for in the show
        // listener, it came too late for that layout: a sheet taller than its peek opened as a
        // stub (one row, or just its title) and only snapped fully open on the next layout -
        // a second or two later, when the bars came back.
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        if (focusLater && window != null) {
            dialog.setOnShowListener(d -> window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE));
        }

        dialog.show();
        if (side) {
            // After show: the sheet's onCreate sets its window back to the whole screen.
            setChatColumn(LiveChatSheet.placeSideCard(dialog, getWindow().getDecorView()));
        }
    }

    private int landscapeSheetMaxHeight() {
        View decor = getWindow().getDecorView();
        int height = decor.getHeight();
        return height > 0 ? height - dp(MobileSheets.LANDSCAPE_TOP_GAP_DP) : 0;
    }

    private void pruneShownSheets() {
        for (int i = mShownSheets.size() - 1; i >= 0; i--) {
            BottomSheetDialog shown = mShownSheets.get(i).get();
            if (shown == null || !shown.isShowing()) {
                mShownSheets.remove(i);
            }
        }
    }

    /**
     * A sheet from {@link #showPlayerSheet} is open over the player, or a picker screen is
     * ({@code MobileAppDialogActivity}: Repeat, Zoom, Save to playlist...), or is on its way.
     */
    private boolean isPlayerSheetShown() {
        pruneShownSheets();
        return !mShownSheets.isEmpty() || MobileSheets.isPickerShown()
                || AppDialogPresenter.instance(this).isDialogShown(); // the screen still on its way
    }

    private void onPlayerSheetClosed() {
        if (!isPlayerSheetShown() && !isFinishing()) {
            armAutoHide();
            setControlsGlassLive(true);
        }
    }

    /**
     * MEETUBE(glass): off while a sheet or a picker covers the controls - dimmed under it, their
     * frost cost the video its smoothness and showed nothing (#28). Back on as the last one goes.
     */
    private void setControlsGlassLive(boolean live) {
        for (View control : mGlassControls) {
            if (control != null) {
                com.newtube.mobile.ui.common.GlassDrawable.setLive(control, live);
            }
        }
    }

    /**
     * One gear-sheet row, official-app anatomy: leading icon, label, then (optionally) the
     * current value in secondary color and a chevron when the row opens a sub-picker.
     */
    private void addMenuRow(LinearLayout container, BottomSheetDialog sheet, int iconRes,
                            int labelRes, String trailing, boolean chevron, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(resolveSelectableItemBackground());
        // 48dp, the pickers' row (item_mobile_quality_row) and the official app's.
        row.setPadding(dp(20), dp(13), dp(16), dp(13));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(getColorInt(R.color.mobile_color_on_surface));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        iconLp.setMarginEnd(dp(20));
        icon.setLayoutParams(iconLp);
        row.addView(icon);

        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextColor(getColorInt(R.color.mobile_color_on_surface));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        if (trailing != null) {
            TextView state = new TextView(this);
            state.setText(trailing);
            state.setTextColor(getColorInt(R.color.mobile_color_on_surface_secondary));
            state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            row.addView(state);
        }

        if (chevron) {
            ImageView arrow = new ImageView(this);
            arrow.setImageResource(R.drawable.ic_chevron_right);
            arrow.setColorFilter(getColorInt(R.color.mobile_color_on_surface_secondary));
            LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(dp(20), dp(20));
            arrowLp.setMarginStart(dp(4));
            arrow.setLayoutParams(arrowLp);
            row.addView(arrow);
        }

        row.setOnClickListener(v -> {
            if (mSheetHandingOff == sheet) {
                return; // already opening what a row asked for
            }
            if (!chevron) {
                sheet.dismiss();
                action.run();
                return;
            }
            action.run();
            handOffSheet(sheet);
        });

        container.addView(row);
    }

    /** NEWTUBE(sheet-landscape): the sheet waiting for what one of its rows opened (handOffSheet). */
    @Nullable private BottomSheetDialog mSheetHandingOff;

    /** Longest a sheet waits for the picker its row opened before it leaves anyway. */
    private static final long SHEET_HAND_OFF_TIMEOUT_MS = 1_000;

    /**
     * NEWTUBE(sheet-landscape): {@code sheet}'s row opened the next level - another sheet, or a
     * picker screen (Repeat, Zoom, Save to playlist...). Closing the sheet first left a gap: for
     * a moment, or a second on a slow phone, the bare bright video and its controls showed between
     * the two dimmed menus. Now the next level comes up first: over a sheet already shown, this
     * one fades out under it at once; for a picker screen, it waits until that screen's sheet
     * starts sliding in. Anything else - a confirmation dialog, a message, a picker that loads
     * first (Download) - and it closes at once, as it always did: waiting for a screen that is
     * not coming kept it up a second and then took it away under whatever had opened.
     */
    private void handOffSheet(BottomSheetDialog sheet) {
        if (!sheet.isShowing()) {
            return;
        }
        pruneShownSheets();
        for (java.lang.ref.WeakReference<BottomSheetDialog> ref : mShownSheets) {
            if (ref.get() != null && ref.get() != sheet) {
                sheet.dismiss(); // the next sheet is already on top
                return;
            }
        }
        if (!AppDialogPresenter.instance(this).isDialogShown()) { // true while its screen is pending
            sheet.dismiss();
            return;
        }
        mSheetHandingOff = sheet;
        Runnable leave = new Runnable() {
            @Override
            public void run() {
                Utils.removeCallbacks(this);
                if (mSheetHandingOff == sheet) {
                    mSheetHandingOff = null;
                    MobileSheets.setCoveringSheetListener(null);
                }
                if (sheet.isShowing()) {
                    sheet.dismiss();
                }
            }
        };
        MobileSheets.setCoveringSheetListener(leave);
        Utils.postDelayed(leave, SHEET_HAND_OFF_TIMEOUT_MS);
    }

    private String stateLabel(boolean on) {
        return getString(on ? R.string.mobile_menu_on : R.string.mobile_menu_off);
    }

    private int resolveSelectableItemBackground() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Toggle the SmartTube playback mode between Shuffle and All (default). Persisted in PlayerData. */
    /** Shuffling right now, whether that came from the stored mode or from this queue. */
    private boolean isShuffling() {
        return PlayerData.instance(this).getPlaybackMode() == PlayerConstants.PLAYBACK_MODE_SHUFFLE
                || QueuePlaybackMode.coversQueueOf(getVideo());
    }

    private void toggleShuffleMode() {
        // Off when anything is shuffling - including a queue-scoped shuffle, which the write below
        // clears (PlayerData.setPlaybackMode drops the override). Reading only the stored mode
        // would turn a "stop shuffling" tap into "shuffle everything from now on".
        int mode = isShuffling() ? PlayerConstants.PLAYBACK_MODE_ALL : PlayerConstants.PLAYBACK_MODE_SHUFFLE;
        PlayerData.instance(this).setPlaybackMode(mode);
        // Reflect on the (hidden) repeat button state so the menu shows the right On/Off next time.
        setButtonState(R.id.action_repeat, mode);
    }

    private void updateFullscreenIcon(int orientation) {
        if (mFullscreenButton == null) {
            return;
        }

        mFullscreenButton.setImageResource(orientation == Configuration.ORIENTATION_LANDSCAPE
                ? R.drawable.ic_player_fullscreen_exit
                : R.drawable.ic_player_fullscreen);
    }

    private static final int GLYPH_NONE = 0;
    private static final int GLYPH_PLAY = 1;
    private static final int GLYPH_PAUSE = 2;
    private static final int GLYPH_REPLAY = 3;
    /** What the center button shows; the progress tick re-asks every few hundred ms. */
    private int mPlayPauseGlyph = GLYPH_NONE;

    /**
     * The center button's glyph. NEWTUBE(motion): play and pause MORPH into each other, like
     * YouTube's, when the change happens in front of the viewer (the controls are up); otherwise,
     * and for replay, the glyph is simply set.
     */
    private void updatePlayPauseIcon() {
        if (mPlayPauseButton == null) {
            return;
        }

        int glyph;
        if (mIsEnded) {
            glyph = GLYPH_REPLAY;
        } else if (mExoPlayerController != null && mExoPlayerController.getPlayWhenReady()) {
            glyph = GLYPH_PAUSE;
        } else {
            glyph = GLYPH_PLAY;
        }
        if (glyph == mPlayPauseGlyph) {
            return;
        }

        boolean morph = mControlsVisible && mControlsRoot != null && mControlsRoot.getAlpha() > 0f
                && (mPlayPauseGlyph == GLYPH_PLAY && glyph == GLYPH_PAUSE
                        || mPlayPauseGlyph == GLYPH_PAUSE && glyph == GLYPH_PLAY);
        mPlayPauseGlyph = glyph;
        Drawable morphing = morph ? ContextCompat.getDrawable(this, glyph == GLYPH_PAUSE
                ? R.drawable.avd_player_play_to_pause : R.drawable.avd_player_pause_to_play) : null;
        if (morphing instanceof android.graphics.drawable.Animatable) {
            mPlayPauseButton.setImageDrawable(morphing);
            ((android.graphics.drawable.Animatable) morphing).start();
        } else {
            mPlayPauseButton.setImageResource(glyph == GLYPH_REPLAY ? R.drawable.ic_player_replay
                    : glyph == GLYPH_PAUSE ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
        }
        mPlayPauseButton.setContentDescription(getString(glyph == GLYPH_REPLAY ? R.string.mobile_player_replay
                : glyph == GLYPH_PAUSE ? R.string.mobile_player_pause : R.string.mobile_player_play));
    }

    /**
     * The controls' progress loop while they are up; while they are hidden, the seek bar's line
     * loop when the bar shows one (portrait), else nothing.
     */
    private void startProgressUpdates() {
        stopProgressUpdates();
        if (mIsStopped) {
            return;
        }
        if (mControlsVisible) {
            Utils.postDelayed(mProgressUpdateRunnable, 0);
        } else if (mTimeBar != null && (mTimeBar.isLineShownWhenHidden() || isShortsMode())) {
            Utils.postDelayed(mLineUpdateRunnable, 0);
        }
    }

    private void stopProgressUpdates() {
        Utils.removeCallbacks(mProgressUpdateRunnable);
        Utils.removeCallbacks(mLineUpdateRunnable);
    }

    /**
     * NEWTUBE(seek bar): how long until the bar's dot moves a pixel (the bar's own estimate, faster
     * at a raised speed), within [{@code minMs}, {@code maxMs}].
     */
    private long barUpdateDelayMs(long minMs, long maxMs) {
        long delay = mTimeBar != null ? mTimeBar.getPreferredUpdateDelay() : maxMs;
        float speed = mPlayer != null ? mPlayer.getPlaybackParameters().speed : 1f;
        if (speed > 0f && delay != Long.MAX_VALUE) {
            delay = (long) (delay / speed);
        }
        return Math.max(minMs, Math.min(maxMs, delay));
    }

    /**
     * Controls hidden, portrait: the seek bar's line along the video's bottom edge. Pixel-paced
     * while playing (a short video's line glides instead of stepping), once a second otherwise.
     */
    private void onLineTick() {
        boolean shorts = isShortsMode();
        if (mIsStopped || mControlsVisible || mPlayer == null || mExoPlayerController == null
                || (!mTimeBar.isLineShownWhenHidden() && !shorts)) {
            return;
        }
        long duration = getDurationMs();
        long position = Math.max(mExoPlayerController.getPositionMs(), 0);
        mTimeBar.setDuration(Math.max(duration, 0));
        mTimeBar.setPosition(position);
        if (shorts) {
            mShorts.setProgress(position, Math.max(duration, 0)); // MEETUBE(shorts): its own scrub line
        }
        Utils.postDelayed(mLineUpdateRunnable, shorts && isPlaying() ? 100
                : isPlaying() ? barUpdateDelayMs(100, 1_000) : 1_000);
    }

    private static void setTextIfChanged(@Nullable TextView view, CharSequence text) {
        if (view != null && !TextUtils.equals(view.getText(), text)) {
            view.setText(text);
        }
    }

    private void onProgressTick() {
        if (mIsStopped || !mControlsVisible || mPlayer == null || mExoPlayerController == null) {
            return;
        }

        if (!mScrubbing) {
            long position = mExoPlayerController.getPositionMs();
            long duration = getDurationMs();
            long buffered = mPlayer.getBufferedPosition();

            if (duration < 0) {
                duration = 0;
            }
            if (position < 0) {
                position = 0;
            }

            mTimeBar.setDuration(duration);
            mTimeBar.setPosition(position);
            mTimeBar.setBufferedPosition(buffered);
            if (mShorts != null) {
                mShorts.setProgress(position, duration);
            }
            setTextIfChanged(mPositionView, positionText(position, duration));
            setTextIfChanged(mDurationView, getString(R.string.mobile_player_duration, formatTime(duration)));
            updateLiveChip(position, duration);
            updateChapterButton(position);
        }

        updatePlayPauseIcon();

        // Pixel-paced like the line: the bar now spans the whole width, and a short video's dot
        // stepped visibly at a fixed 500 ms.
        Utils.postDelayed(mProgressUpdateRunnable,
                isPlaying() ? barUpdateDelayMs(100, PROGRESS_UPDATE_MS) : PROGRESS_UPDATE_MS);
    }

    /**
     * Live streams: a red LIVE chip when watching at the edge, dimmed while rewound into the DVR
     * window (the seekbar stays scrubbable); tapping it jumps back to the edge. Non-live keeps
     * the plain position/duration pair.
     */
    private void updateLiveChip(long positionMs, long durationMs) {
        if (mLiveChip == null) {
            return;
        }

        boolean isLive = getVideo() != null && getVideo().isLive;
        mLiveChip.setVisibility(isLive ? View.VISIBLE : View.GONE);
        if (isLive) {
            com.newtube.mobile.ui.common.LiveRingView.markLive(getVideo().channelId);
        }
        if (mWatchAvatar != null) { // MEETUBE(issue #73): the channel's glowing live ring
            com.newtube.mobile.ui.common.LiveRingView.bind(findViewById(R.id.mobile_watch_avatar_live), mWatchAvatar, isLive, 0);
        }
        updateSeekBarLine();

        if (isLive) {
            boolean atEdge = durationMs - positionMs <= LIVE_EDGE_THRESHOLD_MS;
            mLiveChip.setAlpha(atEdge ? 1f : 0.55f);
        }
    }

    private void jumpToLiveEdge() {
        long durationMs = getDurationMs();

        if (mExoPlayerController == null || durationMs <= 0) {
            return;
        }

        mExoPlayerController.setPositionMs(Math.max(0, durationMs - LIVE_EDGE_OFFSET_MS));

        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(true);
        }
    }

    /** The pill's first half: elapsed, or "-" and the time left (issue #67); a live stream stays elapsed. */
    private String positionText(long positionMs, long durationMs) {
        boolean live = getVideo() != null && getVideo().isLive;
        return mTimeRemaining && !live ? "-" + formatTime(durationMs - positionMs) : formatTime(positionMs);
    }

    private String formatTime(long timeMs) {
        if (timeMs < 0) {
            timeMs = 0;
        }
        return PlayerTimeBar.formatTime(mFormatBuilder, mFormatter, timeMs);
    }

    private final Player.Listener mUiPlayerListener = new Player.Listener() {
        @Override
        public void onVideoSizeChanged(VideoSize videoSize) {
            // NEWTUBE(issue #9): the fullscreen controls follow this video's shape (controlsStrip).
            // An unknown size keeps the last one, as the content frame does (DoubleTapPlayerViewImpl).
            if (videoSize.width > 0 && videoSize.height > 0) {
                mVideoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height;
                if (isShortsMode()) {
                    setResizeMode(PlayerData.instance(MobilePlaybackActivity.this).getResizeMode()); // fill or whole, by shape
                }
                applyControlsInsets();
                updatePipActions(); // a new shape reshapes the PiP window
                setInlineAspect(mVideoAspect);
            }
        }

        @Override
        public void onTracksChanged(androidx.media3.common.Tracks tracks) {
            // MEETUBE(issue #13): the new video's shape before its first frame, so the box is
            // ready when the picture arrives instead of jumping under it.
            setInlineAspect(tracksAspect(tracks));
            mHdrTrack = isHdrSelected(tracks);
            updateHdrOutput();
        }

        @Override
        public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
            // The old 2-arg onPlayerStateChanged callback split in two in media3; both re-enter
            // the same state handler so the icon/PiP/screen-on logic sees every combination.
            if (mPlayer != null) {
                handleUiStateChange(playWhenReady, mPlayer.getPlaybackState());
            }
            // MEETUBE(issue #101): a pause from outside the screen (earphones pulled, a headset or
            // Bluetooth button, another app's sound) brings the controls up, so the paused state is
            // visible. They stay while paused, as after a tap (onAutoHideTick).
            if (!playWhenReady && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
                    && !mControlsVisible && !mIsInPip
                    && (mCastSessionManager == null || !mCastSessionManager.isConnected())
                    && getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                showControlsInternal(true);
            }
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            handleUiStateChange(mPlayer != null && mPlayer.getPlayWhenReady(), playbackState);
        }

        @Override
        public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition,
                int reason) {
            // A seek (a double tap's jump) shows on the bar now, not at the loop's next tick, which
            // is up to a second away (more while the seek buffers).
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                startProgressUpdates();
            }
        }

        private void handleUiStateChange(boolean playWhenReady, int playbackState) {
            switch (playbackState) {
                case Player.STATE_BUFFERING:
                    showProgressBar(true);
                    break;
                case Player.STATE_READY:
                    showProgressBar(false);
                    mIsEnded = false;
                    // CASTING: the TV owns playback while a session is active - the local engine
                    // may load/buffer (so disconnect can resume instantly) but must never audibly
                    // play. Re-pause the moment any (re)load reaches READY.
                    if (mCastSessionManager != null && mCastSessionManager.isConnected()
                            && mPlayer != null && mPlayer.getPlayWhenReady()) {
                        mPlayer.setPlayWhenReady(false);
                    }
                    // A stream reached READY = the one-time session setup is complete (whether the
                    // background warmup or this very load did the work). Persists; kills the
                    // first-run hint for good.
                    SessionWarmup.markWarm(MobilePlaybackActivity.this);
                    // LOADING STILL: the NEW stream is ready. NEWTUBE(still-lift): when this open's
                    // first frame is already on the texture, lift now; otherwise the very next
                    // rendered frame is the new video, so let onSurfaceTextureUpdated lift it then.
                    if (mStillAwaitReady) {
                        mStillAwaitReady = false;
                        if (canLiftStillAtReady()) {
                            mStillAwaitFrame = false;
                            liftLoadingStill("ready");
                        } else {
                            mStillAwaitFrame = true;
                        }
                    }
                    // READY can precede the first rendered frame. Only an audio-only stream has
                    // no video frame to wait for; visible video releases its header when the new
                    // stream's texture frame lifts the loading still.
                    if (mBackgroundAudioMode || (mPlayer != null
                            && !mPlayer.getCurrentTracks().isTypeSelected(
                                    androidx.media3.common.C.TRACK_TYPE_VIDEO))) {
                        releaseWatchMetadata();
                    }
                    break;
                case Player.STATE_ENDED:
                    showProgressBar(false);
                    mIsEnded = true;
                    // Surface the replay affordance - but NOT while in PiP: full-size controls would
                    // appear inside the tiny PiP window and onAutoHideTick would keep re-arming them
                    // (mIsEnded stays true), so they'd never hide. In PiP the RemoteAction handles it.
                    if (!mIsInPip) {
                        showControlsInternal(true);
                    }
                    break;
                default:
                    break;
            }
            updatePlayPauseIcon();
            // Keep the PiP play/pause action icon in sync with the real playback state.
            updatePipActions();

            // SCREEN-ON: hold the screen awake while actively playing/buffering (like the YouTube
            // app); paused or ended releases it so the system's own display timeout applies. This
            // replaces the TV ScreensaverManager dim, which is disabled on mobile.
            applyKeepScreenOn(playWhenReady
                    && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING));
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            releaseWatchMetadata();
            // Never leave the loading still covering an error state.
            mStillAwaitReady = false;
            mStillAwaitFrame = false;
            hideVideoStill();
        }

        @Override
        public void onRenderedFirstFrame() {
            // NEWTUBE(still-lift): the controller's AnalyticsListener saw this event first, so
            // OpenFirstFrame already knows whether it was this open's; the texture may have it.
            if (mHdrSwapPending) {
                finishHdrSwap();
            }
            if (mHdrOutput) {
                // MEETUBE(issue #35): the frame went to the HDR SurfaceView, which the still-lift
                // can't watch: count it as the texture's, and lift a hand-off still here.
                mLastTextureFrameRealtimeMs = android.os.SystemClock.elapsedRealtime();
                if (mStillAwaitFrame && !mStillAwaitReady) {
                    mStillAwaitFrame = false;
                    liftLoadingStill("hdr");
                }
            }
            maybeLiftStillAtFrame();
            onVideoShapeKnown();
        }
    };

    // ---------------------------------------------------------------------------------
    // Persistent video surface + loading still
    //
    // The video decodes into ONE SurfaceTexture for the whole life of this activity. PlayerView
    // no longer owns a surface (surface_type="none"): a code-managed TextureView lives inside its
    // content frame (so aspect-ratio/zoom still apply) and hands its very first SurfaceTexture to
    // the player as a Surface the player never lets go of. Every hand-off - minimize to the
    // Browse mini card, expand back, background/return - only RE-PARENTS that texture between
    // TextureViews. The codec's output surface never changes, so the decoder is never released
    // and re-initialized: no more "audio keeps playing while the frames are stuck" (a codec
    // re-init must decode from the previous keyframe back to the position, which takes seconds
    // on some devices). This is the same technique the YouTube app uses.
    //
    // The "loading still" ImageView covers the texture in the two moments a stale frame would
    // show: a NEW video opening on this reused activity (thumbnail until the new stream's first
    // frame - see maybeShowLoadingStill) and the mini-player hand-offs (a captured frame bridges
    // the couple of frames until the re-parented texture paints).
    // ---------------------------------------------------------------------------------

    private TextureView mVideoTexture;
    private SurfaceTexture mSessionTexture;
    private Surface mSessionSurface;
    private ImageView mVideoStill;
    /** Loading-still state: waiting for the NEW stream's STATE_READY... */
    private boolean mStillAwaitReady;
    /** ...then for the next actually-rendered frame; only then the still lifts. */
    private boolean mStillAwaitFrame;
    /** New selections reveal instantly once ready; mini-player handoffs retain their short fade. */
    private boolean mNewVideoStill;
    private String mStillVideoId;
    /** NEWTUBE(still-lift): elapsedRealtime of the last frame the video texture consumed. */
    private long mLastTextureFrameRealtimeMs;
    /** NEWTUBE(still-lift): elapsedRealtime at which the still last started waiting for READY. */
    private long mStillArmedRealtimeMs;

    /** The still waits for the NEW stream's READY (see canLiftStillAtReady). */
    private void armStillForReady() {
        mStillAwaitReady = true;
        mStillArmedRealtimeMs = android.os.SystemClock.elapsedRealtime();
    }

    // ---------------------------------------------------------------------------------
    // MEETUBE(issue #35): HDR output. Android shows HDR only from a SurfaceView; the session
    // TextureView above tone-maps it to SDR (measured on a vivo V2404: SurfaceFlinger composites
    // the SurfaceView layer as BT2020_ITU_PQ, the window as sRGB/P3 even in COLOR_MODE_HDR). So
    // while an HDR track plays in the resting watch page or fullscreen, the codec draws into a
    // SurfaceView under the texture instead; every morph and the mini card keep the texture (a
    // SurfaceView can't be clipped to the morph's corners or read back for the hand-off stills).
    // A switch is MediaCodec#setOutputSurface (no re-init), and the old output stays on screen
    // until the first frame reaches the new one, so neither direction flashes black.
    // ---------------------------------------------------------------------------------

    @Nullable
    private android.view.SurfaceView mHdrSurface;
    /** The selected video track is HDR (only in the manifest with the HDR setting on). */
    private boolean mHdrTrack;
    /** The codec draws into {@link #mHdrSurface}. */
    private boolean mHdrOutput;
    /** Switched output, waiting for its first frame to retire the old one. */
    private boolean mHdrSwapPending;

    private void updateHdrOutput() {
        // Shorts stay on the texture (SDR): the SurfaceView fought the overlay's glass, peek and paging
        boolean want = mPlayer != null && mHdrTrack && mMorphFraction == 0f && !MiniPlayerBridge.isActive()
                && (mShorts == null || !mShorts.isActive());
        if (want == mHdrOutput) {
            return;
        }
        mHdrOutput = want;
        mHdrSwapPending = true;
        if (want) {
            ViewGroup contentFrame = mPlayerView.getContentFrame();
            if (mHdrSurface == null) {
                mHdrSurface = new android.view.SurfaceView(this);
            }
            if (contentFrame.indexOfChild(mHdrSurface) != 0) { // under the texture, which covers it until the swap
                contentFrame.removeView(mHdrSurface);
                contentFrame.addView(mHdrSurface, 0, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            mHdrSurface.setVisibility(View.VISIBLE);
            setVideoGlassSampling(false);
            mPlayer.setVideoSurfaceView(mHdrSurface);
        } else if (mPlayer != null && mSessionSurface != null) {
            mPlayer.setVideoSurface(mSessionSurface);
        } else {
            finishHdrSwap(); // no player to draw a frame: just put the texture back
        }
    }

    /** The first frame reached the new output: show it, retire the old one. */
    private void finishHdrSwap() {
        mHdrSwapPending = false;
        if (mVideoTexture != null) {
            mVideoTexture.setAlpha(mHdrOutput ? 0f : 1f);
        }
        if (!mHdrOutput && mHdrSurface != null) {
            mHdrSurface.setVisibility(View.GONE);
            setVideoGlassSampling(true);
        }
    }

    /** Over HDR's SurfaceView the controls' glass is the tint alone (GlassSource#setSampling). */
    private void setVideoGlassSampling(boolean on) {
        View glass = findViewById(R.id.mobile_player_glass);
        if (glass instanceof com.newtube.mobile.ui.common.GlassSource) {
            ((com.newtube.mobile.ui.common.GlassSource) glass).setSampling(on);
        }
    }

    /** The HDR SurfaceView's frame for a hand-off still (PixelCopy, waited for; a few ms), or null. */
    @Nullable
    private Bitmap copyHdrFrame(int width, int height) {
        if (mHdrSurface == null || !mHdrSurface.getHolder().getSurface().isValid()) {
            return null;
        }
        Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        android.os.HandlerThread thread = new android.os.HandlerThread("hdr-still");
        thread.start();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        int[] result = {-1};
        android.view.PixelCopy.request(mHdrSurface, frame, copyResult -> {
            result[0] = copyResult;
            done.countDown();
        }, new android.os.Handler(thread.getLooper()));
        try {
            done.await(200, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        thread.quitSafely();
        return result[0] == android.view.PixelCopy.SUCCESS ? frame : null;
    }

    private static boolean isHdrSelected(androidx.media3.common.Tracks tracks) {
        for (androidx.media3.common.Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != androidx.media3.common.C.TRACK_TYPE_VIDEO) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                androidx.media3.common.Format format = group.getTrackFormat(i);
                if (group.isTrackSelected(i) && com.liskovsoft.smartyoutubetv2.common.exoplayer.selector
                        .TrackSelectorUtil.isHdrFormat(format.id, format.codecs)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Build the code-managed video texture + still inside the PlayerView's content frame. */
    private void setupVideoSurface() {
        ViewGroup contentFrame = mPlayerView.getContentFrame();

        mVideoTexture = new TextureView(this);
        // NEWTUBE(texture-opaque): a frame with alpha 0 (seen from the emulator's VP9 decoder
        // after a paused seek) punched through an OPAQUE TextureView and the translucent player
        // window, showing Home inside the video box. Blended, such a frame shows the black box.
        mVideoTexture.setOpaque(false);
        mVideoTexture.setSurfaceTextureListener(mVideoTextureListener);
        contentFrame.addView(mVideoTexture, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mVideoStill = new ImageView(this);
        mVideoStill.setScaleType(ImageView.ScaleType.FIT_XY);
        mVideoStill.setBackgroundColor(Color.BLACK);
        mVideoStill.setVisibility(View.GONE);
        contentFrame.addView(mVideoStill, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** MEETUBE(shorts): the paged-away short's last frame, over the video while the next one opens. */
    private ImageView mShortFreeze;

    /**
     * MEETUBE(shorts): a swipe opens the next short as the finger lifts, while the old one is still
     * sliding away. The old one keeps its last frame (half size is plenty for 200 ms of motion) over
     * whatever the new open draws - its still, its first frames - until the slide ends.
     */
    private void setShortFreeze(boolean freeze) {
        if (!freeze) {
            if (mShortFreeze != null) {
                mShortFreeze.setVisibility(View.GONE);
                mShortFreeze.setImageDrawable(null);
            }
            return;
        }
        if (mVideoTexture == null || !mVideoTexture.isAvailable()) {
            return;
        }
        Bitmap frame = mVideoTexture.getBitmap(Math.max(1, mVideoTexture.getWidth() / 2),
                Math.max(1, mVideoTexture.getHeight() / 2));
        if (frame == null) {
            return;
        }
        if (mShortFreeze == null) {
            mShortFreeze = new ImageView(this);
            mShortFreeze.setScaleType(ImageView.ScaleType.FIT_XY);
            mPlayerView.getContentFrame().addView(mShortFreeze, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        mShortFreeze.setImageBitmap(frame);
        mShortFreeze.setVisibility(View.VISIBLE);
    }

    private final TextureView.SurfaceTextureListener mVideoTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            if (mSessionTexture == null) {
                // Very first availability: adopt this texture for the whole session.
                mSessionTexture = texture;
                mSessionSurface = new Surface(texture);
                if (mPlayer != null && !mHdrOutput) {
                    mPlayer.setVideoSurface(mSessionSurface);
                }
            } else if (texture != mSessionTexture) {
                // The view re-created its texture (re-attach after mini / return from background):
                // swap the session texture back in. The codec kept decoding into it all along, so
                // the live stream shows within a frame or two. The fresh texture is discarded.
                mVideoTexture.setSurfaceTexture(mSessionTexture);
                texture.release();
            }
            logPip("surface-available size=" + width + 'x' + height
                    + " adopted=" + (texture == mSessionTexture ? "y" : "n"));
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            // NEVER let a view release the session texture - that would detach the codec's
            // surface and force the re-init this whole design exists to avoid. Disposable
            // (never-adopted) textures may be released normally.
            boolean release = texture != mSessionTexture;
            logPip("surface-destroyed release=" + (release ? "y" : "n"));
            return release;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            mLastTextureFrameRealtimeMs = android.os.SystemClock.elapsedRealtime();
            // NEWTUBE(still-lift): this open's first frame reached the texture before READY.
            if (maybeLiftStillAtFrame()) {
                return;
            }
            // A real frame just rendered behind the still: lift it.
            if (mStillAwaitFrame && !mStillAwaitReady) {
                mStillAwaitFrame = false;
                liftLoadingStill("texture");
            }
        }
    };

    private void liftLoadingStill(String lift) {
        // MEETUBE(shorts peek): the peek's real frame stood over this short - it goes in this
        // frame, and so does the thumbnail under it (a fade showed the thumbnail between the
        // peek's frame and the video: a twitch on every swipe).
        if (mNewVideoStill && mShorts != null && mShorts.onPictureVisible(mStillVideoId)
                && hideLoadingStillImmediately(mVideoStill)) {
            NetPath.log(NetPath.context() + " picture-visible +" + NetPath.elapsedMs()
                    + " state=ready-texture-overlay-gone lift=peek");
        }
        hideVideoStill(mNewVideoStill, lift);
        // The persistent Surface can deliver the previous video's queued renderer event
        // after a new selection. Reuse the still's new-stream READY + texture-frame gate
        // instead of letting an unconditional onRenderedFirstFrame release its metadata.
        releaseWatchMetadata();
    }

    /**
     * NEWTUBE(still-lift): at the new stream's READY, is its first frame already on screen behind
     * the still? The still used to wait for the NEXT texture frame after READY - frame 2, released on
     * the audio clock - which hid a decoded picture for 128 / 148 ms (median / p90, READY to
     * picture-visible, n=67 Pixel 9 LTE opens, 2026-09-29); the first frame came before READY in
     * 67 of 67. All of this must hold, so a stale frame of the previous video on this reused
     * surface can never be revealed: the controller saw THIS open's first frame (its generation,
     * delivered after the open's fence through the playback thread - see OpenFirstFrame), rendered
     * after the still began waiting (so an old stream re-reaching READY before the new open resets
     * it never counts); the texture consumed a frame since that render time; and READY itself,
     * which media3 reports with a surface attached only after the new stream's first frame was
     * released. Otherwise, or with
     * debug.arc.still_lift=texture, the texture-frame path (onSurfaceTextureUpdated after READY)
     * stays in charge.
     */
    private boolean canLiftStillAtReady() {
        if (mExoPlayerController == null || mBackgroundAudioMode
                || !com.newtube.mobile.player.SwitchExperiments.stillLiftAtReady()) {
            return false;
        }
        return firstFrameOnTexture(mExoPlayerController.getOpenFirstFrameRealtimeMs(),
                mStillArmedRealtimeMs, mLastTextureFrameRealtimeMs);
    }

    /**
     * NEWTUBE(still-lift): lift a new selection's still at this open's first rendered frame, before
     * READY (SwitchExperiments.StillLift.FRAME, the default): the picture shows while the player
     * finishes buffering to READY (the analysis measured first frame -> picture visible at 67-74 ms
     * on the Pixel, 20-48 on the Mi 8, all of it this wait). The same per-open marker as the READY
     * path guards it: the controller saw THIS open's first frame (its fence and generation, see
     * OpenFirstFrame), rendered after the still began waiting, and the texture consumed a frame
     * since. Called from the first-frame event and from each texture update while the still waits
     * for READY; whichever comes second lifts it.
     */
    private boolean maybeLiftStillAtFrame() {
        // Cheap state first: the switch reads a property (once per process) only while a new
        // selection's still waits.
        if (!mStillAwaitReady || !mNewVideoStill || mExoPlayerController == null
                || !com.newtube.mobile.player.SwitchExperiments.stillLiftAtFrame()) {
            return false;
        }
        if (!canLiftStillAtFrame(true, true, mBackgroundAudioMode,
                mExoPlayerController.getOpenFirstFrameRealtimeMs(), mStillArmedRealtimeMs,
                mLastTextureFrameRealtimeMs)) {
            return false;
        }
        mStillAwaitReady = false;
        mStillAwaitFrame = false;
        liftLoadingStill("frame");
        return true;
    }

    /**
     * NEWTUBE(still-lift): the FRAME lift's rule. A new selection's still that waits for READY, not
     * in background audio, and this open's first frame on the texture (see firstFrameOnTexture).
     *
     * <p>Known limit, shared with the READY lift since v17 (a Codex sol review of v21): the texture
     * callback's time says the texture latched A buffer after this open's first frame was released,
     * not WHICH one, so a buffer the previous stream queued and the view had not drawn yet could
     * be shown for a frame. Reading the buffer's own timestamp does not work here:
     * onSurfaceTextureUpdated runs before the RenderThread latches the buffer, so
     * SurfaceTexture.getTimestamp() still reports the previous one (0 on a fresh texture: the
     * emulator's first open logged exactly that). A real check needs the renderer's per-frame
     * release times (a VideoFrameMetadataListener) counted against latches: a follow-up. The READY
     * lift waits 20-70 ms longer for the same predicate, which narrows that window without closing it.
     */
    static boolean canLiftStillAtFrame(boolean awaitingReady, boolean newVideoStill,
            boolean backgroundAudio, long firstFrameAtMs, long stillArmedAtMs, long lastTextureFrameAtMs) {
        return awaitingReady && newVideoStill && !backgroundAudio
                && firstFrameOnTexture(firstFrameAtMs, stillArmedAtMs, lastTextureFrameAtMs);
    }

    /**
     * @param firstFrameAtMs when this open's first frame was released (0 = not yet, or stale)
     * @param stillArmedAtMs when the still started waiting for the new stream's READY
     * @param lastTextureFrameAtMs when the texture last consumed a frame (all one clock)
     */
    static boolean firstFrameOnTexture(long firstFrameAtMs, long stillArmedAtMs,
            long lastTextureFrameAtMs) {
        return firstFrameAtMs > 0 && firstFrameAtMs >= stillArmedAtMs
                && lastTextureFrameAtMs >= firstFrameAtMs;
    }

    /** Session-long video texture, handed to the Browse mini card (see MiniPlayerBridge). */
    SurfaceTexture getSessionTexture() {
        return mSessionTexture;
    }

    /** Detach the TextureView so the session texture is free for another view's GL consumer. */
    private void detachVideoTexture() {
        if (mVideoTexture != null && mVideoTexture.getParent() instanceof ViewGroup) {
            // NOTE: do NOT "swap a throwaway texture in" here to force an eager GL release.
            // TextureView#setSurfaceTexture releases the texture it currently holds, so that
            // swap destroys the session texture and the adopting mini card crashes with
            // "Cannot setSurfaceTexture to a released SurfaceTexture". The known cosmetic
            // cost of plain removeView is that the outgoing HWUI layer can keep the texture
            // GL-bound for a beat (observed 1.5-3s on Pixel), during which the adopting card
            // renders mis-transformed and then snaps - an open issue needing a different fix.
            ((ViewGroup) mVideoTexture.getParent()).removeView(mVideoTexture);
        }
    }

    /** Re-parent the (still decoding) session texture back into this player's content frame. */
    private void reattachVideoTexture() {
        if (mVideoTexture != null && mVideoTexture.getParent() == null) {
            ViewGroup contentFrame = mPlayerView.getContentFrame();
            contentFrame.addView(mVideoTexture, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    /**
     * Card-thumbnail geometry for the loading still: byte-for-byte the request
     * {@code VideoCardAdapter} already issued for the tapped card, so the still normally costs ZERO
     * network. It must stay in sync with that adapter's {@code thumbWidth()} - a different size,
     * transformation or decode format is a different Glide cache key and would re-fetch.
     */
    private int mStillW;
    private int mStillH;

    private void ensureStillSize() {
        if (mStillW == 0) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            mStillW = Math.min(dm.widthPixels, dm.heightPixels);
            mStillH = mStillW * 9 / 16;
        }
    }

    /**
     * NEWTUBE(motion): the picture of the Up-next row that was just tapped, drawn at the tap (the
     * presenter clears the rows right after). The loading still starts from it instead of black:
     * the box went black for ~90 ms on every related tap while the cached thumbnail decoded.
     */
    @Nullable
    private Bitmap mTappedStill;
    @Nullable
    private String mTappedStillVideoId;

    private void seedTappedStill(@Nullable Video video, @Nullable ImageView thumbnail) {
        mTappedStill = null;
        mTappedStillVideoId = null;
        if (video == null || video.videoId == null || thumbnail == null || thumbnail.getDrawable() == null
                || thumbnail.getWidth() <= 0 || thumbnail.getHeight() <= 0) {
            return;
        }
        try {
            Bitmap frame = Bitmap.createBitmap(thumbnail.getWidth(), thumbnail.getHeight(), Bitmap.Config.RGB_565);
            thumbnail.draw(new android.graphics.Canvas(frame));
            mTappedStill = frame;
            mTappedStillVideoId = video.videoId;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            // no seed: the still starts black, as before
        }
    }

    /**
     * NEWTUBE(motion): what the loading still shows until the cached thumbnail lands - the tapped
     * row's picture, else a picture already covering the box (the open morph's card snapshot), else
     * nothing (black).
     */
    @Nullable
    private android.graphics.drawable.Drawable takeStillSeed(String videoId) {
        Bitmap tapped = mTappedStill;
        boolean match = tapped != null && videoId.equals(mTappedStillVideoId);
        mTappedStill = null;
        mTappedStillVideoId = null;
        if (match) {
            return new android.graphics.drawable.BitmapDrawable(getResources(), tapped);
        }
        if (mVideoStill.getVisibility() == View.VISIBLE && mVideoStill.getDrawable() != null
                && mVideoStill.getWidth() > 0 && mVideoStill.getHeight() > 0) {
            // A copy, never the drawable itself: when it came from Glide, replacing the request
            // below releases its bitmap for reuse (Codex review of this change).
            try {
                Bitmap copy = Bitmap.createBitmap(mVideoStill.getWidth(), mVideoStill.getHeight(),
                        Bitmap.Config.RGB_565);
                mVideoStill.draw(new android.graphics.Canvas(copy));
                return new android.graphics.drawable.BitmapDrawable(getResources(), copy);
            } catch (RuntimeException | OutOfMemoryError ignored) {
                return null;
            }
        }
        return null;
    }

    /** New video on this reused view: thumbnail over the stale frame until the new first frame. */
    private void maybeShowLoadingStill(Video item) {
        if (item == null || item.videoId == null || mVideoStill == null
                || Helpers.equals(item.videoId, mStillVideoId)) {
            return;
        }
        mStillVideoId = item.videoId;
        mNewVideoStill = true;
        armStillForReady(); // the OLD stream is still READY; wait for the new one
        mStillAwaitFrame = false;
        android.graphics.drawable.Drawable seed = takeStillSeed(item.videoId);
        mVideoStill.animate().cancel();
        mVideoStill.setImageDrawable(seed); // the seed, or solid black, until the thumbnail lands
        mVideoStill.setAlpha(1f);
        mVideoStill.setVisibility(View.VISIBLE);

        // NEWTUBE(net): a video is opening - hold every OTHER image request on this page until its
        // first frame renders (see holdImageRequests).
        holdImageRequests();

        // NEWTUBE(net): the CARD thumbnail, not getBackgroundUrl(). That one is the maxres still
        // (108-180 KB) and was fetched fresh on every open, straight into the critical path next to
        // /player and the first media chunks - to fill a ~190dp box that cannot show the detail. The
        // card image is normally already in Glide's cache (the feed/related row that was tapped just
        // drew it), so this usually resolves without touching the network at all.
        // Loaded through the APPLICATION request manager on purpose: the activity-scoped one is what
        // holdImageRequests() pauses, and the still is the one image that must not be held back.
        // NEWTUBE(net): and it must cost ZERO bytes, from either entry point. Whichever row was
        // tapped already drew this video's thumbnail, but the feed draws a full-width rendition
        // while a related row now draws a narrow one - so asking for one fixed size would put a
        // fresh ~114 KB download in the critical path for half of all opens. Ask for the wide one
        // FROM CACHE ONLY and let the narrow one (also cached, by the related row) serve the miss.
        // MEETUBE(shorts): a short fills the screen - its portrait still, from the network if need be
        // (the Shorts overlay has usually preloaded it into memory for its swipe peek already).
        mVideoStill.setScaleType(!item.belongsToShorts() ? ImageView.ScaleType.FIT_XY
                : ShortsOverlay.fillsScreen(this) ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        if (item.belongsToShorts() && !isFinishing() && !isDestroyed()) {
            ShortsOverlay.portrait(this, item.videoId).placeholder(seed).error(seed).into(mVideoStill);
            return;
        }
        String thumb = ClickbaitRemover.updateThumbnail(item, MainUIData.instance(this).getThumbQuality());
        if (thumb != null && !isFinishing() && !isDestroyed()) {
            ensureStillSize();
            String narrow = ClickbaitRemover.fitThumbnail(thumb, getResources().getDimensionPixelSize(
                    R.dimen.mobile_watch_related_thumb_width));
            Glide.with(getApplicationContext())
                    .load(thumb)
                    .onlyRetrieveFromCache(true)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .override(mStillW, mStillH)
                    .centerCrop()
                    .placeholder(seed) // Glide would otherwise clear the seed while it loads
                    .error(Glide.with(getApplicationContext())
                            .load(narrow)
                            .onlyRetrieveFromCache(true)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .format(DecodeFormat.PREFER_RGB_565)
                            .override(mStillW, mStillH)
                            .centerCrop()
                            .placeholder(seed)
                            .error(seed))
                    .into(mVideoStill);
        }
    }

    // ---------------------------------------------------------------------------------
    // NEWTUBE(net): image requests yield to the opening video
    //
    // The watch page asks for 30-80 related thumbnails (1-2.5 MB) the moment suggestions land,
    // which is exactly when /player and the first media chunks need the link. Worse, the related
    // RecyclerView sits in a NestedScrollView with wrap_content + nestedScrollingEnabled=false, so
    // it is measured UNSPECIFIED, lays out every row at once and never recycles - RelatedVideoAdapter
    // .unbind() (and with it Glide's per-row cancellation) is dead code today.
    //
    // So the whole activity-scoped RequestManager is paused for the open and resumed when the
    // loading still lifts, i.e. at the first rendered frame of the new video. Nothing is cancelled -
    // Glide re-runs the pending requests on resume. Blank-forever is guarded three ways: the still
    // always converges on hideVideoStill() (STATE_READY or its next frame, or onPlayerError), a watchdog
    // releases the hold regardless, and Glide's own activity lifecycle resumes the manager on every
    // onStart.
    //
    // FOLLOW-UP: the real fix is to make the whole watch page ONE outer RecyclerView so the related
    // rows recycle and only visible thumbnails are ever requested. That is a layout refactor of
    // activity_mobile_playback.xml plus a multi-view-type adapter, deliberately not done here.
    // ---------------------------------------------------------------------------------

    /** Upper bound on the hold. Longer than a healthy open, shorter than a person's patience. */
    private static final long IMAGE_HOLD_TIMEOUT_MS = 6_000;
    private boolean mImageRequestsHeld;
    private final Runnable mReleaseImageRequests = () -> releaseImageRequests("timeout");

    private void holdImageRequests() {
        Utils.removeCallbacks(mReleaseImageRequests);
        Utils.postDelayed(mReleaseImageRequests, IMAGE_HOLD_TIMEOUT_MS);
        if (mImageRequestsHeld || isFinishing() || isDestroyed()) {
            return;
        }
        mImageRequestsHeld = true;
        Glide.with(this).pauseRequests();
        NetPath.log(NetPath.context() + " image-hold on mgr="
                + Integer.toHexString(System.identityHashCode(Glide.with(this))));
    }

    private void releaseImageRequests(String why) {
        Utils.removeCallbacks(mReleaseImageRequests);
        if (!mImageRequestsHeld) {
            return;
        }
        mImageRequestsHeld = false;
        if (!isDestroyed()) {
            Glide.with(this).resumeRequests();
        }
        NetPath.log(NetPath.context() + " image-hold off why=" + why);
    }

    private void releaseImageRequests() {
        releaseImageRequests("unspecified");
    }

    /** Mini hand-off: show a captured frame while the re-parented texture paints (1-2 frames). */
    private void showHandoffStill(Bitmap frame) {
        if (mVideoStill == null || frame == null) {
            return;
        }
        mStillAwaitReady = false;
        mStillAwaitFrame = true;
        mVideoStill.animate().cancel();
        mVideoStill.setImageBitmap(frame);
        mVideoStill.setAlpha(1f);
        mVideoStill.setVisibility(View.VISIBLE);
    }

    private void hideVideoStill() {
        hideVideoStill(false, "texture");
    }

    /** @param lift {@code frame}, {@code ready} or {@code texture}: which still-lift path revealed the new video. */
    private void hideVideoStill(boolean revealNewVideo, String lift) {
        mNewVideoStill = false;
        if (revealNewVideo) {
            boolean hidden = fadeOutLoadingStill(mVideoStill);
            if (hidden && mVideoArea != null && mVideoArea.isShown()) {
                // UI visibility milestone after a texture update of this open's frames, not a
                // compositor-present timestamp. There is no remaining still-fade interval after
                // this event. lift=frame: at this open's first frame, before READY; lift=ready: at
                // READY, that update came before it (NEWTUBE(still-lift)); lift=texture: it is the
                // first one after READY.
                NetPath.log(NetPath.context() + " picture-visible +" + NetPath.elapsedMs()
                        + " state=ready-texture-overlay-gone lift=" + lift);
            }
            releaseImageRequests("picture-visible");
            return;
        }
        // The still lifting IS the first-frame milestone, so it is also where the page's other
        // images get the link back. Before the visibility guard: every path that gives up on the
        // still (player error, a hand-off that never showed one) must release the hold too.
        releaseImageRequests("still-lifted");

        if (mVideoStill == null || mVideoStill.getVisibility() != View.VISIBLE) {
            return;
        }
        mVideoStill.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            mVideoStill.setVisibility(View.GONE);
            mVideoStill.setAlpha(1f);
            mVideoStill.setImageDrawable(null);
        }).start();
    }

    /**
     * NEWTUBE(motion): how long the new video's first frame takes to come through the thumbnail. It
     * was a cut (a different picture swapped in one frame); the frame is already decoded and on the
     * texture behind the still, so the fade delays nothing, and the curve shows most of the picture
     * within the first frames. Kept short on purpose: Codex's review of the plan warned that a long
     * fade reads as a slower start.
     */
    private static final long STILL_REVEAL_MS = 100;

    /** As {@link #hideLoadingStillImmediately}, fading the still off over {@link #STILL_REVEAL_MS}. */
    static boolean fadeOutLoadingStill(@Nullable ImageView still) {
        if (still == null || still.getVisibility() != View.VISIBLE) {
            return false;
        }
        still.animate().cancel();
        still.animate().alpha(0f).setDuration(STILL_REVEAL_MS).setInterpolator(Motion.STANDARD)
                .withEndAction(() -> {
                    still.setVisibility(View.GONE);
                    still.setAlpha(1f);
                    still.setImageDrawable(null);
                    // picture-visible marks the fade's START (the picture begins to show and is
                    // mostly through within ~40 ms); this marks the overlay fully gone.
                    NetPath.log(NetPath.context() + " picture-revealed +" + NetPath.elapsedMs());
                }).start();
        return true;
    }

    /**
     * Only called for a new stream after the caller observed READY and a texture frame of it: the
     * next one after READY, or one since this open's first frame (canLiftStillAtReady).
     */
    static boolean hideLoadingStillImmediately(@Nullable ImageView still) {
        if (still == null || still.getVisibility() != View.VISIBLE) {
            return false;
        }
        still.animate().cancel();
        still.setVisibility(View.GONE);
        still.setAlpha(1f);
        still.setImageDrawable(null);
        return true;
    }

    private void releaseSessionTexture() {
        if (mSessionSurface != null) {
            mSessionSurface.release();
            mSessionSurface = null;
        }
        if (mSessionTexture != null) {
            // If the texture is still displayed by the Browse card (we died while minimized) the
            // card's own detach releases it again - a double release is tolerated natively.
            try {
                mSessionTexture.release();
            } catch (RuntimeException ignored) {
            }
            mSessionTexture = null;
        }
    }

    // ---------------------------------------------------------------------------------
    // NEWTUBE(gestures): the player's swipes (PlayerContainerLayout.SwipeListener, issue #12).
    // Portrait: down minimizes, up goes fullscreen. Fullscreen: down comes back out, and up/down on
    // the left or right 3/8 sets the brightness or the volume. Sideways anywhere seeks, like a
    // drag on the seek bar. Nothing starts while the finger holds 2x, a seek bar drag or a morph
    // is under way, or the player is in PiP.
    // ---------------------------------------------------------------------------------

    private static final int SWIPE_MINIMIZE = 1;
    private static final int SWIPE_ENTER_FULLSCREEN = 2;
    private static final int SWIPE_EXIT_FULLSCREEN = 3;
    private static final int SWIPE_BRIGHTNESS = 4;
    private static final int SWIPE_VOLUME = 5;
    private static final int SWIPE_SEEK = 6;
    /** MEETUBE(shorts): a vertical drag that pages to the next / previous short. */
    private static final int SWIPE_SHORTS = 7;
    /** MEETUBE(more videos, issue #24): the landscape panel of Up next cards, under the finger. */
    private static final int SWIPE_MORE_VIDEOS = 8;

    @Nullable
    private MoreVideosPanel mMoreVideos;

    /**
     * The up chevron at the bottom of the landscape controls: shown in the band beside the actions
     * pill (hidden with the pill, whose band the seek bar then takes) while there are videos to raise.
     */
    private void updateMoreVideosHint() {
        View hint = findViewById(R.id.mobile_player_more_hint);
        if (hint == null) {
            return;
        }
        boolean show = isLandscape() && mActionsRow != null && mActionsRow.getVisibility() == View.VISIBLE
                && !mRelatedVideos.isEmpty();
        hint.setVisibility(show ? View.VISIBLE : View.GONE);
        hint.setOnClickListener(v -> {
            if (mMoreVideos != null && mMoreVideos.begin()) {
                mMoreVideos.open();
            }
        });
        if (show) {
            setMargins(hint, 0, 0, dp(12), ((ViewGroup.MarginLayoutParams) mActionsRow.getLayoutParams()).bottomMargin);
        }
    }

    private ShortsOverlay.Watch shortsWatchViews() {
        ShortsOverlay.Watch watch = new ShortsOverlay.Watch();
        watch.like = mWatchLike;
        watch.dislike = mWatchDislike;
        watch.share = mWatchShare;
        watch.download = mWatchDownload;
        watch.subscribe = mWatchSubscribe;
        watch.subscribeLabel = mWatchSubscribe;
        watch.channel = findViewById(R.id.mobile_watch_channel_row);
        watch.likeCount = mWatchLikeCount;
        watch.commentsCount = findViewById(R.id.mobile_watch_comments_count);
        watch.meta = mWatchMeta;
        watch.avatar = mWatchAvatar;
        return watch;
    }

    /** MEETUBE(shorts): the top bar's mute, kept from short to short until it is tapped again or Shorts end. */
    private boolean mShortsMuted;
    private float mShortsVolumeBeforeMute = 1f;

    /** Through the controller, like the close fade, so loudness normalization does not undo it. */
    private boolean toggleShortsMute() {
        if (mExoPlayerController == null) {
            return mShortsMuted;
        }
        mShortsMuted = !mShortsMuted;
        if (mShortsMuted) {
            mShortsVolumeBeforeMute = mExoPlayerController.getVolume();
            mExoPlayerController.setVolume(0f);
        } else {
            mExoPlayerController.setVolume(mShortsVolumeBeforeMute);
        }
        return mShortsMuted;
    }

    /** MEETUBE(shorts): how far a short's open comments are off the screen (0 open, 1 gone); -1 none. */
    private float mShortsCommentsGone = -1f;
    private final int[] mShortsBoxAt = new int[2];

    /**
     * MEETUBE(shorts): the short above its comments grows and shrinks with the sheet a frame at a
     * time, like YouTube's (#27). Scaled from its top centre and lifted, the box reaches down to
     * the sheet's top edge; with the sheet gone it covers the whole window, where the full-screen
     * layout then puts it, so the switch at the end does not show. Re-applied after each layout
     * (the box shrinks to its third one frame after the sheet starts up).
     */
    private void followShortsComments(float gone) {
        if (mVideoArea == null || mShorts == null || !mShorts.isCommentsOpen() || isLandscape()) {
            return;
        }
        mShortsCommentsGone = gone;
        int height = mVideoArea.getHeight();
        int window = getWindow().getDecorView().getHeight();
        if (height <= 0 || window <= height) {
            return; // still the full-screen box
        }
        mVideoArea.getLocationInWindow(mShortsBoxAt);
        float top = mShortsBoxAt[1] - mVideoArea.getTranslationY();
        float scale = 1f + (window / (float) height - 1f) * gone;
        mVideoArea.animate().cancel();
        mVideoArea.setPivotX(mVideoArea.getWidth() / 2f);
        mVideoArea.setPivotY(0f);
        mVideoArea.setScaleX(scale);
        mVideoArea.setScaleY(scale);
        mVideoArea.setTranslationY(-top * gone);
    }

    /** MEETUBE(shorts): a short's comments are gone - the short takes the full screen again. */
    private void onShortsCommentsClosed() {
        boolean followed = mShortsCommentsGone >= 0.99f;
        mShortsCommentsGone = -1f;
        if (!followed) { // the sheet left without telling (predictive back): swell back
            resetShortsBox();
            growShortBack(220);
            return;
        }
        // Already drawn full size: swap in the full-screen layout under the same picture.
        mShorts.setCommentsOpen(false);
        applyShortsLayout();
        if (mVideoArea != null) {
            OneShotPreDrawListener.add(mVideoArea, this::resetShortsBox);
        }
    }

    private void resetShortsBox() {
        if (mVideoArea != null) {
            mVideoArea.setScaleX(1f);
            mVideoArea.setScaleY(1f);
            mVideoArea.setTranslationY(0f);
        }
    }

    /**
     * MEETUBE(shorts): leave the comments - the box takes the full screen at once and is scaled
     * down to where the short was, then grows into place (top-centre pivot), so the short swells
     * back instead of jumping.
     */
    private void growShortBack(long durationMs) {
        int from = mVideoArea != null ? mVideoArea.getHeight() : 0;
        mShorts.setCommentsOpen(false);
        applyShortsLayout();
        if (mVideoArea == null || from <= 0) {
            return;
        }
        OneShotPreDrawListener.add(mVideoArea, () -> {
            int to = mVideoArea.getHeight();
            if (to <= from) {
                return;
            }
            float start = (float) from / to;
            mVideoArea.animate().cancel();
            mVideoArea.setPivotX(mVideoArea.getWidth() / 2f);
            mVideoArea.setPivotY(0f);
            mVideoArea.setScaleX(start);
            mVideoArea.setScaleY(start);
            mVideoArea.animate().scaleX(1f).scaleY(1f).setDuration(Math.max(220, durationMs + 80))
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f)).start();
        });
    }

    /** MEETUBE(shorts): a tap waits out a possible second tap - one pauses, two like. */
    private final Runnable mShortsSingleTap = () -> {
        togglePlayPause();
        if (mShorts != null && mPlayer != null) {
            mShorts.flashPlayState(mPlayer.getPlayWhenReady());
        }
    };

    private void likeShort() {
        if (getButtonState(R.id.action_thumbs_up) != BUTTON_ON && mWatchLike != null) {
            mWatchLike.performClick();
        }
        mShorts.popHeart();
    }

    /** Shorts filling the portrait screen (not while its comments are open, nor in landscape). */
    /**
     * MEETUBE(fullscreen): YouTube's vertical fullscreen - the video fills the upright screen and
     * the page steps aside, with the system bars hidden. Entered by pulling the page down from its
     * top (the title); left by Back, a swipe up or down on the video, or turning the phone sideways.
     */
    private boolean mPortraitFull;
    @Nullable
    private View mWatchFab;
    /** How far the page must be pulled down from its top to enter the vertical fullscreen. */
    private static final float PORTRAIT_FULL_PULL_DP = 72f;
    /** The box's move into and out of the vertical fullscreen. */
    private static final long PORTRAIT_FULL_MS = 320;
    private float mPullDownX;
    private float mPullDownY = -1f;
    private boolean mPullConsumed;

    /** The video fills the portrait screen: a short, or the vertical fullscreen. */
    private boolean isTallMode() {
        return isShortsMode() || (mPortraitFull && !isLandscape()) || (isJoyLocalPage() && !isLandscape()); // JOYBOY(v950)
    }

    /** JOYBOY(v950): this is the separate local-media player (files / direct links / browser): it never shows the YouTube watch page. */
    private boolean isJoyLocalPage() {
        return this instanceof MobileLocalPlaybackActivity;
    }

    private void setPortraitFull(boolean on) {
        com.newtube.mobile.ui.common.PlayerTrace.mark("setPortraitFull(" + on + ")"); // JOYBOY(v952)
        if (!on && isJoyLocalPage() && !isLandscape()) { // JOYBOY(v950): local player — upright fullscreen is the only upright layout (never the watch page)
            return;
        }
        if (on == mPortraitFull || (on && (isLandscape() || isShortsMode() || mIsInPip || mPipEnterPending))) {
            return;
        }
        mPortraitFull = on;
        if (mWatchRoot != null) {
            android.transition.TransitionManager.beginDelayedTransition((ViewGroup) mWatchRoot,
                    new android.transition.ChangeBounds().setDuration(PORTRAIT_FULL_MS).setInterpolator(Motion.EMPHASIZED));
        }
        // MEETUBE(fullscreen): the bars' room goes or comes back in this same layout, so the box moves
        // once - it used to jump by the status bar when the bars' insets landed mid-move.
        refreshContentInsets();
        applyWatchLayoutForOrientation(Configuration.ORIENTATION_PORTRAIT);
        if (mVideoArea != null) { // a pull's lift settles as the box grows
            mVideoArea.animate().scaleX(1f).scaleY(1f).setDuration(PORTRAIT_FULL_MS)
                    .setInterpolator(Motion.EMPHASIZED).start();
        }
        if (on && mWatchScroll != null) {
            // The growing box pushes the page off the bottom, fading, instead of it vanishing at once.
            mWatchScroll.setVisibility(View.VISIBLE);
            mWatchScroll.animate().cancel();
            mWatchScroll.animate().alpha(0f)
                    .translationY(mWatchScroll.getTranslationY() + 96 * getResources().getDisplayMetrics().density)
                    .setDuration(PORTRAIT_FULL_MS).setInterpolator(Motion.EMPHASIZED_ACCELERATE).start();
            mWatchScroll.postDelayed(() -> {
                if (mPortraitFull && mWatchScroll != null) {
                    mWatchScroll.setVisibility(View.GONE);
                    mWatchScroll.setAlpha(1f);
                    mWatchScroll.setTranslationY(0f);
                }
            }, PORTRAIT_FULL_MS);
        } else if (!on && mWatchScroll != null) {
            // Coming back, the page rises in under the shrinking box instead of popping in.
            mWatchScroll.animate().cancel();
            mWatchScroll.setAlpha(0f);
            mWatchScroll.setTranslationY(64 * getResources().getDisplayMetrics().density);
            mWatchScroll.animate().alpha(1f).translationY(0f).setStartDelay(PORTRAIT_FULL_MS / 4)
                    .setDuration(PORTRAIT_FULL_MS).setInterpolator(Motion.EMPHASIZED_DECELERATE)
                    .withEndAction(() -> mWatchScroll.animate().setStartDelay(0)).start();
        }
        setResizeMode(PlayerData.instance(this).getResizeMode()); // fit while upright-full; also re-lays the controls
        if (mAmbient != null) {
            mAmbient.setFill(on ? mVideoArea : null); // the black bands glow with the video
        }
        if (on) {
            applyDisplayCutoutMode(true); // the picture may use the camera's band too
            androidx.core.view.WindowInsetsControllerCompat bars =
                    androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            bars.setSystemBarsBehavior(
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            bars.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            applySystemBarsForOrientation(Configuration.ORIENTATION_PORTRAIT);
        }
        applyControlsInsets(); // the top row clears the camera band while upright-full
        Haptics.threshold(mContainer, on);
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        com.newtube.mobile.ui.common.PlayerTrace.touch(this, event); // JOYBOY(v952)
        int action = event.getActionMasked();
        if (mPlayerLocked) {
            // MEETUBE(lock, issue #98): a locked player answers only its unlock button - no swipe,
            // tap, pinch or double tap reaches it. A tap anywhere else shows the button.
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                mUnlockTouch = mPlayerUnlock.getVisibility() == View.VISIBLE && isOn(mPlayerUnlock, event);
            }
            if (mUnlockTouch) {
                // the pill stays up while it is being dragged (FloatingLock)
                mPlayerUnlock.removeCallbacks(mHideUnlock);
                boolean handled = super.dispatchTouchEvent(event);
                if (mPlayerLocked && (action == android.view.MotionEvent.ACTION_UP
                        || action == android.view.MotionEvent.ACTION_CANCEL)) {
                    flashUnlock();
                }
                return handled;
            }
            if (action == android.view.MotionEvent.ACTION_UP) {
                flashUnlock();
            }
            return true;
        }
        if (action == android.view.MotionEvent.ACTION_DOWN && mShortsStayClean) {
            // MEETUBE(shorts): the touch after a clean hold only brings the buttons back
            mShortsStayClean = false;
            mShortsRevealTouch = true;
            if (mShorts != null) {
                mShorts.setClean(false);
            }
        }
        if (mShortsRevealTouch) {
            if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                mShortsRevealTouch = false;
            }
            return true;
        }
        return pullToPortraitFull(event) || super.dispatchTouchEvent(event);
    }

    /** MEETUBE(lock, issue #98): the fullscreen lock, and whether this touch is the unlock button's. */
    private View mPlayerLock;
    private View mPlayerUnlock;
    private FloatingLock mFloatingLock;
    private boolean mPlayerLocked;
    private boolean mUnlockTouch;
    private final Runnable mHideUnlock = () -> {
        mPlayerUnlock.animate().alpha(0f).setDuration(150)
                .withEndAction(() -> mPlayerUnlock.setVisibility(View.GONE)).start();
        if (mPlayerLocked) {
            hideControls(); // the bar, time and chapter leave with it
        }
    };
    /** MEETUBE(lock): the alphas the locked look took away, given back on unlock. */
    private final java.util.Map<View, Float> mLockedAlphas = new java.util.HashMap<>();
    @Nullable private Drawable[] mLockedChapterIcons;
    private boolean mLockedChrome;

    private void setPlayerLocked(boolean locked) {
        if (mPlayerLocked == locked) {
            return;
        }
        mPlayerLocked = locked;
        Haptics.threshold(mContainer, locked);
        if (locked) {
            flashUnlock();
        } else {
            mPlayerUnlock.removeCallbacks(mHideUnlock);
            mPlayerUnlock.animate().cancel();
            mPlayerUnlock.setVisibility(View.GONE);
            showControlsInternal(!mControlsVisible);
        }
    }

    /**
     * MEETUBE(lock): a locked player still says where the video is - the seek bar, the time and the
     * current chapter, without its chevron (nothing takes a tap) - and nothing else: the top row,
     * the transport, the lock, fullscreen and the actions row (likes, comments...) step aside.
     */
    private void setLockedChrome(boolean locked) {
        if (locked == mLockedChrome || !(mControlsRoot instanceof ViewGroup) || !(mBottomRow instanceof ViewGroup)) {
            return;
        }
        mLockedChrome = locked;
        // Locked: no dot (nothing takes a touch) and no room kept for the hidden actions row (#201).
        mTimeBar.setDotHidden(locked);
        applyControlsInsets();
        if (!locked) {
            for (java.util.Map.Entry<View, Float> hidden : mLockedAlphas.entrySet()) {
                hidden.getKey().setAlpha(hidden.getValue());
            }
            mLockedAlphas.clear();
            if (mLockedChapterIcons != null) {
                mChapterButton.setCompoundDrawablesRelative(mLockedChapterIcons[0], mLockedChapterIcons[1],
                        mLockedChapterIcons[2], mLockedChapterIcons[3]);
                mLockedChapterIcons = null;
            }
            return;
        }
        View bottomScrim = findViewById(R.id.mobile_player_bottom_scrim);
        View chapter = (View) mChapterButton.getParent();
        for (ViewGroup group : new ViewGroup[] {(ViewGroup) mControlsRoot, (ViewGroup) mBottomRow}) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                int id = child.getId();
                if (child == mBottomRow || child == bottomScrim || child == chapter
                        || id == R.id.mobile_player_time || id == R.id.mobile_player_live) {
                    continue;
                }
                child.animate().cancel();
                mLockedAlphas.put(child, child.getAlpha());
                child.setAlpha(0f);
            }
        }
        mLockedChapterIcons = mChapterButton.getCompoundDrawablesRelative();
        mChapterButton.setCompoundDrawablesRelative(null, null, null, null);
    }

    /** The unlock button for a few seconds, then it fades away again - with where the video is. */
    private void flashUnlock() {
        showControlsInternal(!mControlsVisible);
        mPlayerUnlock.removeCallbacks(mHideUnlock);
        mPlayerUnlock.animate().cancel();
        if (mPlayerUnlock.getVisibility() != View.VISIBLE) {
            mPlayerUnlock.setAlpha(0f);
            mPlayerUnlock.setVisibility(View.VISIBLE);
        }
        mPlayerUnlock.animate().alpha(1f).setDuration(150).start();
        mPlayerUnlock.postDelayed(mHideUnlock, 2500);
    }

    private static boolean isOn(View view, android.view.MotionEvent event) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        float x = event.getRawX() - at[0];
        float y = event.getRawY() - at[1];
        return x >= 0 && y >= 0 && x < view.getWidth() && y < view.getHeight();
    }

    /** MEETUBE(shorts): a short held clean (not at 2x) stays clean after the finger lifts. */
    private boolean mShortsStayClean;
    /** The gesture that brought a clean short's buttons back - swallowed whole. */
    private boolean mShortsRevealTouch;

    /** True while the gesture is the pull that entered the vertical fullscreen (it is swallowed). */
    private boolean pullToPortraitFull(android.view.MotionEvent event) {
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                mPullConsumed = false;
                mPullDownX = event.getRawX();
                mPullDownY = canPullToPortraitFull(event) ? event.getRawY() : -1f;
                return false;
            case android.view.MotionEvent.ACTION_MOVE:
                if (mPullDownY < 0f) {
                    return mPullConsumed;
                }
                float dy = event.getRawY() - mPullDownY;
                float density = getResources().getDisplayMetrics().density;
                if (!mPullConsumed) {
                    float dx = Math.abs(event.getRawX() - mPullDownX);
                    if (dy < -8 * density || dx > Math.max(16 * density, Math.abs(dy))) {
                        mPullDownY = -1f; // scrolling the page, or a sideways drag
                        return false;
                    }
                    if (dy < 10 * density) {
                        return false;
                    }
                    // MEETUBE(fullscreen): a pull from here on - the page follows the finger.
                    mPullConsumed = true;
                    android.view.MotionEvent cancel = android.view.MotionEvent.obtain(event);
                    cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                }
                showPullProgress(Math.max(0f, dy));
                return true;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                boolean consumed = mPullConsumed;
                if (consumed && mPullDownY >= 0f) {
                    float pulled = event.getRawY() - mPullDownY;
                    boolean enter = event.getActionMasked() == android.view.MotionEvent.ACTION_UP
                            && pulled > PORTRAIT_FULL_PULL_DP * getResources().getDisplayMetrics().density;
                    if (enter) {
                        setPortraitFull(true);
                    } else {
                        settlePull();
                    }
                }
                mPullDownY = -1f;
                mPullConsumed = false;
                return consumed;
            default:
                return mPullConsumed;
        }
    }

    /** The pull under the finger: the page slides down (damped) and fades, the video lifts a little. */
    private void showPullProgress(float dy) {
        float full = PORTRAIT_FULL_PULL_DP * getResources().getDisplayMetrics().density;
        float progress = Math.min(1f, dy / full);
        // Past the threshold the page keeps moving, slower: it still feels attached.
        float travel = dy <= full ? dy * 0.6f : full * 0.6f + (dy - full) * 0.2f;
        if (mWatchScroll != null) {
            mWatchScroll.animate().cancel();
            mWatchScroll.setTranslationY(travel);
            mWatchScroll.setAlpha(1f - 0.35f * progress);
        }
        if (mVideoArea != null) {
            mVideoArea.animate().cancel();
            mVideoArea.setPivotX(mVideoArea.getWidth() / 2f);
            mVideoArea.setPivotY(0f);
            float scale = 1f + 0.04f * progress;
            mVideoArea.setScaleX(scale);
            mVideoArea.setScaleY(scale);
        }
    }

    /** A pull let go short of the threshold: everything springs back. */
    private void settlePull() {
        if (mWatchScroll != null) {
            mWatchScroll.animate().translationY(0f).alpha(1f).setDuration(220)
                    .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
        }
        if (mVideoArea != null) {
            mVideoArea.animate().scaleX(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
        }
    }

    /** A touch on the watch page while it rests at its top (the title row is in view). */
    private boolean canPullToPortraitFull(android.view.MotionEvent event) {
        if (mWatchScroll == null || mWatchScroll.getVisibility() != View.VISIBLE || mWatchScroll.getScrollY() != 0
                || mTallBoxCollapse > 0 // the pull grows the box first (#36)
                || isLandscape() || isTallMode() || mIsInPip || mClosing || mMorphFraction != 0f
                || (mCommentsPanel != null && mCommentsPanel.isOpen())
                || (openSidePanel() != null)) {
            return false;
        }
        int[] at = new int[2];
        mWatchScroll.getLocationOnScreen(at);
        float x = event.getRawX();
        float y = event.getRawY();
        return x >= at[0] && x < at[0] + mWatchScroll.getWidth()
                && y >= at[1] && y < at[1] + mWatchScroll.getHeight();
    }

    /**
     * Any orientation (#57): landscape used to fall back to the ordinary fullscreen player under
     * the Shorts overlay, so a vertical swipe set the brightness instead of paging.
     */
    private boolean isShortsMode() {
        return mShorts != null && mShorts.isFullscreen();
    }

    /**
     * MEETUBE(shorts): keep the overlay's buttons and text clear of the system bars while its
     * scrims run on under them - padding the whole overlay cut each scrim off in a hard line at
     * the status bar and the navigation bar. The XML values are kept in each child's tag.
     */
    private static void insetShortsOverlay(ViewGroup overlay, int top, int bottom) {
        for (int i = 0; i < overlay.getChildCount(); i++) {
            View child = overlay.getChildAt(i);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) child.getLayoutParams();
            int vertical = lp.gravity & Gravity.VERTICAL_GRAVITY_MASK;
            if (vertical != Gravity.TOP && vertical != Gravity.BOTTOM) {
                continue; // the peek fills the box; the flash and the heart sit in its middle
            }
            if (!(child.getTag() instanceof int[])) {
                child.setTag(new int[] {lp.height, lp.topMargin, lp.bottomMargin});
            }
            int[] base = (int[]) child.getTag();
            int inset = vertical == Gravity.TOP ? top : bottom;
            if (child.getClass() == View.class) { // a scrim: taller by the bar it runs under
                lp.height = base[0] + inset;
            } else if (vertical == Gravity.TOP) {
                lp.topMargin = base[1] + inset;
            } else {
                lp.bottomMargin = base[2] + inset;
            }
            child.setLayoutParams(lp);
        }
    }

    /** Re-lays the video box and its fill for the current shorts state. */
    private void applyShortsLayout() {
        refreshContentInsets(); // edge to edge while a short fills the screen
        applyWatchLayoutForOrientation(layoutOrientation());
        setResizeMode(PlayerData.instance(this).getResizeMode());
        if (mAmbient != null) {
            mAmbient.setFill(isTallMode() ? mVideoArea : null); // the bands around a short glow
        }
        updateSeekBarLine();
        if (!mControlsVisible) {
            startProgressUpdates(); // the Shorts line is fed by the line loop
        }
    }
    /**
     * Fullscreen: this share of the screen on the left (brightness) and the right (volume) - the
     * zones of ReVanced's swipe controls, which most people who swipe on YouTube learned them from.
     * The quarter between them keeps YouTube's swipe down out of fullscreen.
     */
    private static final float LEVEL_ZONE = 3f / 8f;
    /** The fullscreen swipes stick and click like the minimize one; a flick this fast (dp/s) goes at once. */
    private static final float FULLSCREEN_FLICK_DP = 800f;
    private static final long FULLSCREEN_ROTATION_WAIT_MS = 700;
    /**
     * YouTube 21.18, measured: dragged down, the fullscreen video shrinks to 95% within ~38 dp and
     * slides down until 30% of its height, where it stops dead.
     */
    private static final float FULLSCREEN_PULL_SCALE = 0.95f;
    private static final float FULLSCREEN_PULL_SCALE_DP = 38f;
    private static final float FULLSCREEN_PULL_MAX = 0.3f;

    private SwipeLevels mSwipeLevels;
    private float mSwipeDownRawX;
    /** Finger travel when a level swipe was recognized: the level starts moving from there. */
    private float mSwipeStartDy;
    @Nullable
    private MagneticDrag mFullscreenMagnet;
    /** SWIPE_ENTER_FULLSCREEN / SWIPE_EXIT_FULLSCREEN while one is under the finger or settling. */
    private int mFullscreenSwipe;
    /** MEETUBE(fullscreen): the vertical fullscreen is being left by a swipe UP - its pull goes up. */
    private boolean mFullscreenSwipeUp;
    @Nullable
    private ValueAnimator mFullscreenSettle;

    @Override
    public int onSwipeStart(int direction, float downRawX, float downRawY, float dx, float dy) {
        if (mClosing || mIsInPip || mPipEnterPending || mScrubbing || mBackPreview
                || mFullscreenSwipe != 0 || mVideoArea == null
                || (mExoPlayerController != null && mExoPlayerController.isHoldSpeedOn())) {
            return PlayerContainerLayout.SWIPE_NONE;
        }
        mSwipeDownRawX = downRawX;
        boolean vertical = direction == PlayerContainerLayout.UP || direction == PlayerContainerLayout.DOWN;
        if (mMoreVideos != null && mMoreVideos.isShown()) {
            // Up or down moves the panel; sideways is its row's own scroll, not a seek.
            return vertical && mMoreVideos.begin() ? SWIPE_MORE_VIDEOS : PlayerContainerLayout.SWIPE_NONE;
        }
        if (!vertical) {
            return beginSeekSwipe(downRawX + dx);
        }
        if (isShortsMode()) {
            // MEETUBE(issue #172): a pending tap must not pause a swiped-to short.
            mVideoArea.removeCallbacks(mShortsSingleTap);
            hideControls();
            return SWIPE_SHORTS;
        }
        if (!isLandscape()) {
            if (mPortraitFull) {
                // MEETUBE(fullscreen): up or down, a swipe on the vertical fullscreen goes back to the page.
                int swipe = beginFullscreenSwipe(SWIPE_EXIT_FULLSCREEN);
                mFullscreenSwipeUp = direction == PlayerContainerLayout.UP;
                return swipe;
            }
            if (direction == PlayerContainerLayout.DOWN) {
                return canStartDismissDrag() ? SWIPE_MINIMIZE : PlayerContainerLayout.SWIPE_NONE;
            }
            return beginFullscreenSwipe(SWIPE_ENTER_FULLSCREEN);
        }
        int level = levelSwipeAt(downRawX);
        if (level != PlayerContainerLayout.SWIPE_NONE) {
            mSwipeStartDy = dy;
            hideControls();
            mSwipeLevels.begin(level == SWIPE_VOLUME ? SwipeLevels.VOLUME : SwipeLevels.BRIGHTNESS,
                    mVideoArea.getHeight());
            return level;
        }
        if (direction == PlayerContainerLayout.DOWN) {
            return beginFullscreenSwipe(SWIPE_EXIT_FULLSCREEN);
        }
        // MEETUBE(more videos): up, between the level zones, raises the Up next cards over the video.
        if (mMoreVideos != null && !isCastOverlayShown() && mMoreVideos.begin()) {
            hideControls();
            return SWIPE_MORE_VIDEOS;
        }
        return PlayerContainerLayout.SWIPE_NONE;
    }

    @Override
    public void onSwipeMove(int swipe, float dx, float dy) {
        switch (swipe) {
            case SWIPE_SHORTS:
                mShorts.drag(dy);
                break;
            case SWIPE_MORE_VIDEOS:
                mMoreVideos.drag(dy);
                break;
            case SWIPE_MINIMIZE:
                onDismissDrag(Math.max(0f, dy));
                break;
            case SWIPE_ENTER_FULLSCREEN:
            case SWIPE_EXIT_FULLSCREEN:
                if (mFullscreenSwipe == swipe) { // else a configuration change ended it under the finger
                    fullscreenMagnet().move(Math.max(0f, swipe == SWIPE_ENTER_FULLSCREEN || mFullscreenSwipeUp ? -dy : dy));
                }
                break;
            case SWIPE_BRIGHTNESS:
            case SWIPE_VOLUME:
                mSwipeLevels.move(mSwipeStartDy - dy);
                break;
            case SWIPE_SEEK:
                mTimeBar.moveSwipeScrub(mSwipeDownRawX + dx);
                break;
            default:
                break;
        }
    }

    @Override
    public void onSwipeReleased(int swipe, float dx, float dy, float xVelocity, float yVelocity) {
        switch (swipe) {
            case SWIPE_SHORTS:
                mShorts.release(dy, yVelocity);
                break;
            case SWIPE_MORE_VIDEOS:
                mMoreVideos.release(dy, yVelocity);
                break;
            case SWIPE_MINIMIZE:
                onDismissDragReleased(Math.max(0f, dy), yVelocity);
                break;
            case SWIPE_ENTER_FULLSCREEN:
            case SWIPE_EXIT_FULLSCREEN:
                if (mFullscreenSwipe == swipe) {
                    boolean up = swipe == SWIPE_ENTER_FULLSCREEN || mFullscreenSwipeUp;
                    fullscreenMagnet().move(Math.max(0f, up ? -dy : dy));
                    endFullscreenSwipe(up ? -yVelocity : yVelocity);
                }
                break;
            case SWIPE_BRIGHTNESS:
            case SWIPE_VOLUME:
                mSwipeLevels.move(mSwipeStartDy - dy);
                mSwipeLevels.end();
                break;
            case SWIPE_SEEK:
                mTimeBar.moveSwipeScrub(mSwipeDownRawX + dx);
                mTimeBar.stopSwipeScrub(false);
                break;
            default:
                break;
        }
    }

    @Override
    public void onSwipeCancelled(int swipe) {
        switch (swipe) {
            case SWIPE_SHORTS:
                mShorts.release(0f, 0f);
                break;
            case SWIPE_MORE_VIDEOS:
                mMoreVideos.release(0f, 0f);
                break;
            case SWIPE_MINIMIZE:
                onDismissDragCancelled();
                break;
            case SWIPE_ENTER_FULLSCREEN:
            case SWIPE_EXIT_FULLSCREEN:
                if (mFullscreenSwipe == swipe) {
                    fullscreenMagnet().finish();
                    settleFullscreenPull();
                }
                break;
            case SWIPE_BRIGHTNESS:
            case SWIPE_VOLUME:
                mSwipeLevels.end();
                break;
            case SWIPE_SEEK:
                mTimeBar.stopSwipeScrub(true);
                break;
            default:
                break;
        }
    }

    /** Fullscreen: SWIPE_BRIGHTNESS / SWIPE_VOLUME for a swipe that began at {@code rawX}, if any. */
    private int levelSwipeAt(float rawX) {
        if (!PlayerGesturePrefs.isLevelSwipesOn(this) || isCastOverlayShown()) {
            return PlayerContainerLayout.SWIPE_NONE;
        }
        int[] at = new int[2];
        mContainer.getLocationOnScreen(at);
        float width = Math.max(1, mContainer.getWidth());
        float x = (rawX - at[0]) / width;
        if (x < LEVEL_ZONE && mSwipeLevels.canSwipe(SwipeLevels.BRIGHTNESS)) {
            return SWIPE_BRIGHTNESS;
        }
        if (x > 1f - LEVEL_ZONE && mSwipeLevels.canSwipe(SwipeLevels.VOLUME)) {
            return SWIPE_VOLUME;
        }
        return PlayerContainerLayout.SWIPE_NONE;
    }

    private boolean isCastOverlayShown() {
        return mCastOverlay != null && mCastOverlay.getVisibility() == View.VISIBLE;
    }

    /**
     * Sideways: the seek bar's own drag, from anywhere on the video - the controls come up, step
     * aside for the bar, and the time and chapter under the dot show in the pill, as when the bar
     * is dragged. Not from the screen's side edges, where gesture navigation's Back begins.
     */
    private int beginSeekSwipe(float rawX) {
        if (!PlayerGesturePrefs.isSeekSwipeOn(this) || mPlayer == null || mExoPlayerController == null
                || mTimeBar == null || isCastOverlayShown() || mMorphAnimator != null || mMorphFraction != 0f
                || mContainer.isInSideSystemGestureBand(mSwipeDownRawX)) {
            return PlayerContainerLayout.SWIPE_NONE;
        }
        long duration = getDurationMs();
        if (duration <= 0) {
            return PlayerContainerLayout.SWIPE_NONE;
        }
        if (!mControlsVisible) {
            showControlsInternal(true);
        }
        // The bar only follows playback while the controls are up: bring it to now first.
        mTimeBar.setDuration(duration);
        mTimeBar.setPosition(Math.max(mExoPlayerController.getPositionMs(), 0));
        return mTimeBar.startSwipeScrub(rawX) ? SWIPE_SEEK : PlayerContainerLayout.SWIPE_NONE;
    }

    /**
     * Up into fullscreen from the portrait page, or down out of it. Coming down moves the way
     * YouTube 21.18's does (measured): the video shrinks a little and slides down with the finger,
     * stopping at 30% of its height. Going up, YouTube shows nothing until the rotation; here the
     * page slides up beneath the video as far, so the finger sees where it is going. Both stick and
     * let go with the minimize drag's click at the same 72 dp (YouTube: ~70 dp up, ~150 dp down,
     * no haptics); past it, letting go rotates - the fullscreen button's own toggle.
     */
    private int beginFullscreenSwipe(int swipe) {
        if (mPlayer == null || mMorphAnimator != null || mMorphFraction != 0f) {
            return PlayerContainerLayout.SWIPE_NONE;
        }
        if (mFullscreenSettle != null) {
            mFullscreenSettle.cancel();
            mFullscreenSettle = null;
        }
        mFullscreenSwipe = swipe;
        mFullscreenSwipeUp = false;
        hideControls();
        fullscreenMagnet().start();
        return swipe;
    }

    private MagneticDrag fullscreenMagnet() {
        if (mFullscreenMagnet == null) {
            mFullscreenMagnet = new MagneticDrag(mContainer, MINIMIZE_PULL, this::applyFullscreenPull);
        }
        return mFullscreenMagnet;
    }

    /** {@code px} along the finger's way (up to enter, down to leave); 0 = at rest. */
    private void applyFullscreenPull(float px) {
        if (mVideoArea == null || mFullscreenSwipe == 0) {
            return;
        }
        float pulled = Math.min(Math.max(0f, px), mVideoArea.getHeight() * FULLSCREEN_PULL_MAX);
        if (mFullscreenSwipe == SWIPE_ENTER_FULLSCREEN) {
            // The page passes under the video box: the watch column draws that box last.
            View page = findViewById(R.id.mobile_watch_area);
            if (page != null) {
                page.setTranslationY(-pulled);
            }
            return;
        }
        float shrink = Math.min(1f, pulled / (FULLSCREEN_PULL_SCALE_DP * getResources().getDisplayMetrics().density));
        float scale = 1f - (1f - FULLSCREEN_PULL_SCALE) * shrink;
        mVideoArea.setPivotX(mVideoArea.getWidth() / 2f);
        mVideoArea.setPivotY(mFullscreenSwipeUp ? mVideoArea.getHeight() : 0f);
        mVideoArea.setScaleX(scale);
        mVideoArea.setScaleY(scale);
        mVideoArea.setTranslationY(mFullscreenSwipeUp ? -pulled : pulled);
    }

    /** {@code velocity}: px/s toward the swipe's goal (negative = flicked back). */
    private void endFullscreenSwipe(float velocity) {
        MagneticDrag magnet = fullscreenMagnet();
        boolean detached = magnet.isDetached();
        magnet.finish();
        boolean go;
        if (detached) {
            go = velocity >= -1200f; // past the click it goes, unless flicked back
        } else {
            go = velocity > FULLSCREEN_FLICK_DP * getResources().getDisplayMetrics().density;
            if (go) {
                Haptics.threshold(mContainer, true);
            }
        }
        if (!go) {
            settleFullscreenPull();
            return;
        }
        // JOYBOY(v941): exiting fullscreen for a non-YouTube source (Media/browser/local/direct) never
        // lands on the watch page — it goes straight to a small paused PiP, or closes outright (back to
        // the origin) if the system refuses PiP. YouTube keeps the old fullscreen-exit behaviour.
        if (mFullscreenSwipe == SWIPE_EXIT_FULLSCREEN && JoyboyPlayerHost.onMinimize(this)) {
            if (mFullscreenSettle != null) {
                mFullscreenSettle.cancel();
            }
            if (mVideoArea != null) {
                mVideoArea.setScaleX(1f);
                mVideoArea.setScaleY(1f);
                mVideoArea.setTranslationY(0f);
            }
            mFullscreenSwipe = 0;
            setPlayWhenReady(false);
            enterPipMode();
            if (mPipEnterPending) {
                return;
            }
            if (mPresenter != null) {
                mPresenter.onFinish();
            }
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        // The pull stays as it is through the rotation (its snapshot is the first frame), and the
        // new orientation's layout puts the video back at rest (onFullscreenSwipeConfigured).
        if (BuildConfig.DEBUG) {
            NetPath.log("gesture fullscreen " + (mFullscreenSwipe == SWIPE_ENTER_FULLSCREEN ? "enter" : "exit")
                    + " detached=" + detached);
        }
        if (mPortraitFull && !isLandscape() && mFullscreenSwipe == SWIPE_EXIT_FULLSCREEN) {
            // MEETUBE(fullscreen): the pull comes off in step with the box's shrink (same time, same
            // curve), so the video moves one way only - an up swipe's pull used to dip down first.
            settleFullscreenPull(PORTRAIT_FULL_MS, Motion.STANDARD);
            setPortraitFull(false); // the vertical fullscreen leaves without a rotation
            return;
        }
        if (mFullscreenSwipe == SWIPE_ENTER_FULLSCREEN && !isLandscape() && isVerticalVideo()) {
            settleFullscreenPull(PORTRAIT_FULL_MS, Motion.STANDARD);
            setPortraitFull(true); // a vertical video goes fullscreen upright, without a rotation
            return;
        }
        boolean landscapeNow = isLandscape();
        toggleFullscreen();
        // The rotation's configuration change arrives a few frames later. If none comes (a window
        // whose orientation requests are ignored - large screens on Android 16), rest here.
        mContainer.postDelayed(() -> {
            if (mFullscreenSwipe != 0 && isLandscape() == landscapeNow) {
                settleFullscreenPull();
            }
        }, FULLSCREEN_ROTATION_WAIT_MS);
    }

    /** Back to rest, at the decelerating pace a released minimize drag returns with. */
    private void settleFullscreenPull() {
        settleFullscreenPull(180, new android.view.animation.DecelerateInterpolator());
    }

    private void settleFullscreenPull(long durationMs, android.animation.TimeInterpolator interpolator) {
        if (mVideoArea == null) {
            mFullscreenSwipe = 0;
            return;
        }
        if (mFullscreenSettle != null) {
            mFullscreenSettle.cancel();
        }
        float from = fullscreenMagnet().position();
        ValueAnimator settle = ValueAnimator.ofFloat(from, 0f);
        settle.setDuration(durationMs);
        settle.setInterpolator(interpolator);
        settle.addUpdateListener(a -> applyFullscreenPull((float) a.getAnimatedValue()));
        settle.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mFullscreenSettle == animation) {
                    mFullscreenSettle = null;
                }
                if (!mCancelled) {
                    resetFullscreenPull();
                }
            }
        });
        mFullscreenSettle = settle;
        settle.start();
    }

    private void resetFullscreenPull() {
        if (mFullscreenSettle != null) {
            mFullscreenSettle.cancel();
            mFullscreenSettle = null;
        }
        if (mFullscreenMagnet != null) {
            mFullscreenMagnet.finish(); // a configuration change mid-drag: its catch-up spring stops too
        }
        if (mFullscreenSwipe != 0) {
            View page = findViewById(R.id.mobile_watch_area);
            if (page != null) {
                page.setTranslationY(0f);
            }
            if (mVideoArea != null) {
                mVideoArea.setScaleX(1f);
                mVideoArea.setScaleY(1f);
                mVideoArea.setTranslationY(0f);
            }
        }
        mFullscreenSwipe = 0;
    }

    /**
     * The orientation changed (or the player came back to the front): a fullscreen swipe's pull
     * comes off, and the swiped brightness holds only while the player is fullscreen in front.
     */
    private void onFullscreenSwipeConfigured() {
        resetFullscreenPull();
        if (mMoreVideos != null) {
            mMoreVideos.hide();
        }
        updateSwipeBrightness();
    }

    private void updateSwipeBrightness() {
        if (mSwipeLevels != null) {
            mSwipeLevels.setBrightnessActive(isLandscape() && !mIsInPip && !mPipEnterPending);
        }
    }

    // ---------------------------------------------------------------------------------
    // Swipe-down-to-dismiss (SWIPE_MINIMIZE)
    // ---------------------------------------------------------------------------------

    /**
     * Swipe-down morph, YouTube-style: the video itself shrinks toward the exact spot where
     * Browse's floating mini-player card sits while the watch content fades away. The player
     * window is translucent, so every pixel uncovered by that movement reveals the already-live
     * screen underneath throughout the drag; there is no intermediate black window or route fade.
     * Geometry note: both activities fit system windows, so their coordinates line up and a 16:9
     * video scaled to the card's width lands exactly on the 16:9 card.
     */
    private float mMorphScaleX = 1f;
    private float mMorphScaleY = 1f;
    private float mMorphTx;
    private float mMorphTy;
    private float mMorphFraction;
    private ValueAnimator mMorphAnimator;
    /**
     * NEWTUBE(no-host-minimize): this drag has nothing of ours underneath - a cold share link
     * opened the player as the task root, so no Home/Search/Channel exists yet. Fading the backdrop
     * (below) then revealed the LAUNCHER through this translucent window, with the shrinking video
     * floating over it like a system PiP window until Home was created after the release (~0.7 s
     * on a Pixel 9). Such a drag keeps the dark backdrop: the video settles onto the mini-card spot
     * over it, and Home - created on the release, as before - fades in on top (minimizeByDrag).
     * Launching Home at the release instead was tried and rejected: its creation runs on this main
     * thread and froze the settle half-way. Decided once per drag.
     */
    /** NEWTUBE(motion): when a tap on the video last revealed the controls (0 = it hid them). */
    private long mInstantRevealAt;
    /** NEWTUBE(motion): finger travel for the whole drag (dragTravelFor); set when a drag begins. */
    private float mDragTravelPx = 1f;
    /**
     * NEWTUBE(haptics): the minimize drag sticks, then lets go with a click, like a notification on
     * the Pixel (MagneticDrag): letting go past that click minimizes, before it springs back. The
     * morph used to follow the finger from the first pixel and minimize past 30% of its travel
     * (~185 dp on a Pixel 9); it now commits at the click, 72 dp.
     */
    @Nullable
    private MagneticDrag mMinimizeMagnet;
    /** NEWTUBE(haptics): a minimize drag is under the finger (from its first move to its release). */
    private boolean mMagnetDragging;
    /** NEWTUBE(haptics): a flick this fast (dp/s) minimizes even before the drag lets go. */
    private static final float MINIMIZE_FLICK_DP = 800f;
    /**
     * NEWTUBE(haptics): the video follows 75% of the finger until the click - lighter than the
     * notification's half (owner, on the Pixel: at half it trailed the finger).
     */
    private static final float MINIMIZE_PULL = 0.75f;
    private static final long SETTLE_MIN_MS = 90;
    private static final long SETTLE_MAX_MS = 280;
    /**
     * NEWTUBE(haptics): the spring a released minimize drag lands on the card with (Motion.Spring):
     * it settles ~10 px past the card before coming to rest - the small landing of the Pixel's
     * recents flick.
     */
    private static final float SETTLE_LAND_STIFFNESS = 800f;
    private static final float SETTLE_LAND_DAMPING = 0.85f;
    private boolean mMorphOverOwnBackdrop;
    /** NEWTUBE(motion): an open/expand morph is posted but has not placed its first frame yet. */
    private boolean mMorphStartPending;
    /** NEWTUBE(motion): see the mini expansion in onResume. */
    private static final long HOST_CARD_FOLD_WAIT_MS = 50;
    /**
     * NEWTUBE(motion): the corner radius and elevation the video box morphs to - the mini card's
     * (mobile_mini_player_overlay.xml) and a feed card thumbnail's. The box used to stay square with
     * a 12dp shadow and swap in one frame for the rounded card on landing (and start square over a
     * rounded thumbnail on open).
     */
    private static final float MORPH_CORNER_DP = 12f;
    private static final float MORPH_ELEVATION_DP = 8f;
    /** Corner radius of the video box in its OWN coordinates: the morph scales it with the box. */
    private float mMorphCornerLocalPx;
    @Nullable
    private android.view.ViewOutlineProvider mVideoAreaOutline;
    private final android.view.ViewOutlineProvider mMorphOutline = new android.view.ViewOutlineProvider() {
        @Override
        public void getOutline(View view, android.graphics.Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mMorphCornerLocalPx);
        }
    };

    /** Compute the video transform (pivot 0,0) that maps the video area onto the mini card. */
    private void computeMorphTarget() {
        float density = getResources().getDisplayMetrics().density;
        float picW = MiniSize.width(this); // a tablet's is bigger, the setting scales it
        float cardW = MiniSize.cardWidth(this);
        float picLeft = MiniSize.pictureOffset(this);
        float margin = 12 * density;
        // Card bottom offset comes from the DESTINATION host: Browse's card floats above its
        // 56dp bottom-nav row, Search/Channel overlay cards sit flush at the content bottom.
        // Both containers already exclude the gesture inset in portrait, so no inset term here.
        MiniPlayerBridge.MiniHost host = MiniPlayerBridge.getMiniHost();
        float bottomNav = host != null ? host.getMiniCardBottomOffsetPx() : 56 * density;

        Rect video = new Rect();
        video.set(0, 0, mVideoArea.getWidth(), mVideoArea.getHeight());
        mContainer.offsetDescendantRectToMyCoords(mVideoArea, video);

        // MEETUBE(issue #13): the card is 16:9 and fits the video in it, so a box of another shape
        // (a square or vertical video's) lands centred on the card, where the card shows its picture.
        float cardH = MiniSize.height(this);
        float scale = video.width() > 0 && video.height() > 0
                ? Math.min(picW / video.width(), cardH / video.height()) : 0.44f;
        // JOYBOY(v910): the card's home is its END corner (layout_constraintEnd + marginEnd), which is the LEFT one in RTL.
        // The landing target used to assume the right corner, so the video flew to the right and the card then appeared at the left.
        boolean rtl = mContainer.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float cardLeft = rtl ? margin : mContainer.getWidth() - margin - cardW;
        // the picture sits picLeft in from the card's START edge (marginStart of the frame): right edge in RTL
        float pictureLeft = rtl ? cardLeft + cardW - picLeft - picW : cardLeft + picLeft;
        float targetX = pictureLeft + (picW - video.width() * scale) / 2f;
        float targetY = mContainer.getHeight() - bottomNav - margin - (cardH + video.height() * scale) / 2f;

        mMorphScaleX = scale;
        mMorphScaleY = scale;
        // mVideoArea's translation is expressed in its parent's (unscaled) coordinates. With a
        // top-left pivot its rendered origin is layoutOrigin + translation, independent of scale.
        mMorphTx = targetX - video.left;
        mMorphTy = targetY - video.top;
    }

    /** Map the watch-page video box onto an arbitrary tapped thumbnail in screen coordinates. */
    private void computeMorphTarget(Rect sourceBounds) {
        Rect video = new Rect(0, 0, mVideoArea.getWidth(), mVideoArea.getHeight());
        mContainer.offsetDescendantRectToMyCoords(mVideoArea, video);

        int[] containerLocation = new int[2];
        mContainer.getLocationOnScreen(containerLocation);
        float sourceLeft = sourceBounds.left - containerLocation[0];
        float sourceTop = sourceBounds.top - containerLocation[1];

        mMorphScaleX = video.width() > 0
                ? (float) sourceBounds.width() / video.width() : 1f;
        mMorphScaleY = video.height() > 0
                ? (float) sourceBounds.height() / video.height() : mMorphScaleX;
        mMorphTx = sourceLeft - video.left;
        mMorphTy = sourceTop - video.top;
    }

    /**
     * MEETUBE(issue #99): the mini card letterboxes the video (issue #13), so a square or vertical
     * video's box lands centred on the card at one scale, as {@link #computeMorphTarget()} does for
     * the home corner, instead of stretched to the card's 16:9. Runs after computeMorphTarget(Rect).
     */
    private void fitMorphInto(Rect card) {
        float scale = Math.min(mMorphScaleX, mMorphScaleY);
        mMorphTx += (card.width() - mVideoArea.getWidth() * scale) / 2f;
        mMorphTy += (card.height() - mVideoArea.getHeight() * scale) / 2f;
        mMorphScaleX = scale;
        mMorphScaleY = scale;
    }

    /** Start with the player hidden, reveal it exactly over the tapped card, then expand. */
    private void startOpenMorph(Rect sourceBounds, long durationMs) {
        if (mContainer == null || mVideoArea == null) {
            return;
        }
        overridePendingTransition(0, 0);
        mContainer.setVisibility(View.INVISIBLE);
        mMorphStartPending = true;
        mContainer.post(() -> {
            mMorphStartPending = false;
            if (isFinishing() || isDestroyed()) {
                return;
            }
            computeMorphTarget(sourceBounds);
            applyMorph(1f);
            mContainer.setVisibility(View.VISIBLE);
            mContainer.postOnAnimation(() -> animateMorph(0f, durationMs, Motion.EMPHASIZED, () -> {
                resetMorph();
                // The launch thumbnail may now yield to the next actual frame. If a new stream is
                // still loading, mStillAwaitReady keeps it up until STATE_READY first.
                if (mVideoStill != null && mVideoStill.getVisibility() == View.VISIBLE) {
                    mStillAwaitFrame = true;
                }
            }));
        });
    }

    /** Apply the morph at fraction f (0 = fullscreen player, 1 = sitting on the mini card). */
    private void applyMorph(float f) {
        mMorphFraction = f;
        updateHdrOutput(); // a morphing box shows the texture (#35)
        mVideoArea.setPivotX(0f);
        mVideoArea.setPivotY(0f);
        float sx = 1f + (mMorphScaleX - 1f) * f;
        float sy = 1f + (mMorphScaleY - 1f) * f;
        mVideoArea.setScaleX(sx);
        mVideoArea.setScaleY(sy);
        mVideoArea.setTranslationX(mMorphTx * f);
        mVideoArea.setTranslationY(mMorphTy * f);
        // The content column is the next LinearLayout child and would otherwise be drawn over the
        // moving video. Any positive Z keeps the live TextureView visually on top during the morph.
        float density = getResources().getDisplayMetrics().density;
        mVideoArea.setTranslationZ(f > 0f ? MORPH_ELEVATION_DP * density : 0f);
        // NEWTUBE(haptics): the settle spring lands a few px past the card (f a little over 1):
        // the box moves and shrinks on with it, but corners and fades stop at their card values.
        float settled = Math.min(1f, f);
        applyMorphCorners(settled, Math.min(sx, sy), density);

        // Remove labels/cards early so they do not ghost over Browse, then fade the solid watch
        // background more slowly. This reads as a black sheet becoming transparent while the live
        // video remains fully opaque above it.
        float contentAlpha = Math.max(0f, 1f - f * 5f);
        if (mWatchContent != null) {
            mWatchContent.setAlpha(contentAlpha);
        }
        float backdrop = morphBackdropAlpha(settled, mMorphOverOwnBackdrop); // NEWTUBE(no-host-minimize)
        if (setMorphBlur(settled)) {
            // MEETUBE(minimize blur): Home shows through blurred, not under a black sheet - the black
            // goes in the first quarter, a light dim stays and clears with the blur.
            backdrop = Math.max(0f, 1f - 4f * settled) * 0.65f + 0.35f * (1f - settled);
        }
        if (mWatchScroll != null && mWatchScroll.getBackground() != null) {
            int backdropAlpha = Math.round(255f * backdrop);
            mWatchScroll.getBackground().mutate().setAlpha(backdropAlpha);
        }
        setWindowBackdropAlpha(backdrop);
        if (mControlsRoot != null && mControlsRoot.getVisibility() == View.VISIBLE) {
            mControlsRoot.setAlpha(contentAlpha);
        }
        if (mTimeBar != null) {
            mTimeBar.setAlpha(contentAlpha); // outside the controls since NEWTUBE(seek bar): fade it too
        }
        if (mWatchFab != null) {
            mWatchFab.setAlpha(contentAlpha); // MEETUBE(fullscreen): the floating button goes with the page
        }
        if (mCommentsPanel != null) {
            mCommentsPanel.setMorphAlpha(contentAlpha);
        }
        for (CommentsPanelLayout panel : mSidePanels) {
            panel.setAlpha(contentAlpha);
        }
        LyricsSheet.setMorphAlpha(mLyricsSheet, contentAlpha); // MEETUBE(lyrics): fades like the comments
        LyricsSheet.setMorphAlpha(mTranscriptSheet, contentAlpha);
    }

    /**
     * NEWTUBE(no-host-minimize): backdrop opacity at morph fraction {@code f}. It fades with the
     * morph to uncover the live screen beneath, except when there is none of ours beneath (see
     * {@link #mMorphOverOwnBackdrop}) - then it stays opaque rather than uncover the launcher.
     */
    static float morphBackdropAlpha(float f, boolean overOwnBackdrop) {
        return overOwnBackdrop ? 1f : 1f - f;
    }

    /**
     * Fade the edge-to-edge window backdrop with the view morph. On current Android versions the
     * decor drawable also paints the manually inset status/navigation-bar bands, so leaving it
     * opaque would hide the Activity below even after all watch-page children became transparent.
     */
    private static final float MORPH_BLUR_DP = 24f;

    /**
     * MEETUBE(minimize blur): blur what is behind the window (Home) by the morph's progress, full at
     * the start, none on the card; off (and false) at rest, over our own backdrop, or where the
     * system has no cross-window blur (before Android 12, battery saver, some devices).
     *
     * <p>The window's background blur, not FLAG_BLUR_BEHIND: a blur-behind radius is a window
     * attribute, so changing it every frame re-laid out the window every frame, and the screen
     * showed the shrinking video a frame late now and then - it jumped back and forth (recorded on
     * a vivo V2404). The background blur is drawn with the window's own frames.</p>
     */
    private boolean setMorphBlur(float f) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false;
        }
        boolean on = f > 0f && f < 1f && !mMorphOverOwnBackdrop && getWindowManager().isCrossWindowBlurEnabled();
        getWindow().setBackgroundBlurRadius(on
                ? Math.round(MORPH_BLUR_DP * getResources().getDisplayMetrics().density * (1f - f)) : 0);
        return on;
    }

    private void setWindowBackdropAlpha(float alpha) {
        Drawable backdrop = getWindow().getDecorView().getBackground();
        if (backdrop instanceof android.graphics.drawable.LayerDrawable) {
            // the decor's background blur (setMorphBlur) under the window's own background: only
            // the background fades, the blur goes by its radius
            android.graphics.drawable.LayerDrawable layers = (android.graphics.drawable.LayerDrawable) backdrop;
            backdrop = layers.getDrawable(layers.getNumberOfLayers() - 1);
        }
        if (backdrop != null) {
            int drawableAlpha = Math.round(255f * Math.max(0f, Math.min(1f, alpha)));
            backdrop.mutate().setAlpha(drawableAlpha);
        }
        View content = findViewById(android.R.id.content);
        if (content != null && content.getBackground() != null) { // MEETUBE(ambient): the status bar glow
            content.getBackground().setAlpha(Math.round(255f * Math.max(0f, Math.min(1f, alpha))));
        }
    }

    /**
     * NEWTUBE(motion): round the video box as it shrinks toward a card - 12dp ON SCREEN at the card
     * end, square at full size. The outline lives in the box's own coordinates and the morph scales
     * it, so the local radius is divided by the scale. The clip covers the texture, the still and the
     * letterbox alike, and the elevation shadow follows it.
     */
    private void applyMorphCorners(float f, float scale, float density) {
        if (f <= 0f) {
            restoreVideoAreaOutline();
            return;
        }
        mMorphCornerLocalPx = scale > 0f ? MORPH_CORNER_DP * density * f / scale : 0f;
        if (mVideoArea.getOutlineProvider() != mMorphOutline) {
            mVideoAreaOutline = mVideoArea.getOutlineProvider();
            mVideoArea.setOutlineProvider(mMorphOutline);
            mVideoArea.setClipToOutline(true);
        }
        mVideoArea.invalidateOutline();
    }

    private void restoreVideoAreaOutline() {
        if (mVideoArea.getOutlineProvider() == mMorphOutline) {
            mVideoArea.setClipToOutline(false);
            mVideoArea.setOutlineProvider(mVideoAreaOutline != null
                    ? mVideoAreaOutline : android.view.ViewOutlineProvider.BACKGROUND);
            mVideoAreaOutline = null;
        }
        mMorphCornerLocalPx = 0f;
    }

    private void resetMorph() {
        if (mMorphAnimator != null) {
            mMorphAnimator.cancel();
            mMorphAnimator = null;
        }
        // NEWTUBE(haptics): a drag cut short without its release (onStop mid-drag) must not keep
        // its gap spring moving the box, nor leave the next drag thinking it already began.
        endMagnetDrag();
        // Drag cancelled (or undone by the in-PiP guard): this window owns the video again, so
        // re-arm the standing auto-enter flag the drag turned off.
        if (mDismissDragActive) {
            mDismissDragActive = false;
            updatePipActions();
        }
        mMorphFraction = 0f;
        updateHdrOutput();
        mMorphOverOwnBackdrop = false;
        mVideoArea.setScaleX(1f);
        mVideoArea.setScaleY(1f);
        mVideoArea.setTranslationX(0f);
        mVideoArea.setTranslationY(0f);
        mVideoArea.setTranslationZ(0f);
        restoreVideoAreaOutline();
        if (mWatchContent != null) {
            mWatchContent.setAlpha(1f);
        }
        if (mWatchScroll != null && mWatchScroll.getBackground() != null) {
            mWatchScroll.getBackground().mutate().setAlpha(255);
        }
        setWindowBackdropAlpha(1f);
        setMorphBlur(0f);
        if (mControlsRoot != null) {
            mControlsRoot.setAlpha(mControlsVisible ? 1f : 0f);
        }
        if (mTimeBar != null) {
            mTimeBar.setAlpha(1f);
        }
        if (mWatchFab != null) {
            mWatchFab.setAlpha(1f); // MEETUBE(fullscreen): the floating button goes with the page
        }
        if (mCommentsPanel != null) {
            mCommentsPanel.setMorphAlpha(1f);
        }
        for (CommentsPanelLayout panel : mSidePanels) {
            panel.setAlpha(1f);
        }
        LyricsSheet.setMorphAlpha(mLyricsSheet, 1f); // MEETUBE(lyrics)
        LyricsSheet.setMorphAlpha(mTranscriptSheet, 1f);
    }

    private void animateMorph(float to, long durationMs, @Nullable Runnable endAction) {
        animateMorph(to, durationMs, new DecelerateInterpolator(), endAction);
    }

    /**
     * NEWTUBE(motion): the end action runs only when the morph lands. It used to run on cancel too
     * (onAnimationEnd follows onAnimationCancel), so interrupting a settle could still dock or
     * finish the player.
     */
    private void animateMorph(float to, long durationMs, android.view.animation.Interpolator interpolator,
            @Nullable Runnable endAction) {
        if (mMorphAnimator != null) {
            mMorphAnimator.cancel();
        }
        ValueAnimator animator = ValueAnimator.ofFloat(mMorphFraction, to);
        mMorphAnimator = animator;
        animator.setDuration(durationMs);
        animator.setInterpolator(interpolator);
        animator.addUpdateListener(a -> applyMorph((float) a.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mMorphAnimator == animator) {
                    mMorphAnimator = null;
                }
                if (!mCancelled && endAction != null) {
                    endAction.run();
                }
            }
        });
        animator.start();
    }

    private boolean canStartDismissDrag() {
        return !mScrubbing && mPlayer != null && mMorphAnimator == null
                && !(mShorts != null && mShorts.isActive()); // MEETUBE(shorts): no mini card
    }

    private void onDismissDrag(float dy) {
        if (!mMagnetDragging) {
            if (dy <= 0f) {
                return;
            }
            mMagnetDragging = true;
            beginMinimizeMorph();
            mDragTravelPx = dragTravelFor(mContainer.getDownRawY());
            minimizeMagnet().start();
        }
        minimizeMagnet().move(dy);
    }

    /** NEWTUBE(haptics): the magnet moves the morph; its position is finger travel in px. */
    private MagneticDrag minimizeMagnet() {
        if (mMinimizeMagnet == null) {
            mMinimizeMagnet = new MagneticDrag(mContainer, MINIMIZE_PULL,
                    position -> applyMorph(Math.max(0f, Math.min(1f, position / mDragTravelPx))));
        }
        return mMinimizeMagnet;
    }

    /** NEWTUBE(haptics): the finger let go (or was taken away): was the drag past its click? */
    private boolean endMagnetDrag() {
        if (!mMagnetDragging) {
            return false;
        }
        mMagnetDragging = false;
        MagneticDrag magnet = minimizeMagnet();
        magnet.finish();
        return magnet.isDetached();
    }

    /**
     * NEWTUBE(motion): finger travel (px) from full size to the card for the point the finger
     * grabbed to stay under it: at fraction f that point sits at videoTop + f * ty + v * h * s(f),
     * linear in f, so one travel length does it for the whole drag. The morph used to advance a
     * fixed 60% of the screen per unit, and the video slid away from the finger - most for a grab
     * low on the video. Sideways the video follows its path to the card, like YouTube's.
     */
    private float dragTravelFor(float downRawY) {
        float fallback = Math.max(1, mContainer.getHeight()) * 0.6f;
        float height = mVideoArea.getHeight();
        if (height <= 0f) {
            return fallback;
        }
        int[] location = new int[2];
        mVideoArea.getLocationOnScreen(location);
        float grabbed = Math.max(0f, Math.min(1f, (downRawY - location[1]) / height));
        float travel = mMorphTy + grabbed * height * (mMorphScaleY - 1f);
        // A grab the finger cannot keep (low on a fullscreen box, whose card end sits above it)
        // still needs a real distance to minimize: never under a third of the screen.
        return Math.max(travel, Math.max(1, mContainer.getHeight()) * 0.33f);
    }

    private void onDismissDragCancelled() {
        endMagnetDrag();
        settleMorph(0f, 0f, this::resetMorph);
    }

    /**
     * NEWTUBE(motion): finish a released drag at the finger's own speed - a decelerating settle
     * whose first frames carry on at the release velocity (DecelerateInterpolator starts at twice
     * its average speed), so a flick lands fast and a slow release eases in. Bounded, so a
     * near-still release still moves promptly and a fast one never snaps in a frame or two.
     *
     * <p>NEWTUBE(haptics): onto the card, a spring instead, so it lands with a small settle. It is
     * launched at the speed that curve would have started with: a spring started at the finger's
     * own speed began from rest after a slow release and hung back behind the finger for its first
     * frames (owner, on the Pixel). Back to full size stays the curve - nothing to land there.</p>
     */
    private void settleMorph(float to, float yVelocity, Runnable endAction) {
        float distance = Math.abs(to - mMorphFraction);
        float speedToward = to > mMorphFraction ? yVelocity : -yVelocity; // px/s, >0 = the way we go
        long durationMs;
        if (speedToward > 300f) {
            durationMs = Math.round(2000f * distance * mDragTravelPx / speedToward);
        } else {
            durationMs = Math.round(60f + 150f * distance);
        }
        durationMs = Math.max(SETTLE_MIN_MS, Math.min(SETTLE_MAX_MS, durationMs));
        if (to <= mMorphFraction) {
            animateMorph(to, durationMs, new android.view.animation.DecelerateInterpolator(), endAction);
            return;
        }
        float travel = Math.max(1f, mDragTravelPx);
        // The curve's own start speed, or the finger's if faster - but never faster than the curve
        // ever started (its shortest, 90 ms): an unbounded flick in a short window (landscape,
        // tablet) sprang far enough past the card to shrink the box through zero. Capped, the
        // overshoot stays under 1.5% of the travel for any window.
        float launch = Math.max(yVelocity / travel, 2f * distance * 1000f / durationMs); // fraction/s
        launch = Math.min(launch, 2f * distance * 1000f / SETTLE_MIN_MS);
        Motion.Spring spring = new Motion.Spring(mMorphFraction, to, launch,
                SETTLE_LAND_STIFFNESS, SETTLE_LAND_DAMPING, 1f / travel);
        animateMorph(to, spring.durationMs, spring, endAction);
    }

    /** Start a minimize morph: the drag's first move, a back gesture or Back itself. */
    private void beginMinimizeMorph() {
        computeMorphTarget(); // anchor the corner path once per morph
        // NEWTUBE(no-host-minimize): nothing of ours beneath - see the field doc.
        mMorphOverOwnBackdrop = MiniPlayerBridge.getMiniHost() == null;
        // A minimize means "dock it inside the app", never "PiP it". Disarm auto-enter for the whole
        // morph so an overlapping home gesture cannot pin the task (see the field doc).
        mDismissDragActive = true;
        updatePipActions();
    }

    private void onDismissDragReleased(float dy, float yVelocity) {
        if (mMagnetDragging) {
            minimizeMagnet().move(dy); // where the finger lifted, which a last MOVE may not have said
        }
        boolean dismiss;
        if (endMagnetDrag()) {
            // Past the click: it goes, unless flicked back up - the finger changed its mind.
            dismiss = yVelocity >= -1200f;
        } else {
            // Still stuck: only a real flick down takes it, with the click it skipped (a flung
            // notification clicks the same way).
            dismiss = yVelocity > MINIMIZE_FLICK_DP * getResources().getDisplayMetrics().density;
            if (dismiss) {
                Haptics.threshold(mContainer, true);
            }
        }

        if (!dismiss) {
            settleMorph(0f, yVelocity, this::resetMorph);
            return;
        }

        if (mPlayer == null) {
            // Error screen (nothing to dock): old close-by-drag behavior.
            animateMorph(1f, 150, () -> {
                if (mPresenter != null) {
                    mPresenter.onFinish();
                }
                finish();
                overridePendingTransition(0, 0);
            });
            return;
        }

        // The destination is already visible through our translucent window, so finish the live
        // video motion in this Activity first. Only then reorder Browse and hand it the texture;
        // its mini card occupies the same rectangle, making the Activity switch a visual no-op.
        // If the finger already dragged to the endpoint, do not run a 150ms no-op animator after
        // release: that pause made the eventual surface handoff look like a refresh/stutter.
        float remaining = Math.max(0f, 1f - mMorphFraction);
        if (remaining < 0.001f) {
            applyMorph(1f);
            minimizeByDrag();
        } else {
            settleMorph(1f, yVelocity, this::minimizeByDrag);
        }
    }

    /**
     * Swipe-down now MINIMIZES like the YouTube app (playback continues in the Browse mini
     * card) instead of closing. This activity stays alive behind Browse - it still owns the
     * player. Called once the release animation has landed on the mini-card rectangle.
     */
    private void minimizeByDrag() {
        com.newtube.mobile.ui.common.PlayerTrace.mark("MINIMIZE by drag (mini player)"); // JOYBOY(v952)
        if (MiniSize.type(this) == MiniSize.DISABLED) { // MEETUBE(issue #188): no mini player, minimizing closes
            if (mPresenter != null) {
                mPresenter.onFinish();
            }
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        // WHOLE-APP-IN-PIP GUARD: the host reorder below launches Browse into THIS activity's task.
        // While the task is pinned that puts Browse on top of the PiP window, so the system renders
        // the entire app - feed, tab bar and all - shrunk into the corner (and removeTop then leaves
        // Browse alone in the pinned task). The drag settles on an animator, so a minimize started
        // just before a home gesture still lands here ~150ms after PiP entry. Nothing to minimize
        // into once we are already a small window: undo the morph and stay put.
        if (mIsInPip || mPipEnterPending) {
            logPip("minimize-blocked reason=in-pip");
            resetMorph();
            return;
        }

        // JOYBOY(v939): مصدر مش يوتيوب (متصفح/واتساب/ملف محلي/رابط مباشر) — التصغير يروح PiP بتاع النظام فوق التطبيق
        // الأصلي بدل ما يدوّر على مضيف مصغّر بتاع MeeTube ويقع على يوتيوب. يوتيوب بيكمل على الدوك الأصلي بتاعه.
        if (JoyboyPlayerHost.onMinimize(this)) {
            // JOYBOY(v941): الفيديو بيوقف أول ما يصغّر، ولو النظام رفض الـPiP بيتقفل ويرجع للمصدر بدل ما يقع على دوك يوتيوب.
            setPlayWhenReady(false);
            enterPipMode();
            if (mPipEnterPending) {
                return;
            }
            if (mPresenter != null) {
                mPresenter.onFinish();
            }
            finish();
            overridePendingTransition(0, 0);
            return;
        }

        if (!prepareMiniPlayerHandoff(false)) {
            return;
        }

        // Return to the screen the video was opened from (Search, Channel, Home...) - the
        // last-resumed mini host is exactly the Activity visible through this translucent
        // window during the drag. Falling back to Home only when no host exists (deep link).
        MiniPlayerBridge.MiniHost host = MiniPlayerBridge.getMiniHost();
        final Class<?> hostView = host != null ? host.getMiniHostViewClass() : BrowseView.class;

        Runnable showHost = () -> {
            // prepareMiniHostForHandoff may run this a frame or more later, so re-check: the same
            // reorder-into-a-pinned-task hazard the guard above covers applies at THIS point too.
            if (isFinishing() || isDestroyed() || mIsInPip || mPipEnterPending) {
                return;
            }
            getViewManager().startView(hostView);
            if (host != null) {
                overridePendingTransition(0, 0);
            }
            // NEWTUBE(no-host-minimize): with no host Home is a NEW window over our dark backdrop,
            // not a reorder of one already visible - keep its quick fade-in instead of a cut.
            // Only AFTER the reorder launch: a docked player leaves the logical back stack (see
            // prepareMiniPlayerHandoff). Removing it first would make the host the logical top
            // and startView's "already top" guard would skip the reorder entirely, stranding the
            // transparent player window above the host (observed: taps fell through to nothing).
            getViewManager().removeTop(this);
        };

        boolean prepared = MiniPlayerBridge.prepareMiniHostForHandoff(showHost);
        if (BuildConfig.DEBUG) {
            android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                    "mini minimize host=" + (host != null ? host.getClass().getSimpleName() : "none")
                            + " prepared=" + prepared);
        }
        if (!prepared) {
            // Cold/deep-link path: no retained host instance exists to pre-render.
            showHost.run();
        }
    }

    /** Channel navigation completed: background this player as a live in-app mini session. */
    boolean minimizeForNavigation() {
        // Same hazard as minimizeByDrag: a channel route that resolves after PiP entry would dock
        // the player behind an Activity living in the pinned task. Let the route open normally
        // (own task) and leave the PiP window owning the video.
        if (mIsInPip || mPipEnterPending) {
            logPip("minimize-blocked reason=in-pip-navigation");
            return false;
        }

        if (!prepareMiniPlayerHandoff(true)) {
            return false;
        }
        // The channel is already launched and on top here, so the docked player can leave the
        // logical back stack immediately (the drag path defers this until after its reorder).
        getViewManager().removeTop(this);
        return true;
    }

    /** Capture/freeze the current frame and make the session texture available to a mini host. */
    private boolean prepareMiniPlayerHandoff(boolean captureFullSizeStill) {
        if (mPlayer == null) {
            return false;
        }
        // MEETUBE(lyrics): the panel goes with the watch page. Left open, its window outlived the
        // minimize and resetMorph faded it back in over the mini player (issue #30).
        closeSidePanelsImmediately();
        if (mLyricsSheet != null) {
            mLyricsSheet.dismiss();
            mLyricsSheet = null;
        }

        // Freeze the current frame over the morphing video box, then detach the TextureView so
        // the session texture is free for the Browse card the moment it resumes (a SurfaceTexture
        // can feed only one GL consumer at a time). The codec keeps decoding into the briefly
        // consumer-less texture - audio and playback never hiccup - and the card picks the live
        // stream up without any surface change on the player.
        if (mVideoTexture != null && mVideoTexture.isAvailable()) {
            // A completed dismiss only needs a mini-card-sized bridge frame. Reading the entire
            // video TextureView back to a Bitmap on ACTION_UP forces a much larger GPU->CPU copy
            // on the UI thread and was the remaining hitch at the end of the gesture. Channel
            // navigation still starts its destination animation at full width, so retain the
            // full-size capture for that path.
            Bitmap frame;
            int width = Math.max(1, MiniSize.width(this));
            int height = Math.max(1, MiniSize.height(this));
            if (mHdrOutput && mHdrSurface != null) { // the texture holds an old frame (#35)
                frame = captureFullSizeStill
                        ? copyHdrFrame(Math.max(1, mHdrSurface.getWidth()), Math.max(1, mHdrSurface.getHeight()))
                        : copyHdrFrame(width, height);
            } else if (captureFullSizeStill) {
                frame = mVideoTexture.getBitmap();
            } else {
                frame = mVideoTexture.getBitmap(width, height);
            }
            if (frame != null) {
                showHandoffStill(frame);
                mStillAwaitFrame = false; // keep it until the expand path re-arms the lift
                // The destination paints this same frame until its TextureView receives the first
                // live update, avoiding a black/new-frame discontinuity at the Activity switch.
                MiniPlayerBridge.setMiniEntryStill(frame);
            }
        }
        detachVideoTexture();
        if (BuildConfig.DEBUG) {
            android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                    "mini handoff detach t=" + android.os.SystemClock.uptimeMillis());
        }
        MiniPlayerBridge.activate(this);
        updateHdrOutput(); // the card shows the session texture (#35)
        // The card shows this player's live frames at card size: cap NEW chunks to that window.
        // Card px use the app density on purpose - it is the density the card is laid out with.
        mExoPlayerController.setSmallWindowViewport("mini",
                MiniSize.width(this), MiniSize.height(this));
        // A deep-linked open arms ViewManager's player-only mode ("watch, then back to the
        // launcher"). Minimizing into an in-app host means the user is now USING the app, so
        // drop the flag - a stale one makes startParentView "exit to Home" on the next back
        // press (observed: back from a channel with a docked card sent the app to the launcher).
        getViewManager().enablePlayerOnlyMode(false);
        // NOTE: the docked player also leaves ViewManager's logical stack (so a host's
        // back-press resolves its parent to the screen BELOW the player instead of expanding
        // the video), but each caller removes it at its own safe point - see minimizeByDrag
        // (after the reorder launch) and minimizeForNavigation. The next onResume's addTop()
        // re-inserts it when the card expands back to full screen.
        // Launching Browse over ourselves delivers onUserLeaveHint to this activity, and the
        // isNewViewPending() guard there is NOT reliable for this hand-off (observed: minimize
        // put the player into a system PiP window floating over the mini card). Suppress
        // explicitly; cleared on the next onResume. The param push disarms the Android 12+
        // auto-enter flag too - same failure mode, system-initiated instead of leave-hint.
        mSuppressAutoPip = true;
        updatePipActions();
        return true;
    }

    /** X tapped on the Browse mini bar: stop playback and quietly retire this hidden activity. */
    void closeFromMiniPlayer() {
        if (mPresenter != null) {
            mPresenter.onFinish();
        }
        // finishReally() (not finish()): Browse is already in the foreground; finish()'s root-screen
        // branch would send the whole app to the background when the player was deep-linked (no
        // parent view), yanking Browse away mid-scroll.
        finishReally();
    }

    /** Live player accessor for the mini bar (package-private, see MiniPlayerBridge). */
    ExoPlayer getSharedPlayer() {
        return mPlayer;
    }

    // ---------------------------------------------------------------------------------
    // PlayerUI - touch surface implemented (drives the custom overlay above).
    // ---------------------------------------------------------------------------------

    @Override
    public void showOverlay(boolean show) {
        if (show) {
            showControlsInternal(true);
        } else if (!isHoldingSeekBar() && !isPlayerSheetShown()) {
            // NEWTUBE(seek bar): the shared UI timer (PlayerUIController's auto-hide) fires on its
            // own clock; it used to pull the controls - and now the seek bar with its drag - out
            // from under a finger that was still dragging. NEWTUBE(sheet-landscape): nor behind
            // an open sheet - see showPlayerSheet.
            hideControls();
        }
    }

    @Override
    public boolean isOverlayShown() {
        return mControlsVisible;
    }

    @Override
    public void showControls(boolean show) {
        showOverlay(show);
    }

    @Override
    public boolean isControlsShown() {
        return mControlsVisible;
    }

    @Override
    public void setTitle(String title) {
        if (mTitleView != null) {
            mTitleView.setText(title);
        }
    }

    /**
     * NEWTUBE(motion): while the spinner turns, the play/pause glyph steps aside (YouTube shows one
     * or the other). Not while a playback notice is up: then the play button is the retry.
     */
    private void syncPlayPauseWithSpinner() {
        if (mPlayPauseButton == null) {
            return;
        }
        boolean noticeUp = mNoticeView != null && mNoticeView.getVisibility() == View.VISIBLE;
        boolean hide = mSpinnerShown && !noticeUp;
        mPlayPauseButton.setClickable(!hide);
        float target = hide ? 0f : 1f;
        mPlayPauseButton.animate().cancel();
        if (mPlayPauseButton.getAlpha() != target) {
            mPlayPauseButton.animate().alpha(target)
                    .setDuration(hide ? Motion.FADE_OUT_MS : Motion.FADE_IN_MS)
                    .setInterpolator(Motion.STANDARD).start();
        }
    }

    @Override
    public void showPlaybackNotice(String message) {
        if (mNoticeView == null) {
            return;
        }

        boolean show = message != null && !message.isEmpty();
        mNoticeView.setText(show ? message : null);
        mNoticeView.setVisibility(show ? View.VISIBLE : View.GONE);
        syncPlayPauseWithSpinner(); // with a notice up, play is the retry: never hide it
        if (show) {
            // The video box is showing a frozen frame (or nothing) behind this - the play button is
            // the way out (it retries), so make sure the controls are up. They stay: the auto-hide
            // only runs during steady playback.
            showControls(true);
        }
    }

    /**
     * MEETUBE(up-next): hold autoplay behind the countdown card - only while someone is looking at
     * this player (resumed, not PiP, not Shorts); otherwise the next video opens at once. A short
     * that ended (Loop shorts off, issue #134) slides on to the next one, as a swipe up does.
     */
    @Override
    public boolean showNextCountdown(Video next, Runnable playNext) {
        boolean watched = !isInPictureInPictureMode()
                && getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED);
        if (mShorts != null && mShorts.isActive()) {
            return watched && mShorts.pageNext();
        }
        if (mNextCountdown == null || !watched) {
            return false;
        }
        showControls(false);
        mNextCountdown.show(next != null ? next.getTitle() : null, playNext);
        return true;
    }

    @Override
    public boolean showSkipSegment(String category, Runnable skip, long timeoutMs) {
        if (mSkipSegmentPill == null || isInPictureInPictureMode() || (mShorts != null && mShorts.isActive())) {
            return false;
        }
        mSkipSegmentPill.setText(R.string.mobile_player_skip_segment);
        mSkipSegmentPill.setOnClickListener(v -> skip.run());
        mSkipSegmentPill.setVisibility(View.VISIBLE);
        mSkipSegmentPill.removeCallbacks(mHideSkipSegment);
        mSkipSegmentPill.postDelayed(mHideSkipSegment, Math.max(timeoutMs, 3_000));
        return true;
    }

    @Override
    public void hideSkipSegment() {
        if (mSkipSegmentPill != null) {
            mSkipSegmentPill.removeCallbacks(mHideSkipSegment);
            mSkipSegmentPill.setVisibility(View.GONE);
        }
    }

    /** NEWTUBE(motion): the buffering spinner's state, see showProgressBar. */
    private boolean mSpinnerShown;
    /**
     * MEETUBE(shorts): a short opening shows its still, like YouTube's, not a spinner over it; the
     * spinner (white there) comes only for a load or stall longer than this.
     */
    private static final long SHORTS_SPINNER_DELAY_MS = 1000;
    private final Runnable mShowShortsSpinner = () -> setSpinnerShown(true);

    @Override
    public void showProgressBar(boolean show) {
        if (mProgressBar != null) {
            mProgressBar.removeCallbacks(mShowShortsSpinner);
            if (show && !mSpinnerShown && isShortsMode()) {
                mProgressBar.postDelayed(mShowShortsSpinner, SHORTS_SPINNER_DELAY_MS);
            } else {
                setSpinnerShown(show);
            }
        }
        // Fresh installs: while the one-time session setup is still running, tell the user why
        // this first load is longer than usual. Never shows again once any fetch succeeded.
        if (mSetupHint != null) {
            mSetupHint.setVisibility(show && !SessionWarmup.isWarm() ? View.VISIBLE : View.GONE);
        }
    }

    private void setSpinnerShown(boolean show) {
        if (show != mSpinnerShown) {
            // NEWTUBE(motion): fade in and out instead of popping, and take the play/pause glyph's
            // place rather than drawing over it (it spun around the pause bars on every open).
            mSpinnerShown = show;
            mProgressBar.animate().cancel();
            if (show) {
                mProgressBar.setAlpha(0f);
                mProgressBar.setVisibility(View.VISIBLE);
                mProgressBar.animate().alpha(1f).setDuration(Motion.FADE_IN_MS)
                        .setInterpolator(Motion.STANDARD).start();
            } else {
                mProgressBar.animate().alpha(0f).setDuration(Motion.FADE_OUT_MS)
                        .setInterpolator(Motion.STANDARD)
                        .withEndAction(() -> mProgressBar.setVisibility(View.GONE)).start();
            }
            syncPlayPauseWithSpinner();
        }
    }

    // ---------------------------------------------------------------------------------
    // PlayerUI - Suggestions (related / up-next list in the portrait watch page).
    //
    // The SuggestionsController feeds this. On each new video it calls clearSuggestions() then
    // updateSuggestions(group) once per row (chapters/queue/related). We flatten all non-chapter
    // rows into a single related list keyed by group id (LinkedHashMap preserves delivery order),
    // so continuations (ACTION_APPEND, same id) append to that row. isSuggestionsShown() returns
    // false so the controller always (re)populates suggestions for the current video (the mobile
    // watch page rebuilds them per video rather than preserving a TV-style focused row).
    // ---------------------------------------------------------------------------------

    @Override
    public void updateSuggestions(VideoGroup group) {
        if (group == null || group.isEmpty()) {
            return;
        }

        // Chapter rows aren't related videos: route them to the YouTube-style "Chapters" entry
        // (titled list + tap-to-seek) instead of the Up-next list.
        if (group.isChapters()) {
            runOnUiThread(() -> setChapters(group.getVideos()));
            return;
        }

        runOnUiThread(() -> {
            int id = group.getId();
            List<Video> incoming = group.getVideos();

            switch (group.getAction()) {
                case VideoGroup.ACTION_REPLACE:
                    mSuggestionVideos.put(id, new ArrayList<>(incoming));
                    mSuggestionGroups.put(id, group);
                    break;
                case VideoGroup.ACTION_REMOVE:
                case VideoGroup.ACTION_REMOVE_AUTHOR: {
                    List<Video> existing = mSuggestionVideos.get(id);
                    if (existing != null) {
                        existing.removeAll(incoming);
                    }
                    break;
                }
                case VideoGroup.ACTION_SYNC: {
                    List<Video> existing = mSuggestionVideos.get(id);
                    if (existing == null) {
                        mSuggestionVideos.put(id, new ArrayList<>(incoming));
                        mSuggestionGroups.put(id, group);
                    } else {
                        for (Video v : incoming) {
                            int idx = existing.indexOf(v);
                            if (idx >= 0) {
                                existing.set(idx, v);
                            }
                        }
                    }
                    break;
                }
                case VideoGroup.ACTION_APPEND:
                default: {
                    List<Video> existing = mSuggestionVideos.get(id);
                    if (existing == null) {
                        mSuggestionVideos.put(id, new ArrayList<>(incoming));
                    } else {
                        for (Video v : incoming) {
                            if (!existing.contains(v)) {
                                existing.add(v);
                            }
                        }
                    }
                    mSuggestionGroups.put(id, group);
                    break;
                }
            }

            rebuildRelatedList();
        });
    }

    @Override
    public void removeSuggestions(VideoGroup group) {
        if (group == null) {
            return;
        }

        runOnUiThread(() -> {
            mSuggestionVideos.remove(group.getId());
            mSuggestionGroups.remove(group.getId());
            rebuildRelatedList();
        });
    }

    @Override
    public int getSuggestionsIndex(VideoGroup group) {
        if (group == null) {
            return -1;
        }

        int id = group.getId();
        int i = 0;
        for (Integer key : mSuggestionVideos.keySet()) {
            if (key != null && key == id) {
                return i;
            }
            i++;
        }
        return -1;
    }

    @Override
    public VideoGroup getSuggestionsByIndex(int index) {
        // Callers null-check this (see SuggestionsController.focusCurrentChapter).
        if (mRelatedVideos.isEmpty() || index < 0) {
            return null;
        }

        int i = 0;
        for (Integer key : mSuggestionVideos.keySet()) {
            if (i == index) {
                List<Video> vids = mSuggestionVideos.get(key);
                return (vids == null || vids.isEmpty()) ? null : VideoGroup.from(vids);
            }
            i++;
        }
        return null;
    }

    @Override
    public void focusSuggestedItem(int index) {
        // No TV-style row focus on touch; the related list is a plain scroll list.
    }

    @Override
    public void focusSuggestedItem(Video video) {
        // No TV-style row focus on touch.
    }

    @Override
    public void resetSuggestedPosition() {
        // No TV-style row focus on touch.
    }

    @Override
    public boolean isSuggestionsEmpty() {
        // Queue rows are suggestions too - they're just rendered in their own card. Reporting
        // "empty" while a playlist is on screen would invite the controller to refetch them.
        return mRelatedVideos.isEmpty() && mQueueVideos.isEmpty();
    }

    @Override
    public void clearSuggestions() {
        runOnUiThread(() -> {
            mRelatedRenderGate.cancelPending();
            mSuggestionVideos.clear();
            mSuggestionGroups.clear();
            mRelatedVideos.clear();
            mQueueVideos.clear();
            mLastPagedVideo = null;
            mRelatedWindow = RELATED_WINDOW_INITIAL; // new video: back to one screenful of thumbnails
            resetRelatedChips();
            setChapters(null);
            if (mRelatedAdapter != null) {
                mRelatedAdapter.submitList(new ArrayList<>());
            }
            if (mMoreVideos != null) {
                mMoreVideos.setVideos(mRelatedVideos);
                updateMoreVideosHint();
            }
            // Hide the queue card until the new video's rows say it's still in one. mQueueExpanded
            // is deliberately NOT reset: staying open across an in-queue advance is the point.
            if (mQueueCard != null) {
                mQueueCard.setVisibility(View.GONE);
            }
            if (mQueueAdapter != null) {
                mQueueAdapter.submitList(new ArrayList<>());
            }
            if (mWatchRelatedLabel != null) {
                // NEWTUBE(watch-jump): held over the skeleton instead of inserted above it later.
                mWatchRelatedLabel.setText(R.string.mobile_watch_related);
                mWatchRelatedLabel.setVisibility(View.VISIBLE);
            }
            // A new video is loading: the video itself starts first (by design), so show the
            // pulsing "up next" skeleton until the related feed lands.
            showRelatedSkeleton();
        });
    }

    /**
     * LOADING SKELETON: pulsing placeholder rows under "Up next" while the related feed loads.
     * The video deliberately starts before the page content (all fetches are parallelized), so the
     * skeleton communicates "this part is on its way" instead of leaving dead space. Hidden the
     * moment real rows land ({@link #rebuildRelatedList}) or after a safety timeout (no related).
     */
    private static final long SKELETON_TIMEOUT_MS = 10_000;
    private final Runnable mHideSkeletonTimeout = this::onRelatedSkeletonTimeout;

    /**
     * The related feed never came. Collapse what was held for it; and with no network (typically a
     * download played offline) say so once instead of an empty "Up next" and "–" like counts that
     * look like a broken page (NEWTUBE(watch-offline)).
     */
    private void onRelatedSkeletonTimeout() {
        hideRelatedSkeleton();
        if (!mRelatedVideos.isEmpty()) {
            return;
        }
        boolean offline = !com.liskovsoft.smartyoutubetv2.common.utils.LoadFailure.hasValidatedNetwork(this);
        if (mWatchRelatedLabel != null) {
            if (offline) {
                mWatchRelatedLabel.setText(R.string.mobile_watch_offline);
                mWatchRelatedLabel.setVisibility(View.VISIBLE);
            } else {
                mWatchRelatedLabel.setVisibility(View.GONE);
            }
        }
        // Nothing came for this page at all: drop what was held for it (online too - a failed
        // /next leaves no comments key either; a late metadata bind re-shows the row).
        if (mCommentsKey == null && mWatchCommentsEntry != null) {
            setCommentsEntryVisibility(View.GONE);
        }
        if (TextUtils.isEmpty(mWatchSubs.getText())) {
            mWatchSubs.setVisibility(View.GONE);
        }
        if (offline) {
            if (isCountUnset(mWatchLikeCount)) {
                mWatchLikeCount.setVisibility(View.GONE);
            }
            if (isCountUnset(mWatchDislikeCount)) {
                mWatchDislikeCount.setVisibility(View.GONE);
            }
        }
    }

    private void showRelatedSkeleton() {
        if (isRelatedHidden()) {
            // MEETUBE(issue #122): Settings > Hide Up next - no list is coming, so no label or
            // placeholder rows for one. Runs on every new video, the first open included.
            if (mWatchRelatedLabel != null) {
                mWatchRelatedLabel.setVisibility(View.GONE);
            }
            return;
        }
        if (mRelatedSkeleton == null) {
            return;
        }
        mRelatedSkeleton.setVisibility(View.VISIBLE); // it shimmers itself (ShimmerLinearLayout)
        Utils.removeCallbacks(mHideSkeletonTimeout);
        Utils.postDelayed(mHideSkeletonTimeout, SKELETON_TIMEOUT_MS);
    }

    private void hideRelatedSkeleton() {
        Utils.removeCallbacks(mHideSkeletonTimeout);
        if (mRelatedSkeleton != null) {
            mRelatedSkeleton.setVisibility(View.GONE);
        }
    }

    @Override
    public void showSuggestions(boolean show) {
        // The related list is always visible as part of the scrollable portrait content.
    }

    @Override
    public boolean isSuggestionsShown() {
        // Report "not shown" so the controller always (re)loads suggestions for the current video.
        return false;
    }

    // ---------------------------------------------------------------------------------
    // PlayerUI - action buttons (Like / Dislike / Subscribe visual state).
    // ---------------------------------------------------------------------------------

    @Override
    public int getButtonState(int buttonId) {
        if (buttonId == R.id.action_thumbs_up
                || buttonId == R.id.action_thumbs_down
                || buttonId == R.id.action_subscribe
                || buttonId == R.id.action_chat
                // Overflow-menu toggles: track state so the reused controllers can flip them and
                // the menu can reflect On/Off. setButtonState() already stores every id it receives.
                || buttonId == R.id.action_repeat
                || buttonId == R.id.action_video_stats
                || buttonId == R.id.action_playlist_add
                || buttonId == R.id.action_rotate
                || buttonId == R.id.action_sound_off
                // CC toggle state: kept in sync by the controller (onMetadata ->
                // setSubtitleButtonState) and by applyCaptionFormat(); rendered as the overlay
                // CC button's tint.
                || buttonId == R.id.lb_control_closed_captioning) {
            return mButtonStates.get(buttonId, BUTTON_OFF);
        }
        return BUTTON_DISABLED;
    }

    @Override
    public void setButtonState(int buttonId, int buttonState) {
        mButtonStates.put(buttonId, buttonState);
        runOnUiThread(() -> updateButtonVisual(buttonId, buttonState));
    }

    @Override
    public void setChannelIcon(String iconUrl) {
        runOnUiThread(() -> {
            if (mWatchAvatar == null) {
                return;
            }
            if (TextUtils.isEmpty(iconUrl)) {
                mWatchAvatar.setImageResource(R.drawable.ic_watch_channel_placeholder);
            } else {
                Glide.with(this)
                        .load(iconUrl)
                        .circleCrop()
                        .placeholder(R.drawable.ic_watch_channel_placeholder)
                        .error(R.drawable.ic_watch_channel_placeholder)
                        // NEWTUBE(motion): cross-fade from the placeholder (network loads only).
                        .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
                                .withCrossFade((int) Motion.FADE_IN_MS))
                        .into(mWatchAvatar);
            }
        });
    }

    @Override
    public void setSeekPreviewTitle(String title) {
        // TODO Wave N: chapter/seek-preview UI.
    }

    @Override
    public void setNextTitle(Video nextVideo) {
        // The related/up-next list already surfaces what plays next; no separate label needed.
    }

    @Override
    public void showDebugInfo(boolean show) {
        // "Stats for nerds" overlay. Mirrors PlaybackFragment.showDebugInfo(): lazily build the
        // DebugInfoManager over the debug view group and toggle it. Driven by the reused
        // PlayerUIController (action_video_stats -> onDebugInfoClicked -> showDebugInfo()).
        createDebugManager();
        if (mDebugInfoManager != null) {
            mDebugInfoManager.show(show);
        }
    }

    @Override
    public void showSubtitles(boolean show) {
        // The user's subtitle STYLE (size/color/background/position) is applied by SubtitleManager
        // over the PlayerView's built-in SubtitleView. This toggles that view's visibility; actual
        // track selection is done by ExoPlayerController/TrackSelectorManager.
        createSubtitleManager();
        if (mSubtitleManager != null) {
            mSubtitleManager.show(show);
        }
    }

    /**
     * Build the {@link Media3SubtitleManager} over the PlayerView's built-in {@link
     * androidx.media3.ui.SubtitleView} and register it as a player listener (media3's cue path),
     * so the user's stored {@code SubtitleStyle} (from SubtitleSettingsPresenter) actually takes
     * effect. Mirrors PlaybackFragment.createSubtitleManager(). Idempotent.
     */
    private void createSubtitleManager() {
        if (mSubtitleManager != null || mPlayer == null || mPlayerView == null) {
            return;
        }

        androidx.media3.ui.SubtitleView subtitleView = mPlayerView.getSubtitleView();
        if (subtitleView == null) {
            return;
        }

        mSubtitleManager = new Media3SubtitleManager(subtitleView);
        mPlayer.addListener(mSubtitleManager);
    }

    /** Build the media3 stats-for-nerds over the debug overlay group. Mirrors the TV fragment. */
    private void createDebugManager() {
        if (mDebugInfoManager != null || mDebugViewGroup == null || mPlayer == null) {
            return;
        }
        mDebugInfoManager = new Media3DebugInfoManager(mDebugViewGroup, mPlayer,
                mExoPlayerController.getMediaSourceFactory().getBandwidthMeter());
    }

    @Override
    public void loadStoryboard() {
        // TODO Wave N: storyboard thumbnail preview on the seek bar.
    }

    @Override
    public void setSeekBarSegments(List<SeekBarSegment> segments) {
        // SponsorBlock colored ranges on the seek bar. SponsorBlockController resolves each range to
        // start/end progress fractions + an ARGB color and pushes them here (null to reset); the
        // overlay draws them on the scrubber track. Skipping itself is done by the controller.
        if (mTimeBar == null) {
            return;
        }
        runOnUiThread(() -> mTimeBar.setSegments(segments));
    }

    @Override
    public void updateEndingTime() {
        // TODO Wave N: "ends at HH:mm" label (no surface for it yet).
    }

    @Override
    public void setChatReceiver(ChatReceiver chatReceiver) {
        // The reused ChatController pushes a receiver when live chat is enabled for a live stream
        // (and null when it is torn down). Subscribe to it: each incoming ChatItem is buffered and
        // forwarded to an open LiveChatSheet. Best-effort - if the video isn't live this is never
        // called and the chat panel stays hidden.
        runOnUiThread(() -> {
            mChatReceiver = chatReceiver;

            if (chatReceiver == null) {
                return;
            }

            chatReceiver.setCallback(this::onChatItemReceived);

            // Chat is now streaming; make sure the entry is reachable even if metadata was slow.
            setChatEntryVisible(true);
        });
    }

    private void onChatItemReceived(ChatItem item) {
        if (item == null) {
            return;
        }
        runOnUiThread(() -> {
            mChatItems.add(item);
            while (mChatItems.size() > MAX_CHAT_ITEMS) {
                mChatItems.remove(0);
            }
            if (mChatObserver != null) {
                mChatObserver.onChatItem(item);
            }
        });
    }

    // ---------------------------------------------------------------------------------
    // Chapters (NEWTUBE(chapters), issue #13). Data = the isChapters() suggestions group. UI, as in
    // YouTube: a mark on the time bar at every chapter start, the playing chapter's title above the
    // bar (tap = the Chapters list, ChaptersSheet), and while dragging the bar the title of the
    // chapter under the finger in its place.
    // ---------------------------------------------------------------------------------

    /**
     * A chapter jump never lands before the chapter's start (the player-wide default may land 5 s
     * early, which would play the end of the previous chapter and name it above the bar): the
     * start itself, or a keyframe at most 2 s into the chapter when there is one.
     */
    private static final SeekParameters CHAPTER_SEEK_PARAMETERS =
            new SeekParameters(/* toleranceBeforeUs= */ 0, /* toleranceAfterUs= */ 2_000_000);

    /** Store the current video's chapters (null/empty clears them). */
    private void setChapters(List<Video> chapters) {
        if (mChaptersSheet != null && !sameChapters(mChapterVideos, chapters)) {
            mFullAfterChapters = false; // another video's chapters: no fullscreen to return to
            mChaptersSheet.dismiss();
            mChaptersSheet = null;
        }

        mChapterVideos.clear();

        if (chapters != null && !chapters.isEmpty()) {
            mChapterVideos.addAll(chapters);
        }

        updateChapterMarks();
        mChapterButtonIndex = -1;
        updateChapterButton(mExoPlayerController != null ? mExoPlayerController.getPositionMs() : 0);
    }

    /** Same starts and titles: a re-delivered document of the same video, not a new chapter list. */
    private static boolean sameChapters(List<Video> current, @Nullable List<Video> incoming) {
        int size = incoming != null ? incoming.size() : 0;
        if (current.size() != size) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            Video a = current.get(i);
            Video b = incoming.get(i);
            if (a.startTimeMs != b.startTimeMs || !TextUtils.equals(a.title, b.title)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The chapter line sits right above the seek row, and on a narrow portrait player (a 360 dp wide
     * phone's 16:9 box is ~202 dp tall) it reaches up into the previous / play buttons: a long title
     * would slide under them and take their taps. Its width is capped to end short of any transport
     * button whose height it shares (layout positions, so the controls' fade-in motion can't move it).
     */
    private void fitChapterButton() {
        if (mChapterButton == null || !(mChapterButton.getParent() instanceof View)) {
            return;
        }

        View line = (View) mChapterButton.getParent();
        int lineBottom = offsetInControls(line, false) + line.getHeight();
        int lineTop = lineBottom - Math.max(mChapterButton.getMinHeight(), mChapterButton.getHeight());
        int buttonLeft = offsetInControls(line, true)
                + ((ViewGroup.MarginLayoutParams) mChapterButton.getLayoutParams()).getMarginStart();

        int maxWidth = Integer.MAX_VALUE;
        for (View control : new View[] {mPrevButton, mPlayPauseButton, mNextButton}) {
            if (control == null || control.getVisibility() != View.VISIBLE) {
                continue;
            }
            // Previous / next draw only their icon: grazing their touch padding is fine. The center
            // button's circle is its whole box.
            int controlBottom = offsetInControls(control, false) + control.getHeight()
                    - (control == mPlayPauseButton ? 0 : control.getPaddingBottom());
            int controlLeft = offsetInControls(control, true)
                    + (control == mPlayPauseButton ? 0 : control.getPaddingLeft());
            if (controlBottom > lineTop && controlLeft > buttonLeft) {
                maxWidth = Math.min(maxWidth, Math.max(dp(64), controlLeft - buttonLeft - dp(8)));
            }
        }

        if (mChapterButton.getMaxWidth() != maxWidth) {
            mChapterButton.setMaxWidth(maxWidth);
        }
    }

    /** {@code view}'s left (or top) edge relative to mControlsRoot, from layout positions only. */
    private int offsetInControls(View view, boolean horizontal) {
        int offset = 0;
        View current = view;
        while (current != null && current != mControlsRoot) {
            offset += horizontal ? current.getLeft() : current.getTop();
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return offset;
    }

    /** The seek bar's chapter gaps: one per chapter start after the first. */
    private void updateChapterMarks() {
        if (mTimeBar == null) {
            return;
        }

        long[] starts = new long[mChapterVideos.size()];
        for (int i = 0; i < starts.length; i++) {
            starts[i] = mChapterVideos.get(i).startTimeMs;
        }
        mTimeBar.setChapterStarts(starts);
    }

    /** The chapter playing at {@code positionMs}: an index into mChapterVideos, -1 if none. */
    private int chapterIndexAt(long positionMs) {
        int index = -1;
        for (int i = 0; i < mChapterVideos.size(); i++) {
            if (mChapterVideos.get(i).startTimeMs <= positionMs) {
                index = i;
            } else {
                break;
            }
        }
        return index;
    }

    /**
     * The playing chapter's title after the time ("· Title >"); hidden without chapters. While the
     * bar is dragged the whole row is faded aside, so it keeps what it showed.
     */
    private void updateChapterButton(long positionMs) {
        if (mChapterButton == null) {
            return;
        }

        int index = chapterIndexAt(positionMs);
        if (index >= 0 && TextUtils.isEmpty(mChapterVideos.get(index).title)) {
            index = -1;
        }

        if (index < 0) {
            mChapterButtonIndex = -1;
            mChapterButton.setVisibility(View.GONE);
            return;
        }

        if (index != mChapterButtonIndex) {
            boolean changed = mChapterButtonIndex >= 0 && mChapterButton.getVisibility() == View.VISIBLE;
            mChapterButtonIndex = index;
            String title = mChapterVideos.get(index).title;
            CharSequence text = getString(R.string.mobile_player_chapter_title, title);
            mChapterButton.setContentDescription(getString(R.string.mobile_player_chapter_button, title));
            if (changed && mControlsVisible && !mScrubbing) {
                // Playback moved into the next chapter under the reader's eyes: a quick fade-through.
                crossfadeText(mChapterButton, text);
            } else {
                mChapterButton.animate().cancel(); // a pending fade-through would put its text back
                mChapterButton.setAlpha(1f);
                mChapterButton.setText(text);
            }
        }
        mChapterButton.setVisibility(View.VISIBLE);
    }

    /** Fade {@code view} out, swap its text, fade it back in (Motion's fade-through timing). */
    private static void crossfadeText(TextView view, CharSequence text) {
        view.animate().cancel();
        view.animate().alpha(0f).setDuration(Motion.FADE_OUT_MS).setInterpolator(Motion.STANDARD_ACCELERATE)
                .withEndAction(() -> {
                    view.setText(text);
                    view.animate().alpha(1f).setDuration(Motion.FADE_IN_MS)
                            .setInterpolator(Motion.STANDARD_DECELERATE).start();
                }).start();
    }

    /** While scrubbing: the pill above the bar - the time under the finger, then its chapter. */
    private void updateScrubLabel(long positionMs) {
        if (mScrubChapterView == null) {
            return;
        }

        int index = chapterIndexAt(positionMs);
        CharSequence title = index >= 0 ? mChapterVideos.get(index).title : null;
        String time = formatTime(positionMs);
        setTextIfChanged(mScrubChapterView, TextUtils.isEmpty(title) ? time : time + "   " + title);
        if (mSeekFilmstrip != null) {
            mSeekFilmstrip.update(positionMs, time, title);
        }
    }

    /**
     * NEWTUBE(seek bar): while the bar is dragged the other controls step aside, like YouTube's: the
     * top row, the transport, the bottom row and the top scrim fade out and the time + chapter under
     * the finger shows in a pill above the bar (the bottom scrim stays, so the bar and the pill
     * read over a bright picture). Letting go brings everything back.
     */
    private void setScrubChrome(boolean scrubbing) {
        mScrubChromeHidden = scrubbing;
        float alpha = scrubbing ? 0f : 1f;
        long duration = scrubbing ? Motion.FADE_OUT_MS : Motion.FADE_IN_MS;
        for (View view : new View[] {mBackButton, mAudioButton, mTitleView, mPlayerAuthor, mOptionsRow, mTransport,
                mBottomRow, mActionsRow, mTopScrim}) {
            if (view != null) {
                view.animate().cancel();
                view.animate().alpha(alpha).setDuration(duration).setInterpolator(Motion.STANDARD).start();
            }
        }
        // the filmstrip draws the time; no second pill
        if (mSeekFilmstrip != null) {
            Video video = getVideo();
            if (scrubbing && video != null) {
                mSeekFilmstrip.prepare(video.videoId);
            }
            fadePill(mSeekFilmstrip, scrubbing);
        }
    }

    /** The pill over the top of the video ({@code null} hides it). */
    private void showTopPill(@Nullable CharSequence text) {
        showTopPill(text, 0);
    }

    /** ...with {@code iconRes} after the text (0 = none). */
    private void showTopPill(@Nullable CharSequence text, int iconRes) {
        if (mTopPill == null) {
            return;
        }
        if (text != null) {
            setTextIfChanged(mTopPill, text);
            mTopPill.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, iconRes, 0);
            mTopPill.setCompoundDrawablePadding(iconRes != 0 ? dp(4) : 0);
        }
        fadePill(mTopPill, text != null);
    }

    /** Press-and-hold speed: YouTube's. */
    private static final float HOLD_SPEED = 2f;

    /**
     * A still finger on the video passed the long-press timeout: play at 2x until it lifts. Only
     * over local, non-live playback that is playing and slower than that; the controls step aside
     * like YouTube's and a pill says what is happening.
     */
    private boolean beginHoldSpeed() {
        Video video = getVideo();
        if (mExoPlayerController == null || mPlayer == null || mIsEnded || mIsInPip || mScrubbing
                || (mCastSessionManager != null && mCastSessionManager.isConnected())
                || (video != null && video.isLive)
                || !mExoPlayerController.getPlayWhenReady()
                || mExoPlayerController.getSpeed() >= HOLD_SPEED) {
            return false;
        }
        mExoPlayerController.beginHoldSpeed(HOLD_SPEED);
        Haptics.longPress(mVideoArea); // the player view opts out of tap haptics
        hideControls();
        showTopPill(getString(R.string.mobile_player_hold_speed), R.drawable.ic_player_hold_speed);
        return true;
    }

    private void endHoldSpeed() {
        if (mExoPlayerController != null) {
            mExoPlayerController.endHoldSpeed();
        }
        showTopPill(null);
    }

    /**
     * Ends a press-and-hold boost that outlived its video or its window: a new video (autoplay
     * while the finger stayed down would have played it at 2x, over its own restored speed), PiP,
     * casting. The finger lifting later finds nothing to end.
     */
    private void cancelHoldSpeed() {
        if (mExoPlayerController != null && mExoPlayerController.isHoldSpeedOn()) {
            endHoldSpeed();
        }
    }

    /** A pill appears with a short fade and scale-up from 90%, and leaves with a shorter fade. */
    private static void fadePill(@Nullable View pill, boolean show) {
        if (pill == null) {
            return;
        }
        pill.animate().cancel();
        if (show) {
            if (pill.getVisibility() != View.VISIBLE) {
                pill.setAlpha(0f);
                pill.setScaleX(0.9f);
                pill.setScaleY(0.9f);
                pill.setVisibility(View.VISIBLE);
            }
            pill.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.FADE_IN_MS)
                    .setInterpolator(Motion.STANDARD_DECELERATE).start();
        } else if (pill.getVisibility() == View.VISIBLE) {
            pill.animate().alpha(0f).setDuration(Motion.FADE_OUT_MS).setInterpolator(Motion.STANDARD_ACCELERATE)
                    .withEndAction(() -> pill.setVisibility(View.GONE)).start();
        }
    }

    /** In portrait, a sheet's cap that stops it at the bottom of the video; 0 (no cap) otherwise. */
    private int sheetHeightBelowVideo() {
        if (isLandscape() || mVideoArea == null) {
            return 0;
        }
        int[] location = new int[2];
        mVideoArea.getLocationInWindow(location);
        int belowVideo = getWindow().getDecorView().getHeight() - (location[1] + mVideoArea.getHeight());
        return belowVideo >= dp(240) ? belowVideo : 0;
    }

    /**
     * The Chapters list over the player. In portrait it stops at the bottom of the video, like the
     * comments panel, so the chapter being picked stays in view; fullscreen gets the usual sheet.
     */
    private void showChaptersSheet() {
        if (mChapterVideos.isEmpty()) {
            return;
        }
        // MEETUBE(issue #219): like the comments - upright fullscreen goes back to the page, the list
        // opens under the video, and the fullscreen comes back when it closes.
        if (mPortraitFull && !isLandscape()) {
            mChaptersAfterFullscreen = true;
            setPortraitFull(false);
            return;
        }
        boolean fromFull = mChaptersAfterFullscreen;
        mChaptersAfterFullscreen = false;
        cancelAutoHide();

        releaseImageRequests("chapters-sheet"); // like the chat sheet: its frames are what's on screen now
        long positionMs = mExoPlayerController != null ? mExoPlayerController.getPositionMs() : 0;
        BottomSheetDialog dialog = ChaptersSheet.create(this, mChapterVideos, chapterIndexAt(positionMs),
                sheetHeightBelowVideo(), chapter -> seekFromList(chapter.startTimeMs, CHAPTER_SEEK_PARAMETERS));
        dialog.setOnDismissListener(d -> {
            if (mChaptersSheet == d) {
                mChaptersSheet = null;
            }
            if (mFullAfterChapters) {
                mFullAfterChapters = false;
                if (!isFinishing() && mMorphFraction == 0f) {
                    setPortraitFull(true);
                }
            }
        });
        mChaptersSheet = dialog;
        mFullAfterChapters = fromFull;
        // MEETUBE(issue #219): no dim over the video; sideways a card on the right beside it, as the
        // description and comments.
        if (dialog.getWindow() != null) {
            dialog.getWindow().setDimAmount(0f);
        }
        showPlayerSheet(dialog, true);
        if (isLandscape()) {
            hideControls(); // the picture beside the card, clear
        }
    }

    /**
     * A jump picked from a list (a chapter, a comment's timestamp): on the TV while casting, else
     * here - with {@code parameters} for this one seek when given - then the controls come up for
     * their usual while to show where it landed.
     */
    private void seekFromList(long positionMs, @Nullable SeekParameters parameters) {
        if (mCastSessionManager != null && mCastSessionManager.isConnected()) {
            mCastSessionManager.seekTo(positionMs);
        } else if (mExoPlayerController != null) {
            // set -> seek -> restore reach the playback thread in order (see SEEK_BURST_WATCHDOG_MS).
            SeekParameters previous = parameters != null && mPlayer != null ? mPlayer.getSeekParameters() : null;
            if (previous != null) {
                mPlayer.setSeekParameters(parameters);
            }
            mExoPlayerController.setPositionMs(positionMs);
            if (previous != null) {
                mPlayer.setSeekParameters(previous);
            }
            updateChapterButton(positionMs);
        }
        if (mControlsRoot != null && !mIsInPip) {
            showControlsInternal(true);
            armAutoHide();
        }
    }

    // ---------------------------------------------------------------------------------
    // Simple Quality and Audio track sheets (the gear sheet's everyday pickers)
    // ---------------------------------------------------------------------------------

    /**
     * Distinct resolution rung of the current video: "1080p" / "1080p60" style, "2160p60 HDR" for
     * an HDR stream (issue #63), so an HDR rung and its SDR twin are separate rows, like YouTube.
     */
    private static String qualityLabel(FormatItem item) {
        String label = qualityLabel(item.getWidth(), item.getHeight(), item.getFrameRate());
        com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack track = item.getTrack();
        return track != null && com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorUtil
                .isHdrFormat(track.format) ? label + " HDR" : label;
    }

    /**
     * YouTube's name for the rung: its class, not its height (a vertical video's 1080x1920 is
     * "1080p" and its 608x1080 is "480p", see VideoRung). A frame rate the stream does not state
     * ({@code Format.NO_VALUE}) reads as a plain rung.
     */
    private static String qualityLabel(int width, int height, float frameRate) {
        boolean highFps = frameRate > 40;
        return com.newtube.mobile.player.VideoRung.classHeight(width, height) + "p" + (highFps ? "60" : "");
    }

    /**
     * "Auto" = a ceiling preset, not a concrete stream format. The DEFAULT format constants ship
     * with the isPreset flag unset but a null format id - the selector's own "preset by id
     * presence" rule (VideoTrack.inBounds) - so both must count, or a fresh install never shows
     * Auto as active and explicit rungs persist instead of being per-session.
     */
    private static boolean isAutoFormat(FormatItem item) {
        if (item == null || item.isPreset()) {
            return true;
        }

        com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack track = item.getTrack();
        return track == null || track.format == null || track.format.id == null;
    }

    private void showQualitySheet() {
        List<FormatItem> videoFormats = getVideoFormats();
        if (videoFormats == null) {
            videoFormats = new ArrayList<>();
        }

        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.sheet_mobile_quality, null);
        dialog.setContentView(content);

        LinearLayout qualityList = content.findViewById(R.id.quality_sheet_quality_list);

        // ---- Quality: Auto + one row per distinct resolution rung (best track of each rung). ----
        // The ACTIVE choice is this video's pick when there is one, else the Settings quality -
        // otherwise the sheet would keep check-marking Auto right after the user picked a rung.
        PlayerData playerData = PlayerData.instance(this);
        Video openedFor = getVideo();
        String openedForId = openedFor != null ? openedFor.videoId : null;
        boolean autoActive = isAutoFormat(chosenVideoFormat());

        // Rung -> representative track. Formats arrive quality-descending; the first of each rung
        // is its best variant. An explicitly selected non-preset track marks its rung instead.
        java.util.LinkedHashMap<String, FormatItem> rungs = new java.util.LinkedHashMap<>();
        String selectedRung = null;
        for (FormatItem item : videoFormats) {
            if (item.getHeight() <= 0) {
                continue;
            }
            String label = qualityLabel(item);
            if (!rungs.containsKey(label)) {
                rungs.put(label, item);
            }
            if (!autoActive && item.isSelected()) {
                selectedRung = label;
            }
        }

        // MEETUBE(issue #85): the rungs this phone's decoder reports beyond it (a 2160p60 on a 2K30
        // decoder), which YouTube offers anyway - each in its place by size and frame rate.
        // A rung already offered in a playable codec is not repeated.
        java.util.ArrayDeque<java.util.Map.Entry<String, FormatItem>> beyond = new java.util.ArrayDeque<>();
        List<FormatItem> beyondFormats = mExoPlayerController != null
                ? mExoPlayerController.getVideoFormatsBeyondDecoder() : null;
        if (beyondFormats != null) {
            java.util.LinkedHashMap<String, FormatItem> beyondRungs = new java.util.LinkedHashMap<>();
            for (FormatItem item : beyondFormats) {
                String label = item.getHeight() > 0 ? qualityLabel(item) : null;
                if (label == null || rungs.containsKey(label)) {
                    continue;
                }
                if (!beyondRungs.containsKey(label)) {
                    beyondRungs.put(label, item);
                }
                if (!autoActive && item.isSelected()) {
                    selectedRung = label;
                }
            }
            beyond.addAll(beyondRungs.entrySet());
        }

        // MEETUBE(quality): neither row writes the Settings quality - a pick here is this video's
        // only, and the next video opens at Settings' again. Each also drops the error fixer's
        // session override, which restoreVideoFormat would otherwise re-apply over the pick.
        addQualityRow(qualityList, getString(R.string.mobile_quality_auto), autoActive, () -> {
            // Back to the Settings quality (Auto under its ceiling).
            playerData.setTempVideoFormat(null);
            playerData.setVideoVideoFormat(null, null);
            setFormat(playerData.getFormat(FormatItem.TYPE_VIDEO));
            dialog.dismiss();
        });
        for (java.util.Map.Entry<String, FormatItem> rung : rungs.entrySet()) {
            while (!beyond.isEmpty() && isRungAbove(beyond.peek().getValue(), rung.getValue())) {
                addRungRow(qualityList, dialog, openedForId, beyond.poll(), selectedRung, true);
            }
            addRungRow(qualityList, dialog, openedForId, rung, selectedRung, false);
        }
        while (!beyond.isEmpty()) {
            addRungRow(qualityList, dialog, openedForId, beyond.poll(), selectedRung, true);
        }

        showPlayerSheet(dialog);
    }

    /**
     * One rung row of the Quality sheet: a pick is this video's only. {@code beyondDecoder}: a
     * rung the decoder reports beyond it (issue #85), unmarked like YouTube; if it really can't, the error fixer
     * drops the video to 1080p as for any decoder failure.
     */
    private void addRungRow(LinearLayout list, android.app.Dialog dialog, String openedForId,
            java.util.Map.Entry<String, FormatItem> rung, String selectedRung, boolean beyondDecoder) {
        FormatItem item = rung.getValue();
        addQualityRow(list, rung.getKey(), rung.getKey().equals(selectedRung), () -> {
            dialog.dismiss();
            Video now = getVideo();
            if (!TextUtils.equals(openedForId, now != null ? now.videoId : null)) {
                return; // autoplay moved on under the open sheet: these rows are another video's
            }
            if (beyondDecoder) {
                mExoPlayerController.allowBeyondDecoder(item);
            }
            setFormat(item);
            PlayerData playerData = PlayerData.instance(this);
            playerData.setTempVideoFormat(null);
            playerData.setVideoVideoFormat(openedForId, item);
        });
    }

    /** Whether rung {@code a} sits above {@code b} in the sheet: a bigger class, or the same at 60 fps against 30. */
    private static boolean isRungAbove(FormatItem a, FormatItem b) {
        int classA = com.newtube.mobile.player.VideoRung.classHeight(a.getWidth(), a.getHeight());
        int classB = com.newtube.mobile.player.VideoRung.classHeight(b.getWidth(), b.getHeight());
        return classA != classB ? classA > classB : a.getFrameRate() > 40 && b.getFrameRate() <= 40;
    }

    /** The current video's audio language variants, one per row ({@link AudioTrackChoices}). */
    private List<AudioTrackChoices.Choice> audioTrackChoices() {
        List<FormatItem> audioFormats = mExoPlayerController != null ? getAudioFormats() : null;
        return AudioTrackChoices.from(audioFormats, this::audioTrackLabel,
                getResources().getConfiguration().getLocales().get(0));
    }

    /** "English (original)"; an untagged track (no language at all) reads "Default". */
    private String audioTrackLabel(@Nullable String language) {
        return TextUtils.isEmpty(language)
                ? getString(R.string.mobile_audio_default) : AudioTrackLabel.format(this, language);
    }

    /**
     * The Audio track picker (gear sheet -> Audio track), same anatomy as the Quality sheet: one
     * row per language, the check on the one playing. A pick applies at once to THIS video only
     * (PlayerData.setVideoAudioFormat, kept across its reloads, never persisted): the next video
     * starts on its original track again (Media3TrackAdapter.applyOriginalAudioDefault), like
     * YouTube. Issues #52/#53: a stored pick used to drag every later dubbed video onto it.
     */
    private void showAudioTrackSheet() {
        List<AudioTrackChoices.Choice> choices = audioTrackChoices();
        if (choices.size() < 2) {
            return; // the video changed under the gear sheet
        }

        cancelAutoHide();

        BottomSheetDialog dialog = Haptics.watch(new BottomSheetDialog(this));
        View content = getLayoutInflater().inflate(R.layout.sheet_mobile_quality, null);
        dialog.setContentView(content);
        ((TextView) content.findViewById(R.id.quality_sheet_quality_title))
                .setText(R.string.mobile_player_audio_track);
        LinearLayout list = content.findViewById(R.id.quality_sheet_quality_list);

        PlayerData playerData = PlayerData.instance(this);
        Video openedFor = getVideo();
        String openedForId = openedFor != null ? openedFor.videoId : null;
        for (AudioTrackChoices.Choice choice : choices) {
            addQualityRow(list, choice.label, choice.selected, () -> {
                dialog.dismiss();
                Video now = getVideo();
                if (!TextUtils.equals(openedForId, now != null ? now.videoId : null)) {
                    return; // autoplay moved on under the open sheet: these rows are another video's
                }
                setFormat(choice.item);
                // Issues #52/#53: this video only. Never PlayerData.setFormat - a stored pick used
                // to carry the language to every later video. Reloads of this video keep it.
                playerData.setVideoAudioFormat(openedForId, choice.item);
                // An explicit pick outranks the error fixer's per-video audio fallback, which
                // restoreAudioFormat would otherwise re-apply on a reload of this video.
                playerData.setTempAudioFormat(null);
                com.newtube.mobile.ui.common.MobileSnackbar.show(this, getString(R.string.mobile_audio_track_toast, choice.label));
            });
        }

        showPlayerSheet(dialog);
    }

    private void addQualityRow(LinearLayout parent, CharSequence label, boolean selected, Runnable onClick) {
        View row = getLayoutInflater().inflate(R.layout.item_mobile_quality_row, parent, false);
        TextView labelView = row.findViewById(R.id.quality_row_label);
        labelView.setText(label);
        if (selected) {
            // YouTube-style: the leading check alone marks the active choice (no bold).
            row.findViewById(R.id.quality_row_check).setVisibility(View.VISIBLE);
        }
        row.setOnClickListener(v -> {
            if (mSheetHandingOff == null) { // a footer row is opening the next level
                onClick.run();
            }
        });
        parent.addView(row);
    }

    private static String capitalize(String text) {
        return TextUtils.isEmpty(text) ? text
                : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // ---------------------------------------------------------------------------------
    // Captions, native YouTube-style UX. The overlay CC button toggles (last track <-> off);
    // the captions sheet below (long-press CC, or gear -> Subtitles) is the track picker,
    // replacing the TV AppDialog radio list.
    // ---------------------------------------------------------------------------------

    /** A real caption track, as opposed to the fake default/"disabled" entry. */
    private static boolean isCaptionTrack(FormatItem item) {
        return item != null && !item.isDefault() && item.getLanguage() != null;
    }

    /**
     * Row label for a caption track. Subtitle FormatItems carry the MPD's human-readable name in
     * the language slot ("English", "English (auto-generated)*"); autogenerated/auto-translated
     * variants end with the TRANSLATE_MARKER, which the picker shouldn't show.
     */
    private static String captionLabel(FormatItem item) {
        String label = item.getLanguage() != null ? item.getLanguage()
                : item.getTitle() != null ? item.getTitle().toString() : "";
        if (SubtitleTrack.isAuto(label)) {
            label = label.substring(0, label.length() - 1);
        }
        // The MPD's localized track names arrive lowercase in some languages ("inglés").
        return capitalize(label);
    }

    /** Mirrors PlayerUIController.isSubtitleSelected: a real track is actually selected. */
    private boolean areCaptionsOn() {
        List<FormatItem> formats = getSubtitleFormats();
        if (formats == null) {
            return false;
        }
        for (FormatItem item : formats) {
            if (item.isSelected() && isCaptionTrack(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * CC button tap: toggle like the official app - captions off, or the last-used track back on.
     * Falls through to the picker when there's no usable history yet (first use, or the remembered
     * languages don't exist on this video) and when the video has no tracks at all (the sheet
     * shows its empty state).
     */
    private void toggleCaptions() {
        cancelAutoHide();

        if (areCaptionsOn()) {
            applyCaptionFormat(FormatItem.SUBTITLE_NONE);
            armAutoHide();
            return;
        }

        FormatItem match = null;
        List<FormatItem> formats = getSubtitleFormats();
        if (formats != null) {
            for (FormatItem last : PlayerData.instance(this).getLastSubtitleFormats()) {
                int index = formats.indexOf(last);
                if (index != -1) {
                    // Apply THIS video's own track instance, not the persisted twin: the stored
                    // item's format id can be stale across videos/sessions, in which case the
                    // selector override finds no track and the selection silently stays put.
                    match = formats.get(index);
                    break;
                }
            }
        }

        if (match != null) {
            applyCaptionFormat(match);
            armAutoHide();
        } else {
            showCaptionsSheet();
        }
    }

    /**
     * Select a caption track (or {@link FormatItem#SUBTITLE_NONE}): the same persistence steps as
     * the TV picker's callback (PlayerUIController.onSubtitleLongClicked) - engine, PlayerData
     * (which also feeds the last-used toggle list), per-channel memory - plus the CC button state
     * and the official app's confirmation snackbar ("Subtitles on (English)" / "Subtitles off").
     */
    private void applyCaptionFormat(FormatItem format) {
        boolean on = isCaptionTrack(format);

        setFormat(format);
        PlayerData playerData = PlayerData.instance(this);
        playerData.setFormat(format);

        if (playerData.isSubtitlesPerChannelEnabled()) {
            Video video = getVideo();
            String channelId = video != null ? video.channelId : null;
            if (on) {
                playerData.enableSubtitlesPerChannel(channelId);
            } else {
                playerData.disableSubtitlesPerChannel(channelId);
            }
        }

        setButtonState(R.id.lb_control_closed_captioning, on ? BUTTON_ON : BUTTON_OFF);

        com.newtube.mobile.ui.common.MobileSnackbar.show(this, on ? getString(R.string.mobile_captions_on_toast, captionLabel(format)) : getString(R.string.mobile_captions_off_toast));
    }

    /** Last-used tracks bubble to the top, like the TV picker (PlayerUIController.reorderSubtitles). */
    private void moveLastUsedCaptionsFirst(List<FormatItem> tracks) {
        List<FormatItem> top = new ArrayList<>();
        for (FormatItem last : PlayerData.instance(this).getLastSubtitleFormats()) {
            if (last == null || last.getLanguage() == null) {
                continue;
            }
            int index = tracks.indexOf(last);
            if (index != -1) {
                top.add(tracks.remove(index));
            }
        }
        tracks.addAll(0, top);
    }

    private void showCaptionsSheet() {
        cancelAutoHide();

        List<FormatItem> tracks = new ArrayList<>();
        List<FormatItem> autoTracks = new ArrayList<>();
        List<FormatItem> formats = getSubtitleFormats();
        if (formats != null) {
            for (FormatItem item : formats) {
                if (!isCaptionTrack(item)) {
                    continue;
                }
                if (SubtitleTrack.isAuto(item.getLanguage())) {
                    autoTracks.add(item);
                } else {
                    tracks.add(item);
                }
            }
        }
        moveLastUsedCaptionsFirst(tracks);
        moveLastUsedCaptionsFirst(autoTracks);

        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.sheet_mobile_captions, null);
        dialog.setContentView(content);

        // One flat list, official-app style: Off, then the video's tracks, then the
        // autogenerated/auto-translated variants (their labels already carry the
        // "(auto-generated)" wording, so no section header is needed).
        LinearLayout trackList = content.findViewById(R.id.captions_sheet_track_list);
        addQualityRow(trackList, getString(R.string.mobile_captions_off), !areCaptionsOn(), () -> {
            applyCaptionFormat(FormatItem.SUBTITLE_NONE);
            dialog.dismiss();
        });
        tracks.addAll(autoTracks);
        for (FormatItem item : tracks) {
            addQualityRow(trackList, captionLabel(item), item.isSelected(), () -> {
                applyCaptionFormat(item);
                dialog.dismiss();
            });
        }

        if (tracks.isEmpty()) {
            content.findViewById(R.id.captions_sheet_empty).setVisibility(View.VISIBLE);
        }

        // Caption appearance (style/size/position + the per-channel memory switch): the existing
        // settings dialog, rendered by MobileAppDialogActivity like the rest of settings.
        content.findViewById(R.id.captions_sheet_style).setOnClickListener(v -> {
            if (mSheetHandingOff != dialog) {
                SubtitleSettingsPresenter.instance(this).show();
                handOffSheet(dialog);
            }
        });

        showPlayerSheet(dialog);
    }

    /** Trailing value for the gear menu's Subtitles row: the active track, or "Off". */
    private String currentCaptionsLabel() {
        List<FormatItem> formats = getSubtitleFormats();
        if (formats != null) {
            for (FormatItem item : formats) {
                if (item.isSelected() && isCaptionTrack(item)) {
                    return captionLabel(item);
                }
            }
        }
        return getString(R.string.mobile_menu_off);
    }

    // ---------------------------------------------------------------------------------
    // Playback speed: native preset sheet, classic official-app anatomy (0.25x..2x flat
    // list, "Normal" for 1x, leading check). The exhaustive TV dialog (0.25-4x list +
    // remember-speed options) stays reachable behind "More speeds".
    // ---------------------------------------------------------------------------------

    private static final float[] SPEED_PRESETS = {0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};

    /** "Normal" for 1x (official-app wording), "0.5x"/"1.5x"/"2x" otherwise. */
    private String speedLabel(float speed) {
        if (Helpers.floatEquals(speed, 1.0f)) {
            return getString(R.string.mobile_speed_normal);
        }
        String number = speed == Math.floor(speed)
                ? String.valueOf((int) speed) : String.valueOf(speed);
        return number + "x";
    }

    // ---------------------------------------------------------------------------------
    // MEETUBE(sleep-timer): gear -> Sleep timer (SleepTimer holds the countdown).
    // ---------------------------------------------------------------------------------

    private final Runnable mSleepPause = this::sleepNow;

    private final Player.Listener mSleepEndListener = new Player.Listener() {
        @Override
        public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                SleepTimer.onPausedAtEnd();
            }
        }
    };

    /** The timer ran out (or the video ended under "End of video"): pause, like the pause button. */
    private void sleepNow() {
        if (mPlayer != null) {
            mPlayer.setPauseAtEndOfMediaItems(false);
        }
        if (mExoPlayerController != null && mExoPlayerController.getPlayWhenReady()) {
            mExoPlayerController.setPlayWhenReady(false);
            if (mPresenter != null) {
                mPresenter.onPauseClicked();
            }
        }
    }

    /**
     * JOYBOY(v937, merge plan phase 3): previous/next first goes to the add-on (episodes of the local
     * library folder); when it does not handle it, MeeTube's own previous/next runs as before.
     */
    private void stepVideo(int dir) {
        if (JoyboyPlayerHost.onStep(this, dir)) {
            return;
        }
        if (mPresenter == null) {
            return;
        }
        if (dir > 0) {
            mPresenter.onNextClicked();
        } else {
            mPresenter.onPreviousClicked();
        }
    }

    private String sleepTimerLabel() {
        int picked = SleepTimer.picked();
        return picked == SleepTimer.END_OF_VIDEO ? getString(R.string.mobile_sleep_end_of_video)
                : picked == SleepTimer.OFF ? getString(R.string.mobile_menu_off)
                : getString(R.string.mobile_sleep_left, SleepTimer.minutesLeft());
    }

    private String sleepPresetLabel(int minutes) {
        return minutes == SleepTimer.END_OF_VIDEO ? getString(R.string.mobile_sleep_end_of_video)
                : minutes == SleepTimer.OFF ? getString(R.string.mobile_menu_off)
                : getResources().getQuantityString(R.plurals.mobile_settings_minutes, minutes, minutes);
    }

    private void showSleepTimerSheet() {
        cancelAutoHide();

        BottomSheetDialog dialog = Haptics.watch(new BottomSheetDialog(this));
        // Same anatomy as the speed sheet: title, one flat list with a check on the active pick.
        View content = getLayoutInflater().inflate(R.layout.sheet_mobile_speed, null);
        dialog.setContentView(content);
        ((TextView) content.findViewById(R.id.speed_sheet_title)).setText(R.string.mobile_player_sleep_timer);
        content.findViewById(R.id.speed_sheet_more).setVisibility(View.GONE);

        LinearLayout list = content.findViewById(R.id.speed_sheet_list);
        int picked = SleepTimer.picked();
        for (int minutes : SleepTimer.PRESETS) {
            addQualityRow(list, sleepPresetLabel(minutes), minutes == picked, () -> {
                SleepTimer.set(minutes);
                if (mPlayer != null) {
                    mPlayer.setPauseAtEndOfMediaItems(SleepTimer.isEndOfVideo());
                }
                String toast = minutes == SleepTimer.END_OF_VIDEO ? getString(R.string.mobile_sleep_toast_end)
                        : minutes == SleepTimer.OFF ? getString(R.string.mobile_sleep_toast_off)
                        : getString(R.string.mobile_sleep_toast_minutes, sleepPresetLabel(minutes));
                com.newtube.mobile.ui.common.MobileSnackbar.show(this, toast);
                dialog.dismiss();
            });
        }

        dialog.setOnDismissListener(d -> armAutoHide());
        showPlayerSheet(dialog);
    }

    private void showSpeedSheet() {
        cancelAutoHide();

        float current = mExoPlayerController != null ? mExoPlayerController.getSpeed() : -1;
        if (current <= 0) {
            current = 1f;
        }

        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.sheet_mobile_speed, null);
        dialog.setContentView(content);

        LinearLayout list = content.findViewById(R.id.speed_sheet_list);
        for (float speed : SPEED_PRESETS) {
            addQualityRow(list, speedLabel(speed), Helpers.floatEquals(speed, current), () -> {
                applySpeed(speed);
                dialog.dismiss();
            });
        }

        // Full TV speed dialog: extended 0.25-4x list (long-click path always opens the list).
        content.findViewById(R.id.speed_sheet_more).setOnClickListener(v -> {
            if (mSheetHandingOff != dialog) {
                openPlayerOption(R.id.action_video_speed, true);
                handOffSheet(dialog);
            }
        });

        showPlayerSheet(dialog);
    }

    /**
     * Apply a speed pick. The engine change fires onSpeedChanged, which VideoStateController
     * already persists (global/per-channel memory); only the per-video State save - the TV
     * dialog's close hook - needs mirroring here. Confirms via snackbar, same as captions.
     */
    private void applySpeed(float speed) {
        setSpeed(speed);

        Video video = getVideo();
        if (video != null && PlayerData.instance(this).isSpeedPerVideoEnabled()) {
            VideoStateService stateService = VideoStateService.instance(this);
            State state = stateService.getByVideoId(video.videoId);
            if (state != null) {
                stateService.save(new State(state.video, state.positionMs, state.durationMs, speed));
            }
        }

        com.newtube.mobile.ui.common.MobileSnackbar.show(this, getString(R.string.mobile_speed_toast, speedLabel(speed)));
    }

    /**
     * The comments row on the page, and its button on the fullscreen pill (MEETUBE(fullscreen-actions)),
     * which only shows once there are comments: the row's reserved space means nothing there.
     */
    private void setCommentsEntryVisibility(int visibility) {
        mWatchCommentsEntry.setVisibility(visibility);
        if (mPlayerComments != null) {
            mPlayerComments.setVisibility(mCommentsKey != null ? View.VISIBLE : View.GONE);
        }
    }

    private void onCommentsEntryClicked() {
        if (mCommentsKey == null || mCommentsPanel == null) {
            return;
        }
        closeSidePanelsImmediately(); // MEETUBE(lyrics-panel): one panel at a time
        // NEWTUBE(comments-panel): the open-time image hold is NOT lifted here (the old sheet did):
        // that resumed every queued watch-page image before the first frame. Avatars wait for the
        // hold's normal release like the rest of the page.
        Utils.removeCallbacks(mPrefetchComments);
        // MEETUBE(issue #36): a tall video shrinks to 16:9, leaving the comments the room YouTube gives them.
        if (mWatchRoot != null && mIsResumed) {
            android.transition.TransitionManager.beginDelayedTransition((ViewGroup) mWatchRoot,
                    new android.transition.ChangeBounds().setDuration(PORTRAIT_FULL_MS).setInterpolator(Motion.EMPHASIZED));
        }
        collapseTallBox(mWatchScroll, Integer.MAX_VALUE / 2);
        mCommentsPanel.open();
    }

    /** Best effort: the first comments page, fetched while the video plays, so the panel opens full. */
    private void prefetchComments() {
        if (mCommentsPanel == null || mIsInPip || mIsStopped || isFinishing()) {
            return;
        }
        mCommentsPanel.prefetch();
    }

    private void clearTopComments() {
        Utils.removeCallbacks(mNextTopComment);
        mTopComments.clear();
        mTopCommentIndex = 0;
        if (mCommentTeaser != null) {
            mCommentTeaser.animate().cancel();
            mCommentTeaser.setVisibility(View.GONE);
        }
    }

    /** MEETUBE(watch-look): the next top comment on the compact page's card, faded across. */
    private void showNextTopComment() {
        if (mCommentTeaser == null || mTopComments.isEmpty()) {
            return;
        }
        Utils.postDelayed(mNextTopComment, TOP_COMMENT_MS);
        if (mIsStopped && mCommentTeaser.getVisibility() == View.VISIBLE) {
            return; // nobody is looking: no new comment, no work, until the page shows again
        }
        CommentItem comment = mTopComments.get(mTopCommentIndex++ % mTopComments.size());
        Runnable bind = () -> {
            CommentsAdapter.loadAvatar(mCommentTeaserAvatar, comment.getAuthorPhoto(),
                    mCommentTeaserAvatar.getLayoutParams().width);
            mCommentTeaserText.setText(comment.getMessage());
            mCommentTeaser.setTranslationY(6 * getResources().getDisplayMetrics().density);
            mCommentTeaser.animate().alpha(1f).translationY(0f)
                    .setDuration(Motion.FADE_IN_MS).setInterpolator(Motion.STANDARD).start();
        };
        mCommentTeaser.animate().cancel();
        if (mCommentTeaser.getVisibility() != View.VISIBLE) {
            mCommentTeaser.setAlpha(0f);
            mCommentTeaser.setVisibility(View.VISIBLE);
            bind.run();
        } else {
            mCommentTeaser.animate().alpha(0f).setDuration(Motion.FADE_OUT_MS)
                    .setInterpolator(Motion.STANDARD).withEndAction(bind).start();
        }
    }

    private final CommentsPanel.Host mCommentsHost = new CommentsPanel.Host() {
        @Override
        public void onTopComments(List<CommentItem> comments) {
            if (mCommentTeaser == null) {
                return; // classic watch page: no teaser
            }
            clearTopComments();
            for (CommentItem comment : comments) {
                if (comment != null && !TextUtils.isEmpty(comment.getMessage())) {
                    mTopComments.add(comment);
                }
                if (mTopComments.size() == 10) {
                    break;
                }
            }
            showNextTopComment();
        }

        @Override
        public void onCommentTimestamp(long positionMs) {
            seekFromList(positionMs, null);
        }

        @Override
        public void onCommentVideoLink(String videoId) {
            onRelatedClicked(Video.from(videoId));
        }

        @Override
        public void onCommentsPanelMoved(float gone) {
            // MEETUBE(issue #214): beside the video, the card's rim leaves with the panel.
            View area = mCommentsSide ? findViewById(R.id.mobile_watch_area) : null;
            if (area != null && area.getForeground() != null) {
                area.getForeground().setAlpha(Math.round(255 * (1f - gone)));
            }
            // MEETUBE(shorts): as the comments slide the short grows or shrinks with them.
            followShortsComments(gone);
        }

        @Override
        public void onCommentsPanelShown(boolean shown) {
            if (shown) {
                closeSidePanelsImmediately(); // MEETUBE(lyrics-panel): also covers a deferred open
            }
            // MEETUBE(shorts): the comments of a short closed - it fills the screen again.
            if (!shown && mShorts != null && mShorts.isCommentsOpen()) {
                onShortsCommentsClosed();
            } else if (!shown && mShortsCommentsGone >= 0f) { // Shorts left with the comments up
                mShortsCommentsGone = -1f;
                resetShortsBox();
            }
            // MEETUBE(fullscreen-actions): the landscape comments closed - the video takes the screen back.
            if (!shown && mCommentsSide && mCommentsPanel != null && !mCommentsPanel.isOpen()) {
                setCommentsSide(false);
            }
            // ...and upright, the vertical fullscreen they were opened from comes back.
            if (!shown && mFullAfterComments && mCommentsPanel != null && !mCommentsPanel.isOpen()) {
                mFullAfterComments = false;
                setPortraitFull(true);
            }
            // The page under the panel is covered: keep TalkBack off it (the video stays reachable).
            updateSidePanelsShown(shown);
        }
    };

    private void onChatEntryClicked() {
        // If the reused ChatController already pushed a receiver (live chat auto-enabled in settings),
        // messages already flow through setChatReceiver(); just open the panel. Otherwise open our own
        // subscription to the same LiveChatService the controller uses, keyed by the live-chat key
        // from the metadata (live chat defaults to off, so the controller won't have started it).
        if (mChatReceiver == null && mLiveChatAction == null) {
            startLiveChatStream();
        }
        releaseImageRequests("chat-sheet"); // same as comments: the chat sheet's avatars are what's on screen now
        LiveChatSheet.show(getSupportFragmentManager());
    }

    private void startLiveChatStream() {
        if (mLiveChatKey == null) {
            return;
        }
        RxHelper.disposeActions(mLiveChatAction);
        LiveChatService chatService = YouTubeServiceManager.instance().getLiveChatService();
        mLiveChatAction = chatService.openLiveChatObserve(mLiveChatKey)
                .subscribe(
                        this::onChatItemReceived,
                        error -> { /* stream error - panel keeps last messages */ },
                        () -> { /* live chat session closed */ });
    }

    /**
     * Stop the Activity-owned live-chat poll (openLiveChatObserve loops forever) and clear it so a
     * later onChatEntryClicked re-seeds a fresh stream - its gate requires mLiveChatAction == null.
     * The ChatController receiver path (mChatReceiver) owns its own stream and is left untouched.
     */
    private void stopLiveChatStream() {
        RxHelper.disposeActions(mLiveChatAction);
        mLiveChatAction = null;
    }

    // ---------------------------------------------------------------------------------
    // LiveChatSheet.Host - expose the buffered chat stream to the open sheet.
    // ---------------------------------------------------------------------------------

    @Override
    public List<ChatItem> getChatSnapshot() {
        return new ArrayList<>(mChatItems);
    }

    @Override
    public void registerChatObserver(LiveChatSheet.Observer observer) {
        mChatObserver = observer;
    }

    @Override
    public void unregisterChatObserver(LiveChatSheet.Observer observer) {
        if (mChatObserver == observer) {
            mChatObserver = null;
        }
    }

    @Override
    public void onChatSheetDismissed() {
        // Panel closed by the user: stop the invisible forever-poll opened in onChatEntryClicked.
        // A later re-open re-seeds a fresh stream via the mLiveChatAction == null gate.
        stopLiveChatStream();
    }

    @Nullable
    @Override
    public View getChatVideoArea() {
        return isLandscape() ? null : mVideoArea;
    }

    @Override
    public void setChatColumn(int widthPx) {
        int margin = isLandscape() ? widthPx : 0;
        if (mChatMargin != margin) {
            boolean wasSide = mChatSide;
            mChatSide = margin > 0;
            mChatMargin = margin;
            // The video glides aside for the chat and back when it closes, the way the sheet slides.
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mVideoArea.getLayoutParams();
            if (mChatSlide != null) {
                mChatSlide.cancel();
            }
            mChatSlide = android.animation.ValueAnimator.ofInt(lp.getMarginEnd(), margin);
            mChatSlide.setDuration(Motion.ENTER_MS);
            mChatSlide.setInterpolator(Motion.STANDARD);
            mChatSlide.addUpdateListener(a -> {
                lp.setMarginEnd((int) a.getAnimatedValue());
                mVideoArea.setLayoutParams(lp);
            });
            mChatSlide.start();
            setResizeMode(PlayerData.instance(this).getResizeMode()); // the whole picture beside the chat
        }
    }

    /** The page's live chat row and its button on the fullscreen pill (issue #32) show together. */
    private void setChatEntryVisible(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (mWatchChatEntry != null) {
            mWatchChatEntry.setVisibility(visibility);
        }
        if (mPlayerChat != null) {
            mPlayerChat.setVisibility(visibility);
        }
    }

    // ---------------------------------------------------------------------------------
    // Watch page - header binding, actions and related list.
    // ---------------------------------------------------------------------------------

    /**
     * Bind the watch-page header from the current {@link Video}. Called from {@link #setVideo} on
     * every update: the presenter/SuggestionsController calls setVideo() again after folding the
     * loaded metadata (and again after the real Return-YouTube-Dislike counts) into the Video, so
     * the like/dislike/subscriber counts and description fill in as they arrive. A fresh video id
     * additionally resets the header, scrolls back to the top and kicks off a metadata load for the
     * bits not stored on the Video (channel avatar, clean view-count / date line).
     */
    private void bindWatchVideo(Video item) {
        if (item == null || mWatchTitle == null) {
            return;
        }
        if (mShorts != null) {
            mShorts.bind(item);
            updateHdrOutput(); // Shorts never use the HDR SurfaceView (#35)
        }

        mWatchVideo = item;
        boolean isNewVideo = !Helpers.equals(item.videoId, mWatchVideoId);

        if (isNewVideo) {
            mWatchVideoId = item.videoId;
            mWatchMetadataGate.open(item.videoId);
            mRelatedRenderGate.reset();
            Utils.removeCallbacks(mReleaseWatchMetadata);
            Utils.postDelayed(mReleaseWatchMetadata, WATCH_METADATA_TIMEOUT_MS);
            clearSuggestions();
            resetWatchHeader();
            // A new video closes the comments panel and forgets the old video's comments.
            Utils.removeCallbacks(mPrefetchComments);
            if (mCommentsPanel != null) {
                mFullAfterComments = false; // another video: its own page, not the old fullscreen
                mCommentsPanel.onVideoChanged(item.videoId);
            }
            closeSidePanelsImmediately();
            if (mWatchScroll != null) {
                mWatchScroll.scrollTo(0, 0);
            }
            if (mTallBoxCollapse > 0 && mWatchRoot != null) { // its box starts whole (#36)
                mTallBoxCollapse = 0;
                mWatchRoot.requestLayout(); // mWatchRootLayoutListener re-lays the box
            }
        }

        // Never blank a previously shown title on a SAME-video rebind: error-reloads re-enter with
        // a bare Video (title lost) and the fetch that would repopulate it may die on a bad
        // network - keep the last-known-good text. A genuinely new video may reset to empty.
        if (isNewVideo || !TextUtils.isEmpty(item.getTitleFull())) {
            mWatchTitle.setText(item.getTitleFull());
        }
        updateDownloadPill();
        // Channel name has the same bare-reload-Video blanking problem as the title above.
        if (isNewVideo || !TextUtils.isEmpty(item.getAuthor())) {
            mWatchChannelName.setText(item.getAuthor());
            mPlayerAuthor.setText(item.getAuthor());
        }

        // Fallback meta line until the metadata load returns a clean "views • date". The
        // second-title leads with the channel name, which the channel row right below repeats -
        // strip it so the line reads "1.4M views • 10 months ago" like YouTube's.
        CharSequence second = item.getSecondTitleFull();
        if (mWatchMeta.length() == 0 && !TextUtils.isEmpty(second)) {
            String line = second.toString();
            String author = item.getAuthor();
            if (!TextUtils.isEmpty(author) && line.startsWith(author)) {
                String stripped = line.substring(author.length()).replaceFirst("^\\s*[•·]\\s*", "");
                if (!stripped.isEmpty()) {
                    line = stripped;
                }
            }
            line = line.replaceFirst("(?i)(published|premiered|streamed live) on ", "");
            if (mWatchCompact) {
                line = line.replaceAll("\\s*[•·]\\s*", COMPACT_DOT);
            }
            mWatchMeta.setText(line);
        }

        if (!TextUtils.isEmpty(item.likeCount)) {
            mLikeCountRaw = item.likeCount;
            showLikeCount(false);
        }
        // NEWTUBE(ryd-opt-in): also on a same-video bind, so switching the option off hides the count
        // it showed, and a live /next refresh can't paint its estimate.
        if (!showsDislikeCount()) {
            mWatchDislikeCount.setVisibility(View.GONE);
        } else if (!TextUtils.isEmpty(item.dislikeCount)) {
            mWatchDislikeCount.setText(item.dislikeCount);
        }
        if (!TextUtils.isEmpty(item.subscriberCount)) {
            mWatchSubs.setText(item.subscriberCount);
            mWatchSubs.setVisibility(View.VISIBLE);
        }
        if (!TextUtils.isEmpty(item.description)) {
            mWatchDescription.setText(item.description);
        }
    }

    private void resetWatchHeader() {
        mWatchDescription.setText(null);
        mDescriptionViews = null;
        mDescriptionDate = null;
        mLikeCountExact = -1;
        mWatchMeta.setText(null);
        mWatchLikeCount.setText(R.string.mobile_watch_count_placeholder);
        mLikeCountRaw = null;
        mLikedInRaw = -1;
        mWatchDislikeCount.setText(R.string.mobile_watch_count_placeholder);
        mWatchSubs.setText(null);
        // NEWTUBE(watch-jump): INVISIBLE, not GONE - the subscriber line, the Comments row and the
        // "Up next" label used to pop in as metadata/related landed, pushing the page down ~180dp
        // under the person's thumb. Their space is held from the start; only the rare video that
        // turns out to have none of them collapses (bindWatchMetadata / onRelatedSkeletonTimeout).
        mWatchSubs.setVisibility(View.INVISIBLE);
        mWatchAvatar.setImageResource(R.drawable.ic_watch_channel_placeholder);
        mWatchLikeCount.setVisibility(View.VISIBLE);
        // NEWTUBE(ryd-opt-in): no Return YouTube Dislike, no dislike number - just the thumb, as on YouTube.
        mWatchDislikeCount.setVisibility(showsDislikeCount() ? View.VISIBLE : View.GONE);

        // New video: clear comments/chat availability and any buffered chat until metadata returns.
        mCommentsKey = null;
        mLiveChatKey = null;
        if (mWatchCommentsEntry != null) {
            setCommentsEntryVisibility(View.VISIBLE); // reserved; see above
        }
        if (mWatchCommentsCount != null) {
            mWatchCommentsCount.setText(null);
        }
        clearTopComments();
        closeSidePanelImmediately(mTranscriptPanel);
        if (mTranscriptSheet != null) {
            mTranscriptSheet.dismiss(); // the last video's words
            mTranscriptSheet = null;
        }
        setChatEntryVisible(false);
        mChatItems.clear();
        RxHelper.disposeActions(mLiveChatAction);
        mLiveChatAction = null;
        mChatReceiver = null;
    }

    @Override
    public void onWatchMetadata(MediaItemMetadata metadata) {
        if (metadata == null) {
            return;
        }

        // NEWTUBE(mobile-ttff): delivered on the metadata load thread by SuggestionsController. Marshal
        // to the UI thread, then either bind now (first frame already rendered) or stash and bind on
        // the new stream's first texture frame. Reset for EVERY new video, including related taps
        // on the reused activity; a session-wide ready flag allowed all later opens to bind early.
        runOnUiThread(() -> {
            bindWatchMetadata(mWatchMetadataGate.offer(metadata));
        });
    }

    private void releaseWatchMetadata() {
        Utils.removeCallbacks(mReleaseWatchMetadata);
        bindWatchMetadata(mWatchMetadataGate.release());
        // NEWTUBE(motion): the Up-next rows wait until the new video's first frame has come through
        // (STILL_REVEAL_MS). Laying out a dozen fresh rows is a ~15 ms frame on a Pixel 9, and landing
        // in the same frames as the still's fade it stuttered the very first frames of the video.
        // They sit under the video; a tenth of a second later is not something anyone sees.
        final String videoId = mWatchVideoId;
        Utils.removeCallbacks(mReleaseRelatedRender);
        mReleaseRelatedRender = () -> {
            if (Helpers.equals(videoId, mWatchVideoId)) {
                mRelatedRenderGate.release();
            }
        };
        Utils.postDelayed(mReleaseRelatedRender, STILL_REVEAL_MS + 20);
    }

    @Nullable
    private Runnable mReleaseRelatedRender;

    /** "Published on Jan 14, 2024" / "Fecha de publicación: ..." -> just the date. */
    @Nullable
    private static String cleanPublishedDate(@Nullable String date) {
        if (date == null) {
            return null;
        }
        date = date.replaceFirst("(?i)^(published|premiered|streamed live) on ", "");
        // Non-English locales label the date "Data de publicació: 29 de des. 2019" /
        // "Fecha de publicación: ..." - drop the leading "Label:" too. Publish dates
        // never contain a colon themselves, so this can't clip the date.
        String unlabeled = date.replaceFirst("^[^:]{1,40}:\\s*", "");
        return unlabeled.isEmpty() ? date : unlabeled;
    }

    private void bindWatchMetadata(MediaItemMetadata metadata) {
        if (metadata == null) {
            return;
        }

        bindRelatedChips(metadata);

        {
            // Clean "views • date" line. YouTube's raw date string arrives as "Published on
            // Jan 14, 2024" / "Premiered ..." - drop the wordy prefix, keep just the date.
            // The title otherwise binds only from setVideo (Video.getTitleFull) - which is empty on
            // bare error-reload Videos. Metadata carries the real title: use it to (re)populate,
            // and fill the controls title too if nothing is showing there.
            if (!TextUtils.isEmpty(metadata.getTitle())) {
                setWatchTextFaded(mWatchTitle, metadata.getTitle());
                if (mTitleView != null && TextUtils.isEmpty(mTitleView.getText())) {
                    mTitleView.setText(metadata.getTitle());
                }
            }

            String views = metadata.getViewCount();
            // NEWTUBE(watch-meta): prefer the relative date ("4 days ago") - the shape the card's own
            // line has, so the swap below doesn't jump. The absolute date is the fallback.
            String relativeDate = metadata.getRelativePublishedDate();
            String date = cleanPublishedDate(!TextUtils.isEmpty(relativeDate) ? relativeDate : metadata.getPublishedDate());
            // MEETUBE(description): the Description sheet's row shows the calendar date, as YouTube's does.
            if (!TextUtils.isEmpty(views)) {
                mDescriptionViews = views;
            }
            String calendarDate = cleanPublishedDate(metadata.getPublishedDate());
            if (!TextUtils.isEmpty(calendarDate) || TextUtils.isEmpty(mDescriptionDate)) {
                mDescriptionDate = !TextUtils.isEmpty(calendarDate) ? calendarDate : date;
            }
            String meta;
            if (!TextUtils.isEmpty(views) && !TextUtils.isEmpty(date)) {
                meta = views + (mWatchCompact ? COMPACT_DOT : com.newtube.mobile.ui.common.MetaSeparator.DOT) + date;
            } else if (!TextUtils.isEmpty(views)) {
                meta = views;
            } else {
                meta = date;
            }
            // NEWTUBE(watch-meta): a relative line replaces whatever the card put there - it reads like
            // a feed card's line anyway, and the card's line isn't always views + date (a download
            // shows "144p • 8.8 MB", a signed-out Subscriptions card "1.6M • Thu Sep 3 2026"). An
            // absolute-only answer fills just an empty line: swapping a card's "4 days ago" for
            // "Sep 20, 2026" a second after opening was the jump UX-14 removed.
            if (!TextUtils.isEmpty(meta)
                    && (!TextUtils.isEmpty(relativeDate) || mWatchMeta.length() == 0)) {
                setWatchTextFaded(mWatchMeta, meta);
            }

            String description = metadata.getDescription();
            if (!TextUtils.isEmpty(description)) {
                mWatchDescription.setText(description);
            }

            if (!TextUtils.isEmpty(metadata.getAuthor())) {
                setWatchTextFaded(mWatchChannelName, metadata.getAuthor());
                mPlayerAuthor.setText(metadata.getAuthor());
            }

            if (!TextUtils.isEmpty(metadata.getSubscriberCount())) {
                mWatchSubs.setVisibility(View.VISIBLE); // held INVISIBLE until now (watch-jump)
                setWatchTextFaded(mWatchSubs, metadata.getSubscriberCount());
            } else if (TextUtils.isEmpty(mWatchSubs.getText())) {
                mWatchSubs.setVisibility(View.GONE); // hidden count: release the held line
            }

            setChannelIcon(metadata.getAuthorImageUrl());
            com.newtube.mobile.ui.common.ChannelAvatars.put(this, metadata.getChannelId(),
                    metadata.getAuthor(), metadata.getAuthorImageUrl()); // MEETUBE(card avatars)

            // Counts: prefer the real values already synced onto the Video; fall back to metadata.
            if (isCountUnset(mWatchLikeCount) && !TextUtils.isEmpty(metadata.getLikeCount())) {
                mLikeCountRaw = metadata.getLikeCount();
            }
            mLikeCountExact = metadata.getLikeCountNumber(); // counted with mLikedInRaw's like, as below
            // NEWTUBE(ryd-opt-in): metadata's dislike count is only an estimate from the likes.
            if (showsDislikeCount() && isCountUnset(mWatchDislikeCount)
                    && !TextUtils.isEmpty(metadata.getDislikeCount())) {
                mWatchDislikeCount.setText(metadata.getDislikeCount());
            }

            // Initial like/dislike/subscribe button states (also pushed by PlayerUIController.onMetadata).
            setButtonState(R.id.action_thumbs_up,
                    metadata.getLikeStatus() == MediaItemMetadata.LIKE_STATUS_LIKE ? BUTTON_ON : BUTTON_OFF);
            setButtonState(R.id.action_thumbs_down,
                    metadata.getLikeStatus() == MediaItemMetadata.LIKE_STATUS_DISLIKE ? BUTTON_ON : BUTTON_OFF);
            setButtonState(R.id.action_subscribe, metadata.isSubscribed() ? BUTTON_ON : BUTTON_OFF);
            // MEETUBE(issue #45): YouTube's count holds the like it reports; a tap moves it from there.
            mLikedInRaw = metadata.getLikeStatus() == MediaItemMetadata.LIKE_STATUS_LIKE ? 1 : 0;
            showLikeCount(true);

            // Comments / live-chat availability. A non-null comments key = comments enabled; a
            // non-null live-chat key = live stream. Reuse the same keys the TV controllers use.
            mCommentsKey = metadata.getCommentsKey();
            mLiveChatKey = metadata.getLiveChatKey();
            if (mWatchCommentsEntry != null) {
                setCommentsEntryVisibility(mCommentsKey != null ? View.VISIBLE : View.GONE);
            }
            // NEWTUBE(comments-panel): the panel's source, the total on the card, and - best
            // effort, once the video has played a moment - the first page (bound after the first
            // frame already, see mWatchMetadataGate).
            String commentsCount = mCommentsKey != null ? metadata.getCommentsCount() : null;
            if (mWatchCommentsCount != null && !TextUtils.isEmpty(commentsCount)) {
                setWatchTextFaded(mWatchCommentsCount, commentsCount);
            }
            if (mCommentsPanel != null) {
                mCommentsPanel.setSource(mWatchVideoId, mCommentsKey, metadata.getNewestCommentsKey(), commentsCount);
                Utils.removeCallbacks(mPrefetchComments);
                if (mCommentsKey != null) {
                    Utils.postDelayed(mPrefetchComments, COMMENTS_PREFETCH_DELAY_MS);
                }
            }
            if (mLiveChatKey != null) {
                setChatEntryVisible(true);
            }
        }
    }

    /**
     * NEWTUBE(motion): the page's lines settle as the video's details arrive (right after its first
     * frame): the card's short title becomes the full one, "-" becomes "5.4K", the channel line gets
     * its subscriber count. Swapped in one frame, that was a flurry of pops; a line whose text really
     * changes now fades in (150 ms), all of them in the same frame, so it reads as one settle.
     */
    private void setWatchTextFaded(@Nullable TextView view, CharSequence text) {
        if (view == null || TextUtils.equals(view.getText(), text)) {
            return;
        }
        view.setText(text);
        if (!view.isShown()) {
            return;
        }
        view.animate().cancel();
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(Motion.FADE_IN_MS).setInterpolator(Motion.STANDARD).start();
    }

    /** Settings > Hide Up next: SuggestionsController sends no related rows (the queue still comes). */
    private boolean isRelatedHidden() {
        return PlayerTweaksData.instance(this).isSuggestionsDisabled();
    }

    private boolean showsDislikeCount() {
        return PlayerTweaksData.instance(this).isReturnYouTubeDislikeEnabled();
    }

    private boolean isCountUnset(TextView view) {
        CharSequence text = view.getText();
        return TextUtils.isEmpty(text) || getString(R.string.mobile_watch_count_placeholder).contentEquals(text);
    }

    /**
     * MEETUBE(description): the title opens YouTube's Description panel (DescriptionSheet) under the
     * video, instead of unfolding the description into the page.
     */
    private void showDescriptionSheet() {
        if (TextUtils.isEmpty(mWatchTitle.getText()) || isFinishing()) {
            return;
        }
        CharSequence likes = isCountUnset(mWatchLikeCount) ? null : mWatchLikeCount.getText();
        boolean shorts = mShorts != null && mShorts.isActive();
        // MEETUBE(issue #217): upright fullscreen goes back to the page first, like its comments button.
        if (mPortraitFull && !isLandscape() && !shorts) {
            mDescriptionAfterFullscreen = true;
            setPortraitFull(false);
            return;
        }
        boolean fromFull = mDescriptionAfterFullscreen;
        mDescriptionAfterFullscreen = false;
        DescriptionSheet.Extras extras = new DescriptionSheet.Extras();
        extras.transcript = !shorts && transcriptTrack() != null ? this::showTranscriptSheet : null;
        if (!shorts) {
            extras.channel = mWatchChannelName.getText();
            extras.subscribers = mWatchSubs.getText();
            extras.avatar = mWatchAvatar.getDrawable();
            extras.subscribe = mWatchSubscribe;
            extras.openChannel = this::openCurrentChannel;
            extras.seek = ms -> seekFromList(ms, null); // MEETUBE(issue #175)
            extras.durationMs = getDurationMs();
        }
        // MEETUBE(exact counts): Video details in whole numbers - the likes moved by the person's
        // own tap like the short count (showLikeCount), the views from this video's /player reply.
        if (mLikeCountExact > 0 && mLikedInRaw != -1) {
            int likedNow = getButtonState(R.id.action_thumbs_up) == BUTTON_ON ? 1 : 0;
            extras.likes = mLikeCountExact + likedNow - mLikedInRaw;
        }
        Video video = getVideo();
        if (mCaptionInfo != null && video != null && Helpers.equals(video.videoId, mCaptionInfo.getVideoId())) {
            extras.views = Helpers.parseLong(mCaptionInfo.getViewCount());
        }
        // MEETUBE(description-panel): portrait lives under the video, so its seek dot draws over the edge.
        if (canShowSidePanel()) {
            if (!prepareSidePanel(mDescriptionPanel)) {
                return;
            }
            DescriptionSheet.bindPanel(mDescriptionPanel, mWatchTitle.getText(), likes, mDescriptionViews,
                    mDescriptionDate, mWatchDescription.getText(), extras);
            mDescriptionPanel.open();
            mFullAfterDescription = fromFull;
            return;
        }
        showPlayerSheet(DescriptionSheet.create(this, mWatchTitle.getText(), likes, mDescriptionViews,
                mDescriptionDate, mWatchDescription.getText(),
                // A short fills the screen: a plain sheet over it, not one sized to under the video.
                isLandscape() || shorts ? null : mVideoArea, extras),
                !shorts); // sideways, beside the video as it plays, YouTube's
        if (isLandscape() && !shorts) {
            hideControls(); // the picture beside the card, clear
        }
    }

    // MEETUBE(lyrics-panel): one Back handler for the open panel, ahead of the player's.
    private void setupSidePanels() {
        mDescriptionPanel = findViewById(R.id.mobile_description_panel);
        mLyricsPanel = findViewById(R.id.mobile_lyrics_panel);
        mTranscriptPanel = findViewById(R.id.mobile_transcript_panel);
        mSidePanels = new CommentsPanelLayout[]{mDescriptionPanel, mLyricsPanel, mTranscriptPanel};
        mSidePanelBackCallback = new androidx.activity.OnBackPressedCallback(false) {
            @Nullable private androidx.activity.BackEventCompat mLastBackEvent;
            @Nullable private CommentsPanelLayout mBackPanel;

            @Override
            public void handleOnBackStarted(@NonNull androidx.activity.BackEventCompat backEvent) {
                mLastBackEvent = null;
                mBackPanel = openSidePanel();
                if (mBackPanel != null) {
                    mBackPanel.startBackProgress(backEvent);
                }
            }

            @Override
            public void handleOnBackProgressed(@NonNull androidx.activity.BackEventCompat backEvent) {
                mLastBackEvent = backEvent;
                if (mBackPanel != null) {
                    mBackPanel.updateBackProgress(backEvent);
                }
            }

            @Override
            public void handleOnBackCancelled() {
                mLastBackEvent = null;
                if (mBackPanel != null) {
                    mBackPanel.cancelBackProgress();
                    mBackPanel = null;
                }
            }

            @Override
            public void handleOnBackPressed() {
                CommentsPanelLayout panel = mBackPanel != null ? mBackPanel : openSidePanel();
                androidx.activity.BackEventCompat last = mLastBackEvent;
                mLastBackEvent = null;
                mBackPanel = null;
                if (panel != null) {
                    panel.handleBack(last);
                    releaseSidePanelBinding(panel);
                }
                setEnabled(false);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, mSidePanelBackCallback);
        int[] titles = {R.string.mobile_watch_description, R.string.mobile_lyrics, R.string.mobile_transcript};
        for (int i = 0; i < mSidePanels.length; i++) {
            CommentsPanelLayout panel = mSidePanels[i];
            panel.setCallback(new CommentsPanelLayout.Callback() {
                @Override
                public void onPanelOpened() {
                    updateSidePanelsShown();
                }

                @Override
                public void onPanelClosing(long durationMs) {
                    mSidePanelBackCallback.setEnabled(false);
                    releaseSidePanelBinding(panel);
                }

                @Override
                public void onPanelClosed() {
                    releaseSidePanelBinding(panel);
                    updateSidePanelsShown();
                    if (panel == mDescriptionPanel && mFullAfterDescription) { // MEETUBE(issue #217)
                        mFullAfterDescription = false;
                        setPortraitFull(true);
                    }
                }
            });
            ViewCompat.setAccessibilityPaneTitle(panel.getSheet(), getString(titles[i]));
        }
    }

    private boolean canShowSidePanel() {
        boolean shorts = mShorts != null && mShorts.isActive();
        return !isLandscape() && !shorts && !mPortraitFull && !mIsInPip && !mPipEnterPending;
    }

    @Nullable
    private CommentsPanelLayout openSidePanel() {
        for (CommentsPanelLayout panel : mSidePanels) {
            if (panel.isOpen()) {
                return panel;
            }
        }
        return null;
    }

    private boolean prepareSidePanel(CommentsPanelLayout panel) {
        if (panel.isOpen() || panel.isBackFinishing()) {
            return false;
        }
        mFullAfterComments = false;
        if (mCommentsPanel != null) {
            mCommentsPanel.closeImmediately();
        }
        closeSidePanelsImmediately();
        com.newtube.mobile.ui.common.SheetGlass.frost(panel.getSheet(), getWindow());
        return true;
    }

    private void updateSidePanelsShown() {
        updateSidePanelsShown(mCommentsPanel != null && mCommentsPanel.isOpen());
    }

    private void updateSidePanelsShown(boolean commentsShown) {
        boolean shown = openSidePanel() != null;
        if (mSidePanelBackCallback != null) {
            mSidePanelBackCallback.setEnabled(shown);
        }
        if (mWatchScroll != null) {
            mWatchScroll.setImportantForAccessibility(shown || commentsShown
                    ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }
    }

    // MEETUBE(transcript-panel): release polling, search and late answers on every close path.
    private void releaseSidePanelBinding(CommentsPanelLayout panel) {
        Runnable release = mSidePanelBindings.remove(panel);
        if (release != null) {
            release.run();
        }
    }

    private void closeSidePanelImmediately(@Nullable CommentsPanelLayout panel) {
        if (panel != null) {
            mFullAfterDescription = false; // closed for another panel, video or minimize: no fullscreen back
            releaseSidePanelBinding(panel);
            panel.closeImmediately();
            updateSidePanelsShown();
        }
    }

    // MEETUBE(lyrics-panel): an interrupted close already cleared isOpen; reset Back and TalkBack too.
    private void closeSidePanelsImmediately() {
        for (CommentsPanelLayout panel : mSidePanels) {
            closeSidePanelImmediately(panel);
        }
    }

    /** MEETUBE(transcript): the captions the open video's transcript is made from, or null. */
    @Nullable
    private com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle transcriptTrack() {
        Video video = getVideo();
        if (mCaptionInfo == null || video == null || !Helpers.equals(video.videoId, mCaptionInfo.getVideoId())) {
            return null;
        }
        return TranscriptSheet.pick(mCaptionInfo.getSubtitles());
    }

    /** MEETUBE(transcript): the transcript under the video (in fullscreen, a plain sheet). */
    private void showTranscriptSheet() {
        showTranscriptSheet(false);
    }

    /** {@code noLyrics}: in place of the Lyrics panel, which found none. False when there is no transcript. */
    private boolean showTranscriptSheet(boolean noLyrics) {
        com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle track = transcriptTrack();
        if (track == null || isFinishing()) {
            return false;
        }
        // MEETUBE(transcript-panel): portrait shares the description panel's place and motion.
        if (canShowSidePanel()) {
            if (!prepareSidePanel(mTranscriptPanel)) {
                return mTranscriptPanel.isOpen();
            }
            mSidePanelBindings.put(mTranscriptPanel, TranscriptSheet.bindPanel(mTranscriptPanel, track,
                    () -> mExoPlayerController != null ? mExoPlayerController.getPositionMs() : -1,
                    positionMs -> seekFromList(positionMs, null), noLyrics));
            mTranscriptPanel.open();
            return true;
        }
        mTranscriptSheet = TranscriptSheet.create(this, track, isLandscape() ? null : mVideoArea,
                () -> mExoPlayerController != null ? mExoPlayerController.getPositionMs() : -1,
                positionMs -> seekFromList(positionMs, null), noLyrics);
        showPlayerSheet(mTranscriptSheet);
        return true;
    }

    /** MEETUBE(watch-look): Subscribe's label color, kept faded while it shows as the mark. */
    private void setSubscribeTextColor(int color) {
        mSubscribeTextColor = color;
        applySubscribeMorph(mSubscribeMorph, -1);
    }

    /** The label gives way to the app's mark (collapsed), or comes back, the pill resizing between. */
    private void morphSubscribe(boolean collapsed) {
        if (collapsed == mSubscribeCollapsed || mSubscribeIcon == null) {
            return;
        }
        mSubscribeCollapsed = collapsed;
        if (mSubscribeAnimator != null) {
            mSubscribeAnimator.cancel();
        }
        // The label's own width now (it may have changed to "Subscribed" meanwhile), and the round mark's.
        ViewGroup.LayoutParams lp = mWatchSubscribe.getLayoutParams();
        int current = mWatchSubscribe.getWidth();
        mWatchSubscribe.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int label = mWatchSubscribe.getMeasuredWidth();
        int round = dp(36);
        float from = mSubscribeMorph;
        float to = collapsed ? 1f : 0f;
        // The size overshoots and settles (the bounce); the label and mark fade without it.
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(380);
        android.view.animation.Interpolator bounce = new android.view.animation.OvershootInterpolator(1.6f);
        animator.addUpdateListener(a -> {
            float fraction = a.getAnimatedFraction();
            float t = from + (to - from) * Motion.STANDARD.getInterpolation(Math.min(1f, fraction * 1.4f));
            int target = collapsed ? round : label;
            float size = bounce.getInterpolation(fraction);
            applySubscribeMorph(t, Math.max(round / 2, Math.round(current + (target - current) * size)));
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (mSubscribeAnimator == animation && !mSubscribeCollapsed) {
                    lp.width = ViewGroup.LayoutParams.WRAP_CONTENT; // a new label sizes itself again
                    mWatchSubscribe.setLayoutParams(lp);
                }
            }
        });
        mSubscribeAnimator = animator;
        animator.start();
    }

    /** {@code width} < 0 keeps the pill's width. The label fades out in the first half, the mark in, in the second. */
    private void applySubscribeMorph(float t, int width) {
        mSubscribeMorph = t;
        int alpha = Math.round(android.graphics.Color.alpha(mSubscribeTextColor) * Math.max(0f, 1f - 2f * t));
        mWatchSubscribe.setTextColor(androidx.core.graphics.ColorUtils.setAlphaComponent(mSubscribeTextColor, alpha));
        if (mSubscribeIcon == null) {
            return;
        }
        float mark = Math.max(0f, 2f * t - 1f);
        mSubscribeIcon.setColorFilter(mSubscribeTextColor);
        mSubscribeIcon.setAlpha(mark);
        mSubscribeIcon.setScaleX(0.6f + 0.4f * mark);
        mSubscribeIcon.setScaleY(0.6f + 0.4f * mark);
        ViewGroup.LayoutParams lp = mWatchSubscribe.getLayoutParams();
        if (width >= 0 && lp.width != width) {
            lp.width = width;
            // The row's own size never changes, so it lays itself out here: a requestLayout made the
            // whole watch page measure every frame (3-13 ms on a 120 Hz phone), and the morph stuttered.
            View box = (View) mWatchSubscribe.getParent();
            View row = (View) box.getParent();
            if (row.getWidth() > 0 && !row.isLayoutRequested()) {
                mWatchSubscribe.forceLayout();
                box.forceLayout();
                row.forceLayout();
                row.measure(View.MeasureSpec.makeMeasureSpec(row.getWidth(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(row.getHeight(), View.MeasureSpec.EXACTLY));
                row.layout(row.getLeft(), row.getTop(), row.getRight(), row.getBottom());
            } else {
                mWatchSubscribe.setLayoutParams(lp);
            }
        }
    }

    /** Route Like / Dislike / Subscribe through the presenter's onButtonClicked vocabulary. */
    private void onActionButtonClicked(int actionId) {
        if (mPresenter == null) {
            return;
        }

        int currentState = getButtonState(actionId);
        if (currentState == BUTTON_DISABLED) {
            currentState = BUTTON_OFF;
        }

        // The controller performs the toggle and calls setButtonState() back with the new state.
        mPresenter.onButtonClicked(actionId, currentState);
    }

    /**
     * Like/Dislike (signed in), confirmed like every other watch-page action. The controller flips
     * the state synchronously when it sends the rating; an unchanged state means it didn't (the
     * video's data isn't in yet), which the phone says instead of a silent tap.
     */
    private void onRateTapped(int actionId) {
        int stateBefore = getButtonState(actionId);
        int ratingBefore = currentRating();
        onActionButtonClicked(actionId);
        int stateAfter = getButtonState(actionId);
        if (stateAfter == stateBefore) {
            WatchActionFeedback.rateNotReady(this);
            return;
        }
        int ratingAfter = currentRating();
        // NEWTUBE(haptics): the tap took effect - a click and a pop of the thumb, like YouTube's.
        boolean like = actionId == R.id.action_thumbs_up;
        Haptics.click(like ? mWatchLike : mWatchDislike);
        // MEETUBE(burst): a like splashes - on the Shorts thumb or the fullscreen pill when the tap
        // came from there.
        boolean pill = mActionsRow != null && mActionsRow.isShown();
        View likeIcon = mShorts != null && mShorts.isActive() ? mShorts.likeIcon() : pill ? mPlayerLike : mWatchLikeIcon;
        if (like && stateAfter == BUTTON_ON) {
            Burst.play(likeIcon, getColorInt(R.color.mobile_color_primary));
        }
        Motion.pop(like ? likeIcon : pill ? mPlayerDislike : mWatchDislikeIcon);
        WatchActionFeedback.confirmRating(this, like, stateAfter == BUTTON_ON,
                undoRating(ratingBefore, ratingAfter));
    }

    /** MEETUBE(issue #45): the like count as YouTube gave it, for {@link #showLikeCount}. */
    @Nullable
    private CharSequence mLikeCountRaw;
    /** Whether {@link #mLikeCountRaw} holds this account's like: 1/0, -1 until metadata says. */
    private int mLikedInRaw = -1;

    /** YouTube's like count, moved by one when the thumb's state differs from the one it counted. */
    private void showLikeCount(boolean faded) {
        if (mLikeCountRaw == null || mWatchLikeCount == null) {
            return;
        }
        CharSequence text = mLikedInRaw == -1 ? mLikeCountRaw : RatingUndo.likeCount(mLikeCountRaw,
                mLikedInRaw == 1, getButtonState(R.id.action_thumbs_up) == BUTTON_ON);
        if (faded) {
            setWatchTextFaded(mWatchLikeCount, text);
        } else {
            mWatchLikeCount.setText(text);
        }
    }

    private int currentRating() {
        return RatingUndo.rating(getButtonState(R.id.action_thumbs_up) == BUTTON_ON,
                getButtonState(R.id.action_thumbs_down) == BUTTON_ON);
    }

    /**
     * Undo puts back the rating from before the tap (liked -> Dislike -> Undo is liked again, not
     * unrated), only on the video it confirmed - the Snackbar outlives a switch to an Up next video
     * on this same screen - and only while the thumbs still show what the tap left.
     */
    private Runnable undoRating(int ratingBefore, int ratingAfter) {
        String videoId = currentVideoId();
        return () -> {
            if (videoId == null || !videoId.equals(currentVideoId()) || currentRating() != ratingAfter) {
                return;
            }
            int tap = RatingUndo.tapFor(ratingAfter, ratingBefore, R.id.action_thumbs_up, R.id.action_thumbs_down);
            if (tap != 0) {
                onActionButtonClicked(tap);
            }
        };
    }

    /** Subscribe's Undo: re-tap it, on the same video and while it still shows {@code stateAfter}. */
    private Runnable undoOnThisVideo(int actionId, int stateAfter) {
        String videoId = currentVideoId();
        return () -> {
            if (videoId != null && videoId.equals(currentVideoId()) && getButtonState(actionId) == stateAfter) {
                onActionButtonClicked(actionId);
            }
        };
    }

    @Nullable
    private String currentVideoId() {
        Video video = getVideo();
        return video != null ? video.videoId : null;
    }

    /** NEWTUBE(snackbar): the rating did not reach YouTube; the controller already put the thumbs back. */
    @Override
    public void onRatingNotSaved() {
        runOnUiThread(() -> WatchActionFeedback.ratingNotSaved(this));
    }

    /** Subscribe/Unsubscribe, confirmed only when the controller actually flipped the state. */
    private void onSubscribeTapped() {
        int before = getButtonState(R.id.action_subscribe);
        onActionButtonClicked(R.id.action_subscribe);
        int after = getButtonState(R.id.action_subscribe); // set synchronously by the controller
        if (after != before) {
            Haptics.click(mWatchSubscribe);
            if (after == BUTTON_ON) {
                View button = mShorts != null && mShorts.isActive() ? mShorts.subscribeButton() : mWatchSubscribe;
                Burst.play(button, getColorInt(R.color.mobile_color_on_surface));
                Motion.pop(button);
            }
            Video video = getVideo();
            WatchActionFeedback.confirmSubscription(this, after == BUTTON_ON,
                    video != null ? video.getAuthor() : null,
                    undoOnThisVideo(R.id.action_subscribe, after));
        }
    }

    private void updateButtonVisual(int buttonId, int buttonState) {
        boolean on = buttonState == BUTTON_ON;

        if (buttonId == R.id.action_thumbs_up && mWatchLikeIcon != null) {
            // NEWTUBE(icons): filled while on, outlined while off, both in the text colour - the
            // CC button's language. A red tint on the active thumb broke the no-tints rule.
            int icon = on ? R.drawable.ic_watch_thumb_up : R.drawable.ic_watch_thumb_up_outline;
            mWatchLikeIcon.setImageResource(icon);
            if (mPlayerLike != null) {
                mPlayerLike.setImageResource(icon);
            }
            if (mWatchLike != null) {
                mWatchLike.setSelected(on);
                if (mShorts != null) {
                    mShorts.setLiked(on);
                }
            }
            showLikeCount(false); // Shorts mirrors it
        } else if (buttonId == R.id.action_thumbs_down && mWatchDislikeIcon != null) {
            int icon = on ? R.drawable.ic_watch_thumb_down : R.drawable.ic_watch_thumb_down_outline;
            mWatchDislikeIcon.setImageResource(icon);
            if (mPlayerDislike != null) {
                mPlayerDislike.setImageResource(icon);
            }
            if (mWatchDislike != null) {
                mWatchDislike.setSelected(on);
            }
        } else if (buttonId == R.id.lb_control_closed_captioning && mSubtitlesButton != null) {
            // YouTube-style: filled CC glyph while captions are on, outlined while off.
            mSubtitlesButton.setImageResource(on ? R.drawable.ic_player_cc : R.drawable.ic_player_cc_off);
        } else if (buttonId == R.id.action_playlist_add && mWatchSaveIcon != null) {
            // In at least one playlist -> check glyph + "Saved", like YouTube's filled Save.
            mWatchSaveIcon.setImageResource(on ? R.drawable.ic_mobile_check : R.drawable.ic_player_playlist_add);
            if (mWatchSaveLabel != null) {
                mWatchSaveLabel.setText(on ? R.string.mobile_watch_saved : R.string.mobile_watch_save);
            }
        } else if (buttonId == R.id.action_subscribe && mWatchSubscribe != null) {
            mWatchSubscribe.setText(on ? R.string.mobile_watch_subscribed : R.string.mobile_watch_subscribe);
            if (mShorts != null) {
                mShorts.setSubscribed(on);
            }
            // NEWTUBE(theme): the main-action pill while not subscribed (white on the dark page,
            // near-black on the light one). MEETUBE(glass): once subscribed, a glass pill like the
            // action row's - bg_watch_subscribe's selected state.
            mWatchSubscribe.setSelected(on);
            setSubscribeTextColor(getColorInt(on
                    ? R.color.mobile_color_on_surface : R.color.mobile_color_on_inverse_surface));
        }
    }

    private int getColorInt(int colorRes) {
        return androidx.core.content.ContextCompat.getColor(this, colorRes);
    }

    // ---------------------------------------------------------------------------------
    // Downloads (watch-page pill + gear -> More row)
    // ---------------------------------------------------------------------------------

    private final DownloadRegistry.Listener mDownloadsListener = this::updateDownloadPill;

    /** The download (finished or in flight) of the video on screen, if any. */
    @Nullable
    private DownloadItem currentDownload() {
        Video video = getVideo();
        if (video == null || video.videoId == null) {
            return null;
        }
        DownloadRegistry registry = DownloadRegistry.instance(this);
        DownloadItem active = registry.findActive(video.videoId);
        return active != null ? active : registry.findDone(video.videoId, DownloadOption.KIND_VIDEO);
    }

    /** Trailing value for the More row: "Downloaded", "45%", "Waiting..." or nothing. */
    @Nullable
    private String downloadStateLabel() {
        DownloadItem item = currentDownload();
        if (item == null) {
            return null;
        }
        if (item.isDone()) {
            return getString(R.string.mobile_download_pill_downloaded);
        }
        if (item.state == DownloadItem.STATE_DOWNLOADING && item.progressPercent() >= 0) {
            return item.progressPercent() + "%";
        }
        if (item.isFailed()) {
            return getString(R.string.mobile_download_retry);
        }
        if (item.isPaused()) {
            return getString(R.string.mobile_download_badge_paused);
        }
        return getString(R.string.mobile_download_badge_queued);
    }

    /** MEETUBE(download-folder): a long press picks where downloads are saved. */
    private boolean onDownloadLongPressed() {
        if (!DownloadFolder.isSupported()) {
            return false;
        }
        Haptics.longPress(mWatchDownload);
        DownloadFolder.showOptions(this);
        return true;
    }

    private void onDownloadTapped() {
        DownloadItem item = currentDownload();
        if (item != null) {
            DownloadMenu.show(this, item);
        } else {
            VideoDownloads.request(this, getVideo());
        }
    }

    /**
     * The pill mirrors the download state of the video on screen, like YouTube's: "Download"
     * -> a live percentage while it fetches -> "Downloaded" (check icon). Hidden for streams
     * that cannot be downloaded (live, upcoming).
     */
    private void updateDownloadPill() {
        if (mWatchDownload == null) {
            return;
        }
        runOnUiThread(() -> {
            Video video = getVideo();
            DownloadItem item = currentDownload();
            boolean offered = VideoDownloads.canDownload(video) || (video != null && video.isLocal()) || item != null;
            mWatchDownload.setVisibility(offered ? View.VISIBLE : View.GONE);
            mPlayerDownload.setVisibility(offered ? View.VISIBLE : View.GONE);
            if (!offered) {
                return;
            }
            int icon = item != null && item.isDone() ? R.drawable.ic_watch_downloaded : R.drawable.ic_watch_download;
            mWatchDownloadIcon.setImageResource(icon);
            mPlayerDownload.setImageResource(icon);
            if (item != null && item.isDone()) {
                mWatchDownloadLabel.setText(R.string.mobile_download_pill_downloaded);
            } else if (item != null && item.state == DownloadItem.STATE_DOWNLOADING && item.progressPercent() >= 0) {
                mWatchDownloadLabel.setText(item.progressPercent() + "%");
            } else if (item != null && item.isPaused()) {
                mWatchDownloadLabel.setText(R.string.mobile_download_badge_paused);
            } else if (item != null && item.isActive()) {
                mWatchDownloadLabel.setText(R.string.mobile_download_badge_queued);
            } else {
                mWatchDownloadLabel.setText(R.string.dialog_download);
            }
            if (mWatchCompact) {
                mWatchDownloadLabel.setVisibility(item != null && (item.isActive() || item.isPaused())
                        ? View.VISIBLE : View.GONE);
            }
        });
    }

    /**
     * MEETUBE(share-at-time, issue #68): a tap shares the plain link, a long press shares it from
     * the second playing now ({@code ?t=}); a live stream or the very start shares plain.
     */
    private boolean shareCurrentVideo(boolean atPosition) {
        Video video = getVideo();
        if (video == null || TextUtils.isEmpty(video.videoId)) {
            return false;
        }

        String url = "https://youtu.be/" + video.videoId;
        long seconds = atPosition && mPlayer != null && !video.isLive ? mPlayer.getCurrentPosition() / 1000 : 0;
        if (seconds > 0) {
            url += "?t=" + seconds;
            com.newtube.mobile.ui.common.MobileSnackbar.show(this, getString(R.string.mobile_watch_share_at, formatTime(seconds * 1000)));
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.mobile_watch_share_subject));
        intent.putExtra(Intent.EXTRA_TEXT, url);
        startActivity(Intent.createChooser(intent, getString(R.string.mobile_watch_share)));
        return true;
    }

    /** MEETUBE(music): the Music pill shows for videos YouTube Music has, and only those. */
    private void updateMusicPill(@Nullable Video video) {
        String videoId = video != null ? video.videoId : null;
        if (mWatchMusic == null || Helpers.equals(videoId, mMusicPillVideoId)) {
            return;
        }
        mMusicPillVideoId = videoId;
        mWatchMusic.setVisibility(View.GONE);
        closeSidePanelImmediately(mLyricsPanel);
        if (mLyricsSheet != null) {
            mLyricsSheet.dismiss();
            mLyricsSheet = null;
        }
        if (TextUtils.isEmpty(videoId)) {
            return;
        }
        MusicLink.check(videoId, music -> {
            if (music && Helpers.equals(videoId, mMusicPillVideoId) && !isDestroyed()) {
                mWatchMusic.setVisibility(View.VISIBLE);
            }
        });
    }

    /** MEETUBE(lyrics): the Music pill opens the song's lyrics (LyricsSheet) under the video. */
    private void showLyricsSheet() {
        Video video = getVideo();
        if (video == null || TextUtils.isEmpty(video.videoId) || isFinishing()) {
            return;
        }
        // MEETUBE(lyrics-panel): portrait shares the description panel's place and motion.
        if (canShowSidePanel()) {
            if (!prepareSidePanel(mLyricsPanel)) {
                return;
            }
            mSidePanelBindings.put(mLyricsPanel, LyricsSheet.bindPanel(this, mLyricsPanel,
                    video.videoId, video.getTitle(), video.getAuthor(),
                    () -> mExoPlayerController != null ? mExoPlayerController.getPositionMs() : -1,
                    this::showPlayerSheet, transcriptTrack() != null ? this::showTranscriptSheet : null));
            mLyricsPanel.open();
            return;
        }
        mLyricsSheet = LyricsSheet.create(this, video.videoId, video.getTitle(), video.getAuthor(),
                isLandscape() ? null : mVideoArea,
                () -> mExoPlayerController != null ? mExoPlayerController.getPositionMs() : -1,
                this::showPlayerSheet,
                transcriptTrack() != null ? this::showTranscriptSheet : null);
        showPlayerSheet(mLyricsSheet);
    }

    /** Video id + elapsedRealtime of the last touch-prefetch, to report whether the tap used it. */
    private String mTouchPrefetchVideoId;
    private long mTouchPrefetchAtMs;

    /**
     * NEWTUBE(touch-prefetch, experiment): a finger rests on a related row - resolve its /player now
     * so the tap that usually follows finds it in flight or cached. Never for the playing video,
     * local files or while the page is going away.
     */
    private void onRelatedPressed(Video video) {
        Video current = getVideo();
        if (video == null || video.videoId == null || video.isLocal() || isFinishing()
                || (current != null && video.videoId.equals(current.videoId))) {
            return;
        }
        if (MediaServiceManager.instance().speculativePrefetchFormatInfo(video)) {
            mTouchPrefetchVideoId = video.videoId;
            mTouchPrefetchAtMs = android.os.SystemClock.elapsedRealtime();
            NetPath.log(NetPath.context() + " touch-prefetch fire video=" + video.videoId);
        }
    }

    private void onRelatedClicked(Video video) {
        onRelatedClicked(video, null);
    }

    private void onRelatedClicked(Video video, @Nullable ImageView thumbnail) {
        seedTappedStill(video, thumbnail);
        if (video != null && video.videoId != null && video.hasVideo() && !video.isLocal()) {
            // NEWTUBE(open-phases): an in-player switch is a tap like a Home card's: start its
            // NetPath window here (the open line then keeps this t0), so a related hop shows up as
            // tap -> open -> info -> prepare -> first-frame in the same harness as card taps.
            NetPath.logTap(video.videoId);
        }
        if (video != null && video.videoId != null && video.videoId.equals(mTouchPrefetchVideoId)) {
            NetPath.log("touch-prefetch used video=" + video.videoId + " leadMs="
                    + (android.os.SystemClock.elapsedRealtime() - mTouchPrefetchAtMs));
            mTouchPrefetchVideoId = null;
        }
        if (mPresenter != null && video != null) {
            // Loads + plays the tapped video in this same player (VideoLoaderController.openVideoInt).
            mPresenter.onSuggestionItemClicked(video);
        }
    }

    /** Watch-page channel row tap → the mobile channel screen. Same routing as the card menu's
     *  "Open channel" entry; playback stays alive behind the channel screen (the pending-view
     *  flag in onUserLeaveHint keeps auto-PiP from hijacking the in-app navigation). */
    private void openCurrentChannel() {
        Video video = getVideo();
        if (video == null || !ChannelPresenter.canOpenChannel(video)) {
            return;
        }
        // Channel-id lookup may be asynchronous. Mark the route now, but detach/activate the live
        // mini session only when MobileChannelActivity is actually created; a failed lookup leaves
        // the watch page untouched. A short never goes mini: it stays behind the channel, paused
        // by onStop, and Back returns to it.
        if (!isShortsMode()) {
            MiniPlayerBridge.prepareNavigation(this);
        }
        MediaServiceManager.chooseChannelPresenter(this, video);
    }

    private void maybePageSuggestions() {
        // A temporarily empty adapter can look scrolled to the bottom while its model is already
        // populated. Do not let that layout callback bypass the first-frame rendering gate.
        if (!mRelatedRenderGate.isReleased()) {
            return;
        }
        if (mChipVideos != null) {
            if (mRelatedWindow < mChipVideos.size()) {
                mRelatedWindow += RELATED_WINDOW_STEP;
                submitRelatedWindow();
            } else if (mChipPage != null) {
                loadChannelChip(mChipPage);
            }
            return;
        }
        if (mPresenter == null || mRelatedVideos.isEmpty()) {
            return;
        }

        // NEWTUBE(net): reveal what's already fetched before asking for more. Every bound row loads
        // its thumbnail immediately (the list never recycles - see the image-hold block), so binding
        // the full 30-80 suggestions at open costs 1-2.5 MB that nobody has scrolled to yet.
        if (mRelatedWindow < mRelatedVideos.size()) {
            mRelatedWindow += RELATED_WINDOW_STEP;
            submitRelatedWindow();
            return;
        }

        Video last = mRelatedVideos.get(mRelatedVideos.size() - 1);
        if (last == mLastPagedVideo) {
            return;
        }

        mLastPagedVideo = last;
        // The controller derives the row to continue from last.getGroup().
        mPresenter.onScrollEnd(last);
    }

    private void rebuildRelatedList() {
        mRelatedVideos.clear();
        mQueueVideos.clear();

        // When playing from a playlist, the section-playlist row (SuggestionsController.
        // appendSectionPlaylistIfNeeded) contains the WHOLE playlist including the video that's
        // already playing. It used to be flattened into "Up next" with the playing item filtered
        // out, which lost the playlist entirely: no name, no position, and the rest of the
        // playlist was indistinguishable from algorithmic suggestions. It now goes to the queue
        // card instead, and keeps the playing item (marked "Now playing") because that is what the
        // "3 / 48" line points at. The controller's next/prev logic walks the group objects, which
        // stay untouched either way.
        Video current = getVideo();
        String currentId = current != null ? current.videoId : null;
        Integer queueId = findQueueGroupId(current);

        for (Map.Entry<Integer, List<Video>> entry : mSuggestionVideos.entrySet()) {
            boolean isQueueRow = queueId != null && queueId.equals(entry.getKey());
            List<Video> vids = entry.getValue();
            if (vids == null) {
                continue;
            }
            for (Video v : vids) {
                if (v == null || com.newtube.mobile.ui.common.ShortsFilter.isShort(v)) {
                    continue; // NEWTUBE(shorts): neither Up next nor the queue card lists Shorts
                }
                if (isQueueRow) {
                    mQueueVideos.add(v);
                } else if (currentId == null || !currentId.equals(v.videoId)) {
                    // Match by videoId, not Video.equals (unreliable across instances).
                    mRelatedVideos.add(v);
                }
            }
        }

        NetPath.log("related-list rows=" + mSuggestionVideos.size() + " size=" + mRelatedVideos.size()
                + " queue=" + mQueueVideos.size());

        // Keep these models current for controller queries and queue actions, but coalesce the
        // adapter submissions/row inflation until moving playback has priority on the main thread.
        mRelatedRenderGate.renderWhenReady(this::renderRelatedList);
    }

    private void renderRelatedList() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Video current = getVideo();
        String currentId = current != null ? current.videoId : null;
        bindQueueCard(current, currentId, findQueueGroupId(current));
        if (mMoreVideos != null) {
            mMoreVideos.setVideos(mRelatedVideos);
            updateMoreVideosHint();
        }

        // Queue rows count as "content landed" too: a playlist whose suggestions are ALL queue
        // would otherwise leave the Up-next skeleton pulsing until its safety timeout.
        // NEWTUBE(watch-jump): hide it once the rows are actually in the list - submitList diffs
        // off the main thread, and hiding before its commit left "Up next" blank for ~1 s.
        submitRelatedWindow(() -> {
            if (!mRelatedVideos.isEmpty() || !mQueueVideos.isEmpty()) {
                hideRelatedSkeleton(); // real rows are in; stop pulsing
            }
        });
    }

    /** MEETUBE(issue #74): a new video: "All" again, and no channel chip until its metadata names one. */
    private void resetRelatedChips() {
        RxHelper.disposeActions(mChipAction);
        mChipMetadata = null;
        mChipVideos = null;
        mChipPage = null;
        mPickedChip = mAllChip;
        if (mRelatedChips != null) {
            mRelatedChips.setVisibility(View.GONE);
            mRelatedTrack.setVisibility(View.GONE);
            syncRelatedChips();
            mRelatedChips.scrollTo(0, 0);
        }
    }

    private void bindRelatedChips(MediaItemMetadata metadata) {
        if (mRelatedChips == null || isShortsMode() || isRelatedHidden()
                || TextUtils.isEmpty(metadata.getAuthor()) || TextUtils.isEmpty(metadata.getChannelId())) {
            return;
        }
        mChipMetadata = metadata;
        mChannelChip.setText(getString(R.string.mobile_watch_chip_channel, metadata.getAuthor()));
        boolean segment = com.newtube.mobile.ui.common.Glass.chipStyle(this)
                == com.newtube.mobile.ui.common.Glass.CHIPS_SEGMENT;
        mRelatedChips.setVisibility(segment ? View.GONE : View.VISIBLE);
        mRelatedTrack.setVisibility(segment ? View.VISIBLE : View.GONE);
        syncRelatedChips(); // the track's labels carry the channel's name
    }

    private void onRelatedChip(TextView chip) {
        if (chip == mPickedChip) {
            return;
        }
        RxHelper.disposeActions(mChipAction);
        mChipPage = null;
        mPickedChip = chip;
        mChipVideos = chip == mAllChip ? null : chip == mChannelChip ? new ArrayList<>() : relatedSeen(chip == mWatchedChip);
        mRelatedWindow = RELATED_WINDOW_INITIAL;
        submitRelatedWindow();
        if (chip == mChannelChip) {
            loadChannelChip(null);
        }
    }

    /**
     * MEETUBE(issue #104): the related videos you have watched ({@code watched}) or not - by your
     * history: YouTube's progress on the card, this phone's resume points and the History feed.
     */
    private List<Video> relatedSeen(boolean watched) {
        java.util.Set<String> history = new java.util.HashSet<>();
        List<Video> feed = com.newtube.mobile.ui.common.FeedCache.get(
                com.liskovsoft.mediaserviceinterfaces.data.MediaGroup.TYPE_HISTORY);
        if (feed != null) {
            for (Video v : feed) {
                if (v != null) {
                    history.add(v.videoId);
                }
            }
        }
        VideoStateService states = VideoStateService.instance(this);
        List<Video> picked = new ArrayList<>();
        for (Video v : mRelatedVideos) {
            boolean seen = v.percentWatched > 0 || history.contains(v.videoId) || states.getByVideoId(v.videoId) != null;
            if (seen == watched) {
                picked.add(v);
            }
        }
        return picked;
    }

    /**
     * MEETUBE(issue #105): the picked chip is a solid pill in the text colour, the rest glass.
     * New to you and Watched show only while the related list has some of each.
     */
    private void syncRelatedChips() {
        if (mRelatedChipViews == null) {
            return;
        }
        boolean split = mPickedChip == mNewChip || mPickedChip == mWatchedChip
                || (!relatedSeen(true).isEmpty() && !relatedSeen(false).isEmpty());
        mNewChip.setVisibility(split ? View.VISIBLE : View.GONE);
        mWatchedChip.setVisibility(split ? View.VISIBLE : View.GONE);
        for (TextView chip : mRelatedChipViews) {
            boolean picked = chip == mPickedChip;
            if (picked) {
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setCornerRadius(18 * getResources().getDisplayMetrics().density);
                bg.setColor(getColorInt(R.color.mobile_color_on_surface));
                chip.setBackground(bg);
            } else {
                chip.setBackground((android.graphics.drawable.Drawable) chip.getTag(R.id.mobile_watch_related_chips));
            }
            chip.setTextColor(getColorInt(picked ? R.color.mobile_color_background : R.color.mobile_color_on_surface));
        }
        // The segment bar shows the same chips: the visible ones, the picked one on the pill.
        if (mRelatedTrack != null) {
            mTrackChips.clear();
            List<CharSequence> labels = new ArrayList<>();
            for (TextView chip : mRelatedChipViews) {
                if (chip.getVisibility() == View.VISIBLE) {
                    mTrackChips.add(chip);
                    labels.add(chip.getText());
                }
            }
            mRelatedTrack.setChips(labels);
            mRelatedTrack.setSelected(mTrackChips.indexOf(mPickedChip));
        }
    }

    /** The channel's uploads: the first page when {@code page} is null, else the one after it. */
    private void loadChannelChip(@Nullable com.liskovsoft.mediaserviceinterfaces.data.MediaGroup page) {
        MediaItemMetadata metadata = mChipMetadata;
        List<Video> target = mChipVideos;
        if (metadata == null || target == null || (page != null && page.getNextPageKey() == null)) {
            return;
        }
        mChipPage = null; // one request per page
        com.liskovsoft.mediaserviceinterfaces.ContentService content =
                YouTubeServiceManager.instance().getContentService();
        // A channel item without a reload key or a bare playlist resolves to the channel's uploads grid.
        mChipAction = RxHelper.execute(page == null
                        ? content.getGroupObserve(com.liskovsoft.smartyoutubetv2.common.app.models.data.SimpleMediaItem.from(metadata))
                        : content.continueGroupObserve(page),
                group -> runOnUiThread(() -> onChannelChipPage(target, group)),
                error -> android.util.Log.w("MobilePlayback", "Channel chip: " + error));
    }

    private void onChannelChipPage(List<Video> target, @Nullable com.liskovsoft.mediaserviceinterfaces.data.MediaGroup group) {
        if (mChipVideos != target || group == null || isDestroyed()) {
            return;
        }
        mChipPage = group;
        Video current = getVideo();
        for (Video v : VideoGroup.from(group).getVideos()) {
            if (v != null && !com.newtube.mobile.ui.common.ShortsFilter.isShort(v)
                    && (current == null || !Helpers.equals(current.videoId, v.videoId))) {
                target.add(v);
            }
        }
        submitRelatedWindow();
    }

    /**
     * How many Up-next rows are bound on open, and how many more each scroll-to-bottom reveals.
     * Bound rows are NOT a preview: this list never recycles, so a bound row is a thumbnail
     * downloaded whether or not it is ever scrolled into view. Twelve is comfortably more than a
     * portrait screenful, so the window is never visibly the reason the list ends.
     */
    private static final int RELATED_WINDOW_INITIAL = 12;
    private static final int RELATED_WINDOW_STEP = 12;
    private int mRelatedWindow = RELATED_WINDOW_INITIAL;

    /** Push the currently revealed slice of {@link #mRelatedVideos} into the Up-next adapter. */
    private void submitRelatedWindow() {
        submitRelatedWindow(null);
    }

    /** As {@link #submitRelatedWindow()}; {@code onCommitted} runs once the list shows the slice. */
    private void submitRelatedWindow(Runnable onCommitted) {
        if (mRelatedAdapter != null) {
            List<Video> shown = mChipVideos != null ? mChipVideos : mRelatedVideos;
            int end = Math.min(mRelatedWindow, shown.size());
            mRelatedAdapter.submitList(new ArrayList<>(shown.subList(0, end)), onCommitted);
        } else if (onCommitted != null) {
            onCommitted.run();
        }
        syncRelatedChips();
        if (mWatchRelatedLabel != null && !mRelatedVideos.isEmpty()) {
            mWatchRelatedLabel.setText(R.string.mobile_watch_related);
            mWatchRelatedLabel.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Id of the suggestion row that is the queue, or null when the video isn't playing from one.
     *
     * <p>The test is "the row contains the video that is PLAYING". A playlist/queue row always
     * does (it's the whole playlist); an algorithmic related row never does. That is
     * self-maintaining - it leans on no row ordering, no group title we don't control, and no
     * playlist-id plumbing that varies by entry point (section playlist vs /next playlist vs the
     * local Playlist queue).</p>
     *
     * <p>Two things bound what can reach here. Upstream, {@code Video.isSectionPlaylistEnabled}
     * only pushes the row a video was opened from when that row really is a playlist, so an
     * ordinary feed/search/subscriptions open delivers no section row. And here, the video must
     * be playing from a playlist a person chose - see {@link #isChosenPlaylist}.</p>
     */
    private Integer findQueueGroupId(Video current) {
        String currentId = current != null ? current.videoId : null;

        if (currentId == null || !isChosenPlaylist(current)) {
            return null;
        }

        for (Map.Entry<Integer, List<Video>> entry : mSuggestionVideos.entrySet()) {
            List<Video> vids = entry.getValue();
            if (vids == null) {
                continue;
            }
            for (Video v : vids) {
                if (v != null && currentId.equals(v.videoId)) {
                    return entry.getKey();
                }
            }
        }

        return null;
    }

    /**
     * Is this video playing from a playlist a PERSON chose, rather than one YouTube attached?
     *
     * <p>Having a playlist id isn't enough. YouTube hangs an auto-radio off ordinary videos - the
     * home-feed item that opened as "Playing from Relaxing July Morning Jazz - 1 / 20" carried
     * {@code playlistId=RDrLNaachBzBI}, i.e. literally {@code "RD" + its own videoId} - and its
     * {@code /next} panel row does contain the playing video, so the row test alone brought the
     * card back on exactly the videos this was supposed to clear it from. YouTube keeps that radio
     * to itself: no panel on a plain video, panel only once you open a playlist.</p>
     *
     * <p>So: a playlist id that isn't a radio. {@code RD...} covers auto-radios and Mixes; real
     * playlists are {@code PL/LL/UU/OL/FL...}. The trade is that deliberately tapping a Mix card
     * shows no card either (its videos still list under Up next) - worth it, because a Mix and an
     * auto-radio are the same object here and there is no signal left at this point to tell "the
     * user picked this mix" from "YouTube attached one".</p>
     */
    private boolean isChosenPlaylist(Video video) {
        String playlistId = video != null ? video.getPlaylistId() : null;

        return playlistId != null && !playlistId.startsWith("RD");
    }

    /**
     * Binds (or hides) the "Playing from X - i/N" card.
     *
     * <p>Position and size come from {@code PlaylistInfo} when the /next document carried one -
     * it's YouTube's own count, and it stays right for playlists longer than the rows we've paged
     * in so far. Otherwise they're derived from the rows actually in hand, which is the honest
     * number for a locally-built queue.</p>
     */
    private void bindQueueCard(Video current, String currentId, Integer queueId) {
        if (mQueueCard == null) {
            return;
        }

        if (mQueueVideos.isEmpty()) {
            mQueueCard.setVisibility(View.GONE);
            if (mQueueAdapter != null) {
                mQueueAdapter.submitList(new ArrayList<>());
            }
            return;
        }

        // WHICH playlist are we actually listing? Two different things can own the queue and they
        // do NOT agree: the SECTION playlist (the playlist row the video was opened from - the app
        // auto-advances through it) and the /next playlist. They can describe different lists, and
        // naming one after the other reads as a bug - the card said "Playing from <mix>" over a
        // list that wasn't the mix. So PlaylistInfo is trusted only when the queue is NOT the
        // section group.
        // ...unless it names the SAME playlist we are listing (Play all / a video opened from a
        // playlist page): then the two agree and PlaylistInfo is the better source, because its
        // size is the playlist's server-side total while the rows in hand are only the first page
        // ("1 / 15" over a 30-video Watch later).
        VideoGroup sectionGroup = current != null ? current.getGroup() : null;
        boolean isSectionQueue = sectionGroup != null && queueId != null
                && queueId.equals(sectionGroup.getId());
        PlaylistInfo currentInfo = current != null ? current.playlistInfo : null;
        boolean namesSameList = currentInfo != null && current != null
                && Helpers.equals(currentInfo.getPlaylistId(), current.getPlaylistId());
        PlaylistInfo info = !isSectionQueue || namesSameList ? currentInfo : null;

        VideoGroup queueGroup = queueId != null ? mSuggestionGroups.get(queueId) : null;
        // Prefer the title of the group whose videos are on screen, so the name always describes
        // the list under it.
        String name = queueGroup != null ? queueGroup.getTitle() : null;
        if (TextUtils.isEmpty(name) && info != null) {
            name = info.getTitle();
        }
        if (TextUtils.isEmpty(name)) {
            name = getString(R.string.mobile_watch_queue_fallback);
        }
        mQueueTitle.setText(getString(R.string.mobile_watch_queue_from, name));

        int size = info != null ? info.getSize() : 0;
        int index = info != null ? info.getCurrentIndex() + 1 : 0; // PlaylistInfo index is 0-based
        if (size <= 0 || index <= 0 || index > size) {
            // No usable server count: fall back to this video's spot among the rows we hold.
            size = mQueueVideos.size();
            index = indexOfQueueVideo(currentId) + 1; // -1 (absent) collapses to 0 = "unknown"
        }

        if (index > 0 && size > 0) {
            mQueueSubtitle.setText(getString(R.string.mobile_watch_queue_position, index, size));
            mQueueSubtitle.setVisibility(View.VISIBLE);
        } else {
            mQueueSubtitle.setVisibility(View.GONE);
        }

        mQueueAdapter.setCurrentVideoId(currentId);
        mQueueAdapter.submitList(new ArrayList<>(mQueueVideos));
        mQueueCard.setVisibility(View.VISIBLE);
        applyQueueExpanded();
    }

    /** Position of {@code videoId} among the queue rows in hand, or -1. */
    private int indexOfQueueVideo(String videoId) {
        if (videoId == null) {
            return -1;
        }

        for (int i = 0; i < mQueueVideos.size(); i++) {
            Video v = mQueueVideos.get(i);
            if (v != null && videoId.equals(v.videoId)) {
                return i;
            }
        }

        return -1;
    }

    private void toggleQueueExpanded() {
        mQueueExpanded = !mQueueExpanded;
        float chevronFrom = mQueueChevron != null ? mQueueChevron.getRotation() : 0f;
        applyQueueExpanded();
        if (mQueueChevron != null) {
            // NEWTUBE(motion): the chevron spins like the description's instead of snapping.
            mQueueChevron.setRotation(chevronFrom);
            mQueueChevron.animate().rotation(mQueueExpanded ? 180f : 0f).setDuration(180)
                    .setInterpolator(Motion.STANDARD).start();
        }

        // Jump straight to the playing row so expanding a long playlist doesn't open on item 1.
        // Matched by videoId, NOT List.indexOf: Video.equals is a composite hash (playlistId,
        // sectionId, channelGroupId, mediaItem, ...), so the playing Video and its own row in the
        // queue - which arrived in a different group - almost never compare equal. indexOf
        // returned -1 and the scroll silently no-opped on exactly the long playlists it exists
        // for. Everything else in this card already keys off videoId; this now matches.
        if (mQueueExpanded) {
            int index = indexOfQueueVideo(getVideo() != null ? getVideo().videoId : null);
            if (index > 0) {
                mQueueList.scrollToPosition(index);
            }
        }
    }

    private void applyQueueExpanded() {
        if (mQueueList == null || mQueueChevron == null) {
            return;
        }

        mQueueList.setVisibility(mQueueExpanded ? View.VISIBLE : View.GONE);
        mQueueChevron.animate().cancel();
        mQueueChevron.setRotation(mQueueExpanded ? 180f : 0f);
    }

    // ---------------------------------------------------------------------------------
    // PlayerEngine - real, delegates to ExoPlayerController (playback-critical).
    // ---------------------------------------------------------------------------------

    @Override
    public void prebuildNextSource(MediaItemFormatInfo formatInfo) {
        // NEWTUBE(prepare-stash): pre-build + stash the likely next video's MediaSource so the
        // auto-advance open skips the MPD gen+parse (TV keeps the no-op PlayerEngine default).
        mExoPlayerController.prebuildNextSource(formatInfo, false);
    }

    @Override
    public void prebuildNextSource(MediaItemFormatInfo formatInfo, boolean preloadMedia) {
        mExoPlayerController.prebuildNextSource(formatInfo, preloadMedia);
        if (preloadMedia && mShorts != null && !mBackgroundAudioMode) {
            mShorts.preparePeek(formatInfo, mExoPlayerController); // MEETUBE(shorts peek)
        }
    }

    @Override
    public void openSabr(MediaItemFormatInfo formatInfo) {
        mCaptionInfo = formatInfo; // MEETUBE(transcript)
        mExoPlayerController.openSabr(formatInfo);
    }

    @Override
    public boolean allowsAutomaticSourceRecovery() {
        return mExoPlayerController == null || mExoPlayerController.allowsAutomaticSourceRecovery();
    }

    @Override
    public long getMediaReadinessHoldMs() {
        return mExoPlayerController != null ? mExoPlayerController.getMediaReadinessHoldMs() : 0;
    }

    @Override
    public void openDash(MediaItemFormatInfo formatInfo) {
        mCaptionInfo = formatInfo; // MEETUBE(transcript)
        mExoPlayerController.openDash(formatInfo);
    }

    @Override
    public void openDash(InputStream dashManifest) {
        mExoPlayerController.openDash(dashManifest);
    }

    @Override
    public void openDashUrl(String dashManifestUrl) {
        mExoPlayerController.openDashUrl(dashManifestUrl);
    }

    @Override
    public void openHlsUrl(String hlsPlaylistUrl) {
        mExoPlayerController.openHlsUrl(hlsPlaylistUrl);
    }

    @Override
    public void openUrlList(List<String> urlList) {
        mExoPlayerController.openUrlList(urlList);
    }

    // JOYBOY(v934): direct link with headers / separate audio
    @Override
    public void openUrlList(List<String> urlList, java.util.Map<String, String> headers, String audioUrl) {
        mExoPlayerController.openUrlList(urlList, headers, audioUrl);
    }

    @Override
    public void openHlsVod(MediaItemFormatInfo formatInfo) {
        mCaptionInfo = formatInfo; // MEETUBE(transcript)
        mExoPlayerController.openHlsVod(formatInfo);
    }

    @Override
    public void openProgressive(MediaItemFormatInfo formatInfo) {
        mCaptionInfo = formatInfo; // MEETUBE(transcript)
        mExoPlayerController.openProgressive(formatInfo);
    }

    @Override
    public void openMerged(MediaItemFormatInfo formatInfo, String hlsPlaylistUrl) {
        mCaptionInfo = formatInfo; // MEETUBE(transcript)
        mExoPlayerController.openMerged(formatInfo, hlsPlaylistUrl);
    }

    @Override
    public void openMerged(InputStream dashManifest, String hlsPlaylistUrl) {
        mExoPlayerController.openMerged(dashManifest, hlsPlaylistUrl);
    }

    @Override
    public long getPositionMs() {
        return mExoPlayerController.getPositionMs();
    }

    @Override
    public long getForbiddenMediaStartMs() {
        return mExoPlayerController != null ? mExoPlayerController.getMediaRequests().forbiddenStartMs() : -1;
    }

    @Override
    public long getLowestServedMediaStartMs() {
        return mExoPlayerController != null ? mExoPlayerController.getMediaRequests().lowestServedStartMs() : -1;
    }

    @Override
    public long getHighestServedMediaStartMs() {
        return mExoPlayerController != null ? mExoPlayerController.getMediaRequests().highestServedStartMs() : -1;
    }

    /** NEWTUBE(wall-swap): the recovery walk's answer for an open waiting on a refused request. */
    @Override
    public boolean repointMedia(MediaItemFormatInfo formatInfo) {
        return mExoPlayerController != null && mExoPlayerController.repointMedia(formatInfo);
    }

    @Override
    public void cancelRepoint() {
        if (mExoPlayerController != null) {
            mExoPlayerController.cancelRepoint();
        }
    }

    @Override
    public boolean isRepointPending() {
        return mExoPlayerController != null && mExoPlayerController.isRepointPending();
    }

    @Override
    public void setPositionMs(long positionMs) {
        mExoPlayerController.setPositionMs(positionMs);
    }

    /** NEWTUBE(resume-seek): history resume lands on the keyframe at or before the saved spot. */
    @Override
    public void setResumePositionMs(long positionMs) {
        mExoPlayerController.seekToResumePosition(positionMs);
    }

    /** NEWTUBE(resume-seek): never earlier than a snapped-over resume target not yet watched back. */
    @Override
    public long getHistoryPositionMs() {
        return mExoPlayerController.getHistoryPositionMs();
    }

    @Override
    public long getDurationMs() {
        long durationMs = mExoPlayerController.getDurationMs();

        long liveDurationMs = getVideo() != null ? getVideo().getLiveDurationMs() : 0;

        // Belt-and-braces for live: besides the legacy too-big clamp, catch a broken engine
        // duration (media3 computed a negative live window before the LiveDashManifestParser fix;
        // <=0 keeps the timebar dead and live-edge math garbage) and fall back to wall-clock
        // "now - stream start". liveDurationMs != 0 only for live videos (Video.getLiveDurationMs
        // returns 0 when startTimeMs == 0), so VOD's transient pre-prepare durationMs <= 0 is
        // never touched.
        if ((durationMs <= 0 || durationMs > Video.MAX_LIVE_DURATION_MS) && liveDurationMs != 0) {
            durationMs = liveDurationMs;
        }

        return durationMs;
    }

    @Override
    public void setPlayWhenReady(boolean play) {
        mExoPlayerController.setPlayWhenReady(play);
    }

    @Override
    public boolean getPlayWhenReady() {
        return mExoPlayerController.getPlayWhenReady();
    }

    @Override
    public boolean isPlaying() {
        return mExoPlayerController.isPlaying();
    }

    @Override
    public boolean isLoading() {
        return mExoPlayerController.isLoading();
    }

    @Override
    public List<FormatItem> getVideoFormats() {
        return mExoPlayerController.getVideoFormats();
    }

    @Override
    public List<FormatItem> getAudioFormats() {
        return mExoPlayerController.getAudioFormats();
    }

    @Override
    public List<FormatItem> getSubtitleFormats() {
        return mExoPlayerController.getSubtitleFormats();
    }

    @Override
    public void setFormat(FormatItem option) {
        mExoPlayerController.selectFormat(option);
    }

    @Override
    public FormatItem getVideoFormat() {
        return mExoPlayerController.getVideoFormat();
    }

    @Override
    public FormatItem getAudioFormat() {
        return mExoPlayerController.getAudioFormat();
    }

    @Override
    public FormatItem getSubtitleFormat() {
        return mExoPlayerController.getSubtitleFormat();
    }

    @Override
    public boolean isEngineInitialized() {
        return mPlayer != null;
    }

    @Override
    public void restartEngine() {
        destroyPlayerObjects();
        createPlayerObjects();
    }

    @Override
    public void reloadPlayback() {
        if (mPlayer != null) {
            mPresenter.onEngineReleased();
            mPresenter.onEngineInitialized();
        }
    }

    @Override
    public void blockEngine(boolean block) {
        mIsEngineBlocked = block;
    }

    @Override
    public boolean isEngineBlocked() {
        return mIsEngineBlocked;
    }

    @Override
    public boolean isInPIPMode() {
        return mIsInPip;
    }

    @Override
    public boolean containsMedia() {
        return mExoPlayerController != null && mExoPlayerController.containsMedia();
    }

    @Override
    public void setSpeed(float speed) {
        mExoPlayerController.setSpeed(speed);
    }

    @Override
    public float getSpeed() {
        return mExoPlayerController.getSpeed();
    }

    @Override
    public float getEffectiveSpeed() {
        return mExoPlayerController != null ? mExoPlayerController.getEffectiveSpeed() : getSpeed();
    }

    @Override
    public void setPitch(float pitch) {
        mExoPlayerController.setPitch(pitch);
    }

    @Override
    public float getPitch() {
        return mExoPlayerController.getPitch();
    }

    @Override
    public void setVolume(float volume) {
        mExoPlayerController.setVolume(volume);
    }

    @Override
    public float getVolume() {
        return mExoPlayerController.getVolume();
    }

    // JOYBOY(v942): named Joyboy actions (from MtBar / MtTvPlayer) mapped onto MeeTube's own internals. Called via JoyboyPlayerHost.onJoyboyAction; true = handled.
    public boolean handleJoyboyAction(String action) {
        if (action == null) {
            return false;
        }
        try {
            if (action.startsWith("fit:")) {
                int n = 0;
                try {
                    n = Integer.parseInt(action.substring(4));
                } catch (NumberFormatException e) {
                    n = 0;
                }
                switch (n) {
                    case 1:
                        setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL);
                        break;
                    case 2:
                        setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                        break;
                    case 3:
                        setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH);
                        break;
                    default:
                        setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
                        break;
                }
                return true;
            }
            switch (action) {
                case "fit":
                    int cur = getResizeMode();
                    int next;
                    if (cur == androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL) {
                        next = 2;
                    } else if (cur == androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                        next = 3;
                    } else if (cur == androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH) {
                        next = 0;
                    } else {
                        next = 1;
                    }
                    return handleJoyboyAction("fit:" + next);
                case "pip":
                    enterPipMode();
                    return true;
                case "lock":
                    setPlayerLocked(!mPlayerLocked);
                    return true;
                case "rotate":
                    toggleFullscreen();
                    return true;
                case "prev":
                    stepVideo(-1);
                    return true;
                case "next":
                    stepVideo(1);
                    return true;
                case "speed":
                    float curSpeed = getSpeed();
                    float nextSpeed = SPEED_PRESETS[SPEED_PRESETS.length - 1];
                    for (float preset : SPEED_PRESETS) {
                        if (preset > curSpeed + 0.001f) {
                            nextSpeed = preset;
                            break;
                        }
                    }
                    setSpeed(nextSpeed);
                    return true;
                case "cc":
                    toggleCaptions();
                    return true;
                case "quality":
                    openPlayerMenu();
                    return true;
                case "audio":
                    showAudioTrackSheet();
                    return true;
                case "sleep":
                    showSleepTimerSheet();
                    return true;
                case "chapters":
                    showChaptersSheet();
                    return true;
                case "download":
                    onDownloadTapped();
                    return true;
                case "audio_only":
                    setAudioOnly(!mAudioOnly);
                    return true;
                case "playpause":
                    togglePlayPause();
                    return true;
                default:
                    if (action.startsWith("speed:")) {
                        try {
                            setSpeed(Float.parseFloat(action.substring(6)));
                            return true;
                        } catch (NumberFormatException e) {
                            return false;
                        }
                    }
                    return false;
            }
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void setResizeMode(int mode) {
        if (isShortsMode()) {
            if (ShortsOverlay.fillsScreen(this) && mVideoAspect < SHORTS_FILL_MAX_ASPECT) {
                mode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM; // shorts fill a phone
            } else if (ShortsOverlay.fillsScreen(this)) {
                // A square or wide "short" zoomed to the screen kept a strip of its middle: whole,
                // like YouTube, with the ambient glow in the bands.
                mode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT;
            }
        } else if (mPortraitFull && !isLandscape() || mCommentsSide || mChatSide
                || mShorts != null && mShorts.isCommentsOpen()) {
            // MEETUBE(fullscreen): the whole picture, upright; ambient mode fills the bands. A pinch's
            // "fill" (landscape) would crop a 16:9 video to a sliver of its middle here - and to
            // its middle beside the landscape comments (MEETUBE(fullscreen-actions)), and a short
            // to a band of its middle in the third above its comments (#27).
            mode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT;
        }
        if (mPlayerView != null) {
            mPlayerView.setResizeMode(mode);
        }
        applyControlsInsets(); // fill/zoom: the controls span the width (controlsStrip)
    }

    /**
     * A short this narrow or narrower (width / height) fills a phone. 9:16 is 0.56, 3:4 is 0.75: a
     * tall short loses a sliver of its sides, a 4:5, square or wide one would lose most of itself.
     * 0 (size not known yet) fills, as every short did.
     */
    private static final float SHORTS_FILL_MAX_ASPECT = 0.7f;

    @Override
    public int getResizeMode() {
        return mPlayerView != null ? mPlayerView.getResizeMode() : RESIZE_MODE_DEFAULT;
    }

    @Override
    public void setZoomPercents(int percents) {
        // Pinch-to-zoom itself is handled by PinchZoomLayout -> onPinchZoom (snap fill/fit via
        // resize mode). TODO Wave N: the dialog's percent-based zoom values (50%-300%).
    }

    @Override
    public void setAspectRatio(float ratio) {
        // TODO Wave N: forced aspect-ratio setting (HQDialog quality sheet, Wave 4).
    }

    @Override
    public void setRotationAngle(int angle) {
        // TODO Wave N: forced video-frame rotation (rare edge case, HQDialog sheet).
    }

    @Override
    public void setVideoFlipEnabled(boolean enabled) {
        // TODO Wave N: forced video-frame flip (rare edge case, HQDialog sheet).
    }

    @Override
    public void setVideoGravity(int gravity) {
        // TODO Wave N: forced video-frame gravity (rare edge case, HQDialog sheet).
    }

    // ---------------------------------------------------------------------------------
    // PlayerManager
    // ---------------------------------------------------------------------------------

    @Override
    public void setVideo(Video item) {
        if (item != null && item.videoId != null) {
            SessionWarmup.onPlaybackRequested();
            if (!Helpers.equals(item.videoId, mWatchVideoId)) {
                cancelClose(); // NEWTUBE(motion): a video picked while Back was closing this player
                // NEWTUBE(motion): or while a host's X was closing its card - this session goes on.
                MiniPlayerBridge.cancelClosing();
            }
        }
        if (mExoPlayerController != null) {
            mExoPlayerController.setVideo(item);
        }

        // Same keep-last-known-good rule as bindWatchVideo: a same-video rebind with an empty
        // title (bare error-reload Video) must not blank the controls title.
        boolean sameVideo = item != null && Helpers.equals(item.videoId, mWatchVideoId);
        if (!sameVideo) {
            mVideoShapePending = true; // NEWTUBE(issue #69): known again at its first frame
            showPlaybackNotice(null);
            hideSkipSegment();
            if (mNextCountdown != null) {
                mNextCountdown.hide();
            }
            cancelHoldSpeed();
            if (mVideoArea != null) {
                mVideoArea.removeCallbacks(mShortsSingleTap); // MEETUBE(issue #172): taps stay with their video.
                mVideoArea.resetZoom(); // MEETUBE(issue #55): each video starts whole
            }
            // NEWTUBE(gestures): a drag held across an autoplay would have seeked the new video to
            // the old one's spot (the bar's and a swipe's alike): it ends here, seeking nothing.
            if (mTimeBar != null) {
                mTimeBar.cancelScrub();
            }
        }
        if (!sameVideo || (item != null && !TextUtils.isEmpty(item.getTitleFull()))) {
            setTitle(item != null ? item.getTitleFull() : null);
        }
        bindWatchVideo(item);

        // LOADING STILL: a DIFFERENT video was just set on this (reused) view. The texture still
        // shows the previous video's last frame and the new audio starts as soon as it buffers,
        // so cover the stale frame with the new video's thumbnail until ITS first frame renders
        // (YouTube does exactly this). Also gives the very first open a thumbnail instead of black.
        runOnUiThread(() -> {
            maybeShowLoadingStill(item);
            showAudioCover(item); // MEETUBE(audio-only): the next video's thumbnail
            updateMusicPill(item);
        });

        // LOADING SKELETON: a new video is being set on the view and its related feed hasn't landed
        // yet. Covers the FIRST open too (clearSuggestions only fires on subsequent loads).
        if (item != null && mRelatedVideos.isEmpty()) {
            runOnUiThread(this::showRelatedSkeleton);
        }

        // CASTING: an active session claims every newly selected video (see maybeRouteVideoToCast).
        if (item != null && item.videoId != null) {
            final String videoId = item.videoId;
            runOnUiThread(() -> maybeRouteVideoToCast(videoId));
        }
    }

    @Override
    public Video getVideo() {
        return mExoPlayerController != null ? mExoPlayerController.getVideo() : null;
    }

    // finish()/finishReally() are inherited from MobileActivity/MotherActivity, which already
    // implement the PlayerManager contract (parent-aware back navigation via ViewManager). No
    // override needed here; player resource cleanup happens in onDestroy() above.

    @Override
    public void showBackground(String url) {
        // TODO Wave N: idle/loading background art (no UriBackgroundManager equivalent yet).
    }

    @Override
    public void showBackgroundColor(int colorResId) {
        // TODO Wave N: idle/loading background art.
    }

    @Override
    public void resetPlayerState() {
        if (mExoPlayerController != null) {
            mExoPlayerController.resetPlayerState();
        }
    }

    @Override
    public boolean isEmbed() {
        return false;
    }

    /**
     * NEWTUBE(diagnostics): debug-only per-chunk load logging under tag {@code NetPath}. media3's
     * stock {@code EventLogger} prints loadError only - loadStarted/loadCompleted (bytes, duration,
     * media position per chunk) never reach logcat with it. One dense line per event:
     * {@code load[S|C|X|E] <dataType>/<trackType> <uri-tail> bytes=<n> ms=<n> pos=<n>}
     * (S=started, C=completed, X=canceled, E=error; E appends the exception class+message).
     * Registered next to EventLogger in {@link #createPlayerObjects()} behind the same
     * {@code BuildConfig.DEBUG} gate. Uses android.util.Log directly so lines always reach logcat.
     */
    private static final class NetPathLoadListener implements androidx.media3.exoplayer.analytics.AnalyticsListener {
        private final Context mContext;

        NetPathLoadListener(Context context) {
            mContext = context.getApplicationContext();
        }

        @Override
        public void onLoadStarted(EventTime eventTime, androidx.media3.exoplayer.source.LoadEventInfo loadEventInfo,
                androidx.media3.exoplayer.source.MediaLoadData mediaLoadData, int retryCount) {
            log("load[S]", loadEventInfo, mediaLoadData, null);
        }

        @Override
        public void onLoadCompleted(EventTime eventTime, androidx.media3.exoplayer.source.LoadEventInfo loadEventInfo,
                androidx.media3.exoplayer.source.MediaLoadData mediaLoadData) {
            log("load[C]", loadEventInfo, mediaLoadData, null);
        }

        @Override
        public void onLoadCanceled(EventTime eventTime, androidx.media3.exoplayer.source.LoadEventInfo loadEventInfo,
                androidx.media3.exoplayer.source.MediaLoadData mediaLoadData) {
            log("load[X]", loadEventInfo, mediaLoadData, null);
        }

        @Override
        public void onLoadError(EventTime eventTime, androidx.media3.exoplayer.source.LoadEventInfo loadEventInfo,
                androidx.media3.exoplayer.source.MediaLoadData mediaLoadData, java.io.IOException error,
                boolean wasCanceled) {
            log("load[E]", loadEventInfo, mediaLoadData, error);
        }

        @Override
        public void onPositionDiscontinuity(EventTime eventTime,
                androidx.media3.common.Player.PositionInfo oldPosition,
                androidx.media3.common.Player.PositionInfo newPosition, int reason) {
            android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                    com.liskovsoft.smartyoutubetv2.common.misc.NetPath.context()
                            + " position-discontinuity reason=" + discontinuityReason(reason)
                            + " from=" + oldPosition.positionMs + " to=" + newPosition.positionMs
                            + " delta=" + (newPosition.positionMs - oldPosition.positionMs));
        }

        @Override
        public void onDownstreamFormatChanged(EventTime eventTime,
                androidx.media3.exoplayer.source.MediaLoadData mediaLoadData) {
            androidx.media3.common.Format format = mediaLoadData.trackFormat;
            if (format == null) {
                return;
            }
            android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                    com.liskovsoft.smartyoutubetv2.common.misc.NetPath.context()
                            + " track-selected type=" + mediaLoadData.trackType
                            + " id=" + com.liskovsoft.smartyoutubetv2.common.misc.NetPath.trunc(format.id, 32)
                            + " mime=" + format.sampleMimeType
                            + " bitrate=" + format.bitrate
                            + " size=" + format.width + 'x' + format.height
                            + " fps=" + format.frameRate);
        }

        private void log(String event,
                androidx.media3.exoplayer.source.LoadEventInfo info,
                androidx.media3.exoplayer.source.MediaLoadData data,
                @Nullable Exception error) {
            StringBuilder line = new StringBuilder(
                    com.liskovsoft.smartyoutubetv2.common.misc.NetPath.context())
                    .append(' ').append(event)
                    .append(" lid=").append(info.loadTaskId)
                    .append(' ').append(data.dataType).append('/').append(data.trackType)
                    .append(" host=").append(info.uri.getHost())
                    .append(' ').append(uriTail(info.uri))
                    .append(" req=").append(info.dataSpec.position).append('+').append(info.dataSpec.length)
                    .append(" bytes=").append(info.bytesLoaded)
                    .append(" ms=").append(info.loadDurationMs)
                    .append(" pos=").append(data.mediaStartTimeMs)
                    .append(" net=").append(activeNetwork());
            appendResponseSummary(line, info.responseHeaders);
            if (error != null) {
                // Errors get the request's byte range plus the URL's declared length/version
                // params: a req beyond clen, or an lmt that differs between /player mints, each
                // pin a distinct failure mode of a deterministic per-range 403 (seen on-device).
                if (info.uri.isHierarchical()) {
                    appendQueryParam(line, info.uri, "clen");
                    appendQueryParam(line, info.uri, "lmt");
                }
                line.append(' ').append(error.getClass().getSimpleName()).append(": ")
                        .append(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.trunc(error.getMessage(), 120));
                android.util.Log.w(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG, line.toString());
                // On HTTP errors log the rejection body plus a replay-useful but credential-safe
                // URL fingerprint. Complete googlevideo URLs contain signed credentials and must
                // never enter logcat.
                androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException http =
                        findInvalidResponseCode(error);
                if (http != null) {
                    StringBuilder detail = new StringBuilder(
                            com.liskovsoft.smartyoutubetv2.common.misc.NetPath.context())
                            .append(" load[E-http] code=").append(http.responseCode);
                    byte[] body = http.responseBody;
                    if (body != null && body.length > 0) {
                        detail.append(" body[").append(body.length).append("] hash=")
                                .append(fingerprint(body)).append(" text=").append(printable(body, 240));
                    }
                    android.util.Log.w(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG, detail.toString());
                    android.util.Log.w(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                            com.liskovsoft.smartyoutubetv2.common.misc.NetPath.context()
                                    + " load[E-request] " + safeRequestFingerprint(info));
                }
            } else {
                android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG, line.toString());
            }
        }

        private static void appendResponseSummary(StringBuilder line,
                java.util.Map<String, java.util.List<String>> headers) {
            if (headers == null || headers.isEmpty()) {
                line.append(" responseHeaders=none");
                return;
            }
            line.append(" responseHeaders=y");
            appendHeader(line, headers, "content-length", "respLen");
            appendHeader(line, headers, "content-range", "contentRange");
            appendHeader(line, headers, "accept-ranges", "acceptRanges");
            appendHeader(line, headers, "content-encoding", "encoding");
            appendHeader(line, headers, "server", "server");
        }

        private static void appendHeader(StringBuilder line,
                java.util.Map<String, java.util.List<String>> headers,
                String wantedName, String logName) {
            for (java.util.Map.Entry<String, java.util.List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && wantedName.equalsIgnoreCase(entry.getKey())
                        && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    line.append(' ').append(logName).append('=')
                            .append(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.trunc(
                                    entry.getValue().get(0), 80));
                    return;
                }
            }
        }

        private static String discontinuityReason(int reason) {
            switch (reason) {
                case androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION:
                    return "auto";
                case androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK:
                    return "seek";
                case androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT:
                    return "seek-adjust";
                case androidx.media3.common.Player.DISCONTINUITY_REASON_SKIP:
                    return "skip";
                case androidx.media3.common.Player.DISCONTINUITY_REASON_REMOVE:
                    return "remove";
                case androidx.media3.common.Player.DISCONTINUITY_REASON_INTERNAL:
                    return "internal";
                default:
                    return Integer.toString(reason);
            }
        }

        private String activeNetwork() {
            return com.liskovsoft.smartyoutubetv2.common.misc.NetPath.networkId(mContext);
        }

        /** Last path segment plus the identifying query params (itag/range/sq/rn), max ~80 chars. */
        private static String uriTail(android.net.Uri uri) {
            StringBuilder tail = new StringBuilder();
            String segment = uri.getLastPathSegment();
            tail.append(segment != null ? segment : uri);
            if (uri.isHierarchical()) {
                appendQueryParam(tail, uri, "itag");
                appendQueryParam(tail, uri, "range");
                appendQueryParam(tail, uri, "sq");
                appendQueryParam(tail, uri, "rn");
            }
            return tail.length() <= 80 ? tail.toString() : tail.substring(0, 80);
        }

        private static void appendQueryParam(StringBuilder tail, android.net.Uri uri, String name) {
            String value = uri.getQueryParameter(name);
            if (value != null) {
                tail.append(' ').append(name).append('=').append(value);
            }
        }

        private static String safeRequestFingerprint(
                androidx.media3.exoplayer.source.LoadEventInfo info) {
            android.net.Uri uri = info.uri;
            StringBuilder result = new StringBuilder("host=").append(uri.getHost())
                    .append(" req=").append(info.dataSpec.position).append('+').append(info.dataSpec.length);
            appendQueryParam(result, uri, "itag");
            appendQueryParam(result, uri, "c");
            appendQueryParam(result, uri, "cver");
            appendQueryParam(result, uri, "range");
            String expire = uri.getQueryParameter("expire");
            if (expire != null) {
                try {
                    long remaining = Long.parseLong(expire) - System.currentTimeMillis() / 1_000L;
                    result.append(" expireInSec=").append(remaining);
                } catch (NumberFormatException ignored) {
                    result.append(" expire=invalid");
                }
            }
            result.append(" ipBound=").append(uri.getQueryParameter("ip") != null ? 'y' : 'n')
                    .append(" pot=").append(uri.getQueryParameter("pot") != null ? 'y' : 'n');
            return result.toString();
        }

        @Nullable
        private static androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
                findInvalidResponseCode(Throwable error) {
            for (Throwable e = error; e != null; e = e.getCause()) {
                if (e instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                    return (androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) e;
                }
            }
            return null;
        }

        /** Body bytes as one logcat-safe line: printable ASCII kept, everything else becomes '.'. */
        private static String printable(byte[] body, int max) {
            int n = Math.min(body.length, max);
            StringBuilder sb = new StringBuilder(n);
            for (int i = 0; i < n; i++) {
                char c = (char) (body[i] & 0xFF);
                sb.append(c >= 0x20 && c < 0x7F ? c : '.');
            }
            if (body.length > max) {
                sb.append("...");
            }
            return sb.toString();
        }

        private static String fingerprint(byte[] value) {
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(value);
                StringBuilder result = new StringBuilder(10);
                for (int i = 0; i < 5; i++) {
                    result.append(String.format(Locale.US, "%02x", hash[i] & 0xff));
                }
                return result.toString();
            } catch (java.security.NoSuchAlgorithmException e) {
                return Integer.toHexString(java.util.Arrays.hashCode(value));
            }
        }
    }
}
