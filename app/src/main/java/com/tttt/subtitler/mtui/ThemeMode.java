package com.tttt.subtitler.mtui;

import android.app.Activity;
import android.app.Application;
import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

/**
 * (v1109 Y6b) نسخة app من ThemeMode بتاع MeeTube (بتقرا/تكتب نفس ملف الإعدادات "newtube_theme" فالاختيار القديم محفوظ).
 * اتشال منه: سجل شاشات :mobile (MobileActivity) و MotherActivity.setNightModeSource و NetPath.log و Loader — كلهم من :mobile/:common اللي هيتمسحوا.
 */
public final class ThemeMode {
    public static final int SYSTEM = 0;
    public static final int LIGHT = 1;
    public static final int DARK = 2;

    private static final String PREFS = "newtube_theme";
    private static final String KEY_MODE = "mode";

    private static volatile int sMode = SYSTEM;

    private ThemeMode() {
    }

    public static void init(@NonNull Application app) {
        SharedPreferences prefs = prefs(app);
        if (!prefs.contains(KEY_MODE)) {
            prefs.edit().putInt(KEY_MODE, DARK).apply(); // التصميم الزجاجي داكن أولًا
        }
        sMode = sanitize(prefs.getInt(KEY_MODE, SYSTEM));
        applyProcessWide(app, sMode);
    }

    public static int get() {
        return sMode;
    }

    public static int peek(@NonNull Context context) {
        if (sMode != SYSTEM) {
            return sMode;
        }
        try {
            return sanitize(prefs(context.getApplicationContext()).getInt(KEY_MODE, DARK));
        } catch (RuntimeException e) {
            return DARK;
        }
    }

    public static void set(@NonNull Context context, int mode) {
        mode = sanitize(mode);
        if (mode == sMode) {
            return;
        }
        sMode = mode;
        Context app = context.getApplicationContext();
        prefs(app).edit().putInt(KEY_MODE, mode).apply();
        applyProcessWide(app, mode);
    }

    public static int desiredNight(@NonNull Context context) {
        int forced = forcedNight(sMode);
        if (forced != Configuration.UI_MODE_NIGHT_UNDEFINED) {
            return forced;
        }
        int system = context.getApplicationContext().getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return system == Configuration.UI_MODE_NIGHT_YES ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
    }

    public static int currentNight(@NonNull Context context) {
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
    }

    public static boolean isLight(@NonNull Context context) {
        return currentNight(context) != Configuration.UI_MODE_NIGHT_YES;
    }

    /** يودّي موارد الشاشة وثيمها لجانب night (نفس تقنية AppCompat) — اللي JoyApp بيعمله قبل أي شاشة. */
    @SuppressWarnings("deprecation")
    public static void syncResources(@NonNull Activity activity, int night) {
        Resources res = activity.getResources();
        Configuration current = res.getConfiguration();
        if ((current.uiMode & Configuration.UI_MODE_NIGHT_MASK) == night) {
            return;
        }
        Configuration updated = new Configuration(current);
        updated.uiMode = (updated.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        res.updateConfiguration(updated, null);
        if (Build.VERSION.SDK_INT >= 29) {
            activity.getTheme().rebase();
        } else {
            int themeRes = manifestTheme(activity);
            if (themeRes != 0) {
                activity.getTheme().applyStyle(themeRes, true);
            }
        }
    }

    static int forcedNight(int mode) {
        switch (mode) {
            case LIGHT:
                return Configuration.UI_MODE_NIGHT_NO;
            case DARK:
                return Configuration.UI_MODE_NIGHT_YES;
            default:
                return Configuration.UI_MODE_NIGHT_UNDEFINED;
        }
    }

    static int appCompatMode(int mode) {
        switch (mode) {
            case LIGHT:
                return AppCompatDelegate.MODE_NIGHT_NO;
            case DARK:
                return AppCompatDelegate.MODE_NIGHT_YES;
            default:
                return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
    }

    private static void applyProcessWide(Context app, int mode) {
        AppCompatDelegate.setDefaultNightMode(appCompatMode(mode));
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                UiModeManager uiModeManager = app.getSystemService(UiModeManager.class);
                if (uiModeManager != null) {
                    uiModeManager.setApplicationNightMode(mode == LIGHT ? UiModeManager.MODE_NIGHT_NO
                            : mode == DARK ? UiModeManager.MODE_NIGHT_YES : UiModeManager.MODE_NIGHT_AUTO);
                }
            } catch (RuntimeException e) {
                // الـsplash بس بيعتمد عليها؛ الشاشات ليها override خاص
            }
        }
    }

    private static int manifestTheme(Activity activity) {
        try {
            ActivityInfo info = activity.getPackageManager().getActivityInfo(activity.getComponentName(), 0);
            return info.getThemeResource();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private static int sanitize(int mode) {
        return mode == LIGHT || mode == DARK ? mode : SYSTEM;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
