package com.enthu.reel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.audio.AudioCapabilities;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Plays a Plex item. Tries the original file first (best quality, no server work); if the stick
 * can't play a track, restarts from the same spot through Plex's transcoder.
 *
 * Press the Menu button (the three lines) on the remote for the audio / subtitle / info panel.
 */
public class PlayerActivity extends Activity {

    private static final long TIMELINE_EVERY_MS = 10_000L;
    private static final int TAB_AUDIO = 0;
    private static final int TAB_SUBS = 1;
    private static final int TAB_VIDEO = 2;
    private static final int TAB_INFO = 3;
    private static final String[] TAB_NAMES = {"Audio", "Subtitles", "Video", "Info"};
    private static final int TAB_COUNT = 4;
    // label, max bitrate (kbps), resolution; the first entry means "play the original file"
    private static final Object[][] QUALITIES = {
            {"Original (direct play)", 0, ""},
            {"4K  \u00B7  40 Mbps", 40000, "3840x2160"},
            {"1080p  \u00B7  20 Mbps", 20000, "1920x1080"},
            {"1080p  \u00B7  10 Mbps", 10000, "1920x1080"},
            {"720p  \u00B7  4 Mbps", 4000, "1280x720"},
            {"480p  \u00B7  1.5 Mbps", 1500, "854x480"},
    };
    private int quality = 0;

    // Languages offered when searching for subtitles online: {label, code}
    private static final String[][] SUB_LANGS = {
            {"English", "en"}, {"Tamil", "ta"}, {"Hindi", "hi"}, {"Spanish", "es"},
            {"French", "fr"}, {"German", "de"}, {"Arabic", "ar"}, {"Japanese", "ja"},
    };

    static void start(Context c, String ratingKey, long startMs) {
        Intent i = new Intent(c, PlayerActivity.class);
        i.putExtra("key", ratingKey);
        i.putExtra("start", startMs);
        c.startActivity(i);
    }

    /** The play queue (a shuffled library, say). It outlives each video's screen, so it carries on to the next. */
    private static final List<Plex.Item> sQueue = new ArrayList<>();

