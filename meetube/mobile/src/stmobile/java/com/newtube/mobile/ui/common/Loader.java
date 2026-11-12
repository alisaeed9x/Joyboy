package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;

import androidx.annotation.DrawableRes;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * MEETUBE(loader): the loading spinner picked in Settings > Customization. Every spinner reads the
 * theme's {@code meetubeLoader} (Widget.MeeTube.GlassSpinner, Widget.MeeTube.Loader), so a pick is
 * a theme overlay applied to each screen as it is created ({@link MobileActivity}); screens open
 * at the time are rebuilt on their next resume, the player keeps its spinner until it reopens.
 */
public final class Loader {
    public static final String NEON = "neon";
    public static final String FROSTED = "frosted";
    public static final String COOKIE = "cookie";
    public static final String FROSTED_COOKIE = "frosted_cookie";
    // The liquid glass pack.
    public static final String LIQUID_RING = "liquid_ring";
    public static final String GLASS_ORB = "glass_orb";
    public static final String MORPHING_BLOB = "morphing_blob";
    public static final String GLASS_ORBIT = "glass_orbit";
    private static final String PREFS = "meetube_loader";
    private static final String KEY = "loader";

    private Loader() {
    }

    public static String current(Context context) {
        return prefs(context).getString(KEY, NEON);
    }

    public static void set(Context context, String loader) {
        prefs(context).edit().putString(KEY, loader).apply();
    }

    /** The loader's drawable, for the picker's tiles. */
    @DrawableRes
    public static int drawable(String loader) {
        return FROSTED.equals(loader) ? R.drawable.avd_frosted_ring
                : COOKIE.equals(loader) ? R.drawable.meetube_loader
                : FROSTED_COOKIE.equals(loader) ? R.drawable.avd_frosted_cookie
                : LIQUID_RING.equals(loader) ? R.drawable.avd_liquid_ring
                : GLASS_ORB.equals(loader) ? R.drawable.avd_glass_orb
                : MORPHING_BLOB.equals(loader) ? R.drawable.avd_morphing_blob
                : GLASS_ORBIT.equals(loader) ? R.drawable.avd_glass_orbit : R.drawable.avd_neon_ring;
    }

    /** Puts the picked loader into {@code theme}; the neon ring is the base theme's own. */
    static void applyTo(Context context, Resources.Theme theme) {
        String loader = current(context);
        int overlay = FROSTED.equals(loader) ? R.style.ThemeOverlay_MeeTube_Loader_Frosted
                : COOKIE.equals(loader) ? R.style.ThemeOverlay_MeeTube_Loader_Cookie
                : FROSTED_COOKIE.equals(loader) ? R.style.ThemeOverlay_MeeTube_Loader_FrostedCookie
                : LIQUID_RING.equals(loader) ? R.style.ThemeOverlay_MeeTube_Loader_LiquidRing
                : GLASS_ORB.equals(loader) ? R.style.ThemeOverlay_MeeTube_Loader_GlassOrb
                : MORPHING_BLOB.equals(loader) ? R.style.ThemeOverlay_MeeTube_Loader_MorphingBlob
                : GLASS_ORBIT.equals(loader) ? R.style.ThemeOverlay_MeeTube_Loader_GlassOrbit : 0;
        if (overlay != 0) {
            theme.applyStyle(overlay, true);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
