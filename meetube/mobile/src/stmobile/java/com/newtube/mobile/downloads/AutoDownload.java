package com.newtube.mobile.downloads;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * MEETUBE(issues #202, #203): Settings > Downloads. The quality a Download tap takes without asking
 * (Ask each time by default), and auto download: new uploads of the picked channels and what the
 * picked playlists hold (Watch later too) download by themselves, up to a quality, on Wi-Fi only
 * unless that is turned off. {@link AutoDownloadWorker} checks every few hours.
 */
public final class AutoDownload {
    public static final int ASK = 0;
    public static final int AUDIO = -1;
    public static final int BEST = Integer.MAX_VALUE;
    /** The choices, tallest first, after Ask (manual only) and before Audio only. */
    public static final int[] HEIGHTS = {2160, 1440, 1080, 720, 480, 360};
    public static final String WATCH_LATER = "WL";

    public static final String CHANNEL = "c";
    public static final String PLAYLIST = "p";
    private static final String SEP = "\u001F";
    private static final String PREFS = "meetube_downloads"; // DownloadFolder's file
    private static final String KEY_QUALITY = "default_quality";
    private static final String KEY_AUTO_QUALITY = "auto_quality";
    private static final String KEY_WIFI = "auto_wifi_only";
    private static final String KEY_SOURCES = "auto_sources";
    private static final String KEY_SEEN = "auto_seen";
    private static final String KEY_PRIMED = "auto_primed";
    private static final String WORK = "meetube_auto_download";
    private static final int SEEN_MAX = 2000;

    private AutoDownload() {
    }

    public static int defaultQuality(Context context) {
        return prefs(context).getInt(KEY_QUALITY, ASK);
    }

    public static void setDefaultQuality(Context context, int quality) {
        prefs(context).edit().putInt(KEY_QUALITY, quality).apply();
    }

    public static int autoQuality(Context context) {
        return prefs(context).getInt(KEY_AUTO_QUALITY, 720);
    }

    public static void setAutoQuality(Context context, int quality) {
        prefs(context).edit().putInt(KEY_AUTO_QUALITY, quality).apply();
    }

    public static boolean wifiOnly(Context context) {
        return prefs(context).getBoolean(KEY_WIFI, true);
    }

    public static void setWifiOnly(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_WIFI, on).apply();
        schedule(context);
    }

    /**
     * The rung a set quality takes: the tallest at or under it (else the shortest there is), or the
     * audio track for {@link #AUDIO}; null when there is nothing to take.
     */
    @Nullable
    public static DownloadOption pick(List<DownloadOption> options, int quality) {
        DownloadOption best = null;
        DownloadOption shortest = null;
        DownloadOption audio = null;
        for (DownloadOption option : options) {
            if (option.isAudioOnly()) {
                audio = option;
                continue;
            }
            if (option.height <= quality && (best == null || option.height > best.height)) {
                best = option;
            }
            if (shortest == null || option.height < shortest.height) {
                shortest = option;
            }
        }
        if (quality == AUDIO) {
            return audio;
        }
        return best != null ? best : shortest != null ? shortest : audio;
    }

    // ---- the picked channels and playlists ---------------------------------------------------

    /** id to title, of {@link #CHANNEL} or {@link #PLAYLIST} sources, in no set order. */
    public static Map<String, String> sources(Context context, String kind) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String entry : prefs(context).getStringSet(KEY_SOURCES, new HashSet<>())) {
            String[] parts = entry.split(SEP, 3);
            if (parts.length == 3 && parts[0].equals(kind)) {
                result.put(parts[1], parts[2]);
            }
        }
        return result;
    }

    public static void setSources(Context context, String kind, Map<String, String> picked) {
        Set<String> all = new HashSet<>();
        for (String entry : prefs(context).getStringSet(KEY_SOURCES, new HashSet<>())) {
            if (!entry.startsWith(kind + SEP)) {
                all.add(entry);
            }
        }
        for (Map.Entry<String, String> source : picked.entrySet()) {
            all.add(kind + SEP + source.getKey() + SEP + source.getValue());
        }
        prefs(context).edit().putStringSet(KEY_SOURCES, all).apply();
        schedule(context);
        checkNow(context);
    }

    // ---- what the worker remembers -------------------------------------------------------------

    static Set<String> seen(Context context) {
        return new HashSet<>(prefs(context).getStringSet(KEY_SEEN, new HashSet<>()));
    }

    /** ponytail: an unordered set trimmed at random past SEEN_MAX; a dropped id at worst downloads again. */
    static void setSeen(Context context, Set<String> seen) {
        List<String> ids = new ArrayList<>(seen);
        Set<String> kept = new HashSet<>(ids.subList(Math.max(0, ids.size() - SEEN_MAX), ids.size()));
        prefs(context).edit().putStringSet(KEY_SEEN, kept).apply();
    }

    /** Channels already looked at once: their uploads from before were marked seen, not downloaded. */
    static Set<String> primed(Context context) {
        return new HashSet<>(prefs(context).getStringSet(KEY_PRIMED, new HashSet<>()));
    }

    static void setPrimed(Context context, Set<String> primed) {
        prefs(context).edit().putStringSet(KEY_PRIMED, primed).apply();
    }

    // ---- scheduling ------------------------------------------------------------------------------

    /** Every 3 hours while any source is picked; call at app start and when the settings change. */
    public static void schedule(Context context) {
        WorkManager work = WorkManager.getInstance(context);
        if (prefs(context).getStringSet(KEY_SOURCES, new HashSet<>()).isEmpty()) {
            work.cancelUniqueWork(WORK);
            return;
        }
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE,
                new PeriodicWorkRequest.Builder(AutoDownloadWorker.class, 3, TimeUnit.HOURS)
                        .setConstraints(constraints(context)).build());
    }

    public static void checkNow(Context context) {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK + "_now", ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(AutoDownloadWorker.class).setConstraints(constraints(context)).build());
    }

    private static Constraints constraints(Context context) {
        return new Constraints.Builder()
                .setRequiredNetworkType(wifiOnly(context) ? NetworkType.UNMETERED : NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
