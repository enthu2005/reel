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
        UiUtil.clipRounded(card, radius);
        root.addView(card, new LinearLayout.LayoutParams(w, h));

        ImageView image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Darkens the bottom of the art so the progress bar reads.
        View shade = new View(c);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x99000000}));
        card.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (h * 0.35f), Gravity.BOTTOM));

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
        Glide.with(vh.image).load(Plex.img(path, w, h)).centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade(200)).into(vh.image);

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
