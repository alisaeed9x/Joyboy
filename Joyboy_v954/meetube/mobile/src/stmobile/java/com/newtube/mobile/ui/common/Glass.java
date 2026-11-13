package com.newtube.mobile.ui.common;

import android.view.View;

/** MEETUBE(glass): wires a {@link GlassView} to the {@link GlassSource} it frosts. */
public final class Glass {
    private static final String PREFS = "meetube_app";
    private static final String KEY_LIQUID = "liquid_glass";
    private static final String KEY_PLAYER_FROST = "player_frost"; // before Player buttons: read once, as its default
    private static final String KEY_PLAYER_BUTTONS = "player_buttons";

    private Glass() {
    }

    /** The Liquid glass setting: off, every glass is a plain frosted blur (no lens, light or grain). */
    public static boolean isLiquid(android.content.Context context) {
        return context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY_LIQUID, true);
    }

    public static void setLiquid(android.content.Context context, boolean on) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY_LIQUID, on).apply();
    }

    /** The Player buttons setting's three looks for the controls over a video (and a short's). */
    public static final int BUTTONS_SEE_THROUGH = 0;
    /** Every disc re-blurs the video under it each frame. */
    public static final int BUTTONS_FROSTED = 1;
    /** MEETUBE(minimal buttons): no discs, the bare icons with a soft shadow, like YouTube. */
    public static final int BUTTONS_MINIMAL = 2;

    /** See-through tinted discs by default; the old Frosted player buttons switch carries over. */
    public static int playerButtons(android.content.Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
        return prefs.getInt(KEY_PLAYER_BUTTONS,
                prefs.getBoolean(KEY_PLAYER_FROST, false) ? BUTTONS_FROSTED : BUTTONS_SEE_THROUGH);
    }

    public static void setPlayerButtons(android.content.Context context, int style) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putInt(KEY_PLAYER_BUTTONS, style).apply();
    }

    /** MEETUBE(glass segmented): Home's topic chips and the search filters, as separate pills or one glass segment. */
    public static final int CHIPS_PILLS = 0;
    public static final int CHIPS_SEGMENT = 1;

    public static int chipStyle(android.content.Context context) {
        return context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getInt("chip_style", CHIPS_SEGMENT);
    }

    public static void setChipStyle(android.content.Context context, int style) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putInt("chip_style", style).apply();
    }

    public static boolean isPlayerFrost(android.content.Context context) {
        return playerButtons(context) == BUTTONS_FROSTED;
    }

    public static void blur(View glass, View source) {
        if (glass instanceof GlassView && source instanceof GlassSource) {
            ((GlassView) glass).setSource((GlassSource) source);
        }
    }
}
