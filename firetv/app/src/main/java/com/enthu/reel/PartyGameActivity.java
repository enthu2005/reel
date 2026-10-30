package com.enthu.reel;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The two phone-answered games on the TV: Guess the film (a poster that sharpens while phones race to name it)
 * and Movie quiz night (questions made from the library's own details). Everyone joins from the QR code with a name,
 * faster right answers score more, and the scoreboard builds to a fireworks finish.
 */
public class PartyGameActivity extends ReelActivity {
    private static final int[] COLORS = {0xFFE0524D, 0xFF3D8BFD, 0xFFE8B13A, 0xFF3FB27F};
    private static final int[] STAGE_W = {6, 12, 24, 48, 100, 420};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<Integer, Bitmap[]> stages = new ConcurrentHashMap<>();
    private FrameLayout root;
    private int W;
    private int H;
    private int mode;
    private String sectionKey;
    private String sectionTitle;
    private boolean started;
    private int shownPhase = -1;
    private int shownRound = -1;
    private FireworksView fx;

    // views updated every tick
    private TextView playersView;
    private TextView timerText;
    private TextView answeredText;
    private View timerBar;
    private ImageView poster;
    private int shownStage = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Plex.init(this);
        SoundFx.init(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;
        mode = getIntent().getIntExtra("mode", Party.QUIZ);
        sectionKey = getIntent().getStringExtra("section");
        sectionTitle = getIntent().getStringExtra("title");
        root = new FrameLayout(this);
        root.setBackgroundColor(UiUtil.BG);
        setContentView(root);
        PhoneRemote.ensure(this);
        message("Writing the " + (mode == Party.GUESS ? "posters" : "questions") + "…");
        build(false);
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

    /** Makes the questions (off the main thread), then opens the lobby; [again] keeps the players and code. */
    private void build(final boolean again) {
        Plex.async(() -> makeRounds(), new Plex.Callback<List<Party.Round>>() {
            @Override
            public void ok(List<Party.Round> rounds) {
                if (isDestroyed()) return;
                if (rounds.size() < 3) {
                    message("Not enough films in that library for a game.");
                    return;
                }
                started = true;
                if (again) Party.restartGame(rounds);
                else Party.startGame(mode, rounds);
                shownPhase = -1;
                shownRound = -1;
                if (mode == Party.GUESS) preloadPosters(rounds);
                tick.run();
            }

            @Override
            public void fail(Exception e) {
                if (!isDestroyed()) message("Couldn't load films: " + e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- the questions

    private List<Party.Round> makeRounds() throws Exception {
        List<Plex.Item> pool = new ArrayList<>();
        for (Plex.Item it : Plex.items(Plex.paged("/library/sections/" + sectionKey + "/all?sort=random", 80))) {
            if (it.type.equals("movie") && it.title != null && !it.title.isEmpty() && it.thumb != null) pool.add(it);
        }
        Collections.shuffle(pool);
        Random rnd = new Random();
        List<Party.Round> out = new ArrayList<>();
        if (mode == Party.GUESS) {
            for (int i = 0; i < 6 && i < pool.size(); i++) {
                Plex.Item c = pool.get(i);
                out.add(titleRound("Which film is this?", c, pool, c.thumb, 30000, rnd));
            }
            return out;
        }
        if (mode == Party.TRAILER) {
            int tries = 0;
            for (Plex.Item c : pool) {
                if (out.size() >= 6 || tries++ >= 30) break;
                String url;
                try {
                    url = Plex.trailerUrl(c.key);
                } catch (Exception e) {
                    continue;
                }
                if (url == null) continue;
                boolean audio = out.size() % 2 == 0; // every other clip has its picture hidden
                Party.Round base = titleRound(audio ? "Which film is this trailer?\n\nListen to the TV — the picture is hidden."
                        : "Which film is this trailer?\n\nWatch the TV — it is silent.", c, pool, c.thumb, 25000, rnd);
                out.add(new Party.Round(base.prompt, base.options, base.correct, c.thumb, 25000, url, audio));
            }
            return out;
        }
        int next = 0;
        for (int q = 0; q < 8 && next < pool.size(); q++) {
            Plex.Item c = pool.get(next++);
            int kind = q % 3;
            if (kind == 1 && (c.summary == null || c.summary.length() < 60)) kind = 0;
            if (kind == 2 && next + 3 >= pool.size()) kind = 0;
            if (kind == 0 && c.year <= 0) kind = 1;
            if (kind == 1 && (c.summary == null || c.summary.length() < 60)) continue;
            if (kind == 0) {
                List<String> yrs = new ArrayList<>();
                yrs.add(String.valueOf(c.year));
                int tries = 0;
                while (yrs.size() < 4 && tries++ < 60) {
                    int y = c.year + (rnd.nextInt(15) - 7);
                    if (y > 1900 && y <= 2030 && !yrs.contains(String.valueOf(y))) yrs.add(String.valueOf(y));
                }
                if (yrs.size() < 4) continue;
                Collections.shuffle(yrs);
                out.add(new Party.Round("In which year was “" + c.displayTitle() + "” released?",
                        yrs.toArray(new String[0]), yrs.indexOf(String.valueOf(c.year)), "", 20000));
            } else if (kind == 1) {
                String text = Pattern.compile(Pattern.quote(c.title), Pattern.CASE_INSENSITIVE).matcher(c.summary)
                        .replaceAll("▢▢▢");
                if (text.length() > 230) {
                    int cut = text.lastIndexOf(' ', 230);
                    text = text.substring(0, cut > 100 ? cut : 230) + "…";
                }
                out.add(titleRound("Which film is this?\n\n" + text, c, pool, "", 25000, rnd));
            } else {
                List<Plex.Item> four = new ArrayList<>();
                four.add(c);
                for (int j = next; j < pool.size() && four.size() < 4; j++) {
                    Plex.Item o = pool.get(j);
                    boolean clash = o.year <= 0;
                    for (Plex.Item f : four) if (f.year == o.year) clash = true;
                    if (!clash) four.add(o);
                }
                if (four.size() < 4 || c.year <= 0) continue;
                Collections.shuffle(four);
                int best = 0;
                String[] opts = new String[4];
                for (int j = 0; j < 4; j++) {
                    opts[j] = four.get(j).displayTitle();
                    if (four.get(j).year < four.get(best).year) best = j;
                }
                out.add(new Party.Round("Which of these came out first?", opts, best, "", 20000));
            }
        }
        return out;
    }

    /** A question whose answer is a film title: the right one among three other films from the library. */
    private static Party.Round titleRound(String prompt, Plex.Item right, List<Plex.Item> pool, String thumb, long ms,
                                          Random rnd) {
        List<String> opts = new ArrayList<>();
        opts.add(right.displayTitle());
        int tries = 0;
        while (opts.size() < 4 && tries++ < 200) {
            String t = pool.get(rnd.nextInt(pool.size())).displayTitle();
            if (!opts.contains(t)) opts.add(t);
        }
        Collections.shuffle(opts);
        return new Party.Round(prompt, opts.toArray(new String[0]), opts.indexOf(right.displayTitle()), thumb, ms);
    }

    /** Guess the film: fetch every poster at six sizes, from a few pixels up, so it can sharpen on cue. */
    private void preloadPosters(final List<Party.Round> rounds) {
        final android.content.Context app = getApplicationContext();
        Thread t = new Thread(() -> {
            for (int r = 0; r < rounds.size(); r++) {
                Bitmap[] set = new Bitmap[STAGE_W.length];
                for (int s = 0; s < STAGE_W.length; s++) {
                    try {
                        String url = Plex.img(rounds.get(r).thumb, STAGE_W[s], STAGE_W[s] * 3 / 2);
                        if (url == null) continue;
                        set[s] = com.bumptech.glide.Glide.with(app).asBitmap().load(url).submit().get();
                    } catch (Exception ignored) {
                    }
                    stages.put(r, set);
                }
            }
        }, "ReelPartyPosters");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- the screens

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (isDestroyed()) return;
            Party.Snap s = Party.snap();
            if (s.phase != shownPhase || s.round != shownRound) {
                shownPhase = s.phase;
                shownRound = s.round;
                if (s.phase == Party.G_LOBBY) lobby();
                else if (s.phase == Party.G_FINAL) finale(s);
                else question(s);
            }
            update(s);
            handler.postDelayed(this, 250);
        }
    };

    private TextView button(String label) {
        final TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
        b.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        b.setPadding((int) (W * 0.018f), (int) (H * 0.016f), (int) (W * 0.018f), (int) (H * 0.016f));
        b.setGravity(Gravity.CENTER);
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

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        if (bold) t.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    private void lobby() {
        releaseTrailer();
        stopFx();
        root.removeAllViews();
        root.setBackgroundColor(UiUtil.BG);
        playersView = null;
        String base = PhoneRemote.baseUrl();
        String link = base == null ? null : base + "/party?p=" + Party.token();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding((int) (W * 0.06f), (int) (H * 0.08f), (int) (W * 0.05f), 0);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(mode == Party.GUESS ? "Guess the film" : mode == Party.TRAILER ? "Trailer guess" : "Movie quiz night", H * 0.05f, Color.WHITE, true));
        left.addView(text("Scan to join · " + (sectionTitle == null ? "" : sectionTitle), H * 0.026f, 0x99FFFFFF, false));
        int qs = (int) (H * 0.4f);
        ImageView code = new ImageView(this);
        if (link != null) code.setImageBitmap(PlayerActivity.qr(link, qs));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(qs, qs);
        qlp.topMargin = (int) (H * 0.03f);
        left.addView(code, qlp);
        TextView start = button("Start game");
        start.setOnClickListener(v -> {
            SoundFx.click();
            if (Party.snap().players == 0) {
                Toast.makeText(this, "Waiting for the first player to join", Toast.LENGTH_SHORT).show();
                return;
            }
            Party.beginGame();
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = (int) (H * 0.04f);
        left.addView(start, sl);
        row.addView(left, new LinearLayout.LayoutParams((int) (W * 0.36f), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.addView(text("Players", H * 0.04f, 0xFFE8833A, true));
        playersView = text("Waiting for players…", H * 0.036f, Color.WHITE, false);
        playersView.setLineSpacing(0, 1.25f);
        playersView.setPadding(0, (int) (H * 0.02f), 0, 0);
        right.addView(playersView);
        row.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);
        start.requestFocus();
    }

    private void question(Party.Snap s) {
        stopFx();
        root.removeAllViews();
        root.setBackgroundColor(UiUtil.BG);
        final Party.Round q = s.question;
        boolean result = s.phase == Party.G_RESULT;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding((int) (W * 0.05f), (int) (H * 0.045f), (int) (W * 0.05f), 0);

        FrameLayout top = new FrameLayout(this);
        top.addView(text("Round " + (s.round + 1) + " of " + s.total, H * 0.032f, 0x99FFFFFF, false));
        timerText = text("", H * 0.06f, 0xFFE8833A, true);
        top.addView(timerText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
        col.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        timerBar = new View(this);
        timerBar.setBackground(UiUtil.rounded(0xFFE8833A, H * 0.006f));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, (int) (H * 0.012f));
        tlp.topMargin = (int) (H * 0.012f);
        tlp.gravity = Gravity.START;
        col.addView(timerBar, tlp);

        poster = null;
        shownStage = -1;
        if (mode == Party.TRAILER) {
            trailerBox(col, q, s.round, result);
        } else if (!q.thumb.isEmpty()) {
            poster = new ImageView(this);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setBackground(UiUtil.rounded(UiUtil.CARD, H * 0.015f));
            UiUtil.clipRounded(poster, H * 0.015f);
            int ph = (int) (H * 0.46f);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ph * 2 / 3, ph);
            plp.topMargin = (int) (H * 0.02f);
            col.addView(poster, plp);
            col.addView(text(result ? q.options[q.correct] : q.prompt, H * 0.04f, Color.WHITE, true),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            TextView p = text(q.prompt, H * 0.04f, Color.WHITE, true);
            p.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams((int) (W * 0.82f), (int) (H * 0.4f));
            pp.topMargin = (int) (H * 0.03f);
            col.addView(p, pp);
        }
        answeredText = text("", H * 0.026f, 0x99FFFFFF, false);
        col.addView(answeredText);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < 2; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 2; c++) {
                int i = r * 2 + c;
                if (i >= q.options.length) continue;
                TextView o = text("ABCD".charAt(i) + "   " + q.options[i], H * 0.034f, Color.WHITE, true);
                o.setSingleLine();
                o.setEllipsize(android.text.TextUtils.TruncateAt.END);
                o.setGravity(Gravity.CENTER_VERTICAL);
                o.setPadding((int) (W * 0.02f), 0, (int) (W * 0.02f), 0);
                int color = COLORS[i];
                if (result) color = i == q.correct ? 0xFF2FAA63 : 0xFF2A2B31;
                o.setBackground(UiUtil.rounded(color, H * 0.02f));
                if (result && i != q.correct) o.setTextColor(0x66FFFFFF);
                LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams((int) (W * 0.43f), (int) (H * 0.095f));
                ol.setMargins((int) (W * 0.01f), (int) (H * 0.01f), (int) (W * 0.01f), 0);
                line.addView(o, ol);
            }
            grid.addView(line);
        }
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        gl.topMargin = (int) (H * 0.015f);
        col.addView(grid, gl);
        root.addView(col);

        if (result) { // the scores so far, down the right
            StringBuilder sb = new StringBuilder("Scores\n");
            for (int i = 0; i < Math.min(5, s.board.size()); i++) {
                sb.append(i + 1).append("  ").append(s.board.get(i)[0]).append("   ").append(s.board.get(i)[1]).append('\n');
            }
            TextView sc = text(sb.toString(), H * 0.03f, 0xFFE8833A, true);
            sc.setLineSpacing(0, 1.2f);
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
            sp.leftMargin = (int) (W * 0.03f);
            root.addView(sc, sp);
            if (s.question != null) SoundFx.click();
        }
    }

    // ---------------------------------------------------------------- trailer guess

    private androidx.media3.exoplayer.ExoPlayer trailerPlayer;
    private int trailerRound = -1;

    private void releaseTrailer() {
        if (trailerPlayer != null) {
            trailerPlayer.release();
            trailerPlayer = null;
        }
        trailerRound = -1;
    }

    /**
     * The clip for this round. Some have the picture hidden ("LISTEN"), the others play silently. When the answer
     * comes up the same clip carries on with sound (and the poster replaces the hidden picture).
     */
    private void trailerBox(LinearLayout col, Party.Round q, int round, boolean result) {
        int bh = (int) (H * 0.4f);
        FrameLayout box = new FrameLayout(this);
        box.setBackground(UiUtil.rounded(UiUtil.CARD, H * 0.015f));
        UiUtil.clipRounded(box, H * 0.015f);
        androidx.media3.ui.PlayerView pv = new androidx.media3.ui.PlayerView(this);
        pv.setUseController(false);
        box.addView(pv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (trailerPlayer == null || trailerRound != round) {
            releaseTrailer();
            trailerRound = round;
            final androidx.media3.exoplayer.ExoPlayer p = PreviewHandoff.newPlayer(this);
            trailerPlayer = p;
            p.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_ONE);
            p.addListener(new androidx.media3.common.Player.Listener() {
                private boolean jumped;

                @Override
                public void onPlaybackStateChanged(int state) {
                    // start somewhere in the middle, past the studio logos
                    if (state == androidx.media3.common.Player.STATE_READY && !jumped && p.getDuration() > 20000) {
                        jumped = true;
                        p.seekTo((long) (p.getDuration() * (0.2 + new Random().nextDouble() * 0.4)));
                    }
                }

                @Override
                public void onPlayerError(androidx.media3.common.PlaybackException error) {
                    // this clip won't play: the round carries on without it
                }
            });
            p.setMediaItem(androidx.media3.common.MediaItem.fromUri(q.trailer));
            p.setVolume(q.audioOnly ? 1f : 0f);
            p.prepare();
            p.play();
        }
        pv.setPlayer(trailerPlayer);
        if (result) trailerPlayer.setVolume(1f);

        if (q.audioOnly) {
            pv.setAlpha(0f);
            if (result) {
                ImageView pic = new ImageView(this);
                pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
                String url = Plex.img(q.thumb.isEmpty() ? null : q.thumb, 400, 600);
                if (url != null) com.bumptech.glide.Glide.with(this).load(url).centerCrop().into(pic);
                box.addView(pic, new FrameLayout.LayoutParams(bh * 2 / 3, bh, Gravity.CENTER));
            } else {
                TextView listen = text("LISTEN", H * 0.09f, Color.WHITE, true);
                listen.setLetterSpacing(0.2f);
                listen.setGravity(Gravity.CENTER);
                box.addView(listen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
                listen.animate().alpha(0.35f).setDuration(700).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (!isDestroyed() && listen.isAttachedToWindow()) {
                            listen.animate().alpha(listen.getAlpha() > 0.5f ? 0.35f : 1f).setDuration(700).withEndAction(this).start();
                        }
                    }
                }).start();
            }
        }
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(q.audioOnly && result ? bh * 2 / 3 : bh * 16 / 9, bh);
        blp.topMargin = (int) (H * 0.02f);
        col.addView(box, blp);
        TextView cap = text(result ? q.options[q.correct] : q.prompt, result ? H * 0.04f : H * 0.03f, Color.WHITE, result);
        cap.setGravity(Gravity.CENTER);
        col.addView(cap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void finale(Party.Snap s) {
        releaseTrailer();
        root.removeAllViews();
        root.setBackgroundColor(0xFF05050A);
        fx = new FireworksView(this);
        root.addView(fx, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        fx.start(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(0, (int) (H * 0.1f), 0, 0);
        String winner = s.board.isEmpty() ? "" : String.valueOf(s.board.get(0)[0]);
        TextView who = text("AND THE WINNER IS…", H * 0.04f, 0xFFE8833A, true);
        who.setGravity(Gravity.CENTER);
        col.addView(who);
        TextView w = text(winner, H * 0.12f, Color.WHITE, true);
        w.setGravity(Gravity.CENTER);
        w.setPadding(0, (int) (H * 0.01f), 0, (int) (H * 0.04f));
        col.addView(w);
        for (int i = 0; i < Math.min(5, s.board.size()); i++) {
            TextView r = text((i + 1) + "   " + s.board.get(i)[0] + "   ·   " + s.board.get(i)[1], H * 0.04f,
                    i == 0 ? 0xFFE8833A : Color.WHITE, i == 0);
            r.setGravity(Gravity.CENTER);
            r.setPadding(0, (int) (H * 0.006f), 0, 0);
            col.addView(r);
        }
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView again = button("New round");
        again.setOnClickListener(v -> {
            SoundFx.click();
            stopFx();
            message("Writing new " + (mode == Party.GUESS ? "posters" : "questions") + "…");
            handler.removeCallbacks(tick);
            build(true);
        });
        TextView change = button("Change game");
        change.setOnClickListener(v -> {
            SoundFx.click();
            startActivity(new android.content.Intent(this, PartyActivity.class));
            finish();
        });
        TextView done = button("Done");
        done.setOnClickListener(v -> {
            SoundFx.click();
            finish();
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams((int) (W * 0.16f), ViewGroup.LayoutParams.WRAP_CONTENT);
        buttons.addView(again, bl);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams((int) (W * 0.16f), ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.leftMargin = (int) (W * 0.012f);
        buttons.addView(change, cl);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams((int) (W * 0.16f), ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.leftMargin = (int) (W * 0.012f);
        buttons.addView(done, dl);
        LinearLayout.LayoutParams bll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bll.topMargin = (int) (H * 0.04f);
        col.addView(buttons, bll);
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        again.requestFocus();
        for (int i = 0; i < 6; i++) {
            final int k = i;
            handler.postDelayed(() -> {
                if (!isDestroyed() && fx != null) fx.burst(0.1f + 0.15f * k, 0.2f + (k % 2) * 0.3f, 110);
            }, i * 150L);
        }
    }

    private void stopFx() {
        if (fx != null) fx.stop();
        fx = null;
    }

    /** The parts that change while a screen is up: the player list, the clock, the poster's sharpness. */
    private void update(Party.Snap s) {
        if (s.phase == Party.G_LOBBY && playersView != null) {
            StringBuilder sb = new StringBuilder();
            for (Object[] b : s.board) sb.append(b[0]).append('\n');
            playersView.setText(sb.length() == 0 ? "Waiting for players…" : sb.toString());
        }
        if (s.phase == Party.G_ASK || s.phase == Party.G_RESULT) {
            boolean asking = s.phase == Party.G_ASK;
            if (timerText != null) timerText.setText(asking ? String.valueOf((s.left + 999) / 1000) : "");
            if (timerBar != null && s.question != null) {
                ViewGroup.LayoutParams lp = timerBar.getLayoutParams();
                lp.width = asking ? (int) (W * 0.9f * s.left / s.question.ms) : 0;
                timerBar.setLayoutParams(lp);
            }
            if (answeredText != null) {
                answeredText.setText(asking ? s.answered + " of " + s.players + " answered" : "");
            }
            if (poster != null && s.question != null) {
                int want = asking ? (int) Math.min(STAGE_W.length - 1, (s.question.ms - s.left) * 5 / (s.question.ms * 4 / 5 + 1))
                        : STAGE_W.length - 1;
                Bitmap[] set = stages.get(s.round);
                if (set != null) {
                    for (int st = want; st >= 0; st--) {
                        if (set[st] != null) {
                            if (st != shownStage) {
                                shownStage = st;
                                poster.setImageBitmap(set[st]);
                            }
                            break;
                        }
                    }
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseTrailer();
        stopFx();
        if (started) Party.cancel();
        super.onDestroy();
    }
}
