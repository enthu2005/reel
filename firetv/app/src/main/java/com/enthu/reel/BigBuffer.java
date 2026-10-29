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
        if (hlsMode) return (long) hlsAheadSec;
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
        // (Another film's leftovers are cleared by the background task, not here: wiping up to 3 GB of
        // files on the main thread held up the start of playback.)
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

    /** Where the store lives, how many files are in it and its size limit: for the nerdy Info page. */
    static synchronized String describeStore(Context c) {
        try {
            File dir = new File(c.getApplicationContext().getCacheDir(), "bigbuffer");
            int files = 0;
            long bytes = 0;
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isDirectory()) {
                        File[] in = f.listFiles();
                        if (in != null) for (File g : in) {
                            files++;
                            bytes += g.length();
                        }
                    } else {
                        files++;
                        bytes += f.length();
                    }
                }
            }
            return dir.getAbsolutePath() + "  " + files + " files, " + (bytes >> 20) + " MB on disk, limit "
                    + (MAX_BYTES >> 20) + " MB";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    long fileLength() {
        return fileLen;
    }

    // ------------------------------------------------------------------ converted (HLS) streams

    private volatile boolean hlsMode;
    private volatile double hlsAheadSec;
    private volatile long hlsBytes;
    private volatile int hlsSegments;
    private volatile long playerAheadMs; // what the player itself already holds ahead (it loads that part itself)

    void playerAhead(long ms) {
        playerAheadMs = ms;
    }

    boolean isHls() {
        return hlsMode;
    }

    long hlsStoredBytes() {
        return hlsBytes;
    }

    int hlsSegmentsStored() {
        return hlsSegments;
    }

    /** A data source for a converted stream: reads through the on-device store, sending Plex's request headers. */
    static CacheDataSource.Factory factory(Context c, java.util.Map<String, String> headers) {
        DataSource.Factory upstream = new DefaultDataSource.Factory(c,
                new DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(30000)
                        .setReadTimeoutMs(60000)
                        .setDefaultRequestProperties(headers));
        return new CacheDataSource.Factory()
                .setCache(cache(c))
                .setUpstreamDataSourceFactory(upstream)
                .setEventListener(new CacheDataSource.EventListener() {
                    @Override
                    public void onCachedBytesRead(long cacheSizeBytes, long cachedBytesRead) {
                        servedFromDisk += cachedBytesRead; // the player got this from the disk store, not the network
                    }

                    @Override
                    public void onCacheIgnored(int reason) {
                    }
                })
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    /** How many bytes the player has been given from the disk store (instead of the network) since this stream began. */
    static volatile long servedFromDisk;

    private volatile boolean playerLoading = true;

    /** True while the player is still pulling data from the network into memory. The disk fill waits for it. */
    void playerLoading(boolean loading) {
        playerLoading = loading;
    }

    boolean playerLoadingNow() {
        return playerLoading;
    }

    /**
     * Plex is converting the film (so there is no file to read ahead in). Instead this follows the converted stream's
     * playlist and downloads its pieces ahead of the playhead into the same on-device store, so the buffer on disk
     * works here too. [posMs] must then be the player's position inside this converted stream.
     */
    void startHls(final Context c, final String masterUrl, final java.util.Map<String, String> headers) {
        stop();
        stop = false;
        go = true; // the disk fill already waits for the memory to be full, so it needs no other wait
        hlsMode = true;
        servedFromDisk = 0;
        hlsAheadSec = 0;
        hlsBytes = 0;
        hlsSegments = 0;
        fileLen = 0;
        fileDurMs = 0;
        aheadBytes = 0;
        final CacheDataSource.Factory f = factory(c, headers);
        thread = new Thread(() -> runHls(c, f, masterUrl, headers), "ReelBigBufferHls");
        thread.setDaemon(true);
        thread.start();
    }

    private static String fetchText(String url, java.util.Map<String, String> headers) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) h.setRequestProperty(e.getKey(), e.getValue());
            h.setConnectTimeout(15000);
            h.setReadTimeout(30000);
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(h.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                return sb.toString();
            }
        } finally {
            h.disconnect();
        }
    }

    private void runHls(Context c, CacheDataSource.Factory f, String masterUrl, java.util.Map<String, String> headers) {
        try {
            // a new converted stream: whatever was stored for another film or session is of no use now
            java.util.regex.Matcher sm = java.util.regex.Pattern.compile("session=([^&]+)").matcher(masterUrl);
            String sid = sm.find() ? sm.group(1) : "";
            SimpleCache sc = cache(c);
            for (String k : new java.util.ArrayList<>(sc.getKeys())) if (sid.isEmpty() || !k.contains(sid)) sc.removeResource(k);
        } catch (Throwable ignored) {
        }
        status = "waiting for the picture";
        for (int i = 0; i < 150 && !stop && !go; i++) {
            try { Thread.sleep(100); } catch (InterruptedException ie) { return; }
        }
        CacheDataSource ds = f.createDataSource();
        String variant = null;
        java.util.Map<String, Double> starts = new java.util.HashMap<>(); // segment address -> where it starts, seconds
        java.util.Set<String> done = new java.util.HashSet<>();
        while (!stop) {
            try {
                if (variant == null) {
                    String master = fetchText(masterUrl, headers);
                    for (String line : master.split("\n")) {
                        line = line.trim();
                        if (!line.isEmpty() && !line.startsWith("#")) {
                            variant = java.net.URI.create(masterUrl).resolve(line).toString();
                            break;
                        }
                    }
                    if (variant == null) {
                        status = "no playlist yet";
                        Thread.sleep(2000);
                        continue;
                    }
                }
                String pl = fetchText(variant, headers);
                java.util.List<String> uris = new java.util.ArrayList<>();
                java.util.List<Double> durs = new java.util.ArrayList<>();
                double dur = 0;
                for (String line : pl.split("\n")) {
                    line = line.trim();
                    if (line.startsWith("#EXTINF:")) {
                        String d = line.substring(8);
                        int comma = d.indexOf(',');
                        dur = Double.parseDouble(comma >= 0 ? d.substring(0, comma) : d);
                    } else if (!line.isEmpty() && !line.startsWith("#")) {
                        uris.add(java.net.URI.create(variant).resolve(line).toString());
                        durs.add(dur);
                    }
                }
                double t = 0;
                double endMax = 0;
                double playhead = Math.max(0, posMs) / 1000.0;
                boolean fetchedOne = false;
                for (int i = 0; i < uris.size() && !stop; i++) {
                    String u = uris.get(i);
                    double segStart = t;
                    t += durs.get(i);
                    starts.put(u, segStart);
                    if (segStart + durs.get(i) < playhead - 5) {
                        if (done.remove(u)) { // played: free its space on the disk
                            try { cache(c).removeResource(u); } catch (Throwable ignored) { }
                            hlsSegments = Math.max(0, hlsSegments - 1);
                        }
                        continue;
                    }
                    if (segStart > playhead + HLS_AHEAD_SEC) break;
                    if (!done.contains(u) && segStart < playhead + playerAheadMs / 1000.0) continue; // already in memory
                    // Memory first: while the player is still filling its memory from the network, the disk fill waits.
                    // Only when memory is full does the rest go to the disk, and the player later reads it from there.
                    for (int w = 0; w < 400 && playerLoading && !stop; w++) Thread.sleep(250);
                    if (stop) return;
                    if (!done.contains(u) && segStart < playhead + playerAheadMs / 1000.0) continue;
                    if (done.contains(u)) {
                        endMax = Math.max(endMax, segStart + durs.get(i));
                        hlsAheadSec = Math.max(Math.max(0, endMax - playhead), playerAheadMs / 1000.0);
                        continue;
                    }
                    long t0 = System.nanoTime();
                    final long[] got = {0};
                    new CacheWriter(ds, new DataSpec.Builder().setUri(Uri.parse(u)).build(), null,
                            (len, bytes, newBytes) -> got[0] += newBytes).cache();
                    done.add(u);
                    hlsSegments++;
                    hlsBytes += got[0];
                    endMax = Math.max(endMax, segStart + durs.get(i));
                    hlsAheadSec = Math.max(Math.max(0, endMax - playhead), playerAheadMs / 1000.0);
                    fetchedOne = true;
                    long ms = Math.max(1, (System.nanoTime() - t0) / 1_000_000L);
                    if (got[0] > 0) lastMbps = got[0] * 8.0 / 1000.0 / ms;
                    int mbit = c.getSharedPreferences("reel_ui", Context.MODE_PRIVATE).getInt("bigBufferMbit", 25);
                    if (mbit > 0 && got[0] > 0) {
                        long minMs = got[0] * 8000L / ((long) mbit * 1_000_000L);
                        if (ms < minMs) Thread.sleep(minMs - ms);
                    }
                    status = "ok";
                    playhead = Math.max(0, posMs) / 1000.0;
                }
                hlsAheadSec = Math.max(Math.max(0, endMax - playhead), playerAheadMs / 1000.0);
                if (!fetchedOne) {
                    status = "ok (up to date)";
                    Thread.sleep(2000);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                status = "err " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private static final double HLS_AHEAD_SEC = 900; // fifteen minutes of the converted stream

    /** Roughly how far past the playhead the background task has fetched. */
    long ahead() {
        return Math.max(0, aheadBytes);
    }

    void position(long ms) {
        posMs = ms;
    }

    private volatile boolean go;

    /** The player has its first picture: from now the background task may use the network too. */
    void playerReady() {
        go = true;
    }

    /** Removes everything stored for any other film. The film's cache key is its address. */
    private static void purgeOthers(Context c, String url) {
        try {
            SimpleCache sc = cache(c);
            for (String k : new java.util.ArrayList<>(sc.getKeys())) if (!k.equals(url)) sc.removeResource(k);
        } catch (Throwable ignored) {
        }
    }

    void start(final Context c, final String filmKey, final String url, final long durationMs) {
        stop();
        hlsMode = false;
        stop = false;
        go = false;
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
        purgeOthers(c, url);
        // Let the player have the whole connection until it is showing a picture (or 15 s have passed)
        status = "waiting for the picture";
        for (int i = 0; i < 150 && !stop && !go; i++) {
            try { Thread.sleep(100); } catch (InterruptedException ie) { return; }
        }
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
