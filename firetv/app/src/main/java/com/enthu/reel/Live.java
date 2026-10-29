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
        int num;
        String name;
        final List<Prog> loop = new ArrayList<>();
        long total;
    }

    /** {channel number, name, genre words (any matches), "movie" or "show"} */
    private static final String[][] LINEUP = {
            {"1", "Adventure", "adventure", "movie"},
            {"2", "Action Movies", "action", "movie"},
            {"3", "Comedy TV", "comedy", "show"},
            {"4", "Sci-Fi", "science fiction,sci-fi", "movie"},
            {"5", "Drama TV", "drama", "show"},
            {"6", "Horror", "horror", "movie"},
            {"7", "Comedy Movies", "comedy", "movie"},
            {"8", "Animation", "animation", "movie"},
            {"9", "Crime TV", "crime", "show"},
            {"10", "Thrillers", "thriller", "movie"},
            {"11", "Documentaries", "documentary", "movie"},
            {"12", "Sci-Fi & Fantasy TV", "sci-fi,fantasy,science fiction", "show"},
    };

    private static final long EPOCH = 1_700_000_000_000L; // any fixed moment: the loops start here
    private static final long MIN_PROG = 4 * 60_000L;
    private static List<Channel> built;
    private static long builtAt;

    static List<Channel> cached() {
        return built;
    }

    static Channel byNum(int n) {
        if (built != null) for (Channel c : built) if (c.num == n) return c;
        return null;
    }

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
            for (int i = 0; i < all.length(); i++) (movie ? movies : shows).add(all.getJSONObject(i));
        }
        sortByKey(movies);
        sortByKey(shows);
        List<Channel> out = new ArrayList<>();
        for (String[] def : LINEUP) {
            Channel c = new Channel();
            c.num = Integer.parseInt(def[0]);
            c.name = def[1];
            Random rnd = new Random(def[1].hashCode());
            List<JSONObject> pool = match(def[3].equals("movie") ? movies : shows, def[2].split(","));
            Collections.shuffle(pool, rnd);
            if (def[3].equals("movie")) {
                for (JSONObject m : pool) {
                    if (c.loop.size() >= 150) break;
                    Prog p = prog(m, "");
                    if (p != null) c.loop.add(p);
                }
            } else {
                buildShows(c, pool, rnd);
            }
            for (Prog p : c.loop) c.total += p.durMs;
            if (c.total > 0) out.add(c);
        }
        built = out;
        builtAt = now;
        return out;
    }

    private static void sortByKey(List<JSONObject> l) {
        Collections.sort(l, (a, b) -> a.optString("ratingKey").compareTo(b.optString("ratingKey")));
    }

    private static List<JSONObject> match(List<JSONObject> src, String[] words) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject m : src) {
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
    private static void buildShows(Channel c, List<JSONObject> pool, Random rnd) throws Exception {
        List<List<Prog>> lists = new ArrayList<>();
        for (int i = 0; i < pool.size() && lists.size() < 8; i++) {
            JSONObject show = pool.get(i);
            List<Prog> eps = new ArrayList<>();
            for (Plex.Item e : Plex.episodesOf(show.optString("ratingKey"))) {
                if (e.duration < MIN_PROG || e.parentIndex <= 0) continue; // skip specials
                Prog p = new Prog();
                p.key = e.key;
                p.title = show.optString("title");
                p.sub = "S" + e.parentIndex + " · E" + e.index + "  " + e.title;
                p.durMs = e.duration;
                eps.add(p);
                if (eps.size() >= 120) break;
            }
            if (!eps.isEmpty()) lists.add(eps);
        }
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
        long t = from - pos;                // start of the loop that [from] falls in
        int i = 0;
        while (i < c.loop.size() && pos >= c.loop.get(i).durMs) {
            pos -= c.loop.get(i).durMs;
            t += c.loop.get(i).durMs;
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
        ctx.startActivity(i);
        if (ctx instanceof Activity) ((Activity) ctx).overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
