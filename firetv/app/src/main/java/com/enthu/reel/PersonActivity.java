package com.enthu.reel;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** An actor or director: a big picture, a short bio, and their titles from the library underneath. */
public class PersonActivity extends ReelActivity implements PosterAdapter.Listener {

    private final List<Plex.Item> items = new ArrayList<>();
    private int W;
    private int H;
    private ImageView photo;
    private TextView tagline;
    private TextView bio;
    private TextView rowTitle;
    private RecyclerView row;
    private String name;
    private boolean havePhoto;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Plex.init(this);
        SoundFx.init(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;
        name = getIntent().getStringExtra("name");
        String thumb = getIntent().getStringExtra("thumb");
        final String filter = getIntent().getStringExtra("filter");
        if (name == null) name = "";

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(UiUtil.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (W * 0.045f), (int) (H * 0.07f), (int) (W * 0.03f), 0);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        int ph = (int) (H * 0.40f);
        int pw = (int) (ph * 0.78f);
        photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setBackground(UiUtil.rounded(UiUtil.CARD, H * 0.02f));
        UiUtil.clipRounded(photo, H * 0.02f);
        top.addView(photo, new LinearLayout.LayoutParams(pw, ph));
        if (thumb != null) {
            havePhoto = true;
            String url = thumb.startsWith("http") ? thumb : Plex.img(thumb, 600, 800);
            Glide.with(this).load(url).centerCrop().into(photo);
        }

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding((int) (W * 0.03f), 0, (int) (W * 0.04f), 0);
        TextView title = new TextView(this);
        title.setText(name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.07f);
        title.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title);
        tagline = new TextView(this);
        tagline.setTextColor(UiUtil.TEXT2);
        tagline.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
        tagline.setPadding(0, (int) (H * 0.006f), 0, (int) (H * 0.018f));
        tagline.setVisibility(View.GONE);
        info.addView(tagline);
        bio = new TextView(this);
        bio.setTextColor(0xE6FFFFFF);
        bio.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.027f);
        bio.setLineSpacing(0, 1.12f);
        bio.setMaxLines(7);
        bio.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(bio);
        top.addView(info, new LinearLayout.LayoutParams(0, ph, 1f));
        col.addView(top);

        rowTitle = new TextView(this);
        rowTitle.setText("Films & shows");
        rowTitle.setTextColor(Color.WHITE);
        rowTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.034f);
        rowTitle.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        rowTitle.setPadding((int) (H * 0.011f), (int) (H * 0.04f), 0, (int) (H * 0.004f));
        col.addView(rowTitle);

        row = new RecyclerView(this);
        row.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        row.setClipToPadding(false);
        row.setClipChildren(false);
        row.setItemAnimator(null);
        col.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // Their titles in the library
        if (filter != null && !filter.isEmpty()) {
            final String url = Plex.paged("/library/all?" + filter, 500);
            Plex.async(() -> Plex.items(url), new Plex.Callback<List<Plex.Item>>() {
                @Override
                public void ok(List<Plex.Item> list) {
                    if (isDestroyed()) return;
                    items.clear();
                    for (Plex.Item it : list) {
                        if (it.type.equals("movie") || it.type.equals("show")) items.add(it);
                    }
                    if (items.isEmpty()) items.addAll(list);
                    // Newest first
                    java.util.Collections.sort(items, (a, b) -> Integer.compare(b.year, a.year));
                    rowTitle.setText(items.isEmpty() ? "Nothing in your library yet"
                            : "In your library  ·  " + items.size());
                    int w = (int) (H * 0.20f);
                    row.setAdapter(new PosterAdapter(items, w, (int) (w * 1.5f), false,
                            PersonActivity.this).withTitles());
                    row.post(() -> {
                        RecyclerView.ViewHolder vh = row.findViewHolderForAdapterPosition(0);
                        if (vh != null) vh.itemView.requestFocus();
                    });
                }

                @Override
                public void fail(Exception e) {
                    if (isDestroyed()) return;
                    rowTitle.setText("Couldn't load their titles");
                }
            });
        }

        // The bio (and a picture, if Plex had none) from Wikipedia
        final String who = name;
        Plex.async(() -> wiki(who), new Plex.Callback<String[]>() {
            @Override
            public void ok(String[] w) {
                if (isDestroyed() || w == null) return;
                if (w[0] != null && !w[0].isEmpty()) {
                    String d = w[0];
                    tagline.setText(d.substring(0, 1).toUpperCase() + d.substring(1));
                    tagline.setVisibility(View.VISIBLE);
                }
                if (w[1] != null) bio.setText(w[1]);
                if (!havePhoto && w[2] != null) Glide.with(PersonActivity.this).load(w[2]).centerCrop().into(photo);
            }

            @Override
            public void fail(Exception e) {
            }
        });
    }

    private static final Pattern FILMY = Pattern.compile(
            "(?i)actor|actress|director|filmmaker|producer|screenwriter|comedian|singer|writer|performer|presenter|model|rapper");

    /** {short description, summary, picture url} from Wikipedia, or null. */
    private static String[] wiki(String who) throws Exception {
        String[] tries = {who, who + " (actor)", who + " (actress)", who + " (director)"};
        for (String t : tries) {
            try {
                String u = "https://en.wikipedia.org/api/rest_v1/page/summary/"
                        + URLEncoder.encode(t.replace(' ', '_'), "UTF-8").replace("+", "%20");
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "Reel/1.0 (Fire TV Plex client)");
                if (c.getResponseCode() != 200) continue;
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                JSONObject o = new JSONObject(sb.toString());
                String type = o.optString("type");
                String desc = o.optString("description");
                if (!"standard".equals(type) || !FILMY.matcher(desc).find()) continue;
                JSONObject img = o.optJSONObject("originalimage");
                if (img == null) img = o.optJSONObject("thumbnail");
                return new String[]{desc, o.optString("extract"), img != null ? img.optString("source", null) : null};
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    @Override
    public void onFocused(Plex.Item item) {
    }

    @Override
    public void onClicked(Plex.Item it) {
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra("key", it.key);
        startActivity(i);
    }
}
