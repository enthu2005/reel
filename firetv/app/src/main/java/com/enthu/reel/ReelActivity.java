package com.enthu.reel;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

/** Base for the browsing screens: starts Reel's screensaver after the remote has been idle a while. */
public class ReelActivity extends Activity {
    static long lastInput = SystemClock.uptimeMillis();
    private final Handler idle = new Handler(Looper.getMainLooper());

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            long timeout = screensaverMs();
            if (timeout <= 0) return;
            long left = timeout - (SystemClock.uptimeMillis() - lastInput);
            if (left > 0) {
                idle.postDelayed(this, left);
                return;
            }
            lastInput = SystemClock.uptimeMillis();
            startActivity(new Intent(ReelActivity.this, ScreensaverActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }
    };

    private long screensaverMs() {
        int min = getSharedPreferences("reel_ui", MODE_PRIVATE).getInt("screensaverMin", 3);
        return min * 60_000L;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        lastInput = SystemClock.uptimeMillis();
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        PhoneRemote.ensure(this); // the phone page can search and send titles here
        lastInput = SystemClock.uptimeMillis();
        idle.removeCallbacks(check);
        long t = screensaverMs();
        if (t > 0) idle.postDelayed(check, t);
    }

    @Override
    protected void onPause() {
        super.onPause();
        idle.removeCallbacks(check);
    }
}
