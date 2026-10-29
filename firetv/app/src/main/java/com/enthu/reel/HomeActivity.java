package com.enthu.reel;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Home: a spotlight card that rotates through featured titles, a frosted-glass tab bar,
 * and rows of posters underneath. Moving onto a poster previews that title in the card.
 */
public class HomeActivity extends ReelActivity implements PosterAdapter.Listener {

    private static final int TAB_HOME = 0;
    private static final int TAB_MOVIES = 1;
    private static final int TAB_SHOWS = 2;
    private static final String[] TAB_NAMES = {"Home", "Movies", "TV Shows", "Libraries", "Discover", "Live", "Search"};
    private static final long SPOTLIGHT_MS = 12000L;
    private static final int SPOTLIGHT_COUNT = 10;

    private static class Row {
        final String title;
        final List<Plex.Item> items;
        final boolean landscape;
        PosterAdapter adapter; // built once, so scrolling back to a row doesn't rebuild it

        Row(String title, List<Plex.Item> items, boolean landscape) {
            this.title = title;
            this.items = items;
            this.landscape = landscape;
        }
    }

    private static class Loaded {
        final List<Row> rows = new ArrayList<>();
        final List<Plex.Item> spotlight = new ArrayList<>();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Row> rows = new ArrayList<>();
    private final List<Plex.Item> spotlight = new ArrayList<>();
    private final TextView[] tabViews = new TextView[7];

    private int W;
    private int H;
    private int tab = TAB_HOME;
    private boolean loadedOnce = false;
    private int loadToken = 0;

    // Backdrop (two layers so we can cross-fade) and the frosted bar's blurred copy
    private FrameLayout bgBox;
    private ImageView bgA;
    private ImageView bgB;
    private ImageView bgFront;
    private ImageView blurImg;

    // Spotlight card
    private FrameLayout heroOuter;
    private LinearLayout heroGroup;
    private ImageView heroLogo;
    private TextView heroTitle;
    private TextView heroMeta;
    private TextView heroSummary;
    private TextView playBtn;

    private LinearLayout dots;

    private TextView status;
    private RecyclerView rowsView;
    private RowAdapter rowAdapter;

    private Plex.Item current;

    // ------------------------------------------- Live previews (optional)
    // Rest on a card and it grows into a wider card with the trailer playing inside it.
    // Pressing OK then opens the title with the trailer filling the screen behind it.
    private androidx.media3.exoplayer.ExoPlayer previewPlayer;
    private String previewSession;
    private boolean previewIsClip;
    private boolean previewShowing; // first frame is on screen
    private int previewToken = 0;
    private static final long PREVIEW_DELAY_MS = 4000;
    private static final long PREVIEW_MAX_MS = 45_000;
    private final Runnable previewStart = () -> startPreview(this.focusedItem);
    private final Runnable previewEnd = this::stopPreview;

    private FrameLayout rootView;
    private SeasonalView season;

