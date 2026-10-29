package com.enthu.reel;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Hours watched, top genres and actors, and when you watch. "Wrapped" tells it as a slideshow. */
public class StatsActivity extends ReelActivity {

    static class Ranked {
        final String name;
        final double value; // hours, or a count
        final int count;

        Ranked(String name, double value, int count) {
            this.name = name;
            this.value = value;
            this.count = count;
        }
    }

    /**
     * What you have watched, worked out from the watch state Plex keeps for this profile (when each film or
     * episode was last watched, and how many times). A rewatch inside a year only shows once; "All time" counts
     * every play.
     */
    static class WatchStats {
        final int year; // 0 = all time
        double hours;
        int movies;
        int episodes;
        int shows;
        List<Ranked> genres = new ArrayList<>();
        List<Ranked> actors = new ArrayList<>();
        List<Ranked> topShows = new ArrayList<>();
        List<Ranked> topMovies = new ArrayList<>();
        final double[] weekday = new double[7]; // hours, Monday first
        final double[] hourOfDay = new double[24];
        final double[] month = new double[12];
        int longestStreak;

        static final String[] WEEKDAYS = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
        static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August",
                "September", "October", "November", "December"};

        WatchStats(int year) {
            this.year = year;
        }

        boolean isEmpty() {
            return hours <= 0;
        }

        private static int top(double[] a) {
            int b = 0;
            for (int i = 1; i < a.length; i++) if (a[i] > a[b]) b = i;
            return b;
        }

        int busiestWeekday() {
            return top(weekday);
        }

        int busiestMonth() {
            return top(month);
        }

        int peakHour() {
            return top(hourOfDay);
        }

        String persona() {
            int h = peakHour();
            if (h >= 22 || h < 4) return "Night owl";
            if (h >= 4 && h < 10) return "Early bird";
            if (h >= 10 && h < 17) return "Daytime viewer";
            return "Evening regular";
        }

