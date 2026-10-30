package com.enthu.reel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Party mode: a handful of random films from one library, voted on from guests' phones.
 * Phases: LOBBY (the code is up, guests wait), VOTING (a countdown runs), DONE (the winner is known).
 * One vote per guest: a guest is identified by their device's address on the Wi-Fi, so clearing the browser or
 * opening a private tab doesn't give a second vote. Changing your mind replaces your vote.
 * Guests only ever get this page's own link code, never the phone remote's, so they can vote but not control the TV.
 */
final class Party {
    static final int LOBBY = 0, VOTING = 1, DONE = 2;

    static final class Film {
        final String key;
        final String title;
        final String sub;
        final String thumb;

        Film(String key, String title, String sub, String thumb) {
            this.key = key;
            this.title = title;
            this.sub = sub;
            this.thumb = thumb == null ? "" : thumb;
        }
    }

    private static int phase = DONE;
    private static boolean active;
    private static String token = "";
    private static final List<Film> films = new ArrayList<>();
    private static final Map<String, String> votes = new HashMap<>(); // guest address -> film key
    private static String winnerKey = "";
    private static long endsAt;
    private static String sectionKey = "";
    private static boolean guestAdd;
    private static final Map<String, Integer> adds = new HashMap<>(); // guest address -> films they added
    static final int MAX_FILMS = 16;
    static final int ADDS_EACH = 2;

    private Party() {
    }

    /** Opens a lobby for these films (guests can see the page but not vote yet); returns the code for the link. */
    static synchronized String start(List<Film> list, String section, boolean guestsMayAdd) {
        sectionKey = section == null ? "" : section;
        guestAdd = guestsMayAdd;
        adds.clear();
        byte[] b = new byte[6];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        token = sb.toString();
        films.clear();
        films.addAll(list);
        votes.clear();
        winnerKey = "";
        endsAt = 0;
        phase = LOBBY;
        active = true;
        return token;
    }

    /** The host starts the vote: it runs for [ms], then closes by itself. */
    static synchronized void begin(long ms) {
        if (!active || phase != LOBBY) return;
        endsAt = System.currentTimeMillis() + ms;
        phase = VOTING;
    }

    static synchronized int phase() {
        checkTime();
        return phase;
    }

    /** Closes the vote when the time is up. */
    private static void checkTime() {
        if (phase == VOTING && System.currentTimeMillis() >= endsAt) finish();
    }

    static synchronized long remainingMs() {
        checkTime();
        return phase == VOTING ? Math.max(0, endsAt - System.currentTimeMillis()) : 0;
    }

    static synchronized boolean valid(String p) {
        return active && p != null && !token.isEmpty() && token.equals(p);
    }

    static synchronized String token() {
        return token;
    }

    static synchronized boolean allowsThumb(String path) {
        if (path == null) return false;
        for (Film f : films) if (!f.thumb.isEmpty() && f.thumb.equals(path)) return true;
        // when guests may add films, the posters of their search results are fine too (and only posters)
        return guestAdd && path.matches("^/library/metadata/\\d+/thumb/\\d+$");
    }

    /** A new round with the same code and settings, so guests' pages carry on: fresh films, no votes. */
    static synchronized void restart(List<Film> list) {
        if (!active) return;
        films.clear();
        films.addAll(list);
        votes.clear();
        adds.clear();
        winnerKey = "";
        endsAt = 0;
        phase = LOBBY;
    }

    static synchronized String section() {
        return sectionKey;
    }

    static synchronized boolean guestsMayAdd() {
        return guestAdd;
    }

    /** A guest adds a film from the chosen library: a couple each, up to a cap, no repeats, not once the vote is over. */
    static synchronized String addFilm(String guest, Film f) {
        if (!active || !guestAdd || phase == DONE) return "closed";
        if (films.size() >= MAX_FILMS) return "full";
        for (Film x : films) if (x.key.equals(f.key)) return "already there";
        int n = adds.getOrDefault(guest, 0);
        if (n >= ADDS_EACH) return "limit";
        adds.put(guest, n + 1);
        films.add(f);
        return "";
    }

    /** One vote per guest address; voting again replaces it. */
    static synchronized boolean vote(String guest, String filmKey) {
        checkTime();
        if (phase != VOTING || guest == null || guest.isEmpty()) return false;
        for (Film f : films) {
            if (f.key.equals(filmKey)) {
                votes.put(guest, filmKey);
                return true;
            }
        }
        return false;
    }

    private static int count(String filmKey) {
        int n = 0;
        for (String v : votes.values()) if (v.equals(filmKey)) n++;
        return n;
    }

    /** Films with their votes, most votes first (ties keep the order they were drawn in). */
    static synchronized List<Object[]> ranking() {
        List<Object[]> out = new ArrayList<>();
        for (Film f : films) out.add(new Object[]{f, count(f.key)});
        java.util.Collections.sort(out, (a, b) -> Integer.compare((Integer) b[1], (Integer) a[1]));
        return out;
    }

    static synchronized int voters() {
        return votes.size();
    }

    /** Closes the vote and returns the winning film's key (a random one of those tied), or "" if no film exists. */
    static synchronized String finish() {
        if (phase == DONE) return winnerKey;
        phase = DONE;
        List<Object[]> r = ranking();
        if (r.isEmpty()) return "";
        int best = (Integer) r.get(0)[1];
        List<Film> tied = new ArrayList<>();
        for (Object[] o : r) if ((Integer) o[1] == best) tied.add((Film) o[0]);
        winnerKey = tied.get(new java.util.Random().nextInt(tied.size())).key;
        return winnerKey;
    }

    static synchronized Film winnerFilm() {
        for (Film f : films) if (f.key.equals(winnerKey)) return f;
        return null;
    }

    static synchronized int votesFor(String filmKey) {
        return count(filmKey);
    }

    static synchronized void cancel() {
        guestAdd = false;
        sectionKey = "";
        adds.clear();
        active = false;
        phase = DONE;
        token = "";
        films.clear();
        votes.clear();
        winnerKey = "";
    }

    static synchronized String stateJson(String guest) {
        try {
            checkTime();
            JSONObject o = new JSONObject();
            o.put("phase", phase);
            o.put("left", phase == VOTING ? Math.max(0, endsAt - System.currentTimeMillis()) : 0);
            o.put("voters", votes.size());
            o.put("mine", guest == null ? "" : votes.getOrDefault(guest, ""));
            o.put("winner", winnerKey);
            o.put("canAdd", guestAdd && phase != DONE && films.size() < MAX_FILMS);
            o.put("addsLeft", guest == null ? 0 : Math.max(0, ADDS_EACH - adds.getOrDefault(guest, 0)));
            JSONArray a = new JSONArray();
            for (Object[] r : ranking()) {
                Film f = (Film) r[0];
                a.put(new JSONObject().put("key", f.key).put("title", f.title).put("sub", f.sub)
                        .put("thumb", f.thumb).put("votes", (Integer) r[1]));
            }
            o.put("films", a);
            return o.toString();
        } catch (Exception e) {
            return "{\"phase\":2,\"films\":[]}";
        }
    }
}
