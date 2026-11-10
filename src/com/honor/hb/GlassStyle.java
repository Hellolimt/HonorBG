package com.honor.hb;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

/**
 * Central "liquid glass" painter, shared by the renderer and the group-block painter.
 *
 * Recipe (beta25):
 *  - translucent frosted base (light: white; dark: near-black surface),
 *  - one single 1dp gradient hairline (bright top edge, dark bottom edge),
 *  - diagonal top-left specular sheen,
 *  - 18dp rounded corners, 14dp side inset, and the ROM's own group gap between blocks.
 *
 * <p>Since 1.4.6 the tunable parts are read from {@link GlassConfig} instead of being
 * hard-coded. Geometry constants that define *which rows form a group* stay fixed: exposing
 * those would break the verified grouping behaviour. The three new effects - refraction
 * band, stroke tuning and divider colour/width - are all opt-in and default to "off" /
 * beta25 values, so an install with no preference keys paints exactly the perfect build.
 *
 * Since the group merge a card is no longer built per row: one rounded block covers a whole
 * system group and is painted straight onto the row's background drawable. That keeps exactly
 * one fill, one stroke and one sheen per group, so no row can end up with a double stroke.
 */
final class GlassStyle {

    // ---- geometry that defines group membership: deliberately NOT user-tunable ----
    static final float MIN_ROW_HEIGHT_DP = 44f;
    /** A group never takes more than this share of the viewport (keeps heroes out). */
    static final float MAX_ROW_HEIGHT_FRAC = 0.62f;
    /** A row must span at least this share of the container width. */
    static final float MIN_ROW_WIDTH_FRAC = 0.55f;
    /** Hard floor for "these two rows belong to the same group". */
    static final float GROUP_GAP_DP = 20f;
    /** Sub-pixel/rounding tolerance: a gap this small still means "same group". */
    static final float GAP_ROUND_DP = 4f;
    /** Children shorter than this are glue (divider stubs), not rows. */
    static final float STUB_MAX_DP = 8f;

    // ---- beta25 defaults, now also the default values in GlassConfig ----
    static final float DEFAULT_CORNER_RADIUS_DP = 18f;
    static final float DEFAULT_H_INSET_DP = 14f;
    static final float DEFAULT_V_INSET_DP = 4f;
    static final float DEFAULT_BORDER_DP = 1f;
    static final float DEFAULT_DIVIDER_INSET_DP = 16f;

    /** Live values, so every painter reads one consistent snapshot per frame. */
    static float cornerRadiusDp() {
        return GlassConfig.get().cornerDp();
    }

    static float hInsetDp() {
        return GlassConfig.get().hInsetDp();
    }

    static float vInsetDp() {
        return GlassConfig.get().vInsetDp();
    }

    static float dividerInsetDp() {
        return GlassConfig.get().dividerInsetDp();
    }

    private static final int CARD_RGB_LIGHT = 0xFFFFFF;
    private static final int CARD_RGB_DARK = 0x1B1B1F;

    /** Native Honor blur mask colour: 2% white (light) / 8% black (dark). */
    static final int MASK_LIGHT = 0x05FFFFFF;
    static final int MASK_DARK = 0x14000000;

    /** Card surface alpha drawn over the native blur: 5/255 (light) / 10/255 (dark). */
    static final int LAYER_ALPHA_LIGHT = 5;
    static final int LAYER_ALPHA_DARK = 10;

    /** Eye-care page scrim fill opacity, so body text stays readable over a busy wallpaper. */
    private static final int SCRIM_ALPHA_LIGHT = 158;
    private static final int SCRIM_ALPHA_DARK = 140;

    private static final int STROKE_TOP = 0x59FFFFFF;
    private static final int STROKE_BOTTOM = 0x14000000;
    private static final int DIVIDER_LIGHT = 0x1A000000;
    private static final int DIVIDER_DARK = 0x26FFFFFF;

    private static final Paint sFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint sSheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint sStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint sRefractPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint sDividerPaint = new Paint();

    static {
        sStrokePaint.setStyle(Paint.Style.STROKE);
        sRefractPaint.setStyle(Paint.Style.STROKE);
        sDividerPaint.setStyle(Paint.Style.FILL);
    }

    private GlassStyle() {
    }

