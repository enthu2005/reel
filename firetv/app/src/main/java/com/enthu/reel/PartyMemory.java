package com.enthu.reel;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Party mode's memory: which films each game has already used in a library, so the next game (or the next round)
 * starts with ones you haven't seen. When too few fresh films are left it forgets and starts over.
 */
final class PartyMemory {
    interface Key<T> {
        String of(T item);
    }

    private PartyMemory() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("reel_party", Context.MODE_PRIVATE);
    }

    static Set<String> seen(Context c, String scope) {
        return new HashSet<>(sp(c).getStringSet(scope, new HashSet<String>()));
    }

    static void add(Context c, String scope, Collection<String> keys) {
        if (keys.isEmpty()) return;
        Set<String> s = seen(c, scope);
        s.addAll(keys);
        sp(c).edit().putStringSet(scope, s).apply();
    }

    static void clear(Context c, String scope) {
        sp(c).edit().remove(scope).apply();
    }

    /** Only the items not used before (all of them if every one has been). */
    static <T> List<T> unseen(Context c, String scope, List<T> items, Key<T> key) {
        Set<String> seen = seen(c, scope);
        List<T> out = new ArrayList<>();
        for (T it : items) if (!seen.contains(key.of(it))) out.add(it);
        return out.isEmpty() ? items : out;
    }

    /**
     * [items] with the ones not used before first (the order inside each group is kept). If fewer than [need] fresh
     * ones remain, the memory for this scope is wiped and everything counts as fresh again.
     */
    static <T> List<T> fresh(Context c, String scope, List<T> items, Key<T> key, int need) {
        Set<String> seen = seen(c, scope);
        List<T> unseen = new ArrayList<>();
        List<T> old = new ArrayList<>();
        for (T it : items) (seen.contains(key.of(it)) ? old : unseen).add(it);
        if (unseen.size() < need) {
            clear(c, scope);
            return items;
        }
        unseen.addAll(old);
        return unseen;
    }
}
