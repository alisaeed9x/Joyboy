package com.liskovsoft.appupdatechecker2;

import java.util.List;

public interface AppUpdateCheckerListener {
    String UPDATE_CHECK_DISABLED = "Update check disabled";
    String LATEST_VERSION = "Latest version";
    /**
     * Callback fired when update is found and apk is downloaded and ready to install.
     * @param changelog items what is changed
     */
    void onUpdateFound(String versionName, List<String> changelog, String apkPath);
    void onUpdateError(Exception error);

    /**
     * NEWTUBE(update-flow): with {@link AppUpdateChecker#setDownloadOnCheck} off, a newer version
     * exists. Nothing has been downloaded unless {@link UpdateInfo#apkPath} is set (an earlier
     * download for the same version); {@link AppUpdateChecker#downloadUpdate()} fetches it.
     */
    default void onUpdateAvailable(UpdateInfo info) {
    }

    /**
     * NEWTUBE(update-flow): the installed version is the newest. {@link UpdateInfo#installedRelease}
     * carries its notes when the manifest lists them. Callers that only know errors keep getting
     * {@link #LATEST_VERSION} there.
     */
    default void onUpToDate(UpdateInfo info) {
        onUpdateError(new IllegalStateException(LATEST_VERSION));
    }

    /**
     * NEWTUBE(update-flow): download progress, on the main thread a few times a second.
     * @param total bytes, or -1 when neither the server nor the manifest said
     */
    default void onDownloadProgress(long bytes, long total) {
    }

    /**
     * NEWTUBE(update-flow): the APK download failed, or was cancelled ({@link AppUpdateChecker#cancelDownload()}).
     * Kept apart from a failed check so an update screen can say which one it was.
     */
    default void onDownloadError(Exception error) {
        onUpdateError(error);
    }
}
