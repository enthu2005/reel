package com.enthu.reel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/** Fireworks over a dark background: rockets climb, burst into sparks that fall and fade. Draws only while running. */
final class FireworksView extends View {
    private static final class Rocket {
        float x, y, vy;
        float apex;
        int hue;
    }

    private static final class Spark {
        float x, y, px, py, vx, vy, life, decay;
        int color;
    }

    private final Random rnd = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Rocket> rockets = new ArrayList<>();
    private final List<Spark> sparks = new ArrayList<>();
    private boolean running;
    private boolean auto;
    private long last;
    private long nextLaunch;

    FireworksView(Context c) {
        super(c);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Start the show. [autoLaunch] keeps sending up new rockets; otherwise only bursts you ask for. */
    void start(boolean autoLaunch) {
        auto = autoLaunch;
        running = true;
        last = System.nanoTime();
        nextLaunch = 0;
        postInvalidateOnAnimation();
    }

    void autoLaunch(boolean on) {
        auto = on;
    }

    void stop() {
        running = false;
    }

    /** A rocket from the bottom at [fx] (0..1 across) that bursts [fy] (0..1 down) of the way up the view. */
    void launch(float fx, float fy) {
        Rocket r = new Rocket();
        r.x = getWidth() * fx;
        r.y = getHeight();
        r.apex = getHeight() * fy;
        r.vy = -getHeight() * 0.0125f;
        r.hue = rnd.nextInt(360);
        rockets.add(r);
    }

    /** An instant burst at a spot (fractions of the view). */
    void burst(float fx, float fy, int count) {
        explode(getWidth() * fx, getHeight() * fy, rnd.nextInt(360), count);
    }

    private void explode(float x, float y, int hue, int count) {
        boolean ring = rnd.nextInt(3) == 0;
        for (int i = 0; i < count; i++) {
            Spark s = new Spark();
            s.x = s.px = x;
            s.y = s.py = y;
            double a = rnd.nextDouble() * Math.PI * 2;
            float v = ring ? getHeight() * 0.0085f : getHeight() * (0.002f + rnd.nextFloat() * 0.0085f);
            s.vx = (float) Math.cos(a) * v;
            s.vy = (float) Math.sin(a) * v;
            s.life = 1f;
            s.decay = 0.006f + rnd.nextFloat() * 0.008f;
            s.color = Color.HSVToColor(new float[]{(hue + rnd.nextInt(50)) % 360, 0.55f + rnd.nextFloat() * 0.4f, 1f});
            sparks.add(s);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!running) return;
        long now = System.nanoTime();
        float dt = Math.min(3f, (now - last) / 16_666_666f); // 1.0 = one 60 Hz frame
        last = now;
        int w = getWidth(), h = getHeight();
        if (auto && System.currentTimeMillis() >= nextLaunch && w > 0) {
            launch(0.1f + rnd.nextFloat() * 0.8f, 0.12f + rnd.nextFloat() * 0.38f);
            nextLaunch = System.currentTimeMillis() + 250 + rnd.nextInt(650);
        }
        float g = h * 0.00009f;
        for (Iterator<Rocket> it = rockets.iterator(); it.hasNext(); ) {
            Rocket r = it.next();
            r.y += r.vy * dt;
            r.vy += g * 0.4f * dt;
            paint.setColor(Color.WHITE);
            paint.setAlpha(230);
            c.drawCircle(r.x, r.y, h * 0.004f, paint);
            if (r.y <= r.apex || r.vy >= 0) {
                explode(r.x, r.y, r.hue, 90 + rnd.nextInt(60));
                it.remove();
            }
        }
        paint.setStrokeWidth(Math.max(2f, h * 0.0035f));
        for (Iterator<Spark> it = sparks.iterator(); it.hasNext(); ) {
            Spark s = it.next();
            s.px = s.x;
            s.py = s.y;
            s.x += s.vx * dt;
            s.y += s.vy * dt;
            s.vy += g * dt;
            s.vx *= 0.992f;
            s.life -= s.decay * dt;
            if (s.life <= 0) {
                it.remove();
                continue;
            }
            paint.setColor(s.color);
            paint.setAlpha((int) (255 * Math.min(1f, s.life * 1.4f)));
            c.drawLine(s.px, s.py, s.x, s.y, paint);
        }
        postInvalidateOnAnimation();
    }
}
