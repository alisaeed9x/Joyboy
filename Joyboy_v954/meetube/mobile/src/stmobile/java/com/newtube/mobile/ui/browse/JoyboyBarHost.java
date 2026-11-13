package com.newtube.mobile.ui.browse;

import android.app.Activity;
import android.view.ViewGroup;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * JOYBOY(v884): bridge for Joyboy's own bottom bar inside MeeTube's browse screen.
 * This module (:mobile) cannot depend on the app module, so Joyboy registers a
 * {@link Provider} from its side (JoyApp / MeeTubeLink) and this host only finds the
 * {@code joyboy_bottom_bar} container in the layout and hands it over. No Joyboy
 * class is referenced here, so the module boundary stays clean.
 */
public final class JoyboyBarHost {
    /** Implemented on the Joyboy side (MeeTubeBar): builds the bar into the container. */
    public interface Provider {
        boolean attach(Activity activity, ViewGroup container);
    }

    private static volatile Provider sProvider;

    private JoyboyBarHost() {
    }

    public static void setProvider(Provider provider) {
        sProvider = provider;
    }

    /** Called from MobileBrowseActivity once its views are bound. False = no bar (Joyboy side not registered or no container). */
    public static boolean attach(Activity activity) {
        try {
            Provider provider = sProvider;
            if (provider == null || activity == null) {
                return false;
            }
            ViewGroup container = activity.findViewById(R.id.joyboy_bottom_bar);
            if (container == null) {
                return false;
            }
            return provider.attach(activity, container);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
