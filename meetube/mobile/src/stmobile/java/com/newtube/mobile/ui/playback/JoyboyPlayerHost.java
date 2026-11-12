package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.media3.exoplayer.ExoPlayer;

import java.util.Collections;
import java.util.Map;

/**
 * JOYBOY(v890): bridge that lets Joyboy add its translation engine on top of MeeTube's player.
 * :mobile cannot depend on the app module, so Joyboy registers a {@link Provider} (MeeTubeLink.bindBar)
 * and MobilePlaybackActivity only calls attach/detach. No Joyboy class is referenced here.
 */
public final class JoyboyPlayerHost {
    /** What the provider can ask the activity about the video that is playing right now. */
    public interface VideoInfo {
        String videoId();
        String title();
        /** JOYBOY(v923): local file / direct link the card plays (Video.localUri), or null for a YouTube video. */
        default String localUri() { return null; }
        /** JOYBOY(v930): "youtube" | "local" | "direct", derived from localUri(). */
        default String sourceKind() {
            String u = localUri();
            if (u == null || u.isEmpty()) {
                return "youtube";
            }
            String l = u.toLowerCase(java.util.Locale.ROOT);
            return (l.startsWith("http://") || l.startsWith("https://")) ? "direct" : "local";
        }
        /** JOYBOY(v930): HTTP headers (ref/ua/cookie) the source needs; empty until the Activity supplies them (phase 2). */
        default Map<String, String> headers() { return Collections.emptyMap(); }
    }

    /** Implemented on the Joyboy side (MtTranslate). */
    public interface Provider {
        /** Player (re)built: overlay = frame above the video, options = top-right button row. Safe to call again. */
        void attach(Activity activity, ExoPlayer player, ViewGroup overlay, ViewGroup options, VideoInfo info);

        /** Player released / activity going away. */
        void detach(Activity activity);

        // JOYBOY(v930): extension points for the merge plan. All default no-ops, so MeeTube behaves exactly as before
        // until a phase wires them in MobilePlaybackActivity.

        /** Phase 1: true = this video should open landscape + fullscreen by itself (local file / direct link). */
        default boolean wantsLandscape(Activity activity) { return false; }

        /** Phase 4: a key the add-on wants first (TV remote layer). true = consumed. */
        default boolean onKey(Activity activity, KeyEvent event) { return false; }

        /** Phase 2: controls shown/hidden, so the subtitle bottom margin can follow them. */
        default void onControlsVisibility(Activity activity, boolean visible) { }

        /** Phase 2/4: lets the add-on put its own buttons into the player's control bar. */
        default void extraControls(Activity activity, ViewGroup bar) { }
    }

    private static volatile Provider sProvider;

    private JoyboyPlayerHost() {
    }

    public static void setProvider(Provider provider) {
        sProvider = provider;
    }

    public static void attach(Activity activity, ExoPlayer player, ViewGroup overlay, View options, VideoInfo info) {
        try {
            Provider p = sProvider;
            if (p == null || activity == null || player == null || overlay == null) {
                return;
            }
            p.attach(activity, player, overlay, options instanceof ViewGroup ? (ViewGroup) options : null, info);
        } catch (RuntimeException e) {
            // never let the add-on break MeeTube's player
        }
    }

    public static void detach(Activity activity) {
        try {
            Provider p = sProvider;
            if (p != null && activity != null) {
                p.detach(activity);
            }
        } catch (RuntimeException e) {
            // ignore
        }
    }

    /** JOYBOY(v930): true = the add-on asks for landscape + fullscreen on open. Never throws. */
    public static boolean wantsLandscape(Activity activity) {
        try {
            Provider p = sProvider;
            return p != null && activity != null && p.wantsLandscape(activity);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** JOYBOY(v930): offers a key event to the add-on first. true = consumed. Never throws. */
    public static boolean onKey(Activity activity, KeyEvent event) {
        try {
            Provider p = sProvider;
            return p != null && activity != null && event != null && p.onKey(activity, event);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** JOYBOY(v930): tells the add-on the control bar was shown or hidden. Never throws. */
    public static void onControlsVisibility(Activity activity, boolean visible) {
        try {
            Provider p = sProvider;
            if (p != null && activity != null) {
                p.onControlsVisibility(activity, visible);
            }
        } catch (RuntimeException e) {
            // ignore
        }
    }

    /** JOYBOY(v930): lets the add-on add its buttons to the control bar. Never throws. */
    public static void extraControls(Activity activity, ViewGroup bar) {
        try {
            Provider p = sProvider;
            if (p != null && activity != null && bar != null) {
                p.extraControls(activity, bar);
            }
        } catch (RuntimeException e) {
            // ignore
        }
    }
}
