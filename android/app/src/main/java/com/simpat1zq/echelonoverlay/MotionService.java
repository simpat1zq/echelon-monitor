package com.simpat1zq.echelonoverlay;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.util.Range;

import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;

/**
 * Watches the front camera at low resolution and wakes the screen when something
 * moves. Frames are compared in memory only; nothing is stored or sent.
 */
public class MotionService extends Service {

    static volatile boolean running = false;
    /** Set by MainActivity so motion can also reset the page's screen timeout. */
    static volatile Runnable onMotion;

    private static final String TAG = "MotionService";
    private static final String CHANNEL = "motion";
    private static final String ACTION_STOP = "stop";
    private static final long CHECK_EVERY_MS = 4000;
    private static final int GRID_W = 32, GRID_H = 24;
    private static final int CELL_DIFF = 14;           // brightness change (0-255) that counts
    private static final float CHANGED_FRACTION = 0.03f; // share of cells that must change

    private HandlerThread thread;
    private Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private PowerManager.WakeLock cpuLock;
    private int[] previous;
    private long lastCheck, lastWake, lastNote;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, buildNotification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(2, buildNotification(null));
        }
        // Keep the CPU (and so the camera) running while the screen is off.
        PowerManager pm = getSystemService(PowerManager.class);
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "echelon:motion-watch");
        cpuLock.acquire();
        thread = new HandlerThread("motion");
        thread.start();
        bg = new Handler(thread.getLooper());
        bg.post(this::openCamera);
        WifiWatchdog.start(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putBoolean("motion", false).apply();
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        WifiWatchdog.stop();
        bg.post(this::closeCamera);
        thread.quitSafely();
        if (cpuLock.isHeld()) cpuLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- camera -----------------------------------------------------------

    private void openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No camera permission");
            stopSelf();
            return;
        }
        try {
            CameraManager cm = getSystemService(CameraManager.class);
            String id = null;
            for (String c : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    id = c;
                    break;
                }
                if (id == null) id = c;
            }
            if (id == null) {
                Log.w(TAG, "No camera");
                return;
            }
            final String cameraId = id;
            reader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onImage, bg);
            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice cam) {
                    camera = cam;
                    startCapture(cm, cameraId);
                }

                @Override
                public void onDisconnected(CameraDevice cam) {
                    // Another app took the camera; try again later.
                    cam.close();
                    camera = null;
                    retryLater();
                }

                @Override
                public void onError(CameraDevice cam, int error) {
                    Log.w(TAG, "Camera error " + error);
                    cam.close();
                    camera = null;
                    retryLater();
                }
            }, bg);
        } catch (Exception e) {
            Log.w(TAG, "Couldn't open camera", e);
            retryLater();
        }
    }

    private void startCapture(CameraManager cm, String cameraId) {
        try {
            camera.createCaptureSession(Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                b.addTarget(reader.getSurface());
                                Range<Integer> slow = slowestFps(cm, cameraId);
                                if (slow != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, slow);
                                s.setRepeatingRequest(b.build(), null, bg);
                            } catch (Exception e) {
                                Log.w(TAG, "Couldn't start capture", e);
                                retryLater();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            retryLater();
                        }
                    }, bg);
        } catch (Exception e) {
            Log.w(TAG, "Couldn't create session", e);
            retryLater();
        }
    }

    private static Range<Integer> slowestFps(CameraManager cm, String id) {
        try {
            Range<Integer>[] ranges = cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Range<Integer> best = null;
            if (ranges != null) {
                for (Range<Integer> r : ranges) {
                    if (best == null || r.getUpper() < best.getUpper()) best = r;
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private void retryLater() {
        closeCamera();
        if (running) bg.postDelayed(this::openCamera, 30000);
    }

    private void closeCamera() {
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        session = null;
        if (camera != null) camera.close();
        camera = null;
        if (reader != null) reader.close();
        reader = null;
    }

    // --- motion -----------------------------------------------------------

    private void onImage(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        try {
            long now = System.currentTimeMillis();
            if (now - lastCheck < CHECK_EVERY_MS) return;
            lastCheck = now;
            int[] grid = luminanceGrid(img);
            if (previous != null && changed(previous, grid)) motion();
            previous = grid;
        } finally {
            img.close();
        }
    }

    /** Average brightness of each cell in a GRID_W x GRID_H grid (Y plane, sampled). */
    private static int[] luminanceGrid(Image img) {
        Image.Plane y = img.getPlanes()[0];
        ByteBuffer buf = y.getBuffer();
        int w = img.getWidth(), h = img.getHeight();
        int rowStride = y.getRowStride(), pixStride = y.getPixelStride();
        int[] sum = new int[GRID_W * GRID_H];
        int[] count = new int[GRID_W * GRID_H];
        for (int py = 0; py < h; py += 4) {
            int gy = py * GRID_H / h;
            for (int px = 0; px < w; px += 4) {
                int cell = gy * GRID_W + px * GRID_W / w;
                sum[cell] += buf.get(py * rowStride + px * pixStride) & 0xFF;
                count[cell]++;
            }
        }
        for (int i = 0; i < sum.length; i++) sum[i] = count[i] == 0 ? 0 : sum[i] / count[i];
        return sum;
    }

    private static boolean changed(int[] a, int[] b) {
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > CELL_DIFF) n++;
        }
        return n > a.length * CHANGED_FRACTION;
    }

    @SuppressWarnings("deprecation")  // SCREEN_BRIGHT_WAKE_LOCK is a fallback for older Android
    private void motion() {
        long now = System.currentTimeMillis();
        PowerManager pm = getSystemService(PowerManager.class);
        if (!pm.isInteractive() && now - lastWake > 5000) {
            lastWake = now;
            PowerManager.WakeLock wake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "echelon:motion-wake");
            wake.acquire(3000);
            // Newer Android ignores the wake lock above; an activity that asks to turn
            // the screen on works (allowed from the background because the app can
            // draw over other apps).
            try {
                startActivity(new Intent(this, WakeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            } catch (Exception e) {
                Log.w(TAG, "Couldn't start wake screen", e);
            }
        }
        // Show the last detection in the notification, to tell detection from waking.
        if (now - lastNote > 5000) {
            lastNote = now;
            String when = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(now));
            getSystemService(NotificationManager.class).notify(2, buildNotification("Motion seen at " + when));
        }
        Runnable listener = onMotion;
        if (listener != null) main.post(listener);
    }

    // --- plumbing ---------------------------------------------------------

    private Notification buildNotification(String status) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Wake on motion", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, MotionService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Wake on motion is on")
                .setContentText(status != null ? status
                        : "The front camera watches for movement to turn the screen on")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Turn off", stop).build())
                .setOngoing(true)
                .build();
    }
}
