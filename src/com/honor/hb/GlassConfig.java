package com.honor.hb;

import android.content.SharedPreferences;

import io.github.libxposed.api.XposedModule;

/**
 * The single source of truth for every user-adjustable glass parameter.
 *
 * <p>Before this class the visual recipe was scattered across three places: three static
 * fields in HonorRenderer, immutable constants in GlassStyle, and three preference keys in
 * AppearanceState. Adding one control meant touching all three. Now:
 *
 * <pre>
 *   HonorSettingsUi  --writes-->  SharedPreferences("backgrounds")
 *          |                            |
 *          |       pr editor mirrors every key into LSPosed remote prefs
 *          v                            v
 *   KEY_* constants  <---------  GlassConfig.refresh(XposedModule)
 *                                      |
 *                    immutable snapshot -> GlassStyle / RowGlass / SurfaceGlass
 * </pre>
 *
 * <p><b>Every default reproduces beta25 exactly.</b> New effects (refraction, custom divider
 * colour, custom geometry) are opt-in: with no preference keys present the renderer paints
 * byte-for-byte the same picture as the verified perfect build. That is deliberate - the
 * baseline stays the fallback for the whole 1.4.6 line.
 *
 * <p>Keys use the {@code hb_} namespace. The beta25 line used {@code honor_glass_*}; keeping
 * those names would make this build inherit any stale value left in LSPosed's remote-pref
 * cache (including values written during development), so 1.4.6 starts from a clean namespace
 * and falls back to the defaults below.
 *
 * <p>Values are stored as integers in tenths (x10) where a decimal is needed. Remote prefs
 * travel through a generic map, so ints are the most portable type; it also keeps the
 * SeekBar wiring in the settings page trivial.
 */
final class GlassConfig {

    // ---- keys (shared with the settings UI, never duplicated as string literals) ----
    static final String KEY_VERSION = "hb_cfg_version";

    static final String KEY_GLASS_ENABLED = "hb_glass_enabled";
    static final String KEY_FILL_ALPHA = "hb_fill_alpha";
    static final String KEY_SHEEN = "hb_sheen";
    static final String KEY_REFRACTION = "hb_refraction";

    static final String KEY_STROKE_ENABLED = "hb_stroke_enabled";
    static final String KEY_STROKE_ALPHA = "hb_stroke_alpha";
    static final String KEY_STROKE_WIDTH = "hb_stroke_width_x10";

    static final String KEY_DIVIDER_ENABLED = "hb_divider_enabled";
    static final String KEY_DIVIDER_CUSTOM = "hb_divider_custom";
    static final String KEY_DIVIDER_COLOR = "hb_divider_color";
    static final String KEY_DIVIDER_ALPHA = "hb_divider_alpha";
    static final String KEY_DIVIDER_WIDTH = "hb_divider_width_x10";
    static final String KEY_DIVIDER_INSET = "hb_divider_inset_x10";

    static final String KEY_CORNER = "hb_corner_x10";
    static final String KEY_H_INSET = "hb_h_inset_x10";
    static final String KEY_V_INSET = "hb_v_inset_x10";

    // ---- defaults: one definition, used by both read() and "restore defaults" ----
    static final boolean DEF_GLASS_ENABLED = true;
    static final int DEF_FILL_ALPHA = 0x99;   // 153, the 60% surface from beta25
    static final int DEF_SHEEN = 40;
    static final int DEF_REFRACTION = 0;      // off
    static final boolean DEF_STROKE_ENABLED = true;
    static final int DEF_STROKE_ALPHA = 100;
    static final int DEF_STROKE_WIDTH = 10;   // 1dp
    static final boolean DEF_DIVIDER_ENABLED = true;
    static final boolean DEF_DIVIDER_CUSTOM = false;
    static final int DEF_DIVIDER_COLOR = 0xFFFFFF;
    static final int DEF_DIVIDER_ALPHA = 20;
    static final int DEF_DIVIDER_WIDTH = 10;  // 1px
    static final int DEF_DIVIDER_INSET = 160; // 16dp
    static final int DEF_CORNER = 180;        // 18dp
    static final int DEF_H_INSET = 140;       // 14dp
    static final int DEF_V_INSET = 40;        // 4dp

