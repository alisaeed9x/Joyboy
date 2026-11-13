package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import com.liskovsoft.appupdatechecker2.AppUpdateChecker;
import com.liskovsoft.sharedutils.helpers.AppInfoHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AppUpdatePresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.DiagnosticLog;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.about.AboutShareRows;
import com.newtube.mobile.ui.common.ThemeMode;

import java.util.ArrayList;
import java.util.List;

/**
 * The phone Settings tree (issue #2), modelled on YouTube's: a top level of sections with icons,
 * each opening one short page. Pages are built on demand from the prefs classes; page ids are plain
 * strings so a recreated screen rebuilds the same pages.
 *
 * <pre>
 * Settings
 *   [ Search settings ]          (SettingsSearchFragment: every row of every page)
 *   App:    General · Customization · Feeds · History and privacy
 *   Video:  Playback · Video quality · Audio quality · Captions · SponsorBlock · DeArrow
 *   Other:  Backup and restore · Advanced · About
 * </pre>
 */
public final class SettingsPages {
    public static final String ROOT = "root";
    public static final String GENERAL = "general";
    public static final String CUSTOMIZE = "customize";
    public static final String FEEDS = "feeds";
    public static final String FEEDS_TABS = "feeds_tabs";
    public static final String FEEDS_HIDDEN = "feeds_hidden";
    public static final String FEEDS_MENU = "feeds_menu";
    public static final String PLAYER_BUTTONS = "player_buttons";
    public static final String PRIVACY = "privacy";
    public static final String PLAYBACK = "playback";
    public static final String QUALITY = "quality";
    public static final String AUDIO = "audio";
    public static final String CAPTIONS = "captions";
    public static final String SPONSORBLOCK = "sponsorblock";
    public static final String SPONSORBLOCK_MARKS = "sponsorblock_marks";
    public static final String DEARROW = "dearrow";
    public static final String DOWNLOADS = "downloads";
    public static final String BACKUP = "backup";
    public static final String ADVANCED = "advanced";
    public static final String ABOUT = "about";
    public static final String TV_INSTALL = "tv_install";
    public static final String ABOUT_REPORTERS = "about_reporters";
    public static final String ABOUT_CONTRIBUTORS = "about_contributors";
    // JOYBOY(translation engine)
    public static final String TR_ENGINE = "tr_engine";
    public static final String TR_PIPELINE = "tr_pipeline";
    public static final String TR_KEYS = "tr_keys";
    public static final String TR_AUDIO = "tr_audio";
    public static final String TR_PERF = "tr_perf";

    /** A built page: its title and rows. */
    public static final class Page {
        final CharSequence title;
        final List<SettingsRow> rows;

        Page(CharSequence title, List<SettingsRow> rows) {
            this.title = title;
            this.rows = rows;
        }
    }

    private SettingsPages() {
    }

    @NonNull
    static Page build(@NonNull Context context, @NonNull String id) {
        switch (id) {
            case GENERAL:
                return AppPages.general(context);
            case CUSTOMIZE:
                return AppPages.customize(context);
            case FEEDS:
                return AppPages.feeds(context);
            case FEEDS_TABS:
                return AppPages.tabs(context);
            case FEEDS_HIDDEN:
                return AppPages.hidden(context);
            case FEEDS_MENU:
                return AppPages.videoMenu(context);
            case PLAYER_BUTTONS:
                return AppPages.playerButtons(context);
            case PRIVACY:
                return AppPages.privacy(context);
            case PLAYBACK:
                return PlayerPages.playback(context);
            case QUALITY:
                return PlayerPages.quality(context);
            case AUDIO:
                return PlayerPages.audio(context);
            case CAPTIONS:
                return PlayerPages.captions(context);
            case SPONSORBLOCK:
                return PlayerPages.sponsorBlock(context);
            case SPONSORBLOCK_MARKS:
                return PlayerPages.sponsorBlockMarks(context);
            case DEARROW:
                return PlayerPages.deArrow(context);
            case DOWNLOADS:
                return DownloadPages.downloads(context);
            case BACKUP:
                return AppPages.backup(context);
            case ADVANCED:
                return PlayerPages.advanced(context);
            case ABOUT:
                return about(context);
            case TV_INSTALL:
                return Build.VERSION.SDK_INT >= 26 ? TvInstall.page(context) : root(context);
            case ABOUT_REPORTERS:
                return AboutPeople.reporters(context);
            case ABOUT_CONTRIBUTORS:
                return AboutPeople.contributors(context);
            case TR_ENGINE:
                return TranslationPages.index(context);
            case TR_PIPELINE:
                return TranslationPages.pipeline(context);
            case TR_KEYS:
                return TranslationPages.keys(context);
            case TR_AUDIO:
                return TranslationPages.audio(context);
            case TR_PERF:
                return TranslationPages.perf(context);
            case ROOT:
            default:
                return root(context);
        }
    }

