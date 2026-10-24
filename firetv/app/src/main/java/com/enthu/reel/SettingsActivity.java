package com.enthu.reel;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
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

/** Audio options. Values are read by the player each time a video starts. */
public class SettingsActivity extends ReelActivity {

    private int W;
    private int H;
    private SharedPreferences prefs;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;
        prefs = getSharedPreferences("reel_ui", MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(UiUtil.BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int side = (int) (W * 0.12f);
        col.setPadding(side, (int) (H * 0.10f), side, (int) (H * 0.06f));

        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.06f);
        title.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Playback, sound and account");
        sub.setTextColor(UiUtil.TEXT2);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
        sub.setPadding(0, (int) (H * 0.01f), 0, (int) (H * 0.03f));
        col.addView(sub);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        render(true);
    }

    private List<Plex.Section> sectionsCache;
    private boolean sectionsLoading;

    private void render(boolean focusFirst) {
        int keep = 0;
        for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).hasFocus()) keep = i;
        list.removeAllViews();
        int mode = prefs.getInt("audioMode", 0);
        boolean aac = prefs.getBoolean("aacToDolby", false);

        String[] names = {"Passthrough", "Compatible", "Convert to Dolby"};
        String[] helps = {
                "Sends Dolby TrueHD, Atmos, DTS-HD and DTS:X untouched to your receiver.",
                "DTS-HD and DTS:X play as the DTS core, Atmos as Dolby Digital Plus, and TrueHD "
                        + "is converted to Dolby Digital by Plex.",
                "DTS, DTS-HD, DTS:X and TrueHD are always converted to Dolby Digital (AC3, up to 5.1) by "
                        + "Plex. The video is still sent untouched. Use this if your Fire TV refuses DTS."};
        View first = row("Surround sound", names[mode], helps[mode],
                v -> {
                    prefs.edit().putInt("audioMode", (prefs.getInt("audioMode", 0) + 1) % 3).apply();
                    render(false);
                });
        int out = prefs.getInt("output", 0);
        row("Audio output", out == 0 ? "HDMI direct / eARC" : "Optical or ARC",
                out == 0 ? "The Fire TV is connected straight to your receiver, or through eARC. Dolby Digital+, "
                        + "TrueHD and DTS-HD can be sent as they are. If your receiver shows PCM for Dolby Digital+ "
                        + "files, your sound is probably going through the TV's plain ARC, so switch this to \"Optical or ARC\"."
                        : "Sound goes out of the optical port, or over the TV's ordinary ARC (not eARC). Both can only carry "
                        + "stereo, Dolby Digital 5.1 and DTS core, so Dolby Digital+, TrueHD and DTS-HD are swapped for a "
                        + "Dolby Digital or DTS track, or converted to Dolby Digital by Plex, so your receiver gets them "
                        + "as Dolby Digital instead of PCM.",
                v -> {
                    prefs.edit().putInt("output", prefs.getInt("output", 0) == 0 ? 1 : 0).apply();
                    render(false);
                });
        boolean direct = prefs.getBoolean("directFile", true);
        row("Direct file mode", direct ? "On" : "Off",
                "Plays the original file straight from your server, like Kodi, so there is no Plex "
                        + "transcode session to stall on weak Wi-Fi. If the main sound track won't play, Reel picks "
                        + "a Dolby Digital or DTS track from the same file. Converting only happens if the file has none.",
                v -> {
                    prefs.edit().putBoolean("directFile", !prefs.getBoolean("directFile", true)).apply();
                    render(false);
                });
        row("Convert AAC to Dolby Digital", aac ? "On" : "Off",
                "Plex converts AAC sound to Dolby Digital (AC3, up to 5.1). Only used when Direct file mode is off.",
                v -> {
                    prefs.edit().putBoolean("aacToDolby", !prefs.getBoolean("aacToDolby", false)).apply();
                    render(false);
                });
        boolean snd = prefs.getBoolean("sounds", true);
        row("Interface sounds", snd ? "On" : "Off",
                "Soft clicks as you move around and select things.",
                v -> {
                    prefs.edit().putBoolean("sounds", !prefs.getBoolean("sounds", true)).apply();
                    SoundFx.init(this);
                    render(false);
                });
        boolean auto = prefs.getBoolean("autoPlay", true);
        row("Auto-play next episode", auto ? "On" : "Off",
                "When the end credits start, an Up Next card counts down 10 seconds and plays the next "
                        + "episode. Reel fetches it a minute early so it starts quickly.",
                v -> {
                    prefs.edit().putBoolean("autoPlay", !prefs.getBoolean("autoPlay", true)).apply();
                    render(false);
                });

        final int[] saverMins = {0, 3, 5, 10, 15};
        int saver = prefs.getInt("screensaverMin", 3);
        row("Screensaver", saver == 0 ? "Off" : "After " + saver + " min",
                "Artwork from your libraries drifting across the screen when the remote's been idle.",
                v -> {
                    int cur = prefs.getInt("screensaverMin", 3);
                    int at = 0;
                    for (int i = 0; i < saverMins.length; i++) if (saverMins[i] == cur) at = i;
                    prefs.edit().putInt("screensaverMin", saverMins[(at + 1) % saverMins.length]).apply();
                    render(false);
                });

        final String[] sizes = {"Small", "Medium", "Large", "Extra large"};
        int subSize = prefs.getInt("subSize", 1);
        row("Subtitle size", sizes[Math.max(0, Math.min(3, subSize))],
                "Text subtitles only. Picture subtitles (PGS) keep the size they were made at.",
                v -> {
                    prefs.edit().putInt("subSize", (prefs.getInt("subSize", 1) + 1) % 4).apply();
                    render(false);
                });

        final String[] colourNames = {"White", "Yellow", "Cyan", "Green"};
        int subColour = prefs.getInt("subColour", 0);
        row("Subtitle colour", colourNames[Math.max(0, Math.min(3, subColour))],
                "The colour of subtitle text. Picture subtitles (PGS) keep their own colours.",
                v -> {
                    prefs.edit().putInt("subColour", (prefs.getInt("subColour", 0) + 1) % 4).apply();
                    render(false);
                });

        final String[] posNames = {"Lowest", "Low", "Default", "High", "Highest"};
        int subPos = prefs.getInt("subPos", 2);
        row("Subtitle position", posNames[Math.max(0, Math.min(4, subPos))],
                "How far up the screen the subtitles sit. Picture subtitles (PGS) stay where they were made.",
                v -> {
                    prefs.edit().putInt("subPos", (prefs.getInt("subPos", 2) + 1) % 5).apply();
                    render(false);
                });

        final String[] looks = {"Outline", "Soft box", "Solid box"};
        int look = prefs.getInt("subLook", 0);
        row("Subtitle style", looks[Math.max(0, Math.min(2, look))],
                "Outline keeps the picture clear; a box behind the words is easiest to read.",
                v -> {
                    prefs.edit().putInt("subLook", (prefs.getInt("subLook", 0) + 1) % 3).apply();
                    render(false);
                });

        final String[] subLangs = {"Off", "English", "Tamil", "Hindi", "Spanish", "French", "German", "Arabic", "Japanese"};
        final String[] subCodes = {"off", "en", "ta", "hi", "es", "fr", "de", "ar", "ja"};
        final String currentSubLang = prefs.getString("subtitleLanguage", "off");
        final int[] subLangIndex = {0};
        for (int i = 0; i < subCodes.length; i++) if (subCodes[i].equals(currentSubLang)) { subLangIndex[0] = i; break; }
        row("Default subtitles", subLangs[subLangIndex[0]],
                "Sets the default subtitle language globally when a file supports it. If the track is missing, Reel falls back to the file's regular subtitle list.",
                v -> {
                    subLangIndex[0] = (subLangIndex[0] + 1) % subCodes.length;
                    prefs.edit().putString("subtitleLanguage", subCodes[subLangIndex[0]]).apply();
                    render(false);
                });

        Seerr.init(this);
        String seerr = Seerr.base();
        row("Seerr", seerr.isEmpty() ? "Not set up" : seerr.replaceFirst("^https?://", ""),
                "Your Seerr (Overseerr / Jellyseerr) address, e.g. http://192.168.1.50:5055 or "
                        + "https://requests.yourdomain.com. Reel signs in with your Plex account. It has to be "
                        + "reachable from where the Fire TV is.",
                v -> askSeerr());

        boolean homeRow = prefs.getBoolean("homeRow", true);
        row("Fire TV home row", homeRow ? "On" : "Off",
                "Shows your Up Next titles in the \"Recommended by your apps\" row on the Fire TV home "
                        + "screen. Picking one starts playing straight away.",
                v -> {
                    boolean on = !prefs.getBoolean("homeRow", true);
                    prefs.edit().putBoolean("homeRow", on).apply();
                    if (on) TvRecommendations.refresh(this, null);
                    else TvRecommendations.clear(this);
                    render(false);
                });

        boolean dt = prefs.getBoolean("detailTrailer", true);
        row("Trailer on title pages", dt ? "On" : "Off",
                "When you open a film or show, its trailer starts quietly behind the page after a moment "
                        + "(if your server has one).",
                v -> {
                    prefs.edit().putBoolean("detailTrailer", !prefs.getBoolean("detailTrailer", true)).apply();
                    render(false);
                });

        final String[] sounds = {"Off", "Low", "Medium"};
        int ps = prefs.getInt("previewSound", 1);
        row("Preview sound", sounds[Math.max(0, Math.min(2, ps))],
                "How loud live previews play. They fade in gently, a little louder on a title's page.",
                v -> {
                    prefs.edit().putInt("previewSound", (prefs.getInt("previewSound", 1) + 1) % 3).apply();
                    render(false);
                });

        boolean prev = prefs.getBoolean("previews", true);
        row("Live previews", prev ? "On" : "Off",
                "Rest on a title on the home screen for 3 seconds and its trailer (or a clip of the film) "
                        + "plays muted behind it. Uses about 3 Mbps while it plays.",
                v -> {
                    prefs.edit().putBoolean("previews", !prefs.getBoolean("previews", true)).apply();
                    render(false);
                });

        final boolean tvDv = PlayerActivity.tvDolbyVision(this);
        final boolean tvHdr = PlayerActivity.tvHdr10(this);
        final String detected = PlayerActivity.displayPicture(this);
        row("My TV shows Dolby Vision", tvDv ? "Yes" : "No",
                "Pre-filled from what the TV reports (right now: " + detected + "). If it's wrong, press to change it. "
                        + "Yes plays Dolby Vision files as Dolby Vision. No plays them as ordinary HDR10 / SDR.",
                v -> {
                    prefs.edit().putBoolean("tvDv", !tvDv).apply();
                    render(false);
                });
        row("My TV shows HDR10", tvHdr ? "Yes" : "No",
                "Pre-filled from what the TV reports. Press to change it. If both this and Dolby Vision are No, "
                        + "the TV is treated as a normal SDR TV.",
                v -> {
                    prefs.edit().putBoolean("tvHdr", !tvHdr).apply();
                    render(false);
                });

        boolean raw = prefs.getBoolean("dtsHdRaw", true);
        row("DTS-HD Master Audio output", raw ? "Raw stream (like Kodi)" : "Standard",
                "Raw stream sends DTS-HD Master Audio and DTS:X to the receiver untouched, the way Kodi does, so the "
                        + "receiver can show DTS-HD MA. Standard uses the Fire TV's own path, which often only passes "
                        + "the DTS core. Only used when Surround sound is on Passthrough. Press to change.",
                v -> {
                    prefs.edit().putBoolean("dtsHdRaw", !prefs.getBoolean("dtsHdRaw", true)).apply();
                    render(false);
                });

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
                    if (!isFinishing()) render(false);
                }

                @Override
                public void fail(Exception e) {
                    sectionsLoading = false;
                }
            });
        }
        if (!libraryChoices.isEmpty()) {
            int visible = 0;
            for (Plex.Section s : libraryChoices) if (Plex.isLibraryEnabled(s.key)) visible++;
            row("Home & movies libraries", visible + " / " + libraryChoices.size() + " shown",
                    "Choose which Plex libraries appear in Home and Movies. Hidden libraries stay available elsewhere.",
                    v -> chooseLibraries(libraryChoices, "visibleLibraries", "Libraries on Home & Movies", null));

            java.util.Set<String> saverChoice = Plex.libraryChoice("screensaverLibraries");
            String saverValue;
            if (saverChoice == null) {
                saverValue = "Movie libraries";
            } else {
                int n = 0;
                for (Plex.Section s : libraryChoices) if (saverChoice.contains(s.key)) n++;
                saverValue = n + " / " + libraryChoices.size() + " used";
            }
            final java.util.Set<String> movieKeys = new java.util.HashSet<>();
            for (Plex.Section s : libraryChoices) if (s.type.equals("movie")) movieKeys.add(s.key);
            row("Screensaver libraries", saverValue,
                    "Which libraries the screensaver takes its artwork from. Until you choose, it uses only your movie libraries.",
                    v -> chooseLibraries(libraryChoices, "screensaverLibraries", "Libraries for the screensaver", movieKeys));
        }

        boolean tun = prefs.getBoolean("tunneled", false);
        row("Tunneled video", tun ? "On" : "Off",
                "Sends the picture straight to the Fire TV's video chip, the way some other apps do. Try this if a 4K "
                        + "HDR or Dolby Vision file shows green lines or noise. Press to change.",
                v -> {
                    prefs.edit().putBoolean("tunneled", !prefs.getBoolean("tunneled", false)).apply();
                    render(false);
                });

        boolean autoIn = prefs.getBoolean("autoSignIn", false);
        row("Automatic sign in", (autoIn ? "\u2611  On" : "\u2610  Off"),
                "On: Reel opens straight into the profile you used last. Off: it asks \"Who's watching?\" each time it "
                        + "starts (only when your Plex Home has more than one profile). Press to change.",
                v -> {
                    prefs.edit().putBoolean("autoSignIn", !prefs.getBoolean("autoSignIn", false)).apply();
                    render(false);
                });
        row("Profile", Plex.profileName == null ? "Main account" : Plex.profileName,
                "Switch to another profile in your Plex Home. Press to choose.",
                v -> startActivity(new Intent(this, ProfilesActivity.class)));

        row("Sign out", "", "Signs Reel out of your Plex account.",
                v -> {
                    Plex.signOut();
                    Intent i = new Intent(this, LoginActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                });
        if (focusFirst) first.requestFocus();
        else if (keep < list.getChildCount()) list.getChildAt(keep).requestFocus();
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
            render(false);
            if (url.isEmpty()) return;
            android.widget.Toast.makeText(this, "Checking Seerr\u2026", android.widget.Toast.LENGTH_SHORT).show();
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
        }
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding((int) (W * 0.04f), (int) (H * 0.03f), (int) (W * 0.04f), (int) (H * 0.03f));
        panel.setBackground(UiUtil.rounded(UiUtil.CARD, H * 0.022f));

        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.032f);
        heading.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        heading.setPadding(0, 0, 0, (int) (H * 0.02f));
        panel.addView(heading);

        for (final Plex.Section s : sections) {
            final TextView item = new TextView(this);
            item.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.026f);
            item.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
            int pad = (int) (H * 0.016f);
            item.setPadding(pad, pad, pad, pad);
            item.setBackground(UiUtil.rounded(UiUtil.CARD, H * 0.015f));
            item.setFocusable(true);
            item.setClickable(true);
            final float rad = H * 0.015f;
            final Runnable paint = () -> {
                boolean on = chosen.contains(s.key);
                boolean f = item.hasFocus();
                item.setText((on ? "\u2611 " : "\u2610 ") + s.title);
                item.setTextColor(f ? 0xFF000000 : on ? Color.WHITE : UiUtil.TEXT2);
                item.setBackground(UiUtil.rounded(f ? 0xFFFFFFFF : UiUtil.CARD, rad));
            };
            paint.run();
            item.setOnFocusChangeListener((v, f) -> {
                paint.run();
                float sc = f ? 1.03f : 1f;
                item.animate().scaleX(sc).scaleY(sc).setDuration(Motion.FAST_MS).setInterpolator(Motion.EASE).start();
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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (H * 0.012f);
            panel.addView(item, lp);
        }

        d.setContentView(panel);
        d.setOnDismissListener(x -> render(false));
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xCC000000));
            d.getWindow().setLayout((int) (W * 0.52f), ViewGroup.LayoutParams.WRAP_CONTENT);
            d.getWindow().setGravity(Gravity.CENTER);
        }
        d.show();
    }

    private View row(String name, String value, String help, View.OnClickListener click) {
        final LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (H * 0.022f);
        r.setPadding(pad * 2, pad, pad * 2, pad);
        final float rad = H * 0.018f;
        r.setBackground(UiUtil.rounded(UiUtil.CARD, rad));
        r.setFocusable(true);
        r.setClickable(true);
        r.setOnClickListener(click);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        final TextView n = new TextView(this);
        n.setText(name);
        n.setTextColor(Color.WHITE);
        n.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
        n.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        top.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(UiUtil.TEXT2);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.028f);
        v.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        top.addView(v);
        r.addView(top);

        final TextView h = new TextView(this);
        h.setText(help);
        h.setTextColor(UiUtil.TEXT3);
        h.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.022f);
        h.setPadding(0, (int) (H * 0.006f), 0, 0);
        r.addView(h);

        r.setOnFocusChangeListener((view, focused) -> {
            r.setBackground(UiUtil.rounded(focused ? 0xFFFFFFFF : UiUtil.CARD, rad));
            n.setTextColor(focused ? 0xFF000000 : Color.WHITE);
            h.setTextColor(focused ? 0xFF555555 : UiUtil.TEXT3);
            v.setTextColor(focused ? 0xFF333333 : UiUtil.TEXT2);
            float sc = focused ? 1.03f : 1f;
            r.animate().scaleX(sc).scaleY(sc).setDuration(Motion.FAST_MS).setInterpolator(Motion.EASE).start();
            r.setElevation(focused ? H * 0.015f : 0f);
            if (focused) SoundFx.move();
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (H * 0.015f);
        list.addView(r, lp);
        return r;
    }
}
