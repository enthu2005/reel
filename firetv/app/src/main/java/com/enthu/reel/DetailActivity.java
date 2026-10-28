package com.enthu.reel;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Details for a movie, show or episode, with a Play button and (for shows) the episode list. */
public class DetailActivity extends ReelActivity implements PosterAdapter.Listener {

    private static final class Loaded {
        Plex.Item item;
        List<Plex.Item> episodes = new ArrayList<>();
        List<Plex.Item> related = new ArrayList<>();
        List<Plex.Item> collection = new ArrayList<>();
        List<Plex.Item> sameLibrary = new ArrayList<>();
        List<Seerr.Media> requests = new ArrayList<>();
        String showLogo;
    }

    private String showLogo;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String key;
    private int W;
    private int H;

    private Plex.Item item;
    private final List<Plex.Item> episodes = new ArrayList<>();

    private ImageView bg;
    private ImageView logo;
    private TextView title;
    private TextView meta;
    private LinearLayout ratingRow;
    private LinearLayout badgeRow;
    private TextView summary;
    private TextView playBtn;
    private TextView startOverBtn;
    private TextView trailerBtn;
    private TextView previewBtn;
    private FrameLayout rootView;
    private boolean previewFull;
    private String trailerKey;
    private TextView epLabel;
    private RecyclerView epRow;
    private boolean firstLoad = true;
    private int selSeason = -1; // -1 not chosen yet, 0 all episodes
    private final List<Plex.Item> shown = new ArrayList<>();
    private LinearLayout castBox;
    private RecyclerView castRow;
    private LinearLayout moreBox;
    private RecyclerView moreRow;
    private TextView moreLabel;
    private LinearLayout.LayoutParams castLp;
    private LinearLayout.LayoutParams moreLp;
    private final List<Plex.Item> related = new ArrayList<>();
    private final List<Plex.Item> collection = new ArrayList<>();
    private final List<Plex.Item> sameLibrary = new ArrayList<>();
    private final List<Plex.Item> requestItems = new ArrayList<>();
    private LinearLayout requestBox;
    private RecyclerView requestRow;
    private LinearLayout.LayoutParams requestLp;
    private android.widget.ScrollView lowerScroll;
    private TextView shuffleBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Plex.init(this);
        key = getIntent().getStringExtra("key");
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!previewPlaying()) {
            behindStarted = false;
            handler.postDelayed(this::startBehindWhenReady, 1200);
        }
        load(); // refresh resume position after playing
    }

    // -------------------------------------------------------------- UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(UiUtil.BG);

        bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(bg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The home screen's preview, if one was playing for this title, carries on here.
        previewView = new androidx.media3.ui.PlayerView(this);
        previewView.setUseController(false);
        previewView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        previewView.setShutterBackgroundColor(0x00000000);
        previewView.setAlpha(0f);
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ytBox = new FrameLayout(this);
        ytBox.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        ytBox.setAlpha(0f);
        root.addView(ytBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // A light all-over tint while a preview plays, so text stays readable on bright scenes
        tint = new View(this);
        tint.setBackgroundColor(0x59000000);
        tint.setAlpha(0f);
        root.addView(tint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        scrimLeft = new View(this);
        scrimLeft.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xEE000000, 0x99000000, 0x00000000}));
        root.addView(scrimLeft, new FrameLayout.LayoutParams((int) (W * 0.70f),
                ViewGroup.LayoutParams.MATCH_PARENT));

        scrimBottom = new View(this);
        scrimBottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xB3000000, 0xFF000000}));
        root.addView(scrimBottom, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (H * 0.5f), Gravity.BOTTOM));

        LinearLayout col = new LinearLayout(this);
        infoCol = col;
        col.setOrientation(LinearLayout.VERTICAL);

        FrameLayout logoBox = new FrameLayout(this);
        logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        logo.setVisibility(View.GONE);
        logoBox.addView(logo, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM | Gravity.START));
        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.065f);
        title.setTypeface(UiUtil.font(UiUtil.BOLD));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setShadowLayer(12, 0, 2, 0x99000000);
        // Long titles shrink to fit the box instead of being cut off at the bottom
        title.setGravity(Gravity.BOTTOM | Gravity.START);
        title.setAutoSizeTextTypeUniformWithConfiguration((int) (H * 0.032f), (int) (H * 0.065f), 1,
                TypedValue.COMPLEX_UNIT_PX);
        logoBox.addView(title, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM | Gravity.START));
        col.addView(logoBox, new LinearLayout.LayoutParams(
                (int) (W * 0.42f), (int) (H * 0.095f)));

        meta = new TextView(this);
        meta.setTextColor(UiUtil.TEXT2);
        meta.setTypeface(UiUtil.font(UiUtil.MEDIUM));
        meta.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.024f);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                (int) (W * 0.5f), ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = (int) (H * 0.012f);
        col.addView(meta, metaLp);

        ratingRow = new LinearLayout(this);
        ratingRow.setOrientation(LinearLayout.HORIZONTAL);
        ratingRow.setGravity(Gravity.CENTER_VERTICAL);
        ratingRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams ratingLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ratingLp.topMargin = (int) (H * 0.012f);
        col.addView(ratingRow, ratingLp);

        badgeRow = new LinearLayout(this);
        badgeRow.setOrientation(LinearLayout.HORIZONTAL);
        badgeRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.topMargin = (int) (H * 0.016f);

        summary = new TextView(this);
        summary.setTextColor(0xD9FFFFFF);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.025f);
        summary.setMaxLines(3);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        summary.setLineSpacing(0, 1.1f);
        LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(
                (int) (W * 0.5f), ViewGroup.LayoutParams.WRAP_CONTENT);
        sumLp.topMargin = (int) (H * 0.012f);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setClipChildren(false);
        playBtn = makeButton("Play", true);
        startOverBtn = makeButton("Start over", false);
        playBtn.setOnClickListener(v -> play(false));
        startOverBtn.setOnClickListener(v -> play(true));
        // Play, then the preview and trailer, then the rest. The row sizes to its buttons so
        // none of them get squeezed or cut off.
        buttons.addView(playBtn);
        // A small "expand" icon (four corner brackets) instead of a word; it takes the button's text colour
        previewBtn = makeButton("", false);
        previewBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(
                new ExpandIcon(previewBtn, (int) (H * 0.034f)), null, null, null);
        previewBtn.setContentDescription("Full preview");
        previewBtn.setVisibility(View.GONE);
        previewBtn.setOnClickListener(v -> expandPreview());
        buttons.addView(previewBtn, buttonGap());
        trailerBtn = makeButton("Trailer", false); // kept out of the page: the trailer just plays behind it
        trailerBtn.setVisibility(View.GONE);
        shuffleBtn = makeButton("Shuffle", false);
        shuffleBtn.setVisibility(View.GONE);
        shuffleBtn.setOnClickListener(v -> shuffleQueue());
        buttons.addView(shuffleBtn, buttonGap());
        buttons.addView(startOverBtn, buttonGap());
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRowLp.topMargin = (int) (H * 0.025f);
        btnRowLp.topMargin = (int) (H * 0.028f);
        col.addView(buttons, btnRowLp);
        sumLp.topMargin = (int) (H * 0.026f);
        col.addView(summary, sumLp);
        col.addView(badgeRow, badgeLp);

        col.setClipChildren(false);
        col.setClipToPadding(false);
        FrameLayout.LayoutParams colLp = new FrameLayout.LayoutParams(
                (int) (W * 0.92f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        colLp.leftMargin = (int) (W * 0.05f);
        colLp.topMargin = (int) (H * 0.05f);
        root.addView(col, colLp);

        // Episodes, along the bottom
        LinearLayout epBox = new LinearLayout(this);
        epBox.setOrientation(LinearLayout.VERTICAL);
        epBox.setClipChildren(false);
        epLabel = new TextView(this);
        epLabel.setText("Episodes");
        epLabel.setTextColor(UiUtil.TEXT);
        epLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.028f);
        epLabel.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        epLabel.setPadding((int) (W * 0.045f) + 8, (int) (H * 0.008f), (int) (W * 0.02f), (int) (H * 0.008f));
        epLabel.setOnFocusChangeListener((v, f) -> {
            styleSeasonButton(f);
            if (f) SoundFx.move();
        });
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = (int) (W * 0.045f) - 8;
        epLabel.setPadding((int) (W * 0.016f), (int) (H * 0.008f), (int) (W * 0.016f), (int) (H * 0.008f));
        labelLp.bottomMargin = (int) (H * 0.008f);
        epBox.addView(epLabel, labelLp);

        epRow = new RecyclerView(this);
        epRow.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        epRow.setClipToPadding(false);
        epRow.setClipChildren(false);
        epRow.setItemAnimator(null);
        epRow.setPadding((int) (W * 0.045f), 0, (int) (W * 0.045f), 0);
        epBox.addView(epRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        epBox.setVisibility(View.GONE);
        epBox.setTag("epbox");

        FrameLayout.LayoutParams epLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        epLp.bottomMargin = (int) (H * 0.05f);
        root.addView(epBox, epLp);

        // Cast and Similar stack in one box along the bottom, so they can never sit on top of each other
        final LinearLayout lowerBox = new LinearLayout(this);
        lowerBox.setOrientation(LinearLayout.VERTICAL);
        lowerBox.setClipChildren(false);
        lowerBox.setClipToPadding(false);

        // Cast, along the bottom of movie pages
        castBox = new LinearLayout(this);
        castBox.setOrientation(LinearLayout.VERTICAL);
        castBox.setClipChildren(false);
        castBox.setVisibility(View.GONE);
        TextView castLabel = new TextView(this);
        castLabel.setText("Cast");
        castLabel.setTextColor(UiUtil.TEXT);
        castLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.028f);
        castLabel.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        castLabel.setPadding((int) (W * 0.045f) + (int) (H * 0.011f), 0, 0, (int) (H * 0.006f));
        castBox.addView(castLabel);
        castRow = new RecyclerView(this);
        castRow.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        castRow.setClipToPadding(false);
        castRow.setClipChildren(false);
        castRow.setItemAnimator(null);
        castRow.setPadding((int) (W * 0.045f), 0, (int) (W * 0.045f), 0);
        castBox.addView(castRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        castLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lowerBox.addView(castBox, castLp);

        moreBox = new LinearLayout(this);
        moreBox.setOrientation(LinearLayout.VERTICAL);
        moreBox.setClipChildren(false);
        moreBox.setVisibility(View.GONE);
        moreLabel = new TextView(this);
        moreLabel.setText("Similar");
        moreLabel.setTextColor(UiUtil.TEXT);
        moreLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.028f);
        moreLabel.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        moreLabel.setPadding((int) (W * 0.045f) + (int) (H * 0.011f), 0, 0, (int) (H * 0.006f));
        moreBox.addView(moreLabel);
        moreRow = new RecyclerView(this);
        moreRow.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        moreRow.setClipToPadding(false);
        moreRow.setClipChildren(false);
        moreRow.setItemAnimator(null);
        moreRow.setPadding((int) (W * 0.045f), 0, (int) (W * 0.045f), 0);
        moreBox.addView(moreRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        moreLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lowerBox.addView(moreBox, moreLp);

        // Similar titles you could request through Seerr
        requestBox = new LinearLayout(this);
        requestBox.setOrientation(LinearLayout.VERTICAL);
        requestBox.setClipChildren(false);
        requestBox.setVisibility(View.GONE);
        TextView requestLabel = new TextView(this);
        requestLabel.setText("Similar to request");
        requestLabel.setTextColor(UiUtil.TEXT);
        requestLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.028f);
        requestLabel.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        requestLabel.setPadding((int) (W * 0.045f) + (int) (H * 0.011f), 0, 0, (int) (H * 0.006f));
        requestBox.addView(requestLabel);
        requestRow = new RecyclerView(this);
        requestRow.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        requestRow.setClipToPadding(false);
        requestRow.setClipChildren(false);
        requestRow.setItemAnimator(null);
        requestRow.setPadding((int) (W * 0.045f), 0, (int) (W * 0.045f), 0);
        requestBox.addView(requestRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        requestLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lowerBox.addView(requestBox, requestLp);
        FrameLayout.LayoutParams lowerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lowerLp.bottomMargin = (int) (H * 0.004f);
        // Cast and Similar fill this window as before; "Similar to request" waits just below it and
        // scrolls into view when you press down, so the three rows never sit on top of each other.
        lowerScroll = new android.widget.ScrollView(this);
        lowerScroll.setVerticalScrollBarEnabled(false);
        lowerScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        lowerScroll.setClipChildren(false);
        lowerScroll.setClipToPadding(false);
        int lift = (int) (H * 0.02f); // room for a focused card to grow
        lowerScroll.setPadding(0, lift, 0, lift);
        lowerScroll.addView(lowerBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // The padding above would lift the rows, so pull the window down by the same amount (and a touch more)
        lowerLp.bottomMargin = (int) (H * 0.004f) - lift - (int) (H * 0.015f);
        root.addView(lowerScroll, lowerLp);

        setContentView(root);
        rootView = root;
        takePreview();
    }

    private androidx.media3.ui.PlayerView previewView;
    private FrameLayout ytBox;
    private View scrimLeft;
    private View tint;
    private View scrimBottom;
    private LinearLayout infoCol;

    /**
     * A preview is (or stops) playing behind the page: lighten the dark shading and let the text and
     * buttons go a little see-through so more of the video shows. The focused button stays solid.
     */
    private void previewOn(boolean on) {
        previewBtn.setVisibility(on ? View.VISIBLE : View.GONE);
        if (scrimLeft == null) return;
        // The side and bottom shading stay mostly there (text must stay readable) and fade to clear
        // where there is nothing on the page; the whole picture also gets a light tint.
        scrimLeft.animate().alpha(on ? 0.85f : 1f).setDuration(on ? FADE_MS : Motion.LONG_MS).setInterpolator(FADE_EASE).start();
        scrimBottom.animate().alpha(on ? 0.85f : 1f).setDuration(on ? FADE_MS : Motion.LONG_MS).setInterpolator(FADE_EASE).start();
        if (tint != null && !previewFull) tint.animate().alpha(on ? 1f : 0f).setDuration(Motion.LONG_MS).setInterpolator(Motion.EASE).start();
    }
    private android.webkit.WebView previewWeb; // a YouTube trailer playing behind the page
    private static final long BEHIND_DELAY_MS = 4000;
    // Poster -> video: a long, even dissolve (no quick start)
    private static final long FADE_MS = 2200;
    private static final android.view.animation.Interpolator FADE_EASE =
            new android.view.animation.AccelerateDecelerateInterpolator();
    private boolean behindStarted;
    private long behindHoldMs = 0; // how long a preloaded background preview waits before playing

    private boolean previewPlaying() {
        return preview != null || previewWeb != null;
    }

    /**
     * No preview came with us (or it hadn't found anything yet): find the best one for this title,
     * the same way the cards do (trailer file, Plex's online trailer, YouTube, or a scene), and
     * start it behind the page two seconds after the page opened.
     */
    private void startBehindWhenReady() {
        if (isDestroyed() || behindStarted || previewPlaying() || item == null || !autoTrailer()) return;
        behindStarted = true;
        final Plex.Item it = item;
        final String[] known = HomeActivity.cachedPreviewSource(key);
        Plex.async(() -> known != null ? known : PreviewCard.plexSource(it), new Plex.Callback<String[]>() {
            @Override
            public void ok(final String[] src) {
                if (isDestroyed() || src == null || src[0].equals("none")) return;
                long wait = Math.max(0, BEHIND_DELAY_MS - (android.os.SystemClock.uptimeMillis() - openedAt));
                if (src[0].equals("yt")) {
                    handler.postDelayed(() -> playBehindSource(src), wait);
                } else {
                    // A file or a scene from the film: start loading it now (paused, silent, hidden) so it is
                    // ready the moment the gap is over, instead of only starting to load then.
                    behindHoldMs = wait;
                    playBehindSource(src);
                }
            }

            @Override
            public void fail(Exception e) {
            }
        });
    }

    private void playBehindSource(String[] src) {
        if (isFinishing() || isDestroyed() || previewPlaying() || (behindHoldMs == 0 && !hasWindowFocus())) return;
        switch (src[0]) {
            case "direct":
                playBehind(src[1]);
                break;
            case "hls": {
                java.util.Map<String, String> hdr = new java.util.HashMap<>();
                hdr.put("X-Plex-Client-Profile-Extra", Plex.previewProfile());
                hdr.put("X-Plex-Client-Identifier", Plex.clientId());
                hdr.put("X-Plex-Product", "Reel");
                hdr.put("X-Plex-Client-Profile-Name", "Konvergo");
                long off = 0;
                try {
                    off = Long.parseLong(src[2]);
                } catch (Exception ignored) {
                }
                String session = java.util.UUID.randomUUID().toString();
                androidx.media3.exoplayer.ExoPlayer p = PreviewHandoff.newPlayer(this, hdr);
                boolean clip = !(src.length > 3 && "trailer".equals(src[3]));
                startBehindPlayer(p, new androidx.media3.common.MediaItem.Builder()
                        .setUri(Plex.previewUrl(src[1], off, session))
                        .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8).build());
                previewSession = session;
                previewClip = clip;
                if (clip) handler.postDelayed(this::endPreview, 60_000);
                break;
            }
            case "yt": {
                final android.webkit.WebView[] ref = new android.webkit.WebView[1];
                ref[0] = YouTubeEmbed.create(this, src[1], HomeActivity.previewVolume(this),
                        new YouTubeEmbed.Events() {
                            @Override
                            public void playing() {
                                if (previewWeb != ref[0]) return;
                                ytBox.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(FADE_EASE).start();
                                previewOn(true);
                            }

                            @Override
                            public void ended() {
                                if (previewWeb == ref[0]) endPreview();
                            }
                        });
                previewWeb = ref[0];
                previewClip = false;
                ytBox.addView(previewWeb, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                break;
            }
            default:
                break;
        }
    }
    private androidx.media3.exoplayer.ExoPlayer preview;
    private String previewSession;

    private boolean previewClip; // the preview is a clip of the film, not the trailer
    private final long openedAt = android.os.SystemClock.uptimeMillis();

    private void takePreview() {
        boolean clip = PreviewHandoff.clip;
        final boolean rendered = PreviewHandoff.rendered;
        preview = PreviewHandoff.take(key);
        if (preview == null) return; // startBehindWhenReady() takes over once the title has loaded
        previewClip = clip;
        if (!rendered) {
            // It was still loading when you clicked: start it a second after the page appears.
            final androidx.media3.exoplayer.ExoPlayer p = preview;
            previewSession = PreviewHandoff.session;
            PreviewHandoff.session = null;
            previewView.setAlpha(0f);
            previewView.setPlayer(p);
            p.setVolume(0f);
            p.setPlayWhenReady(false); // hold until the gap is over
            p.addListener(new androidx.media3.common.Player.Listener() {
                private boolean shown;

                @Override
                public void onIsPlayingChanged(boolean playing) {
                    // The first frame can be ready while it's still waiting out the gap: keep the poster until it really plays
                    if (!playing || shown || preview != p) return;
                    shown = true;
                    previewView.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(FADE_EASE).start();
                    HomeActivity.fadeVolume(p, HomeActivity.previewVolume(DetailActivity.this), 1800);
                    previewOn(true);
                }

                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == androidx.media3.common.Player.STATE_ENDED) endPreview();
                }

                @Override
                public void onPlayerError(androidx.media3.common.PlaybackException error) {
                    endPreview();
                }
            });
            handler.postDelayed(() -> {
                if (preview == p) p.play();
            }, BEHIND_DELAY_MS);
            if (clip) handler.postDelayed(this::endPreview, 60_000);
            return;
        }
        previewSession = PreviewHandoff.session;
        PreviewHandoff.session = null;
        previewView.setPlayer(preview);
        previewView.setAlpha(1f); // carries straight on from the grown card
        previewOn(true);
        // A touch louder on the title page, still quiet.
        float v = HomeActivity.previewVolume(this);
        HomeActivity.fadeVolume(preview, v, 1200);
        preview.addListener(new androidx.media3.common.Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == androidx.media3.common.Player.STATE_ENDED) endPreview();
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                endPreview();
            }
        });
        // A clip of the film itself stops after a minute; a trailer plays to its end.
        if (clip) handler.postDelayed(this::endPreview, 60_000);
    }

    private void endPreview() {
        if (previewWeb != null) {
            final android.webkit.WebView wv = previewWeb;
            previewWeb = null;
            previewOn(false);
            if (previewFull) collapsePreview();
            YouTubeEmbed.setVolume(wv, 0f);
            ytBox.animate().alpha(0f).setDuration(Motion.SLOW_MS).setInterpolator(Motion.EASE).withEndAction(() -> YouTubeEmbed.destroy(wv)).start();
        }
        if (preview == null) return;
        previewOn(false);
        if (previewFull) collapsePreview();
        final androidx.media3.exoplayer.ExoPlayer p = preview;
        final String session = previewSession;
        preview = null;
        previewSession = null;
        HomeActivity.fadeVolume(p, 0f, 450);
        previewView.animate().alpha(0f).setDuration(Motion.SLOW_MS).setInterpolator(Motion.EASE).withEndAction(() -> {
            previewView.setPlayer(null);
            p.release();
            if (session != null) Plex.stopTranscode(session);
        }).start();
    }

    /** Hides the page so the preview fills the screen. Any button brings the page back. */
    private void expandPreview() {
        if (!previewPlaying() || rootView == null) return;
        SoundFx.click();
        previewFull = true;
        for (int i = 0; i < rootView.getChildCount(); i++) {
            View v = rootView.getChildAt(i);
            if (v == bg || v == previewView || v == ytBox) continue;
            v.animate().alpha(0f).setDuration(Motion.MEDIUM_MS).setInterpolator(Motion.EASE).start();
        }
        HomeActivity.fadeVolume(preview, HomeActivity.previewVolume(this), 600);
    }

    private void collapsePreview() {
        previewFull = false;
        if (rootView == null) return;
        for (int i = 0; i < rootView.getChildCount(); i++) {
            View v = rootView.getChildAt(i);
            if (v == bg || v == previewView || v == ytBox || v == tint) continue;
            v.animate().alpha(1f).setDuration(Motion.MEDIUM_MS).setInterpolator(Motion.EASE).start();
        }
        if (previewPlaying() && scrimLeft != null) {
            // still playing behind the page: keep the lighter shading
            scrimLeft.animate().alpha(0.85f).setDuration(Motion.MEDIUM_MS).setInterpolator(Motion.EASE).start();
            scrimBottom.animate().alpha(0.85f).setDuration(Motion.MEDIUM_MS).setInterpolator(Motion.EASE).start();
            if (tint != null) tint.animate().alpha(1f).setDuration(Motion.MEDIUM_MS).setInterpolator(Motion.EASE).start();
        }
        if (preview != null) {
            float vol = HomeActivity.previewVolume(this);
            HomeActivity.fadeVolume(preview, vol, 500);
        }
        (previewBtn.getVisibility() == View.VISIBLE ? previewBtn : playBtn).requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (previewFull) {
            // OK pauses / resumes the preview; Back (or a direction) brings the page back.
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                int k = e.getKeyCode();
                boolean ok = k == android.view.KeyEvent.KEYCODE_DPAD_CENTER || k == android.view.KeyEvent.KEYCODE_ENTER
                        || k == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
                if (ok && previewPlaying()) {
                    webPaused = previewWeb != null && !webPaused;
                    if (preview != null) preview.setPlayWhenReady(!preview.getPlayWhenReady());
                    YouTubeEmbed.pause(previewWeb, webPaused);
                    boolean playing = preview != null ? preview.getPlayWhenReady() : !webPaused;
                    android.widget.Toast.makeText(this, playing ? "Playing" : "Paused \u00B7 Back to return",
                            android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    if (preview != null) preview.setPlayWhenReady(true);
                    if (webPaused) {
                        webPaused = false;
                        YouTubeEmbed.pause(previewWeb, false);
                    }
                    collapsePreview();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean webPaused;

    @Override
    protected void onPause() {
        super.onPause();
        releasePreviewNow();
    }

    @Override
    protected void onStop() {
        super.onStop();
        releasePreviewNow();
    }

    private void releasePreviewNow() {
        handler.removeCallbacks(startTrailerBehind);
        if (previewWeb != null) {
            YouTubeEmbed.destroy(previewWeb);
            previewWeb = null;
            ytBox.setAlpha(0f);
            previewOn(false);
        }
        // Leaving the page (or pressing Play): stop the preview straight away.
        if (preview != null) {
            previewView.setPlayer(null);
            preview.release();
            preview = null;
            if (previewSession != null) Plex.stopTranscode(previewSession);
            previewSession = null;
            previewView.setAlpha(0f);
        }
    }

    private final class CastAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<Plex.Cast> cast;

        CastAdapter(List<Plex.Cast> cast) {
            this.cast = cast;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            SoundFx.init(DetailActivity.this);
            LinearLayout card = new LinearLayout(DetailActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setFocusable(true);
            card.setClickable(true);
            int w = (int) (H * 0.12f);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            int m = (int) (H * 0.008f);
            lp.setMargins(m, m, m, m);
            card.setLayoutParams(lp);

            int d = (int) (H * 0.08f);
            ImageView face = new ImageView(DetailActivity.this);
            face.setScaleType(ImageView.ScaleType.CENTER_CROP);
            face.setBackground(UiUtil.rounded(UiUtil.CARD, d / 2f));
            UiUtil.clipRounded(face, d / 2f);
            card.addView(face, new LinearLayout.LayoutParams(d, d));

            TextView name = new TextView(DetailActivity.this);
            name.setTextColor(Color.WHITE);
            name.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.02f);
            name.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            name.setPadding(0, (int) (H * 0.008f), 0, 0);
            card.addView(name, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView role = new TextView(DetailActivity.this);
            role.setTextColor(UiUtil.TEXT2);
            role.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.017f);
            role.setSingleLine(true);
            role.setEllipsize(TextUtils.TruncateAt.END);
            role.setGravity(Gravity.CENTER);
            card.addView(role, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(card) {
            };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder vh, int position) {
            final Plex.Cast c = cast.get(position);
            final LinearLayout card = (LinearLayout) vh.itemView;
            final ImageView face = (ImageView) card.getChildAt(0);
            ((TextView) card.getChildAt(1)).setText(c.name);
            ((TextView) card.getChildAt(2)).setText(c.role);
            if (c.thumb != null) {
                String url = c.thumb.startsWith("http") ? c.thumb
                        : Plex.img(c.thumb, 300, 300);
                Glide.with(face).load(url).centerCrop().into(face);
            } else {
                face.setImageDrawable(null);
            }
            final int d = (int) (H * 0.08f);
            card.setOnFocusChangeListener((v, f) -> {
                face.setForeground(f ? UiUtil.sheen(d / 2f) : null);
                face.setElevation(f ? H * 0.02f : 0f);
                float sc = f ? 1.12f : 1f;
                Motion.focusScale(face, sc);
                v.setAlpha(1f);
                ((TextView) card.getChildAt(1)).animate().translationY(f ? d * 0.06f : 0f).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.EASE).start();
                ((TextView) card.getChildAt(2)).animate().translationY(f ? d * 0.06f : 0f).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.EASE).start();
                if (f) SoundFx.move();
            });
            card.setOnClickListener(v -> {
                SoundFx.click();
                Intent i = new Intent(DetailActivity.this, PersonActivity.class);
                i.putExtra("filter", c.filter);
                i.putExtra("name", c.name);
                i.putExtra("thumb", c.thumb);
                startActivity(i);
            });
        }

        @Override
        public int getItemCount() {
            return cast.size();
        }
    }

    /** Four corner brackets, the usual "expand to full screen" symbol, drawn in the button's text colour. */
    private static final class ExpandIcon extends android.graphics.drawable.Drawable {
        private final TextView host;
        private final int size;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        ExpandIcon(TextView host, int size) {
            this.host = host;
            this.size = size;
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            paint.setStrokeWidth(size * 0.12f);
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            paint.setColor(host.getCurrentTextColor());
            android.graphics.Rect b = getBounds();
            float inset = paint.getStrokeWidth() / 2f;
            float l = b.left + inset;
            float t = b.top + inset;
            float r = b.right - inset;
            float bo = b.bottom - inset;
            float a = (r - l) * 0.38f;
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(l, t + a);
            path.lineTo(l, t);
            path.lineTo(l + a, t);
            path.moveTo(r - a, t);
            path.lineTo(r, t);
            path.lineTo(r, t + a);
            path.moveTo(r, bo - a);
            path.lineTo(r, bo);
            path.lineTo(r - a, bo);
            path.moveTo(l + a, bo);
            path.lineTo(l, bo);
            path.lineTo(l, bo - a);
            c.drawPath(path, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private LinearLayout.LayoutParams buttonGap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (int) (W * 0.01f);
        return lp;
    }

    private TextView makeButton(String label, final boolean primary) {
        final TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.027f);
        t.setTypeface(UiUtil.font(UiUtil.SEMIBOLD));
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(primary ? (int) (W * 0.11f) : 0);
        t.setPadding((int) (W * 0.026f), (int) (H * 0.018f), (int) (W * 0.026f), (int) (H * 0.018f));
        t.setFocusable(true);
        t.setClickable(true);
        final float r = H * 0.05f;
        style(t, primary, false, r);
        t.setOnFocusChangeListener((v, focused) -> {
            style(t, primary, focused, r);
            float s = focused ? 1.1f : 1f;
            Motion.focusScale(v, s);
            v.setElevation(focused ? H * 0.02f : 0f);
            if (focused) SoundFx.move();
        });
        return t;
    }

    private void style(TextView t, boolean primary, boolean focused, float r) {
        UiUtil.stylePill(t, focused, r);
        if (primary && !focused) t.setBackground(UiUtil.rounded(0x4DFFFFFF, r));
    }

    private boolean shouldShowShuffle() {
        if (item == null || !item.type.equals("movie") || sameLibrary.isEmpty()) return false;
        String lib = item.librarySectionTitle == null ? "" : item.librarySectionTitle.toLowerCase(Locale.ROOT);
        return lib.contains("tamil") && (lib.contains("music") || lib.contains("video"));
    }

    private void shuffleQueue() {
        if (item == null || sameLibrary.isEmpty()) return;
        ArrayList<Plex.Item> queue = new ArrayList<>(sameLibrary);
        queue.removeIf(it -> it.key.equals(item.key));
        if (queue.isEmpty()) return;
        java.util.Collections.shuffle(queue, new java.util.Random());
        queue.add(0, item); // this video plays first and stays in the queue with the rest
        PlayerActivity.startQueue(this, queue, 0);
    }

    // ----------------------------------------------------------- Loading

    private void load() {
        Plex.async(() -> {
            Loaded l = new Loaded();
            l.item = Plex.metadata(key);
            if (l.item.type.equals("episode") && l.item.gpKey != null) {
                // Show this episode, with every episode of the series along the bottom.
                l.episodes = Plex.items(Plex.paged("/library/metadata/" + l.item.gpKey + "/allLeaves", 800));
                try {
                    l.showLogo = Plex.metadata(l.item.gpKey).logo;
                } catch (Exception ignored) {
                    // the title shows as text instead
                }
            } else if (l.item.type.equals("show")) {
                l.episodes = Plex.items(Plex.paged("/library/metadata/" + key + "/allLeaves", 600));
            } else if (l.item.type.equals("season")) {
                l.episodes = Plex.items("/library/metadata/" + key + "/children");
            }
            if (l.item != null && l.item.type.equals("movie") && l.item.librarySectionID > 0) {
                try {
                    List<Plex.Item> lib = Plex.items(Plex.paged("/library/sections/" + l.item.librarySectionID + "/all?sort=random", 24));
                    lib.removeIf(it -> it.key.equals(l.item.key));
                    l.sameLibrary.addAll(lib);
                } catch (Exception ignored) {
                }
            }
            if (l.item != null && l.item.type.equals("movie") && !l.item.collFilter.isEmpty() && l.item.librarySectionID > 0) {
                try {
                    List<Plex.Item> col = Plex.items(Plex.paged("/library/sections/" + l.item.librarySectionID
                            + "/all?" + l.item.collFilter, 60));
                    col.removeIf(it -> it.key.equals(l.item.key));
                    // Release order: the first film first
                    java.util.Collections.sort(col, (a, b) -> a.year != b.year ? Integer.compare(a.year, b.year)
                            : a.title.compareToIgnoreCase(b.title));
                    l.collection.addAll(col);
                } catch (Exception ignored) {
                }
            }
            if (l.item != null && (l.item.type.equals("movie") || l.item.type.equals("episode"))) {
                try {
                    l.related.addAll(Plex.related(l.item.key));
                } catch (Exception ignored) {
                }
            }
            if (l.item != null && (l.item.type.equals("movie") || l.item.type.equals("show"))) {
                // Titles like this one that aren't in your libraries yet, to request
                try {
                    Seerr.init(DetailActivity.this);
                    String tmdb = Seerr.configured() ? Plex.tmdbId(l.item.key) : null;
                    if (tmdb != null) {
                        for (Seerr.Media m : Seerr.recommendations(l.item.type.equals("show") ? "tv" : "movie", tmdb)) {
                            if (m.status < 5 && m.requestStatus != 3 && l.requests.size() < 20) l.requests.add(m);
                        }
                    }
                } catch (Exception ignored) {
                    // Seerr unreachable: no request row
                }
            }
            return l;
        }, new Plex.Callback<Loaded>() {
            @Override
            public void ok(Loaded l) {
                if (isDestroyed()) return;
                item = l.item;
                showLogo = l.showLogo;
                related.clear();
                related.addAll(l.related);
                collection.clear();
                collection.addAll(l.collection);
                sameLibrary.clear();
                sameLibrary.addAll(l.sameLibrary);
                requestItems.clear();
                for (Seerr.Media m : l.requests) requestItems.add(m.toItem());
                findTrailer();
                startBehindWhenReady();
                episodes.clear();
                episodes.addAll(l.episodes);
                bind();
            }

            @Override
            public void fail(Exception e) {
                if (isDestroyed()) return;
                meta.setText("Couldn't load this: " + e.getMessage());
            }
        });
    }

    private boolean isContainer() {
        return item != null && (item.type.equals("show") || item.type.equals("season"));
    }

    private Plex.Item target() {
        if (!isContainer()) return item;
        for (Plex.Item e : episodes) if (e.viewOffset > 0) return e;
        for (Plex.Item e : episodes) if (e.viewCount == 0) return e;
        return episodes.isEmpty() ? null : episodes.get(0);
    }

    private String tintFor;

    /** Pulls the main colour out of the poster and lets it colour the dark gradients (the Apple TV look). */
    private void applyTint(final String posterPath) {
        if (posterPath == null || posterPath.equals(tintFor) || scrimLeft == null) return;
        tintFor = posterPath;
        Glide.with(this).asBitmap().load(Plex.img(posterPath, 48, 72))
                .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>(48, 72) {
                    @Override
                    public void onResourceReady(android.graphics.Bitmap bmp,
                                                com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> t) {
                        if (isDestroyed() || scrimLeft == null) return;
                        int c = dominantColour(bmp);
                        if (c == 0) return;
                        scrimLeft.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                                new int[]{shade(c, 0.17f, 0xEE), shade(c, 0.20f, 0x99), shade(c, 0.20f, 0x00)}));
                        scrimBottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                                new int[]{shade(c, 0.14f, 0x00), shade(c, 0.12f, 0xB3), shade(c, 0.07f, 0xFF)}));
                    }

                    @Override
                    public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
                    }
                });
    }

    /** The colour that stands out most in a small picture: colourful, not too dark, not washed out. */
    static int dominantColour(android.graphics.Bitmap b) {
        if (b == null || b.getWidth() == 0) return 0;
        float[] hsv = new float[3];
        double r = 0, g = 0, bl = 0, wsum = 0;
        for (int y = 0; y < b.getHeight(); y++) {
            for (int x = 0; x < b.getWidth(); x++) {
                int px = b.getPixel(x, y);
                Color.colorToHSV(px, hsv);
                double w = hsv[1] * hsv[2] + 0.02;
                if (hsv[2] < 0.15f) w *= 0.1;
                r += Color.red(px) * w;
                g += Color.green(px) * w;
                bl += Color.blue(px) * w;
                wsum += w;
            }
        }
        if (wsum <= 0) return 0;
        return Color.rgb((int) (r / wsum), (int) (g / wsum), (int) (bl / wsum));
    }

    /** The colour at a fixed darkness, so text on top stays readable. */
    static int shade(int rgb, float value, int alpha) {
        float[] hsv = new float[3];
        Color.colorToHSV(rgb, hsv);
        hsv[1] = Math.min(hsv[1], 0.75f);
        hsv[2] = value;
        return Color.HSVToColor(alpha, hsv);
    }

    /** Rotten Tomatoes / IMDb scores and the runtime on one line; false if there's nothing to show. */
    private boolean fillRatings() {
        ratingRow.removeAllViews();
        boolean runtime = item.duration > 0 && item.isPlayable();
        if (item.ratings.isEmpty()) {
            ratingRow.setVisibility(View.GONE);
            return false;
        }
        for (String[] r : item.ratings) {
            boolean rt = r[0].equals("RT");
            boolean imdb = r[0].equals("IMDb");
            int bg = rt ? 0xFFFA320A : imdb ? 0xFFF5C518 : 0xFF01B4E4;
            int fg = imdb ? 0xFF111111 : 0xFFFFFFFF;
            TextView tag = new TextView(this);
            tag.setText(r[0]);
            tag.setTextColor(fg);
            tag.setTypeface(UiUtil.font(UiUtil.BOLD));
            tag.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.016f);
            tag.setPadding((int) (H * 0.009f), (int) (H * 0.002f), (int) (H * 0.009f), (int) (H * 0.002f));
            tag.setBackground(UiUtil.rounded(bg, H * 0.008f));
            ratingRow.addView(tag);
            TextView val = new TextView(this);
            val.setText(r[1]);
            val.setTextColor(UiUtil.TEXT);
            val.setTypeface(UiUtil.font(UiUtil.MEDIUM));
            val.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.021f);
            LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vl.leftMargin = (int) (H * 0.010f);
            vl.rightMargin = (int) (H * 0.026f);
            ratingRow.addView(val, vl);
        }
        if (runtime) {
            TextView rt = new TextView(this);
            rt.setText(UiUtil.duration(item.duration));
            rt.setTextColor(UiUtil.TEXT2);
            rt.setTypeface(UiUtil.font(UiUtil.MEDIUM));
            rt.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.021f);
            ratingRow.addView(rt);
        }
        ratingRow.setVisibility(View.VISIBLE);
        return runtime;
    }

    private void bind() {
        title.setText(item.displayTitle());
        title.setVisibility(View.VISIBLE);
        logo.setVisibility(View.GONE);
        boolean strip = fillRatings();
        meta.setText(item.metaLine(!strip));
        summary.setText(item.summary);
        Badges.fill(badgeRow, item, (int) (H * 0.8f)); // smaller badges leave room for the rows below

        String art = Plex.img(item.heroArt() != null ? item.heroArt() : item.poster(), 1920, 1080);
        if (art != null) {
            Glide.with(this).load(art).centerCrop()
                    .transition(DrawableTransitionOptions.withCrossFade(300)).into(bg);
        }

        applyTint(item.poster());

        String logoPath = item.logo != null ? item.logo : showLogo;
        if (logoPath != null) {
            Glide.with(this).load(Plex.raw(logoPath)).into(logo);
            logo.setVisibility(View.VISIBLE);
            title.setVisibility(View.GONE);
        }

        Plex.Item t = target();
        if (t == null) {
            playBtn.setVisibility(View.GONE);
            startOverBtn.setVisibility(View.GONE);
        } else {
            playBtn.setVisibility(View.VISIBLE);
            String label = t.viewOffset > 0 ? "Continue" : "Play";
            if (isContainer() && t.parentIndex > 0) label += "  S" + t.parentIndex + " E" + t.index;
            playBtn.setText(label);
            startOverBtn.setVisibility(t.viewOffset > 0 ? View.VISIBLE : View.GONE);
        }

        View epBox = ((ViewGroup) epRow.getParent());
        boolean isEpisode = item.type.equals("episode");
        if ((isContainer() || isEpisode) && !episodes.isEmpty()) {
            epBox.setVisibility(View.VISIBLE);
            if (selSeason < 0) {
                // Start on the season you're in: this episode's, or the one you'd continue.
                Plex.Item t2 = isEpisode ? item : target();
                selSeason = t2 != null && t2.parentIndex > 0 ? t2.parentIndex : 0;
            }
            renderEpisodes(isEpisode);
        } else {
            epBox.setVisibility(View.GONE);
        }

        if (item.type.equals("movie") && !item.cast.isEmpty()) {
            // Coming back from a cast page re-runs this: keep the row (and the focus on it) as it was
            String castSig = item.key + ":" + item.cast.size();
            if (castRow.getAdapter() == null || !castSig.equals(castRow.getTag())) {
                castRow.setAdapter(new CastAdapter(item.cast));
                castRow.setTag(castSig);
            }
            castBox.setVisibility(View.VISIBLE);
            final List<Plex.Item> moreItems = !collection.isEmpty() ? collection : related;
            if (!moreItems.isEmpty()) {
                moreLabel.setText(!collection.isEmpty() ? item.collName + "  \u203A" : "Similar in your libraries");
                moreLabel.setFocusable(!collection.isEmpty());
                moreLabel.setClickable(!collection.isEmpty());
                if (!collection.isEmpty()) {
                    // The collection's own page: every film in it, in release order
                    moreLabel.setOnClickListener(v -> {
                        SoundFx.click();
                        Intent i = new Intent(DetailActivity.this, BrowseActivity.class);
                        i.putExtra("path", "/library/sections/" + item.librarySectionID + "/all?" + item.collFilter
                                + "&sort=originallyAvailableAt:asc");
                        i.putExtra("title", item.collName);
                        i.putExtra("type", "movie");
                        startActivity(i);
                    });
                    moreLabel.setOnFocusChangeListener((v, f) -> {
                        moreLabel.setBackground(f ? UiUtil.rounded(0xFFFFFFFF, H * 0.04f) : null);
                        moreLabel.setTextColor(f ? 0xFF111111 : UiUtil.TEXT);
                        if (f) SoundFx.move();
                    });
                } else {
                    moreLabel.setOnClickListener(null);
                    moreLabel.setOnFocusChangeListener(null);
                    moreLabel.setBackground(null);
                    moreLabel.setTextColor(UiUtil.TEXT);
                }
                String sigrelated = item.key + ":" + moreItems.size() + (collection.isEmpty() ? "r" : "c");
                if (moreRow.getAdapter() == null || !sigrelated.equals(moreRow.getTag()))
                { moreRow.setTag(sigrelated);
                moreRow.setAdapter(new PosterAdapter(moreItems, (int) (H * 0.13f * 16f / 9f), (int) (H * 0.13f), true,
                        new PosterAdapter.Listener() {
                            @Override public void onFocused(Plex.Item it) {}
                            @Override public void onClicked(Plex.Item it) {
                                Intent i = new Intent(DetailActivity.this, DetailActivity.class);
                                i.putExtra("key", it.key);
                                startActivity(i);
                            }
                        }));
                }
                moreBox.setVisibility(View.VISIBLE);
            } else {
                moreBox.setVisibility(View.GONE);
            }
        } else {
            castBox.setVisibility(View.GONE);
            if (!sameLibrary.isEmpty()) {
                moreLabel.setText("More in this library");
                String sigsameLibrary = item.key + ":" + sameLibrary.size();
                if (moreRow.getAdapter() == null || !sigsameLibrary.equals(moreRow.getTag()))
                { moreRow.setTag(sigsameLibrary);
                moreRow.setAdapter(new PosterAdapter(sameLibrary, (int) (H * 0.13f * 16f / 9f), (int) (H * 0.13f), true,
                        new PosterAdapter.Listener() {
                            @Override public void onFocused(Plex.Item it) {}
                            @Override public void onClicked(Plex.Item it) {
                                Intent i = new Intent(DetailActivity.this, DetailActivity.class);
                                i.putExtra("key", it.key);
                                startActivity(i);
                            }
                        }));
                }
                moreBox.setVisibility(View.VISIBLE);
            } else {
                moreBox.setVisibility(View.GONE);
            }
        }

        if (!requestItems.isEmpty()) {
            String sigRequest = item.key + ":" + requestItems.size();
            if (requestRow.getAdapter() == null || !sigRequest.equals(requestRow.getTag())) {
                requestRow.setTag(sigRequest);
                requestRow.setAdapter(new PosterAdapter(requestItems, (int) (H * 0.13f * 16f / 9f), (int) (H * 0.13f), true,
                        new PosterAdapter.Listener() {
                            @Override public void onFocused(Plex.Item it) {}
                            @Override public void onClicked(Plex.Item it) {
                                // Opens in Seerr, where it can be requested
                                String[] p = it.key.split(":");
                                Intent i = new Intent(DetailActivity.this, SeerrActivity.class);
                                i.putExtra("openType", p[1]);
                                i.putExtra("openId", Integer.parseInt(p[2]));
                                startActivity(i);
                            }
                        }));
            }
            requestBox.setVisibility(View.VISIBLE);
        } else {
            requestBox.setVisibility(View.GONE);
        }

        boolean castVisible = castBox.getVisibility() == View.VISIBLE;
        boolean moreVisible = moreBox.getVisibility() == View.VISIBLE;
        boolean requestVisible = requestBox.getVisibility() == View.VISIBLE;
        moreLp.topMargin = castVisible && moreVisible ? (int) (H * 0.012f) : 0;
        requestLp.topMargin = (castVisible || moreVisible) && requestVisible ? (int) (H * 0.012f) : 0;
        castBox.setLayoutParams(castLp);
        moreBox.setLayoutParams(moreLp);
        requestBox.setLayoutParams(requestLp);
        // Less room is needed up top when there are rows underneath
        summary.setMaxLines(castVisible ? 2 : 3);

        // The window shows only Cast and Similar; the request row is reached by scrolling down
        sizeLowerWindow(8);

        shuffleBtn.setVisibility(shouldShowShuffle() ? View.VISIBLE : View.GONE);

        if (firstLoad) {
            firstLoad = false;
            playBtn.requestFocus();
        }
    }

    /** Sizes the bottom window to Cast + Similar once those rows have been laid out. */
    private void sizeLowerWindow(final int tries) {
        lowerScroll.postDelayed(() -> {
            if (isDestroyed()) return;
            boolean cast = castBox.getVisibility() == View.VISIBLE;
            boolean more = moreBox.getVisibility() == View.VISIBLE;
            boolean request = requestBox.getVisibility() == View.VISIBLE;
            if ((cast && castBox.getHeight() == 0) || (more && moreBox.getHeight() == 0)
                    || (request && requestBox.getHeight() == 0) || infoCol.getHeight() == 0) {
                if (tries > 0) sizeLowerWindow(tries - 1); // not laid out yet
                return;
            }
            int pad = lowerScroll.getPaddingTop() + lowerScroll.getPaddingBottom();
            int shown = pad + (cast ? castBox.getHeight() : 0)
                    + (more ? moreBox.getHeight() + moreLp.topMargin : 0);
            int all = shown + (request ? requestBox.getHeight() + requestLp.topMargin : 0);
            // Cast and Similar are what rests on screen; the request row waits below
            int want = request && (cast || more) ? shown : all;
            // Never taller than the space under the title, buttons, summary and badges
            int room = H - ((FrameLayout.LayoutParams) lowerScroll.getLayoutParams()).bottomMargin - infoCol.getBottom();
            want = Math.max((int) (H * 0.2f), Math.min(want, room));
            if (want >= all) want = ViewGroup.LayoutParams.WRAP_CONTENT;
            ViewGroup.LayoutParams lp = lowerScroll.getLayoutParams();
            if (lp.height != want) {
                lp.height = want;
                lowerScroll.setLayoutParams(lp);
            }
        }, 60);
    }

    private java.util.List<Integer> seasonNumbers() {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        for (Plex.Item e : episodes) if (e.parentIndex > 0) set.add(e.parentIndex);
        return new ArrayList<>(set);
    }

    /** Fills the episode row for the chosen season, with a Netflix-style season button above it. */
    private void renderEpisodes(boolean scrollToCurrent) {
        final java.util.List<Integer> seasons = seasonNumbers();
        if (selSeason > 0 && !seasons.contains(selSeason)) selSeason = 0;
        shown.clear();
        for (Plex.Item e : episodes) if (selSeason == 0 || e.parentIndex == selSeason) shown.add(e);

        if (seasons.size() > 1) {
            epLabel.setText((selSeason == 0 ? "All episodes" : "Season " + selSeason) + "  \u25BE");
            epLabel.setFocusable(true);
            epLabel.setClickable(true);
            epLabel.setOnClickListener(v -> showSeasonPicker(seasons));
            styleSeasonButton(epLabel.hasFocus());
        } else {
            epLabel.setText("Episodes");
            epLabel.setFocusable(false);
            epLabel.setClickable(false);
            epLabel.setBackground(null);
        }

        int ph = (int) (H * 0.20f);
        int pw = (int) (ph * 16f / 9f);
        epRow.setAdapter(new PosterAdapter(shown, pw, ph, true, this));
        int at = 0;
        if (scrollToCurrent) {
            for (int i = 0; i < shown.size(); i++) if (shown.get(i).key.equals(item.key)) at = i;
        }
        ((LinearLayoutManager) epRow.getLayoutManager()).scrollToPositionWithOffset(at, (int) (W * 0.045f));
    }

    private void styleSeasonButton(boolean focused) {
        if (!epLabel.isFocusable()) return;
        UiUtil.stylePill(epLabel, focused, H * 0.04f);
    }

    private void showSeasonPicker(java.util.List<Integer> seasons) {
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (H * 0.02f);
        box.setPadding(pad, pad, pad, pad);
        box.setBackground(UiUtil.rounded(0xF21C1C1E, H * 0.03f));

        TextView head = new TextView(this);
        head.setText("Seasons");
        head.setTextColor(0xAAFFFFFF);
        head.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.026f);
        head.setPadding(pad, 0, pad, (int) (H * 0.01f));
        box.addView(head);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View current = null;
        java.util.List<Integer> options = new ArrayList<>();
        options.addAll(seasons);
        options.add(0); // all episodes goes last
        for (final int n : options) {
            int count = 0;
            for (Plex.Item e : episodes) if (n == 0 || e.parentIndex == n) count++;
            final TextView row = new TextView(this);
            row.setText((n == 0 ? "All episodes" : "Season " + n) + "   \u00B7   " + count);
            row.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.032f);
            row.setTypeface(UiUtil.tf("sans-serif-medium", n == selSeason ? Typeface.BOLD : Typeface.NORMAL));
            row.setTextColor(Color.WHITE);
            row.setPadding(pad * 2, pad, pad * 3, pad);
            row.setFocusable(true);
            row.setClickable(true);
            final float r = H * 0.02f;
            row.setBackground(n == selSeason ? UiUtil.rounded(0x33FFFFFF, r) : null);
            row.setOnFocusChangeListener((v, f) -> {
                row.setBackground(f ? UiUtil.rounded(0xFFFFFFFF, r)
                        : (n == selSeason ? UiUtil.rounded(0x33FFFFFF, r) : null));
                row.setTextColor(f ? 0xFF000000 : Color.WHITE);
                if (f) SoundFx.move();
            });
            row.setOnClickListener(v -> {
                SoundFx.click();
                selSeason = n;
                d.dismiss();
                renderEpisodes(false);
                epLabel.requestFocus();
            });
            list.addView(row);
            if (n == selSeason) current = row;
        }
        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x99000000));
            d.getWindow().setLayout((int) (W * 0.34f), (int) Math.min(H * 0.8f, ViewGroup.LayoutParams.WRAP_CONTENT));
            d.getWindow().setGravity(Gravity.CENTER);
        }
        d.show();
        if (current != null) current.requestFocus();
        else if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
    }

    private boolean allWatched() {
        for (Plex.Item e : episodes) if (e.viewCount == 0) return false;
        return true;
    }

    // ----------------------------------------------------------- Actions

    /** Shows the Trailer button if the server has one for this title (or its show). */
    private void findTrailer() {
        if (item == null || trailerKey != null) return;
        final String k = item.type.equals("episode") && item.gpKey != null ? item.gpKey : item.key;
        Plex.async(() -> {
            String t = Plex.trailerKey(k);
            return t == null ? "" : t;
        }, new Plex.Callback<String>() {
            @Override
            public void ok(String t) {
                if (isDestroyed()) return;
                if (t.isEmpty()) {
                    startBehindWhenReady(); // no trailer: a scene from the film instead
                    return;
                }
                trailerKey = t;
                // No preview came with us from the home screen: start the trailer behind the page,
                // about a second after the page opened.
                startBehindWhenReady();
            }

            @Override
            public void fail(Exception e) {
                if (!isDestroyed()) startBehindWhenReady();
            }
        });
    }

    private boolean autoTrailer() {
        return getSharedPreferences("reel_ui", MODE_PRIVATE).getBoolean("detailTrailer", true) && !HomeActivity.quiet(this);
    }

    private final Runnable startTrailerBehind = this::startTrailerBehind;

    /** Plays the trailer file quietly behind the page (fades in once the first picture is ready). */
    private void startTrailerBehind() {
        if (trailerKey == null || preview != null || isFinishing()) return;
        final String tk = trailerKey;
        Plex.async(() -> Plex.metadata(tk), new Plex.Callback<Plex.Item>() {
            @Override
            public void ok(Plex.Item t) {
                if (t.partKey != null) playBehind(Plex.directUrl(t));
            }

            @Override
            public void fail(Exception e) {
            }
        });
    }

    /** Plays a trailer file quietly behind the page, fading in once the first picture is ready. */
    private void playBehind(String url) {
        if (isFinishing() || isDestroyed() || previewPlaying() || (behindHoldMs == 0 && !hasWindowFocus())) return;
        startBehindPlayer(PreviewHandoff.newPlayer(DetailActivity.this),
                androidx.media3.common.MediaItem.fromUri(url));
    }

    private void startBehindPlayer(final androidx.media3.exoplayer.ExoPlayer p,
                                   androidx.media3.common.MediaItem mi) {
        preview = p;
        previewSession = null;
        previewClip = false;
        p.setVolume(0f);
        previewView.setAlpha(0f);
        previewView.setPlayer(p);
        p.addListener(new androidx.media3.common.Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                if (preview != p) return;
                previewView.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(FADE_EASE).start();
                HomeActivity.fadeVolume(p, HomeActivity.previewVolume(DetailActivity.this), 1800);
                previewOn(true);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == androidx.media3.common.Player.STATE_ENDED) endPreview();
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                endPreview();
            }
        });
        p.setMediaItem(mi);
        final long hold = behindHoldMs;
        behindHoldMs = 0;
        if (hold > 0) {
            p.setPlayWhenReady(false);
            p.prepare();
            handler.postDelayed(() -> {
                if (preview == p) p.play();
            }, hold);
        } else {
            p.prepare();
            p.play();
        }
    }

    /** Full-screen trailer; carries on from where the background preview had got to. */
    private void playTrailer() {
        if (trailerKey == null) return;
        SoundFx.click();
        long from = 0;
        if (preview != null && !previewClip) from = Math.max(0, preview.getCurrentPosition());
        PlayerActivity.start(this, trailerKey, from);
    }

    private void play(boolean fromStart) {
        Plex.Item t = target();
        if (t == null) return;
        PlayerActivity.start(this, t.key, fromStart ? 0 : t.viewOffset);
    }

    @Override
    public void onFocused(Plex.Item it) {
        // Nothing to update here.
    }

    @Override
    public void onClicked(Plex.Item it) {
        if (it.isPlayable()) PlayerActivity.start(this, it.key, it.viewOffset);
    }
}
