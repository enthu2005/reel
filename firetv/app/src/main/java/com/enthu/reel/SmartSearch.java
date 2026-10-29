package com.enthu.reel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-English library search with no online service: "sci-fi under two hours", "unwatched comedy",
 * "tom hanks 4k", "80s horror rated 7+", "which batman did i watch last". The words are turned into filters and
 * applied to the Plex library listing on this device.
 */
final class SmartSearch {
    static final class Query {
        boolean movies, shows;
        final Set<String> genres = new HashSet<>();
        int yearMin, yearMax;
        double minRating;
        int minMinutes, maxMinutes;
        boolean unwatched, watched, lastWatched;
        String resolution = ""; // "4k", "1080", "720"
        String person = "";
        String text = "";
        final List<String> notes = new ArrayList<>();

        /** True when the words hold at least one filter (a bare title or name goes to the ordinary search). */
        boolean smart() {
            return !genres.isEmpty() || yearMin > 0 || yearMax > 0 || minRating > 0 || minMinutes > 0 || maxMinutes > 0
                    || unwatched || watched || lastWatched || !resolution.isEmpty() || !person.isEmpty();
        }

        String describe() {
            return android.text.TextUtils.join("  ·  ", notes);
        }
    }

    private SmartSearch() {
    }

    private static final String[][] GENRES = {
            {"science fiction", "sci-fi", "sci fi", "scifi", "science fiction", "space"},
            {"comedy", "comedy", "comedies", "funny", "hilarious", "sitcom", "sitcoms"},
            {"horror", "horror", "scary", "spooky", "creepy"},
            {"action", "action", "explosive"},
            {"drama", "drama", "dramas"},
            {"thriller", "thriller", "thrillers", "suspense", "suspenseful"},
            {"romance", "romance", "romantic", "rom-com", "romcom"},
            {"animation", "animation", "animated", "cartoon", "cartoons", "anime"},
            {"documentary", "documentary", "documentaries", "docs"},
            {"fantasy", "fantasy"},
            {"crime", "crime", "heist", "gangster"},
            {"mystery", "mystery", "whodunit", "whodunnit"},
            {"war", "war"},
            {"western", "western", "westerns", "cowboy"},
            {"family", "family", "kids", "kid-friendly"},
            {"adventure", "adventure", "adventures"},
            {"music", "musical", "musicals", "music"},
            {"history", "historical", "history"},
    };

    private static final String[] NOISE = {"find", "show", "me", "give", "get", "a", "an", "the", "some", "something", "any",
            "good", "great", "best", "i", "ive", "own", "have", "that", "are", "is", "of", "to", "please", "which", "what", "did",
            "do", "my", "movies", "movie", "films", "film", "shows", "series", "tv", "episodes", "episode", "library", "one", "ones",
            "with", "and", "in", "on", "for", "watch", "watching", "want", "looking", "recommend", "new", "old", "at", "from", "about"};

