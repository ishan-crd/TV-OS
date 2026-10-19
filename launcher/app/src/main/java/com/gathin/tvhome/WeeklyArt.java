package com.gathin.tvhome;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The hero's artwork for streaming apps (config.xml weekly_art): a still from what's popular on that service
 * this week, from TMDB's catalogue of what each service offers in a region. Refreshed once a week; the
 * image and its title are kept in the app's files, so the home never waits on the network and works offline.
 */
final class WeeklyArt {

    private static final long WEEK_MS = 7L * 24 * 3600 * 1000;
    private static final String API = "https://api.themoviedb.org/3/";

    private WeeklyArt() {
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "weekly");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** The TMDB provider id configured for this app, or null. */
    private static String provider(Context c, String pkg) {
        for (String item : c.getResources().getStringArray(R.array.weekly_art)) {
            int eq = item.indexOf('=');
            if (eq > 0 && item.substring(0, eq).trim().equals(pkg)) return item.substring(eq + 1).trim();
        }
        return null;
    }

    /** The title shown this week for the app, from the saved copy; null when there's none. Cheap. */
    static String title(Context c, String pkg) {
        try {
            return new JSONObject(read(new FileInputStream(new File(dir(c), pkg + ".json")))).optString("title", null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Blocking, for a background thread: this week's image for the app, fetching a new one when the saved
     * one is a week old. The previous week's image while offline; null when the app has none.
     */
    static File image(Context c, String pkg) {
        String prov = provider(c, pkg);
        if (prov == null || BuildConfig.TMDB_KEY.isEmpty()) return null;
        File img = new File(dir(c), pkg + ".jpg"), meta = new File(dir(c), pkg + ".json");
        boolean fresh = img.isFile() && meta.isFile() && System.currentTimeMillis() - meta.lastModified() < WEEK_MS;
        if (!fresh) {
            try {
                refresh(c, prov, img, meta);
            } catch (Exception ignored) {
                // Offline or TMDB down: keep last week's picture.
            }
        }
        return img.isFile() ? img : null;
    }

    /** The most popular series or film released lately on the service, its backdrop and title. */
    private static void refresh(Context c, String prov, File img, File meta) throws Exception {
        String region = c.getString(R.string.weekly_art_region);
        long now = System.currentTimeMillis();
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        JSONObject best = null;
        double bestPop = -1;
        boolean bestTv = false;
        for (boolean tv : new boolean[]{true, false}) {
            // Recent releases only: plain popularity favours decades-old series that never leave the catalogue.
            String since = day.format(new Date(now - (tv ? 2 : 1) * 365L * 24 * 3600 * 1000));
            Uri u = Uri.parse(API + (tv ? "discover/tv" : "discover/movie")).buildUpon()
                    .appendQueryParameter("api_key", BuildConfig.TMDB_KEY)
                    .appendQueryParameter("watch_region", region)
                    .appendQueryParameter("with_watch_providers", prov)
                    .appendQueryParameter("sort_by", "popularity.desc")
                    .appendQueryParameter(tv ? "first_air_date.gte" : "primary_release_date.gte", since)
                    .appendQueryParameter("vote_count.gte", "30")
                    .build();
            JSONArray res = new JSONObject(get(u.toString())).optJSONArray("results");
            for (int i = 0; res != null && i < res.length(); i++) {
                JSONObject o = res.getJSONObject(i);
                if (o.optString("backdrop_path", "").isEmpty() || "null".equals(o.optString("backdrop_path"))) continue;
                if (o.optDouble("popularity") > bestPop) {
                    best = o;
                    bestPop = o.optDouble("popularity");
                    bestTv = tv;
                }
                break;   // the first with a backdrop is the most popular of its kind
            }
        }
        if (best == null) return;
        File tmp = new File(img.getPath() + ".tmp");
        download("https://image.tmdb.org/t/p/w1280" + best.getString("backdrop_path"), tmp);
        if (!tmp.renameTo(img)) throw new java.io.IOException("rename");
        JSONObject m = new JSONObject().put("title", best.optString(bestTv ? "name" : "title"));
        try (OutputStream out = new FileOutputStream(meta)) {
            out.write(m.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File to) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
