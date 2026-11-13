package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import androidx.core.content.ContextCompat;

import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem.VideoPreset;
import com.liskovsoft.smartyoutubetv2.common.misc.AppDataSourceManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.smartyoutubetv2.common.prefs.DeArrowData;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.NetworkData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.prefs.SponsorBlockData;
import com.liskovsoft.smartyoutubetv2.common.proxy.WebProxyDialog;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;
import com.newtube.mobile.player.SabrSourcePreference;
import com.newtube.mobile.player.AudioEffects;
import com.newtube.mobile.player.AudioPreferences;
import com.newtube.mobile.ui.playback.PlayerGesturePrefs;
import com.newtube.mobile.ui.common.MobileSnackbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The video-side pages of the phone Settings: Playback, Video quality, Captions, SponsorBlock,
 * DeArrow and Advanced. Only settings the phone player reads are offered (2026-10 audit); the
 * TV-only knobs (remote keys, seek confirmation, pixel ratio, chipset fixes...) are left out with
 * their stored values untouched.
 */
final class PlayerPages {
    /** SponsorBlock's highlight is a point, not a segment: nothing to skip or paint on the phone. */
    private static final String SEGMENT_HIGHLIGHT = "poi_highlight";

    private PlayerPages() {
    }

    // ---------------------------------------------------------------------------------------------
    // Playback
    // ---------------------------------------------------------------------------------------------