    /** Christmas / Halloween (Settings), drawn over the page. */
    private void applySeason() {
        int t = SeasonalView.theme();
        if (season != null && season.getTag() != null && (Integer) season.getTag() == t) return;
        if (season != null) rootView.removeView(season);
        season = null;
        if (t == SeasonalView.NONE || rootView == null) return;
        season = new SeasonalView(this, t);
        season.setTag(t);
        rootView.addView(season, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onResume() {
        super.onResume();
        applySeason();
        checkElsewhere();
    }

    private static boolean elsewhereChecked;

    /**
     * "Continue from another device": the newest thing you were partway through is newer than anything this
     * device has played, so it was watched somewhere else. Asked once each time Reel opens.
     */
    private void checkElsewhere() {
        if (elsewhereChecked || !Plex.isReady() || Plex.prefs() == null) return;
        elsewhereChecked = true;
        Plex.async(() -> {
            Plex.Item best = null;
            for (Plex.Item x : Plex.items(Plex.paged("/library/onDeck", 20))) {
                if (x.viewOffset <= 0 || x.duration <= 0 || x.lastViewedAt <= 0) continue;
                if (best == null || x.lastViewedAt > best.lastViewedAt) best = x;
            }
            if (best == null) return new Object[0];
            // only if it was played in the last 5 minutes, otherwise it is just noise
            long recent = System.currentTimeMillis() / 1000 - 5 * 60;
            if (best.lastViewedAt < recent || best.lastViewedAt <= Plex.prefs().getLong("lastPlayedAt", 0) + 90) {
                return new Object[0];
            }
            if (Plex.prefs().getString("dismissedBanner", "").equals(best.key + "@" + best.viewOffset)) {
                return new Object[0];
            }
            String[] d = Plex.lastDevice(best.key);
            if (d[1].equals("1")) return new Object[0]; // that was this device
            return new Object[]{best, d[0]};
        }, new Plex.Callback<Object[]>() {
            @Override
            public void ok(Object[] r) {
                if (r.length == 0 || isDestroyed() || isFinishing()) return;
                final Plex.Item it = (Plex.Item) r[0];
                String name = it.type.equals("episode") && it.gpTitle != null
                        ? it.gpTitle + "  S" + it.parentIndex + " E" + it.index : it.title;
                Runnable dismiss = () -> Plex.prefs().edit().putString("dismissedBanner", it.key + "@" + it.viewOffset).apply();
                ResumeDialog.elsewhere(HomeActivity.this, name, (String) r[1], it.viewOffset, () -> {
                    dismiss.run();
                    PlayerActivity.start(HomeActivity.this, it.key, it.viewOffset);
                }, dismiss);
            }

            @Override
            public void fail(Exception e) {
            }
        });
    }
    private View focusedCardRoot;  // the focused card's whole item view
    private View focusedCard;      // its picture
    private View hiddenCardRoot;   // the original card, hidden while its preview card is up

    private void showHiddenCard() {
        if (hiddenCardRoot != null) hiddenCardRoot.setAlpha(1f);
        hiddenCardRoot = null;
    }
    private Plex.Item focusedItem;
    private FrameLayout pCard;     // the grown card
    private android.view.TextureView pTex;
    private float[] pFrom;         // card rect the grown card came from {x, y, w, h}

    private boolean previewsOn() {
        return getSharedPreferences("reel_ui", MODE_PRIVATE).getBoolean("previews", true) && !quiet(this);
    }

    /** Quiet mode (the moon next to Settings): no previews, no auto-playing trailers, no preview sound. Stays until changed. */
    static boolean quiet(android.content.Context c) {
        return c.getSharedPreferences("reel_ui", MODE_PRIVATE).getBoolean("quiet", false);
    }

    // Where each title's preview comes from, remembered so a second visit starts at once:
    // {"direct", file url, "0"} for a trailer file, {"hls", ratingKey, "1"} for a clip of the film,
    // or {"none"}.
    private static final java.util.Map<String, String[]> previewSources = PreviewCard.SOURCES; // shared

    /** The preview source found for a title earlier (for the title page), or null. */
    static String[] cachedPreviewSource(String key) {
        return key == null ? null : previewSources.get(key);
    }
    private boolean previewReady;   // player built and loading
    private boolean previewDue;     // the card should be showing by now
    private static final long PREVIEW_PREPARE_MS = 150;
    private final Runnable previewShow = () -> {
        previewDue = true;
        if (previewReady) showCard(this.focusedItem);
    };

    @Override
    public void onFocusedView(Plex.Item item, View card) {
        if (focusedCardRoot == card.getParent() && focusedItem == item) return;
        stopHeroPreview(); // moving down into the rows: the spotlight preview stops
        stopPreview();
        if (isGenre(item)) {
            // A genre card has no trailer to preview
            focusedCard = null;
            focusedCardRoot = null;
            focusedItem = null;
            return;
        }
        focusedCard = card;
        focusedCardRoot = (View) card.getParent();
        focusedItem = item;
        if (previewsOn()) {
            // Start loading early (quietly, off screen), show the card a moment later.
            handler.postDelayed(previewStart, PREVIEW_PREPARE_MS);
            handler.postDelayed(previewShow, PREVIEW_DELAY_MS);
        }
    }

    private void startPreview(final Plex.Item it) {
        if (it == null || isFinishing() || !previewsOn() || focusedCard == null) return;
        if (!it.type.equals("movie") && !it.type.equals("show") && !it.type.equals("episode")) return;
        final int token = ++previewToken;
        final Seerr.Media req = isRequest(it) ? requestMedia.get(it.key) : null;
        if (isRequest(it) && req == null) return;
        previewReady = false;
        String[] known = previewSources.get(it.key);
        if (known != null) {
            usePreviewSource(it, known, token);
            return;
        }
        Plex.async(() -> {
            if (req != null && (req.ratingKey == null || req.status < 4)) {
                // A request that isn't in Plex yet: its trailer from YouTube, via Seerr.
                Seerr.Media full = Seerr.details(req.type, req.id);
                return full.youtubeTrailer != null ? new String[]{"yt", full.youtubeTrailer, "0"} : new String[]{"none"};
            }
            if (req != null) {
                // In Plex already: treat it like any library title.
                Plex.Item p = new Plex.Item();
                p.key = req.ratingKey;
                p.type = req.type.equals("tv") ? "show" : "movie";
                return PreviewCard.plexSource(p);
            }
            return PreviewCard.plexSource(it);
        }, new Plex.Callback<String[]>() {
            @Override
            public void ok(String[] src) {
                previewSources.put(it.key, src);
                if (token != previewToken || isFinishing() || focusedItem != it) return;
                usePreviewSource(it, src, token);
            }

            @Override
            public void fail(Exception e) {
            }
        });
    }

    private void usePreviewSource(Plex.Item it, String[] src, int token) {
        if (src[0].equals("none")) return;
        previewIsClip = src[0].equals("hls") && !(src.length > 3 && "trailer".equals(src[3]));
        if (src[0].equals("yt")) {
            ytKey = src[1];
            previewReady = true;
            if (previewDue) showCard(it);
            return;
        }
        if (src[0].equals("direct")) {
            playPreview(src[1], null, 0, token);
        } else {
            long off = 0;
            try {
                off = Long.parseLong(src[2]);
            } catch (Exception ignored) {
            }
            playPreview(null, src[1], off, token);
        }
        previewReady = true;
        if (previewDue) showCard(it);
    }

    /** Grows the card and lets the (already loading) video play in it. */
    private void showCard(Plex.Item it) {
        if (it == null || it != focusedItem) return;
        if (ytKey != null && previewPlayer == null) {
            if (pCard == null) openCard(it);
            if (bgDrift != null) bgDrift.pause();
            addYoutube(ytKey, previewToken);
            return;
        }
        if (previewPlayer == null) return;
        if (pCard == null) openCard(it);
        if (pTex != null) previewPlayer.setVideoTextureView(pTex);
        // The slow zoom on the big background picture costs the graphics chip a lot; pause it
        // while the preview plays, and start the video once the card has finished growing.
        if (bgDrift != null) bgDrift.pause();
        final int token = previewToken;
        handler.postDelayed(() -> {
            if (token == previewToken && previewPlayer != null) previewPlayer.play();
        }, 340);
    }

    private android.animation.ObjectAnimator bgDrift;

    // ---- YouTube trailers for requests (things not in Plex yet), in the official embedded player
    private String ytKey;
    private android.webkit.WebView ytView;

    private void addYoutube(String id, final int token) {
        if (pCard == null) return;
        removeYoutube();
        final android.webkit.WebView[] ref = new android.webkit.WebView[1];
        ref[0] = YouTubeEmbed.create(this, id, previewVolume(this), new YouTubeEmbed.Events() {
            @Override
            public void playing() {
                if (token != previewToken || ytView != ref[0]) return;
                previewShowing = true;
                ref[0].animate().alpha(1f).setDuration(Motion.SLOW_MS).setInterpolator(Motion.EASE).start();
                handler.removeCallbacks(previewEnd);
                handler.postDelayed(previewEnd, PREVIEW_MAX_MS);
            }

            @Override
            public void ended() {
                if (token == previewToken) stopPreview();
            }
        });
        ytView = ref[0];
        ytView.setAlpha(0f);
        pCard.addView(ytView, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void setYoutubeVolume(float v) {
        YouTubeEmbed.setVolume(ytView, v);
    }

    private void removeYoutube() {
        android.webkit.WebView wv = ytView;
        ytView = null;
        YouTubeEmbed.destroy(wv);
    }

    /** Grows a wide card out of the focused one, showing the backdrop until the video starts. */
    private void openCard(Plex.Item it) {
        if (focusedCard == null || rootView == null) return;
        removeCard();
        int[] rl = new int[2];
        int[] cl = new int[2];
        rootView.getLocationInWindow(rl);
        focusedCard.getLocationInWindow(cl);
        float cw = focusedCard.getWidth() * focusedCard.getScaleX();
        float ch = focusedCard.getHeight() * focusedCard.getScaleY();
        float cx = cl[0] - rl[0];
        float cy = cl[1] - rl[1];
        if (cw <= 0 || ch <= 0) return;
        boolean wide = cw > ch;
        float th = Math.max(H * 0.26f, wide ? ch * 1.35f : ch);
        float tw = th * 16f / 9f;
        float tx = Math.max(W * 0.02f, Math.min(W * 0.98f - tw, cx + cw / 2f - tw / 2f));
        float ty = Math.max(H * 0.02f, Math.min(H * 0.98f - th, cy + ch / 2f - th / 2f));
        pFrom = new float[]{cx, cy, cw, ch};

        final float r = H * 0.016f;
        pCard = new FrameLayout(this);
        pCard.setFocusable(false);
        pCard.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS); // the video must never take the remote
        pCard.setBackground(UiUtil.rounded(UiUtil.CARD, r));
        UiUtil.clipRounded(pCard, r);
        pCard.setElevation(H * 0.03f);
        ImageView still = new ImageView(this);
        still.setScaleType(ImageView.ScaleType.CENTER_CROP);
        String art = it.heroArt() != null ? it.heroArt() : it.thumb;
        Glide.with(this).load(Plex.img(art, (int) tw, (int) th)).centerCrop().into(still);
        pCard.addView(still, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        pTex = new android.view.TextureView(this);
        pTex.setAlpha(0f);
        pCard.addView(pTex, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(this);
        shade.setTag("cardText");
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xCC000000}));
        pCard.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (th * 0.45f), Gravity.BOTTOM));
        LinearLayout text = new LinearLayout(this);
        text.setTag("cardText");
        text.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (th * 0.06f);
        text.setPadding(pad, 0, pad, pad);
        TextView t = new TextView(this);
        t.setText(it.displayTitle());
        t.setTextColor(Color.WHITE);
        t.setTypeface(UiUtil.font(UiUtil.BOLD));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, th * 0.1f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(t);
        TextView m = new TextView(this);
        m.setText(it.metaLine());
        m.setTextColor(UiUtil.TEXT2);
        m.setTypeface(UiUtil.font(UiUtil.MEDIUM));
        m.setTextSize(TypedValue.COMPLEX_UNIT_PX, th * 0.065f);
        m.setSingleLine(true);
        m.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(m);
        pCard.addView(text, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) tw, (int) th, Gravity.TOP | Gravity.START);
        lp.leftMargin = (int) tx;
        lp.topMargin = (int) ty;
        rootView.addView(pCard, lp);
        // Start exactly over the card, then grow.
        pCard.setPivotX(0f);
        pCard.setPivotY(0f);
        pCard.setScaleX(cw / tw);
        pCard.setScaleY(ch / th);
        pCard.setTranslationX(cx - tx);
        pCard.setTranslationY(cy - ty);
        pCard.setAlpha(0.6f);
        pCard.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).alpha(1f)
                .setDuration(Motion.MEDIUM_MS).setInterpolator(Motion.EASE).start();
        showHiddenCard();
        if (focusedCardRoot != null) {
            hiddenCardRoot = focusedCardRoot;
            hiddenCardRoot.setAlpha(0f);
        }
        dimBackdrop(true);
    }

    /** While a card preview plays the page behind it goes to a plain dark colour (the backdrop
     *  picture there no longer matches what is on screen); it comes back when the preview ends. */
    private void dimBackdrop(boolean on) {
        if (bgBox == null) return;
        bgBox.animate().cancel();
        bgBox.animate().alpha(on ? 0f : 1f).setDuration(on ? Motion.MEDIUM_MS : Motion.SLOW_MS).start();
    }

    /** Fills the TextureView with the video, cropping the edges (like a centre-crop picture). */
    private void cropVideo(int vw, int vh, float ratio) {
        if (pTex == null || vw <= 0 || vh <= 0) return;
        float viewW = pTex.getWidth();
        float viewH = pTex.getHeight();
        if (viewW <= 0 || viewH <= 0) return;
        float videoW = vw * (ratio > 0 ? ratio : 1f);
        float s = Math.max(viewW / videoW, viewH / vh);
        android.graphics.Matrix mx = new android.graphics.Matrix();
        mx.setScale(videoW * s / viewW, vh * s / viewH, viewW / 2f, viewH / 2f);
        pTex.setTransform(mx);
    }

    private void playPreview(String directUrl, String hlsKey, long offsetMs, final int token) {
        releasePreviewPlayer();
        previewShowing = false;
        java.util.Map<String, String> hdr = new java.util.HashMap<>();
        hdr.put("X-Plex-Client-Profile-Extra", Plex.previewProfile());
        hdr.put("X-Plex-Client-Identifier", Plex.clientId());
        hdr.put("X-Plex-Product", "Reel");
        hdr.put("X-Plex-Client-Profile-Name", "Konvergo");
        // Sound decoded in the app (so it can be quiet) and levelled, same as every other preview.
        previewPlayer = PreviewHandoff.newPlayer(this, hdr);
        previewPlayer.setVolume(0f);
        previewPlayer.addListener(new androidx.media3.common.Player.Listener() {
            @Override
            public void onVideoSizeChanged(androidx.media3.common.VideoSize vs) {
                if (token == previewToken) cropVideo(vs.width, vs.height, vs.pixelWidthHeightRatio);
            }

            @Override
            public void onRenderedFirstFrame() {
                if (token != previewToken || pTex == null) return;
                previewShowing = true;
                pTex.animate().alpha(1f).setDuration(Motion.SLOW_MS).setInterpolator(Motion.EASE).start();
                fadeVolume(previewPlayer, previewVolume(HomeActivity.this), 1800);
                handler.removeCallbacks(previewEnd);
                handler.postDelayed(previewEnd, PREVIEW_MAX_MS);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (token == previewToken && state == androidx.media3.common.Player.STATE_ENDED) stopPreview();
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                if (token == previewToken) {
                    if (focusedItem != null) previewSources.remove(focusedItem.key); // try afresh next time
                    stopPreview();
                }
            }
        });
        androidx.media3.common.MediaItem mi;
        if (directUrl != null) {
            mi = androidx.media3.common.MediaItem.fromUri(directUrl);
        } else {
            previewSession = java.util.UUID.randomUUID().toString();
            mi = new androidx.media3.common.MediaItem.Builder()
                    .setUri(Plex.previewUrl(hlsKey, offsetMs, previewSession))
                    .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8).build();
        }
        previewPlayer.setMediaItem(mi);
        previewPlayer.setPlayWhenReady(false); // load now, play once the card is up
        previewPlayer.prepare();
    }

    /** Preview sound level from Settings: off, low (default) or medium. */
    static float previewVolume(android.content.Context c) {
        if (quiet(c)) return 0f;
        int v = c.getSharedPreferences("reel_ui", MODE_PRIVATE).getInt("previewSound", 1);
        // One level for every preview and trailer (card, title page, full screen), so they match.
        return v == 0 ? 0f : v == 2 ? 0.5f : 0.3f;
    }

    /** Eases the volume up (or down) so the sound doesn't jump in. */
    static void fadeVolume(final androidx.media3.exoplayer.ExoPlayer p, float to, long ms) {
        if (p == null) return;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(p.getVolume(), to);
        a.setDuration(ms);
        a.addUpdateListener(an -> {
            try {
                p.setVolume((Float) an.getAnimatedValue());
            } catch (Exception ignored) {
                an.cancel(); // the player was released
            }
        });
        a.start();
    }

    /** Shrinks the grown card back into its place and stops the video. */
    private void stopPreview() {
        if (bgDrift != null && bgDrift.isPaused()) bgDrift.resume();
        previewToken++;
        previewFullscreen = false;
        previewPaused = false;
        ytKey = null;
        removeYoutube();
        previewShowing = false;
        previewReady = false;
        previewDue = false;
        handler.removeCallbacks(previewShow);
        handler.removeCallbacks(previewStart);
        handler.removeCallbacks(previewEnd);
        releasePreviewPlayer();
        final FrameLayout c = pCard;
        pCard = null;
        pTex = null;
        dimBackdrop(false);
        showHiddenCard();
        if (c == null) return;
        if (pFrom != null && c.getWidth() > 0) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) c.getLayoutParams();
            c.animate().scaleX(pFrom[2] / c.getWidth()).scaleY(pFrom[3] / c.getHeight())
                    .translationX(pFrom[0] - lp.leftMargin).translationY(pFrom[1] - lp.topMargin)
                    .alpha(0f).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.EASE).withEndAction(() -> {
                        if (c.getParent() != null) ((ViewGroup) c.getParent()).removeView(c);
                    }).start();
        } else if (c.getParent() != null) {
            ((ViewGroup) c.getParent()).removeView(c);
        }
    }

    private void removeCard() {
        dimBackdrop(false);
        showHiddenCard();
        removeYoutube();
        if (pCard != null && pCard.getParent() != null) ((ViewGroup) pCard.getParent()).removeView(pCard);
        pCard = null;
        pTex = null;
    }

    private void releasePreviewPlayer() {
        if (previewPlayer != null) {
            previewPlayer.release();
            previewPlayer = null;
        }
        if (previewSession != null) {
            Plex.stopTranscode(previewSession);
            previewSession = null;
        }
    }

    // ---- Spotlight preview: after 3 seconds the backdrop turns into the title's trailer / a scene.
    // The video is found and loaded during those 3 seconds, so it can start the moment they're up.
    private androidx.media3.exoplayer.ExoPlayer heroPlayer;
    private android.view.TextureView heroTex;
    private String heroSession;
    private int heroPrevToken;
    private boolean heroPrevDue;
    private boolean heroPrevReady;
    private boolean heroPrevShowing;
    private boolean rowsPushed;
    private boolean heroFull;
    private final java.util.HashMap<View, Float> heroFullAlphas = new java.util.HashMap<>();

    /** Holding OK on the spotlight button while its preview plays: just the video, nothing on top. */
    private boolean enterHeroFull() {
        if (heroFull || !heroPrevShowing || heroPlayer == null || rootView == null) return false;
        heroFull = true;
        heroFullAlphas.clear();
        for (int i = 0; i < rootView.getChildCount(); i++) {
            View v = rootView.getChildAt(i);
            if (v == bgBox || v == heroTex) continue;
            heroFullAlphas.put(v, v.getAlpha());
            v.animate().alpha(0f).setDuration(Motion.MEDIUM_MS).setInterpolator(Motion.EASE).start();
        }
        handler.removeCallbacks(heroEnd);
        if (heroPrevClip) handler.postDelayed(heroEnd, 90_000); // a clip of the film still stops
        return true;
    }

    private void exitHeroFull() {
        if (!heroFull) return;
        heroFull = false;
        for (java.util.Map.Entry<View, Float> en : heroFullAlphas.entrySet()) {
            en.getKey().animate().alpha(en.getValue()).setDuration(Motion.MEDIUM_MS).setInterpolator(Motion.EASE).start();
        }
        heroFullAlphas.clear();
        if (heroPrevShowing) {
            handler.removeCallbacks(heroEnd);
            handler.postDelayed(heroEnd, PREVIEW_MAX_MS);
        }
    }

    /** While the spotlight preview plays the rows slide down out of the way; title, nav and button stay. */
    /** A short beat after the preview picture appears, the cards slide down out of its way. */
    private final Runnable heroPush = () -> {
        if (heroPrevShowing) pushRows(true);
    };
    private static final long PUSH_DELAY_MS = 1200;

    private void pushRows(boolean down) {
        if (rowsView == null || rowsPushed == down) return;
        rowsPushed = down;
        rowsView.animate().cancel();
        rowsView.animate().translationY(down ? H * 0.34f : 0f).setDuration(down ? Motion.LONG_MS : Motion.SLOW_MS)
                .setInterpolator(Motion.EASE).start();
        // The title, details and button drop too, so the picture above them is easy to see
        if (heroOuter != null) {
            heroOuter.animate().cancel();
            heroOuter.animate().translationY(down ? H * 0.3f : 0f).setDuration(down ? Motion.LONG_MS : Motion.SLOW_MS)
                    .setInterpolator(Motion.EASE).start();
        }
    }
    private static final long HERO_PREVIEW_MS = 2000;
    private final Runnable heroDue = () -> {
        heroPrevDue = true;
        if (heroPrevReady && heroPlayer != null) heroPlayer.play();
    };
    private final Runnable heroEnd = this::stopHeroPreview;
    private boolean heroPrevActive;
    private boolean heroPrevClip;

    private void maybeStartHeroPreview() {
        if (spotlightMode && !heroPrevActive && current != null) startHeroPreview(current);
    }

    /** OK on the spotlight while its preview plays: the same player carries on in the title page. */
    private boolean handOffHero(Plex.Item it) {
        if (heroPlayer == null || !heroPrevReady || current == null || it == null
                || !it.key.equals(current.key)) return false;
        PreviewHandoff.releaseLeftover();
        PreviewHandoff.player = heroPlayer;
        PreviewHandoff.session = heroSession;
        PreviewHandoff.key = it.key;
        PreviewHandoff.clip = heroPrevClip;
        PreviewHandoff.rendered = heroPrevShowing;
        heroPlayer = null;
        heroSession = null;
        heroPrevToken++; // the old callbacks must not touch it any more
        heroPrevReady = false;
        heroPrevActive = false;
        handler.removeCallbacks(heroDue);
        handler.removeCallbacks(heroEnd);
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra("key", it.key);
        startActivity(i);
        overridePendingTransition(R.anim.reel_fade_in, R.anim.reel_hold); // dissolve; the video carries on
        final android.view.TextureView tx = heroTex;
        handler.postDelayed(() -> {
            pushRows(false);
            if (tx != null) tx.setAlpha(0f);
        }, 800);
        return true;
    }

    private void startHeroPreview(final Plex.Item it) {
        if (it == null || isFinishing() || !previewsOn() || isRequest(it)) return;
        if (playBtn == null || !playBtn.hasFocus()) return; // only while the spotlight button is selected
        if (!it.type.equals("movie") && !it.type.equals("show") && !it.type.equals("episode")) return;
        heroPrevActive = true;
        final int token = ++heroPrevToken;
        handler.postDelayed(heroDue, HERO_PREVIEW_MS);
        String[] known = previewSources.get(it.key);
        if (known != null) {
            useHeroSource(known, token);
            return;
        }
        Plex.async(() -> PreviewCard.plexSource(it), new Plex.Callback<String[]>() {
            @Override
            public void ok(String[] src) {
                previewSources.put(it.key, src);
                if (token == heroPrevToken && !isFinishing()) useHeroSource(src, token);
            }

            @Override
            public void fail(Exception e) {
            }
        });
    }

    private void useHeroSource(String[] src, final int token) {
        if (src[0].equals("none") || src[0].equals("yt") || heroTex == null) return;
        java.util.Map<String, String> hdr = null;
        androidx.media3.common.MediaItem mi;
        heroPrevClip = src[0].equals("hls") && !(src.length > 3 && "trailer".equals(src[3]));
        if (src[0].equals("direct")) {
            mi = androidx.media3.common.MediaItem.fromUri(src[1]);
        } else {
            hdr = new java.util.HashMap<>();
            hdr.put("X-Plex-Client-Profile-Extra", Plex.previewProfile());
            hdr.put("X-Plex-Client-Identifier", Plex.clientId());
            hdr.put("X-Plex-Product", "Reel");
            hdr.put("X-Plex-Client-Profile-Name", "Konvergo");
            long off = 0;
            try {
                off = Long.parseLong(src[2]);
            } catch (Exception ignored) {
            }
            heroSession = java.util.UUID.randomUUID().toString();
            mi = new androidx.media3.common.MediaItem.Builder()
                    .setUri(Plex.previewUrl(src[1], off, heroSession))
                    .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8).build();
        }
        releaseHeroPlayer();
        heroPlayer = PreviewHandoff.newPlayer(this, hdr);
        heroPlayer.setVolume(0f);
        heroPlayer.addListener(new androidx.media3.common.Player.Listener() {
            @Override
            public void onVideoSizeChanged(androidx.media3.common.VideoSize vs) {
                if (token != heroPrevToken || heroTex == null || vs.width <= 0 || vs.height <= 0) return;
                float vw = heroTex.getWidth(), vh = heroTex.getHeight();
                if (vw <= 0 || vh <= 0) return;
                float videoW = vs.width * (vs.pixelWidthHeightRatio > 0 ? vs.pixelWidthHeightRatio : 1f);
                float sc = Math.max(vw / videoW, vh / vs.height);
                android.graphics.Matrix mx = new android.graphics.Matrix();
                mx.setScale(videoW * sc / vw, vs.height * sc / vh, vw / 2f, vh / 2f);
                heroTex.setTransform(mx);
            }

            @Override
            public void onRenderedFirstFrame() {
                if (token != heroPrevToken || heroTex == null) return;
                heroPrevShowing = true;
                if (bgDrift != null) bgDrift.pause();
                handler.removeCallbacks(heroPush);
                handler.postDelayed(heroPush, PUSH_DELAY_MS);
                heroTex.animate().alpha(1f).setDuration(Motion.LONG_MS).setInterpolator(Motion.EASE).start();
                fadeVolume(heroPlayer, previewVolume(HomeActivity.this), 1800);
                handler.removeCallbacks(heroEnd);
                handler.postDelayed(heroEnd, PREVIEW_MAX_MS);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (token == heroPrevToken && state == androidx.media3.common.Player.STATE_ENDED) stopHeroPreview();
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                if (token == heroPrevToken) stopHeroPreview();
            }
        });
        heroPlayer.setVideoTextureView(heroTex);
        heroPlayer.setMediaItem(mi);
        heroPlayer.setPlayWhenReady(false); // load now; play once the 3 seconds are up
        heroPlayer.prepare();
        heroPrevReady = true;
        if (heroPrevDue) heroPlayer.play();
    }

    private void releaseHeroPlayer() {
        if (heroPlayer != null) {
            heroPlayer.release();
            heroPlayer = null;
        }
        if (heroSession != null) {
            Plex.stopTranscode(heroSession);
            heroSession = null;
        }
    }

    private void stopHeroPreview() {
        exitHeroFull();
        heroPrevActive = false;
        heroPrevToken++;
        heroPrevDue = false;
        heroPrevReady = false;
        heroPrevShowing = false;
        handler.removeCallbacks(heroPush);
        if (bgDrift != null && bgDrift.isPaused() && pCard == null) bgDrift.resume();
        pushRows(false);
        handler.removeCallbacks(heroDue);
        handler.removeCallbacks(heroEnd);
        if (heroTex != null) heroTex.animate().cancel();
        if (heroTex != null) heroTex.setAlpha(0f);
        releaseHeroPlayer();
    }

    private int heroToken = 0;
    private boolean spotlightMode = true;
    private int spotIndex = 0;
    private Runnable pendingHero;

    private final Runnable spotTick = new Runnable() {
        @Override
        public void run() {
            if (spotlightMode && spotlight.size() > 1 && !heroPrevShowing) stepSpotlight(1);
            handler.postDelayed(this, SPOTLIGHT_MS);
        }
    };

    // ------------------------------------------------------- Lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Plex.init(this);
        Seerr.init(this);
        SoundFx.init(this);
        if (!Plex.isReady()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        // Netflix style "Who's watching?" when the app starts, unless Automatic sign in is on
        if (!Plex.profileChosen) {
            Plex.profileChosen = true;
            if (!getSharedPreferences("reel_ui", MODE_PRIVATE).getBoolean("autoSignIn", false)) {
                Intent pi = new Intent(this, ProfilesActivity.class);
                pi.putExtra("startup", true);
                startActivity(pi);
                finish();
                return;
            }
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;
        buildUi();
        load();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (loadedOnce && Plex.isReady()) {
            long away = android.os.SystemClock.uptimeMillis() - stoppedAt;
            if (away > 10 * 60_000L) {
                load(); // been away a while: rebuild (and put you back on your card)
            } else {
                // Back from a title: leave everything where it is and just update Up Next quietly.
                softRefresh();
                for (Row r : rows) {
                    if (r.title.equals("Your requests")) {
                        handler.removeCallbacks(requestsTick);
                        handler.postDelayed(requestsTick, 3000);
                    }
                }
                if (focusedCardRoot != null && focusedCardRoot.hasFocus() && focusedItem != null && previewsOn()) {
                    handler.postDelayed(previewStart, PREVIEW_PREPARE_MS);
                    handler.postDelayed(previewShow, PREVIEW_DELAY_MS);
                }
            }
        }
        TvRecommendations.refreshIfStale(this);
        if (spotlightMode) restartSpotlightTimer();
    }

    private long stoppedAt = 0;

    /** Updates progress on the Up Next row without moving anything or changing focus. */
    private void softRefresh() {
        if (tab != TAB_HOME) return;
        final int mine = loadToken;
        Plex.async(() -> Plex.items(Plex.paged("/library/onDeck", 20)), new Plex.Callback<List<Plex.Item>>() {
            @Override
            public void ok(List<Plex.Item> fresh) {
                if (mine != loadToken || isDestroyed()) return;
                int idx = -1;
                for (int i = 0; i < rows.size(); i++) if (rows.get(i).title.equals("Continue Watching")) { idx = i; break; }
                if (idx < 0) return;
                List<Plex.Item> cur = rows.get(idx).items;
                boolean same = cur.size() == fresh.size();
                for (int i = 0; same && i < cur.size(); i++) same = cur.get(i).key.equals(fresh.get(i).key);
                RecyclerView.ViewHolder vh = rowsView.findViewHolderForAdapterPosition(idx);
                boolean focusedHere = vh != null && vh.itemView.hasFocus();
                if (same) {
                    // Same titles in the same order: just update progress in place (focus stays put).
                    for (int i = 0; i < cur.size(); i++) {
                        Plex.Item a = cur.get(i);
                        Plex.Item b = fresh.get(i);
                        a.viewOffset = b.viewOffset;
                        a.duration = b.duration;
                        a.viewCount = b.viewCount;
                    }
                    if (vh instanceof RowVH && ((RowVH) vh).inner.getAdapter() != null) {
                        ((RowVH) vh).inner.getAdapter().notifyItemRangeChanged(0, cur.size());
                    }
                } else if (!focusedHere) {
                    cur.clear();
                    cur.addAll(fresh);
                    rowAdapter.notifyItemChanged(idx);
                }
                // If you're on that row and its order changed, it updates next time instead of jumping.
            }

            @Override
            public void fail(Exception e) {
            }
        });
    }

    // Where you were, so coming back from a title lands on the same card.
    private String restoreRow;
    private String restoreKey;
    private int restoreRowTop;
    private int restoreItemLeft;
    private int restoreTab = -1;
    private long restoredAt = 0;

    private void saveFocus() {
        restoreRow = null;
        for (int i = 0; i < rowsView.getChildCount(); i++) {
            View ch = rowsView.getChildAt(i);
            if (!ch.hasFocus()) continue;
            int pos = rowsView.getChildAdapterPosition(ch);
            RecyclerView.ViewHolder vh = rowsView.getChildViewHolder(ch);
            if (pos < 0 || pos >= rows.size() || !(vh instanceof RowVH)) return;
            RowVH r = (RowVH) vh;
            View f = r.inner.getFocusedChild();
            if (f == null) return;
            int ip = r.inner.getChildAdapterPosition(f);
            if (ip < 0 || ip >= rows.get(pos).items.size()) return;
            restoreRow = rows.get(pos).title;
            restoreKey = rows.get(pos).items.get(ip).key;
            restoreRowTop = ch.getTop() - rowsView.getPaddingTop();
            restoreItemLeft = f.getLeft() - r.inner.getPaddingLeft();
            restoreTab = tab;
            return;
        }
    }

    /** Puts focus back on the saved card if its row is loaded. Returns true if it did. */
    private boolean tryRestore() {
        if (restoreRow == null || restoreTab != tab) return false;
        int ri = -1;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).title.equals(restoreRow)) { ri = i; break; }
        if (ri < 0) return false;
        List<Plex.Item> items = rows.get(ri).items;
        if (items.isEmpty()) return false;
        int ii = 0; // if the title has gone from the row (e.g. finished), the start of the row
        for (int j = 0; j < items.size(); j++) if (items.get(j).key.equals(restoreKey)) { ii = j; break; }
        restoreRow = null;
        restoredAt = android.os.SystemClock.uptimeMillis();
        final int fri = ri;
        final int fii = ii;
        final int left = restoreItemLeft;
        ((LinearLayoutManager) rowsView.getLayoutManager()).scrollToPositionWithOffset(ri, restoreRowTop);
        spotlightMode = false;
        dots.setAlpha(0f);
        applyHero(items.get(ii), false);
        rowsView.post(() -> {
            RecyclerView.ViewHolder vh = rowsView.findViewHolderForAdapterPosition(fri);
            if (!(vh instanceof RowVH)) return;
            final RowVH r = (RowVH) vh;
            ((LinearLayoutManager) r.inner.getLayoutManager()).scrollToPositionWithOffset(fii, left);
            r.inner.post(() -> {
                RecyclerView.ViewHolder c = r.inner.findViewHolderForAdapterPosition(fii);
                if (c != null) c.itemView.requestFocus();
            });
        });
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Free the preview players' audio before another screen (the movie) opens its own
        stopHeroPreview();
        stopPreview();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stoppedAt = android.os.SystemClock.uptimeMillis();
        if (rowsView != null) saveFocus();
        handler.removeCallbacks(spotTick);
        handler.removeCallbacks(requestsTick);
        stopHeroPreview();
        stopPreview();
    }

    private void restartSpotlightTimer() {
        handler.removeCallbacks(spotTick);
        handler.postDelayed(spotTick, SPOTLIGHT_MS);
    }

    // -------------------------------------------------------------- UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        root.setBackgroundColor(UiUtil.BG);
        // A focused button grows past its container's edge; don't clip each child to its own box or the
        // growing button's rounded left end is cut flat
        root.setClipChildren(false);

        // Backdrop: two layers, newest on top
        bgBox = new FrameLayout(this);
        bgA = newBg();
        bgB = newBg();
        bgFront = bgA;
        bgA.setAlpha(1f);
        bgBox.addView(bgB);
        bgBox.addView(bgA);

        root.addView(bgBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        heroTex = new android.view.TextureView(this);
        heroTex.setAlpha(0f);
        root.addView(heroTex, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The three dark overlays (left, top, bottom) are painted together into one small picture that is
        // stretched over the screen. Blending three big gradients on every frame was the single biggest
        // cost of moving through the rows on this graphics chip.
        leftGrad = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xE6000000, 0x99000000, 0x00000000});
        GradientDrawable topGrad = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x88000000, 0x00000000});
        bottomGrad = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xB3000000, 0xFF000000});
        View scrims = new ScrimView(this, leftGrad, topGrad, bottomGrad);
        root.addView(scrims, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildHero(root);
        buildNav(root);

        // Rows of posters, starting about 60% of the way down
        rowsView = new RecyclerView(this);
        rowsView.setLayoutManager(new LinearLayoutManager(this));
        rowsView.setClipToPadding(false);
        rowsView.setClipChildren(false);
        // The rows live below the tab bar: anything scrolled above it is cut off (with a soft fade)
        // so nothing slides behind the bar.
        rowsView.setPadding(0, (int) (H * 0.60f - H * 0.13f), 0, (int) (H * 0.10f));
        rowsView.setVerticalFadingEdgeEnabled(false); // the fade drew dark bands over the raised cards
        rowsView.setItemAnimator(null);
        rowAdapter = new RowAdapter();
        rowsView.setAdapter(rowAdapter);
        rowsView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                // The spotlight card fades as the rows slide up over it.
                float a = 1f - Math.min(1f, rv.computeVerticalScrollOffset() / (H * 0.25f));
                heroOuter.setAlpha(a);
            }
        });
        FrameLayout.LayoutParams rowsLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        rowsLp.topMargin = (int) (H * 0.13f);
        root.addView(rowsView, rowsLp);

        status = new TextView(this);
        status.setTextColor(0xCCFFFFFF);
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
        status.setGravity(Gravity.CENTER);
        status.setFocusable(true);
        status.setPadding(60, 30, 60, 30);
        status.setVisibility(View.GONE);
        root.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        setContentView(root);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            if (focusedCardRoot != null && newF != focusedCardRoot) {
                focusedCardRoot = null;
                focusedCard = null;
                focusedItem = null;
                stopPreview();
            }
        });
        // The splash (logo on black) sits behind; fade the home screen in over it.
        root.setAlpha(0f);
        root.animate().alpha(1f).setStartDelay(120).setDuration(Motion.LONG_MS)
                .setInterpolator(Motion.EASE).start();
    }

    /**
     * The home screen's dark overlays: a shade down the left, a little at the top and a deep one up from the
     * bottom. They are drawn into a bitmap a fraction of the screen's size and scaled up, so the graphics chip
     * blends one picture instead of three screen-sized gradients. Changing a gradient's colours (the ambient
     * tint) repaints the picture.
     */
    private static final class ScrimView extends View {
        private static final int SHRINK = 5;
        private final GradientDrawable left;
        private final GradientDrawable top;
        private final GradientDrawable bottom;
        private final android.graphics.Canvas small = new android.graphics.Canvas();
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.Rect dst = new android.graphics.Rect();
        private android.graphics.Bitmap bmp;
        private boolean dirty = true;

        ScrimView(android.content.Context c, GradientDrawable left, GradientDrawable top, GradientDrawable bottom) {
            super(c);
            this.left = left;
            this.top = top;
            this.bottom = bottom;
            left.setCallback(this);
            top.setCallback(this);
            bottom.setCallback(this);
        }

        @Override
        protected boolean verifyDrawable(Drawable who) {
            return who == left || who == top || who == bottom || super.verifyDrawable(who);
        }

        @Override
        public void invalidateDrawable(Drawable who) {
            dirty = true;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            int bw = Math.max(1, w / SHRINK);
            int bh = Math.max(1, h / SHRINK);
            bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
            small.setBitmap(bmp);
            left.setBounds(0, 0, (int) (bw * 0.70f), bh);
            top.setBounds(0, 0, bw, (int) (bh * 0.22f));
            bottom.setBounds(0, (int) (bh * 0.40f), bw, bh);
            dirty = true;
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            if (bmp == null) return;
            if (dirty) {
                bmp.eraseColor(0);
                left.draw(small);
                top.draw(small);
                bottom.draw(small);
                dirty = false;
            }
            dst.set(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(bmp, null, dst, paint);
        }
    }

    private ImageView newBg() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAlpha(0f);
        v.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return v;
    }

    private void buildHero(FrameLayout root) {
        LinearLayout hero = new LinearLayout(this) {
            @Override
            public boolean hasOverlappingRendering() {
                return false; // fades without an offscreen layer
            }
        };
        hero.setOrientation(LinearLayout.VERTICAL);
        heroGroup = hero;

        FrameLayout logoBox = new FrameLayout(this);
        heroLogo = new ImageView(this);
        heroLogo.setScaleType(ImageView.ScaleType.FIT_START);
        heroLogo.setVisibility(View.GONE);
        logoBox.addView(heroLogo, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM | Gravity.START));
        heroTitle = new TextView(this);
        heroTitle.setTextColor(Color.WHITE);
        heroTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.065f);
        heroTitle.setTypeface(UiUtil.font(UiUtil.BOLD));
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroTitle.setShadowLayer(12, 0, 2, 0x99000000);
        logoBox.addView(heroTitle, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START));
        hero.addView(logoBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (H * 0.17f)));

        heroMeta = new TextView(this);
        heroMeta.setTextColor(UiUtil.TEXT2);
        heroMeta.setTypeface(UiUtil.font(UiUtil.MEDIUM));
        heroMeta.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.024f);
        heroMeta.setSingleLine(true);
        heroMeta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = (int) (H * 0.012f);
        hero.addView(heroMeta, metaLp);

        heroSummary = new TextView(this);
        heroSummary.setTextColor(0xD9FFFFFF);
        heroSummary.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.025f);
        heroSummary.setMaxLines(2);
        heroSummary.setEllipsize(TextUtils.TruncateAt.END);
        heroSummary.setLineSpacing(0, 1.1f);
        LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sumLp.topMargin = (int) (H * 0.012f);
        hero.addView(heroSummary, sumLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setClipChildren(false);
        buttons.setClipToPadding(false); // the left padding is room for the button to grow into, not a clip line
        buttons.setPadding((int) (W * 0.012f), 0, 0, 0);
        hero.setClipChildren(false);
        hero.setClipToPadding(false);
        // One button that opens the title's page. Left / Right on it steps through the spotlight.
        playBtn = makeButton("Go to movie", true);
        playBtn.setOnClickListener(v -> openDetail(current));
        playBtn.setOnLongClickListener(v -> enterHeroFull());
        playBtn.setOnKeyListener((v, code, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT) {
                stepSpotlight(-1);
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                stepSpotlight(1);
                return true;
            }
            return false;
        });
        buttons.addView(playBtn);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRowLp.topMargin = (int) (H * 0.025f);
        hero.addView(buttons, btnRowLp);

        // The outer box carries the scroll fade, the inner group carries the swap animation
        // The banner fades as the rows scroll over it: fade each piece directly instead of drawing the
        // whole banner to a separate layer first (that layer was redrawn on every scroll step)
        heroOuter = new FrameLayout(this) {
            @Override
            public boolean hasOverlappingRendering() {
                return false;
            }
        };
        heroOuter.setClipChildren(false);
        heroOuter.setClipToPadding(false);
        heroOuter.addView(hero, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams heroLp = new FrameLayout.LayoutParams(
                (int) (W * 0.42f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        heroLp.leftMargin = (int) (W * 0.05f);
        heroLp.topMargin = (int) (H * 0.14f);
        root.addView(heroOuter, heroLp);

        // Page dots, centred under the card
        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams dotsLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        dotsLp.topMargin = (int) (H * 0.565f);
        root.addView(dots, dotsLp);
    }

    private void buildNav(FrameLayout root) {
        final float navR = H * 0.05f;

        // Frosted glass: a heavily shrunk copy of the backdrop (so it looks blurred), clipped to the
        // bar and shifted so it lines up with the picture behind it, plus a light tint and edge.
        // The bar is sized by the tabs only. (Plain Views set to match_parent would otherwise
        // stretch a wrap_content parent to the whole screen.)
        final FrameLayout glass = new FrameLayout(this) {
            @Override
            protected void onMeasure(int wSpec, int hSpec) {
                View last = getChildAt(getChildCount() - 1);
                last.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                int w = last.getMeasuredWidth();
                int h = last.getMeasuredHeight();
                setMeasuredDimension(w, h);
                for (int i = 0; i < getChildCount(); i++) {
                    getChildAt(i).measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                }
            }
        };
        UiUtil.clipRounded(glass, navR);

        blurImg = new ImageView(this);
        blurImg.setVisibility(View.GONE); // no shrunk copy of the backdrop behind the bar any more
        blurImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        glass.addView(blurImg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        // A focused tab grows a little; clipping it to the padded area flattened its top and bottom
        nav.setClipChildren(false);
        nav.setClipToPadding(false);
        int pad = (int) (H * 0.008f);
        nav.setPadding(pad * 2, pad, pad * 2, pad);
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int index = i;
            TextView t = makeTab(TAB_NAMES[i]);
            t.setOnClickListener(v -> {
                SoundFx.click();
                if (index == 3) startActivity(new Intent(this, LibrariesActivity.class));
                else if (index == 4) startActivity(new Intent(this, SeerrActivity.class));
                else if (index == 5) startActivity(new Intent(this, LiveActivity.class));
                else if (index == 6) startActivity(new Intent(this, SearchActivity.class));
                else switchTab(index);
            });
            tabViews[i] = t;
            nav.addView(t);
        }
        glass.addView(nav, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        GradientDrawable edge = new GradientDrawable();
        edge.setColor(Color.TRANSPARENT);
        edge.setCornerRadius(navR);
        edge.setStroke(Math.max(1, (int) (H * 0.0015f)), 0x26FFFFFF);
        // Same look as the Windows app: a plain translucent dark bar (the real picture shows through)
        GradientDrawable tint = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8C1C1C1E, 0x8C1C1C1E});
        tint.setCornerRadius(navR);
        glass.setForeground(new android.graphics.drawable.LayerDrawable(
                new Drawable[]{tint, edge}));
        navTint = tint;
        navEdge = edge;
        navGlass = glass;
        glass.setElevation(H * 0.012f); // a soft shadow lifts the bar off busy pictures


        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        navLp.topMargin = (int) (H * 0.04f);
        root.addView(glass, navLp);
        styleTabs();
        // Coming up from the page onto the top bar lands on the tab you are on, not on the first one
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            if (newF == null || tab < 0 || tab >= tabViews.length || tabViews[tab] == null) return;
            if (!isNavTab(newF) || isNavTab(oldF) || oldF == cornerSettings || oldF == cornerMoon || newF == tabViews[tab]) return;
            final View target = tabViews[tab];
            handler.post(target::requestFocus);
        });

        LinearLayout corner = new LinearLayout(this);
        corner.setOrientation(LinearLayout.HORIZONTAL);
        corner.setGravity(Gravity.CENTER_VERTICAL);
        // The moon and Settings grow when focused; don't cut them off at their own edges
        corner.setClipChildren(false);
        corner.setClipToPadding(false);

        // Quiet mode: a small moon, easy to miss on purpose
        final TextView moon = makeTab(" ");
        cornerMoon = moon;
        final float moonSize = H * 0.03f;
        final MoonDrawable moonIcon = new MoonDrawable((int) moonSize);
        moon.setCompoundDrawablesRelative(moonIcon, null, null, null);
        moon.setCompoundDrawablePadding(0);
        moon.setPadding((int) (W * 0.010f), (int) (H * 0.012f), (int) (W * 0.010f), (int) (H * 0.012f));
        moon.setContentDescription("Quiet mode");
        final Runnable paintMoon = () -> {
            boolean q = quiet(this);
            moonIcon.set(moon.hasFocus() ? 0xFF000000 : q ? 0xFFFFE9A8 : 0xFFFFFFFF, q);
            moon.setAlpha(moon.hasFocus() ? 1f : q ? 1f : 0.45f);
        };
        final View.OnFocusChangeListener baseFocus = moon.getOnFocusChangeListener();
        moon.setOnFocusChangeListener((v, f) -> {
            if (baseFocus != null) baseFocus.onFocusChange(v, f);
            paintMoon.run();
        });
        moon.setOnClickListener(v -> {
            boolean now = !quiet(this);
            getSharedPreferences("reel_ui", MODE_PRIVATE).edit().putBoolean("quiet", now).apply();
            SoundFx.click();
            if (now) {
                stopPreview();
                stopHeroPreview();
            }
            paintMoon.run();
            android.widget.Toast.makeText(this, now ? "Quiet mode on" : "Quiet mode off",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        paintMoon.run();
        corner.addView(moon);

        TextView settings = makeTab("Settings");
        cornerSettings = settings;
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        settings.setAlpha(0.72f);
        corner.addView(settings);


        FrameLayout.LayoutParams soLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        soLp.topMargin = (int) (H * 0.045f);
        soLp.rightMargin = (int) (W * 0.03f);
        root.addView(corner, soLp);
    }

    /** A crescent moon (outlined faintly when quiet mode is off, filled when on). */
    private static final class MoonDrawable extends android.graphics.drawable.Drawable {
        private final int size;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private boolean filled;

        MoonDrawable(int size) {
            this.size = size;
            setBounds(0, 0, size, size);
        }

        void set(int color, boolean filled) {
            paint.setColor(color);
            this.filled = filled;
            invalidateSelf();
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            float r = size * 0.42f;
            float cx = size / 2f;
            float cy = size / 2f;
            android.graphics.Path moon = new android.graphics.Path();
            moon.addCircle(cx, cy, r, android.graphics.Path.Direction.CW);
            android.graphics.Path bite = new android.graphics.Path();
            bite.addCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.85f, android.graphics.Path.Direction.CW);
            moon.op(bite, android.graphics.Path.Op.DIFFERENCE);
            paint.setStyle(filled ? android.graphics.Paint.Style.FILL : android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, size * 0.09f));
            c.drawPath(moon, paint);
        }

        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
    }

    private static String aacLabel(boolean on) {
        return on ? "AAC \u2192 Dolby: On" : "AAC \u2192 Dolby: Off";
    }

    private static String audioLabel(int mode) {
        return mode == 0 ? "Audio: Passthrough" : "Audio: Compatible";
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
        applyButtonStyle(t, primary, false, r);
        t.setOnFocusChangeListener((v, focused) -> {
            applyButtonStyle(t, primary, focused, r);
            float s = focused ? 1.1f : 1f;
            Motion.focusScale(v, s);
            v.setElevation(focused ? H * 0.02f : 0f);
            if (focused) {
                SoundFx.move();
                enterSpotlight();
                maybeStartHeroPreview();
            }
        });
        return t;
    }

    private void applyButtonStyle(TextView t, boolean primary, boolean focused, float r) {
        UiUtil.stylePill(t, focused, r);
        if (primary && !focused) t.setBackground(UiUtil.rounded(0x4DFFFFFF, r));
    }

    private TextView makeTab(String label) {
        final TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.026f);
        t.setTypeface(UiUtil.font(UiUtil.MEDIUM));
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) (W * 0.016f), (int) (H * 0.012f), (int) (W * 0.016f), (int) (H * 0.012f));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnFocusChangeListener((v, focused) -> {
            float s = focused ? 1.06f : 1f;
            Motion.focusScale(v, s);
            if (focused) {
                SoundFx.move();
                // On the top bar nothing plays in the background: the quick way to get the app quiet
                stopPreview();
                stopHeroPreview();
                t.setBackground(UiUtil.rounded(0xFFFFFFFF, H * 0.05f));
                t.setTextColor(0xFF000000);
                t.setAlpha(1f);
            } else {
                t.setTextColor(Color.WHITE);
                styleTabs();
                if (t.getBackground() != null && !isSelectedTab(t)) t.setBackground(null);
            }
        });
        return t;
    }

    private boolean isNavTab(View v) {
        if (v == null) return false;
        for (TextView t : tabViews) if (t == v) return true;
        return false;
    }

    private boolean isSelectedTab(TextView t) {
        for (int i = 0; i < tabViews.length; i++) if (tabViews[i] == t) return i == tab;
        return false;
    }

    private void styleTabs() {
        for (int i = 0; i < TAB_NAMES.length; i++) {
            TextView t = tabViews[i];
            if (t == null || t.hasFocus()) continue;
            boolean selected = i == tab;
            t.setBackground(selected ? UiUtil.rounded(0x40FFFFFF, H * 0.05f) : null);
            t.setTextColor(Color.WHITE);
            t.setTypeface(UiUtil.font(selected ? UiUtil.SEMIBOLD : UiUtil.MEDIUM));
            t.setAlpha(selected ? 1f : 0.72f);
        }
    }

    // ---------------------------------------------------------- Loading

    private void switchTab(int index) {
        if (index == tab) return;
        restoreRow = null;
        tab = index;
        loadedOnce = false;
        styleTabs();
        // Rows slide down and fade while the new tab loads
        rowsView.animate().cancel();
        rowsView.animate().alpha(0f).translationY(H * 0.03f).setDuration(Motion.FAST_MS).setInterpolator(Motion.EASE).start();
        load();
    }

    private void load() {
        final int mine = ++loadToken;
        final int whichTab = tab;
        if (rows.isEmpty()) {
            status.setText("Loading…");
            status.setOnClickListener(null);
            status.setVisibility(View.VISIBLE);
        }

        final java.util.concurrent.Callable<Loaded> job = () -> {
            Loaded out = new Loaded();
            List<List<Plex.Item>> recents = new ArrayList<>();
            List<Plex.Section> allSections = Plex.sections();
            List<Plex.Section> sections = whichTab == TAB_MOVIES
                    ? Plex.movieSections(allSections) : Plex.visibleSections(allSections);

            if (whichTab == TAB_HOME) {
                List<Plex.Item> onDeck = Plex.items(Plex.paged("/library/onDeck", 20));
                if (!onDeck.isEmpty()) out.rows.add(new Row("Continue Watching", onDeck, true));

                // Recently Added comes straight after Continue Watching
                List<Plex.Item> recent = recentlyAdded(sections);
                if (!recent.isEmpty()) {
                    out.rows.add(new Row("Recently Added", recent, false));
                    recents.add(recent);
                }

                try {
                    List<Plex.Item> live = Plex.sessions();
                    if (!live.isEmpty()) out.rows.add(new Row("Currently Watching", live, true));
                } catch (Exception ignored) {
                    // Some accounts can't see server activity; skip the row.
                }
                // What you're partway through is worth a spot in the banner too
                if (!onDeck.isEmpty()) recents.add(onDeck);
            } else {
                String type = whichTab == TAB_MOVIES ? "movie" : "show";
                // Continue Watching: only this kind of thing (films here, episodes under TV Shows)
                try {
                    List<Plex.Item> deck = Plex.items(Plex.paged("/library/onDeck", 40));
                    List<Plex.Item> keep = new ArrayList<>();
                    for (Plex.Item d : deck) {
                        if (type.equals("movie") ? d.type.equals("movie") : d.type.equals("episode")) keep.add(d);
                    }
                    if (!keep.isEmpty()) out.rows.add(new Row("Continue Watching", keep, true));
                } catch (Exception ignored) {
                    // no Continue Watching row if the server won't say
                }
                List<Plex.Section> tabSections = new ArrayList<>();
                for (Plex.Section s : sections) {
                    if (whichTab == TAB_SHOWS && !s.type.equals("show")) continue; // Movies already uses its own list
                    tabSections.add(s);
                }
                List<Plex.Item> recent = recentlyAdded(tabSections);
                if (!recent.isEmpty()) {
                    out.rows.add(new Row("Recently Added", recent, false));
                    recents.add(recent);
                }
                // The whole library is a button (a tile that opens its page), not a row of thousands
                List<Plex.Item> tiles = new ArrayList<>();
                for (Plex.Section s : tabSections) {
                    Plex.Item t = new Plex.Item();
                    t.key = "lib:" + s.key;
                    t.type = "genre"; // drawn as a name tile
                    t.title = s.title.toLowerCase().startsWith("all ") ? s.title : "All " + s.title;
                    t.summary = s.type; // so the page knows what it's opening
                    try {
                        List<Plex.Item> one = Plex.items(Plex.paged(
                                "/library/sections/" + s.key + "/all?sort=random", 1));
                        if (!one.isEmpty()) t.thumb = one.get(0).art != null ? one.get(0).art : one.get(0).thumb;
                    } catch (Exception ignored) {
                        // no picture: the tile still opens
                    }
                    tiles.add(t);
                }
                if (!tiles.isEmpty()) out.rows.add(new Row("Libraries", tiles, true));
            }

            // Spotlight: the newest titles that have artwork, mixing libraries
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 24 && out.spotlight.size() < SPOTLIGHT_COUNT; i++) {
                for (List<Plex.Item> l : recents) {
                    if (i >= l.size() || out.spotlight.size() >= SPOTLIGHT_COUNT) continue;
                    Plex.Item it = l.get(i);
                    if (it.heroArt() != null && seen.add(it.key)) out.spotlight.add(it);
                }
            }
            // Fetch full details so titles can show as logos
            for (Plex.Item it : out.spotlight) {
                if (it.logo != null) continue;
                try {
                    it.logo = Plex.metadata(it.key).logo;
                } catch (Exception ignored) {
                    // keep the text title
                }
            }
            return out;
        };
        final Plex.Callback<Loaded> cb = new Plex.Callback<Loaded>() {
            @Override
            public void ok(Loaded result) {
                if (mine != loadToken || isDestroyed()) return;
                loadedOnce = true;
                rows.clear();
                rows.addAll(result.rows);
                spotlight.clear();
                spotlight.addAll(result.spotlight);
                rowAdapter.notifyDataSetChanged();

                boolean returning = restoreRow != null && restoreTab == tab;
                if (!returning) {
                    rowsView.setTranslationY(H * 0.03f);
                    rowsView.setAlpha(0f);
                    rowsView.animate().alpha(1f).translationY(0f).setDuration(Motion.MEDIUM_MS)
                            .setInterpolator(Motion.EASE).start();
                }

                if (rows.isEmpty()) {
                    showStatus("Nothing here yet.", null);
                    return;
                }
                status.setVisibility(View.GONE);
                if (whichTab == TAB_HOME) loadRequests(mine);
                if (whichTab == TAB_MOVIES || whichTab == TAB_HOME) loadGenres(mine, whichTab);
                loadExtras(mine, whichTab);

                spotIndex = 0;
                spotlightMode = true;
                buildDots();
                if (returning && tryRestore()) {
                    // back on the card you opened
                } else if (!spotlight.isEmpty()) {
                    applyHero(spotlight.get(0), false);
                    restartSpotlightTimer();
                    handler.postDelayed(() -> playBtn.requestFocus(), 250);
                } else {
                    applyHero(rows.get(0).items.get(0), false);
                    focusFirstPoster(3);
                }
            }

            @Override
            public void fail(Exception e) {
                if (mine != loadToken || isDestroyed()) return;
                String msg = String.valueOf(e.getMessage());
                if (msg.contains("401") && Plex.profileName != null && Plex.mainToken != null) {
                    // This profile isn't allowed on the server: go back to the main account and ask again
                    Plex.useMainAccount();
                    android.widget.Toast.makeText(HomeActivity.this,
                            "That profile can't open this server. Check its access in Plex.",
                            android.widget.Toast.LENGTH_LONG).show();
                    Intent back = new Intent(HomeActivity.this, ProfilesActivity.class);
                    back.putExtra("recover", true);
                    startActivity(back);
                    finish();
                    return;
                }
                if (msg.contains("401")) {
                    Plex.signOut();
                    startActivity(new Intent(HomeActivity.this, LoginActivity.class));
                    finish();
                    return;
                }
                if (!rows.isEmpty()) return; // keep what we have
                showStatus("Can't reach your Plex server.\nPress OK to try again.", () -> load());
            }
        };
        final boolean[] cached = {false, false}; // {showed the saved copy, the network answer is in}
        if (rows.isEmpty()) {
            // Open straight away on what was seen last time; the fresh answer replaces it below
            Plex.async(() -> Plex.cacheOnly(job), new Plex.Callback<Loaded>() {
                @Override
                public void ok(Loaded r) {
                    if (mine != loadToken || isDestroyed() || cached[1] || r.rows.isEmpty()) return;
                    cached[0] = true;
                    lastSig = signature(r);
                    cb.ok(r);
                }

                @Override
                public void fail(Exception e) {
                }
            });
        }
        Plex.async(job, new Plex.Callback<Loaded>() {
            @Override
            public void ok(Loaded r) {
                cached[1] = true;
                if (mine != loadToken || isDestroyed()) return;
                String sig = signature(r);
                if (cached[0] && sig.equals(lastSig)) return; // nothing changed since the saved copy
                lastSig = sig;
                cb.ok(r);
            }

            @Override
            public void fail(Exception e) {
                cb.fail(e);
            }
        });
    }

    private String lastSig = "";

    /** The rows and what's in them, so a refresh that changed nothing doesn't redraw the page. */
    private static String signature(Loaded r) {
        StringBuilder sb = new StringBuilder();
        for (Row row : r.rows) {
            sb.append(row.title).append('|');
            for (int i = 0; i < row.items.size() && i < 30; i++) sb.append(row.items.get(i).key).append(',');
            sb.append(';');
        }
        for (Plex.Item it : r.spotlight) sb.append(it.key).append('.');
        return sb.toString();
    }

    private final java.util.Map<String, Seerr.Media> requestMedia = new java.util.HashMap<>();

    /** Keeps the progress on "Your requests" cards current while the home screen is open. */
    private final Runnable requestsTick = new Runnable() {
        @Override
        public void run() {
            final int mine = loadToken;
            Plex.async(() -> Seerr.myRequests(12), new Plex.Callback<List<Seerr.Media>>() {
                @Override
                public void ok(List<Seerr.Media> list) {
                    if (mine != loadToken || isDestroyed()) return;
                    int idx = -1;
                    for (int i = 0; i < rows.size(); i++) if (rows.get(i).title.equals("Your requests")) idx = i;
                    if (idx < 0) return;
                    boolean active = false;
                    java.util.Map<String, Seerr.Media> fresh = new java.util.HashMap<>();
                    for (Seerr.Media m : list) fresh.put(m.key(), m);
                    List<Plex.Item> items = rows.get(idx).items;
                    for (Plex.Item it : items) {
                        Seerr.Media m = fresh.get(it.key);
                        if (m == null) continue;
                        requestMedia.put(m.key(), m);
                        it.watcher = requestLabel(m);
                        if (m.status == 2 || m.status == 3 || m.dlPercent >= 0) active = true;
                    }
                    // Update the cards in place so focus stays where it is.
                    RecyclerView.ViewHolder vh = rowsView.findViewHolderForAdapterPosition(idx);
                    if (vh instanceof RowVH && ((RowVH) vh).inner.getAdapter() != null) {
                        ((RowVH) vh).inner.getAdapter().notifyItemRangeChanged(0, items.size());
                    }
                    handler.postDelayed(requestsTick, active ? 20_000 : 120_000);
                }

                @Override
                public void fail(Exception e) {
                    handler.postDelayed(requestsTick, 120_000);
                }
            });
        }
    };

    private static String requestLabel(Seerr.Media m) {
        if (m.dlPercent >= 0) return "Downloading " + m.dlPercent + "%";
        String st = m.statusText();
        if (!st.isEmpty() && m.status >= 4) return st;
        String eta = m.eta();
        return !eta.isEmpty() ? eta : st;
    }

    /** Removes every row with this title, so a late second load can't leave a duplicate behind. */
    private void removeRows(String title) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (rows.get(i).title.equals(title)) {
                rows.remove(i);
                rowAdapter.notifyItemRemoved(i);
            }
        }
    }

    /** The "Libraries" buttons always sit at the very bottom, whatever was added after them. */
    private void keepLibrariesLast() {
        for (int i = 0; i < rows.size() - 1; i++) {
            if (!rows.get(i).title.equals("Libraries")) continue;
            View v = rowsView.getLayoutManager() == null ? null : rowsView.getLayoutManager().findViewByPosition(i);
            if (v != null && v.hasFocus()) return; // don't pull the row away from under the cursor
            Row r = rows.remove(i);
            rows.add(r);
            rowAdapter.notifyItemMoved(i, rows.size() - 1);
            return;
        }
    }

    /** "Your requests" from Seerr, placed just under Up Next. Skipped if Seerr isn't set up. */
    private void loadRequests(final int mine) {
        Seerr.init(this);
        if (!Seerr.configured()) return;
        Plex.async(() -> Seerr.myRequests(12), new Plex.Callback<List<Seerr.Media>>() {
            @Override
            public void ok(List<Seerr.Media> list) {
                if (mine != loadToken || isDestroyed() || list.isEmpty()) return;
                List<Plex.Item> items = new ArrayList<>();
                for (Seerr.Media m : list) {
                    requestMedia.put(m.key(), m);
                    Plex.Item it = m.toItem();
                    it.watcher = requestLabel(m);
                    items.add(it);
                }
                removeRows("Your requests");
                int at = 0;
                for (int i = 0; i < rows.size(); i++) {
                    String t = rows.get(i).title;
                    if (t.equals("Continue Watching") || t.equals("Currently Watching")
                            || t.startsWith("Recently Added")) at = i + 1;
                }
                rows.add(at, new Row("Your requests", items, true));
                rowAdapter.notifyItemInserted(at);
                keepLibrariesLast();
                if (!rowsView.hasFocus() && android.os.SystemClock.uptimeMillis() - restoredAt > 3000
                        && rowsView.getLayoutManager() instanceof LinearLayoutManager) {
                    ((LinearLayoutManager) rowsView.getLayoutManager()).scrollToPositionWithOffset(0, 0);
                }
                tryRestore();
                handler.removeCallbacks(requestsTick);
                handler.postDelayed(requestsTick, 20_000);
            }

            @Override
            public void fail(Exception e) {
                // Seerr unreachable: just leave the row out.
            }
        });
    }

    /**
     * Second pass, so the screen appears quickly: "Because you watched", "Unwatched", genre and
     * collection rows. Each is optional; anything the server can't answer is skipped.
     */
    /** A row of the library's titles with this person in them, unwatched ones first; skipped if there are fewer than 4. */
    private static void addPersonRow(List<Row> out, String sectionKey, String prefix, Plex.Cast who, String exceptKey) {
        if (who == null) return;
        try {
            List<Plex.Item> list = Plex.items(Plex.paged("/library/sections/" + sectionKey + "/all?" + who.filter
                    + "&sort=year:desc", 40));
            list.removeIf(x -> x.key.equals(exceptKey));
            List<Plex.Item> sorted = new ArrayList<>();
            for (Plex.Item x : list) if (!x.isWatched()) sorted.add(x);
            for (Plex.Item x : list) if (x.isWatched()) sorted.add(x);
            if (sorted.size() >= 4) out.add(new Row(prefix + who.name, sorted, false));
        } catch (Exception ignored) {
        }
    }

    private void loadExtras(final int mine, final int whichTab) {
        Plex.async(() -> {
            List<Row> out = new ArrayList<>();
            java.util.Random rnd = new java.util.Random();
            List<Plex.Section> allSections = Plex.sections();
            List<Plex.Section> sections = whichTab == TAB_MOVIES
                    ? Plex.movieSections(allSections) : Plex.visibleSections(allSections);
            int collRows = whichTab == TAB_HOME ? 2 : 3;

            // One "Because you watched" and one "Unwatched" row for movies, and the same for shows,
            // each drawing on every library ticked in Settings.
            List<Plex.Section> movieLibs = new ArrayList<>();
            List<Plex.Section> showLibs = new ArrayList<>();
            for (Plex.Section s : sections) {
                // Every library on the Movies tab counts as movies, whatever Plex calls it
                if (whichTab == TAB_MOVIES || s.type.equals("movie")) movieLibs.add(s);
                else if (s.type.equals("show")) showLibs.add(s);
            }
            if (whichTab == TAB_SHOWS) movieLibs.clear();
            for (int pass = 0; pass < 2; pass++) {
                boolean movie = pass == 0;
                List<Plex.Section> group = movie ? movieLibs : showLibs;
                if (group.isEmpty()) continue;

                try {
                    List<Plex.Item> last = new ArrayList<>();
                    for (Plex.Section s : group) {
                        last.addAll(Plex.items(Plex.paged(
                                "/library/sections/" + s.key + "/all?sort=lastViewedAt:desc", 10)));
                    }
                    java.util.Collections.sort(last, (a, b) -> Long.compare(b.lastViewedAt, a.lastViewedAt));
                    for (Plex.Item w : last) {
                        // A show counts as watched once any episode has been
                        if (w.viewCount <= 0 && w.viewOffset <= 0 && w.viewedLeafCount <= 0) continue;
                        List<Plex.Item> like = new ArrayList<>();
                        for (Plex.Item x : Plex.related(w.key)) {
                            if (x.viewCount == 0 && x.viewedLeafCount == 0 && like.size() < 20) like.add(x); // only things you haven't seen
                        }
                        if (like.size() < 4) continue; // try the next thing you watched
                        out.add(new Row("Because you watched " + w.displayTitle(), like, false));
                        break;
                    }
                    // ... and rows for the lead actor and the director of something you watched lately
                    if (movie) {
                        for (Plex.Item w : last) {
                            if (w.viewCount <= 0) continue;
                            String sec = w.librarySectionID > 0 ? String.valueOf(w.librarySectionID) : group.get(0).key;
                            Plex.Item full = Plex.metadata(w.key);
                            addPersonRow(out, sec, "More with ", full.cast.isEmpty() ? null : full.cast.get(0), w.key);
                            addPersonRow(out, sec, "Directed by ", full.directors.isEmpty() ? null : full.directors.get(0), w.key);
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }

                // Unwatched, shuffled, from all of them together
                try {
                    List<Plex.Item> un = new ArrayList<>();
                    for (Plex.Section s : group) {
                        un.addAll(Plex.items(Plex.paged(
                                "/library/sections/" + s.key + "/all?unwatched=1&sort=random", 30)));
                    }
                    java.util.Collections.shuffle(un, rnd);
                    if (un.size() > 30) un = new ArrayList<>(un.subList(0, 30));
                    if (un.size() >= 3) out.add(new Row(movie ? "Unwatched Movies" : "Unwatched Shows", un, false));
                } catch (Exception ignored) {
                }
            }

            for (Plex.Section s : sections) {
                boolean movie = whichTab == TAB_MOVIES || s.type.equals("movie");
                boolean show = whichTab != TAB_MOVIES && s.type.equals("show");
                if (!movie && !show) continue;
                if (whichTab == TAB_SHOWS && !show) continue;

                // Collections
                try {
                    List<String[]> cols = Plex.collections(s.key);
                    java.util.Collections.shuffle(cols, rnd);
                    int added = 0;
                    for (String[] c : cols) {
                        if (added >= collRows) break;
                        if (Integer.parseInt(c[2]) < 3) continue;
                        List<Plex.Item> kids = Plex.items(Plex.paged("/library/collections/" + c[1] + "/children", 60));
                        if (kids.size() < 3) continue;
                        out.add(new Row(c[0], kids, false));
                        added++;
                    }
                } catch (Exception ignored) {
                }
            }
            return out;
        }, new Plex.Callback<List<Row>>() {
            @Override
            public void ok(List<Row> all) {
                if (mine != loadToken || isDestroyed() || all.isEmpty()) return;
                // Drop a row when most of its titles are already on the screen in a row above it
                Set<String> onScreen = new HashSet<>();
                for (Row r : rows) {
                    if (r.title.equals("Genres") || r.title.equals("Libraries")) continue;
                    for (Plex.Item it : r.items) onScreen.add(it.key);
                }
                List<Row> extra = new ArrayList<>();
                for (Row r : all) {
                    int dup = 0;
                    for (Plex.Item it : r.items) if (onScreen.contains(it.key)) dup++;
                    if (dup * 2 > r.items.size()) continue;
                    for (Plex.Item it : r.items) onScreen.add(it.key);
                    extra.add(r);
                }
                if (extra.isEmpty()) return;
                // "Because you watched" and "Unwatched" go near the top; genres and collections
                // further down (before the long "All" list).
                List<Row> top = new ArrayList<>();
                List<Row> rest = new ArrayList<>();
                for (Row r : extra) {
                    if (r.title.startsWith("Because you watched")
                            || r.title.startsWith("More with ") || r.title.startsWith("Directed by ")) top.add(r);
                    else rest.add(r);
                }
                // "Unwatched" goes all the way down, after the collections (just above the Libraries buttons)
                List<Row> unwatched = new ArrayList<>();
                for (Row r : rest) if (r.title.startsWith("Unwatched")) unwatched.add(r);
                rest.removeAll(unwatched);
                rest.addAll(unwatched);

                // Never slip rows in above where you are (that hid them off the top of the list).
                int focusedRow = -1;
                for (int i = 0; i < rowsView.getChildCount(); i++) {
                    View ch = rowsView.getChildAt(i);
                    if (ch.hasFocus()) focusedRow = rowsView.getChildAdapterPosition(ch);
                }
                boolean inRows = focusedRow >= 0;

                int topAt = 0;
                for (int i = 0; i < rows.size(); i++) {
                    String t = rows.get(i).title;
                    if (t.equals("Continue Watching") || t.equals("Currently Watching") || t.equals("Your requests")
                            || t.equals("Recently Added")) {
                        topAt = i + 1;
                    }
                }
                if (inRows) topAt = Math.max(topAt, focusedRow + 1);
                if (!top.isEmpty()) {
                    rows.addAll(topAt, top);
                    rowAdapter.notifyItemRangeInserted(topAt, top.size());
                }

                int restAt = rows.size();
                for (int i = 0; i < rows.size(); i++) {
                    if (rows.get(i).title.equals("Libraries")) { restAt = i; break; } // keep the library buttons last
                }
                if (inRows) restAt = Math.max(restAt, focusedRow + 1 + top.size());
                if (!rest.isEmpty()) {
                    rows.addAll(restAt, rest);
                    rowAdapter.notifyItemRangeInserted(restAt, rest.size());
                }

                // Still at the top of the page: make sure the new rows are in view, not above it.
                boolean justRestored = android.os.SystemClock.uptimeMillis() - restoredAt < 3000;
                if (!inRows && !justRestored && rowsView.getLayoutManager() instanceof LinearLayoutManager) {
                    ((LinearLayoutManager) rowsView.getLayoutManager()).scrollToPositionWithOffset(0, 0);
                }
                keepLibrariesLast();
                tryRestore(); // coming back to a card in one of these rows
            }

            @Override
            public void fail(Exception e) {
            }
        });
    }

    /** The newest titles across all of these libraries together, newest first. */
    private static List<Plex.Item> recentlyAdded(List<Plex.Section> libs) throws Exception {
        List<Plex.Item> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Plex.Section s : libs) {
            for (Plex.Item it : Plex.items(Plex.paged("/library/sections/" + s.key + "/all?sort=addedAt:desc", 40))) {
                if (seen.add(it.key)) all.add(it);
            }
        }
        java.util.Collections.sort(all, (a, b) -> Long.compare(b.addedAt, a.addedAt));
        return all.size() > 60 ? new ArrayList<>(all.subList(0, 60)) : all;
    }

    /** Genre card key -> the Plex paths holding that genre's titles (one per movie library). */
    private final java.util.Map<String, List<String>> genrePaths = new java.util.HashMap<>();

    private static boolean isGenre(Plex.Item it) {
        return it != null && "genre".equals(it.type);
    }

    /**
     * The Movies tab's "Genres" row: one card per genre across every movie library, each with a
     * picture from a title in it. Opening a card browses that genre in all those libraries.
     */
    private void loadGenres(final int mine, final int whichTab) {
        Plex.async(() -> {
            java.util.Map<String, String> names = new java.util.TreeMap<>();
            java.util.Map<String, List<String>> paths = new java.util.HashMap<>();
            List<Plex.Section> libs = new ArrayList<>();
            if (whichTab == TAB_MOVIES) {
                libs.addAll(Plex.movieSections(Plex.sections()));
            } else {
                // Home: every movie and TV library ticked for Home, genres merged by name
                libs.addAll(Plex.visibleSections(Plex.sections()));
            }
            for (Plex.Section s : libs) {
                try {
                    for (String[] g : Plex.genres(s.key)) {
                        String k = g[0].toLowerCase();
                        names.putIfAbsent(k, g[0]);
                        List<String> list = paths.get(k);
                        if (list == null) paths.put(k, list = new ArrayList<>());
                        list.add(g[1]);
                    }
                } catch (Exception ignored) {
                    // a library that won't list its genres is just left out
                }
            }
            List<Plex.Item> cards = new ArrayList<>();
            java.util.Map<String, List<String>> keep = new java.util.HashMap<>();
            for (java.util.Map.Entry<String, String> e : names.entrySet()) {
                List<String> p = paths.get(e.getKey());
                Plex.Item card = new Plex.Item();
                card.key = "genre:" + e.getKey();
                card.type = "genre";
                card.title = e.getValue();
                try {
                    String first = p.get(0);
                    List<Plex.Item> one = Plex.items(Plex.paged(
                            first + (first.contains("?") ? "&" : "?") + "sort=random", 1));
                    if (one.isEmpty() && p.size() == 1) continue; // an empty genre
                    if (!one.isEmpty()) card.thumb = one.get(0).art != null ? one.get(0).art : one.get(0).thumb;
                } catch (Exception ignored) {
                    // no picture: the card still opens
                }
                keep.put(card.key, p);
                cards.add(card);
            }
            return new Object[]{cards, keep};
        }, new Plex.Callback<Object[]>() {
            @Override
            @SuppressWarnings("unchecked")
            public void ok(Object[] result) {
                List<Plex.Item> cards = (List<Plex.Item>) result[0];
                if (mine != loadToken || isDestroyed() || tab != whichTab || cards.isEmpty()) return;
                genrePaths.putAll((java.util.Map<String, List<String>>) result[1]);

                // Just under Continue Watching and Recently Added, and never above where you are
                removeRows("Genres");
                int at = 0;
                for (int i = 0; i < rows.size(); i++) {
                    String t = rows.get(i).title;
                    if (t.equals("Continue Watching") || t.startsWith("Recently Added")) at = i + 1;
                }
                for (int i = 0; i < rowsView.getChildCount(); i++) {
                    View ch = rowsView.getChildAt(i);
                    if (ch.hasFocus()) at = Math.max(at, rowsView.getChildAdapterPosition(ch) + 1);
                }
                at = Math.min(at, rows.size());
                rows.add(at, new Row("Genres", cards, true));
                rowAdapter.notifyItemInserted(at);
                keepLibrariesLast();
                tryRestore();
            }

            @Override
            public void fail(Exception e) {
            }
        });
    }

    private void showStatus(String text, @Nullable final Runnable retry) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
        if (retry != null) {
            status.setOnClickListener(v -> retry.run());
            status.requestFocus();
        } else {
            status.setOnClickListener(null);
        }
    }

    private void focusFirstPoster(final int attempts) {
        handler.postDelayed(() -> {
            if (isDestroyed()) return;
            RecyclerView.ViewHolder vh = rowsView.findViewHolderForAdapterPosition(0);
            if (vh instanceof RowVH) {
                View first = ((RowVH) vh).inner.getChildAt(0);
                if (first != null) {
                    first.requestFocus();
                    return;
                }
            }
            if (attempts > 0) focusFirstPoster(attempts - 1);
        }, 300);
    }

    // -------------------------------------------------------- Spotlight

    private void buildDots() {
        dots.removeAllViews();
        if (spotlight.size() < 2) return;
        for (int i = 0; i < spotlight.size(); i++) {
            View d = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 0);
            lp.leftMargin = (int) (H * 0.007f);
            lp.rightMargin = (int) (H * 0.007f);
            dots.addView(d, lp);
        }
        updateDots();
    }

    private void updateDots() {
        int small = (int) (H * 0.011f);
        int wide = (int) (H * 0.032f);
        for (int i = 0; i < dots.getChildCount(); i++) {
            View d = dots.getChildAt(i);
            boolean active = i == spotIndex;
            ViewGroup.LayoutParams lp = d.getLayoutParams();
            lp.width = active ? wide : small;
            lp.height = small;
            d.setLayoutParams(lp);
            d.setBackground(UiUtil.rounded(active ? 0xFFFFFFFF : 0x80FFFFFF, small));
        }
    }

    private void stepSpotlight(int delta) {
        int n = spotlight.size();
        if (n < 2) return;
        spotIndex = (spotIndex + delta + n) % n;
        updateDots();
        if (spotlightMode) applyHero(spotlight.get(spotIndex), true);
        restartSpotlightTimer();
    }

    /** Focus came back to the card's buttons: show the spotlight title again. */
    private void enterSpotlight() {
        if (pendingHero != null) handler.removeCallbacks(pendingHero);
        if (spotlightMode || spotlight.isEmpty()) return;
        spotlightMode = true;
        dots.animate().alpha(1f).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.EASE).start();
        applyHero(spotlight.get(spotIndex), true);
        restartSpotlightTimer();
    }

    // -------------------------------------------------------------- Hero

    @Override
    public void onFocused(final Plex.Item item) {
        if (pendingHero != null) handler.removeCallbacks(pendingHero);
        if (isGenre(item)) return; // the banner keeps the last title
        pendingHero = () -> {
            if (spotlightMode) {
                spotlightMode = false;
                dots.animate().alpha(0f).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.EASE).start();
            }
            applyHero(item, true);
        };
        handler.postDelayed(pendingHero, 350);
    }

    @Override
    public void onClicked(Plex.Item item) {
        openDetail(item);
    }

    // ---- Ambient colour: the shading follows the colours of whichever poster is focused ----
    private GradientDrawable leftGrad;
    private GradientDrawable bottomGrad;
    private int ambientCur = 0;
    private String ambientFor;
    private android.animation.ValueAnimator ambientAnim;
    private final java.util.HashMap<String, Integer> ambientCache = new java.util.HashMap<>();

    private void ambientFor(final Plex.Item it) {
        final String path = it.poster();
        if (path == null || leftGrad == null || path.equals(ambientFor)) return;
        ambientFor = path;
        Integer known = ambientCache.get(path);
        if (known != null) {
            ambientTo(known);
            return;
        }
        Glide.with(this).asBitmap().load(Plex.img(path, 48, 72))
                .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>(48, 72) {
                    @Override
                    public void onResourceReady(android.graphics.Bitmap bmp,
                                                com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> t) {
                        if (isDestroyed()) return;
                        int c = DetailActivity.dominantColour(bmp);
                        if (c == 0) return;
                        ambientCache.put(path, c);
                        if (path.equals(ambientFor)) ambientTo(c);
                    }

                    @Override
                    public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
                    }
                });
    }

    private void ambientTo(final int target) {
        if (ambientAnim != null) ambientAnim.cancel();
        final int from = ambientCur != 0 ? ambientCur : target;
        ambientCur = target;
        ambientAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        ambientAnim.setDuration(Motion.LONG_MS * 2);
        ambientAnim.setInterpolator(Motion.EASE);
        ambientAnim.addUpdateListener(a -> {
            int c = (Integer) new android.animation.ArgbEvaluator().evaluate(a.getAnimatedFraction(), from, target);
            leftGrad.setColors(new int[]{DetailActivity.shade(c, 0.17f, 0xE6), DetailActivity.shade(c, 0.20f, 0x99),
                    DetailActivity.shade(c, 0.20f, 0x00)});
            bottomGrad.setColors(new int[]{DetailActivity.shade(c, 0.14f, 0x00), DetailActivity.shade(c, 0.12f, 0xB3),
                    DetailActivity.shade(c, 0.07f, 0xFF)});
        });
        ambientAnim.start();
    }

    private void applyHero(final Plex.Item it, boolean animate) {
        ambientFor(it);
        current = it;
        final int token = ++heroToken;
        stopHeroPreview();
        if (spotlightMode) startHeroPreview(it);

        // 1280x720 is plenty behind the dark overlays and costs about half the graphics memory traffic
        setBackdrop(Plex.img(it.heroArt() != null ? it.heroArt() : it.poster(), 1280, 720));

        heroGroup.animate().cancel();
        if (animate) {
            heroGroup.animate().alpha(0f).translationY(H * 0.012f).setDuration(Motion.FAST_MS).setInterpolator(Motion.EASE)
                    .withEndAction(() -> {
                        if (token != heroToken) return;
                        bindHeroText(it);
                        heroGroup.animate().alpha(1f).translationY(0f).setDuration(Motion.MEDIUM_MS)
                                .setInterpolator(Motion.EASE).start();
                    }).start();
        } else {
            bindHeroText(it);
            heroGroup.setAlpha(1f);
            heroGroup.setTranslationY(0f);
        }
    }

    private void bindHeroText(final Plex.Item it) {
        heroTitle.setText(it.displayTitle());
        heroTitle.setVisibility(View.VISIBLE);
        heroLogo.setVisibility(View.GONE);
        heroMeta.setText(it.metaLine());
        heroSummary.setText(it.summary);
        playBtn.setText(isRequest(it) ? "View request"
                : it.type.equals("show") ? "Go to show"
                : it.type.equals("episode") ? "Go to episode"
                : it.type.equals("movie") ? "Go to movie" : "Go to title");

        if (it.logo != null) {
            // Show the title as a logo when the server has one; keep the text if loading fails.
            Glide.with(this).load(Plex.raw(it.logo))
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                    Target<Drawable> target, boolean isFirstResource) {
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(Drawable resource, Object model,
                                                       Target<Drawable> target, DataSource dataSource,
                                                       boolean isFirstResource) {
                            if (current == it) {
                                heroLogo.setVisibility(View.VISIBLE);
                                heroTitle.setVisibility(View.GONE);
                            }
                            return false;
                        }
                    })
                    .into(heroLogo);
        }
    }

    /** Cross-fades to a new backdrop with a slow zoom, and updates the frosted bar's copy. */
    private void setBackdrop(String url) {
        if (url == null) return;
        final ImageView next = bgFront == bgA ? bgB : bgA;
        final ImageView old = bgFront;

        Glide.with(this).load(url).centerCrop().format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                .listener(new RequestListener<Drawable>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                        Target<Drawable> target, boolean isFirstResource) {
                return false;
            }

            @Override
            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target,
                                           DataSource dataSource, boolean isFirstResource) {
                next.post(() -> crossfadeBackdrop(next, old));
                return false;
            }
        }).into(next);

        Glide.with(this).load(url).override(48, 27).centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade(350))
                .into(blurImg);

        // Look at how bright the picture is behind the tab bar and adjust the bar to stay readable.
        Glide.with(this).asBitmap().load(url).override(48, 27).centerCrop()
                .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                    @Override
                    public void onResourceReady(android.graphics.Bitmap bmp,
                            @Nullable com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> t) {
                        adaptNav(topBrightness(bmp));
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                    }
                });
    }

    private GradientDrawable navTint;
    private GradientDrawable navEdge;
    private View navGlass;
    private TextView cornerSettings;
    private TextView cornerMoon;
    private int[] navColors = {0x8C1C1C1E, 0x8C1C1C1E};
    private android.animation.ValueAnimator navAnim;

    /** Average brightness (0 dark .. 1 bright) of the top middle of the picture, where the bar sits. */
    private static float topBrightness(android.graphics.Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        double sum = 0;
        int n = 0;
        for (int y = 0; y < Math.max(1, h / 4); y++) {
            for (int x = w / 5; x < w - w / 5; x++) {
                int c = b.getPixel(x, y);
                sum += (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0;
                n++;
            }
        }
        return n == 0 ? 0f : (float) (sum / n);
    }

    /** Darker, stronger glass over bright pictures; light frosted glass over dark ones. */
    private void adaptNav(float lum) {
        if (navTint == null || isDestroyed()) return;
        final int[] to;
        int edgeColor;
        float shadow;
        // Same bar over every picture (like Windows); only the text shadow grows over bright ones
        to = new int[]{0x8C1C1C1E, 0x8C1C1C1E};
        edgeColor = 0x26FFFFFF;
        shadow = lum > 0.6f ? 8f : lum > 0.35f ? 6f : 0f;
        navEdge.setStroke(Math.max(1, (int) (H * 0.0015f)), edgeColor);
        for (TextView t : tabViews) if (t != null) t.setShadowLayer(shadow, 0, shadow / 3f, 0xCC000000);
        if (cornerSettings != null) cornerSettings.setShadowLayer(Math.max(shadow, 6f), 0, 2f, 0xCC000000);
        final int[] from = navColors.clone();
        if (navAnim != null) navAnim.cancel();
        navAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        navAnim.setDuration(Motion.SLOW_MS).setInterpolator(Motion.EASE);
        final android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
        navAnim.addUpdateListener(a -> {
            float f = (Float) a.getAnimatedValue();
            navColors = new int[]{(Integer) ev.evaluate(f, from[0], to[0]), (Integer) ev.evaluate(f, from[1], to[1])};
            navTint.setColors(navColors);
            if (navGlass != null) navGlass.invalidate();
        });
        navAnim.start();
    }

    private void crossfadeBackdrop(final ImageView next, final ImageView old) {
        next.bringToFront();
        next.setScaleX(1f);
        next.setScaleY(1f);
        next.setAlpha(0f);
        bgFront = next;
        next.animate().cancel();
        next.animate().alpha(1f).setDuration(Motion.SLOW_MS).setInterpolator(Motion.EASE).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (bgFront == next) old.setAlpha(0f);
                next.animate().setListener(null);
            }
        }).start();

        // (The slow zoom drift is gone: it kept the whole screen redrawing every frame, even when nothing moved.)
        if (bgDrift != null) bgDrift.cancel();
        bgDrift = null;
    }

    private boolean previewFullscreen;

    /** Holding OK on a card whose preview is playing: just the preview, full screen. */
    @Override
    public boolean onLongClicked(Plex.Item item) {
        if (pCard == null || (previewPlayer == null && ytView == null) || !previewShowing || item != focusedItem) {
            return false;
        }
        final FrameLayout c = pCard;
        handler.removeCallbacks(previewEnd);
        if (previewIsClip) handler.postDelayed(previewEnd, 90_000); // a clip of the film still stops
        previewFullscreen = true;
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
        android.animation.ObjectAnimator grow = android.animation.ObjectAnimator.ofPropertyValuesHolder(c,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, c.getScaleX(), s),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, c.getScaleY(), s),
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_X, c.getTranslationX(),
                        W / 2f - (lp.leftMargin + cw / 2f)),
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, c.getTranslationY(),
                        H / 2f - (lp.topMargin + chh / 2f)));
        grow.setDuration(Motion.SLOW_MS);
        grow.setInterpolator(Motion.EASE);
        grow.start();
        fadeVolume(previewPlayer, previewVolume(this), 700);
        setYoutubeVolume(previewVolume(this));
        return true;
    }

    private boolean previewPaused;

    private void resumePreviewIfPaused() {
        if (!previewPaused) return;
        previewPaused = false;
        if (previewPlayer != null) previewPlayer.setPlayWhenReady(true);
        YouTubeEmbed.pause(ytView, false);
    }

    /** Back to the card in its row. */
    private void shrinkPreview() {
        previewFullscreen = false;
        final FrameLayout c = pCard;
        if (c == null) return;
        handler.removeCallbacks(previewEnd);
        handler.postDelayed(previewEnd, PREVIEW_MAX_MS);
        c.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).rotationY(0f)
                .setDuration(Motion.MEDIUM_MS).setInterpolator(Motion.EASE)
                .withEndAction(() -> UiUtil.clipRounded(c, H * 0.016f)).start();
        for (int i = 0; i < c.getChildCount(); i++) {
            View ch = c.getChildAt(i);
            if ("cardText".equals(ch.getTag())) ch.animate().alpha(1f).setDuration(Motion.MEDIUM_MS).setInterpolator(Motion.EASE).start();
        }
        fadeVolume(previewPlayer, previewVolume(this), 500);
        setYoutubeVolume(previewVolume(this));
    }

    // Back that was used up closing a full-screen preview: its key-up must not also jump to the top bar
    private boolean swallowBackUp;

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP && swallowBackUp) {
            swallowBackUp = false;
            return true;
        }
        // Up on the top bar stays on the tab you are on (it used to wander off to the moon / Settings)
        if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP && !heroFull && !previewFullscreen) {
            View f = getCurrentFocus();
            if (isNavTab(f) || f == cornerMoon || f == cornerSettings) return true;
        }
        if (heroFull) {
            // Full-screen spotlight preview: OK opens the title, anything else goes back to the page
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                int k = e.getKeyCode();
                boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER;
                if (k == KeyEvent.KEYCODE_BACK) swallowBackUp = true;
                exitHeroFull();
                if (ok) openDetail(current);
            }
            return true;
        }
        // Back from anywhere on the page goes up to the top bar (on the tab you are on), silent;
        // Back on the top bar behaves as before.
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && !previewFullscreen && tabViews[tab] != null) {
            boolean onBar = (cornerSettings != null && cornerSettings.hasFocus()) || (cornerMoon != null && cornerMoon.hasFocus());
            for (TextView tv : tabViews) if (tv != null && tv.hasFocus()) onBar = true;
            if (!onBar) {
                if (e.getAction() == KeyEvent.ACTION_UP) {
                    stopPreview();
                    stopHeroPreview();
                    if (rowsView != null) rowsView.smoothScrollToPosition(0);
                    tabViews[tab].requestFocus();
                }
                return true;
            }
        }
        if (previewFullscreen) {
            // OK opens the title card; while the preview is paused it resumes instead.
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                int k = e.getKeyCode();
                boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                        || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
                if (ok) {
                    if (previewPaused) {
                        previewPaused = false;
                        if (previewPlayer != null) previewPlayer.setPlayWhenReady(true);
                        YouTubeEmbed.pause(ytView, false);
                        handler.removeCallbacks(previewEnd);
                        if (previewIsClip) handler.postDelayed(previewEnd, 90_000);
                        android.widget.Toast.makeText(this, "Playing", android.widget.Toast.LENGTH_SHORT).show();
                    } else if (focusedItem != null) {
                        fadeExpandedDetailsIn();
                        expandAndOpen(focusedItem);
                    }
                } else {
                    if (k == KeyEvent.KEYCODE_BACK) swallowBackUp = true;
                    resumePreviewIfPaused();
                    shrinkPreview();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void fadeExpandedDetailsIn() {
        final FrameLayout c = pCard;
        if (c == null) return;
        for (int i = 0; i < c.getChildCount(); i++) {
            View ch = c.getChildAt(i);
            if ("cardText".equals(ch.getTag())) {
                ch.animate().cancel();
                ch.setAlpha(0.15f);
                ch.animate().alpha(1f).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.EASE).start();
            }
        }
    }

    /** The grown card fills the screen, then the title page opens with the video still playing. */
    private void expandAndOpen(final Plex.Item it) {
        final FrameLayout c = pCard;
        if (c == null || it == null) return;
        handler.removeCallbacks(previewEnd);
        handler.removeCallbacks(previewStart);
        previewToken++;
        for (int i = 0; i < c.getChildCount(); i++) {
            View ch = c.getChildAt(i);
            if ("cardText".equals(ch.getTag())) {
                ch.animate().cancel();
                ch.animate().alpha(1f).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.EASE).start();
            }
        }
        // No growing animation: scaling a card with live video up to full screen is heavy and looked laggy.
        // The title page just dissolves in over the card (the video carries on underneath).
        handOffAndOpen(it);
    }

    private void handOffAndOpen(final Plex.Item it) {
        PreviewHandoff.releaseLeftover();
        PreviewHandoff.player = previewPlayer;
        PreviewHandoff.session = previewSession;
        PreviewHandoff.key = it.key;
        PreviewHandoff.clip = previewIsClip;
        PreviewHandoff.rendered = previewShowing;
        previewPlayer = null;
        previewSession = null;
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra("key", it.key);
        startActivity(i);
        // The title page dissolves in over the full-screen card; the video carries on underneath.
        overridePendingTransition(R.anim.reel_fade_in, R.anim.reel_hold);
        handler.postDelayed(this::removeCard, 1000);
    }

    private static boolean isRequest(Plex.Item it) {
        return it != null && it.key != null && it.key.startsWith("seerr:");
    }

    private void playCurrent() {
        if (current == null) return;
        if (isRequest(current)) {
            openDetail(current);
        } else if (current.isPlayable()) {
            ResumeDialog.play(this, current);
        } else {
            openDetail(current);
        }
    }

    private void openDetail(Plex.Item it) {
        if (it == null) return;
        if (isGenre(it) && it.key.startsWith("lib:")) {
            Intent l = new Intent(this, BrowseActivity.class);
            l.putExtra("section", it.key.substring(4));
            l.putExtra("title", it.title.replaceFirst("^All ", ""));
            l.putExtra("type", it.summary);
            startActivity(l);
            return;
        }
        if (isGenre(it)) {
            List<String> paths = genrePaths.get(it.key);
            if (paths == null) return;
            Intent g = new Intent(this, BrowseActivity.class);
            g.putExtra("paths", paths.toArray(new String[0]));
            g.putExtra("title", it.title);
            g.putExtra("type", "movie");
            startActivity(g);
            return;
        }
        if (!isRequest(it) && handOffHero(it)) return;
        if (!isRequest(it) && previewsOn() && focusedItem != null && focusedCard != null
                && it.key.equals(focusedItem.key)) {
            // Grow the card to full screen even if the preview hasn't started yet; whatever is
            // loading carries on behind the title page.
            if (pCard == null) openCard(it);
            if (pCard != null) {
                pCard.animate().cancel();
                expandAndOpen(it);
                return;
            }
        }
        if (isRequest(it)) {
            // A Seerr request: open it in Discover (or straight in Plex once it's arrived).
            Seerr.Media m = requestMedia.get(it.key);
            if (m != null && m.ratingKey != null && m.status >= 4) {
                Intent i = new Intent(this, DetailActivity.class);
                i.putExtra("key", m.ratingKey);
                startActivity(i);
                return;
            }
            String[] p = it.key.split(":");
            Intent i = new Intent(this, SeerrActivity.class);
            i.putExtra("openType", p[1]);
            i.putExtra("openId", Integer.parseInt(p[2]));
            startActivity(i);
            return;
        }
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra("key", it.key);
        View card = focusedItem != null && it.key.equals(focusedItem.key) ? focusedCard : null;
        if (card != null && card.getWidth() > 0) {
            // Swivel the poster and open the page growing out of it.
            card.setCameraDistance(card.getWidth() * 10f);
            android.animation.ObjectAnimator sw = android.animation.ObjectAnimator.ofPropertyValuesHolder(card,
                    android.animation.PropertyValuesHolder.ofKeyframe(View.ROTATION_Y,
                            android.animation.Keyframe.ofFloat(0f, 0f),
                            android.animation.Keyframe.ofFloat(0.5f, -22f),
                            android.animation.Keyframe.ofFloat(1f, 0f)));
            sw.setDuration(Motion.SLOW_MS).setInterpolator(Motion.EASE);
            sw.start();
            android.app.ActivityOptions o = android.app.ActivityOptions.makeScaleUpAnimation(
                    card, 0, 0, card.getWidth(), card.getHeight());
            startActivity(i, o.toBundle());
        } else {
            startActivity(i);
        }
    }

    // -------------------------------------------------------------- Rows

    private static final class RowVH extends RecyclerView.ViewHolder {
        final TextView title;
        final RecyclerView inner;

        RowVH(LinearLayout v, TextView title, RecyclerView inner) {
            super(v);
            this.title = title;
            this.inner = inner;
        }
    }

    private final class RowAdapter extends RecyclerView.Adapter<RowVH> {
        // Every row's cards are the same size, so rows share one pool: a card scrolled off one row is
        // reused in the next instead of building a new set of views each time.
        private final RecyclerView.RecycledViewPool cardPool = new RecyclerView.RecycledViewPool();

        RowAdapter() {
            cardPool.setMaxRecycledViews(0, 48);
        }

        @Override
        public RowVH onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout v = new LinearLayout(HomeActivity.this);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setClipChildren(false);
            v.setClipToPadding(false);
            v.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            int leftPad = (int) (W * 0.045f);

            TextView title = new TextView(HomeActivity.this);
            title.setTextColor(UiUtil.TEXT);
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
            title.setTypeface(UiUtil.font(UiUtil.SEMIBOLD));
            title.setPadding(leftPad + (int) (H * 0.011f), (int) (H * 0.03f), 0, (int) (H * 0.004f));
            v.addView(title);

            RecyclerView inner = new RecyclerView(HomeActivity.this);
            LinearLayoutManager lm = new LinearLayoutManager(HomeActivity.this, LinearLayoutManager.HORIZONTAL, false);
            lm.setInitialPrefetchItemCount(6); // get the visible cards ready while the row scrolls into view
            inner.setLayoutManager(lm);
            inner.setRecycledViewPool(cardPool);
            inner.setClipToPadding(false);
            inner.setClipChildren(false);
            inner.setItemAnimator(null);
            inner.setItemViewCacheSize(12);
            inner.setPadding(leftPad, 0, leftPad, 0);
            v.addView(inner, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RowVH(v, title, inner);
        }

        @Override
        public void onBindViewHolder(RowVH vh, int position) {
            Row row = rows.get(position);
            vh.title.setText(row.title);
            int ph = (int) (H * 0.185f);
            int pw = (int) (ph * 16f / 9f);
            if (row.adapter == null) {
                row.adapter = new PosterAdapter(row.items, pw, ph, true, HomeActivity.this);
                if (tab != TAB_HOME) row.adapter.withTitles();
            }
            if (vh.inner.getAdapter() != row.adapter) vh.inner.setAdapter(row.adapter);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}
