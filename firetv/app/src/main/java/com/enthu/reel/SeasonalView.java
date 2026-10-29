package com.enthu.reel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.Random;

/**
 * A seasonal look drawn over the Home screen: snow for Christmas, rising embers for Halloween. It never takes
 * focus or clicks, and it stops drawing when the screen is not showing.
 */
final class SeasonalView extends View {

    static final int NONE = 0;
    static final int CHRISTMAS = 1;
    static final int HALLOWEEN = 2;

    static int theme() {
        return Plex.prefs() == null ? NONE : Plex.prefs().getInt("theme", NONE);
    }

    static void setTheme(int t) {
        Plex.prefs().edit().putInt("theme", t).apply();
    }

    static String name(int t) {
        return t == CHRISTMAS ? "Christmas" : t == HALLOWEEN ? "Halloween" : "None";
    }

    private static final int N = 60;
    private final int kind;
    private final Random rnd = new Random();
    private final float[] x = new float[N];
    private final float[] y = new float[N];
    private final float[] r = new float[N];
    private final float[] speed = new float[N];
    private final float[] sway = new float[N];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long last;

    SeasonalView(Context c, int kind) {
        super(c);
        this.kind = kind;
        setFocusable(false);
        setClickable(false);
        setWillNotDraw(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        for (int i = 0; i < N; i++) spawn(i, w, h, true);
    }

    private void spawn(int i, int w, int h, boolean anywhere) {
        x[i] = rnd.nextFloat() * w;
        r[i] = h * (kind == CHRISTMAS ? 0.003f + rnd.nextFloat() * 0.006f : 0.002f + rnd.nextFloat() * 0.004f);
        speed[i] = h * (0.04f + rnd.nextFloat() * 0.07f); // px per second
        sway[i] = rnd.nextFloat() * 6.28f;
        y[i] = anywhere ? rnd.nextFloat() * h : (kind == CHRISTMAS ? -r[i] : h + r[i]);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        long now = android.os.SystemClock.uptimeMillis();
        float dt = last == 0 ? 0.016f : Math.min(0.1f, (now - last) / 1000f);
        last = now;
        for (int i = 0; i < N; i++) {
            sway[i] += dt * 1.2f;
            x[i] += (float) Math.sin(sway[i]) * h * 0.02f * dt;
            if (kind == CHRISTMAS) {
                y[i] += speed[i] * dt;
                paint.setColor(0xB3FFFFFF);
                if (y[i] > h + r[i]) spawn(i, w, h, false);
            } else {
                y[i] -= speed[i] * dt * 1.4f;
                float fade = Math.max(0f, Math.min(1f, y[i] / h));
                paint.setColor(((int) (200 * fade) << 24) | (i % 3 == 0 ? 0xFF7A1A : 0xFFB02E));
                if (y[i] < -r[i]) spawn(i, w, h, false);
            }
            canvas.drawCircle(x[i], y[i], r[i], paint);
        }
        postInvalidateOnAnimation();
    }
}
