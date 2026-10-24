package com.enthu.reel;

import android.view.KeyEvent;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/** One set of animation timings and one easing curve for the whole app, so everything moves alike. */
final class Motion {
    private Motion() {
    }

    /** A press: the quick squeeze and release when you click something. */
    static final int TAP_MS = 100;
    /** Small things appearing or leaving: labels, dots, hints. */
    static final int FAST_MS = 150;
    /** Things that react to the remote: focus growing, buttons lifting. */
    static final int FOCUS_MS = 200;
    /** Panels, rows and cards moving into place. */
    static final int MEDIUM_MS = 320;
    /** Whole-screen changes: pages, backdrops, pictures fading. */
    static final int SLOW_MS = 480;
    /** Video and big backdrops fading in. */
    static final int LONG_MS = 650;

    /** Quick start, long soft landing. */
    static final Interpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    // The direction of the last remote press, so a card can lean the way you moved.
    static int lastDx;
    static int lastDy;

    static void noteKey(int keyCode) {
        lastDx = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
        lastDy = keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 0;
    }

    /** Focus grow / shrink for buttons and small items. */
    static void focusScale(View v, float s) {
        v.animate().scaleX(s).scaleY(s).setDuration(FOCUS_MS).setInterpolator(EASE).start();
    }
}
