package com.liskovsoft.sharedutils.locale;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build.VERSION;
import android.os.LocaleList;
import android.util.DisplayMetrics;

import java.util.Locale;

/**
 * <a href="https://stackoverflow.com/questions/4985805/set-locale-programmatically">More info</a>
 */
public class LocaleContextWrapper extends ContextWrapper {
    public LocaleContextWrapper(Context base) {
        super(base);
    }

    public static Context wrap(Context context, Locale newLocale) {
        return wrap(context, newLocale, null);
    }
    
    public static Context wrap(Context context, Locale newLocale, DisplayMetrics customMetrics) {
        if (newLocale == null) {
            return context;
        }

        Resources res = context.getResources();

        if (res == null) {
            return context;
        }

        if (VERSION.SDK_INT >= 17) {
            // NEWTUBE(issue #17): an override of the locale only. createConfigurationContext keeps
            // every defined field of its argument for the context's whole life, so the full copy of
            // the current configuration this used to pass froze the orientation and screen size the
            // activity was created with: a player that rotated in place (configChanges) still read
            // portrait, padded and sized its landscape video like the portrait box. An empty
            // Configuration leaves the rest UNDEFINED, which follows the system. fontScale is
            // cleared because Android 7's constructor sets it to 1.
            Configuration override = new Configuration();
            override.fontScale = 0;

            if (customMetrics != null) {
                override.densityDpi = (int) (customMetrics.density * 160); // 160 is the baseline DPI
            }

            if (VERSION.SDK_INT >= 24) {
                LocaleList localeList = new LocaleList(newLocale);
                LocaleList.setDefault(localeList);
                override.setLocales(localeList);
            } else {
                override.setLocale(newLocale);
            }

            context = context.createConfigurationContext(override);
        } else {
            Configuration configuration = res.getConfiguration();

            if (customMetrics != null) {
                configuration.densityDpi = (int) (customMetrics.density * 160); // 160 is the baseline DPI
            }

            configuration.locale = newLocale;
            res.updateConfiguration(configuration, res.getDisplayMetrics());
        }

        return new ContextWrapper(context);
    }

    public static void applySavedLocale(Context context, Locale newLocale, DisplayMetrics customMetrics) {
        if (context == null) {
            return;
        }

        if (newLocale == null) {
            return;
        }

        Resources res = context.getResources();

        if (res == null) {
            return;
        }

        Configuration configuration = res.getConfiguration();

        if (customMetrics != null) {
            configuration.densityDpi = (int) (customMetrics.density * 160); // 160 is the baseline DPI
        }

        configuration.locale = newLocale;
        res.updateConfiguration(configuration, res.getDisplayMetrics());
    }
}