    // ---- live values ----
    boolean enabled = DEF_GLASS_ENABLED;
    int fillAlpha = DEF_FILL_ALPHA;
    /** Diagonal top-left specular highlight, 0..255. */
    int sheen = DEF_SHEEN;
    /** Edge refraction band, 0..100 (%). 0 = off, i.e. exactly beta25. */
    int refraction = DEF_REFRACTION;

    boolean strokeEnabled = DEF_STROKE_ENABLED;
    /** Stroke alpha scale, 0..100 (%). */
    int strokeAlphaPct = DEF_STROKE_ALPHA;
    /** Stroke width in tenths of a dp. */
    int strokeWidthX10 = DEF_STROKE_WIDTH;

    boolean dividerEnabled = DEF_DIVIDER_ENABLED;
    /** False = use the ROM-matching light/dark divider colour (beta25). */
    boolean dividerCustom = DEF_DIVIDER_CUSTOM;
    /** RGB only, 0xRRGGBB. */
    int dividerColor = DEF_DIVIDER_COLOR;
    /** Divider alpha, 0..100 (%); only used when dividerCustom is true. */
    int dividerAlphaPct = DEF_DIVIDER_ALPHA;
    /** Divider thickness in tenths of a physical pixel. */
    int dividerWidthX10 = DEF_DIVIDER_WIDTH;
    /** Left/right inset in tenths of a dp. */
    int dividerInsetX10 = DEF_DIVIDER_INSET;

    int cornerX10 = DEF_CORNER;
    int hInsetX10 = DEF_H_INSET;
    int vInsetX10 = DEF_V_INSET;

    /**
     * Bumped by the settings UI on every edit so the renderer knows to repaint. Kept as an
     * int: the writer uses putInt(), and reading it with getLong() silently returned the
     * fallback 0 on every page entry.
     */
    int version;

    private static volatile GlassConfig sCurrent = new GlassConfig();
    /** Set by refresh() when the snapshot changed; consumed by the next page entry. */
    private static volatile boolean sDirty;

    static GlassConfig get() {
        return sCurrent;
    }

    /** True once after a config edit, so the page knows to repaint already-painted rows. */
    static boolean consumeDirty() {
        boolean d = sDirty;
        sDirty = false;
        return d;
    }

    float cornerDp() {
        return cornerX10 / 10f;
    }

    float hInsetDp() {
        return hInsetX10 / 10f;
    }

    float vInsetDp() {
        return vInsetX10 / 10f;
    }

    float dividerInsetDp() {
        return dividerInsetX10 / 10f;
    }

    float strokeWidthDp() {
        return strokeWidthX10 / 10f;
    }

    float dividerWidthPx() {
        return dividerWidthX10 / 10f;
    }

    /**
     * Writes every default explicitly. "Restore defaults" must not rely on
     * {@code Editor.remove()}: the {@code pr} wrapper mirrors the local preference map into
     * LSPosed's remote channel, and a key that is absent locally is not guaranteed to be
     * deleted remotely. Overwriting guarantees the remote side sees the beta25 recipe.
     */
    static void writeDefaults(SharedPreferences.Editor e) {
        e.putBoolean(KEY_GLASS_ENABLED, DEF_GLASS_ENABLED);
        e.putInt(KEY_FILL_ALPHA, DEF_FILL_ALPHA);
        e.putInt(KEY_SHEEN, DEF_SHEEN);
        e.putInt(KEY_REFRACTION, DEF_REFRACTION);
        e.putBoolean(KEY_STROKE_ENABLED, DEF_STROKE_ENABLED);
        e.putInt(KEY_STROKE_ALPHA, DEF_STROKE_ALPHA);
        e.putInt(KEY_STROKE_WIDTH, DEF_STROKE_WIDTH);
        e.putBoolean(KEY_DIVIDER_ENABLED, DEF_DIVIDER_ENABLED);
        e.putBoolean(KEY_DIVIDER_CUSTOM, DEF_DIVIDER_CUSTOM);
        e.putInt(KEY_DIVIDER_COLOR, DEF_DIVIDER_COLOR);
        e.putInt(KEY_DIVIDER_ALPHA, DEF_DIVIDER_ALPHA);
        e.putInt(KEY_DIVIDER_WIDTH, DEF_DIVIDER_WIDTH);
        e.putInt(KEY_DIVIDER_INSET, DEF_DIVIDER_INSET);
        e.putInt(KEY_CORNER, DEF_CORNER);
        e.putInt(KEY_H_INSET, DEF_H_INSET);
        e.putInt(KEY_V_INSET, DEF_V_INSET);
    }

