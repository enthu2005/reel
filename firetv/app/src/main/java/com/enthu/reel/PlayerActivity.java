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

    static void startQueue(Context c, List<Plex.Item> items, int startIndex) {
        if (items == null || items.isEmpty()) return;
        ArrayList<String> keys = new ArrayList<>();
        for (Plex.Item it : items) if (it != null && it.key != null && !it.key.isEmpty()) keys.add(it.key);
        if (keys.isEmpty()) return;
        int start = Math.max(0, Math.min(startIndex, keys.size() - 1));
        Intent i = new Intent(c, PlayerActivity.class);
        i.putExtra("key", keys.get(start));
        i.putExtra("start", 0L);
        i.putStringArrayListExtra("queue", keys);
        i.putExtra("queueIndex", start);
        c.startActivity(i);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String session = UUID.randomUUID().toString(); // new for every converted stream

    private int W;
    private int H;

    // Playback
    private PlayerView view;
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
    private final List<String> queue = new java.util.ArrayList<>();
    private int queueIndex = -1;

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

    private Plex.Item nextEpisode() {
        if (item == null || !"episode".equals(item.type)) return null;
        for (int i = 0; i < episodes.size() - 1; i++) {
            if (episodes.get(i).key.equals(key)) return episodes.get(i + 1);
        }
        return null;
    }

    private boolean queueActive() {
        return !queue.isEmpty() && queueIndex >= 0 && queueIndex < queue.size();
    }

    private void playQueueNext() {
        if (!queueActive() || queueIndex + 1 >= queue.size()) return;
        queueIndex++;
        PlayerActivity.start(this, queue.get(queueIndex), 0);
        finish();
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

        // A minute ahead: fetch the next episode's details and warm up its file on the server.
        if (!upNextPrefetched && pos >= start - 60_000) {
            upNextPrefetched = true;
            Plex.async(() -> {
                Plex.Item m = Plex.metadata(next.key);
                preloaded = m;
                if (m.partKey != null) {
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
            upNext.animate().alpha(1f).translationX(0f).setDuration(300)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            upNextAt = android.os.SystemClock.uptimeMillis();
            upNextLeft = UP_NEXT_MS;
            if (skipBtn != null) skipBtn.setVisibility(View.GONE);
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (player.getPlayWhenReady()) upNextLeft -= Math.max(0, now - upNextAt); // holds while paused
        upNextAt = now;
        if (upNextLeft <= 0) {
            playNext();
            return;
        }
        upNextCount.setText(player.getPlayWhenReady() ? "Playing in " + ((upNextLeft + 999) / 1000) : "Paused");
        View box = (View) upNextBar.getParent();
        ViewGroup.LayoutParams lp = upNextBar.getLayoutParams();
        lp.width = (int) (box.getWidth() * (1f - (float) upNextLeft / UP_NEXT_MS));
        upNextBar.setLayoutParams(lp);
    }

    private void hideUpNext() {
        upNext.animate().alpha(0f).setDuration(200).withEndAction(() -> upNext.setVisibility(View.GONE)).start();
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
    private int bufferCapMb = 0;
    private String plan = "";
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

    /** True when the Fire TV says the chain to the receiver can carry Dolby Digital Plus as it is. */
    private boolean sinkEac3() {
        try {
            return AudioCapabilities.getCapabilities(this).supportsEncoding(C.ENCODING_E_AC3);
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
            int sc = c.contains("ac3") && !c.contains("eac3") ? 40 : isDts(c) ? 30
                    : (c.contains("eac3") ? 25 : 10);
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        view = new PlayerView(this);
        view.setKeepScreenOn(true);
        view.setControllerShowTimeoutMs(4000);
        view.setShowSubtitleButton(false);
        view.setUseController(false); // our own Plex-style controls (PlayerOsd)
        applySubStyle();
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
            hint.setVisibility(vis && !panelOpen ? View.VISIBLE : View.GONE);
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
        final long startMs = getIntent().getLongExtra("start", 0);
        ArrayList<String> queued = getIntent().getStringArrayListExtra("queue");
        if (queued != null) {
            queue.clear();
            queue.addAll(queued);
            queueIndex = getIntent().getIntExtra("queueIndex", queue.indexOf(key));
            if (queueIndex < 0 && !queue.isEmpty()) queueIndex = 0;
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

    private MediaItem buildItem(boolean transcode) {
        String url = transcode
                ? Plex.transcodeUrl(key, offsetMs, session,
                curAudio != null ? curAudio.id : 0, burnId(), ac3,
                (Integer) QUALITIES[quality][1], (String) QUALITIES[quality][2])
                : Plex.directUrl(item);
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
        sv.setApplyEmbeddedFontSizes(false);
        sv.setApplyEmbeddedStyles(look == 0);
        sv.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * sizes[size]);
        int bg = look == 1 ? 0x99000000 : look == 2 ? 0xFF000000 : 0x00000000;
        int edge = look == 0 ? androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
                : androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE;
        sv.setStyle(new androidx.media3.ui.CaptionStyleCompat(0xFFFFFFFF, bg, 0x00000000, edge, 0xFF000000,
                UiUtil.font(UiUtil.SEMIBOLD)));
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
            Plex.setPartSubtitle(item.partKey, want);
            return true;
        }, new Plex.Callback<Boolean>() {
            @Override
            public void ok(Boolean r) {
                lastPartSub = want;
                if (!isDestroyed()) beginNow(positionMs, wantTranscode);
            }

            @Override
            public void fail(Exception e) {
                if (!isDestroyed()) beginNow(positionMs, wantTranscode); // carry on without it
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
                        if (compat) {
                            // Compatible mode: only offer Dolby Digital(+) and plain DTS. A DTS-HD / DTS:X
                            // track then goes out as its DTS core, and Atmos as Dolby Digital Plus.
                            AudioCapabilities real = AudioCapabilities.getCapabilities(context);
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
        ExoPlayer.Builder pb = new ExoPlayer.Builder(this, rf);
        // The Fire TV Cube has little spare memory and a 4K picture needs most of it for the video decoder.
        // Holding a huge buffer starves the decoder (green lines, stutter, "low memory" in the system log),
        // so keep it modest: about 20 to 30 seconds of a big 4K film, a little more of anything smaller.
        long heap = Runtime.getRuntime().maxMemory();
        long cap = Math.max(64L * 1024 * 1024, Math.min(heap / 4, 96L * 1024 * 1024));
        bufferCapMb = (int) (cap / (1024 * 1024));
        pb.setLoadControl(new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 60_000, 2_500, 5_000)
                .setTargetBufferBytes((int) cap)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build());
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
            pb.setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    new androidx.media3.datasource.DefaultDataSource.Factory(this,
                            new androidx.media3.datasource.DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                                    .setConnectTimeoutMs(30000)
                                    .setReadTimeoutMs(60000)
                                    .setDefaultRequestProperties(hdr)))
                    .setLoadErrorHandlingPolicy(
                            new androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(8)));
        }
        else {
            pb.setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    new androidx.media3.datasource.DefaultDataSource.Factory(this,
                            new androidx.media3.datasource.DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                                    .setConnectTimeoutMs(30000)
                                    .setReadTimeoutMs(60000)),
                    new DtsCoreExtractors())
                    .setLoadErrorHandlingPolicy(
                            new androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(8)));
        }
        player = pb.build();
        // Jump to the nearest keyframe when skipping. Exact seeks on 4K HEVC make the picture decode for
        // seconds while the sound is already playing.
        player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC);
        if (getSharedPreferences("reel_ui", MODE_PRIVATE).getBoolean("tunneled", false) && !IecDtsHdSink.enabled) {
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
        view.setPlayer(player);
        player.setMediaItem(buildItem(transcode));
        if (!transcode && positionMs > 0) player.seekTo(positionMs);
        if (!transcode && externalSub != null) enableText(true);
        if (transcode && sidecarSub() != null) enableText(true);

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
                    boolean io = error.errorCode >= 2000 && error.errorCode < 3000;
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
                if (state == Player.STATE_READY) handler.postDelayed(() -> seekingTo = -1, 300);
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
                if (state == Player.STATE_ENDED) {
                    if (queueActive() && queueIndex + 1 < queue.size()) {
                        playQueueNext();
                        return;
                    }
                    if (nextEpisode() != null && autoPlayOn() && !upNextCancelled) {
                        playNext();
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

    private void seekAbs(long ms) {
        if (player == null) return;
        ms = Math.max(0, ms);
        lastGood = ms;
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
    }

    private void releasePlayer() {
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
            v.animate().scaleX(sc).scaleY(sc).setDuration(130).start();
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
        label.setText("Episodes");
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
        label.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        label.setPadding((int) (W * 0.045f) + 8, 0, 0, (int) (H * 0.006f));
        epPanel.addView(label);

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
                        epList.setAdapter(new PosterAdapter(episodes, (int) (ph * 16f / 9f), ph, true,
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
                                }));
                    }

                    @Override
                    public void fail(Exception e) {
                        // Pressing Down just does nothing if the list couldn't load.
                    }
                });
    }

    private void openEpisodes() {
        if (episodes.isEmpty() || panelOpen) return;
        epOpen = true;
        if (osd != null) osd.hide();
        hint.setVisibility(View.GONE);
        epPanel.setVisibility(View.VISIBLE);
        int idx = 0;
        for (int i = 0; i < episodes.size(); i++) if (episodes.get(i).key.equals(key)) idx = i;
        final int at = idx;
        ((androidx.recyclerview.widget.LinearLayoutManager) epList.getLayoutManager())
                .scrollToPositionWithOffset(at, (int) (W * 0.045f));
        epList.postDelayed(() -> {
            androidx.recyclerview.widget.RecyclerView.ViewHolder vh = epList.findViewHolderForAdapterPosition(at);
            if (vh != null) vh.itemView.requestFocus();
        }, 150);
    }

    private void closeEpisodes() {
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
        if (!panelOpen && !epOpen && osd != null) {
            boolean ok = kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER;
            if (ok && skipBtn != null && skipBtn.getVisibility() == View.VISIBLE) {
                // A skip intro / credits button on screen: OK presses it.
                if (event.getAction() == KeyEvent.ACTION_UP) skipBtn.performClick();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_DPAD_UP) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) openPanel();
                return true;
            }
            if (osd.handleKey(event)) return true;
            if (kc == KeyEvent.KEYCODE_DPAD_DOWN && episodes.isEmpty()) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) osd.show();
                return true;
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && !panelOpen) {
            if (!epOpen && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN && !episodes.isEmpty()) {
                openEpisodes();
                return true;
            }
            if (epOpen && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
                closeEpisodes();
                return true;
            }
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

    private void showVideoTab() {
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

    private void showAudioTab() {
        if (transcoding) {
            addHeading("Plex is converting the stream. Picking a track restarts it from here.");
        }
        View selected = null;
        List<Plex.Stream> audios = item.streamsOf(2);
        if (audios.isEmpty()) addHeading("No audio tracks reported");
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
            View row = makeRow(s.label(), sub, isCur, false, v -> {
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

        for (final Plex.Stream s : item.streamsOf(3)) {
            boolean isCur = subsActive && curSub != null && curSub.id == s.id;
            String sub = (s.isExternal() ? "File" : "Built in") + (s.forced ? "  ·  forced" : "")
                    + (s.codec.isEmpty() ? "" : "  ·  " + s.codec.toUpperCase(Locale.ROOT))
                    + (transcoding && isPicture(s) ? "  ·  burns in, re-encodes the video" : "");
            View row = makeRow(s.label(), sub, isCur, false, v -> {
                selectSub(s);
                showTab(TAB_SUBS);
            });
            list.addView(row);
            if (isCur) selected = row;
        }

        addHeading("");
        list.addView(makeRow("Find subtitles online…", "Searches through your Plex server", false, false,
                v -> showLanguagePicker()));

        // Timing, for subtitles that are out of sync
        addHeading("");
        timingLabel = new TextView(this);
        timingLabel.setText(delayText());
        timingLabel.setTextColor(0xFFFFFFFF);
        timingLabel.setTypeface(UiUtil.font(UiUtil.SEMIBOLD));
        timingLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.026f);
        timingLabel.setPadding((int) (W * 0.014f), (int) (H * 0.01f), 0, (int) (H * 0.006f));
        list.addView(timingLabel);
        list.addView(makeRow("Earlier by 0.25 s", "Subtitles show up too late", false, false, v -> nudgeDelay(-250)));
        list.addView(makeRow("Later by 0.25 s", "Subtitles show up too early", false, false, v -> nudgeDelay(250)));
        list.addView(makeRow("Earlier by 1 s", null, false, false, v -> nudgeDelay(-1000)));
        list.addView(makeRow("Later by 1 s", null, false, false, v -> nudgeDelay(1000)));
        list.addView(makeRow("Reset timing", null, false, false, v -> nudgeDelay(0)));
        focusFirstOrSelected(selected);
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
        list.removeAllViews();
        addHeading("Downloading…");
        Plex.async(() -> {
            Plex.downloadSubtitle(key, r);
            Thread.sleep(2500); // give the server a moment to attach the file
            return Plex.metadata(key);
        }, new Plex.Callback<Plex.Item>() {
            @Override
            public void ok(Plex.Item fresh) {
                if (isDestroyed()) return;
                item.streams = fresh.streams;
                Toast.makeText(PlayerActivity.this, "Subtitles added. Pick them from the list.",
                        Toast.LENGTH_LONG).show();
                if (panelOpen) showTab(TAB_SUBS);
            }

            @Override
            public void fail(Exception e) {
                if (isDestroyed() || !panelOpen) return;
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

    private String debugText() {
        StringBuilder sb = new StringBuilder();
        if (player == null) return "Not playing";
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
        sb.append("Buffered  ").append(player.getTotalBufferedDuration() / 1000).append(" s\n");

        sb.append("\nSound output can take (from your TV/receiver):\n");
        try {
            AudioCapabilities caps = AudioCapabilities.getCapabilities(this);
            sb.append("  Dolby Digital       ").append(yesNo(caps.supportsEncoding(C.ENCODING_AC3))).append('\n');
            sb.append("  Dolby Digital Plus  ").append(yesNo(caps.supportsEncoding(C.ENCODING_E_AC3))).append('\n');
            sb.append("  Atmos (DD+ JOC)     ").append(yesNo(caps.supportsEncoding(C.ENCODING_E_AC3_JOC))).append('\n');
            sb.append("  Dolby TrueHD        ").append(yesNo(caps.supportsEncoding(C.ENCODING_DOLBY_TRUEHD))).append('\n');
            sb.append("  DTS                 ").append(yesNo(caps.supportsEncoding(C.ENCODING_DTS))).append('\n');
            sb.append("  DTS-HD              ").append(yesNo(caps.supportsEncoding(C.ENCODING_DTS_HD))).append('\n');
            sb.append("\nHDMI outputs (what the TV or receiver offers):\n").append(IecDtsHdSink.hdmiInfo(this));
            sb.append("  Max channels        ").append(caps.getMaxChannelCount()).append('\n');
        } catch (RuntimeException e) {
            sb.append("  (couldn't read)\n");
        }

        sb.append("\nAudio tracks in this file:\n");
        List<Tracks.Group> groups = audioGroups();
        if (groups.isEmpty()) sb.append("  (none reported)\n");
        for (int i = 0; i < groups.size(); i++) {
            Tracks.Group g = groups.get(i);
            Format f = g.getTrackFormat(0);
            sb.append("  ").append(g.isSelected() ? "> " : "  ").append(i + 1).append(". ")
                    .append(f.sampleMimeType).append(' ').append(f.channelCount).append("ch ")
                    .append(f.language == null ? "" : f.language)
                    .append(g.isTrackSupported(0) ? "  ok" : "  NOT SUPPORTED").append('\n');
        }
        return sb.toString();
    }

    // -------------------------------------------------------- Lifecycle

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) player.pause();
        finishReporting();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (osd != null) osd.release();
        // Update the Fire TV home row once Plex has recorded where you stopped.
        final android.content.Context app = getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> TvRecommendations.refresh(app, null), 4000);
        finishReporting();
        releasePlayer();
    }

    private void finishReporting() {
        if (reportedStop || item == null || player == null) return;
        long pos = currentMs();
        Plex.timeline(key, "stopped", pos, item.duration);
        if (item.duration > 0 && pos > item.duration * 0.92) Plex.setWatched(key, true);
        reportedStop = true;
    }
}
