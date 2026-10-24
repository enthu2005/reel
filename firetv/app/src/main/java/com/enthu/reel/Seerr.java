package com.enthu.reel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Talks to Seerr (Overseerr / Jellyseerr) so you can find things that aren't in Plex yet and
 * request them. Signs in with the same Plex account Reel uses, so only the address is needed.
 */
final class Seerr {
    private Seerr() {
    }

    static final String IMG = "https://image.tmdb.org/t/p/";

    /** A movie or show as Seerr describes it. */
    static final class Media {
        String type = "movie"; // movie or tv
        int id;                // TMDB id
        String title = "";
        int year;
        String overview = "";
        String poster;
        String backdrop;
        int status;            // 1 unknown, 2 pending, 3 processing, 4 partly available, 5 available
        String ratingKey;      // Plex item, once it's in your library
        int requestStatus;     // for "My requests": 1 waiting for approval, 2 approved, 3 declined
        final List<int[]> seasons = new ArrayList<>(); // {seasonNumber, episodeCount, status}
        int dlPercent = -1;      // from Radarr / Sonarr's download queue, via Seerr
        long dlMinutesLeft = -1;
        String digitalRelease;   // e.g. "12 Nov 2026" (movies, from the full details)
        boolean digitalReleaseFuture;
        String nextEpisode;      // e.g. "S2 E5 on 3 Nov 2026" (shows)
        String youtubeTrailer;   // YouTube video id of the trailer (full details only)

        /** The best "when will I get it" line Seerr can give, or "" if nothing useful. */
        String eta() {
            if (status == 5) return "";
            if (dlPercent >= 0) {
                String t = dlMinutesLeft < 0 ? "" : dlMinutesLeft < 1 ? " \u00B7 almost done"
                        : dlMinutesLeft < 90 ? " \u00B7 about " + dlMinutesLeft + " min left"
                        : " \u00B7 about " + Math.round(dlMinutesLeft / 60f) + " hr left";
                return "Downloading \u00B7 " + dlPercent + "%" + t;
            }
            if (digitalRelease != null && digitalReleaseFuture) {
                return (status >= 2 ? "Requested \u00B7 " : "") + "Out digitally on " + digitalRelease
                        + (status >= 2 ? ", it'll download then" : "");
            }
            if (nextEpisode != null) return "Next episode: " + nextEpisode;
            if (status == 3) return "Approved \u00B7 waiting for a download to be found";
            if (status == 2) return "Waiting for approval";
            return "";
        }

        String key() {
            return "seerr:" + type + ":" + id;
        }

        String statusText() {
            if (requestStatus == 3) return "Declined";
            if (requestStatus == 1 && status < 4) return "Waiting for approval";
            switch (status) {
                case 2:
                    return "Requested";
                case 3:
                    return "Downloading";
                case 4:
                    return "Partly available";
                case 5:
                    return "Available";
                default:
                    return "";
            }
        }

        boolean requestable() {
            return status <= 1 || (type.equals("tv") && status == 4);
        }

