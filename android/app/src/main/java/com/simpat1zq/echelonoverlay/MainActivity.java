package com.simpat1zq.echelonoverlay;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Html;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.Arrays;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Full-screen wrapper around the dashboard web page. The page's menu gets extra
 * items (start/stop overlay, server address) through the EchelonApp JS bridge.
 */
public class MainActivity extends Activity {

    static final String PREFS = "overlay";
    static final String KEY_URL = "url";
    static final String KEY_OVERLAY = "overlay_on";
    static final String DEFAULT_URL = "https://echelon.sphome.top";

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean startAfterPermission = false;
    private String pendingUpdateUrl = null;
    private static final String ACTION_INSTALL_STATUS = "install_status";
    private static final int REQ_CAMERA = 7, REQ_WEB_CAMERA = 8;
    private PermissionRequest pendingWebCamera;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The page keeps the screen on during runs (setKeepScreenOn below).

        web = new WebView(this);
        web.setBackgroundColor(0xFF0F1115);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);  // the run-start face check plays the camera unprompted
        web.addJavascriptInterface(new Bridge(), "EchelonApp");
        web.setWebViewClient(new Client());
        // Without a WebChromeClient, alert()/confirm()/prompt() silently do nothing.
        // It also lets the page use the camera (face setup and recognition).
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                main.post(() -> {
                    if (!Arrays.asList(request.getResources()).contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                        request.deny();
                    } else if (hasCamera()) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        pendingWebCamera = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_WEB_CAMERA);
                    }
                });
            }
        });
        setContentView(web);
        web.loadUrl(serverUrl());

        if (prefs().getBoolean("motion", false) && !MotionService.running && hasCamera()) {
            startForegroundService(new Intent(this, MotionService.class));
        }
        // The overlay is on by default; it stays off only if it was turned off.
        if (prefs().getBoolean(KEY_OVERLAY, true) && !OverlayService.running) {
            if (Settings.canDrawOverlays(this)) {
                startOverlay();
            } else if (!prefs().getBoolean("overlay_asked", false)) {
                prefs().edit().putBoolean("overlay_asked", true).apply();
                toggleOverlay();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (startAfterPermission && Settings.canDrawOverlays(this)) {
            startAfterPermission = false;
            startOverlay();
        }
        if (pendingUpdateUrl != null && getPackageManager().canRequestPackageInstalls()) {
            String url = pendingUpdateUrl;
            pendingUpdateUrl = null;
            installUpdate(url);
        }
        refreshMenu();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!ACTION_INSTALL_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) startActivity(confirm);
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            toast("Update not installed: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }

    // The overlay steps aside while the app itself is on screen.
    @Override
    protected void onStart() {
        super.onStart();
        OverlayService.setAppVisible(true);
    }

    @Override
    protected void onStop() {
        OverlayService.setAppVisible(false);
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_WEB_CAMERA && pendingWebCamera != null) {
            if (hasCamera()) pendingWebCamera.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            else pendingWebCamera.deny();
            pendingWebCamera = null;
        }
        if (code == REQ_CAMERA) {
            if (hasCamera()) setMotion(true);
            else toast("Wake on motion needs the camera permission.");
        }
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    // --- overlay ----------------------------------------------------------

    private boolean toggleOverlay() {
        if (OverlayService.running) {
            prefs().edit().putBoolean(KEY_OVERLAY, false).apply();
            stopService(new Intent(this, OverlayService.class));
            return false;
        }
        if (!Settings.canDrawOverlays(this)) {
            startAfterPermission = true;
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return false;
        }
        startOverlay();
        return true;
    }

    private void startOverlay() {
        prefs().edit().putBoolean(KEY_OVERLAY, true).apply();
        startForegroundService(new Intent(this, OverlayService.class));
        main.postDelayed(this::refreshMenu, 500);
    }

    private void refreshMenu() {
        web.evaluateJavascript(
                "window.TreadmillMenu && window.TreadmillMenu.refreshApp && window.TreadmillMenu.refreshApp()",
                null);
    }

    // --- wake on motion ---------------------------------------------------

    private boolean hasCamera() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void setMotion(boolean on) {
        if (on && !hasCamera()) {
            // Android 13+ also needs permission to show the service's notification.
            String[] perms = Build.VERSION.SDK_INT >= 33
                    ? new String[]{Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS}
                    : new String[]{Manifest.permission.CAMERA};
            requestPermissions(perms, REQ_CAMERA);
            return;
        }
        if (on && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
        prefs().edit().putBoolean("motion", on).apply();
        Intent i = new Intent(this, MotionService.class);
        if (on) startForegroundService(i);
        else stopService(i);
        main.postDelayed(this::refreshMenu, 500);
    }

    // --- app updates ------------------------------------------------------

    private int appVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Downloads the APK and hands it to Android's installer, which asks to confirm. */
    private void installUpdate(String apkUrl) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            pendingUpdateUrl = apkUrl;
            toast("Allow installing updates from this app, then come back.");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        toast("Downloading update…");
        new Thread(() -> {
            try {
                PackageInstaller installer = getPackageManager().getPackageInstaller();
                int id = installer.createSession(new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL));
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    HttpURLConnection c = (HttpURLConnection) new URL(apkUrl).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(30000);
                    try (InputStream in = c.getInputStream();
                         OutputStream out = session.openWrite("update.apk", 0, -1)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        session.fsync(out);
                    } finally {
                        c.disconnect();
                    }
                    Intent status = new Intent(this, MainActivity.class).setAction(ACTION_INSTALL_STATUS);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    session.commit(PendingIntent.getActivity(this, 1, status, flags).getIntentSender());
                }
            } catch (Exception e) {
                main.post(() -> toast("Update failed: " + e.getMessage()));
            }
        }).start();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    // --- server address ---------------------------------------------------

    private String serverUrl() {
        return prefs().getString(KEY_URL, DEFAULT_URL);
    }

    private void setServerUrl(String url) {
        url = url == null ? "" : url.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.isEmpty()) url = DEFAULT_URL;
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        prefs().edit().putString(KEY_URL, url).apply();
        if (OverlayService.running) {  // the overlay reads the address when it starts
            stopService(new Intent(this, OverlayService.class));
            startOverlay();
        }
        web.loadUrl(url);
    }

    private boolean sameServer(Uri uri) {
        Uri server = Uri.parse(serverUrl());
        return uri.getScheme() != null && uri.getScheme().equals(server.getScheme())
                && uri.getHost() != null && uri.getHost().equalsIgnoreCase(server.getHost())
                && uri.getPort() == server.getPort();
    }

    /** Shown when the dashboard can't be loaded: retry or change the address. */
    private void showSetup(String problem) {
        String url = Html.escapeHtml(serverUrl());
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;padding:24px;background:#0f1115;color:#f2f4f8;font-family:sans-serif}"
                + "h1{font-size:20px}p{color:#8b93a3;line-height:1.4}"
                + "input{width:100%;box-sizing:border-box;padding:12px;font-size:16px;border-radius:8px;"
                + "border:1px solid #2a2f3a;background:#1a1d24;color:#f2f4f8}"
                + "button{margin-top:12px;width:100%;padding:12px;font-size:16px;border-radius:8px;"
                + "border:1px solid #2f4a6a;background:#22324a;color:#f2f4f8}</style></head><body>"
                + "<h1>Can't reach the dashboard</h1><p>" + Html.escapeHtml(problem) + "</p>"
                + "<p>Server address</p><input id='u' value='" + url + "' inputmode='url'>"
                + "<button onclick='EchelonApp.setServerUrl(document.getElementById(\"u\").value)'>Save and connect</button>"
                + "<button onclick='EchelonApp.retry()'>Retry</button>"
                + "<p>For an https address that uses your own certificate (like Caddy's), install that "
                + "certificate on this device: Settings, Security, Encryption &amp; credentials, Install from storage.</p>"
                + "</body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri uri = req.getUrl();
            if (sameServer(uri)) return false;
            // Anything else (e.g. the version link to GitHub) opens in the browser.
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception ignored) {
            }
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            if (req.isForMainFrame()) showSetup(String.valueOf(err.getDescription()));
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            showSetup("The server's certificate isn't trusted on this device.");
        }
    }

    /** Called from the page's JavaScript (menu.js). */
    private class Bridge {
        @JavascriptInterface
        public String deviceId() {
            return BatteryReporter.deviceId(MainActivity.this);
        }

        @JavascriptInterface
        public boolean overlayRunning() {
            return OverlayService.running;
        }

        @JavascriptInterface
        public void toggleOverlay() {
            main.post(MainActivity.this::toggleOverlay);
        }

        /** The page decides: on while the treadmill is in use, off after its timeout. */
        @JavascriptInterface
        public void setKeepScreenOn(boolean on) {
            main.post(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public boolean motionEnabled() {
            return MotionService.running;
        }

        @JavascriptInterface
        public void setMotionEnabled(boolean on) {
            main.post(() -> setMotion(on));
        }

        @JavascriptInterface
        public int appVersion() {
            return MainActivity.this.appVersion();
        }

        @JavascriptInterface
        public void installUpdate(String apkUrl) {
            main.post(() -> MainActivity.this.installUpdate(apkUrl));
        }

        @JavascriptInterface
        public String serverUrl() {
            return MainActivity.this.serverUrl();
        }

        @JavascriptInterface
        public void setServerUrl(String url) {
            main.post(() -> MainActivity.this.setServerUrl(url));
        }

        @JavascriptInterface
        public void retry() {
            main.post(() -> web.loadUrl(MainActivity.this.serverUrl()));
        }
    }
}
