package com.enthu.reel;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Apple TV style screensaver: artwork from your libraries drifting slowly across the screen,
 * with the title and the time. Any button closes it.
 */
public class ScreensaverActivity extends Activity {
    private static final long SLIDE_MS = 12_000;
    private static final long MAX_AWAKE_MS = 2 * 60 * 60_000L; // let the TV sleep after two hours

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Plex.Item> art = new ArrayList<>();
    private final Random rnd = new Random();
    private ImageView a;
    private ImageView b;
    private ImageView front;
    private TextView title;
    private TextView sub;
    private int index = 0;
    private int W;
    private int H;

    private final Runnable next = new Runnable() {
        @Override
        public void run() {
            show();
            handler.postDelayed(this, SLIDE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Plex.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.postDelayed(() -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON),
                MAX_AWAKE_MS);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        a = img();
        b = img();
        root.addView(b);
        root.addView(a);
        front = a;

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xB3000000, 0x00000000}));
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (H * 0.4f), Gravity.BOTTOM));

        LinearLayout cap = new LinearLayout(this);
        cap.setOrientation(LinearLayout.VERTICAL);
        title = new TextView(this);
        title.setTextColor(0xF2FFFFFF);
        title.setTypeface(UiUtil.font(UiUtil.SEMIBOLD));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.034f);
        title.setShadowLayer(12, 0, 2, 0x99000000);
        cap.addView(title);
        sub = new TextView(this);
        sub.setTextColor(UiUtil.TEXT2);
        sub.setTypeface(UiUtil.font(UiUtil.MEDIUM));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.022f);
        cap.addView(sub);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        clp.leftMargin = (int) (W * 0.05f);
        clp.bottomMargin = (int) (H * 0.07f);
        root.addView(cap, clp);

        TextClock clock = new TextClock(this);
        clock.setTextColor(0xE6FFFFFF);
        clock.setTypeface(UiUtil.font(UiUtil.MEDIUM));
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.05f);
        clock.setShadowLayer(12, 0, 2, 0x99000000);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        tlp.rightMargin = (int) (W * 0.05f);
        tlp.bottomMargin = (int) (H * 0.07f);
        root.addView(clock, tlp);

        setContentView(root);
        load();
    }

    private ImageView img() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAlpha(0f);
        v.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return v;
    }

    private void load() {
        Plex.async(() -> {
            List<Plex.Item> out = new ArrayList<>();
            // Only the libraries chosen in Settings; until then, just the movie libraries
            java.util.Set<String> chosen = Plex.libraryChoice("screensaverLibraries");
            for (Plex.Section s : Plex.sections()) {
                if (chosen == null ? !s.type.equals("movie")
                        : !chosen.contains(s.key) || (!s.type.equals("movie") && !s.type.equals("show"))) continue;
                for (Plex.Item it : Plex.items(Plex.paged("/library/sections/" + s.key + "/all?sort=random", 40))) {
                    if (it.art != null) out.add(it);
                }
            }
            Collections.shuffle(out, rnd);
            return out;
        }, new Plex.Callback<List<Plex.Item>>() {
            @Override
            public void ok(List<Plex.Item> list) {
                if (isDestroyed()) return;
                art.addAll(list);
                if (art.isEmpty()) {
                    finish();
                    return;
                }
                handler.post(next);
            }

            @Override
            public void fail(Exception e) {
                if (!isDestroyed()) finish();
            }
        });
    }

    private void show() {
        if (art.isEmpty()) return;
        final Plex.Item it = art.get(index++ % art.size());
        final ImageView in = front == a ? b : a;
        final ImageView out = front;
        Glide.with(this).load(Plex.img(it.art, 1920, 1080)).centerCrop()
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                Target<Drawable> target, boolean isFirstResource) {
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target,
                                                   DataSource dataSource, boolean isFirstResource) {
                        in.post(() -> swap(in, out, it));
                        return false;
                    }
                }).into(in);
    }

    private void swap(ImageView in, ImageView out, Plex.Item it) {
        front = in;
        in.bringToFront();
        // Slow drift: zoom from one random framing to another over the whole slide.
        float s0 = 1.08f + rnd.nextFloat() * 0.08f;
        float s1 = 1.08f + rnd.nextFloat() * 0.08f;
        float dx = W * 0.03f;
        float dy = H * 0.02f;
        in.setScaleX(s0);
        in.setScaleY(s0);
        in.setTranslationX((rnd.nextFloat() * 2 - 1) * dx);
        in.setTranslationY((rnd.nextFloat() * 2 - 1) * dy);
        in.setAlpha(0f);
        in.animate().cancel();
        in.animate().alpha(1f).setDuration(1500).start();
        android.animation.ObjectAnimator drift = android.animation.ObjectAnimator.ofPropertyValuesHolder(in,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, s0, s1),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, s0, s1),
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_X, in.getTranslationX(),
                        (rnd.nextFloat() * 2 - 1) * dx),
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, in.getTranslationY(),
                        (rnd.nextFloat() * 2 - 1) * dy));
        drift.setDuration(SLIDE_MS + 2000);
        drift.setInterpolator(new LinearInterpolator());
        drift.start();
        out.animate().alpha(0f).setStartDelay(1500).setDuration(10).start();
        // Bring the caption views back above the new picture
        ViewGroup root = (ViewGroup) in.getParent();
        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if (v != a && v != b) v.bringToFront();
        }
        title.setText(it.displayTitle());
        sub.setText(it.year > 0 ? String.valueOf(it.year) : "");
        title.setAlpha(0f);
        sub.setAlpha(0f);
        title.animate().alpha(1f).setDuration(900).start();
        sub.animate().alpha(1f).setDuration(900).start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Any button wakes Reel back up (and is not passed on).
        if (event.getAction() == KeyEvent.ACTION_UP) {
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