        /** As a Plex.Item so the normal cards can show it. */
        Plex.Item toItem() {
            Plex.Item i = new Plex.Item();
            i.key = key();
            i.type = type.equals("tv") ? "show" : "movie";
            i.title = title;
            i.summary = overview;
            i.year = year;
            i.thumb = poster;
            i.art = backdrop;
            i.watcher = statusText();
            return i;
        }
    }

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
    }

    private static SharedPreferences prefs() {
        return app.getSharedPreferences("reel_ui", Context.MODE_PRIVATE);
    }

    static String base() {
        if (app == null) return "";
        String u = prefs().getString("seerrUrl", "").trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    static boolean configured() {
        return !base().isEmpty();
    }

    static void setBase(String url) {
        String u = url == null ? "" : url.trim();
        if (!u.isEmpty() && !u.startsWith("http")) u = "http://" + u;
        prefs().edit().putString("seerrUrl", u).remove("seerrCookie").apply();
    }

    // ----------------------------------------------------------- HTTP

    private static String send(String method, String path, String body, String cookie, String[] outCookie)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base() + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/json");
        if (cookie != null) c.setRequestProperty("Cookie", cookie);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes("UTF-8"));
            }
        }
        int code = c.getResponseCode();
        if (outCookie != null) {
            Map<String, List<String>> h = c.getHeaderFields();
            for (Map.Entry<String, List<String>> e : h.entrySet()) {
                if (e.getKey() == null || !e.getKey().equalsIgnoreCase("Set-Cookie")) continue;
                StringBuilder sb = new StringBuilder();
                for (String v : e.getValue()) {
                    String part = v.split(";", 2)[0];
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(part);
                }
                outCookie[0] = sb.toString();
            }
        }
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = "";
        if (in != null) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            text = sb.toString();
        }
        if (code == 401 || code == 403) throw new AuthException();
        if (code >= 400) {
            String msg = "";
            try {
                msg = new JSONObject(text).optString("message", "");
            } catch (Exception ignored) {
            }
            throw new Exception("Seerr said HTTP " + code + (msg.isEmpty() ? "" : ": " + msg));
        }
        return text;
    }

    static final class AuthException extends Exception {
        AuthException() {
            super("Seerr didn't accept the sign-in");
        }
    }

    /** Signs in with the Plex account. Returns the Seerr display name. */
    static String login() throws Exception {
        if (!configured()) throw new Exception("No Seerr address set");
        String[] ck = new String[1];
        JSONObject body = new JSONObject().put("authToken", Plex.plexToken());
        String r = send("POST", "/api/v1/auth/plex", body.toString(), null, ck);
        if (ck[0] == null || ck[0].isEmpty()) throw new Exception("Seerr didn't return a session");
        prefs().edit().putString("seerrCookie", ck[0]).apply();
        JSONObject u = new JSONObject(r);
        return u.optString("displayName", u.optString("plexUsername", u.optString("email", "you")));
    }

    private static String call(String method, String path, String body) throws Exception {
        String cookie = prefs().getString("seerrCookie", null);
        if (cookie == null) {
            login();
            cookie = prefs().getString("seerrCookie", null);
        }
        try {
            return send(method, path, body, cookie, null);
        } catch (AuthException e) {
            login(); // session expired: sign in again once
            return send(method, path, body, prefs().getString("seerrCookie", null), null);
        }
    }

    // ---------------------------------------------------------- Lists

    private static List<Media> results(String path) throws Exception {
        return results(path, null);
    }

    private static List<Media> results(String path, String forceType) throws Exception {
        JSONObject o = new JSONObject(call("GET", path, null));
        JSONArray a = o.optJSONArray("results");
        List<Media> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject m = a.getJSONObject(i);
            String t = forceType != null ? forceType : m.optString("mediaType", "");
            if (t.equals("person")) continue;
            Media x = parse(m, t.isEmpty() ? null : t);
            if (x != null) out.add(x);
        }
        return out;
    }

    static List<Media> trending() throws Exception {
        return results("/api/v1/discover/trending?page=1");
    }

    static List<Media> popularMovies() throws Exception {
        return results("/api/v1/discover/movies?page=1");
    }

    static List<Media> popularShows() throws Exception {
        return results("/api/v1/discover/tv?page=1");
    }

    static List<Media> upcomingMovies() throws Exception {
        return results("/api/v1/discover/movies/upcoming?page=1");
    }

    /** Genres with a picture: {id, name, backdrop url}. type is "movie" or "tv". */
    static List<String[]> genres(String type) throws Exception {
        JSONArray a = new JSONArray(call("GET", "/api/v1/discover/genreslider/" + type, null));
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject g = a.getJSONObject(i);
            JSONArray b = g.optJSONArray("backdrops");
            String img = b != null && b.length() > 0 ? IMG + "w780" + b.optString(0) : null;
            out.add(new String[]{String.valueOf(g.optInt("id")), g.optString("name"), img});
        }
        return out;
    }

    static List<Media> byGenre(String type, String genreId, int page) throws Exception {
        String path = type.equals("tv") ? "/api/v1/discover/tv/genre/" + genreId
                : "/api/v1/discover/movies/genre/" + genreId;
        return results(path + "?page=" + page, type);
    }

    /** "More like this" for a title Seerr knows by its TMDB id. */
    static List<Media> recommendations(String type, String tmdbId) throws Exception {
        return results("/api/v1/" + (type.equals("tv") ? "tv/" : "movie/") + tmdbId + "/recommendations?page=1", type);
    }

    static List<Media> search(String q) throws Exception {
        String enc = URLEncoder.encode(q, "UTF-8").replace("+", "%20");
        return results("/api/v1/search?query=" + enc + "&page=1");
    }

    /** Your most recent requests, with their titles and posters filled in. */
    static List<Media> myRequests() throws Exception {
        return myRequests(20);
    }

    static List<Media> myRequests(int take) throws Exception {
        JSONObject o = new JSONObject(call("GET", "/api/v1/request?take=" + take + "&skip=0&sort=added&filter=all", null));
        JSONArray a = o.optJSONArray("results");
        List<Media> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.getJSONObject(i);
            JSONObject media = r.optJSONObject("media");
            if (media == null) continue;
            String type = media.optString("mediaType", "movie");
            int tmdb = media.optInt("tmdbId", 0);
            if (tmdb == 0) continue;
            try {
                Media m = details(type, tmdb);
                m.requestStatus = r.optInt("status", 0);
                out.add(m);
            } catch (Exception ignored) {
                // skip one we can't look up
            }
        }
        return out;
    }

    static Media details(String type, int id) throws Exception {
        String path = type.equals("tv") ? "/api/v1/tv/" + id : "/api/v1/movie/" + id;
        return parse(new JSONObject(call("GET", path, null)), type);
    }

    private static Media parse(JSONObject m, String type) {
        Media x = new Media();
        x.type = type != null ? type : (m.has("name") && !m.has("title") ? "tv" : "movie");
        x.id = m.optInt("id", 0);
        if (x.id == 0) return null;
        x.title = m.optString(x.type.equals("tv") ? "name" : "title", m.optString("title", m.optString("name", "")));
        String date = m.optString(x.type.equals("tv") ? "firstAirDate" : "releaseDate", "");
        if (date.length() >= 4) {
            try {
                x.year = Integer.parseInt(date.substring(0, 4));
            } catch (Exception ignored) {
            }
        }
        x.overview = m.optString("overview", "");
        String p = m.optString("posterPath", "");
        String b = m.optString("backdropPath", "");
        if (!p.isEmpty() && !p.equals("null")) x.poster = IMG + "w342" + p;
        if (!b.isEmpty() && !b.equals("null")) x.backdrop = IMG + "w1280" + b;
        JSONObject info = m.optJSONObject("mediaInfo");
        if (info != null) {
            x.status = info.optInt("status", 1);
            String rk = info.optString("ratingKey", "");
            if (!rk.isEmpty() && !rk.equals("null")) x.ratingKey = rk;
            // Live download progress, if Seerr is linked to Radarr / Sonarr
            JSONArray dl = info.optJSONArray("downloadStatus");
            if (dl != null && dl.length() > 0) {
                long size = 0, left = 0, minutes = -1;
                for (int i = 0; i < dl.length(); i++) {
                    JSONObject d = dl.optJSONObject(i);
                    if (d == null) continue;
                    size += d.optLong("size", 0);
                    left += d.optLong("sizeLeft", 0);
                    long mins = minutesUntil(d.optString("estimatedCompletionTime", ""));
                    if (mins < 0) mins = parseTimeLeft(d.optString("timeLeft", ""));
                    if (mins > minutes) minutes = mins;
                }
                if (size > 0) {
                    x.dlPercent = (int) Math.max(0, Math.min(100, 100 - left * 100 / size));
                    x.dlMinutesLeft = minutes;
                }
            }
        }
        // Digital release date (movies): the UK first, then the US
        JSONObject rel = m.optJSONObject("releases");
        JSONArray countries = rel != null ? rel.optJSONArray("results") : null;
        if (countries != null) {
            String best = null;
            for (String want : new String[]{"GB", "US"}) {
                for (int i = 0; i < countries.length() && best == null; i++) {
                    JSONObject c = countries.optJSONObject(i);
                    if (c == null || !want.equals(c.optString("iso_3166_1"))) continue;
                    JSONArray dates = c.optJSONArray("release_dates");
                    if (dates == null) continue;
                    for (int j = 0; j < dates.length(); j++) {
                        JSONObject d = dates.optJSONObject(j);
                        if (d != null && d.optInt("type") == 4) best = d.optString("release_date", "");
                    }
                }
                if (best != null) break;
            }
            if (best != null && best.length() >= 10) {
                x.digitalRelease = niceDate(best.substring(0, 10));
                x.digitalReleaseFuture = best.substring(0, 10).compareTo(today()) > 0;
            }
        }
        JSONArray vids = m.optJSONArray("relatedVideos");
        if (vids != null) {
            for (int i = 0; i < vids.length() && x.youtubeTrailer == null; i++) {
                JSONObject v = vids.optJSONObject(i);
                if (v != null && "YouTube".equals(v.optString("site")) && "Trailer".equals(v.optString("type"))) {
                    x.youtubeTrailer = v.optString("key", null);
                }
            }
            for (int i = 0; i < vids.length() && x.youtubeTrailer == null; i++) {
                JSONObject v = vids.optJSONObject(i);
                if (v != null && "YouTube".equals(v.optString("site"))) x.youtubeTrailer = v.optString("key", null);
            }
        }
        JSONObject next = m.optJSONObject("nextEpisodeToAir");
        if (next != null && next.optString("airDate", "").length() >= 10) {
            x.nextEpisode = "S" + next.optInt("seasonNumber") + " E" + next.optInt("episodeNumber")
                    + " on " + niceDate(next.optString("airDate").substring(0, 10));
        }
        JSONArray seasons = m.optJSONArray("seasons");
        if (seasons != null) {
            JSONArray have = info != null ? info.optJSONArray("seasons") : null;
            for (int i = 0; i < seasons.length(); i++) {
                JSONObject s = seasons.optJSONObject(i);
                if (s == null) continue;
                int n = s.optInt("seasonNumber", 0);
                if (n <= 0) continue; // skip specials
                int st = 1;
                if (have != null) {
                    for (int j = 0; j < have.length(); j++) {
                        JSONObject h = have.optJSONObject(j);
                        if (h != null && h.optInt("seasonNumber", -1) == n) st = h.optInt("status", 1);
                    }
                }
                x.seasons.add(new int[]{n, s.optInt("episodeCount", 0), st});
            }
        }
        return x;
    }

    private static String today() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date());
    }

    private static String niceDate(String ymd) {
        try {
            java.util.Date d = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).parse(ymd);
            return new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK).format(d);
        } catch (Exception e) {
            return ymd;
        }
    }

    private static long minutesUntil(String iso) {
        if (iso == null || iso.length() < 19) return -1;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            long t = f.parse(iso.substring(0, 19)).getTime();
            return Math.max(0, (t - System.currentTimeMillis()) / 60000L);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Radarr / Sonarr style "01:23:45" (or "1.02:03:04" with days). */
    private static long parseTimeLeft(String s) {
        if (s == null || s.isEmpty()) return -1;
        try {
            long days = 0;
            if (s.contains(".")) {
                days = Long.parseLong(s.substring(0, s.indexOf('.')));
                s = s.substring(s.indexOf('.') + 1);
            }
            String[] p = s.split(":");
            if (p.length < 2) return -1;
            return days * 1440 + Long.parseLong(p[0]) * 60 + Long.parseLong(p[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Titles like this one (Seerr's recommendations, falling back to "similar"). */
    static List<Media> moreLike(Media m) {
        String base = "/api/v1/" + (m.type.equals("tv") ? "tv/" : "movie/") + m.id;
        try {
            List<Media> r = results(base + "/recommendations?page=1", m.type);
            if (r.size() >= 4) return r;
        } catch (Exception ignored) {
        }
        try {
            return results(base + "/similar?page=1", m.type);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // -------------------------------------------------------- Requests

    static void requestMovie(int tmdbId) throws Exception {
        JSONObject b = new JSONObject().put("mediaType", "movie").put("mediaId", tmdbId);
        call("POST", "/api/v1/request", b.toString());
    }

    static void requestShow(int tmdbId, List<Integer> seasons) throws Exception {
        JSONArray s = new JSONArray();
        for (int n : seasons) s.put(n);
        JSONObject b = new JSONObject().put("mediaType", "tv").put("mediaId", tmdbId).put("seasons", s);
        call("POST", "/api/v1/request", b.toString());
    }
}
