package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import androidx.media3.exoplayer.ExoPlayer;

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
    }

    /** Implemented on the Joyboy side (MtTranslate). */
    public interface Provider {
        /** Player (re)built: overlay = frame above the video, options = top-right button row. Safe to call again. */
        void attach(Activity activity, ExoPlayer player, ViewGroup overlay, ViewGroup options, VideoInfo info);

        /** Player released / activity going away. */
        void detach(Activity activity);
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
}
