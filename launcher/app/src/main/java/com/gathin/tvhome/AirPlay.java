package com.gathin.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * Switches the AirPlay receiver (our iMirror build, https://github.com/prat3ik/iMirror, kept as a separate
 * GPL app) on and off without opening it. It accepts start/stop/status broadcasts from apps holding its
 * signature permission dev.imirror.receiver.permission.CONTROL, i.e. signed with the same key.
 */
final class AirPlay {

    interface Result {
        void running(boolean on);
    }

    private static final String PKG = "dev.imirror.receiver";
    private static final String PERMISSION = PKG + ".permission.CONTROL";
    private static final String RECEIVER = PKG + ".service.ControlReceiver";

    private AirPlay() {
    }

    /** True when the receiver is installed and accepts our control broadcasts. */
    static boolean controllable(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    static void status(Activity a, Result r) {
        send(a, PKG + ".control.STATUS", r);
    }

    static void set(Activity a, boolean on, Result r) {
        send(a, PKG + ".control." + (on ? "START" : "STOP"), r);
    }

    /** An ordered broadcast; the receiver answers with result code 1 (running) or 0. */
    private static void send(Activity a, String action, Result r) {
        Intent i = new Intent(action).setClassName(PKG, RECEIVER);
        a.sendOrderedBroadcast(i, null, new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!a.isFinishing()) r.running(getResultCode() == 1);
            }
        }, null, -1, null, null);
    }
}
