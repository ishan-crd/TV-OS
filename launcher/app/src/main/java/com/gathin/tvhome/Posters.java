package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Show artwork for Continue Watching: fetched once (https) or read from the publishing app (content://),
 * kept on disk in the cache dir and as small bitmaps in memory. All IO runs off the main thread.
 */
final class Posters {

    interface Callback {
        void onLoaded(String uri, Bitmap bitmap);
    }

    /** Card size in pixels: 160x90 dp at 2x. */
    static final int CARD_W = 320, CARD_H = 180;

    private static Posters instance;

    private final Context ctx;
    private final File dir;
    private final LruCache<String, Bitmap> mem = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };
    private final Map<String, List<Callback>> pending = new HashMap<>();
    private final ExecutorService io = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "posters");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());

    static Posters get(Context c) {
        if (instance == null) instance = new Posters(c.getApplicationContext());
        return instance;
    }

    private Posters(Context ctx) {
        this.ctx = ctx;
        dir = new File(ctx.getCacheDir(), "posters");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    /** Main thread. The card-sized bitmap, from memory or loaded in the background; null on failure. */
    void load(String uri, Callback cb) {
        load(uri, CARD_W, CARD_H, cb);
    }

    /** As {@link #load(String, Callback)}, decoded to at least about w×h pixels. */
    void load(String uri, int w, int h, Callback cb) {
        if (uri == null) {
            cb.onLoaded(null, null);
            return;
        }
        final String key = w == CARD_W && h == CARD_H ? uri : uri + "@" + w + "x" + h;
        Bitmap b = mem.get(key);
        if (b != null) {
            cb.onLoaded(uri, b);
            return;
        }
        List<Callback> waiting = pending.get(key);
        if (waiting != null) {
            waiting.add(cb);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(cb);
        pending.put(key, waiting);
        io.execute(() -> {
            Bitmap loaded = decode(uri, w, h);
            main.post(() -> {
                if (loaded != null) mem.put(key, loaded);
                List<Callback> cbs = pending.remove(key);
                if (cbs != null) for (Callback c : cbs) c.onLoaded(uri, loaded);
            });
        });
    }

    /** Blocking, any thread: the image decoded to at least about this size, or null. */
    Bitmap decode(String uri, int reqW, int reqH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = open(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            try (InputStream in = open(uri)) {
                return BitmapFactory.decodeStream(in, null, d);
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private InputStream open(String uri) throws IOException {
        Uri u = Uri.parse(uri);
        String scheme = u.getScheme();
        if ("http".equals(scheme) || "https".equals(scheme)) return new FileInputStream(download(uri));
        InputStream in = ctx.getContentResolver().openInputStream(u);
        if (in == null) throw new IOException("no stream for " + uri);
        return in;
    }

    /** The disk-cached copy of a web image, downloading it the first time. */
    private File download(String uri) throws IOException {
        File f = new File(dir, sha1(uri));
        if (f.isFile() && f.length() > 0) return f;
        File tmp = new File(dir, f.getName() + ".tmp");
        HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
        trim();
        return f;
    }

    /** Keeps the disk cache under about 40 MB by dropping the oldest files. */
    private void trim() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total < 40L * 1024 * 1024) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total < 30L * 1024 * 1024) break;
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static String sha1(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
