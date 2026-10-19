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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Film and series details from TMDB (themoviedb.org) for the featured carousel: genres, rating, plot,
 * director and cast, artwork and recommendations. Blocking calls, for a background thread. Responses are
 * kept on disk for a week, so a resume costs no network. Needs BuildConfig.TMDB_KEY (local.properties
 * tmdb.key); without it every lookup returns null and the home falls back to the app carousel.
 */
final class Tmdb {

    static final String IMG = "https://image.tmdb.org/t/p/";
    private static final String API = "https://api.themoviedb.org/3/";
    private static final long TTL_MS = 7L * 24 * 3600 * 1000;

    /** One cast or crew member. */
    static final class Person {
        final String name, photo;

        Person(String name, String photo) {
            this.name = name;
            this.photo = photo;
        }
    }

    /** A title in a row: enough for its poster card and to look up its details. */
    static final class Brief {
        final long id;
        final boolean tv;
        final String title, poster, backdrop, year;

        Brief(long id, boolean tv, String title, String poster, String backdrop, String year) {
            this.id = id;
            this.tv = tv;
            this.title = title;
            this.poster = poster;
            this.backdrop = backdrop;
            this.year = year;
        }

        String key() {
            return (tv ? "tv:" : "movie:") + id;
        }
    }

    /** Everything the featured view shows. */
    static final class Meta {
        long id;
        boolean tv;
        String title, overview, country, year, certification, length, poster, backdrop;
        double rating;
        int votes;
        final List<String> genres = new ArrayList<>();
        /** Director, or the creator for a series. */
        Person director;
        final List<Person> cast = new ArrayList<>();
        final List<Brief> similar = new ArrayList<>();

        Brief brief() {
            return new Brief(id, tv, title, poster, backdrop, year);
        }
    }

    private final File dir;
    private final String key = BuildConfig.TMDB_KEY;