    static Query parse(String raw) {
        Query q = new Query();
        String s = " " + raw.toLowerCase(Locale.ROOT).replace("’", "'").replaceAll("[,!?]", " ") + " ";

        // Last watched: "which batman did i watch last"
        Matcher m = Pattern.compile("\\b(last watched|watched last|watch last|most recently watched|recently watched|"
                + "watched recently|i last watched|latest watched)\\b").matcher(s);
        if (m.find()) {
            q.lastWatched = true;
            q.watched = true;
            q.notes.add("last watched");
            s = s.replace(m.group(), " ");
        }

        // Decades: 1980s, 80s, eighties
        m = Pattern.compile("\\b(19|20)(\\d)0'?s\\b").matcher(s);
        if (m.find()) {
            int d = Integer.parseInt(m.group(1) + m.group(2) + "0");
            q.yearMin = d;
            q.yearMax = d + 9;
            q.notes.add(d + "s");
            s = s.replace(m.group(), " ");
        } else {
            m = Pattern.compile("\\b(\\d)0'?s\\b").matcher(s);
            if (m.find()) {
                int dg = Integer.parseInt(m.group(1));
                if (dg >= 2 || dg == 0) {
                    int d = dg == 0 ? 2000 : 1900 + dg * 10;
                    q.yearMin = d;
                    q.yearMax = d + 9;
                    q.notes.add(d + "s");
                    s = s.replace(m.group(), " ");
                }
            } else {
                String[][] words = {{"fifties", "1950"}, {"sixties", "1960"}, {"seventies", "1970"}, {"eighties", "1980"},
                        {"nineties", "1990"}};
                for (String[] w : words) {
                    if (s.contains(" " + w[0] + " ")) {
                        int d = Integer.parseInt(w[1]);
                        q.yearMin = d;
                        q.yearMax = d + 9;
                        q.notes.add(d + "s");
                        s = s.replace(" " + w[0] + " ", " ");
                        break;
                    }
                }
            }
        }

        // Years
        m = Pattern.compile("\\b(?:after|since|newer than|released after)\\s+((?:19|20)\\d\\d)\\b").matcher(s);
        if (m.find()) {
            q.yearMin = Integer.parseInt(m.group(1)) + (m.group().contains("after") ? 1 : 0);
            q.notes.add("from " + q.yearMin);
            s = s.replace(m.group(), " ");
        }
        m = Pattern.compile("\\b(?:before|older than|until|prior to)\\s+((?:19|20)\\d\\d)\\b").matcher(s);
        if (m.find()) {
            q.yearMax = Integer.parseInt(m.group(1)) - 1;
            q.notes.add("before " + m.group(1));
            s = s.replace(m.group(), " ");
        }
        m = Pattern.compile("\\b(?:in|from|of|year)?\\s*((?:19|20)\\d\\d)\\b").matcher(s);
        if (q.yearMin == 0 && q.yearMax == 0 && m.find()) {
            q.yearMin = q.yearMax = Integer.parseInt(m.group(1));
            q.notes.add(m.group(1));
            s = s.replace(m.group(), " ");
        }

        // Runtime
        String num = "(\\d+(?:\\.\\d+)?|an|one|two|three|four|a)";
        m = Pattern.compile("\\b(?:under|less than|shorter than|below|no more than|within|max)\\s+" + num
                + "(?:\\s+and a half)?\\s*(hours?|hrs?|h|minutes?|mins?|m)\\b").matcher(s);
        if (m.find()) {
            q.maxMinutes = minutes(m.group(1), m.group().contains("and a half"), m.group(2));
            q.notes.add("under " + runtimeText(q.maxMinutes));
            s = s.replace(m.group(), " ");
        }
        m = Pattern.compile("\\b(?:over|more than|longer than|at least|above)\\s+" + num
                + "(?:\\s+and a half)?\\s*(hours?|hrs?|minutes?|mins?)\\b").matcher(s);
        if (m.find()) {
            q.minMinutes = minutes(m.group(1), m.group().contains("and a half"), m.group(2));
            q.notes.add("over " + runtimeText(q.minMinutes));
            s = s.replace(m.group(), " ");
        }
        if (s.contains(" short ")) {
            q.maxMinutes = 100;
            q.notes.add("short");
            s = s.replace(" short ", " ");
        }

        // Rating out of 10
        String[] ratingPatterns = {
                "\\b(?:rating|rated|score|scored)\\s+(?:of\\s+)?(?:at least|over|above|more than|higher than|min)?\\s*(\\d(?:\\.\\d)?)\\b",
                "\\b(?:at least|over|above|more than|higher than|minimum)\\s+(?:a\\s+)?(\\d(?:\\.\\d)?)\\s*(?:rating|score|stars?|out of 10|/10)",
                "\\b(\\d(?:\\.\\d)?)\\s*\\+(?=\\s)",
                "\\b(\\d(?:\\.\\d)?)\\s*(?:or (?:higher|more|better|above)|out of 10|/10)\\b"};
        for (String p : ratingPatterns) {
            m = Pattern.compile(p).matcher(s);
            if (m.find()) {
                double r = Double.parseDouble(m.group(1));
                if (r > 0 && r <= 10) {
                    q.minRating = r;
                    q.notes.add("rated " + (r == Math.floor(r) ? String.valueOf((int) r) : String.valueOf(r)) + "+");
                    s = s.replace(m.group(), " ");
                    break;
                }
            }
        }
        if (q.minRating == 0) {
            m = Pattern.compile("\\b(highly rated|well rated|well-rated|top rated|top-rated|acclaimed|excellent|critically acclaimed)\\b").matcher(s);
            if (m.find()) {
                q.minRating = 8;
                q.notes.add("rated 8+");
                s = s.replace(m.group(), " ");
            } else {
                m = Pattern.compile("\\b(good|great|best)\\b").matcher(s);
                if (m.find()) {
                    q.minRating = m.group().equals("good") ? 7 : 7.5;
                    q.notes.add("well rated");
                    s = s.replaceFirst("\\b(good|great|best)\\b", " ");
                }
            }
        }

        // Watched or not
        m = Pattern.compile("\\b(haven'?t|have not|havent|never|not|yet to)\\s+(?:yet\\s+)?(?:watched|seen)\\b|\\bunwatched\\b|"
                + "\\bnot seen\\b|\\bnew to me\\b|\\bnever seen\\b").matcher(s);
        if (m.find()) {
            q.unwatched = true;
            q.notes.add("unwatched");
            s = s.replace(m.group(), " ");
        } else if (!q.lastWatched) {
            m = Pattern.compile("\\b(?:i'?ve|i have)\\s+(?:already\\s+)?(?:watched|seen)\\b|\\balready (?:watched|seen)\\b|\\bwatched\\b").matcher(s);
            if (m.find()) {
                q.watched = true;
                q.notes.add("watched");
                s = s.replace(m.group(), " ");
            }
        }

        // Picture size
        m = Pattern.compile("\\b(4k|uhd|2160p|ultra hd)\\b").matcher(s);
        if (m.find()) {
            q.resolution = "4k";
            q.notes.add("4K");
            s = s.replace(m.group(), " ");
        } else {
            m = Pattern.compile("\\b(1080p?|full hd)\\b").matcher(s);
            if (m.find()) {
                q.resolution = "1080";
                q.notes.add("1080p");
                s = s.replace(m.group(), " ");
            } else {
                m = Pattern.compile("\\b(720p?)\\b").matcher(s);
                if (m.find()) {
                    q.resolution = "720";
                    q.notes.add("720p");
                    s = s.replace(m.group(), " ");
                }
            }
        }

        // Movies or shows
        if (Pattern.compile("\\b(movies?|films?)\\b").matcher(s).find()) q.movies = true;
        if (Pattern.compile("\\b(shows?|series|tv|sitcoms?)\\b").matcher(s).find()) q.shows = true;
        if (q.movies && !q.shows) q.notes.add(0, "movies");
        if (q.shows && !q.movies) q.notes.add(0, "shows");

        // Genres
        for (String[] g : GENRES) {
            for (int i = 1; i < g.length; i++) {
                Matcher gm = Pattern.compile("(?<![a-z])" + Pattern.quote(g[i]) + "(?![a-z])").matcher(s);
                if (gm.find()) {
                    if (q.genres.add(g[0])) q.notes.add(0, g[0].equals("science fiction") ? "sci-fi" : g[0]);
                    s = s.replace(gm.group(), " ");
                    break;
                }
            }
        }

        // People: "with tom hanks", "directed by nolan"
        m = Pattern.compile("\\b(?:starring|featuring|directed by|director|with|by|actor)\\s+([a-z][a-z'.\\- ]+?)"
                + "(?=\\s+(?:in|that|which|under|over|rated|and|i|from|before|after|on)\\b|\\s*$)").matcher(s);
        if (m.find()) {
            String name = m.group(1).trim();
            if (name.split("\\s+").length <= 4 && !name.isEmpty()) {
                q.person = name;
                q.notes.add("with " + name);
                s = s.replace(m.group(), " ");
            }
        }

        // What's left: the title or a name
        StringBuilder rest = new StringBuilder();
        Set<String> noise = new HashSet<>();
        Collections.addAll(noise, NOISE);
        for (String w : s.replaceAll("[^a-z0-9'\\- ]", " ").trim().split("\\s+")) {
            if (w.isEmpty() || noise.contains(w.replace("'", ""))) continue;
            if (rest.length() > 0) rest.append(' ');
            rest.append(w);
        }
        q.text = rest.toString();
        if (!q.text.isEmpty() && q.smart()) q.notes.add("“" + q.text + "”");
        return q;
    }

