package com.simpat1zq.echelonoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Floating, draggable box showing live treadmill stats from the server's /api/state. */
public class OverlayService extends Service {

    static volatile boolean running = false;

    private static final String CHANNEL = "overlay";
    private static final String ACTION_STOP = "stop";
    private static final int BLUE = 0xFF4FC3F7;
    private static final int WHITE = 0xFFF2F4F8;
    private static final int MUTED = 0xFF8B93A3;
    private static final int RED = 0xFFEF5350;

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private LinearLayout box;
    private TextView speedTv, speedUnitTv, timeTv, distTv, distUnitTv, noteTv;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService poller;
    private String baseUrl;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        startForeground(1, buildNotification());
        baseUrl = prefs().getString(MainActivity.KEY_URL, MainActivity.DEFAULT_URL);

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildView();
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = prefs().getInt("x", dp(16));
        lp.y = prefs().getInt("y", dp(16));
        wm.addView(box, lp);

        poller = Executors.newSingleThreadScheduledExecutor();
        poller.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (poller != null) poller.shutdownNow();
        if (box != null) {
            try {
                wm.removeView(box);
            } catch (IllegalArgumentException ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- view -------------------------------------------------------------

    private void buildView() {
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(8), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD90F1115);
        bg.setCornerRadius(dp(14));
        box.setBackground(bg);

        LinearLayout speedRow = row();
        speedTv = text(40, BLUE, true);
        speedUnitTv = text(14, MUTED, false);
        speedUnitTv.setPadding(dp(4), 0, 0, dp(6));
        speedRow.addView(speedTv);
        speedRow.addView(speedUnitTv);
        box.addView(speedRow);

        LinearLayout statsRow = row();
        timeTv = text(22, WHITE, true);
        distTv = text(22, WHITE, true);
        distTv.setPadding(dp(16), 0, 0, 0);
        distUnitTv = text(13, MUTED, false);
        distUnitTv.setPadding(dp(3), 0, 0, dp(3));
        statsRow.addView(timeTv);
        statsRow.addView(distTv);
        statsRow.addView(distUnitTv);
        box.addView(statsRow);

        noteTv = text(12, RED, false);
        noteTv.setVisibility(View.GONE);
        box.addView(noteTv);

        speedTv.setText("–");
        timeTv.setText("0:00");
        distTv.setText("0.00");
        box.setOnTouchListener(new DragListener());
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.BOTTOM);
        return r;
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private class DragListener implements View.OnTouchListener {
        private int startX, startY;
        private float touchX, touchY;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lp.x;
                    startY = lp.y;
                    touchX = e.getRawX();
                    touchY = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x = startX + Math.round(e.getRawX() - touchX);
                    lp.y = startY + Math.round(e.getRawY() - touchY);
                    wm.updateViewLayout(box, lp);
                    return true;
                case MotionEvent.ACTION_UP:
                    prefs().edit().putInt("x", lp.x).putInt("y", lp.y).apply();
                    return true;
                default:
                    return false;
            }
        }
    }

    // --- data -------------------------------------------------------------

    private void poll() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + "/api/state").openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            } finally {
                c.disconnect();
            }
            JSONObject d = new JSONObject(sb.toString());
            main.post(() -> show(d));
        } catch (Exception ex) {
            main.post(() -> showError("server unreachable"));
        }
    }

    private void show(JSONObject d) {
        boolean connected = d.optBoolean("connected", false);
        speedTv.setText(String.format(Locale.US, "%.1f", d.optDouble("speed", 0)));
        speedTv.setTextColor(connected ? BLUE : MUTED);
        speedUnitTv.setText(d.optString("speed_unit", "mph"));
        timeTv.setText(formatTime(d.optInt("moving_seconds", 0)));
        distTv.setText(String.format(Locale.US, "%.2f", d.optDouble("distance", 0)));
        distUnitTv.setText(d.optString("distance_unit", "mi"));
        if (connected) {
            noteTv.setVisibility(View.GONE);
        } else {
            noteTv.setText("treadmill not connected");
            noteTv.setVisibility(View.VISIBLE);
        }
    }

    private void showError(String msg) {
        speedTv.setTextColor(MUTED);
        noteTv.setText(msg);
        noteTv.setVisibility(View.VISIBLE);
    }

    private static String formatTime(int s) {
        int h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%d:%02d", m, sec);
    }

    // --- plumbing ---------------------------------------------------------

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Overlay", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Treadmill overlay is on")
                .setContentText("Tap for settings")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
