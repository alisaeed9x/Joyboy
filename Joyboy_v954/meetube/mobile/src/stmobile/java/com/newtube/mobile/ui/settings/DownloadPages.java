package com.newtube.mobile.ui.settings;

import android.content.Context;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.newtube.mobile.downloads.AutoDownload;
import com.newtube.mobile.downloads.DownloadFolder;
import com.newtube.mobile.ui.common.MobileSnackbar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;

/**
 * MEETUBE(issues #202, #203): Settings > Downloads - the folder, the quality a Download tap takes
 * (Ask each time by default), and auto download from picked channels and playlists.
 */
final class DownloadPages {
    private DownloadPages() {
    }

    static SettingsPages.Page downloads(Context context) {
        List<SettingsRow> rows = new ArrayList<>();

        if (DownloadFolder.isSupported()) { // MEETUBE(download-folder): also a long press on any Download button
            rows.add(SettingsRow.action(0, context.getString(R.string.mobile_download_folder),
                    () -> DownloadFolder.label(context),
                    page -> page.showDialog(DownloadFolder.showOptions(page.requireActivity()))));
        }
        rows.add(quality(context, context.getString(R.string.mobile_settings_download_quality), true,
                AutoDownload.defaultQuality(context), q -> AutoDownload.setDefaultQuality(context, q)));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_auto_download)));
        rows.add(SettingsRow.action(0, context.getString(R.string.mobile_settings_auto_channels),
                () -> count(context, AutoDownload.CHANNEL),
                page -> pick(page, AutoDownload.CHANNEL)));
        rows.add(SettingsRow.action(0, context.getString(R.string.mobile_settings_auto_playlists),
                () -> count(context, AutoDownload.PLAYLIST),
                page -> pick(page, AutoDownload.PLAYLIST)));
        rows.add(quality(context, context.getString(R.string.mobile_settings_auto_quality), false,
                AutoDownload.autoQuality(context), q -> AutoDownload.setAutoQuality(context, q)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_auto_wifi),
                context.getString(R.string.mobile_settings_auto_wifi_summary),
                () -> AutoDownload.wifiOnly(context), on -> AutoDownload.setWifiOnly(context, on)));
        rows.add(SettingsRow.action(context.getString(R.string.mobile_settings_auto_now), null, page -> {
            AutoDownload.checkNow(context);
            MobileSnackbar.show(context, R.string.mobile_settings_auto_now_done);
        }));
        rows.add(SettingsRow.note(context.getString(R.string.mobile_settings_auto_note)));

        return new SettingsPages.Page(context.getString(R.string.header_downloads), rows);
    }

    private static SettingsRow quality(Context context, String title, boolean ask, int current,
                                       SettingsRow.Pick<Integer> set) {
        SettingsRow.Choice<Integer> choice = SettingsRow.choice(title);
        if (ask) {
            choice.option(context.getString(R.string.mobile_settings_download_ask), AutoDownload.ASK);
        }
        choice.option(context.getString(R.string.mobile_settings_download_best), AutoDownload.BEST);
        for (int height : AutoDownload.HEIGHTS) {
            choice.option(height + "p", height);
        }
        choice.option(context.getString(R.string.mobile_download_audio_only), AutoDownload.AUDIO);
        return choice.bind(() -> current, set);
    }

    private static CharSequence count(Context context, String kind) {
        Map<String, String> picked = AutoDownload.sources(context, kind);
        if (picked.isEmpty()) {
            return context.getString(R.string.mobile_settings_auto_none);
        }
        return android.text.TextUtils.join(", ", picked.values());
    }

    /** The subscribed channels, or Watch later and the account's playlists, as a list to tick. */
    private static void pick(SettingsPageFragment page, String kind) {
        Context context = page.requireContext();
        boolean channels = AutoDownload.CHANNEL.equals(kind);
        Observable<MediaGroup> fetch = channels
                ? YouTubeServiceManager.instance().getContentService().getSubscribedChannelsByNameObserve()
                : YouTubeServiceManager.instance().getContentService().getPlaylistsObserve();
        fetch.observeOn(AndroidSchedulers.mainThread()).subscribe(group -> {
            if (!page.isAdded()) {
                return;
            }
            Map<String, String> offered = new LinkedHashMap<>();
            if (!channels) {
                offered.put(AutoDownload.WATCH_LATER, context.getString(R.string.mobile_settings_auto_watch_later));
            }
            List<MediaItem> items = group != null ? group.getMediaItems() : null;
            for (int i = 0; items != null && i < items.size(); i++) {
                MediaItem item = items.get(i);
                String id = channels ? item.getChannelId() : item.getPlaylistId();
                if (id != null && item.getTitle() != null && !offered.containsKey(id)) {
                    offered.put(id, item.getTitle());
                }
            }
            if (offered.isEmpty()) {
                MobileSnackbar.show(context, R.string.mobile_settings_auto_sign_in);
                return;
            }
            Map<String, String> picked = new LinkedHashMap<>(AutoDownload.sources(context, kind));
            List<String> ids = new ArrayList<>(offered.keySet());
            CharSequence[] titles = offered.values().toArray(new CharSequence[0]);
            boolean[] checked = new boolean[ids.size()];
            for (int i = 0; i < ids.size(); i++) {
                checked[i] = picked.containsKey(ids.get(i));
            }
            page.showDialog(new MaterialAlertDialogBuilder(page.requireActivity())
                    .setTitle(channels ? R.string.mobile_settings_auto_channels : R.string.mobile_settings_auto_playlists)
                    .setMultiChoiceItems(titles, checked, (dialog, which, on) -> {
                        if (on) {
                            picked.put(ids.get(which), titles[which].toString());
                        } else {
                            picked.remove(ids.get(which));
                        }
                    })
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        AutoDownload.setSources(context, kind, picked);
                        page.rebuild();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
        }, error -> {
            if (page.isAdded()) {
                MobileSnackbar.show(context, R.string.mobile_settings_auto_sign_in);
            }
        });
    }
}
