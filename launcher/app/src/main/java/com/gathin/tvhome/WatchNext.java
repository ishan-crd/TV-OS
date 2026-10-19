package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Android TV's Watch Next list: the shows and films apps report as part-watched or up next. Readable with
 * the runtime permission android.permission.READ_TV_LISTINGS (build-and-install.sh grants it over adb).
 * Apps decide what they publish: on Google TV, JioHotstar and Prime Video do, Netflix and YouTube don't.
 */
final class WatchNext {

    static final class Program {
        final String pkg, title, episode, posterUri, intentUri;
        final long lastEngaged;
        final float progress;
        /** A film rather than a series, season or episode (Watch Next type). */
        boolean movie = true;

        Program(String pkg, String title, String episode, String posterUri, String intentUri,
                long lastEngaged, float progress) {
            this.pkg = pkg;
            this.title = title;
            this.episode = episode;
            this.posterUri = posterUri;
            this.intentUri = intentUri;
            this.lastEngaged = lastEngaged;
            this.progress = progress;
        }

        String key() {
            return pkg + "|" + title + "|" + episode;
        }

        /** Identity for hiding: the show, whichever episode is up next. */
        String showKey() {
            return pkg + "|" + title;
        }
    }

    private static final String[] COLUMNS = {
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_TYPE,
    };

    private WatchNext() {
    }

    static boolean permitted(Context c) {
        return c.checkSelfPermission("android.permission.READ_TV_LISTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    /** Most recently watched first, one entry per show, only for apps in `installed`. Empty without permission. */
    static List<Program> load(Context c, Set<String> installed, int limit) {
        List<Program> out = new ArrayList<>();
        if (!permitted(c)) return out;
        Set<String> seen = new HashSet<>();
        java.util.Map<String, Long> hidden = Prefs.hiddenShows(c);
        try (Cursor cur = c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, COLUMNS, null, null,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")) {
            if (cur == null) return out;
            while (cur.moveToNext() && out.size() < limit) {
                String pkg = cur.getString(0), title = cur.getString(1);
                if (pkg == null || title == null || !installed.contains(pkg)) continue;
                String season = cur.getString(3), number = cur.getString(4), epTitle = cur.getString(2);
                String episode = null;
                if (number != null) episode = (season != null ? "S" + season + " " : "") + "E" + number;
                if (epTitle != null && !epTitle.isEmpty()) episode = episode == null ? epTitle : episode + " · " + epTitle;
                if (!seen.add(pkg + "|" + title)) continue;
                long engaged = cur.getLong(8);
                Long hiddenAt = hidden.get(pkg + "|" + title);
                if (hiddenAt != null && engaged <= hiddenAt) continue;   // removed, and not watched since
                String poster = cur.getString(5);
                if (poster == null || poster.isEmpty()) poster = cur.getString(6);
                long duration = cur.getLong(9), position = cur.getLong(10);
                float progress = duration > 0 && position > 0 ? Math.min(1f, position / (float) duration) : 0f;
                Program p = new Program(pkg, title, episode, poster, cur.getString(7), engaged, progress);
                p.movie = cur.isNull(11) ? number == null
                        : cur.getInt(11) == TvContract.WatchNextPrograms.TYPE_MOVIE;
                out.add(p);
            }
        } catch (Exception e) {
            Log.w("WatchNext", "query failed", e);
        }
        return out;
    }

    /** Resumes the show in its app, or opens the app when the show's link doesn't work. */
    static void open(Context c, Program p, AppAdapter.Entry app) {
        if (p.intentUri != null) {
            try {
                Intent i = Intent.parseUri(p.intentUri, Intent.URI_INTENT_SCHEME);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        if (app != null) AppAdapter.launch(c, app);
    }
}