    static void startQueue(Context c, List<Plex.Item> items, int startIndex) {
        if (items == null || items.isEmpty()) return;
        List<Plex.Item> good = new ArrayList<>();
        for (Plex.Item it : items) if (it != null && it.key != null && !it.key.isEmpty()) good.add(it);
        if (good.isEmpty()) return;
        sQueue.clear();
        sQueue.addAll(good);
        int start = Math.max(0, Math.min(startIndex, sQueue.size() - 1));
        Intent i = new Intent(c, PlayerActivity.class);
        i.putExtra("key", sQueue.get(start).key);
        i.putExtra("start", 0L);
        i.putExtra("inQueue", true);
        i.putExtra("queueIndex", start);
        c.startActivity(i);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String session = UUID.randomUUID().toString(); // new for every converted stream

    private int W;
    private int H;

    // Playback
    private PlayerView view;
    private int fitMode = 0; // picture fit for this film only: 0 fit, 1 fill (crop), 2 stretch. Not saved.
    private ExoPlayer player;
    private Plex.Item item;
    private String key;
    private boolean transcoding = false;
    private long offsetMs = 0;
    private boolean reportedStop = false;

    // Chosen tracks
    private Plex.Stream curAudio;
    private TextView skipBtn;
    private PlayerOsd osd;
    private long[] skipRange;
    private final Runnable skipTick = new Runnable() {
        @Override
        public void run() {
            checkSkip();
            checkUpNext();
            handler.postDelayed(this, 500);
        }
    };
    private LinearLayout epPanel;
    private androidx.recyclerview.widget.RecyclerView epList;
    private final List<Plex.Item> episodes = new java.util.ArrayList<>();
    private int queueIndex = -1;
    private PosterAdapter epEpisodeAdapter;
    private PosterAdapter epQueueAdapter;
    private TextView epTitle;
    private TextView epPrev;
    private TextView epNext;
    private String moveKey;               // the queue entry being moved (picked up with a long press)
    private boolean moveReleased = false; // OK has been let go since picking it up

    // ------------------------------------------------ Up next (auto-play)
    private static volatile Plex.Item preloaded; // the next episode's details, fetched ahead
    private LinearLayout upNext;
    private ImageView upNextImg;
    private TextView upNextTitle;
    private TextView upNextCount;
    private View upNextBar;
    private long upNextAt = -1;        // last countdown tick (uptime ms)
    private long upNextLeft = UP_NEXT_MS;
    private boolean upNextCancelled = false;
    private boolean upNextPrefetched = false;
    private static final long UP_NEXT_MS = 10_000;

    private boolean autoPlayOn() {
        return getSharedPreferences("reel_ui", MODE_PRIVATE).getBoolean("autoPlay", true);
    }

    // ---- "Still watching?": asks before autoplay carries on once nobody seems to be there
    private static int sChain = 0;          // episodes that started on their own since the last button press
    private static long sChainStart = 0;    // wall clock of that last press (or first start)
    private static long sLastSeen = 0;      // when a player screen last closed
    private LinearLayout stillCard;

    private void touched() {
        sChain = 0;
        sChainStart = System.currentTimeMillis();
    }

    private boolean shouldAskStillWatching() {
        android.content.SharedPreferences p = getSharedPreferences("reel_ui", MODE_PRIVATE);
        if (!p.getBoolean("stillOn", true)) return false;
        if (sChain + 1 < p.getInt("stillEpisodes", 3)) return false;
        boolean night = false;
        if (p.getBoolean("stillNight", true)) {
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            night = hour < 6; // 12am to 6am
        }
        int hours = p.getInt("stillHours", 3);
        boolean long_ = hours > 0 && System.currentTimeMillis() - sChainStart >= hours * 3_600_000L;
        return night || long_;
    }

    /** Autoplay's way to the next episode: asks first if nobody seems to be watching. */
    private void advanceAuto() {
        if (nextEpisode() == null) return;
        if (stillCard != null) return;
        if (!shouldAskStillWatching()) {
            sChain++;
            playNext();
            return;
        }
        if (player != null) player.setPlayWhenReady(false);
        if (upNext != null) upNext.setVisibility(View.GONE);
        stillCard = new LinearLayout(this);
        stillCard.setOrientation(LinearLayout.VERTICAL);
        stillCard.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (H * 0.04f);
        stillCard.setPadding(pad * 2, pad, pad * 2, pad);
        stillCard.setBackground(UiUtil.rounded(0xF01C1C1E, H * 0.03f));
        stillCard.setElevation(H * 0.03f);
        TextView t = new TextView(this);
        t.setText("Still watching?");
        t.setTextColor(0xFFFFFFFF);
        t.setTypeface(UiUtil.font(UiUtil.SEMIBOLD));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.05f);
        stillCard.addView(t);
        TextView s = new TextView(this);
        s.setText(sChain + 1 + " episodes have played without a button press.\nPaused until you answer.");
        s.setTextColor(UiUtil.TEXT2);
        s.setGravity(Gravity.CENTER);
        s.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.025f);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = (int) (H * 0.012f);
        stillCard.addView(s, sl);
        TextView b = new TextView(this);
        b.setText("Yes, keep watching");
        b.setTextColor(0xFF000000);
        b.setTypeface(UiUtil.font(UiUtil.SEMIBOLD));
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.028f);
        b.setBackground(UiUtil.rounded(0xFFE8833A, H * 0.04f));
        b.setPadding(pad, (int) (pad * 0.4f), pad, (int) (pad * 0.4f));
        b.setFocusable(true);
        b.setOnClickListener(v -> {
            touched();
            root.removeView(stillCard);
            stillCard = null;
            playNext();
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = (int) (H * 0.03f);
        stillCard.addView(b, bl);
        root.addView(stillCard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        b.requestFocus();
    }

    // ---- Live channels: playing a programme of the pretend TV schedule (see Live)
    private int channelNum = 0;
    private boolean liveSynced;
    private LinearLayout chanCard;

    /** Next (+1) or previous (-1) channel. */
    private void zap(int delta) {
        List<Live.Channel> all = Live.cached();
        if (channelNum <= 0 || all == null || all.size() < 2) return;
        int at = 0;
        for (int i = 0; i < all.size(); i++) if (all.get(i).num == channelNum) at = i;
        Live.tune(this, all.get(Math.floorMod(at + delta, all.size())));
        finish();
    }

    private LinearLayout chanBanner;

    /** A short banner when a channel is tuned (or surfed to): its number, name, what is on now and what is next. */
    private void showChannelBanner(Live.Channel ch) {
        if (ch == null || root == null || chanBanner != null) return;
        long now = System.currentTimeMillis();
        List<Live.Slot> upcoming = Live.window(ch, now, now + 4 * 3600_000L);
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (H * 0.025f);
        b.setPadding(pad * 2, pad, pad * 2, pad);
        b.setBackground(UiUtil.rounded(0xE61C1C1E, H * 0.025f));
        View logo = ChannelLogo.tile(this, ch.name, (int) (H * 0.1f));
        ((LinearLayout.LayoutParams) logo.getLayoutParams()).rightMargin = pad;
        b.addView(logo);
        TextView num = new TextView(this);
        num.setText(String.valueOf(ch.num));
        num.setTextColor(0xFFE8833A);
        num.setTypeface(UiUtil.font(UiUtil.SEMIBOLD));
        num.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.085f);
        num.setPadding(0, 0, pad * 2, 0);
        b.addView(num);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(ch.name);
        name.setTextColor(0xFFFFFFFF);
        name.setTypeface(UiUtil.font(UiUtil.SEMIBOLD));
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.036f);
        col.addView(name);
        for (int i = 0; i < Math.min(2, upcoming.size()); i++) {
            Live.Slot s = upcoming.get(i);
            TextView t = new TextView(this);
            t.setText((i == 0 ? "Now  " : "Next  ") + s.prog.title + (s.prog.code().isEmpty() ? "" : "  ·  " + s.prog.code()));
            t.setTextColor(i == 0 ? 0xFFFFFFFF : 0xB3FFFFFF);
            t.setTypeface(UiUtil.font(UiUtil.MEDIUM));
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.026f);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setMaxWidth((int) (W * 0.4f));
            col.addView(t);
        }
        b.addView(col);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.leftMargin = (int) (W * 0.04f);
        lp.topMargin = (int) (H * 0.06f);
        b.setAlpha(0f);
        root.addView(b, lp);
        chanBanner = b;
        b.animate().alpha(1f).setDuration(Motion.MEDIUM_MS).start();
        handler.postDelayed(() -> {
            if (chanBanner != b) return;
            b.animate().alpha(0f).setDuration(Motion.LONG_MS).withEndAction(() -> {
                root.removeView(b);
                if (chanBanner == b) chanBanner = null;
            }).start();
        }, 4500);
    }

    private void closeChannels() {
        if (chanCard != null) root.removeView(chanCard);
        chanCard = null;
        view.requestFocus();
    }

    /** A strip of channels with what is on each right now. */
    private void openChannels() {
        List<Live.Channel> all = Live.cached();
        if (all == null || all.isEmpty() || chanCard != null) return;
        chanCard = new LinearLayout(this);
        chanCard.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (H * 0.02f);
        chanCard.setPadding(pad, pad, pad, pad);
        chanCard.setBackground(UiUtil.rounded(0xF01C1C1E, H * 0.025f));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout lst = new LinearLayout(this);
        lst.setOrientation(LinearLayout.VERTICAL);
        sv.addView(lst);
        View current = null;
        for (final Live.Channel c : all) {
            Live.Slot s = Live.nowOn(c);
            final TextView r = new TextView(this);
            r.setText(c.num + "   " + c.name + (s != null ? "\n" + s.prog.title
                    + (s.prog.code().isEmpty() ? "" : "  ·  " + s.prog.code()) : ""));
            r.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.026f);
            r.setTypeface(UiUtil.font(UiUtil.MEDIUM));
            r.setTextColor(0xFFFFFFFF);
            r.setSingleLine(false);
            r.setMaxLines(2);
            r.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.setPadding(pad, (int) (pad * 0.5f), pad, (int) (pad * 0.5f));
            r.setFocusable(true);
            final boolean cur = c.num == channelNum;
            r.setBackground(cur ? UiUtil.rounded(0x33E8833A, H * 0.015f) : null);
            r.setOnFocusChangeListener((v, has) -> {
                r.setBackground(has ? UiUtil.rounded(0xFFE8833A, H * 0.015f)
                        : cur ? UiUtil.rounded(0x33E8833A, H * 0.015f) : null);
                r.setTextColor(has ? 0xFF000000 : 0xFFFFFFFF);
            });
            r.setOnClickListener(v -> {
                if (cur) { closeChannels(); return; }
                Live.tune(this, c);
                finish();
            });
            lst.addView(r, new LinearLayout.LayoutParams(
                    (int) (W * 0.3f), ViewGroup.LayoutParams.WRAP_CONTENT));
            if (cur) current = r;
        }
        chanCard.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (int) (H * 0.6f)));
        root.addView(chanCard, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.START));
        ((FrameLayout.LayoutParams) chanCard.getLayoutParams()).leftMargin = (int) (W * 0.04f);
        ((FrameLayout.LayoutParams) chanCard.getLayoutParams()).topMargin = (int) (H * 0.1f); // below the title
        if (current != null) current.requestFocus();
        else lst.getChildAt(0).requestFocus();
    }

    private Plex.Item nextEpisode() {
        if (channelNum > 0) return null; // a channel carries on by itself
        if (item == null || !"episode".equals(item.type)) return null;
        for (int i = 0; i < episodes.size() - 1; i++) {
            if (episodes.get(i).key.equals(key)) return episodes.get(i + 1);
        }
        return null;
    }

    private boolean queueActive() {
        return !sQueue.isEmpty() && queueIndex >= 0 && queueIndex < sQueue.size();
    }

    /** Starts the queue entry at [index] on a fresh player screen and leaves this one. */
    private void playQueueAt(int index) {
        if (index < 0 || index >= sQueue.size()) return;
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("key", sQueue.get(index).key);
        i.putExtra("start", 0L);
        i.putExtra("inQueue", true);
        i.putExtra("queueIndex", index);
        startActivity(i);
        finish();
    }

    private void playQueueNext() {
        if (!queueActive() || queueIndex + 1 >= sQueue.size()) return;
        playQueueAt(queueIndex + 1);
    }

    private int queuePos(String k) {
        for (int i = 0; i < sQueue.size(); i++) if (sQueue.get(i).key.equals(k)) return i;
        return -1;
    }

    /** Is there a row to pull up with Down: the queue, or a show's episodes. */
    private boolean hasPanelList() {
        return queueActive() ? sQueue.size() > 1 : !episodes.isEmpty();
    }

    /** Where the end credits begin: Plex's credits marker, or 30 seconds before the end. */
    private long creditsStart() {
        long best = -1;
        if (item == null) return best;
        for (long[] r : item.credits) {
            if (item.duration > 0 && r[0] > item.duration * 0.7 && (best < 0 || r[0] < best)) best = r[0];
        }
        if (best < 0 && item.duration > 60_000) best = item.duration - 30_000;
        return best;
    }

    private void buildUpNext() {
        upNext = new LinearLayout(this);
        upNext.setOrientation(LinearLayout.HORIZONTAL);
        upNext.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (H * 0.016f);
        upNext.setPadding(pad, pad, pad * 2, pad);
        upNext.setBackground(UiUtil.rounded(0xE61C1C1E, H * 0.022f));
        upNext.setElevation(H * 0.02f);
        upNext.setVisibility(View.GONE);

        int iw = (int) (W * 0.13f);
        int ih = (int) (iw * 9f / 16f);
        FrameLayout imgBox = new FrameLayout(this);
        upNextImg = new ImageView(this);
        upNextImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        float r = H * 0.012f;
        upNextImg.setBackground(UiUtil.rounded(UiUtil.CARD, r));
        UiUtil.clipRounded(upNextImg, r);
        imgBox.addView(upNextImg, new FrameLayout.LayoutParams(iw, ih));
        upNextBar = new View(this);
        upNextBar.setBackground(UiUtil.rounded(0xFFFFFFFF, 3));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(0, Math.max(4, (int) (H * 0.005f)),
                Gravity.BOTTOM | Gravity.START);
        imgBox.addView(upNextBar, bl);
        upNext.addView(imgBox);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad * 2, 0, 0, 0);
        TextView head = new TextView(this);
        head.setText("Up Next");
        head.setTextColor(UiUtil.TEXT2);
        head.setTypeface(UiUtil.font(UiUtil.SEMIBOLD));
        head.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.021f);
        col.addView(head);
        upNextTitle = new TextView(this);
        upNextTitle.setTextColor(0xFFFFFFFF);
        upNextTitle.setTypeface(UiUtil.font(UiUtil.SEMIBOLD));
        upNextTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.027f);
        upNextTitle.setMaxWidth((int) (W * 0.22f));
        upNextTitle.setMaxLines(2);
        upNextTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(upNextTitle);
        upNextCount = new TextView(this);
        upNextCount.setTextColor(0xFF000000);
        upNextCount.setTypeface(UiUtil.font(UiUtil.SEMIBOLD));
        upNextCount.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.022f);
        upNextCount.setBackground(UiUtil.rounded(0xFFFFFFFF, H * 0.03f));
        upNextCount.setPadding(pad, (int) (pad * 0.4f), pad, (int) (pad * 0.4f));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = (int) (H * 0.012f);
        col.addView(upNextCount, cl);
        TextView help = new TextView(this);
        help.setText("OK to play now · Back to keep watching");
        help.setTextColor(UiUtil.TEXT3);
        help.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.018f);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = (int) (H * 0.008f);
        col.addView(help, hl);
        upNext.addView(col);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        lp.bottomMargin = (int) (H * 0.20f);
        lp.rightMargin = (int) (W * 0.04f);
        root.addView(upNext, lp);
    }

    private boolean upNextShowing() {
        return upNext != null && upNext.getVisibility() == View.VISIBLE;
    }

    private void checkUpNext() {
        if (upNext == null || player == null || item == null || !autoPlayOn()) return;
        final Plex.Item next = nextEpisode();
        if (next == null) return;
        long pos = currentMs();
        long start = creditsStart();
        if (start < 0) return;

        // Ahead of the credits: fetch the next episode's details and get its start ready (on the device or the server's cache).
        if (!upNextPrefetched && pos >= start - 120_000) {
            upNextPrefetched = true;
            Plex.async(() -> {
                Plex.Item m = Plex.metadata(next.key);
                preloaded = m;
                if (m.partKey != null && BigBuffer.enabled(PlayerActivity.this)) {
                    // Extra large buffer on: keep the first minutes of it on the device, so the next episode starts at once
                    BigBuffer.prefetchStart(PlayerActivity.this, Plex.directUrl(m), 96L * 1024 * 1024);
                } else if (m.partKey != null) {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL(Plex.directUrl(m)).openConnection();
                    c.setRequestProperty("Range", "bytes=0-4194303");
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(20000);
                    try (java.io.InputStream in = c.getInputStream()) {
                        byte[] b = new byte[64 * 1024];
                        while (in.read(b) > 0) { /* just warming the server's cache */ }
                    }
                }
                return m;
            }, new Plex.Callback<Plex.Item>() {
                @Override
                public void ok(Plex.Item r) {
                }

                @Override
                public void fail(Exception e) {
                }
            });
        }

        boolean inZone = pos >= start;
        if (!inZone) {
            // Went back before the credits: forget a dismissal and hide the card.
            upNextCancelled = false;
            if (upNextShowing()) hideUpNext();
            return;
        }
        if (upNextCancelled || panelOpen || epOpen) return;
        if (!upNextShowing()) {
            upNextTitle.setText((next.parentIndex > 0 ? "S" + next.parentIndex + ", " : "")
                    + "E" + next.index + " · " + next.title);
            String thumb = next.thumb != null ? next.thumb : next.poster();
            com.bumptech.glide.Glide.with(this).load(Plex.img(thumb, 480, 270)).centerCrop().into(upNextImg);
            upNext.setAlpha(0f);
            upNext.setTranslationX(W * 0.05f);
            upNext.setVisibility(View.VISIBLE);
            upNext.animate().alpha(1f).translationX(0f).setDuration(Motion.MEDIUM_MS)
                    .setInterpolator(Motion.EASE).start();
            upNextAt = android.os.SystemClock.uptimeMillis();
            upNextLeft = UP_NEXT_MS;
            if (skipBtn != null) skipBtn.setVisibility(View.GONE);
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (player.getPlayWhenReady()) upNextLeft -= Math.max(0, now - upNextAt); // holds while paused
        upNextAt = now;
        if (upNextLeft <= 0) {
            advanceAuto();
            return;
        }
        upNextCount.setText(player.getPlayWhenReady() ? "Playing in " + ((upNextLeft + 999) / 1000) : "Paused");
        View box = (View) upNextBar.getParent();
        ViewGroup.LayoutParams lp = upNextBar.getLayoutParams();
        lp.width = (int) (box.getWidth() * (1f - (float) upNextLeft / UP_NEXT_MS));
        upNextBar.setLayoutParams(lp);
    }

    private void hideUpNext() {
        upNext.animate().alpha(0f).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.EASE).withEndAction(() -> upNext.setVisibility(View.GONE)).start();
    }

    private void playNext() {
        Plex.Item next = nextEpisode();
        if (next == null) return;
        reportedStop = true;
        Plex.setWatched(key, true);
        if (item != null) Plex.timeline(key, "stopped", item.duration, item.duration);
        if (upNext != null) upNext.setVisibility(View.GONE);
        PlayerActivity.start(this, next.key, 0);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }
    private boolean epOpen = false;
    private LinearLayout titleBox;
    private TextView subText;
    private ImageView titleLogo;
    private TextView titleText;
    private String lastError = "";
    private boolean baseChecked = false;
    private long lastGood = 0;
    private int earlyEnds = 0;
    private int ioRetries = 0;
    private androidx.media3.exoplayer.DefaultLoadControl loadCtl;
    private int bufferCapMb = 0;
    private String plan = "";
    private boolean tunnelOff = false;  // tunnelled playback was refused (or can't work with this sound)
    private boolean tunnelUsed = false; // the current player was built with tunnelling on
    private boolean forceCompat = false; // set after the receiver refused an audio format
    private boolean subChosen = false;   // only burn subtitles if the user picked one while transcoding
    /** What the TV says it can show, e.g. "Dolby Vision, HDR10, HLG", or "SDR only". */
    static String displayPicture(android.app.Activity a) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            android.view.Display d = a.getWindowManager().getDefaultDisplay();
            android.view.Display.HdrCapabilities c = d.getHdrCapabilities();
            if (c != null) {
                for (int t : c.getSupportedHdrTypes()) {
                    if (t == android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) out.add("Dolby Vision");
                    else if (t == android.view.Display.HdrCapabilities.HDR_TYPE_HDR10) out.add("HDR10");
                    else if (t == android.view.Display.HdrCapabilities.HDR_TYPE_HLG) out.add("HLG");
                    else if (t == 4) out.add("HDR10+");
                }
            }
        } catch (Exception ignored) {
        }
        return out.isEmpty() ? "SDR only (no HDR)" : android.text.TextUtils.join(", ", out);
    }

    /** The Settings checklist: Dolby Vision, pre-filled from what the TV reports until the user changes it. */
    static boolean tvDolbyVision(android.app.Activity a) {
        return a.getSharedPreferences("reel_ui", MODE_PRIVATE).getBoolean("tvDv", displaySupportsDolbyVision(a));
    }

    /** The Settings checklist: HDR10, pre-filled from what the TV reports until the user changes it. */
    static boolean tvHdr10(android.app.Activity a) {
        return a.getSharedPreferences("reel_ui", MODE_PRIVATE).getBoolean("tvHdr", displayPicture(a).contains("HDR10"));
    }

    /** Whether the TV this Fire TV is plugged into says it can show Dolby Vision. */
    static boolean displaySupportsDolbyVision(android.app.Activity a) {
        try {
            android.view.Display d = a.getWindowManager().getDefaultDisplay();
            android.view.Display.HdrCapabilities c = d.getHdrCapabilities();
            if (c != null) {
                for (int t : c.getSupportedHdrTypes()) {
                    if (t == android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean ac3 = false; // ask Plex to convert only the sound to Dolby Digital
    private boolean stereoFallback = false; // the sound system refused Dolby Digital too: stereo AAC from Plex

    private boolean compatMode() {
        return getSharedPreferences("reel_ui", MODE_PRIVATE).getInt("audioMode", 0) == 1;
    }

    private boolean aacToDolby() {
        return getSharedPreferences("reel_ui", MODE_PRIVATE).getBoolean("aacToDolby", false);
    }

    private int audioMode() {
        return getSharedPreferences("reel_ui", MODE_PRIVATE).getInt("audioMode", 0);
    }

    private static boolean isDts(String c) {
        return c.contains("dts") || c.contains("dca");
    }

    /** DTS-HD is cut down to DTS core inside the app when the receiver can't take the full thing. */
    private boolean stripDts() {
        return directFileMode() && (optical() || compatMode() || forceCompat || audioMode() == 2);
    }

    private boolean optical() {
        return getSharedPreferences("reel_ui", MODE_PRIVATE).getInt("output", 0) == 1;
    }

    /** Encodings (AudioFormat numbers) the HDMI / ARC outputs say they accept. */
    private static java.util.Set<Integer> hdmiEncodings(Context c) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        try {
            android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t != android.media.AudioDeviceInfo.TYPE_HDMI && t != android.media.AudioDeviceInfo.TYPE_HDMI_ARC
                        && t != 29 && t != android.media.AudioDeviceInfo.TYPE_LINE_DIGITAL) continue;
                for (int e : d.getEncodings()) out.add(e);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * What the player may send to the receiver as it is. Starts from what the Fire TV reports, then fixes
     * two things it gets wrong on some set-ups: Dolby Digital / Digital Plus the HDMI output says it
     * accepts but the summary leaves out, and a channel limit (often 2) that makes a 5.1 Dolby track
     * fail the check, so it was decoded to PCM instead of passed through.
     */
    private static AudioCapabilities effectiveCaps(Context c) {
        AudioCapabilities real = AudioCapabilities.getCapabilities(c);
        try {
            java.util.LinkedHashSet<Integer> enc = new java.util.LinkedHashSet<>();
            int[] known = {C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT,
                    C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC, C.ENCODING_DTS, C.ENCODING_DTS_HD,
                    C.ENCODING_DOLBY_TRUEHD};
            for (int e : known) if (real.supportsEncoding(e)) enc.add(e);
            java.util.Set<Integer> hdmi = hdmiEncodings(c);
            if (hdmi.contains(C.ENCODING_AC3)) enc.add(C.ENCODING_AC3);
            if (hdmi.contains(C.ENCODING_E_AC3) || hdmi.contains(C.ENCODING_E_AC3_JOC)) enc.add(C.ENCODING_E_AC3);
            if (hdmi.contains(C.ENCODING_E_AC3_JOC)) enc.add(C.ENCODING_E_AC3_JOC);
            boolean dolby = enc.contains(C.ENCODING_AC3) || enc.contains(C.ENCODING_E_AC3);
            int max = dolby ? Math.max(8, real.getMaxChannelCount()) : real.getMaxChannelCount();
            int[] arr = new int[enc.size()];
            int i = 0;
            for (int e : enc) arr[i++] = e;
            AudioCapabilities fixed = new AudioCapabilities(arr, max);
            android.util.Log.d("ReelPlayer", "Audio capabilities as reported: " + real + "  used: " + fixed
                    + "  HDMI says: " + hdmi);
            return fixed;
        } catch (Throwable t) {
            return real;
        }
    }

    /** True when the Fire TV says the chain to the receiver can carry Dolby Digital Plus as it is. */
    private boolean sinkEac3() {
        try {
            return effectiveCaps(this).supportsEncoding(C.ENCODING_E_AC3);
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean isEac3(String c) {
        return c.contains("eac3") || c.contains("ec3");
    }

    private boolean directFileMode() {
        return getSharedPreferences("reel_ui", MODE_PRIVATE).getBoolean("directFile", true);
    }

    private static boolean isHdDts(Plex.Stream s) {
        String c = s.codec == null ? "" : s.codec.toLowerCase(Locale.ROOT);
        if (!isDts(c)) return false;
        String p = ((s.profile == null ? "" : s.profile) + " " + (s.displayTitle == null ? "" : s.displayTitle))
                .toLowerCase(Locale.ROOT);
        return p.contains("ma") || p.contains("hra") || p.contains("hd") || p.contains(":x") || p.contains("dts-x");
    }

    /** True when the track can go to the receiver as it is, with no Plex conversion. */
    private boolean plainPlayable(Plex.Stream s) {
        if (s == null || s.codec == null) return true;
        String c = s.codec.toLowerCase(Locale.ROOT);
        if (c.contains("truehd")) return false;
        if (isDts(c)) return !isHdDts(s) || stripDts();
        if (isEac3(c)) return !optical() && sinkEac3();
        // Optical can't carry multichannel PCM, so AAC/FLAC/Opus/PCM above stereo need Dolby Digital.
        if (optical() && !c.contains("ac3") && !isDts(c) && s.channels > 2) return false;
        return true;
    }

    private boolean needsAc3(Plex.Stream s) {
        if (s == null || s.codec == null) return false;
        String c = s.codec.toLowerCase(Locale.ROOT);
        if (directFileMode()) {
            if (plainPlayable(s)) return false;
            if (optical()) return true; // optical only carries Dolby Digital / DTS core
            if (isEac3(c)) return true; // the chain can't carry Dolby Digital Plus: send Dolby Digital instead of PCM
            return ((compatMode() || forceCompat) && c.contains("truehd"))
                    || (audioMode() == 2 && (c.contains("truehd") || isDts(c)));
        }
        boolean convertAll = audioMode() == 2 && (c.contains("truehd") || isDts(c));
        if (isEac3(c) && (optical() || !sinkEac3())) return true;
        return ((compatMode() || forceCompat) && c.contains("truehd")) || convertAll
                || (aacToDolby() && c.contains("aac"));
    }

    /** Direct file mode: prefer a track in the file the receiver already accepts, so Plex isn't involved. */
    private Plex.Stream betterDirectTrack(Plex.Stream cur) {
        if (!directFileMode() || cur == null || !needsAc3(cur)) return cur;
        Plex.Stream best = null;
        int bestScore = -1;
        for (Plex.Stream o : item.streamsOf(2)) {
            if (o.id == cur.id || o.codec == null || needsAc3(o)) continue;
            if (cur.langCode != null && !cur.langCode.equals(o.langCode)) continue;
            String c = o.codec.toLowerCase(Locale.ROOT);
            // Only a Dolby / DTS track is worth switching to; a stereo AAC track is not (Plex converts to AC3 instead)
            if (!c.contains("ac3") && !isDts(c)) continue;
            int sc = c.contains("ac3") && !c.contains("eac3") ? 40 : isDts(c) ? 30 : 25;
            sc += Math.min(o.channels, 8);
            if (sc > bestScore) { bestScore = sc; best = o; }
        }
        if (best != null) {
            final String lbl = best.label();
            runOnUiThread(() -> Toast.makeText(this, "Playing the " + lbl
                    + " track straight from the file", Toast.LENGTH_LONG).show());
            return best;
        }
        return cur;
    }

    private static boolean isTrueHd(Plex.Stream s) {
        return s != null && s.codec != null && s.codec.toLowerCase(Locale.ROOT).contains("truehd");
    }
    private Plex.Stream curSub;
    private Plex.Stream externalSub;
    private Plex.Stream pendingSub;
    private boolean initialApplied = false;

    // Debug numbers
    private String audioDecoder = "-";
    private String videoDecoder = "-";
    private int dropped = 0;
    private int underruns = 0;
    private boolean overlayOn = false;

    // UI
    private FrameLayout root;
    private TextView hint;
    private TextView overlay;
    private LinearLayout panel;
    private LinearLayout list;
    private ScrollView scroll;
    private final TextView[] tabViews = new TextView[TAB_COUNT];
    private boolean panelOpen = false;
    private int tabIndex = TAB_AUDIO;
    private boolean inSubView = false; // inside the subtitle search screens
    private TextView infoText;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            report("playing");
            handler.postDelayed(this, TIMELINE_EVERY_MS);
        }
    };

    // Frozen-picture watchdog: sound still playing but no new video frame for several seconds
    // (the decoder has stalled). Restart playback from the same spot, up to twice.
    private long wdLastRendered = -1;
    private int wdStalled = 0;
    private int wdRestarts = 0;
    private final Runnable frameWatch = new Runnable() {
        @Override
        public void run() {
            handler.postDelayed(this, 1000);
            if (player == null) return;
            androidx.media3.exoplayer.DecoderCounters dc = player.getVideoDecoderCounters();
            if (dc == null || !player.isPlaying() || player.getVideoSize().width <= 0 || seekingTo >= 0) {
                wdStalled = 0;
                wdLastRendered = -1;
                return;
            }
            dc.ensureUpdated();
            long r = dc.renderedOutputBufferCount;
            if (r == wdLastRendered) wdStalled++;
            else wdStalled = 0;
            wdLastRendered = r;
            if (wdStalled >= 4 && wdRestarts < 2) {
                wdRestarts++;
                wdStalled = 0;
                wdLastRendered = -1;
                android.util.Log.w("ReelPlayer", "Picture froze while the sound played: restarting at " + currentMs());
                begin(currentMs(), transcoding);
            }
        }
    };

    private final Runnable debugTick = new Runnable() {
        @Override
        public void run() {
            if (infoText != null && panelOpen && tabIndex == TAB_INFO) infoText.setText(debugText());
            if (overlayOn) overlay.setText(debugText());
            if ((panelOpen && tabIndex == TAB_INFO) || overlayOn) handler.postDelayed(this, 1000);
        }
    };

    // -------------------------------------------------------- Lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Plex.init(this);
        // Coming back after a while is a fresh start for the "Still watching?" count
        if (sChainStart == 0 || System.currentTimeMillis() - sLastSeen > 10 * 60_000L) touched();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        configureView();
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Small hint shown with the playback controls
        hint = new TextView(this);
        hint.setText("\u25B2  Audio & subtitles");
        hint.setTextColor(0xFFFFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.026f);
        hint.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.NORMAL));
        hint.setPadding((int) (W * 0.014f), (int) (H * 0.012f), (int) (W * 0.014f), (int) (H * 0.012f));
        hint.setBackground(UiUtil.rounded(0x99000000, H * 0.05f));
        hint.setFocusable(false);
        hint.setTypeface(UiUtil.font(UiUtil.MEDIUM));
        hint.setVisibility(View.GONE);
        hint.setOnClickListener(v -> openPanel());
        hint.setOnFocusChangeListener((v, f) ->
                hint.setBackground(UiUtil.rounded(f ? 0xFFFFFFFF : 0x99000000, H * 0.05f)));
        hint.setOnFocusChangeListener((v, f) -> {
            hint.setBackground(UiUtil.rounded(f ? 0xFFFFFFFF : 0x99000000, H * 0.05f));
            hint.setTextColor(f ? 0xFF111111 : 0xFFFFFFFF);
        });
        trickLabel = new TextView(this);
        trickLabel.setTextColor(0xFFFFFFFF);
        trickLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.04f);
        trickLabel.setTypeface(UiUtil.font(UiUtil.MEDIUM));
        trickLabel.setPadding((int) (W * 0.02f), (int) (H * 0.016f), (int) (W * 0.02f), (int) (H * 0.016f));
        trickLabel.setBackground(UiUtil.rounded(0xB3000000, H * 0.06f));
        trickLabel.setVisibility(View.GONE);
        FrameLayout.LayoutParams trickLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        trickLp.topMargin = (int) (H * 0.12f);
        root.addView(trickLabel, trickLp);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        hintLp.topMargin = (int) (H * 0.04f);
        hintLp.rightMargin = (int) (W * 0.03f);
        root.addView(hint, hintLp);
        // Title (or logo) top left, shown together with the controls
        titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setVisibility(View.GONE);
        titleLogo = new ImageView(this);
        titleLogo.setScaleType(ImageView.ScaleType.FIT_START);
        titleLogo.setVisibility(View.GONE);
        titleBox.addView(titleLogo, new LinearLayout.LayoutParams(
                (int) (W * 0.22f), (int) (H * 0.12f)));
        titleText = new TextView(this);
        titleText.setTextColor(0xFFFFFFFF);
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.04f);
        titleText.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        titleText.setShadowLayer(10, 0, 2, 0xAA000000);
        titleText.setMaxLines(2);
        titleText.setMaxWidth((int) (W * 0.5f));
        titleBox.addView(titleText);
        subText = new TextView(this);
        subText.setTextColor(0xE6FFFFFF);
        subText.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
        subText.setShadowLayer(8, 0, 2, 0xAA000000);
        subText.setMaxLines(1);
        subText.setMaxWidth((int) (W * 0.5f));
        subText.setVisibility(View.GONE);
        titleBox.addView(subText);
        FrameLayout.LayoutParams tbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        tbLp.topMargin = (int) (H * 0.04f);
        tbLp.leftMargin = (int) (W * 0.03f);
        root.addView(titleBox, tbLp);
        osd = new PlayerOsd(this, root, W, H, new PlayerOsd.Host() {
            @Override
            public long position() {
                // While a jump is still loading, count from where we're going, not the old spot.
                return seekingTo >= 0 ? seekingTo : currentMs();
            }

            @Override
            public long duration() {
                if (item != null && item.duration > 0) return item.duration;
                return player == null ? 0 : Math.max(0, player.getDuration());
            }

            @Override
            public long buffered() {
                return player == null ? 0 : offsetMs + Math.max(0, player.getBufferedPosition());
            }

            @Override
            public boolean playing() {
                return player != null && player.getPlayWhenReady();
            }

            @Override
            public void setPlaying(boolean play) {
                if (player != null) player.setPlayWhenReady(play);
            }

            @Override
            public void seekTo(long ms) {
                seekAbs(ms);
            }
        });
        osd.setVisibilityListener(vis -> {
            hint.setText(hasPanelList()
                    ? "\u25B2  Audio & subtitles      \u25BC  " + (queueActive() ? "Queue" : "Episodes")
                    : "\u25B2  Audio & subtitles");
            hint.setVisibility(vis && !panelOpen && !epOpen ? View.VISIBLE : View.GONE);
            titleBox.setVisibility(vis ? View.VISIBLE : View.GONE);
        });

        // On-screen audio debug text (off until you switch it on in the Info tab)
        overlay = new TextView(this);
        overlay.setTextColor(0xFF9DFFB0);
        overlay.setTypeface(Typeface.MONOSPACE);
        overlay.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.021f);
        overlay.setBackgroundColor(0xAA000000);
        overlay.setPadding(16, 10, 16, 10);
        overlay.setVisibility(View.GONE);
        FrameLayout.LayoutParams ovLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        ovLp.topMargin = (int) (H * 0.16f);
        ovLp.leftMargin = (int) (W * 0.03f);
        root.addView(overlay, ovLp);

        buildPanel();
        buildEpisodePanel();
        buildSkipButton();
        buildUpNext();
        setContentView(root);
        view.requestFocus();

        key = getIntent().getStringExtra("key");
        channelNum = getIntent().getIntExtra("channel", 0);
        final long startMs = getIntent().getLongExtra("start", 0);
        startedAtMs = startMs;
        if (getIntent().getBooleanExtra("inQueue", false)) {
            queueIndex = getIntent().getIntExtra("queueIndex", queuePos(key));
            if (queueIndex < 0 || queueIndex >= sQueue.size()) queueIndex = sQueue.isEmpty() ? -1 : 0;
        } else {
            sQueue.clear(); // playing something on its own ends any queue
            queueIndex = -1;
        }

        Plex.async(() -> {
            Plex.Item pre = preloaded;
            preloaded = null;
            return pre != null && key.equals(pre.key) ? pre : Plex.metadata(key);
        }, new Plex.Callback<Plex.Item>() {
            @Override
            public void ok(Plex.Item it) {
                if (isDestroyed()) return;
                item = it;
                titleText.setText(it.displayTitle());
                if ("episode".equals(it.type)) {
                    subText.setText((it.parentIndex > 0 ? "S" + it.parentIndex + " \u00B7 " : "")
                            + (it.index > 0 ? "E" + it.index + "  " : "") + it.title);
                    subText.setVisibility(View.VISIBLE);
                }
                if (channelNum > 0) {
                    Live.Channel ch = Live.byNum(channelNum);
                    String ep = "episode".equals(it.type) && it.parentIndex > 0 && it.index > 0
                            ? "Season " + it.parentIndex + " · Episode " + it.index + "  ·  " + it.title : "";
                    subText.setText("CH " + channelNum + (ch != null ? "  ·  " + ch.name : "")
                            + (!ep.isEmpty() ? "  ·  " + ep
                            : subText.getVisibility() == View.VISIBLE ? "  ·  " + subText.getText() : ""));
                    subText.setVisibility(View.VISIBLE);
                    showChannelBanner(ch);
                }
                if (it.logo != null) {
                    com.bumptech.glide.Glide.with(PlayerActivity.this).load(Plex.raw(it.logo))
                            .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                                @Override
                                public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e,
                                        Object m, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> t,
                                        boolean first) {
                                    return false;
                                }

                                @Override
                                public boolean onResourceReady(android.graphics.drawable.Drawable r, Object m,
                                        com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> t,
                                        com.bumptech.glide.load.DataSource d, boolean first) {
                                    titleLogo.setVisibility(View.VISIBLE);
                                    titleText.setVisibility(View.GONE);
                                    return false;
                                }
                            }).into(titleLogo);
                }
                pickInitialTracks();
                osd.loadThumbnails(it.partKey);
                osd.loadChapterThumbnails(it.key);
                Plex.async(() -> Plex.chapterNames(it.key), new Plex.Callback<List<String[]>>() {
                    @Override
                    public void ok(List<String[]> r) {
                        remoteChapters = r;
                    }

                    @Override
                    public void fail(Exception e) {
                    }
                });
                PhoneRemote.attach(PlayerActivity.this, remoteHost);
                if ("episode".equals(it.type) && it.gpKey != null) loadEpisodes(it.gpKey);
                begin(startMs, false);
            }

            @Override
            public void fail(Exception e) {
                Toast.makeText(PlayerActivity.this, "Couldn't start: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    private String preferredSubtitleLanguage() {
        String code = getSharedPreferences("reel_ui", MODE_PRIVATE).getString("subtitleLanguage", "off");
        return code == null ? "off" : code;
    }

    private void pickPreferredSubtitle() {
        String pref = preferredSubtitleLanguage();
        if (pref == null || pref.isEmpty() || "off".equals(pref)) {
            curSub = null;
            subChosen = false;
            externalSub = null;
            pendingSub = null;
            return;
        }
        Plex.Stream match = null;
        for (Plex.Stream s : item.streamsOf(3)) {
            if (s == null || s.langCode == null || s.langCode.isEmpty()) continue;
            String code = s.langCode.toLowerCase(Locale.ROOT);
            if (code.equals(pref) || code.startsWith(pref.toLowerCase(Locale.ROOT))) {
                match = s;
                break;
            }
            if (match == null && s.label().toLowerCase(Locale.ROOT).contains(pref.toLowerCase(Locale.ROOT))) {
                match = s;
            }
        }
        curSub = match;
        if (match == null) {
            subChosen = false;
            externalSub = null;
            pendingSub = null;
            return;
        }
        subChosen = true;
        if (match.isExternal()) {
            externalSub = match;
            pendingSub = null;
        } else {
            externalSub = null;
            pendingSub = match;
        }
    }

    private void pickInitialTracks() {
        curAudio = null;
        curSub = null;
        externalSub = null;
        pendingSub = null;
        subChosen = false;
        List<Plex.Stream> audios = item.streamsOf(2);
        for (Plex.Stream s : audios) if (s.selected) curAudio = s;
        if (curAudio == null && !audios.isEmpty()) curAudio = audios.get(0);
        curAudio = betterDirectTrack(curAudio);
        for (Plex.Stream s : item.streamsOf(3)) if (s.selected) curSub = s;
        if (curSub == null) pickPreferredSubtitle();
    }

    // --------------------------------------------------------- Playback

    /** The converted stream through the Dolby Vision fix (see TsFix). */
    private static androidx.media3.datasource.DataSource.Factory tsFixed(final androidx.media3.datasource.DataSource.Factory f) {
        return () -> new TsFix(f.createDataSource(), null);
    }

    private String lastHlsUrl; // the converted stream's address, for the buffer on disk

    private MediaItem buildItem(boolean transcode) {
        String url = !transcode ? Plex.directUrl(item)
                : stereoFallback
                ? Plex.stereoUrl(key, offsetMs, session, curAudio != null ? curAudio.id : 0, burnId(),
                (Integer) QUALITIES[quality][1], (String) QUALITIES[quality][2])
                : Plex.transcodeUrl(key, offsetMs, session,
                curAudio != null ? curAudio.id : 0, burnId(), ac3,
                (Integer) QUALITIES[quality][1], (String) QUALITIES[quality][2]);
        lastHlsUrl = transcode ? url : null;
        MediaItem.Builder mi = new MediaItem.Builder().setUri(url);
        if (transcode) {
            mi.setMimeType(MimeTypes.APPLICATION_M3U8);
            Plex.Stream cs = sidecarSub();
            if (cs != null) {
                String u = cs.isExternal() ? Plex.raw(cs.key)
                        : Plex.raw("/library/streams/" + cs.id + "?encoding=utf-8&format=srt");
                String mime = cs.isExternal() ? subtitleMime(cs) : MimeTypes.APPLICATION_SUBRIP;
                Uri shifted = shiftedUri(cs);
                mi.setSubtitleConfigurations(java.util.Collections.singletonList(
                        new MediaItem.SubtitleConfiguration.Builder(shifted != null ? shifted : Uri.parse(u))
                                .setMimeType(shifted != null ? MimeTypes.APPLICATION_SUBRIP : mime)
                                .setLanguage(cs.langCode.isEmpty() ? null : cs.langCode)
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                .build()));
            }
        } else if (externalSub != null) {
            Uri shifted = shiftedUri(externalSub);
            mi.setSubtitleConfigurations(java.util.Collections.singletonList(
                    new MediaItem.SubtitleConfiguration.Builder(shifted != null ? shifted
                            : Uri.parse(Plex.raw(externalSub.key)))
                            .setMimeType(shifted != null ? MimeTypes.APPLICATION_SUBRIP : subtitleMime(externalSub))
                            .setLanguage(externalSub.langCode.isEmpty() ? null : externalSub.langCode)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()));
        }
        return mi.build();
    }

    // ------------------------------------------------- Subtitle look and timing
    private long subDelayMs = 0;          // + = subtitles later
    private java.io.File shiftedSub;
    private String shiftedFor = "";
    private TextView timingLabel;
    private final Runnable applyDelay = this::applySubDelay;

    /** Size and background from Settings, applied to text subtitles. */
    private void applySubStyle() {
        androidx.media3.ui.SubtitleView sv = view.getSubtitleView();
        if (sv == null) return;
        android.content.SharedPreferences p = getSharedPreferences("reel_ui", MODE_PRIVATE);
        float[] sizes = {0.8f, 1f, 1.25f, 1.5f};
        int size = Math.max(0, Math.min(3, p.getInt("subSize", 1)));
        int look = p.getInt("subLook", 0); // 0 outline, 1 translucent box, 2 solid box
        int colour = Math.max(0, Math.min(SUB_COLOURS.length - 1, p.getInt("subColour", 0)));
        sv.setApplyEmbeddedFontSizes(false);
        sv.setApplyEmbeddedStyles(look == 0 && colour == 0);
        sv.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * sizes[size]);
        int bg = look == 1 ? 0x99000000 : look == 2 ? 0xFF000000 : 0x00000000;
        int edge = look == 0 ? androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
                : androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE;
        sv.setStyle(new androidx.media3.ui.CaptionStyleCompat(SUB_COLOURS[colour], bg, 0x00000000, edge, 0xFF000000,
                UiUtil.font(UiUtil.SEMIBOLD)));
        sv.setBottomPaddingFraction(subBase());
    }

    private static final String[] SUB_SIZE_NAMES = {"Small", "Medium", "Large", "Extra large"};
    private static final String[] SUB_COLOUR_NAMES = {"White", "Yellow", "Cyan", "Green"};
    private static final int[] SUB_COLOURS = {0xFFFFFFFF, 0xFFFFE066, 0xFF7FE9FF, 0xFF8CFF9E};
    private static final String[] SUB_POS_NAMES = {"Lowest", "Low", "Default", "High", "Highest"};
    private static final float[] SUB_POS = {0.02f, 0.05f, 0.08f, 0.16f, 0.26f};
    private int subFocusRow = -1; // row to put focus back on after a subtitle setting changes

    /** How far up from the bottom edge the subtitles sit, from the saved position. */
    private float subBase() {
        int pos = getSharedPreferences("reel_ui", MODE_PRIVATE).getInt("subPos", 2);
        return SUB_POS[Math.max(0, Math.min(SUB_POS.length - 1, pos))];
    }

    private void cycleSubSetting(String prefKey, int count, int def, View row) {
        android.content.SharedPreferences p = getSharedPreferences("reel_ui", MODE_PRIVATE);
        p.edit().putInt(prefKey, (p.getInt(prefKey, def) + 1) % count).apply();
        applySubStyle();
        applyFit(); // keeps subtitles clear of the edge when the picture is zoomed
        subFocusRow = list.indexOfChild(row);
        showTab(TAB_SUBS);
    }

    /** A copy of the subtitle file with every time moved by subDelayMs, once it's been made. */
    private Uri shiftedUri(Plex.Stream s) {
        if (subDelayMs == 0 || shiftedSub == null || s == null) return null;
        if (!shiftedFor.equals(s.id + ":" + subDelayMs)) return null;
        return Uri.fromFile(shiftedSub);
    }

    private String delayText() {
        if (subDelayMs == 0) return "Timing: in sync with the file";
        String v = String.format(Locale.ROOT, "%+.2f s", subDelayMs / 1000f);
        return "Timing: " + v + (subDelayMs > 0 ? " (subtitles later)" : " (subtitles earlier)");
    }

    private void nudgeDelay(long by) {
        subDelayMs = by == 0 ? 0 : subDelayMs + by;
        if (timingLabel != null) timingLabel.setText(delayText());
        handler.removeCallbacks(applyDelay);
        handler.postDelayed(applyDelay, 1300); // wait for the presses to stop, then apply once
    }

    private void applySubDelay() {
        if (player == null) return;
        final Plex.Stream side = transcoding ? sidecarSub() : externalSub;
        if (side == null) {
            // Subtitles inside the file: the reader shifts them as it goes.
            DtsCoreExtractors.textShiftUs = subDelayMs * 1000L;
            if (!transcoding) rebuildKeepingPosition();
            return;
        }
        if (subDelayMs == 0) {
            begin(currentMs(), transcoding);
            return;
        }
        final long delay = subDelayMs;
        Plex.async(() -> {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                    Plex.raw("/library/streams/" + side.id + "?encoding=utf-8&format=srt")).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            String text;
            try (java.io.InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                text = bo.toString("UTF-8");
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{3})").matcher(text);
            StringBuffer out = new StringBuffer();
            while (m.find()) {
                long t = Long.parseLong(m.group(1)) * 3600000L + Long.parseLong(m.group(2)) * 60000L
                        + Long.parseLong(m.group(3)) * 1000L + Long.parseLong(m.group(4)) + delay;
                t = Math.max(0, t);
                m.appendReplacement(out, String.format(Locale.ROOT, "%02d:%02d:%02d,%03d",
                        t / 3600000L, (t / 60000L) % 60, (t / 1000L) % 60, t % 1000));
            }
            m.appendTail(out);
            java.io.File f = new java.io.File(getCacheDir(), "sub_shift_" + delay + ".srt");
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) {
                fo.write(out.toString().getBytes("UTF-8"));
            }
            return f;
        }, new Plex.Callback<java.io.File>() {
            @Override
            public void ok(java.io.File f) {
                if (isDestroyed() || delay != subDelayMs) return;
                shiftedSub = f;
                shiftedFor = side.id + ":" + delay;
                if (transcoding) begin(currentMs(), true);
                else rebuildKeepingPosition();
            }

            @Override
            public void fail(Exception e) {
                Toast.makeText(PlayerActivity.this, "Couldn't move the subtitles: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static boolean isPicture(Plex.Stream s) {
        String f = ((s.format == null ? "" : s.format) + " " + (s.codec == null ? "" : s.codec))
                .toLowerCase(Locale.ROOT);
        return f.contains("pgs") || f.contains("vobsub") || f.contains("dvd_sub") || f.contains("dvb")
                || f.contains("hdmv");
    }

    /** Picture subtitles (PGS) are the only kind Plex has to burn into the video. */
    private int burnId() {
        return subChosen && curSub != null && isPicture(curSub) ? curSub.id : 0;
    }

    /** A text subtitle the user picked while Plex is transcoding; the app draws it over the video. */
    private Plex.Stream sidecarSub() {
        return subChosen && curSub != null && !isPicture(curSub) ? curSub : null;
    }

    private static String subtitleMime(Plex.Stream s) {
        String f = (s.format.isEmpty() ? s.codec : s.format).toLowerCase(Locale.ROOT);
        if (f.contains("ass") || f.contains("ssa")) return MimeTypes.TEXT_SSA;
        if (f.contains("vtt")) return MimeTypes.TEXT_VTT;
        return MimeTypes.APPLICATION_SUBRIP;
    }

    private int lastPartSub = -1;

    /**
     * Plex burns in whichever subtitle the server has marked selected for this file, even when the
     * request says none. So before any converted stream starts, set the selection to what we want.
     */
    private boolean toneMapChecked;

    /**
     * HDR / Dolby Vision on a TV with no HDR: the stick's own decoder gives noise or washed-out colours,
     * so have Plex convert it to a normal 1080p picture (it tone-maps HDR itself) the first time we start.
     */
    private void autoToneMap() {
        if (toneMapChecked) return;
        toneMapChecked = true;
        if (true) return; // plays the original file, like the Plex app; no server conversion
        if (quality != 0 || item == null) return;
        if (!getSharedPreferences("reel_ui", MODE_PRIVATE).getBoolean("sdrConvert", false)) return;
        boolean hdr = false;
        for (Plex.Stream v : item.streamsOf(1)) {
            if (v.dovi || v.colorTrc.equalsIgnoreCase("smpte2084") || v.colorTrc.equalsIgnoreCase("arib-std-b67")
                    || (v.height >= 1700 && v.bitDepth >= 10)) hdr = true;
        }
        boolean force = getSharedPreferences("reel_ui", MODE_PRIVATE).getBoolean("sdrForce", false);
        String shows = displayPicture(this);
        android.util.Log.d("ReelPlayer", "picture check: hdr/4K10bit file=" + hdr + ", TV shows=" + shows + ", always convert=" + force);
        if (hdr && (force || shows.startsWith("SDR"))) {
            quality = 2; // 1080p, 20 Mbps
            android.util.Log.d("ReelPlayer", "asking Plex for a converted 1080p picture");
            Toast.makeText(this, force ? "Converting the picture to normal colours (1080p) on the server."
                            : "This TV can't show HDR, so Plex is converting the picture to normal colours (1080p).",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Tunnelled playback asks the Fire TV for a direct, hardware-synced sound output, which it refuses
     *  to open for 5.1 sound that the app has to decode itself (AAC, FLAC, PCM...). */
    private boolean tunnelBreaksSound() {
        if (curAudio == null || curAudio.codec == null || curAudio.channels <= 2) return false;
        String c = curAudio.codec.toLowerCase(Locale.ROOT);
        return c.contains("aac") || c.contains("flac") || c.contains("pcm") || c.contains("opus")
                || c.contains("vorbis") || c.contains("mp3") || c.contains("mp2") || c.contains("alac")
                || c.contains("wma");
    }

    private void begin(final long positionMs, final boolean wantTranscode) {
        autoToneMap();
        final boolean willTranscode = wantTranscode || quality != 0 || needsAc3(curAudio);
        final int want = burnId();
        if (!willTranscode || item == null || item.partKey == null || lastPartSub == want) {
            beginNow(positionMs, wantTranscode);
            return;
        }
        releasePlayer();
        Plex.async(() -> {
            // Plex burns in whichever subtitle the server has marked selected, so this has to land: a miss
            // means its pick is burned in under the one the app draws. Try a few times.
            Exception last = null;
            for (int i = 0; i < 3; i++) {
                try {
                    Plex.setPartSubtitle(item.partKey, want);
                    return true;
                } catch (Exception e) {
                    last = e;
                    Thread.sleep(400);
                }
            }
            throw last;
        }, new Plex.Callback<Boolean>() {
            @Override
            public void ok(Boolean r) {
                lastPartSub = want;
                if (!isDestroyed()) beginNow(positionMs, wantTranscode);
            }

            @Override
            public void fail(Exception e) {
                if (isDestroyed()) return;
                Toast.makeText(PlayerActivity.this, "Couldn't reset the server's subtitle choice; you may see a second subtitle",
                        Toast.LENGTH_LONG).show();
                beginNow(positionMs, wantTranscode); // carry on without it
            }
        });
    }

    private void beginNow(long positionMs, boolean transcode) {
        if (transcode || quality != 0 || needsAc3(curAudio)) {
            // Reusing one session id across restarts made Plex mix up the old and new streams.
            Plex.stopTranscode(session);
            session = UUID.randomUUID().toString();
            Plex.activeSession = session;
        } else {
            Plex.activeSession = null;
        }
        releasePlayer();
        PreviewHandoff.releaseLeftover(); // a preview's audio output must not still be open
        if (quality != 0) transcode = true; // a lower quality was chosen in the Video tab
        if (!transcode && needsAc3(curAudio)) {
            // TrueHD can't be turned into a core stream, so Plex converts just the sound to Dolby Digital.
            transcode = true;
            ac3 = true;
        }
        transcoding = transcode;
        DtsCoreExtractors.textShiftUs = subDelayMs * 1000L;
        offsetMs = transcode ? positionMs : 0;
        initialApplied = false;

        // Bitstream (passthrough) is used automatically whenever the TV/receiver reports it can take
        // the format, which covers Dolby TrueHD, Atmos, DTS-HD MA and DTS:X over HDMI.
        final boolean compat = compatMode() || forceCompat || optical();
        // Dual-layer Dolby Vision (profile 7, from disc rips) comes out green on the Fire TV's Dolby Vision
        // decoder. Like Plex, play those through the plain HEVC decoder (the HDR10 picture underneath).
        boolean dv7 = false;
        if (item != null) {
            for (Plex.Stream v : item.streamsOf(1)) {
                if (v.dovi && (v.doviProfile == 7 || v.doviProfile == 4)) dv7 = true;
            }
        }
        final boolean dvAsHevc = dv7;
        // Dolby Vision (any profile with an HDR10 picture underneath, i.e. not profile 5) plays as HDR10
        // unless you've turned that off in Settings: the Cube's decoder gives noise on some DV files.
        boolean dvAny = false;
        if (item != null) {
            for (Plex.Stream v : item.streamsOf(1)) {
                if (v.dovi && v.doviProfile != 5) dvAny = true;
            }
        }
        // Setting: Automatic (Dolby Vision only if this TV does Dolby Vision), always Dolby Vision, or always HDR10.
        String dvMode = "checklist";
        boolean tvDv = tvDolbyVision(this);
        DtsCoreExtractors.dvAsHdr10 = dvAny && !tvDv;
        DtsCoreExtractors.stripToCore = compat || audioMode() == 2;
        DtsCoreExtractors.trackIsDtsHd = curAudio != null && curAudio.codec != null
                && isDts(curAudio.codec.toLowerCase(Locale.ROOT)) && isHdDts(curAudio);
        IecDtsHdSink.enabled = !DtsCoreExtractors.stripToCore && DtsCoreExtractors.trackIsDtsHd
                && getSharedPreferences("reel_ui", MODE_PRIVATE).getBoolean("dtsHdRaw", true);
        android.util.Log.d("ReelPlayer", "DTS-HD raw IEC 61937 stream: " + IecDtsHdSink.enabled);
        android.util.Log.d("ReelPlayer", "DTS-HD cut to DTS core: " + DtsCoreExtractors.stripToCore
                + " (compat " + compat + ", audioMode " + audioMode() + ")");
        android.util.Log.d("ReelPlayer", "Dolby Vision mode " + dvMode + ", TV does Dolby Vision: " + tvDv
                + ", playing DV files as HDR10: " + DtsCoreExtractors.dvAsHdr10);
        if (dvAsHevc) android.util.Log.d("ReelPlayer", "Dolby Vision profile 7: using the plain HEVC decoder");
        androidx.media3.exoplayer.DefaultRenderersFactory rf =
                new androidx.media3.exoplayer.DefaultRenderersFactory(this) {
                    @Override
                    protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(
                            android.content.Context context, boolean enableFloatOutput,
                            boolean enableAudioTrackPlaybackParams) {
                        androidx.media3.exoplayer.audio.DefaultAudioSink.Builder b =
                                new androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                                        .setEnableFloatOutput(enableFloatOutput)
                                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams);
                        if (!compat) {
                            b.setAudioCapabilities(effectiveCaps(context));
                        } else {
                            // Compatible mode: only offer Dolby Digital(+) and plain DTS. A DTS-HD / DTS:X
                            // track then goes out as its DTS core, and Atmos as Dolby Digital Plus.
                            AudioCapabilities real = effectiveCaps(context);
                            int[] all = {C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT,
                                    C.ENCODING_PCM_FLOAT, C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_DTS};
                            int n = 0;
                            int[] keep = new int[all.length];
                            for (int e : all) if (real.supportsEncoding(e)) keep[n++] = e;
                            b.setAudioCapabilities(new AudioCapabilities(
                                    java.util.Arrays.copyOf(keep, n), real.getMaxChannelCount()));
                        }
                        return new IecDtsHdSink(context, b.build());
                    }
                    @Override
                    protected void buildVideoRenderers(android.content.Context context, int extensionRendererMode,
                            final androidx.media3.exoplayer.mediacodec.MediaCodecSelector selector,
                            boolean enableDecoderFallback, android.os.Handler eventHandler,
                            androidx.media3.exoplayer.video.VideoRendererEventListener eventListener,
                            long allowedVideoJoiningTimeMs, java.util.ArrayList<androidx.media3.exoplayer.Renderer> out) {
                        androidx.media3.exoplayer.mediacodec.MediaCodecSelector sel = !dvAsHevc ? selector
                                : (mime, secure, tunneling) -> androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION.equals(mime)
                                        ? java.util.Collections.<androidx.media3.exoplayer.mediacodec.MediaCodecInfo>emptyList()
                                        : selector.getDecoderInfos(mime, secure, tunneling);
                        super.buildVideoRenderers(context, extensionRendererMode, sel, enableDecoderFallback,
                                eventHandler, eventListener, allowedVideoJoiningTimeMs, out);
                    }
                }.setEnableDecoderFallback(true);
        // Don't let the sound start before the first picture is ready (the default lets the video join up to 5 s late)
        rf.setAllowedVideoJoiningTimeMs(0);
        ExoPlayer.Builder pb = new ExoPlayer.Builder(this, rf);
        // The Fire TV Cube has little spare memory and a 4K picture needs most of it for the video decoder.
        // Holding a huge buffer starves the decoder (green lines, stutter, "low memory" in the system log),
        // so keep it modest: about 20 to 30 seconds of a big 4K film, a little more of anything smaller.
        long heap = Runtime.getRuntime().maxMemory();
        final boolean keepFull = BigBuffer.enabled(this) && (transcode || (item != null && item.partKey != null));
        // With the extra large buffer the memory part may be bigger (it is topped up from the on-device store).
        long cap = keepFull
                ? Math.max(96L * 1024 * 1024, Math.min(heap / 3, 224L * 1024 * 1024))
                : Math.max(64L * 1024 * 1024, Math.min(heap / 4, 96L * 1024 * 1024));
        bufferCapMb = (int) (cap / (1024 * 1024));
        // Normally the player fills to 60 s, stops, and only starts again when it has dropped to 30 s (so the
        // buffer saws between 60 and 30). With the extra large buffer the data is already on the device, so keep
        // topping the memory buffer up all the time instead: it stays full.
        loadCtl = new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(keepFull ? 300_000 : 30_000, keepFull ? 300_000 : 60_000, 2_500, 5_000)
                .setTargetBufferBytes((int) cap)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build();
        pb.setLoadControl(loadCtl);
        if (transcode) {
            // Plex reads its client profile from a header; send it on every request to the transcoder.
            java.util.Map<String, String> hdr = new java.util.HashMap<>();
            hdr.put("X-Plex-Client-Profile-Extra", Plex.profileExtra(ac3));
            hdr.put("X-Plex-Client-Identifier", Plex.clientId());
            hdr.put("X-Plex-Product", "Reel");
            hdr.put("X-Plex-Client-Profile-Name", "Konvergo");
            // Copying a big 4K stream takes Plex a while to start and sends fast bursts over the internet:
            // wait longer and retry more before giving up (the defaults are 8 seconds and 3 retries).
            // DefaultDataSource also opens local files (re-timed subtitles), the rest goes over http.
            // With the extra large buffer on, what the converted stream sends also goes through the on-device store
            androidx.media3.datasource.DataSource.Factory hlsSource = BigBuffer.enabled(this)
                    ? BigBuffer.factory(this, hdr)
                    : tsFixed(new androidx.media3.datasource.DefaultDataSource.Factory(this,
                            new androidx.media3.datasource.DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                                    .setConnectTimeoutMs(30000)
                                    .setReadTimeoutMs(60000)
                                    .setDefaultRequestProperties(hdr)));
            pb.setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(hlsSource)
                    .setLoadErrorHandlingPolicy(
                            new androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(8)));
        }
        else {
            // Setting "Extra large buffer": read through a store on the device that a background task fills ahead
            final boolean big = BigBuffer.enabled(this) && item != null && item.partKey != null;
            androidx.media3.datasource.DataSource.Factory direct = big
                    ? BigBuffer.factory(this, key)
                    : new androidx.media3.datasource.DefaultDataSource.Factory(this,
                            new androidx.media3.datasource.DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                                    .setConnectTimeoutMs(30000)
                                    .setReadTimeoutMs(60000));
            if (big) {
                if (bigBuffer == null) bigBuffer = new BigBuffer();
                bigBuffer.start(this, key, Plex.directUrl(item), item.duration);
                handler.removeCallbacks(bigTick);
                handler.post(bigTick);
            }
            pb.setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    direct,
                    new DtsCoreExtractors())
                    .setLoadErrorHandlingPolicy(
                            new androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(8)));
        }
        player = pb.build();
        // Jump to the nearest keyframe when skipping. Exact seeks on 4K HEVC make the picture decode for
        // seconds while the sound is already playing.
        player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC);
        tunnelUsed = false;
        if (getSharedPreferences("reel_ui", MODE_PRIVATE).getBoolean("tunneled", false) && !IecDtsHdSink.enabled
                && !tunnelOff && !tunnelBreaksSound()) {
            tunnelUsed = true;
            android.util.Log.d("ReelPlayer", "tunneled video playback on");
            androidx.media3.exoplayer.trackselection.TrackSelector tsel = player.getTrackSelector();
            if (tsel instanceof androidx.media3.exoplayer.trackselection.DefaultTrackSelector) {
                androidx.media3.exoplayer.trackselection.DefaultTrackSelector dts =
                        (androidx.media3.exoplayer.trackselection.DefaultTrackSelector) tsel;
                dts.setParameters(dts.buildUponParameters().setTunnelingEnabled(true));
            }
        }
        plan = "";
        baseChecked = false;
        if (transcode) {
            final int sid = burnId();
            final boolean a3 = ac3;
            final int aid = curAudio != null ? curAudio.id : 0;
            final long off = positionMs;
            Plex.async(() -> Plex.decision(key, off, session + "-plan", aid, sid, a3,
                            (Integer) QUALITIES[quality][1], (String) QUALITIES[quality][2]),
                    new Plex.Callback<String>() {
                        @Override
                        public void ok(String t) {
                            plan = t;
                        }

                        @Override
                        public void fail(Exception e) {
                            plan = "Couldn't read Plex's plan: " + e.getMessage() + "\n";
                        }
                    });
        }
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        freshView();
        handler.removeCallbacks(frameWatch);
        wdStalled = 0;
        wdLastRendered = -1;
        handler.postDelayed(frameWatch, 4000);
        view.setPlayer(player);
        player.setMediaItem(buildItem(transcode));
        if (transcode && BigBuffer.enabled(this) && lastHlsUrl != null) {
            // Plex is converting: keep the buffer on the disk too, by fetching the converted stream ahead of the playhead
            java.util.Map<String, String> bh = new java.util.HashMap<>();
            bh.put("X-Plex-Client-Profile-Extra", Plex.profileExtra(ac3));
            bh.put("X-Plex-Client-Identifier", Plex.clientId());
            bh.put("X-Plex-Product", "Reel");
            bh.put("X-Plex-Client-Profile-Name", "Konvergo");
            if (bigBuffer == null) bigBuffer = new BigBuffer();
            bigBuffer.startHls(this, lastHlsUrl, bh);
            handler.removeCallbacks(bigTick);
            handler.post(bigTick);
        }
        if (!transcode && positionMs > 0) player.seekTo(positionMs);
        // Exactly one subtitle source at a time: a track the player draws, or one Plex burns in, never both.
        // Without this the player also shows whatever track the file flags as default (an Off choice still
        // showed it, and a second one drawn on top of the one you picked).
        if (transcode) {
            enableText(sidecarSub() != null);
        } else if (externalSub != null) {
            enableText(true);
        } else if (pendingSub == null) {
            if (subChosen && curSub != null) pendingSub = curSub; // re-apply your pick once tracks are known
            else enableText(false);
        }

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                if (!transcoding) {
                    int c = error.errorCode;
                    lastError = error.getErrorCodeName() + (error.getMessage() != null ? " - " + error.getMessage() : "");
                    boolean audioProblem = c == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                            || c == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
                            || c == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                            || c == PlaybackException.ERROR_CODE_DECODING_FAILED
                            || c == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                            || c == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES;
                    // A picture decoder failure (ERROR_CODE_DECODING_FAILED on the video) is not an audio problem.
                    if (error instanceof androidx.media3.exoplayer.ExoPlaybackException) {
                        androidx.media3.common.Format rf0 = ((androidx.media3.exoplayer.ExoPlaybackException) error).rendererFormat;
                        if (rf0 != null && rf0.sampleMimeType != null && !rf0.sampleMimeType.startsWith("audio/")) {
                            audioProblem = false;
                        }
                    }
                    if (audioProblem && tunnelUsed && !tunnelOff) {
                        // The sound output wouldn't open in tunnelled mode: carry on without tunnelling
                        tunnelOff = true;
                        android.util.Log.d("ReelPlayer", "audio refused while tunnelled, retrying without tunnelling");
                        begin(currentMs(), false);
                        return;
                    }
                    if (audioProblem && !forceCompat) {
                        // First retry still direct, offering only DTS core / Dolby Digital to the receiver.
                        forceCompat = true;
                        Toast.makeText(PlayerActivity.this, "Receiver refused that audio ("
                                + error.getErrorCodeName() + "), retrying as DTS core / Dolby Digital",
                                Toast.LENGTH_LONG).show();
                        begin(currentMs(), false);
                        return;
                    }
                    if (audioProblem && forceCompat && curAudio != null && curAudio.codec != null) {
                        String cc = curAudio.codec.toLowerCase(Locale.ROOT);
                        if (cc.contains("truehd") || isDts(cc)) {
                            // The Fire TV said yes but refused to open the sound: stop trying next time.
                            Toast.makeText(PlayerActivity.this, "Your setup won't take DTS/TrueHD directly, "
                                    + "converting this film's sound to Dolby Digital.", Toast.LENGTH_LONG).show();
                        }
                    }
                    ac3 = audioProblem;
                    Toast.makeText(PlayerActivity.this, (audioProblem
                            ? "Audio format not accepted (" : "Direct play failed (")
                            + error.getErrorCodeName() + "), converting"
                            + (audioProblem ? " the sound only" : ""), Toast.LENGTH_LONG).show();
                    begin(currentMs(), true);
                } else {
                    if ((error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                            || error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED) && !stereoFallback) {
                        // Not even Dolby Digital would open (the receiver or ARC isn't taking it right now): stereo works anywhere
                        stereoFallback = true;
                        Toast.makeText(PlayerActivity.this, "Your sound system refused Dolby Digital, playing in stereo",
                                Toast.LENGTH_LONG).show();
                        begin(currentMs(), true);
                        return;
                    }
                    String why = "";
                    for (Throwable t = error.getCause(); t != null; t = t.getCause()) {
                        if (t instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                            why = " (server said HTTP "
                                    + ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) t).responseCode
                                    + ")";
                            break;
                        }
                    }
                    lastError = error.getErrorCodeName() + why
                            + (error.getCause() != null ? " - " + error.getCause().getClass().getSimpleName()
                            + ": " + error.getCause().getMessage() : "");
                    // A dropped connection, or Plex's converter handing back an empty / broken piece
                    // while it (re)starts ("container malformed"): start a fresh stream from where it stopped
                    boolean io = (error.errorCode >= 2000 && error.errorCode < 3000)
                            || error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED;
                    if (io && ioRetries < 6) {
                        // Weak Wi-Fi: keep the same quality and just try again from where it stopped.
                        ioRetries++;
                        Toast.makeText(PlayerActivity.this, "Connection dropped, retrying\u2026",
                                Toast.LENGTH_SHORT).show();
                        begin(Math.max(lastGood, currentMs()), true);
                        return;
                    }
                    Toast.makeText(PlayerActivity.this, "Playback failed: " + error.getErrorCodeName() + why,
                            Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && !liveSynced && channelNum > 0) {
                    // Live: the stream took a few seconds to open, so jump forward to where the channel is by now
                    // (the little picture on the guide was already there)
                    liveSynced = true;
                    long liveStart = getIntent().getLongExtra("liveStart", 0);
                    if (liveStart > 0 && item != null) {
                        long target = System.currentTimeMillis() - liveStart;
                        if (target - currentMs() > 1500 && target < item.duration - 8000) seekAbs(target);
                    }
                }
                if (state == Player.STATE_READY) handler.postDelayed(() -> seekingTo = -1, 300);
                if (state == Player.STATE_READY && bigBuffer != null) bigBuffer.playerReady();
                if (state == Player.STATE_READY && transcoding && !baseChecked && player != null) {
                    baseChecked = true;
                    // Some Plex streams already count time from the start of the movie. If so, don't
                    // add the restart offset on top (that is what threw playback to the end).
                    if (offsetMs > 15000 && player.getCurrentPosition() > offsetMs - 15000) offsetMs = 0;
                }
                if (state == Player.STATE_ENDED && item != null && item.duration > 0
                        && currentMs() < item.duration * 0.9 && earlyEnds < 2) {
                    earlyEnds++;
                    Toast.makeText(PlayerActivity.this, "Stream ended early, picking up where you were",
                            Toast.LENGTH_SHORT).show();
                    begin(lastGood, transcoding);
                    return;
                }
                if (state == Player.STATE_ENDED && channelNum > 0 && Live.byNum(channelNum) != null) {
                    Live.tune(PlayerActivity.this, Live.byNum(channelNum)); // on to whatever is next on the channel
                    finish();
                    return;
                }
                if (state == Player.STATE_ENDED) {
                    if (queueActive() && queueIndex + 1 < sQueue.size()) {
                        playQueueNext();
                        return;
                    }
                    if (nextEpisode() != null && autoPlayOn() && !upNextCancelled) {
                        advanceAuto();
                        return;
                    }
                }
                if (state == Player.STATE_ENDED) {
                    reportedStop = true;
                    Plex.setWatched(key, true);
                    Plex.timeline(key, "stopped", item.duration, item.duration);
                    finish();
                }
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                handleTracks();
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                if (osd != null) osd.onPlayingChanged(playWhenReady);
            }
        });

        player.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onLoadStarted(EventTime t, androidx.media3.exoplayer.source.LoadEventInfo li,
                                      androidx.media3.exoplayer.source.MediaLoadData md) {
                if (md.dataType == C.DATA_TYPE_MEDIA) android.util.Log.d("ReelLoad", "start " + li.uri.getLastPathSegment());
            }

            @Override
            public void onLoadCompleted(EventTime t, androidx.media3.exoplayer.source.LoadEventInfo li,
                                        androidx.media3.exoplayer.source.MediaLoadData md) {
                if (md.dataType == C.DATA_TYPE_MEDIA) android.util.Log.d("ReelLoad", "done " + li.uri.getLastPathSegment()
                        + " " + (li.bytesLoaded >> 10) + " KB in " + li.loadDurationMs + " ms");
            }

            @Override
            public void onLoadError(EventTime t, androidx.media3.exoplayer.source.LoadEventInfo li,
                                    androidx.media3.exoplayer.source.MediaLoadData md, java.io.IOException e, boolean cancelled) {
                android.util.Log.w("ReelLoad", "error " + li.uri.getLastPathSegment() + " " + e);
            }

            @Override
            public void onAudioDecoderInitialized(EventTime eventTime, String decoderName,
                                                  long initializedTimestampMs,
                                                  long initializationDurationMs) {
                audioDecoder = decoderName;
            }

            @Override
            public void onVideoDecoderInitialized(EventTime eventTime, String decoderName,
                                                  long initializedTimestampMs,
                                                  long initializationDurationMs) {
                videoDecoder = decoderName;
            }

            @Override
            public void onDroppedVideoFrames(EventTime eventTime, int droppedFrames, long elapsedMs) {
                dropped += droppedFrames;
            }

            @Override
            public void onAudioUnderrun(EventTime eventTime, int bufferSize, long bufferSizeMs,
                                        long elapsedSinceLastFeedMs) {
                underruns++;
            }
        });

        player.prepare();
        player.play();

        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, TIMELINE_EVERY_MS);
        handler.removeCallbacks(skipTick);
        handler.post(skipTick);
        report("playing");
    }

    /**
     * Jump to a point in the movie. Direct play just seeks. While Plex is converting, a jump inside
     * what's already loaded seeks too; anything further restarts Plex from that point, which is
     * far smoother than asking the stream to leap past what Plex has made so far.
     */
    private long seekingTo = -1;

    // ---- Fast forward / rewind at 2x 4x 8x 16x (remote skip buttons) ----
    private static final int[] TRICK_SPEEDS = {2, 4, 8, 16, 32};
    private static final long TRICK_TICK_MS = 100;
    // How many seconds of film the timeline moves per real second at each step (2x, 4x, 8x, 16x)
    private static final int[] TRICK_RATE = {10, 30, 90, 240, 600};
    private long trickStart = 0;
    private boolean trickWasPlaying = false;
    private int trickEat = 0;
    private int trickDir = 0;      // +1 forward, -1 back, 0 off
    private int trickIdx = 0;
    private long trickPos = 0;
    private TextView trickLabel;
    private final Runnable trickTick = new Runnable() {
        @Override
        public void run() {
            if (trickDir == 0 || player == null) return;
            long dur = item != null && item.duration > 0 ? item.duration : offsetMs + Math.max(0, player.getDuration());
            trickPos += trickDir * TRICK_RATE[trickIdx] * TRICK_TICK_MS;
            if (trickDir < 0 && trickPos <= 0) { trickPos = 0; stopTrick(true); return; }
            if (trickDir > 0 && dur > 0 && trickPos >= dur - 4000) { trickPos = Math.max(0, dur - 4000); stopTrick(true); return; }
            if (osd != null) osd.trickTo(trickPos);
            updateTrickLabel();
            handler.postDelayed(this, TRICK_TICK_MS);
        }
    };

    private void updateTrickLabel() {
        if (trickLabel == null) return;
        long s = Math.max(0, trickPos) / 1000;
        String t = (s >= 3600 ? (s / 3600) + ":" + String.format(java.util.Locale.US, "%02d:%02d", (s / 60) % 60, s % 60)
                : String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60));
        trickLabel.setText((trickDir > 0 ? "\u25B6\u25B6  " : "\u25C0\u25C0  ") + TRICK_SPEEDS[trickIdx] + "x    " + t);
    }

    private void trickPress(int dir) {
        if (player == null) return;
        if (trickDir == 0) {
            trickDir = dir;
            trickIdx = 0;
            trickPos = currentMs();
            trickStart = trickPos;
            trickWasPlaying = player.getPlayWhenReady();
            player.setPlayWhenReady(false);
            if (osd != null) { osd.show(); osd.pin(true); }
            if (trickLabel != null) {
                trickLabel.setVisibility(View.VISIBLE);
                trickLabel.setAlpha(0f);
                trickLabel.animate().alpha(1f).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.EASE).start();
            }
        } else if (dir == trickDir) {
            trickIdx = Math.min(TRICK_SPEEDS.length - 1, trickIdx + 1);
        } else if (trickIdx == 0) {
            stopTrick(true);
            return;
        } else {
            trickIdx--;
        }
        updateTrickLabel();
        handler.removeCallbacks(trickTick);
        handler.postDelayed(trickTick, TRICK_TICK_MS);
    }

    /** Leave fast forward / rewind; commit = jump there (needed when the stream is being transcoded). */
    private void stopTrick(boolean commit) {
        if (trickDir == 0) return;
        trickDir = 0;
        handler.removeCallbacks(trickTick);
        if (osd != null) { osd.trickCancel(); osd.pin(false); }
        if (player != null) {
            seekAbs(commit ? trickPos : trickStart);
            player.setPlayWhenReady(trickWasPlaying || commit);
        }
        if (trickLabel != null) {
            trickLabel.animate().alpha(0f).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.EASE)
                    .withEndAction(() -> { if (trickDir == 0) trickLabel.setVisibility(View.GONE); }).start();
        }
    }

    private void seekAbs(long ms) {
        if (player == null) return;
        ms = Math.max(0, ms);
        lastGood = ms;
        // Jump to the nearest keyframe: exact seeks on big 4K files make the decoder stall (sound with a frozen picture)
        player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC);
        if (!transcoding) {
            player.seekTo(ms);
            return;
        }
        long rel = ms - offsetMs;
        long loaded = Math.max(0, player.getBufferedPosition());
        if (rel >= 0 && rel <= loaded + 1000) {
            player.seekTo(rel);
        } else {
            // Plex restarts from the new point; until it's playing, report the new point as "here".
            seekingTo = ms;
            handler.postDelayed(() -> seekingTo = -1, 20_000);
            begin(ms, true);
        }
    }

    private long currentMs() {
        return player == null ? 0 : offsetMs + Math.max(0, player.getCurrentPosition());
    }

    private void report(String state) {
        if (item == null || player == null) return;
        long here = currentMs();
        if (item.duration <= 0 || here < item.duration * 0.9) lastGood = here;
        Plex.timeline(key, state, currentMs(), item.duration);
        // so Home can tell when something was last watched on another device
        Plex.prefs().edit().putLong("lastPlayedAt", System.currentTimeMillis() / 1000).apply();
    }

    private boolean viewUsed;

    /** Makes a fresh PlayerView with our settings (also used to get a clean picture surface after a restart). */
    private void configureView() {
        view = new PlayerView(this);
        view.setKeepScreenOn(true);
        view.setControllerShowTimeoutMs(4000);
        view.setShowSubtitleButton(false);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS); // spinner while the picture catches up
        view.setUseController(false); // our own Plex-style controls (PlayerOsd)
        applySubStyle();
    }

    /** After a restart (new quality, track...) the old picture surface can stay tied to the released
     *  decoder, giving sound with no picture. Swap in a brand new one. */
    private void freshView() {
        if (!viewUsed) {
            viewUsed = true;
            return;
        }
        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent == null) return;
        int idx = parent.indexOfChild(view);
        parent.removeView(view);
        configureView();
        parent.addView(view, idx, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyFit();
    }

    private BigBuffer bigBuffer;
    /** How far ahead a buffer reaches, always in seconds. */
    private static String aheadText(long sec) {
        return sec + " s";
    }

    private final Runnable bigTick = new Runnable() {
        @Override
        public void run() {
            if (bigBuffer == null || player == null) return;
            bigBuffer.position(bigBuffer.isHls() ? player.getCurrentPosition() : currentMs());
            bigBuffer.playerAhead(player.getTotalBufferedDuration());
            bigBuffer.playerLoading(player.isLoading());
            handler.postDelayed(this, 500);
        }
    };

    private void releasePlayer() {
        handler.removeCallbacks(bigTick);
        if (bigBuffer != null) {
            bigBuffer.stop();
            bigBuffer = null;
        }
        trickDir = 0;
        handler.removeCallbacks(trickTick);
        handler.removeCallbacks(frameWatch);
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(skipTick);
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setMode(android.media.AudioManager.MODE_NORMAL);
                am.setParameters("audio_passthrough=0");
            }
        } catch (Throwable ignored) {
        }
        if (player != null) {
            player.release();
            player = null;
        }
        view.setPlayer(null);
    }

    // ----------------------------------------------------- Track choices

    private void handleTracks() {
        if (transcoding || player == null) return;
        if (!initialApplied && !audioGroups().isEmpty()) {
            initialApplied = true;
            if (curAudio != null && item.streamsOf(2).size() > 1) applyAudioDirect(curAudio);
        }
        if (pendingSub != null) {
            Plex.Stream s = pendingSub;
            pendingSub = null;
            applySubDirect(s);
        }
    }

    private List<Tracks.Group> groupsOfType(int type) {
        List<Tracks.Group> out = new ArrayList<>();
        if (player == null) return out;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() == type) out.add(g);
        }
        return out;
    }

    private List<Tracks.Group> audioGroups() {
        return groupsOfType(C.TRACK_TYPE_AUDIO);
    }

    private static boolean sameLang(String plexCode, String exoLang) {
        if (plexCode == null || exoLang == null || plexCode.length() < 2 || exoLang.length() < 2) {
            return false;
        }
        return plexCode.substring(0, 2).equalsIgnoreCase(exoLang.substring(0, 2));
    }

    /** Matches a Plex audio stream to the player's track, by position then channels and language. */
    private Tracks.Group audioGroupFor(Plex.Stream s) {
        List<Plex.Stream> all = item.streamsOf(2);
        List<Tracks.Group> groups = audioGroups();
        if (groups.isEmpty()) return null;
        int idx = all.indexOf(s);
        Tracks.Group best = null;
        int bestScore = -1;
        for (int i = 0; i < groups.size(); i++) {
            Format f = groups.get(i).getTrackFormat(0);
            int score = 0;
            if (i == idx) score += 2;
            if (s.channels > 0 && f.channelCount == s.channels) score += 2;
            if (sameLang(s.langCode, f.language)) score += 1;
            if (score > bestScore) {
                bestScore = score;
                best = groups.get(i);
            }
        }
        return best;
    }

    private Tracks.Group subGroupFor(Plex.Stream s) {
        List<Plex.Stream> embedded = new ArrayList<>();
        for (Plex.Stream x : item.streamsOf(3)) if (!x.isExternal()) embedded.add(x);
        List<Tracks.Group> groups = groupsOfType(C.TRACK_TYPE_TEXT);
        int idx = embedded.indexOf(s);
        if (idx < 0 || idx >= groups.size()) return null;
        return groups.get(idx);
    }

    private void setOverride(int type, Tracks.Group g) {
        TrackGroup tg = g.getMediaTrackGroup();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(type, false)
                .setOverrideForType(new TrackSelectionOverride(tg, 0))
                .build());
    }

    private void enableText(boolean on) {
        if (player == null) return;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on).build());
    }

    private void applyAudioDirect(Plex.Stream s) {
        if (needsAc3(s)) {
            curAudio = s;
            ac3 = true;
            Toast.makeText(this, "Converting that sound to Dolby Digital", Toast.LENGTH_SHORT).show();
            begin(currentMs(), true);
            return;
        }
        curAudio = s;
        Tracks.Group g = audioGroupFor(s);
        if (g == null || !g.isTrackSupported(0)) {
            // Not playable as is: try another track in the same language before involving Plex.
            for (Plex.Stream o : item.streamsOf(2)) {
                if (o.id == s.id || !o.langCode.equals(s.langCode)) continue;
                Tracks.Group og = audioGroupFor(o);
                if (og != null && og.isTrackSupported(0)) {
                    curAudio = o;
                    Toast.makeText(this, "Playing " + o.label() + " instead", Toast.LENGTH_SHORT).show();
                    setOverride(C.TRACK_TYPE_AUDIO, og);
                    return;
                }
            }
            ac3 = true;
            Toast.makeText(this, "Converting just the sound to Dolby Digital", Toast.LENGTH_SHORT).show();
            begin(currentMs(), true);
            return;
        }
        setOverride(C.TRACK_TYPE_AUDIO, g);
    }

    private void applySubDirect(Plex.Stream s) {
        Tracks.Group g = subGroupFor(s);
        if (g == null) {
            Toast.makeText(this, "Couldn't find that subtitle track in the video", Toast.LENGTH_SHORT).show();
            return;
        }
        setOverride(C.TRACK_TYPE_TEXT, g);
    }

    private void selectAudio(Plex.Stream s) {
        curAudio = s;
        if (transcoding) {
            ac3 = ac3 || needsAc3(s);
            begin(currentMs(), true);
        } else {
            applyAudioDirect(s);
        }
    }

    private void selectSub(Plex.Stream s) {
        curSub = s;
        subChosen = true;
        if (transcoding) {
            // Text subtitles are drawn by the app; only picture subtitles make Plex burn them in.
            if (s != null && isPicture(s)) {
                Toast.makeText(this, "Picture subtitles get burned in by Plex, which re-encodes the video",
                        Toast.LENGTH_LONG).show();
            }
            begin(currentMs(), true);
            return;
        }
        if (s == null) {
            enableText(false);
            return;
        }
        if (s.isExternal()) {
            externalSub = s;
            rebuildKeepingPosition();
            enableText(true);
        } else if (externalSub != null) {
            externalSub = null;
            pendingSub = s;
            rebuildKeepingPosition();
        } else {
            applySubDirect(s);
        }
    }

    private void rebuildKeepingPosition() {
        long pos = player.getCurrentPosition();
        player.setMediaItem(buildItem(false), pos);
        player.prepare();
        player.play();
    }

    // ----------------------------------------------------------- Panel

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF2101218);
        int pad = (int) (W * 0.02f);
        panel.setPadding(pad, pad, pad, pad);
        panel.setVisibility(View.GONE);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < TAB_COUNT; i++) {
            final int index = i;
            final TextView t = new TextView(this);
            t.setText(TAB_NAMES[i]);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
            t.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
            t.setGravity(Gravity.CENTER);
            t.setPadding((int) (W * 0.014f), (int) (H * 0.012f), (int) (W * 0.014f), (int) (H * 0.012f));
            t.setFocusable(true);
            t.setClickable(true);
            t.setOnClickListener(v -> showTab(index));
            t.setOnFocusChangeListener((v, f) -> styleTabs());
            tabViews[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (W * 0.006f);
            tabs.addView(t, lp);
        }
        panel.addView(tabs);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sLp.topMargin = (int) (H * 0.02f);
        panel.addView(scroll, sLp);

        FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(
                (int) (W * 0.42f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        root.addView(panel, pLp);
    }

    private void styleTabs() {
        for (int i = 0; i < TAB_COUNT; i++) {
            TextView t = tabViews[i];
            boolean focused = t.hasFocus();
            boolean selected = i == tabIndex;
            t.setBackground(UiUtil.rounded(focused ? 0xFFFFFFFF : (selected ? 0x44FFFFFF : 0x00000000),
                    H * 0.04f));
            t.setTextColor(focused ? 0xFF111111 : 0xFFFFFFFF);
            t.setAlpha(selected || focused ? 1f : 0.7f);
        }
    }

    private void buildSkipButton() {
        skipBtn = new TextView(this);
        skipBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
        skipBtn.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        skipBtn.setGravity(Gravity.CENTER);
        skipBtn.setPadding((int) (W * 0.022f), (int) (H * 0.016f), (int) (W * 0.022f), (int) (H * 0.016f));
        skipBtn.setFocusable(true);
        skipBtn.setClickable(true);
        skipBtn.setVisibility(View.GONE);
        final float r = H * 0.05f;
        skipBtn.setBackground(UiUtil.rounded(0xCC000000, r));
        skipBtn.setTextColor(0xFFFFFFFF);
        skipBtn.setOnFocusChangeListener((v, f) -> {
            skipBtn.setBackground(UiUtil.rounded(f ? 0xFFFFFFFF : 0xCC000000, r));
            skipBtn.setTextColor(f ? 0xFF111111 : 0xFFFFFFFF);
            float sc = f ? 1.08f : 1f;
            v.animate().scaleX(sc).scaleY(sc).setDuration(Motion.FAST_MS).setInterpolator(Motion.EASE).start();
        });
        skipBtn.setOnClickListener(v -> {
            if (skipRange == null || player == null) return;
            seekAbs(skipRange[1]);
            skipBtn.setVisibility(View.GONE);
            skipRange = null;
            view.requestFocus();
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        lp.bottomMargin = (int) (H * 0.22f);
        lp.rightMargin = (int) (W * 0.04f);
        root.addView(skipBtn, lp);
    }

    /** Shows "Skip intro" / "Skip credits" while playback is inside a marker Plex has detected. */
    private void checkSkip() {
        if (item == null || player == null || panelOpen || epOpen) {
            if (skipBtn != null && (panelOpen || epOpen)) skipBtn.setVisibility(View.GONE);
            return;
        }
        long pos = currentMs();
        long[] hit = null;
        String label = null;
        for (long[] r : item.intros) if (pos >= r[0] && pos < r[1] - 1000) { hit = r; label = "Skip intro"; }
        boolean autoNext = autoPlayOn() && nextEpisode() != null; // the Up Next card covers credits
        if (hit == null && !autoNext) {
            for (long[] r : item.credits) if (pos >= r[0] && pos < r[1] - 1000) { hit = r; label = "Skip credits"; }
        }
        if (hit == null) {
            if (skipBtn.getVisibility() == View.VISIBLE) {
                boolean hadFocus = skipBtn.hasFocus();
                skipBtn.setVisibility(View.GONE);
                skipRange = null;
                if (hadFocus) view.requestFocus();
            }
            return;
        }
        skipRange = hit;
        if (skipBtn.getVisibility() != View.VISIBLE) {
            skipBtn.setText(label);
            skipBtn.setVisibility(View.VISIBLE);
            skipBtn.requestFocus();
        }
    }

    private void buildEpisodePanel() {
        epPanel = new LinearLayout(this);
        epPanel.setOrientation(LinearLayout.VERTICAL);
        epPanel.setVisibility(View.GONE);
        epPanel.setClipChildren(false);
        epPanel.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xEE000000, 0xFF000000}));
        epPanel.setPadding(0, (int) (H * 0.08f), 0, (int) (H * 0.04f));

        TextView label = new TextView(this);
        epTitle = label;
        label.setText("Episodes");
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
        label.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        label.setPadding((int) (W * 0.045f) + 8, 0, 0, (int) (H * 0.006f));
        // Header: the title, then Previous / Next
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        epPrev = makePill("\u00AB   Previous", v -> skipTo(-1));
        epNext = makePill("Next   \u00BB", v -> skipTo(1));
        head.addView(epPrev);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = (int) (W * 0.01f);
        nlp.rightMargin = (int) (W * 0.045f);
        head.addView(epNext, nlp);
        epPanel.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        epList = new androidx.recyclerview.widget.RecyclerView(this);
        epList.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this,
                androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false));
        epList.setClipToPadding(false);
        epList.setClipChildren(false);
        epList.setItemAnimator(null);
        epList.setPadding((int) (W * 0.045f), 0, (int) (W * 0.045f), 0);
        epPanel.addView(epList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(epPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
    }

    private void loadEpisodes(final String showKey) {
        Plex.async(() -> Plex.items(Plex.paged("/library/metadata/" + showKey + "/allLeaves", 800)),
                new Plex.Callback<List<Plex.Item>>() {
                    @Override
                    public void ok(List<Plex.Item> list) {
                        if (isDestroyed()) return;
                        episodes.clear();
                        episodes.addAll(list);
                        int ph = (int) (H * 0.20f);
                        epEpisodeAdapter = new PosterAdapter(episodes, (int) (ph * 16f / 9f), ph, true,
                                new PosterAdapter.Listener() {
                                    @Override
                                    public void onFocused(Plex.Item it) {
                                    }

                                    @Override
                                    public void onClicked(Plex.Item it) {
                                        closeEpisodes();
                                        if (it.key.equals(key)) return;
                                        // Start the chosen episode and leave this one.
                                        PlayerActivity.start(PlayerActivity.this, it.key, it.viewOffset);
                                        finish();
                                    }
                                });
                    }

                    @Override
                    public void fail(Exception e) {
                        // Pressing Down just does nothing if the list couldn't load.
                    }
                });
    }

    private TextView makePill(String text, View.OnClickListener click) {
        final TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.028f);
        t.setTypeface(UiUtil.font(UiUtil.SEMIBOLD));
        t.setGravity(Gravity.CENTER);
        t.setMinWidth((int) (W * 0.11f));
        t.setPadding((int) (W * 0.025f), (int) (H * 0.016f), (int) (W * 0.025f), (int) (H * 0.016f));
        t.setFocusable(true);
        t.setClickable(true);
        final float r = H * 0.04f;
        UiUtil.stylePill(t, false, r);
        t.setOnFocusChangeListener((v, f) -> {
            UiUtil.stylePill(t, f, r);
            if (f) SoundFx.move();
        });
        t.setOnClickListener(click);
        return t;
    }

    /** The video before (-1) or after (+1) this one: in the queue, or among a show's episodes. */
    private Plex.Item neighbour(int dir) {
        if (queueActive()) {
            int to = queueIndex + dir;
            return to >= 0 && to < sQueue.size() ? sQueue.get(to) : null;
        }
        if (item == null || !"episode".equals(item.type)) return null;
        for (int i = 0; i < episodes.size(); i++) {
            if (episodes.get(i).key.equals(key)) {
                int to = i + dir;
                return to >= 0 && to < episodes.size() ? episodes.get(to) : null;
            }
        }
        return null;
    }

    private void skipTo(int dir) {
        Plex.Item n = neighbour(dir);
        if (n == null) {
            Toast.makeText(this, dir > 0 ? "That's the last one" : "That's the first one",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (queueActive()) {
            playQueueAt(queueIndex + dir);
        } else {
            PlayerActivity.start(this, n.key, 0);
            finish();
        }
    }

    private void openEpisodes() {
        if (!hasPanelList() || panelOpen) return;
        epOpen = true;
        // The timeline stays up, lifted above the row
        if (osd != null) {
            osd.show();
            osd.pin(true);
            osd.lift(H * 0.40f);
        }
        hint.setVisibility(View.GONE);
        epPrev.setAlpha(neighbour(-1) == null ? 0.4f : 1f);
        epNext.setAlpha(neighbour(1) == null ? 0.4f : 1f);
        final boolean q = queueActive();
        if (q) {
            moveKey = null;
            epTitle.setText(QUEUE_HELP);
            int ph = (int) (H * 0.20f);
            epQueueAdapter = new PosterAdapter(sQueue, (int) (ph * 16f / 9f), ph, true,
                    new PosterAdapter.Listener() {
                        @Override
                        public void onFocused(Plex.Item it) {
                        }

                        @Override
                        public void onClicked(Plex.Item it) {
                            int at = queuePos(it.key);
                            if (at < 0) return;
                            closeEpisodes();
                            if (!it.key.equals(key)) playQueueAt(at);
                        }

                        @Override
                        public boolean onLongClicked(Plex.Item it) {
                            // Pick it up: Left / Right move it, Down removes it, OK puts it down
                            moveKey = it.key;
                            moveReleased = false;
                            epTitle.setText(MOVE_HELP);
                            return true;
                        }
                    });
            epList.setAdapter(epQueueAdapter);
        } else {
            epTitle.setText("Episodes");
            if (epEpisodeAdapter != null) epList.setAdapter(epEpisodeAdapter);
        }
        epPanel.setVisibility(View.VISIBLE);
        int idx = 0;
        List<Plex.Item> src = q ? sQueue : episodes;
        for (int i = 0; i < src.size(); i++) if (src.get(i).key.equals(key)) idx = i;
        final int at = idx;
        ((androidx.recyclerview.widget.LinearLayoutManager) epList.getLayoutManager())
                .scrollToPositionWithOffset(at, (int) (W * 0.045f));
        epList.postDelayed(() -> {
            androidx.recyclerview.widget.RecyclerView.ViewHolder vh = epList.findViewHolderForAdapterPosition(at);
            if (vh != null) vh.itemView.requestFocus();
        }, 150);
    }

    private static final String QUEUE_HELP = "Queue  \u00B7  hold OK on a video to move or remove it";
    private static final String MOVE_HELP =
            "Moving  \u00B7  \u25C0 \u25B6 to move  \u00B7  \u25BC to remove  \u00B7  OK to drop";

    private void endMove() {
        moveKey = null;
        moveReleased = false;
        if (epTitle != null) epTitle.setText(QUEUE_HELP);
    }

    private void refocusQueue(final int pos) {
        epList.postDelayed(() -> {
            if (epList == null || sQueue.isEmpty()) return;
            int p = Math.max(0, Math.min(pos, sQueue.size() - 1));
            androidx.recyclerview.widget.RecyclerView.ViewHolder vh = epList.findViewHolderForAdapterPosition(p);
            if (vh != null) vh.itemView.requestFocus();
        }, 90);
    }

    /** Keys while a queue entry is picked up. Everything is used up so nothing else reacts. */
    private void handleMoveKey(KeyEvent event) {
        int kc = event.getKeyCode();
        boolean ok = kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER;
        if (ok && event.getAction() == KeyEvent.ACTION_UP) {
            moveReleased = true; // the long press that picked it up has been let go
            return;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return;
        int idx = queuePos(moveKey);
        if (idx < 0) {
            endMove();
            return;
        }
        if (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int to = idx + (kc == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            if (to < 0 || to >= sQueue.size()) {
                SoundFx.edge();
                return;
            }
            java.util.Collections.swap(sQueue, idx, to);
            epQueueAdapter.notifyItemMoved(idx, to);
            int cur = queuePos(key);
            if (cur >= 0) queueIndex = cur;
            epList.scrollToPosition(to);
            SoundFx.move();
        } else if (kc == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (moveKey.equals(key)) {
                Toast.makeText(this, "That one is playing now, so it can't be removed", Toast.LENGTH_SHORT).show();
                return;
            }
            sQueue.remove(idx);
            epQueueAdapter.notifyItemRemoved(idx);
            int cur = queuePos(key);
            if (cur >= 0) queueIndex = cur;
            endMove();
            Toast.makeText(this, "Removed from the queue", Toast.LENGTH_SHORT).show();
            refocusQueue(idx);
        } else if (ok) {
            if (moveReleased) endMove();
        } else if (kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_DPAD_UP) {
            endMove();
        }
    }

    private void closeEpisodes() {
        endMove();
        if (osd != null) {
            osd.pin(false);
            osd.lift(0);
        }
        epOpen = false;
        epPanel.setVisibility(View.GONE);
        view.requestFocus();
    }

    private void openPanel() {
        if (item == null || player == null) return;
        panelOpen = true;
        if (osd != null) osd.hide();
        hint.setVisibility(View.GONE);
        panel.setVisibility(View.VISIBLE);
        showTab(TAB_AUDIO);
    }

    private void closePanel() {
        panelOpen = false;
        inSubView = false;
        panel.setVisibility(View.GONE);
        handler.removeCallbacks(debugTick);
        if (overlayOn) handler.post(debugTick);
        view.requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (!epOpen && !panelOpen && osd != null && osd.onBack()) return;
        if (epOpen) {
            closeEpisodes();
            return;
        }
        if (panelOpen) {
            if (inSubView) {
                showTab(TAB_SUBS);
            } else {
                closePanel();
            }
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int kc = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) touched();
        if (chanCard != null) {
            if (kc == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) closeChannels();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        if (channelNum > 0 && (kc == KeyEvent.KEYCODE_CHANNEL_UP || kc == KeyEvent.KEYCODE_CHANNEL_DOWN)) {
            if (event.getAction() == KeyEvent.ACTION_UP) zap(kc == KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1);
            return true;
        }
        if (channelNum > 0 && !panelOpen && kc == KeyEvent.KEYCODE_DPAD_DOWN) {
            // Down shows the controls first, then the channel list
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (osd != null && osd.isVisible()) openChannels();
                else if (osd != null) osd.show();
            }
            return true;
        }
        if (stillCard != null && kc != KeyEvent.KEYCODE_BACK) {
            // Only OK answers the question; the rest of the remote is held until then
            if (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER) return super.dispatchKeyEvent(event);
            return true;
        }
        if (epOpen && moveKey != null) {
            handleMoveKey(event);
            return true;
        }
        if (panelOpen && !inSubView && kc == KeyEvent.KEYCODE_DPAD_UP && event.getAction() == KeyEvent.ACTION_DOWN) {
            // Up from the list lands on the tab you are in, not whichever tab happens to be closest
            View f = getCurrentFocus();
            if (f != null && f.getParent() == list) {
                View n = f.focusSearch(View.FOCUS_UP);
                for (TextView tv : tabViews) {
                    if (n == tv) {
                        tabViews[tabIndex].requestFocus();
                        return true;
                    }
                }
            }
        }
        if (!panelOpen && !epOpen && upNextShowing()) {
            boolean ok = kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER;
            if (ok) {
                if (event.getAction() == KeyEvent.ACTION_UP) playNext();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    upNextCancelled = true;
                    hideUpNext();
                }
                return true;
            }
        }
        if (!panelOpen && !epOpen && player != null) {
            boolean ff = kc == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || kc == KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD;
            boolean rw = kc == KeyEvent.KEYCODE_MEDIA_REWIND || kc == KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD;
            if (ff || rw) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) trickPress(ff ? 1 : -1);
                return true;
            }
            if (trickDir != 0) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    // Any other button ends it where we are; Back, OK and Play/Pause are used up by that
                    boolean eat = kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_DPAD_CENTER
                            || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                            || kc == KeyEvent.KEYCODE_MEDIA_PLAY || kc == KeyEvent.KEYCODE_MEDIA_PAUSE;
                    stopTrick(kc != KeyEvent.KEYCODE_BACK);
                    if (eat) trickEat = kc;
                    if (eat) return true;
                } else if (trickEat == kc) {
                    if (event.getAction() == KeyEvent.ACTION_UP) trickEat = 0;
                    return true;
                }
            } else if (trickEat == kc) {
                if (event.getAction() == KeyEvent.ACTION_UP) trickEat = 0;
                return true;
            }
        }
        if (!panelOpen && !epOpen && osd != null) {
            boolean ok = kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER;
            if (ok && skipBtn != null && skipBtn.getVisibility() == View.VISIBLE) {
                // A skip intro / credits button on screen: OK presses it.
                if (event.getAction() == KeyEvent.ACTION_UP) skipBtn.performClick();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_DPAD_UP) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    // First press only brings up the controls; a second press opens settings
                    if (!osd.isVisible()) osd.show();
                    else openPanel();
                }
                return true;
            }
            if (osd.handleKey(event)) return true;
            if (kc == KeyEvent.KEYCODE_DPAD_DOWN && !hasPanelList()) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) osd.show();
                return true;
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && !panelOpen) {
            if (!epOpen && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN && hasPanelList()) {
                openEpisodes();
                return true;
            }
            if (epOpen && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
                // From the row, Up goes to Previous / Next; from those, Up closes the row
                if (epList.hasFocus() && epPrev != null) {
                    // Land on whichever button actually has somewhere to go (Next first)
                    if (neighbour(1) != null) epNext.requestFocus();
                    else epPrev.requestFocus();
                } else {
                    closeEpisodes();
                }
                return true;
            }
        }
        if ((panelOpen || epOpen) && player != null && (kc == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || kc == KeyEvent.KEYCODE_MEDIA_PLAY || kc == KeyEvent.KEYCODE_MEDIA_PAUSE)) {
            // Play/Pause on the remote still works with a settings panel or the episode row open
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                boolean play = kc == KeyEvent.KEYCODE_MEDIA_PLAY
                        || (kc != KeyEvent.KEYCODE_MEDIA_PAUSE && !player.getPlayWhenReady());
                player.setPlayWhenReady(play);
            }
            return true;
        }
        if (kc == KeyEvent.KEYCODE_MEDIA_NEXT || kc == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            if (event.getAction() == KeyEvent.ACTION_UP) skipTo(kc == KeyEvent.KEYCODE_MEDIA_NEXT ? 1 : -1);
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (panelOpen) closePanel();
                else openPanel();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private View makeRow(String title, String sub, boolean selected, boolean dim, View.OnClickListener click) {
        final LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setFocusable(true);
        r.setClickable(true);
        int ph = (int) (W * 0.014f);
        int pv = (int) (H * 0.014f);
        r.setPadding(ph, pv, ph, pv);

        final TextView t = new TextView(this);
        t.setText((selected ? "✓  " : "     ") + title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.029f);
        t.setTextColor(0xFFFFFFFF);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(t);

        final TextView s = new TextView(this);
        if (sub != null && !sub.isEmpty()) {
            s.setText("     " + sub);
            s.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.022f);
            s.setTextColor(0xAAFFFFFF);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.addView(s);
        }
        r.setAlpha(dim ? 0.55f : 1f);

        final float radius = H * 0.012f;
        r.setOnFocusChangeListener((v, f) -> {
            r.setBackground(f ? UiUtil.rounded(0xFFFFFFFF, radius) : null);
            t.setTextColor(f ? 0xFF111111 : 0xFFFFFFFF);
            s.setTextColor(f ? 0xFF444444 : 0xAAFFFFFF);
        });
        r.setOnClickListener(click);
        return r;
    }

    private void addHeading(String text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextColor(0xAAFFFFFF);
        h.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.024f);
        h.setPadding((int) (W * 0.014f), (int) (H * 0.01f), 0, (int) (H * 0.006f));
        list.addView(h);
    }

    private void focusFirstOrSelected(View selectedRow) {
        View target = selectedRow != null ? selectedRow : list.getChildAt(0);
        if (target != null && target.isFocusable()) {
            target.requestFocus();
        } else {
            for (int i = 0; i < list.getChildCount(); i++) {
                if (list.getChildAt(i).isFocusable()) {
                    list.getChildAt(i).requestFocus();
                    return;
                }
            }
            tabViews[tabIndex].requestFocus();
        }
    }

    private void showTab(int index) {
        tabIndex = index;
        inSubView = false;
        styleTabs();
        list.removeAllViews();
        infoText = null;
        handler.removeCallbacks(debugTick);

        if (index == TAB_AUDIO) {
            showAudioTab();
        } else if (index == TAB_SUBS) {
            showSubsTab();
        } else if (index == TAB_VIDEO) {
            showVideoTab();
        } else {
            showInfoTab();
        }
    }

    // ------------------------------------------------------------ Tabs

    /** Fit / Fill / Stretch for this film. Fill zooms in until the screen is full, cropping the edges. */
    private void applyFit() {
        int mode = fitMode == 1 ? androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                : fitMode == 2 ? androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                : androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT;
        view.setResizeMode(mode);
        // Subtitles are laid out inside the zoomed picture, so lift them back above the screen's bottom edge
        androidx.media3.ui.SubtitleView sv = view.getSubtitleView();
        if (sv == null) return;
        float lift = 0f;
        androidx.media3.common.VideoSize vs = player == null ? null : player.getVideoSize();
        if (fitMode == 1 && vs != null && vs.width > 0 && vs.height > 0 && W > 0 && H > 0) {
            float ar = vs.width * (vs.pixelWidthHeightRatio > 0 ? vs.pixelWidthHeightRatio : 1f) / vs.height;
            float screenAr = (float) W / H;
            if (ar < screenAr) { // taller than the screen: the zoomed picture runs off the top and bottom
                float frameH = W / ar;
                lift = (frameH - H) / 2f / frameH;
            }
        }
        sv.setBottomPaddingFraction(subBase() + lift);
    }

    private void showVideoTab() {
        addHeading("Picture size, for this film only. Fill zooms in to remove black bars (it crops the edges); "
                + "Stretch pulls the picture to the screen's shape.");
        String[] fitNames = {"Fit", "Fill", "Stretch"};
        String[] fitNotes = {"The whole picture, black bars if the shapes differ", "Fills the screen, crops the edges",
                "Fills the screen, squashes the picture"};
        for (int i = 0; i < fitNames.length; i++) {
            final int f = i;
            list.addView(makeRow(fitNames[i], fitNotes[i], i == fitMode, false, v -> {
                fitMode = f;
                applyFit();
                showTab(TAB_VIDEO);
            }));
        }
        addHeading("");
        addHeading("Pick a lower quality and Plex converts the picture to match. "
                + "Original plays the file as it is.");
        for (int i = 0; i < QUALITIES.length; i++) {
            final int q = i;
            list.addView(makeRow((String) QUALITIES[i][0], null, i == quality, false, v -> {
                if (q == quality) return;
                quality = q;
                ac3 = needsAc3(curAudio);
                long pos = currentMs();
                begin(pos, q != 0);
                showTab(TAB_VIDEO);
            }));
        }
    }

    /** Plain track names: the language (plus Commentary / SDH / Forced when it applies), numbered if
     *  several tracks share one. The file's own track title, often a release-group tag, is dropped. */
    private java.util.Map<Integer, String> trackNames(List<Plex.Stream> streams) {
        java.util.Map<Integer, String> out = new java.util.HashMap<>();
        java.util.Map<String, Integer> total = new java.util.HashMap<>();
        java.util.Map<Integer, String> base = new java.util.HashMap<>();
        for (Plex.Stream s : streams) {
            String lang = !s.lang.isEmpty() ? s.lang : !s.langCode.isEmpty() ? s.langCode.toUpperCase(Locale.ROOT) : "";
            if (lang.isEmpty()) {
                base.put(s.id, s.label());
                continue;
            }
            String all = (s.extTitle + " " + s.title + " " + s.displayTitle).toLowerCase(Locale.ROOT);
            String tag = all.contains("commentary") ? "Commentary"
                    : all.contains("descriptive") || all.contains("audio description") ? "Audio description"
                    : all.contains("sdh") || all.contains("hearing impaired") ? "SDH"
                    : s.forced || all.contains("forced") ? "Forced" : "";
            String b = tag.isEmpty() ? lang : lang + "  ·  " + tag;
            base.put(s.id, b);
            total.merge(b, 1, Integer::sum);
        }
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (Plex.Stream s : streams) {
            String b = base.get(s.id);
            Integer t = total.get(b);
            if (t != null && t > 1) {
                int n = seen.merge(b, 1, Integer::sum);
                b = b + " " + n;
            }
            out.put(s.id, b);
        }
        return out;
    }

    private void showAudioTab() {
        if (transcoding) {
            addHeading("Plex is converting the stream. Picking a track restarts it from here.");
        }
        View selected = null;
        List<Plex.Stream> audios = item.streamsOf(2);
        if (audios.isEmpty()) addHeading("No audio tracks reported");
        final java.util.Map<Integer, String> names = trackNames(audios);
        for (final Plex.Stream s : audios) {
            boolean isCur = curAudio != null && curAudio.id == s.id;
            String sub = Badges.audioName(s);
            String ch = Badges.channelText(s);
            if (!ch.isEmpty()) sub += "  " + ch;
            if (s.bitrate > 0) sub += "  ·  " + s.bitrate + " kbps";
            if (!transcoding) {
                Tracks.Group g = audioGroupFor(s);
                if (g != null && !g.isTrackSupported(0)) sub += "  ·  needs transcoding";
            }
            View row = makeRow(names.get(s.id), sub, isCur, false, v -> {
                selectAudio(s);
                showTab(TAB_AUDIO);
            });
            list.addView(row);
            if (isCur) selected = row;
        }
        focusFirstOrSelected(selected);
    }

    private void showSubsTab() {
        if (transcoding) {
            addHeading("Picture subtitles (PGS) can only be burned in by Plex, which re-encodes the video. "
                    + "Text subtitles are lighter on the server.");
        }
        View selected = null;
        final boolean subsActive = !transcoding || subChosen; // while converting, nothing is on until you pick
        View off = makeRow("Off", null, curSub == null || !subsActive, false, v -> {
            selectSub(null);
            showTab(TAB_SUBS);
        });
        list.addView(off);
        if (curSub == null || !subsActive) selected = off;

        final java.util.Map<Integer, String> subNames = trackNames(item.streamsOf(3));
        for (final Plex.Stream s : item.streamsOf(3)) {
            boolean isCur = subsActive && curSub != null && curSub.id == s.id;
            String sub = (s.isExternal() ? "File" : "Built in") + (s.forced ? "  ·  forced" : "")
                    + (s.codec.isEmpty() ? "" : "  ·  " + s.codec.toUpperCase(Locale.ROOT))
                    + (transcoding && isPicture(s) ? "  ·  burns in, re-encodes the video" : "");
            View row = makeRow(subNames.get(s.id), sub, isCur, false, v -> {
                selectSub(s);
                showTab(TAB_SUBS);
            });
            list.addView(row);
            if (isCur) selected = row;
        }

        addHeading("");
        list.addView(makeRow("Find subtitles online…", "Searches through your Plex server", false, false,
                v -> showLanguagePicker()));

        // How the subtitles look: size, colour and where they sit. Applies at once.
        addHeading("");
        addHeading("How subtitles look");
        android.content.SharedPreferences lp = getSharedPreferences("reel_ui", MODE_PRIVATE);
        int size = Math.max(0, Math.min(SUB_SIZE_NAMES.length - 1, lp.getInt("subSize", 1)));
        int colour = Math.max(0, Math.min(SUB_COLOUR_NAMES.length - 1, lp.getInt("subColour", 0)));
        int pos = Math.max(0, Math.min(SUB_POS_NAMES.length - 1, lp.getInt("subPos", 2)));
        list.addView(makeRow("Size: " + SUB_SIZE_NAMES[size], "Press to change", false, false,
                v -> cycleSubSetting("subSize", SUB_SIZE_NAMES.length, 1, v)));
        list.addView(makeRow("Colour: " + SUB_COLOUR_NAMES[colour], "Press to change", false, false,
                v -> cycleSubSetting("subColour", SUB_COLOUR_NAMES.length, 0, v)));
        list.addView(makeRow("Position: " + SUB_POS_NAMES[pos], "Press to move them up the screen", false, false,
                v -> cycleSubSetting("subPos", SUB_POS_NAMES.length, 2, v)));
        if (subFocusRow >= 0 && subFocusRow < list.getChildCount() && list.getChildAt(subFocusRow).isFocusable()) {
            list.getChildAt(subFocusRow).requestFocus();
            subFocusRow = -1;
        } else {
            subFocusRow = -1;
            focusFirstOrSelected(selected);
        }
    }

    private void showLanguagePicker() {
        inSubView = true;
        list.removeAllViews();
        addHeading("Search in which language?");
        for (final String[] lang : SUB_LANGS) {
            list.addView(makeRow(lang[0], null, false, false, v -> searchSubs(lang[0], lang[1])));
        }
        focusFirstOrSelected(null);
    }

    private void searchSubs(final String label, final String code) {
        inSubView = true;
        list.removeAllViews();
        addHeading("Searching for " + label + " subtitles…");
        Plex.async(() -> Plex.searchSubtitles(key, code), new Plex.Callback<List<Plex.SubResult>>() {
            @Override
            public void ok(List<Plex.SubResult> results) {
                if (isDestroyed() || !panelOpen || !inSubView) return;
                list.removeAllViews();
                if (results.isEmpty()) {
                    addHeading("No " + label + " subtitles found.");
                    list.addView(makeRow("Back", null, false, false, v -> showTab(TAB_SUBS)));
                    focusFirstOrSelected(null);
                    return;
                }
                addHeading(results.size() + " results. Pick one to download it.");
                for (final Plex.SubResult r : results) {
                    String title = r.title.isEmpty() ? (label + " subtitles") : r.title;
                    String sub = (r.provider.isEmpty() ? "" : r.provider + "  ·  ")
                            + r.codec.toUpperCase(Locale.ROOT)
                            + (r.hearingImpaired ? "  ·  hearing impaired" : "")
                            + (r.score > 0 ? "  ·  match " + Math.round(r.score * 100) + "%" : "");
                    list.addView(makeRow(title, sub, false, false, v -> downloadSub(r)));
                }
                focusFirstOrSelected(null);
            }

            @Override
            public void fail(Exception e) {
                if (isDestroyed() || !panelOpen) return;
                list.removeAllViews();
                String why = String.valueOf(e.getMessage());
                addHeading("Couldn't search (" + why + ").");
                addHeading("This needs Plex Pass and a subtitle provider enabled in your server settings.");
                list.addView(makeRow("Back", null, false, false, v -> showTab(TAB_SUBS)));
                focusFirstOrSelected(null);
            }
        });
    }

    private void downloadSub(final Plex.SubResult r) {
        // Pause while the file is fetched, then switch it on and carry on from the same spot
        final boolean wasPlaying = player != null && player.getPlayWhenReady();
        if (player != null) player.pause();
        final java.util.Set<Integer> had = new java.util.HashSet<>();
        for (Plex.Stream st : item.streamsOf(3)) had.add(st.id);
        list.removeAllViews();
        addHeading("Downloading… the video is paused until the subtitles are ready.");
        Plex.async(() -> {
            Plex.downloadSubtitle(key, r);
            Plex.Item latest = null;
            for (int i = 0; i < 10; i++) {
                Thread.sleep(1200); // give the server a moment to attach the file
                latest = Plex.metadata(key);
                if (latest.streamsOf(3).size() > had.size()) break;
            }
            return latest;
        }, new Plex.Callback<Plex.Item>() {
            @Override
            public void ok(Plex.Item fresh) {
                if (isDestroyed()) return;
                item.streams = fresh.streams;
                Plex.Stream added = null;
                for (Plex.Stream st : item.streamsOf(3)) {
                    if (!had.contains(st.id)) added = st;
                }
                if (panelOpen) closePanel();
                if (added != null) {
                    selectSub(added);
                    Toast.makeText(PlayerActivity.this, "Subtitles added and switched on", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(PlayerActivity.this,
                            "Downloaded, but they haven't appeared yet. Check the Subtitles list.",
                            Toast.LENGTH_LONG).show();
                }
                if (player != null && wasPlaying) player.play();
            }

            @Override
            public void fail(Exception e) {
                if (isDestroyed()) return;
                if (player != null && wasPlaying) player.play();
                if (!panelOpen) return;
                list.removeAllViews();
                addHeading("Couldn't download that one (" + e.getMessage() + ").");
                list.addView(makeRow("Back", null, false, false, v -> showTab(TAB_SUBS)));
                focusFirstOrSelected(null);
            }
        });
    }

    private void showInfoTab() {
        list.addView(makeRow("Show this on the video", overlayOn ? "On" : "Off", overlayOn, false, v -> {
            overlayOn = !overlayOn;
            overlay.setVisibility(overlayOn ? View.VISIBLE : View.GONE);
            showTab(TAB_INFO);
        }));

        // Phone remote: scan to follow and control this from a phone on the same Wi-Fi
        final String remoteUrl = PhoneRemote.url();
        if (remoteUrl != null) {
            LinearLayout remoteRow = new LinearLayout(this);
            remoteRow.setOrientation(LinearLayout.HORIZONTAL);
            remoteRow.setGravity(Gravity.CENTER_VERTICAL);
            remoteRow.setPadding((int) (W * 0.014f), (int) (H * 0.02f), 0, 0);
            ImageView code = new ImageView(this);
            int qs = (int) (H * 0.2f);
            code.setImageBitmap(qr(remoteUrl, qs));
            remoteRow.addView(code, new LinearLayout.LayoutParams(qs, qs));
            TextView how = new TextView(this);
            how.setText("Control this from your phone\nScan the code, or open\n" + remoteUrl.replace("?t=", "\n?t="));
            how.setTextColor(UiUtil.TEXT2);
            how.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.022f);
            how.setPadding((int) (W * 0.016f), 0, 0, 0);
            remoteRow.addView(how);
            list.addView(remoteRow);
        }

        infoText = new TextView(this);
        infoText.setTextColor(0xFF9DFFB0);
        infoText.setTypeface(Typeface.MONOSPACE);
        infoText.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.021f);
        infoText.setPadding((int) (W * 0.014f), (int) (H * 0.02f), 0, 0);
        infoText.setText(debugText());
        list.addView(infoText);

        list.getChildAt(0).requestFocus();
        handler.removeCallbacks(debugTick);
        handler.post(debugTick);
    }

    // ----------------------------------------------------- Audio debug

    private static String yesNo(boolean b) {
        return b ? "yes" : "no";
    }

    // ------------------------------------------------------ Phone remote

    private List<String[]> remoteChapters = new ArrayList<>();

    /** What the phone page sees and can do; PhoneRemote calls this on the main thread. */
    private final PhoneRemote.Host remoteHost = new PhoneRemote.Host() {
        @Override
        public String stateJson() {
            try {
                if (item == null || player == null) return "{\"idle\":true}";
                org.json.JSONObject o = new org.json.JSONObject();
                boolean ep = "episode".equals(item.type);
                o.put("title", item.displayTitle());
                o.put("sub", ep ? "S" + item.parentIndex + " E" + item.index + " · " + item.title : "");
                long dur = item.duration > 0 ? item.duration : offsetMs + Math.max(0, player.getDuration());
                o.put("pos", trickDir != 0 ? trickPos : currentMs());
                o.put("dur", dur);
                o.put("playing", player.getPlayWhenReady());
                org.json.JSONArray au = new org.json.JSONArray();
                for (Plex.Stream s : item.streamsOf(2)) {
                    au.put(new org.json.JSONObject().put("id", s.id).put("label", s.label())
                            .put("sel", curAudio != null && curAudio.id == s.id));
                }
                o.put("audio", au);
                org.json.JSONArray su = new org.json.JSONArray();
                boolean subsOn = curSub != null && (!transcoding || subChosen);
                for (Plex.Stream s : item.streamsOf(3)) {
                    su.put(new org.json.JSONObject().put("id", s.id)
                            .put("label", s.label() + (s.forced ? " (forced)" : ""))
                            .put("sel", subsOn && curSub.id == s.id));
                }
                o.put("subs", su);
                o.put("subOff", !subsOn);
                org.json.JSONArray ch = new org.json.JSONArray();
                for (String[] c : remoteChapters) {
                    ch.put(new org.json.JSONObject().put("ms", Long.parseLong(c[0])).put("name", c[1]));
                }
                o.put("chapters", ch);
                o.put("info", remoteInfo(dur));
                o.put("buffered", offsetMs + Math.max(0, player.getBufferedPosition()));
                o.put("buf", remoteBuffer());
                o.put("stats", remoteStats(dur));
                return o.toString();
            } catch (Exception e) {
                return "{\"idle\":true}";
            }
        }

        @Override
        public String command(String name, java.util.Map<String, String> a) {
            if (player == null || item == null) return "";
            touched(); // a phone press counts as someone being there
            try {
                switch (name) {
                    case "toggle":
                        player.setPlayWhenReady(!player.getPlayWhenReady());
                        break;
                    case "close": // something else is being sent to the TV: let go of the sound output first
                        finish();
                        break;
                    case "seek": {
                        long dur = item.duration > 0 ? item.duration : Long.MAX_VALUE;
                        seekAbs(Math.min(dur - 2000, currentMs() + Long.parseLong(a.get("d"))));
                        break;
                    }
                    case "chapter": {
                        long dur = item.duration > 0 ? item.duration : Long.MAX_VALUE;
                        seekAbs(Math.min(dur - 2000, Long.parseLong(a.get("ms"))));
                        break;
                    }
                    case "audio": {
                        int id = Integer.parseInt(a.get("id"));
                        for (Plex.Stream s : item.streamsOf(2)) if (s.id == id) selectAudio(s);
                        break;
                    }
                    case "sub": {
                        int id = Integer.parseInt(a.get("id"));
                        if (id == 0) {
                            selectSub(null);
                        } else {
                            for (Plex.Stream s : item.streamsOf(3)) if (s.id == id) selectSub(s);
                        }
                        break;
                    }
                    default:
                        break;
                }
            } catch (RuntimeException ignored) {
            }
            return "";
        }
    };

    /** Live buffer numbers: seconds ahead in memory and on disk, how much memory, download speed. */
    private org.json.JSONObject remoteBuffer() throws org.json.JSONException {
        org.json.JSONObject b = new org.json.JSONObject();
        long ramSec = player.getTotalBufferedDuration() / 1000;
        long rate = bigBuffer != null && !transcoding ? bigBuffer.bytesPerSecond() : 0;
        if (rate <= 0) {
            Format vf = player.getVideoFormat();
            Format af = player.getAudioFormat();
            rate = ((vf != null && vf.bitrate > 0 ? vf.bitrate : 0) + (af != null && af.bitrate > 0 ? af.bitrate : 0)) / 8;
        }
        b.put("ramSec", ramSec);
        b.put("ramMb", rate > 0 ? Math.min(ramSec * rate >> 20, 99999) : 0);
        b.put("capMb", bufferCapMb);
        boolean disk = BigBuffer.enabled(this) && bigBuffer != null;
        b.put("disk", disk);
        b.put("diskSec", disk && bigBuffer != null ? Math.max(0, bigBuffer.aheadSeconds() - ramSec) : 0);
        b.put("diskGb", disk ? Math.round(BigBuffer.usedBytes() / 1073741824.0 * 100) / 100.0 : 0);
        double mbps = bigBuffer != null && disk ? bigBuffer.downloadMbps() : 0;
        if (mbps <= 0) {
            mbps = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(this)
                    .getBitrateEstimate() / 1_000_000.0;
        }
        b.put("mbps", Math.round(mbps * 10) / 10.0);
        return b;
    }

    /** Everything else worth a glance on the phone, as {k, v} rows. */
    private org.json.JSONArray remoteStats(long dur) throws org.json.JSONException {
        org.json.JSONArray out = new org.json.JSONArray();
        java.util.function.BiConsumer<String, String> add = (k, v) -> {
            try {
                out.put(new org.json.JSONObject().put("k", k).put("v", v));
            } catch (org.json.JSONException ignored) {
            }
        };
        add.accept("Mode", transcoding ? "Transcode (Plex converts)" : "Direct play");
        Format v = player.getVideoFormat();
        if (v != null) {
            String mime = v.sampleMimeType == null ? "" : v.sampleMimeType;
            String codec = mime.contains("hevc") ? "HEVC" : mime.contains("avc") ? "H.264" : mime.contains("av01") ? "AV1"
                    : mime.contains("vp9") ? "VP9" : mime.replace("video/", "").toUpperCase(Locale.ROOT);
            String range = "SDR";
            for (Plex.Stream s : item.streamsOf(1)) if (s.dovi) range = "Dolby Vision" + (s.doviProfile > 0 ? " P" + s.doviProfile : "");
            if (range.equals("SDR") && v.colorInfo != null) {
                range = v.colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084 ? "HDR10"
                        : v.colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG ? "HLG" : "SDR";
            }
            add.accept("Video", codec + "  " + v.width + "×" + v.height
                    + (v.frameRate > 0 ? "  " + Math.round(v.frameRate * 1000) / 1000f + " fps" : "") + "  " + range);
        }
        add.accept("Video decoder", videoDecoder);
        if (v != null) {
            Object[] pic = pictureStatus(v);
            add.accept("Picture", ((Boolean) pic[0] ? "✓ " : "✗ ") + pic[1]);
        }
        Format a = player.getAudioFormat();
        if (a != null) {
            add.accept("Audio", String.valueOf(a.sampleMimeType).replace("audio/", "") + "  " + a.channelCount + " ch  "
                    + a.sampleRate + " Hz" + (a.averageBitrate > 0 ? "  " + a.averageBitrate / 1000 + " kbps" : ""));
        }
        add.accept("Audio decoder", audioDecoder);
        if (item.fileSize > 0 && dur > 0) {
            add.accept("File", String.format(Locale.US, "%.2f GB  (avg %.1f Mbit/s)", item.fileSize / 1073741824.0,
                    item.fileSize * 8.0 / (dur / 1000.0) / 1_000_000.0));
        }
        add.accept("Dropped frames", String.valueOf(dropped));
        add.accept("Audio underruns", String.valueOf(underruns));
        try {
            android.view.Display d = getWindowManager().getDefaultDisplay();
            android.graphics.Point p = new android.graphics.Point();
            d.getRealSize(p);
            add.accept("TV output", p.x + "×" + p.y + "  @ " + Math.round(d.getRefreshRate() * 1000) / 1000f + " Hz");
        } catch (RuntimeException ignored) {
        }
        try {
            Runtime rt = Runtime.getRuntime();
            long used = (rt.totalMemory() - rt.freeMemory() + android.os.Debug.getNativeHeapAllocatedSize()) >> 20;
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            ((android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
            add.accept("App memory", used + " MB  (device free " + (mi.availMem >> 20) + " of " + (mi.totalMem >> 20) + " MB)");
        } catch (RuntimeException ignored) {
        }
        for (String[] r : nerdRows()) add.accept(r[0], r[1]);
        if (!lastError.isEmpty()) add.accept("Last error", lastError);
        return out;
    }

    private static String mb(long bytes) {
        return bytes >= 1073741824L ? String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
                : String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private static String kbps(long kbit) {
        return kbit >= 1000 ? String.format(Locale.US, "%.2f Mbit/s", kbit / 1000.0) : kbit + " kbit/s";
    }

    /** Hours:minutes:seconds.millis for a length in ms. */
    private static String hms(long ms) {
        return String.format(Locale.US, "%d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }

    /**
     * The nerdy extras, as {name, value} rows (an empty value makes a heading): what is in the original file
     * as Plex sees it, and how the disk and memory buffers are doing. Used by the phone page and the TV Info tab.
     */
    private List<String[]> nerdRows() {
        List<String[]> out = new ArrayList<>();
        if (item == null) return out;
        org.json.JSONObject m = item.media == null ? new org.json.JSONObject() : item.media;
        org.json.JSONObject pt = item.part == null ? new org.json.JSONObject() : item.part;
        out.add(new String[]{"ORIGINAL FILE", ""});
        String file = pt.optString("file", "");
        if (!file.isEmpty()) out.add(new String[]{"Path on server", file});
        String cont = pt.optString("container", m.optString("container", ""));
        out.add(new String[]{"Container", (cont.isEmpty() ? "?" : cont.toUpperCase(Locale.ROOT))
                + (pt.optBoolean("optimizedForStreaming", false) ? "  (optimised for streaming)" : "")});
        if (item.fileSize > 0) {
            out.add(new String[]{"Size", mb(item.fileSize) + "  (" + String.format(Locale.US, "%,d", item.fileSize) + " bytes)"});
        }
        long fdur = pt.optLong("duration", item.duration);
        if (fdur > 0) out.add(new String[]{"Duration", hms(fdur)});
        if (m.optLong("bitrate", 0) > 0) out.add(new String[]{"Overall bitrate", kbps(m.optLong("bitrate"))});
        if (item.mediaCount > 1) out.add(new String[]{"Versions on server", item.mediaCount + " (playing the first)"});
        if (!m.optString("videoFrameRate", "").isEmpty()) out.add(new String[]{"Frame rate class", m.optString("videoFrameRate")});
        if (!m.optString("aspectRatio", "").isEmpty()) out.add(new String[]{"Aspect ratio", m.optString("aspectRatio") + " : 1"});
        int n = 0;
        int subCount = 0;
        for (Plex.Stream s : item.streams) {
            org.json.JSONObject r = s.raw == null ? new org.json.JSONObject() : s.raw;
            n++;
            if (s.type == 1) {
                out.add(new String[]{"VIDEO STREAM #" + r.optInt("index", n - 1), ""});
                out.add(new String[]{"Codec", (s.codec.isEmpty() ? "?" : s.codec.toUpperCase(Locale.ROOT))
                        + (s.profile.isEmpty() ? "" : "  profile " + s.profile)
                        + (r.has("level") ? "  level " + r.optInt("level") : "")});
                out.add(new String[]{"Frame size", r.optInt("width", 0) + " × " + r.optInt("height", s.height)
                        + (r.has("codedWidth") ? "  (coded " + r.optInt("codedWidth") + " × " + r.optInt("codedHeight") + ")" : "")});
                out.add(new String[]{"Frame rate", r.optString("frameRate", "?") + " fps"
                        + (r.optString("scanType", "").isEmpty() ? "" : "  " + r.optString("scanType"))});
                out.add(new String[]{"Bit depth / chroma", (s.bitDepth > 0 ? s.bitDepth + "-bit" : "?") + "  "
                        + r.optString("chromaSubsampling", "?")
                        + (r.optString("chromaLocation", "").isEmpty() ? "" : "  (" + r.optString("chromaLocation") + ")")});
                out.add(new String[]{"Colour", r.optString("colorSpace", "?") + "  primaries " + r.optString("colorPrimaries", "?")
                        + "  transfer " + (s.colorTrc.isEmpty() ? "?" : s.colorTrc) + "  range " + r.optString("colorRange", "?")});
                if (r.has("refFrames")) out.add(new String[]{"Reference frames", String.valueOf(r.optInt("refFrames"))});
                if (s.bitrate > 0) out.add(new String[]{"Video bitrate", kbps(s.bitrate)});
                if (s.dovi) {
                    out.add(new String[]{"Dolby Vision", "profile " + s.doviProfile + "  level " + r.optInt("DOVILevel", 0)
                            + "  BL " + (r.optBoolean("DOVIBLPresent") ? "yes" : "no")
                            + "  EL " + (r.optBoolean("DOVIELPresent") ? "yes" : "no")
                            + "  RPU " + (r.optBoolean("DOVIRPUPresent") ? "yes" : "no")
                            + (r.has("DOVIBLCompatID") ? "  BL compat " + r.optInt("DOVIBLCompatID") : "")});
                }
            } else if (s.type == 2) {
                out.add(new String[]{"AUDIO STREAM #" + r.optInt("index", n - 1) + (s.selected ? "  (selected)" : ""), ""});
                out.add(new String[]{"Codec", (s.codec.isEmpty() ? "?" : s.codec.toUpperCase(Locale.ROOT))
                        + (s.profile.isEmpty() ? "" : "  " + s.profile)
                        + "  " + s.channels + " ch" + (s.layout.isEmpty() ? "" : " (" + s.layout + ")")});
                out.add(new String[]{"Format", (r.optInt("samplingRate", 0) > 0 ? r.optInt("samplingRate") + " Hz" : "? Hz")
                        + (s.bitDepth > 0 ? "  " + s.bitDepth + "-bit" : "")
                        + (s.bitrate > 0 ? "  " + kbps(s.bitrate) : "")
                        + (r.optString("bitrateMode", "").isEmpty() ? "" : "  " + r.optString("bitrateMode").toUpperCase(Locale.ROOT))});
                out.add(new String[]{"Track", (s.lang.isEmpty() ? "?" : s.lang) + (s.isDefault ? "  default" : "")
                        + (s.title.isEmpty() ? "" : "  “" + s.title + "”")});
            } else if (s.type == 3) {
                if (++subCount > 6) {
                    if (subCount == 7) out.add(new String[]{"More subtitles", item.streamsOf(3).size() - 6 + " more tracks in the file"});
                    continue;
                }
                out.add(new String[]{"SUBTITLE #" + r.optInt("index", n - 1) + (s.isExternal() ? "  (separate file)" : "  (in file)"), ""});
                out.add(new String[]{"Format", (s.format.isEmpty() ? s.codec : s.format).toUpperCase(Locale.ROOT) + "  "
                        + (s.lang.isEmpty() ? "?" : s.lang) + (s.forced ? "  forced" : "") + (s.isDefault ? "  default" : "")});
            }
        }
        out.add(new String[]{"STORAGE AND BUFFER", ""});
        try {
            out.add(new String[]{"Disk store", BigBuffer.describeStore(this)});
            android.os.StatFs sf = new android.os.StatFs(getCacheDir().getAbsolutePath());
            out.add(new String[]{"Free space", mb(sf.getAvailableBytes()) + " of " + mb(sf.getTotalBytes())});
        } catch (RuntimeException ignored) {
        }
        if (bigBuffer != null && BigBuffer.enabled(this)) {
            if (bigBuffer.isHls()) {
                out.add(new String[]{"Disk filler", bigBuffer.status() + "  (converted stream: "
                        + bigBuffer.hlsSegmentsStored() + " pieces, " + mb(bigBuffer.hlsStoredBytes()) + " fetched so far, "
                        + bigBuffer.aheadSeconds() + " s ahead of the playhead)"});
                out.add(new String[]{"Read from disk", mb(BigBuffer.servedFromDisk) + " given to the player from the disk store"
                        + (bigBuffer.playerLoadingNow() ? "  (memory still filling from the network)"
                        : "  (memory full: the disk is filling)")});
            } else {
                out.add(new String[]{"Disk filler", bigBuffer.status() + "  (" + mb(bigBuffer.ahead()) + " fetched ahead of "
                        + mb(bigBuffer.fileLength()) + ")"});
                out.add(new String[]{"File data rate", mb(bigBuffer.bytesPerSecond()) + "/s average"});
            }
            out.add(new String[]{"Memory buffer cap", bufferCapMb + " MB  (" + (bufferCapMb > 100 ? "extra large" : "normal") + ")"});
        }
        if (player != null) {
            String[] states = {"idle", "buffering", "ready", "ended"};
            out.add(new String[]{"Player buffer", "position " + hms(player.getCurrentPosition()) + "  buffered to "
                    + hms(player.getBufferedPosition()) + "  (+" + (player.getTotalBufferedDuration() / 1000.0) + " s)"});
            out.add(new String[]{"Player state", states[Math.max(0, Math.min(3, player.getPlaybackState() - 1))]
                    + (player.getPlayWhenReady() ? ", playing" : ", paused")
                    + "  speed " + player.getPlaybackParameters().speed + "×"});
            if (loadCtl != null) {
                out.add(new String[]{"Player memory", mb(loadCtl.getAllocator().getTotalBytesAllocated()) + " in use of "
                        + bufferCapMb + " MB cap  (" + (player.isLoading() ? "loading from network/disk" : "not loading") + ")"});
            }
        }
        return out;
    }

    /** "Direct Play • HEVC • DV P8 • 74.2 Mbps • TrueHD Atmos 7.1 • buffer 61s" for the phone. */
    private String remoteInfo(long dur) {
        List<String> p = new ArrayList<>();
        p.add(transcoding ? "Transcode" : "Direct Play");
        Format v = player.getVideoFormat();
        String mime = v == null || v.sampleMimeType == null ? "" : v.sampleMimeType;
        if (!mime.isEmpty()) {
            p.add(mime.contains("hevc") ? "HEVC" : mime.contains("avc") ? "H.264" : mime.contains("av01") ? "AV1"
                    : mime.contains("vp9") ? "VP9" : mime.replace("video/", "").toUpperCase(Locale.ROOT));
        }
        for (Plex.Stream s : item.streamsOf(1)) {
            if (s.dovi) p.add(s.doviProfile > 0 ? "DV P" + s.doviProfile : "DV");
        }
        if (item.fileSize > 0 && dur > 0) {
            p.add(String.format(Locale.US, "%.1f Mbps", item.fileSize * 8.0 / (dur / 1000.0) / 1_000_000.0));
        }
        if (curAudio != null) p.add(curAudio.displayTitle.isEmpty() ? curAudio.label() : curAudio.displayTitle);
        p.add("buffer " + player.getTotalBufferedDuration() / 1000 + "s");
        return android.text.TextUtils.join(" • ", p);
    }

    /** A QR code for [text] as a bitmap, dark on white. */
    private static android.graphics.Bitmap qr(String text, int px) {
        try {
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, px, px);
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
            for (int x = 0; x < px; x++) for (int y = 0; y < px; y++) b.setPixel(x, y, m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * What picture is really being played, judged from the stream the player decodes (not just what the TV
     * could do): {ok, text}. The file's Dolby Vision layer only counts when the decoder is running it as Dolby Vision.
     */
    private Object[] pictureStatus(Format v) {
        boolean srcDv = false;
        int dvProfile = 0;
        boolean srcPq = false;
        boolean srcHlg = false;
        if (item != null) {
            for (Plex.Stream s : item.streamsOf(1)) {
                if (s.dovi) {
                    srcDv = true;
                    dvProfile = s.doviProfile;
                }
                if (s.colorTrc.equals("smpte2084")) srcPq = true;
                if (s.colorTrc.equals("arib-std-b67")) srcHlg = true;
            }
        }
        String mime = v.sampleMimeType == null ? "" : v.sampleMimeType;
        int trc = v.colorInfo == null ? C.COLOR_TRANSFER_SDR : v.colorInfo.colorTransfer;
        boolean outDv = mime.contains("dolby-vision");
        boolean outPq = trc == C.COLOR_TRANSFER_ST2084;
        boolean outHlg = trc == C.COLOR_TRANSFER_HLG;
        boolean dvTv = displaySupportsDolbyVision(this);
        boolean hdrTv = tvHdr10(this);
        String how = transcoding ? " (via the Plex converter)" : "";
        if (outDv) {
            return new Object[]{dvTv, dvTv ? "Playing in Dolby Vision" + (dvProfile > 0 ? " (profile " + dvProfile + ")" : "") + how
                    : "Dolby Vision stream, but this TV can't show it" + how};
        }
        if (outPq) {
            String t = srcDv ? "Playing as HDR10 (the Dolby Vision layer is not in use)" : "Playing in HDR10";
            return new Object[]{hdrTv, hdrTv ? t + how : "HDR10 stream, but this TV can't show it" + how};
        }
        if (outHlg) return new Object[]{hdrTv, hdrTv ? "Playing in HLG HDR" + how : "HLG stream, but this TV can't show it" + how};
        if (srcDv || srcPq || srcHlg) return new Object[]{false, "Playing in SDR: the file's HDR is not being used" + how};
        return new Object[]{true, "Playing in SDR (the file is not HDR)"};
    }

    private static String tick(boolean ok, String text) {
        return (ok ? "✓ " : "✗ ") + text + '\n';
    }

    /** The short "will this play properly" list shown at the top of Info: picture, sound, subtitles, refresh rate, speed. */
    private String compatSummary() {
        StringBuilder sb = new StringBuilder();
        if (item != null) sb.append(item.displayTitle()).append('\n');
        Format v = player.getVideoFormat();
        String mime = v == null || v.sampleMimeType == null ? "" : v.sampleMimeType;
        if (v != null) {
            String codec = mime.contains("hevc") ? "HEVC" : mime.contains("avc") ? "H.264"
                    : mime.contains("av01") ? "AV1" : mime.contains("vp9") ? "VP9"
                    : mime.contains("dolby-vision") ? "Dolby Vision" : mime.replace("video/", "").toUpperCase(Locale.ROOT);
            String dec = videoDecoder == null ? "" : videoDecoder.toLowerCase(Locale.ROOT);
            boolean software = dec.startsWith("c2.android.") || dec.startsWith("omx.google.") || dec.contains(".sw.");
            sb.append(tick(!software, codec + (software ? " software decode" : " hardware decode")));
            Object[] pic = pictureStatus(v);
            sb.append(tick((Boolean) pic[0], (String) pic[1]));
        }
        Format a = player.getAudioFormat();
        if (a != null && a.sampleMimeType != null) {
            String m = a.sampleMimeType;
            boolean atmos = curAudio != null && curAudio.displayTitle != null
                    && curAudio.displayTitle.toLowerCase(Locale.ROOT).contains("atmos");
            String name = m.contains("true-hd") ? "TrueHD" + (atmos ? " Atmos" : "")
                    : m.contains("eac3-joc") ? "Dolby Digital Plus Atmos"
                    : m.contains("eac3") ? "Dolby Digital Plus" : m.contains("ac3") ? "Dolby Digital"
                    : m.contains("vnd.dts.hd") ? "DTS-HD" : m.contains("dts") ? "DTS"
                    : m.contains("mp4a") ? "AAC" : m.replace("audio/", "").toUpperCase(Locale.ROOT);
            boolean bitstream = m.contains("true-hd") || m.contains("ac3") || m.contains("dts");
            sb.append(tick(true, name + (bitstream && !transcoding && !ac3 ? " passthrough" : " (decoded by the Fire TV)")));
        }
        if (curSub != null && (!transcoding || subChosen)) {
            String f = (curSub.format.isEmpty() ? curSub.codec : curSub.format).toUpperCase(Locale.ROOT);
            if (f.contains("PGS") || f.contains("HDMV")) f = "PGS";
            sb.append(tick(true, f + " subtitles " + (transcoding && isPicture(curSub) ? "(burned in by Plex)" : "supported")));
        }
        if (v != null && v.frameRate > 0) {
            float best = 0;
            try {
                for (android.view.Display.Mode mode : getWindowManager().getDefaultDisplay().getSupportedModes()) {
                    for (int k = 1; k <= 4; k++) {
                        if (Math.abs(mode.getRefreshRate() - v.frameRate * k) < 0.05f) best = Math.max(best, mode.getRefreshRate());
                    }
                }
            } catch (RuntimeException ignored) {
            }
            float fps = Math.round(v.frameRate * 1000) / 1000f;
            if (best > 0) sb.append(tick(true, String.format(Locale.US, "%.3f Hz available", best)));
            else sb.append(tick(false, String.format(Locale.US, "%.3f Hz not offered by the TV", fps)));
        }
        double avg = item != null && item.fileSize > 0 && item.duration > 0
                ? item.fileSize * 8.0 / (item.duration / 1000.0) / 1_000_000.0 : 0;
        if (avg > 0 && !transcoding) {
            double est = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(this)
                    .getBitrateEstimate() / 1_000_000.0;
            if (est > 0) sb.append(tick(est >= avg * 1.3, est >= avg * 1.3 ? "Bandwidth sufficient" : "Bandwidth is tight for this file"));
        }
        sb.append(transcoding ? "TRANSCODING (Plex converts)" : "DIRECT PLAY");
        if (avg > 0) sb.append(String.format(Locale.US, " — %.1f Mbps", avg));
        sb.append('\n');
        return sb.toString();
    }

    private String debugText() {
        StringBuilder sb = new StringBuilder();
        if (player == null) return "Not playing";
        sb.append(compatSummary()).append('\n');
        if (!lastError.isEmpty()) sb.append("Last error ").append(lastError).append('\n');
        if (transcoding && !plan.isEmpty()) sb.append("Plex plan:\n").append(plan);

        if (bufferCapMb > 0) sb.append("Buffer cap ").append(bufferCapMb).append(" MB\n");
        sb.append("Mode      ").append(transcoding ? "Transcode (Plex converts)" : "Direct play").append('\n');
        Format v = player.getVideoFormat();
        if (v != null) {
            sb.append("Video     ").append(v.sampleMimeType).append("  ").append(v.width).append('x')
                    .append(v.height);
            if (v.frameRate > 0) sb.append("  ").append(Math.round(v.frameRate * 100) / 100f).append("fps");
            sb.append('\n');
            if (v.colorInfo != null) {
                String t = v.colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084 ? "HDR10 / PQ"
                        : v.colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG ? "HLG" : "SDR";
                sb.append("          ").append(t).append('\n');
            }
        }
        sb.append("Video dec ").append(videoDecoder).append('\n');

        Format a = player.getAudioFormat();
        if (a != null) {
            sb.append("Audio     ").append(a.sampleMimeType).append('\n');
            sb.append("          ").append(a.channelCount).append(" ch  ").append(a.sampleRate)
                    .append(" Hz");
            if (a.averageBitrate > 0) sb.append("  ").append(a.averageBitrate / 1000).append(" kbps");
            sb.append('\n');
        } else {
            sb.append("Audio     none playing\n");
        }
        sb.append("Audio dec ").append(audioDecoder).append('\n');

        sb.append("Dropped   ").append(dropped).append(" frames\n");
        sb.append("Underruns ").append(underruns).append('\n');
        // Buffer, in two simple lines: what's waiting in memory, and what's waiting on the Fire TV's storage
        long ramSec = player.getTotalBufferedDuration() / 1000;
        // ...with the memory it takes in brackets (seconds x the file's data rate; the player doesn't report it)
        long rate = bigBuffer != null && !transcoding ? bigBuffer.bytesPerSecond() : 0;
        if (rate <= 0) {
            Format vf = player.getVideoFormat();
            Format af = player.getAudioFormat();
            long bits = (vf != null && vf.bitrate > 0 ? vf.bitrate : 0) + (af != null && af.bitrate > 0 ? af.bitrate : 0);
            rate = bits / 8;
        }
        sb.append("Buffer RAM   ").append(aheadText(ramSec)).append(" ahead");
        if (rate > 0) {
            sb.append("  (~").append(Math.min(ramSec * rate >> 20, 99999)).append(" MB of ")
                    .append(bufferCapMb).append(" MB)");
        }
        sb.append('\n');
        if (BigBuffer.enabled(this) && bigBuffer != null) {
            // The store counts everything fetched ahead; what's already loaded into memory isn't "on disk" any more
            long diskSec = bigBuffer == null ? 0 : Math.max(0, bigBuffer.aheadSeconds() - ramSec);
            sb.append("Buffer disk  ").append(aheadText(diskSec)).append(" ahead  (")
                    .append(String.format(Locale.US, "%.2f", BigBuffer.usedBytes() / 1073741824.0)).append(" GB)\n");
        } else if (BigBuffer.enabled(this)) {
            // The setting is on, but this film isn't playing as it is, so there is nothing to store ahead
            sb.append("Buffer disk  not used: Plex is converting this film (")
                    .append(quality != 0 ? "a lower video quality is chosen"
                            : ac3 ? "its sound is being converted"
                            : "direct play failed" + (lastError.isEmpty() ? "" : ", " + lastError))
                    .append("), so the memory buffer is limited to ").append(bufferCapMb).append(" MB\n");
        } else {
            sb.append("Buffer disk  off (Settings > Extra large buffer)\n");
        }
        // Download speed: the disk filler's own pace when it's running, otherwise the player's estimate
        double mbps = bigBuffer != null && BigBuffer.enabled(this) ? bigBuffer.downloadMbps() : 0;
        if (mbps <= 0) {
            long est = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(this).getBitrateEstimate();
            mbps = est / 1_000_000.0;
        }
        sb.append("Download     ").append(mbps > 0 ? String.format(Locale.US, "%.1f Mbit/s", mbps) : "measuring...").append('\n');
        try {
            Runtime rt = Runtime.getRuntime();
            long heapUsed = rt.totalMemory() - rt.freeMemory();
            long nativeUsed = android.os.Debug.getNativeHeapAllocatedSize();
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            ((android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
            sb.append("RAM       app ").append((heapUsed + nativeUsed) >> 20).append(" MB  (heap ")
                    .append(heapUsed >> 20).append(" of ").append(rt.maxMemory() >> 20).append(", native ")
                    .append(nativeUsed >> 20).append(")\n");
            sb.append("          device free ").append(mi.availMem >> 20).append(" of ").append(mi.totalMem >> 20)
                    .append(" MB\n");
        } catch (RuntimeException ignored) {
        }
        return sb.toString();
    }

    // -------------------------------------------------------- Lifecycle

    @Override
    protected void onPause() {
        super.onPause();
        // Leaving for good: give the audio output back now, so the next film can open its own
        if (isFinishing()) {
            finishReporting();
            releasePlayer();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) player.pause();
        finishReporting();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sLastSeen = System.currentTimeMillis();
        handler.removeCallbacksAndMessages(null);
        PhoneRemote.detach(remoteHost);
        if (osd != null) osd.release();
        // Update the Fire TV home row once Plex has recorded where you stopped.
        final android.content.Context app = getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> TvRecommendations.refresh(app, null), 4000);
        finishReporting();
        releasePlayer();
    }

    private static final long SAMPLE_MS = 5 * 60_000L;
    private long startedAtMs = 0; // where this play began (0 for a fresh start)

    private void finishReporting() {
        if (reportedStop || item == null || player == null) return;
        long pos = currentMs();
        // Quit within the first five minutes of something you only just started: don't leave it in Continue Watching
        boolean justSampled = channelNum > 0 || (pos < SAMPLE_MS && startedAtMs < SAMPLE_MS && item.viewOffset <= 0);
        Plex.timeline(key, "stopped", justSampled ? 0 : pos, item.duration);
        // Channel viewing is just TV in the background: it never marks things watched or fills Continue Watching
        if (channelNum <= 0 && item.duration > 0 && pos > item.duration * 0.92) Plex.setWatched(key, true);
        reportedStop = true;
    }
}