    private static Page root(Context context) {
        List<SettingsRow> rows = new ArrayList<>();

        // MEETUBE: no account or coffee rows here - the You tab switches accounts, About thanks.
        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_group_app)));
        rows.add(SettingsRow.page(R.drawable.ic_settings_general, context.getString(R.string.mobile_settings_general),
                context.getString(R.string.mobile_settings_general_summary), GENERAL));
        rows.add(SettingsRow.page(R.drawable.ic_settings_customize, context.getString(R.string.mobile_settings_customize),
                context.getString(R.string.mobile_settings_customize_summary), CUSTOMIZE)
                .summary(() -> customizeSummary(context)));
        rows.add(SettingsRow.page(R.drawable.ic_settings_feeds, context.getString(R.string.mobile_settings_feeds),
                context.getString(R.string.mobile_settings_feeds_summary), FEEDS));
        rows.add(SettingsRow.page(R.drawable.ic_settings_privacy, context.getString(R.string.mobile_settings_privacy),
                context.getString(R.string.mobile_settings_privacy_summary), PRIVACY));
        rows.add(SettingsRow.page(R.drawable.ic_nav_downloads_outline, context.getString(R.string.header_downloads),
                context.getString(R.string.mobile_settings_downloads_summary), DOWNLOADS));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_group_video)));
        rows.add(SettingsRow.page(R.drawable.ic_settings_playback, context.getString(R.string.mobile_settings_playback),
                context.getString(R.string.mobile_settings_playback_summary), PLAYBACK));
        rows.add(SettingsRow.page(R.drawable.ic_settings_quality, context.getString(R.string.mobile_settings_quality),
                null, QUALITY).summary(() -> PlayerPages.qualitySummary(context)));
        rows.add(SettingsRow.page(R.drawable.ic_settings_audio, context.getString(R.string.mobile_settings_audio_quality),
                context.getString(R.string.mobile_settings_audio_quality_summary), AUDIO));
        rows.add(SettingsRow.page(R.drawable.ic_settings_captions, context.getString(R.string.mobile_settings_captions),
                context.getString(R.string.mobile_settings_captions_summary), CAPTIONS));
        rows.add(SettingsRow.page(R.drawable.ic_settings_sponsorblock, context.getString(R.string.content_block_provider),
                null, SPONSORBLOCK).summary(() -> PlayerPages.sponsorBlockSummary(context)));
        rows.add(SettingsRow.page(R.drawable.ic_settings_dearrow, context.getString(R.string.dearrow_provider),
                null, DEARROW).summary(() -> PlayerPages.deArrowSummary(context)));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_group_other)));
        rows.add(SettingsRow.page(R.drawable.ic_settings_backup, context.getString(R.string.mobile_settings_backup),
                context.getString(R.string.mobile_settings_backup_summary), BACKUP));
        rows.add(SettingsRow.page(R.drawable.ic_settings_advanced, context.getString(R.string.mobile_settings_advanced),
                context.getString(R.string.mobile_settings_advanced_summary), ADVANCED));
        rows.add(SettingsRow.page(R.drawable.ic_settings_about, context.getString(R.string.mobile_settings_about),
                versionLine(context), ABOUT));
        if (Build.VERSION.SDK_INT >= 26) {
            rows.add(SettingsRow.page(R.drawable.ic_settings_tv, context.getString(R.string.mobile_settings_tv_title),
                    context.getString(R.string.mobile_settings_tv_summary), TV_INSTALL));
        }

        // JOYBOY(translation engine): under MeeTube's own settings, the translation engine's.
        rows.add(SettingsRow.header("الترجمة"));
        rows.add(SettingsRow.page(R.drawable.ic_settings_captions, "إعدادات محرك الترجمة",
                "اللهجة · المفاتيح · التفريغ · الموديل والأداء", TR_ENGINE));

        return new Page(context.getString(R.string.header_settings), rows);
    }

    /** "Dark theme, tabs, ...": the theme is what people open Customization for most. */
    private static CharSequence customizeSummary(Context context) {
        int theme = ThemeMode.get();
        String themeLabel = context.getString(theme == ThemeMode.LIGHT ? R.string.mobile_settings_theme_light_short
                : theme == ThemeMode.DARK ? R.string.mobile_settings_theme_dark_short : R.string.mobile_settings_theme_system_short);
        return context.getString(R.string.mobile_settings_customize_summary_value, themeLabel);
    }

    private static String versionLine(Context context) {
        return context.getString(R.string.app_name) + " " + AppInfoHelpers.getAppVersionName(context);
    }

    private static Page about(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(SettingsRow.header(context.getString(R.string.mobile_about_creator)));
        rows.add(SettingsRow.custom(context.getString(R.string.mobile_about_dev_name), SettingsPages::aboutCard));

        if (AppUpdatePresenter.isInAppUpdatesDisabled()) {
            // Store build (-Pfdroid): the version stays, the update row and switch go.
            rows.add(icon(R.drawable.ic_mobile_update,
                    SettingsRow.action(context.getString(R.string.mobile_settings_version), versionLine(context), page -> { })));
        } else {
            rows.add(icon(R.drawable.ic_mobile_update, SettingsRow.action(context.getString(R.string.check_for_updates),
                    versionLine(context), page -> AppUpdatePresenter.instance(page.requireContext()).start(true))));
            AppUpdateChecker checker = new AppUpdateChecker(context, null);
            rows.add(icon(R.drawable.ic_about_notifications, SettingsRow.toggle(context.getString(R.string.check_updates_auto),
                    context.getString(R.string.mobile_settings_update_notify_summary),
                    checker::isUpdateCheckEnabled, checker::setUpdateCheckEnabled)));
            // MEETUBE(update pop-up): "Don't ask again" on the pop-up turns this off.
            com.newtube.mobile.update.AppUpdates updates = com.newtube.mobile.update.AppUpdates.instance(context);
            rows.add(icon(R.drawable.ic_about_campaign, SettingsRow.toggle(context.getString(R.string.mobile_settings_update_prompt),
                    context.getString(R.string.mobile_settings_update_prompt_summary),
                    () -> !updates.isPromptOff(), on -> updates.setPromptOff(!on))));
        }

        rows.add(SettingsRow.divider());
        rows.add(SettingsRow.page(R.drawable.ic_about_groups, context.getString(R.string.mobile_about_reporters),
                context.getString(R.string.mobile_about_reporters_summary), ABOUT_REPORTERS));
        rows.add(SettingsRow.page(R.drawable.ic_about_handshake, context.getString(R.string.mobile_about_contributors),
                context.getString(R.string.mobile_about_contributors_summary), ABOUT_CONTRIBUTORS));
        rows.add(icon(R.drawable.ic_about_code, SettingsRow.action(context.getString(R.string.about_source_code),
                context.getString(R.string.about_source_code_url).replace("https://", ""),
                page -> Utils.openLinkExt(page.requireContext(), context.getString(R.string.about_source_code_url)))));
        rows.add(icon(R.drawable.ic_about_gavel, SettingsRow.action(context.getString(R.string.about_license),
                context.getString(R.string.about_license_name),
                page -> Utils.openLinkExt(page.requireContext(), context.getString(R.string.about_license_url)))));

        return new Page(context.getString(R.string.about_app, context.getString(R.string.app_name)), rows);
    }

    /** MEETUBE(about): an About row's icon, in the same glass bead as the top level's. */
    private static SettingsRow icon(@androidx.annotation.DrawableRes int icon, SettingsRow row) {
        row.icon = icon;
        return row;
    }

    /**
     * MEETUBE(about): who makes MeeTube - the GitHub photo, name and handle (each opens the profile),
     * the coffee pill, and Star, Share and Send log as glass buttons.
     */
    private static android.view.View aboutCard(@NonNull android.view.ViewGroup parent, @NonNull SettingsAdapter.Listener page) {
        Context context = parent.getContext();
        android.view.View card = android.view.LayoutInflater.from(context)
                .inflate(R.layout.item_mobile_about_header, parent, false);
        android.view.View.OnClickListener profile = v -> Utils.openLinkExt(context, context.getString(R.string.mobile_about_dev_url));
        for (int id : new int[] {R.id.about_avatar, R.id.about_name, R.id.about_handle}) {
            card.findViewById(id).setOnClickListener(profile);
        }
        card.findViewById(R.id.about_avatar).setContentDescription(context.getString(R.string.mobile_about_made_by,
                context.getString(R.string.mobile_about_dev_name)));
        card.findViewById(R.id.about_coffee).setOnClickListener(v -> CoffeeSheet.show(context));
        card.findViewById(R.id.about_star).setOnClickListener(v -> AboutShareRows.star(context));
        card.findViewById(R.id.about_share).setOnClickListener(v -> AboutShareRows.share(context));
        card.findViewById(R.id.about_log).setOnClickListener(v -> DiagnosticLog.share(context));
        ((android.widget.TextView) card.findViewById(R.id.about_terminal)).setText(androidx.core.text.HtmlCompat.fromHtml(
                context.getString(R.string.mobile_about_coffee_note), androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY));
        // A view animation, not an animator: it only runs while the card is drawn.
        android.view.animation.AlphaAnimation blink = new android.view.animation.AlphaAnimation(1f, 0f);
        blink.setDuration(1060);
        blink.setRepeatCount(android.view.animation.Animation.INFINITE);
        blink.setInterpolator(t -> t < 0.5f ? 0f : 1f);
        card.findViewById(R.id.about_cursor).startAnimation(blink);
        TiltParallax.attach((android.view.ViewGroup) card, card.findViewById(R.id.about_avatar), card.findViewById(R.id.about_name),
                card.findViewById(R.id.about_handle), card.findViewById(R.id.about_terminal_box),
                card.findViewById(R.id.about_coffee), card.findViewById(R.id.about_buttons));
        return card;
    }
}
