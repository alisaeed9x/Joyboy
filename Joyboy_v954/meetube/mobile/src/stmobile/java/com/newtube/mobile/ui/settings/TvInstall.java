package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.liskovsoft.smartyoutubetv2.tv.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import dadb.AdbAuthException;
import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;
import dadb.InstallResult;

// MEETUBE(tv install): a phone guide and background ADB installer, with no desktop needed.
@RequiresApi(26)
final class TvInstall {
    private static final String TAG = "TvInstall";
    private static final String LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK";
    private static final String RELEASES = "https://api.github.com/repos/yashoncode/meetube/releases?per_page=30";
    private static final AtomicBoolean INSTALLING = new AtomicBoolean();
    private final Context mContext;
    private final View mRoot;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final EditText mIp;
    private final View mSearch;
    private final View mInstall;
    private final TextView mSearchStatus;
    private final TextView mStatus;
    private final ProgressBar mProgress;
    private final ChipGroup mFound;
    private ActivityResultLauncher<String> mPermission;
    private Runnable mPending;
    private Thread mScanThread;

    static SettingsPages.Page page(Context context) {
        String title = context.getString(R.string.mobile_settings_tv_title);
        List<SettingsRow> rows = Collections.singletonList(SettingsRow.custom(title, TvInstall::guide));
        return new SettingsPages.Page(title, rows);
    }

