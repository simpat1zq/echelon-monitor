package com.simpat1zq.echelonoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextPaint;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.ScaleGestureDetector;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Floating, draggable box showing live treadmill stats from the server's /api/state.
 * Tap to change which stat is shown large; double-tap to open the app.
 */
public class OverlayService extends Service {

    static volatile boolean running = false;

    private static final String CHANNEL = "overlay";
    private static final String ACTION_STOP = "stop";
    private static final int BLUE = 0xFF4FC3F7;
    private static final int WHITE = 0xFFF2F4F8;
    private static final int MUTED = 0xFFB8C0CC;
    private static final int RED = 0xFFFF6B6B;
    private static final int OUTLINE = 0xFF000000;

    // The stats, in the order a tap cycles through them. Time is the default primary.
    private static final int TIME = 0, SPEED = 1, DISTANCE = 2;

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private LinearLayout box;
    private OutlinedText primaryValue, primaryUnit, noteTv;
    private final OutlinedText[] secondaryValue = new OutlinedText[2];
    private final OutlinedText[] secondaryUnit = new OutlinedText[2];
    private final Handler main = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService poller;
    private String baseUrl;
    private int primary;

    // Size: 1.0 is the original size; long-press-drag or pinch between these limits.
    private static final float MIN_SCALE = 0.75f, MAX_SCALE = 2.0f;
    private float scale;
    private GradientDrawable bg;
    /** Everything whose size follows the scale: text views with base sizes and paddings. */
    private final List<Sized> sized = new ArrayList<>();

    private static class Sized {
        final OutlinedText view;
        final float sp, strokeDp;
        float padL, padT, padR, padB;

        Sized(OutlinedText view, float sp, float strokeDp) {
            this.view = view;
            this.sp = sp;
            this.strokeDp = strokeDp;
        }
    }

    // Latest values from the server.
    private String timeText = "0:00", speedText = "0.0", speedUnit = "mph";
    private String distText = "0.00", distUnit = "mi";
    private boolean connected = false;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        startForeground(1, buildNotification());
        baseUrl = prefs().getString(MainActivity.KEY_URL, MainActivity.DEFAULT_URL);
        primary = prefs().getInt("primary", TIME);
        scale = clampScale(prefs().getFloat("scale", 1f));

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
        applyScale();
        render();

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
        bg = new GradientDrawable();
        bg.setColor(0x260F1115);  // ~15% opaque: just enough to show the box's edges
        box.setBackground(bg);

        LinearLayout top = row();
        primaryValue = text(40, 3, 0, 0, 0, 0);
        primaryUnit = text(12, 2, 4, 0, 0, 6);
        top.addView(primaryValue);
        top.addView(primaryUnit);
        box.addView(top);

        LinearLayout bottom = row();
        for (int i = 0; i < 2; i++) {
            secondaryValue[i] = text(20, 2.5f, i == 0 ? 0 : 14, 0, 0, 0);
            secondaryUnit[i] = text(10, 2, 3, 0, 0, 3);
            bottom.addView(secondaryValue[i]);
            bottom.addView(secondaryUnit[i]);
        }
        box.addView(bottom);

        noteTv = text(12, 2, 0, 0, 0, 0);
        noteTv.setTextColor(RED);
        noteTv.setVisibility(View.GONE);
        box.addView(noteTv);

