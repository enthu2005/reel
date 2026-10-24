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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Every library on the server (Movies, Movies (India), TV Shows, Demo, Photos, Video...). */
public class LibrariesActivity extends ReelActivity {

    private static final int[] COLORS = {0xFF3A2E6B, 0xFF1F4E5F, 0xFF5A2D3C, 0xFF2D4A2F, 0xFF55421F, 0xFF2B3A67};

    private int W;
    private int H;
    private final List<Plex.Section> sections = new ArrayList<>();
    private final java.util.Map<String, Integer> counts = new java.util.HashMap<>();
    private final java.util.Map<String, String> arts = new java.util.HashMap<>();
    private Adapter adapter;
    private RecyclerView grid;
    private TextView message;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Plex.init(this);
        SoundFx.init(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(UiUtil.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (W * 0.06f), (int) (H * 0.09f), (int) (W * 0.06f), (int) (H * 0.05f));

        TextView title = new TextView(this);
        title.setText("Libraries");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.06f);
        title.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        title.setPadding(0, 0, 0, (int) (H * 0.03f));
        col.addView(title);

        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, 3));
        grid.setClipToPadding(false);
        grid.setItemAnimator(null);
        col.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(col);

        message = new TextView(this);
        message.setText("Loading…");
        message.setTextColor(0x99FFFFFF);
        message.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.032f);
        root.addView(message, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);

        Plex.async(Plex::allSections, new Plex.Callback<List<Plex.Section>>() {
            @Override
            public void ok(List<Plex.Section> list) {
                if (isDestroyed()) return;
                sections.clear();
                for (Plex.Section s : list) {
                    if (!s.type.equals("artist")) sections.add(s); // music libraries aren't shown here
                }
                message.setVisibility(sections.isEmpty() ? View.VISIBLE : View.GONE);
                if (sections.isEmpty()) message.setText("No libraries found");
                adapter = new Adapter();
                grid.setAdapter(adapter);
                grid.post(() -> {
                    RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(0);
                    if (vh != null) vh.itemView.requestFocus();
                });
                // How many titles each library holds, filled in as the answers arrive
                for (final Plex.Section s : sections) {
                    Plex.async(() -> Plex.sectionLatest(s.key), new Plex.Callback<Plex.Item>() {
                        @Override
                        public void ok(Plex.Item latest) {
                            if (isDestroyed() || adapter == null || latest == null) return;
                            String path = latest.art != null ? latest.art : latest.poster();
                            if (path == null) return;
                            arts.put(s.key, path);
                            int at = sections.indexOf(s);
                            if (at >= 0) adapter.notifyItemChanged(at);
                        }

                        @Override
                        public void fail(Exception e) {
                        }
                    });
                    Plex.async(() -> Plex.sectionCount(s.key), new Plex.Callback<Integer>() {
                        @Override
                        public void ok(Integer n) {
                            if (isDestroyed() || adapter == null) return;
                            counts.put(s.key, n);
                            int at = sections.indexOf(s);
                            if (at >= 0) adapter.notifyItemChanged(at);
                        }

                        @Override
                        public void fail(Exception e) {
                        }
                    });
                }
            }

            @Override
            public void fail(Exception e) {
                if (isDestroyed()) return;
                message.setText("Couldn't load libraries: " + e.getMessage());
            }
        });
    }

    private static String kind(String type) {
        switch (type) {
            case "movie":
                return "Movies and videos";
            case "show":
                return "TV shows";
            case "photo":
                return "Photos";
            case "artist":
                return "Music";
            default:
                return type;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final float r = H * 0.022f;
            final FrameLayout card = new FrameLayout(LibrariesActivity.this);
            card.setFocusable(true);
            card.setClickable(true);
            card.setBackground(UiUtil.rounded(UiUtil.CARD, r));
            UiUtil.clipRounded(card, r);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (H * 0.27f));
            int m = (int) (H * 0.012f);
            lp.setMargins(m, m, m, m);
            card.setLayoutParams(lp);

            ImageView art = new ImageView(LibrariesActivity.this);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(art, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            View shade = new View(LibrariesActivity.this);
            shade.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x66000000, 0xE6000000}));
            card.addView(shade, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout text = new LinearLayout(LibrariesActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setGravity(Gravity.BOTTOM);
            int pad = (int) (H * 0.026f);
            text.setPadding(pad, pad, pad, pad);
            TextView kindView = new TextView(LibrariesActivity.this);
            kindView.setTextColor(0xCCFFFFFF);
            kindView.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.022f);
            kindView.setTypeface(UiUtil.font(UiUtil.MEDIUM));
            text.addView(kindView);
            TextView name = new TextView(LibrariesActivity.this);
            name.setTextColor(Color.WHITE);
            name.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.04f);
            name.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.addView(name);
            TextView count = new TextView(LibrariesActivity.this);
            count.setTextColor(0xCCFFFFFF);
            count.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.024f);
            count.setTypeface(UiUtil.font(UiUtil.MEDIUM));
            text.addView(count);
            card.addView(text, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            card.setOnFocusChangeListener((v, f) -> {
                card.setForeground(f ? UiUtil.sheen(r) : null);
                card.setElevation(f ? H * 0.025f : 0f);
                float sc = f ? 1.05f : 1f;
                v.animate().scaleX(sc).scaleY(sc).setDuration(Motion.FAST_MS).setInterpolator(Motion.EASE).start();
                if (f) SoundFx.move();
            });
            return new RecyclerView.ViewHolder(card) {
            };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder vh, int position) {
            final Plex.Section s = sections.get(position);
            final FrameLayout card = (FrameLayout) vh.itemView;
            ImageView art = (ImageView) card.getChildAt(0);
            LinearLayout text = (LinearLayout) card.getChildAt(2);
            ((TextView) text.getChildAt(0)).setText(kind(s.type));
            ((TextView) text.getChildAt(1)).setText(s.title);
            Integer n = counts.get(s.key);
            ((TextView) text.getChildAt(2)).setText(n == null ? "" : n + (n == 1 ? " title" : " titles"));

            String path = arts.get(s.key);
            if (path != null) {
                com.bumptech.glide.Glide.with(art).load(Plex.img(path, 640, 360)).centerCrop().into(art);
            } else {
                art.setImageDrawable(new android.graphics.drawable.ColorDrawable(COLORS[position % COLORS.length]));
            }

            card.setOnClickListener(v -> {
                SoundFx.click();
                Intent i = new Intent(LibrariesActivity.this, BrowseActivity.class);
                i.putExtra("section", s.key);
                i.putExtra("title", s.title);
                i.putExtra("type", s.type);
                startActivity(i);
            });
        }

        @Override
        public int getItemCount() {
            return sections.size();
        }
    }
}
