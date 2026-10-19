package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;

/**
 * "Screensaver" in the quick panel: the clock screensaver right now. Android only lets system apps start
 * the real screensaver, so this shows the same picture; any button closes it.
 */
public class ClockActivity extends Activity {

    private ClockScene scene;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dream_clock);
        scene = new ClockScene(getWindow().getDecorView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        scene.start();
    }

    @Override
    protected void onPause() {
        scene.stop();
        super.onPause();
        finish();   // leaving it (Home, another app) ends it, like the real screensaver
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }
}
