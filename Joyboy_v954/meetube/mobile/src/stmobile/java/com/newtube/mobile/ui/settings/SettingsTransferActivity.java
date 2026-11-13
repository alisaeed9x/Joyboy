package com.newtube.mobile.ui.settings;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import com.liskovsoft.sharedutils.helpers.FileHelpers;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.AppIcon;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * MEETUBE(settings-transfer): the app's settings as one JSON file - saved for later, shared with a
 * friend, or imported back - and the invisible host for the system file pickers it needs.
 *
 * <p>Only settings travel, by allowlist: never the signed-in accounts, linked devices, watch
 * history, caches, the download folder (a path on this phone) or the settings / master password.
 * An import keeps this phone's passwords and restarts the app from the files it wrote (a normal
 * restart first saves the in-memory settings back over them).
 */
public final class SettingsTransferActivity extends Activity {
    private static final String EXTRA_IMPORT = "import";
    private static final int REQUEST_EXPORT = 1;
    private static final int REQUEST_IMPORT = 2;
    private static final String FORMAT = "meetube-settings";
    /** A settings file is a few KB; anything far bigger is not one. */
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    /** The settings data files under files/app_prefs (the profile's own copy when it has one). */
    private static final String[] DATA = {"general_data", "main_ui_data2", "video_player_data",
            "video_player_tweaks_data", "search_data", "content_block_data", "sidebar_data",
            "channel_group_data", "blocked_channel_data"};
    /** The phone's own settings (SharedPreferences files). */
    private static final String[] PREFS = {"meetube_app", "meetube_ui", "meetube_background",
            "meetube_performance", "meetube_player", "meetube_player_ui", "meetube_nav_tabs", "meetube_loader",
            "meetube_shorts", "mini_pip", "newtube_theme", "newtube_playback_sources"};
    /** general_data's settings password and master password (GeneralData.restoreData). */
    private static final int[] PASSWORDS = {33, 41};

    static void export(Context context) {
        context.startActivity(new Intent(context, SettingsTransferActivity.class));
    }

    static void importFile(Context context) {
        context.startActivity(new Intent(context, SettingsTransferActivity.class).putExtra(EXTRA_IMPORT, true));
    }

