package com.enthu.reel;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Party mode on the TV: you pick a library, ten of its films are drawn at random, and guests scan the code to vote on
 * their phones. The leaderboard updates live here and on every phone. "Reveal winner" closes the vote and opens
 * the winning title's page (it is opened, not played).
 */
public class PartyActivity extends ReelActivity {
    private static final int FILMS = 10;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private LinearLayout board;
    private TextView voters;
    private int W;
    private int H;
    private boolean started;
    private boolean revealed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Plex.init(this);
        SoundFx.init(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;
        root = new FrameLayout(this);
        root.setBackgroundColor(UiUtil.BG);
        setContentView(root);
        PhoneRemote.ensure(this);
        message("Looking for your libraries…");
        Plex.async(() -> {
            List<Plex.Section> out = new ArrayList<>();
            for (Plex.Section s : Plex.movieSections(Plex.sections())) out.add(s);
            return out;
        }, new Plex.Callback<List<Plex.Section>>() {
            @Override
            public void ok(List<Plex.Section> libs) {
                if (isDestroyed()) return;
                if (libs.isEmpty()) {
                    message("No movie library to draw films from.");
                    return;
                }
                chooseLibrary(libs);
            }

            @Override
            public void fail(Exception e) {
                if (!isDestroyed()) message("Couldn't reach the server: " + e.getMessage());
            }
        });
    }

