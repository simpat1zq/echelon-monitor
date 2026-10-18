package com.simpat1zq.echelonoverlay;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sends the tablet's battery level to the server every minute, so it can switch
 * the charging plug to keep the battery between the levels set in the menu.
 * Runs while the overlay or wake-on-motion service is running.
 */
final class BatteryReporter {

    private static final String TAG = "BatteryReporter";
    private static ScheduledExecutorService timer;
    private static Context app;
    private static int users;

    private BatteryReporter() {
    }

    /** Identifies this device to the server, which only acts on the chosen device's reports. */
    static String deviceId(Context context) {
        return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    /** Call from a service's onCreate (main thread). */
    static void start(Context context) {
        if (users++ > 0) return;
        app = context.getApplicationContext();
        timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleWithFixedDelay(BatteryReporter::report, 0, 60, TimeUnit.SECONDS);
    }

    /** Call from the same service's onDestroy. */
    static void stop() {
        if (users > 0 && --users == 0) {
            timer.shutdownNow();
            timer = null;
        }
    }

    private static void report() {
        try {
            Intent battery = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return;
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            if (level < 0 || scale <= 0) return;
            JSONObject body = new JSONObject()
                    .put("battery", Math.round(level * 100f / scale))
                    .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL)
                    .put("device", deviceId(app))
                    .put("name", Build.MANUFACTURER + " " + Build.MODEL);
            String base = app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                    .getString(MainActivity.KEY_URL, MainActivity.DEFAULT_URL);
            HttpURLConnection c = (HttpURLConnection) new URL(base + "/api/tablet").openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            c.getResponseCode();
            c.disconnect();
        } catch (Exception e) {
            Log.w(TAG, "Couldn't report the battery: " + e);
        }
    }
}
