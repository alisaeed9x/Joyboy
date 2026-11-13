package com.newtube.mobile.ui.channel;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * MEETUBE(channel header, issue #10): the top of a channel page - banner, avatar, name, handle,
 * "subscribers • videos" and description. The TV client's channel browse carries none of it, so
 * this reads the header of an anonymous MWEB browse of the channel (~70 KB gzipped) on a plain
 * client: the shared one adds the TV sign-in, which WEB endpoints refuse (as ChannelAvatars).
 */
final class ChannelHeader {
    static final class Info {
        String title;
        String handle;
        String stats;
        String description;
        String avatarUrl;
        String bannerUrl;
        /** MEETUBE(issue #73): the avatar's "LIVE" badge (YouTube's wording) and the stream, when live. */
        String liveBadge;
        String liveVideoId;
    }

    interface Callback {
        void onInfo(Info info);
    }

    private static final ExecutorService sFetcher = Executors.newSingleThreadExecutor();
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    /** Main thread only; a few channels per session. */
    private static final Map<String, Info> sCache = new java.util.LinkedHashMap<String, Info>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Info> eldest) {
            return size() > 20;
        }
    };
    private static OkHttpClient sHttp;

    private ChannelHeader() {
    }

    /** Main thread. {@code callback} runs on the main thread, only when a header was found. */
    static void fetch(String channelId, Callback callback) {
        Info cached = sCache.get(channelId);
        if (cached != null) {
            callback.onInfo(cached);
            return;
        }
        sFetcher.execute(() -> {
            Info info = load(channelId);
            if (info != null) {
                sMain.post(() -> {
                    sCache.put(channelId, info);
                    callback.onInfo(info);
                });
            }
        });
    }

    interface ViewsCallback {
        void onViews(Map<String, String> views);
    }

    /** The channel's Shorts tab, as MWEB's channel page asks for it. */
    private static final String SHORTS_TAB_PARAMS = "EgZzaG9ydHPyBgUKA5oBAA==";

    /**
     * MEETUBE(shorts): "26K views" for each short on the channel's Shorts tab, by video id - the TV
     * client's Shorts tiles carry only the handle. One anonymous MWEB browse of that tab: its first
     * page, ~48 shorts; later ones go without. Main thread; {@code callback} on it, when found.
     */
    static void fetchShortsViews(String channelId, ViewsCallback callback) {
        sFetcher.execute(() -> {
            Map<String, String> views = new java.util.HashMap<>();
            JSONObject response = browse(channelId, SHORTS_TAB_PARAMS);
            if (response != null) {
                collectShortsViews(response, views);
            }
            if (!views.isEmpty()) {
                sMain.post(() -> callback.onViews(views));
            }
        });
    }

    private static void collectShortsViews(@Nullable Object node, Map<String, String> out) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject lockup = object.optJSONObject("shortsLockupViewModel");
            if (lockup != null) {
                JSONObject reel = path(lockup, "onTap", "innertubeCommand", "reelWatchEndpoint");
                String views = text(path(lockup, "overlayMetadata", "secondaryText"));
                if (reel != null && views != null) {
                    out.put(reel.optString("videoId"), views);
                }
            }
            for (java.util.Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                collectShortsViews(object.opt(keys.next()), out);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                collectShortsViews(array.opt(i), out);
            }
        }
    }

    interface IdCallback {
        void onId(String channelId);
    }

    /** Main thread only: "@handle" -> UC id, for the session. */
    private static final Map<String, String> sHandles = new java.util.HashMap<>();

    /**
     * A channel opened from a youtube.com/@handle link knows only the handle all the way through
     * (the TV browse takes it, and a Shorts tab's tiles carry no channel id), while the header,
     * Subscribe and the Shorts tab's view counts need the UC id: MWEB's resolve_url gives it.
     * Main thread; {@code callback} on it, when found.
     */
    static void resolveHandle(String handle, IdCallback callback) {
        String cached = sHandles.get(handle);
        if (cached != null) {
            callback.onId(cached);
            return;
        }
        sFetcher.execute(() -> {
            JSONObject response = null;
            try {
                response = call("navigation/resolve_url",
                        new JSONObject().put("url", "https://www.youtube.com/" + handle));
            } catch (org.json.JSONException ignored) {
                // a constant body
            }
            JSONObject browse = path(response, "endpoint", "browseEndpoint");
            String id = browse != null ? browse.optString("browseId") : "";
            if (id.startsWith("UC")) {
                sMain.post(() -> {
                    sHandles.put(handle, id);
                    callback.onId(id);
                });
            }
        });
    }

    @Nullable
    private static Info load(String channelId) {
        JSONObject response = browse(channelId, null);
        return response != null ? parse(response) : null;
    }

    @Nullable
    private static JSONObject browse(String channelId, @Nullable String params) {
        try {
            JSONObject body = new JSONObject().put("browseId", channelId);
            if (params != null) {
                body.put("params", params);
            }
            return call("browse", body);
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /** An anonymous MWEB InnerTube call: {@code body} plus the client context. */
    @Nullable
    private static JSONObject call(String endpoint, JSONObject body) {
        try {
            if (sHttp == null) {
                sHttp = new OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build();
            }
            // The feeds' own hl (BCP-47: zh-TW, not "zh", which YouTube answers in Simplified).
            body.put("context", new JSONObject().put("client", new JSONObject()
                    .put("clientName", "MWEB").put("clientVersion", "2.20251015.01.00")
                    .put("hl", com.liskovsoft.googlecommon.common.locale.LocaleManager.instance().getLanguage())));
            Request request = new Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/" + endpoint + "?prettyPrint=false")
                    .post(RequestBody.create(body.toString(), MediaType.get("application/json")))
                    .build();
            try (Response response = sHttp.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return null;
                }
                return new JSONObject(response.body().string());
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** The header of a browse response (pageHeaderViewModel), or null when it has none. */
    @Nullable
    static Info parse(JSONObject response) {
        JSONObject header = path(response, "header", "pageHeaderRenderer", "content", "pageHeaderViewModel");
        if (header == null) {
            return null;
        }
        Info info = new Info();
        info.title = text(path(header, "title", "dynamicTextViewModel", "text"));
        info.avatarUrl = largest(path(header, "image", "decoratedAvatarViewModel", "avatar", "avatarViewModel", "image"), 0);
        JSONObject avatar = path(header, "image", "decoratedAvatarViewModel");
        info.liveBadge = avatar != null && path(avatar, "liveData") != null
                ? path(avatar, "liveData").optString("liveBadgeText", "").trim() : "";
        JSONObject watch = path(avatar, "rendererContext", "commandContext", "onTap", "innertubeCommand", "watchEndpoint");
        info.liveVideoId = watch != null ? watch.optString("videoId", "") : "";
        if (info.liveBadge.isEmpty() || info.liveVideoId.isEmpty()) {
            info.liveBadge = info.liveVideoId = null;
        }
        info.bannerUrl = largest(path(header, "banner", "imageBannerViewModel", "image"), 1080);
        info.description = text(path(header, "description", "descriptionPreviewViewModel", "description"));
        List<String> stats = new ArrayList<>();
        JSONArray rows = path(header, "metadata", "contentMetadataViewModel") != null
                ? path(header, "metadata", "contentMetadataViewModel").optJSONArray("metadataRows") : null;
        for (int r = 0; rows != null && r < rows.length(); r++) {
            JSONArray parts = rows.optJSONObject(r) != null ? rows.optJSONObject(r).optJSONArray("metadataParts") : null;
            for (int p = 0; parts != null && p < parts.length(); p++) {
                String part = parts.optJSONObject(p) != null ? text(parts.optJSONObject(p).optJSONObject("text")) : null;
                if (part == null) {
                    continue;
                }
                if (part.startsWith("@") && info.handle == null) {
                    info.handle = part;
                } else {
                    stats.add(part);
                }
            }
        }
        info.stats = stats.isEmpty() ? null : android.text.TextUtils.join(" • ", stats);
        return info.title != null ? info : null;
    }

    @Nullable
    private static JSONObject path(@Nullable JSONObject node, String... keys) {
        for (String key : keys) {
            if (node == null) {
                return null;
            }
            node = node.optJSONObject(key);
        }
        return node;
    }

    @Nullable
    private static String text(@Nullable JSONObject node) {
        String content = node != null ? node.optString("content", "").trim() : "";
        return content.isEmpty() ? null : content;
    }

    /** The first source at least {@code minWidth} wide, else the widest. */
    @Nullable
    private static String largest(@Nullable JSONObject image, int minWidth) {
        JSONArray sources = image != null ? image.optJSONArray("sources") : null;
        if (sources == null || sources.length() == 0) {
            return null;
        }
        JSONObject pick = sources.optJSONObject(sources.length() - 1);
        for (int i = 0; minWidth > 0 && i < sources.length(); i++) {
            JSONObject source = sources.optJSONObject(i);
            if (source != null && source.optInt("width") >= minWidth) {
                pick = source;
                break;
            }
        }
        String url = pick != null ? pick.optString("url", "") : "";
        return url.startsWith("//") ? "https:" + url : (url.startsWith("http") ? url : null);
    }
}