    /** Re-reads the remote prefs; returns true when anything a painter uses has changed. */
    static boolean refresh(XposedModule module) {
        Prefs p = Prefs.of(module);
        if (!p.available()) {
            return false;
        }
        GlassConfig next = read(p);
        if (next.sameAs(sCurrent)) {
            return false;
        }
        sCurrent = next;
        sDirty = true;
        return true;
    }

    private static GlassConfig read(Prefs p) {
        GlassConfig c = new GlassConfig();
        c.version = p.num(KEY_VERSION, 0);
        c.enabled = p.bool(KEY_GLASS_ENABLED, DEF_GLASS_ENABLED);
        c.fillAlpha = Prefs.clamp(p.num(KEY_FILL_ALPHA, DEF_FILL_ALPHA), 0, 255);
        c.sheen = Prefs.clamp(p.num(KEY_SHEEN, DEF_SHEEN), 0, 255);
        c.refraction = Prefs.clamp(p.num(KEY_REFRACTION, DEF_REFRACTION), 0, 100);

        c.strokeEnabled = p.bool(KEY_STROKE_ENABLED, DEF_STROKE_ENABLED);
        c.strokeAlphaPct = Prefs.clamp(p.num(KEY_STROKE_ALPHA, DEF_STROKE_ALPHA), 0, 100);
        c.strokeWidthX10 = Prefs.clamp(p.num(KEY_STROKE_WIDTH, DEF_STROKE_WIDTH), 5, 30);

        c.dividerEnabled = p.bool(KEY_DIVIDER_ENABLED, DEF_DIVIDER_ENABLED);
        c.dividerCustom = p.bool(KEY_DIVIDER_CUSTOM, DEF_DIVIDER_CUSTOM);
        c.dividerColor = p.num(KEY_DIVIDER_COLOR, DEF_DIVIDER_COLOR) & 0xFFFFFF;
        c.dividerAlphaPct = Prefs.clamp(p.num(KEY_DIVIDER_ALPHA, DEF_DIVIDER_ALPHA), 0, 100);
        c.dividerWidthX10 = Prefs.clamp(p.num(KEY_DIVIDER_WIDTH, DEF_DIVIDER_WIDTH), 2, 40);
        c.dividerInsetX10 = Prefs.clamp(p.num(KEY_DIVIDER_INSET, DEF_DIVIDER_INSET), 0, 320);

        c.cornerX10 = Prefs.clamp(p.num(KEY_CORNER, DEF_CORNER), 0, 300);
        c.hInsetX10 = Prefs.clamp(p.num(KEY_H_INSET, DEF_H_INSET), 0, 280);
        c.vInsetX10 = Prefs.clamp(p.num(KEY_V_INSET, DEF_V_INSET), 0, 120);
        return c;
    }

    private boolean sameAs(GlassConfig o) {
        if (o == null) {
            return false;
        }
        return version == o.version
                && enabled == o.enabled
                && fillAlpha == o.fillAlpha
                && sheen == o.sheen
                && refraction == o.refraction
                && strokeEnabled == o.strokeEnabled
                && strokeAlphaPct == o.strokeAlphaPct
                && strokeWidthX10 == o.strokeWidthX10
                && dividerEnabled == o.dividerEnabled
                && dividerCustom == o.dividerCustom
                && dividerColor == o.dividerColor
                && dividerAlphaPct == o.dividerAlphaPct
                && dividerWidthX10 == o.dividerWidthX10
                && dividerInsetX10 == o.dividerInsetX10
                && cornerX10 == o.cornerX10
                && hInsetX10 == o.hInsetX10
                && vInsetX10 == o.vInsetX10;
    }

    /** Compact signature for the module log. */
    String describe() {
        return "glass=" + enabled + " fill=" + fillAlpha + " sheen=" + sheen
                + " refr=" + refraction + " stroke=" + strokeEnabled + "/" + strokeAlphaPct
                + "/" + strokeWidthX10 + " div=" + dividerEnabled + "/" + dividerCustom
                + "/#" + Integer.toHexString(dividerColor) + "/" + dividerAlphaPct
                + "/" + dividerWidthX10 + " geo=" + cornerX10 + "/" + hInsetX10
                + "/" + vInsetX10 + " v=" + version;
    }
}
