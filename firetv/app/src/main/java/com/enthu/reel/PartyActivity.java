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
 * Party mode on the TV. 1) Pick a library and ten of its films are drawn. 2) The code goes up (the lobby) until you
 * press Start votes. 3) A countdown runs while guests vote on their phones and the leaderboard updates live.
 * 4) When time is up (or you press Reveal now) comes the big reveal: fireworks, the winning poster, and OK opens
 * that title's page (it is opened, not played).
 */
public class PartyActivity extends ReelActivity {
    private static final int FILMS = 10;
    private static final int[] MINUTES = {1, 2, 3, 5};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private LinearLayout board;
    private TextView voters;
    private TextView clock;
    private TextView startBtn;
    private TextView timeBtn;
    private TextView revealBtn;
    private TextView bigCount;
    private int minutesAt = 0; // 1 minute unless changed
    private int W;
    private int H;
    private boolean started;   // a party exists (the code is up)
    private boolean voting;    // Start votes was pressed
    private boolean revealed;
    private long lastBoard;
    private Plex.Section section;
    private boolean guestAdd;
    private boolean again;     // the next draw is "Pick again": same code and settings
    private final java.util.Set<String> lastKeys = new java.util.HashSet<>();

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
                chooseMode(libs);
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

    private static final int ROULETTE = 9;
    private int gameMode = Party.VOTE_MODE;

