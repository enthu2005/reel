package com.enthu.reel;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;

import java.util.List;

/**
 * "Who's watching?": the people in your Plex Home as big round pictures, Netflix style. Shown when
 * Reel starts unless Automatic sign in is on in Settings, and from Settings > Profile.
 */
public class ProfilesActivity extends Activity {

    private static final int[] COLOURS = {0xFF5B8DEF, 0xFFE5A00D, 0xFFE0566B, 0xFF4CB782, 0xFF9B6BDE, 0xFF3FB6C9};

    private int W;
    private int H;
    private boolean startup;
    private boolean closed;
    private FrameLayout root;
    private LinearLayout row;
    private TextView heading;
    private TextView message;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Plex.init(this);
        SoundFx.init(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;
        startup = getIntent().getBooleanExtra("startup", false);

        root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0B0D12, 0xFF1B1F2E, 0xFF2A2140}));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        heading = new TextView(this);
        heading.setText("Who's watching?");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.06f);
        heading.setTypeface(UiUtil.font(UiUtil.BOLD));
        heading.setGravity(Gravity.CENTER);
        heading.setVisibility(View.INVISIBLE);
        col.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        int vp = (int) (H * 0.04f);
        row.setPadding(0, vp, 0, vp);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setFillViewport(true);
        hs.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = (int) (H * 0.05f);
        col.addView(hs, hlp);

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        message = new TextView(this);
        message.setText("Loading…");
        message.setTextColor(0x99FFFFFF);
        message.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.032f);
        message.setGravity(Gravity.CENTER);
        // Status text sits low on the screen, clear of the profile pictures
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        mlp.bottomMargin = (int) (H * 0.12f);
        root.addView(message, mlp);

        setContentView(root);
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closed = true;
    }

    private void goHome() {
        Intent i = new Intent(this, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void load() {
        Plex.async(Plex::homeUsers, new Plex.Callback<List<Plex.Profile>>() {
            @Override
            public void ok(List<Plex.Profile> list) {
                if (closed) return;
                if (startup && list.size() <= 1) {
                    goHome(); // only one person: nothing to choose
                    return;
                }
                show(list);
            }

            @Override
            public void fail(Exception e) {
                if (closed) return;
                if (startup) {
                    goHome(); // can't reach plex.tv: carry on as the signed-in profile
                    return;
                }
                message.setText("Couldn't load the profiles (" + e.getMessage() + ").\nPress Back to return.");
            }
        });
    }

    private void show(List<Plex.Profile> list) {
        message.setVisibility(View.GONE);
        heading.setVisibility(View.VISIBLE);
        row.removeAllViews();
        View first = null;
        int i = 0;
        for (final Plex.Profile p : list) {
            View tile = tile(p, i++);
            row.addView(tile);
            if (first == null) first = tile;
            // start on the profile that is in use
            if (Plex.profileName != null && Plex.profileName.equals(p.title)) first = tile;
        }
        if (first != null) first.requestFocus();
    }

    private View tile(final Plex.Profile p, int index) {
        final int size = (int) (H * 0.22f);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setFocusable(true);
        t.setClickable(true);
        int mp = (int) (W * 0.016f);
        t.setPadding(mp, mp, mp, mp);

        final FrameLayout face = new FrameLayout(this);
        final GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(COLOURS[index % COLOURS.length]);
        face.setBackground(ring);
        TextView letter = new TextView(this);
        letter.setText(p.title.isEmpty() ? "?" : p.title.substring(0, 1).toUpperCase());
        letter.setTextColor(Color.WHITE);
        letter.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.42f);
        letter.setTypeface(UiUtil.font(UiUtil.BOLD));
        letter.setGravity(Gravity.CENTER);
        face.addView(letter, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (!p.thumb.isEmpty()) {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            face.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Glide.with(this).load(p.thumb).circleCrop().into(img);
        }
        t.addView(face, new LinearLayout.LayoutParams(size, size));

        final TextView name = new TextView(this);
        name.setText(p.title + (p.hasPin ? "  🔒" : ""));
        name.setTextColor(0xFFB0B4C0);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.034f);
        name.setTypeface(UiUtil.font(UiUtil.MEDIUM));
        name.setSingleLine(true);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                size + mp * 2, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = (int) (H * 0.02f);
        t.addView(name, nlp);

        t.setOnFocusChangeListener((v, f) -> {
            float s = f ? 1.12f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(150).start();
            name.setTextColor(f ? Color.WHITE : 0xFFB0B4C0);
            ring.setStroke(f ? Math.max(4, (int) (H * 0.006f)) : 0, Color.WHITE);
            if (f) SoundFx.move();
        });
        t.setOnClickListener(v -> {
            SoundFx.click();
            choose(p);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (int) (W * 0.012f);
        lp.rightMargin = (int) (W * 0.012f);
        t.setLayoutParams(lp);
        return t;
    }

    private void choose(final Plex.Profile p) {
        if (busy) return;
        if (p.hasPin) askPin(p);
        else switchTo(p, null);
    }

    private void switchTo(final Plex.Profile p, final String pin) {
        if (busy) return;
        busy = true;
        message.setText("Switching to " + p.title + "…");
        message.setVisibility(View.VISIBLE);
        Plex.async(() -> {
            String tok = Plex.switchProfile(p, pin);
            Plex.useProfile(tok, p);
            Plex.findServer();
            return true;
        }, new Plex.Callback<Boolean>() {
            @Override
            public void ok(Boolean r) {
                if (closed) return;
                goHome();
            }

            @Override
            public void fail(Exception e) {
                busy = false;
                if (closed) return;
                message.setVisibility(View.GONE);
                boolean wrong = e.getMessage() != null && (e.getMessage().contains("401") || e.getMessage().contains("403"));
                Toast.makeText(ProfilesActivity.this,
                        wrong ? "That PIN didn't work" : "Couldn't switch profile: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
                if (wrong && p.hasPin) askPin(p);
            }
        });
    }

    /** A number pad for the profile's 4-digit PIN. */
    private void askPin(final Plex.Profile p) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        final StringBuilder pin = new StringBuilder();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        int pd = (int) (H * 0.035f);
        final int panelW = (int) (W * 0.30f);
        panel.setLayoutParams(new ViewGroup.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.setPadding(pd, pd, pd, pd);
        panel.setBackground(UiUtil.rounded(UiUtil.CARD, H * 0.03f));

        TextView title = new TextView(this);
        title.setText("Enter PIN");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.036f);
        title.setTypeface(UiUtil.font(UiUtil.SEMIBOLD));
        title.setGravity(Gravity.CENTER);
        panel.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView who = new TextView(this);
        who.setText(p.title);
        who.setTextColor(0xFFB0B4C0);
        who.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.026f);
        who.setSingleLine(true);
        who.setEllipsize(android.text.TextUtils.TruncateAt.END);
        who.setGravity(Gravity.CENTER);
        panel.addView(who, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView dots = new TextView(this);
        dots.setTextColor(Color.WHITE);
        dots.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.05f);
        dots.setLetterSpacing(0.15f);
        dots.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.topMargin = (int) (H * 0.015f);
        dl.bottomMargin = (int) (H * 0.015f);
        panel.addView(dots, dl);
        final Runnable paint = () -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(i < pin.length() ? "●" : "○");
            dots.setText(sb.toString());
        };
        paint.run();

        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "Cancel", "0", "Delete"};
        int kw = (panelW - pd * 2 - 24) / 3;
        int kh = (int) (H * 0.085f);
        View firstKey = null;
        for (int r = 0; r < 4; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER);
            for (int c = 0; c < 3; c++) {
                final String k = keys[r * 3 + c];
                final TextView b = new TextView(this);
                b.setText(k);
                b.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * (k.length() > 1 ? 0.026f : 0.04f));
                b.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
                b.setTextColor(Color.WHITE);
                b.setGravity(Gravity.CENTER);
                b.setFocusable(true);
                b.setClickable(true);
                final float rad = H * 0.02f;
                b.setBackground(UiUtil.rounded(0x1AFFFFFF, rad));
                b.setOnFocusChangeListener((v, f) -> {
                    b.setBackground(UiUtil.rounded(f ? 0xFFFFFFFF : 0x1AFFFFFF, rad));
                    b.setTextColor(f ? 0xFF000000 : Color.WHITE);
                    if (f) SoundFx.move();
                });
                b.setOnClickListener(v -> {
                    SoundFx.click();
                    if (k.equals("Cancel")) {
                        d.dismiss();
                    } else if (k.equals("Delete")) {
                        if (pin.length() > 0) pin.setLength(pin.length() - 1);
                        paint.run();
                    } else {
                        if (pin.length() < 4) pin.append(k);
                        paint.run();
                        if (pin.length() == 4) {
                            d.dismiss();
                            switchTo(p, pin.toString());
                        }
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(kw, kh);
                lp.setMargins(4, 4, 4, 4);
                line.addView(b, lp);
                if (k.equals("5")) firstKey = b;
            }
            panel.addView(line);
        }
        d.setContentView(panel);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xCC000000));
            d.getWindow().setLayout(panelW, ViewGroup.LayoutParams.WRAP_CONTENT);
            d.getWindow().setGravity(Gravity.CENTER);
        }
        d.show();
        if (firstKey != null) firstKey.requestFocus();
    }
}
