package com.newtube.mobile.ui.playback;

import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.newtube.mobile.ui.browse.MobileBrowseActivity;

/**
 * JOYBOY(v947): second instance of the touch player for local files / direct links / browser videos.
 * Same code as {@link MobilePlaybackActivity}, but declared with its own taskAffinity in the manifest, so opening
 * a file never drags MeeTube's browse screen (YouTube home) into the stack and never reloads it. YouTube videos keep
 * using MobilePlaybackActivity. The ViewManager mapping for PlaybackView is switched to this class by
 * {@link #route(android.content.Context)} just before a local video opens, and back to the YouTube player when this one is destroyed.
 */
public class MobileLocalPlaybackActivity extends MobilePlaybackActivity {
    /** PlaybackView -> this class (no parent: it lives in its own task and closing it returns to where the video was opened). */
    public static void route(android.content.Context c) {
        ViewManager.instance(c.getApplicationContext()).register(PlaybackView.class, MobileLocalPlaybackActivity.class, null);
    }

    /** PlaybackView -> the normal YouTube player (child of the browse screen). */
    public static void routeYoutube(android.content.Context c) {
        ViewManager.instance(c.getApplicationContext()).register(PlaybackView.class, MobilePlaybackActivity.class, MobileBrowseActivity.class);
    }

    /**
     * JOYBOY(v949): a local file / direct link / browser video asks for landscape BEFORE the first layout, so the
     * player never opens as the portrait "watch page" (video on top, subscriptions/related below). A vertical
     * video gives the lock back at its first frame (MobilePlaybackActivity.autoLandscapeIfLocal).
     */
    /** JOYBOY(v950): set by MtPlay - called once this player is on screen so the router activity behind it can close (no library/white flash). */
    public static volatile Runnable sOnShown;

    /** JOYBOY(v951): set by MtPlay right before opening — the video is vertical (probed from its metadata). */
    public static volatile boolean sNextPortrait;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        // JOYBOY(v951): MtPlay probed the video's size BEFORE opening — a vertical one opens locked portrait from the first frame (no landscape-then-rotate)
        final boolean portrait = sNextPortrait;
        sNextPortrait = false;
        try { setRequestedOrientation(portrait ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE); } catch (Throwable ignored) { }
        super.onCreate(savedInstanceState);
        // JOYBOY(v950): black from the very first frame (never the white watch-page / theme window), no open animation
        try { getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK)); } catch (Throwable ignored) { }
        try { overridePendingTransition(0, 0); } catch (Throwable ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        final Runnable r = sOnShown;
        if (r != null) {
            sOnShown = null;
            getWindow().getDecorView().postDelayed(r, 150);
        }
    }

    @Override
    public void finish() {
        super.finish();
        try { overridePendingTransition(0, 0); } catch (Throwable ignored) { }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { routeYoutube(this); } catch (Throwable ignored) { }
    }
}
