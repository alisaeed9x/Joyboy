package com.newtube.mobile.ui.common;

import android.content.Context;

/**
 * MEETUBE(home bar): Settings > Customization can hide Home's Cast button (top bar) and its topic
 * chips. Both on by default.
 */
public final class HomeBar {
    private static final String PREFS = "meetube_app";
    private static final String KEY_CAST = "home_cast_button";
    private static final String KEY_CHIPS = "home_chips";

    private HomeBar() {
    }

    public static boolean castShown(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CAST, true);
    }

    public static void setCastShown(Context context, boolean shown) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CAST, shown).apply();
    }

    public static boolean chipsShown(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CHIPS, true);
    }

    public static void setChipsShown(Context context, boolean shown) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CHIPS, shown).apply();
    }
}
