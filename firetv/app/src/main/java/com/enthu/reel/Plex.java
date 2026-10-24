package com.enthu.reel;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal Plex client: sign-in with a PIN, find your server, read the library, build play URLs. */
public final class Plex {
    private Plex() {
    }

    public interface Callback<T> {
        void ok(T result);

        void fail(Exception e);
    }

    public static final Callback<Boolean> NOOP = new Callback<Boolean>() {
        @Override
        public void ok(Boolean result) {
        }

        @Override
        public void fail(Exception e) {
        }
    };

    /** One audio, video or subtitle stream inside a file. */
    public static class Stream {
        public int id;
        public int type; // 1 video, 2 audio, 3 subtitle
        public String codec = "";
        public String profile = "";
        public int channels;
        public String layout = "";
        public String lang = "";
        public String langCode = "";
        public String displayTitle = "";
        public String extTitle = "";
        public String title = "";
        public boolean selected;
        public boolean isDefault;
        public boolean forced;
        public String key; // set for subtitle files that sit outside the video file
        public String format = "";
        public boolean dovi;
        public int doviProfile; // 5, 7, 8… (0 = unknown / none)
        public String colorTrc = "";
        public int bitDepth;
        public int height;
        public int bitrate;

        public boolean isExternal() {
            return type == 3 && key != null && !key.isEmpty();
        }

        public String label() {
            if (!extTitle.isEmpty()) return extTitle;
            if (!displayTitle.isEmpty()) return displayTitle;
            if (!title.isEmpty()) return title;
            return lang.isEmpty() ? "Track " + id : lang;
        }
    }

    public static class SubResult {
        public String key;
        public String codec = "srt";
        public String language = "en";
        public String provider = "";
        public String title = "";
        public boolean hearingImpaired;
        public double score;
    }

    public static class Section {
        public String key;
        public String type;
        public String title;
        public List<String> paths = new ArrayList<>(); // the folders on the server this library reads from
    }

    public static class Cast {
        public String name = "";
        public String role = "";
        public String thumb;
        public String filter = ""; // e.g. "actor=1234"
    }

    public static class Item {
        public String key = "";
        public String type = "";
        public String title = "";
        public String summary = "";
        public int year;
        public int librarySectionID;
        public String librarySectionTitle = "";
        public String contentRating = "";
        public String tagline = "";
        public long addedAt;          // seconds since 1970
        public int leafCount;         // episodes in a show
        public int viewedLeafCount;   // ...of which watched
        public String collName = "";   // the collection / franchise it belongs to, if any
        public String collFilter = ""; // e.g. "collection=123"
        public final List<String[]> ratings = new ArrayList<>(); // {source, shown value}
        public String thumb;
        public String art;
        public String logo;
        public String gpTitle;
        public final List<long[]> intros = new ArrayList<>();  // {start, end} in ms
        public final List<long[]> credits = new ArrayList<>();
        public final List<Cast> cast = new ArrayList<>();
        public final List<Cast> directors = new ArrayList<>();
        public String gpKey; // rating key of the show an episode belongs to
        public String gpThumb;
        public String gpArt;
        public long duration;
        public long viewOffset;
        public int viewCount;
        public int index;
        public int parentIndex;
        public String partKey;
        public String videoRes = "";
        public int videoHeight;
        public String watcher = ""; // who is watching it right now (sessions only)
        public List<Stream> streams = new ArrayList<>();

        public List<Stream> streamsOf(int type) {
            List<Stream> out = new ArrayList<>();
            for (Stream s : streams) if (s.type == type) out.add(s);
            return out;
        }

        /** Watched all the way through (a film or episode seen, or every episode of a show). */
        public boolean isWatched() {
            if (type.equals("show") || type.equals("season")) return leafCount > 0 && viewedLeafCount >= leafCount;
            return viewCount > 0 && viewOffset <= 0;
        }

        /** Added in the last week and not started. */
        public boolean isNew() {
            if (addedAt <= 0 || isWatched() || viewOffset > 0 || viewedLeafCount > 0) return false;
            return System.currentTimeMillis() / 1000L - addedAt < 7 * 24 * 3600L;
        }

        public boolean isPlayable() {
            return type.equals("movie") || type.equals("episode");
        }

        public String heroArt() {
            return art != null ? art : gpArt;
        }

        public String poster() {
            if (type.equals("episode") && gpThumb != null) return gpThumb;
            return thumb;
        }

        public String displayTitle() {
            if (type.equals("episode") && gpTitle != null) return gpTitle;
            return title;
        }

        public String metaLine() {
            return metaLine(true);
        }

        public String metaLine(boolean withRuntime) {
            List<String> p = new ArrayList<>();
            switch (type) {
                case "movie":
                    p.add("Movie");
                    break;
                case "show":
                    p.add("TV Show");
                    break;
                case "season":
                    p.add("Season");
                    break;
                case "episode":
                    if (parentIndex > 0) p.add("S" + parentIndex + " E" + index);
                    p.add(title);
                    break;
                default:
                    p.add(type);
            }
            if (!type.equals("episode") && year > 0) p.add(String.valueOf(year));
            if (!contentRating.isEmpty()) p.add(contentRating);
            if (withRuntime && duration > 0 && isPlayable()) p.add(UiUtil.duration(duration));
            return TextUtils.join(" · ", p);
        }
    }

    // ------------------------------------------------------------ State

    private static SharedPreferences prefs;
    private static String clientId;
    public static String token;
    public static String serverToken; // the token this server wants (for a shared server it differs from your account token)
    public static String mainToken;   // the account owner's token (kept so other profiles can be switched to)
    public static String profileName; // the profile in use (null = the main account)
    public static boolean profileChosen; // "Who's watching?" already handled in this run of the app
    public static String serverUri;
    public static String serverName;

    private static final ExecutorService IO = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void init(Context c) {
        if (prefs != null) return;
        prefs = c.getApplicationContext().getSharedPreferences("reel", Context.MODE_PRIVATE);
        apiCache = new java.io.File(c.getApplicationContext().getCacheDir(), "api");
        apiCache.mkdirs();
        clientId = prefs.getString("cid", null);
        if (clientId == null) {
            clientId = UUID.randomUUID().toString();
            prefs.edit().putString("cid", clientId).apply();
        }
        token = prefs.getString("token", null);
        mainToken = prefs.getString("mainToken", token);
        profileName = prefs.getString("profileName", null);
        serverToken = prefs.getString("serverToken", null);
        serverUri = prefs.getString("server", null);
        serverName = prefs.getString("serverName", null);
    }

