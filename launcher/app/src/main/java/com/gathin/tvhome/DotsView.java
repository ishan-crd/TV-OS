package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Page dots in a dark capsule; the current page is a wider white pill that slides to its place. */
public class DotsView extends View {

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d, gap, wide, pad, h;
    private int count;
    /** Animated position of the wide dot, in pages. */
    private float pos;
    private ValueAnimator anim;

    public DotsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float dp = getResources().getDisplayMetrics().density;
        d = 4.5f * dp;
        gap = 4.5f * dp;
        wide = 11f * dp;
        pad = 7f * dp;
        h = 12f * dp;
        bg.setColor(0x66000000);
    }

    void setCount(int n) {
        if (n == count) return;
        count = n;
        requestLayout();
        invalidate();
    }

    void setActive(int i, boolean animate) {
        if (anim != null) anim.cancel();
        if (!animate) {
            pos = i;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(pos, i).setDuration(280);
        anim.setInterpolator(new DecelerateInterpolator(1.5f));
        anim.addUpdateListener(a -> {
            pos = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int n = Math.max(count, 1);
        float w = pad * 2 + (n - 1) * (d + gap) + wide;
        setMeasuredDimension(Math.round(w), Math.round(h));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (count <= 1) return;
        r.set(0, 0, getWidth(), getHeight());
        c.drawRoundRect(r, h / 2, h / 2, bg);
        float x = pad, cy = h / 2;
        for (int i = 0; i < count; i++) {
            // How "current" this dot is: 1 on the active page, blending while the pill slides.
            float k = Math.max(0f, 1f - Math.abs(pos - i));
            float w = d + (wide - d) * k;
            dot.setColor(blend(0x80FFFFFF, 0xFFFFFFFF, k));
            r.set(x, cy - d / 2, x + w, cy + d / 2);
            c.drawRoundRect(r, d / 2, d / 2, dot);
            x += w + gap;
        }
    }

    private static int blend(int a, int b, float t) {
        int aa = a >>> 24, ba = b >>> 24;
        int alpha = Math.round(aa + (ba - aa) * t);
        return (alpha << 24) | (b & 0x00FFFFFF);
    }
}
