package com.liskovsoft.appupdatechecker2;

import java.util.Collections;
import java.util.List;

/**
 * NEWTUBE(update-flow): what a check found, for an update screen that shows the release notes and
 * asks before it downloads anything ({@link AppUpdateChecker#setDownloadOnCheck}).
 */
public final class UpdateInfo {
    /** The newest published version. */
    public final String versionName;
    public final int versionCode;
    /** The published versions newer than the installed one, newest first (empty when up to date). */
    public final List<ReleaseNotes> newReleases;
    /** The installed version's own entry, or null when the manifest doesn't list it. */
    public final ReleaseNotes installedRelease;
    /** Bytes to download, or -1 when the manifest doesn't say. */
    public final long downloadSize;
    /** The newest version's APK, already downloaded and verified, or null. */
    public final String apkPath;

    public UpdateInfo(String versionName, int versionCode, List<ReleaseNotes> newReleases,
                      ReleaseNotes installedRelease, long downloadSize, String apkPath) {
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.newReleases = newReleases != null ? Collections.unmodifiableList(newReleases) : Collections.emptyList();
        this.installedRelease = installedRelease;
        this.downloadSize = downloadSize;
        this.apkPath = apkPath;
    }

    public UpdateInfo withApkPath(String path) {
        return new UpdateInfo(versionName, versionCode, newReleases, installedRelease, downloadSize, path);
    }
}
