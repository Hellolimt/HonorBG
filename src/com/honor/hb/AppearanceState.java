package com.honor.hb;

import java.util.ArrayList;
import java.util.List;

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
 *
 * <p>Glass appearance is NOT here any more: it lives in {@link GlassConfig}, which owns both
 * the keys and the live snapshot. This class only answers "is there a wallpaper, and how
 * should the window layer show it".
 */
final class AppearanceState {

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
    final List<String> candidates = new ArrayList<String>();

    static AppearanceState read(XposedModule module) {
        Prefs p = Prefs.of(module);
        if (!p.available()) {
            return null;
        }
        // A page entry is the natural point to pick up edits made in the module's settings
        // page (a different process). GlassConfig.refresh() re-reads the remote prefs and
        // swaps one immutable snapshot, so painters see a consistent set of values.
        GlassConfig.refresh(module);

        AppearanceState st = new AppearanceState();
        st.mime = p.str("mime_home", null);
        long modified = p.lng("modified_home", -1L);
        st.modified = modified;
        st.enabled = st.mime != null && st.mime.length() > 0
                && !"application/octet-stream".equals(st.mime)
                && modified >= 0L;
        st.opacity = Prefs.clamp(p.num("opacity_home", 100), 0, 100);
        st.blurEnabled = p.bool("blur_enabled_home", false);
        st.blurRadiusDp = Prefs.clamp(p.num("blur_radius_home", 20), 0, 80);
        st.randomEnabled = p.bool("ui_random_bg_enabled", false)
                && p.setContains("ui_random_bg_slots", "home")
                && p.lng("random_size_home", -1L) >= 0L;
        st.honorEnabled = p.bool("honor_home_enabled", true);
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
}
