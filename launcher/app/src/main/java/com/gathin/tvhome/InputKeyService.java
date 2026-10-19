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

    /** "keyCode" or "keyCode:scanCode" → target; an empty target swallows the key. */
    private final Map<String, String> appKeys = new HashMap<>();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
