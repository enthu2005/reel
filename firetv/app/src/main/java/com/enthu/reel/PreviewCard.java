package com.enthu.reel;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.exoplayer.ExoPlayer;

import com.bumptech.glide.Glide;

import java.util.HashMap;
import java.util.Map;

/**
 * A live preview that grows out of a focused card: the trailer plays inside a wider card over the
 * row. The video starts loading out of sight first, so the card only grows once it's ready.
 * Holding OK makes it full screen. Used by Discover.
 */
final class PreviewCard {

    /** Finds what to play for a title (runs off the main thread). Returns {"direct", url},
     *  {"yt", youtubeId} or {"none"}. */
    interface Source {
        String[] resolve(Plex.Item it) throws Exception;
    }

    private static final long PREPARE_MS = 150;
    private static final long SHOW_MS = 4000;

    /** What every title's preview turned out to be, shared by all screens (home, libraries, search,
     *  Discover, title pages), so a title only has to be looked up once. */
    static final Map<String, String[]> SOURCES = new HashMap<>();
    private static final long MAX_MS = 45_000;

    private final Activity act;
    private final FrameLayout root;
    private final Source source;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, String[]> cache = SOURCES;
    private final int W;
    private final int H;

    private int token = 0;
    private Plex.Item item;
    private View cardRoot;
    private View card;
    private FrameLayout pCard;
    private TextureView tex;
    private WebView web;
    private ExoPlayer player;
    private String session; // Plex conversion session for a clip of the film
    private boolean clip;
    private String[] src;
    private boolean ready;    // something to show (player loading / YouTube playing)
    private boolean due;      // the delay has passed
    private boolean shown;    // card has grown
    private boolean showing;  // video on screen
    private boolean full;
    private boolean paused;
    private float[] from;

    PreviewCard(Activity act, FrameLayout root, Source source) {
        this.act = act;
        this.root = root;
        this.source = source;
        Seerr.init(act);
        W = act.getResources().getDisplayMetrics().widthPixels;
        H = act.getResources().getDisplayMetrics().heightPixels;
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((o, n) -> {
            if (cardRoot != null && n != null && n != cardRoot) {
                cardRoot = null;
                card = null;
                item = null;
                stop();
            }
        });
    }

    private boolean enabled() {
        return act.getSharedPreferences("reel_ui", Activity.MODE_PRIVATE).getBoolean("previews", true) && !HomeActivity.quiet(act);
    }

    /** Source for library titles (see plexSource). */
    static String[] plexTrailer(Plex.Item it) throws Exception {
        return plexSource(it);
    }

    /**
     * The best preview for a library title (background thread), using only what Plex provides:
     * 1. the trailer Plex shows in its own apps (your files, or one it fetched online with
     * Plex Pass); 2. a scene from the film, starting at a chapter around 40% in. Results: {"direct", url, "trailer"},
     * {"hls", ratingKey, offsetMs, "trailer" | "clip"}, {"yt", id} or {"none"}.
     */
    static String[] plexSource(Plex.Item it) throws Exception {
        if (!it.type.equals("movie") && !it.type.equals("show") && !it.type.equals("episode")) {
            return new String[]{"none"};
        }
        // Only what Plex itself provides for things in your library (no YouTube here).
        String k = it.type.equals("episode") && it.gpKey != null ? it.gpKey : it.key;
        String url = Plex.trailerUrl(k);
        if (url != null) return new String[]{"direct", url, "trailer"};
        if (it.type.equals("show")) {
            // No trailer: use a scene from the first episode instead.
            Plex.Item ep = Plex.firstEpisode(it.key);
            if (ep == null) return new String[]{"none"};
            return new String[]{"hls", ep.key, String.valueOf(Plex.sceneOffset(ep.key, ep.duration)), "clip"};
        }
        return new String[]{"hls", it.key, String.valueOf(Plex.sceneOffset(it.key, it.duration)), "clip"};
    }

    /** Old trailer-file-only version, kept for reference. */
    static String[] plexTrailerFileOnly(Plex.Item it) throws Exception {
        if (!it.type.equals("movie") && !it.type.equals("show") && !it.type.equals("episode")) {
            return new String[]{"none"};
        }
        String k = it.type.equals("episode") && it.gpKey != null ? it.gpKey : it.key;
        String tk = Plex.trailerKey(k);
        if (tk != null) {
            Plex.Item t = Plex.metadata(tk);
            if (t.partKey != null) return new String[]{"direct", Plex.directUrl(t)};
        }
        // No trailer on the server: a clip from a fifth of the way into the film / episode instead
        // (same as the home screen does).
        if (it.type.equals("show")) return new String[]{"none"};
        return new String[]{"hls", it.key, String.valueOf((long) (it.duration * 0.2))};
    }

