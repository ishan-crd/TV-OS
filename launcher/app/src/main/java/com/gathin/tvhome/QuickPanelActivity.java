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
        // AirPlay: a separate receiver app (config.xml mirroring_app). Without it, the tile explains where to get it.
        String mirrorPkg = getString(R.string.mirroring_app).trim();
        Intent mirror = mirrorPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(mirrorPkg);
        if (mirror == null && !mirrorPkg.isEmpty()) mirror = getPackageManager().getLaunchIntentForPackage(mirrorPkg);
        final Intent mirrorIntent = mirror;
        java.util.List<Object[]> all = new java.util.ArrayList<>(java.util.Arrays.asList(new Object[][]{
                {R.drawable.ic_wifi, R.string.wifi, networkName(), WIFI_LIST_IN_PANEL ? (Action) this::showWifi
                        : (Action) () -> go(new Intent(Settings.ACTION_WIFI_SETTINGS))},
                {R.drawable.ic_qp_bluetooth, R.string.qp_bluetooth, null,
                        // Android TV's own "Remotes & Accessories" page (Bluetooth on/off, pair, devices), opened
                        // directly as Google TV's panel does. Settings only shows it to holders of
                        // WRITE_SECURE_SETTINGS (granted over adb at install); without it, the remote-key service
                        // selects the entry inside Settings instead.
                        (Action) () -> {
                            openBluetooth(this);
                            finish();
                        }},
                {R.drawable.ic_qp_picture, R.string.qp_picture, null,
                        (Action) () -> go(new Intent(Settings.ACTION_DISPLAY_SETTINGS))},
                {R.drawable.ic_qp_sound, R.string.qp_sound, null,
                        (Action) () -> go(new Intent("com.android.tv.action.VIEW_SOUND_SETTINGS"),
                                new Intent(Settings.ACTION_SOUND_SETTINGS))},
                {R.drawable.ic_qp_screensaver, R.string.qp_screensaver, null,
                        (Action) () -> go(new Intent(this, ClockActivity.class))},
                {R.drawable.ic_input, R.string.inputs, null,
                        (Action) () -> go(new Intent("com.android.tv.action.VIEW_INPUTS"))},
                {R.drawable.ic_qp_display, R.string.qp_display_mode, null,
                        (Action) () -> go(new Intent("android.settings.oem.DISPLAYMODE_SETTINGS"),
                                new Intent(Settings.ACTION_DISPLAY_SETTINGS))},
                {R.drawable.ic_qp_accessibility, R.string.qp_accessibility, null,
                        (Action) () -> go(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))},
                {R.drawable.ic_qp_apps, R.string.qp_running_apps, null,
                        (Action) () -> go(new Intent(this, RunningAppsActivity.class))},
        }));
        if (mirrorIntent != null) {
            // With the receiver's control permission (our iMirror build), the tile switches AirPlay on and
            // off without opening the app; otherwise it opens the app.
            Action mirrorAction = AirPlay.controllable(this) ? (Action) this::toggleAirPlay : () -> go(mirrorIntent);
            all.add(all.size() - 1, new Object[]{R.drawable.ic_qp_mirror, R.string.qp_mirroring, "", mirrorAction});
        } else if (!mirrorPkg.isEmpty()) {
            all.add(all.size() - 1, new Object[]{R.drawable.ic_qp_mirror, R.string.qp_mirroring,
                    getString(R.string.airplay_get), (Action) this::showAirPlayInfo});
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
            if ((int) items[i][1] == R.string.qp_mirroring) airPlayTile = tile;
            if ((int) items[i][1] == R.string.wifi) wifiTile = tile;
            if ((int) items[i][1] == R.string.qp_mirroring && mirrorIntent != null) {
                airPlaySub = tile.findViewById(R.id.qpSub);
                // Long press: the receiver's own settings (name, PIN).
                tile.setOnLongClickListener(v -> {
                    go(mirrorIntent);
                    return true;
                });
                if (AirPlay.controllable(this)) AirPlay.status(this, this::showAirPlay);
                else airPlaySub.setVisibility(View.GONE);
            }
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

    private TextView airPlaySub;
    /**
     * Off for now: Wi-Fi opens the system Wi-Fi page, as the home's Wi-Fi icon does. Set to true for the
     * network list inside the panel (showWifi below), like Google TV's.
     */
    private static final boolean WIFI_LIST_IN_PANEL = false;

    private View airPlayTile, wifiTile;
    private android.content.BroadcastReceiver scanReceiver;

    // ---- Wi-Fi list ------------------------------------------------------------------------------

    /**
     * Swaps the tiles for the Wi-Fi list, as Google TV's panel did: the current network, then nearby ones
     * by signal. Android lets only system apps connect to a network, so choosing one opens its connect
     * screen in Settings (the remote-key service selects it in the list).
     */
    private void showWifi() {
        findViewById(R.id.qpTiles).setVisibility(View.GONE);
        findViewById(R.id.qpWifi).setVisibility(View.VISIBLE);
        findViewById(R.id.qpWifiAll).setOnClickListener(v -> go(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        fillWifi();
        // Fresh results arrive a few seconds after a scan; the list refreshes then (focus stays put).
        android.net.wifi.WifiManager wm = getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
        scanReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                fillWifi();
            }
        };
        registerReceiver(scanReceiver, new android.content.IntentFilter(
                android.net.wifi.WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        try {
            //noinspection deprecation
            wm.startScan();
        } catch (Exception ignored) {
        }
    }

    private void fillWifi() {
        LinearLayout list = findViewById(R.id.qpWifiList);
        View focused = getCurrentFocus();
        String focusedSsid = focused != null && focused.getTag() instanceof String ? (String) focused.getTag() : null;
        list.removeAllViews();
        android.net.wifi.WifiManager wm = getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
        String current = networkName();
        // One row per network name: its strongest access point (2.4 and 5 GHz radios share a name).
        java.util.Map<String, android.net.wifi.ScanResult> best = new java.util.LinkedHashMap<>();
        try {
            for (android.net.wifi.ScanResult r : wm.getScanResults()) {
                //noinspection deprecation
                String ssid = r.SSID;
                if (ssid == null || ssid.trim().isEmpty()) continue;
                android.net.wifi.ScanResult b = best.get(ssid);
                if (b == null || r.level > b.level) best.put(ssid, r);
            }
        } catch (SecurityException ignored) {
        }
        java.util.List<android.net.wifi.ScanResult> nets = new java.util.ArrayList<>(best.values());
        //noinspection deprecation
        nets.sort((a, b) -> a.SSID.equals(current) ? -1 : b.SSID.equals(current) ? 1 : Integer.compare(b.level, a.level));
        if (nets.size() > 12) nets = nets.subList(0, 12);
        View restore = null;
        for (android.net.wifi.ScanResult r : nets) {
            //noinspection deprecation
            final String ssid = r.SSID;
            boolean connected = ssid.equals(current);
            boolean secured = r.capabilities != null && (r.capabilities.contains("WPA")
                    || r.capabilities.contains("WEP") || r.capabilities.contains("SAE") || r.capabilities.contains("EAP"));
            View row = getLayoutInflater().inflate(R.layout.item_wifi_row, list, false);
            row.setTag(ssid);
            ((TextView) row.findViewById(R.id.wfName)).setText(ssid);
            ((TextView) row.findViewById(R.id.wfSub)).setText(connected ? R.string.wifi_connected
                    : secured ? R.string.wifi_secured : R.string.wifi_open);
            // Signal: the icon fades with distance (5 levels).
            int level = android.net.wifi.WifiManager.calculateSignalLevel(r.level, 5);
            row.findViewById(R.id.wfSignal).setAlpha(0.35f + 0.65f * level / 4f);
            row.findViewById(R.id.wfLock).setVisibility(secured ? View.VISIBLE : View.GONE);
            row.setOnClickListener(v -> {
                Intent wifi = new Intent(Settings.ACTION_WIFI_SETTINGS);
                if (connected || !InputKeyService.openSettingsEntry(wifi, ssid, true)) go(wifi);
                else finish();
            });
            list.addView(row);
            if (ssid.equals(focusedSsid)) restore = row;
        }
        if (nets.isEmpty()) {
            TextView none = new TextView(this);
            none.setText(R.string.wifi_none);
            none.setTextColor(0x99FFFFFF);
            none.setPadding(0, 8, 0, 16);
            list.addView(none);
        }
        // At most five rows on screen; more scroll.
        View scroll = findViewById(R.id.qpWifiScroll);
        android.view.ViewGroup.LayoutParams lp = scroll.getLayoutParams();
        lp.height = nets.size() > 5 ? Math.round(5 * 54 * getResources().getDisplayMetrics().density)
                : android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        scroll.setLayoutParams(lp);
        if (restore != null) restore.requestFocus();
        else if (focusedSsid == null && list.getChildCount() > 0 && !(focused != null && focused.getId() == R.id.qpWifiAll)) {
            list.getChildAt(0).requestFocus();
        }
    }

    private void hideWifi() {
        if (scanReceiver != null) {
            unregisterReceiver(scanReceiver);
            scanReceiver = null;
        }
        findViewById(R.id.qpWifi).setVisibility(View.GONE);
        findViewById(R.id.qpTiles).setVisibility(View.VISIBLE);
        if (wifiTile != null) wifiTile.requestFocus();
    }

    @Override
    protected void onDestroy() {
        if (scanReceiver != null) unregisterReceiver(scanReceiver);
        scanReceiver = null;
        super.onDestroy();
    }

    /** Swaps the tiles for "get the iMirror app" (link and QR code); Back swaps them back. */
    private void showAirPlayInfo() {
        findViewById(R.id.qpTiles).setVisibility(View.GONE);
        View info = findViewById(R.id.qpAirPlayInfo);
        info.setVisibility(View.VISIBLE);
        info.requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (findViewById(R.id.qpWifi).getVisibility() == View.VISIBLE) {
            hideWifi();
            return;
        }
        View info = findViewById(R.id.qpAirPlayInfo);
        if (info.getVisibility() == View.VISIBLE) {
            info.setVisibility(View.GONE);
            findViewById(R.id.qpTiles).setVisibility(View.VISIBLE);
            if (airPlayTile != null) airPlayTile.requestFocus();
            return;
        }
        super.onBackPressed();
    }

    private void toggleAirPlay() {
        if (airPlaySub == null) return;
        boolean turnOn = !airPlayOn;
        airPlaySub.setText(turnOn ? R.string.airplay_starting : R.string.airplay_stopping);
        AirPlay.set(this, turnOn, this::showAirPlay);
    }

    private boolean airPlayOn;

    private void showAirPlay(boolean on) {
        airPlayOn = on;
        if (airPlaySub == null) return;
        String name = Settings.Global.getString(getContentResolver(), "device_name");
        airPlaySub.setText(on ? getString(R.string.airplay_on, name == null ? "" : name) : getString(R.string.airplay_off));
        airPlaySub.setVisibility(View.VISIBLE);
    }

    /**
     * Opens Android TV's "Remotes & Accessories" page (Bluetooth on/off, pair, devices) directly, as Google
     * TV's panel does. Settings only shows it to holders of WRITE_SECURE_SETTINGS (granted over adb at
     * install); without it, the remote-key service selects the entry inside Settings instead.
     */
    static void openBluetooth(Context c) {
        Intent i;
        if (c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            i = new Intent("android.settings.SLICE_SETTINGS").putExtra("slice_uri",
                    "content://com.android.tv.settings.accessories.sliceprovider/general");
        } else if (InputKeyService.openSettingsEntry(c, "Remotes")) {
            return;
        } else {
            i = new Intent(Settings.ACTION_SETTINGS);
        }
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        } catch (Exception e) {
            Toast.makeText(c, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
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
