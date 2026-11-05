package com.enthu.reel;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings, laid out like the Apple TV: categories down the left, a frosted panel on the right with
 * one line per setting (value on the right) and the focused setting's explanation underneath.
 * Values are read by the player each time a video starts.
 */
public class SettingsActivity extends ReelActivity {

    private static final String[] CATEGORIES = {
            "Playback", "Audio", "Subtitles", "Picture", "Home and browsing", "Account"};
    private static final int PILL = 0xFFFFFFFF;
    private static final int PANEL = 0xD9262629;

    private interface Flip { void set(boolean on); }

    private interface Pick { void pick(int index); }

    private int W;
    private int H;
    private SharedPreferences prefs;
    private LinearLayout list;
    private LinearLayout cats;
    private TextView panelTitle;
    private TextView caption;
    private int category = 0;
    private final List<TextView> catViews = new ArrayList<>();
    private List<Plex.Section> sectionsCache;
    private boolean sectionsLoading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;
        prefs = getSharedPreferences("reel_ui", MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(UiUtil.BG);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding((int) (W * 0.06f), (int) (H * 0.09f), (int) (W * 0.06f), (int) (H * 0.07f));
        body.setClipChildren(false);
        body.setClipToPadding(false);

        // Left: title and categories
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.058f);
        title.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        title.setPadding((int) (W * 0.012f), 0, 0, (int) (H * 0.04f));
        left.addView(title);

