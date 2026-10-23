package com.enthu.reel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;

import com.bumptech.glide.Glide;

import java.util.List;

/**
 * Puts your Up Next titles on the Fire TV home screen ("Recommended by your apps" row).
 * Fire OS 7 / 8 take these as recommendation notifications; choosing one starts playback in Reel.
 */
final class TvRecommendations {
    private static final String CHANNEL = "reel_up_next";
    private static final int BASE_ID = 4100;
    private static final int MAX = 8;

    private TvRecommendations() {
    }

    static boolean enabled(Context c) {
        return c.getSharedPreferences("reel_ui", Context.MODE_PRIVATE).getBoolean("homeRow", true);
    }

    private static long lastRefresh = 0;

    /** Refresh unless it was done in the last 15 minutes. */
    static void refreshIfStale(Context ctx) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastRefresh != 0 && now - lastRefresh < 15 * 60_000L) return;
        refresh(ctx, null);
    }

    /** Rebuilds the row from Plex's On Deck list. done (may be null) runs when finished. */
    static void refresh(final Context ctx, final Runnable done) {
        final Context app = ctx.getApplicationContext();
        lastRefresh = android.os.SystemClock.elapsedRealtime();
        Plex.init(app);
        if (!Plex.isReady()) {
            if (done != null) done.run();
            return;
        }
        if (!enabled(app)) {
            clear(app);
            if (done != null) done.run();
            return;
        }
        Plex.async(() -> {
            List<Plex.Item> deck = Plex.items(Plex.paged("/library/onDeck", MAX));
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return 0;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Up Next",
                        NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("What to watch next, shown on the Fire TV home screen");
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
            int posted = 0;
            for (int i = 0; i < deck.size() && posted < MAX; i++) {
                Plex.Item it = deck.get(i);
                try {
                    Notification n = build(app, it, i);
                    if (n == null) continue;
                    nm.notify(BASE_ID + posted, n);
                    posted++;
                } catch (Exception ignored) {
                    // skip this one
                }
            }
            SharedPreferences p = app.getSharedPreferences("reel_ui", Context.MODE_PRIVATE);
            int before = p.getInt("homeRowCount", 0);
            for (int i = posted; i < before; i++) nm.cancel(BASE_ID + i); // remove ones no longer on deck
            p.edit().putInt("homeRowCount", posted).apply();
            return posted;
        }, new Plex.Callback<Integer>() {
            @Override
            public void ok(Integer n) {
                if (done != null) done.run();
            }

            @Override
            public void fail(Exception e) {
                if (done != null) done.run();
            }
        });
    }

    static void clear(Context ctx) {
        Context app = ctx.getApplicationContext();
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        SharedPreferences p = app.getSharedPreferences("reel_ui", Context.MODE_PRIVATE);
        int before = p.getInt("homeRowCount", 0);
        if (nm != null) for (int i = 0; i < Math.max(before, MAX); i++) nm.cancel(BASE_ID + i);
        p.edit().putInt("homeRowCount", 0).apply();
    }

    /** Runs on a background thread (it downloads the picture). */
    private static Notification build(Context app, Plex.Item it, int rank) throws Exception {
        boolean episode = "episode".equals(it.type);
        String title = episode && it.gpTitle != null ? it.gpTitle : it.displayTitle();
        String text = episode
                ? (it.parentIndex > 0 ? "S" + it.parentIndex + ", " : "") + "E" + it.index + " · " + it.title
                : (it.year > 0 ? String.valueOf(it.year) : "Movie");
        if (it.viewOffset > 0 && it.duration > 0) {
            long min = Math.max(1, (it.duration - it.viewOffset) / 60000L);
            text += " · " + min + " min left";
        }

        // 16:9 picture: the episode still, or the film's backdrop.
        String path = episode ? (it.thumb != null ? it.thumb : it.art) : (it.art != null ? it.art : it.thumb);
        if (path == null) path = it.poster();
        Bitmap img = Glide.with(app).asBitmap().load(Plex.img(path, 640, 360)).centerCrop()
                .submit(640, 360).get();
        if (img == null) return null;

        Intent play = new Intent(app, PlayerActivity.class);
        play.putExtra("key", it.key);
        play.putExtra("start", it.viewOffset);
        play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pi = PendingIntent.getActivity(app, 7000 + rank, play,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // A recommendation is a notification in the "recommendation" category (what Android TV's
        // ContentRecommendation helper builds; that class isn't in the public SDK).
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(app, CHANNEL) : new Notification.Builder(app);
        b.setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setContentTitle(title)
                .setContentText(text)
                .setLargeIcon(img)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setLocalOnly(true)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setSortKey(String.valueOf(1000 - rank))
                .setStyle(new Notification.BigPictureStyle().bigPicture(img));
        if (it.duration > 0 && it.viewOffset > 0) {
            b.setProgress((int) (it.duration / 1000L), (int) (it.viewOffset / 1000L), false);
        }
        Notification n = b.build();
        n.extras.putStringArray("android.contentType", new String[]{
                episode ? "android.contentType.serial" : "android.contentType.movie"});
        if (it.duration > 0) n.extras.putLong("android.contentDuration", it.duration);

        // Fire TV extras
        n.extras.putString("com.amazon.extra.DISPLAY_NAME", "Reel");
        n.extras.putInt("com.amazon.extra.RANK", rank);
        n.extras.putString("com.amazon.extra.LONG_DESCRIPTION", it.summary == null ? "" : it.summary);
        n.extras.putLong("com.amazon.extra.LAST_WATCHED_DATETIME", System.currentTimeMillis());
        if (it.year > 0) n.extras.putString("com.amazon.extra.CONTENT_RELEASE_DATE", String.valueOf(it.year));
        n.extras.putInt("com.amazon.extra.TEXT_EMBEDDED_IMAGE", 0);
        return n;
    }
}
