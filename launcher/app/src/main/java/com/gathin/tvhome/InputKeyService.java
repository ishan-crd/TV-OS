package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps the remote's Input button to the system input switcher, from any app. Remotes such as the Chromecast voice
 * remote send KEYCODE_TV for it; on Google TV the stock launcher handles that key, so with the stock launcher
 * disabled the button does nothing.
 *
 * Also takes over the remote's app buttons listed in config.xml (remote_app_keys) before the TV's own global-key
 * handler sees them. On the Philips/Skyworth eleonas that handler (com.smartdevice.global.service) queries the
 * stock launcher's content provider without a null check and crashes when the stock launcher is disabled; five
 * crashes in a row make Android's RescueParty reset settings, and further ones escalate towards a reboot.
 * Every other key passes through untouched.
 * Opt-in: INPUT_KEY_SERVICE=1 build-and-install.sh (or -InputKeyService on Windows) enables it.
 */
public class InputKeyService extends AccessibilityService {

    private static final String TAG = "InputKeyService";
    private static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";

    /** The running service, for the quick panel; null when it's off. */
    private static InputKeyService instance;
    /** A Settings entry to select once Settings is on screen (see openSettingsEntry), and until when to try. */
    private String pendingEntry;
    private long pendingUntil;
    private int pendingScrolls;
    private boolean pendingExact;

    // Force stop, one app at a time: its info page, "Force stop", then "OK" in the confirmation.
    private final java.util.ArrayDeque<String> stopQueue = new java.util.ArrayDeque<>();
    private String stopping;
    /** 0: waiting for the app's page, 1: waiting for the confirmation, 2: confirmed. */
    private int stopStep;
    private long stopDeadline;
    private Runnable stopsDone;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable stopWatchdog = new Runnable() {
        @Override
        public void run() {
            if (stopping == null) return;
            if (System.currentTimeMillis() > stopDeadline) nextStop();   // stuck: skip this app
            else handler.postDelayed(this, 500);
        }
    };

