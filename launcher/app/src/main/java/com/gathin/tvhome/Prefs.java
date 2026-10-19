package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The hero home's app lists as edited on the TV (long-press a card). Until something is changed there,
 * the lists come from config.xml: your_apps for the Your Apps row, dock_apps for the featured carousel.
 */
final class Prefs {

    static final String YOUR_APPS = "your_apps", FEATURED = "featured";
    private static final String HIDDEN_SHOWS = "hidden_shows";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    static List<String> list(Context c, String name) {
        String saved = sp(c).getString(name, null);
        if (saved != null) {
            List<String> out = new ArrayList<>();
            for (String p : saved.split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
            return out;
        }
        int res = name.equals(FEATURED) ? R.array.dock_apps : R.array.your_apps;
        List<String> out = new ArrayList<>();
        for (String p : Arrays.asList(c.getResources().getStringArray(res))) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    static void save(Context c, String name, List<String> pkgs) {
        sp(c).edit().putString(name, String.join(",", pkgs)).apply();
    }

    /**
     * Shows removed from Continue Watching, with the time they were removed. The launcher can't delete
     * another app's Watch Next entry, so it hides it; watching the show again (a newer engagement time
     * from its app) brings it back.
     */
    static java.util.Map<String, Long> hiddenShows(Context c) {
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        for (String e : sp(c).getStringSet(HIDDEN_SHOWS, new java.util.HashSet<>())) {
            int bar = e.indexOf('|');
            if (bar <= 0) continue;
            try {
                out.put(e.substring(bar + 1), Long.parseLong(e.substring(0, bar)));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static void hideShow(Context c, String showKey) {
        java.util.Map<String, Long> hidden = hiddenShows(c);
        hidden.put(showKey, System.currentTimeMillis());
        java.util.Set<String> set = new java.util.HashSet<>();
        for (java.util.Map.Entry<String, Long> e : hidden.entrySet()) set.add(e.getValue() + "|" + e.getKey());
        sp(c).edit().putStringSet(HIDDEN_SHOWS, set).apply();
    }
}