    private static int minutes(String n, boolean andHalf, String unit) {
        double v;
        switch (n) {
            case "an":
            case "a":
            case "one":
                v = 1;
                break;
            case "two":
                v = 2;
                break;
            case "three":
                v = 3;
                break;
            case "four":
                v = 4;
                break;
            default:
                try {
                    v = Double.parseDouble(n);
                } catch (NumberFormatException e) {
                    v = 0;
                }
        }
        if (andHalf) v += 0.5;
        boolean hours = unit.startsWith("h");
        return (int) Math.round(hours ? v * 60 : v);
    }

    private static String runtimeText(int min) {
        return min % 60 == 0 ? (min / 60) + " h" : min >= 60 ? (min / 60) + " h " + (min % 60) + " m" : min + " min";
    }

    // ------------------------------------------------------------------ running it

    private static final class Hit {
        Plex.Item item;
        double rating;
        long lastViewed;
        boolean titleMatch;
    }

    static List<Plex.Item> run(Query q) throws Exception {
        List<Hit> hits = new ArrayList<>();
        String text = q.text.toLowerCase(Locale.ROOT);
        String person = q.person.toLowerCase(Locale.ROOT);
        for (Plex.Section sec : Plex.sections()) {
            if (!Plex.isLibraryEnabled(sec.key)) continue;
            boolean movie = sec.type.equals("movie");
            if (q.movies && !q.shows && !movie) continue;
            if (q.shows && !q.movies && movie) continue;
            JSONArray all = Plex.listAll(sec.key);
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.getJSONObject(i);
                int year = o.optInt("year", 0);
                if (q.yearMin > 0 && year < q.yearMin) continue;
                if (q.yearMax > 0 && year > q.yearMax) continue;
                long dur = o.optLong("duration", 0);
                if (movie || q.maxMinutes > 0 || q.minMinutes > 0) {
                    if (q.maxMinutes > 0 && (dur <= 0 || dur > q.maxMinutes * 60_000L)) continue;
                    if (q.minMinutes > 0 && dur < q.minMinutes * 60_000L) continue;
                }
                double rating = o.optDouble("audienceRating", -1);
                if (rating <= 0) rating = o.optDouble("rating", -1);
                if (q.minRating > 0 && rating < q.minRating) continue;
                boolean seen = movie ? o.optInt("viewCount", 0) > 0
                        : o.optInt("viewedLeafCount", 0) > 0;
                boolean fully = movie ? seen : o.optInt("leafCount", 0) > 0 && o.optInt("viewedLeafCount", 0) >= o.optInt("leafCount", 0);
                if (q.unwatched && (movie ? seen : o.optInt("viewedLeafCount", 0) > 0)) continue;
                if (q.watched && !seen) continue;
                if (!q.resolution.isEmpty()) {
                    JSONArray media = o.optJSONArray("Media");
                    String res = media != null && media.length() > 0
                            ? media.getJSONObject(0).optString("videoResolution", "") : "";
                    if (!res.equalsIgnoreCase(q.resolution)) continue;
                }
                if (!q.genres.isEmpty() && !hasAll(o.optJSONArray("Genre"), q.genres)) continue;
                String title = o.optString("title", "").toLowerCase(Locale.ROOT);
                boolean titleMatch = false;
                if (!person.isEmpty()) {
                    if (!hasName(o.optJSONArray("Role"), person) && !hasName(o.optJSONArray("Director"), person)
                            && !hasName(o.optJSONArray("Writer"), person)) continue;
                }
                if (!text.isEmpty()) {
                    titleMatch = containsAll(title, text);
                    if (!titleMatch && !hasName(o.optJSONArray("Role"), text) && !hasName(o.optJSONArray("Director"), text)
                            && !containsAll(o.optString("summary", "").toLowerCase(Locale.ROOT), text)) continue;
                }
                Hit h = new Hit();
                h.item = Plex.parseItem(o);
                h.rating = rating;
                h.lastViewed = o.optLong("lastViewedAt", 0);
                h.titleMatch = titleMatch;
                hits.add(h);
            }
        }
        final boolean last = q.lastWatched;
        final boolean byRating = q.minRating > 0;
        Collections.sort(hits, new Comparator<Hit>() {
            @Override
            public int compare(Hit a, Hit b) {
                if (last) return Long.compare(b.lastViewed, a.lastViewed);
                if (a.titleMatch != b.titleMatch) return a.titleMatch ? -1 : 1;
                if (byRating || a.rating != b.rating) return Double.compare(b.rating, a.rating);
                return a.item.title.compareToIgnoreCase(b.item.title);
            }
        });
        List<Plex.Item> out = new ArrayList<>();
        for (Hit h : hits) {
            out.add(h.item);
            if (out.size() >= (last ? 12 : 60)) break;
        }
        return out;
    }

    private static boolean hasAll(JSONArray tags, Set<String> want) {
        if (tags == null) return false;
        Set<String> have = new HashSet<>();
        for (int i = 0; i < tags.length(); i++) have.add(tags.optJSONObject(i).optString("tag", "").toLowerCase(Locale.ROOT));
        for (String w : want) {
            boolean ok = false;
            for (String h : have) if (h.contains(w) || (w.equals("science fiction") && h.contains("sci-fi"))) ok = true;
            if (!ok) return false;
        }
        return true;
    }

    private static boolean hasName(JSONArray tags, String name) {
        if (tags == null) return false;
        for (int i = 0; i < tags.length(); i++) {
            JSONObject t = tags.optJSONObject(i);
            if (t != null && containsAll(t.optString("tag", "").toLowerCase(Locale.ROOT), name)) return true;
        }
        return false;
    }

    /** Every word of [words] appears in [hay]. */
    private static boolean containsAll(String hay, String words) {
        for (String w : words.split("\\s+")) if (!w.isEmpty() && !hay.contains(w)) return false;
        return true;
    }
}
