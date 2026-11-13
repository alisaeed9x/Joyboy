package com.newtube.mobile.translate;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * JOYBOY(translation engine): the translation engine's settings store. Same file ("p") and the
 * same key names and types as Joyboy's Kotlin {@code Cfg}, so the engine port in the next step
 * reads exactly what these pages write, and a Joyboy backup restores into it unchanged.
 */
public final class TrCfg {
    private static final String FILE = "p";

    /** Stored as Boolean (CfgCodec.BOOLS). */
    private static final Set<String> BOOLS = new HashSet<>(Arrays.asList(
            "autochars", "autopron", "autotpl", "hitiming", "gapfill", "soundtags", "prefine", "refine2pass"));
    /** Stored as Int (CfgCodec.INTS). */
    private static final Set<String> INTS = new HashSet<>(Arrays.asList(
            "chunk", "ahead", "hls_ahead", "atrack", "parallel", "kmode", "nmain", "nback"));

    private TrCfg() {
    }

    private static SharedPreferences p(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String str(Context context, String key, String def) {
        Object v = p(context).getAll().get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? "1" : "0";
        }
        return v.toString();
    }

    public static boolean bool(Context context, String key, boolean def) {
        Object v = p(context).getAll().get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue() != 0;
        }
        String s = v.toString();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    public static int integer(Context context, String key, int def) {
        Object v = p(context).getAll().get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return def;
    }

    /** Writes with the type the engine expects for this key (see CfgCodec.typed). */
    public static void put(Context context, String key, String value) {
        SharedPreferences.Editor editor = p(context).edit();
        if (BOOLS.contains(key)) {
            editor.putBoolean(key, "1".equals(value) || "true".equalsIgnoreCase(value));
        } else if (INTS.contains(key)) {
            try {
                editor.putInt(key, Integer.parseInt(value.trim()));
            } catch (NumberFormatException e) {
                editor.putString(key, value);
            }
        } else {
            editor.putString(key, value);
        }
        editor.apply();
    }

    public static void putBool(Context context, String key, boolean value) {
        put(context, key, value ? "1" : "0");
    }

    /** How many non-trivial key lines the multi-line key box holds (the engine drops lines of 10 chars or less). */
    public static int keyCount(Context context, String key) {
        int n = 0;
        for (String line : str(context, key, "").split("\n")) {
            if (line.trim().length() > 10) {
                n++;
            }
        }
        return n;
    }
}
