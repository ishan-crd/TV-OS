package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Photo screensaver: the pictures in the launcher's files/screensaver folder on the TV, shuffled, one at a
 * time with a crossfade and a slow zoom. Photos are zoomed to fill the screen (config.xml
 * screensaver_photo_fill), or with that off, wide ones fill and tall or square ones are shown whole over
 * a blurred copy of themselves. Each one is decoded at screen size in the background, and only the
 * two on screen during a crossfade are kept in memory.
 */
final class PhotoShow {

    private static final long FADE_MS = 2000;
    /** Wider than this fills the screen (losing at most a sliver top and bottom); narrower is shown whole. */
    private static final float FILL_ASPECT = 1.6f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "photos");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Random random = new Random();
    private final ImageView[] views;
    private final List<File> files;
    private final long showMs;
    private final boolean fill;
    private int next, front;
    private boolean running;

    private final Runnable advance = this::showNext;

    /** The folder photos are read from: /sdcard/Android/data/<launcher>/files/screensaver. */
    static File folder(Context c) {
        return c.getExternalFilesDir("screensaver");
    }

    /** The slideshow, or null when the folder has no pictures (the clock screensaver then shows instead). */
    static PhotoShow from(View root) {
        Context c = root.getContext();
        File dir = folder(c);
        File[] all = dir != null ? dir.listFiles() : null;
        List<File> pics = new ArrayList<>();
        if (all != null) {
            for (File f : all) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))) {
                    pics.add(f);
                }
            }
        }
        if (pics.isEmpty()) return null;
        return new PhotoShow(root, pics, c.getResources().getInteger(R.integer.screensaver_photo_seconds) * 1000L,
                c.getResources().getBoolean(R.bool.screensaver_photo_fill));
    }

    private PhotoShow(View root, List<File> files, long showMs, boolean fill) {
        this.files = files;
        this.fill = fill;
        this.showMs = Math.max(4000, showMs);
        views = new ImageView[]{root.findViewById(R.id.dreamPhotoA), root.findViewById(R.id.dreamPhotoB)};
        for (ImageView v : views) {
            v.setVisibility(View.VISIBLE);
            v.setAlpha(0f);
        }
        Collections.shuffle(files, random);
    }

    void start() {
        running = true;
        showNext();
    }

    void stop() {
        running = false;
        handler.removeCallbacks(advance);
        for (ImageView v : views) {
            v.animate().cancel();
            v.setImageDrawable(null);
        }
        io.shutdownNow();
    }

    /** Decodes the next photo in the background, then crossfades to it and waits its turn. */
    private void showNext() {
        if (!running) return;
        View parent = (View) views[0].getParent();
        int w = parent.getWidth(), h = parent.getHeight();
        if (w == 0 || h == 0) {
            parent.post(this::showNext);
            return;
        }
        File f = files.get(next);
        next = (next + 1) % files.size();
        if (next == 0) Collections.shuffle(files, random);
        try {
            io.execute(() -> {
                Bitmap b = compose(f, w, h, fill);
                handler.post(() -> {
                    if (!running) return;
                    if (b != null) crossfade(b);
                    handler.postDelayed(advance, b != null ? showMs : 0);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // stopped
        }
    }

    private void crossfade(Bitmap b) {
        ImageView out = views[front];
        front = 1 - front;
        ImageView in = views[front];
        in.animate().cancel();
        in.setImageBitmap(b);
        // The incoming photo goes over the outgoing one; the clock and its shade sit higher still (Z 2).
        in.setTranslationZ(1f);
        out.setTranslationZ(0f);
        // A slow zoom and drift across the photo's whole time on screen.
        float zoom = 1.08f;
        in.setScaleX(1f);
        in.setScaleY(1f);
        in.setTranslationX(0f);
        in.setTranslationY(0f);
        float dx = (random.nextFloat() - 0.5f) * 0.04f * in.getWidth();
        float dy = (random.nextFloat() - 0.5f) * 0.03f * in.getHeight();
        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(new LinearInterpolator()).start();
        in.animate().scaleX(zoom).scaleY(zoom).translationX(dx).translationY(dy)
                .setDuration(showMs + FADE_MS * 2).start();
        // The new photo fades in on top; once it's opaque the old one is dropped.
        handler.postDelayed(() -> {
            if (out != views[front]) {
                out.animate().cancel();
                out.setAlpha(0f);
                out.setImageDrawable(null);   // let the old bitmap go
            }
        }, FADE_MS + 50);
    }

    /** Blocking: the photo laid out on a screen-sized picture, upright, or null if it can't be read. */
    private static Bitmap compose(File f, int w, int h, boolean fill) {
        try {
            Bitmap photo = decode(f, w, h);
            if (photo == null) return null;
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            float pw = photo.getWidth(), ph = photo.getHeight();
            if (fill || pw / ph >= FILL_ASPECT) {
                // Fill: crop the sides or a sliver of the bottom, keeping faces (usually high in the frame).
                float s = Math.max(w / pw, h / ph);
                float left = (w - pw * s) / 2f, top = (h - ph * s) * 0.35f;
                c.drawBitmap(photo, null, new RectF(left, top, left + pw * s, top + ph * s), p);
            } else {
                // Whole photo, over a dimmed and blurred copy of itself.
                Bitmap blur = blur(photo);
                float bs = Math.max(w / (float) blur.getWidth(), h / (float) blur.getHeight());
                float bw = blur.getWidth() * bs, bh = blur.getHeight() * bs;
                c.drawBitmap(blur, null, new RectF((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f), p);
                blur.recycle();
                c.drawColor(0x8C000000);
                float s = Math.min(w / pw, h / ph);
                float left = (w - pw * s) / 2f, top = (h - ph * s) / 2f;
                c.drawBitmap(photo, null, new RectF(left, top, left + pw * s, top + ph * s), p);
            }
            photo.recycle();
            return out;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** At least screen size (halving while it stays bigger), turned upright from the camera's EXIF tag. */
    private static Bitmap decode(File f, int w, int h) throws java.io.IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int rot = 0;
        try {
            switch (new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                case ExifInterface.ORIENTATION_ROTATE_90: rot = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: rot = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: rot = 270; break;
                default: break;
            }
        } catch (Exception ignored) {
            // PNG and WebP without EXIF
        }
        int sw = rot % 180 == 0 ? o.outWidth : o.outHeight, sh = rot % 180 == 0 ? o.outHeight : o.outWidth;
        int sample = 1;
        while (sw / (sample * 2) >= w && sh / (sample * 2) >= h) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), d);
        if (b == null || rot == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(rot);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    /** A soft, tiny copy of the photo: shrunk to 48 px wide, box-blurred three times. */
    private static Bitmap blur(Bitmap src) {
        int bw = 48, bh = Math.max(1, Math.round(48f * src.getHeight() / src.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(src, bw, bh, true);
        Bitmap out = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
        if (out != small) small.recycle();
        int[] px = new int[bw * bh], tmp = new int[bw * bh];
        out.getPixels(px, 0, bw, 0, 0, bw, bh);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, tmp, bw, bh, 3, true);
            boxBlur(tmp, px, bw, bh, 3, false);
        }
        out.setPixels(px, 0, bw, 0, 0, bw, bh);
        return out;
    }

    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < len; i++) {
                int a = 0, rr = 0, g = 0, b = 0, n = 0;
                for (int k = Math.max(0, i - r); k <= Math.min(len - 1, i + r); k++) {
                    int c = in[horizontal ? line * w + k : k * w + line];
                    rr += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                    n++;
                }
                out[horizontal ? line * w + i : i * w + line] = 0xFF000000 | (rr / n) << 16 | (g / n) << 8 | (b / n);
            }
        }
    }
}