    static boolean isDark(Context ctx) {
        try {
            int mode = ctx.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return mode == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    static int cardRgb(Context ctx) {
        return isDark(ctx) ? CARD_RGB_DARK : CARD_RGB_LIGHT;
    }

    static int maskColor(Context ctx) {
        return isDark(ctx) ? MASK_DARK : MASK_LIGHT;
    }

    static int layerAlpha(Context ctx) {
        return isDark(ctx) ? LAYER_ALPHA_DARK : LAYER_ALPHA_LIGHT;
    }

    static float dp(Context ctx, float v) {
        try {
            return v * ctx.getResources().getDisplayMetrics().density;
        } catch (Throwable t) {
            return v;
        }
    }

    /** Light mode is dropped by ~25% because 60% white washes text out. */
    static int blockFillAlpha(boolean dark, int glassAlpha, boolean scrim) {
        if (scrim) {
            return dark ? SCRIM_ALPHA_DARK : SCRIM_ALPHA_LIGHT;
        }
        int ga = clamp(glassAlpha);
        return dark ? ga : (ga * 3) / 4;
    }

    /**
     * Translucent fill + diagonal sheen + optional edge refraction for one share of a group.
     * Rows call this separately from the stroke so the stroke can be clipped at a seam while
     * the fill tiles seamlessly.
     */
    static void drawBlockFill(Canvas c, Path path, float left, float top, float right,
                              float bottom, boolean dark, int fillAlpha, int sheen,
                              float density) {
        GlassConfig cfg = GlassConfig.get();
        int rgb = dark ? CARD_RGB_DARK : CARD_RGB_LIGHT;
        sFillPaint.setShader(null);
        sFillPaint.setColor((clamp(fillAlpha) << 24) | rgb);
        c.drawPath(path, sFillPaint);

        int sh = clamp(sheen);
        if (dark) {
            sh = sh / 2; // dark cards take half the specular, same as the old per-row recipe
        }
        if (sh > 1) {
            sSheenPaint.setShader(new LinearGradient(left, top, right, bottom,
                    (sh << 24) | 0x00FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawPath(path, sSheenPaint);
        }

        // Refraction: a narrow bright band hugging the top of the block, fading down the sides.
        // top/bottom are BLOCK coordinates (same ramp the sheen uses), so each row paints its
        // own slice of one continuous band instead of restarting a highlight per row. Default
        // 0 skips the whole branch - the verified beta25 picture is untouched.
        if (cfg.refraction > 0 && bottom > top) {
            int a = clamp(Math.round(cfg.refraction * 1.2f));
            sRefractPaint.setStrokeWidth(Math.max(1.5f, 2f * density));
            sRefractPaint.setShader(new LinearGradient(left, top, left, bottom,
                    (a << 24) | 0x00FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawPath(path, sRefractPaint);
        }
    }

    /** The single gradient hairline that outlines a block share. */
    static void drawBlockStroke(Canvas c, Path path, float top, float bottom,
                                boolean dark, float density) {
        GlassConfig cfg = GlassConfig.get();
        if (!cfg.strokeEnabled || cfg.strokeAlphaPct <= 0) {
            return;
        }
        float stroke = Math.max(1f, cfg.strokeWidthDp() * density);
        sStrokePaint.setStrokeWidth(stroke);
        sStrokePaint.setShader(new LinearGradient(0f, top, 0f, bottom,
                scaleAlpha(STROKE_TOP, cfg.strokeAlphaPct),
                scaleAlpha(STROKE_BOTTOM, cfg.strokeAlphaPct), Shader.TileMode.CLAMP));
        c.drawPath(path, sStrokePaint);
    }

    /** One hairline between two rows of the same block. */
    static void drawDivider(Canvas c, float x1, float y, float x2, boolean dark) {
        GlassConfig cfg = GlassConfig.get();
        if (!cfg.dividerEnabled) {
            return;
        }
        int color;
        if (cfg.dividerCustom) {
            int a = clamp(Math.round(cfg.dividerAlphaPct * 255f / 100f));
            color = (a << 24) | (cfg.dividerColor & 0xFFFFFF);
        } else {
            color = dark ? DIVIDER_DARK : DIVIDER_LIGHT;
        }
        sDividerPaint.setShader(null);
        sDividerPaint.setColor(color);
        c.drawRect(x1, y, x2, y + cfg.dividerWidthPx(), sDividerPaint);
    }

    private static int scaleAlpha(int argb, int pct) {
        int a = ((argb >>> 24) * pct) / 100;
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
