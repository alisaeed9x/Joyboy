package com.liskovsoft.appupdatechecker2.core;

import com.liskovsoft.appupdatechecker2.ReleaseNotes;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * NEWTUBE(update-flow): the update manifest, parsed without touching Android APIs (so it can be
 * unit-tested). Format - see {@link AppVersionChecker}: a "package" object with the download links
 * ("downloadUrlList_&lt;abi&gt;", "downloadUrlList" or "downloadUrl") and, optionally, the size in
 * bytes of the file each list points to ("downloadSize_&lt;abi&gt;", "downloadSize"); then one
 * object per version name with its "versionCode" and "changelog" / "changelog_&lt;language&gt;".
 * Every top-level key other than "package" is read as a version, so new fields go inside "package".
 */
public final class UpdateManifest {
    private static final String PACKAGE = "package";
    public final JSONObject packageInfo;
    /** Every listed version, newest first. Never empty. */
    public final List<ReleaseNotes> releases;
    public final List<String> downloadUrls;
    /** Bytes of the file {@link #downloadUrls} point to, or -1 when the manifest doesn't say. */
    public final long downloadSize;

    private UpdateManifest(JSONObject packageInfo, List<ReleaseNotes> releases, List<String> downloadUrls, long downloadSize) {
        this.packageInfo = packageInfo;
        this.releases = Collections.unmodifiableList(releases);
        this.downloadUrls = Collections.unmodifiableList(downloadUrls);
        this.downloadSize = downloadSize;
    }

    /**
     * @param abi the device's primary ABI (its own download list wins over the universal one)
     * @param language changelog language code; English when the manifest has no list in it
     */
    public static UpdateManifest parse(JSONObject jo, String abi, String language) throws JSONException {
        JSONObject packageInfo = jo.getJSONObject(PACKAGE);
        List<ReleaseNotes> releases = new ArrayList<>();

        for (Iterator<String> keys = jo.keys(); keys.hasNext(); ) {
            String versionName = keys.next();
            if (PACKAGE.equals(versionName)) {
                continue;
            }

            JSONObject versionInfo = jo.getJSONObject(versionName);
            JSONArray changelog = versionInfo.optJSONArray("changelog_" + language);
            if (changelog == null) {
                changelog = versionInfo.optJSONArray("changelog");
            }

            releases.add(new ReleaseNotes(versionName, versionInfo.getInt("versionCode"), toStrings(changelog)));
        }

        if (releases.isEmpty()) {
            throw new JSONException("No versions in the update manifest");
        }

        Collections.sort(releases, (first, second) -> Integer.compare(second.versionCode, first.versionCode));

        String abiList = "downloadUrlList_" + abi;
        List<String> downloadUrls;
        long downloadSize;

        if (packageInfo.has(abiList)) {
            downloadUrls = toStrings(packageInfo.getJSONArray(abiList));
            downloadSize = packageInfo.optLong("downloadSize_" + abi, -1);
        } else if (packageInfo.has("downloadUrlList")) {
            downloadUrls = toStrings(packageInfo.getJSONArray("downloadUrlList"));
            downloadSize = packageInfo.optLong("downloadSize", -1);
        } else {
            downloadUrls = Collections.singletonList(packageInfo.getString("downloadUrl"));
            downloadSize = packageInfo.optLong("downloadSize", -1);
        }

        return new UpdateManifest(packageInfo, releases, downloadUrls, downloadSize > 0 ? downloadSize : -1);
    }

    public ReleaseNotes latest() {
        return releases.get(0);
    }

    /** The versions newer than {@code versionCode}, newest first. */
    public List<ReleaseNotes> newerThan(int versionCode) {
        List<ReleaseNotes> result = new ArrayList<>();

        for (ReleaseNotes release : releases) {
            if (release.versionCode > versionCode) {
                result.add(release);
            }
        }

        return result;
    }

    /** The entry for {@code versionCode}, or null when the manifest no longer lists it. */
    public ReleaseNotes find(int versionCode) {
        for (ReleaseNotes release : releases) {
            if (release.versionCode == versionCode) {
                return release;
            }
        }

        return null;
    }

    private static List<String> toStrings(JSONArray array) throws JSONException {
        List<String> result = new ArrayList<>();

        if (array == null) {
            return result;
        }

        for (int i = 0; i < array.length(); i++) {
            result.add(array.getString(i));
        }

        return result;
    }
}