    public static boolean isReady() {
        return token != null && serverUri != null;
    }

    private static String st() {
        return serverToken != null && !serverToken.isEmpty() ? serverToken : token;
    }

    public static void signOut() {
        serverToken = null;
        token = null;
        mainToken = null;
        profileName = null;
        profileChosen = false;
        serverUri = null;
        serverName = null;
        prefs.edit().remove("token").remove("mainToken").remove("profileName").remove("profileId")
                .remove("server").remove("serverName").remove("serverToken").apply();
    }

    public static void saveToken(String t) {
        token = t;
        mainToken = t;
        serverToken = null;
        profileName = null;
        profileChosen = true; // just signed in: no need to ask who is watching
        prefs.edit().putString("token", t).putString("mainToken", t)
                .remove("profileName").remove("profileId").apply();
    }

    // ---------------------------------------------------- Profiles (Plex Home users)

    public static class Profile {
        public String id = "";
        public String title = "";
        public String thumb = "";
        public boolean hasPin;
        public boolean admin;
    }

    private static String plexTv(String method, String url, String tk) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/xml");
        c.setRequestProperty("X-Plex-Client-Identifier", clientId);
        c.setRequestProperty("X-Plex-Product", "Reel");
        c.setRequestProperty("X-Plex-Version", "0.1");
        c.setRequestProperty("X-Plex-Platform", "Android");
        c.setRequestProperty("X-Plex-Device", "Fire TV");
        c.setRequestProperty("X-Plex-Device-Name", "Reel");
        if (tk != null) c.setRequestProperty("X-Plex-Token", tk);
        if (method.equals("POST")) {
            c.setDoOutput(true);
            c.getOutputStream().close();
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
        }
        if (code >= 400) throw new Exception("HTTP " + code);
        return sb.toString();
    }

    private static String xmlUnescape(String v) {
        return v.replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private static java.util.Map<String, String> xmlAttrs(String tagBody) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        java.util.regex.Matcher a = java.util.regex.Pattern.compile("([\\w:-]+)=\"([^\"]*)\"").matcher(tagBody);
        while (a.find()) m.put(a.group(1), xmlUnescape(a.group(2)));
        return m;
    }

    /** The people in your Plex Home (the profiles). */
    public static List<Profile> homeUsers() throws Exception {
        String body = plexTv("GET", "https://plex.tv/api/home/users", mainToken != null ? mainToken : token);
        List<Profile> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<User\\s([^>]*?)/?>").matcher(body);
        while (m.find()) {
            java.util.Map<String, String> a = xmlAttrs(m.group(1));
            if (!a.containsKey("id")) continue;
            Profile p = new Profile();
            p.id = a.get("id");
            p.title = a.containsKey("title") && !a.get("title").isEmpty() ? a.get("title")
                    : a.containsKey("username") ? a.get("username") : "Profile";
            p.thumb = a.containsKey("thumb") ? a.get("thumb") : "";
            p.hasPin = "1".equals(a.get("protected"));
            p.admin = "1".equals(a.get("admin"));
            out.add(p);
        }
        return out;
    }

    /** Switches to a profile (with its PIN if it has one) and returns that profile's token. */
    public static String switchProfile(Profile p, String pin) throws Exception {
        String url = "https://plex.tv/api/home/users/" + p.id + "/switch"
                + (pin != null && !pin.isEmpty() ? "?pin=" + enc(pin) : "");
        String body = plexTv("POST", url, mainToken != null ? mainToken : token);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<user\\s([^>]*?)/?>",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(body);
        if (m.find()) {
            String t = xmlAttrs(m.group(1)).get("authenticationToken");
            if (t != null && !t.isEmpty()) return t;
        }
        throw new Exception("No token came back");
    }

    public static void useProfile(String tok, Profile p) {
        token = tok;
        serverToken = null;
        profileName = p.title;
        profileChosen = true;
        serverUri = null; // look the server up again for this profile
        prefs.edit().putString("token", tok).putString("profileName", p.title).putString("profileId", p.id)
                .remove("server").remove("serverToken").apply();
    }

    /** Back to the account owner's own sign-in (used when a profile can't open the server). */
    public static void useMainAccount() {
        token = mainToken;
        serverToken = null;
        serverUri = null;
        profileName = null;
        prefs.edit().putString("token", mainToken).remove("profileName").remove("profileId")
                .remove("server").remove("serverToken").apply();
    }

    /** The signed-in Plex token (used to sign in to Seerr with the same account). */
    public static String plexToken() {
        return token;
    }

    public static String clientId() {
        return clientId;
    }

    public static <T> void async(final Callable<T> job, final Callback<T> cb) {
        IO.execute(() -> {
            try {
                final T result = job.call();
                MAIN.post(() -> cb.ok(result));
            } catch (final Exception e) {
                MAIN.post(() -> cb.fail(e));
            }
        });
    }

    // ------------------------------------------------------------- HTTP

    private static String http(String method, String url, int timeoutMs) throws Exception {
        return http(method, url, timeoutMs, 0);
    }

    private static String http(String method, String url, int timeoutMs, int redirects) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-Plex-Client-Identifier", clientId);
        c.setRequestProperty("X-Plex-Product", "Reel");
        c.setRequestProperty("X-Plex-Version", "0.1");
        c.setRequestProperty("X-Plex-Platform", "Android");
        c.setRequestProperty("X-Plex-Device", "Fire TV");
        c.setRequestProperty("X-Plex-Device-Name", "Reel");
        String tk = token;
        if (serverToken != null && !serverToken.isEmpty() && serverUri != null && url.startsWith(serverUri)) tk = serverToken;
        if (tk != null) c.setRequestProperty("X-Plex-Token", tk);
        if (method.equals("POST") || method.equals("PUT")) {
            c.setDoOutput(true);
            c.getOutputStream().close();
        }
        int code = c.getResponseCode();
        if (code >= 300 && code < 400 && redirects < 3) {
            // Java won't follow a redirect from http to https by itself (Plex does this when
            // "Secure connections" is Required), so follow it here.
            String to = c.getHeaderField("Location");
            if (to != null && !to.isEmpty()) return http(method, new URL(new URL(url), to).toString(), timeoutMs, redirects + 1);
        }
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = "";
        if (in != null) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            body = sb.toString();
        }
        if (code >= 400) throw new Exception("HTTP " + code);
        return body;
    }

    // Library answers are kept on disk so Home can open straight away and refresh behind the scenes.
    private static java.io.File apiCache;
    private static final ThreadLocal<Boolean> CACHE_ONLY = new ThreadLocal<>();

    private static java.io.File cacheFile(String path) {
        if (apiCache == null || serverUri == null || !path.startsWith("/library/")) return null;
        String k = Integer.toHexString((serverUri + "|" + profileName + "|" + path).hashCode());
        return new java.io.File(apiCache, k + ".json");
    }

    /** Runs the job reading only what was saved earlier: missing answers come back empty, nothing hits the network. */
    public static <T> T cacheOnly(Callable<T> job) throws Exception {
        CACHE_ONLY.set(Boolean.TRUE);
        try {
            return job.call();
        } finally {
            CACHE_ONLY.remove();
        }
    }

    private static JSONObject get(String path) throws Exception {
        java.io.File cf = cacheFile(path);
        if (Boolean.TRUE.equals(CACHE_ONLY.get())) {
            if (cf != null && cf.exists()) {
                try {
                    java.io.FileInputStream in = new java.io.FileInputStream(cf);
                    byte[] buf = new byte[(int) cf.length()];
                    int n = 0;
                    while (n < buf.length) {
                        int r = in.read(buf, n, buf.length - n);
                        if (r < 0) break;
                        n += r;
                    }
                    in.close();
                    return new JSONObject(new String(buf, 0, n, "UTF-8"));
                } catch (Exception ignored) {
                    // fall through to empty
                }
            }
            return new JSONObject("{\"MediaContainer\":{}}");
        }
        String body = http("GET", serverUri + path, 15000);
        if (cf != null && body.length() < 2_000_000) {
            try {
                java.io.FileOutputStream out = new java.io.FileOutputStream(cf);
                out.write(body.getBytes("UTF-8"));
                out.close();
            } catch (Exception ignored) {
                // no cache this time
            }
        }
        return new JSONObject(body);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    public static String paged(String path, int size) {
        return path + (path.contains("?") ? "&" : "?")
                + "X-Plex-Container-Start=0&X-Plex-Container-Size=" + size;
    }

    // ----------------------------------------------------------- Sign-in

    public static JSONObject createPin() throws Exception {
        return new JSONObject(http("POST", "https://plex.tv/api/v2/pins?strong=false", 10000));
    }

    /** Returns the auth token once the user has entered the code at plex.tv/link, else null. */
    public static String pollPin(long id) throws Exception {
        JSONObject o = new JSONObject(http("GET", "https://plex.tv/api/v2/pins/" + id, 10000));
        return o.isNull("authToken") ? null : o.getString("authToken");
    }

    /** Picks your own server first, trying local addresses before remote and relay ones. */
    public static void findServer() throws Exception {
        JSONArray res = new JSONArray(http("GET",
                "https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", 15000));

        List<String[]> candidates = new ArrayList<>(); // {uri, name}
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < res.length(); i++) {
                JSONObject r = res.getJSONObject(i);
                if (!r.optString("provides", "").contains("server")) continue;
                boolean owned = r.optBoolean("owned", false);
                if ((pass == 0) != owned) continue;
                String name = r.optString("name", "Plex");
                final String acc = owned ? "" : r.optString("accessToken", "");
                JSONArray conns = r.optJSONArray("connections");
                if (conns == null) continue;

                List<String> local = new ArrayList<>();
                List<String> remote = new ArrayList<>();
                List<String> relay = new ArrayList<>();
                for (int k = 0; k < conns.length(); k++) {
                    JSONObject c = conns.getJSONObject(k);
                    String uri = c.optString("uri", "");
                    if (c.optBoolean("relay", false)) {
                        relay.add(uri);
                    } else if (c.optBoolean("local", false)) {
                        String addr = c.optString("address", "");
                        if (!addr.isEmpty()) local.add("http://" + addr + ":" + c.optInt("port", 32400));
                        local.add(uri);
                    } else {
                        remote.add(uri);
                    }
                }
                for (String u : local) candidates.add(new String[]{u, name, acc});
                for (String u : remote) candidates.add(new String[]{u, name, acc});
                for (String u : relay) candidates.add(new String[]{u, name, acc});
            }
        }

        // Addresses you've pinned yourself go first: the one saved in prefs ("manualServer"), then the
        // home NAS at 192.168.0.199, which plex.tv doesn't always list (it only knows what the server reports).
        List<String[]> pinned = new ArrayList<>();
        String nasName = candidates.isEmpty() ? "Plex" : candidates.get(0)[1];
        String nasAcc = candidates.isEmpty() ? "" : candidates.get(0)[2];
        String manual = prefs == null ? "" : prefs.getString("manualServer", "");
        if (!manual.isEmpty()) {
            if (!manual.startsWith("http")) manual = "http://" + manual;
            if (manual.indexOf(':', 7) < 0) manual = manual + ":32400";
            pinned.add(new String[]{manual, nasName, nasAcc});
        }
        pinned.add(new String[]{"http://192.168.0.199:32400", nasName, nasAcc});
        candidates.addAll(0, pinned);

        // Test every address at the same time, then take the best one that answered
        // (the list is already ordered: local, then remote, then relay).
        final List<String[]> tryList = new ArrayList<>();
        for (String[] c : candidates) {
            if (!c[0].isEmpty()) tryList.add(c);
        }
        if (tryList.isEmpty()) {
            throw new Exception("No Plex server found on this account");
        }

        final int n = tryList.size();
        final boolean[] answered = new boolean[n];
        final CountDownLatch latch = new CountDownLatch(n);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(n, 12));
        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.execute(() -> {
                try {
                    http("GET", tryList.get(idx)[0] + "/identity", 5000);
                    answered[idx] = true;
                } catch (Exception ignored) {
                    // this address didn't answer
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(8, TimeUnit.SECONDS);
        pool.shutdownNow();

        for (int i = 0; i < n; i++) {
            if (answered[i]) {
                serverUri = tryList.get(i)[0];
                serverName = tryList.get(i)[1];
                serverToken = tryList.get(i)[2].isEmpty() ? null : tryList.get(i)[2];
                SharedPreferences.Editor ed = prefs.edit().putString("server", serverUri).putString("serverName", serverName);
                if (serverToken == null) ed.remove("serverToken");
                else ed.putString("serverToken", serverToken);
                ed.apply();
                return;
            }
        }
        throw new Exception("Found your server but none of its " + n + " addresses answered");
    }

    // ----------------------------------------------------------- Library

    /** Every library, including photos and anything else (used by the Libraries page). */
    public static List<Section> allSections() throws Exception {
        JSONArray d = get("/library/sections").getJSONObject("MediaContainer").optJSONArray("Directory");
        List<Section> out = new ArrayList<>();
        if (d == null) return out;
        for (int i = 0; i < d.length(); i++) {
            JSONObject o = d.getJSONObject(i);
            Section s = new Section();
            s.key = o.optString("key");
            s.type = o.optString("type");
            s.title = o.optString("title");
            JSONArray loc = o.optJSONArray("Location");
            if (loc != null) {
                for (int j = 0; j < loc.length(); j++) {
                    String path = loc.getJSONObject(j).optString("path");
                    if (!path.isEmpty()) s.paths.add(path);
                }
            }
            out.add(s);
        }
        return out;
    }

    /** How many titles a library holds. */
    public static int sectionCount(String sectionKey) throws Exception {
        JSONObject mc = get(paged("/library/sections/" + sectionKey + "/all", 0)).getJSONObject("MediaContainer");
        return mc.optInt("totalSize", mc.optInt("size", 0));
    }

    /** The newest title in a library (its artwork stands in for the library), or null if it's empty. */
    public static Item sectionLatest(String sectionKey) throws Exception {
        List<Item> l = items(paged("/library/sections/" + sectionKey + "/all?sort=addedAt:desc", 1));
        return l.isEmpty() ? null : l.get(0);
    }

    /** The first episode of a show (a preview can use it when the show has no trailer). */
    public static Item firstEpisode(String showKey) throws Exception {
        List<Item> l = items(paged("/library/metadata/" + showKey + "/allLeaves", 1));
        return l.isEmpty() ? null : l.get(0);
    }

    /** Searches movies, shows and episodes. */
    public static List<Item> search(String query) throws Exception {
        JSONArray hubs = get("/hubs/search?query=" + enc(query) + "&limit=24&includeCollections=0")
                .getJSONObject("MediaContainer").optJSONArray("Hub");
        List<Item> out = new ArrayList<>();
        if (hubs == null) return out;
        for (int h = 0; h < hubs.length(); h++) {
            JSONObject hub = hubs.getJSONObject(h);
            String t = hub.optString("type");
            if (!t.equals("movie") && !t.equals("show") && !t.equals("episode")) continue;
            JSONArray md = hub.optJSONArray("Metadata");
            if (md == null) continue;
            for (int i = 0; i < md.length(); i++) out.add(parse(md.getJSONObject(i)));
        }
        // People: an actor (or director) whose name matches brings their titles in after the direct matches
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Item it : out) seen.add(it.key);
        int people = 0;
        for (int h = 0; h < hubs.length() && people < 2; h++) {
            JSONObject hub = hubs.getJSONObject(h);
            String t = hub.optString("type");
            if (!t.equals("actor") && !t.equals("director")) continue;
            JSONArray dirs = hub.optJSONArray("Directory");
            if (dirs == null) continue;
            for (int i = 0; i < dirs.length() && people < 2; i++) {
                JSONObject d = dirs.getJSONObject(i);
                String f = d.optString("filter", "");
                if (f.isEmpty() && d.has("id")) f = t + "=" + d.optString("id");
                if (f.isEmpty()) continue;
                people++;
                try {
                    for (Item it : items(paged("/library/all?" + f, 24))) {
                        if (!it.type.equals("movie") && !it.type.equals("show")) continue;
                        if (seen.add(it.key)) out.add(it);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    /** Search-as-you-type suggestions: titles and people matching the text, those starting with it first. */
    public static List<String> suggest(String query) throws Exception {
        JSONArray hubs = get("/hubs/search?query=" + enc(query) + "&limit=8&includeCollections=0")
                .getJSONObject("MediaContainer").optJSONArray("Hub");
        List<String> starts = new ArrayList<>();
        List<String> others = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String q = query.toLowerCase();
        if (hubs != null) {
            for (int h = 0; h < hubs.length(); h++) {
                JSONObject hub = hubs.getJSONObject(h);
                String t = hub.optString("type");
                boolean person = t.equals("actor") || t.equals("director");
                if (!person && !t.equals("movie") && !t.equals("show")) continue;
                JSONArray arr = hub.optJSONArray(person ? "Directory" : "Metadata");
                if (arr == null) continue;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String name = person ? o.optString("tag") : o.optString("title");
                    if (name.isEmpty() || !seen.add(name.toLowerCase())) continue;
                    (name.toLowerCase().startsWith(q) ? starts : others).add(name);
                }
            }
        }
        starts.addAll(others);
        return starts.size() > 6 ? new ArrayList<>(starts.subList(0, 6)) : starts;
    }

    public static List<Section> sections() throws Exception {
        JSONArray d = get("/library/sections").getJSONObject("MediaContainer").optJSONArray("Directory");
        List<Section> out = new ArrayList<>();
        if (d == null) return out;
        for (int i = 0; i < d.length(); i++) {
            JSONObject o = d.getJSONObject(i);
            String type = o.optString("type");
            if (!type.equals("movie") && !type.equals("show")) continue;
            Section s = new Section();
            s.key = o.optString("key");
            s.type = type;
            s.title = o.optString("title");
            out.add(s);
        }
        return out;
    }

    public static boolean isLibraryEnabled(String sectionKey) {
        if (sectionKey == null || sectionKey.isEmpty() || prefs == null) return true;
        Set<String> allowed = prefs.getStringSet("visibleLibraries", null);
        if (allowed == null) return true;
        return allowed.contains(sectionKey);
    }

    public static void setLibraryEnabled(String sectionKey, boolean enabled) {
        if (sectionKey == null || sectionKey.isEmpty() || prefs == null) return;
        Set<String> allowed = new HashSet<>(prefs.getStringSet("visibleLibraries", new HashSet<>()));
        if (enabled) allowed.add(sectionKey);
        else allowed.remove(sectionKey);
        if (allowed.isEmpty()) prefs.edit().remove("visibleLibraries").apply();
        else prefs.edit().putStringSet("visibleLibraries", allowed).apply();
    }

    /** A saved choice of libraries under [prefKey], or null when nothing has been chosen yet. */
    public static Set<String> libraryChoice(String prefKey) {
        if (prefs == null) return null;
        Set<String> saved = prefs.getStringSet(prefKey, null);
        return saved == null ? null : new HashSet<>(saved);
    }

    public static void saveLibraryChoice(String prefKey, Set<String> keys) {
        if (prefs == null) return;
        prefs.edit().putStringSet(prefKey, new HashSet<>(keys)).apply();
    }

    public static List<Section> visibleSections(List<Section> all) {
        if (all == null || all.isEmpty()) return all == null ? new ArrayList<>() : all;
        if (prefs == null) return all;
        Set<String> allowed = prefs.getStringSet("visibleLibraries", null);
        if (allowed == null) return all;
        List<Section> out = new ArrayList<>();
        for (Section s : all) if (allowed.contains(s.key)) out.add(s);
        return out;
    }

    public static List<Item> items(String path) throws Exception {
        JSONObject mc = get(path).getJSONObject("MediaContainer");
        JSONArray m = mc.optJSONArray("Metadata");
        if (m == null) m = mc.optJSONArray("Directory"); // photo albums come back this way
        List<Item> out = new ArrayList<>();
        if (m == null) return out;
        for (int i = 0; i < m.length(); i++) out.add(parse(m.getJSONObject(i)));
        return out;
    }

    /** Genres (or other filter lists) of a library: {title, path to its items}. */
    public static List<String[]> genres(String sectionKey) throws Exception {
        JSONObject mc = get("/library/sections/" + sectionKey + "/genre").getJSONObject("MediaContainer");
        JSONArray d = mc.optJSONArray("Directory");
        List<String[]> out = new ArrayList<>();
        if (d == null) return out;
        for (int i = 0; i < d.length(); i++) {
            JSONObject o = d.getJSONObject(i);
            String fast = opt(o, "fastKey");
            String path = fast != null ? fast : "/library/sections/" + sectionKey + "/all?genre=" + o.optString("key");
            out.add(new String[]{o.optString("title"), path});
        }
        return out;
    }

    /** Collections in a library: {title, ratingKey, childCount}. */
    public static List<String[]> collections(String sectionKey) throws Exception {
        JSONObject mc = get("/library/sections/" + sectionKey + "/collections").getJSONObject("MediaContainer");
        JSONArray m = mc.optJSONArray("Metadata");
        List<String[]> out = new ArrayList<>();
        if (m == null) return out;
        for (int i = 0; i < m.length(); i++) {
            JSONObject o = m.getJSONObject(i);
            out.add(new String[]{o.optString("title"), o.optString("ratingKey"),
                    String.valueOf(o.optInt("childCount", 0))});
        }
        return out;
    }

    /** Titles like this one, from Plex's own "related" hubs (falls back to the similar list). */
    public static List<Item> related(String ratingKey) {
        List<Item> out = new ArrayList<>();
        try {
            JSONObject mc = get(paged("/hubs/metadata/" + ratingKey + "/related", 30)).getJSONObject("MediaContainer");
            JSONArray hubs = mc.optJSONArray("Hub");
            if (hubs != null) {
                for (int h = 0; h < hubs.length() && out.size() < 3; h++) {
                    JSONArray m = hubs.getJSONObject(h).optJSONArray("Metadata");
                    if (m == null || m.length() < 3) continue;
                    for (int i = 0; i < m.length(); i++) out.add(parse(m.getJSONObject(i)));
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        if (out.size() < 3) {
            try {
                out = items(paged("/library/metadata/" + ratingKey + "/similar", 30));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** The ratingKey of a title's trailer, if the server has one in its extras. */
    public static String trailerKey(String ratingKey) {
        try {
            JSONObject mc = get("/library/metadata/" + ratingKey + "/extras").getJSONObject("MediaContainer");
            JSONArray m = mc.optJSONArray("Metadata");
            if (m == null) return null;
            String first = null;
            for (int i = 0; i < m.length(); i++) {
                JSONObject o = m.getJSONObject(i);
                String rk = o.optString("ratingKey", "");
                if (rk.isEmpty()) continue;
                if ("trailer".equals(o.optString("subtype")) || o.optInt("extraType", 0) == 1) return rk;
                if (first == null) first = rk;
            }
            return first;
        } catch (Exception e) {
            return null;
        }
    }

    /** The TMDB id Plex has matched a movie or show to (null if none). */
    public static String tmdbId(String ratingKey) {
        try {
            JSONObject mc = get("/library/metadata/" + ratingKey + "?includeGuids=1").getJSONObject("MediaContainer");
            JSONArray m = mc.optJSONArray("Metadata");
            if (m == null || m.length() == 0) return null;
            JSONArray g = m.getJSONObject(0).optJSONArray("Guid");
            if (g == null) return null;
            for (int i = 0; i < g.length(); i++) {
                String id = g.getJSONObject(i).optString("id", "");
                if (id.startsWith("tmdb://")) return id.substring(7);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Chapter pictures for a title: {startMs, thumbPath} in order (empty if Plex has none). */
    public static java.util.List<String[]> chapterThumbs(String ratingKey) {
        java.util.List<String[]> out = new ArrayList<>();
        try {
            JSONObject mc = get("/library/metadata/" + ratingKey + "?includeChapters=1").getJSONObject("MediaContainer");
            JSONArray m = mc.optJSONArray("Metadata");
            if (m == null || m.length() == 0) return out;
            JSONArray ch = m.getJSONObject(0).optJSONArray("Chapter");
            if (ch == null) return out;
            for (int i = 0; i < ch.length(); i++) {
                JSONObject c = ch.getJSONObject(i);
                String th = c.optString("thumb", "");
                if (th.isEmpty()) continue;
                out.add(new String[]{String.valueOf(c.optLong("startTimeOffset", 0)), th});
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * Where to start a preview clip of a film or episode: the start of a chapter around 40% of the
     * way in (chapters begin at real scene changes, and the middle of a film is where the big
     * moments tend to be), else a fifth of the way in.
     */
    public static long sceneOffset(String ratingKey, long durationMs) {
        long fallback = (long) (durationMs * 0.2);
        try {
            JSONObject mc = get("/library/metadata/" + ratingKey + "?includeChapters=1").getJSONObject("MediaContainer");
            JSONArray m = mc.optJSONArray("Metadata");
            if (m == null || m.length() == 0) return fallback;
            JSONObject o = m.getJSONObject(0);
            long dur = durationMs > 0 ? durationMs : o.optLong("duration", 0);
            if (fallback <= 0) fallback = (long) (dur * 0.2);
            JSONArray ch = o.optJSONArray("Chapter");
            if (ch == null || ch.length() < 5 || dur <= 0) return fallback;
            long target = (long) (dur * 0.42);
            long best = -1;
            long bestDiff = Long.MAX_VALUE;
            for (int i = 2; i < ch.length() - 2; i++) { // skip the opening and the end credits
                long st = ch.getJSONObject(i).optLong("startTimeOffset", -1);
                if (st < dur * 0.25 || st > dur * 0.7) continue;
                long diff = Math.abs(st - target);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = st;
                }
            }
            return best > 0 ? best + 2000 : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * A playable address for a title's trailer from Plex itself: first the extras Plex shows in
     * its own apps (including trailers it fetched online, with Plex Pass), then the local extras.
     */
    public static String trailerUrl(String ratingKey) {
        try {
            JSONObject mc = get("/library/metadata/" + ratingKey + "?includeExtras=1").getJSONObject("MediaContainer");
            JSONArray m = mc.optJSONArray("Metadata");
            if (m != null && m.length() > 0) {
                JSONObject ex = m.getJSONObject(0).optJSONObject("Extras");
                String u = pickTrailerPart(ex != null ? ex.optJSONArray("Metadata") : null);
                if (u != null) return u;
            }
        } catch (Exception ignored) {
        }
        try {
            JSONObject mc = get("/library/metadata/" + ratingKey + "/extras").getJSONObject("MediaContainer");
            return pickTrailerPart(mc.optJSONArray("Metadata"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String pickTrailerPart(JSONArray a) {
        if (a == null) return null;
        String first = null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            JSONArray media = o.optJSONArray("Media");
            if (media == null || media.length() == 0) continue;
            JSONArray parts = media.optJSONObject(0) != null ? media.optJSONObject(0).optJSONArray("Part") : null;
            if (parts == null || parts.length() == 0) continue;
            String key = parts.optJSONObject(0) != null ? parts.optJSONObject(0).optString("key", "") : "";
            if (key.isEmpty()) continue;
            String url = key.startsWith("http") ? key : raw(key);
            boolean trailer = "trailer".equals(o.optString("subtype")) || o.optInt("extraType", 0) == 1;
            if (trailer) return url;
            if (first == null) first = url;
        }
        return first;
    }

    /** What is playing on the server right now. */
    public static List<Item> sessions() throws Exception {
        return items("/status/sessions");
    }

    public static Item metadata(String ratingKey) throws Exception {
        List<Item> l = items("/library/metadata/" + ratingKey + "?includeMarkers=1");
        if (l.isEmpty()) throw new Exception("Item not found");
        return l.get(0);
    }

    /** Turns Plex's rating + image id (e.g. rottentomatoes://image.rating.ripe) into a labelled score. */
    private static void addRating(Item i, double v, String image) {
        if (v < 0 || image == null) return;
        String src;
        String shown;
        if (image.startsWith("rottentomatoes")) {
            src = "RT";
            shown = Math.round(v * 10) + "%";
        } else if (image.startsWith("imdb")) {
            src = "IMDb";
            shown = String.format(java.util.Locale.US, "%.1f", v);
        } else if (image.startsWith("themoviedb")) {
            src = "TMDb";
            shown = Math.round(v * 10) + "%";
        } else {
            return;
        }
        for (String[] r : i.ratings) if (r[0].equals(src)) return;
        i.ratings.add(new String[]{src, shown});
    }

    private static String opt(JSONObject o, String k) {
        return (!o.has(k) || o.isNull(k)) ? null : o.optString(k);
    }

    private static Item parse(JSONObject m) {
        Item i = new Item();
        i.key = m.optString("ratingKey");
        i.type = m.optString("type");
        i.title = m.optString("title");
        i.summary = m.optString("summary");
        i.tagline = m.optString("tagline", "");
        i.addedAt = m.optLong("addedAt", 0);
        i.leafCount = m.optInt("leafCount", 0);
        i.viewedLeafCount = m.optInt("viewedLeafCount", 0);
        i.year = m.optInt("year", 0);
        i.librarySectionID = m.optInt("librarySectionID", 0);
        i.librarySectionTitle = opt(m, "librarySectionTitle");
        i.contentRating = m.optString("contentRating", "");
        addRating(i, m.optDouble("rating", -1), opt(m, "ratingImage"));
        addRating(i, m.optDouble("audienceRating", -1), opt(m, "audienceRatingImage"));
        i.thumb = opt(m, "thumb");
        i.art = opt(m, "art");
        i.gpTitle = opt(m, "grandparentTitle");
        i.gpKey = opt(m, "grandparentRatingKey");
        JSONArray colls = m.optJSONArray("Collection");
        if (colls != null) {
            for (int k = 0; k < colls.length(); k++) {
                JSONObject co = colls.optJSONObject(k);
                if (co == null) continue;
                String f = co.optString("filter", "");
                String t = co.optString("tag", "");
                if (f.startsWith("collection=") && !t.isEmpty()) {
                    i.collName = t;
                    i.collFilter = f;
                    break;
                }
            }
        }
        JSONArray dirs = m.optJSONArray("Director");
        if (dirs != null) {
            for (int k = 0; k < dirs.length() && k < 3; k++) {
                JSONObject d = dirs.optJSONObject(k);
                if (d == null) continue;
                Cast c = new Cast();
                c.name = d.optString("tag");
                c.filter = d.optString("filter");
                c.thumb = opt(d, "thumb");
                if (!c.name.isEmpty() && !c.filter.isEmpty()) i.directors.add(c);
            }
        }
        JSONArray roles = m.optJSONArray("Role");
        if (roles != null) {
            for (int k = 0; k < roles.length() && k < 30; k++) {
                JSONObject ro = roles.optJSONObject(k);
                if (ro == null) continue;
                Cast c = new Cast();
                c.name = ro.optString("tag");
                c.role = ro.optString("role");
                c.thumb = opt(ro, "thumb");
                c.filter = ro.optString("filter");
                if (!c.name.isEmpty() && !c.filter.isEmpty()) i.cast.add(c);
            }
        }
        JSONArray markers = m.optJSONArray("Marker");
        if (markers != null) {
            for (int k = 0; k < markers.length(); k++) {
                JSONObject mk = markers.optJSONObject(k);
                if (mk == null) continue;
                long[] range = {mk.optLong("startTimeOffset"), mk.optLong("endTimeOffset")};
                String t = mk.optString("type");
                if (t.equals("intro")) i.intros.add(range);
                else if (t.equals("credits")) i.credits.add(range);
            }
        }
        i.gpThumb = opt(m, "grandparentThumb");
        i.gpArt = opt(m, "grandparentArt");
        i.duration = m.optLong("duration", 0);
        i.viewOffset = m.optLong("viewOffset", 0);
        i.viewCount = m.optInt("viewCount", 0);
        i.index = m.optInt("index", 0);
        i.parentIndex = m.optInt("parentIndex", 0);

        JSONObject user = m.optJSONObject("User");
        JSONObject player = m.optJSONObject("Player");
        if (user != null || player != null) {
            String u = user == null ? "" : user.optString("title", "");
            String d = player == null ? "" : player.optString("title", "");
            String state = player == null ? "" : player.optString("state", "");
            i.watcher = (u.isEmpty() ? d : (d.isEmpty() ? u : u + " on " + d))
                    + ("paused".equals(state) ? " (paused)" : "");
        }

        JSONArray images = m.optJSONArray("Image");
        if (images != null) {
            for (int k = 0; k < images.length(); k++) {
                JSONObject im = images.optJSONObject(k);
                if (im != null && "clearLogo".equals(im.optString("type"))) {
                    i.logo = opt(im, "url");
                }
            }
        }

        JSONArray media = m.optJSONArray("Media");
        if (media != null && media.length() > 0) {
            JSONObject m0 = media.optJSONObject(0);
            i.videoRes = m0.optString("videoResolution", "");
            i.videoHeight = m0.optInt("height", 0);
            JSONArray parts = m0.optJSONArray("Part");
            if (parts != null && parts.length() > 0) {
                JSONObject p0 = parts.optJSONObject(0);
                i.partKey = opt(p0, "key");
                JSONArray ss = p0.optJSONArray("Stream");
                if (ss != null) {
                    for (int k = 0; k < ss.length(); k++) {
                        JSONObject so = ss.optJSONObject(k);
                        if (so != null) i.streams.add(parseStream(so));
                    }
                }
            }
        }
        return i;
    }

    private static Stream parseStream(JSONObject o) {
        Stream s = new Stream();
        s.id = o.optInt("id", 0);
        s.type = o.optInt("streamType", 0);
        s.codec = o.optString("codec", "");
        s.profile = o.optString("profile", "");
        s.channels = o.optInt("channels", 0);
        s.layout = o.optString("audioChannelLayout", "");
        s.lang = o.optString("language", "");
        s.langCode = o.optString("languageCode", "");
        s.displayTitle = o.optString("displayTitle", "");
        s.extTitle = o.optString("extendedDisplayTitle", "");
        s.title = o.optString("title", "");
        s.selected = o.optBoolean("selected", false);
        s.isDefault = o.optBoolean("default", false);
        s.forced = o.optBoolean("forced", false);
        s.key = opt(o, "key");
        s.format = o.optString("format", "");
        s.dovi = o.optBoolean("DOVIPresent", false);
        s.doviProfile = o.optInt("DOVIProfile", 0);
        s.colorTrc = o.optString("colorTrc", "");
        s.bitDepth = o.optInt("bitDepth", 0);
        s.height = o.optInt("height", 0);
        s.bitrate = o.optInt("bitrate", 0);
        return s;
    }

    // --------------------------------------------------- Subtitle search

    /** Asks your server to look for subtitles online (needs Plex Pass and a subtitle agent). */
    public static List<SubResult> searchSubtitles(String ratingKey, String lang) throws Exception {
        String body = http("GET", serverUri + "/library/metadata/" + ratingKey
                + "/subtitles?language=" + enc(lang) + "&hearingImpaired=0&forced=0", 45000);
        JSONArray a = new JSONObject(body).getJSONObject("MediaContainer").optJSONArray("Stream");
        List<SubResult> out = new ArrayList<>();
        if (a == null) return out;
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.getJSONObject(k);
            SubResult r = new SubResult();
            r.key = opt(o, "key");
            if (r.key == null) continue;
            r.codec = o.optString("codec", "srt");
            r.language = o.optString("languageCode", lang);
            r.provider = o.optString("providerTitle", "");
            r.title = o.optString("title", o.optString("displayTitle", ""));
            r.hearingImpaired = o.optBoolean("hearingImpaired", false);
            r.score = o.optDouble("score", 0);
            out.add(r);
        }
        return out;
    }

    public static void downloadSubtitle(String ratingKey, SubResult r) throws Exception {
        http("PUT", serverUri + "/library/metadata/" + ratingKey + "/subtitles?key=" + enc(r.key)
                + "&codec=" + enc(r.codec) + "&language=" + enc(r.language)
                + "&hearingImpaired=" + (r.hearingImpaired ? 1 : 0) + "&forced=0"
                + "&providerTitle=" + enc(r.provider), 60000);
    }

    // ------------------------------------------------------------ Images

    /** A resized copy of a server image (posters, backgrounds, stills). */
    public static String img(String path, int w, int h) {
        if (path != null && path.startsWith("http")) return path; // already a full address (e.g. TMDB)
        if (path == null || serverUri == null) return null;
        return serverUri + "/photo/:/transcode?width=" + w + "&height=" + h
                + "&minSize=1&upscale=1&url=" + enc(path) + "&X-Plex-Token=" + st();
    }

    /** The original image; used for logos so their transparency is kept. */
    public static String raw(String path) {
        if (path == null || serverUri == null) return null;
        return serverUri + path + (path.contains("?") ? "&" : "?") + "X-Plex-Token=" + st();
    }

    // ---------------------------------------------------------- Playback

    public static String directUrl(Item i) {
        return raw(i.partKey);
    }

    /** Fallback when the stick can't play the file as it is (unsupported audio or video). */
    /** Tells Plex what Reel can take, so it copies the video and only converts what it must. */
    /** Previews: stereo AAC, so the app can play them quietly (Dolby passthrough ignores the volume). */
    public static String previewProfile() {
        return "add-transcode-target(type=videoProfile&context=streaming&protocol=hls"
                + "&container=mpegts&videoCodec=h264,hevc&audioCodec=aac)"
                + "+add-limitation(scope=videoAudioCodec&scopeName=aac&type=upperBound"
                + "&name=audio.channels&value=2)";
    }

    public static String previewUrl(String ratingKey, long offsetMs, String session) {
        return transcodeUrl(ratingKey, offsetMs, session, 0, 0, false, 3500, "1280x720")
                .replace("X-Plex-Client-Profile-Extra=" + enc(profileExtra(false)),
                        "X-Plex-Client-Profile-Extra=" + enc(previewProfile()))
                + "&directStreamAudio=0&audioChannelCount=2";
    }

    public static String profileExtra(boolean ac3) {
        return "add-transcode-target(type=videoProfile&context=streaming&protocol=hls"
                + "&container=mpegts&videoCodec=h264,hevc&audioCodec=" + (ac3 ? "ac3" : "ac3,eac3,aac") + ")"
                + (ac3 ? "+add-limitation(scope=videoAudioCodec&scopeName=ac3&type=upperBound"
                + "&name=audio.channels&value=6)" : "");
    }

    public static String transcodeUrl(String ratingKey, long offsetMs, String session,
                                      int audioId, int subId, boolean ac3, int maxKbps, String res) {
        return serverUri + "/video/:/transcode/universal/start.m3u8"
                + "?path=" + enc("/library/metadata/" + ratingKey)
                + "&mediaIndex=0&partIndex=0&protocol=hls&fastSeek=1"
                + "&directPlay=0&directStream=1&subtitleSize=100&audioBoost=100"
                + "&maxVideoBitrate=" + (maxKbps > 0 ? maxKbps : 200000) + "&videoQuality=100"
                + (res != null && !res.isEmpty() ? "&videoResolution=" + res : "")
                + (ac3 ? "&directStreamAudio=0" : "")
                // Let Plex copy HEVC / H.264 video untouched into the stream (only audio changes).
                + "&X-Plex-Client-Profile-Extra=" + enc(profileExtra(ac3))
                + "&offset=" + (offsetMs / 1000L)
                + "&session=" + session
                + "&X-Plex-Client-Identifier=" + enc(clientId)
                + "&X-Plex-Product=Reel&X-Plex-Version=0.1&X-Plex-Platform=Android"
                + "&X-Plex-Client-Profile-Name=Konvergo"
                + "&X-Plex-Device=" + enc("Fire TV") + "&X-Plex-Device-Name=Reel"
                + (audioId > 0 ? "&audioStreamID=" + audioId : "")
                + (subId > 0 ? "&subtitleStreamID=" + subId + "&subtitles=burn" : "&subtitleStreamID=0&subtitles=none")
                + "&X-Plex-Token=" + st();
    }

    /** Sets which subtitle Plex treats as selected for this file (0 = none); it burns the selected one in. */
    public static void setPartSubtitle(String partKey, int subtitleStreamId) throws Exception {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/library/parts/(\\d+)").matcher(partKey);
        if (!m.find()) return;
        http("PUT", serverUri + "/library/parts/" + m.group(1) + "?subtitleStreamID=" + subtitleStreamId
                + "&allParts=1", 10000);
    }

    /** Tells Plex to stop (and clean up) a converted stream. */
    public static void stopTranscode(final String session) {
        if (session == null || serverUri == null) return;
        async(() -> {
            http("GET", serverUri + "/video/:/transcode/universal/stop?session=" + enc(session), 8000);
            return true;
        }, NOOP);
    }

    /** Asks Plex what it plans to do with this stream (copy or transcode) and why; for the Info page. */
    public static String decision(String ratingKey, long offsetMs, String session, int audioId, int subId,
                                  boolean ac3, int maxKbps, String res) throws Exception {
        String url = transcodeUrl(ratingKey, offsetMs, session, audioId, subId, ac3, maxKbps, res)
                .replace("/transcode/universal/start.m3u8", "/transcode/universal/decision");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-Plex-Client-Profile-Extra", profileExtra(ac3));
        c.setRequestProperty("X-Plex-Client-Profile-Name", "Konvergo");
        c.setRequestProperty("X-Plex-Client-Identifier", clientId);
        c.setRequestProperty("X-Plex-Product", "Reel");
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        JSONObject mc = new JSONObject(sb.toString()).getJSONObject("MediaContainer");
        StringBuilder out = new StringBuilder();
        String[] keys = {"generalDecisionText", "directPlayDecisionText", "mdeDecisionText", "transcodeDecisionText"};
        for (String k : keys) {
            String v = mc.optString(k, "");
            if (!v.isEmpty()) out.append(k.replace("DecisionText", "")).append(": ").append(v).append('\n');
        }
        JSONArray md = mc.optJSONArray("Metadata");
        if (md != null && md.length() > 0) {
            JSONArray media = md.getJSONObject(0).optJSONArray("Media");
            if (media != null && media.length() > 0) {
                JSONArray parts = media.getJSONObject(0).optJSONArray("Part");
                if (parts != null && parts.length() > 0) {
                    JSONArray ss = parts.getJSONObject(0).optJSONArray("Stream");
                    if (ss != null) {
                        for (int i = 0; i < ss.length(); i++) {
                            JSONObject so = ss.getJSONObject(i);
                            String d = so.optString("decision", "");
                            if (d.isEmpty() || d.equals("ignore") && so.optInt("streamType") == 3) continue;
                            String type = so.optInt("streamType") == 1 ? "video"
                                    : so.optInt("streamType") == 2 ? "audio" : "subtitle";
                            out.append(type).append(": ").append(d).append(" (")
                                    .append(so.optString("codec")).append(")\n");
                        }
                    }
                }
            }
        }
        return out.length() == 0 ? "No decision details returned" : out.toString();
    }

    /** Tells Plex where you are so "Continue Watching" stays in sync. */
    /** Session id of the converted stream being played, so progress reports are tied to it. */
    public static volatile String activeSession = null;

    public static void timeline(final String key, final String state, final long timeMs, final long durMs) {
        async(() -> {
            http("GET", serverUri + "/:/timeline?ratingKey=" + key
                    + "&key=" + enc("/library/metadata/" + key)
                    + "&identifier=com.plexapp.plugins.library"
                    + "&state=" + state + "&time=" + timeMs + "&duration=" + durMs
                    + (activeSession != null ? "&X-Plex-Session-Identifier=" + enc(activeSession) : ""), 8000);
            return true;
        }, NOOP);
    }

    public static void setWatched(final String key, final boolean watched) {
        async(() -> {
            http("GET", serverUri + "/:/" + (watched ? "scrobble" : "unscrobble")
                    + "?key=" + key + "&identifier=com.plexapp.plugins.library", 8000);
            return true;
        }, NOOP);
    }
}
