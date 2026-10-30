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
        mode = VOTE_MODE;
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
        mode = VOTE_MODE;
        rounds.clear();
        names.clear();
        scores.clear();
        answers.clear();
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
        if (mode != VOTE_MODE) return gameJson(guest);
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

    // ------------------------------------------------------------------ games: guess the film, movie quiz

    static final int VOTE_MODE = 0, GUESS = 1, QUIZ = 2, TRAILER = 3, EMOJI = 4;
    static final int G_LOBBY = 0, G_ASK = 1, G_RESULT = 2, G_FINAL = 3;
    private static final long RESULT_MS = 4500;

    /** One question: a prompt (or a poster that sharpens), four options, and which one is right. */
    static final class Round {
        final String prompt;
        final String[] options;
        final int correct;
        final String thumb; // guess the film: the poster the TV sharpens, "" for text questions
        final String trailer;   // trailer guess: the clip the TV plays (never sent to phones)
        final boolean audioOnly; // ... with the picture hidden
        final long ms;

        Round(String prompt, String[] options, int correct, String thumb, long ms) {
            this(prompt, options, correct, thumb, ms, "", false);
        }

        Round(String prompt, String[] options, int correct, String thumb, long ms, String trailer, boolean audioOnly) {
            this.trailer = trailer == null ? "" : trailer;
            this.audioOnly = audioOnly;
            this.prompt = prompt;
            this.options = options;
            this.correct = correct;
            this.thumb = thumb == null ? "" : thumb;
            this.ms = ms;
        }
    }

    private static int mode = VOTE_MODE;
    private static int gPhase = G_LOBBY;
    private static int gRound;
    private static long gEndsAt;
    private static final List<Round> rounds = new ArrayList<>();
    private static final Map<String, String> names = new HashMap<>();   // guest address -> name
    private static final Map<String, Integer> scores = new HashMap<>();
    private static final Map<String, int[]> answers = new HashMap<>();  // this round: guest -> {choice, points}

    /** Opens a game lobby: players join with a name, the host starts it. Returns the code for the link. */
    static synchronized String startGame(int gameMode, List<Round> list) {
        byte[] b = new byte[6];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        token = sb.toString();
        mode = gameMode;
        active = true;
        films.clear();
        votes.clear();
        guestAdd = false;
        names.clear();
        scores.clear();
        answers.clear();
        loadRounds(list);
        return token;
    }

    private static void loadRounds(List<Round> list) {
        rounds.clear();
        rounds.addAll(list);
        gPhase = G_LOBBY;
        gRound = 0;
    }

    /** Another game with the same players and code: new questions, scores back to zero. */
    static synchronized void restartGame(List<Round> list) {
        if (!active || mode == VOTE_MODE) return;
        for (String k : new ArrayList<>(scores.keySet())) scores.put(k, 0);
        answers.clear();
        loadRounds(list);
    }

    static synchronized int mode() {
        return mode;
    }

    static synchronized boolean join(String guest, String name) {
        if (!active || mode == VOTE_MODE || guest == null || guest.isEmpty()) return false;
        String n = name == null ? "" : name.trim().replaceAll("[<>&\"]", "");
        if (n.isEmpty()) return false;
        if (n.length() > 14) n = n.substring(0, 14);
        names.put(guest, n);
        if (!scores.containsKey(guest)) scores.put(guest, 0);
        return true;
    }

    /** The host starts the game: round one begins. */
    static synchronized void beginGame() {
        if (!active || mode == VOTE_MODE || gPhase != G_LOBBY || rounds.isEmpty()) return;
        gRound = 0;
        startRound();
    }

    private static void startRound() {
        answers.clear();
        gPhase = G_ASK;
        gEndsAt = System.currentTimeMillis() + rounds.get(gRound).ms;
    }

    /** Moves the game on when time is up or everyone has answered. Safe to call as often as you like. */
    static synchronized void gameTick() {
        if (mode == VOTE_MODE || !active) return;
        long now = System.currentTimeMillis();
        if (gPhase == G_ASK && (now >= gEndsAt || (!names.isEmpty() && answers.size() >= names.size()))) {
            gPhase = G_RESULT;
            gEndsAt = now + RESULT_MS;
        } else if (gPhase == G_RESULT && now >= gEndsAt) {
            if (gRound + 1 < rounds.size()) {
                gRound++;
                startRound();
            } else {
                gPhase = G_FINAL;
            }
        }
    }

    /** A player's answer: the first one counts; right answers score more the faster they come (500 to 1000). */
    static synchronized boolean answer(String guest, int round, int choice) {
        gameTick();
        if (gPhase != G_ASK || round != gRound || !names.containsKey(guest) || answers.containsKey(guest)) return false;
        Round r = rounds.get(gRound);
        if (choice < 0 || choice >= r.options.length) return false;
        int pts = 0;
        if (choice == r.correct) {
            double left = Math.max(0, gEndsAt - System.currentTimeMillis());
            pts = 500 + (int) (500.0 * left / r.ms);
            scores.put(guest, scores.getOrDefault(guest, 0) + pts);
        }
        answers.put(guest, new int[]{choice, pts});
        return true;
    }

    /** What the TV needs, as plain values. */
    static final class Snap {
        int phase, round, total, answered, players;
        long left;
        Round question;
        List<Object[]> board = new ArrayList<>(); // {name, score}
    }

    static synchronized Snap snap() {
        gameTick();
        Snap s = new Snap();
        s.phase = gPhase;
        s.round = gRound;
        s.total = rounds.size();
        s.answered = answers.size();
        s.players = names.size();
        s.left = Math.max(0, gEndsAt - System.currentTimeMillis());
        s.question = rounds.isEmpty() ? null : rounds.get(Math.min(gRound, rounds.size() - 1));
        List<Map.Entry<String, Integer>> es = new ArrayList<>(scores.entrySet());
        java.util.Collections.sort(es, (a, b) -> b.getValue() - a.getValue());
        for (Map.Entry<String, Integer> e : es) s.board.add(new Object[]{names.getOrDefault(e.getKey(), "?"), e.getValue()});
        return s;
    }

    private static String gameJson(String guest) {
        try {
            gameTick();
            JSONObject o = new JSONObject();
            o.put("mode", mode);
            o.put("gPhase", gPhase);
            o.put("round", gRound);
            o.put("total", rounds.size());
            o.put("left", gPhase == G_ASK || gPhase == G_RESULT ? Math.max(0, gEndsAt - System.currentTimeMillis()) : 0);
            o.put("name", guest == null ? "" : names.getOrDefault(guest, ""));
            o.put("myScore", guest == null ? 0 : scores.getOrDefault(guest, 0));
            int[] mine = guest == null ? null : answers.get(guest);
            o.put("myChoice", mine == null ? -1 : mine[0]);
            o.put("myPoints", mine == null ? 0 : mine[1]);
            o.put("players", names.size());
            o.put("answered", answers.size());
            if ((gPhase == G_ASK || gPhase == G_RESULT) && !rounds.isEmpty()) {
                Round r = rounds.get(gRound);
                o.put("prompt", r.prompt);
                o.put("ms", r.ms);
                JSONArray opts = new JSONArray();
                for (String s : r.options) opts.put(s);
                o.put("options", opts);
                if (gPhase == G_RESULT) o.put("correct", r.correct);
            }
            JSONArray board = new JSONArray();
            List<Map.Entry<String, Integer>> es = new ArrayList<>(scores.entrySet());
            java.util.Collections.sort(es, (a, b) -> b.getValue() - a.getValue());
            int rank = 0, myRank = 0;
            for (Map.Entry<String, Integer> e : es) {
                rank++;
                if (e.getKey().equals(guest)) myRank = rank;
                if (rank <= 8) board.put(new JSONObject().put("name", names.getOrDefault(e.getKey(), "?")).put("score", e.getValue()));
            }
            o.put("board", board);
            o.put("myRank", myRank);
            return o.toString();
        } catch (Exception e) {
            return "{\"mode\":1,\"gPhase\":0}";
        }
    }
}
