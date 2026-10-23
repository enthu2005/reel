package com.enthu.reel;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Playback controls in the style of the new Plex player: a clean timeline along the bottom with
 * elapsed / remaining time and the end time, preview thumbnails while skipping, and a big
 * play / pause mark. Left and Right skip straight away (no need to press OK first); presses are
 * gathered for a moment and done as one jump, which keeps things smooth on a slow connection.
 */
final class PlayerOsd {

    interface Host {
        long position();      // ms from the start of the movie

        long duration();

        long buffered();      // ms from the start of the movie

        boolean playing();

        void setPlaying(boolean play);

        void seekTo(long ms); // ms from the start of the movie
    }

    private static final long HIDE_MS = 4500;
    private static final long COMMIT_MS = 650;

    private final Activity act;
    private final Host host;
    private final int W;
    private final int H;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService thumbs = Executors.newSingleThreadExecutor();

    private final View shadeBottom;
    private final View shadeTop;
    private final FrameLayout bottom;
    private final FrameLayout track;
    private final View bufferedBar;
    private final View playedBar;
    private final View knob;
    private final TextView elapsed;
    private final TextView remaining;
    private final TextView endsAt;
    private final FrameLayout preview;
    private final ImageView previewImg;
    private final TextView previewTime;
    private final PlayMark mark;

    private boolean visible = false;
    private long lastTouch = 0;
    private long scrubTarget = -1;
    private int thumbToken = 0;
    private VisListener visListener;

    interface VisListener {
        void onChange(boolean visible);
    }

