package com.enthu.reel;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Discover: trending and popular titles from Seerr, search, and requesting what isn't in Plex yet. */
public class SeerrActivity extends ReelActivity implements PosterAdapter.Listener {

    private static final class Row {
        final String title;
        final List<Plex.Item> items;
        final boolean wide; // category cards are wide

        Row(String title, List<Plex.Item> items, boolean wide) {
            this.title = title;
            this.items = items;
            this.wide = wide;
        }
    }

    /** Genre cards: key -> {type, id, name}. */
    private final Map<String, String[]> genreCards = new HashMap<>();
    // Set when this screen shows one genre as a grid
    private String gType;
    private String gId;
    private String gName;

    private final List<Row> rows = new ArrayList<>();
    private PreviewCard preview;

    /** Your server's trailer file if the title is in Plex, else its YouTube trailer. Background thread. */
    private static String[] trailerSource(Seerr.Media m) throws Exception {
        if (m.ratingKey != null && m.status >= 4) {
            String tk = Plex.trailerKey(m.ratingKey);
            if (tk != null) {
                Plex.Item t = Plex.metadata(tk);
                if (t.partKey != null) return new String[]{"direct", Plex.directUrl(t)};
            }
        }
        String yt = m.youtubeTrailer;
        if (yt == null) yt = Seerr.details(m.type, m.id).youtubeTrailer;
        return yt != null ? new String[]{"yt", yt} : new String[]{"none"};
    }

    @Override
    public void onFocusedView(Plex.Item item, View card) {
        if (preview != null && media.containsKey(item.key)) preview.onFocused(item, card);
    }

