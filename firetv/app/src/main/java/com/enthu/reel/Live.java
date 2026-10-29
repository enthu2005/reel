package com.enthu.reel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Pretend TV channels made from your own libraries. Each channel has a fixed, endless loop of films or
 * episodes, and the loop is laid against the clock, so what is "on" right now is the same every time you
 * tune in and tuning in joins it part-way through, like real TV. Nothing here is stored on the server.
 */
final class Live {

    static class Prog {
        String key;
        String title;
        String sub = "";
        long durMs;
    }

    static class Slot {
        Prog prog;
        long start; // wall clock ms
        long end;
    }

    static class Channel {
        /** The number shown on the guide: its place in the (reordered, unhidden) list. */
        int num;
        String id;
        String name;
        boolean custom;
        final List<Prog> loop = new ArrayList<>();
        long total;
    }

    /** A channel you made: from a collection, one show, a mix of shows, genres or a saved search. */
    static class Custom {
        String id;
        String name;
        String kind = "genres"; // "collection", "show", "shows", "genres" or "search"
        String ref = "";        // the collection's / show's key, or the search words
        String refTitle = "";
        String type = "movie";  // genres: "movie" or "show"
        final List<String> genres = new ArrayList<>();
        final List<String> refs = new ArrayList<>();
        final List<String> refTitles = new ArrayList<>();
        final List<String> libs = new ArrayList<>();   // library keys it may use (empty = every enabled library)
        boolean builtin;        // one of the standard channels (kept as a changed copy once you edit it)

