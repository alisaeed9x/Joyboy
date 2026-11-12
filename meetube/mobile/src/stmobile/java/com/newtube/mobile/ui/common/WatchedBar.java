package com.newtube.mobile.ui.common;

import android.content.Context;

/**
 * MEETUBE(issue #132): whether video cards draw the red watched line under the thumbnail
 * (VideoCardAdapter, RelatedVideoAdapter). YouTube sends the watched share with every card even
 * while watch history is paused, so hiding it is the app's call. On by default.
 */
public final class WatchedBar {
    private static final String PREFS = "meetube_app";
    private static final String KEY_SHOWN = "watched_bar";

    private WatchedBar() {
    }

    public static boolean isShown(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOWN, true);
    }

    public static void setShown(Context context, boolean shown) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOWN, shown).apply();
    }
}