    @Override
    public boolean onLongClicked(Plex.Item item) {
        return preview != null && preview.longPress(item);
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (preview != null && preview.handleKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (preview != null) preview.stop();
    }
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Map<String, Seerr.Media> media = new HashMap<>();
    private int W;
    private int H;
    private ImageView backdrop;
    private TextView heroTitle;
    private TextView heroMeta;
    private TextView heroText;
    private TextView message;
    private RecyclerView rowsView;
    private RowAdapter adapter;
    private TextView searchBtn;
    private String searchQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Plex.init(this);
        Seerr.init(this);
        SoundFx.init(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(UiUtil.BG);

        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (H * 0.62f), Gravity.TOP));
        View fadeLeft = new View(this);
        fadeLeft.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF2000000, 0x99000000, 0x00000000}));
        root.addView(fadeLeft, new FrameLayout.LayoutParams((int) (W * 0.7f), (int) (H * 0.62f)));
        View fadeBottom = new View(this);
        fadeBottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xFF000000}));
        FrameLayout.LayoutParams fb = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (H * 0.3f), Gravity.TOP);
        fb.topMargin = (int) (H * 0.32f);
        root.addView(fadeBottom, fb);

        // Header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        gType = getIntent().getStringExtra("genreType");
        gId = getIntent().getStringExtra("genreId");
        gName = getIntent().getStringExtra("genreName");
        title.setText(gId != null ? gName + (gType.equals("tv") ? " Shows" : " Movies") : "Discover");
        title.setTextColor(Color.WHITE);
        title.setTypeface(UiUtil.font(UiUtil.BOLD));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.045f);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchBtn = pill("Search Seerr");
        searchBtn.setOnClickListener(v -> askSearch());
        if (gId == null) head.addView(searchBtn);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        hl.leftMargin = (int) (W * 0.05f);
        hl.rightMargin = (int) (W * 0.05f);
        hl.topMargin = (int) (H * 0.05f);
        root.addView(head, hl);

        // Mini hero for the focused title
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        heroTitle = new TextView(this);
        heroTitle.setTextColor(Color.WHITE);
        heroTitle.setTypeface(UiUtil.font(UiUtil.BOLD));
        heroTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.05f);
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        hero.addView(heroTitle);
        heroMeta = new TextView(this);
        heroMeta.setTextColor(UiUtil.TEXT2);
        heroMeta.setTypeface(UiUtil.font(UiUtil.MEDIUM));
        heroMeta.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.024f);
        heroMeta.setPadding(0, (int) (H * 0.008f), 0, 0);
        hero.addView(heroMeta);
        heroText = new TextView(this);
        heroText.setTextColor(0xD9FFFFFF);
        heroText.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.024f);
        heroText.setMaxLines(3);
        heroText.setEllipsize(TextUtils.TruncateAt.END);
        heroText.setPadding(0, (int) (H * 0.012f), 0, 0);
        hero.addView(heroText);
        FrameLayout.LayoutParams hel = new FrameLayout.LayoutParams((int) (W * 0.45f),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        hel.leftMargin = (int) (W * 0.05f);
        hel.topMargin = (int) (H * 0.16f);
        root.addView(hero, hel);

        rowsView = new RecyclerView(this);
        rowsView.setLayoutManager(new LinearLayoutManager(this));
        rowsView.setClipToPadding(false);
        rowsView.setClipChildren(false);
        rowsView.setItemAnimator(null);
        rowsView.setPadding(0, (int) (H * 0.46f - H * 0.15f), 0, (int) (H * 0.08f));
        rowsView.setVerticalFadingEdgeEnabled(false); // the fade drew dark bands over the raised cards
        adapter = new RowAdapter();
        rowsView.setAdapter(adapter);
        FrameLayout.LayoutParams rowsLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        rowsLp.topMargin = (int) (H * 0.15f); // below the header, so nothing scrolls behind it
        root.addView(rowsView, rowsLp);
        final View heroView = hero;
        rowsView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                // The title and description fade away as the rows slide up over them.
                float a = 1f - Math.min(1f, rv.computeVerticalScrollOffset() / (H * 0.18f));
                heroView.setAlpha(a);
            }
        });

        message = new TextView(this);
        message.setTextColor(UiUtil.TEXT2);
        message.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
        message.setGravity(Gravity.CENTER);
        message.setFocusable(true);
        root.addView(message, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        setContentView(root);
        // Live previews on the cards: your server's trailer if the title is already in Plex,
        // otherwise its YouTube trailer (via Seerr).
        preview = new PreviewCard(this, root, it -> {
            Seerr.Media m = media.get(it.key);
            return m == null ? new String[]{"none"} : trailerSource(m);
        });
        load();

        // Opened from a request on the home screen: show that title straight away.
        int openId = getIntent().getIntExtra("openId", 0);
        if (openId != 0) {
            Seerr.Media stub = new Seerr.Media();
            stub.type = getIntent().getStringExtra("openType");
            stub.id = openId;
            stub.title = "";
            openDetail(stub);
        }
    }

    private TextView pill(String label) {
        final TextView t = new TextView(this);
        t.setText(label);
        t.setTypeface(UiUtil.font(UiUtil.SEMIBOLD));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.026f);
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) (W * 0.022f), (int) (H * 0.016f), (int) (W * 0.022f), (int) (H * 0.016f));
        t.setFocusable(true);
        t.setClickable(true);
        final float r = H * 0.05f;
        UiUtil.stylePill(t, false, r);
        t.setOnFocusChangeListener((v, f) -> {
            UiUtil.stylePill(t, f, r);
            float s = f ? 1.08f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(150).start();
            if (f) SoundFx.move();
        });
        return t;
    }

    private void load() {
        if (!Seerr.configured()) {
            message.setText("Add your Seerr address in Settings to discover and request titles.\nPress OK to open Settings.");
            message.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
            message.setVisibility(View.VISIBLE);
            message.requestFocus();
            return;
        }
        message.setText("Loading…");
        message.setOnClickListener(null);
        message.setVisibility(View.VISIBLE);
        if (gId != null) {
            loadGenre();
            return;
        }
        final String q = searchQuery;
        Plex.async(() -> {
            List<Object[]> out = new ArrayList<>();
            if (!q.isEmpty()) out.add(new Object[]{"Results for \u201C" + q + "\u201D", Seerr.search(q)});
            try {
                out.add(new Object[]{"Your requests", Seerr.myRequests()});
            } catch (Exception ignored) {
            }
            // Because you watched: your latest movie and show in Plex, matched to TMDB
            for (Plex.Section sec : Plex.sections()) {
                boolean movie = sec.type.equals("movie");
                if (!movie && !sec.type.equals("show")) continue;
                try {
                    List<Plex.Item> last = Plex.items(Plex.paged(
                            "/library/sections/" + sec.key + "/all?sort=lastViewedAt:desc", 8));
                    for (Plex.Item w : last) {
                        if (w.viewCount <= 0 && w.viewOffset <= 0) continue;
                        String tmdb = Plex.tmdbId(w.key);
                        if (tmdb == null) continue;
                        List<Seerr.Media> recs = Seerr.recommendations(movie ? "movie" : "tv", tmdb);
                        if (recs.size() >= 3) out.add(new Object[]{"Because you watched " + w.title, recs});
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
            out.add(new Object[]{"Trending", Seerr.trending()});
            try {
                out.add(new Object[]{"Movie categories", Seerr.genres("movie"), "movie"});
            } catch (Exception ignored) {
            }
            out.add(new Object[]{"Popular movies", Seerr.popularMovies()});
            try {
                out.add(new Object[]{"TV categories", Seerr.genres("tv"), "tv"});
            } catch (Exception ignored) {
            }
            out.add(new Object[]{"Popular shows", Seerr.popularShows()});
            out.add(new Object[]{"Coming soon", Seerr.upcomingMovies()});
            return out;
        }, new Plex.Callback<List<Object[]>>() {
            @Override
            @SuppressWarnings("unchecked")
            public void ok(List<Object[]> result) {
                if (isDestroyed()) return;
                rows.clear();
                media.clear();
                genreCards.clear();
                for (Object[] r : result) {
                    List<Plex.Item> items = new ArrayList<>();
                    if (r.length > 2) {
                        // a categories row
                        String type = (String) r[2];
                        for (String[] g : (List<String[]>) r[1]) {
                            Plex.Item it = new Plex.Item();
                            it.key = "genre:" + type + ":" + g[0];
                            it.type = "movie";
                            it.title = g[1];
                            it.art = g[2];
                            it.thumb = g[2];
                            genreCards.put(it.key, new String[]{type, g[0], g[1]});
                            items.add(it);
                        }
                        if (!items.isEmpty()) rows.add(new Row((String) r[0], items, true));
                        continue;
                    }
                    List<Seerr.Media> list = (List<Seerr.Media>) r[1];
                    if (list.isEmpty()) continue;
                    for (Seerr.Media m : list) {
                        media.put(m.key(), m);
                        items.add(m.toItem());
                    }
                    rows.add(new Row((String) r[0], items, false));
                }
                adapter.notifyDataSetChanged();
                if (rows.isEmpty()) {
                    message.setText(q.isEmpty() ? "Seerr didn't return anything." : "Nothing found for \u201C" + q + "\u201D");
                    return;
                }
                message.setVisibility(View.GONE);
                onFocused(rows.get(0).items.get(0));
                rowsView.postDelayed(() -> {
                    RecyclerView.ViewHolder vh = rowsView.findViewHolderForAdapterPosition(0);
                    if (vh instanceof RowVH && ((RowVH) vh).inner.getChildCount() > 0) {
                        ((RowVH) vh).inner.getChildAt(0).requestFocus();
                    }
                }, 250);
            }

            @Override
            public void fail(Exception e) {
                showError(e);
            }
        });
    }

    private void showError(Exception e) {
        if (isDestroyed()) return;
        message.setText("Couldn't reach Seerr: " + e.getMessage()
                + "\nCheck the address in Settings. Press OK to try again.");
        message.setOnClickListener(v -> load());
        message.setVisibility(View.VISIBLE);
        message.requestFocus();
    }

    /** One genre as a grid: the first few pages of the most popular titles in it. */
    private void loadGenre() {
        Plex.async(() -> {
            List<Seerr.Media> all = new ArrayList<>();
            for (int page = 1; page <= 4; page++) {
                try {
                    all.addAll(Seerr.byGenre(gType, gId, page));
                } catch (Exception e) {
                    if (page == 1) throw e;
                    break;
                }
            }
            return all;
        }, new Plex.Callback<List<Seerr.Media>>() {
            @Override
            public void ok(List<Seerr.Media> list) {
                if (isDestroyed()) return;
                media.clear();
                List<Plex.Item> items = new ArrayList<>();
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (Seerr.Media m : list) {
                    if (!seen.add(m.key())) continue;
                    media.put(m.key(), m);
                    items.add(m.toItem());
                }
                if (items.isEmpty()) {
                    message.setText("Nothing in " + gName + " right now.");
                    return;
                }
                message.setVisibility(View.GONE);
                int cols = 7;
                rowsView.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(SeerrActivity.this, cols));
                rowsView.setPadding((int) (W * 0.04f), (int) (H * 0.46f - H * 0.15f), (int) (W * 0.04f), (int) (H * 0.08f));
                int w = (int) ((W * 0.92f) / cols) - (int) (H * 0.022f);
                rowsView.setAdapter(new PosterAdapter(items, w, (int) (w * 1.5f), false, SeerrActivity.this).withTitles());
                onFocused(items.get(0));
                rowsView.postDelayed(() -> {
                    RecyclerView.ViewHolder vh = rowsView.findViewHolderForAdapterPosition(0);
                    if (vh != null) vh.itemView.requestFocus();
                }, 250);
            }

            @Override
            public void fail(Exception e) {
                showError(e);
            }
        });
    }

    // ----------------------------------------------------------- Search

    private void askSearch() {
        SoundFx.click();
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final android.widget.EditText box = new android.widget.EditText(this);
        box.setSingleLine(true);
        box.setHint("Search for a movie or show");
        box.setText(searchQuery);
        box.setTextColor(Color.WHITE);
        box.setHintTextColor(0x66FFFFFF);
        box.setTypeface(UiUtil.font(UiUtil.MEDIUM));
        box.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.04f);
        int p = (int) (H * 0.025f);
        box.setPadding(p, p, p, p);
        box.setBackground(UiUtil.rounded(UiUtil.CARD, H * 0.025f));
        box.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        box.setOnEditorActionListener((v, id, ev) -> {
            d.dismiss();
            return true;
        });
        d.setOnDismissListener(di -> {
            String q = box.getText().toString().trim();
            if (!q.equals(searchQuery)) {
                searchQuery = q;
                searchBtn.setText(q.isEmpty() ? "Search Seerr" : "Search: " + q);
                load();
            }
        });
        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            d.getWindow().setLayout((int) (W * 0.5f), ViewGroup.LayoutParams.WRAP_CONTENT);
            d.getWindow().setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        d.show();
        box.requestFocus();
    }

    // ------------------------------------------------------------ Hero

    @Override
    public void onFocused(Plex.Item item) {
        String[] g = genreCards.get(item.key);
        if (g != null) {
            heroTitle.setText(g[2]);
            heroMeta.setText(g[0].equals("tv") ? "TV category" : "Movie category");
            heroText.setText("Press OK to browse the most popular " + g[2].toLowerCase()
                    + (g[0].equals("tv") ? " shows." : " movies."));
            if (item.art != null) {
                Glide.with(this).load(item.art).centerCrop()
                        .transition(DrawableTransitionOptions.withCrossFade(300)).into(backdrop);
            }
            return;
        }
        Seerr.Media m = media.get(item.key);
        if (m == null) return;
        heroTitle.setText(m.title);
        heroMeta.setText(meta(m));
        heroText.setText(m.overview);
        if (m.backdrop != null) {
            Glide.with(this).load(m.backdrop).centerCrop()
                    .transition(DrawableTransitionOptions.withCrossFade(300)).into(backdrop);
        }
    }

    private static String meta(Seerr.Media m) {
        StringBuilder sb = new StringBuilder(m.type.equals("tv") ? "TV Show" : "Movie");
        if (m.year > 0) sb.append("  ·  ").append(m.year);
        String st = m.statusText();
        if (!st.isEmpty()) sb.append("  ·  ").append(st);
        if (m.dlPercent >= 0) sb.append(" ").append(m.dlPercent).append("%");
        return sb.toString();
    }

    // ---------------------------------------------------------- Details

    @Override
    public void onClicked(Plex.Item item) {
        if (preview != null) preview.stop();
        String[] g = genreCards.get(item.key);
        if (g != null) {
            Intent i = new Intent(this, SeerrActivity.class);
            i.putExtra("genreType", g[0]);
            i.putExtra("genreId", g[1]);
            i.putExtra("genreName", g[2]);
            startActivity(i);
            return;
        }
        final Seerr.Media m = media.get(item.key);
        if (m == null) return;
        openDetail(m);
    }

    /** Fetches the full details (seasons, release dates, download progress) and similar titles. */
    private void openDetail(final Seerr.Media m) {
        Plex.async(() -> {
            Seerr.Media full;
            try {
                full = Seerr.details(m.type, m.id);
                full.requestStatus = m.requestStatus;
            } catch (Exception e) {
                full = m;
            }
            List<Seerr.Media> like = Seerr.moreLike(full);
            return new Object[]{full, like};
        }, new Plex.Callback<Object[]>() {
            @Override
            @SuppressWarnings("unchecked")
            public void ok(Object[] r) {
                if (isDestroyed()) return;
                Seerr.Media full = (Seerr.Media) r[0];
                media.put(full.key(), full);
                showDetail(full, (List<Seerr.Media>) r[1]);
            }

            @Override
            public void fail(Exception e) {
                if (!isDestroyed()) showDetail(m, new ArrayList<>());
            }
        });
    }

    private void showDetail(final Seerr.Media m, final List<Seerr.Media> like) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        ImageView bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (m.backdrop != null) Glide.with(this).load(m.backdrop).centerCrop().into(bg);
        root.addView(bg);
        final FrameLayout trailerBox = new FrameLayout(this);
        trailerBox.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        trailerBox.setAlpha(0f);
        root.addView(trailerBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        View fade = new View(this);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF2000000, 0xB3000000, 0x33000000}));
        root.addView(fade);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(m.title);
        t.setTextColor(Color.WHITE);
        t.setTypeface(UiUtil.font(UiUtil.BOLD));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.06f);
        col.addView(t);
        TextView meta = new TextView(this);
        meta.setText(meta(m));
        meta.setTextColor(UiUtil.TEXT2);
        meta.setTypeface(UiUtil.font(UiUtil.MEDIUM));
        meta.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.025f);
        meta.setPadding(0, (int) (H * 0.01f), 0, 0);
        col.addView(meta);
        String eta = m.eta();
        final TextView et = new TextView(this);
        {
            et.setText(eta);
            et.setTextColor(0xFF000000);
            et.setTypeface(UiUtil.font(UiUtil.SEMIBOLD));
            et.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.022f);
            int ep = (int) (H * 0.012f);
            et.setPadding(ep, ep / 2, ep, ep / 2);
            et.setBackground(UiUtil.rounded(0xE6FFFFFF, H * 0.03f));
            LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            el.topMargin = (int) (H * 0.016f);
            col.addView(et, el);
            et.setVisibility(eta.isEmpty() ? View.GONE : View.VISIBLE);
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = (int) (H * 0.03f);
        col.addView(buttons, bl);
        View first = null;

        if (m.ratingKey != null && m.status >= 4) {
            TextView play = pill("Open in Reel");
            play.setOnClickListener(v -> {
                SoundFx.click();
                d.dismiss();
                Intent i = new Intent(this, DetailActivity.class);
                i.putExtra("key", m.ratingKey);
                startActivity(i);
            });
            buttons.addView(play, gap(buttons));
            first = play;
        }
        if (m.requestable()) {
            if (m.type.equals("movie")) {
                TextView req = pill("Request");
                req.setOnClickListener(v -> request(m, null, d));
                buttons.addView(req, gap(buttons));
                if (first == null) first = req;
            } else {
                TextView all = pill("Request all seasons");
                all.setOnClickListener(v -> {
                    List<Integer> want = new ArrayList<>();
                    for (int[] s : m.seasons) if (s[2] <= 1) want.add(s[0]);
                    request(m, want, d);
                });
                buttons.addView(all, gap(buttons));
                if (first == null) first = all;
                if (m.seasons.size() > 1) {
                    TextView pick = pill("Choose seasons");
                    pick.setOnClickListener(v -> chooseSeasons(m, d));
                    buttons.addView(pick, gap(buttons));
                }
            }
        } else if (m.ratingKey == null || m.status < 4) {
            TextView st = pill(m.statusText().isEmpty() ? "Requested" : m.statusText());
            buttons.addView(st, gap(buttons));
            if (first == null) first = st;
        }

        TextView ov = new TextView(this);
        ov.setText(m.overview);
        ov.setTextColor(0xD9FFFFFF);
        ov.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.026f);
        ov.setLineSpacing(0, 1.1f);
        ov.setMaxLines(like.isEmpty() ? 6 : 4);
        ov.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ol.topMargin = (int) (H * 0.03f);
        col.addView(ov, ol);

        if (m.type.equals("tv") && !m.seasons.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int[] s : m.seasons) {
                sb.append("Season ").append(s[0]).append("  ·  ").append(s[1]).append(" episodes");
                String st = s[2] == 5 ? "In Plex" : s[2] == 4 ? "Partly in Plex" : s[2] == 3 ? "Downloading"
                        : s[2] == 2 ? "Requested" : "";
                if (!st.isEmpty()) sb.append("  ·  ").append(st);
                sb.append('\n');
            }
            TextView ss = new TextView(this);
            ss.setText(sb.toString().trim());
            ss.setTextColor(UiUtil.TEXT2);
            ss.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.022f);
            ss.setLineSpacing(0, 1.2f);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            sl.topMargin = (int) (H * 0.025f);
            col.addView(ss, sl);
        }

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams((int) (W * 0.5f),
                like.isEmpty() ? ViewGroup.LayoutParams.MATCH_PARENT : (int) (H * 0.5f), Gravity.START | Gravity.TOP);
        cl.leftMargin = (int) (W * 0.06f);
        cl.topMargin = (int) (H * 0.1f);
        root.addView(sv, cl);

        if (!like.isEmpty()) {
            View shade = new View(this);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x00000000, 0xE6000000, 0xFF000000}));
            root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (H * 0.5f), Gravity.BOTTOM));
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setClipChildren(false);
            TextView lt = new TextView(this);
            lt.setText("More like this");
            lt.setTextColor(Color.WHITE);
            lt.setTypeface(UiUtil.font(UiUtil.SEMIBOLD));
            lt.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.028f);
            lt.setPadding((int) (W * 0.045f) + (int) (H * 0.011f), 0, 0, (int) (H * 0.004f));
            box.addView(lt);
            RecyclerView rv = new RecyclerView(this);
            rv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            rv.setClipToPadding(false);
            rv.setClipChildren(false);
            rv.setItemAnimator(null);
            rv.setPadding((int) (W * 0.045f), 0, (int) (W * 0.045f), 0);
            List<Plex.Item> items = new ArrayList<>();
            for (Seerr.Media x : like) {
                media.put(x.key(), x);
                items.add(x.toItem());
            }
            int ph = (int) (H * 0.24f);
            rv.setAdapter(new PosterAdapter(items, (int) (ph * 2f / 3f), ph, false, new PosterAdapter.Listener() {
                @Override
                public void onFocused(Plex.Item item) {
                }

                @Override
                public void onClicked(Plex.Item item) {
                    Seerr.Media x = media.get(item.key);
                    if (x != null) openDetail(x);
                }
            }).withTitles());
            box.addView(rv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            ll.bottomMargin = (int) (H * 0.03f);
            root.addView(box, ll);
        }

        d.setContentView(root);

        // The trailer plays quietly behind the page; "Watch trailer" makes it full screen.
        final TextView watch = pill("Watch trailer");
        watch.setVisibility(View.GONE);
        buttons.addView(watch, gap(buttons));
        final Object[] tr = new Object[2]; // {ExoPlayer or null, WebView or null}
        final boolean[] full = {false};
        final boolean[] paused = {false};
        final Runnable showTrailer = () -> {
            trailerBox.animate().alpha(1f).setDuration(600).start();
            watch.setVisibility(View.VISIBLE);
        };
        final Runnable[] setFull = new Runnable[1];
        setFull[0] = () -> {
            for (int i = 0; i < root.getChildCount(); i++) {
                View v = root.getChildAt(i);
                if (v == bg || v == trailerBox) continue;
                v.animate().alpha(full[0] ? 0f : 1f).setDuration(300).start();
            }
            float vol = HomeActivity.previewVolume(this);
            if (tr[0] != null) HomeActivity.fadeVolume((androidx.media3.exoplayer.ExoPlayer) tr[0], Math.min(0.8f, vol), 500);
            YouTubeEmbed.setVolume((android.webkit.WebView) tr[1], Math.min(0.8f, vol));
            if (!full[0]) watch.requestFocus();
        };
        watch.setOnClickListener(v -> {
            SoundFx.click();
            full[0] = true;
            setFull[0].run();
        });
        d.setOnKeyListener((di, code, ev) -> {
            if (!full[0]) return false;
            if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                if (code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER) {
                    paused[0] = !paused[0];
                    if (tr[0] != null) ((androidx.media3.exoplayer.ExoPlayer) tr[0]).setPlayWhenReady(!paused[0]);
                    YouTubeEmbed.pause((android.webkit.WebView) tr[1], paused[0]);
                } else {
                    if (paused[0]) {
                        paused[0] = false;
                        if (tr[0] != null) ((androidx.media3.exoplayer.ExoPlayer) tr[0]).setPlayWhenReady(true);
                        YouTubeEmbed.pause((android.webkit.WebView) tr[1], false);
                    }
                    full[0] = false;
                    setFull[0].run();
                }
            }
            return true;
        });
        final float bgVol = HomeActivity.previewVolume(this);
        String[] cachedSrc = preview != null ? preview.cached(m.key()) : null;
        final Plex.Callback<String[]> startTrailer = new Plex.Callback<String[]>() {
            @Override
            public void ok(String[] src) {
                if (!d.isShowing() || src == null || src[0].equals("none")) return;
                if (!getSharedPreferences("reel_ui", MODE_PRIVATE).getBoolean("detailTrailer", true)) return;
                if (src[0].equals("direct")) {
                    final androidx.media3.exoplayer.ExoPlayer p = PreviewHandoff.newPlayer(SeerrActivity.this);
                    tr[0] = p;
                    p.setVolume(0f);
                    androidx.media3.ui.PlayerView pv = new androidx.media3.ui.PlayerView(SeerrActivity.this);
                    pv.setUseController(false);
                    pv.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                    pv.setShutterBackgroundColor(0);
                    pv.setPlayer(p);
                    trailerBox.addView(pv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
                    p.addListener(new androidx.media3.common.Player.Listener() {
                        @Override
                        public void onRenderedFirstFrame() {
                            showTrailer.run();
                            HomeActivity.fadeVolume(p, bgVol, 1500);
                        }

                        @Override
                        public void onPlaybackStateChanged(int state) {
                            if (state == androidx.media3.common.Player.STATE_ENDED) {
                                trailerBox.animate().alpha(0f).setDuration(500).start();
                                watch.setVisibility(View.GONE);
                                if (full[0]) {
                                    full[0] = false;
                                    setFull[0].run();
                                }
                            }
                        }
                    });
                    p.setMediaItem(androidx.media3.common.MediaItem.fromUri(src[1]));
                    p.prepare();
                    p.play();
                } else {
                    android.webkit.WebView wv = YouTubeEmbed.create(SeerrActivity.this, src[1], bgVol,
                            new YouTubeEmbed.Events() {
                                @Override
                                public void playing() {
                                    showTrailer.run();
                                }

                                @Override
                                public void ended() {
                                    trailerBox.animate().alpha(0f).setDuration(500).start();
                                    watch.setVisibility(View.GONE);
                                    if (full[0]) {
                                        full[0] = false;
                                        setFull[0].run();
                                    }
                                }
                            });
                    tr[1] = wv;
                    trailerBox.addView(wv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
                }
            }

            @Override
            public void fail(Exception e) {
            }
        };
        if (cachedSrc != null) {
            ui.postDelayed(() -> startTrailer.ok(cachedSrc), 500);
        } else {
            Plex.async(() -> trailerSource(m), startTrailer);
        }

        // While the page is open, keep the ETA / download progress live.
        final boolean[] open = {true};
        final Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (!open[0] || isDestroyed()) return;
            Plex.async(() -> Seerr.details(m.type, m.id), new Plex.Callback<Seerr.Media>() {
                @Override
                public void ok(Seerr.Media f) {
                    if (!open[0]) return;
                    f.requestStatus = m.requestStatus;
                    String e = f.eta();
                    et.setText(e);
                    et.setVisibility(e.isEmpty() ? View.GONE : View.VISIBLE);
                    meta.setText(meta(f));
                    if (f.status >= 4 && m.status < 4) {
                        m.status = f.status;
                        m.ratingKey = f.ratingKey;
                        Toast.makeText(SeerrActivity.this, m.title + " is now in Plex", Toast.LENGTH_LONG).show();
                    }
                    ui.postDelayed(poll[0], f.dlPercent >= 0 ? 10_000 : 30_000);
                }

                @Override
                public void fail(Exception e) {
                    if (open[0]) ui.postDelayed(poll[0], 30_000);
                }
            });
        };
        if (m.status != 5) ui.postDelayed(poll[0], 10_000);
        d.setOnDismissListener(di -> {
            open[0] = false;
            ui.removeCallbacks(poll[0]);
            if (tr[0] != null) ((androidx.media3.exoplayer.ExoPlayer) tr[0]).release();
            YouTubeEmbed.destroy((android.webkit.WebView) tr[1]);
            tr[0] = null;
            tr[1] = null;
        });
        d.show();
        if (first != null) first.requestFocus();
    }

    private LinearLayout.LayoutParams gap(LinearLayout row) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (row.getChildCount() > 0) lp.leftMargin = (int) (W * 0.012f);
        return lp;
    }

    private void chooseSeasons(final Seerr.Media m, final Dialog parent) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (H * 0.02f);
        box.setPadding(pad, pad, pad, pad);
        box.setBackground(UiUtil.rounded(0xF21C1C1E, H * 0.03f));
        final List<Integer> chosen = new ArrayList<>();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (final int[] s : m.seasons) {
            final boolean have = s[2] >= 2;
            final TextView r = new TextView(this);
            r.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
            r.setTypeface(UiUtil.font(UiUtil.MEDIUM));
            r.setPadding(pad * 2, pad, pad * 3, pad);
            r.setFocusable(!have);
            r.setClickable(!have);
            r.setAlpha(have ? 0.45f : 1f);
            final Runnable paint = () -> r.setText((chosen.contains(s[0]) ? "✓  " : "      ")
                    + "Season " + s[0] + (have ? "  ·  already requested" : ""));
            paint.run();
            final float rad = H * 0.02f;
            r.setTextColor(Color.WHITE);
            r.setOnFocusChangeListener((v, f) -> {
                r.setBackground(f ? UiUtil.rounded(0xFFFFFFFF, rad) : null);
                r.setTextColor(f ? 0xFF000000 : Color.WHITE);
                if (f) SoundFx.move();
            });
            r.setOnClickListener(v -> {
                SoundFx.click();
                if (chosen.contains(s[0])) chosen.remove(Integer.valueOf(s[0]));
                else chosen.add(s[0]);
                paint.run();
            });
            list.addView(r);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView go = pill("Request selected");
        go.setOnClickListener(v -> {
            if (chosen.isEmpty()) {
                Toast.makeText(this, "Pick at least one season", Toast.LENGTH_SHORT).show();
                return;
            }
            d.dismiss();
            request(m, chosen, parent);
        });
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        gl.topMargin = pad;
        gl.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(go, gl);
        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x99000000));
            d.getWindow().setLayout((int) (W * 0.36f), (int) (H * 0.8f));
        }
        d.show();
        for (int i = 0; i < list.getChildCount(); i++) {
            if (list.getChildAt(i).isFocusable()) {
                list.getChildAt(i).requestFocus();
                break;
            }
        }
    }

    private void request(final Seerr.Media m, final List<Integer> seasons, final Dialog d) {
        SoundFx.click();
        if (m.type.equals("tv") && (seasons == null || seasons.isEmpty())) {
            Toast.makeText(this, "Every season is already requested", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Requesting…", Toast.LENGTH_SHORT).show();
        Plex.async(() -> {
            if (m.type.equals("movie")) Seerr.requestMovie(m.id);
            else Seerr.requestShow(m.id, seasons);
            return true;
        }, new Plex.Callback<Boolean>() {
            @Override
            public void ok(Boolean r) {
                if (isDestroyed()) return;
                Toast.makeText(SeerrActivity.this, "Requested " + m.title
                        + ". It'll show up in Plex once it's ready.", Toast.LENGTH_LONG).show();
                d.dismiss();
                load();
            }

            @Override
            public void fail(Exception e) {
                if (isDestroyed()) return;
                Toast.makeText(SeerrActivity.this, "Couldn't request: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    // ------------------------------------------------------------ Rows

    private static final class RowVH extends RecyclerView.ViewHolder {
        final TextView title;
        final RecyclerView inner;

        RowVH(LinearLayout v, TextView title, RecyclerView inner) {
            super(v);
            this.title = title;
            this.inner = inner;
        }
    }

    private final class RowAdapter extends RecyclerView.Adapter<RowVH> {
        @Override
        public RowVH onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout v = new LinearLayout(SeerrActivity.this);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setClipChildren(false);
            v.setClipToPadding(false);
            v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            int leftPad = (int) (W * 0.045f);
            TextView title = new TextView(SeerrActivity.this);
            title.setTextColor(Color.WHITE);
            title.setTypeface(UiUtil.font(UiUtil.SEMIBOLD));
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
            title.setPadding(leftPad + (int) (H * 0.011f), (int) (H * 0.025f), 0, (int) (H * 0.004f));
            v.addView(title);
            RecyclerView inner = new RecyclerView(SeerrActivity.this);
            inner.setLayoutManager(new LinearLayoutManager(SeerrActivity.this, LinearLayoutManager.HORIZONTAL, false));
            inner.setClipToPadding(false);
            inner.setClipChildren(false);
            inner.setItemAnimator(null);
            inner.setPadding(leftPad, 0, leftPad, 0);
            v.addView(inner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RowVH(v, title, inner);
        }

        @Override
        public void onBindViewHolder(RowVH vh, int position) {
            Row row = rows.get(position);
            vh.title.setText(row.title);
            if (row.wide) {
                int ph = (int) (H * 0.15f);
                int pw = (int) (ph * 16f / 9f);
                vh.inner.setAdapter(new PosterAdapter(row.items, pw, ph, true, SeerrActivity.this));
            } else {
                int ph = (int) (H * 0.26f);
                int pw = (int) (ph * 2f / 3f);
                vh.inner.setAdapter(new PosterAdapter(row.items, pw, ph, false, SeerrActivity.this).withTitles());
            }
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}
