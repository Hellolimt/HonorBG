package com.honor.hb;

import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

/**
 * Glass for ONE row of a merged group.
 *
 * <p>Why per row instead of one panel behind the whole list: RecyclerView scrolls by moving
 * the rows' own render nodes, so the list's display list - and with it any background drawable
 * attached to the container - is <b>not</b> re-recorded while scrolling. A container-wide panel
 * therefore stays frozen where it was last recorded, which is exactly the translucent white
 * rectangle that used to be left behind during fast flings. A row's own background is part of
 * the row's render node and moves with it.
 *
 * <p><b>The block column belongs to the container, not to the row.</b> Rows of the same list do
 * not all have the same width: on "About phone" the hero spans the full 1272px while the info
 * grid below it is already inset to 63..1209. Deriving the block edge from each row's own width
 * put those two shares at 49..1223 and 112..1167 - a 63px step in the middle of what is meant to
 * be a single block. So the frame is computed once per container
 * ({@link HonorRenderer#updateColumn}, a 14dp inset widened to the rows' common bounds) and only
 * <i>clamped</i> into the row it is painted on. Every block of a list therefore shares one
 * column, and a row that is already inset collapses the clamp to its own bounds instead of
 * shifting the whole block.
 *
 * <p>Vertically a share covers its row with the native 4dp inset, so a block is exactly as tall
 * as the card the ROM drew for that group and the 63px gap the list leaves between groups
 * survives at the same size.
 *
 * <p>Each row paints only its share of the block: the first row rounds the top, the last one
 * rounds the bottom, every row strokes its own left/right edges, and every non-first row draws
 * the 1px divider at its top. The horizontal seams are pushed outside the row bounds and
 * clipped away, so the merged result is one block with a single continuous hairline - never a
 * second border between rows.
 */
final class RowGlass extends Drawable {

    private static final Path sPath = new Path();
    private static final float[] sRadii = new float[8];
    /** Scratch for {@link GlassBlocks#blockExtent}: draw runs on the UI thread only. */
    private static final int[] sExtent = new int[2];

    private final View row;
    private final boolean scrim;
    private final boolean dark;
    private final float density;
    /** Block geometry resolved by the last strip pass (see {@link #pinBlock}). */
    private boolean mPinned;
    private boolean mFirst;
    private boolean mLast;
    private float mRampTop;
    private float mRampBottom;

    RowGlass(View row, boolean scrim) {
        this.row = row;
        this.scrim = scrim;
        this.density = row.getResources().getDisplayMetrics().density;
        this.dark = GlassStyle.isDark(row.getContext());
    }

