package com.honor.hb;

import android.content.SharedPreferences;

import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * One reader for the module's two preference groups.
 *
 * <p>The original module writes {@code backgrounds} through its {@code pr} wrapper, whose
 * editor mirrors EVERY key into the LSPosed remote preference channel on commit. The hook side
 * therefore reads live values with {@link XposedModule#getRemotePreferences(String)}; the
 * legacy {@code settings_appearance} group is kept as a fallback for older installs.
 *
 * <p>Before this class existed, AppearanceState carried four near-identical readBoolean /
 * readString / readInt / readLong pairs, each with its own primary-vs-mirror and
 * ClassCastException handling. Keeping that logic in one place also makes the new
 * configurable glass parameters trivial to add: one key, one line.
 */
final class Prefs {

    static final String GROUP_PRIMARY = "backgrounds";
    static final String GROUP_MIRROR = "settings_appearance";

    private final SharedPreferences primary;
    private final SharedPreferences mirror;

    private Prefs(SharedPreferences primary, SharedPreferences mirror) {
        this.primary = primary;
        this.mirror = mirror;
    }

    static Prefs of(XposedModule module) {
        return new Prefs(open(module, GROUP_PRIMARY), open(module, GROUP_MIRROR));
    }

    boolean available() {
        return primary != null || mirror != null;
    }

    private static SharedPreferences open(XposedModule module, String name) {
        if (module == null) {
            return null;
        }
        try {
            return module.getRemotePreferences(name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The group that actually holds this key, or null when neither does. */
    private SharedPreferences owner(String key) {
        try {
            if (primary != null && primary.contains(key)) {
                return primary;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mirror != null && mirror.contains(key)) {
                return mirror;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    boolean bool(String key, boolean def) {
        SharedPreferences p = owner(key);
        if (p == null) {
            return def;
        }
        try {
            return p.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    String str(String key, String def) {
        SharedPreferences p = owner(key);
        if (p == null) {
            return def;
        }
        try {
            String v = p.getString(key, def);
            return v != null ? v : def;
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * Remote prefs are rebuilt from a generic map, so an older install may have stored a
     * numeric key as a String. Accept both without breaking the caller.
     */
    int num(String key, int def) {
        SharedPreferences p = owner(key);
        if (p == null) {
            return def;
        }
        try {
            return p.getInt(key, def);
        } catch (ClassCastException cce) {
            return parseInt(p.getString(key, null), def);
        } catch (Throwable t) {
            return def;
        }
    }

    long lng(String key, long def) {
        SharedPreferences p = owner(key);
        if (p == null) {
            return def;
        }
        try {
            return p.getLong(key, def);
        } catch (ClassCastException cce) {
            return parseLong(p.getString(key, null), def);
        } catch (Throwable t) {
            return def;
        }
    }

    boolean setContains(String key, String value) {
        SharedPreferences p = owner(key);
        if (p == null) {
            return false;
        }
        try {
            Set<String> set = p.getStringSet(key, null);
            return set != null && set.contains(value);
        } catch (Throwable t) {
            return false;
        }
    }

    static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
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
}
