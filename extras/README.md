# Extras

Features that are built and tested but kept out of the launcher for now.

## movie-hero.patch: cinema-style home with TMDB details

A featured view for the films and series your apps report as in progress (Android TV's Watch Next),
with details from TMDB: full-screen backdrop, poster, glass genre chips, facts, plot, Watch movie /
Add to wishlist, rating and a director-and-cast card, plus Similar movies and Wishlist rows.

Left out because it roughly doubles the launcher's memory (about 127 MB instead of 65 MB, from the
larger artwork) and costs some smoothness (median frame 30 ms instead of 24 ms) on a 1.8 GB TV.

To bring it back:

```bash
git apply extras/movie-hero.patch        # or: git cherry-pick eda2623 (branch movie-hero)
echo "tmdb.key=<your TMDB API key>" >> launcher/local.properties
```

Get a free key at themoviedb.org → Settings → API. The key stays out of git (local.properties).
