package com.honor.hb;

import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Glass for a single card that is not a row of a list.
 *
 * <p>{@link RowGlass} splits a block across the rows of a merged group, which only applies inside
 * a list. Some MagicOS pages also put a free-standing card above the list - the switch bar of
 * 蓝牙 / 荣耀互联 ({@code HnListCardLayout}: the title plus its toggle) - and that card stays the
 * stock opaque white while every group below it is glass, which reads as "this page is not
 * styled like the others". This drawable paints one rounded panel on the view's own bounds and
 * exactly the block geometry ({@link GlassStyle#hInsetDp()}, {@link GlassStyle#vInsetDp()},
 * {@link GlassStyle#cornerRadiusDp()}) so the bar lines up with the blocks under it.
 *
 * <p>The view's padding is preserved by the installer: the native card carries its inset as
 * drawable padding, so replacing the background without restoring the padding would move the
 * title and the toggle.
 */
final class SurfaceGlass extends Drawable {

    private static final Path sPath = new Path();
    private static final float[] sRadii = new float[8];

    private final View card;
    private final boolean dark;
    private final float density;

    SurfaceGlass(View card) {
        this.card = card;
        this.density = card.getResources().getDisplayMetrics().density;
        this.dark = GlassStyle.isDark(card.getContext());
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0) {
            return;
        }
        float inset = GlassStyle.hInsetDp() * density;
        float vInset = GlassStyle.vInsetDp() * density;
        float left = inset;
        float right = w - inset;
        if (right - left < 8f) {
            return;
        }
        float top = Math.min(vInset, h / 4f);
        float bottom = Math.max(h - vInset, h * 0.75f);
        float radius = Math.min(GlassStyle.cornerRadiusDp() * density, (right - left) / 2f);
        radius = Math.min(radius, (bottom - top) / 2f);

        int save = canvas.save();
        try {
            canvas.clipRect(b);
            if (b.left != 0 || b.top != 0) {
                canvas.translate(b.left, b.top);
            }
            sRadii[0] = radius;
            sRadii[1] = radius;
            sRadii[2] = radius;
            sRadii[3] = radius;
            sRadii[4] = radius;
            sRadii[5] = radius;
            sRadii[6] = radius;
            sRadii[7] = radius;
            sPath.reset();
            sPath.addRoundRect(left, top, right, bottom, sRadii, Path.Direction.CW);

            GlassConfig cfg = GlassConfig.get();
            int alpha = GlassStyle.blockFillAlpha(dark, cfg.fillAlpha, false);
            GlassStyle.drawBlockFill(canvas, sPath, left, top, right, bottom, dark, alpha,
                    cfg.sheen, density);
            GlassStyle.drawBlockStroke(canvas, sPath, top, bottom, dark, density);
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
