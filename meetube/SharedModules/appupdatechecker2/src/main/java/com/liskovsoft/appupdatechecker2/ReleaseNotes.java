package com.liskovsoft.appupdatechecker2;

import java.util.Collections;
import java.util.List;

/**
 * NEWTUBE(update-flow): one version's entry in the update manifest - its name, code and changelog
 * lines in the user's language (English when the manifest has none for it).
 */
public final class ReleaseNotes {
    public final String versionName;
    public final int versionCode;
    public final List<String> lines;

    public ReleaseNotes(String versionName, int versionCode, List<String> lines) {
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.lines = lines != null ? Collections.unmodifiableList(lines) : Collections.emptyList();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof ReleaseNotes)) {
            return false;
        }

        ReleaseNotes notes = (ReleaseNotes) other;
        return versionCode == notes.versionCode && versionName.equals(notes.versionName) && lines.equals(notes.lines);
    }

    @Override
    public int hashCode() {
        return 31 * versionCode + lines.hashCode();
    }
}
