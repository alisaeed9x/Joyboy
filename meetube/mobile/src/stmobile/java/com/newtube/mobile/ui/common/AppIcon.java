package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * MEETUBE(app icon): the launcher icon picked in Settings > Customization. Each icon is an
 * {@code activity-alias} of SplashActivity (stmobile AndroidManifest, com.newtube.mobile.icon.*) and
 * exactly one is enabled. The in-app marks (Home's brand pill, the update sheet) follow the pick too;
 * the launch animation is the same for every icon (LaunchSplash). Only icons built into the APK can
 * be a launcher icon, so there is no upload-your-own (tried: it could only ever be a shortcut beside the real icon).
 *
 * <p>Disabling an alias makes Android close the task it started, and the system then refuses to
 * reopen the app from the background. So a pick is remembered here and applied either by the
 * Restart the pick offers ({@link #restart}: the app reopens through the new alias first, then the
 * old one is disabled) or once the app leaves the screen ({@link #applyPending}, from
 * onTrimMemory(UI_HIDDEN)).
 */
public final class AppIcon {
    /**
     * Alias names; the first is the default. The aliases keep their first names so a pick survives
     * updates; Settings shows the icons' own names (tools/icons_from_pngs.py has the mapping).
     */
    public static final String DEFAULT = "Default";
    public static final String CLASSIC = "Classic";
    public static final String FANTASY = "Fantasy";
    public static final String GLASS_HEART = "GlassHeart";
    public static final String RUBY = "Ruby";
    public static final String PLAY = "Play";
    public static final String PLAY_DARK = "PlayDark";
    public static final String BLAZE = "Blaze";
    private static final String[] ICONS = {DEFAULT, CLASSIC, FANTASY, GLASS_HEART, RUBY, PLAY, PLAY_DARK, BLAZE};
    // Per icon, in ICONS' order: launcher icon, brand-pill mark.
    private static final int[] LAUNCHERS = {R.mipmap.app_icon, R.mipmap.app_icon_classic, R.mipmap.app_icon_fantasy,
            R.mipmap.app_icon_glass_heart, R.mipmap.app_icon_ruby, R.mipmap.app_icon_play,
            R.mipmap.app_icon_play_dark, R.mipmap.app_icon_blaze};
    private static final int[] MARKS = {R.drawable.ic_meetube_mark_default, R.drawable.ic_meetube_mark_classic,
            R.drawable.ic_meetube_mark_art, R.drawable.ic_meetube_mark_glass_heart, R.drawable.ic_meetube_mark_ruby,
            R.drawable.ic_meetube_mark_play, R.drawable.ic_meetube_mark_play_dark, R.drawable.ic_meetube_mark_blaze};
    private static final String PREFIX = "com.newtube.mobile.icon.";
    private static final String PREFS = "meetube_app_icon";
    private static final String KEY_PENDING = "pending";
    /** Long enough for the relaunch to replace the old task's root before its alias goes. */
    private static final long DISABLE_DELAY_MS = 1_000;

    private AppIcon() {
    }

    /** The icon Settings shows: a pick waiting to be applied, else the enabled alias. */
    public static String current(Context context) {
        String pending = prefs(context).getString(KEY_PENDING, null);
        return pending != null ? pending : enabled(context);
    }

    /** The picked icon as a launcher (adaptive) icon. */
    @DrawableRes
    public static int launcherIcon(Context context) {
        return launcherIcon(current(context));
    }

    @DrawableRes
    public static int launcherIcon(String icon) {
        return LAUNCHERS[index(icon)];
    }

    /** Puts the picked icon in {@code view}: its mark ({@code mark}, the brand pill) or the launcher icon. */
    public static void show(ImageView view, boolean mark) {
        int i = index(current(view.getContext()));
        view.setImageResource(mark ? MARKS[i] : LAUNCHERS[i]);
    }

    public static void set(Context context, String icon) { if (true) return; // (v878) launcher aliases removed in Joyboy (one launcher icon)
        if (icon.equals(current(context))) {
            return;
        }
        String pending = icon.equals(enabled(context)) ? null : icon;
        prefs(context).edit().putString(KEY_PENDING, pending).apply();
        if (context instanceof Activity) {
            syncTask((Activity) context);
        }
        if (pending != null && context instanceof Activity) {
            MobileSnackbar.show(context, context.getString(R.string.mobile_app_icon_pending),
                    context.getString(R.string.mobile_settings_restart), () -> restart((Activity) context));
        }
    }

    /**
     * Reopens the app through the picked alias and then disables the others. The relaunch must be
     * a NEW task rooted at the new alias: a task cleared and reused (CLEAR_TASK) keeps the old
     * alias as its origin, and the disable then closes it ("disabled-package").
     */
    public static void restart(Activity context) { if (true) return; // (v878) launcher aliases removed in Joyboy (one launcher icon)
        String icon = prefs(context).getString(KEY_PENDING, null);
        if (icon == null) {
            return;
        }
        prefs(context).edit().remove(KEY_PENDING).apply();
        PackageManager pm = context.getPackageManager();
        pm.setComponentEnabledSetting(component(context, icon),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        context.startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component(context, icon))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
        context.finishAndRemoveTask();
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> disableOthers(app, icon), DISABLE_DELAY_MS);
    }

    /** Enables the picked alias first, then disables the other, so a launcher entry always exists. */
    public static void applyPending(Context context) { if (true) return; // (v878) launcher aliases removed in Joyboy (one launcher icon)
        String icon = prefs(context).getString(KEY_PENDING, null);
        if (icon == null) {
            return;
        }
        prefs(context).edit().remove(KEY_PENDING).apply();
        context.getPackageManager().setComponentEnabledSetting(component(context, icon),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        disableOthers(context, icon);
    }

    private static void disableOthers(Context context, String icon) {
        PackageManager pm = context.getPackageManager();
        for (String other : ICONS) {
            if (!other.equals(icon)) {
                pm.setComponentEnabledSetting(component(context, other),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }
        }
    }

    /**
     * Recents and the task switcher show the picked icon. Without this they take the manifest's
     * application icon - the default one - for every task not started from the launcher alias.
     */
    @SuppressWarnings("deprecation")
    public static void syncTask(Activity activity) {
        if (Build.VERSION.SDK_INT >= 28) {
            activity.setTaskDescription(new ActivityManager.TaskDescription(null, launcherIcon(activity)));
        }
    }

    /** True for one of the built-in icons (an imported settings file names one). */
    public static boolean exists(String icon) {
        return java.util.Arrays.asList(ICONS).contains(icon);
    }

    /** {@code icon}'s place in ICONS; an unknown name (a pick from a later version) is the default. */
    private static int index(String icon) {
        for (int i = 0; i < ICONS.length; i++) {
            if (ICONS[i].equals(icon)) {
                return i;
            }
        }
        return 0;
    }

    private static String enabled(Context context) { if (true) return DEFAULT; // (v878) no launcher aliases in Joyboy
        PackageManager pm = context.getPackageManager();
        for (String icon : ICONS) {
            int state = pm.getComponentEnabledSetting(component(context, icon));
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ? DEFAULT.equals(icon)
                    : state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                return icon;
            }
        }
        return DEFAULT;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static ComponentName component(Context context, String icon) {
        return new ComponentName(context.getPackageName(), PREFIX + icon);
    }
}