        JSONObject toJson() throws org.json.JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id).put("name", name).put("kind", kind).put("ref", ref).put("refTitle", refTitle)
                    .put("type", type).put("genres", new JSONArray(genres)).put("refs", new JSONArray(refs))
                    .put("refTitles", new JSONArray(refTitles)).put("libs", new JSONArray(libs)).put("builtin", builtin);
            return o;
        }

        static Custom fromJson(JSONObject o) {
            Custom c = new Custom();
            c.id = o.optString("id");
            c.name = o.optString("name");
            c.kind = o.optString("kind", "genres");
            c.ref = o.optString("ref");
            c.refTitle = o.optString("refTitle");
            c.type = o.optString("type", "movie");
            fill(c.genres, o.optJSONArray("genres"));
            fill(c.refs, o.optJSONArray("refs"));
            fill(c.refTitles, o.optJSONArray("refTitles"));
            fill(c.libs, o.optJSONArray("libs"));
            c.builtin = o.optBoolean("builtin");
            return c;
        }

        private static void fill(List<String> to, JSONArray a) {
            if (a != null) for (int i = 0; i < a.length(); i++) to.add(a.optString(i));
        }

        String describe() {
            switch (kind) {
                case "collection": return "Collection: " + refTitle;
                case "show": return "Show: " + refTitle;
                case "shows": return "Mix of shows: " + android.text.TextUtils.join(", ", refTitles);
                case "search": return "Search: " + ref;
                default: return (type.equals("movie") ? "Movies: " : "TV: ") + android.text.TextUtils.join(", ", genres);
            }
        }
    }

    /** {name, genre words (any matches), "movie" or "show"} */
    private static final String[][] LINEUP = {
            {"Adventure", "adventure", "movie"},
            {"Action Movies", "action", "movie"},
            {"Comedy TV", "comedy", "show"},
            {"Sci-Fi", "science fiction,sci-fi", "movie"},
            {"Drama TV", "drama", "show"},
            {"Horror", "horror", "movie"},
            {"Comedy Movies", "comedy", "movie"},
            {"Animation", "animation", "movie"},
            {"Crime TV", "crime", "show"},
            {"Thrillers", "thriller", "movie"},
            {"Documentaries", "documentary", "movie"},
            {"Sci-Fi & Fantasy TV", "sci-fi,fantasy,science fiction", "show"},
    };

    private static final long EPOCH = 1_700_000_000_000L; // any fixed moment: the loops start here
    private static final long MIN_PROG = 4 * 60_000L;
    private static List<Channel> everything; // every channel built, hidden or not, in the usual order
    private static List<Channel> built;      // what the guide shows: unhidden, in your order, numbered
    private static long builtAt;
    private static final java.util.Map<String, List<Prog>> EP_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    static List<Channel> cached() {
        return built;
    }

    static Channel byNum(int n) {
        if (built != null) for (Channel c : built) if (c.num == n) return c;
        return null;
    }

    // ------------------------------------------------------------------ your own channels

    static List<Custom> customChannels() {
        List<Custom> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Plex.prefs().getString("liveCustom", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Custom.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
            // a bad list is as good as none
        }
        return out;
    }

    private static void saveCustom(List<Custom> l) {
        JSONArray a = new JSONArray();
        try {
            for (Custom c : l) a.put(c.toJson());
        } catch (org.json.JSONException ignored) {
            return;
        }
        Plex.prefs().edit().putString("liveCustom", a.toString()).apply();
        invalidate();
    }

    static void addCustom(Custom c) {
        List<Custom> l = customChannels();
        l.add(c);
        saveCustom(l);
    }

    static void removeCustom(String id) {
        List<Custom> l = customChannels();
        for (int i = 0; i < l.size(); i++) if (l.get(i).id.equals(id)) l.remove(i--);
        saveCustom(l);
        List<String> h = strings("liveHidden");
        h.remove(id);
        putStrings("liveHidden", h);
        List<String> o = strings("liveOrder");
        o.remove(id);
        putStrings("liveOrder", o);
    }


    // ------------------------------------------------------------------ what every channel is made from

    private static Custom standard(String[] def) {
        Custom c = new Custom();
        c.id = def[0];
        c.name = def[0];
        c.builtin = true;
        c.kind = "genres";
        c.type = def[2];
        for (String g : def[1].split(",")) c.genres.add(g.trim());
        return c;
    }

    private static JSONObject overrides() {
        try {
            return new JSONObject(Plex.prefs().getString("liveOver", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Every channel's recipe: the standard ones (or your changed copy of them) then the ones you made. */
    static List<Custom> definitions() {
        List<Custom> out = new ArrayList<>();
        JSONObject over = overrides();
        for (String[] def : LINEUP) {
            JSONObject o = over.optJSONObject(def[0]);
            out.add(o == null ? standard(def) : Custom.fromJson(o));
        }
        out.addAll(customChannels());
        return out;
    }

    static Custom definition(String id) {
        for (Custom c : definitions()) if (c.id.equals(id)) return c;
        return null;
    }

    /** True when a standard channel has been changed from how it came. */
    static boolean changed(String id) {
        return overrides().has(id);
    }

    /** Saves a changed recipe (name, what it plays, which libraries) for a standard or own channel. */
    static void saveDefinition(Custom c) {
        if (c.builtin) {
            JSONObject over = overrides();
            try {
                over.put(c.id, c.toJson());
            } catch (org.json.JSONException ignored) {
                return;
            }
            Plex.prefs().edit().putString("liveOver", over.toString()).apply();
            invalidate();
            return;
        }
        List<Custom> l = customChannels();
        for (int i = 0; i < l.size(); i++) if (l.get(i).id.equals(c.id)) l.set(i, c);
        saveCustom(l);
    }

    /** Puts a standard channel back as it came. */
    static void resetDefinition(String id) {
        JSONObject over = overrides();
        over.remove(id);
        Plex.prefs().edit().putString("liveOver", over.toString()).apply();
        invalidate();
    }
    /** Forget what was built, so the next load builds the channels again. */
    static synchronized void invalidate() {
        builtAt = 0;
    }

    private static List<String> strings(String key) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Plex.prefs().getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (Exception ignored) {
            // empty
        }
        return out;
    }

    private static void putStrings(String key, List<String> l) {
        Plex.prefs().edit().putString(key, new JSONArray(l).toString()).apply();
    }

    static boolean isHidden(String id) {
        return strings("liveHidden").contains(id);
    }

    /** Every channel in the guide's order (hidden ones included), for the editor. */
    static List<Channel> allInOrder() {
        return arranged(everything == null ? new ArrayList<Channel>() : everything, false);
    }

    private static List<Channel> arranged(List<Channel> all, boolean dropHidden) {
        List<Channel> rest = new ArrayList<>(all);
        List<Channel> out = new ArrayList<>();
        for (String id : strings("liveOrder")) {
            for (int i = 0; i < rest.size(); i++) {
                if (rest.get(i).id.equals(id)) {
                    out.add(rest.remove(i));
                    break;
                }
            }
        }
        out.addAll(rest);
        if (dropHidden) {
            List<String> hide = strings("liveHidden");
            List<Channel> shown = new ArrayList<>();
            for (Channel c : out) if (!hide.contains(c.id)) shown.add(c);
            return shown;
        }
        return out;
    }

    /** Re-applies your order and hidden choices without building anything again. */
    static synchronized void rearrange() {
        List<Channel> l = arranged(everything == null ? new ArrayList<Channel>() : everything, true);
        for (int i = 0; i < l.size(); i++) l.get(i).num = i + 1;
        built = l;
    }

    static void moveChannel(String id, int delta) {
        List<String> ids = new ArrayList<>();
        for (Channel c : allInOrder()) ids.add(c.id);
        int i = ids.indexOf(id);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= ids.size()) return;
        ids.add(j, ids.remove(i));
        putStrings("liveOrder", ids);
        rearrange();
    }

    static void setHidden(String id, boolean hide) {
        List<String> h = strings("liveHidden");
        h.remove(id);
        if (hide) h.add(id);
        putStrings("liveHidden", h);
        rearrange();
    }

    // ------------------------------------------------------------------ building

    /** Builds (or reuses, for half an hour) the channel list. Run off the main thread. */
    static synchronized List<Channel> load() throws Exception {
        long now = System.currentTimeMillis();
        if (built != null && now - builtAt < 30 * 60_000L) return built;
        List<JSONObject> movies = new ArrayList<>();
        List<JSONObject> shows = new ArrayList<>();
        for (Plex.Section s : Plex.allSections()) {
            if (!Plex.isLibraryEnabled(s.key)) continue;
            boolean movie = s.type.equals("movie");
            if (!movie && !s.type.equals("show")) continue;
            JSONArray all = Plex.listAll(s.key);
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.getJSONObject(i);
                o.put("_lib", s.key);
                (movie ? movies : shows).add(o);
            }
        }
        sortByKey(movies);
        sortByKey(shows);
        List<Channel> out = new ArrayList<>();
        for (Custom cc : definitions()) {
            Channel c = new Channel();
            c.id = cc.id;
            c.name = cc.name;
            c.custom = !cc.builtin;
            try {
                switch (cc.kind) {
                    case "collection":
                        fromCollection(c, cc.ref);
                        break;
                    case "show":
                        fromShow(c, cc.ref, cc.refTitle);
                        break;
                    case "search":
                        fromSearch(c, cc.ref);
                        break;
                    case "shows": {
                        List<JSONObject> pool = new ArrayList<>();
                        for (int i = 0; i < cc.refs.size(); i++) {
                            JSONObject o = new JSONObject();
                            o.put("ratingKey", cc.refs.get(i));
                            o.put("title", i < cc.refTitles.size() ? cc.refTitles.get(i) : "");
                            pool.add(o);
                        }
                        buildShows(c, pool, new Random(c.id.hashCode()), 12);
                        break;
                    }
                    default: {
                        String[] words = new String[cc.genres.size()];
                        for (int i = 0; i < words.length; i++) words[i] = cc.genres.get(i).toLowerCase();
                        fromGenres(c, words, cc.type.equals("movie"), movies, shows, cc.libs);
                    }
                }
            } catch (Exception ignored) {
                // a channel that can't be built is left out
            }
            addIfAny(out, c);
        }
        everything = out;
        builtAt = now;
        rearrange();
        return built;
    }

    private static void addIfAny(List<Channel> out, Channel c) {
        for (Prog p : c.loop) c.total += p.durMs;
        if (c.total > 0) out.add(c);
    }

    private static void fromGenres(Channel c, String[] words, boolean movie, List<JSONObject> movies,
                                   List<JSONObject> shows, List<String> libs) {
        Random rnd = new Random(c.id.hashCode());
        List<JSONObject> pool = match(movie ? movies : shows, words, libs);
        Collections.shuffle(pool, rnd);
        if (movie) {
            for (JSONObject m : pool) {
                if (c.loop.size() >= 150) break;
                Prog p = prog(m, "");
                if (p != null) c.loop.add(p);
            }
        } else {
            buildShows(c, pool, rnd, 8);
        }
    }

    /** A collection plays in release order (a marathon); a collection of shows mixes their episodes. */
    private static void fromCollection(Channel c, String key) throws Exception {
        List<Plex.Item> items = Plex.children(key);
        if (items.isEmpty()) return;
        if (items.get(0).type.equals("show")) {
            List<JSONObject> pool = new ArrayList<>();
            for (Plex.Item s : items) pool.add(new JSONObject().put("ratingKey", s.key).put("title", s.title));
            buildShows(c, pool, new Random(c.id.hashCode()), 8);
            return;
        }
        List<Plex.Item> movies = new ArrayList<>();
        for (Plex.Item m : items) if (m.type.equals("movie")) movies.add(m);
        Collections.sort(movies,
                (a, b) -> a.year != b.year ? Integer.compare(a.year, b.year) : a.title.compareTo(b.title));
        for (Plex.Item m : movies) {
            if (m.duration < MIN_PROG) continue;
            c.loop.add(film(m));
        }
    }

    private static Prog film(Plex.Item m) {
        Prog p = new Prog();
        p.key = m.key;
        p.title = m.title;
        p.sub = m.year > 0 ? String.valueOf(m.year) : "";
        p.durMs = m.duration;
        return p;
    }

    /** A saved smart search ("80s horror rated 7+"): whatever it finds today, films and shows mixed together. */
    private static void fromSearch(Channel c, String query) throws Exception {
        List<Plex.Item> items = SmartSearch.run(SmartSearch.parse(query));
        Random rnd = new Random(c.id.hashCode());
        List<List<Prog>> lists = new ArrayList<>();
        int shows = 0;
        for (int n = 0; n < items.size() && n < 300; n++) {
            Plex.Item it = items.get(n);
            if (it.type.equals("movie")) {
                if (it.duration >= MIN_PROG) {
                    List<Prog> one = new ArrayList<>();
                    one.add(film(it));
                    lists.add(one);
                }
            } else if (it.type.equals("show") && shows < 12) {
                try {
                    List<Prog> eps = episodesOf(it.key, it.title, 120);
                    if (!eps.isEmpty()) {
                        lists.add(eps);
                        shows++;
                    }
                } catch (Exception ignored) {
                    // skip this show
                }
            }
        }
        Collections.shuffle(lists, rnd);
        mix(c, lists, rnd);
    }

    /** One show, every episode in order, round and round (24/7). */
    private static void fromShow(Channel c, String key, String title) throws Exception {
        c.loop.addAll(episodesOf(key, title, 600));
    }

    private static List<Prog> episodesOf(String showKey, String showTitle, int cap) throws Exception {
        List<Prog> hit = EP_CACHE.get(showKey + "/" + cap);
        if (hit != null) return hit;
        List<Prog> eps = new ArrayList<>();
        for (Plex.Item e : Plex.episodesOf(showKey)) {
            if (e.duration < MIN_PROG || e.parentIndex <= 0) continue; // skip specials
            Prog p = new Prog();
            p.key = e.key;
            p.title = showTitle;
            p.sub = "S" + e.parentIndex + " · E" + e.index + "  " + e.title;
            p.durMs = e.duration;
            eps.add(p);
            if (eps.size() >= cap) break;
        }
        EP_CACHE.put(showKey + "/" + cap, eps);
        return eps;
    }

    private static void sortByKey(List<JSONObject> l) {
        Collections.sort(l, (a, b) -> a.optString("ratingKey").compareTo(b.optString("ratingKey")));
    }

    private static List<JSONObject> match(List<JSONObject> src, String[] words, List<String> libs) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject m : src) {
            if (!libs.isEmpty() && !libs.contains(m.optString("_lib"))) continue;
            JSONArray g = m.optJSONArray("Genre");
            if (g == null) continue;
            boolean hit = false;
            for (int i = 0; i < g.length() && !hit; i++) {
                String tag = g.optJSONObject(i) == null ? "" : g.optJSONObject(i).optString("tag").toLowerCase();
                for (String w : words) if (tag.contains(w.trim())) hit = true;
            }
            if (hit) out.add(m);
        }
        return out;
    }

    private static Prog prog(JSONObject m, String sub) {
        long d = m.optLong("duration", 0);
        String key = m.optString("ratingKey");
        if (d < MIN_PROG || key.isEmpty()) return null;
        Prog p = new Prog();
        p.key = key;
        p.title = m.optString("title");
        p.sub = sub;
        p.durMs = d;
        if (sub.isEmpty() && m.optInt("year") > 0) p.sub = String.valueOf(m.optInt("year"));
        return p;
    }

    /** A few shows, their episodes in order, mixed together so it feels like an evening of different shows. */
    private static void buildShows(Channel c, List<JSONObject> pool, Random rnd, int maxShows) {
        // The shows' episode lists are fetched together, not one after the other
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(6);
        List<List<Prog>> lists = new ArrayList<>();
        try {
            List<java.util.concurrent.Future<List<Prog>>> jobs = new ArrayList<>();
            for (int i = 0; i < pool.size() && i < maxShows + 4; i++) {
                final JSONObject show = pool.get(i);
                jobs.add(ex.submit(() -> episodesOf(show.optString("ratingKey"), show.optString("title"), 120)));
            }
            for (java.util.concurrent.Future<List<Prog>> f : jobs) {
                if (lists.size() >= maxShows) break;
                try {
                    List<Prog> eps = f.get();
                    if (!eps.isEmpty()) lists.add(eps);
                } catch (Exception ignored) {
                    // skip this show
                }
            }
        } finally {
            ex.shutdown();
        }
        mix(c, lists, rnd);
    }

    /** Takes the next programme from a randomly picked list, over and over, so each list stays in order. */
    private static void mix(Channel c, List<List<Prog>> lists, Random rnd) {
        int[] at = new int[lists.size()];
        while (c.loop.size() < 400) {
            List<Integer> live = new ArrayList<>();
            for (int i = 0; i < lists.size(); i++) if (at[i] < lists.get(i).size()) live.add(i);
            if (live.isEmpty()) break;
            int pick = live.get(rnd.nextInt(live.size()));
            c.loop.add(lists.get(pick).get(at[pick]++));
        }
    }

    // ------------------------------------------------------------------ the schedule

    /** The programmes of [c] that touch [from, to). */
    static List<Slot> window(Channel c, long from, long to) {
        List<Slot> out = new ArrayList<>();
        if (c.total <= 0) return out;
        long pos = Math.floorMod(from - EPOCH, c.total);
        int i = 0;
        while (i < c.loop.size() && pos >= c.loop.get(i).durMs) {
            pos -= c.loop.get(i).durMs;
            i++;
        }
        long start = from - pos;
        for (int guard = 0; start < to && guard < 5000; guard++) {
            Prog p = c.loop.get(i % c.loop.size());
            Slot s = new Slot();
            s.prog = p;
            s.start = start;
            s.end = start + p.durMs;
            out.add(s);
            start = s.end;
            i++;
        }
        return out;
    }

    static Slot nowOn(Channel c) {
        long now = System.currentTimeMillis();
        List<Slot> w = window(c, now, now + 1);
        return w.isEmpty() ? null : w.get(0);
    }

    /** Switch to a channel: joins whatever is on right now, part-way through. */
    static void tune(Context ctx, Channel c) {
        Slot s = nowOn(c);
        if (s == null) return;
        long off = Math.max(0, System.currentTimeMillis() - s.start);
        if (s.end - System.currentTimeMillis() < 15_000) { // about to end: join the next one at its start
            List<Slot> n = window(c, s.end, s.end + 1);
            if (!n.isEmpty()) s = n.get(0);
            off = 0;
        }
        Intent i = new Intent(ctx, PlayerActivity.class);
        i.putExtra("key", s.prog.key);
        i.putExtra("start", off);
        i.putExtra("channel", c.num);
        i.putExtra("liveStart", off > 0 ? s.start : 0L); // lets the player catch up to the clock once it has opened
        ctx.startActivity(i);
        if (ctx instanceof Activity) ((Activity) ctx).overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