    Tmdb(Context c) {
        dir = new File(c.getCacheDir(), "tmdb");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    boolean enabled() {
        return !key.isEmpty();
    }

    /** The best match for a title from an app, or null. `tv` picks the series search first. */
    Meta find(String title, boolean tv) {
        if (!enabled() || title == null) return null;
        String clean = title.replaceAll("(?i)\\s*\\((19|20)\\d\\d\\)\\s*$", "").trim();
        Long id = search(clean, tv);
        boolean asTv = tv;
        if (id == null) {
            id = search(clean, !tv);
            asTv = !tv;
        }
        return id == null ? null : details(id, asTv);
    }

    /** Full details for a known title. */
    Meta details(long id, boolean tv) {
        if (!enabled()) return null;
        JSONObject d = get((tv ? "tv/" : "movie/") + id, "append_to_response",
                tv ? "credits,content_ratings,recommendations" : "credits,release_dates,recommendations");
        return d == null ? null : parse(d, tv);
    }

    private Long search(String query, boolean tv) {
        JSONObject r = get(tv ? "search/tv" : "search/movie", "query", query);
        if (r == null) return null;
        JSONArray res = r.optJSONArray("results");
        if (res == null || res.length() == 0) return null;
        // An exact title match among the first few wins; otherwise the most popular result.
        String q = norm(query);
        for (int i = 0; i < Math.min(5, res.length()); i++) {
            JSONObject o = res.optJSONObject(i);
            if (o != null && norm(o.optString(tv ? "name" : "title")).equals(q)) return o.optLong("id");
        }
        JSONObject first = res.optJSONObject(0);
        return first == null ? null : first.optLong("id");
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static Meta parse(JSONObject d, boolean tv) {
        Meta m = new Meta();
        m.id = d.optLong("id");
        m.tv = tv;
        m.title = d.optString(tv ? "name" : "title");
        m.overview = d.optString("overview", "");
        m.rating = d.optDouble("vote_average", 0);
        m.votes = d.optInt("vote_count", 0);
        m.poster = str(d, "poster_path");
        m.backdrop = str(d, "backdrop_path");
        String date = d.optString(tv ? "first_air_date" : "release_date", "");
        m.year = date.length() >= 4 ? date.substring(0, 4) : null;
        JSONArray g = d.optJSONArray("genres");
        for (int i = 0; g != null && i < g.length() && m.genres.size() < 3; i++) {
            String name = g.optJSONObject(i).optString("name");
            // TMDB's long series genres: keep the first word pair readable on a chip.
            m.genres.add(name.replace("Action & Adventure", "Action").replace("Sci-Fi & Fantasy", "Sci-fi")
                    .replace("Science Fiction", "Sci-fi"));
        }
        if (tv) {
            JSONArray oc = d.optJSONArray("origin_country");
            m.country = oc != null && oc.length() > 0 ? oc.optString(0) : null;
            int seasons = d.optInt("number_of_seasons", 0);
            m.length = seasons > 0 ? seasons + (seasons == 1 ? " Season" : " Seasons") : null;
            JSONArray by = d.optJSONArray("created_by");
            if (by != null && by.length() > 0) {
                JSONObject p = by.optJSONObject(0);
                m.director = new Person(p.optString("name"), str(p, "profile_path"));
            }
            m.certification = rating(d.optJSONObject("content_ratings"), false);
        } else {
            JSONArray pc = d.optJSONArray("production_countries");
            m.country = pc != null && pc.length() > 0 ? pc.optJSONObject(0).optString("iso_3166_1") : null;
            int min = d.optInt("runtime", 0);
            m.length = min > 0 ? (min / 60 > 0 ? (min / 60) + "h " : "") + (min % 60) + "m" : null;
            m.certification = rating(d.optJSONObject("release_dates"), true);
        }
        if ("US".equals(m.country)) m.country = "USA";
        else if ("GB".equals(m.country)) m.country = "UK";
        else if ("IN".equals(m.country)) m.country = "India";
        JSONObject credits = d.optJSONObject("credits");
        if (credits != null) {
            JSONArray crew = credits.optJSONArray("crew");
            for (int i = 0; m.director == null && crew != null && i < crew.length(); i++) {
                JSONObject p = crew.optJSONObject(i);
                if ("Director".equals(p.optString("job"))) m.director = new Person(p.optString("name"), str(p, "profile_path"));
            }
            JSONArray cast = credits.optJSONArray("cast");
            for (int i = 0; cast != null && i < cast.length() && m.cast.size() < 8; i++) {
                JSONObject p = cast.optJSONObject(i);
                m.cast.add(new Person(p.optString("name"), str(p, "profile_path")));
            }
        }
        JSONObject recs = d.optJSONObject("recommendations");
        JSONArray rr = recs == null ? null : recs.optJSONArray("results");
        for (int i = 0; rr != null && i < rr.length() && m.similar.size() < 12; i++) {
            JSONObject o = rr.optJSONObject(i);
            String poster = str(o, "poster_path");
            if (poster == null) continue;
            boolean isTv = "tv".equals(o.optString("media_type", tv ? "tv" : "movie"));
            String dt = o.optString(isTv ? "first_air_date" : "release_date", "");
            m.similar.add(new Brief(o.optLong("id"), isTv, o.optString(isTv ? "name" : "title"), poster,
                    str(o, "backdrop_path"), dt.length() >= 4 ? dt.substring(0, 4) : null));
        }
        return m;
    }

    /** The age rating for India, else the US, from release_dates (films) or content_ratings (series). */
    private static String rating(JSONObject o, boolean film) {
        JSONArray res = o == null ? null : o.optJSONArray("results");
        if (res == null) return null;
        for (String country : new String[]{"IN", "US"}) {
            for (int i = 0; i < res.length(); i++) {
                JSONObject c = res.optJSONObject(i);
                if (!country.equals(c.optString("iso_3166_1"))) continue;
                if (!film) {
                    String r = c.optString("rating", "");
                    if (!r.isEmpty()) return r;
                    continue;
                }
                JSONArray rd = c.optJSONArray("release_dates");
                for (int j = 0; rd != null && j < rd.length(); j++) {
                    String r = rd.optJSONObject(j).optString("certification", "");
                    if (!r.isEmpty()) return r;
                }
            }
        }
        return null;
    }

    private static String str(JSONObject o, String k) {
        String v = o.optString(k, "");
        return v.isEmpty() || "null".equals(v) ? null : v;
    }

    /** GET with the API key, from the disk cache when fresh. Null on any failure. */
    private JSONObject get(String path, String param, String value) {
        Uri.Builder b = Uri.parse(API + path).buildUpon().appendQueryParameter("api_key", key)
                .appendQueryParameter("language", "en-US");
        if (param != null) b.appendQueryParameter(param, value);
        String url = b.build().toString();
        File f = new File(dir, Integer.toHexString((path + "|" + value).hashCode()) + ".json");
        if (f.isFile() && System.currentTimeMillis() - f.lastModified() < TTL_MS) {
            try {
                return new JSONObject(read(new FileInputStream(f)));
            } catch (Exception ignored) {
            }
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            if (c.getResponseCode() != 200) return null;
            String body = read(c.getInputStream());
            JSONObject json = new JSONObject(body);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            return json;
        } catch (Exception e) {
            // Offline: a stale copy beats nothing.
            if (f.isFile()) {
                try {
                    return new JSONObject(read(new FileInputStream(f)));
                } catch (Exception ignored) {
                }
            }
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
