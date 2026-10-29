package com.enthu.reel;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live channels: add your own (from a collection, one show, a mix of shows or genres), reorder them, hide the
 * ones you don't want and remove the ones you made. One list screen that changes content; Back goes up a level.
 */
public class LiveEditActivity extends ReelActivity {

    private static class Entry {
        final String label;
        final String value;
        final String help;
        Runnable click;
        TextView valueView;
        TextView labelView;

        Entry(String label, String value, String help, Runnable click) {
            this.label = label;
            this.value = value;
            this.help = help;
            this.click = click;
        }
    }

    private int H;
    private LinearLayout list;
    private ScrollView scroll;
    private TextView title;
    private TextView note;
    private final List<Runnable> stack = new ArrayList<>();
    private Live.Custom editing; // set while choosing new contents for an existing channel

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Plex.init(this);
        SoundFx.init(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int W = dm.widthPixels;
        H = dm.heightPixels;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(UiUtil.BG);
        col.setPadding((int) (W * 0.1f), (int) (H * 0.06f), (int) (W * 0.1f), (int) (H * 0.04f));
        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.055f);
        title.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        col.addView(title);
        note = new TextView(this);
        note.setTextColor(UiUtil.TEXT2);
        note.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.024f);
        note.setPadding(0, (int) (H * 0.008f), 0, (int) (H * 0.02f));
        col.addView(note);
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        col.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(col);
        main();
    }

    @Override
    public void onBackPressed() {
        if (stack.isEmpty()) {
            super.onBackPressed();
            return;
        }
        stack.remove(stack.size() - 1).run();
    }

    /** Shows [entries] with a heading; [back] is where Back goes (null = leave). */
    private void screen(String heading, String hint, List<Entry> entries, int focus) {
        title.setText(heading);
        note.setText(hint);
        note.setVisibility(hint.isEmpty() ? View.GONE : View.VISIBLE);
        list.removeAllViews();
        View first = null;
        for (int i = 0; i < entries.size(); i++) {
            View r = row(entries.get(i));
            if (i == Math.max(0, Math.min(focus, entries.size() - 1))) first = r;
        }
        if (first != null) first.requestFocus();
        scroll.scrollTo(0, 0);
    }

    private void busy(String what) {
        title.setText(what);
        note.setVisibility(View.GONE);
        list.removeAllViews();
    }

    /** Opens [next]; Back returns to [back]. */
    private void go(Runnable back, Runnable next) {
        stack.add(back);
        next.run();
    }

    // ------------------------------------------------------------------ main list

    private void main() {
        stack.clear();
        busy("Loading channels…");
        Plex.async(Live::load, new Plex.Callback<List<Live.Channel>>() {
            @Override
            public void ok(List<Live.Channel> r) {
                if (!isDestroyed()) mainList(0);
            }

            @Override
            public void fail(Exception e) {
                if (!isDestroyed()) {
                    title.setText("Couldn't load the channels");
                    note.setText(e.getMessage());
                    note.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void mainList(final int focus) {
        stack.clear();
        editing = null;
        List<Entry> es = new ArrayList<>();
        es.add(new Entry("+  Add a channel", "", "From a collection, a show, a mix of shows or genres. "
                + "To make one from a search, search for it and press Menu on the results.",
                () -> go(() -> mainList(0), this::addMenu)));
        final List<Live.Channel> all = Live.allInOrder();
        for (int i = 0; i < all.size(); i++) {
            final Live.Channel c = all.get(i);
            final int at = i + 1;
            boolean hid = Live.isHidden(c.id);
            es.add(new Entry(c.name, hid ? "Hidden" : "CH " + shownNumber(c),
                    describe(c.id), () -> channelMenu(c, at)));
        }
        screen("Live channels", "OK on a channel to hide it, move it or remove it. Back when you're done.", es, focus);
    }

    private static String shownNumber(Live.Channel c) {
        List<Live.Channel> shown = Live.cached();
        if (shown != null) for (Live.Channel s : shown) if (s.id.equals(c.id)) return String.valueOf(s.num);
        return "";
    }

    private static String describe(String id) {
        Live.Custom c = Live.definition(id);
        return c == null ? "" : (c.builtin ? (Live.changed(id) ? "Changed: " : "Standard: ") : "") + c.describe();
    }

    private void channelMenu(final Live.Channel c, final int at) {
        List<Entry> es = new ArrayList<>();
        final boolean hid = Live.isHidden(c.id);
        es.add(new Entry(hid ? "Show in the guide" : "Hide from the guide", "", "", () -> {
            Live.setHidden(c.id, !hid);
            mainList(at);
        }));
        if (at > 1) es.add(new Entry("Move up", "", "", () -> {
            Live.moveChannel(c.id, -1);
            mainList(at - 1);
        }));
        if (at < Live.allInOrder().size()) es.add(new Entry("Move down", "", "", () -> {
            Live.moveChannel(c.id, 1);
            mainList(at + 1);
        }));
        final Live.Custom def = Live.definition(c.id);
        if (def != null) {
            es.add(new Entry("Rename", "", "Now called “" + c.name + "”.", () -> rename(def, at)));
            es.add(new Entry("Change what it plays", "", def.describe(), () -> {
                editing = def;
                go(() -> channelMenu(c, at), this::addMenu);
            }));
            if (def.kind.equals("genres")) {
                es.add(new Entry("Libraries it uses", def.libs.isEmpty() ? "All" : def.libs.size() + " picked",
                        "Only look in the libraries you choose.", () -> go(() -> channelMenu(c, at),
                        () -> pickLibraries(def, c, at))));
            }
            if (def.builtin && Live.changed(c.id)) {
                es.add(new Entry("Put back as it came", "", "Forget your changes to this standard channel.", () -> {
                    Live.resetDefinition(c.id);
                    toast("Reset " + c.name);
                    main();
                }));
            }
        }
        if (c.custom) es.add(new Entry("Remove this channel", "", "It was made by you; this deletes it.", () -> {
            Live.removeCustom(c.id);
            toast("Removed " + c.name);
            main();
        }));
        es.add(new Entry("Back", "", "", () -> mainList(at)));
        stack.clear();
        stack.add(() -> mainList(at));
        screen("CH " + (hid ? "–" : shownNumber(c)) + "  " + c.name, c.custom ? describe(c.id) : "", es, 0);
    }

    private void rename(final Live.Custom def, final int at) {
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setText(def.name);
        in.setSingleLine();
        in.setSelectAllOnFocus(true);
        in.setTextColor(Color.WHITE);
        in.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        final android.app.AlertDialog d = new android.app.AlertDialog.Builder(this,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Name this channel").setView(in)
                .setPositiveButton("Save", (x, y) -> {
                    String n = in.getText().toString().trim();
                    if (n.isEmpty()) return;
                    def.name = n;
                    Live.saveDefinition(def);
                    main();
                })
                .setNegativeButton("Cancel", null).create();
        in.setOnEditorActionListener((v, action, ev) -> {
            d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
        d.show();
        in.requestFocus();
    }

    /** Which libraries a genre channel may take its titles from (none picked = all of them). */
    private void pickLibraries(final Live.Custom def, final Live.Channel c, final int at) {
        busy("Finding libraries…");
        Plex.async(() -> {
            List<String[]> out = new ArrayList<>();
            for (Plex.Section s : Plex.allSections()) {
                if (Plex.isLibraryEnabled(s.key) && s.type.equals(def.type)) out.add(new String[]{s.key, s.title});
            }
            return out;
        }, new Plex.Callback<List<String[]>>() {
            @Override
            public void ok(final List<String[]> r) {
                if (isDestroyed()) return;
                List<Integer> pre = new ArrayList<>();
                for (int i = 0; i < r.size(); i++) if (def.libs.contains(r.get(i)[0])) pre.add(i);
                multi("Libraries for " + def.name, r, pre, (picked) -> {
                    def.libs.clear();
                    if (picked.size() < r.size()) for (int i : picked) def.libs.add(r.get(i)[0]);
                    Live.saveDefinition(def);
                    main();
                });
            }

            @Override
            public void fail(Exception e) {
                if (!isDestroyed()) screen("Couldn't load libraries", e.getMessage(), new ArrayList<Entry>(), 0);
            }
        });
    }

    // ------------------------------------------------------------------ adding

    private void addMenu() {
        List<Entry> es = new ArrayList<>();
        es.add(new Entry("A collection", "", "A marathon in release order (a collection of shows mixes their episodes).",
                () -> go(this::addMenu, this::pickCollection)));
        es.add(new Entry("One show", "", "Every episode in order, round and round.",
                () -> go(this::addMenu, () -> pickShows(false))));
        es.add(new Entry("A mix of shows", "", "Pick a few shows; their episodes are mixed like an evening of TV.",
                () -> go(this::addMenu, () -> pickShows(true))));
        es.add(new Entry("Films by genre", "", "Choose one or more genres.",
                () -> go(this::addMenu, () -> pickGenres("movie"))));
        es.add(new Entry("TV by genre", "", "Choose one or more genres.",
                () -> go(this::addMenu, () -> pickGenres("show"))));
        screen(editing != null ? "What “" + editing.name + "” plays" : "Add a channel",
                editing != null ? "Pick what it should be made from now." : "", es, 0);
    }

    private void done(Live.Custom c, String name) {
        if (editing != null) {
            // new contents for an existing channel; its name, number and libraries stay as they are
            Live.Custom e = editing;
            editing = null;
            e.kind = c.kind;
            e.ref = c.ref;
            e.refTitle = c.refTitle;
            e.type = c.type;
            e.genres.clear();
            e.genres.addAll(c.genres);
            e.refs.clear();
            e.refs.addAll(c.refs);
            e.refTitles.clear();
            e.refTitles.addAll(c.refTitles);
            if (!e.kind.equals("genres")) e.libs.clear();
            Live.saveDefinition(e);
            toast("Updated “" + e.name + "”");
            main();
            return;
        }
        c.id = "c" + System.currentTimeMillis();
        c.name = name;
        Live.addCustom(c);
        toast("Added “" + name + "”");
        main();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void pickCollection() {
        busy("Finding collections…");
        Plex.async(() -> {
            List<String[]> out = new ArrayList<>();
            for (Plex.Section s : Plex.allSections()) {
                if (!Plex.isLibraryEnabled(s.key) || !(s.type.equals("movie") || s.type.equals("show"))) continue;
                out.addAll(Plex.collections(s.key));
            }
            return out;
        }, new Plex.Callback<List<String[]>>() {
            @Override
            public void ok(List<String[]> r) {
                if (isDestroyed()) return;
                List<Entry> es = new ArrayList<>();
                for (final String[] c : r) {
                    es.add(new Entry(c[0], c[2] + " titles", "", () -> {
                        Live.Custom cu = new Live.Custom();
                        cu.kind = "collection";
                        cu.ref = c[1];
                        cu.refTitle = c[0];
                        done(cu, c[0]);
                    }));
                }
                screen("Pick a collection", r.isEmpty() ? "No collections found." : "", es, 0);
            }

            @Override
            public void fail(Exception e) {
                if (!isDestroyed()) screen("Couldn't load collections", e.getMessage(), new ArrayList<Entry>(), 0);
            }
        });
    }

    /** Shows of every enabled show library, A to Z: {ratingKey, title}. */
    private static List<String[]> allShows() throws Exception {
        List<String[]> out = new ArrayList<>();
        for (Plex.Section s : Plex.allSections()) {
            if (!Plex.isLibraryEnabled(s.key) || !s.type.equals("show")) continue;
            JSONArray a = Plex.listAll(s.key);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.optString("ratingKey"), o.optString("title")});
            }
        }
        Collections.sort(out, (x, y) -> x[1].compareToIgnoreCase(y[1]));
        return out;
    }

    private void pickShows(final boolean many) {
        busy("Finding shows…");
        Plex.async(LiveEditActivity::allShows, new Plex.Callback<List<String[]>>() {
            @Override
            public void ok(List<String[]> r) {
                if (isDestroyed()) return;
                if (!many) {
                    List<Entry> es = new ArrayList<>();
                    for (final String[] s : r) {
                        es.add(new Entry(s[1], "", "", () -> {
                            Live.Custom cu = new Live.Custom();
                            cu.kind = "show";
                            cu.ref = s[0];
                            cu.refTitle = s[1];
                            done(cu, s[1]);
                        }));
                    }
                    screen("Pick a show", "", es, 0);
                } else {
                    List<Integer> pre = new ArrayList<>();
                    if (editing != null) for (int i = 0; i < r.size(); i++) if (editing.refs.contains(r.get(i)[0])) pre.add(i);
                    multi("Pick the shows to mix", r, pre, (picked) -> {
                        Live.Custom cu = new Live.Custom();
                        cu.kind = "shows";
                        List<String> names = new ArrayList<>();
                        for (int i : picked) {
                            cu.refs.add(r.get(i)[0]);
                            cu.refTitles.add(r.get(i)[1]);
                            names.add(r.get(i)[1]);
                        }
                        String name = names.size() <= 2 ? android.text.TextUtils.join(" & ", names)
                                : names.get(0) + ", " + names.get(1) + " +" + (names.size() - 2);
                        done(cu, name);
                    });
                }
            }

            @Override
            public void fail(Exception e) {
                if (!isDestroyed()) screen("Couldn't load shows", e.getMessage(), new ArrayList<Entry>(), 0);
            }
        });
    }

    private void pickGenres(final String type) {
        busy("Finding genres…");
        Plex.async(() -> {
            Map<String, String[]> seen = new LinkedHashMap<>();
            for (Plex.Section s : Plex.allSections()) {
                if (!Plex.isLibraryEnabled(s.key) || !s.type.equals(type)) continue;
                for (String[] g : Plex.genres(s.key)) seen.put(g[0].toLowerCase(), new String[]{g[0], g[0]});
            }
            List<String[]> out = new ArrayList<>(seen.values());
            Collections.sort(out, (x, y) -> x[1].compareToIgnoreCase(y[1]));
            return out;
        }, new Plex.Callback<List<String[]>>() {
            @Override
            public void ok(final List<String[]> r) {
                if (isDestroyed()) return;
                List<Integer> pre = new ArrayList<>();
                if (editing != null && editing.type.equals(type)) {
                    for (int i = 0; i < r.size(); i++) {
                        for (String g : editing.genres) if (g.equalsIgnoreCase(r.get(i)[1])) pre.add(i);
                    }
                }
                multi("Pick genres", r, pre, (picked) -> {
                    Live.Custom cu = new Live.Custom();
                    cu.kind = "genres";
                    cu.type = type;
                    List<String> names = new ArrayList<>();
                    for (int i : picked) {
                        cu.genres.add(r.get(i)[1]);
                        names.add(r.get(i)[1]);
                    }
                    String name = android.text.TextUtils.join(" & ", names) + (type.equals("movie") ? " Movies" : " TV");
                    done(cu, name);
                });
            }

            @Override
            public void fail(Exception e) {
                if (!isDestroyed()) screen("Couldn't load genres", e.getMessage(), new ArrayList<Entry>(), 0);
            }
        });
    }

    private interface Picked {
        void run(List<Integer> picked);
    }

    /** A list where OK ticks and unticks, with "Done" at the top. Ticking only changes that row (no redraw). */
    private void multi(final String heading, final List<String[]> items, final List<Integer> picked, final Picked then) {
        List<Entry> es = new ArrayList<>();
        final Entry done = new Entry(doneLabel(picked), "", "", null);
        done.click = () -> {
            if (picked.isEmpty()) {
                toast("Pick at least one first");
                return;
            }
            then.run(picked);
        };
        es.add(done);
        for (int i = 0; i < items.size(); i++) {
            final int at = i;
            final Entry e = new Entry(items.get(i)[1], picked.contains(i) ? "☑" : "☐", "", null);
            e.click = () -> {
                if (picked.contains(at)) picked.remove((Integer) at);
                else picked.add(at);
                e.valueView.setText(picked.contains(at) ? "☑" : "☐");
                done.labelView.setText(doneLabel(picked));
            };
            es.add(e);
        }
        screen(heading, "OK ticks or unticks. Choose Done at the top when you have them all.", es, 0);
    }

    private static String doneLabel(List<Integer> picked) {
        return picked.isEmpty() ? "Done (pick at least one)" : "Done  ·  " + picked.size() + " picked";
    }

    // ------------------------------------------------------------------ one row

    private View row(Entry e) {
        final LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (H * 0.018f);
        r.setPadding(pad * 2, pad, pad * 2, pad);
        final float rad = H * 0.016f;
        r.setBackground(UiUtil.rounded(UiUtil.CARD, rad));
        r.setFocusable(true);
        r.setClickable(true);
        r.setOnClickListener(v -> {
            SoundFx.click();
            e.click.run();
        });
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        final TextView n = new TextView(this);
        n.setText(e.label);
        n.setSingleLine();
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        n.setTextColor(Color.WHITE);
        n.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
        n.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        top.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView v = new TextView(this);
        v.setText(e.value);
        v.setTextColor(UiUtil.TEXT2);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.028f);
        v.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        e.labelView = n;
        e.valueView = v;
        top.addView(v);
        r.addView(top);
        final TextView h = new TextView(this);
        h.setText(e.help);
        h.setTextColor(UiUtil.TEXT3);
        h.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.021f);
        h.setPadding(0, (int) (H * 0.004f), 0, 0);
        h.setVisibility(e.help.isEmpty() ? View.GONE : View.VISIBLE);
        r.addView(h);
        r.setOnFocusChangeListener((view, focused) -> {
            r.setBackground(UiUtil.rounded(focused ? 0xFFFFFFFF : UiUtil.CARD, rad));
            n.setTextColor(focused ? 0xFF000000 : Color.WHITE);
            h.setTextColor(focused ? 0xFF555555 : UiUtil.TEXT3);
            v.setTextColor(focused ? 0xFF333333 : UiUtil.TEXT2);
            if (focused) SoundFx.move();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (H * 0.011f);
        list.addView(r, lp);
        return r;
    }
}
