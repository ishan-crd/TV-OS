package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.List;

/**
 * Long-press menu on a hero card: edit Your Apps and the featured carousel from the TV (saved in
 * {@link Prefs}), resume a show, or open the app's info page.
 */
final class CardMenu {

    interface OnChanged {
        /** The lists changed; keep focus on the card with this key. */
        void changed(String focusKey);
    }

    private CardMenu() {
    }

    static void show(Activity act, View card, RowAdapter.Item it, OnChanged onChanged) {
        AppAdapter.Entry app = it.app;
        float density = act.getResources().getDisplayMetrics().density;
        ViewGroup root = act.findViewById(R.id.root);
        View panel = act.getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = new PopupWindow(panel, Math.round(240 * density),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);

        List<String> your = Prefs.list(act, Prefs.YOUR_APPS);
        List<String> featured = Prefs.list(act, Prefs.FEATURED);
        String appKey = app.component.flattenToShortString();

        if (it.show != null) {
            add(act, rows, popup, R.drawable.ic_play, act.getString(R.string.menu_resume, it.title),
                    () -> WatchNext.open(act, it.show, app));
        }
        add(act, rows, popup, R.drawable.ic_play, act.getString(R.string.menu_open, app.label),
                () -> AppAdapter.launch(act, app));

        int at = your.indexOf(app.pkg);
        if (at < 0) {
            add(act, rows, popup, R.drawable.ic_home, act.getString(R.string.menu_add_your_apps), () -> {
                your.add(app.pkg);
                Prefs.save(act, Prefs.YOUR_APPS, your);
                onChanged.changed(appKey);
            });
        } else {
            if (at > 0) {
                add(act, rows, popup, R.drawable.ic_chevron_right, act.getString(R.string.menu_move_left), () -> {
                    your.add(at - 1, your.remove(at));
                    Prefs.save(act, Prefs.YOUR_APPS, your);
                    onChanged.changed(appKey);
                }).findViewById(R.id.icon).setRotation(180f);
            }
            if (at < your.size() - 1) {
                add(act, rows, popup, R.drawable.ic_chevron_right, act.getString(R.string.menu_move_right), () -> {
                    your.add(at + 1, your.remove(at));
                    Prefs.save(act, Prefs.YOUR_APPS, your);
                    onChanged.changed(appKey);
                });
            }
            add(act, rows, popup, R.drawable.ic_home, act.getString(R.string.menu_remove_your_apps), () -> {
                your.remove(at);
                Prefs.save(act, Prefs.YOUR_APPS, your);
                onChanged.changed(appKey);
            });
        }

        if (!featured.contains(app.pkg)) {
            add(act, rows, popup, R.drawable.ic_info_outline, act.getString(R.string.menu_add_featured), () -> {
                featured.add(app.pkg);
                Prefs.save(act, Prefs.FEATURED, featured);
                onChanged.changed(it.key);
            });
        } else if (featured.size() > 1) {
            add(act, rows, popup, R.drawable.ic_info_outline, act.getString(R.string.menu_remove_featured), () -> {
                featured.remove(app.pkg);
                Prefs.save(act, Prefs.FEATURED, featured);
                onChanged.changed(it.key);
            });
        }
        add(act, rows, popup, R.drawable.ic_settings, act.getString(R.string.hero_info),
                () -> AppAdapter.openAppInfo(act, app));

        // Under the card, or above it when there isn't room below.
        panel.measure(View.MeasureSpec.makeMeasureSpec(popup.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        Rect r = new Rect();
        card.getGlobalVisibleRect(r);
        int gap = Math.round(12 * density);
        int y = r.bottom + gap;
        if (y + panel.getMeasuredHeight() > root.getHeight()) y = Math.max(gap, r.top - gap - panel.getMeasuredHeight());
        panel.setAlpha(0f);
        panel.setScaleY(0.94f);
        popup.showAtLocation(root, Gravity.NO_GRAVITY, r.left, y);
        panel.animate().alpha(1f).scaleY(1f).setDuration(180).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private static View add(Activity act, LinearLayout rows, PopupWindow popup, int icon, String title, Runnable action) {
        View row = act.getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        TextView t = row.findViewById(R.id.title);
        t.setText(title);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
        return row;
    }
}
