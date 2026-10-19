package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.Random;

/**
 * The clock screensaver's picture (layout dream_clock): a thin clock and date over a slow ambient glow.
 * Every 30 seconds the clock drifts to a new spot and the glow shifts colour, so nothing burns in.
 * With photos on the TV ({@link PhotoShow}) they play instead of the glow, and a smaller clock swaps
 * between the bottom corners.
 * Shared by the system screensaver ({@link ClockDream}) and "Screensaver" in the quick panel ({@link ClockActivity}).
 */
final class ClockScene {

    private static final long MOVE_MS = 30_000L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};
    /** The clock's size over photos, and its distance from the screen edges. */
    private static final float PHOTO_CLOCK_SCALE = 0.5f;
    private static final int PHOTO_CLOCK_MARGIN_DP = 56;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final View content;
    private final GradientDrawable glow = new GradientDrawable();
    private int colorIndex;
    private int glowColor;
    private ValueAnimator glowAnim;
    private final PhotoShow photos;
    private boolean clockRight;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            if (photos == null) shiftGlow();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    /** root: the inflated dream_clock layout. */
    ClockScene(View root) {
        content = root.findViewById(R.id.dreamContent);
        View ambient = root.findViewById(R.id.dreamAmbient);
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
        photos = PhotoShow.from(root);
        if (photos != null) {
            ambient.setVisibility(View.GONE);
            root.findViewById(R.id.dreamScrim).setVisibility(View.VISIBLE);
            content.setPivotX(0f);
            content.setPivotY(0f);
            content.setScaleX(PHOTO_CLOCK_SCALE);
            content.setScaleY(PHOTO_CLOCK_SCALE);
            clockRight = random.nextBoolean();
        }
    }

    void start() {
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, MOVE_MS);
        if (photos != null) photos.start();
    }

    void stop() {
        handler.removeCallbacks(tick);
        if (photos != null) photos.stop();
        if (glowAnim != null) glowAnim.cancel();
        content.animate().cancel();
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        }).start();
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private void placeClock() {
        View parent = (View) content.getParent();
        if (photos != null) {
            // Over photos: bottom left or bottom right, swapping each time.
            float m = PHOTO_CLOCK_MARGIN_DP * parent.getResources().getDisplayMetrics().density;
            float w = content.getWidth() * PHOTO_CLOCK_SCALE, h = content.getHeight() * PHOTO_CLOCK_SCALE;
            content.setX(clockRight ? parent.getWidth() - m - w : m);
            content.setY(parent.getHeight() - m - h);
            clockRight = !clockRight;
            return;
        }
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    private void shiftGlow() {
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), from, PALETTE[colorIndex]);
        glowAnim.setDuration(8000);
        glowAnim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
