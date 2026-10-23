package com.enthu.reel;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Browse one library (or a photo album) as a grid. */
public class BrowseActivity extends ReelActivity implements PosterAdapter.Listener {

    private static final String[][] SORTS = {
            {"A to Z", "titleSort"}, {"Recently added", "addedAt:desc"}, {"Newest first", "year:desc"}};

    private final List<Plex.Item> items = new ArrayList<>();
    private int W;
    private int H;
    private String section;
    private String parent;
    private String path;
    private String type = "";
    private int sort = 0;
    private int loadToken = 0;
    private RecyclerView grid;
    private TextView message;
    private TextView count;
    private TextView sortBtn;
    private boolean landscape;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Plex.init(this);
        SoundFx.init(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;
        section = getIntent().getStringExtra("section");
        parent = getIntent().getStringExtra("parent");
        path = getIntent().getStringExtra("path");
        String name = getIntent().getStringExtra("title");
        String t = getIntent().getStringExtra("type");
        type = t == null ? "" : t;
        landscape = type.equals("photo") || parent != null;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(UiUtil.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (W * 0.045f), (int) (H * 0.07f), (int) (W * 0.03f), 0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(name == null ? "Browse" : name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.055f);
        title.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        head.addView(title);
        count = new TextView(this);
        count.setTextColor(0x99FFFFFF);
        count.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.028f);
        count.setPadding((int) (W * 0.015f), 0, 0, 0);
        head.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (section != null) {
            sortBtn = new TextView(this);
            sortBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.028f);
            sortBtn.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
            sortBtn.setPadding((int) (W * 0.018f), (int) (H * 0.012f), (int) (W * 0.018f), (int) (H * 0.012f));
            sortBtn.setFocusable(true);
            sortBtn.setClickable(true);
            styleSort(false);
            sortBtn.setOnFocusChangeListener((v, f) -> {
                styleSort(f);
                if (f) SoundFx.move();
            });
            sortBtn.setOnClickListener(v -> {
                SoundFx.click();
                sort = (sort + 1) % SORTS.length;
                load();
            });
            head.addView(sortBtn);
        }
        col.addView(head);

        int cols = landscape ? 4 : 6;
        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, cols));
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setItemAnimator(null);
        grid.setItemViewCacheSize(24);
        grid.setPadding(0, (int) (H * 0.025f), (int) (W * 0.015f), (int) (H * 0.06f));
        col.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(col);

        message = new TextView(this);
        message.setTextColor(0x99FFFFFF);
        message.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.032f);
        root.addView(message, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        preview = new PreviewCard(this, root, PreviewCard::plexTrailer); // live previews on the posters
        load();
    }

    private void styleSort(boolean focused) {
        sortBtn.setText("Sort: " + SORTS[sort][0]);
        sortBtn.setBackground(UiUtil.rounded(focused ? 0xFFFFFFFF : 0x33FFFFFF, H * 0.04f));
        sortBtn.setTextColor(focused ? 0xFF000000 : Color.WHITE);
    }

    private void load() {
        final int mine = ++loadToken;
        message.setText("Loading…");
        message.setVisibility(View.VISIBLE);
        if (sortBtn != null) styleSort(sortBtn.hasFocus());
        final String url = path != null ? Plex.paged(path, 3000) : section != null
                ? Plex.paged("/library/sections/" + section + "/all?sort=" + SORTS[sort][1], 5000)
                : Plex.paged("/library/metadata/" + parent + "/children", 5000);
        Plex.async(() -> Plex.items(url), new Plex.Callback<List<Plex.Item>>() {
            @Override
            public void ok(List<Plex.Item> list) {
                if (isDestroyed() || mine != loadToken) return;
                items.clear();
                items.addAll(list);
                count.setText(list.size() + (list.size() == 1 ? " item" : " items"));
                message.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                if (list.isEmpty()) message.setText("Nothing in here");
                int cols = landscape ? 4 : 6;
                int w = (int) ((W * 0.915f) / cols) - UiUtil.dp(BrowseActivity.this, 14);
                int h = landscape ? (int) (w * 9f / 16f) : (int) (w * 3f / 2f);
                grid.setAdapter(new PosterAdapter(items, w, h, landscape, BrowseActivity.this).withTitles());
                if (sortBtn == null || !sortBtn.hasFocus()) {
                    grid.post(() -> {
                        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(0);
                        if (vh != null) vh.itemView.requestFocus();
                    });
                }
            }

            @Override
            public void fail(Exception e) {
                if (isDestroyed() || mine != loadToken) return;
                message.setText("Couldn't load this: " + e.getMessage());
            }
        });
    }

    @Override
    public void onFocused(Plex.Item item) {
    }

    private PreviewCard preview;

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (preview != null && preview.handleKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onFocusedView(Plex.Item item, View card) {
        if (preview != null) preview.onFocused(item, card);
    }

    @Override
    public boolean onLongClicked(Plex.Item item) {
        return preview != null && preview.longPress(item);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (preview != null) preview.stop();
    }

    @Override
    public void onClicked(Plex.Item it) {
        if (it.type.equals("photo")) {
            ArrayList<String> urls = new ArrayList<>();
            int at = 0;
            for (Plex.Item o : items) {
                if (!o.type.equals("photo") || o.partKey == null) continue;
                if (o.key.equals(it.key)) at = urls.size();
                urls.add(Plex.raw(o.partKey));
            }
            Intent i = new Intent(this, PhotoActivity.class);
            i.putStringArrayListExtra("urls", urls);
            i.putExtra("index", at);
            startActivity(i);
        } else if (it.type.equals("movie") || it.type.equals("show") || it.type.equals("episode")
                || it.type.equals("season")) {
            final Intent i = new Intent(this, DetailActivity.class);
            i.putExtra("key", it.key);
            if (preview != null && preview.openFullThen(it.key, () -> startActivity(i))) return;
            if (preview != null) preview.handOff(it.key);
            startActivity(i);
        } else {
            // Albums and folders open as another grid.
            Intent i = new Intent(this, BrowseActivity.class);
            i.putExtra("parent", it.key);
            i.putExtra("title", it.title);
            i.putExtra("type", "photo");
            startActivity(i);
        }
    }
}
