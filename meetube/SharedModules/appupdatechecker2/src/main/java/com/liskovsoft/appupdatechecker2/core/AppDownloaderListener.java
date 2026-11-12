package com.liskovsoft.appupdatechecker2.core;

public interface AppDownloaderListener {
    void onApkDownloaded(String path);
    void onDownloadError(Exception e);

    /**
     * NEWTUBE(update-flow): on the main thread, a few times a second.
     * @param total bytes, or -1 when the server didn't say
     */
    default void onDownloadProgress(long bytes, long total) {
    }
}
