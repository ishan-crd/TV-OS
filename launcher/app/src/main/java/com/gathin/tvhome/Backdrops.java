package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen hero artwork per app, built off the main thread and kept in a small cache.
 *
 * An image pushed to the app's files dir wins: backdrops/&lt;package&gt;.jpg (or .png/.webp), e.g.
 * {@code /sdcard/Android/data/com.gathin.tvhome/files/backdrops/com.netflix.ninja.jpg}. Otherwise the
 * app's own banner is turned into ambient light: a dark wash of its colours and a large glow in its
 * brand colour. (Banners are about 320 px wide; blown up to the full screen they only look blurry.)
 */
final class Backdrops {

    interface Callback {
        void onReady(String pkg, Bitmap bitmap);
    }

    /** Half the 1080p screen: the ImageView scales it up, banners have no more detail than this anyway. */
    private static final int W = 960, H = 540;

    private final Context ctx;
    // Six 960x540 ARGB backdrops are about 12 MB. (RGB_565 halves that but bands the glows.)
    private final LruCache<String, Bitmap> cache = new LruCache<>(6);
    private final Map<String, Callback> pending = new HashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "backdrops");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());

    Backdrops(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Main thread. Calls back immediately when cached, otherwise once built. Keyed by package. */
    void get(AppAdapter.Entry e, Callback cb) {
        final Drawable art = e.banner != null ? e.banner : e.icon;
        final Drawable copy = art == null ? null
                : art.getConstantState() != null ? art.getConstantState().newDrawable().mutate() : art;
        final String pkg = e.pkg;
        request(pkg, cb, () -> make(pkg, copy));
    }

    /**
     * A show's poster as the backdrop, Rivulet style: the artwork full screen under the scrim. Keyed by
     * "img:" + uri; the callback gets null when the image can't be loaded.
     */
    void getImage(String uri, Callback cb) {
        request("img:" + uri, cb, () -> {
            Bitmap src = Posters.get(ctx).decode(uri, W, H);
            if (src == null) return null;
            Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawColor(Color.BLACK);
            drawCover(new android.graphics.drawable.BitmapDrawable(ctx.getResources(), src), c, W, H);
            src.recycle();
            scrim(c, W, H);
            return out;
        });
    }

    private interface Maker {
        Bitmap make();
    }

    private void request(String key, Callback cb, Maker maker) {
        Bitmap b = cache.get(key);
        if (b != null) {
            cb.onReady(key, b);
            return;
        }
        boolean running = pending.containsKey(key);
        pending.put(key, cb);       // only the latest caller for a key is told
        if (running) return;
        worker.execute(() -> {
            Bitmap made;
            try {
                made = maker.make();
            } catch (Throwable t) {
                made = null;
            }
            final Bitmap result = made;
            main.post(() -> {
                if (result != null) cache.put(key, result);
                Callback c = pending.remove(key);
                if (c != null) c.onReady(key, result);
            });
        });
    }

    /** Builds an image backdrop in the background so a later getImage() is instant. */
    void preloadImage(String uri) {
        String key = "img:" + uri;
        if (cache.get(key) == null && !pending.containsKey(key)) getImage(uri, (k, b) -> { });
    }

    /** Builds it in the background so a later get() is instant. */
    void preload(AppAdapter.Entry e) {
        if (cache.get(e.pkg) == null && !pending.containsKey(e.pkg)) get(e, (p, b) -> { });
    }

    private Bitmap make(String pkg, Drawable art) {
        Bitmap b = compose(pkg, art);
        scrim(new Canvas(b), b.getWidth(), b.getHeight());
        return b;
    }

    private Bitmap compose(String pkg, Drawable art) {
        Bitmap custom = loadCustom(pkg);
        if (custom != null) return custom;

        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.BLACK);
        if (art == null) return out;
        Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);

        // Colour wash: the banner shrunk to a few pixels and smoothed back up in steps, so it reads as
        // light and colour rather than as a blown-up logo.
        Bitmap tiny = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
        drawCover(art, new Canvas(tiny), 16, 9);
        Bitmap mid = Bitmap.createScaledBitmap(tiny, 64, 36, true);
        Bitmap big = Bitmap.createScaledBitmap(mid, 240, 135, true);
        c.drawBitmap(big, null, new Rect(0, 0, W, H), filter);
        tiny.recycle();
        mid.recycle();
        big.recycle();
        c.drawColor(0xB3000000);

        // A large glow in the app's own colour, up and to the right of the text, like the light in key art.
        int vivid = AppAdapter.vividColor(art) & 0x00FFFFFF;
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setShader(new RadialGradient(W * 0.74f, H * 0.32f, W * 0.62f,
                new int[]{0xB3000000 | vivid, 0x52000000 | vivid, 0x14000000 | vivid, 0x00000000 | vivid},
                new float[]{0f, 0.38f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, glow);
        // And a softer second light low on the left, so the frame isn't one flat blob.
        glow.setShader(new RadialGradient(W * 0.18f, H * 0.95f, W * 0.45f,
                new int[]{0x33000000 | vivid, 0x00000000 | vivid}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, glow);
        return out;
    }

    /** The hero's fixed scrim, drawn into every backdrop instead of as a full-screen view over it. */
    private static void scrim(Canvas c, int w, int h) {
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, w * 0.66f, 0,
                new int[]{0xE0000000, 0xA6000000, 0x3D000000, 0x00000000},
                new float[]{0f, 0.38f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w * 0.66f, h, p);
        p.setShader(new LinearGradient(0, h * 0.45f, 0, h,
                new int[]{0x00000000, 0x8C000000, 0xEB000000, 0xFF000000},
                new float[]{0f, 0.45f, 0.8f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.45f, w, h, p);
        p.setShader(new LinearGradient(0, 0, 0, h * 0.2f, 0x66000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.2f, p);
    }

    private Bitmap loadCustom(String pkg) {
        File dir = ctx.getExternalFilesDir("backdrops");
        if (dir == null) return null;
        for (String ext : new String[]{".jpg", ".jpeg", ".png", ".webp"}) {
            File f = new File(dir, pkg + ext);
            if (!f.isFile()) continue;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 1920) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inMutable = true;   // the scrim is drawn into it
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            if (b != null) return b;
        }
        return null;
    }

    private static void drawCover(Drawable d, Canvas c, int w, int h) {
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) {
            iw = w;
            ih = h;
        }
        float s = Math.max(w / (float) iw, h / (float) ih);
        int dw = Math.round(iw * s), dh = Math.round(ih * s);
        int x = (w - dw) / 2, y = (h - dh) / 2;
        Rect saved = new Rect(d.getBounds());
        d.setBounds(x, y, x + dw, y + dh);
        d.draw(c);
        d.setBounds(saved);
    }
}