    private void message(String text) {
        root.removeAllViews();
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0x99FFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.032f);
        root.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    /** Step 1: which library the ten films come from (before any code is shown). */
    private void chooseLibrary(final List<Plex.Section> libs) {
        root.removeAllViews();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (W * 0.1f), (int) (H * 0.12f), (int) (W * 0.1f), 0);
        TextView head = new TextView(this);
        head.setText("Party mode");
        head.setTextColor(Color.WHITE);
        head.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.06f);
        head.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        col.addView(head);
        TextView sub = new TextView(this);
        sub.setText("Pick the library. " + FILMS + " random films from it go to a vote on everyone's phone.");
        sub.setTextColor(0x99FFFFFF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.028f);
        sub.setPadding(0, (int) (H * 0.01f), 0, (int) (H * 0.04f));
        col.addView(sub);
        View first = null;
        for (final Plex.Section s : libs) {
            TextView b = button(s.title);
            b.setOnClickListener(v -> {
                SoundFx.click();
                begin(s);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    (int) (W * 0.4f), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (H * 0.015f);
            col.addView(b, lp);
            if (first == null) first = b;
        }
        root.addView(col);
        if (first != null) first.requestFocus();
    }

    private TextView button(String label) {
        final TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
        b.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        b.setPadding((int) (W * 0.018f), (int) (H * 0.016f), (int) (W * 0.018f), (int) (H * 0.016f));
        b.setFocusable(true);
        b.setClickable(true);
        final Runnable paint = () -> {
            boolean f = b.hasFocus();
            b.setBackground(UiUtil.rounded(f ? 0xFFFFFFFF : 0x33FFFFFF, H * 0.04f));
            b.setTextColor(f ? 0xFF000000 : Color.WHITE);
        };
        paint.run();
        b.setOnFocusChangeListener((v, f) -> {
            paint.run();
            if (f) SoundFx.move();
        });
        return b;
    }

    /** Step 2: draw the films and open the vote. */
    private void begin(final Plex.Section s) {
        message("Choosing " + FILMS + " films from " + s.title + "…");
        Plex.async(() -> {
            List<Plex.Item> pool = Plex.items(Plex.paged("/library/sections/" + s.key + "/all?sort=random", 60));
            java.util.Collections.shuffle(pool);
            List<Party.Film> out = new ArrayList<>();
            for (Plex.Item it : pool) {
                if (out.size() >= FILMS) break;
                String thumb = it.poster();
                out.add(new Party.Film(it.key, it.displayTitle(), it.year > 0 ? String.valueOf(it.year) : "", thumb));
            }
            return out;
        }, new Plex.Callback<List<Party.Film>>() {
            @Override
            public void ok(List<Party.Film> films) {
                if (isDestroyed()) return;
                if (films.size() < 2) {
                    message("Not enough films in that library.");
                    return;
                }
                started = true;
                Party.start(films);
                showVote();
            }

            @Override
            public void fail(Exception e) {
                if (!isDestroyed()) message("Couldn't load films: " + e.getMessage());
            }
        });
    }

    private void showVote() {
        root.removeAllViews();
        String base = PhoneRemote.baseUrl();
        final String link = base == null ? null : base + "/party?p=" + Party.token();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding((int) (W * 0.06f), (int) (H * 0.08f), (int) (W * 0.05f), 0);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView head = new TextView(this);
        head.setText("Scan to vote");
        head.setTextColor(Color.WHITE);
        head.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.05f);
        head.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        left.addView(head);
        int qs = (int) (H * 0.42f);
        ImageView code = new ImageView(this);
        if (link != null) code.setImageBitmap(PlayerActivity.qr(link, qs));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(qs, qs);
        qlp.topMargin = (int) (H * 0.03f);
        left.addView(code, qlp);
        TextView how = new TextView(this);
        how.setText(link == null ? "No network" : "Same Wi-Fi as this TV");
        how.setTextColor(0x99FFFFFF);
        how.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.024f);
        how.setPadding(0, (int) (H * 0.015f), 0, 0);
        left.addView(how);
        voters = new TextView(this);
        voters.setTextColor(0xFFE8833A);
        voters.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
        voters.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        voters.setPadding(0, (int) (H * 0.02f), 0, (int) (H * 0.03f));
        left.addView(voters);
        TextView reveal = button("Reveal winner");
        reveal.setOnClickListener(v -> {
            SoundFx.click();
            reveal();
        });
        left.addView(reveal, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(left, new LinearLayout.LayoutParams((int) (W * 0.34f), ViewGroup.LayoutParams.WRAP_CONTENT));

        board = new LinearLayout(this);
        board.setOrientation(LinearLayout.VERTICAL);
        row.addView(board, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);
        reveal.requestFocus();
        refresh();
    }

    private final Runnable tick = this::refresh;

    private void refresh() {
        if (isDestroyed() || board == null || revealed) return;
        board.removeAllViews();
        List<Object[]> rank = Party.ranking();
        int max = 1;
        for (Object[] r : rank) max = Math.max(max, (Integer) r[1]);
        int n = 0;
        for (Object[] r : rank) {
            Party.Film f = (Party.Film) r[0];
            int votes = (Integer) r[1];
            n++;
            FrameLayout line = new FrameLayout(this);
            View bar = new View(this);
            bar.setBackground(UiUtil.rounded(0x33E8833A, H * 0.012f));
            line.addView(bar, new FrameLayout.LayoutParams((int) (W * 0.5f * votes / max), ViewGroup.LayoutParams.MATCH_PARENT));
            TextView t = new TextView(this);
            t.setText(n + "   " + f.title + (f.sub.isEmpty() ? "" : "  (" + f.sub + ")"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
            t.setSingleLine();
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setPadding((int) (W * 0.01f), (int) (H * 0.011f), (int) (W * 0.08f), (int) (H * 0.011f));
            line.addView(t);
            TextView c = new TextView(this);
            c.setText(String.valueOf(votes));
            c.setTextColor(0xFFE8833A);
            c.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.034f);
            c.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
            c.setPadding(0, (int) (H * 0.008f), (int) (W * 0.012f), 0);
            line.addView(c, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (H * 0.062f)); // fixed, so the bar can fill the row
            lp.bottomMargin = (int) (H * 0.008f);
            board.addView(line, lp);
        }
        int v = Party.voters();
        voters.setText(v + (v == 1 ? " vote" : " votes") + " in");
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 1000);
    }

    /** Closes the vote and opens the winning film's page (not played). */
    private void reveal() {
        if (revealed) return;
        final String win = Party.finish();
        if (win.isEmpty()) return;
        revealed = true;
        handler.removeCallbacks(tick);
        String name = "";
        for (Object[] r : Party.ranking()) if (((Party.Film) r[0]).key.equals(win)) name = ((Party.Film) r[0]).title;
        message("The winner is\n" + name);
        handler.postDelayed(() -> {
            if (isDestroyed()) return;
            Intent i = new Intent(this, DetailActivity.class);
            i.putExtra("key", win);
            startActivity(i);
            finish();
        }, 2500);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP && started && !revealed) {
            Party.cancel(); // backing out ends the vote
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (started && !revealed) Party.cancel();
        super.onDestroy();
    }
}
