package com.newtube.mobile.ui.common;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * MEETUBE(issue #41): now and then a feed row reaches the app as bare video ids - a thumbnail and
 * nothing else, no title, channel or length (the same row upstream, yuliskov/SmartTube#6314).
 * Whatever shape YouTube sent it in, a card like that asks YouTube's oEmbed for its title and
 * channel by the id, and is filled in when they come. Debug builds log each one, so the row's
 * source can still be pinned down.
 */
public final class MissingTitles {
    /** The answer for a video id: {title, author}. */
    private static final Map<String, String[]> sFound = new HashMap<>();
    /** Cards waiting on a lookup under way, by video id: {video, onFilled}. */
    private static final Map<String, List<Object[]>> sWaiting = new HashMap<>();
    private static final ExecutorService sFetcher = Executors.newFixedThreadPool(2);
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static OkHttpClient sHttp;

    private MissingTitles() {
    }

    /**
     * {@code video} has no title: fill its title (and its channel, if it has none) from what is
     * known, or ask, and run {@code onFilled} on the main thread once it is filled. Main thread.
     */
    public static void fill(Video video, Runnable onFilled) {
        String id = video.videoId;
        if (id == null || !TextUtils.isEmpty(video.getTitle())) {
            return;
        }
        String[] found = sFound.get(id);
        if (found != null) {
            apply(video, found);
            return; // the caller binds what is now there
        }
        List<Object[]> waiting = sWaiting.get(id);
        if (waiting != null) {
            waiting.add(new Object[] {video, onFilled}); // asked already: one answer fills both
            return;
        }
        List<Object[]> list = new ArrayList<>();
        list.add(new Object[] {video, onFilled});
        sWaiting.put(id, list);
        if (BuildConfig.DEBUG) {
            NetPath.log("title-missing video=" + id + " group=" + (video.getGroup() != null ? video.getGroup().getTitle() : null));
        }
        sFetcher.execute(() -> {
            String[] answer = lookup(id);
            sMain.post(() -> {
                sWaiting.remove(id);
                if (answer == null) {
                    return; // offline, or no oEmbed for it: a later bind asks again
                }
                sFound.put(id, answer);
                for (Object[] entry : list) {
                    apply((Video) entry[0], answer);
                    ((Runnable) entry[1]).run();
                }
            });
        });
    }

    private static void apply(Video video, String[] found) {
        video.title = found[0];
        if (TextUtils.isEmpty(video.author)) {
            video.author = found[1];
        }
    }

    @Nullable
    private static String[] lookup(String videoId) {
        try {
            if (sHttp == null) {
                sHttp = new OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build();
            }
            Request request = new Request.Builder()
                    .url("https://www.youtube.com/oembed?format=json&url=https://www.youtube.com/watch?v=" + videoId)
                    .build();
            try (Response response = sHttp.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return null;
                }
                org.json.JSONObject json = new org.json.JSONObject(response.body().string());
                String title = json.optString("title", "");
                return title.isEmpty() ? null : new String[] {title, json.optString("author_name", "")};
            }
        } catch (Exception e) {
            return null;
        }
    }
}
