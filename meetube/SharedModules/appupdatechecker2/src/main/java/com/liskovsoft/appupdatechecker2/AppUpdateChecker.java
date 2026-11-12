package com.liskovsoft.appupdatechecker2;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import com.liskovsoft.appupdatechecker2.core.AppDownloader;
import com.liskovsoft.appupdatechecker2.core.AppDownloaderListener;
import com.liskovsoft.appupdatechecker2.core.AppVersionChecker;
import com.liskovsoft.appupdatechecker2.core.AppVersionCheckerListener;
import com.liskovsoft.appupdatechecker2.core.UpdateManifest;
import com.liskovsoft.appupdatechecker2.other.SettingsManager;
import com.liskovsoft.appupdatechecker2.other.downloadmanager.DownloadManager;
import com.liskovsoft.sharedutils.helpers.FileHelpers;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AppUpdateChecker implements AppVersionCheckerListener, AppDownloaderListener {
    private static final String TAG = AppUpdateChecker.class.getSimpleName();
    private static final int MIN_APK_SIZE_BYTES = 1_000_000; // 1 MB
    // NEWTUBE(update-metered): an automatic check that found an update on a metered network looks
    // again after this long (the manifest is small), instead of after the full check interval.
    private static final long METERED_RECHECK_MS = 60 * 60 * 1_000L; // 1 hour
    private final Context mContext;
    private final AppVersionChecker mVersionChecker;
    private final AppDownloader mDownloader;
    private final AppUpdateCheckerListener mListener;
    private final SettingsManager mSettingsManager;
    private List<String> mChangeLog;
    private String mLatestVersionName;
    private int mLatestVersionNumber;
    // NEWTUBE(update-flow): what the last check found, for downloadUpdate() and the update screen
    private Uri[] mDownloadUris;
    private long mDownloadSize = -1;
    private boolean mDownloadOnCheck = true;
    // NEWTUBE(update-metered): the user asked (forceCheckForUpdates) during the check in flight - also
    // when their request was folded into an automatic check already running. Consumed by the result.
    // Only a user-initiated check may download the APK on a metered network.
    private volatile boolean mIsUserInitiated;

    public AppUpdateChecker(Context context, AppUpdateCheckerListener listener) {
        this(context, listener, MIN_APK_SIZE_BYTES);
    }

    public AppUpdateChecker(Context context, AppUpdateCheckerListener listener, int minApkSizeBytes) {
        Log.d(TAG, "Starting...");

        FileHelpers.checkCachePermissions(context); // should be an Activity context

        mContext = context.getApplicationContext();
        mListener = listener;
        mVersionChecker = new AppVersionChecker(mContext, this);
        mDownloader = new AppDownloader(mContext, this, minApkSizeBytes);
        mSettingsManager = new SettingsManager(mContext);

        // Cleanup the storage. I don't want to accidentally install old version.
        //FileHelpers.delete(mSettingsManager.getApkPath());
    }

    /**
     * You normally shouldn't need to call this, as {@link #checkForUpdates(String[] versionListUrls)} checks it before doing any updates.
     *
     * @return true if the updater should check for updates
     */
    private boolean isStale() {
        if (mSettingsManager.getMinIntervalMs() < 0) {
            return false;
        }

        return System.currentTimeMillis() - mSettingsManager.getLastCheckedMs() > mSettingsManager.getMinIntervalMs();
    }

    public void checkForUpdates(String updateManifestUrl) {
        checkForUpdates(new String[]{updateManifestUrl});
    }

    /**
     * Checks for updates if updates haven't been checked for recently and if checking is enabled.
     */
    public void checkForUpdates(String[] updateManifestUrls) {
        if (isUpdateCheckEnabled() && isStale()) {
            checkForUpdatesInt(updateManifestUrls);
        } else {
            mListener.onUpdateError(new IllegalStateException(AppUpdateCheckerListener.UPDATE_CHECK_DISABLED));
        }
    }

    public void forceCheckForUpdates(String updateManifestUrl) {
        forceCheckForUpdates(new String[]{updateManifestUrl});
    }

    public void forceCheckForUpdates(String[] updateManifestUrls) {
        mIsUserInitiated = true;
        checkForUpdatesInt(updateManifestUrls);
    }

    /**
     * NEWTUBE(update-flow): false = a check only reads the manifest and reports a newer version
     * through {@link AppUpdateCheckerListener#onUpdateAvailable}; the APK is fetched when the user
     * asks ({@link #downloadUpdate()}), with progress. true (the default) = the old behaviour: the
     * check downloads the APK first and reports it through onUpdateFound.
     */
    public void setDownloadOnCheck(boolean downloadOnCheck) {
        mDownloadOnCheck = downloadOnCheck;
    }

    /**
     * NEWTUBE(update-flow): downloads the version the last check found. Ends in
     * {@link AppUpdateCheckerListener#onUpdateFound} or {@link AppUpdateCheckerListener#onDownloadError}.
     */
    public void downloadUpdate() {
        if (mDownloadUris == null || mDownloadUris.length == 0) {
            mListener.onDownloadError(new IllegalStateException("No update to download"));
            return;
        }

        mDownloader.download(mDownloadUris, true);
    }

    /** NEWTUBE(update-flow): stops {@link #downloadUpdate()}; it then ends in onDownloadError. */
    public void cancelDownload() {
        mDownloader.cancel();
    }

    public boolean isDownloading() {
        return mDownloader.isInProgress();
    }

    /**
     * NEWTUBE(update-flow): the APK an earlier download left behind, when it is still a complete
     * package of a version newer than the installed one - so a restarted app can offer Install
     * without the network. Parses the APK: call it when the answer is needed, not at startup.
     * The notes are the caller's to fill in. Null when there is none.
     */
    public UpdateInfo getDownloadedUpdate() {
        int versionCode = mSettingsManager.getLatestVersionNumber();
        String path = mSettingsManager.getApkPath();

        if (versionCode <= getInstalledVersionCode() || mDownloader.isInProgress() || !checkApk(path, versionCode)) {
            return null;
        }

        return new UpdateInfo(mSettingsManager.getLatestVersionName(), versionCode, null, null, -1, path);
    }

    /** NEWTUBE(update-flow): drops the downloaded APK once its version (or a newer one) is installed. */
    public void discardInstalledUpdate() {
        String path = mSettingsManager.getApkPath();

        if (path != null && !mDownloader.isInProgress()
                && mSettingsManager.getLatestVersionNumber() <= getInstalledVersionCode()) {
            FileHelpers.delete(path);
        }
    }

    private void checkForUpdatesInt(String[] updateManifestUrls) {
        if (!checkPostponed()) {
            Uri[] uris = new Uri[updateManifestUrls.length];

            for (int i = 0; i < updateManifestUrls.length; i++) {
                uris[i] = Uri.parse(updateManifestUrls[i]);
            }

            checkForUpdatesInt(uris);
        }
    }

    private void checkForUpdatesInt(Uri[] updateManifestUrls) {
        if (!checkPostponed()) {
            mVersionChecker.checkForUpdates(updateManifestUrls);
        }
    }

    private boolean checkPostponed() {
        return false;
    }

    @Override
    public void onManifestReceived(UpdateManifest manifest, Uri[] downloadUris) {
        // A successful manifest fetch/parse always reaches here (errors go to
        // onCheckError), so record the check time for BOTH branches — otherwise the
        // interval never throttles while an update is pending and we recheck every launch.
        mSettingsManager.setLastCheckedMs(System.currentTimeMillis());

        boolean userInitiated = mIsUserInitiated;
        mIsUserInitiated = false;

        int installedVersion = getInstalledVersionCode();
        List<ReleaseNotes> newReleases = manifest.newerThan(installedVersion);
        ReleaseNotes latest = manifest.latest();
        UpdateInfo info = new UpdateInfo(latest.versionName, latest.versionCode, newReleases,
                manifest.find(installedVersion), manifest.downloadSize, null);

        if (!newReleases.isEmpty()) {
            if (downloadUris != null) {
                mChangeLog = flatten(newReleases);
                mLatestVersionName = latest.versionName;
                mLatestVersionNumber = latest.versionCode;
                mDownloadUris = downloadUris;
                mDownloadSize = manifest.downloadSize;

                // Reuse the already-downloaded apk if it is a complete package for the
                // advertised version. isInProgress() guards against reading the file
                // while a concurrent download is writing to the same path.
                boolean apkReady = latest.versionCode == mSettingsManager.getLatestVersionNumber() &&
                        !mDownloader.isInProgress() &&
                        checkApk(mSettingsManager.getApkPath(), latest.versionCode);

                if (!mDownloadOnCheck) {
                    mListener.onUpdateAvailable(apkReady ? info.withApkPath(mSettingsManager.getApkPath()) : info);
                } else if (apkReady) {
                    mListener.onUpdateFound(latest.versionName, mChangeLog, mSettingsManager.getApkPath());
                } else if (!userInitiated && DownloadManager.isActiveNetworkMetered(mContext)) {
                    // NEWTUBE(update-metered): the automatic check would pull a ~65 MB APK before the
                    // user agreed to anything. Defer it off the metered network; a user-initiated
                    // check still downloads right away.
                    deferMeteredDownload(latest.versionName);
                } else {
                    mDownloader.download(downloadUris, userInitiated);
                }
            }
        } else {
            // No update is needed. Remove old apks.
            FileHelpers.delete(mSettingsManager.getApkPath());

            mListener.onUpToDate(info);
        }
    }

    private static List<String> flatten(List<ReleaseNotes> releases) {
        List<String> lines = new ArrayList<>();

        for (ReleaseNotes release : releases) {
            lines.addAll(release.lines);
        }

        return lines;
    }

    @SuppressWarnings("deprecation")
    private int getInstalledVersionCode() {
        try {
            return mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0).versionCode;
        } catch (NameNotFoundException e) {
            return 0;
        }
    }

    private void deferMeteredDownload(String latestVersionName) {
        long intervalMs = mSettingsManager.getMinIntervalMs();

        if (intervalMs > METERED_RECHECK_MS) {
            // Stale again in METERED_RECHECK_MS rather than in a full interval
            mSettingsManager.setLastCheckedMs(System.currentTimeMillis() - intervalMs + METERED_RECHECK_MS);
        }

        android.util.Log.d("NetPath", "update-download deferred reason=metered version=" + latestVersionName
                + " recheck-in=" + METERED_RECHECK_MS / 60_000 + "min");
        mListener.onUpdateError(new IllegalStateException(DownloadManager.METERED_MESSAGE));
    }

    @Override
    public void onApkDownloaded(String path) {
        if (!checkApk(path, mLatestVersionNumber)) {
            // NEWTUBE(update-flow): used to return silently, leaving whoever asked waiting forever
            FileHelpers.delete(path);
            mListener.onDownloadError(new IllegalStateException("The downloaded file is not NewTube " + mLatestVersionName));
            return;
        }

        mSettingsManager.setApkPath(path);
        mSettingsManager.setLatestVersionName(mLatestVersionName);
        mSettingsManager.setLatestVersionNumber(mLatestVersionNumber);

        Log.d(TAG, "App update received. Apk path: " + path);
        Log.d(TAG, "App update received. Changelog: " + mChangeLog);

        mListener.onUpdateFound(mLatestVersionName, mChangeLog, path);
    }

    @Override
    public void onCheckError(Exception e) {
        // A definitive server answer (404 manifest = nothing published, malformed JSON) is
        // throttled like a completed check — without this an unpublished manifest re-fires the
        // GET on every splash. Connectivity-class failures don't stamp the clock: an offline or
        // flaky launch should retry on the next one. DownloadManager wraps transport exceptions
        // in IllegalStateException with the cause preserved, so walk the chain.
        if (!isConnectivityError(e)) {
            mSettingsManager.setLastCheckedMs(System.currentTimeMillis());
        }
        mIsUserInitiated = false;
        mListener.onUpdateError(e);
    }

    private static boolean isConnectivityError(Throwable e) {
        while (e != null) {
            if (e instanceof java.net.SocketException
                    || e instanceof java.net.UnknownHostException
                    || e instanceof java.io.InterruptedIOException
                    || e instanceof javax.net.ssl.SSLException) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    @Override
    public void onDownloadError(Exception e) {
        mListener.onDownloadError(e);
    }

    @Override
    public void onDownloadProgress(long bytes, long total) {
        mListener.onDownloadProgress(bytes, total > 0 ? total : mDownloadSize);
    }

    @Override
    public void processDownloadUrls(Uri[] downloadUrls) {
        String preferredHost = getPreferredHost();

        if (preferredHost == null) {
            return;
        }

        Arrays.sort(downloadUrls, ((o1, o2) -> {
            boolean firstMatch = o1 != null && Helpers.equals(preferredHost, o1.getHost());
            boolean secondMatch = o2 != null && Helpers.equals(preferredHost, o2.getHost());

            return firstMatch == secondMatch ? 0 : firstMatch ? -1 : 1;
        }));
    }

    public void installUpdate() {
        Helpers.installPackage(mContext, mSettingsManager.getApkPath());
    }

    public boolean isUpdateCheckEnabled() {
        return mSettingsManager.getMinIntervalMs() > 0;
    }

    public void setUpdateCheckEnabled(boolean enable) {
        mSettingsManager.setMinIntervalMs(enable ? SettingsManager.CHECK_INTERVAL_DEFAULT_MS : -1);
    }

    public String getPreferredHost() {
        return mSettingsManager.getPreferredHost();
    }

    public void setPreferredHost(String host) {
        mSettingsManager.setPreferredHost(host);
    }

    /**
     * Verifies the cached apk is a complete, parseable package for this app at the
     * expected version. A truncated/partial download makes getPackageArchiveInfo()
     * return null, so a non-null result for our package at the advertised versionCode
     * proves the apk is fully downloaded and current — no time-based freshness
     * heuristic is needed (which used to force a full re-download every 15 minutes).
     */
    @SuppressWarnings("deprecation")
    private boolean checkApk(String path, int expectedVersionNumber) {
        if (path == null) {
            return false;
        }

        PackageInfo archInfo = mContext.getPackageManager().getPackageArchiveInfo(path, 0);
        return archInfo != null
                && mContext.getPackageName().equals(archInfo.packageName)
                && archInfo.versionCode == expectedVersionNumber;
    }
}
