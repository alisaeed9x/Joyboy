package com.newtube.mobile.ui.browse;

import android.content.Context;
import android.content.SharedPreferences;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.misc.VideoDownloads;

/**
 * MEETUBE(nav tabs, issue #7): the bottom bar is Home | tab | tab | You with Search before, between
 * or after the two tabs ({@link #searchPosition}), or up in the top bar ({@link #searchOnTop}).
 * Home and You are fixed; the tabs are picked and the order dragged in Settings > Customization >
 * Tabs (BarEditor), each tab one of {@link #CHOICES} or {@link #NONE}. Picking the tab the other
 * slot holds swaps the two.
 */
public final class NavTabs {
    public static final int NONE = -1;
    public static final int FIRST = 0;
    public static final int SECOND = 1;
    /** What a slot can hold, in the order Settings lists them. */
    public static final int[] CHOICES = {MediaGroup.TYPE_SHORTS, MediaGroup.TYPE_SUBSCRIPTIONS,
            VideoDownloads.SECTION_ID, MediaGroup.TYPE_HISTORY}; // JOYBOY(v924): Media tab removed (it is in Joyboy's own bar)
    private static final String PREFS = "meetube_nav_tabs";
    private static final String[] KEYS = {"before_search", "after_search"}; // the names from when Search sat between
    private static final String KEY_SUBS_MIGRATED = "subs_next_to_home_v883";
    private static final String KEY_MEDIA_REMOVED = "media_tab_removed_v924";
    private static final String KEY_LABELS = "labels";
    private static final String KEY_HOME = "home";
    private static final String KEY_BRAND_NAME = "brand_name";
    private static final String KEY_SEARCH_ON_TOP = "search_on_top";
    private static final String KEY_SEARCH_POSITION = "search_position";
    private static final String KEY_SEARCH_FAB_LEFT = "search_fab_left";
    private static final String KEY_TOP_BAR_PINNED = "top_bar_pinned";
    /** The bar as it was before the tabs could be picked. */
    private static final int[] DEFAULTS = {MediaGroup.TYPE_SUBSCRIPTIONS, MediaGroup.TYPE_SHORTS}; // JOYBOY(v883): Home | Subscriptions | Shorts | You

    private NavTabs() {
    }

    /** The section id in {@code slot}, or {@link #NONE}. */
    public static int get(Context context, int slot) {
        migrateSubsNextToHome(context);
        migrateRemoveMedia(context);
        return prefs(context).getInt(KEYS[slot], DEFAULTS[slot]);
    }

    /**
     * JOYBOY(v883): Subscriptions sits right after Home. Runs once per install: if Subscriptions is not
     * already the first tab, it takes that slot, the old first tab moves to the second slot, and the old
     * second one (Downloads by default) drops out of the bar. It is still pickable in Settings > Tabs.
     */
    private static void migrateSubsNextToHome(Context context) {
        SharedPreferences p = prefs(context);
        if (p.getBoolean(KEY_SUBS_MIGRATED, false)) {
            return;
        }
        int a = p.getInt(KEYS[FIRST], DEFAULTS[FIRST]);
        SharedPreferences.Editor editor = p.edit().putBoolean(KEY_SUBS_MIGRATED, true);
        if (a != MediaGroup.TYPE_SUBSCRIPTIONS && a != com.liskovsoft.smartyoutubetv2.common.misc.LocalMedia.SECTION_ID) { // JOYBOY: Media stays first
            editor.putInt(KEYS[FIRST], MediaGroup.TYPE_SUBSCRIPTIONS).putInt(KEYS[SECOND], a);
        }
        editor.apply();
    }

    /** JOYBOY(v924): the Media tab leaves the MeeTube bar (Joyboy's own bar already has it). Runs once: a slot holding Media gets Subscriptions/Shorts instead. */
    private static void migrateRemoveMedia(Context context) {
        SharedPreferences p = prefs(context);
        if (p.getBoolean(KEY_MEDIA_REMOVED, false)) {
            return;
        }
        final int media = com.liskovsoft.smartyoutubetv2.common.misc.LocalMedia.SECTION_ID;
        int a = p.getInt(KEYS[FIRST], DEFAULTS[FIRST]);
        int b = p.getInt(KEYS[SECOND], DEFAULTS[SECOND]);
        SharedPreferences.Editor editor = p.edit().putBoolean(KEY_MEDIA_REMOVED, true);
        if (a == media && b == media) {
            editor.putInt(KEYS[FIRST], MediaGroup.TYPE_SUBSCRIPTIONS).putInt(KEYS[SECOND], MediaGroup.TYPE_SHORTS);
        } else if (a == media) {
            editor.putInt(KEYS[FIRST], b == MediaGroup.TYPE_SUBSCRIPTIONS ? MediaGroup.TYPE_SHORTS : MediaGroup.TYPE_SUBSCRIPTIONS);
        } else if (b == media) {
            editor.putInt(KEYS[SECOND], a == MediaGroup.TYPE_SUBSCRIPTIONS ? MediaGroup.TYPE_SHORTS : MediaGroup.TYPE_SUBSCRIPTIONS);
        }
        editor.apply();
    }

