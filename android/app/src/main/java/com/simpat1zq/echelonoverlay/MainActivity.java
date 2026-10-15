package com.simpat1zq.echelonoverlay;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Html;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Full-screen wrapper around the dashboard web page. The page's menu gets extra
 * items (start/stop overlay, server address) through the EchelonApp JS bridge.
 */
public class MainActivity extends Activity {

    static final String PREFS = "overlay";
    static final String KEY_URL = "url";
    static final String DEFAULT_URL = "https://echelon.sphome.top";

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean startAfterPermission = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0F1115);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "EchelonApp");
        web.setWebViewClient(new Client());
        setContentView(web);
        hideSystemBars();
        web.loadUrl(serverUrl());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (startAfterPermission && Settings.canDrawOverlays(this)) {
            startAfterPermission = false;
            startOverlay();
        }
        refreshMenu();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // --- overlay ----------------------------------------------------------

    private boolean toggleOverlay() {
        if (OverlayService.running) {
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
        startForegroundService(new Intent(this, OverlayService.class));
        main.postDelayed(this::refreshMenu, 500);
    }

    private void refreshMenu() {
        web.evaluateJavascript(
                "window.TreadmillMenu && window.TreadmillMenu.refreshApp && window.TreadmillMenu.refreshApp()",
                null);
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
        public boolean overlayRunning() {
            return OverlayService.running;
        }

        @JavascriptInterface
        public void toggleOverlay() {
            main.post(MainActivity.this::toggleOverlay);
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
