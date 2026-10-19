package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Running apps" from the quick panel: the apps you opened since the TV started (which Android may still be
 * holding in memory), how much memory is in use, ✕ per app and Clear all. Closing is a real force stop,
 * done by the remote-key service through each app's Settings page, the only way for an app that isn't part
 * of the system; without that service it falls back to killBackgroundProcesses (idle apps only).
 */
public class RunningAppsActivity extends Activity {

    /** When each app was last closed here, so it drops off the list until it's opened again. */
    private static final Map<String, Long> closedAt = new HashMap<>();

    /** Free memory before a Clear all, passed back when the panel reopens afterwards. */
    private static final String EXTRA_MEM_BEFORE = "mem_before";

    private ActivityManager am;
    private LinearLayout list;
    private TextView memText, empty;
    private View memFill, clearAll;
    private long memBeforeClear = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_running_apps);
        am = getSystemService(ActivityManager.class);
        list = findViewById(R.id.raList);
        memText = findViewById(R.id.raMemText);
        memFill = findViewById(R.id.raMemFill);
        empty = findViewById(R.id.raEmpty);
        clearAll = findViewById(R.id.raClearAll);
        clearAll.setOnClickListener(v -> clearAll());
        memBeforeClear = getIntent().getLongExtra(EXTRA_MEM_BEFORE, -1);
        overridePendingTransition(R.anim.qp_in, 0);
        refresh();
        clearAll.requestFocus();
    }

    private void refresh() {
        list.removeAllViews();
        List<AppAdapter.Entry> running = runningApps();
        empty.setVisibility(running.isEmpty() ? View.VISIBLE : View.GONE);
        clearAll.setEnabled(!running.isEmpty());
        clearAll.setAlpha(running.isEmpty() ? 0.45f : 1f);
        for (AppAdapter.Entry e : running) addRow(e);
        // At most five rows on screen; more scroll inside the panel.
        View scroll = (View) list.getParent();
        android.view.ViewGroup.LayoutParams lp = scroll.getLayoutParams();
        lp.height = running.size() > 5 ? Math.round(5 * 60 * getResources().getDisplayMetrics().density)
                : android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        scroll.setLayoutParams(lp);
        list.post(this::showMemory);
        // Memory comes back over a second or two as stopped apps exit: measure again.
        if (memBeforeClear >= 0) list.postDelayed(this::showMemory, 1500);
    }

    /** Launchable apps used since the TV started and not closed here since, most recent first. */
    private List<AppAdapter.Entry> runningApps() {
        List<AppAdapter.Entry> out = new ArrayList<>();
        long boot = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        Map<String, Long> last = new HashMap<>();
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            for (Map.Entry<String, UsageStats> s : usm.queryAndAggregateUsageStats(boot, System.currentTimeMillis()).entrySet()) {
                long t = s.getValue().getLastTimeUsed();
                Long closed = closedAt.get(s.getKey());
                if (t > boot && (closed == null || t > closed)) last.put(s.getKey(), t);
            }
        } catch (Exception ignored) {
        }
        for (AppAdapter.Entry e : AppAdapter.loadApps(this)) {
            if (last.containsKey(e.pkg) && !e.pkg.equals(getPackageName()) && isUserApp(e.pkg)) out.add(e);
        }
        out.sort((a, b) -> Long.compare(last.get(b.pkg), last.get(a.pkg)));
        for (AppAdapter.Entry e : out) ago.put(e.pkg, last.get(e.pkg));
        return out;
    }

    private final Map<String, Long> ago = new HashMap<>();

    private void addRow(AppAdapter.Entry e) {
        View row = getLayoutInflater().inflate(R.layout.item_running_app, list, false);
        ((ImageView) row.findViewById(R.id.raIcon)).setImageDrawable(e.icon);
        ((TextView) row.findViewById(R.id.raName)).setText(e.label);
        Long t = ago.get(e.pkg);
        ((TextView) row.findViewById(R.id.raWhen)).setText(t == null ? "" :
                DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
        View open = row.findViewById(R.id.raOpen);
        View close = row.findViewById(R.id.raClose);
        open.setOnClickListener(v -> {
            AppAdapter.launch(this, e);
            finish();
        });
        close.setOnClickListener(v -> stop(java.util.Collections.singletonList(e.pkg)));
        list.addView(row);
    }

    /** Marks apps closed and force-stops them; the panel comes back with the memory freed when done. */
    private void stop(List<String> pkgs) {
        long before = availableMem();
        for (String p : pkgs) closedAt.put(p, System.currentTimeMillis());
        // Afterwards: back to the home screen (closing the last Settings page), then this panel again.
        Context app = getApplicationContext();
        Runnable back = () -> {
            app.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() ->
                    app.startActivity(new Intent(app, RunningAppsActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_MEM_BEFORE, before)), 300);
        };
        if (InputKeyService.forceStop(pkgs, back)) {
            finish();
        } else {
            // Without the remote-key service only idle apps can be closed.
            for (String p : pkgs) am.killBackgroundProcesses(p);
            memBeforeClear = before;
            refresh();
            list.postDelayed(this::showMemory, 800);
        }
    }

    private void clearAll() {
        List<String> pkgs = new ArrayList<>();
        for (AppAdapter.Entry e : runningApps()) pkgs.add(e.pkg);
        if (!pkgs.isEmpty()) stop(pkgs);
    }

    /**
     * Apps you use: installed by you, or preinstalled and updated through Play (Netflix, YouTube...). The TV's
     * own system parts (Settings, the maker's services) are left out: the TV runs on their background services.
     */
    private boolean isUserApp(String pkg) {
        try {
            int f = getPackageManager().getApplicationInfo(pkg, 0).flags;
            return (f & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
                    || (f & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    private long availableMem() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem;
    }

    private void showMemory() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long used = mi.totalMem - mi.availMem;
        String text = getString(R.string.ra_memory, gb(used), gb(mi.totalMem));
        long freed = memBeforeClear >= 0 ? (mi.availMem - memBeforeClear) / (1024 * 1024) : 0;
        if (freed > 0) text = getString(R.string.ra_freed, freed) + " · " + text;
        memText.setText(text);
        View track = (View) memFill.getParent();
        android.view.ViewGroup.LayoutParams lp = memFill.getLayoutParams();
        lp.width = Math.round(track.getWidth() * (used / (float) mi.totalMem));
        memFill.setLayoutParams(lp);
    }

    private static String gb(long bytes) {
        return String.format(java.util.Locale.getDefault(), "%.1f GB", bytes / (1024f * 1024f * 1024f));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            View f = getCurrentFocus();
            int code = e.getKeyCode();
            if (f != null && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP)) {
                int dir = code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : code == KeyEvent.KEYCODE_DPAD_RIGHT
                        ? View.FOCUS_RIGHT : code == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN : View.FOCUS_UP;
                if (f.focusSearch(dir) == null) return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, R.anim.qp_out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!isFinishing()) finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }
}