    static View guide(ViewGroup parent, SettingsAdapter.Listener page) {
        if (parent.getChildCount() == 1 && parent.getChildAt(0).getTag() instanceof TvInstall) {
            return parent.getChildAt(0);
        }
        View root = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_mobile_tv_install, parent, false);
        root.setTag(new TvInstall(root, page));
        return root;
    }

    private TvInstall(View root, SettingsAdapter.Listener page) {
        mRoot = root;
        mContext = root.getContext().getApplicationContext();
        mIp = root.findViewById(R.id.tv_ip);
        mSearch = root.findViewById(R.id.tv_search);
        mInstall = root.findViewById(R.id.tv_install);
        mSearchStatus = root.findViewById(R.id.tv_search_status);
        mStatus = root.findViewById(R.id.tv_install_status);
        mProgress = root.findViewById(R.id.tv_install_progress);
        mFound = root.findViewById(R.id.tv_found);
        ChipGroup type = root.findViewById(R.id.tv_type);
        Chip android = chip(type, mContext.getString(R.string.mobile_settings_tv_android));
        Chip fire = chip(type, mContext.getString(R.string.mobile_settings_tv_fire));
        type.check(android.getId());
        type.setOnCheckedStateChangeListener((group, checked) -> {
            boolean isFire = checked.contains(fire.getId());
            ((TextView) root.findViewById(R.id.tv_developer_text)).setText(isFire
                    ? R.string.mobile_settings_tv_developer_fire : R.string.mobile_settings_tv_developer_android);
            ((TextView) root.findViewById(R.id.tv_debugging_text)).setText(isFire
                    ? R.string.mobile_settings_tv_debugging_fire : R.string.mobile_settings_tv_debugging_android);
        });
        mSearch.setOnClickListener(v -> withNetworkPermission(this::scan));
        mInstall.setOnClickListener(v -> withNetworkPermission(this::startInstall));
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                if (page instanceof SettingsPageFragment) {
                    ActivityResultRegistry registry = ((SettingsPageFragment) page).requireActivity().getActivityResultRegistry();
                    mPermission = registry.register("tv_install_" + View.generateViewId(),
                            new ActivityResultContracts.RequestPermission(), allowed -> {
                                Runnable action = mPending;
                                mPending = null;
                                ui(() -> {
                                    if (allowed && action != null) action.run();
                                    else showStatus(mContext.getString(R.string.mobile_settings_tv_permission), 0);
                                });
                            });
                }
            }

            @Override public void onViewDetachedFromWindow(View view) {
                if (mScanThread != null) mScanThread.interrupt();
                if (mPermission != null) mPermission.unregister();
                mPermission = null;
                mPending = null;
            }
        });
    }

    private void withNetworkPermission(Runnable action) {
        if (Build.VERSION.SDK_INT >= 36
                && ContextCompat.checkSelfPermission(mContext, LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            mPending = action;
            if (mPermission != null) mPermission.launch(LOCAL_NETWORK);
            else showStatus(mContext.getString(R.string.mobile_settings_tv_permission), 0);
            return;
        }
        action.run();
    }

    private static Chip chip(ChipGroup group, String label) {
        Chip chip = (Chip) LayoutInflater.from(group.getContext()).inflate(R.layout.mobile_search_filter_chip, group, false);
        chip.setId(View.generateViewId());
        chip.setText(label);
        group.addView(chip);
        return chip;
    }

    private void scan() {
        mSearch.setEnabled(false);
        mFound.removeAllViews();
        mSearchStatus.setVisibility(View.VISIBLE);
        mSearchStatus.setText(R.string.mobile_settings_tv_searching);
        mScanThread = new Thread(() -> {
            ExecutorService pool = Executors.newFixedThreadPool(48);
            try {
                String own = phoneAddress();
                if (own == null) return;
                String prefix = own.substring(0, own.lastIndexOf('.') + 1);
                CompletionService<String> probes = new ExecutorCompletionService<>(pool);
                int count = 0;
                for (int host = 1; host < 255; host++) {
                    String ip = prefix + host;
                    if (ip.equals(own)) continue;
                    probes.submit(() -> {
                        if (Thread.currentThread().isInterrupted()) return null;
                        try (Socket socket = new Socket()) {
                            socket.connect(new InetSocketAddress(ip, 5555), 500);
                            return ip;
                        } catch (IOException e) {
                            return null;
                        }
                    });
                    count++;
                }
                for (int i = 0; i < count; i++) {
                    String ip = probes.take().get();
                    if (ip != null) ui(() -> chip(mFound, ip).setOnClickListener(v -> {
                        mIp.setText(ip);
                        mIp.setSelection(ip.length());
                        mIp.setError(null);
                    }));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.w(TAG, "TV discovery failed", e);
            } finally {
                pool.shutdownNow();
                ui(() -> {
                    mSearch.setEnabled(true);
                    mSearchStatus.setText(mFound.getChildCount() == 0
                            ? mContext.getString(R.string.mobile_settings_tv_none_found)
                            : mContext.getString(R.string.mobile_settings_tv_found, String.valueOf(mFound.getChildCount())));
                });
            }
        }, "meetube-tv-scan");
        mScanThread.start();
    }

    private String phoneAddress() {
        ConnectivityManager manager = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = manager != null ? manager.getActiveNetwork() : null;
        NetworkCapabilities capabilities = network != null ? manager.getNetworkCapabilities(network) : null;
        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null;
        LinkProperties links = network != null ? manager.getLinkProperties(network) : null;
        if (links != null) {
            for (LinkAddress link : links.getLinkAddresses()) {
                if (link.getAddress() instanceof Inet4Address && !link.getAddress().isLoopbackAddress()) {
                    return link.getAddress().getHostAddress();
                }
            }
        }
        return null;
    }

    private void startInstall() {
        String ip = mIp.getText().toString().trim();
        if (!ipv4(ip)) {
            mIp.setError(mContext.getString(R.string.mobile_settings_tv_ip_invalid,
                    mContext.getString(R.string.mobile_settings_tv_ip_hint)));
            mIp.requestFocus();
            return;
        }
        if (!INSTALLING.compareAndSet(false, true)) {
            showStatus(mContext.getString(R.string.mobile_settings_tv_busy), -1);
            return;
        }
        mInstall.setEnabled(false);
        showStatus(mContext.getString(R.string.mobile_settings_tv_connecting, ip), -1);
        new Thread(() -> install(ip), "meetube-tv-install").start();
    }

    private void install(String ip) {
        File apk = new File(mContext.getCacheDir(), "meetube_tv.apk");
        int phase = 0;
        try {
            File privateKey = new File(mContext.getFilesDir(), "adb/adbkey");
            File publicKey = new File(mContext.getFilesDir(), "adb/adbkey.pub");
            if (!privateKey.isFile() || !publicKey.isFile()) AdbKeyPair.generate(privateKey, publicKey);
            AdbKeyPair keyPair = AdbKeyPair.read(privateKey, publicKey);
            try (Dadb dadb = Dadb.create(ip, 5555, keyPair, 5000, 60000, false)) {
                // Dadb connects lazily: the first shell waits for the TV to authorize this key.
                AdbShellResponse probe = dadb.shell("getprop ro.product.cpu.abi");
                if (probe.getExitCode() != 0) throw new IOException(probe.getOutput());
                phase = 1;
                status(R.string.mobile_settings_tv_finding_release, -1);
                JSONObject release = release();
                JSONObject asset = asset(release.getJSONArray("assets"), probe.getOutput());
                String version = release.getString("tag_name").substring(4);
                download(asset, version, apk);
                phase = 2;
                status(R.string.mobile_settings_tv_installing, -1);
                InstallResult result = dadb.install(apk);
                // 2.0.0 returns package-manager refusals, while transport failures throw.
                if (result instanceof InstallResult.Failure) {
                    throw new IOException(((InstallResult.Failure) result).getReason());
                }
                phase = 3;
                AdbShellResponse launch = dadb.shell("monkey -p io.yash.meetube.tv -c android.intent.category.LAUNCHER 1");
                if (launch.getExitCode() != 0) throw new IOException(launch.getOutput());
                status(R.string.mobile_settings_tv_done, 100);
            }
        } catch (Exception e) {
            Log.e(TAG, "TV install failed in phase " + phase, e);
            if (phase == 0) {
                status(authFailure(e) ? R.string.mobile_settings_tv_auth_failed : R.string.mobile_settings_tv_unreachable, 0);
            } else if (phase == 1) {
                status(R.string.mobile_settings_tv_download_failed, 0);
            } else if (phase == 3) {
                status(R.string.mobile_settings_tv_launch_failed, 100);
            } else {
                String message = e.getMessage();
                if (message == null || message.isEmpty()) message = mContext.getString(R.string.mobile_settings_tv_unknown_error);
                String text = mContext.getString(R.string.mobile_settings_tv_install_failed, message);
                ui(() -> showStatus(text, 0));
            }
        } finally {
            if (apk.exists() && !apk.delete()) Log.w(TAG, "Could not remove cached TV APK");
            INSTALLING.set(false);
            ui(() -> mInstall.setEnabled(true));
        }
    }

    private JSONObject release() throws Exception {
        HttpURLConnection connection = connection(RELEASES);
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) bytes.write(buffer, 0, read);
            JSONArray releases = new JSONArray(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.getJSONObject(i);
                if (!release.optBoolean("draft") && release.optString("tag_name").startsWith("tv-v")) return release;
            }
            throw new IOException("No TV release found");
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject asset(JSONArray assets, String abi) throws Exception {
        String suffix = abi.contains("arm64") ? "_arm64-v8a.apk"
                : abi.contains("armeabi") ? "_armeabi-v7a.apk" : "_universal.apk";
        JSONObject universal = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.optString("name");
            if (name.endsWith(suffix)) return asset;
            if (name.endsWith("_universal.apk")) universal = asset;
        }
        if (universal != null) return universal;
        throw new IOException("No compatible TV APK found");
    }

    private void download(JSONObject asset, String version, File apk) throws Exception {
        HttpURLConnection connection = connection(asset.getString("browser_download_url"));
        try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(apk)) {
            long size = connection.getContentLengthLong();
            if (size <= 0) size = asset.optLong("size", -1);
            byte[] buffer = new byte[32768];
            long received = 0;
            int read;
            int previous = -2;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                received += read;
                int progress = size > 0 ? (int) Math.min(100, received * 100 / size) : -1;
                if (progress != previous) {
                    String text = size > 0 ? mContext.getString(R.string.mobile_settings_tv_downloading,
                            version, String.valueOf(progress))
                            : mContext.getString(R.string.mobile_settings_tv_downloading_unknown, version);
                    ui(() -> showStatus(text, progress));
                    previous = progress;
                }
            }
            if (received == 0 || (size > 0 && received != size)) throw new IOException("Incomplete TV APK download");
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection connection(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "MeeTube");
        return connection;
    }

    private static boolean ipv4(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    private static boolean authFailure(Throwable error) {
        boolean handshake = error.getMessage() != null && error.getMessage().contains("handshake");
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof AdbAuthException || (handshake && (cause instanceof SocketTimeoutException
                    || cause instanceof EOFException || cause instanceof SocketException))) return true;
        }
        return false;
    }

    private void status(int text, int progress) {
        ui(() -> showStatus(mContext.getString(text), progress));
    }

    private void showStatus(String text, int progress) {
        mStatus.setVisibility(View.VISIBLE);
        mStatus.setText(text);
        mProgress.setVisibility(View.VISIBLE);
        mProgress.setIndeterminate(progress < 0);
        mProgress.setProgress(Math.max(0, progress));
    }

    private void ui(Runnable action) {
        mMain.post(() -> {
            if (mRoot.isAttachedToWindow()) action.run();
        });
    }
}
