package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * MEETUBE(watch-look): Settings > Customization - the watch page compact (default) or classic, and
 * which player buttons show (PlayerButton reads them).
 */
public final class WatchLook {
    private static final String PREFS = "meetube_ui";
    private static final String KEY_CLASSIC = "watch_classic";
    private static final String KEY_HIDDEN = "player_button_hidden_";

    private WatchLook() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isClassic(Context context) {
        return prefs(context).getBoolean(KEY_CLASSIC, false);
    }

    public static void setClassic(Context context, boolean classic) {
        prefs(context).edit().putBoolean(KEY_CLASSIC, classic).apply();
    }

    /** {@code button} is the layout id's name ("mobile_player_cast"). */
    public static boolean isShown(Context context, String button) {
        if (isRemoved(button)) {
            return false;
        }
        return !prefs(context).getBoolean(KEY_HIDDEN + button, false);
    }

    // MEETUBE(fullscreen-actions): like, dislike and download were removed from the
    // fullscreen pill - they sat hidden behind Joyboy's bar - so they never show again,
    // whatever the stored preference says.
    private static boolean isRemoved(String button) {
        if ("mobile_player_like".equals(button)) {
            return true;
        }
        if ("mobile_player_dislike".equals(button)) {
            return true;
        }
        return "mobile_player_download".equals(button);
    }

    public static void setShown(Context context, String button, boolean shown) {
        prefs(context).edit().putBoolean(KEY_HIDDEN + button, !shown).apply();
    }
}
