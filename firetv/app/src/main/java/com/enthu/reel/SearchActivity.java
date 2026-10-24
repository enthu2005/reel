package com.enthu.reel;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Search for movies, shows and episodes with an on-screen keyboard. */
public class SearchActivity extends ReelActivity implements PosterAdapter.Listener {

    private static final String[] ROWS = {"ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ1234", "567890"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder query = new StringBuilder();
    private final List<Plex.Item> results = new ArrayList<>();
    private int W;
    private int H;
    private TextView queryView;
    private LinearLayout suggBox;
    private Runnable pendingSugg;
    private int suggToken = 0;
    private static final int SUGG_ROW_H_DIV = 16; // row height = screen height / this
    private TextView message;
    private RecyclerView grid;
    private int token = 0;
    private Runnable pending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Plex.init(this);
        SoundFx.init(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(UiUtil.BG);

        // Left: query and keyboard
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (W * 0.04f);
        left.setPadding(pad, (int) (H * 0.08f), 0, 0);

        queryView = new TextView(this);
        queryView.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.045f);
        queryView.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        queryView.setSingleLine(true);
        queryView.setPadding((int) (H * 0.025f), (int) (H * 0.02f), (int) (H * 0.025f), (int) (H * 0.02f));
        queryView.setBackground(UiUtil.rounded(0x22FFFFFF, H * 0.03f));
        left.addView(queryView, new LinearLayout.LayoutParams(
                (int) (W * 0.33f), ViewGroup.LayoutParams.WRAP_CONTENT));

        int keyW = (int) (W * 0.33f / 6f);
        View firstKey = null;

        // Voice and the Fire TV keyboard (which can take dictation on some sticks)
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView kb = key("Fire TV keyboard", keyW * 6);
        kb.setOnClickListener(v -> systemKeyboard());
        top.addView(kb);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = (int) (H * 0.02f);
        left.addView(top, tlp);
        for (String row : ROWS) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (char ch : row.toCharArray()) {
                final String c = String.valueOf(ch);
                TextView k = key(c, keyW);
                k.setOnClickListener(v -> type(c));
                if (firstKey == null) firstKey = k;
                r.addView(k);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (H * 0.012f);
            left.addView(r, lp);
        }
        LinearLayout last = new LinearLayout(this);
        last.setOrientation(LinearLayout.HORIZONTAL);
        TextView space = key("Space", keyW * 2);
        space.setOnClickListener(v -> type(" "));
        TextView del = key("Delete", keyW * 2);
        del.setOnClickListener(v -> {
            if (query.length() > 0) query.setLength(query.length() - 1);
            changed();
        });
        TextView clear = key("Clear", keyW * 2);
        clear.setOnClickListener(v -> {
            query.setLength(0);
            changed();
        });
        last.addView(space);
        last.addView(del);
        last.addView(clear);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = (int) (H * 0.012f);
        left.addView(last, llp);
        root.addView(left, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Right: results
        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, 3));
        grid.setClipToPadding(false);
        grid.setItemAnimator(null);
        grid.setPadding((int) (W * 0.02f), (int) (H * 0.08f), (int) (W * 0.03f), (int) (H * 0.06f));
        FrameLayout.LayoutParams gLp = new FrameLayout.LayoutParams(
                (int) (W * 0.58f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        root.addView(grid, gLp);

        // Suggestions as you type (like YouTube): tap one to search for it
        suggBox = new LinearLayout(this);
        suggBox.setOrientation(LinearLayout.VERTICAL);
        suggBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams sLp = new FrameLayout.LayoutParams(
                (int) (W * 0.52f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
        sLp.topMargin = (int) (H * 0.08f);
        sLp.rightMargin = (int) (W * 0.03f);
        root.addView(suggBox, sLp);

        message = new TextView(this);
        message.setTextColor(0x99FFFFFF);
        message.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.032f);
        message.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams mLp = new FrameLayout.LayoutParams(
                (int) (W * 0.58f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
        root.addView(message, mLp);

        setContentView(root);
        preview = new PreviewCard(this, root, PreviewCard::plexTrailer); // live previews on the posters
        changed();
        if (firstKey != null) firstKey.requestFocus();
        if (getIntent().getBooleanExtra("voice", false)) handler.postDelayed(this::voiceSearch, 300);
    }

    private TextView key(String label, int width) {
        final TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
        t.setTypeface(UiUtil.tf("sans-serif-medium", Typeface.BOLD));
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        final float r = H * 0.02f;
        t.setBackground(UiUtil.rounded(0x14FFFFFF, r));
        t.setOnFocusChangeListener((v, f) -> {
            t.setBackground(UiUtil.rounded(f ? 0xFFFFFFFF : 0x14FFFFFF, r));
            t.setTextColor(f ? 0xFF000000 : Color.WHITE);
            if (f) SoundFx.move();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, (int) (H * 0.085f));
        lp.setMargins(3, 0, 3, 0);
        t.setLayoutParams(lp);
        return t;
    }

    private static final int VOICE = 7;

    /** Android speech input if this Fire TV offers it to apps, otherwise the Fire TV keyboard. */
    private void voiceSearch() {
        SoundFx.click();
        Intent ri = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        ri.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        ri.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a movie or show");
        ri.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        if (ri.resolveActivity(getPackageManager()) != null) {
            try {
                startActivityForResult(ri, VOICE);
                return;
            } catch (Exception ignored) {
                // fall through to the keyboard
            }
        }
        android.widget.Toast.makeText(this, "Hold the mic button on your remote while the keyboard is open "
                + "to speak (if your Fire TV allows it)", android.widget.Toast.LENGTH_LONG).show();
        systemKeyboard();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != VOICE || resultCode != RESULT_OK || data == null) return;
        java.util.ArrayList<String> said = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (said != null && !said.isEmpty()) setQuery(said.get(0));
    }

    /** A real text box, so Fire TV's own keyboard (and its dictation, where available) can be used. */
    private void systemKeyboard() {
        SoundFx.click();
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        final android.widget.EditText box = new android.widget.EditText(this);
        box.setSingleLine(true);
        box.setText(query.toString());
        box.setSelection(box.getText().length());
        box.setHint("Search titles, shows and actors");
        box.setTextColor(Color.WHITE);
        box.setHintTextColor(0x66FFFFFF);
        box.setTypeface(UiUtil.font(UiUtil.MEDIUM));
        box.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.04f);
        int p = (int) (H * 0.025f);
        box.setPadding(p, p, p, p);
        box.setBackground(UiUtil.rounded(0xFF1C1C1E, H * 0.025f));
        box.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        box.setOnEditorActionListener((v, actionId, ev) -> {
            setQuery(box.getText().toString());
            d.dismiss();
            return true;
        });
        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            d.getWindow().setLayout((int) (W * 0.5f), ViewGroup.LayoutParams.WRAP_CONTENT);
            d.getWindow().setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        d.setOnDismissListener(di -> setQuery(box.getText().toString()));
        d.show();
        box.requestFocus();
        box.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(box, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    private void setQuery(String text) {
        String t = text == null ? "" : text.trim().toLowerCase();
        if (t.length() > 40) t = t.substring(0, 40);
        if (t.equals(query.toString())) return;
        query.setLength(0);
        query.append(t);
        changed();
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (preview != null && preview.handleKey(e)) return true;
        // The search / voice keys some remotes have
        int k = e.getKeyCode();
        if ((k == android.view.KeyEvent.KEYCODE_SEARCH || k == android.view.KeyEvent.KEYCODE_VOICE_ASSIST)
                && e.getAction() == android.view.KeyEvent.ACTION_UP) {
            voiceSearch();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void loadSuggestions(final String q) {
        if (pendingSugg != null) handler.removeCallbacks(pendingSugg);
        final int mine = ++suggToken;
        if (q.isEmpty()) {
            showSuggestions(new ArrayList<>());
            return;
        }
        pendingSugg = () -> Plex.async(() -> Plex.suggest(q), new Plex.Callback<List<String>>() {
            @Override
            public void ok(List<String> list) {
                if (isDestroyed() || mine != suggToken) return;
                showSuggestions(list);
            }

            @Override
            public void fail(Exception e) {
            }
        });
        handler.postDelayed(pendingSugg, 200);
    }

    private void showSuggestions(List<String> list) {
        suggBox.removeAllViews();
        final String cur = query.toString().trim().toLowerCase();
        int shown = 0;
        int rowH = H / SUGG_ROW_H_DIV;
        for (final String s : list) {
            if (s.toLowerCase().equals(cur) || shown >= 5) continue;
            shown++;
            final TextView t = new TextView(this);
            t.setText(s);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
            t.setTypeface(UiUtil.font(UiUtil.MEDIUM));
            t.setTextColor(Color.WHITE);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER_VERTICAL);
            int px = (int) (H * 0.022f);
            t.setPadding(px, 0, px, 0);
            t.setFocusable(true);
            t.setClickable(true);
            final float r = H * 0.02f;
            t.setBackground(UiUtil.rounded(0x14FFFFFF, r));
            t.setOnFocusChangeListener((v, f) -> {
                t.setBackground(UiUtil.rounded(f ? 0xFFFFFFFF : 0x14FFFFFF, r));
                t.setTextColor(f ? 0xFF000000 : Color.WHITE);
                if (f) SoundFx.move();
            });
            t.setOnClickListener(v -> {
                SoundFx.click();
                query.setLength(0);
                query.append(s.length() > 40 ? s.substring(0, 40) : s);
                // search for it straight away
                changed();
                suggBox.removeAllViews();
                suggBox.setVisibility(View.GONE);
                updateGridTop();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowH);
            lp.bottomMargin = (int) (H * 0.008f);
            suggBox.addView(t, lp);
        }
        suggBox.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
        updateGridTop();
    }

    /** Pushes the results grid below the suggestions. */
    private void updateGridTop() {
        int n = suggBox.getChildCount();
        int top = (int) (H * 0.08f) + (n == 0 ? 0 : n * (H / SUGG_ROW_H_DIV + (int) (H * 0.008f)) + (int) (H * 0.02f));
        grid.setPadding(grid.getPaddingLeft(), top, grid.getPaddingRight(), grid.getPaddingBottom());
    }

    private void type(String c) {
        SoundFx.click();
        if (query.length() < 40) query.append(c.toLowerCase());
        changed();
    }

    private void changed() {
        if (query.length() == 0) {
            queryView.setText("Search titles, shows and actors");
            queryView.setTextColor(0x66FFFFFF);
        } else {
            queryView.setText(query.toString());
            queryView.setTextColor(Color.WHITE);
        }
        if (pending != null) handler.removeCallbacks(pending);
        final String q = query.toString().trim();
        final int mine = ++token;
        loadSuggestions(q);
        if (q.length() < 2) {
            results.clear();
            grid.setAdapter(null);
            message.setText(q.isEmpty() ? "Type a title or an actor" : "Keep typing…");
            message.setVisibility(View.VISIBLE);
            return;
        }
        pending = () -> Plex.async(() -> Plex.search(q), new Plex.Callback<List<Plex.Item>>() {
            @Override
            public void ok(List<Plex.Item> list) {
                if (isDestroyed() || mine != token) return;
                results.clear();
                results.addAll(list);
                int w = (int) ((W * 0.58f - W * 0.05f) / 3f) - UiUtil.dp(SearchActivity.this, 14);
                grid.setAdapter(new PosterAdapter(results, w, (int) (w * 9f / 16f), true,
                        SearchActivity.this).withSeries());
                message.setText("No results for “" + q + "”");
                message.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
            }

            @Override
            public void fail(Exception e) {
                if (isDestroyed() || mine != token) return;
                message.setText("Search didn't work: " + e.getMessage());
                message.setVisibility(View.VISIBLE);
            }
        });
        handler.postDelayed(pending, 450);
    }

    @Override
    public void onFocused(Plex.Item item) {
    }

    private PreviewCard preview;

    @Override
    public void onFocusedView(Plex.Item item, View card) {
        if (preview != null) preview.onFocused(item, card);
    }

    @Override
    public boolean onLongClicked(Plex.Item item) {
        return preview != null && preview.longPress(item);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (preview != null) preview.stop();
    }

    @Override
    public void onClicked(Plex.Item it) {
        final Intent i = new Intent(this, DetailActivity.class);
        i.putExtra("key", it.key);
        if (preview != null && preview.openFullThen(it.key, () -> startActivity(i))) return;
        if (preview != null) preview.handOff(it.key);
        startActivity(i);
    }
}
