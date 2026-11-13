package com.newtube.mobile.player;

import android.content.Context;
import android.media.AudioManager;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.mylogger.Log;

import java.util.function.Consumer;

/** MEETUBE(issue #177): optional effects on the main player's session; failures never stop audio. */
public final class AudioEffects {
    private static final String TAG = AudioEffects.class.getSimpleName();
    @Nullable private static AudioEffects sCurrent;

    private final Context mContext;
    private final Consumer<Float> mSetPlayerVolume;
    @Nullable private Equalizer mEqualizer;
    @Nullable private LoudnessEnhancer mEnhancer;
    private int mSessionId;
    private float mVolume = 1f;

    AudioEffects(Context context, Consumer<Float> setPlayerVolume) {
        mContext = context.getApplicationContext();
        mSetPlayerVolume = setPlayerVolume;
        sCurrent = this;
    }

    void attach(int sessionId) {
        if (mSessionId == sessionId) {
            return;
        }
        releaseEffects();
        mSessionId = sessionId;
        if (sessionId <= 0) {
            return; // Never attach effects to the deprecated global output session (0).
        }
        try {
            mEqualizer = new Equalizer(0, sessionId);
        } catch (RuntimeException e) {
            Log.w(TAG, "Equalizer unavailable: " + e);
        }
        try {
            mEnhancer = new LoudnessEnhancer(sessionId);
        } catch (RuntimeException e) {
            Log.w(TAG, "Volume boost unavailable: " + e);
        }
        applyEqualizer(mContext, mEqualizer, AudioPreferences.isEqualizerEnabled(mContext));
        setVolume(mVolume);
    }

    void setVolume(float volume) {
        mVolume = volume;
        if (mEnhancer != null) {
            try {
                mEnhancer.setTargetGain(volume > 1f ? (int) Math.round(2000 * Math.log10(volume)) : 0);
                mEnhancer.setEnabled(volume > 1f);
            } catch (RuntimeException e) {
                Log.w(TAG, "Can't apply volume boost: " + e);
            }
        }
    }

    /** The volume row also updates media3's base volume, including values below 100%. */
    public static void applyPlayerVolume(float volume) {
        AudioEffects current = sCurrent;
        if (current != null) {
            current.mSetPlayerVolume.accept(volume);
        }
    }

    public static void reapplyEqualizer() {
        AudioEffects current = sCurrent;
        if (current != null) {
            applyEqualizer(current.mContext, current.mEqualizer,
                    AudioPreferences.isEqualizerEnabled(current.mContext));
        }
    }

    private static void applyEqualizer(Context context, @Nullable Equalizer equalizer, boolean enabled) {
        if (equalizer == null) {
            return;
        }
        try {
            if (!enabled) {
                equalizer.setEnabled(false);
            }
            int preset = AudioPreferences.getPreset(context);
            if (preset >= 0 && preset < equalizer.getNumberOfPresets()) {
                equalizer.usePreset((short) preset);
            } else {
                short[] range = equalizer.getBandLevelRange();
                short[] levels = AudioPreferences.getCustomLevels(context, equalizer.getNumberOfBands());
                for (short band = 0; band < levels.length; band++) {
                    equalizer.setBandLevel(band, (short) Math.max(range[0], Math.min(range[1], levels[band])));
                }
            }
            if (enabled) {
                equalizer.setEnabled(true);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Can't apply equalizer: " + e);
        }
    }

    /** Device bands/presets and the configured curve, even when the equalizer is switched off. */
    public static final class Bands {
        public final int minLevel;
        public final int maxLevel;
        public final int[] frequencies;
        public final short[] levels;
        public final String[] presets;

        private Bands(short[] range, int[] frequencies, short[] levels, String[] presets) {
            minLevel = range[0];
            maxLevel = range[1];
            this.frequencies = frequencies;
            this.levels = levels;
            this.presets = presets;
        }
    }

    @Nullable
    public static Bands bands(Context context) {
        AudioEffects current = sCurrent;
        if (current != null && current.mEqualizer != null) {
            return readBands(current.mEqualizer);
        }
        // Settings can be opened before a video: query a private, silent session and release it.
        Equalizer probe = null;
        try {
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int sessionId = manager != null ? manager.generateAudioSessionId() : 0;
            if (sessionId <= 0) {
                return null;
            }
            probe = new Equalizer(0, sessionId);
            applyEqualizer(context, probe, false);
            return readBands(probe);
        } catch (RuntimeException e) {
            Log.w(TAG, "Can't query equalizer: " + e);
            return null;
        } finally {
            releaseEqualizer(probe);
        }
    }

    @Nullable
    private static Bands readBands(Equalizer equalizer) {
        try {
            short[] range = equalizer.getBandLevelRange();
            int[] frequencies = new int[equalizer.getNumberOfBands()];
            if (frequencies.length == 0 || range.length < 2 || range[0] >= range[1]) {
                return null;
            }
            short[] levels = new short[frequencies.length];
            for (short band = 0; band < frequencies.length; band++) {
                frequencies[band] = equalizer.getCenterFreq(band);
                levels[band] = equalizer.getBandLevel(band);
            }
            String[] presets = new String[equalizer.getNumberOfPresets()];
            for (short preset = 0; preset < presets.length; preset++) {
                presets[preset] = equalizer.getPresetName(preset);
            }
            return new Bands(range, frequencies, levels, presets);
        } catch (RuntimeException e) {
            Log.w(TAG, "Can't read equalizer bands: " + e);
            return null;
        }
    }

    void release() {
        if (sCurrent == this) {
            sCurrent = null;
        }
        releaseEffects();
        mSessionId = 0;
    }

    private void releaseEffects() {
        releaseEqualizer(mEqualizer);
        mEqualizer = null;
        if (mEnhancer != null) {
            try {
                mEnhancer.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "Can't release volume boost: " + e);
            }
            mEnhancer = null;
        }
    }

    private static void releaseEqualizer(@Nullable Equalizer equalizer) {
        if (equalizer != null) {
            try {
                equalizer.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "Can't release equalizer: " + e);
            }
        }
    }
}
