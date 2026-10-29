package com.enthu.reel;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * A tiny web server so a phone on the same Wi-Fi can follow and control what the player is doing
 * (scan the QR code in the player's Info tab). The page lives in assets/remote.html. Every request needs the
 * random pairing code from the QR code's link.
 */
final class PhoneRemote {
    /** What the player gives the phone page. Both run on the main thread. */
    interface Host {
        String stateJson();

        String command(String name, Map<String, String> args);
    }

    private static final int[] PORTS = {8765, 8766, 8767, 8768};
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    private static Context app;
    private static ServerSocket server;
    private static volatile Host host;
    private static String token;
    private static int port;
    private static final Runnable STOP = PhoneRemote::stopServer;

    private PhoneRemote() {
    }

    /** The player is open: start serving (if not already) and send requests to [h]. */
    static synchronized void attach(Context c, Host h) {
        host = h;
        ensure(c);
    }

    /** Starts serving (if not already), so the phone page can search and send things to the TV even with nothing playing. */
    static synchronized void ensure(Context c) {
        app = c.getApplicationContext();
        MAIN.removeCallbacks(STOP);
        if (server != null) return;
        android.content.SharedPreferences sp = app.getSharedPreferences("reel_ui", Context.MODE_PRIVATE);
        if (token == null) {
            // Made once and kept for good, so a phone's bookmark or home-screen shortcut keeps working
            token = sp.getString("remoteToken", null);
            if (token == null || token.isEmpty()) {
                byte[] b = new byte[4];
                new SecureRandom().nextBytes(b);
                StringBuilder sb = new StringBuilder();
                for (byte x : b) sb.append(String.format("%02x", x));
                token = sb.toString();
                sp.edit().putString("remoteToken", token).apply();
            }
        }
        // The port used last time goes first, so the address stays the same too
        int last = sp.getInt("remotePort", 0);
        int[] order = new int[PORTS.length + 1];
        order[0] = last;
        System.arraycopy(PORTS, 0, order, 1, PORTS.length);
        for (int p : order) {
            if (p <= 0) continue;
            try {
                server = new ServerSocket(p);
                port = p;
                sp.edit().putInt("remotePort", p).apply();
                break;
            } catch (Exception ignored) {
            }
        }
        if (server == null) return;
        final ServerSocket s = server;
        new Thread(() -> {
            while (!s.isClosed()) {
                try {
                    final Socket sock = s.accept();
                    POOL.execute(() -> serve(sock));
                } catch (Exception e) {
                    return;
                }
            }
        }, "phone-remote").start();
    }

    /** The player closed. Keep the address alive a little in case the next episode opens straight away. */
    static synchronized void detach(Host h) {
        if (host != h) return;
        host = null; // the server stays up: the page still searches and casts
    }

    private static synchronized void stopServer() {
        if (host != null) return;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        server = null;
    }

    /** http://192.168.x.x:8765/?t=code, or null when there's no network. */
    static synchronized String url() {
        if (server == null) return null;
        String ip = localIp();
        return ip == null ? null : "http://" + ip + ":" + port + "/?t=" + token;
    }

    private static String localIp() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String n = ni.getName();
                        if (n.startsWith("wlan") || n.startsWith("eth")) return a.getHostAddress();
                        fallback = a.getHostAddress();
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ requests

    private static void serve(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String line = in.readLine();
            if (line == null) return;
            String h;
            while ((h = in.readLine()) != null && !h.isEmpty()) {
                // headers aren't needed
            }
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String path = parts[1];
            Map<String, String> q = new HashMap<>();
            int qi = path.indexOf('?');
            if (qi >= 0) {
                for (String kv : path.substring(qi + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e > 0) q.put(URLDecoder.decode(kv.substring(0, e), "UTF-8"),
                            URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
                }
                path = path.substring(0, qi);
            }
            OutputStream out = s.getOutputStream();
            if (token == null || !token.equals(q.get("t"))) {
                reply(out, 403, "text/plain", "Scan the QR code on the TV again.");
                return;
            }
            if (path.equals("/")) {
                reply(out, 200, "text/html; charset=utf-8", asset("remote.html"));
            } else if (path.equals("/state")) {
                reply(out, 200, "application/json; charset=utf-8", onMain(null, null));
            } else if (path.equals("/search")) {
                reply(out, 200, "application/json; charset=utf-8", search(q.get("q")));
            } else if (path.equals("/img")) {
                image(out, q.get("p"));
            } else if (path.equals("/cast")) {
                reply(out, 200, "application/json; charset=utf-8", cast(q.get("key"), "1".equals(q.get("play"))));
            } else if (path.equals("/cmd")) {
                String c = q.get("c");
                reply(out, 200, "application/json; charset=utf-8", onMain(c == null ? "" : c, q));
            } else {
                reply(out, 404, "text/plain", "Not found");
            }
        } catch (Exception ignored) {
        }
    }

    /** Titles in the library matching the phone's search, as JSON. Pictures come through /img, never with the Plex token. */
    private static String search(String text) {
        try {
            final String q = text == null ? "" : text.trim();
            org.json.JSONArray arr = new org.json.JSONArray();
            if (q.length() >= 2) {
                final SmartSearch.Query sq = SmartSearch.parse(q);
                java.util.List<Plex.Item> found = sq.smart() ? SmartSearch.run(sq) : Plex.search(q);
                for (Plex.Item it : found) {
                    if (arr.length() >= 30) break;
                    boolean ep = it.type.equals("episode");
                    String sub = it.type.equals("movie") ? "Movie" : it.type.equals("show") ? "TV show"
                            : ep ? "S" + it.parentIndex + " E" + it.index + " · " + it.title : it.type;
                    if (it.year > 0) sub += " · " + it.year;
                    arr.put(new org.json.JSONObject().put("key", it.key).put("title", it.displayTitle()).put("sub", sub)
                            .put("thumb", it.poster() == null ? "" : it.poster()).put("type", it.type));
                }
            }
            return new org.json.JSONObject().put("results", arr).toString();
        } catch (Exception e) {
            return "{\"results\":[],\"error\":true}";
        }
    }

    /** A poster for the phone page, fetched by the TV so the page never sees the server address or token. */
    private static void image(OutputStream out, String path) throws Exception {
        if (path == null || !path.startsWith("/")) {
            reply(out, 404, "text/plain", "Not found");
            return;
        }
        String url = Plex.img(path, 240, 360);
        if (url == null) {
            reply(out, 404, "text/plain", "Not found");
            return;
        }
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            byte[] body = bo.toByteArray();
            String head = "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + body.length
                    + "\r\nCache-Control: max-age=3600\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes("UTF-8"));
            out.write(body);
            out.flush();
        } finally {
            c.disconnect();
        }
    }

    /** Opens a title's page on the TV, or starts it playing. */
    private static String cast(final String key, final boolean play) {
        if (key == null || !key.matches("\\d+") || app == null) return "{\"ok\":false}";
        MAIN.post(() -> {
            Intent i = play ? new Intent(app, PlayerActivity.class).putExtra("key", key).putExtra("start", 0L)
                    : new Intent(app, DetailActivity.class).putExtra("key", key);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            final Intent go = i;
            final Host h = host;
            if (play && h != null) {
                // A film is already playing and holds the sound output (a second one can't open it): close it first
                h.command("close", null);
                MAIN.postDelayed(() -> app.startActivity(go), 700);
            } else {
                app.startActivity(go);
            }
        });
        return "{\"ok\":true}";
    }

    /** Runs on the main thread (the player isn't safe to touch from here) and waits for the answer. */
    private static String onMain(final String cmd, final Map<String, String> args) {
        final Host h = host;
        if (h == null) return "{\"playing\":false,\"idle\":true}";
        FutureTask<String> task = new FutureTask<>(() -> {
            if (cmd != null) h.command(cmd, args);
            return h.stateJson();
        });
        MAIN.post(task);
        try {
            return task.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "{\"idle\":true}";
        }
    }

    private static String asset(String name) {
        try (InputStream in = app.getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return "Remote page missing";
        }
    }

    private static void reply(OutputStream out, int code, String type, String body) throws Exception {
        byte[] b = body.getBytes("UTF-8");
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : code == 403 ? " Forbidden" : " Not Found") + "\r\n"
                + "Content-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }
}
