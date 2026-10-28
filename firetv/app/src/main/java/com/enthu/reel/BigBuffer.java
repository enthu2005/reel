package com.enthu.reel;

import android.content.Context;
import android.net.Uri;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * "Extra large buffer": while a film plays directly from your server, a background task keeps downloading
 * ahead of the playhead into a cache on the Fire TV's own storage (up to 3 GB). The player reads from that cache
 * into its normal memory buffer, so a slow patch on the network is covered by many minutes of stored video.
 * Only used when the file is played as it is (not when Plex is converting it on the fly).
 */
final class BigBuffer {
    static final long MAX_BYTES = 3L * 1024 * 1024 * 1024;
    private static final long AHEAD_BYTES = 2_600L * 1024 * 1024;   // how far past the playhead to fetch
    private static final long CHUNK = 16L * 1024 * 1024;
    private static final long SKIP_BYTES = 16L * 1024 * 1024;       // leave the first stretch to the player itself

    private static SimpleCache cache;
    private static String cachedFor = "";

    private volatile long aheadBytes;
    private volatile boolean stop;
    private volatile long posMs;
    private Thread thread;
    private volatile String status = "starting";

    private volatile long fileLen;
    private volatile long fileDurMs;

    /** About how many seconds of video the stored-ahead bytes cover. */
    long aheadSeconds() {
        if (fileLen <= 0 || fileDurMs <= 0) return 0;
        return (long) ((double) ahead() / fileLen * fileDurMs / 1000.0);
    }

    String status() {
        return status;
    }

    /** How fast the background task last pulled data from the server, in Mbit/s (0 until it has). */
    private volatile double lastMbps;

    double downloadMbps() {
        return lastMbps;
    }

    /** The file's average data rate in bytes per second (0 if not known yet). */
    long bytesPerSecond() {
        return fileLen <= 0 || fileDurMs <= 0 ? 0 : (long) (fileLen / (fileDurMs / 1000.0));
    }

    static boolean enabled(Context c) {
        return c.getSharedPreferences("reel_ui", Context.MODE_PRIVATE).getBoolean("bigBuffer", false);
    }

    @SuppressWarnings("deprecation")
    private static synchronized SimpleCache cache(Context c) {
        if (cache == null) {
            File dir = new File(c.getApplicationContext().getCacheDir(), "bigbuffer");
            dir.mkdirs();
            cache = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(MAX_BYTES));
        }
        return cache;
    }

    /** Throws away whatever is stored (when you switch to another film, or turn the setting off). */
    static synchronized void clear(Context c) {
        try {
            SimpleCache sc = cache(c);
            for (String k : new java.util.ArrayList<>(sc.getKeys())) sc.removeResource(k);
        } catch (Throwable ignored) {
        }
        cachedFor = "";
    }

    /** A data source that reads through the on-device store first. */
    static CacheDataSource.Factory factory(Context c, String filmKey) {
        synchronized (BigBuffer.class) {
            if (!filmKey.equals(cachedFor)) {
                clear(c); // a different film: start with an empty store
                cachedFor = filmKey;
            }
        }
        DataSource.Factory upstream = new DefaultDataSource.Factory(c,
                new DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(30000)
                        .setReadTimeoutMs(60000));
        return new CacheDataSource.Factory()
                .setCache(cache(c))
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    /** Bytes stored on the device right now (0 if the store hasn't been opened). */
    static synchronized long usedBytes() {
        return cache == null ? 0 : cache.getCacheSpace();
    }

    /** Roughly how far past the playhead the background task has fetched. */
    long ahead() {
        return Math.max(0, aheadBytes);
    }

    void position(long ms) {
        posMs = ms;
    }

    void start(final Context c, final String filmKey, final String url, final long durationMs) {
        stop();
        stop = false;
        final CacheDataSource.Factory f = factory(c, filmKey);
        thread = new Thread(() -> run(c, f, url, durationMs, filmKey), "ReelBigBuffer");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        stop = true;
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
    }

    private void run(Context c, CacheDataSource.Factory f, String url, long durationMs, String filmKey) {
        long length = -1;
        for (int i = 0; i < 5 && !stop && length <= 0; i++) {
            length = contentLength(url);
            if (length <= 0) {
                status = "no file size (try " + (i + 1) + ")";
                try { Thread.sleep(1500); } catch (InterruptedException ie) { return; }
            }
        }
        if (length <= 0) return;
        if (durationMs <= 0) durationMs = 2L * 60 * 60 * 1000;
        fileLen = length;
        fileDurMs = durationMs;
        status = "running";
        Uri uri = Uri.parse(url);
        CacheDataSource ds = f.createDataSource();
        long frontier = -1;
        long lastPlayhead = 0;
        while (!stop) {
            try {
                // Where the playhead is, roughly, as a byte position (files are close enough to even)
                long here = (long) ((double) Math.max(0, posMs) / durationMs * length);
                boolean jumped = frontier < 0 || here > frontier + 32 * CHUNK || here + 32 * CHUNK < lastPlayhead;
                lastPlayhead = here;
                if (jumped) frontier = Math.min(length, here + SKIP_BYTES);
                long target = Math.min(length, here + AHEAD_BYTES);
                aheadBytes = Math.max(0, frontier - here - SKIP_BYTES);
                if (frontier >= target) {
                    Thread.sleep(2000);
                    continue;
                }
                long n = Math.min(CHUNK, length - frontier);
                long t0 = System.nanoTime();
                new CacheWriter(ds, new DataSpec.Builder().setUri(uri).setPosition(frontier).setLength(n).build(),
                        null, null).cache();
                frontier += n;
                long fetchedMs = Math.max(1, (System.nanoTime() - t0) / 1_000_000L);
                lastMbps = n * 8.0 / 1000.0 / fetchedMs; // before any throttling pause
                trimBehind(c, here, length);
                int mbit = c.getSharedPreferences("reel_ui", Context.MODE_PRIVATE).getInt("bigBufferMbit", 25);
                if (mbit > 0) {
                    long minMs = n * 8000L / ((long) mbit * 1_000_000L);
                    long tookMs = (System.nanoTime() - t0) / 1_000_000L;
                    if (tookMs < minMs) Thread.sleep(minMs - tookMs);
                }
                status = "ok";
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                // network hiccup: try again in a moment
                status = "err " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    /**
     * Like swap space in reverse: once the player has moved on, what it already played is deleted from the
     * disk, so the store only ever holds what is still to come (plus a little behind for a short rewind).
     */
    private static void trimBehind(Context c, long here, long length) {
        try {
            long keep = Math.max(128L * 1024 * 1024, length / 50); // the playhead is only an estimate: stay well clear
            long cutoff = here - keep;
            if (cutoff <= 0) return;
            SimpleCache sc = cache(c);
            for (String k : new java.util.ArrayList<>(sc.getKeys())) {
                for (androidx.media3.datasource.cache.CacheSpan span : new java.util.ArrayList<>(sc.getCachedSpans(k))) {
                    if (span.isCached && span.position + span.length < cutoff) sc.removeSpan(span);
                }
            }
        } catch (Throwable ignored) {
            // trimming is a courtesy; the least-recently-used rule still keeps the store under its limit
        }
    }

    private static long contentLength(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("Range", "bytes=0-0");
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            String range = c.getHeaderField("Content-Range"); // bytes 0-0/12345
            if (range != null && range.contains("/")) return Long.parseLong(range.substring(range.indexOf('/') + 1).trim());
            return c.getContentLengthLong();
        } catch (Throwable e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
