package com.gathin.tvhome;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rivulet-style home (config.xml hero_home): the dock apps as a featured carousel over full-screen
 * artwork, with Open / info / next buttons and page dots, and rows of apps under it that slide up on Down.
 * MainActivity still owns the top-right pills (Inputs, network, Settings) and the system callbacks.
 */
final class HeroHome implements RowAdapter.Listener {

    private static final long AUTO_ADVANCE_MS = 9000;
    private static final long ROW_BACKDROP_DELAY_MS = 220;
    private static final long MOVIE_ADVANCE_MS = 12000;
    /** How far the page slides up when the rows take focus (set by the hero mode). */
    private float rowsShiftDp = 386;

    // Static so they survive the activity being recreated while another app is open.
    private static String lastSlidePkg, lastMovieKey;
    private static int lastRow = -1, lastRowPos;

    private final MainActivity act;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Backdrops backdrops;

    private final ImageView backA, backB;
    private final View dim, page, heroText, buttons, btnOpen, btnInfo, btnNext;
    private final ImageView logo;
    private final TextView title, meta, badge, tagline;
    private final DotsView dots;
    /** 0 Similar, 1 Your Apps, 2 Continue Watching, 3 Wishlist, 4 All Apps. An empty row is hidden and skipped. */
    private static final int ROWS = 5;
    private static final int R_SIMILAR = 0, R_YOUR = 1, R_CONTINUE = 2, R_WISH = 3, R_ALL = 4;
    private final RecyclerView[] rows = new RecyclerView[ROWS];
    private final RowAdapter[] adapters = new RowAdapter[ROWS];
    private final TextView[] rowTitles = new TextView[ROWS];

    private final List<AppAdapter.Entry> slides = new ArrayList<>();
    private Map<String, Long> lastUsed = new HashMap<>();
    /** Text for each slide, worked out once per resume so a slide change does no lookups or file IO. */
    private final Map<String, String[]> texts = new HashMap<>();
    private final Map<String, Bitmap> logos = new HashMap<>();
    private final File logoDir;
    private int slide;
    private boolean inRows;
    /** Package whose backdrop should be on screen; late results for other packages are dropped. */
    private String wantBackdrop;
    private boolean frontIsB;
    private RowAdapter.Item pendingRowItem;
    /** Apps on this TV by package, refreshed on resume. */
    private final Map<String, AppAdapter.Entry> apps = new HashMap<>();
    /** Extra darkening of the artwork at night (config.xml night_*), 0 by day. */
    private float nightDim;

    // ---- featured films and series (TMDB) ----
    /** A title your apps report, with its TMDB details; program is null for one picked from a row. */
    static final class Featured {
        final Tmdb.Meta meta;
        final WatchNext.Program program;
        final AppAdapter.Entry app;

        Featured(Tmdb.Meta meta, WatchNext.Program program, AppAdapter.Entry app) {
            this.meta = meta;
            this.program = program;
            this.app = app;
        }
    }

    private final Tmdb tmdb;
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final List<Featured> movies = new ArrayList<>();
    private boolean movieMode;
    /** The film or series on screen (a carousel entry, or one picked from Similar/Wishlist). */
    private Featured shown;
    private boolean fullCast;
    private List<WatchNext.Program> programs = new ArrayList<>();
    private final View movieHero, mvWatch, mvWish, mvFullCast, rowsBlock;
    private final ImageView mvPoster, mvWishIcon;
    private final TextView mvTitle, mvFacts, mvPlot, mvRating, mvVotes, mvWatchLabel, mvWishLabel;
    private final android.widget.LinearLayout mvChips, mvCrew;

    private final Runnable autoAdvance = new Runnable() {
        @Override
        public void run() {
            if (!inRows && slideCount() > 1 && act.hasWindowFocus() && (!movieMode || shown == null
                    || shown.program != null)) {
                showSlide(slide + 1, 1);
            }
            handler.postDelayed(this, movieMode ? MOVIE_ADVANCE_MS : AUTO_ADVANCE_MS);
        }
    };
    private final Runnable rowBackdrop = () -> {
        if (inRows && pendingRowItem != null) showItemBackdrop(pendingRowItem);
    };

    HeroHome(MainActivity act) {
        this.act = act;
        density = act.getResources().getDisplayMetrics().density;
        backdrops = new Backdrops(act);
        logoDir = act.getExternalFilesDir("logos");
        backA = act.findViewById(R.id.backA);
        backB = act.findViewById(R.id.backB);
        dim = act.findViewById(R.id.dim);
        page = act.findViewById(R.id.page);
        heroText = act.findViewById(R.id.heroText);
        buttons = act.findViewById(R.id.buttons);
        logo = act.findViewById(R.id.logo);
        title = act.findViewById(R.id.title);
        meta = act.findViewById(R.id.meta);
        badge = act.findViewById(R.id.badge);
        tagline = act.findViewById(R.id.tagline);
        dots = act.findViewById(R.id.dots);
        btnOpen = act.findViewById(R.id.btnOpen);
        btnInfo = act.findViewById(R.id.btnInfo);
        btnNext = act.findViewById(R.id.btnNext);
        tmdb = new Tmdb(act);
        movieHero = act.findViewById(R.id.movieHero);
        rowsBlock = act.findViewById(R.id.rows);
        mvPoster = act.findViewById(R.id.mvPoster);
        mvTitle = act.findViewById(R.id.mvTitle);
        mvFacts = act.findViewById(R.id.mvFacts);
        mvPlot = act.findViewById(R.id.mvPlot);
        mvRating = act.findViewById(R.id.mvRating);
        mvVotes = act.findViewById(R.id.mvVotes);
        mvChips = act.findViewById(R.id.mvChips);
        mvCrew = act.findViewById(R.id.mvCrew);
        mvWatch = act.findViewById(R.id.mvWatch);
        mvWish = act.findViewById(R.id.mvWish);
        mvWishIcon = act.findViewById(R.id.mvWishIcon);
        mvWatchLabel = act.findViewById(R.id.mvWatchLabel);
        mvWishLabel = act.findViewById(R.id.mvWishLabel);
        mvFullCast = act.findViewById(R.id.mvFullCast);
        roundCorners(mvPoster, 12);
        mvWatch.setOnClickListener(v -> {
            if (shown != null && shown.program != null) WatchNext.open(act, shown.program, shown.app);
        });
        mvWish.setOnClickListener(v -> {
            if (shown == null) return;
            Prefs.toggleWishlist(act, shown.meta.brief());
            bindWish();
            fillWishlist();
        });
        mvFullCast.setOnClickListener(v -> {
            fullCast = !fullCast;
            if (shown != null) bindCrew(shown.meta);
        });

        int[] ids = {R.id.row0, R.id.row1, R.id.row2, R.id.row3, R.id.row4};
        int[] titleIds = {R.id.row0Title, R.id.row1Title, R.id.row2Title, R.id.row3Title, R.id.row4Title};
        for (int i = 0; i < ROWS; i++) {
            rows[i] = act.findViewById(ids[i]);
            rowTitles[i] = act.findViewById(titleIds[i]);
            rows[i].setLayoutManager(new LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false));
            rows[i].setItemAnimator(null);
            rows[i].setHasFixedSize(true);
            adapters[i] = new RowAdapter(i, this, i == R_SIMILAR || i == R_WISH);
            rows[i].setAdapter(adapters[i]);
        }