    static SettingsPages.Page playback(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        PlayerData playerData = PlayerData.instance(context);
        PlayerTweaksData tweaks = PlayerTweaksData.instance(context);

        rows.add(SettingsRow.<Integer>choice(context.getString(R.string.mobile_settings_video_ends))
                .option(context.getString(R.string.mobile_settings_video_ends_next), PlayerConstants.PLAYBACK_MODE_ALL)
                .option(context.getString(R.string.mobile_settings_video_ends_repeat), PlayerConstants.PLAYBACK_MODE_ONE)
                .option(context.getString(R.string.mobile_settings_video_ends_shuffle), PlayerConstants.PLAYBACK_MODE_SHUFFLE)
                .option(context.getString(R.string.mobile_settings_video_ends_list), PlayerConstants.PLAYBACK_MODE_LIST)
                .option(context.getString(R.string.mobile_settings_video_ends_reverse), PlayerConstants.PLAYBACK_MODE_REVERSE_LIST)
                .option(context.getString(R.string.mobile_settings_video_ends_pause), PlayerConstants.PLAYBACK_MODE_PAUSE)
                .option(context.getString(R.string.mobile_settings_video_ends_close), PlayerConstants.PLAYBACK_MODE_CLOSE)
                .bind(playerData::getPlaybackMode, playerData::setPlaybackMode));

        // MEETUBE(issue #134): Shorts loop or scroll on by themselves, apart from the mode above.
        rows.add(SettingsRow.<Boolean>choice(context.getString(R.string.mobile_settings_short_ends))
                .option(context.getString(R.string.mobile_settings_short_ends_loop), true)
                .option(context.getString(R.string.mobile_settings_short_ends_next), false)
                .bind(tweaks::isLoopShortsEnabled, tweaks::setLoopShortsEnabled));

        rows.add(SettingsRow.<Integer>choice(context.getString(R.string.mobile_settings_remember_speed))
                .option(context.getString(R.string.mobile_settings_remember_speed_channel), SPEED_PER_CHANNEL)
                .option(context.getString(R.string.mobile_settings_remember_speed_video), SPEED_PER_VIDEO)
                .option(context.getString(R.string.mobile_settings_remember_speed_all), SPEED_ALL)
                .option(context.getString(R.string.mobile_settings_remember_speed_none),
                        context.getString(R.string.mobile_settings_remember_speed_none_desc), SPEED_NONE)
                .bind(() -> playerData.isSpeedPerChannelEnabled() ? SPEED_PER_CHANNEL
                                : playerData.isAllSpeedEnabled() ? SPEED_ALL
                                : playerData.isSpeedPerVideoEnabled() ? SPEED_PER_VIDEO : SPEED_NONE,
                        mode -> {
                            switch (mode) {
                                case SPEED_PER_CHANNEL:
                                    playerData.setSpeedPerChannelEnabled(true);
                                    break;
                                case SPEED_PER_VIDEO:
                                    playerData.setSpeedPerVideoEnabled(true);
                                    break;
                                case SPEED_ALL:
                                    playerData.setAllSpeedEnabled(true);
                                    break;
                                default:
                                    playerData.setAllSpeedEnabled(false); // clears all three
                                    break;
                            }
                        }));

        rows.add(SettingsRow.<Integer>choice(context.getString(R.string.mobile_settings_speed_list))
                .option(context.getString(R.string.mobile_settings_speed_list_basic), 0)
                .option(context.getString(R.string.mobile_settings_speed_list_fine), 1)
                .option(context.getString(R.string.mobile_settings_speed_list_finest), 2)
                .bind(() -> tweaks.isExtraLongSpeedListEnabled() ? 2 : tweaks.isLongSpeedListEnabled() ? 1 : 0,
                        steps -> {
                            if (steps == 2) {
                                tweaks.setExtraLongSpeedListEnabled(true);
                            } else if (steps == 1) {
                                tweaks.setLongSpeedListEnabled(true);
                            } else {
                                tweaks.setLongSpeedListEnabled(false);
                                tweaks.setExtraLongSpeedListEnabled(false);
                            }
                        }));

        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_natural_voice),
                context.getString(R.string.mobile_settings_natural_voice_summary),
                tweaks::isAudioTimeStretchingEnabled, tweaks::setAudioTimeStretchingEnabled));

        rows.add(sleepTimer(context, playerData));
        // MEETUBE(background): OEM battery managers kill an optimized app's screen-off audio.
        rows.add(SettingsRow.action(0, context.getString(R.string.mobile_settings_screen_off),
                () -> context.getString(com.newtube.mobile.ui.playback.BackgroundPlay.isExempt(context)
                        ? R.string.mobile_settings_screen_off_on : R.string.mobile_settings_screen_off_off),
                page -> com.newtube.mobile.ui.playback.BackgroundPlay.request(page.requireActivity())));

        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_auto_pip),
                context.getString(R.string.mobile_settings_auto_pip_summary),
                () -> com.newtube.mobile.ui.playback.MiniPip.isAutoEnabled(context),
                on -> com.newtube.mobile.ui.playback.MiniPip.setAutoEnabled(context, on)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_audio_focus),
                context.getString(R.string.mobile_settings_audio_focus_summary),
                tweaks::isAudioFocusEnabled, tweaks::setAudioFocusEnabled));

        // MEETUBE(home autoplay): the centred Home card plays muted, as on YouTube.
        rows.add(SettingsRow.<Integer>choice(context.getString(R.string.mobile_settings_home_autoplay))
                .option(context.getString(R.string.mobile_settings_home_autoplay_wifi),
                        context.getString(R.string.mobile_settings_home_autoplay_wifi_desc),
                        com.newtube.mobile.ui.browse.HomePreview.WIFI)
                .option(context.getString(R.string.mobile_settings_home_autoplay_always),
                        context.getString(R.string.mobile_settings_home_autoplay_always_desc),
                        com.newtube.mobile.ui.browse.HomePreview.ALWAYS)
                .option(context.getString(R.string.mobile_settings_home_autoplay_off),
                        context.getString(R.string.mobile_settings_home_autoplay_off_desc),
                        com.newtube.mobile.ui.browse.HomePreview.OFF)
                .bind(() -> com.newtube.mobile.ui.browse.HomePreview.mode(context),
                        mode -> com.newtube.mobile.ui.browse.HomePreview.setMode(context, mode)));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_resume)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_resume_short),
                context.getString(R.string.mobile_settings_resume_short_summary),
                tweaks::isRememberPositionOfShortVideosEnabled, tweaks::setRememberPositionOfShortVideosEnabled));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_resume_live),
                context.getString(R.string.mobile_settings_resume_live_summary),
                tweaks::isRememberPositionOfLiveVideosEnabled, tweaks::setRememberPositionOfLiveVideosEnabled));

        // NEWTUBE(gestures): the optional player swipes (#12), on by default (PlayerGesturePrefs);
        // the fullscreen and minimize swipes are always on.
        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_gestures)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_gesture_levels),
                context.getString(R.string.mobile_settings_gesture_levels_summary),
                () -> PlayerGesturePrefs.isLevelSwipesOn(context),
                on -> PlayerGesturePrefs.setLevelSwipesOn(context, on)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_gesture_seek),
                context.getString(R.string.mobile_settings_gesture_seek_summary),
                () -> PlayerGesturePrefs.isSeekSwipeOn(context),
                on -> PlayerGesturePrefs.setSeekSwipeOn(context, on)));
        // MEETUBE(double-tap seek, issue #6): how far a double tap jumps; 10 s stays the default.
        SettingsRow.Choice<Integer> doubleTap = SettingsRow.choice(
                context.getString(R.string.mobile_settings_double_tap_seek));
        for (int seconds : new int[] {3, 5, 10, 15, 30}) {
            doubleTap.option(context.getString(R.string.seek_interval_sec, String.valueOf(seconds)), seconds * 1000);
        }
        rows.add(doubleTap.bind(playerData::getSeekIncrementMs, playerData::setSeekIncrementMs));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_watch_page)));
        // MEETUBE(ambient): on by default in the dark theme, off in the light one.
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_ambient),
                context.getString(R.string.mobile_settings_ambient_summary),
                () -> com.newtube.mobile.ui.playback.AmbientGlow.isOn(context),
                on -> com.newtube.mobile.ui.playback.AmbientGlow.setOn(context, on)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_hide_related),
                context.getString(R.string.mobile_settings_hide_related_summary),
                tweaks::isSuggestionsDisabled, tweaks::setSuggestionsDisabled));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_dislikes),
                context.getString(R.string.mobile_settings_dislikes_summary),
                tweaks::isReturnYouTubeDislikeEnabled, tweaks::setReturnYouTubeDislikeEnabled));
        SponsorBlockData sponsorBlock = SponsorBlockData.instance(context);
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_paid_promotion),
                context.getString(R.string.mobile_settings_paid_promotion_summary),
                sponsorBlock::isPaidContentNotificationEnabled, sponsorBlock::setPaidContentNotificationEnabled));

        return new SettingsPages.Page(context.getString(R.string.mobile_settings_playback), rows);
    }

    private static final int SPEED_PER_CHANNEL = 0;
    private static final int SPEED_PER_VIDEO = 1;
    private static final int SPEED_ALL = 2;
    private static final int SPEED_NONE = 3;

    /**
     * Pauses playback this long after the player opened (the TV's "no key pressed" half has no
     * phone equivalent). Half-hour steps up to two hours, then whole ones; a stored value from the
     * old 0.5-10 h list stays listed.
     */
    private static SettingsRow sleepTimer(Context context, PlayerData playerData) {
        List<Float> hours = new ArrayList<>();
        for (float value : new float[] {0f, 0.5f, 1f, 1.5f, 2f, 3f, 4f, 6f, 8f}) {
            hours.add(value);
        }
        float stored = playerData.getSleepTimerHours();
        if (!containsFloat(hours, stored)) {
            hours.add(stored);
            Collections.sort(hours);
        }
        SettingsRow.Choice<Float> choice = SettingsRow.choice(context.getString(R.string.mobile_settings_sleep_timer));
        for (float value : hours) {
            choice.option(sleepLabel(context, value), value);
        }
        return choice.bind(() -> nearest(hours, playerData.getSleepTimerHours()), playerData::setSleepTimerHours)
                .summary(() -> {
                    float value = playerData.getSleepTimerHours();
                    return value <= 0 ? context.getString(R.string.mobile_settings_off)
                            : context.getString(R.string.mobile_settings_sleep_timer_summary, sleepLabel(context, value));
                });
    }

    private static String sleepLabel(Context context, float hours) {
        if (hours <= 0) {
            return context.getString(R.string.mobile_settings_off);
        }
        int minutes = Math.round(hours * 60);
        if (minutes < 60) {
            return context.getResources().getQuantityString(R.plurals.mobile_settings_minutes, minutes, minutes);
        }
        if (minutes % 60 == 0) {
            int whole = minutes / 60;
            return context.getResources().getQuantityString(R.plurals.mobile_settings_hours, whole, whole);
        }
        return context.getString(R.string.mobile_settings_hours_minutes, minutes / 60, minutes % 60);
    }

    private static boolean containsFloat(List<Float> values, float value) {
        for (Float candidate : values) {
            if (Math.abs(candidate - value) < 0.001f) {
                return true;
            }
        }
        return false;
    }

    private static Float nearest(List<Float> values, float value) {
        for (Float candidate : values) {
            if (Math.abs(candidate - value) < 0.001f) {
                return candidate;
            }
        }
        return value;
    }

    // ---------------------------------------------------------------------------------------------
    // Video quality (+ audio)
    // ---------------------------------------------------------------------------------------------

    static SettingsPages.Page quality(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        PlayerTweaksData tweaks = PlayerTweaksData.instance(context);

        rows.add(SettingsRow.note(context.getString(R.string.mobile_settings_quality_intro)));
        rows.add(defaultQuality(context, QUALITY_WIFI));
        rows.add(defaultQuality(context, QUALITY_MOBILE));
        rows.add(defaultQuality(context, QUALITY_SHORTS));
        // MEETUBE(issue #35): only where the screen can show it.
        if (com.newtube.mobile.player.Media3PlayerController.isHdrDisplay(context)) {
            rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_hdr),
                    context.getString(R.string.mobile_settings_hdr_summary),
                    tweaks::isHdrEnabled, enabled -> {
                        tweaks.setHdrEnabled(enabled);
                        com.newtube.mobile.player.Media3PlayerController.applyHdrSetting(context);
                    }));
        }

        rows.add(SettingsRow.note(context.getString(R.string.mobile_settings_quality_data_saver_note)));

        return new SettingsPages.Page(context.getString(R.string.mobile_settings_quality), rows);
    }

    // MEETUBE(issue #177): the audio rows have their own page next to Video quality.
    static SettingsPages.Page audio(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        PlayerTweaksData tweaks = PlayerTweaksData.instance(context);

        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_loudness),
                context.getString(R.string.mobile_settings_loudness_summary),
                tweaks::isPlayerAutoVolumeEnabled, tweaks::setPlayerAutoVolumeEnabled));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_stable_volume),
                context.getString(R.string.mobile_settings_stable_volume_summary),
                () -> AudioPreferences.isStableVolumeEnabled(context),
                enabled -> AudioPreferences.setStableVolumeEnabled(context, enabled)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_dolby),
                context.getString(AudioPreferences.decodesDolby() ? R.string.mobile_settings_dolby_summary
                        : R.string.mobile_settings_dolby_summary_none),
                () -> AudioPreferences.isDolbyEnabled(context),
                enabled -> AudioPreferences.setDolbyEnabled(context, enabled)));
        rows.add(playerVolume(context, PlayerData.instance(context)));
        rows.add(SettingsRow.action(0, context.getString(R.string.mobile_settings_equalizer),
                () -> EqualizerSheet.summary(context), EqualizerSheet::show));

        return new SettingsPages.Page(context.getString(R.string.mobile_settings_audio_quality), rows);
    }

    /** "Auto (up to 1080p)" or the cap the user picked, for the Video quality row on the top level. */
    static CharSequence qualitySummary(Context context) {
        CharSequence wifi = defaultQuality(context, QUALITY_WIFI).summaryText();
        if (PlayerData.instance(context).getCellularVideoFormat() == null) {
            return wifi != null ? wifi : "";
        }
        return context.getString(R.string.mobile_settings_quality_both, wifi,
                defaultQuality(context, QUALITY_MOBILE).summaryText());
    }

    private static final String QUALITY_AUTO = "auto";
    private static final String QUALITY_OTHER = "other";
    private static final String QUALITY_SAME = "same";
    /** Which default a quality row sets. */
    private static final int QUALITY_WIFI = 0;
    private static final int QUALITY_MOBILE = 1;
    private static final int QUALITY_SHORTS = 2;

    /**
     * YouTube-style quality choice: Auto plus one cap per resolution. Each cap is a preset (a
     * ceiling the automatic selection stays under, 60 fps where the phone decodes it); the exact
     * codec presets are the player quality sheet's full list. A preset picked there shows
     * here under its own name.
     */
    private static SettingsRow defaultQuality(Context context, int which) {
        PlayerData playerData = PlayerData.instance(context);
        // MEETUBE(quality-network): one row for Wi-Fi, one for mobile data ("Same as Wi-Fi" = none),
        // MEETUBE(shorts-quality): and one for Shorts, Auto (up to 1080p) at first (issue #26).
        FormatItem current = which == QUALITY_MOBILE ? playerData.getCellularVideoFormat()
                : which == QUALITY_SHORTS ? playerData.getShortsVideoFormat() : playerData.getWifiVideoFormat();
        SettingsRow.Pick<FormatItem> store = format -> {
            if (playerData.isLegacyCodecsForced()) {
                playerData.setLegacyCodecsForced(false); // same as setFormat below
            }
            if (which == QUALITY_MOBILE) {
                playerData.setCellularVideoFormat(format);
            } else if (which == QUALITY_SHORTS) {
                playerData.setShortsVideoFormat(format);
            } else {
                playerData.setWifiVideoFormat(format);
            }
        };
        FormatItem auto = playerData.getDefaultVideoFormat();
        int autoHeight = auto != null && auto.getHeight() > 0 ? auto.getHeight() : 1080;

        List<VideoPreset> caps = new ArrayList<>();
        for (int height : new int[] {2160, 1440, 1080, 720, 480, 360}) {
            if (height == autoHeight) {
                continue; // Auto is that cap already
            }
            VideoPreset preset = bestPreset(height);
            if (preset != null) {
                caps.add(preset);
            }
        }

        SettingsRow.Choice<String> choice = SettingsRow.choice(context.getString(
                which == QUALITY_MOBILE ? R.string.mobile_settings_quality_mobile
                        : which == QUALITY_SHORTS ? R.string.mobile_settings_quality_shorts
                        : R.string.mobile_settings_quality_wifi));
        String currentKey;
        if (which == QUALITY_MOBILE) {
            choice.option(context.getString(R.string.mobile_settings_quality_same), QUALITY_SAME);
        }
        choice.option(context.getString(R.string.mobile_settings_quality_auto, autoHeight),
                context.getString(R.string.mobile_settings_quality_auto_desc), QUALITY_AUTO);
        if (which == QUALITY_MOBILE && current == null) {
            currentKey = QUALITY_SAME;
        } else {
            currentKey = current == null || !current.isPreset() ? QUALITY_AUTO : null;
        }
        for (VideoPreset preset : caps) {
            int height = preset.getHeight();
            String description = height > autoHeight ? context.getString(R.string.mobile_settings_quality_more_data)
                    : height <= 480 ? context.getString(R.string.mobile_settings_quality_data_saver)
                    : context.getString(R.string.mobile_settings_quality_less_data);
            choice.option(context.getString(R.string.mobile_settings_quality_up_to, resolutionName(height)), description, preset.name);
            if (currentKey == null && preset.format.equals(current)) {
                currentKey = preset.name;
            }
        }
        if (currentKey == null) {
            // A preset chosen in the player's full list (another codec or frame rate): show it by name.
            choice.option(presetLabel(presetName(current)), QUALITY_OTHER);
            currentKey = QUALITY_OTHER;
        }
        String selected = currentKey;
        return choice.bind(() -> selected, key -> {
            if (QUALITY_SAME.equals(key)) {
                playerData.setCellularVideoFormat(null);
            } else if (QUALITY_AUTO.equals(key)) {
                store.pick(playerData.getDefaultVideoFormat());
            } else if (!QUALITY_OTHER.equals(key)) {
                for (VideoPreset preset : caps) {
                    if (preset.name.equals(key)) {
                        store.pick(preset.format);
                    }
                }
            }
        });
    }

    /** The phone's best preset at a height: 60 fps first, then VP9 > AV1 > AVC, SDR only. */
    private static VideoPreset bestPreset(int height) {
        VideoPreset best = null;
        int bestScore = -1;
        for (VideoPreset preset : AppDataSourceManager.instance().getVideoPresets()) {
            if (preset.getHeight() != height || preset.name.contains("hdr") || !Utils.isPresetSupported(preset)) {
                continue;
            }
            int score = (preset.name.contains("60fps") ? 10 : 0)
                    + (preset.isVP9Preset() ? 3 : preset.isAV1Preset() ? 2 : 1);
            if (score > bestScore) {
                bestScore = score;
                best = preset;
            }
        }
        return best;
    }

    private static String resolutionName(int height) {
        return height == 2160 ? "4K (2160p)" : height == 1440 ? "1440p" : height + "p";
    }

    private static String presetName(FormatItem format) {
        if (format != null) {
            for (VideoPreset preset : AppDataSourceManager.instance().getVideoPresets()) {
                if (preset.format.equals(format)) {
                    return preset.name;
                }
            }
        }
        return format != null ? format.getHeight() + "p" : "";
    }

    /** "(4K) 2160p    60fps    vp9+hdr" -> "2160p60 · VP9 HDR". */
    static String presetLabel(String name) {
        String resolution = "";
        String fps = "";
        String codec = "";
        for (String token : name.trim().split("\\s+")) {
            if (token.endsWith("p") && Character.isDigit(token.charAt(0))) {
                resolution = token;
            } else if (token.endsWith("fps")) {
                fps = token.substring(0, token.length() - 3);
            } else if (!token.startsWith("(")) {
                codec = token;
            }
        }
        String codecLabel = codec.replace("av01", "AV1").replace("vp9", "VP9").replace("avc", "H.264").replace("+hdr", " HDR");
        return String.format(Locale.US, "%s%s · %s", resolution, fps, codecLabel);
    }

    /** Fraction of full volume the player plays at, on top of the phone's own volume. */
    private static SettingsRow playerVolume(Context context, PlayerData playerData) {
        // MEETUBE(issue #177): media3's base volume stops at 100%; the session effect boosts above it.
        return SettingsRow.custom(context.getString(R.string.mobile_settings_player_volume), (parent, page) -> {
            android.widget.LinearLayout root = Tiles.column(parent.getContext());
            android.widget.TextView summary = Tiles.heading(root, context.getString(R.string.mobile_settings_player_volume));
            summary.setVisibility(android.view.View.VISIBLE);
            // Glass spring slider (as the equalizer's): progress p is (p + 2) * 5 percent, 10..200.
            com.newtube.mobile.ui.common.GlassSlider slider = new com.newtube.mobile.ui.common.GlassSlider(parent.getContext());
            slider.setMax(38);
            slider.setProgress(Math.max(0, Math.min(38, Math.round(playerData.getPlayerVolume() * 20) - 2)));
            slider.setContentDescription(context.getString(R.string.mobile_settings_player_volume));
            root.addView(slider, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            Runnable show = () -> {
                int percent = (slider.getProgress() + 2) * 5;
                String label = String.format(Locale.US, "%d%%", percent);
                summary.setText(percent > 100 ? label + "\n" + context.getString(R.string.volume_boost_warning) : label);
            };
            show.run();
            slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(android.widget.SeekBar s, int progress, boolean fromUser) {
                    if (fromUser) {
                        show.run();
                        AudioEffects.applyPlayerVolume((progress + 2) * 0.05f); // live, to judge by ear
                    }
                }

                @Override
                public void onStartTrackingTouch(android.widget.SeekBar s) {
                }

                @Override
                public void onStopTrackingTouch(android.widget.SeekBar s) {
                    playerData.setPlayerVolume((s.getProgress() + 2) * 0.05f);
                }
            });
            return root;
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Captions
    // ---------------------------------------------------------------------------------------------

    static SettingsPages.Page captions(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        PlayerData playerData = PlayerData.instance(context);

        rows.add(SettingsRow.fromRadio(AppDialogUtil.createSubtitleStylesCategory(context),
                context.getString(R.string.mobile_settings_caption_style)));
        rows.add(SettingsRow.fromRadio(AppDialogUtil.createSubtitleSizeCategory(context),
                context.getString(R.string.mobile_settings_caption_size)));
        rows.add(SettingsRow.fromRadio(AppDialogUtil.createSubtitlePositionCategory(context),
                context.getString(R.string.mobile_settings_caption_position)));

        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_caption_per_channel),
                context.getString(R.string.mobile_settings_caption_per_channel_summary),
                playerData::isSubtitlesPerChannelEnabled, playerData::setSubtitlesPerChannelEnabled));

        rows.add(SettingsRow.action(context.getString(R.string.mobile_settings_caption_system),
                context.getString(R.string.mobile_settings_caption_system_summary),
                page -> {
                    try {
                        page.startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));
                    } catch (RuntimeException e) {
                        MobileSnackbar.show(context, R.string.mobile_settings_unavailable);
                    }
                }));

        rows.add(SettingsRow.note(context.getString(R.string.mobile_settings_captions_note)));

        return new SettingsPages.Page(context.getString(R.string.mobile_settings_captions), rows);
    }

    // ---------------------------------------------------------------------------------------------
    // SponsorBlock
    // ---------------------------------------------------------------------------------------------

    static SettingsPages.Page sponsorBlock(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        SponsorBlockData data = SponsorBlockData.instance(context);

        rows.add(SettingsRow.toggle(context.getString(R.string.content_block_provider),
                context.getString(R.string.mobile_settings_sponsorblock_summary),
                data::isSponsorBlockEnabled, data::setSponsorBlockEnabled));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_sponsorblock_segments)));
        for (String category : data.getAllCategories()) {
            if (SEGMENT_HIGHLIGHT.equals(category)) {
                continue;
            }
            rows.add(SettingsRow.<Integer>choice(segmentTitle(context, data, category))
                    .option(context.getString(R.string.mobile_settings_segment_skip), SponsorBlockData.ACTION_SKIP_ONLY)
                    .option(context.getString(R.string.mobile_settings_segment_skip_notify), SponsorBlockData.ACTION_SKIP_WITH_TOAST)
                    .option(context.getString(R.string.mobile_settings_segment_ask), SponsorBlockData.ACTION_SHOW_DIALOG)
                    .option(context.getString(R.string.mobile_settings_segment_nothing), SponsorBlockData.ACTION_DO_NOTHING)
                    .bind(() -> data.getAction(category), action -> data.setAction(category, action))
                    .enabledWhen(data::isSponsorBlockEnabled));
        }

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_more)));
        rows.add(SettingsRow.page(context.getString(R.string.mobile_settings_segment_marks),
                () -> markSummary(context, data), SettingsPages.SPONSORBLOCK_MARKS).enabledWhen(data::isSponsorBlockEnabled));
        rows.add(SettingsRow.fromRadio(AppDialogUtil.createIgnoreShortSegmentsCategory(context),
                context.getString(R.string.mobile_settings_segment_min)).enabledWhen(data::isSponsorBlockEnabled));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_segment_once),
                context.getString(R.string.mobile_settings_segment_once_summary),
                data::isDontSkipSegmentAgainEnabled, data::setDontSkipSegmentAgainEnabled).enabledWhen(data::isSponsorBlockEnabled));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_segment_alt_server),
                context.getString(R.string.mobile_settings_segment_alt_server_summary),
                data::isAltServerEnabled, data::enableAltServer).enabledWhen(data::isSponsorBlockEnabled));
        rows.add(link(context, R.string.content_block_status, R.string.content_block_status_url));
        rows.add(link(context, R.string.about_sponsorblock, R.string.content_block_provider_url));

        return new SettingsPages.Page(context.getString(R.string.content_block_provider), rows);
    }

    static SettingsPages.Page sponsorBlockMarks(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        SponsorBlockData data = SponsorBlockData.instance(context);
        rows.add(SettingsRow.note(context.getString(R.string.mobile_settings_segment_marks_note)));
        for (String category : data.getAllCategories()) {
            if (SEGMENT_HIGHLIGHT.equals(category)) {
                continue;
            }
            rows.add(SettingsRow.toggle(segmentTitle(context, data, category), null,
                    () -> data.isColorMarkerEnabled(category),
                    on -> {
                        if (on) {
                            data.enableColorMarker(category);
                        } else {
                            data.disableColorMarker(category);
                        }
                    }));
        }
        return new SettingsPages.Page(context.getString(R.string.mobile_settings_segment_marks), rows);
    }

    static CharSequence sponsorBlockSummary(Context context) {
        return context.getString(SponsorBlockData.instance(context).isSponsorBlockEnabled()
                ? R.string.mobile_settings_sponsorblock_on : R.string.mobile_settings_off);
    }

    private static CharSequence markSummary(Context context, SponsorBlockData data) {
        int total = 0;
        int marked = 0;
        for (String category : data.getAllCategories()) {
            if (SEGMENT_HIGHLIGHT.equals(category)) {
                continue;
            }
            total++;
            if (data.isColorMarkerEnabled(category)) {
                marked++;
            }
        }
        return context.getString(R.string.mobile_settings_segment_marks_summary, marked, total);
    }

    /** "● Sponsor", the dot in the segment's seek-bar colour. */
    private static CharSequence segmentTitle(Context context, SponsorBlockData data, String category) {
        SpannableStringBuilder title = new SpannableStringBuilder("●  ");
        title.setSpan(new ForegroundColorSpan(ContextCompat.getColor(context, data.getColorRes(category))),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.append(context.getString(data.getLocalizedRes(category)));
        return title;
    }

    private static SettingsRow link(Context context, int titleRes, int urlRes) {
        String url = context.getString(urlRes);
        return SettingsRow.action(context.getString(titleRes), url.replace("https://", ""),
                page -> Utils.openLinkExt(page.requireContext(), url));
    }

    // ---------------------------------------------------------------------------------------------
    // DeArrow
    // ---------------------------------------------------------------------------------------------

    static SettingsPages.Page deArrow(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        DeArrowData data = DeArrowData.instance(context);

        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_dearrow_titles),
                context.getString(R.string.mobile_settings_dearrow_titles_summary),
                // NEWTUBE(#45): no longer turns Original titles off - both can be on; DeArrow's
                // title wins and the original title covers videos DeArrow has none for.
                data::isReplaceTitlesEnabled, data::setReplaceTitlesEnabled));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_dearrow_thumbnails),
                context.getString(R.string.mobile_settings_dearrow_thumbnails_summary),
                data::isReplaceThumbnailsEnabled, data::setReplaceThumbnailsEnabled));
        rows.add(SettingsRow.note(context.getString(R.string.mobile_settings_dearrow_note)));
        rows.add(link(context, R.string.content_block_status, R.string.content_block_status_url));
        rows.add(link(context, R.string.about_dearrow, R.string.dearrow_provider_url));

        return new SettingsPages.Page(context.getString(R.string.dearrow_provider), rows);
    }

    static CharSequence deArrowSummary(Context context) {
        DeArrowData data = DeArrowData.instance(context);
        boolean titles = data.isReplaceTitlesEnabled();
        boolean thumbnails = data.isReplaceThumbnailsEnabled();
        return context.getString(titles && thumbnails ? R.string.mobile_settings_dearrow_both
                : titles ? R.string.mobile_settings_dearrow_titles_only
                : thumbnails ? R.string.mobile_settings_dearrow_thumbnails_only
                : R.string.mobile_settings_off);
    }

    // ---------------------------------------------------------------------------------------------
    // Advanced
    // ---------------------------------------------------------------------------------------------

    static SettingsPages.Page advanced(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        PlayerData playerData = PlayerData.instance(context);
        PlayerTweaksData tweaks = PlayerTweaksData.instance(context);
        GeneralData generalData = GeneralData.instance(context);
        NetworkData networkData = NetworkData.instance(context);
        MediaServiceData serviceData = MediaServiceData.instance();

        rows.add(SettingsRow.note(context.getString(R.string.mobile_settings_advanced_note)));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_streaming)));
        rows.add(SettingsRow.<Integer>choice(context.getString(R.string.mobile_settings_buffer))
                .option(context.getString(R.string.video_buffer_size_low), context.getString(R.string.mobile_settings_buffer_low_desc), PlayerData.BUFFER_LOW)
                .option(context.getString(R.string.video_buffer_size_med), context.getString(R.string.mobile_settings_buffer_med_desc), PlayerData.BUFFER_MEDIUM)
                .option(context.getString(R.string.video_buffer_size_high), context.getString(R.string.mobile_settings_buffer_high_desc), PlayerData.BUFFER_HIGH)
                .option(context.getString(R.string.video_buffer_size_highest), context.getString(R.string.mobile_settings_buffer_highest_desc), PlayerData.BUFFER_HIGHEST)
                .bind(playerData::getVideoBufferType, type -> playerData.setVideoBufferTypeByUser(context, type)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_stall_quality),
                context.getString(R.string.mobile_settings_stall_quality_summary),
                () -> !tweaks.isNetworkErrorFixingDisabled(), on -> tweaks.setNetworkErrorFixingDisabled(!on)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_high_bitrate),
                context.getString(R.string.mobile_settings_high_bitrate_summary),
                tweaks::isHighBitrateFormatsEnabled, tweaks::setHighBitrateFormatsEnabled));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_sabr),
                context.getString(R.string.mobile_settings_sabr_summary),
                () -> SabrSourcePreference.isPreferred(context), on -> SabrSourcePreference.setPreferred(context, on)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_legacy_codecs),
                context.getString(R.string.mobile_settings_legacy_codecs_summary),
                playerData::isLegacyCodecsForced, playerData::setLegacyCodecsForced));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_live)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_live_edge),
                context.getString(R.string.mobile_settings_live_edge_summary),
                tweaks::isBufferOnStreamsDisabled, tweaks::setBufferOnStreamsDisabled));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_live_hls),
                context.getString(R.string.mobile_settings_live_hls_summary),
                tweaks::isHlsStreamsForced, tweaks::setHlsStreamsForced));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_player)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_controls_on_next),
                context.getString(R.string.mobile_settings_controls_on_next_summary),
                tweaks::isPlayerUiOnNextEnabled, tweaks::setPlayerUiOnNextEnabled));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_list_queue),
                context.getString(R.string.mobile_settings_list_queue_summary),
                tweaks::isSectionPlaylistEnabled, tweaks::setSectionPlaylistEnabled));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_queue_mode),
                context.getString(R.string.mobile_settings_queue_mode_summary),
                tweaks::isQueueRespectsPlaybackMode, tweaks::setQueueRespectsPlaybackMode));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_chapter_popup),
                context.getString(R.string.mobile_settings_chapter_popup_summary),
                tweaks::isChapterNotificationEnabled, tweaks::setChapterNotificationEnabled));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_network)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_proxy),
                context.getString(R.string.mobile_settings_proxy_summary),
                generalData::isProxyEnabled, on -> {
                    // Proxy with authentication is supported only by OkHttp (as in General settings).
                    tweaks.setPlayerDataSource(on ? PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP : PlayerTweaksData.PLAYER_DATA_SOURCE_CRONET);
                    generalData.setProxyEnabled(on);
                    new WebProxyDialog(context).enable(on);
                    OkHttpManager.unhold();
                }));
        rows.add(SettingsRow.<Integer>choice(context.getString(R.string.mobile_settings_dns))
                .option(context.getString(R.string.mobile_settings_dns_ipv4), context.getString(R.string.mobile_settings_dns_ipv4_desc), PlayerTweaksData.DNS_TYPE_IPV4)
                .option(context.getString(R.string.mobile_settings_dns_system), PlayerTweaksData.DNS_TYPE_SYSTEM)
                .option(context.getString(R.string.mobile_settings_dns_google), PlayerTweaksData.DNS_TYPE_GOOGLE)
                .bind(tweaks::getPreferredDnsType, tweaks::setPreferredDnsType).needsRestart());
        // Upstream's "Internet censorship" switch (ByeByeDPI and the like): not a TV leftover.
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_conscrypt),
                context.getString(R.string.mobile_settings_conscrypt_summary),
                networkData::isConscryptEnabled, networkData::setConscryptEnabled).needsRestart());

        // From the TV's Account settings dialog, the one of its three rows the phone has a use for.
        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_accounts)));
        AppPrefs prefs = AppPrefs.instance(context);
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_multi_profiles),
                context.getString(R.string.mobile_settings_multi_profiles_summary),
                prefs::isMultiProfilesEnabled, on -> {
                    prefs.enableMultiProfiles(on);
                    BrowsePresenter.instance(context).updateSections();
                }).needsRestart());

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_feeds)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_legacy_feeds),
                context.getString(R.string.mobile_settings_legacy_feeds_summary),
                serviceData::isLegacyUIEnabled, serviceData::setLegacyUIEnabled));

        return new SettingsPages.Page(context.getString(R.string.mobile_settings_advanced), rows);
    }
}
