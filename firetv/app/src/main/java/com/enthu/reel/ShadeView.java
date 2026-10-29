package com.enthu.reel;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/**
 * A big dark gradient drawn as a small picture stretched to fit. Blending a screen-sized gradient on every
 * frame is slow on the Fire TV's graphics chip (especially over playing video); one scaled picture is cheap,
 * and fading it in and out stays cheap too.
 */
final class ShadeView extends View {
    private static final int SHRINK = 5;
    private final GradientDrawable shade;
    private final Canvas small = new Canvas();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();
    private Bitmap bmp;
    private boolean dirty = true;

    ShadeView(Context c, GradientDrawable shade) {
        super(c);
        this.shade = shade;
        shade.setCallback(this);
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return who == shade || super.verifyDrawable(who);
    }

    @Override
    public void invalidateDrawable(Drawable who) {
        dirty = true;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        int bw = Math.max(1, w / SHRINK);
        int bh = Math.max(1, h / SHRINK);
        bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        small.setBitmap(bmp);
        shade.setBounds(0, 0, bw, bh);
        dirty = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bmp == null) return;
        if (dirty) {
            bmp.eraseColor(0);
            shade.draw(small);
            dirty = false;
        }
        dst.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(bmp, null, dst, paint);
    }
}
