package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Quick settings panel, opened by the remote's Settings button (InputKeyService, target @panel) over
 * whatever is on screen, like Google TV's: date and time, All settings, and tiles for Screensaver, Inputs,
 * Picture, Sound, Wi-Fi, Bluetooth, Display mode and Accessibility. Each tile opens the TV's own screen
 * for it. A translucent activity in its own task: nothing runs while it's closed, and it is gone after use.
 */
public class QuickPanelActivity extends Activity {

    private interface Action {
        void run();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quick_panel);
        View gear = findViewById(R.id.qpAllSettings);
        View gearLabel = findViewById(R.id.qpAllSettingsLabel);
        gear.setOnClickListener(v -> go(new Intent(Settings.ACTION_SETTINGS)));
        gear.setOnFocusChangeListener((v, f) -> gearLabel.setVisibility(f ? View.VISIBLE : View.INVISIBLE));

        LinearLayout tiles = findViewById(R.id.qpTiles);
        LinearLayout row = null;
        // Screen mirroring: a separate receiver app (config.xml mirroring_app), shown only when installed.
        String mirrorPkg = getString(R.string.mirroring_app).trim();
        Intent mirror = mirrorPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(mirrorPkg);
        if (mirror == null && !mirrorPkg.isEmpty()) mirror = getPackageManager().getLaunchIntentForPackage(mirrorPkg);
        final Intent mirrorIntent = mirror;
        java.util.List<Object[]> all = new java.util.ArrayList<>(java.util.Arrays.asList(new Object[][]{
                {R.drawable.ic_qp_screensaver, R.string.qp_screensaver, null,
                        (Action) () -> go(new Intent(this, ClockActivity.class))},
                {R.drawable.ic_input, R.string.inputs, null,
                        (Action) () -> go(new Intent("com.android.tv.action.VIEW_INPUTS"))},
                {R.drawable.ic_qp_picture, R.string.qp_picture, null,
                        (Action) () -> go(new Intent(Settings.ACTION_DISPLAY_SETTINGS))},
                {R.drawable.ic_qp_sound, R.string.qp_sound, null,
                        (Action) () -> go(new Intent("com.android.tv.action.VIEW_SOUND_SETTINGS"),
                                new Intent(Settings.ACTION_SOUND_SETTINGS))},
                {R.drawable.ic_wifi, R.string.wifi, networkName(),
                        (Action) () -> go(new Intent(Settings.ACTION_WIFI_SETTINGS))},
                {R.drawable.ic_qp_bluetooth, R.string.qp_bluetooth, null,
                        // Android TV's own "Remotes & Accessories" page (Bluetooth on/off, pair, devices), opened
                        // directly as Google TV's panel does. Settings only shows it to holders of
                        // WRITE_SECURE_SETTINGS (granted over adb at install); without it, the remote-key service
                        // selects the entry inside Settings instead.
                        (Action) () -> {
                            if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                go(new Intent("android.settings.SLICE_SETTINGS").putExtra("slice_uri",
                                        "content://com.android.tv.settings.accessories.sliceprovider/general"));
                            } else if (InputKeyService.openSettingsEntry(this, "Remotes")) {
                                finish();
                            } else {
                                go(new Intent(Settings.ACTION_SETTINGS));
                            }
                        }},
                {R.drawable.ic_qp_display, R.string.qp_display_mode, null,
                        (Action) () -> go(new Intent("android.settings.oem.DISPLAYMODE_SETTINGS"),
                                new Intent(Settings.ACTION_DISPLAY_SETTINGS))},
                {R.drawable.ic_qp_accessibility, R.string.qp_accessibility, null,
                        (Action) () -> go(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))},
                {R.drawable.ic_qp_apps, R.string.qp_running_apps, null,
                        (Action) () -> go(new Intent(this, RunningAppsActivity.class))},
        }));
        if (mirrorIntent != null) {
            all.add(all.size() - 1, new Object[]{R.drawable.ic_qp_mirror, R.string.qp_mirroring, null,
                    (Action) () -> go(mirrorIntent)});
        }
        Object[][] items = all.toArray(new Object[0][]);
        int gap = Math.round(8 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < items.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) lp.topMargin = gap;
                tiles.addView(row, lp);
            }
            View tile = getLayoutInflater().inflate(R.layout.item_qp_tile, row, false);
            ((ImageView) tile.findViewById(R.id.qpIcon)).setImageResource((int) items[i][0]);
            ((TextView) tile.findViewById(R.id.qpLabel)).setText((int) items[i][1]);
            if (items[i][2] != null) {
                TextView sub = tile.findViewById(R.id.qpSub);
                sub.setText((String) items[i][2]);
                sub.setVisibility(View.VISIBLE);
            }
            final Action action = (Action) items[i][3];
            tile.setOnClickListener(v -> action.run());
            if (i % 2 == 1) ((LinearLayout.LayoutParams) tile.getLayoutParams()).setMarginStart(gap);
            // An odd last tile (Running apps) is alone in its row, so it spans it.
            // The top row's Up goes to All settings; nothing else leaves the panel.
            if (i < 2) tile.setNextFocusUpId(R.id.qpAllSettings);
            tile.setId(View.generateViewId());
            row.addView(tile);
            if (i == 0) tile.requestFocus();
        }
        gear.setNextFocusDownId(((LinearLayout) tiles.getChildAt(0)).getChildAt(1).getId());
        overridePendingTransition(R.anim.qp_in, 0);
    }

    /** The Settings button again closes the panel (it arrives here because the panel is singleTask). */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Don't let focus leave the panel sideways or downwards into nothing.
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
        // Gone as soon as it's left (a tile opened something, Home, another app): no panel left behind.
        if (!isFinishing()) finish();
    }

    /**
     * Opens the first intent the TV can handle, then closes the panel. A fresh task each time, so Back
     * goes straight home instead of through every Settings page opened before.
     */
    private void go(Intent... intents) {
        for (Intent i : intents) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            try {
                startActivity(i);
                finish();
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
    }

    /** The Wi-Fi network's name, "Ethernet", or null when offline. */
    private String networkName() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            if (caps == null) return getString(R.string.offline);
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return getString(R.string.ethernet);
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm.getConnectionInfo();
            String ssid = info == null ? null : info.getSSID();
            if (ssid == null) return null;
            ssid = ssid.replace("\"", "").trim();
            return ssid.isEmpty() || ssid.contains("unknown") ? null : ssid;
        } catch (Exception e) {
            return null;
        }
    }
}
