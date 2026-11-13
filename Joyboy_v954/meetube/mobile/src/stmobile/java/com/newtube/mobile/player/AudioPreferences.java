package com.newtube.mobile.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

/** MEETUBE(issue #177): phone-only audio settings, separate from the shared player prefs. */
public final class AudioPreferences {
    public static final int CUSTOM = -1;
    private static final String PREFS = "meetube_audio";

    private AudioPreferences() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isStableVolumeEnabled(Context context) {
        return prefs(context).getBoolean("stable_volume", false);
    }

    public static void setStableVolumeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("stable_volume", enabled).apply();
    }

    /**
     * MEETUBE(dolby): prefer YouTube's Dolby track (E-AC-3 / AC-3, itags 328 and 380, 5.1, Atmos
     * when JOC) where a video has one. On by default; only takes effect where this phone has a
     * Dolby decoder ({@link #decodesDolby}). Applies to the next video opened.
     */
    public static boolean isDolbyEnabled(Context context) {
        return prefs(context).getBoolean("dolby_atmos", true);
    }

    public static void setDolbyEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("dolby_atmos", enabled).apply();
    }

    /** ponytail: asked once per process; a decoder can't appear while the app runs. */
    private static Boolean sDecodesDolby;

    /** Whether the platform has an E-AC-3 decoder (licensed, so many phones have none). */
    public static boolean decodesDolby() {
        if (sDecodesDolby == null) {
            boolean found = false;
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    found |= type.equalsIgnoreCase("audio/eac3") || type.equalsIgnoreCase("audio/eac3-joc");
                }
            }
            sDecodesDolby = found;
        }
        return sDecodesDolby;
    }

    public static boolean isEqualizerEnabled(Context context) {
        return prefs(context).getBoolean("equalizer_enabled", false);
    }

    public static void setEqualizerEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("equalizer_enabled", enabled).apply();
        AudioEffects.reapplyEqualizer();
    }

    public static int getPreset(Context context) {
        return prefs(context).getInt("equalizer_preset", CUSTOM);
    }

    public static String getPresetName(Context context) {
        return prefs(context).getString("equalizer_preset_name", "");
    }

    public static void setPreset(Context context, int preset, String name) {
        prefs(context).edit().putInt("equalizer_preset", preset)
                .putString("equalizer_preset_name", name).apply();
        AudioEffects.reapplyEqualizer();
    }

    public static short[] getCustomLevels(Context context, int bands) {
        short[] levels = new short[bands];
        String[] stored = prefs(context).getString("equalizer_levels", "").split(",");
        for (int i = 0; i < Math.min(bands, stored.length); i++) {
            try {
                levels[i] = Short.parseShort(stored[i]);
            } catch (NumberFormatException ignored) {
                // A missing band starts flat, including on devices with a different band count.
            }
        }
        return levels;
    }

    /** A band edit saves the whole displayed curve, including the preset it started from. */
    public static void setCustomLevels(Context context, short[] levels) {
        StringBuilder stored = new StringBuilder();
        for (short level : levels) {
            if (stored.length() > 0) {
                stored.append(',');
            }
            stored.append(level);
        }
        prefs(context).edit().putInt("equalizer_preset", CUSTOM)
                .putString("equalizer_preset_name", "")
                .putString("equalizer_levels", stored.toString()).apply();
        AudioEffects.reapplyEqualizer();
    }
}
