package com.honor.hb;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

/**
 * Central "liquid glass" style, shared by the renderer and the group-block painter.
 *
 * Recipe (unchanged from 1.4.5-beta2, only the granularity changed):
 *  - translucent frosted base (light: white; dark: near-black surface),
 *  - one single 1dp gradient hairline (bright top edge, dark bottom edge),
 *  - diagonal top-left specular sheen,
 *  - 18dp rounded corners, 14dp side inset, and the ROM's own group gap between blocks.
 *
 * Since the group merge (task 2) a card is no longer built per row: one rounded block
 * covers a whole system group and is painted straight onto the RecyclerView canvas
 * ({@link GlassBlocks}). That keeps exactly one fill, one stroke and one sheen per group,
 * so no row can ever end up with a double stroke or a nested card.
 */
final class GlassStyle {

    /** Tuned on a 3.0x-density device (1272px wide): 18dp ~= 54px radius. */
    static final float CORNER_RADIUS_DP = 18f;
    /**
     * 14dp horizontal inset per side - the inset MagicOS itself gives a group card
     * (measured: the native card's edge sits at 49px on a 1272px-wide, 3.5x screen, i.e. 14dp).
     * The value is a <i>minimum</i>: a list whose rows are already inset further (About phone's
     * info grid starts at 63px) pushes the whole column in, see
     * {@code HonorRenderer.updateColumn}.
     */
    static final float H_INSET_DP = 14f;
    /**
     * 4dp vertical inset per side. The native card is not flush with the group's rows either:
     * measured on a 3.5x screen it starts 12-14px below the first row and ends 18-19px above
     * the last one, on top of the 63px the list already leaves between two groups.
     */
    static final float V_INSET_DP = 4f;
    /** 1dp single stroke. */
    static final float BORDER_DP = 1f;
    /** In-block row divider, inset from the block edge. */
    static final float DIVIDER_INSET_DP = 16f;

    static final float MIN_ROW_HEIGHT_DP = 44f;
    /** A group never takes more than this share of the viewport (keeps heroes out). */
    static final float MAX_ROW_HEIGHT_FRAC = 0.62f;
    /** A row must span at least this share of the container width. */
    static final float MIN_ROW_WIDTH_FRAC = 0.55f;
    /** Hard floor for "these two rows belong to the same group" (adaptive upper bound below). */
    static final float GROUP_GAP_DP = 20f;
    /** Sub-pixel/rounding tolerance: a gap this small still means "same group". */
    static final float GAP_ROUND_DP = 4f;
    /**
     * A child shorter than this is glue, not a row: zero-height layout stubs and 1-2px divider
     * views. They must not be mistaken for a group border, or one system group gets painted as
     * two blocks with a seam in the middle.
     */
    static final float STUB_MAX_DP = 8f;

    private static final int CARD_RGB_LIGHT = 0xFFFFFF;
    private static final int CARD_RGB_DARK = 0x1B1B1F;

    /** Native Honor blur mask colour: 2% white (light) / 8% black (dark). */
    static final int MASK_LIGHT = 0x05FFFFFF;
    static final int MASK_DARK = 0x14000000;

    /** Card surface alpha drawn over the native blur: 5/255 (light) / 10/255 (dark). */
    static final int LAYER_ALPHA_LIGHT = 5;
    static final int LAYER_ALPHA_DARK = 10;

    /** Eye-care page scrim fill opacity: ~62% white (light) / ~55% black (dark), so body
     *  text stays readable over a busy wallpaper. */
    private static final int SCRIM_ALPHA_LIGHT = 158; // 0x9E
    private static final int SCRIM_ALPHA_DARK = 140;  // 0x8C

    private static final int STROKE_TOP = 0x59FFFFFF;
    private static final int STROKE_BOTTOM = 0x14000000;
    private static final int DIVIDER_LIGHT = 0x1A000000;
    private static final int DIVIDER_DARK = 0x26FFFFFF;

    private static final Paint sFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint sSheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint sStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint sDividerPaint = new Paint();

    static {
        sStrokePaint.setStyle(Paint.Style.STROKE);
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

    /**
     * Fill alpha of a block. Light mode is dropped by ~25% (as in the previous per-row card)
     * because 60% white washes text out; the eye-care page keeps a high, fixed scrim so body
     * text stays readable over a busy photo.
     */
    static int blockFillAlpha(boolean dark, int glassAlpha, boolean scrim) {
        if (scrim) {
            return dark ? SCRIM_ALPHA_DARK : SCRIM_ALPHA_LIGHT;
        }
        int ga = clamp(glassAlpha);
        return dark ? ga : (ga * 3) / 4;
    }

    static int dividerColor(boolean dark) {
        return dark ? DIVIDER_DARK : DIVIDER_LIGHT;
    }

    /**
     * Translucent fill + diagonal sheen for one share of a group. Rows call this separately
     * from the stroke so the stroke can be clipped at a seam while the fill tiles seamlessly.
     */
    static void drawBlockFill(Canvas c, Path path, float left, float top, float right,
                              float bottom, boolean dark, int fillAlpha, int sheen) {
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
    }

    /** The single 1dp gradient hairline that outlines a block share. */
    static void drawBlockStroke(Canvas c, Path path, float top, float bottom,
                                boolean dark, float density) {
        float stroke = Math.max(1f, BORDER_DP * density);
        sStrokePaint.setStrokeWidth(stroke);
        sStrokePaint.setShader(new LinearGradient(0f, top, 0f, bottom,
                STROKE_TOP, STROKE_BOTTOM, Shader.TileMode.CLAMP));
        c.drawPath(path, sStrokePaint);
    }

    /** One hairline between two rows of the same block (1 physical pixel, like the system's). */
    static void drawDivider(Canvas c, float x1, float y, float x2, boolean dark) {
        sDividerPaint.setShader(null);
        sDividerPaint.setColor(dividerColor(dark));
        c.drawRect(x1, y, x2, y + 1f, sDividerPaint);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