        cats = new LinearLayout(this);
        cats.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int index = i;
            final TextView c = new TextView(this);
            c.setText(CATEGORIES[i]);
            c.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.032f);
            c.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
            c.setPadding((int) (W * 0.012f), (int) (H * 0.018f), (int) (W * 0.012f), (int) (H * 0.018f));
            c.setSingleLine(true);
            c.setFocusable(true);
            c.setClickable(true);
            c.setOnClickListener(v -> {
                if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
            });
            // Right goes to the first line of the panel, not whichever line happens to sit level with this one
            c.setOnKeyListener((v, keyCode, ev) -> {
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && list.getChildCount() > 0) {
                    if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN) list.getChildAt(0).requestFocus();
                    return true;
                }
                return false;
            });
            c.setOnFocusChangeListener((v, f) -> {
                if (f) {
                    SoundFx.move();
                    if (category != index) {
                        category = index;
                        show(false);
                    }
                }
                paintCategories();
            });
            catViews.add(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (H * 0.006f);
            cats.addView(c, lp);
        }
        left.addView(cats);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                (int) (W * 0.25f), ViewGroup.LayoutParams.MATCH_PARENT);
        leftLp.rightMargin = (int) (W * 0.025f);
        body.addView(left, leftLp);

        // Right: the frosted panel
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = UiUtil.rounded(PANEL, H * 0.04f);
        bg.setStroke(Math.max(1, (int) (H * 0.002f)), 0x26FFFFFF);
        panel.setBackground(bg);
        int px = (int) (W * 0.018f);
        panel.setPadding(px, (int) (H * 0.035f), px, (int) (H * 0.03f));

        panelTitle = new TextView(this);
        panelTitle.setTextColor(UiUtil.TEXT2);
        panelTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.026f);
        panelTitle.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        panelTitle.setPadding((int) (W * 0.012f), 0, 0, (int) (H * 0.015f));
        panel.addView(panelTitle);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View rule = new View(this);
        rule.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) (H * 0.002f)));
        rp.topMargin = (int) (H * 0.012f);
        rp.bottomMargin = (int) (H * 0.012f);
        panel.addView(rule, rp);

        caption = new TextView(this);
        caption.setTextColor(UiUtil.TEXT2);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.0235f);
        caption.setLineSpacing(0, 1.1f);
        caption.setMinLines(3);
        caption.setMaxLines(4);
        caption.setPadding((int) (W * 0.012f), 0, (int) (W * 0.012f), 0);
        panel.addView(caption);

        body.addView(panel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        paintCategories();
        show(false);
        catViews.get(0).requestFocus();
    }

    private void paintCategories() {
        float rad = H * 0.02f;
        for (int i = 0; i < catViews.size(); i++) {
            TextView c = catViews.get(i);
            boolean f = c.hasFocus();
            boolean cur = i == category;
            c.setTextColor(f ? 0xFF000000 : cur ? Color.WHITE : UiUtil.TEXT2);
            c.setBackground(f ? UiUtil.rounded(PILL, rad) : cur ? UiUtil.rounded(0x26FFFFFF, rad) : null);
        }
    }

    /** Redraws the right-hand panel; keeps the focused row where it was when something was just changed. */
    private void show(boolean keepFocus) {
        int keep = -1;
        if (keepFocus) {
            for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).hasFocus()) keep = i;
        }
        list.removeAllViews();
        caption.setText("");
        panelTitle.setText(CATEGORIES[category]);
        switch (category) {
            case 0: playback(); break;
            case 1: audio(); break;
            case 2: subtitles(); break;
            case 3: picture(); break;
            case 4: home(); break;
            default: account(); break;
        }
        if (keep >= 0 && keep < list.getChildCount()) list.getChildAt(keep).requestFocus();
    }

    private void refresh() {
        show(true);
    }

    // ---- Categories ----

    private void playback() {
        toggle("Direct file mode", "directFile", true,
                "Plays the original file straight from your server, like Kodi, so there is no Plex "
                        + "transcode session to stall on weak Wi-Fi. If the main sound track won't play, Reel picks "
                        + "a Dolby Digital or DTS track from the same file. Converting only happens if the file has none.",
                null);
        toggle("Convert AAC to Dolby Digital", "aacToDolby", false,
                "Plex converts AAC sound to Dolby Digital (AC3, up to 5.1). Only used when Direct file mode is off.", null);
        toggle("Tunneled video", "tunneled", false,
                "Sends the picture straight to the Fire TV's video chip, the way some other apps do. Try this if a 4K "
                        + "HDR or Dolby Vision file shows green lines or noise.", null);
        toggle("Auto-play next episode", "autoPlay", true,
                "When the end credits start, an Up Next card counts down 10 seconds and plays the next "
                        + "episode. Reel fetches it a minute early so it starts quickly.", null);
        boolean stillOn = prefs.getBoolean("stillOn", true);
        toggle("Still watching?", "stillOn", true,
                "Autoplay pauses and asks \"Still watching?\" when several episodes in a row have started on "
                        + "their own with no button pressed, and it is also late at night or has been playing a long time. "
                        + "Turn it off to let autoplay run all night.", null);
        if (stillOn) {
            choiceValues("Ask after episodes", "stillEpisodes", 3, new int[]{2, 3, 4, 5, 6},
                    new String[]{"2 in a row", "3 in a row", "4 in a row", "5 in a row", "6 in a row"},
                    "How many episodes can start on their own, with no button pressed, before it may ask.");
            toggle("Late at night", "stillNight", true,
                    "Between midnight and 6am it asks as soon as the episode count above is reached.", null);
            choiceValues("Long viewing time", "stillHours", 3, new int[]{0, 2, 3, 4, 6},
                    new String[]{"Off", "After 2 hours", "After 3 hours", "After 4 hours", "After 6 hours"},
                    "At any hour, it also asks once autoplay has run this long with no button pressed.");
        }
    }

    private void audio() {
        int mode = prefs.getInt("audioMode", 0);
        final String[] helps = {
                "Sends Dolby TrueHD, Atmos, DTS-HD and DTS:X untouched to your receiver.",
                "DTS-HD and DTS:X play as the DTS core, Atmos as Dolby Digital Plus, and TrueHD "
                        + "is converted to Dolby Digital by Plex.",
                "DTS, DTS-HD, DTS:X and TrueHD are always converted to Dolby Digital (AC3, up to 5.1) by "
                        + "Plex. The video is still sent untouched. Use this if your Fire TV refuses DTS."};
        choice("Surround sound", new String[]{"Passthrough", "Compatible", "Convert to Dolby"}, mode,
                helps[Math.max(0, Math.min(2, mode))], i -> prefs.edit().putInt("audioMode", i).apply());

        int out = prefs.getInt("output", 0);
        choice("Audio output", new String[]{"HDMI direct / eARC", "Optical or ARC"}, out,
                out == 0 ? "The Fire TV is connected straight to your receiver, or through eARC. Dolby Digital+, "
                        + "TrueHD and DTS-HD can be sent as they are. If your receiver shows PCM for Dolby Digital+ "
                        + "files, your sound is probably going through the TV's plain ARC, so switch this to \"Optical or ARC\"."
                        : "Sound goes out of the optical port, or over the TV's ordinary ARC (not eARC). Both can only carry "
                        + "stereo, Dolby Digital 5.1 and DTS core, so Dolby Digital+, TrueHD and DTS-HD are swapped for a "
                        + "Dolby Digital or DTS track, or converted to Dolby Digital by Plex, so your receiver gets them "
                        + "as Dolby Digital instead of PCM.",
                i -> prefs.edit().putInt("output", i).apply());

        toggleLabelled("DTS-HD Master Audio output", "dtsHdRaw", true, "Raw stream", "Standard",
                "Raw stream sends DTS-HD Master Audio and DTS:X to the receiver untouched, the way Kodi does, so the "
                        + "receiver can show DTS-HD MA. Standard uses the Fire TV's own path, which often only passes "
                        + "the DTS core. Only used when Surround sound is on Passthrough.");
        toggleLabelled("Dolby Digital / Digital Plus output", "eac3Raw", false, "Raw stream", "Standard",
                "Raw stream sends Dolby Digital and Dolby Digital Plus (not Atmos) to the receiver untouched, the way "
                        + "Kodi does. Use it when the Fire TV's Surround Sound is on Best Available and your receiver "
                        + "shows PCM (or drops out) on those files, which the Fire TV does to any picture it can't "
                        + "tunnel. Standard uses the Fire TV's own path.");
        toggle("Interface sounds", "sounds", true, "Soft clicks as you move around and select things.",
                () -> SoundFx.init(this));
    }

    private void subtitles() {
        final String[] subLangs = {"Off", "English", "Tamil", "Hindi", "Spanish", "French", "German", "Arabic", "Japanese"};
        final String[] subCodes = {"off", "en", "ta", "hi", "es", "fr", "de", "ar", "ja"};
        String cur = prefs.getString("subtitleLanguage", "off");
        int at = 0;
        for (int i = 0; i < subCodes.length; i++) if (subCodes[i].equals(cur)) { at = i; break; }
        choice("Default subtitles", subLangs, at,
                "Sets the default subtitle language globally when a file supports it. If the track is missing, "
                        + "Reel falls back to the file's regular subtitle list.",
                i -> prefs.edit().putString("subtitleLanguage", subCodes[i]).apply());
        choiceInt("Size", "subSize", 1, new String[]{"Small", "Medium", "Large", "Extra large"},
                "Text subtitles only. Picture subtitles (PGS) keep the size they were made at.");
        choiceInt("Colour", "subColour", 0, new String[]{"White", "Yellow", "Cyan", "Green"},
                "The colour of subtitle text. Picture subtitles (PGS) keep their own colours.");
        choiceInt("Position", "subPos", 2, new String[]{"Lowest", "Low", "Default", "High", "Highest"},
                "How far up the screen the subtitles sit. Picture subtitles (PGS) stay where they were made.");
        choiceInt("Style", "subLook", 0, new String[]{"Outline", "Soft box", "Solid box"},
                "Outline keeps the picture clear; a box behind the words is easiest to read.");
    }

    private void picture() {
        final boolean tvDv = PlayerActivity.tvDolbyVision(this);
        final boolean tvHdr = PlayerActivity.tvHdr10(this);
        final String detected = PlayerActivity.displayPicture(this);
        toggleCustom("My TV shows Dolby Vision", tvDv, "Yes", "No",
                "Pre-filled from what the TV reports (right now: " + detected + "). If it's wrong, press to change it. "
                        + "Yes plays Dolby Vision files as Dolby Vision. No plays them as ordinary HDR10 / SDR.",
                on -> prefs.edit().putBoolean("tvDv", on).apply());
        toggleCustom("My TV shows HDR10", tvHdr, "Yes", "No",
                "Pre-filled from what the TV reports. If both this and Dolby Vision are No, "
                        + "the TV is treated as a normal SDR TV.",
                on -> prefs.edit().putBoolean("tvHdr", on).apply());
    }

    private void home() {
        choice("Theme", new String[]{SeasonalView.name(0), SeasonalView.name(1), SeasonalView.name(2)},
                SeasonalView.theme(),
                "A seasonal look for special occasions: falling snow for Christmas, rising embers for Halloween. "
                        + "It shows on the Home page.",
                i -> SeasonalView.setTheme(i));
        toggle("Live previews", "previews", true,
                "Rest on a title on the home screen for 3 seconds and its trailer (or a clip of the film) "
                        + "plays muted behind it. Uses about 3 Mbps while it plays.", null);
        choiceInt("Preview sound", "previewSound", 1, new String[]{"Off", "Low", "Medium"},
                "How loud live previews play. They fade in gently, a little louder on a title's page.");
        toggle("Trailer on title pages", "detailTrailer", true,
                "When you open a film or show, its trailer starts quietly behind the page after a moment "
                        + "(if your server has one).", null);
        final boolean liveTrailers = TrailerBreakActivity.enabled(this);
        toggleCustom("Trailers on Live TV", liveTrailers, "On", "Off",
                "Between one programme and the next on a Live channel, show up to three trailers of what is coming up. "
                        + "Each one can be skipped, or skip them all.",
                on -> prefs.edit().putBoolean("liveTrailers", on).apply());
        toggle("Fire TV home row", "homeRow", true,
                "Shows your Up Next titles in the \"Recommended by your apps\" row on the Fire TV home "
                        + "screen. Picking one starts playing straight away.",
                () -> {
                    if (prefs.getBoolean("homeRow", true)) TvRecommendations.refresh(this, null);
                    else TvRecommendations.clear(this);
                });
        choiceValues("Screensaver", "screensaverMin", 3, new int[]{0, 3, 5, 10, 15},
                new String[]{"Off", "After 3 min", "After 5 min", "After 10 min", "After 15 min"},
                "Artwork from your libraries drifting across the screen when the remote's been idle.");

        // The library list comes from the server, so fetch it off the main thread, then redraw
        List<Plex.Section> libraryChoices = new ArrayList<>();
        if (sectionsCache != null) {
            libraryChoices.addAll(sectionsCache);
        } else if (!sectionsLoading) {
            sectionsLoading = true;
            Plex.async(Plex::sections, new Plex.Callback<List<Plex.Section>>() {
                @Override
                public void ok(List<Plex.Section> r) {
                    sectionsLoading = false;
                    sectionsCache = r;
                    if (!isFinishing() && category == 4) refresh();
                }

                @Override
                public void fail(Exception e) {
                    sectionsLoading = false;
                }
            });
        }
        if (!libraryChoices.isEmpty()) {
            // Home (and TV Shows) start from the old shared choice if there was one
            final java.util.Set<String> homeDefaults = Plex.libraryChoice("visibleLibraries");
            int homeShown = Plex.visibleSections(libraryChoices).size();
            nav("Home libraries", homeShown + " of " + libraryChoices.size(),
                    "Choose which Plex libraries appear on the Home and TV Shows tabs. Hidden libraries stay available elsewhere.",
                    v -> chooseLibraries(libraryChoices, "homeLibraries", "Libraries on Home", homeDefaults));

            // Movies is its own list: tick any library to treat it as movies
            final java.util.Set<String> movieDefaults = new java.util.HashSet<>();
            for (Plex.Section s : libraryChoices) if (s.type.equals("movie")) movieDefaults.add(s.key);
            int movieShown = Plex.movieSections(libraryChoices).size();
            nav("Movies libraries", movieShown + " of " + libraryChoices.size(),
                    "Choose which libraries count as movies. They fill the Movies tab and its Genres row. "
                            + "Until you choose, every Plex movie library is used.",
                    v -> chooseLibraries(libraryChoices, "movieLibraries", "Libraries on Movies", movieDefaults));

            java.util.Set<String> saverChoice = Plex.libraryChoice("screensaverLibraries");
            String saverValue;
            if (saverChoice == null) {
                saverValue = "Movies";
            } else {
                int n = 0;
                for (Plex.Section s : libraryChoices) if (saverChoice.contains(s.key)) n++;
                saverValue = n + " of " + libraryChoices.size();
            }
            final java.util.Set<String> movieKeys = new java.util.HashSet<>();
            for (Plex.Section s : libraryChoices) if (s.type.equals("movie")) movieKeys.add(s.key);
            nav("Screensaver libraries", saverValue,
                    "Which libraries the screensaver takes its artwork from. Until you choose, it uses only your movie libraries.",
                    v -> chooseLibraries(libraryChoices, "screensaverLibraries", "Libraries for the screensaver", movieKeys));
        }
    }

    private void account() {
        PhoneRemote.ensure(this);
        String remote = PhoneRemote.url();
        nav("Phone remote", remote == null ? "No network"
                        : remote.replace("http://", "").replaceFirst("\\?t=.*", ""),
                "Open this on a phone on the same Wi-Fi to search your library and send a title to the TV, and to "
                        + "control what is playing. Press OK to show the QR code, scan it and bookmark the page: "
                        + "the link stays the same.",
                v -> showRemoteCode());
        nav("Stats and Wrapped", "",
                "Hours watched, your top genres and actors, when you watch, and a year-end Wrapped slideshow.",
                v -> startActivity(new Intent(this, StatsActivity.class)));
        Seerr.init(this);
        String seerr = Seerr.base();
        nav("Seerr", seerr.isEmpty() ? "Not set up" : seerr.replaceFirst("^https?://", ""),
                "Your Seerr (Overseerr / Jellyseerr) address, e.g. http://192.168.1.50:5055 or "
                        + "https://requests.yourdomain.com. Reel signs in with your Plex account. It has to be "
                        + "reachable from where the Fire TV is.",
                v -> askSeerr());
        toggle("Automatic sign in", "autoSignIn", false,
                "On: Reel opens straight into the profile you used last. Off: it asks \"Who's watching?\" each time it "
                        + "starts (only when your Plex Home has more than one profile).", null);
        nav("Profile", Plex.profileName == null ? "Main account" : Plex.profileName,
                "Switch to another profile in your Plex Home.",
                v -> startActivity(new Intent(this, ProfilesActivity.class)));
        nav("Sign out", "", "Signs Reel out of your Plex account.",
                v -> {
                    Plex.signOut();
                    Intent i = new Intent(this, LoginActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                });
    }

    // ---- Row kinds ----

    private void toggle(String name, final String key, final boolean def, String help, final Runnable after) {
        boolean on = prefs.getBoolean(key, def);
        line(name, on ? "On" : "Off", false, help, v -> {
            prefs.edit().putBoolean(key, !prefs.getBoolean(key, def)).apply();
            if (after != null) after.run();
            refresh();
        });
    }

    private void toggleLabelled(String name, final String key, final boolean def, String onLabel, String offLabel,
                                String help) {
        boolean on = prefs.getBoolean(key, def);
        line(name, on ? onLabel : offLabel, false, help, v -> {
            prefs.edit().putBoolean(key, !prefs.getBoolean(key, def)).apply();
            refresh();
        });
    }

    private void toggleCustom(String name, final boolean cur, String onLabel, String offLabel, String help,
                              final Flip set) {
        line(name, cur ? onLabel : offLabel, false, help, v -> {
            set.set(!cur);
            refresh();
        });
    }

    /** A setting with a few choices, stored as an index. Opens a picker. */
    private void choice(final String name, final String[] labels, final int cur, String help, final Pick pick) {
        final int at = Math.max(0, Math.min(labels.length - 1, cur));
        line(name, labels[at], true, help, v -> picker(name, labels, at, i -> {
            pick.pick(i);
            refresh();
        }));
    }

    private void choiceInt(String name, final String key, int def, String[] labels, String help) {
        choice(name, labels, prefs.getInt(key, def), help, i -> prefs.edit().putInt(key, i).apply());
    }

    /** Like choiceInt, but the stored numbers aren't 0, 1, 2... */
    private void choiceValues(String name, final String key, int def, final int[] vals, String[] labels, String help) {
        int cur = prefs.getInt(key, def);
        int at = 0;
        for (int i = 0; i < vals.length; i++) if (vals[i] == cur) at = i;
        choice(name, labels, at, help, i -> prefs.edit().putInt(key, vals[i]).apply());
    }

    private void nav(String name, String value, String help, View.OnClickListener click) {
        line(name, value, true, help, click);
    }

    /** One line of the panel: the name, the value on the right and an arrow when it opens something. */
    private View line(String name, String value, boolean arrow, final String help, View.OnClickListener click) {
        final LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int ph = (int) (W * 0.012f);
        int pv = (int) (H * 0.0215f);
        r.setPadding(ph, pv, ph, pv);
        final float rad = H * 0.024f;
        r.setFocusable(true);
        r.setClickable(true);
        r.setOnClickListener(click);

        final TextView n = new TextView(this);
        n.setText(name);
        n.setTextColor(Color.WHITE);
        n.setSingleLine(true);
        n.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
        n.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        r.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(UiUtil.TEXT2);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        v.setMaxWidth((int) (W * 0.3f));
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.028f);
        v.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.NORMAL));
        r.addView(v);

        final TextView a = new TextView(this);
        a.setText(arrow ? "›" : "");
        a.setTextColor(UiUtil.TEXT3);
        a.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.04f);
        a.setPadding((int) (W * 0.008f), 0, 0, 0);
        r.addView(a);

        r.setOnFocusChangeListener((view, focused) -> {
            r.setBackground(focused ? UiUtil.rounded(PILL, rad) : null);
            n.setTextColor(focused ? 0xFF000000 : Color.WHITE);
            v.setTextColor(focused ? 0xFF4A4A4A : UiUtil.TEXT2);
            a.setTextColor(focused ? 0xFF4A4A4A : UiUtil.TEXT3);
            if (focused) {
                caption.setText(help);
                SoundFx.move();
            }
        });
        list.addView(r, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    /** The Apple-style pick list: a small label, a tick on the current choice, a white pill on the focused one. */
    private void picker(String title, String[] labels, int cur, final Pick pick) {
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int px = (int) (W * 0.014f);
        panel.setPadding(px, (int) (H * 0.03f), px, (int) (H * 0.025f));
        GradientDrawable bg = UiUtil.rounded(0xF22A2A2D, H * 0.04f);
        bg.setStroke(Math.max(1, (int) (H * 0.002f)), 0x26FFFFFF);
        panel.setBackground(bg);

        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(UiUtil.TEXT2);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.026f);
        heading.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        heading.setPadding((int) (W * 0.012f), 0, 0, (int) (H * 0.014f));
        panel.addView(heading);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        sv.addView(items, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final float rad = H * 0.024f;
        View focusMe = null;
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding((int) (W * 0.012f), (int) (H * 0.02f), (int) (W * 0.012f), (int) (H * 0.02f));
            r.setFocusable(true);
            r.setClickable(true);

            final TextView tick = new TextView(this);
            tick.setText(i == cur ? "✓" : "");
            tick.setTextColor(Color.WHITE);
            tick.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
            tick.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
            r.addView(tick, new LinearLayout.LayoutParams((int) (W * 0.022f), ViewGroup.LayoutParams.WRAP_CONTENT));

            final TextView t = new TextView(this);
            t.setText(labels[i]);
            t.setTextColor(Color.WHITE);
            t.setSingleLine(true);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
            t.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final LinearLayout row = r;
            r.setOnFocusChangeListener((view, f) -> {
                row.setBackground(f ? UiUtil.rounded(PILL, rad) : null);
                t.setTextColor(f ? 0xFF000000 : Color.WHITE);
                tick.setTextColor(f ? 0xFF000000 : Color.WHITE);
                if (f) SoundFx.move();
            });
            r.setOnClickListener(v -> {
                SoundFx.click();
                d.dismiss();
                pick.pick(index);
            });
            items.addView(r, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i == cur) focusMe = r;
        }

        d.setContentView(panel);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x99000000));
            d.getWindow().setLayout((int) (W * 0.36f), (int) Math.min(H * 0.86f,
                    H * (0.13f + 0.075f * labels.length)));
            d.getWindow().setGravity(Gravity.CENTER);
        }
        d.show();
        if (focusMe != null) focusMe.requestFocus();
        else if (items.getChildCount() > 0) items.getChildAt(0).requestFocus();
    }

    /** The phone remote's QR code, big, with the link. The link never changes, so it can be bookmarked. */
    private void showRemoteCode() {
        final String url = PhoneRemote.url();
        if (url == null) {
            android.widget.Toast.makeText(this, "Not connected to a network", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        int px = (int) (W * 0.02f);
        panel.setPadding(px, (int) (H * 0.035f), px, (int) (H * 0.035f));
        GradientDrawable bg = UiUtil.rounded(0xF22A2A2D, H * 0.04f);
        bg.setStroke(Math.max(1, (int) (H * 0.002f)), 0x26FFFFFF);
        panel.setBackground(bg);

        TextView heading = new TextView(this);
        heading.setText("Phone remote");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.034f);
        heading.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        heading.setPadding(0, 0, 0, (int) (H * 0.022f));
        panel.addView(heading);

        int qs = (int) (H * 0.42f);
        android.widget.ImageView code = new android.widget.ImageView(this);
        code.setImageBitmap(PlayerActivity.qr(url, qs));
        panel.addView(code, new LinearLayout.LayoutParams(qs, qs));

        TextView how = new TextView(this);
        how.setText("Scan with your phone's camera, then bookmark the page. The link stays the same, "
                + "so the bookmark keeps working.\n\n" + url);
        how.setTextColor(UiUtil.TEXT2);
        how.setGravity(Gravity.CENTER);
        how.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.024f);
        how.setPadding(0, (int) (H * 0.022f), 0, 0);
        panel.addView(how, new LinearLayout.LayoutParams(
                (int) (W * 0.36f), ViewGroup.LayoutParams.WRAP_CONTENT));

        panel.setFocusable(true);
        panel.setOnClickListener(v -> d.dismiss());
        d.setContentView(panel);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x99000000));
            d.getWindow().setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            d.getWindow().setGravity(Gravity.CENTER);
        }
        d.show();
        panel.requestFocus();
    }

    private void askSeerr() {
        SoundFx.click();
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        final android.widget.EditText box = new android.widget.EditText(this);
        box.setSingleLine(true);
        box.setText(Seerr.base().isEmpty() ? "http://" : Seerr.base());
        box.setSelection(box.getText().length());
        box.setTextColor(Color.WHITE);
        box.setTypeface(UiUtil.font(UiUtil.MEDIUM));
        box.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.035f);
        int p = (int) (H * 0.025f);
        box.setPadding(p, p, p, p);
        box.setBackground(UiUtil.rounded(UiUtil.CARD, H * 0.025f));
        box.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        box.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        box.setOnEditorActionListener((tv, id, ev) -> {
            d.dismiss();
            return true;
        });
        d.setOnDismissListener(di -> {
            String url = box.getText().toString().trim();
            if (url.equals("http://") || url.equals("https://")) url = "";
            if (url.equals(Seerr.base())) return;
            Seerr.setBase(url);
            refresh();
            if (url.isEmpty()) return;
            android.widget.Toast.makeText(this, "Checking Seerr…", android.widget.Toast.LENGTH_SHORT).show();
            Plex.async(Seerr::login, new Plex.Callback<String>() {
                @Override
                public void ok(String who) {
                    android.widget.Toast.makeText(SettingsActivity.this, "Connected to Seerr as " + who,
                            android.widget.Toast.LENGTH_LONG).show();
                }

                @Override
                public void fail(Exception e) {
                    android.widget.Toast.makeText(SettingsActivity.this, "Couldn't sign in to Seerr: "
                            + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                }
            });
        });
        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            d.getWindow().setLayout((int) (W * 0.55f), ViewGroup.LayoutParams.WRAP_CONTENT);
            d.getWindow().setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        d.show();
        box.requestFocus();
    }

    /**
     * Tick the libraries to use. [defaults] is what counts as ticked until a choice has been saved
     * (null means every library). The list stays open while you tick.
     */
    private void chooseLibraries(final List<Plex.Section> sections, final String prefKey, String title,
                                 java.util.Set<String> defaults) {
        final java.util.Set<String> chosen = new java.util.HashSet<>();
        java.util.Set<String> saved = Plex.libraryChoice(prefKey);
        if (saved != null) {
            chosen.addAll(saved);
        } else {
            for (Plex.Section s : sections) if (defaults == null || defaults.contains(s.key)) chosen.add(s.key);
            // Nothing in common with the defaults (e.g. an old choice for libraries that are gone): show all
            if (chosen.isEmpty()) for (Plex.Section s : sections) chosen.add(s.key);
        }
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int px = (int) (W * 0.014f);
        panel.setPadding(px, (int) (H * 0.03f), px, (int) (H * 0.025f));
        GradientDrawable bg = UiUtil.rounded(0xF22A2A2D, H * 0.04f);
        bg.setStroke(Math.max(1, (int) (H * 0.002f)), 0x26FFFFFF);
        panel.setBackground(bg);

        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(UiUtil.TEXT2);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.026f);
        heading.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        heading.setPadding((int) (W * 0.012f), 0, 0, (int) (H * 0.014f));
        panel.addView(heading);

        final float rad = H * 0.024f;
        for (final Plex.Section s : sections) {
            final LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding((int) (W * 0.012f), (int) (H * 0.02f), (int) (W * 0.012f), (int) (H * 0.02f));
            item.setFocusable(true);
            item.setClickable(true);
            final TextView tick = new TextView(this);
            tick.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
            tick.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
            item.addView(tick, new LinearLayout.LayoutParams((int) (W * 0.022f), ViewGroup.LayoutParams.WRAP_CONTENT));
            final TextView name = new TextView(this);
            name.setText(s.title);
            name.setSingleLine(true);
            name.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
            name.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
            item.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            final Runnable paint = () -> {
                boolean on = chosen.contains(s.key);
                boolean f = item.hasFocus();
                tick.setText(on ? "✓" : "");
                tick.setTextColor(f ? 0xFF000000 : Color.WHITE);
                name.setTextColor(f ? 0xFF000000 : on ? Color.WHITE : UiUtil.TEXT2);
                item.setBackground(f ? UiUtil.rounded(PILL, rad) : null);
            };
            paint.run();
            item.setOnFocusChangeListener((v, f) -> {
                paint.run();
                if (f) SoundFx.move();
            });
            item.setOnClickListener(v -> {
                if (chosen.contains(s.key)) {
                    if (chosen.size() > 1) chosen.remove(s.key); // keep at least one
                } else {
                    chosen.add(s.key);
                }
                Plex.saveLibraryChoice(prefKey, chosen);
                paint.run();
            });
            panel.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        d.setContentView(panel);
        d.setOnDismissListener(x -> refresh());
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x99000000));
            d.getWindow().setLayout((int) (W * 0.36f), ViewGroup.LayoutParams.WRAP_CONTENT);
            d.getWindow().setGravity(Gravity.CENTER);
        }
        d.show();
    }
}
