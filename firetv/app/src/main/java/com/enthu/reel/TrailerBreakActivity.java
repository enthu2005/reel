package com.enthu.reel;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Between two programmes on a Live channel: up to three trailers of what is coming up, like a break on real TV.
 * "Skip trailer" moves to the next one, "Skip all" (or Back) goes straight on to the channel.
 */
public class TrailerBreakActivity extends ReelActivity {
    static final int COUNT = 3;

    private int W;
    private int H;
    private int channelNum;
    private ExoPlayer player;
    private PlayerView view;
    private TextView label;
    private TextView skipOne;
    private TextView skipAll;
    private final List<String> titles = new ArrayList<>();
    private boolean left;

    static boolean enabled(Context c) {
        return c.getSharedPreferences("reel_ui", MODE_PRIVATE).getBoolean("liveTrailers", true);
    }

    /** Goes to the trailer break before [c]'s next programme, or straight to the channel if breaks are off. */
    static void startFor(Context ctx, Live.Channel c) {
        if (!enabled(ctx)) {
            Live.tune(ctx, c);
            return;
        }
        Intent i = new Intent(ctx, TrailerBreakActivity.class);
        i.putExtra("channel", c.num);
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Plex.init(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;
        channelNum = getIntent().getIntExtra("channel", 0);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new PlayerView(this);
        view.setUseController(false);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding((int) (W * 0.04f), (int) (H * 0.02f), (int) (W * 0.04f), (int) (H * 0.02f));
        bar.setBackgroundColor(0xB0000000);
        label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
        label.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        label.setSingleLine();
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setText("Coming up…");
        bar.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        skipOne = button("Skip trailer");
        skipOne.setOnClickListener(v -> {
            SoundFx.click();
            next();
        });
        skipAll = button("Skip all");
        skipAll.setOnClickListener(v -> {
            SoundFx.click();
            goOn();
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = (int) (W * 0.012f);
        bar.addView(skipOne, blp);
        bar.addView(skipAll, blp);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);
        skipOne.requestFocus();
        findTrailers();
    }

    private TextView button(String text) {
        final TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.028f);
        b.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        b.setPadding((int) (W * 0.018f), (int) (H * 0.012f), (int) (W * 0.018f), (int) (H * 0.012f));
        b.setFocusable(true);
        b.setClickable(true);
        final Runnable paint = () -> {
            boolean f = b.hasFocus();
            b.setBackground(UiUtil.rounded(f ? 0xFFFFFFFF : 0x44FFFFFF, H * 0.04f));
            b.setTextColor(f ? 0xFF000000 : Color.WHITE);
        };
        paint.run();
        b.setOnFocusChangeListener((v, f) -> {
            paint.run();
            if (f) SoundFx.move();
        });
        return b;
    }

    /** What is coming up on the channel, each as a trailer if Plex has one (up to three). */
    private void findTrailers() {
        Plex.async(() -> {
            List<String[]> out = new ArrayList<>(); // {url, title}
            Live.Channel ch = Live.byNum(channelNum);
            if (ch == null) return out;
            long now = System.currentTimeMillis();
            Set<String> seen = new HashSet<>();
            for (Live.Slot s : Live.window(ch, now, now + 10 * 3600_000L)) {
                if (out.size() >= COUNT) break;
                try {
                    Plex.Item m = Plex.metadata(s.prog.key);
                    String k = "episode".equals(m.type) && m.gpKey != null ? m.gpKey : m.key;
                    if (!seen.add(k)) continue;
                    String url = Plex.trailerUrl(k);
                    if (url != null) out.add(new String[]{url, m.displayTitle()});
                } catch (Exception ignored) {
                    // no trailer for that one: try the next
                }
            }
            return out;
        }, new Plex.Callback<List<String[]>>() {
            @Override
            public void ok(List<String[]> found) {
                if (isDestroyed() || left) return;
                if (found.isEmpty()) {
                    goOn();
                    return;
                }
                play(found);
            }

            @Override
            public void fail(Exception e) {
                if (!isDestroyed()) goOn();
            }
        });
    }

    private void play(List<String[]> found) {
        player = PreviewHandoff.newPlayer(this);
        view.setPlayer(player);
        List<MediaItem> items = new ArrayList<>();
        for (String[] f : found) {
            items.add(MediaItem.fromUri(f[0]));
            titles.add(f[1]);
        }
        player.setMediaItems(items);
        player.setVolume(1f);
        player.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(MediaItem mediaItem, int reason) {
                showLabel();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) goOn();
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                // a trailer that won't play is skipped
                if (player != null && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem();
                    player.prepare();
                    player.play();
                } else {
                    goOn();
                }
            }
        });
        showLabel();
        player.prepare();
        player.play();
    }

    private void showLabel() {
        if (player == null) return;
        int i = player.getCurrentMediaItemIndex();
        label.setText("Coming up   " + (i + 1) + " of " + titles.size() + "   ·   "
                + (i < titles.size() ? titles.get(i) : ""));
        skipOne.setText(i + 1 >= titles.size() ? "Skip last trailer" : "Skip trailer");
    }

    private void next() {
        if (player != null && player.hasNextMediaItem()) {
            player.seekToNextMediaItem();
            player.play();
        } else {
            goOn();
        }
    }

    /** The break is over (or skipped): on to the channel, joining it where the clock has got to. */
    private void goOn() {
        if (left) return;
        left = true;
        Live.Channel c = Live.byNum(channelNum);
        if (player != null) {
            player.release();
            player = null;
        }
        if (c != null) Live.tune(this, c);
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) goOn(); // Back skips the whole break
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onDestroy() {
        left = true;
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
