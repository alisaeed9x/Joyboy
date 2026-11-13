package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

/**
 * MEETUBE(performance): High performance mode - every screen asks for the display's highest refresh
 * rate (120 Hz where the phone has it) instead of letting Android drop to 60 Hz between touches.
 * A request, not a lock: Android still lowers it when the phone is hot or the battery saver is on.
 * BitChord's way: the window's preferred rate, and from Android 15 no power-saving frame rate and
 * the root view's requested rate.
 */
public final class HighRefresh {
    private static final String PREFS = "meetube_performance";
    private static final String KEY = "high_refresh";

    private HighRefresh() {
    }

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false);
    }

    public static void setEnabled(Context context, boolean on) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply();
    }

    /** The display's highest refresh rate at its current resolution (no mode switch), in Hz. */
    public static int topRate(Context context) {
        Display display = ((DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE))
                .getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) {
            return 60;
        }
        Display.Mode current = display.getMode();
        float top = current.getRefreshRate();
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                    && mode.getPhysicalHeight() == current.getPhysicalHeight()) {
                top = Math.max(top, mode.getRefreshRate());
            }
        }
        return Math.round(top);
    }

    /** Applies the setting to this screen's window; cheap, so every resume calls it. */
    public static void apply(Activity activity) {
        boolean on = isEnabled(activity);
        Window window = activity.getWindow();
        WindowManager.LayoutParams lp = window.getAttributes();
        float rate = on ? topRate(activity) : 0f; // 0 = Android's own choice
        if (lp.preferredRefreshRate != rate) {
            lp.preferredRefreshRate = rate;
            window.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 35) {
            window.setFrameRatePowerSavingsBalanced(!on);
            // the screen's own frame rate vote too (Android 15+ weighs views' votes)
            window.getDecorView().setRequestedFrameRate(on ? rate : android.view.View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT);
        }
    }
}
