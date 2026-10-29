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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Live: a TV guide of channels made from your libraries. OK on any programme tunes the channel. */
public class LiveActivity extends ReelActivity {

    private static final long WINDOW_MS = 3 * 3_600_000L;
    private static final long HALF_H = 30 * 60_000L;

    private int W;
    private int H;
    private LinearLayout rows;
    private FrameLayout rulerBox;
    private ScrollView scroll;
    private TextView message;
    private TextView clock;
    private TextView dChannel;
    private TextView dTitle;
    private TextView dSub;
    private TextView dTime;
    private List<Live.Channel> channels = new ArrayList<>();
    private View[] nowBlocks = new View[0]; // the programme on air now, per channel row
    private long t0;
    private int gridW;
    private int nameW;
    private int rowH;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat fmt = new SimpleDateFormat("h:mm a", Locale.getDefault());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            clock.setText(fmt.format(new Date()));
            if (!channels.isEmpty() && rolledOver()) {
                keepStart = focusedStart;
                int fr = focusedRow();
                Live.Slot cur = fr < channels.size() && fr < shownNow.length ? Live.nowOn(channels.get(fr)) : null;
                if (cur != null && cur.start != shownNow[fr]) pipChannel = 0; // its programme changed: the little picture follows
                render(focusedRow());
            }
            handler.postDelayed(this, 5_000);
        }
    };

    /** The guide starts at this very minute: what has already been on is not shown, the current programme sits at the left. */
    private static long windowStart() {
        return System.currentTimeMillis();
    }

    private long[] shownNow = new long[0]; // for each channel, when the programme drawn as "on now" began
    private long focusedStart;            // the programme the highlight is on
    private long keepStart;               // refocus this one after a redraw, if it is still there

    /** True once some channel has moved on to its next programme since the guide was drawn. */
    private boolean rolledOver() {
        if (shownNow.length != channels.size()) return true;
        for (int i = 0; i < shownNow.length; i++) {
            Live.Slot s = Live.nowOn(channels.get(i));
            if (s != null && s.start != shownNow[i]) return true;
        }
        return false;
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        t.setTypeface(bold ? UiUtil.font(UiUtil.SEMIBOLD) : UiUtil.font(UiUtil.MEDIUM));
        return t;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Plex.init(this);
        SoundFx.init(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;
        nameW = (int) (W * 0.15f);
        gridW = (int) (W * 0.76f);
        rowH = (int) (H * 0.088f);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(UiUtil.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (W * 0.045f), (int) (H * 0.03f), (int) (W * 0.045f), (int) (H * 0.04f));
        col.setClipChildren(true);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Live", H * 0.06f, Color.WHITE, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView edit = text("Edit channels", H * 0.026f, Color.WHITE, true);
        edit.setPadding((int) (W * 0.014f), (int) (H * 0.012f), (int) (W * 0.014f), (int) (H * 0.012f));
        edit.setBackground(UiUtil.rounded(UiUtil.FILL, H));
        edit.setFocusable(true);
        edit.setOnFocusChangeListener((v, has) -> {
            edit.setBackground(UiUtil.rounded(has ? 0xFFE8833A : UiUtil.FILL, H));
            edit.setTextColor(has ? Color.BLACK : Color.WHITE);
            if (has) SoundFx.move();
        });
        edit.setOnClickListener(v -> {
            SoundFx.click();
            openEditor();
        });
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        el.rightMargin = (int) (W * 0.02f);
        head.addView(edit, el);
        clock = text(fmt.format(new Date()), H * 0.034f, UiUtil.TEXT2, false);
        head.addView(clock);
        col.addView(head);

        // What the highlighted programme is
        LinearLayout det = new LinearLayout(this);
        det.setOrientation(LinearLayout.VERTICAL);
        det.setPadding(0, (int) (H * 0.018f), 0, (int) (H * 0.02f));
        dChannel = text("", H * 0.022f, 0xFFE8833A, true);
        dTitle = text("", H * 0.042f, Color.WHITE, true);
        dTitle.setSingleLine();
        dTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        dSub = text("", H * 0.026f, 0xE6FFFFFF, false);
        dSub.setSingleLine();
        dSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        dTime = text("", H * 0.023f, UiUtil.TEXT2, false);
        det.addView(dChannel);
        det.addView(dTitle);
        det.addView(dSub);
        det.addView(dTime);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (H * 0.23f));
        dl.rightMargin = (int) (H * 0.245f * 16f / 9f + W * 0.02f); // room for the little picture
        col.addView(det, dl);

        rulerBox = new FrameLayout(this);
        col.addView(rulerBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (H * 0.045f)));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        scroll.addView(rows);
        col.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(col);
        pipTex = new android.view.TextureView(this);
        pipTex.setAlpha(0f);
        int pw = (int) (H * 0.245f * 16f / 9f);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(pw, (int) (H * 0.245f), Gravity.TOP | Gravity.END);
        pl.topMargin = (int) (H * 0.105f);
        pl.rightMargin = (int) (W * 0.045f);
        root.addView(pipTex, pl);
        pipBoxW = pw;
        pipBoxH = pl.height;
        pipBoxTop = pl.topMargin;
        pipBoxRight = pl.rightMargin;

        message = text("Setting up your channels…", H * 0.032f, 0x99FFFFFF, false);
        root.addView(message, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);

        Plex.async(Live::load, new Plex.Callback<List<Live.Channel>>() {
            @Override
            public void ok(List<Live.Channel> r) {
                if (isDestroyed()) return;
                channels = r;
                if (r.isEmpty()) {
                    message.setText("No channels yet. They fill up from the genres in your libraries.");
                    return;
                }
                message.setVisibility(View.GONE);
                render(0);
            }

            @Override
            public void fail(Exception e) {
                if (!isDestroyed()) message.setText("Couldn't build the guide: " + e.getMessage());
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 20_000);
        // back from the channel editor: the list may have changed
        if (pipWant != null) pipTo(pipWant); // back from watching: the picture starts again
        if (editing) {
            editing = false;
            Plex.async(Live::load, new Plex.Callback<List<Live.Channel>>() {
                @Override
                public void ok(List<Live.Channel> r) {
                    if (isDestroyed()) return;
                    channels = r;
                    if (r.isEmpty()) {
                        rows.removeAllViews();
                        message.setText("No channels. Press Menu to add or show some.");
                        message.setVisibility(View.VISIBLE);
                    } else {
                        message.setVisibility(View.GONE);
                        render(0);
                    }
                }

                @Override
                public void fail(Exception e) {
                }
            });
        }
    }

    private boolean editing;

    private void openEditor() {
        editing = true;
        startActivity(new Intent(this, LiveEditActivity.class));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (k == KeyEvent.KEYCODE_MENU) {
                openEditor();
                return true;
            }
            // Up and Down land on what is on air now in the next channel, not on a programme hours away
            if ((k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) && getCurrentFocus() != null
                    && getCurrentFocus().getTag() instanceof Integer) {
                int r = (Integer) getCurrentFocus().getTag() + (k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                if (r >= 0 && r < nowBlocks.length && nowBlocks[r] != null) {
                    nowBlocks[r].requestFocus();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        pipStop(); // let go of the little picture: two streams at once can stall the real one
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pipStop();
    }

    // ------------------------------------------------------------------ picture-in-picture

    private android.view.TextureView pipTex;
    private androidx.media3.exoplayer.ExoPlayer pipPlayer;
    private String pipSession;
    private int pipChannel;       // the channel the little picture shows
    private Live.Channel pipWant; // the channel it should show once you rest on it
    private int pipToken;
    private final Runnable pipGo = () -> pipStart(pipWant);

    private boolean pipAllowed() {
        return getSharedPreferences("reel_ui", MODE_PRIVATE).getBoolean("previews", true) && !HomeActivity.quiet(this);
    }

    private void pipTo(Live.Channel c) {
        pipWant = c;
        if (!pipAllowed() || c.num == pipChannel) return;
        handler.removeCallbacks(pipGo);
        handler.postDelayed(pipGo, 700);
    }

    private void pipStart(final Live.Channel c) {
        final Live.Slot s = c == null ? null : Live.nowOn(c);
        if (s == null || isDestroyed()) return;
        pipStop();
        final int mine = ++pipToken;
        pipChannel = c.num;
        pipPlay(mine, c, s, Math.max(0, System.currentTimeMillis() - s.start), 0);
    }

    /**
     * Stage 0 and 1: the light converted stream from the server (the second try is for a server that said no for a
     * moment). Stage 2: the original file, if the converted stream keeps failing. Then it gives up quietly.
     */
    private void pipPlay(final int mine, final Live.Channel c, final Live.Slot s, final long off, final int stage) {
        if (mine != pipToken || isDestroyed()) return;
        if (pipPlayer != null) {
            pipPlayer.release();
            pipPlayer = null;
        }
        if (pipSession != null) {
            Plex.stopTranscode(pipSession);
            pipSession = null;
        }
        if (stage == 2) {
            Plex.async(() -> {
                Plex.Item it = Plex.metadata(s.prog.key);
                return it == null || it.partKey == null ? "" : Plex.directUrl(it);
            }, new Plex.Callback<String>() {
                @Override
                public void ok(String url) {
                    if (!url.isEmpty()) pipOpen(mine, c, s, off, stage, url);
                }

                @Override
                public void fail(Exception e) {
                    // nothing more to try
                }
            });
            return;
        }
        pipOpen(mine, c, s, off, stage, null);
    }

    private void pipOpen(final int mine, final Live.Channel c, final Live.Slot s, final long off, final int stage,
                         final String direct) {
        if (mine != pipToken || isDestroyed()) return;
        java.util.Map<String, String> hdr = new java.util.HashMap<>();
        hdr.put("X-Plex-Client-Profile-Extra", Plex.previewProfile());
        hdr.put("X-Plex-Client-Identifier", Plex.clientId());
        hdr.put("X-Plex-Product", "Reel");
        hdr.put("X-Plex-Client-Profile-Name", "Konvergo");
        final androidx.media3.exoplayer.ExoPlayer p = PreviewHandoff.newPlayer(this, hdr);
        pipPlayer = p;
        p.addListener(new androidx.media3.common.Player.Listener() {
            @Override
            public void onVideoSizeChanged(androidx.media3.common.VideoSize v) {
                if (mine == pipToken) pipFit(v);
            }

            @Override
            public void onRenderedFirstFrame() {
                if (mine == pipToken && pipTex != null) pipTex.animate().alpha(1f).setDuration(Motion.SLOW_MS).start();
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                android.util.Log.w("ReelPip", "channel " + c.num + " " + s.prog.title + " key " + s.prog.key
                        + " stage " + stage + " failed: " + error.getErrorCodeName());
                if (mine != pipToken || stage >= 2) {
                    if (mine == pipToken) pipStop();
                    return;
                }
                // a short pause, then the next try
                handler.postDelayed(() -> pipPlay(mine, c, s, Math.max(0, System.currentTimeMillis() - s.start),
                        stage + 1), stage == 0 ? 1500 : 0);
            }
        });
        p.setVideoTextureView(pipTex);
        if (direct != null) {
            p.setMediaItem(androidx.media3.common.MediaItem.fromUri(direct), off);
        } else {
            pipSession = java.util.UUID.randomUUID().toString();
            p.setMediaItem(new androidx.media3.common.MediaItem.Builder()
                    .setUri(Plex.previewUrl(s.prog.key, off, pipSession))
                    .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8).build());
        }
        p.setPlayWhenReady(true);
        p.prepare();
    }

    /** Fits the picture inside the 16:9 box at its own shape, centred, so wide films are not stretched. */
    private void pipFit(androidx.media3.common.VideoSize v) {
        if (pipTex == null || v.width <= 0 || v.height <= 0) return;
        float ar = v.width * v.pixelWidthHeightRatio / v.height;
        int bw = pipBoxW, bh = pipBoxH;
        int w = bw, h = bh;
        if (ar > (float) bw / bh) h = Math.round(bw / ar);
        else w = Math.round(bh * ar);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pipTex.getLayoutParams();
        lp.width = w;
        lp.height = h;
        lp.topMargin = pipBoxTop + (bh - h) / 2;
        lp.rightMargin = pipBoxRight + (bw - w) / 2;
        pipTex.setLayoutParams(lp);
    }

    private int pipBoxW, pipBoxH, pipBoxTop, pipBoxRight;

    private void pipStop() {
        handler.removeCallbacks(pipGo);
        pipToken++;
        pipChannel = 0;
        if (pipTex != null) pipTex.setAlpha(0f);
        if (pipPlayer != null) {
            pipPlayer.release();
            pipPlayer = null;
        }
        if (pipSession != null) {
            Plex.stopTranscode(pipSession);
            pipSession = null;
        }
    }

    private int focusedRow() {
        View f = getCurrentFocus();
        if (f != null && f.getTag() instanceof Integer) return (Integer) f.getTag();
        return 0;
    }

    private void showDetail(Live.Channel c, Live.Slot s) {
        pipTo(c);
        long now = System.currentTimeMillis();
        boolean live = s.start <= now && now < s.end;
        focusedStart = s.start;
        dChannel.setText("CH " + c.num + "  ·  " + c.name + (live ? "   ·   ON NOW" : ""));
        dTitle.setText(s.prog.title);
        dSub.setText(s.prog.sub);
        long mins = s.prog.durMs / 60_000;
        String dur = mins >= 60 ? (mins / 60) + " h " + (mins % 60) + " min" : mins + " min";
        dTime.setText(fmt.format(new Date(s.start)) + " – " + fmt.format(new Date(s.end)) + "   ·   " + dur
                + (live ? "   ·   " + Math.max(1, (s.end - now) / 60_000) + " min left" : ""));
    }

    private void render(int focusRow) {
        t0 = windowStart();
        long t1 = t0 + WINDOW_MS;
        float ppm = (float) gridW / WINDOW_MS;
        long now = System.currentTimeMillis();

        rulerBox.removeAllViews();
        for (long t = t0 - Math.floorMod(t0, HALF_H) + HALF_H; t < t1; t += HALF_H) {
            TextView r = text(fmt.format(new Date(t)), H * 0.022f, UiUtil.TEXT2, false);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL);
            lp.leftMargin = nameW + (int) ((t - t0) * ppm);
            rulerBox.addView(r, lp);
        }

        rows.removeAllViews();
        nowBlocks = new View[channels.size()];
        shownNow = new long[channels.size()];
        View toFocus = null;
        float gap = H * 0.004f;
        for (int ci = 0; ci < channels.size(); ci++) {
            final Live.Channel c = channels.get(ci);
            Live.Slot onNow = Live.nowOn(c);
            shownNow[ci] = onNow == null ? 0 : onNow.start;
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setClipChildren(false);

            LinearLayout name = new LinearLayout(this);
            name.setOrientation(LinearLayout.VERTICAL);
            name.setGravity(Gravity.CENTER_VERTICAL);
            name.setPadding((int) (W * 0.006f), 0, (int) (W * 0.01f), 0);
            name.addView(text(String.valueOf(c.num), H * 0.032f, 0xFFE8833A, true));
            TextView nm = text(c.name, H * 0.022f, Color.WHITE, false);
            nm.setSingleLine();
            nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.addView(nm);
            line.addView(name, new LinearLayout.LayoutParams(nameW, rowH));

            FrameLayout area = new FrameLayout(this);
            area.setClipChildren(false);
            for (final Live.Slot s : Live.window(c, t0, t1)) {
                long a = Math.max(s.start, t0);
                long b = Math.min(s.end, t1);
                int w = Math.max(1, (int) ((b - a) * ppm - gap * 2));
                final TextView blk = new TextView(this);
                blk.setTag(ci);
                blk.setText(s.prog.title + (s.prog.sub.isEmpty() ? "" : "\n" + s.prog.sub));
                blk.setMaxLines(2);
                blk.setEllipsize(android.text.TextUtils.TruncateAt.END);
                blk.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.022f);
                blk.setTypeface(UiUtil.font(UiUtil.MEDIUM));
                blk.setGravity(Gravity.CENTER_VERTICAL);
                blk.setPadding((int) (W * 0.008f), 0, (int) (W * 0.006f), 0);
                final boolean live = s.start <= now && now < s.end;
                final int idle = live ? 0xFF2B2B30 : UiUtil.CARD;
                blk.setBackground(UiUtil.rounded(idle, H * 0.01f));
                blk.setTextColor(Color.WHITE);
                blk.setFocusable(true);
                blk.setOnFocusChangeListener((v, has) -> {
                    blk.setBackground(UiUtil.rounded(has ? 0xFFE8833A : idle, H * 0.01f));
                    blk.setTextColor(has ? Color.BLACK : Color.WHITE);
                    if (has) showDetail(c, s);
                });
                blk.setOnClickListener(v -> {
                    SoundFx.click();
                    Live.tune(this, c);
                });
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, rowH - (int) (gap * 2));
                lp.leftMargin = (int) ((a - t0) * ppm + gap);
                lp.topMargin = (int) gap;
                area.addView(blk, lp);
                if (live) nowBlocks[ci] = blk;
                if (ci == focusRow && keepStart > 0 && s.start == keepStart && s.end > now) toFocus = blk;
                if (ci == focusRow && toFocus == null && s.start <= now && now < s.end) toFocus = blk;
            }
            line.addView(area, new LinearLayout.LayoutParams(gridW, rowH));
            rows.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH));
        }


        keepStart = 0;
        if (toFocus != null) toFocus.requestFocus();
    }
}