    /** First: what to play. */
    private void chooseMode(final List<Plex.Section> libs) {
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
        sub.setText("What are we playing?");
        sub.setTextColor(0x99FFFFFF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.028f);
        sub.setPadding(0, (int) (H * 0.01f), 0, (int) (H * 0.04f));
        col.addView(sub);
        String[] labels = {"Vote on films", "Movie roulette", "Guess the film", "Trailer guess", "Emoji plot", "Soundtrack game", "Movie quiz night"};
        final int[] modes = {Party.VOTE_MODE, ROULETTE, Party.GUESS, Party.TRAILER, Party.EMOJI, Party.SOUND, Party.QUIZ};
        View first = null;
        for (int i = 0; i < labels.length; i++) {
            final int m = modes[i];
            TextView b = button(labels[i]);
            b.setOnClickListener(v -> {
                SoundFx.click();
                gameMode = m;
                chooseLibrary(libs);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (W * 0.4f), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (H * 0.015f);
            col.addView(b, lp);
            if (first == null) first = b;
        }
        root.addView(col);
        if (first != null) first.requestFocus();
    }

    /** What the mode does with the library you picked. */
    private void pickLibrary(Plex.Section s) {
        section = s;
        if (gameMode == Party.VOTE_MODE) {
            options(s);
        } else if (gameMode == ROULETTE) {
            spin(s);
        } else {
            Intent i = new Intent(this, PartyGameActivity.class);
            i.putExtra("mode", gameMode);
            i.putExtra("section", s.key);
            i.putExtra("title", s.title);
            startActivity(i);
            finish();
        }
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
        sub.setText(gameMode == Party.VOTE_MODE ? "Pick the library. " + FILMS + " random films from it go to a vote on everyone's phone."
                : gameMode == ROULETTE ? "Pick the library. The reel lands on a random film you haven't watched."
                : gameMode == Party.GUESS ? "Pick the library. Posters sharpen on the TV; everyone races to name the film on their phone."
                : gameMode == Party.TRAILER ? "Pick the library. Trailer clips play on the TV, some with the picture hidden; name the film on your phone."
                : gameMode == Party.EMOJI ? "Pick the library. A film is shown as emoji on the TV; name it on your phone."
                : gameMode == Party.SOUND ? "Pick the library. Short clips of the sound only play on the TV; name the film on your phone."
                : "Pick the library. Questions about its films; everyone answers on their phone.");
        sub.setTextColor(0x99FFFFFF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.028f);
        sub.setPadding(0, (int) (H * 0.01f), 0, (int) (H * 0.04f));
        col.addView(sub);
        View first = null;
        for (final Plex.Section s : libs) {
            TextView b = button(s.title);
            b.setOnClickListener(v -> {
                SoundFx.click();
                pickLibrary(s);
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

    /** Between the library and the code: whether guests may add films too. */
    private void options(final Plex.Section s) {
        section = s;
        root.removeAllViews();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (W * 0.1f), (int) (H * 0.12f), (int) (W * 0.1f), 0);
        TextView head = new TextView(this);
        head.setText(s.title);
        head.setTextColor(Color.WHITE);
        head.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.06f);
        head.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        col.addView(head);
        TextView sub = new TextView(this);
        sub.setText("Guests can search this library on their phones and add films to the vote (two each).");
        sub.setTextColor(0x99FFFFFF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.028f);
        sub.setPadding(0, (int) (H * 0.01f), 0, (int) (H * 0.04f));
        col.addView(sub);
        final TextView add = button("");
        add.setText("Guests can add films: " + (guestAdd ? "Yes" : "No"));
        add.setOnClickListener(v -> {
            SoundFx.click();
            guestAdd = !guestAdd;
            add.setText("Guests can add films: " + (guestAdd ? "Yes" : "No"));
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (W * 0.4f), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (H * 0.015f);
        col.addView(add, lp);
        TextView go = button("Continue");
        go.setOnClickListener(v -> {
            SoundFx.click();
            begin(s);
        });
        col.addView(go, lp);
        root.addView(col);
        go.requestFocus();
    }

    /** Step 2: draw the films and put the code up. */
    private void begin(final Plex.Section s) {
        message("Choosing " + FILMS + " films from " + s.title + "…");
        Plex.async(() -> {
            List<Plex.Item> pool = Plex.items(Plex.paged("/library/sections/" + s.key + "/all?sort=random", 60));
            java.util.Collections.shuffle(pool);
            if (again) { // not the films that were just on the board, if there are enough others
                List<Plex.Item> fresh = new ArrayList<>();
                for (Plex.Item it : pool) if (!lastKeys.contains(it.key)) fresh.add(it);
                if (fresh.size() >= FILMS) pool = fresh;
            }
            List<Party.Film> out = new ArrayList<>();
            for (Plex.Item it : pool) {
                if (out.size() >= FILMS) break;
                out.add(new Party.Film(it.key, it.displayTitle(), it.year > 0 ? String.valueOf(it.year) : "", it.poster()));
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
                if (again) Party.restart(films);
                else Party.start(films, section.key, guestAdd);
                again = false;
                showLobby();
            }

            @Override
            public void fail(Exception e) {
                if (!isDestroyed()) message("Couldn't load films: " + e.getMessage());
            }
        });
    }

    /** The code and the ten films; nobody can vote until Start votes is pressed. */
    private void showLobby() {
        root.removeAllViews();
        String base = PhoneRemote.baseUrl();
        final String link = base == null ? null : base + "/party?p=" + Party.token();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding((int) (W * 0.06f), (int) (H * 0.08f), (int) (W * 0.05f), 0);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView head = new TextView(this);
        head.setText("Scan to join");
        head.setTextColor(Color.WHITE);
        head.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.05f);
        head.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        left.addView(head);
        int qs = (int) (H * 0.4f);
        ImageView code = new ImageView(this);
        if (link != null) code.setImageBitmap(PlayerActivity.qr(link, qs));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(qs, qs);
        qlp.topMargin = (int) (H * 0.03f);
        left.addView(code, qlp);
        TextView how = new TextView(this);
        how.setText(link == null ? "No network" : "Same Wi-Fi as this TV. One vote each.");
        how.setTextColor(0x99FFFFFF);
        how.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.024f);
        how.setPadding(0, (int) (H * 0.012f), 0, 0);
        left.addView(how);

        clock = new TextView(this);
        clock.setTextColor(0xFFE8833A);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.085f);
        clock.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        clock.setVisibility(View.GONE);
        left.addView(clock);
        voters = new TextView(this);
        voters.setTextColor(0xFFE8833A);
        voters.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
        voters.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        voters.setPadding(0, (int) (H * 0.012f), 0, (int) (H * 0.02f));
        voters.setVisibility(View.GONE);
        left.addView(voters);

        timeBtn = button("");
        paintTime();
        timeBtn.setOnClickListener(v -> {
            SoundFx.click();
            minutesAt = (minutesAt + 1) % MINUTES.length;
            paintTime();
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = (int) (H * 0.025f);
        left.addView(timeBtn, bl);
        startBtn = button("Start votes");
        startBtn.setOnClickListener(v -> {
            SoundFx.click();
            startVoting();
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = (int) (H * 0.012f);
        left.addView(startBtn, sl);
        revealBtn = button("Reveal now");
        revealBtn.setVisibility(View.GONE);
        revealBtn.setOnClickListener(v -> {
            SoundFx.click();
            Party.finish();
            reveal();
        });
        left.addView(revealBtn, sl);
        row.addView(left, new LinearLayout.LayoutParams((int) (W * 0.34f), ViewGroup.LayoutParams.WRAP_CONTENT));

        board = new LinearLayout(this);
        board.setOrientation(LinearLayout.VERTICAL);
        row.addView(board, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);

        bigCount = new TextView(this);
        bigCount.setTextColor(Color.WHITE);
        bigCount.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.4f);
        bigCount.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        bigCount.setShadowLayer(H * 0.02f, 0, 0, 0xFFE8833A);
        bigCount.setVisibility(View.GONE);
        root.addView(bigCount, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        startBtn.requestFocus();
        refresh(true);
    }

    private void paintTime() {
        timeBtn.setText("Voting time: " + MINUTES[minutesAt] + (MINUTES[minutesAt] == 1 ? " minute" : " minutes"));
    }

    private void startVoting() {
        if (voting) return;
        voting = true;
        Party.begin(MINUTES[minutesAt] * 60_000L);
        startBtn.setVisibility(View.GONE);
        timeBtn.setVisibility(View.GONE);
        revealBtn.setVisibility(View.VISIBLE);
        clock.setVisibility(View.VISIBLE);
        voters.setVisibility(View.VISIBLE);
        revealBtn.requestFocus();
        refresh(true);
    }

    private final Runnable tick = () -> refresh(false);

    private void refresh(boolean force) {
        if (isDestroyed() || board == null || revealed) return;
        int phase = Party.phase();
        if (voting && phase == Party.DONE) { // the time ran out
            reveal();
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (voting) {
            long left = Party.remainingMs();
            long s = (left + 999) / 1000;
            clock.setText(s / 60 + ":" + String.format("%02d", s % 60));
            clock.setTextColor(s <= 10 ? 0xFFFF5A5A : 0xFFE8833A);
            if (s <= 5 && s > 0) { // the last five seconds, big in the middle
                bigCount.setText(String.valueOf(s));
                if (bigCount.getVisibility() != View.VISIBLE || !String.valueOf(s).equals(bigCount.getTag())) {
                    bigCount.setTag(String.valueOf(s));
                    bigCount.setVisibility(View.VISIBLE);
                    bigCount.setScaleX(1.5f);
                    bigCount.setScaleY(1.5f);
                    bigCount.setAlpha(1f);
                    bigCount.animate().scaleX(0.8f).scaleY(0.8f).alpha(0.25f).setDuration(900).start();
                    SoundFx.move();
                }
            }
        }
        if (force || now - lastBoard >= 1000) {
            lastBoard = now;
            drawBoard();
        }
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 250);
    }

    private void drawBoard() {
        board.removeAllViews();
        boolean lobby = !voting;
        List<Object[]> rank = Party.ranking();
        int max = 1;
        for (Object[] r : rank) max = Math.max(max, (Integer) r[1]);
        int rowH = (int) Math.min(H * 0.062f, H * 0.8f / Math.max(1, rank.size()) - H * 0.008f);
        int n = 0;
        for (Object[] r : rank) {
            Party.Film f = (Party.Film) r[0];
            int votes = (Integer) r[1];
            n++;
            FrameLayout line = new FrameLayout(this);
            View bar = new View(this);
            bar.setBackground(UiUtil.rounded(0x33E8833A, H * 0.012f));
            line.addView(bar, new FrameLayout.LayoutParams(lobby ? 0 : (int) (W * 0.5f * votes / max),
                    ViewGroup.LayoutParams.MATCH_PARENT));
            TextView t = new TextView(this);
            t.setText(n + "   " + f.title + (f.sub.isEmpty() ? "" : "  (" + f.sub + ")"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
            t.setSingleLine();
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setPadding((int) (W * 0.01f), (int) (H * 0.011f), (int) (W * 0.08f), (int) (H * 0.011f));
            line.addView(t);
            if (!lobby) {
                TextView c = new TextView(this);
                c.setText(String.valueOf(votes));
                c.setTextColor(0xFFE8833A);
                c.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.034f);
                c.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
                c.setPadding(0, (int) (H * 0.008f), (int) (W * 0.012f), 0);
                line.addView(c, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowH); // fixed, so the bar can fill the row
            lp.bottomMargin = (int) (H * 0.008f);
            board.addView(line, lp);
        }
        int v = Party.voters();
        voters.setText(v + (v == 1 ? " vote" : " votes") + " in");
    }

    // ---------------------------------------------------------------- the big reveal

    private FireworksView fx;
    private String againLabel = "Pick again";
    private Runnable againAction = this::pickAgain;

    private void reveal() {
        if (revealed) return;
        final String win = Party.finish();
        final Party.Film film = Party.winnerFilm();
        if (win.isEmpty() || film == null) return;
        int votes = Party.votesFor(win);
        againLabel = "Pick again";
        againAction = this::pickAgain;
        revealFilm(win, film, "AND THE WINNER IS…",
                (film.sub.isEmpty() ? "" : film.sub + "   ·   ") + votes + (votes == 1 ? " vote" : " votes"));
    }

    /** The big reveal for any film: fireworks and a build-up, then the poster with Open and a second button. */
    private void revealFilm(final String win, final Party.Film film, String header, final String subLine) {
        revealed = true;
        handler.removeCallbacks(tick);
        root.removeAllViews();
        root.setBackgroundColor(0xFF05050A);

        fx = new FireworksView(this);
        root.addView(fx, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        fx.start(false);

        final TextView suspense = new TextView(this);
        suspense.setText(header);
        suspense.setTextColor(Color.WHITE);
        suspense.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.07f);
        suspense.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        suspense.setLetterSpacing(0.08f);
        suspense.setAlpha(0f);
        root.addView(suspense, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        suspense.animate().alpha(1f).setDuration(700).start();

        // Rockets go up while it builds
        for (int i = 0; i < 6; i++) {
            final int k = i;
            handler.postDelayed(() -> {
                if (!isDestroyed() && fx != null) fx.launch(0.12f + 0.15f * k, 0.15f + (k % 3) * 0.1f);
            }, 400 + i * 380L);
        }

        handler.postDelayed(() -> {
            if (isDestroyed()) return;
            suspense.animate().alpha(0f).setDuration(300).withEndAction(() -> root.removeView(suspense)).start();
            showWinner(win, film, subLine);
        }, 3200);
    }

    private void showWinner(final String win, Party.Film film, String subLine) {
        fx.autoLaunch(true);
        for (int i = 0; i < 7; i++) {
            final int k = i;
            handler.postDelayed(() -> {
                if (!isDestroyed() && fx != null) fx.burst(0.1f + 0.13f * k, 0.2f + (k % 2) * 0.25f, 110);
            }, i * 120L);
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int ph = (int) (H * 0.56f);
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(UiUtil.rounded(UiUtil.CARD, H * 0.015f));
        UiUtil.clipRounded(poster, H * 0.015f);
        poster.setElevation(H * 0.03f);
        String url = Plex.img(film.thumb.isEmpty() ? null : film.thumb, 600, 900);
        if (url != null) com.bumptech.glide.Glide.with(this).load(url).centerCrop().into(poster);
        col.addView(poster, new LinearLayout.LayoutParams(ph * 2 / 3, ph));

        TextView title = new TextView(this);
        title.setText(film.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.06f);
        title.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        title.setPadding((int) (W * 0.05f), (int) (H * 0.02f), (int) (W * 0.05f), 0);
        col.addView(title, new LinearLayout.LayoutParams((int) (W * 0.8f), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView sub = new TextView(this);
        sub.setText(subLine);
        sub.setTextColor(0xFFE8833A);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.034f);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, (int) (H * 0.006f), 0, (int) (H * 0.025f));
        col.addView(sub);
        TextView open = button("Open");
        open.setGravity(Gravity.CENTER);
        open.setOnClickListener(v -> {
            SoundFx.click();
            Intent i = new Intent(this, DetailActivity.class);
            i.putExtra("key", win);
            startActivity(i);
            finish();
        });
        final TextView again = button(againLabel);
        again.setGravity(Gravity.CENTER);
        again.setOnClickListener(v -> {
            SoundFx.click();
            againAction.run();
        });
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(open, new LinearLayout.LayoutParams((int) (W * 0.16f), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams((int) (W * 0.16f),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = (int) (W * 0.012f);
        buttons.addView(again, alp);
        col.addView(buttons);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(col, clp);

        poster.setPivotX(ph / 3f);
        poster.setPivotY(ph / 2f);
        poster.setScaleX(0.15f);
        poster.setScaleY(0.15f);
        poster.setAlpha(0f);
        poster.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(900)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f)).start();
        title.setAlpha(0f);
        sub.setAlpha(0f);
        open.setAlpha(0f);
        again.setAlpha(0f);
        again.animate().alpha(1f).setStartDelay(1200).setDuration(500).start();
        title.animate().alpha(1f).setStartDelay(700).setDuration(500).start();
        sub.animate().alpha(1f).setStartDelay(900).setDuration(500).start();
        open.animate().alpha(1f).setStartDelay(1200).setDuration(500).start();
        open.requestFocus();
    }

    // ---------------------------------------------------------------- movie roulette

    /** Spins a slot-machine reel of titles that slows and lands on an unwatched film from the library. */
    private void spin(final Plex.Section s) {
        message("Loading the reel…");
        Plex.async(() -> {
            List<Plex.Item> pool = Plex.items(Plex.paged("/library/sections/" + s.key + "/all?unwatched=1&sort=random", 40));
            if (pool.size() < 5) pool = Plex.items(Plex.paged("/library/sections/" + s.key + "/all?sort=random", 40));
            List<Party.Film> out = new ArrayList<>();
            for (Plex.Item it : pool) {
                out.add(new Party.Film(it.key, it.displayTitle(), it.year > 0 ? String.valueOf(it.year) : "", it.poster()));
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
                spinReel(films);
            }

            @Override
            public void fail(Exception e) {
                if (!isDestroyed()) message("Couldn't load films: " + e.getMessage());
            }
        });
    }

    private void spinReel(final List<Party.Film> films) {
        revealed = false;
        root.removeAllViews();
        root.setBackgroundColor(0xFF05050A);
        FrameLayout box = new FrameLayout(this);
        box.setBackground(UiUtil.rounded(0x22FFFFFF, H * 0.03f));
        final TextView title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.09f);
        title.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setPadding((int) (W * 0.03f), 0, (int) (W * 0.03f), 0);
        box.addView(title, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams((int) (W * 0.8f), (int) (H * 0.32f), Gravity.CENTER);
        root.addView(box, blp);
        TextView head = new TextView(this);
        head.setText("MOVIE ROULETTE");
        head.setTextColor(0xFFE8833A);
        head.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.05f);
        head.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        head.setLetterSpacing(0.15f);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        hlp.topMargin = (int) (H * 0.18f);
        root.addView(head, hlp);

        final java.util.Random rnd = new java.util.Random();
        final Party.Film landed = films.get(rnd.nextInt(films.size()));
        // Ticks slow down like a real reel: quick at first, then longer and longer gaps
        final List<Long> delays = new ArrayList<>();
        for (double d = 55; d < 480; d *= 1.14) delays.add((long) d);
        long at = 400;
        for (int i = 0; i < delays.size(); i++) {
            at += delays.get(i);
            final boolean last = i == delays.size() - 1;
            handler.postDelayed(() -> {
                if (isDestroyed()) return;
                Party.Film f = last ? landed : films.get(rnd.nextInt(films.size()));
                title.setText(f.title);
                title.setScaleX(1.08f);
                title.setScaleY(1.08f);
                title.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                SoundFx.move();
                if (last) title.setTextColor(0xFFE8833A);
            }, at);
        }
        handler.postDelayed(() -> {
            if (isDestroyed()) return;
            againLabel = "Spin again";
            againAction = () -> {
                handler.removeCallbacksAndMessages(null);
                if (fx != null) fx.stop();
                fx = null;
                revealed = false;
                root.setBackgroundColor(UiUtil.BG);
                spin(section);
            };
            revealFilm(landed.key, landed, "TONIGHT'S FILM IS…", landed.sub);
        }, at + 900);
    }

    /** After a reveal: ten new films from the same library, the same code, a fresh vote. */
    private void pickAgain() {
        if (section == null) return;
        lastKeys.clear();
        for (Object[] r : Party.ranking()) lastKeys.add(((Party.Film) r[0]).key);
        again = true;
        revealed = false;
        voting = false;
        handler.removeCallbacksAndMessages(null);
        if (fx != null) fx.stop();
        fx = null;
        root.setBackgroundColor(UiUtil.BG);
        begin(section);
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
        if (fx != null) fx.stop();
        if (started && !revealed) Party.cancel();
        super.onDestroy();
    }
}
