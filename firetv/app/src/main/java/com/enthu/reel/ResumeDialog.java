package com.enthu.reel;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A partly watched title asks: "Resume at 31:20" or "Start over". Back backs out. */
final class ResumeDialog {

    private ResumeDialog() {
    }

    static String clock(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s % 60) : String.format("%d:%02d", m, s % 60);
    }

    /** Plays [key] from where it was left, after asking if it was partly watched. */
    static void play(Activity a, Plex.Item it) {
        if (it.viewOffset <= 0) {
            PlayerActivity.start(a, it.key, 0);
            return;
        }
        String name = it.type.equals("episode") && it.gpTitle != null
                ? it.gpTitle + "  S" + it.parentIndex + " E" + it.index : it.title;
        ask(a, name, it.viewOffset, start -> PlayerActivity.start(a, it.key, start));
    }

    interface Choice {
        void start(long ms);
    }

    static void ask(Context c, String name, final long offset, final Choice then) {
        choose(c, name, "You have already watched part of this", "Resume at " + clock(offset),
                () -> then.start(offset), "Start over", () -> then.start(0), true);
    }

    /** "Continue X from the Living room, at 31:20?" for something you left on another device. */
    static void elsewhere(Context c, String name, String device, long offset, Runnable resume, Runnable notNow) {
        choose(c, name, "Continue from " + (device.isEmpty() ? "another device" : device) + ", at " + clock(offset)
                + "?", "Resume", resume, "Not now", notNow, false);
    }

    private static void choose(Context c, String name, String sub, String label1, final Runnable go1,
                               String label2, final Runnable go2, boolean backDoesNothing) {
        final Dialog d = new Dialog(c, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        int H = c.getResources().getDisplayMetrics().heightPixels;
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(0xE6000000);
        TextView t = new TextView(c);
        t.setText(name);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.045f);
        t.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        col.addView(t, new LinearLayout.LayoutParams((int) (H * 1.2f), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView s = new TextView(c);
        s.setText(sub);
        s.setGravity(Gravity.CENTER);
        s.setTextColor(UiUtil.TEXT2);
        s.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.028f);
        s.setPadding(0, (int) (H * 0.01f), 0, (int) (H * 0.03f));
        col.addView(s);
        View first = button(c, d, H, col, label1, go1);
        button(c, d, H, col, label2, go2);
        d.setOnCancelListener(x -> {
            if (!backDoesNothing) go2.run();
        });
        d.setCancelable(true);
        d.setContentView(col);
        d.show();
        first.requestFocus();
    }

    private static View button(Context c, final Dialog d, int H, LinearLayout col, String label, final Runnable go) {
        final TextView b = new TextView(c);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.032f);
        b.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        b.setTextColor(Color.WHITE);
        b.setBackground(UiUtil.rounded(UiUtil.FILL, H));
        b.setFocusable(true);
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(UiUtil.rounded(has ? 0xFFFFFFFF : UiUtil.FILL, H));
            b.setTextColor(has ? Color.BLACK : Color.WHITE);
            if (has) SoundFx.move();
        });
        b.setOnClickListener(v -> {
            SoundFx.click();
            d.dismiss();
            go.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (H * 0.7f), (int) (H * 0.085f));
        lp.topMargin = (int) (H * 0.015f);
        col.addView(b, lp);
        return b;
    }
}
