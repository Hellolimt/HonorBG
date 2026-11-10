package com.honor.hb;

import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a flat row list into the "system groups" Honor itself uses.
 *
 * Why geometry and not resource-ids: MagicOS ships a different view tree on almost every
 * settings page, and its private ids ({@code androidhnext:*}) change between versions. What
 * never changes is the geometry of a grouped list: rows inside a group sit edge to edge, while
 * groups are separated by a visible gap. So the rule is:
 *
 *   block member  = visible child, spans >= 55% of the container width,
 *                   44dp <= height <= 62% of the container height;
 *   group border  = a gap bigger than 4dp, or any child that is not a block member
 *                   (section label, hero, thumbnail strip...);
 *   glue          = zero-height children (dividers, item-decoration stubs) never break a
 *                   group and are skipped when looking for a row's neighbour.
 *
 * Painting lives in {@link RowGlass}: every row draws its own share of the block, which is
 * what keeps the glass glued to the row while a RecyclerView scrolls. This class only answers
 * "is this row part of a group" and "where do groups start and end".
 */
final class GlassBlocks {

    /** One merged group: rows [first, first+count) of the container (diagnostics only). */
    static final class Block {
        int first;
        int count;
        int top;
        int bottom;

        int last() {
            return first + count - 1;
        }
    }

    private GlassBlocks() {
    }

    /** The single membership rule, shared by the grouping pass and by {@link RowGlass}. */
    static boolean isMember(ViewGroup container, View v, float density) {
        if (container == null || v == null || v.getVisibility() != View.VISIBLE) {
            return false;
        }
        int w = v.getWidth();
        int h = v.getHeight();
        if (w <= 0 || h <= 0) {
            return false;
        }
        int cw = container.getWidth();
        int ch = container.getHeight();
        if (cw < 16 || ch < 16) {
            return false;
        }
        return w >= cw * GlassStyle.MIN_ROW_WIDTH_FRAC
                && h >= GlassStyle.MIN_ROW_HEIGHT_DP * density
                && h <= ch * GlassStyle.MAX_ROW_HEIGHT_FRAC;
    }

    /**
     * The next sibling of {@code v} that actually occupies space.
     *
     * <p>Honor puts zero-height and hairline stubs between rows (an empty LinearLayout, a
     * 1272x0 spacer view, a 1-2px divider View). Treating one of those as "the neighbour is not
     * a block member" ends the block in the middle of a group: the row above rounds its bottom,
     * the row below rounds its top, and what should be one block shows up as two - the "blocks
     * got split apart / corners look wrong" symptom. So anything thinner than
     * {@link GlassStyle#STUB_MAX_DP} is skipped as glue between rows; the real 18dp group gap
     * (a 63px spacer view) is far taller and still ends the group.
     */
    static View neighbour(ViewGroup container, int from, int dir, float density) {
        int minHeight = Math.round(GlassStyle.STUB_MAX_DP * density);
        int step = dir < 0 ? -1 : 1;
        int n = container.getChildCount();
        for (int i = from; i >= 0 && i < n; i += step) {
            View c = container.getChildAt(i);
            if (c == null || c.getHeight() >= minHeight) {
                return c;
            }
        }
        return null;
    }

    /**
     * Vertical extent of the group that contains child {@code idx}, in container coordinates.
     *
     * <p>{@link RowGlass} paints one share per row, so the block-wide gradients (the specular
     * sheen and the bright-top/dark-bottom hairline) have to be evaluated over the whole block.
     * Computing them against the row's own top/bottom instead restarted the hairline ramp in
     * every row: each row drew a bright edge at its top and a dark one at its bottom, which
     * reads as a ladder of highlighted lines along the block's left and right edges - the
     * "the blocks look misaligned" symptom.
     *
     * @param out receives {top, bottom}; untouched when the row is not part of a block
     */
    static void blockExtent(ViewGroup container, int idx, float density, int[] out) {
        View v = container.getChildAt(idx);
        if (v == null) {
            return;
        }
        int gapLimit = Math.round(GlassStyle.GAP_ROUND_DP * density);
        int stubMax = Math.round(GlassStyle.STUB_MAX_DP * density);
        int top = v.getTop();
        int bottom = v.getBottom();
        for (int i = idx - 1; i >= 0; i--) {
            View c = container.getChildAt(i);
            if (c == null || c.getHeight() < stubMax) {
                continue;
            }
            if (!isMember(container, c, density) || top - c.getBottom() > gapLimit) {
                break;
            }
            top = c.getTop();
        }
        int n = container.getChildCount();
        for (int i = idx + 1; i < n; i++) {
            View c = container.getChildAt(i);
            if (c == null || c.getHeight() < stubMax) {
                continue;
            }
            if (!isMember(container, c, density) || c.getTop() - bottom > gapLimit) {
                break;
            }
            bottom = c.getBottom();
        }
        out[0] = top;
        out[1] = bottom;
    }

    /** @return the merged groups, in child order (never null, possibly empty). */
    static List<Block> compute(ViewGroup container, float density) {
        List<Block> out = new ArrayList<>();
        int n = container.getChildCount();
        if (n < 1 || container.getWidth() < 16 || container.getHeight() < 16) {
            return out;
        }
        int gapLimit = Math.round(GlassStyle.GAP_ROUND_DP * density);
        int stubMax = Math.round(GlassStyle.STUB_MAX_DP * density);

        Block cur = null;
        for (int i = 0; i < n; i++) {
            View v = container.getChildAt(i);
            if (v != null && v.getHeight() < stubMax) {
                continue; // hairline / zero-height stub: neither a member nor a group border
            }
            if (!isMember(container, v, density)) {
                cur = null; // label / hero / thumbnail strip ends the group
                continue;
            }
            int top = v.getTop();
            int bottom = v.getBottom();
            boolean sameGroup = cur != null && (top - cur.bottom) <= gapLimit;
            if (!sameGroup) {
                cur = new Block();
                cur.first = i;
                cur.count = 1;
                cur.top = top;
                cur.bottom = bottom;
                out.add(cur);
            } else {
                cur.count++;
                cur.bottom = bottom;
            }
        }
        return out;
    }
}
