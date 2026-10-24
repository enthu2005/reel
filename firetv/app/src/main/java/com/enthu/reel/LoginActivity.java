package com.enthu.reel;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** Sign in the TV way: show a code, you type it at plex.tv/link on your phone. */
public class LoginActivity extends Activity {

    private static final long GIVE_UP_MS = 5 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView info;
    private TextView codeView;
    private TextView status;
    private long pinId;
    private long pinStarted;
    private boolean closed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Plex.init(this);
        buildUi();
        if (Plex.token != null) {
            findServer();
        } else {
            startPin();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closed = true;
        handler.removeCallbacksAndMessages(null);
    }

    private void buildUi() {
        int H = getResources().getDisplayMetrics().heightPixels;

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0B0D12, 0xFF1B1F2E, 0xFF2A2140}));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Reel");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.08f);
        title.setTypeface(UiUtil.tf("sans-serif-light", Typeface.NORMAL));
        col.addView(title);

        info = new TextView(this);
        info.setText("Getting a code…");
        info.setTextColor(0xCCFFFFFF);
        info.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.032f);
        info.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.topMargin = (int) (H * 0.04f);
        col.addView(info, infoLp);

        codeView = new TextView(this);
        codeView.setTextColor(0xFFE5A00D);
        codeView.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.13f);
        codeView.setTypeface(UiUtil.tf("monospace", Typeface.BOLD));
        codeView.setLetterSpacing(0.15f);
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        codeLp.topMargin = (int) (H * 0.04f);
        col.addView(codeView, codeLp);

        status = new TextView(this);
        status.setTextColor(0x99FFFFFF);
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.026f);
        status.setGravity(Gravity.CENTER);
        status.setFocusable(true);
        status.setPadding(40, 20, 40, 20);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = (int) (H * 0.05f);
        col.addView(status, stLp);

        root.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        setContentView(root);
    }

    private void startPin() {
        status.setText("");
        status.setOnClickListener(null);
        Plex.async(Plex::createPin, new Plex.Callback<JSONObject>() {
            @Override
            public void ok(JSONObject o) {
                if (closed) return;
                try {
                    pinId = o.getLong("id");
                    String code = o.getString("code").toUpperCase();
                    pinStarted = System.currentTimeMillis();
                    info.setText("On your phone or computer, go to plex.tv/link\nand enter this code");
                    codeView.setText(code);
                    status.setText("Waiting for you to sign in…");
                    schedulePoll();
                } catch (Exception e) {
                    fail(e);
                }
            }

            @Override
            public void fail(Exception e) {
                showError("Couldn't get a sign-in code (" + e.getMessage() + ").\nPress OK to try again.",
                        LoginActivity.this::startPin);
            }
        });
    }

    private void schedulePoll() {
        handler.postDelayed(() -> {
            if (closed) return;
            if (System.currentTimeMillis() - pinStarted > GIVE_UP_MS) {
                startPin();
                return;
            }
            Plex.async(() -> Plex.pollPin(pinId), new Plex.Callback<String>() {
                @Override
                public void ok(String token) {
                    if (closed) return;
                    if (token == null) {
                        schedulePoll();
                    } else {
                        Plex.saveToken(token);
                        findServer();
                    }
                }

                @Override
                public void fail(Exception e) {
                    if (!closed) schedulePoll();
                }
            });
        }, 2000);
    }

    private void findServer() {
        info.setText("Signed in. Finding your server…");
        codeView.setText("");
        status.setText("");
        status.setOnClickListener(null);
        Plex.async(() -> {
            Plex.findServer();
            return true;
        }, new Plex.Callback<Boolean>() {
            @Override
            public void ok(Boolean r) {
                if (closed) return;
                startActivity(new Intent(LoginActivity.this, HomeActivity.class));
                finish();
            }

            @Override
            public void fail(Exception e) {
                showError(e.getMessage() + ".\nCheck the server is on, then press OK to retry.",
                        LoginActivity.this::findServer);
            }
        });
    }

    private void showError(String msg, final Runnable retry) {
        if (closed) return;
        status.setText(msg);
        status.setOnClickListener((View v) -> retry.run());
        status.requestFocus();
    }
}
