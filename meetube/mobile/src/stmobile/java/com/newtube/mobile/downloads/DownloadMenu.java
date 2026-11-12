package com.newtube.mobile.downloads;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.io.File;

/**
 * The context menu of a download: what the long-press on a Downloads card and the watch page's
 * "Downloaded" pill open. Same app dialog as every other card menu, so it looks native.
 */
public final class DownloadMenu {
    private DownloadMenu() {
    }

    public static void show(Context context, @Nullable DownloadItem item) {
        if (context == null || item == null) {
            return;
        }

        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        DownloadStorage storage = new DownloadStorage(context);

        if (item.isDone()) {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.mobile_watch_share),
                    optionItem -> {
                        dialog.closeDialog();
                        share(context, item);
                    }));
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.mobile_download_open_folder),
                    optionItem -> {
                        dialog.closeDialog();
                        openFolder(context, item);
                    }));
        }
        if (item.isFailed() || item.isPaused()) {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(item.isPaused()
                            ? R.string.mobile_download_resume : R.string.mobile_download_retry),
                    optionItem -> {
                        dialog.closeDialog();
                        MobileDownloadService.resume(context, item);
                    }));
        }
        if (item.state == DownloadItem.STATE_QUEUED || item.state == DownloadItem.STATE_DOWNLOADING) {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.mobile_download_pause),
                    optionItem -> {
                        dialog.closeDialog();
                        MobileDownloadService.pause(context, item);
                    }));
        }
        if (item.isActive()) {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.mobile_download_cancel),
                    optionItem -> {
                        dialog.closeDialog();
                        MobileDownloadService.cancel(context, item);
                    }));
        } else {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.mobile_download_delete),
                    optionItem -> {
                        dialog.closeDialog();
                        delete(context, item);
                    }));
        }

        String subtitle = DownloadProgressText.of(context, item);
        dialog.showDialog(item.title + "\n" + subtitle);
    }

    public static void share(Context context, DownloadItem item) {
        Uri uri = new DownloadStorage(context).shareUri(item.outputUri);
        if (uri == null) {
            com.newtube.mobile.ui.common.MobileSnackbar.show(context, R.string.mobile_download_file_missing);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType(item.isAudioOnly() ? "audio/mp4" : DownloadJob.mimeOf(item.fileName))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, item.title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(intent, context.getString(R.string.mobile_watch_share));
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(chooser);
    }

    /** The file's folder in the system file manager; where it is, in words, when none can open it. */
    public static void openFolder(Context context, DownloadItem item) {
        Uri folder = new DownloadStorage(context).folderOf(item.outputUri, item.isAudioOnly());
        if (folder == null) {
            com.newtube.mobile.ui.common.MobileSnackbar.show(context, R.string.mobile_download_file_missing);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            String id = DocumentsContract.getDocumentId(folder);
            String path = id.substring(id.indexOf(':') + 1);
            com.newtube.mobile.ui.common.MobileSnackbar.show(context,
                    context.getString(R.string.mobile_download_saved_in, path.isEmpty() ? "/" : path), null, null);
        }
    }

    public static void delete(Context context, DownloadItem item) {
        DownloadStorage storage = new DownloadStorage(context);
        storage.delete(item.outputUri);
        if (item.thumbPath != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(item.thumbPath).delete();
        }
        File parts = DownloadRegistry.partsDir(context);
        //noinspection ResultOfMethodCallIgnored
        new File(parts, item.id + ".video").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(parts, item.id + ".audio").delete();
        //noinspection ResultOfMethodCallIgnored
        DownloadOptions.audioIdFile(new File(parts, item.id + ".audio")).delete();
        DownloadRegistry.instance(context).remove(item);
    }
}
