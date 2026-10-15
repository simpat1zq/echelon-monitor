package com.simpat1zq.echelonoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

/**
 * Turns Wi-Fi off and back on when the tablet has been off Wi-Fi for 5 minutes,
 * at most once every 30 minutes. Runs while the overlay or wake-on-motion
 * service is running; Wi-Fi the user switched off is left alone.
 *
 * Android 10+ ignores Wi-Fi on/off requests from apps targeting API 29+, so
 * there it only logs and says so once.
 */
final class WifiWatchdog {

    private static final String TAG = "WifiWatchdog";
    private static final long CHECK_EVERY_MS = 30_000;
    private static final long OFFLINE_FOR_MS = 5 * 60_000;
    private static final long MIN_GAP_MS = 30 * 60_000;
    private static final long OFF_FOR_MS = 5_000;
    // Set while Wi-Fi is off because of us, so it gets turned back on even if
    // the app was killed in between.
    private static final String KEY_TURNED_OFF = "wifi_turned_off";
    private static final String KEY_LAST_RESET = "wifi_reset_at";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;
    private static int users;
    private static long offlineSince;
    private static boolean toldBlocked;

    private WifiWatchdog() {
    }

    /** Call from a service's onCreate (main thread). */
    static void start(Context context) {
        if (users++ > 0) return;
        app = context.getApplicationContext();
        offlineSince = 0;
        main.post(CHECK);
    }

    /** Call from the same service's onDestroy. */
    static void stop() {
        if (users > 0 && --users == 0) main.removeCallbacks(CHECK);
    }

    private static final Runnable CHECK = new Runnable() {
        @Override
        public void run() {
            try {
                check();
            } catch (Exception e) {
                Log.w(TAG, "Check failed", e);
            }
            main.postDelayed(this, CHECK_EVERY_MS);
        }
    };

    @SuppressWarnings("deprecation")  // setWifiEnabled still works on Android 9 and older
    private static void check() {
        WifiManager wifi = app.getSystemService(WifiManager.class);
        SharedPreferences prefs = app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        if (!wifi.isWifiEnabled()) {
            offlineSince = 0;
            if (prefs.getBoolean(KEY_TURNED_OFF, false)) turnOn(wifi, prefs);
            return;  // otherwise the user turned it off
        }
        if (onWifi()) {
            offlineSince = 0;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (offlineSince == 0) offlineSince = now;
        if (now - offlineSince < OFFLINE_FOR_MS) return;
        long last = prefs.getLong(KEY_LAST_RESET, 0);
        if (Math.abs(System.currentTimeMillis() - last) < MIN_GAP_MS) return;

        prefs.edit().putLong(KEY_LAST_RESET, System.currentTimeMillis()).apply();
        offlineSince = 0;
        Log.i(TAG, "Off Wi-Fi for 5 minutes; turning Wi-Fi off and on");
        if (!wifi.setWifiEnabled(false)) {
            Log.w(TAG, "Android didn't let the app turn Wi-Fi off");
            if (!toldBlocked) {
                toldBlocked = true;
                toast("Wi-Fi has been disconnected for 5 minutes, and Android doesn't let this app restart it.");
            }
            return;
        }
        prefs.edit().putBoolean(KEY_TURNED_OFF, true).apply();
        toast("Wi-Fi has been disconnected for 5 minutes; restarting it.");
        main.postDelayed(() -> turnOn(wifi, prefs), OFF_FOR_MS);
    }

    @SuppressWarnings("deprecation")
    private static void turnOn(WifiManager wifi, SharedPreferences prefs) {
        if (wifi.isWifiEnabled() || wifi.setWifiEnabled(true)) {
            prefs.edit().putBoolean(KEY_TURNED_OFF, false).apply();
        } else {
            Log.w(TAG, "Couldn't turn Wi-Fi back on; will retry");
        }
    }

    private static boolean onWifi() {
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
        }
        return false;
    }

    private static void toast(String msg) {
        Toast.makeText(app, msg, Toast.LENGTH_LONG).show();
    }
}
