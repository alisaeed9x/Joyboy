package com.newtube.mobile.downloads;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.youtubeapi.service.YouTubeMediaItemService;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MEETUBE(issues #202, #203): one auto download check. A channel's uploads from before it was
 * picked are only marked seen; after that each new one is queued. A playlist is kept offline:
 * whatever it holds that is not here yet is queued, a few per check. Then the queue drains.
 */
public final class AutoDownloadWorker extends Worker {
    private static final String TAG = "AutoDownload";
    /** ponytail: a first check of a long Watch later would queue it all; more come at the next checks. */
    private static final int PER_PLAYLIST = 10;

    public AutoDownloadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        ContentService content = YouTubeServiceManager.instance().getContentService();
        Set<String> seen = AutoDownload.seen(context);
        List<MediaItem> wanted = new ArrayList<>();

        Map<String, String> channels = AutoDownload.sources(context, AutoDownload.CHANNEL);
        Set<String> primed = AutoDownload.primed(context);
        List<String> fresh = new ArrayList<>();
        List<String> known = new ArrayList<>();
        for (String id : channels.keySet()) {
            (primed.contains(id) ? known : fresh).add(id);
        }
        try {
            if (!fresh.isEmpty()) {
                for (MediaItem item : items(content.getRssFeed(fresh.toArray(new String[0])))) {
                    seen.add(item.getVideoId());
                }
                primed.addAll(fresh);
            }
            if (!known.isEmpty()) {
                wanted.addAll(items(content.getRssFeed(known.toArray(new String[0]))));
            }
        } catch (Exception e) {
            Log.w(TAG, "channels: " + e);
        }
        primed.retainAll(channels.keySet());

        for (Map.Entry<String, String> playlist : AutoDownload.sources(context, AutoDownload.PLAYLIST).entrySet()) {
            Video video = new Video();
            video.playlistId = playlist.getKey();
            video.title = playlist.getValue();
            try {
                List<MediaItem> items = items(content.getGroup(video.toMediaItem()));
                wanted.addAll(items.subList(0, Math.min(PER_PLAYLIST, items.size())));
            } catch (Exception e) {
                Log.w(TAG, "playlist " + playlist.getKey() + ": " + e);
            }
        }

        DownloadRegistry registry = DownloadRegistry.instance(context);
        int quality = AutoDownload.autoQuality(context);
        int kind = quality == AutoDownload.AUDIO ? DownloadOption.KIND_AUDIO : DownloadOption.KIND_VIDEO;
        for (MediaItem item : wanted) {
            String videoId = item.getVideoId();
            if (videoId == null || item.isLive() || item.isUpcoming() || !seen.add(videoId)
                    || registry.findDone(videoId, kind) != null || registry.findActive(videoId) != null) {
                continue;
            }
            try {
                MediaItemFormatInfo info = YouTubeMediaItemService.instance().getFormatInfo(videoId);
                if (info == null || info.isUnplayable()) {
                    continue;
                }
                DownloadOption option = AutoDownload.pick(
                        DownloadOptions.build(info.getAdaptiveFormats(), info.getUrlFormats()), quality);
                if (option == null) {
                    continue;
                }
                String title = item.getTitle() != null ? item.getTitle() : info.getTitle();
                registry.add(DownloadItem.create(videoId, title != null ? title : videoId,
                        item.getAuthor() != null ? item.getAuthor() : info.getAuthor(),
                        "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg", option));
            } catch (Exception e) {
                seen.remove(videoId); // try again at the next check
                Log.w(TAG, videoId + ": " + e);
            }
        }

        AutoDownload.setSeen(context, seen);
        AutoDownload.setPrimed(context, primed);
        MobileDownloadService.startOrDrain(context);
        return Result.success();
    }

    private static List<MediaItem> items(MediaGroup group) {
        List<MediaItem> items = group != null ? group.getMediaItems() : null;
        return items != null ? items : new ArrayList<>();
    }
}
