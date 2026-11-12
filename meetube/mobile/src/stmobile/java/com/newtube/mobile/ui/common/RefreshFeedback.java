package com.newtube.mobile.ui.common;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.view.View;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * MEETUBE(refresh): a pull-to-refresh lets go with a light tick and a soft two-note pop
 * (res/raw/refresh_pop.wav). The pop stays quiet while the phone is on silent or vibrate, and
 * is off unless switched on in Settings > Customization (issue #120).
 */
public final class RefreshFeedback {
    private static final String PREFS = "meetube_app";
    private static final String KEY_SOUND = "refresh_sound";
    private static SoundPool sPool;
    private static int sPop;

    private RefreshFeedback() {
    }

    public static boolean isSoundOn(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SOUND, false);
    }

    public static void setSoundOn(Context context, boolean on) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SOUND, on).apply();
    }

    public static void play(View view) {
        Haptics.click(view);
        Context app = view.getContext().getApplicationContext();
        if (!isSoundOn(app)) {
            return;
        }
        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null || audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) {
            return;
        }
        if (sPool == null) {
            sPool = new SoundPool.Builder()
                    .setMaxStreams(1)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .build();
            // The first pull plays it once loaded (a few ms); later ones play at once.
            sPool.setOnLoadCompleteListener((pool, id, status) -> {
                if (status == 0) {
                    pool.play(id, 0.6f, 0.6f, 1, 0, 1f);
                }
            });
            sPop = sPool.load(app, R.raw.refresh_pop, 1);
            return;
        }
        sPool.play(sPop, 0.6f, 0.6f, 1, 0, 1f);
    }
}