    // Preview thumbnails (Plex "BIF" index), kept on disk rather than in memory.
    private File bifFile;
    private int bifCount = 0;
    private int bifInterval = 1000;
    private long[] bifOffsets;
    private long[] bifTimes; // ms into the movie for each frame, as the file says

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!visible) return;
            update();
            boolean idle = SystemClock.uptimeMillis() - lastTouch > HIDE_MS;
            if (idle && host.playing() && scrubTarget < 0) {
                hide();
                return;
            }
            handler.postDelayed(this, 200);
        }
    };

    private final Runnable commit = new Runnable() {
        @Override
        public void run() {
            if (scrubTarget < 0) return;
            long t = scrubTarget;
            scrubTarget = -1;
            host.seekTo(t);
            preview.animate().alpha(0f).setDuration(200).start();
            knob.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            touch();
        }
    };

    PlayerOsd(Activity act, FrameLayout root, int W, int H, Host host) {
        this.act = act;
        this.host = host;
        this.W = W;
        this.H = H;

        shadeBottom = new View(act);
        shadeBottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x99000000, 0xE6000000}));
        shadeBottom.setAlpha(0f);
        root.addView(shadeBottom, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (H * 0.42f), Gravity.BOTTOM));
        shadeTop = new View(act);
        shadeTop.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x00000000}));
        shadeTop.setAlpha(0f);
        root.addView(shadeTop, 2, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (H * 0.28f), Gravity.TOP));

        mark = new PlayMark(act);
        mark.setAlpha(0f);
        int ms = (int) (H * 0.15f);
        root.addView(mark, new FrameLayout.LayoutParams(ms, ms, Gravity.CENTER));

        bottom = new FrameLayout(act);
        bottom.setClipChildren(false);
        bottom.setAlpha(0f);
        bottom.setVisibility(View.GONE);
        int side = (int) (W * 0.05f);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (H * 0.42f), Gravity.BOTTOM);
        blp.leftMargin = side;
        blp.rightMargin = side;
        blp.bottomMargin = (int) (H * 0.06f);
        root.addView(bottom, blp);

        int trackH = Math.max(6, (int) (H * 0.0075f));
        int timesH = (int) (H * 0.05f);
        track = new FrameLayout(act);
        track.setBackground(UiUtil.rounded(0x4DFFFFFF, trackH / 2f));
        bufferedBar = new View(act);
        bufferedBar.setBackground(UiUtil.rounded(0x80FFFFFF, trackH / 2f));
        track.addView(bufferedBar, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        playedBar = new View(act);
        playedBar.setBackground(UiUtil.rounded(0xFFFFFFFF, trackH / 2f));
        track.addView(playedBar, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, trackH, Gravity.BOTTOM);
        tlp.bottomMargin = timesH;
        bottom.addView(track, tlp);

        int k = (int) (H * 0.026f);
        knob = new View(act);
        knob.setBackground(UiUtil.rounded(0xFFFFFFFF, k / 2f));
        knob.setElevation(H * 0.01f);
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(k, k, Gravity.BOTTOM | Gravity.START);
        klp.bottomMargin = timesH + trackH / 2 - k / 2;
        bottom.addView(knob, klp);

        elapsed = label(act, UiUtil.TEXT, UiUtil.MEDIUM);
        bottom.addView(elapsed, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START));
        endsAt = label(act, UiUtil.TEXT2, UiUtil.REGULAR);
        bottom.addView(endsAt, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        remaining = label(act, UiUtil.TEXT, UiUtil.MEDIUM);
        bottom.addView(remaining, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END));

        // Preview above the timeline while skipping
        int pw = (int) (W * 0.17f);
        int ph = (int) (pw * 9f / 16f);
        preview = new FrameLayout(act);
        preview.setAlpha(0f);
        preview.setClipChildren(false);
        previewImg = new ImageView(act);
        previewImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        float pr = H * 0.012f;
        previewImg.setBackground(UiUtil.rounded(0xFF1C1C1E, pr));
        UiUtil.clipRounded(previewImg, pr);
        previewImg.setVisibility(View.GONE);
        preview.addView(previewImg, new FrameLayout.LayoutParams(pw, ph, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        previewTime = label(act, 0xFF000000, UiUtil.SEMIBOLD);
        previewTime.setBackground(UiUtil.rounded(0xFFFFFFFF, H * 0.03f));
        previewTime.setPadding((int) (H * 0.016f), (int) (H * 0.005f), (int) (H * 0.016f), (int) (H * 0.005f));
        preview.addView(previewTime, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(pw, ph + (int) (H * 0.06f),
                Gravity.BOTTOM | Gravity.START);
        plp.bottomMargin = timesH + trackH + (int) (H * 0.02f);
        bottom.addView(preview, plp);
    }

    private TextView label(Context c, int color, int weight) {
        TextView t = new TextView(c);
        t.setTextColor(color);
        t.setTypeface(UiUtil.font(weight));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.025f);
        t.setSingleLine(true);
        return t;
    }

    void setVisibilityListener(VisListener l) {
        visListener = l;
    }

    boolean isVisible() {
        return visible;
    }

    private void touch() {
        lastTouch = SystemClock.uptimeMillis();
    }

    void show() {
        touch();
        if (visible) return;
        visible = true;
        bottom.setVisibility(View.VISIBLE);
        update();
        for (View v : new View[]{shadeBottom, shadeTop, bottom}) {
            v.animate().cancel();
            v.animate().alpha(1f).setDuration(220).start();
        }
        if (visListener != null) visListener.onChange(true);
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    void hide() {
        if (!visible) return;
        visible = false;
        handler.removeCallbacks(tick);
        for (View v : new View[]{shadeBottom, shadeTop, bottom}) {
            v.animate().cancel();
            v.animate().alpha(0f).setDuration(260).start();
        }
        handler.postDelayed(() -> {
            if (!visible) bottom.setVisibility(View.GONE);
        }, 280);
        if (visListener != null) visListener.onChange(false);
    }

    /** Back: drop a pending skip, else hide the controls. Returns true if it used the key. */
    boolean onBack() {
        if (scrubTarget >= 0) {
            handler.removeCallbacks(commit);
            scrubTarget = -1;
            preview.animate().alpha(0f).setDuration(150).start();
            update();
            return true;
        }
        if (visible) {
            hide();
            return true;
        }
        return false;
    }

    private long lastRepeat = 0;

    /** Left / Right / play keys. Returns true when handled. */
    boolean handleKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean left = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_MEDIA_REWIND;
        boolean right = code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
        if (left || right) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            int rep = e.getRepeatCount();
            if (rep > 0) {
                long now = SystemClock.uptimeMillis();
                if (now - lastRepeat < 140) return true; // holding: a steady pace, not a blur
                lastRepeat = now;
            }
            long held = e.getEventTime() - e.getDownTime();
            long step = held < 1500 ? 10_000 : held < 4000 ? 30_000 : 60_000;
            boolean media = code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            if (media) step = 30_000;
            scrub(right ? step : -step);
            return true;
        }
        boolean toggle = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_SPACE;
        if (toggle || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return true;
            if (scrubTarget >= 0 && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
                handler.removeCallbacks(commit);
                commit.run(); // OK while skipping jumps now
                return true;
            }
            boolean play = code == KeyEvent.KEYCODE_MEDIA_PLAY
                    || (code != KeyEvent.KEYCODE_MEDIA_PAUSE && !host.playing());
            host.setPlaying(play);
            mark.set(play);
            mark.animate().cancel();
            mark.setAlpha(1f);
            mark.setScaleX(0.85f);
            mark.setScaleY(0.85f);
            mark.animate().scaleX(1f).scaleY(1f).setDuration(180).withEndAction(() -> {
                if (host.playing()) mark.animate().alpha(0f).setDuration(350).start();
            }).start();
            show();
            return true;
        }
        return false;
    }

    /** Called by the activity when playback starts or stops on its own. */
    void onPlayingChanged(boolean playing) {
        if (playing) {
            mark.animate().alpha(0f).setDuration(300).start();
        } else {
            mark.set(false);
            mark.animate().alpha(1f).setDuration(200).start();
            show();
        }
    }

    private void scrub(long delta) {
        long dur = host.duration();
        long from = scrubTarget >= 0 ? scrubTarget : host.position();
        long t = from + delta;
        if (dur > 0) t = Math.min(t, dur - 2000);
        scrubTarget = Math.max(0, t);
        show();
        update();
        preview.animate().cancel();
        preview.animate().alpha(1f).setDuration(120).start();
        knob.animate().scaleX(1.35f).scaleY(1.35f).setDuration(120).start();
        showThumb(scrubTarget);
        handler.removeCallbacks(commit);
        handler.postDelayed(commit, COMMIT_MS);
    }

    private void update() {
        long dur = host.duration();
        long pos = scrubTarget >= 0 ? scrubTarget : host.position();
        int tw = track.getWidth();
        if (dur <= 0 || tw <= 0) {
            elapsed.setText(time(pos));
            remaining.setText("");
            endsAt.setText("");
            return;
        }
        float fp = Math.max(0f, Math.min(1f, (float) pos / dur));
        float fb = Math.max(fp, Math.min(1f, (float) host.buffered() / dur));
        setWidth(playedBar, (int) (tw * fp));
        setWidth(bufferedBar, (int) (tw * fb));
        knob.setTranslationX(tw * fp - knob.getWidth() / 2f);
        elapsed.setText(time(pos));
        remaining.setText("-" + time(Math.max(0, dur - pos)));
        long end = System.currentTimeMillis() + (dur - pos);
        endsAt.setText("Ends at " + android.text.format.DateFormat.getTimeFormat(act).format(new java.util.Date(end)));
        previewTime.setText(time(pos));
        float px = tw * fp - preview.getWidth() / 2f;
        preview.setTranslationX(Math.max(0, Math.min(tw - preview.getWidth(), px)));
    }

    private static void setWidth(View v, int w) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp.width != w) {
            lp.width = w;
            v.setLayoutParams(lp);
        }
    }

    private static String time(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(java.util.Locale.ROOT, "%d:%02d", m, sec);
    }

    // ------------------------------------------------------- Thumbnails

    /** Downloads Plex's preview thumbnails for this file in the background (if the server made them). */
    void loadThumbnails(final String partKey) {
        if (partKey == null) return;
        Matcher m = Pattern.compile("/library/parts/(\\d+)").matcher(partKey);
        if (!m.find()) return;
        final String id = m.group(1);
        thumbs.execute(() -> {
            try {
                File f = new File(act.getCacheDir(), "bif_" + id + ".bif");
                if (!f.exists() || f.length() < 64) {
                    // keep only one file around
                    File[] old = act.getCacheDir().listFiles();
                    if (old != null) for (File o : old) if (o.getName().startsWith("bif_")) o.delete();
                    HttpURLConnection c = (HttpURLConnection) new URL(
                            Plex.raw("/library/parts/" + id + "/indexes/sd")).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    if (c.getResponseCode() != 200) return;
                    File tmp = new File(act.getCacheDir(), "bif_" + id + ".part");
                    try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                        byte[] b = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    }
                    if (!tmp.renameTo(f)) return;
                }
                try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                    byte[] head = new byte[64];
                    r.readFully(head);
                    if ((head[1] & 0xFF) != 'B' || (head[2] & 0xFF) != 'I' || (head[3] & 0xFF) != 'F') return;
                    int count = le(head, 12);
                    int interval = le(head, 16);
                    if (count <= 0 || count > 200_000) return;
                    byte[] idx = new byte[(count + 1) * 8];
                    r.readFully(idx);
                    final int iv = interval <= 0 ? 1000 : interval;
                    long[] offs = new long[count + 1];
                    long[] times = new long[count + 1];
                    for (int i = 0; i <= count; i++) {
                        // Each entry: the frame's timestamp (in units of the interval) and where it starts.
                        times[i] = (le(idx, i * 8) & 0xFFFFFFFFL) * iv;
                        offs[i] = le(idx, i * 8 + 4) & 0xFFFFFFFFL;
                    }
                    final int cnt = count;
                    handler.post(() -> {
                        bifFile = f;
                        bifOffsets = offs;
                        bifTimes = times;
                        bifInterval = iv;
                        bifCount = cnt;
                    });
                }
            } catch (Exception ignored) {
                // no thumbnails, the time still shows
            }
        });
    }

    private static int le(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private void showThumb(long ms) {
        if (bifFile == null || bifCount == 0 || bifTimes == null) return;
        // The last frame taken at or before this moment (frames aren't always one per interval).
        int lo = 0;
        int hi = bifCount - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (bifTimes[mid] <= ms) lo = mid;
            else hi = mid - 1;
        }
        final int i = lo;
        final long a = bifOffsets[i];
        final long b = bifOffsets[i + 1];
        final File f = bifFile;
        final int token = ++thumbToken;
        if (b <= a || b - a > 2_000_000) return;
        thumbs.execute(() -> {
            if (token != thumbToken) return; // a newer frame was asked for
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                byte[] jpg = new byte[(int) (b - a)];
                r.seek(a);
                r.readFully(jpg);
                final Bitmap bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                handler.post(() -> {
                    if (token != thumbToken || bmp == null) return;
                    previewImg.setImageBitmap(bmp);
                    previewImg.setVisibility(View.VISIBLE);
                });
            } catch (Exception ignored) {
            }
        });
    }

    void release() {
        handler.removeCallbacksAndMessages(null);
        thumbs.shutdownNow();
    }

    /** Big translucent play / pause symbol in the middle of the picture. */
    private static final class PlayMark extends View {
        private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean playing = false;

        PlayMark(Context c) {
            super(c);
            circle.setColor(0x66000000);
            fg.setColor(0xFFFFFFFF);
        }

        void set(boolean playing) {
            this.playing = playing;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            c.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, circle);
            if (playing) {
                Path p = new Path();
                p.moveTo(w * 0.40f, h * 0.30f);
                p.lineTo(w * 0.72f, h * 0.50f);
                p.lineTo(w * 0.40f, h * 0.70f);
                p.close();
                c.drawPath(p, fg);
            } else {
                float r = w * 0.03f;
                c.drawRoundRect(w * 0.35f, h * 0.30f, w * 0.45f, h * 0.70f, r, r, fg);
                c.drawRoundRect(w * 0.55f, h * 0.30f, w * 0.65f, h * 0.70f, r, r, fg);
            }
        }
    }
}