    /** "keyCode" or "keyCode:scanCode" → target; an empty target swallows the key. */
    private final Map<String, String> appKeys = new HashMap<>();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        appKeys.clear();
        for (String item : getResources().getStringArray(R.array.remote_app_keys)) {
            int eq = item.indexOf('=');
            String name = (eq < 0 ? item : item.substring(0, eq)).trim();
            String scan = null;
            int colon = name.indexOf(':');
            if (colon >= 0) {
                scan = name.substring(colon + 1).trim();
                name = name.substring(0, colon).trim();
            }
            int code = KeyEvent.keyCodeFromString(name);
            if (code == KeyEvent.KEYCODE_UNKNOWN) continue;
            appKeys.put(scan == null ? String.valueOf(code) : code + ":" + scan,
                    eq < 0 ? "" : item.substring(eq + 1).trim());
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_TV) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                open(new Intent(ACTION_VIEW_INPUTS));
            }
            return true;
        }
        // Some remotes send one key code for several buttons (Skyworth: ENDCALL for Input and for the
        // custom button), told apart by scan code; a code:scan entry wins over a plain code.
        String target = appKeys.get(code + ":" + event.getScanCode());
        if (target == null) target = appKeys.get(String.valueOf(code));
        if (target == null) {
            if (event.getAction() == KeyEvent.ACTION_UP && Log.isLoggable(TAG, Log.DEBUG)) {
                // adb shell setprop log.tag.InputKeyService DEBUG: shows what each remote button sends.
                Log.d(TAG, "key " + KeyEvent.keyCodeToString(code) + " scancode=" + event.getScanCode());
            }
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            Log.i(TAG, "app key " + KeyEvent.keyCodeToString(code) + " scancode=" + event.getScanCode()
                    + " -> " + (target.isEmpty() ? "(none)" : target));
            if (target.equals("@inputs")) {
                open(new Intent(ACTION_VIEW_INPUTS));
            } else if (target.equals("@running")) {
                open(new Intent(this, RunningAppsActivity.class));
            } else if (target.equals("@panel")) {
                open(new Intent(this, QuickPanelActivity.class));
            } else if (target.equals("@settings")) {
                open(new Intent(android.provider.Settings.ACTION_SETTINGS));
            } else if (target.equals("@home")) {
                open(new Intent(this, MainActivity.class));
            } else if (!target.isEmpty()) {
                Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(target);
                if (i == null) i = getPackageManager().getLaunchIntentForPackage(target);
                if (i != null) open(i.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            }
        }
        return true;
    }

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    /**
     * Opens Android TV Settings and selects the entry whose title starts with `title` (e.g. "Remotes"),
     * for pages Settings won't open for other apps. Events are only delivered for the Settings package
     * (input_key_service.xml), so this costs nothing elsewhere. False when the service isn't running.
     */
    static boolean openSettingsEntry(android.content.Context c, String title) {
        return openSettingsEntry(new Intent(android.provider.Settings.ACTION_SETTINGS), title, false);
    }

    /**
     * Opens a Settings screen and selects the entry titled `title` there (exactly, or by prefix), e.g. a
     * Wi-Fi network in the network list.
     */
    static boolean openSettingsEntry(Intent screen, String title, boolean exact) {
        InputKeyService s = instance;
        if (s == null) return false;
        s.pendingExact = exact;
        s.pendingEntry = title.toLowerCase(java.util.Locale.ROOT);
        s.pendingUntil = System.currentTimeMillis() + 5000;
        s.pendingScrolls = 0;
        s.open(screen.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK));
        return true;
    }

    /**
     * Force-stops these apps through their Settings pages (the only way for an app that isn't part of the
     * system), then runs `done`. False when the service isn't running.
     */
    static boolean forceStop(java.util.List<String> pkgs, Runnable done) {
        InputKeyService s = instance;
        if (s == null) return false;
        s.stopQueue.clear();
        s.stopQueue.addAll(pkgs);
        s.stopsDone = done;
        s.nextStop();
        return true;
    }

    private void nextStop() {
        stopping = stopQueue.poll();
        if (stopping == null) {
            handler.removeCallbacks(stopWatchdog);
            Runnable done = stopsDone;
            stopsDone = null;
            if (done != null) done.run();
            return;
        }
        stopStep = 0;
        stopDeadline = System.currentTimeMillis() + 5000;
        open(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + stopping)).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK));
        handler.removeCallbacks(stopWatchdog);
        handler.postDelayed(stopWatchdog, 500);
    }

    private void stepStop(android.view.accessibility.AccessibilityNodeInfo root) {
        if (stopStep == 0) {
            if (findExact(root, "OK") != null) return;   // a confirmation from before is still closing
            android.view.accessibility.AccessibilityNodeInfo n = clickable(findExact(root, "Force stop"));
            if (n == null) {
                // The page is up (it has "Open") but no Force stop: the app isn't running. Next one.
                if (findExact(root, "Open") != null && findExact(root, "Uninstall") != null
                        || findExact(root, "Open") != null && findExact(root, "Storage used") != null) nextStop();
                return;
            }
            if (!n.isEnabled()) {   // already stopped
                nextStop();
                return;
            }
            stopStep = 1;
            n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
        } else if (stopStep == 1) {
            android.view.accessibility.AccessibilityNodeInfo ok = clickable(findExact(root, "OK"));
            if (ok == null) return;
            stopStep = 2;
            ok.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
            handler.postDelayed(this::nextStop, 350);
        }
    }

    private static android.view.accessibility.AccessibilityNodeInfo clickable(android.view.accessibility.AccessibilityNodeInfo n) {
        while (n != null && !n.isClickable()) n = n.getParent();
        return n;
    }

    private static android.view.accessibility.AccessibilityNodeInfo findExact(
            android.view.accessibility.AccessibilityNodeInfo node, String text) {
        if (node == null) return null;
        CharSequence t = node.getText();
        if (t != null && t.toString().trim().equalsIgnoreCase(text)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo hit = findExact(node.getChild(i), text);
            if (hit != null) return hit;
        }
        return null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (stopping != null) {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) stepStop(root);
            return;
        }
        if (pendingEntry == null) return;
        if (System.currentTimeMillis() > pendingUntil) {
            pendingEntry = null;
            return;
        }
        android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        android.view.accessibility.AccessibilityNodeInfo hit = pendingExact
                ? findExact(root, pendingEntry) : find(root, pendingEntry);
        if (hit == null) {
            // Not laid out yet: the entry is further down the list. Scroll it a page at a time.
            android.view.accessibility.AccessibilityNodeInfo list = scrollable(root);
            if (list != null && pendingScrolls < 6) {
                pendingScrolls++;
                list.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            }
            return;
        }
        android.view.accessibility.AccessibilityNodeInfo n = hit;
        while (n != null && !n.isClickable()) n = n.getParent();
        if (n != null) {
            n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS);
            n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
            pendingEntry = null;
        }
    }

    private static android.view.accessibility.AccessibilityNodeInfo scrollable(
            android.view.accessibility.AccessibilityNodeInfo node) {
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            android.view.accessibility.AccessibilityNodeInfo hit = scrollable(child);
            if (hit != null) return hit;
        }
        return null;
    }

    private static android.view.accessibility.AccessibilityNodeInfo find(
            android.view.accessibility.AccessibilityNodeInfo node, String prefix) {
        CharSequence t = node.getText();
        if (t != null && t.toString().toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            android.view.accessibility.AccessibilityNodeInfo hit = find(child, prefix);
            if (hit != null) return hit;
        }
        return null;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }
}