        btnOpen.setOnClickListener(v -> {
            if (!slides.isEmpty()) AppAdapter.launch(act, slides.get(slide));
        });
        btnInfo.setOnClickListener(v -> {
            if (!slides.isEmpty()) AppAdapter.openAppInfo(act, slides.get(slide));
        });
        btnNext.setOnClickListener(v -> showSlide(slide + 1, 1));
        View.OnKeyListener heroKeys = this::onHeroKey;
        for (View b : new View[]{btnOpen, btnInfo, btnNext, mvWatch, mvWish, mvFullCast}) {
            b.setOnKeyListener(heroKeys);
            // Apple's focus growth: about 10 pt on the pill, more on the small circles.
            final float s = b == btnOpen || b == mvWatch || b == mvWish ? 1.05f : b == mvFullCast ? 1.04f : 1.12f;
            b.setOnFocusChangeListener((v, f) -> v.animate().scaleX(f ? s : 1f).scaleY(f ? s : 1f)
                    .setDuration(f ? 220 : 160).setInterpolator(new DecelerateInterpolator(1.4f)).start());
        }
        // Smaller icons in the top-right pills than the classic layout's.
        int icon = Math.round(15 * density);
        sizeIcon(act.findViewById(R.id.btnWifi), R.drawable.ic_wifi, icon);
    }

    private void sizeIcon(TextView v, int res, int px) {
        Drawable d = act.getDrawable(res);
        if (d == null) return;
        d = d.mutate();
        d.setBounds(0, 0, px, px);
        v.setCompoundDrawablesRelative(d, null, null, null);
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    void onResume() {
        buildRows();
        updateNightDim(false);
        loadMovies();
        texts.clear();
        logos.clear();
        for (AppAdapter.Entry e : slides) {
            texts.put(e.pkg, new String[]{
                    act.getString(R.string.hero_app) + " ·", category(e.pkg), tagline(e.pkg)});
            Bitmap l = customLogo(e.pkg);
            if (l != null) logos.put(e.pkg, l);
        }
        dots.setCount(slideCount());
        slide = -1;
        showSlide(startIndex(), 0);

        // Back from an app opened from a row: return to that card, otherwise to Open.
        if (lastRow >= 0 && lastRow < ROWS && adapters[lastRow].getItemCount() > 0) {
            final int r = lastRow, p = lastRowPos;
            enterRows(false);
            rows[r].post(() -> {
                scrollPageTo(r, false);
                focusCard(r, p);
            });
        } else {
            exitRows(false);
        }
        handler.removeCallbacks(autoAdvance);
        handler.postDelayed(autoAdvance, AUTO_ADVANCE_MS);
    }

    /** Reads the apps, the lists edited on the TV and Watch Next, and fills the carousel and rows. */
    private void buildRows() {
        List<AppAdapter.Entry> all = AppAdapter.loadApps(act);
        lastUsed = readLastUsed();
        apps.clear();
        for (AppAdapter.Entry e : all) apps.put(e.pkg, e);

        slides.clear();
        for (String pkg : Prefs.list(act, Prefs.FEATURED)) if (apps.containsKey(pkg)) slides.add(apps.get(pkg));
        if (slides.isEmpty()) slides.addAll(all.subList(0, Math.min(4, all.size())));

        // Your Apps: the list edited on the TV (or config.xml your_apps); an empty list = recently used.
        List<RowAdapter.Item> first = new ArrayList<>();
        for (String pkg : Prefs.list(act, Prefs.YOUR_APPS)) if (apps.containsKey(pkg)) first.add(new RowAdapter.Item(apps.get(pkg), null));
        if (!first.isEmpty()) {
            rowTitles[R_YOUR].setText(R.string.hero_your_apps);
        } else {
            List<AppAdapter.Entry> recent = new ArrayList<>();
            for (AppAdapter.Entry e : all) if (lastUsed.containsKey(e.pkg)) recent.add(e);
            recent.sort((a, b) -> Long.compare(lastUsed.get(b.pkg), lastUsed.get(a.pkg)));
            for (AppAdapter.Entry e : recent.subList(0, Math.min(12, recent.size()))) first.add(new RowAdapter.Item(e, null));
            rowTitles[R_YOUR].setText(first.isEmpty() ? R.string.hero_favourites : R.string.hero_recent);
            if (first.isEmpty()) for (AppAdapter.Entry e : slides) first.add(new RowAdapter.Item(e, null));
        }
        setRow(R_YOUR, first);

        // Continue Watching: the shows apps report to Android TV's Watch Next list (needs READ_TV_LISTINGS).
        // Without it, or with nothing reported, the video apps used in the last week, most recent first.
        List<RowAdapter.Item> watching = new ArrayList<>();
        programs = WatchNext.load(act, apps.keySet(), 20);
        for (WatchNext.Program p : programs) {
            watching.add(new RowAdapter.Item(p, apps.get(p.pkg)));
        }
        if (watching.isEmpty()) {
            long weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
            List<AppAdapter.Entry> used = new ArrayList<>();
            for (AppAdapter.Entry e : all) {
                Long t = lastUsed.get(e.pkg);
                if (t != null && t >= weekAgo && isWatchApp(e.pkg)) used.add(e);
            }
            used.sort((a, b) -> Long.compare(lastUsed.get(b.pkg), lastUsed.get(a.pkg)));
            for (AppAdapter.Entry e : used) watching.add(new RowAdapter.Item(e, recency(e.pkg)));
        }
        rowTitles[R_CONTINUE].setText(R.string.hero_continue);
        setRow(R_CONTINUE, watching);
        fillWishlist();

        List<AppAdapter.Entry> alpha = new ArrayList<>(all);
        alpha.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        List<RowAdapter.Item> allItems = new ArrayList<>();
        for (AppAdapter.Entry e : alpha) allItems.add(new RowAdapter.Item(e, null));
        rowTitles[R_ALL].setText(R.string.hero_all_apps);
        setRow(R_ALL, allItems);
    }

    /** Called every minute by MainActivity. */
    void onMinute() {
        updateNightDim(true);
    }

    void onPause() {
        handler.removeCallbacks(autoAdvance);
        handler.removeCallbacks(rowBackdrop);
    }

    /** Back: from the rows to the hero. Returns true when handled. */
    boolean onBack() {
        if (!inRows) return false;
        exitRows(true);
        return true;
    }

    // ---- carousel --------------------------------------------------------------------------------

    /** dir: +1 next, -1 previous, 0 no animation. */
    private void showSlide(int index, int dir) {
        if (movieMode) {
            showMovieSlide(index, dir);
            return;
        }
        if (slides.isEmpty()) return;
        int n = slides.size();
        int i = ((index % n) + n) % n;
        if (i == slide) return;
        slide = i;
        AppAdapter.Entry e = slides.get(i);
        lastSlidePkg = e.pkg;
        dots.setActive(i, dir != 0);
        if (dir == 0) {
            heroText.animate().cancel();
            heroText.setAlpha(inRows ? 0f : 1f);
            heroText.setTranslationX(0f);
            bindText(e);
        } else {
            final float dx = 14 * density * dir;
            // Hardware layer: the variable-font text is drawn once per phase, not on every frame of the fade.
            heroText.animate().alpha(0f).translationX(-dx).setDuration(140).withLayer().withEndAction(() -> {
                bindText(e);
                heroText.setTranslationX(dx);
                heroText.animate().alpha(inRows ? 0f : 1f).translationX(0f).setDuration(260)
                        .setInterpolator(new DecelerateInterpolator(1.4f)).withLayer().start();
            }).start();
        }
        if (!inRows) showBackdrop(e, dir != 0);
        // Neighbours next, so the next press of › or the auto-advance has its artwork ready.
        backdrops.preload(slides.get((i + 1) % n));
        backdrops.preload(slides.get((i - 1 + n) % n));
    }

    private void bindText(AppAdapter.Entry e) {
        Bitmap custom = logos.get(e.pkg);
        if (custom != null) {
            logo.setImageBitmap(custom);
            logo.setVisibility(View.VISIBLE);
            title.setVisibility(View.GONE);
        } else {
            logo.setImageDrawable(null);
            logo.setVisibility(View.GONE);
            title.setVisibility(View.VISIBLE);
            title.setText(e.label);
        }
        String[] t = texts.get(e.pkg);
        if (t == null) t = new String[]{act.getString(R.string.hero_app), null, null};
        // "App ·" then the category in the outlined badge: App · [VIDEO].
        meta.setText(t[0]);
        String category = t[1];
        badge.setVisibility(category == null ? View.GONE : View.VISIBLE);
        badge.setText(category);
        String line = t[2];
        tagline.setVisibility(line == null ? View.GONE : View.VISIBLE);
        tagline.setText(line);
    }

    private boolean onHeroKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        // Any key on the hero restarts the auto-advance countdown.
        handler.removeCallbacks(autoAdvance);
        handler.postDelayed(autoAdvance, AUTO_ADVANCE_MS);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int first = nextRow(-1, 1);
                if (first < 0) return true;
                enterRows(true);
                focusCard(first, 0);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP:
                act.findViewById(R.id.btnWifi).requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (v == btnOpen || v == mvWatch || (v == mvWish && mvWatch.getVisibility() != View.VISIBLE)) {
                    showSlide(slide - 1, -1);
                    return true;
                }
                if (v == mvFullCast) {
                    mvWish.requestFocus();
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (v == btnNext || v == mvFullCast || (v == mvWish && mvFullCast.getVisibility() != View.VISIBLE)) {
                    showSlide(slide + 1, 1);
                    return true;
                }
                if (v == mvWish) {
                    mvFullCast.requestFocus();
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    // ---- featured films and series ----------------------------------------------------------------

    private int slideCount() {
        return movieMode ? movies.size() : slides.size();
    }

    /** The carousel entry to start on: the one shown last time. */
    private int startIndex() {
        if (movieMode) {
            for (int k = 0; k < movies.size(); k++) if (movies.get(k).meta.brief().key().equals(lastMovieKey)) return k;
            return 0;
        }
        for (int k = 0; k < slides.size(); k++) if (slides.get(k).pkg.equals(lastSlidePkg)) return k;
        return 0;
    }

    /**
     * Looks up the titles your apps report (Watch Next) on TMDB in the background; with at least one match
     * the hero switches to the film view. Cached on disk, so after the first time this is quick and offline.
     */
    private void loadMovies() {
        if (!tmdb.enabled()) return;
        final List<WatchNext.Program> progs = new ArrayList<>(programs);
        final Map<String, AppAdapter.Entry> appMap = new HashMap<>(apps);
        worker.execute(() -> {
            List<Featured> found = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (WatchNext.Program p : progs) {
                if (found.size() >= 10) break;
                Tmdb.Meta m = tmdb.find(p.title, !p.movie);
                if (m == null || m.backdrop == null || m.poster == null || !seen.add(m.brief().key())) continue;
                found.add(new Featured(m, p, appMap.get(p.pkg)));
            }
            // A different order each day, so the home doesn't open on the same film every time.
            java.util.Collections.shuffle(found, new java.util.Random(System.currentTimeMillis() / 86_400_000L));
            handler.post(() -> setMovies(found));
        });
    }

    private void setMovies(List<Featured> found) {
        String before = shown != null && shown.program != null ? shown.meta.brief().key() : null;
        List<String> oldKeys = new ArrayList<>(), newKeys = new ArrayList<>();
        for (Featured f : movies) oldKeys.add(f.meta.brief().key());
        for (Featured f : found) newKeys.add(f.meta.brief().key());
        if (movieMode && oldKeys.equals(newKeys)) return;   // nothing changed since last resume
        movies.clear();
        movies.addAll(found);
        boolean was = movieMode;
        movieMode = !movies.isEmpty();
        // Layout for the mode: the film view is taller, so the rows start and slide up from lower down.
        heroText.setVisibility(movieMode ? View.GONE : View.VISIBLE);
        buttons.setVisibility(movieMode ? View.GONE : View.VISIBLE);
        movieHero.setVisibility(movieMode ? View.VISIBLE : View.GONE);
        android.widget.FrameLayout.LayoutParams rl = (android.widget.FrameLayout.LayoutParams) rowsBlock.getLayoutParams();
        rl.topMargin = Math.round((movieMode ? 400 : 456) * density);
        rowsBlock.setLayoutParams(rl);
        android.widget.FrameLayout.LayoutParams dl = (android.widget.FrameLayout.LayoutParams) dots.getLayoutParams();
        dl.topMargin = Math.round((movieMode ? 381 : 423) * density);
        dots.setLayoutParams(dl);
        rowsShiftDp = movieMode ? 334 : 386;
        if (!movieMode) setRow(R_SIMILAR, new ArrayList<>());
        dots.setCount(slideCount());
        if (inRows && was == movieMode) return;   // keep the rows as they are under the user's focus
        slide = -1;
        int i = 0;
        for (int k = 0; k < movies.size(); k++) if (movies.get(k).meta.brief().key().equals(before != null ? before : lastMovieKey)) i = k;
        showSlide(movieMode ? i : startIndex(), 0);
        if (!inRows) {
            if (movieMode) mvWatch.requestFocus();
            else btnOpen.requestFocus();
        }
    }

    private void showMovieSlide(int index, int dir) {
        if (movies.isEmpty()) return;
        int n = movies.size();
        int i = ((index % n) + n) % n;
        if (i == slide && shown != null && shown.program != null) return;
        slide = i;
        showFeatured(movies.get(i), dir);
        dots.setActive(i, dir != 0);
        lastMovieKey = movies.get(i).meta.brief().key();
        // Neighbours' artwork in advance.
        backdrops.preloadImage(Tmdb.IMG + "w780" + movies.get((i + 1) % n).meta.backdrop);
    }

    /** Puts a film or series in the hero: text slides, the backdrop crossfades. */
    private void showFeatured(Featured f, int dir) {
        boolean focusInside = movieHero.hasFocus();
        shown = f;
        fullCast = false;
        if (dir == 0) {
            movieHero.animate().cancel();
            movieHero.setTranslationX(0f);
            bindMovie(f);
        } else {
            final float dx = 14 * density * dir;
            movieHero.animate().alpha(0f).translationX(-dx).setDuration(140).withLayer().withEndAction(() -> {
                bindMovie(f);
                movieHero.setTranslationX(dx);
                movieHero.animate().alpha(inRows ? 0f : 1f).translationX(0f).setDuration(260)
                        .setInterpolator(new DecelerateInterpolator(1.4f)).withLayer().start();
            }).start();
        }
        if (!inRows) showMovieBackdrop(f.meta, dir != 0 || backA.getDrawable() != null);
        if (!inRows) fillSimilar(f.meta);
        if (focusInside && f.program == null && mvWatch.isFocused()) mvWish.requestFocus();
    }

    private void bindMovie(Featured f) {
        Tmdb.Meta m = f.meta;
        mvPoster.setImageDrawable(null);
        final String key = m.brief().key();
        Posters.get(act).load(Tmdb.IMG + "w342" + m.poster, 364, 540, (uri, bmp) -> {
            if (bmp != null && shown != null && shown.meta.brief().key().equals(key)) mvPoster.setImageBitmap(bmp);
        });
        mvChips.removeAllViews();
        for (String g : m.genres) mvChips.addView(chip(g));
        mvTitle.setText(m.title);
        List<String> facts = new ArrayList<>();
        for (String x : new String[]{m.country, m.year, m.certification, m.length}) if (x != null && !x.isEmpty()) facts.add(x);
        mvFacts.setText(android.text.TextUtils.join("  •  ", facts));
        mvPlot.setText(m.overview);
        // Rating: 8.7 large in green (yellow, red when lower), then a small /10.
        if (m.votes > 0) {
            String r = String.format(java.util.Locale.US, "%.1f", m.rating);
            android.text.SpannableString sp = new android.text.SpannableString(r + "/10");
            int color = m.rating >= 7 ? 0xFF5BD23C : m.rating >= 5 ? 0xFFF5C518 : 0xFFFF6B5B;
            sp.setSpan(new android.text.style.ForegroundColorSpan(color), 0, r.length(), 0);
            sp.setSpan(new android.text.style.RelativeSizeSpan(0.5f), r.length(), sp.length(), 0);
            sp.setSpan(new android.text.style.ForegroundColorSpan(0xE6FFFFFF), r.length(), sp.length(), 0);
            mvRating.setText(sp);
            mvVotes.setText(act.getString(R.string.mv_reviews, compact(m.votes)));
            mvRating.setVisibility(View.VISIBLE);
            mvVotes.setVisibility(View.VISIBLE);
        } else {
            mvRating.setVisibility(View.GONE);
            mvVotes.setVisibility(View.GONE);
        }
        bindCrew(m);
        boolean playable = f.program != null;
        mvWatch.setVisibility(playable ? View.VISIBLE : View.GONE);
        mvWatchLabel.setText(m.tv ? R.string.mv_watch_show : R.string.mv_watch_movie);
        bindWish();
    }

    private void bindWish() {
        if (shown == null) return;
        boolean in = Prefs.inWishlist(act, shown.meta.brief());
        mvWishLabel.setText(in ? R.string.mv_in_wishlist : R.string.mv_add_wishlist);
        mvWishIcon.setImageResource(in ? R.drawable.ic_mv_check : R.drawable.ic_mv_list_add);
    }

    /** Director (or creator) and the first three of the cast; "See full cast" shows up to eight. */
    private void bindCrew(Tmdb.Meta m) {
        mvCrew.removeAllViews();
        if (m.director != null) {
            mvCrew.addView(label(m.tv ? R.string.mv_creator : R.string.mv_director, false));
            mvCrew.addView(person(m.director));
        }
        if (!m.cast.isEmpty()) {
            mvCrew.addView(label(R.string.mv_cast, m.director != null));
            int n = Math.min(fullCast ? 8 : 3, m.cast.size());
            for (int i = 0; i < n; i++) mvCrew.addView(person(m.cast.get(i)));
        }
        boolean any = m.director != null || !m.cast.isEmpty();
        mvCrew.setVisibility(any ? View.VISIBLE : View.GONE);
        mvFullCast.setVisibility(m.cast.size() > 3 ? View.VISIBLE : View.GONE);
        ((TextView) mvFullCast).setText(fullCast ? R.string.mv_less_cast : R.string.mv_full_cast);
    }

    private TextView label(int res, boolean gap) {
        TextView t = new TextView(act);
        t.setText(res);
        t.setTextColor(0xD9FFFFFF);
        t.setTextSize(9f);
        t.setLetterSpacing(0.06f);
        t.setTypeface(act.getResources().getFont(R.font.ui));
        t.setFontVariationSettings("'wght' 500, 'opsz' 17");
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Math.round((gap ? 9 : 2) * density);
        lp.bottomMargin = Math.round(4 * density);
        t.setLayoutParams(lp);
        return t;
    }

    private View person(Tmdb.Person p) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(act);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.LinearLayout.LayoutParams rl = new android.widget.LinearLayout.LayoutParams(-1, Math.round(23 * density));
        row.setLayoutParams(rl);
        ImageView face = new ImageView(act);
        int sz = Math.round(18 * density);
        face.setLayoutParams(new android.widget.LinearLayout.LayoutParams(sz, sz));
        face.setScaleType(ImageView.ScaleType.CENTER_CROP);
        face.setBackgroundColor(0x33FFFFFF);
        face.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        face.setClipToOutline(true);
        if (p.photo != null) {
            Posters.get(act).load(Tmdb.IMG + "w92" + p.photo, 48, 48, (uri, bmp) -> {
                if (bmp != null) face.setImageBitmap(bmp);
            });
        }
        TextView name = new TextView(act);
        android.widget.LinearLayout.LayoutParams nl = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
        nl.setMarginStart(Math.round(7 * density));
        name.setLayoutParams(nl);
        name.setText(p.name);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(11f);
        name.setTypeface(act.getResources().getFont(R.font.ui));
        name.setFontVariationSettings("'wght' 500, 'opsz' 17");
        row.addView(face);
        row.addView(name);
        return row;
    }

    private TextView chip(String text) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(10.5f);
        t.setTypeface(act.getResources().getFont(R.font.ui));
        t.setFontVariationSettings("'wght' 500, 'opsz' 17");
        t.setGravity(android.view.Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setBackgroundResource(R.drawable.mv_chip);
        int ph = Math.round(13 * density);
        t.setPadding(ph, 0, ph, 0);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-2, -1);
        lp.setMarginEnd(Math.round(5 * density));
        t.setLayoutParams(lp);
        return t;
    }

    private static String compact(int n) {
        if (n >= 1_000_000) return String.format(java.util.Locale.US, "%.1fM", n / 1e6).replace(".0M", "M");
        if (n >= 1_000) return (n / 1000) + "K";
        return String.valueOf(n);
    }

    private void roundCorners(View v, float dp) {
        final float r = dp * density;
        v.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    /** Similar titles from TMDB for the film on screen. */
    private void fillSimilar(Tmdb.Meta m) {
        List<RowAdapter.Item> items = new ArrayList<>();
        for (Tmdb.Brief b : m.similar) items.add(new RowAdapter.Item(b));
        rowTitles[R_SIMILAR].setText(m.tv ? R.string.mv_similar_shows : R.string.mv_similar);
        setRow(R_SIMILAR, items);
    }

    private void fillWishlist() {
        List<RowAdapter.Item> items = new ArrayList<>();
        for (Tmdb.Brief b : Prefs.wishlist(act)) items.add(new RowAdapter.Item(b));
        rowTitles[R_WISH].setText(R.string.mv_wishlist);
        setRow(R_WISH, items);
    }

    /**
     * A title picked from Similar or Wishlist: its details in the hero. Watch shows when one of your apps
     * reports it (Continue Watching); otherwise it can be added to the wishlist.
     */
    private void showBrief(Tmdb.Brief b) {
        Featured inLibrary = null;
        for (Featured f : movies) if (f.meta.brief().key().equals(b.key())) inLibrary = f;
        if (inLibrary != null) {
            exitRows(true);
            showMovieSlide(movies.indexOf(inLibrary), 1);
            return;
        }
        worker.execute(() -> {
            Tmdb.Meta m = tmdb.details(b.id, b.tv);
            if (m == null) return;
            handler.post(() -> {
                exitRows(true);
                showFeatured(new Featured(m, null, null), 1);
                mvWish.requestFocus();
            });
        });
    }

    private void showMovieBackdrop(Tmdb.Meta m, boolean animate) {
        if (m.backdrop == null) return;
        String uri = Tmdb.IMG + "w780" + m.backdrop;
        String want = "img:" + uri;
        if (want.equals(wantBackdrop)) return;
        wantBackdrop = want;
        backdrops.getImage(uri, (key, bmp) -> {
            if (key.equals(wantBackdrop) && bmp != null) crossfade(bmp, animate);
        });
    }

    private void showImageBackdrop(String uri) {
        String want = "img:" + uri;
        if (want.equals(wantBackdrop)) return;
        wantBackdrop = want;
        backdrops.getImage(uri, (key, bmp) -> {
            if (key.equals(wantBackdrop) && bmp != null) crossfade(bmp, true);
        });
    }

    // ---- backdrop --------------------------------------------------------------------------------

    private void showBackdrop(AppAdapter.Entry e, boolean animate) {
        if (e.pkg.equals(wantBackdrop)) return;
        wantBackdrop = e.pkg;
        backdrops.get(e, (key, bmp) -> {
            if (key.equals(wantBackdrop) && bmp != null) crossfade(bmp, animate);
        });
    }

    /** A show's poster full screen; its app's backdrop when there is no poster or it won't load. */
    private void showItemBackdrop(RowAdapter.Item it) {
        if (it.brief != null) {
            if (it.brief.backdrop != null) showImageBackdrop(Tmdb.IMG + "w780" + it.brief.backdrop);
            return;
        }
        if (it.show == null || it.show.posterUri == null) {
            if (it.app != null) showBackdrop(it.app, true);
            return;
        }
        String want = "img:" + it.show.posterUri;
        if (want.equals(wantBackdrop)) return;
        wantBackdrop = want;
        backdrops.getImage(it.show.posterUri, (key, bmp) -> {
            if (!key.equals(wantBackdrop)) return;
            if (bmp != null) crossfade(bmp, true);
            else if (it.app != null) {
                wantBackdrop = null;
                showBackdrop(it.app, true);
            }
        });
    }

    /** Two stacked images: the new one fades in on top, or the top one fades out over it. */
    private void crossfade(Bitmap bmp, boolean animate) {
        long d = animate ? 450 : 0;
        backA.animate().cancel();
        backB.animate().cancel();
        // Both are drawn only while fading; afterwards the hidden one is INVISIBLE, so a still screen
        // costs one full-screen image, which matters on a TV GPU.
        backA.setVisibility(View.VISIBLE);
        backB.setVisibility(View.VISIBLE);
        if (!frontIsB) {
            backB.setImageBitmap(bmp);
            if (d == 0) {
                backB.setAlpha(1f);
                backA.setVisibility(View.INVISIBLE);
            } else {
                backB.animate().alpha(1f).setDuration(d).withEndAction(() -> backA.setVisibility(View.INVISIBLE)).start();
            }
        } else {
            backA.setImageBitmap(bmp);
            backA.setAlpha(1f);
            if (d == 0) {
                backB.setAlpha(0f);
                backB.setVisibility(View.INVISIBLE);
            } else {
                backB.animate().alpha(0f).setDuration(d).withEndAction(() -> backB.setVisibility(View.INVISIBLE)).start();
            }
        }
        frontIsB = !frontIsB;
    }

    // ---- rows ------------------------------------------------------------------------------------

    private void enterRows(boolean animate) {
        handler.removeCallbacks(autoAdvance);
        boolean was = inRows;
        inRows = true;
        long d = animate && !was ? 380 : 0;
        if (!was) {
            page.animate().translationY(-rowsShiftDp * density).setDuration(d)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
        heroText.animate().alpha(0f).setDuration(d / 2).withLayer().start();
        buttons.animate().alpha(0f).setDuration(d / 2).withLayer().start();
        movieHero.animate().alpha(0f).setDuration(d / 2).withLayer().start();
        dots.animate().alpha(0f).setDuration(d / 2).start();
        applyDim(d);
    }

    private void exitRows(boolean animate) {
        inRows = false;
        lastRow = -1;
        handler.removeCallbacks(rowBackdrop);
        long d = animate ? 380 : 0;
        DecelerateInterpolator ease = new DecelerateInterpolator(2f);
        page.animate().translationY(0f).setDuration(d).setInterpolator(ease).start();
        // Rows faded out under the top bar while scrolled down come back.
        for (int r = 0; r < ROWS; r++) {
            rows[r].animate().cancel();
            rowTitles[r].animate().cancel();
            rows[r].setAlpha(1f);
            rowTitles[r].setAlpha(1f);
        }
        heroText.animate().alpha(1f).setDuration(d).withLayer().start();
        buttons.animate().alpha(1f).setDuration(d).withLayer().start();
        movieHero.animate().alpha(1f).setDuration(d).withLayer().start();
        dots.animate().alpha(1f).setDuration(d).start();
        applyDim(d);
        if (movieMode) {
            if (shown != null) showMovieBackdrop(shown.meta, animate);
            (mvWatch.getVisibility() == View.VISIBLE ? mvWatch : mvWish).requestFocus();
        } else {
            if (!slides.isEmpty()) showBackdrop(slides.get(slide), animate);
            btnOpen.requestFocus();
        }
        handler.removeCallbacks(autoAdvance);
        handler.postDelayed(autoAdvance, AUTO_ADVANCE_MS);
    }

    private void focusCard(int row, int position) {
        RecyclerView rv = rows[row];
        int count = adapters[row].getItemCount();
        if (count == 0) {
            int next = nextRow(row, 1);
            if (next >= 0) focusCard(next, position);
            return;
        }
        final int pos = Math.max(0, Math.min(position, count - 1));
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.findViewById(R.id.card).requestFocus();
            return;
        }
        rv.scrollToPosition(pos);
        rv.post(() -> {
            RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
            if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
        });
    }

    /** Adapter position of the card in a row whose centre is nearest to x (screen coordinates). */
    private int nearestCard(int row, float x) {
        RecyclerView rv = rows[row];
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        int[] loc = new int[2];
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            View card = child.findViewById(R.id.card);
            card.getLocationOnScreen(loc);
            float cx = loc[0] + card.getWidth() / 2f;
            float dist = Math.abs(cx - x);
            int pos = rv.getChildAdapterPosition(child);
            if (pos != RecyclerView.NO_POSITION && dist < bestDist) {
                bestDist = dist;
                best = pos;
            }
        }
        return best;
    }

    @Override
    public void onCardFocused(int row, int position, RowAdapter.Item e) {
        if (!inRows) enterRows(true);
        scrollPageTo(row, true);
        lastRow = row;
        lastRowPos = position;
        // The backdrop follows the focused card, after a short pause so racing along a row stays cheap.
        pendingRowItem = e;
        handler.removeCallbacks(rowBackdrop);
        handler.postDelayed(rowBackdrop, ROW_BACKDROP_DELAY_MS);
    }

    @Override
    public boolean onCardKey(int row, int position, int keyCode) {
        int count = adapters[row].getItemCount();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return position == 0;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return position >= count - 1;
            case KeyEvent.KEYCODE_DPAD_UP: {
                int up = nextRow(row, -1);
                if (up < 0) exitRows(true);
                else focusCard(up, nearestCard(up, focusedCardCenterX(row, position)));
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int down = nextRow(row, 1);
                if (down >= 0) focusCard(down, nearestCard(down, focusedCardCenterX(row, position)));
                return true;
            }
            default:
                return false;
        }
    }

    private float focusedCardCenterX(int row, int position) {
        RecyclerView.ViewHolder vh = rows[row].findViewHolderForAdapterPosition(position);
        if (vh == null) return 0f;
        View card = vh.itemView.findViewById(R.id.card);
        int[] loc = new int[2];
        card.getLocationOnScreen(loc);
        return loc[0] + card.getWidth() / 2f;
    }

    private void setRow(int row, List<RowAdapter.Item> items) {
        adapters[row].setItems(items);
        int v = items.isEmpty() ? View.GONE : View.VISIBLE;
        rows[row].setVisibility(v);
        rowTitles[row].setVisibility(v);
    }

    /** The next non-empty row above (dir -1) or below (dir +1), or -1. */
    private int nextRow(int row, int dir) {
        for (int r = row + dir; r >= 0 && r < ROWS; r += dir) {
            if (adapters[r].getItemCount() > 0) return r;
        }
        return -1;
    }

    /**
     * Slides the page so the focused row is on screen: the first two visible rows fit under the top bar,
     * a later row moves the page up by the distance from the second visible row to it.
     */
    private void scrollPageTo(int row, boolean animate) {
        int first = nextRow(-1, 1);
        int second = first < 0 ? -1 : nextRow(first, 1);
        float extra = 0f;
        if (second >= 0 && row != first && row != second) {
            extra = rowTitles[row].getTop() - rowTitles[second].getTop();
        }
        float y = -rowsShiftDp * density - extra;
        // Rows that slide up under the top bar fade out instead of peeking out behind the pills.
        for (int r = 0; r < ROWS; r++) {
            // Everything above the row placed at the top would sit under the top bar.
            float alpha = extra > 0 && r < nextRow(row, -1) ? 0f : 1f;
            if (rows[r].getAlpha() != alpha) {
                rows[r].animate().alpha(alpha).setDuration(animate ? 200 : 0).start();
                rowTitles[r].animate().alpha(alpha).setDuration(animate ? 200 : 0).start();
            }
        }
        if (Math.abs(page.getTranslationY() - y) < 1f) return;
        page.animate().translationY(y).setDuration(animate ? 320 : 0)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    /** Apps you watch or listen in: declared video/music apps, the well-known streaming ones and Live TV. */
    private boolean isWatchApp(String pkg) {
        if (tagline(pkg) != null) return true;
        try {
            int cat = act.getPackageManager().getApplicationInfo(pkg, 0).category;
            return cat == ApplicationInfo.CATEGORY_VIDEO || cat == ApplicationInfo.CATEGORY_AUDIO;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override
    public void onCardClick(RowAdapter.Item it) {
        if (it.brief != null) showBrief(it.brief);
        else if (it.show != null) WatchNext.open(act, it.show, it.app);
        else AppAdapter.launch(act, it.app);
    }

    @Override
    public void onCardLongClick(RowAdapter.Item it, View card) {
        if (it.app != null) CardMenu.show(act, card, it, this::onMenuChanged);
    }

    /** After an edit from the card menu: rebuild, and keep focus on the same card in the same row. */
    private void onMenuChanged(String focusKey) {
        int row = lastRow;
        buildRows();
        dots.setCount(slideCount());
        int i = startIndex();
        slide = -1;
        texts.clear();
        for (AppAdapter.Entry e : slides) {
            texts.put(e.pkg, new String[]{
                    act.getString(R.string.hero_app) + " ·", category(e.pkg), tagline(e.pkg)});
        }
        showSlide(i, 0);
        if (row < 0 || row >= ROWS) return;
        int r = row, pos = adapters[row].positionOf(focusKey);
        if (pos < 0) {
            // The card is gone (removed): stay at the same place in its row, or move to the next row.
            if (adapters[row].getItemCount() > 0) {
                pos = Math.min(lastRowPos, adapters[row].getItemCount() - 1);
            } else {
                r = nextRow(row, 1);
                if (r < 0) r = nextRow(row, -1);
                pos = 0;
            }
        }
        if (r < 0) {
            exitRows(true);
            return;
        }
        final int fr = r, fp = pos;
        rows[fr].post(() -> {
            scrollPageTo(fr, false);
            focusCard(fr, fp);
        });
    }

    // ---- night -----------------------------------------------------------------------------------

    private void updateNightDim(boolean animate) {
        int start = act.getResources().getInteger(R.integer.night_start_hour);
        int end = act.getResources().getInteger(R.integer.night_end_hour);
        int pct = act.getResources().getInteger(R.integer.night_dim_percent);
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        boolean night = start == end ? false : start < end ? (h >= start && h < end) : (h >= start || h < end);
        float want = night ? Math.max(0, Math.min(90, pct)) / 100f : 0f;
        if (want == nightDim) return;
        nightDim = want;
        applyDim(animate ? 1200 : 0);
    }

    /** The dim layer over the artwork: the night setting, or half while the rows are up, whichever is darker. */
    private void applyDim(long duration) {
        float target = Math.max(nightDim, inRows ? 0.5f : 0f);
        dim.animate().cancel();
        if (target > 0f) {
            dim.setVisibility(View.VISIBLE);
            dim.animate().alpha(target).setDuration(duration).withEndAction(null).start();
        } else {
            dim.animate().alpha(0f).setDuration(duration).withEndAction(() -> dim.setVisibility(View.GONE)).start();
        }
    }

    // ---- text ------------------------------------------------------------------------------------

    private Bitmap customLogo(String pkg) {
        if (logoDir == null) return null;
        File f = new File(logoDir, pkg + ".png");
        return f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    /** Last time each app was in the foreground, over the last 60 days. Empty without usage access. */
    private Map<String, Long> readLastUsed() {
        Map<String, Long> out = new HashMap<>();
        try {
            UsageStatsManager usm = (UsageStatsManager) act.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            long from = now - 60L * 24 * 3600 * 1000;
            Map<String, UsageStats> stats = usm.queryAndAggregateUsageStats(from, now);
            for (Map.Entry<String, UsageStats> s : stats.entrySet()) {
                long t = s.getValue().getLastTimeUsed();
                if (t > from && s.getValue().getTotalTimeInForeground() > 0) out.put(s.getKey(), t);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private String recency(String pkg) {
        Long t = lastUsed.get(pkg);
        if (t == null) return null;
        java.util.Calendar then = java.util.Calendar.getInstance();
        then.setTimeInMillis(t);
        java.util.Calendar today = java.util.Calendar.getInstance();
        int days = (int) ((startOfDay(today) - startOfDay(then)) / (24L * 3600 * 1000));
        if (days <= 0) return act.getString(R.string.hero_today);
        if (days == 1) return act.getString(R.string.hero_yesterday);
        return act.getString(R.string.hero_days_ago, days);
    }

    private static long startOfDay(java.util.Calendar c) {
        java.util.Calendar d = (java.util.Calendar) c.clone();
        d.set(java.util.Calendar.HOUR_OF_DAY, 0);
        d.set(java.util.Calendar.MINUTE, 0);
        d.set(java.util.Calendar.SECOND, 0);
        d.set(java.util.Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }

    private String category(String pkg) {
        try {
            ApplicationInfo ai = act.getPackageManager().getApplicationInfo(pkg, 0);
            if (pkg.equals(act.getString(R.string.live_tv_package).trim())) return act.getString(R.string.cat_tv);
            switch (ai.category) {
                case ApplicationInfo.CATEGORY_VIDEO: return act.getString(R.string.cat_video);
                case ApplicationInfo.CATEGORY_AUDIO: return act.getString(R.string.cat_audio);
                case ApplicationInfo.CATEGORY_GAME: return act.getString(R.string.cat_game);
                case ApplicationInfo.CATEGORY_IMAGE: return act.getString(R.string.cat_image);
                case ApplicationInfo.CATEGORY_NEWS: return act.getString(R.string.cat_news);
                case ApplicationInfo.CATEGORY_SOCIAL: return act.getString(R.string.cat_social);
                case ApplicationInfo.CATEGORY_MAPS: return act.getString(R.string.cat_maps);
                case ApplicationInfo.CATEGORY_PRODUCTIVITY: return act.getString(R.string.cat_productivity);
                default: break;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return act.getString(R.string.cat_video);
    }

    /** One plain line about well-known apps, like Rivulet's tagline under the title. */
    private static String tagline(String pkg) {
        switch (pkg) {
            case "com.netflix.ninja": return "Series, films, documentaries and games.";
            case "com.amazon.amazonvideo.livingroom": return "Movies, TV shows and Prime Originals.";
            case "com.google.android.youtube.tv": return "Videos, creators and live streams.";
            case "com.google.android.youtube.tvmusic": return "Songs, albums and music videos.";
            case "in.startv.hotstar": return "Live sports, movies and shows.";
            case "com.sonyliv": return "Live sports, shows and movies.";
            case "com.graymatrix.did": return "Shows, movies and originals.";
            case "com.spotify.tv.android": return "Music and podcasts.";
            case "com.disney.disneyplus": return "Disney, Pixar, Marvel and Star Wars.";
            case "com.cltv.hybrid": return "Broadcast TV channels.";
            case "com.tianci.localmedia": return "Videos, music and photos from USB.";
            default: return null;
        }
    }
}
