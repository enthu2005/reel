package com.enthu.reel;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import java.util.List;

/**
 * Apple TV style cards: rounded artwork that lifts and catches the light when focused, with the
 * title and a grey second line underneath (wide cards). Posters stay clean, like the TV app.
 */
final class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.VH> {

    interface Listener {
        void onFocused(Plex.Item item);

        void onClicked(Plex.Item item);

        /** Also gives the card's picture view, for screens that grow the card (live previews). */
        default void onFocusedView(Plex.Item item, View card) {
        }

        /** Holding OK on a card. Return true if the screen used it (then no click follows). */
        default boolean onLongClicked(Plex.Item item) {
            return false;
        }
    }

    private final List<Plex.Item> items;
    private final int w;
    private final int h;
    private final boolean landscape;
    private final Listener listener;
    private boolean withSeries = false;
    private boolean titles = false;

    /** Show the title (and year) under posters too, for browsing a library. */
    PosterAdapter withTitles() {
        titles = true;
        return this;
    }
    private static long lastRepeat = 0;

    /** Put the series name in episode captions (used by search results). */
    PosterAdapter withSeries() {
        withSeries = true;
        return this;
    }

    PosterAdapter(List<Plex.Item> items, int w, int h, boolean landscape, Listener listener) {
        this.items = items;
        this.w = w;
        this.h = h;
        this.landscape = landscape;
        this.listener = listener;
    }

    static final class VH extends RecyclerView.ViewHolder {
        final LinearLayout root;
        final FrameLayout card;
        final ImageView image;
        final View shade;
        final FrameLayout track;
        final View bar;
        final TextView title;
        final TextView sub;

        VH(LinearLayout root, FrameLayout card, ImageView image, View shade, FrameLayout track, View bar,
           TextView title, TextView sub) {
            super(root);
            this.root = root;
            this.card = card;
            this.image = image;
            this.shade = shade;
            this.track = track;
            this.bar = bar;
            this.title = title;
            this.sub = sub;
        }
    }

