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

    private final List<String> used = new ArrayList<>();

    private String scope() {
        return "g" + mode + "_" + sectionKey;
    }

    /** The questions, from films this game has not used lately; the ones used are remembered for next time. */
    private List<Party.Round> makeRounds() throws Exception {
        used.clear();
        List<Party.Round> r = buildRounds();
        PartyMemory.add(this, scope(), used);
        return r;
    }

    private List<Party.Round> buildRounds() throws Exception {
        if (mode == Party.EMOJI) return emojiRounds();
        List<Plex.Item> pool = new ArrayList<>();
        for (Plex.Item it : Plex.items(Plex.paged("/library/sections/" + sectionKey + "/all?sort=random", 300))) {
            if (it.type.equals("movie") && it.title != null && !it.title.isEmpty() && it.thumb != null) pool.add(it);
        }
        Collections.shuffle(pool);
        pool = PartyMemory.fresh(this, scope(), pool, it -> it.key, 10);
        Random rnd = new Random();
        List<Party.Round> out = new ArrayList<>();
        if (mode == Party.GUESS) {
            for (int i = 0; i < 6 && i < pool.size(); i++) {
                Plex.Item c = pool.get(i);
                out.add(titleRound("Which film is this?", c, pool, c.thumb, 30000, rnd));
                used.add(c.key);
            }
            return out;
        }
        if (mode == Party.TRAILER || mode == Party.SOUND) {
            int tries = 0;
            for (Plex.Item c : pool) {
                if (out.size() >= (mode == Party.SOUND ? 8 : 6) || tries++ >= 45) break;
                String url;
                try {
                    url = Plex.trailerUrl(c.key);
                } catch (Exception e) {
                    continue;
                }
                if (url == null) continue;
                boolean audio = mode == Party.SOUND || out.size() % 2 == 0; // every other clip has its picture hidden
                if (mode == Party.SOUND) {
                    Party.Round b = titleRound("Name the film from its sound.\n\nListen to the TV — the picture is hidden.",
                            c, pool, c.thumb, 20000, rnd);
                    out.add(new Party.Round(b.prompt, b.options, b.correct, c.thumb, 20000, url, true));
                    used.add(c.key);
                    continue;
                }
                Party.Round base = titleRound(audio ? "Which film is this trailer?\n\nListen to the TV — the picture is hidden."
                        : "Which film is this trailer?\n\nWatch the TV — it is silent.", c, pool, c.thumb, 25000, rnd);
                out.add(new Party.Round(base.prompt, base.options, base.correct, c.thumb, 25000, url, audio));
                used.add(c.key);
            }
            return out;
        }
        int next = 0;
        for (int q = 0; q < 8 && next < pool.size(); q++) {
            Plex.Item c = pool.get(next++);
            used.add(c.key);
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

    // ---------------------------------------------------------------- emoji plot

    private java.util.LinkedHashMap<String, String> words;  // word -> emoji
    private java.util.Map<String, String> genreEmoji;       // genre -> emoji

    private void loadEmojiWords() {
        words = new java.util.LinkedHashMap<>();
        genreEmoji = new java.util.HashMap<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(getAssets().open("emoji_words.txt"), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String k = line.substring(0, tab).trim().toLowerCase(Locale.ROOT);
                String e = line.substring(tab + 1).trim();
                if (k.startsWith("genre:")) genreEmoji.put(k.substring(6), e);
                else words.put(k, e);
            }
        } catch (Exception ignored) {
        }
    }

    private String emojiOf(String token) {
        for (java.util.Map.Entry<String, String> w : words.entrySet()) {
            String k = w.getKey();
            if (token.equals(k) || token.equals(k + "s") || token.equals(k + "es") || token.equals(k + "ed")
                    || token.equals(k + "ing") || (k.length() >= 5 && token.startsWith(k))) {
                return w.getValue();
            }
        }
        return null;
    }

    /** The film as up to five emoji, from the words of its title and plot (then its genres); null if too few. */
    private String emojiPlot(String title, String summary, org.json.JSONArray genres) {
        List<String> out = new ArrayList<>();
        String[] tokens = ((title == null ? "" : title) + " " + (summary == null ? "" : summary))
                .toLowerCase(Locale.ROOT).split("[^a-z]+");
        for (String t : tokens) {
            if (t.length() < 3) continue;
            String e = emojiOf(t);
            if (e != null && !out.contains(e)) out.add(e);
            if (out.size() >= 5) break;
        }
        if (out.size() < 4 && genres != null) {
            for (int i = 0; i < genres.length() && out.size() < 4; i++) {
                String e = genreEmoji.get(genres.optJSONObject(i) == null ? "" : genres.optJSONObject(i).optString("tag", "").toLowerCase(Locale.ROOT));
                if (e != null && !out.contains(e)) out.add(e);
            }
        }
        if (out.size() < 3) return null;
        StringBuilder sb = new StringBuilder();
        for (String e : out) sb.append(e).append(' ');
        return sb.toString().trim();
    }

    /** Eight films shown only as emoji; the answer is the right title among three others from the library. */
    private List<Party.Round> emojiRounds() throws Exception {
        loadEmojiWords();
        org.json.JSONArray all = Plex.listAll(sectionKey);
        List<org.json.JSONObject> movies = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            org.json.JSONObject m = all.optJSONObject(i);
            if (m == null || !"movie".equals(m.optString("type")) || m.optString("title").isEmpty()) continue;
            movies.add(m);
            titles.add(m.optString("title"));
        }
        Collections.shuffle(movies);
        movies = PartyMemory.fresh(this, scope(), movies, m -> m.optString("ratingKey"), 10);
        Random rnd = new Random();
        List<Party.Round> out = new ArrayList<>();
        for (org.json.JSONObject m : movies) {
            if (out.size() >= 8) break;
            String plot = emojiPlot(m.optString("title"), m.optString("summary"), m.optJSONArray("Genre"));
            if (plot == null) continue;
            String right = m.optString("title");
            List<String> opts = new ArrayList<>();
            opts.add(right);
            int tries = 0;
            while (opts.size() < 4 && tries++ < 200) {
                String t = titles.get(rnd.nextInt(titles.size()));
                if (!opts.contains(t)) opts.add(t);
            }
            if (opts.size() < 4) continue;
            Collections.shuffle(opts);
            out.add(new Party.Round(plot, opts.toArray(new String[0]), opts.indexOf(right), m.optString("thumb"), 20000));
            used.add(m.optString("ratingKey"));
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
        left.addView(text(mode == Party.GUESS ? "Guess the film" : mode == Party.TRAILER ? "Trailer guess" : mode == Party.EMOJI ? "Emoji plot" : mode == Party.SOUND ? "Soundtrack game" : "Movie quiz night", H * 0.05f, Color.WHITE, true));
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
        if (mode == Party.TRAILER || mode == Party.SOUND) {
            trailerBox(col, q, s.round, result);
        } else if (mode == Party.GUESS && !q.thumb.isEmpty()) {
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
            TextView p = text(q.prompt, mode == Party.EMOJI ? H * 0.13f : H * 0.04f, Color.WHITE, true);
            p.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams((int) (W * 0.82f), (int) (H * 0.4f));
            pp.topMargin = (int) (H * 0.03f);
            if (mode == Party.EMOJI) {
                TextView cap = text("Which film is this?", H * 0.035f, 0xB3FFFFFF, false);
                cap.setGravity(Gravity.CENTER);
                col.addView(cap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                pp = new LinearLayout.LayoutParams((int) (W * 0.82f), (int) (H * 0.3f));
            }
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
                        p.seekTo((long) (p.getDuration() * (mode == Party.SOUND ? 0.5 + new Random().nextDouble() * 0.3 : 0.2 + new Random().nextDouble() * 0.4)));
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
            } else if (mode == Party.SOUND) {
                box.addView(new Equalizer(this), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
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

    /** Dancing sound bars for the soundtrack game: not tied to the audio, just a sign that something is playing. */
    private static final class Equalizer extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float[] speed = new float[28];
        private final float[] phase = new float[28];

        Equalizer(android.content.Context c) {
            super(c);
            Random r = new Random();
            for (int i = 0; i < speed.length; i++) {
                speed[i] = 1.5f + r.nextFloat() * 4f;
                phase[i] = r.nextFloat() * 6.28f;
            }
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight();
            float t = android.os.SystemClock.uptimeMillis() / 1000f;
            float slot = w / speed.length;
            for (int i = 0; i < speed.length; i++) {
                float v = 0.2f + 0.8f * (0.5f + 0.5f * (float) Math.sin(t * speed[i] + phase[i]))
                        * (0.6f + 0.4f * (float) Math.sin(t * 0.7f + i * 0.4f));
                float bh = h * 0.7f * v;
                paint.setColor(i % 3 == 0 ? 0xFFE8833A : i % 3 == 1 ? 0xFFF0A868 : 0xFFC96A22);
                c.drawRoundRect(i * slot + slot * 0.2f, (h - bh) / 2, i * slot + slot * 0.8f, (h + bh) / 2,
                        slot * 0.3f, slot * 0.3f, paint);
            }
            postInvalidateOnAnimation();
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
