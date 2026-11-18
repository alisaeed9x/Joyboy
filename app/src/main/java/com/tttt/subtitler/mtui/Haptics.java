package com.tttt.subtitler.mtui;

import android.content.Context;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * JOYBOY(v1112): minimal copy of MeeTube's Haptics (only what the mtui glass classes call).
 * Fix for the v1111 build: GlassSlider / GlassSwitch were copied to mtui in Y6a but their Haptics wasn't.
 */
public final class Haptics {
    private Haptics() {
    }

    private static boolean isOn(Context c) {
        try {
            return c.getSharedPreferences("meetube_app", Context.MODE_PRIVATE).getBoolean("haptics", true);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** A boundary crossed while dragging (slider step, switch flip). */
    public static void tick(@Nullable View view) {
        try {
            if (view != null && isOn(view.getContext())) {
                view.performHapticFeedback(Build.VERSION.SDK_INT >= 34
                        ? HapticFeedbackConstants.SEGMENT_TICK : HapticFeedbackConstants.CONTEXT_CLICK);
            }
        } catch (RuntimeException ignored) {
        }
    }
}