        private static List<String> tags(JSONArray a) {
            List<String> out = new ArrayList<>();
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject t = a.optJSONObject(i);
                    if (t != null && !t.optString("tag").isEmpty()) out.add(t.optString("tag"));
                }
            }
            return out;
        }

        static WatchStats load(int year) throws Exception {
            final WatchStats st = new WatchStats(year);
            Map<String, Double> genreMs = new HashMap<>();
            Map<String, Integer> actorCount = new HashMap<>();
            Map<String, Double> showMs = new HashMap<>();
            Map<String, Integer> showEps = new HashMap<>();
            List<Ranked> movieRows = new ArrayList<>();
            Set<Long> days = new HashSet<>();
            Calendar cal = Calendar.getInstance();

            for (Plex.Section sec : Plex.allSections()) {
                if (!Plex.isLibraryEnabled(sec.key)) continue;
                if (sec.type.equals("movie")) {
                    JSONArray all = Plex.listAll(sec.key);
                    for (int i = 0; i < all.length(); i++) {
                        JSONObject m = all.getJSONObject(i);
                        int seen = m.optInt("viewCount");
                        long at = m.optLong("lastViewedAt");
                        if (seen <= 0 || at <= 0) continue;
                        cal.setTimeInMillis(at * 1000);
                        if (year != 0 && cal.get(Calendar.YEAR) != year) continue;
                        int plays = year == 0 ? seen : 1;
                        double ms = m.optLong("duration") * (double) plays;
                        if (ms <= 0) continue;
                        st.movies++;
                        st.note(cal, ms, days);
                        for (String g : tags(m.optJSONArray("Genre"))) genreMs.merge(g, ms, Double::sum);
                        List<String> roles = tags(m.optJSONArray("Role"));
                        for (int a = 0; a < roles.size() && a < 6; a++) actorCount.merge(roles.get(a), 1, Integer::sum);
                        movieRows.add(new Ranked(m.optString("title"), ms / 3600000, plays));
                    }
                } else if (sec.type.equals("show")) {
                    Map<String, JSONObject> byKey = new HashMap<>();
                    JSONArray shows = Plex.listAll(sec.key);
                    for (int i = 0; i < shows.length(); i++) byKey.put(shows.getJSONObject(i).optString("ratingKey"), shows.getJSONObject(i));
                    Set<String> seenShows = new HashSet<>();
                    JSONArray eps = Plex.listWatchedEpisodes(sec.key);
                    for (int i = 0; i < eps.length(); i++) {
                        JSONObject e = eps.getJSONObject(i);
                        int seen = e.optInt("viewCount");
                        long at = e.optLong("lastViewedAt");
                        if (seen <= 0 || at <= 0) continue;
                        cal.setTimeInMillis(at * 1000);
                        if (year != 0 && cal.get(Calendar.YEAR) != year) continue;
                        String key = e.optString("grandparentRatingKey");
                        JSONObject show = byKey.get(key);
                        long dur = e.optLong("duration");
                        if (dur <= 0 && show != null) dur = show.optLong("duration");
                        if (dur <= 0) dur = 40 * 60000L;
                        int plays = year == 0 ? seen : 1;
                        double ms = dur * (double) plays;
                        st.episodes++;
                        st.note(cal, ms, days);
                        String title = e.optString("grandparentTitle", show == null ? "" : show.optString("title"));
                        showMs.merge(title, ms, Double::sum);
                        showEps.merge(title, 1, Integer::sum);
                        if (show != null) {
                            for (String g : tags(show.optJSONArray("Genre"))) genreMs.merge(g, ms, Double::sum);
                            if (seenShows.add(key)) {
                                List<String> roles = tags(show.optJSONArray("Role"));
                                for (int a = 0; a < roles.size() && a < 6; a++) actorCount.merge(roles.get(a), 1, Integer::sum);
                            }
                        }
                    }
                    st.shows += seenShows.size();
                }
            }

            for (Map.Entry<String, Double> g : genreMs.entrySet()) st.genres.add(new Ranked(g.getKey(), g.getValue() / 3600000, 0));
            for (Map.Entry<String, Integer> a : actorCount.entrySet()) st.actors.add(new Ranked(a.getKey(), a.getValue(), a.getValue()));
            for (Map.Entry<String, Double> s : showMs.entrySet())
                st.topShows.add(new Ranked(s.getKey(), s.getValue() / 3600000, showEps.get(s.getKey())));
            st.topMovies = movieRows;
            for (List<Ranked> l : java.util.Arrays.asList(st.genres, st.actors, st.topShows, st.topMovies)) {
                Collections.sort(l, (a, b) -> Double.compare(b.value, a.value));
            }
            st.genres = cut(st.genres);
            st.actors = cut(st.actors);
            st.topShows = cut(st.topShows);
            st.topMovies = cut(st.topMovies);

            List<Long> sorted = new ArrayList<>(days);
            Collections.sort(sorted);
            int run = 0;
            int best = 0;
            for (int i = 0; i < sorted.size(); i++) {
                run = (i > 0 && sorted.get(i) == sorted.get(i - 1) + 1) ? run + 1 : 1;
                if (run > best) best = run;
            }
            st.longestStreak = best;
            return st;
        }

        private static List<Ranked> cut(List<Ranked> l) {
            return new ArrayList<>(l.subList(0, Math.min(8, l.size())));
        }

        private void note(Calendar when, double ms, Set<Long> days) {
            double hrs = ms / 3600000;
            hours += hrs;
            weekday[(when.get(Calendar.DAY_OF_WEEK) + 5) % 7] += hrs; // Monday first
            hourOfDay[when.get(Calendar.HOUR_OF_DAY)] += hrs;
            month[when.get(Calendar.MONTH)] += hrs;
            days.add((when.getTimeInMillis() + when.getTimeZone().getOffset(when.getTimeInMillis())) / 86_400_000L);
        }
    }

    // ------------------------------------------------------------------ screen

    private int W;
    private int H;
    private FrameLayout root;
    private LinearLayout body;
    private ScrollView scroll;
    private TextView status;
    private int thisYear;
    private int year;
    private WatchStats stats;
    private int slideAt = -1;      // -1 = the stats page, otherwise the Wrapped slide shown
    private List<Object[]> wrapped = new ArrayList<>();
    private int token;

    private static String hours(double h) {
        return h >= 100 ? String.valueOf(Math.round(h)) : String.format(Locale.US, "%.1f", h);
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        t.setTypeface(bold ? UiUtil.font(UiUtil.SEMIBOLD) : UiUtil.font(UiUtil.MEDIUM));
        return t;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Plex.init(this);
        SoundFx.init(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;
        thisYear = Calendar.getInstance().get(Calendar.YEAR);
        year = thisYear;
        root = new FrameLayout(this);
        root.setBackgroundColor(UiUtil.BG);
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding((int) (W * 0.08f), (int) (H * 0.06f), (int) (W * 0.08f), (int) (H * 0.06f));
        scroll.addView(body);
        root.addView(scroll);
        status = text("", H * 0.03f, UiUtil.TEXT2, false);
        setContentView(root);
        load();
    }

    private void load() {
        final int mine = ++token;
        stats = null;
        render();
        Plex.async(() -> WatchStats.load(year), new Plex.Callback<WatchStats>() {
            @Override
            public void ok(WatchStats r) {
                if (isDestroyed() || mine != token) return;
                stats = r;
                render();
            }

            @Override
            public void fail(Exception e) {
                if (isDestroyed() || mine != token) return;
                stats = null;
                status.setText("Couldn't add it up: " + e.getMessage());
                render();
            }
        });
    }

    private TextView chip(String label, final boolean on, final Runnable go) {
        final TextView t = text(label, H * 0.027f, on ? Color.BLACK : Color.WHITE, true);
        t.setPadding((int) (W * 0.016f), (int) (H * 0.014f), (int) (W * 0.016f), (int) (H * 0.014f));
        t.setBackground(UiUtil.rounded(on ? 0xFFFFFFFF : UiUtil.FILL, H));
        t.setFocusable(true);
        t.setOnFocusChangeListener((v, has) -> {
            t.setBackground(UiUtil.rounded(has ? 0xFFE8833A : on ? 0xFFFFFFFF : UiUtil.FILL, H));
            t.setTextColor(has || on ? Color.BLACK : Color.WHITE);
            if (has) SoundFx.move();
        });
        t.setOnClickListener(v -> {
            SoundFx.click();
            go.run();
        });
        return t;
    }

    private void render() {
        body.removeAllViews();
        TextView title = text("Stats", H * 0.055f, Color.WHITE, true);
        body.addView(title);
        body.addView(text("What you have watched on this profile", H * 0.025f, UiUtil.TEXT2, false));

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, (int) (H * 0.02f), 0, (int) (H * 0.02f));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.rightMargin = (int) (W * 0.01f);
        final int[] choices = {thisYear, thisYear - 1, 0};
        String[] names = {"This year", "Last year", "All time"};
        View firstChip = null;
        for (int i = 0; i < 3; i++) {
            final int y = choices[i];
            TextView c = chip(names[i], year == y, () -> {
                year = y;
                load();
            });
            chips.addView(c, cl);
            if (year == y) firstChip = c;
        }
        if (stats != null && !stats.isEmpty()) {
            chips.addView(chip("Your " + (year == 0 ? "all time" : String.valueOf(year)) + " Wrapped", false, this::startWrapped), cl);
        }
        body.addView(chips);
        if (firstChip != null) firstChip.requestFocus();

        if (stats == null) {
            if (status.getParent() != null) ((ViewGroup) status.getParent()).removeView(status);
            if (status.getText().length() == 0) status.setText("Adding it up…");
            body.addView(status);
            return;
        }
        status.setText("");
        if (stats.isEmpty()) {
            body.addView(text("Nothing watched " + (year == 0 ? "yet" : "in " + year) + ".", H * 0.03f, UiUtil.TEXT2, false));
            return;
        }
        WatchStats s = stats;
        LinearLayout nums = new LinearLayout(this);
        nums.setOrientation(LinearLayout.HORIZONTAL);
        nums.addView(bigNumber(hours(s.hours), "hours watched"));
        nums.addView(bigNumber(String.valueOf(s.movies), "films"));
        nums.addView(bigNumber(String.valueOf(s.episodes), "episodes"));
        nums.addView(bigNumber(String.valueOf(s.shows), "shows"));
        if (s.longestStreak > 1) nums.addView(bigNumber(String.valueOf(s.longestStreak), "day streak"));
        body.addView(nums);
        body.addView(text("You are a  " + s.persona() + "  ·  mostly around " + clockHour(s.peakHour())
                + "  ·  busiest day " + WatchStats.WEEKDAYS[s.busiestWeekday()] + "  ·  busiest month "
                + WatchStats.MONTHS[s.busiestMonth()], H * 0.024f, UiUtil.TEXT2, false));

        bars("Top genres", s.genres, false);
        bars("Top actors", s.actors, true);
        bars("Top shows", s.topShows, false);
        bars("Most watched films", s.topMovies, false);
        columns("Days you watch", s.weekday, new String[]{"M", "T", "W", "T", "F", "S", "S"});
        columns("Months", s.month, new String[]{"J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D"});
    }

    private View bigNumber(String n, String label) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(0, 0, (int) (W * 0.04f), 0);
        c.addView(text(n, H * 0.07f, Color.WHITE, true));
        c.addView(text(label, H * 0.022f, UiUtil.TEXT2, false));
        c.setPadding(0, (int) (H * 0.01f), (int) (W * 0.04f), (int) (H * 0.01f));
        return c;
    }

    private void bars(String heading, List<Ranked> rows, boolean actors) {
        if (rows.isEmpty()) return;
        TextView h = text(heading, H * 0.032f, Color.WHITE, true);
        h.setPadding(0, (int) (H * 0.03f), 0, (int) (H * 0.01f));
        body.addView(h);
        double max = 0;
        for (Ranked r : rows) max = Math.max(max, r.value);
        for (Ranked r : rows) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView n = text(r.name, H * 0.025f, Color.WHITE, false);
            n.setSingleLine();
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            line.addView(n, new LinearLayout.LayoutParams((int) (W * 0.24f), ViewGroup.LayoutParams.WRAP_CONTENT));
            FrameLayout track = new FrameLayout(this);
            View bar = new View(this);
            bar.setBackground(UiUtil.rounded(0xFFE8833A, H));
            int full = (int) (W * 0.4f);
            track.addView(bar, new FrameLayout.LayoutParams(Math.max(4, (int) (full * (max <= 0 ? 0 : r.value / max))), (int) (H * 0.014f)));
            line.addView(track, new LinearLayout.LayoutParams(full, (int) (H * 0.014f)));
            String v = actors ? r.count + " titles" : valueText(r);
            TextView val = text(v, H * 0.023f, UiUtil.TEXT2, false);
            val.setPadding((int) (W * 0.015f), 0, 0, 0);
            line.addView(val);
            line.setPadding(0, (int) (H * 0.006f), 0, (int) (H * 0.006f));
            body.addView(line);
        }
    }

    private static String valueText(Ranked r) {
        // shows list episodes next to hours; films list plays
        return hours(r.value) + " h" + (r.count > 1 ? "  ·  " + r.count : "");
    }

    private void columns(String heading, double[] data, String[] labels) {
        TextView h = text(heading, H * 0.032f, Color.WHITE, true);
        h.setPadding(0, (int) (H * 0.03f), 0, (int) (H * 0.01f));
        body.addView(h);
        double max = 0;
        for (double d : data) max = Math.max(max, d);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        int colH = (int) (H * 0.14f);
        int colW = (int) (W * (data.length > 7 ? 0.03f : 0.05f));
        for (int i = 0; i < data.length; i++) {
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            View bar = new View(this);
            bar.setBackground(UiUtil.rounded(0xFFE8833A, H * 0.004f));
            int bh = max <= 0 ? 2 : Math.max(2, (int) (colH * data[i] / max));
            c.addView(bar, new LinearLayout.LayoutParams((int) (colW * 0.6f), bh));
            c.addView(text(labels[i], H * 0.02f, UiUtil.TEXT2, false));
            row.addView(c, new LinearLayout.LayoutParams(colW, colH + (int) (H * 0.035f)));
        }
        body.addView(row);
    }

    private static String clockHour(int h) {
        int x = h % 12 == 0 ? 12 : h % 12;
        return x + (h < 12 ? " AM" : " PM");
    }

    // ------------------------------------------------------------------ Wrapped

    private Object[] slide(int c1, int c2, String... lines) {
        return new Object[]{c1, c2, lines};
    }

    private void startWrapped() {
        WatchStats s = stats;
        String y = s.year == 0 ? "all time" : String.valueOf(s.year);
        double days = s.hours / 24;
        wrapped = new ArrayList<>();
        // each line starts with a size tag: B big, M medium, S small
        wrapped.add(slide(0xFF3A1C71, 0xFFD76D77, "SYour", "B" + (s.year == 0 ? "All Time" : String.valueOf(s.year)),
                "BWrapped", "SPress OK to begin"));
        wrapped.add(slide(0xFF0F2027, 0xFF2C5364, "SIn " + y + " you watched", "B" + hours(s.hours), "Mhours",
                days >= 1 ? "SThat's " + String.format(Locale.US, "%.1f", days) + " whole days in front of the screen."
                        : "SA cosy " + hours(s.hours) + " hours."));
        wrapped.add(slide(0xFF42275A, 0xFF734B6D, "SThat was", "B" + s.movies, "M" + (s.movies == 1 ? "film" : "films"),
                "Sand " + s.episodes + " episodes across " + s.shows + " shows."));
        if (!s.topShows.isEmpty()) {
            Ranked r = s.topShows.get(0);
            wrapped.add(slide(0xFF134E5E, 0xFF71B280, "SYour show of the year", "M" + r.name, "B" + r.count,
                    "Sepisodes  ·  " + hours(r.value) + " hours"));
        }
        if (!s.topMovies.isEmpty()) {
            Ranked r = s.topMovies.get(0);
            wrapped.add(slide(0xFF232526, 0xFF414345, "SThe film you spent the most time with", "M" + r.name,
                    "S" + (r.count > 1 ? "watched " + r.count + " times" : hours(r.value) + " hours")));
        }
        if (!s.genres.isEmpty()) {
            List<String> l = new ArrayList<>();
            l.add("SYour top genres");
            for (int i = 0; i < s.genres.size() && i < 3; i++)
                l.add(i == 0 ? "B" + s.genres.get(i).name : "M" + (i + 1) + ".  " + s.genres.get(i).name);
            l.add("S" + hours(s.genres.get(0).value) + " hours of " + s.genres.get(0).name);
            wrapped.add(slide(0xFF8E2DE2, 0xFF4A00E0, l.toArray(new String[0])));
        }
        if (!s.actors.isEmpty()) {
            wrapped.add(slide(0xFFCB356B, 0xFFBD3F32, "SThe face you saw most", "B" + s.actors.get(0).name,
                    "Sin " + s.actors.get(0).count + " of your titles"));
        }
        wrapped.add(slide(0xFF141E30, 0xFF243B55, "SYou are a", "B" + s.persona(),
                "SMost of your watching happens around " + clockHour(s.peakHour()) + ".",
                "SBusiest day: " + WatchStats.WEEKDAYS[s.busiestWeekday()] + "s  ·  Busiest month: "
                        + WatchStats.MONTHS[s.busiestMonth()]));
        if (s.longestStreak > 1) {
            wrapped.add(slide(0xFFFF8008, 0xFFFFC837, "SYour longest streak", "B" + s.longestStreak, "Mdays in a row"));
        }
        wrapped.add(slide(0xFF3A1C71, 0xFFD76D77, "MThat's a wrap on " + y, "SPress Back to leave"));
        showSlide(0);
    }

    private View slideView;

    private void showSlide(int i) {
        if (slideView != null) root.removeView(slideView);
        if (i < 0 || i >= wrapped.size()) {
            leaveWrapped();
            return;
        }
        slideAt = i;
        Object[] sl = wrapped.get(i);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding((int) (W * 0.08f), 0, (int) (W * 0.08f), 0);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{(Integer) sl[0], (Integer) sl[1]});
        col.setBackground(g);
        for (String line : (String[]) sl[2]) {
            char kind = line.charAt(0);
            TextView t = text(line.substring(1), kind == 'B' ? H * 0.11f : kind == 'M' ? H * 0.05f : H * 0.032f,
                    kind == 'S' ? 0xCCFFFFFF : Color.WHITE, kind != 'S');
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, (int) (H * 0.012f), 0, (int) (H * 0.012f));
            col.addView(t);
        }
        slideView = col;
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void leaveWrapped() {
        if (slideView != null) root.removeView(slideView);
        slideView = null;
        slideAt = -1;
        render();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (slideAt >= 0) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                int k = e.getKeyCode();
                if (k == KeyEvent.KEYCODE_BACK) {
                    leaveWrapped();
                } else if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (slideAt > 0) showSlide(slideAt - 1);
                } else if (k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_CENTER
                        || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_MEDIA_NEXT) {
                    if (slideAt < wrapped.size() - 1) showSlide(slideAt + 1);
                    else leaveWrapped();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
