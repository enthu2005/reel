package com.enthu.reel;

import android.app.Application;
import android.graphics.Typeface;

import java.lang.reflect.Field;
import java.util.Map;

/** Loads the Inter typeface and makes it the default everywhere in the app. */
public class ReelApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        UiUtil.loadFonts(this);
        Typeface reg = UiUtil.font(UiUtil.REGULAR);
        Typeface bold = UiUtil.font(UiUtil.BOLD);
        if (reg == null) return;
        // Best effort: if Android blocks any of this, text simply stays in the system font.
        setStatic("DEFAULT", reg);
        setStatic("SANS_SERIF", reg);
        setStatic("DEFAULT_BOLD", bold);
        try {
            Field f = Typeface.class.getDeclaredField("sDefaults");
            f.setAccessible(true);
            f.set(null, new Typeface[]{reg, bold, Typeface.create(reg, Typeface.ITALIC),
                    Typeface.create(bold, Typeface.BOLD_ITALIC)});
        } catch (Throwable ignored) {
        }
        try {
            Field f = Typeface.class.getDeclaredField("sSystemFontMap");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Typeface> map = (Map<String, Typeface>) f.get(null);
            if (map != null) {
                java.util.HashMap<String, Typeface> copy = new java.util.HashMap<>(map);
                copy.put("sans-serif", reg);
                copy.put("sans-serif-medium", UiUtil.font(UiUtil.MEDIUM));
                f.set(null, copy);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void setStatic(String name, Typeface t) {
        try {
            Field f = Typeface.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, t);
        } catch (Throwable ignored) {
        }
    }
}