    /**
     * Pins this row's share of its block from the strip pass, which sees the WHOLE list at once.
     *
     * <p>Deriving first/last inside {@link #draw} was wrong during a slow scroll: RecyclerView
     * attaches and detaches children frame by frame, so a row that was just added can be drawn
     * while its neighbour is still unmeasured (or while the child order is transient). That row
     * then rounds all four corners while the rows already on screen stay square, and the group
     * shows up as a mix of merged and standalone cards - frozen until another scroll repaints it.
     * The strip pass computes every row of a block from one consistent tree, so pinning its
     * answer makes all shares agree no matter when each one is drawn.
     *
     * @param rampTop    block top expressed relative to this row's top
     * @param rampBottom block bottom expressed relative to this row's top
     */
    void pinBlock(boolean first, boolean last, float rampTop, float rampBottom) {
        // A 1px rounding wobble during a scroll is not a change worth repainting for; using an
        // exact float compare here would invalidate every frame and keep the list redrawing.
        boolean changed = !mPinned || mFirst != first || mLast != last
                || Math.abs(mRampTop - rampTop) > 1.5f
                || Math.abs(mRampBottom - rampBottom) > 1.5f;
        mPinned = true;
        mFirst = first;
        mLast = last;
        mRampTop = rampTop;
        mRampBottom = rampBottom;
        if (changed) {
            invalidateSelf();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        View v = row;
        if (v == null || !(v.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup parent = (ViewGroup) v.getParent();
        Rect b = getBounds();
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0) {
            return;
        }
        int idx = parent.indexOfChild(v);
        if (idx < 0) {
            return;
        }

        // Same grouping rule as GlassBlocks.compute, evaluated from this row outwards only as a
        // fallback: a RowGlass installed directly by the setBackground hook may draw before the
        // first strip pass has pinned it. Once pinned, all rows of a block use one answer.
        int gapLimit = Math.round(GlassStyle.GAP_ROUND_DP * density);
        float stroke = Math.max(1f, GlassStyle.DEFAULT_BORDER_DP * density);

        boolean first;
        boolean last;
        if (mPinned) {
            first = mFirst;
            last = mLast;
        } else {
            View prev = GlassBlocks.neighbour(parent, idx - 1, -1, density);
            first = prev == null
                    || !GlassBlocks.isMember(parent, prev, density)
                    || v.getTop() - prev.getBottom() > gapLimit;
            View next = GlassBlocks.neighbour(parent, idx + 1, 1, density);
            last = next == null
                    || !GlassBlocks.isMember(parent, next, density)
                    || next.getTop() - v.getBottom() > gapLimit;
        }

        // The block column: one frame per container, clamped into this row. A row that is
        // already inset further than the block column (About phone's info grid starts at 63)
        // collapses the clamp to its own bounds instead of pulling the block off the column.
        float inset = GlassStyle.hInsetDp() * density;
        int cw = parent.getWidth();
        int[] col = HonorRenderer.columnFor(parent);
        float colLeft = col != null ? col[0] : inset;
        float colRight = col != null ? col[1] : (cw > 0 ? cw - inset : w - inset);
        float left = Math.max(0f, colLeft - v.getLeft());
        float right = Math.min(w, colRight - v.getLeft());
        if (right - left < 8f) {
            return;
        }

        // The end of a block is always rounded, whoever paints it. Deciding "is this end inside
        // the viewport" and dropping the arc when it is not was wrong twice over: the row's own
        // bounds already clip the share, so a rounded end that is scrolled past the edge is
        // invisible anyway, while the test itself (row.getTop() / container height) is evaluated
        // against values that are not final yet on the first layout pass - the list is measured
        // after the rows are bound, and a page with a bottom bar is shorter than the rows that
        // were laid out for it. That is why the bottom corner of a page's last block used to be
        // made of straight lines until the list was scrolled once.
        float radius = Math.min(GlassStyle.cornerRadiusDp() * density, (right - left) / 2f);
        float rTop = first ? radius : 0f;
        float rBottom = last ? radius : 0f;

        // Seams sit half a stroke outside the bounds and get clipped, which keeps the vertical
        // edges continuous from one row to the next. The block itself keeps the 4dp vertical
        // inset the native card has, so a block is exactly as tall as the card it replaces.
        float vInset = GlassStyle.vInsetDp() * density;
        float top = first ? vInset : -stroke;
        float bottom = last ? h - vInset : h + stroke;
        if (bottom - top < 4f) {
            return;
        }
        if (rTop > 0f) {
            rTop = Math.min(rTop, (bottom - top) / 2f);
        }
        if (rBottom > 0f) {
            rBottom = Math.min(rBottom, (bottom - top) / 2f);
        }

        int save = canvas.save();
        try {
            canvas.clipRect(b);
            if (b.left != 0 || b.top != 0) {
                canvas.translate(b.left, b.top);
            }
            sRadii[0] = rTop;
            sRadii[1] = rTop;
            sRadii[2] = rTop;
            sRadii[3] = rTop;
            sRadii[4] = rBottom;
            sRadii[5] = rBottom;
            sRadii[6] = rBottom;
            sRadii[7] = rBottom;
            sPath.reset();
            sPath.addRoundRect(left, top, right, bottom, sRadii, Path.Direction.CW);

            GlassConfig cfg = GlassConfig.get();
            int alpha = GlassStyle.blockFillAlpha(dark, cfg.fillAlpha, scrim);
            // Sheen and hairline are gradients over the WHOLE block: evaluated in block
            // coordinates, each row paints its own slice of the same ramp. Measuring them
            // against the row's top/bottom instead restarted them in every row, which is what
            // made a single block look like a stack of misaligned strips along its side edges.
            float rampTop;
            float rampBottom;
            if (mPinned) {
                // Relative to this row's top, so scrolling (which offsets every row of the block
                // by the same amount) cannot invalidate the pinned extent.
                rampTop = mRampTop;
                rampBottom = mRampBottom;
            } else {
                int[] ext = sExtent;
                ext[0] = 0;
                ext[1] = 0;
                GlassBlocks.blockExtent(parent, idx, density, ext);
                rampTop = ext[1] > ext[0] ? ext[0] - v.getTop() : top;
                rampBottom = ext[1] > ext[0] ? ext[1] - v.getTop() : bottom;
            }
            if (rampBottom - rampTop < 2f) {
                rampTop = top;
                rampBottom = bottom;
            }
            GlassStyle.drawBlockFill(canvas, sPath, left, rampTop, right, rampBottom, dark, alpha,
                    cfg.sheen, density);
            GlassStyle.drawBlockStroke(canvas, sPath, rampTop, rampBottom, dark, density);

            if (!first) {
                float divInset = GlassStyle.dividerInsetDp() * density;
                GlassStyle.drawDivider(canvas, left + divInset, 0f, right - divInset, dark);
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }
}
