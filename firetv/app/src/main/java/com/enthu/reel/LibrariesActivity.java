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

/** Every library on the server (Movies, Movies (India), TV Shows, Demo, Photos, Video...). */
public class LibrariesActivity extends ReelActivity {

    private static final int[] COLORS = {0xFF3A2E6B, 0xFF1F4E5F, 0xFF5A2D3C, 0xFF2D4A2F, 0xFF55421F, 0xFF2B3A67};

    private int W;
    private int H;
    private final List<Plex.Section> sections = new ArrayList<>();
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
                sections.addAll(list);
                message.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                if (list.isEmpty()) message.setText("No libraries found");
                grid.setAdapter(new Adapter());
                grid.post(() -> {
                    RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(0);
                    if (vh != null) vh.itemView.requestFocus();
                });
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
            final LinearLayout card = new LinearLayout(LibrariesActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.BOTTOM);
            int pad = (int) (H * 0.03f);
            card.setPadding(pad, pad, pad, pad);
            card.setFocusable(true);
            card.setClickable(true);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (H * 0.26f));
            int m = (int) (H * 0.012f);
            lp.setMargins(m, m, m, m);
            card.setLayoutParams(lp);
            return new RecyclerView.ViewHolder(card) {
            };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder vh, int position) {
            final Plex.Section s = sections.get(position);
            final LinearLayout card = (LinearLayout) vh.itemView;
            card.removeAllViews();
            final float r = H * 0.03f;
            final int color = COLORS[position % COLORS.length];
            card.setBackground(UiUtil.rounded(color, r));

            final TextView name = new TextView(LibrariesActivity.this);
            name.setText(s.title);
            name.setTextColor(Color.WHITE);
            name.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.04f);
            name.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
            name.setMaxLines(2);
            card.addView(name);
            final TextView sub = new TextView(LibrariesActivity.this);
            sub.setText(kind(s.type));
            sub.setTextColor(0xCCFFFFFF);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.026f);
            card.addView(sub);

            card.setOnFocusChangeListener((v, f) -> {
                card.setBackground(UiUtil.rounded(f ? 0xFFFFFFFF : color, r));
                name.setTextColor(f ? 0xFF000000 : Color.WHITE);
                sub.setTextColor(f ? 0xFF444444 : 0xCCFFFFFF);
                float sc = f ? 1.05f : 1f;
                v.animate().scaleX(sc).scaleY(sc).setDuration(130).start();
                if (f) SoundFx.move();
            });
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