    @Override
    public VH onCreateViewHolder(ViewGroup parent, int viewType) {
        Context c = parent.getContext();
        SoundFx.init(c);
        int sh = c.getResources().getDisplayMetrics().heightPixels;
        float radius = sh * 0.014f;

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = (int) (sh * 0.011f);
        lp.setMargins(m, m, m, m);
        root.setLayoutParams(lp);
        root.setFocusable(true);
        root.setClickable(true);

        FrameLayout card = new FrameLayout(c);
        card.setBackground(UiUtil.rounded(UiUtil.CARD, radius));
        // No live rounded clip here: clipping every card to a rounded outline on every frame was the main
        // cost of moving through rows on the Fire TV. The artwork is rounded when it is loaded instead, and
        // the other layers are shaped to match.
        root.addView(card, new LinearLayout.LayoutParams(w, h));

        ImageView image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Darkens the bottom of the art so the progress bar reads.
        View shade = new View(c);
        GradientDrawable shadeBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x99000000});
        shadeBg.setCornerRadii(new float[]{0, 0, 0, 0, radius, radius, radius, radius}); // follows the card's bottom corners
        shade.setBackground(shadeBg);
        card.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (h * 0.35f), Gravity.BOTTOM));

        // Genre tiles: the genre's name big across darkened artwork
        TextView genreName = new TextView(c);
        genreName.setTag("genreName");
        genreName.setTextColor(0xFFFFFFFF);
        genreName.setTypeface(UiUtil.font(UiUtil.BOLD));
        genreName.setTextSize(TypedValue.COMPLEX_UNIT_PX, sh * 0.038f);
        genreName.setGravity(Gravity.CENTER);
        genreName.setSingleLine(true);
        genreName.setEllipsize(TextUtils.TruncateAt.END);
        genreName.setPadding((int) (w * 0.06f), 0, (int) (w * 0.06f), 0);
        genreName.setBackground(UiUtil.rounded(0x88000000, radius));
        genreName.setVisibility(View.GONE);
        card.addView(genreName, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Corner mark: a tick once watched, a "NEW" tag for things added this week
        TextView mark = new TextView(c);
        mark.setTag("mark");
        mark.setTextColor(0xFFFFFFFF);
        mark.setTypeface(UiUtil.font(UiUtil.BOLD));
        mark.setTextSize(TypedValue.COMPLEX_UNIT_PX, sh * 0.016f);
        mark.setGravity(Gravity.CENTER);
        mark.setVisibility(View.GONE);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        mlp.topMargin = (int) (sh * 0.012f);
        mlp.rightMargin = (int) (sh * 0.012f);
        card.addView(mark, mlp);

        int barH = Math.max(4, (int) (sh * 0.0055f));
        FrameLayout track = new FrameLayout(c);
        track.setBackground(UiUtil.rounded(0x59FFFFFF, barH / 2f));
        UiUtil.clipRounded(track, barH / 2f);
        View bar = new View(c);
        bar.setBackground(UiUtil.rounded(0xFFFFFFFF, barH / 2f));
        track.addView(bar, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        int inset = (int) (w * 0.06f);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barH, Gravity.BOTTOM);
        tlp.leftMargin = inset;
        tlp.rightMargin = inset;
        tlp.bottomMargin = (int) (h * 0.07f);
        card.addView(track, tlp);

        TextView title = new TextView(c);
        title.setTextColor(UiUtil.TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sh * 0.022f);
        title.setTypeface(UiUtil.font(UiUtil.MEDIUM));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, (int) (sh * 0.012f), 0, 0);
        root.addView(title, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sub = new TextView(c);
        sub.setTextColor(UiUtil.TEXT2);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sh * 0.019f);
        sub.setTypeface(UiUtil.font(UiUtil.REGULAR));
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(sub, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));

        boolean text = landscape || titles;
        title.setVisibility(text ? View.VISIBLE : View.GONE);
        sub.setVisibility(text ? View.VISIBLE : View.GONE);
        return new VH(root, card, image, shade, track, bar, title, sub);
    }

    /** A small dark circle with a white tick. */
    private static final class CheckDrawable extends android.graphics.drawable.Drawable {
        private final int size;
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint line = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        CheckDrawable(int size) {
            this.size = size;
            fill.setColor(0xB3000000);
            line.setColor(0xFFFFFFFF);
            line.setStyle(android.graphics.Paint.Style.STROKE);
            line.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            line.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            line.setStrokeWidth(Math.max(2f, size * 0.1f));
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float r = Math.min(b.width(), b.height()) / 2f;
            c.drawCircle(cx, cy, r, fill);
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(cx - r * 0.38f, cy + r * 0.02f);
            p.lineTo(cx - r * 0.1f, cy + r * 0.3f);
            p.lineTo(cx + r * 0.4f, cy - r * 0.26f);
            c.drawPath(p, line);
        }

        @Override public void setAlpha(int a) { fill.setAlpha(a); line.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
    }

    private static String left(Plex.Item it) {
        if (it.viewOffset <= 0 || it.duration <= 0) return null;
        long min = Math.max(1, (it.duration - it.viewOffset) / 60000L);
        if (min >= 60) return (min / 60) + " hr " + (min % 60) + " min left";
        return min + " min left";
    }

    @Override
    public void onBindViewHolder(final VH vh, int position) {
        final Plex.Item it = items.get(position);
        final Context c = vh.root.getContext();
        final int sh = c.getResources().getDisplayMetrics().heightPixels;
        final float radius = sh * 0.014f;

        String path;
        if (!landscape) {
            path = it.poster();
        } else if ((it.type.equals("movie") || it.type.equals("show")) && it.art != null) {
            path = it.art; // wide artwork for movies in wide cards
        } else {
            path = it.thumb != null ? it.thumb : it.poster();
        }
        Glide.with(vh.image).load(Plex.img(path, w, h))
                .transform(new com.bumptech.glide.load.resource.bitmap.CenterCrop(),
                        new com.bumptech.glide.load.resource.bitmap.RoundedCorners((int) radius))
                .transition(DrawableTransitionOptions.withCrossFade(200)).into(vh.image);

        boolean genre = it.type.equals("genre");
        TextView genreName = (TextView) vh.card.findViewWithTag("genreName");
        if (genreName != null) {
            genreName.setText(it.title);
            genreName.setVisibility(genre ? View.VISIBLE : View.GONE);
        }

        TextView mark = (TextView) vh.card.findViewWithTag("mark");
        if (mark != null && genre) {
            mark.setVisibility(View.GONE);
        } else if (mark != null) {
            int md = (int) (sh * 0.032f);
            if (it.isNew()) {
                mark.setText("NEW");
                mark.setCompoundDrawables(null, null, null, null);
                mark.setPadding((int) (sh * 0.011f), (int) (sh * 0.004f), (int) (sh * 0.011f), (int) (sh * 0.004f));
                mark.setBackground(UiUtil.rounded(0xFF0A84FF, sh * 0.012f));
                mark.setMinWidth(0);
                mark.setMinHeight(0);
                mark.setVisibility(View.VISIBLE);
            } else if (it.isWatched()) {
                mark.setText("");
                mark.setPadding(0, 0, 0, 0);
                mark.setBackground(new CheckDrawable(md));
                mark.setMinWidth(md);
                mark.setMinHeight(md);
                mark.setVisibility(View.VISIBLE);
            } else {
                mark.setVisibility(View.GONE);
            }
        }

        boolean progress = it.viewOffset > 0 && it.duration > 0;
        vh.track.setVisibility(progress ? View.VISIBLE : View.GONE);
        vh.shade.setVisibility(progress ? View.VISIBLE : View.GONE);
        if (progress) {
            final float frac = Math.min(1f, (float) it.viewOffset / it.duration);
            vh.track.post(() -> {
                ViewGroup.LayoutParams blp = vh.bar.getLayoutParams();
                blp.width = Math.max(vh.track.getHeight(), (int) (vh.track.getWidth() * frac));
                vh.bar.setLayoutParams(blp);
            });
        }

        if (landscape || titles) {
            String t;
            String s;
            String ep = (it.parentIndex > 0 ? "S" + it.parentIndex + ", " : "") + (it.index > 0 ? "E" + it.index : "");
            if (it.type.equals("episode")) {
                boolean showName = (withSeries || !it.watcher.isEmpty()) && it.gpTitle != null;
                t = showName ? it.gpTitle : it.title;
                s = showName ? ep + (ep.isEmpty() ? "" : " · ") + it.title : ep;
            } else {
                t = it.displayTitle();
                s = it.year > 0 ? String.valueOf(it.year) : "";
            }
            String rem = left(it);
            if (rem != null) s = s.isEmpty() ? rem : s + " · " + rem;
            if (!it.watcher.isEmpty()) s = s.isEmpty() ? it.watcher : s + " · " + it.watcher;
            if (genre) {
                t = ""; // the name is already on the tile
                s = "";
            }
            vh.title.setText(t);
            vh.sub.setText(s);
            vh.sub.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
        }

        vh.root.setOnClickListener(v -> {
            SoundFx.click();
            v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(Motion.TAP_MS).setInterpolator(Motion.EASE).withEndAction(
                    () -> v.animate().scaleX(1f).scaleY(1f).setDuration(Motion.TAP_MS).setInterpolator(Motion.EASE).start()).start();
            listener.onClicked(it);
        });
        vh.root.setLongClickable(true);
        vh.root.setOnLongClickListener(v -> listener.onLongClicked(it));
        vh.root.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            Motion.noteKey(code);
            boolean arrow = code == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                    || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == android.view.KeyEvent.KEYCODE_DPAD_UP
                    || code == android.view.KeyEvent.KEYCODE_DPAD_DOWN;
            if (arrow && e.getRepeatCount() > 0) {
                // Holding a direction fires keys faster than the list can scroll; keep a steady pace.
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastRepeat < 120) return true;
                lastRepeat = now;
            }
            int pos = vh.getBindingAdapterPosition();
            boolean end = code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && pos == items.size() - 1;
            boolean start = code == android.view.KeyEvent.KEYCODE_DPAD_LEFT && pos == 0;
            if (!end && !start) return false;
            // End of the list: a little nudge and a thud so it doesn't just do nothing.
            SoundFx.edge();
            float d = sh * 0.012f * (end ? 1 : -1);
            vh.card.animate().translationX(d).setDuration(Motion.TAP_MS).setInterpolator(Motion.EASE).withEndAction(
                    () -> vh.card.animate().translationX(0f).setDuration(Motion.FAST_MS).setInterpolator(Motion.EASE).start()).start();
            return true;
        });
        vh.root.setOnFocusChangeListener((v, focused) -> {
            // tvOS: the artwork grows and lifts, the text underneath steps down out of its way.
            float s = focused ? 1.10f : 1f;
            float grow = (h * 0.10f) / 2f;
            v.setTranslationZ(focused ? sh * 0.02f : 0f);
            vh.card.setPivotX(w / 2f);
            vh.card.setPivotY(h / 2f);
            // The card leans the way you moved (Apple TV style), then settles flat.
            float ry = focused ? -Motion.lastDx * 7f : 0f;
            float rx = focused ? Motion.lastDy * 5f : 0f;
            vh.card.setCameraDistance(w * 14f);
            vh.card.animate().scaleX(s).scaleY(s).rotationY(ry).rotationX(rx).setDuration(Motion.FOCUS_MS)
                    .setInterpolator(Motion.EASE).withEndAction(() -> {
                        if (vh.root.hasFocus()) {
                            vh.card.animate().rotationY(0f).rotationX(0f).setDuration(Motion.SLOW_MS)
                                    .setInterpolator(Motion.EASE).start();
                        }
                    }).start();
            vh.card.setElevation(focused ? sh * 0.025f : 0f);
            vh.card.setForeground(focused ? UiUtil.sheen(radius) : null);
            float drop = focused ? grow : 0f;
            vh.title.animate().translationY(drop).alpha(focused ? 1f : 0.8f).setDuration(Motion.FOCUS_MS)
                    .setInterpolator(Motion.EASE).start();
            vh.sub.animate().translationY(drop).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.EASE).start();
            if (focused) {
                SoundFx.move();
                listener.onFocused(it);
                listener.onFocusedView(it, vh.card);
            }
        });
        vh.title.setAlpha(vh.root.hasFocus() ? 1f : 0.8f);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}