    /**
     * Clicking a card whose preview is up: it grows to full screen (quick swivel), the title page
     * takes over the video with no gap, then {@code open} runs. Returns false if there's no preview
     * card for this title (open normally then).
     */
    boolean openFullThen(final String key, final Runnable open) {
        if (pCard == null || !shown || item == null || !item.key.equals(key)) return false;
        final FrameLayout c = pCard;
        handler.removeCallbacks(end);
        for (int i = 0; i < c.getChildCount(); i++) {
            View ch = c.getChildAt(i);
            if ("cardText".equals(ch.getTag())) ch.animate().alpha(0f).setDuration(Motion.FAST_MS).setInterpolator(Motion.EASE).start();
        }
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) c.getLayoutParams();
        final float cw = c.getWidth() > 0 ? c.getWidth() : Math.max(1, lp.width);
        final float chh = c.getHeight() > 0 ? c.getHeight() : Math.max(1, lp.height);
        float s = Math.max(W / cw, H / chh);
        c.animate().cancel();
        c.setOutlineProvider(null);
        c.setClipToOutline(false);
        c.setPivotX(cw / 2f);
        c.setPivotY(chh / 2f);
        c.setCameraDistance(cw * 10f);
        android.animation.ObjectAnimator g = android.animation.ObjectAnimator.ofPropertyValuesHolder(c,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, c.getScaleX(), s),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, c.getScaleY(), s),
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_X, c.getTranslationX(),
                        W / 2f - (lp.leftMargin + cw / 2f)),
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, c.getTranslationY(),
                        H / 2f - (lp.topMargin + chh / 2f)),
                android.animation.PropertyValuesHolder.ofKeyframe(View.ROTATION_Y,
                        android.animation.Keyframe.ofFloat(0f, 0f),
                        android.animation.Keyframe.ofFloat(0.45f, -10f),
                        android.animation.Keyframe.ofFloat(1f, 0f)));
        g.setDuration(0); // no growing: the title page just dissolves in over the card
        g.setInterpolator(Motion.EASE);
        g.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (player != null) {
                    PreviewHandoff.releaseLeftover();
                    PreviewHandoff.player = player;
                    PreviewHandoff.session = session;
                    PreviewHandoff.key = key;
                    PreviewHandoff.clip = clip;
                    PreviewHandoff.rendered = showing;
                    player = null;
                    session = null;
                }
                open.run();
                handler.postDelayed(() -> stop(), 800);
            }
        });
        g.start();
        return true;
    }

    /** Opening this title's page: give it the playing (or loading) trailer to carry on with. */
    void handOff(String key) {
        if (player != null && item != null && item.key.equals(key)) {
            PreviewHandoff.releaseLeftover();
            PreviewHandoff.player = player;
            PreviewHandoff.session = session;
            PreviewHandoff.key = key;
            PreviewHandoff.clip = clip;
            session = null;
            PreviewHandoff.rendered = showing;
            player = null; // now the title page's
        }
        stop();
    }

    /** What was found for a title earlier (or null). */
    String[] cached(String key) {
        return cache.get(key);
    }

    private final Runnable prepare = () -> start(this.item);
    private final Runnable show = () -> {
        due = true;
        maybePresent();
    };
    private final Runnable end = this::stop;

    /** A card got focus. */
    void onFocused(Plex.Item it, View cardView) {
        if (cardRoot == cardView.getParent() && item == it) return;
        stop();
        card = cardView;
        cardRoot = (View) cardView.getParent();
        item = it;
        if (enabled()) {
            handler.postDelayed(prepare, PREPARE_MS);
            handler.postDelayed(show, SHOW_MS);
        }
    }

    private void start(final Plex.Item it) {
        if (it == null || card == null || act.isFinishing()) return;
        final int t = ++token;
        ready = false;
        String[] known = cache.get(it.key);
        if (known != null) {
            use(known, t);
            return;
        }
        Plex.async(() -> {
            String[] r = source.resolve(it);
            return r == null ? new String[]{"none"} : r;
        }, new Plex.Callback<String[]>() {
            @Override
            public void ok(String[] r) {
                cache.put(it.key, r);
                if (t == token && item == it) use(r, t);
            }

            @Override
            public void fail(Exception e) {
            }
        });
    }

    private void use(String[] r, final int t) {
        if (r[0].equals("none") || item == null) return;
        src = r;
        android.util.Log.d("ReelPreview", "source for " + item.title + ": " + r[0]);
        build(item); // out of sight for now
        if (pCard == null) return;
        clip = r[0].equals("hls") && !(r.length > 3 && "trailer".equals(r[3]));
        boolean hls = r[0].equals("hls");
        if (r[0].equals("direct") || hls) {
            java.util.Map<String, String> hdr = null;
            if (hls) {
                hdr = new java.util.HashMap<>();
                hdr.put("X-Plex-Client-Profile-Extra", Plex.previewProfile());
                hdr.put("X-Plex-Client-Identifier", Plex.clientId());
                hdr.put("X-Plex-Product", "Reel");
                hdr.put("X-Plex-Client-Profile-Name", "Konvergo");
            }
            player = PreviewHandoff.newPlayer(act, hdr);
            player.setVolume(0f);
            player.addListener(new androidx.media3.common.Player.Listener() {
                @Override
                public void onVideoSizeChanged(androidx.media3.common.VideoSize vs) {
                    if (t == token) crop(vs.width, vs.height, vs.pixelWidthHeightRatio);
                }

                @Override
                public void onRenderedFirstFrame() {
                    if (t != token || tex == null) return;
                    showing = true;
                    tex.animate().alpha(1f).setDuration(Motion.SLOW_MS).setInterpolator(Motion.EASE).start();
                    HomeActivity.fadeVolume(player, HomeActivity.previewVolume(act), 1800);
                    handler.removeCallbacks(end);
                    handler.postDelayed(end, MAX_MS);
                }

                @Override
                public void onPlaybackStateChanged(int state) {
                    if (t == token && state == androidx.media3.common.Player.STATE_ENDED) stop();
                }

                @Override
                public void onPlayerError(androidx.media3.common.PlaybackException error) {
                    if (t == token) {
                        if (item != null) cache.remove(item.key);
                        stop();
                    }
                }
            });
            player.setVideoTextureView(tex);
            if (hls) {
                session = java.util.UUID.randomUUID().toString();
                long off = 0;
                try {
                    off = Long.parseLong(r[2]);
                } catch (Exception ignored) {
                }
                player.setMediaItem(new androidx.media3.common.MediaItem.Builder()
                        .setUri(Plex.previewUrl(r[1], off, session))
                        .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8).build());
            } else {
                player.setMediaItem(androidx.media3.common.MediaItem.fromUri(r[1]));
            }
            player.setPlayWhenReady(false); // load now, play once the card is up
            player.prepare();
            ready = true;
            maybePresent();
        } else {
            // YouTube: start it (silent) inside the hidden card; grow the card once it's playing.
            web = YouTubeEmbed.create(act, r[1], 0f, new YouTubeEmbed.Events() {
                @Override
                public void playing() {
                    if (t != token) return;
                    ready = true;
                    if (shown) webOn();
                    else maybePresent();
                }

                @Override
                public void ended() {
                    if (t == token) {
                        if (!shown && item != null) cache.put(item.key, new String[]{"none"});
                        stop();
                    }
                }
            });
            web.setAlpha(0f);
            pCard.addView(web, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            maybePresent();
        }
    }

    private void maybePresent() {
        if (!due || src == null || shown || pCard == null) return;
        // Grow straight away (showing the picture) like the home screen; the video fades in on top
        // the moment it's ready.
        shown = true;
        grow();
        final int t = token;
        if (player != null) {
            handler.postDelayed(() -> {
                if (t == token && player != null) player.play();
            }, 340);
        } else if (web != null && ready) {
            webOn();
        }
    }

    /** YouTube is playing and the card is up: show it and bring the sound in. */
    private void webOn() {
        if (web == null) return;
        showing = true;
        web.animate().alpha(1f).setDuration(Motion.SLOW_MS).setInterpolator(Motion.EASE).start();
        YouTubeEmbed.setVolume(web, HomeActivity.previewVolume(act));
        handler.removeCallbacks(end);
        handler.postDelayed(end, MAX_MS);
    }

    // ------------------------------------------------------------ card

    /** Builds the wide card in its final place, invisible. */
    private void build(Plex.Item it) {
        if (card == null || pCard != null) return;
        int[] rl = new int[2];
        int[] cl = new int[2];
        root.getLocationInWindow(rl);
        card.getLocationInWindow(cl);
        float cw = card.getWidth() * card.getScaleX();
        float ch = card.getHeight() * card.getScaleY();
        float cx = cl[0] - rl[0];
        float cy = cl[1] - rl[1];
        if (cw <= 0 || ch <= 0) return;
        float th = Math.max(H * 0.26f, cw > ch ? ch * 1.35f : ch);
        float tw = th * 16f / 9f;
        float tx = Math.max(W * 0.02f, Math.min(W * 0.98f - tw, cx + cw / 2f - tw / 2f));
        float ty = Math.max(H * 0.02f, Math.min(H * 0.98f - th, cy + ch / 2f - th / 2f));
        from = new float[]{cx, cy, cw, ch};

        final float r = H * 0.016f;
        pCard = new FrameLayout(act);
        pCard.setFocusable(false);
        pCard.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        pCard.setBackground(UiUtil.rounded(UiUtil.CARD, r));
        UiUtil.clipRounded(pCard, r);
        pCard.setElevation(H * 0.03f);
        ImageView still = new ImageView(act);
        still.setScaleType(ImageView.ScaleType.CENTER_CROP);
        String art = it.art != null ? it.art : it.thumb;
        Glide.with(act).load(Plex.img(art, (int) tw, (int) th)).centerCrop().into(still);
        pCard.addView(still, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        tex = new TextureView(act);
        tex.setAlpha(0f);
        pCard.addView(tex, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(act);
        shade.setTag("cardText");
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xCC000000}));
        pCard.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (th * 0.45f), Gravity.BOTTOM));
        LinearLayout text = new LinearLayout(act);
        text.setTag("cardText");
        text.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (th * 0.06f);
        text.setPadding(pad, 0, pad, pad);
        TextView title = new TextView(act);
        title.setText(it.displayTitle());
        title.setTextColor(Color.WHITE);
        title.setTypeface(UiUtil.font(UiUtil.BOLD));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, th * 0.1f);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(title);
        String sub = (it.year > 0 ? it.year + "" : "")
                + (it.watcher.isEmpty() ? "" : (it.year > 0 ? "  ·  " : "") + it.watcher);
        if (!sub.isEmpty()) {
            TextView m = new TextView(act);
            m.setText(sub);
            m.setTextColor(UiUtil.TEXT2);
            m.setTypeface(UiUtil.font(UiUtil.MEDIUM));
            m.setTextSize(TypedValue.COMPLEX_UNIT_PX, th * 0.065f);
            m.setSingleLine(true);
            text.addView(m);
        }
        pCard.addView(text, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) tw, (int) th, Gravity.TOP | Gravity.START);
        lp.leftMargin = (int) tx;
        lp.topMargin = (int) ty;
        pCard.setAlpha(0f);
        root.addView(pCard, lp);
    }

    /** Grows the (hidden) wide card out of the poster. */
    private void grow() {
        if (pCard == null || from == null) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) pCard.getLayoutParams();
        float tw = lp.width;
        float th = lp.height;
        pCard.setPivotX(0f);
        pCard.setPivotY(0f);
        pCard.setScaleX(from[2] / tw);
        pCard.setScaleY(from[3] / th);
        pCard.setTranslationX(from[0] - lp.leftMargin);
        pCard.setTranslationY(from[1] - lp.topMargin);
        pCard.setAlpha(0.6f);
        pCard.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).alpha(1f)
                .setDuration(Motion.MEDIUM_MS).setInterpolator(Motion.EASE).start();
    }

    private void crop(int vw, int vh, float ratio) {
        if (tex == null || vw <= 0 || vh <= 0) return;
        float viewW = tex.getWidth();
        float viewH = tex.getHeight();
        if (viewW <= 0 || viewH <= 0) return;
        float videoW = vw * (ratio > 0 ? ratio : 1f);
        float s = Math.max(viewW / videoW, viewH / vh);
        android.graphics.Matrix mx = new android.graphics.Matrix();
        mx.setScale(videoW * s / viewW, vh * s / viewH, viewW / 2f, viewH / 2f);
        tex.setTransform(mx);
    }

    private void setVolume(float v) {
        if (player != null) HomeActivity.fadeVolume(player, v, 600);
        YouTubeEmbed.setVolume(web, v);
    }

    // ---------------------------------------------------- full screen

    /** Holding OK on the card whose preview is playing: full screen. */
    boolean longPress(Plex.Item it) {
        if (pCard == null || !shown || it != item) return false;
        final FrameLayout c = pCard;
        full = true;
        handler.removeCallbacks(end);
        for (int i = 0; i < c.getChildCount(); i++) {
            View ch = c.getChildAt(i);
            if ("cardText".equals(ch.getTag())) ch.animate().alpha(0f).setDuration(Motion.FAST_MS).setInterpolator(Motion.EASE).start();
        }
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) c.getLayoutParams();
        // A card that was only just created hasn't been measured yet (width 0): use its planned size.
        final float cw = c.getWidth() > 0 ? c.getWidth() : Math.max(1, lp.width);
        final float chh = c.getHeight() > 0 ? c.getHeight() : Math.max(1, lp.height);
        float s = Math.max((float) W / cw, (float) H / chh);
        c.setOutlineProvider(null);
        c.setClipToOutline(false);
        c.setPivotX(cw / 2f);
        c.setPivotY(chh / 2f);
        c.setCameraDistance(cw * 10f);
        android.animation.ObjectAnimator g = android.animation.ObjectAnimator.ofPropertyValuesHolder(c,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, c.getScaleX(), s),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, c.getScaleY(), s),
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_X, c.getTranslationX(),
                        W / 2f - (lp.leftMargin + cw / 2f)),
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, c.getTranslationY(),
                        H / 2f - (lp.topMargin + chh / 2f)),
                android.animation.PropertyValuesHolder.ofKeyframe(View.ROTATION_Y,
                        android.animation.Keyframe.ofFloat(0f, 0f),
                        android.animation.Keyframe.ofFloat(0.45f, -16f),
                        android.animation.Keyframe.ofFloat(1f, 0f)));
        g.setDuration(Motion.SLOW_MS);
        g.setInterpolator(Motion.EASE);
        g.start();
        setVolume(HomeActivity.previewVolume(act));
        return true;
    }

    private void shrink() {
        full = false;
        if (paused) togglePause();
        final FrameLayout c = pCard;
        if (c == null) return;
        handler.removeCallbacks(end);
        handler.postDelayed(end, MAX_MS);
        c.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).rotationY(0f)
                .setDuration(Motion.MEDIUM_MS).setInterpolator(Motion.EASE)
                .withEndAction(() -> UiUtil.clipRounded(c, H * 0.016f)).start();
        for (int i = 0; i < c.getChildCount(); i++) {
            View ch = c.getChildAt(i);
            if ("cardText".equals(ch.getTag())) ch.animate().alpha(1f).setDuration(Motion.MEDIUM_MS).setInterpolator(Motion.EASE).start();
        }
        setVolume(HomeActivity.previewVolume(act));
    }

    private void togglePause() {
        paused = !paused;
        if (player != null) player.setPlayWhenReady(!paused);
        YouTubeEmbed.pause(web, paused);
    }

    /**
     * While full screen: OK opens the title's page (which fades in over the video), Play/Pause pauses,
     * anything else goes back to the card. True if it used the key.
     */
    boolean handleKey(KeyEvent e) {
        if (!full) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            int k = e.getKeyCode();
            boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER;
            if (ok && item != null && item.key != null && !item.key.startsWith("seerr:")) {
                final String openKey = item.key;
                full = false;
                if (paused) togglePause();
                if (openFullThen(openKey, () -> {
                    android.content.Intent i = new android.content.Intent(act, DetailActivity.class);
                    i.putExtra("key", openKey);
                    act.startActivity(i);
                })) {
                    return true;
                }
                full = true;
            }
            if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || ok) {
                togglePause();
                android.widget.Toast.makeText(act, paused ? "Paused · Back to return" : "Playing",
                        android.widget.Toast.LENGTH_SHORT).show();
            } else {
                shrink();
            }
        }
        return true;
    }

    // ------------------------------------------------------------ stop

    void stop() {
        token++;
        full = false;
        paused = false;
        ready = false;
        due = false;
        shown = false;
        showing = false;
        src = null;
        handler.removeCallbacks(prepare);
        handler.removeCallbacks(show);
        handler.removeCallbacks(end);
        if (player != null) {
            player.release();
            player = null;
        }
        if (session != null) {
            Plex.stopTranscode(session);
            session = null;
        }
        WebView wv = web;
        web = null;
        YouTubeEmbed.destroy(wv);
        final FrameLayout c = pCard;
        pCard = null;
        tex = null;
        if (c == null) return;
        if (from != null && c.getWidth() > 0 && c.getAlpha() > 0.05f) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) c.getLayoutParams();
            c.animate().scaleX(from[2] / c.getWidth()).scaleY(from[3] / c.getHeight())
                    .translationX(from[0] - lp.leftMargin).translationY(from[1] - lp.topMargin)
                    .rotationY(0f).alpha(0f).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.EASE).withEndAction(() -> {
                        if (c.getParent() != null) ((ViewGroup) c.getParent()).removeView(c);
                    }).start();
        } else if (c.getParent() != null) {
            ((ViewGroup) c.getParent()).removeView(c);
        }
    }
}