    /** Hands the settings file to the share sheet (a chat, mail, Drive). */
    static void share(Context context) {
        try {
            File file = new File(context.getCacheDir(), fileName());
            write(context, new FileOutputStream(file));
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".update_provider", file);
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.mobile_settings_transfer_share_text))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(send, context.getString(R.string.mobile_settings_transfer_share)));
        } catch (IOException | JSONException | RuntimeException e) {
            com.newtube.mobile.ui.common.MobileSnackbar.show(context, R.string.mobile_settings_transfer_failed);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            return; // the picker is already up
        }
        boolean importing = getIntent().getBooleanExtra(EXTRA_IMPORT, false);
        Intent pick = importing
                ? new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*") // a file from a chat often comes as octet-stream
                : new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json").putExtra(Intent.EXTRA_TITLE, fileName());
        try {
            startActivityForResult(pick, importing ? REQUEST_IMPORT : REQUEST_EXPORT);
        } catch (ActivityNotFoundException e) {
            com.newtube.mobile.ui.common.MobileSnackbar.show(this, R.string.mobile_settings_transfer_failed);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (uri == null) {
            finish();
            return;
        }
        if (requestCode == REQUEST_EXPORT) {
            try {
                write(this, getContentResolver().openOutputStream(uri, "wt"));
                com.newtube.mobile.ui.common.MobileSnackbar.show(this, R.string.mobile_settings_transfer_saved);
            } catch (IOException | JSONException | RuntimeException e) {
                com.newtube.mobile.ui.common.MobileSnackbar.show(this, R.string.mobile_settings_transfer_failed);
            }
            finish();
            return;
        }
        try {
            apply(this, new JSONObject(read(getContentResolver().openInputStream(uri))));
        } catch (IOException | JSONException | RuntimeException e) {
            com.newtube.mobile.ui.common.MobileSnackbar.show(this, R.string.mobile_settings_transfer_not_settings);
            finish();
            return;
        }
        com.newtube.mobile.ui.common.MobileSnackbar.show(this, R.string.mobile_settings_transfer_imported);
        finish();
        // A beat for the icon pick's apply() to reach the disk before the process goes.
        Context app = getApplicationContext();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> Utils.restartTheAppAsStored(app), 600);
    }

    private static String fileName() {
        return "MeeTube_settings_" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".json";
    }

    // ---------------------------------------------------------------------------------------------
    // The file
    // ---------------------------------------------------------------------------------------------

    private static void write(Context context, OutputStream out) throws IOException, JSONException {
        if (out == null) {
            throw new IOException("no output");
        }
        JSONObject root = new JSONObject().put("format", FORMAT).put("version", 1);
        JSONObject data = new JSONObject();
        for (String key : DATA) {
            String value = contents(dataFile(context, key, true));
            if (value == null) {
                value = contents(dataFile(context, key, false));
            }
            if (value != null) {
                data.put(key, "general_data".equals(key) ? withPasswords(value, null) : value);
            }
        }
        root.put("data", data);
        JSONObject prefs = new JSONObject();
        for (String name : PREFS) {
            JSONObject values = new JSONObject();
            for (Map.Entry<String, ?> entry : context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().entrySet()) {
                Object value = entry.getValue();
                String type = value instanceof Boolean ? "b" : value instanceof Integer ? "i" : value instanceof Long ? "l"
                        : value instanceof Float ? "f" : value instanceof String ? "s" : null;
                if (type != null) {
                    values.put(entry.getKey(), new JSONObject().put("t", type).put("v", value));
                }
            }
            if (values.length() > 0) {
                prefs.put(name, values);
            }
        }
        root.put("prefs", prefs);
        root.put("app_icon", AppIcon.current(context));
        try (OutputStream o = out) {
            o.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Writes an imported file's settings; anything not on the allowlists is ignored. */
    private static void apply(Activity activity, JSONObject root) throws JSONException {
        if (!FORMAT.equals(root.optString("format"))) {
            throw new JSONException("not a MeeTube settings file");
        }
        JSONObject data = root.optJSONObject("data");
        if (data != null) {
            for (String key : DATA) {
                String value = data.optString(key, null);
                if (value == null) {
                    continue;
                }
                if ("general_data".equals(key)) {
                    String mine = contents(dataFile(activity, key, true));
                    if (mine == null) {
                        mine = contents(dataFile(activity, key, false));
                    }
                    value = withPasswords(value, mine); // this phone's passwords, not the file's
                }
                // Both copies: the profile's when it has one, and the shared one otherwise read.
                FileHelpers.stringToFile(value, dataFile(activity, key, false));
                File profile = dataFile(activity, key, true);
                if (profile != null) {
                    FileHelpers.stringToFile(value, profile);
                }
            }
        }
        JSONObject prefs = root.optJSONObject("prefs");
        if (prefs != null) {
            for (String name : PREFS) {
                JSONObject values = prefs.optJSONObject(name);
                if (values == null) {
                    continue;
                }
                SharedPreferences.Editor editor = activity.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear();
                for (Iterator<String> keys = values.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    JSONObject entry = values.getJSONObject(key);
                    switch (entry.getString("t")) {
                        case "b": editor.putBoolean(key, entry.getBoolean("v")); break;
                        case "i": editor.putInt(key, entry.getInt("v")); break;
                        case "l": editor.putLong(key, entry.getLong("v")); break;
                        case "f": editor.putFloat(key, (float) entry.getDouble("v")); break;
                        case "s": editor.putString(key, entry.getString("v")); break;
                        default: break;
                    }
                }
                editor.commit(); // on disk before the restart
            }
        }
        String icon = root.optString("app_icon", null);
        if (icon != null && AppIcon.exists(icon)) {
            AppIcon.set(activity, icon); // applied once the restarted app leaves the screen
        }
    }

    /** {@code generalData} with the passwords of {@code source} (none when null). */
    static String withPasswords(String generalData, String source) {
        String[] fields = Helpers.splitData(generalData);
        String[] from = source != null ? Helpers.splitData(source) : null;
        int size = fields.length;
        for (int index : PASSWORDS) {
            size = Math.max(size, index + 1);
        }
        Object[] out = new Object[size];
        System.arraycopy(fields, 0, out, 0, fields.length);
        for (int index : PASSWORDS) {
            out[index] = Helpers.parseStr(from, index);
        }
        return Helpers.mergeData(out);
    }

    /** files/app_prefs/[profile_]key (AppPrefs' layout); null for the profile copy with no profile. */
    private static File dataFile(Context context, String key, boolean profile) {
        File dir = new File(context.getFilesDir(), "app_prefs");
        if (!profile) {
            return new File(dir, key);
        }
        String name = context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE)
                .getString("last_profile_name", null);
        return TextUtils.isEmpty(name) ? null : new File(dir, name + "_" + key);
    }

    private static String contents(File file) {
        return file != null && file.exists() ? FileHelpers.getFileContents(file) : null;
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            throw new IOException("no input");
        }
        try (InputStream i = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int n; (n = i.read(buffer)) != -1; ) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_BYTES) {
                    throw new IOException("too big");
                }
            }
            return out.toString("UTF-8");
        }
    }
}
