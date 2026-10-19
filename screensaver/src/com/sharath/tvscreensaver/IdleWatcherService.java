package com.sharath.tvscreensaver;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public class IdleWatcherService extends AccessibilityService {

    private static final String TAG = "TvClockSaver";
    private static final long MIN_THRESHOLD_MS = 10000L;
    private static final long LEAD_MS = 8000L;

    public static volatile boolean screensaverShowing = false;

    private long lastActivity = 0L;
    private PowerManager.WakeLock screenLock;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            check();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        lastActivity = SystemClock.uptimeMillis();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            screenLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "tvscreensaver:idle");
            screenLock.setReferenceCounted(false);
        }
        handler.removeCallbacks(loop);
        handler.postDelayed(loop, 1000);
        Log.d(TAG, "idle watcher connected");
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            lastActivity = SystemClock.uptimeMillis();
            Log.d(TAG, "key seen: " + event.getKeyCode());
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        handler.removeCallbacks(loop);
        releaseLock();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(loop);
        releaseLock();
        super.onDestroy();
    }

    private void releaseLock() {
        if (screenLock != null && screenLock.isHeld()) {
            screenLock.release();
        }
    }

    private void check() {
        if (screenLock == null) {
            return;
        }

        long idle = SystemClock.uptimeMillis() - lastActivity;

        int timeout = Settings.System.getInt(
                getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 300000);
        if (timeout <= 0) {
            return;
        }
        long threshold = Math.max(MIN_THRESHOLD_MS, timeout - LEAD_MS);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean interactive = pm != null && pm.isInteractive();
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        boolean audioActive = am != null && am.isMusicActive();
        boolean force = Settings.Secure.getInt(
                getContentResolver(), "tvscreensaver_force", 0) == 1;

        if (idle < threshold) {
            releaseLock();
            return;
        }

        if (screensaverShowing || !interactive) {
            return;
        }

        if (audioActive && !force) {
            return;
        }

        if (!screenLock.isHeld()) {
            screenLock.acquire();
        }

        try {
            Intent intent = new Intent(this, ScreensaverActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            screensaverShowing = true;
            startActivity(intent);
            Log.d(TAG, "screensaver launched after " + idle + "ms idle");
        } catch (Exception e) {
            screensaverShowing = false;
            Log.e(TAG, "failed to launch screensaver", e);
        }
    }
}
