package com.honor.hb;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * Snapshot of the appearance the original module UI saved for the "home" slot.
 *
 * Verified against the module data of a real device (HyperBackground 1.4.5-beta2):
 * the live keys in the "backgrounds" preference group use the {@code <key>_<slot>}
 * layout, e.g. mime_home / opacity_home / blur_enabled_home / blur_radius_home /
 * modified_home. A slot counts as enabled when modified_<slot> >= 0 and a real MIME
 * type was stored. The bitmap bytes live next to the module under
 * files/background_<slot>.bin (random mode: files/background_<slot>.random.bin).
 */
final class AppearanceState {

    static final String PREFS_PRIMARY = "backgrounds";
    static final String PREFS_MIRROR = "settings_appearance";

    boolean enabled;
    String mime;
    /** modified_home stamp of the saved wallpaper; part of the decode cache key. */
    long modified = -1L;
    int opacity = 100;
    boolean blurEnabled;
    int blurRadiusDp = 0;
    boolean randomEnabled;
    /** Trimmed-UI switch (honor_home_enabled): lets the user disable the background without clearing it. */
    boolean honorEnabled = true;
    /** Card glass tint alpha (honor_glass_alpha, 0..255, default 0x99 = 60% white). */
    int glassAlpha = 0x99;
    /** Card glass diagonal sheen (honor_glass_sheen, 0..255, default 40). */
    int glassSheen = 40;
    /** Master switch for card glass (honor_glass_enabled, default true). When false the
     *  renderer skips card glassification and only keeps the background + strips opaque
     *  surfaces, acting as a rollback switch. */
    boolean glassEnabled = true;
    final List<String> candidates = new ArrayList<String>();

    static AppearanceState read(XposedModule module) {
        SharedPreferences primary = openPrefs(module, PREFS_PRIMARY);
        SharedPreferences mirror = openPrefs(module, PREFS_MIRROR);
        if (primary == null && mirror == null) {
            return null;
        }

        AppearanceState st = new AppearanceState();
        st.mime = readString(primary, mirror, "mime_home", null);
        long modified = readLong(primary, mirror, "modified_home", -1L);
        st.modified = modified;
        st.enabled = st.mime != null && st.mime.length() > 0
                && !"application/octet-stream".equals(st.mime)
                && modified >= 0L;
        st.opacity = clamp(readInt(primary, mirror, "opacity_home", 100), 0, 100);
        st.blurEnabled = readBoolean(primary, mirror, "blur_enabled_home", false);
        st.blurRadiusDp = clamp(readInt(primary, mirror, "blur_radius_home", 20), 0, 80);
        st.randomEnabled = readBoolean(primary, mirror, "ui_random_bg_enabled", false)
                && stringSetContains(primary, mirror, "ui_random_bg_slots", "home")
                && readLong(primary, mirror, "random_size_home", -1L) >= 0L;
        st.honorEnabled = readBoolean(primary, mirror, "honor_home_enabled", true);
        st.glassAlpha = clamp(readInt(primary, mirror, "honor_glass_alpha", 0x99), 0, 255);
        st.glassSheen = clamp(readInt(primary, mirror, "honor_glass_sheen", 40), 0, 255);
        st.glassEnabled = readBoolean(primary, mirror, "honor_glass_enabled", true);
        st.enabled = st.enabled && st.honorEnabled;

        if (st.randomEnabled) {
            st.candidates.add("background_home.random.bin");
        }
        st.candidates.add("background_home.bin");
        st.candidates.add("backgrounds/home.bin");
        st.candidates.add("ui_background.bin");
        st.candidates.add("settings_appearance/home.bin");
        return st;
    }

    private static SharedPreferences openPrefs(XposedModule module, String name) {
        try {
            return module.getRemotePreferences(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean has(SharedPreferences p, String key) {
        try {
            return p != null && p.contains(key);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean readBoolean(SharedPreferences p, SharedPreferences q, String key, boolean def) {
        try {
            if (has(p, key)) {
                return p.getBoolean(key, def);
            }
            if (has(q, key)) {
                return q.getBoolean(key, def);
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    private static String readString(SharedPreferences p, SharedPreferences q, String key, String def) {
        try {
            if (has(p, key)) {
                return p.getString(key, def);
            }
            if (has(q, key)) {
                return q.getString(key, def);
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    private static int readInt(SharedPreferences p, SharedPreferences q, String key, int def) {
        try {
            if (has(p, key)) {
                try {
                    return p.getInt(key, def);
                } catch (ClassCastException cce) {
                    return parseInt(p.getString(key, null), def);
                }
            }
            if (has(q, key)) {
                try {
                    return q.getInt(key, def);
                } catch (ClassCastException cce) {
                    return parseInt(q.getString(key, null), def);
                }
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    private static long readLong(SharedPreferences p, SharedPreferences q, String key, long def) {
        try {
            if (has(p, key)) {
                try {
                    return p.getLong(key, def);
                } catch (ClassCastException cce) {
                    return parseLong(p.getString(key, null), def);
                }
            }
            if (has(q, key)) {
                try {
                    return q.getLong(key, def);
                } catch (ClassCastException cce) {
                    return parseLong(q.getString(key, null), def);
                }
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    private static boolean stringSetContains(SharedPreferences p, SharedPreferences q, String key, String value) {
        try {
            Set<String> set = null;
            if (has(p, key)) {
                set = p.getStringSet(key, null);
            }
            if ((set == null || set.isEmpty()) && has(q, key)) {
                set = q.getStringSet(key, null);
            }
            return set != null && set.contains(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }
}
