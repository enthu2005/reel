package com.enthu.reel;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewOutlineProvider;

final class UiUtil {
    private UiUtil() {
    }

    // Apple TV-like palette
    static final int BG = 0xFF000000;
    static final int CARD = 0xFF1C1C1E;
    static final int TEXT = 0xFFFFFFFF;
    static final int TEXT2 = 0x99EBEBF5; // secondary label
    static final int TEXT3 = 0x66EBEBF5;
    static final int FILL = 0x33FFFFFF;  // translucent controls

    static final int REGULAR = 0;
    static final int MEDIUM = 1;
    static final int SEMIBOLD = 2;
    static final int BOLD = 3;
    private static final android.graphics.Typeface[] FONTS = new android.graphics.Typeface[4];

    static void loadFonts(Context c) {
        String[] names = {"inter_regular", "inter_medium", "inter_semibold", "inter_bold"};
        for (int i = 0; i < 4; i++) {
            try {
                FONTS[i] = android.graphics.Typeface.createFromAsset(c.getAssets(), "fonts/" + names[i] + ".ttf");
            } catch (Throwable ignored) {
                FONTS[i] = null;
            }
        }
    }

    static android.graphics.Typeface font(int weight) {
        android.graphics.Typeface t = FONTS[weight];
        if (t != null) return t;
        return android.graphics.Typeface.create(weight >= MEDIUM ? "sans-serif-medium" : "sans-serif",
                weight == BOLD ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    /** Drop-in for Typeface.create(family, style) that uses Inter. */
    static android.graphics.Typeface tf(String family, int style) {
        if (family != null && family.contains("mono")) return android.graphics.Typeface.create(family, style);
        boolean med = family != null && family.contains("medium");
        boolean bold = (style & android.graphics.Typeface.BOLD) != 0;
        if (med && bold) return font(SEMIBOLD);
        if (bold) return font(BOLD);
        if (med) return font(MEDIUM);
        return font(REGULAR);
    }

    /** Apple TV style pill: translucent at rest, solid white with dark text when focused. */
    static void stylePill(android.widget.TextView t, boolean focused, float r) {
        t.setBackground(rounded(focused ? 0xFFFFFFFF : FILL, r));
        t.setTextColor(focused ? 0xFF000000 : 0xFFFFFFFF);
    }

    /** A soft diagonal highlight laid over a focused card, like the tvOS sheen. */
    static android.graphics.drawable.Drawable sheen(float r) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x2EFFFFFF, 0x08FFFFFF, 0x00FFFFFF});
        g.setCornerRadius(r);
        return g;
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable stroke(float radiusPx, int widthPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(radiusPx);
        g.setStroke(widthPx, Color.WHITE);
        return g;
    }

    static void clipRounded(View v, final float radiusPx) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        v.setClipToOutline(true);
    }

    static String duration(long ms) {
        long min = ms / 60000L;
        if (min >= 60) return (min / 60) + "h " + (min % 60) + "m";
        return min + "m";
    }
}