    public static void set(Context context, int slot, int sectionId) {
        int other = 1 - slot;
        SharedPreferences.Editor editor = prefs(context).edit().putInt(KEYS[slot], sectionId);
        if (sectionId != NONE && get(context, other) == sectionId) {
            editor.putInt(KEYS[other], get(context, slot));
        }
        editor.apply();
    }

    /**
     * MEETUBE(issue #213): Settings > Customization > Tabs, a tap on Home - off, the bar has no Home
     * and the app opens on its first tab that is a feed (else You), for a feed-free app.
     */
    public static boolean showHome(Context context) {
        return prefs(context).getBoolean(KEY_HOME, true);
    }

    public static void setShowHome(Context context, boolean show) {
        prefs(context).edit().putBoolean(KEY_HOME, show).apply();
    }

    /**
     * The tab Home's place goes to: Home, or with Home removed the first bar tab that is a feed
     * (Shorts is a player), else {@link #NONE} (You).
     */
    public static int landingSectionId(Context context) {
        if (showHome(context)) {
            return MediaGroup.TYPE_HOME;
        }
        for (int slot : new int[] {FIRST, SECOND}) {
            int id = get(context, slot);
            if (id != NONE && id != MediaGroup.TYPE_SHORTS) {
                return id;
            }
        }
        return NONE;
    }

    /** MEETUBE(nav labels): Settings > Customization > Tab names - the names under the bar's icons. */
    public static boolean showLabels(Context context) {
        return prefs(context).getBoolean(KEY_LABELS, true);
    }

    public static void setShowLabels(Context context, boolean show) {
        prefs(context).edit().putBoolean(KEY_LABELS, show).apply();
    }

    /** MEETUBE(brand name): Settings > Customization > App name in the top bar - off, the brand pill is only the icon. */
    public static boolean showBrandName(Context context) {
        return prefs(context).getBoolean(KEY_BRAND_NAME, true);
    }

    public static void setShowBrandName(Context context, boolean show) {
        prefs(context).edit().putBoolean(KEY_BRAND_NAME, show).apply();
    }

    /** MEETUBE(search place): Settings > Customization > Tabs - Search in the top bar instead of the bottom one. */
    public static boolean searchOnTop(Context context) {
        return prefs(context).getBoolean(KEY_SEARCH_ON_TOP, false);
    }

    /** MEETUBE(search fab): the floating Search, out while the bar is slid away, at the left corner (else the right). */
    public static boolean searchFabLeft(Context context) {
        return prefs(context).getBoolean(KEY_SEARCH_FAB_LEFT, false);
    }

    public static void setSearchFabLeft(Context context, boolean left) {
        prefs(context).edit().putBoolean(KEY_SEARCH_FAB_LEFT, left).apply();
    }

    /** MEETUBE(issue #181): the top pills stay while a feed scrolls; off (default) they slide away like the bottom bar. */
    public static boolean topBarAlwaysShown(Context context) {
        return prefs(context).getBoolean(KEY_TOP_BAR_PINNED, false);
    }

    public static void setTopBarAlwaysShown(Context context, boolean shown) {
        prefs(context).edit().putBoolean(KEY_TOP_BAR_PINNED, shown).apply();
    }

    /** Where Search sits in the bottom bar: how many of the two tabs come before it (0 to 2). */
    public static int searchPosition(Context context) {
        return prefs(context).getInt(KEY_SEARCH_POSITION, 1);
    }

    /** The whole bar at once, as BarEditor's stack reads after a drag. */
    public static void setLayout(Context context, int first, int second, int searchPosition, boolean searchOnTop) {
        prefs(context).edit().putInt(KEYS[FIRST], first).putInt(KEYS[SECOND], second)
                .putInt(KEY_SEARCH_POSITION, searchPosition).putBoolean(KEY_SEARCH_ON_TOP, searchOnTop).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