        box.setOnTouchListener(new Touch());
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.BOTTOM);
        return r;
    }

    /** A text view whose size, outline and padding (in dp, before scaling) follow the scale. */
    private OutlinedText text(float sp, float outlineDp, float padL, float padT, float padR, float padB) {
        OutlinedText t = new OutlinedText(this);
        t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setFontFeatureSettings("tnum");  // fixed-width digits so the box doesn't jitter
        Sized z = new Sized(t, sp, outlineDp);
        z.padL = padL;
        z.padT = padT;
        z.padR = padR;
        z.padB = padB;
        sized.add(z);
        return t;
    }

    private static float clampScale(float s) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, s));
    }

    private void applyScale() {
        box.setPadding(dp(12 * scale), dp(6 * scale), dp(12 * scale), dp(8 * scale));
        bg.setCornerRadius(dp(14 * scale));
        for (Sized z : sized) {
            z.view.setTextSize(z.sp * scale);
            float stroke = dp(z.strokeDp * scale);
            z.view.setStroke(stroke);
            int edge = Math.round(stroke);  // room for the outline at the edges
            z.view.setPadding(dp(z.padL * scale) + edge, dp(z.padT * scale),
                    dp(z.padR * scale) + edge, dp(z.padB * scale));
        }
    }

    private void setScale(float s) {
        s = clampScale(s);
        if (Math.abs(s - scale) < 0.01f) return;
        scale = s;
        applyScale();
    }

    /** Puts the chosen stat in the big slot and the other two underneath. */
    private void render() {
        int[] order = {primary, (primary + 1) % 3, (primary + 2) % 3};
        setStat(primaryValue, primaryUnit, order[0], true);
        for (int i = 0; i < 2; i++) setStat(secondaryValue[i], secondaryUnit[i], order[i + 1], false);
    }

    private void setStat(TextView value, TextView unit, int which, boolean big) {
        switch (which) {
            case TIME:
                value.setText(timeText);
                unit.setText("");
                break;
            case SPEED:
                value.setText(speedText);
                unit.setText(speedUnit);
                break;
            default:
                value.setText(distText);
                unit.setText(distUnit);
                break;
        }
        // Whichever stat is big is shown in blue while the treadmill is connected.
        value.setTextColor(big && connected ? BLUE : WHITE);
        unit.setTextColor(MUTED);
        unit.setVisibility(unit.getText().length() == 0 ? View.GONE : View.VISIBLE);
    }

    /**
     * Drag to move, tap to cycle the big stat, double-tap to open the app,
     * long-press then drag (or pinch) to resize.
     */
    private class Touch implements View.OnTouchListener {
        private int startX, startY;
        private boolean resizing;
        private float resizeFromX, resizeFromY, resizeFromScale;

        private final ScaleGestureDetector pinch = new ScaleGestureDetector(OverlayService.this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        setScale(scale * d.getScaleFactor());
                        return true;
                    }
                });

        private final GestureDetector gestures = new GestureDetector(OverlayService.this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        startX = lp.x;
                        startY = lp.y;
                        return true;
                    }

                    @Override
                    public boolean onScroll(MotionEvent down, MotionEvent e, float dx, float dy) {
                        if (resizing || pinch.isInProgress() || e.getPointerCount() > 1) return true;
                        lp.x = startX + Math.round(e.getRawX() - down.getRawX());
                        lp.y = startY + Math.round(e.getRawY() - down.getRawY());
                        wm.updateViewLayout(box, lp);
                        return true;
                    }

                    @Override
                    public void onLongPress(MotionEvent e) {
                        resizing = true;
                        resizeFromX = e.getRawX();
                        resizeFromY = e.getRawY();
                        resizeFromScale = scale;
                        box.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    }

                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        primary = (primary + 1) % 3;
                        prefs().edit().putInt("primary", primary).apply();
                        render();
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        startActivity(new Intent(OverlayService.this, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                        return true;
                    }
                });

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            pinch.onTouchEvent(e);
            gestures.onTouchEvent(e);
            int action = e.getActionMasked();
            if (resizing && action == MotionEvent.ACTION_MOVE) {
                // Right/down grows, left/up shrinks: 200dp of drag = +/-100%.
                float moved = ((e.getRawX() - resizeFromX) + (e.getRawY() - resizeFromY)) / 2f;
                setScale(resizeFromScale * (1f + moved / dp(200)));
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                resizing = false;
                prefs().edit().putInt("x", lp.x).putInt("y", lp.y).putFloat("scale", scale).apply();
            }
            return true;
        }
    }

    /** TextView that draws a dark outline behind its text so it reads over any video. */
    static class OutlinedText extends TextView {
        private float stroke;
        private boolean outlining;

        OutlinedText(Context c) {
            super(c);
        }

        void setStroke(float px) {
            stroke = px;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            TextPaint p = getPaint();
            int fill = getCurrentTextColor();
            outlining = true;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(stroke);
            setTextColor(OUTLINE);
            super.onDraw(canvas);
            p.setStyle(Paint.Style.FILL);
            setTextColor(fill);
            outlining = false;
            super.onDraw(canvas);
        }

        @Override
        public void invalidate() {
            // setTextColor() in onDraw() would otherwise redraw forever.
            if (!outlining) super.invalidate();
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
            main.post(() -> showNote("server unreachable"));
        }
    }

    private void show(JSONObject d) {
        connected = d.optBoolean("connected", false);
        speedText = String.format(Locale.US, "%.1f", d.optDouble("speed", 0));
        speedUnit = d.optString("speed_unit", "mph");
        timeText = formatTime(d.optInt("moving_seconds", 0));
        distText = String.format(Locale.US, "%.2f", d.optDouble("distance", 0));
        distUnit = d.optString("distance_unit", "mi");
        render();
        showNote(connected ? null : "treadmill not connected");
    }

    private void showNote(String msg) {
        if (msg == null) {
            noteTv.setVisibility(View.GONE);
        } else {
            noteTv.setText(msg);
            noteTv.setVisibility(View.VISIBLE);
        }
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

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
