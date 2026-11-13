package com.newtube.mobile.media;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.annotation.NonNull;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.misc.LocalMedia;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JOYBOY(media tab): the phone's implementation of {@link LocalMedia.Handler}. Reads the device's
 * videos from MediaStore and turns each one into an ordinary {@link Video} card whose
 * {@code localUri} is the content:// URI, so a tap opens it in MeeTube's own player (the same path
 * a finished download takes: VideoLoaderController skips the whole /player fetch for local cards).
 *
 * <p>Step 2a: one flat list, newest first, each card naming its folder. Folder browsing and the
 * Music side come in the next steps.</p>
 */
public final class LocalMediaBridge implements LocalMedia.Handler {
    private static volatile LocalMediaBridge sInstance;

    private static final String FOLDER_PREFIX = "joy_folder_";

    private final Context mContext;
    /** MediaStore bucket id of the open folder, or null at the folders list. */
    private volatile String mOpenBucketId;
    private volatile String mOpenBucketName;

    public static LocalMediaBridge instance(Context context) {
        if (sInstance == null) {
            synchronized (LocalMediaBridge.class) {
                if (sInstance == null) {
                    sInstance = new LocalMediaBridge(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private LocalMediaBridge(Context context) {
        mContext = context;
    }

    public static void install(Context context) {
        LocalMedia.setHandler(instance(context));
    }

    /** The runtime permission that reads the device's videos on this Android version. */
    @NonNull
    public static String permission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public boolean isFolder(Video video) {
        return video != null && video.videoId != null && video.videoId.startsWith(FOLDER_PREFIX);
    }

    @Override
    public void openFolder(Video folder) {
        if (!isFolder(folder)) {
            return;
        }
        mOpenBucketId = folder.videoId.substring(FOLDER_PREFIX.length());
        mOpenBucketName = folder.title;
    }

    @Override
    public boolean closeFolder() {
        boolean wasOpen = mOpenBucketId != null;
        mOpenBucketId = null;
        mOpenBucketName = null;
        return wasOpen;
    }

    @Override
    public String openFolderName() {
        return mOpenBucketName;
    }

    @Override
    public List<Video> listSectionVideos() {
        List<Video> result = new ArrayList<>();
        if (!hasPermission(mContext)) {
            return result; // the Media tab asks for the permission and reloads when it is granted
        }
        String bucket = mOpenBucketId;
        return bucket == null ? listFolders() : listFolderVideos(bucket);
    }

    /** Top level: one card per folder (MediaStore bucket), newest folder first. */
    private List<Video> listFolders() {
        List<Video> result = new ArrayList<>();
        Uri collection = videoCollection();
        String[] columns = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.BUCKET_ID,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.DATE_ADDED,
        };
        java.util.LinkedHashMap<String, Video> folders = new java.util.LinkedHashMap<>();
        java.util.HashMap<String, Integer> counts = new java.util.HashMap<>();
        try (Cursor cursor = mContext.getContentResolver().query(collection, columns, null, null,
                MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (cursor == null) {
                return result;
            }
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID);
            int bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
            while (cursor.moveToNext()) {
                String bucketId = cursor.getString(bucketIdCol);
                if (bucketId == null) {
                    continue;
                }
                Integer n = counts.get(bucketId);
                counts.put(bucketId, n == null ? 1 : n + 1);
                if (!folders.containsKey(bucketId)) { // rows are newest first: the first is the cover
                    Uri cover = ContentUris.withAppendedId(collection, cursor.getLong(idCol));
                    Video card = new Video();
                    card.videoId = FOLDER_PREFIX + bucketId;
                    String name = cursor.getString(bucketNameCol);
                    card.title = name != null ? name : "";
                    card.cardImageUrl = cover.toString();
                    card.percentWatched = -1;
                    folders.put(bucketId, card);
                }
            }
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
        for (java.util.Map.Entry<String, Video> e : folders.entrySet()) {
            Video card = e.getValue();
            Integer n = counts.get(e.getKey());
            int count = n == null ? 0 : n;
            card.badge = String.valueOf(count);
            card.secondTitle = count + " \u200Fفيديو";
            result.add(card);
        }
        return result;
    }

    private static Uri videoCollection() {
        return Build.VERSION.SDK_INT >= 29
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    }

    /** Inside a folder: its videos, newest first. */
    private List<Video> listFolderVideos(String bucketId) {
        List<Video> result = new ArrayList<>();

        Uri collection = Build.VERSION.SDK_INT >= 29
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] columns = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        };

        try (Cursor cursor = mContext.getContentResolver().query(collection, columns,
                MediaStore.Video.Media.BUCKET_ID + "=?", new String[]{bucketId},
                MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (cursor == null) {
                return result;
            }
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
            int bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                Uri uri = ContentUris.withAppendedId(collection, id);

                Video card = new Video();
                card.videoId = "joy_local_" + id; // never a YouTube id: every network path is guarded by isLocal()
                card.title = stripExtension(cursor.getString(nameCol));
                card.author = cursor.getString(bucketCol);
                card.localUri = uri.toString();
                card.cardImageUrl = uri.toString(); // Glide takes the frame of a content:// video itself
                long durationMs = cursor.getLong(durCol);
                card.badge = durationMs > 0 ? formatDuration(durationMs) : null;
                card.secondTitle = card.author != null ? card.author : "";
                card.percentWatched = -1;
                result.add(card);
            }
        } catch (RuntimeException e) {
            // A revoked permission or a store that is mid-scan: an empty tab, not a crash.
            result.clear();
        }
        return result;
    }

    private static String stripExtension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String formatDuration(long ms) {
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return h > 0
                ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.US, "%d:%02d", m, s);
    }
}
