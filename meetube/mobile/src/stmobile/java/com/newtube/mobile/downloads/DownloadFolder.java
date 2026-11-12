package com.newtube.mobile.downloads;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.MobileSnackbar;

/**
 * MEETUBE(download-folder): a folder the user picks through the system picker (internal storage,
 * an SD card, anything the picker offers). Unset means the default: Movies/MeeTube and Music/MeeTube.
 * Opened from Settings > General and by a long press on Download.
 */
public final class DownloadFolder {
    private static final String PREFS = "meetube_downloads";
    private static final String KEY_TREE = "folder_tree_uri";

    private DownloadFolder() {
    }

    /** The muxer writes into a file descriptor, which needs Android 8; older phones keep the default. */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    @Nullable
    static Uri get(Context context) {
        String tree = prefs(context).getString(KEY_TREE, null);
        return isSupported() && tree != null ? Uri.parse(tree) : null;
    }

    static void set(Context context, @Nullable Uri tree) {
        Uri old = get(context);
        if (old != null && !old.equals(tree)) {
            try {
                context.getContentResolver().releasePersistableUriPermission(old,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // already gone (folder deleted, permission revoked)
            }
        }
        prefs(context).edit().putString(KEY_TREE, tree != null ? tree.toString() : null).apply();
    }

    /** "Download/MeeTube", or the default's description. */
    public static CharSequence label(Context context) {
        Uri tree = get(context);
        if (tree == null) {
            return context.getString(R.string.mobile_download_folder_default);
        }
        String id = DocumentsContract.getTreeDocumentId(tree); // "primary:Download/MeeTube", "1A2B-3C4D:Videos"
        int colon = id.indexOf(':');
        String volume = colon >= 0 ? id.substring(0, colon) : "";
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        if ("primary".equals(volume)) {
            return path.isEmpty() ? context.getString(R.string.mobile_download_folder_internal) : path;
        }
        return path.isEmpty() ? volume : volume + "/" + path;
    }

    /** Keep the default or pick a folder; returns the shown dialog. */
    public static AlertDialog showOptions(Activity activity) {
        CharSequence[] items = {
                activity.getString(R.string.mobile_download_folder_choose),
                activity.getString(R.string.mobile_download_folder_use_default)
        };
        return new MaterialAlertDialogBuilder(activity, R.style.MobileAlertDialog)
                .setTitle(activity.getString(R.string.mobile_download_folder_title, label(activity)))
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        activity.startActivity(new Intent(activity, DownloadFolderActivity.class));
                    } else {
                        set(activity, null);
                        MobileSnackbar.show(activity, R.string.mobile_download_folder_reset);
                    }
                })
                .show();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
