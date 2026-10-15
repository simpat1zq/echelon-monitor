package com.simpat1zq.echelonoverlay;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Setup screen: server address, overlay permission, start/stop. */
public class MainActivity extends Activity {

    static final String PREFS = "overlay";
    static final String KEY_URL = "url";
    static final String DEFAULT_URL = "https://echelon.sphome.top";

    private EditText urlField;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp(20);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Treadmill Overlay");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView help = new TextView(this);
        help.setText("Shows time, speed and distance in a small box on top of other apps, "
                + "like Jellyfin. Drag the box to move it, tap it to switch which number is "
                + "big, and double-tap it to come back here.");
        help.setPadding(0, dp(8), 0, dp(16));
        col.addView(help);

        TextView label = new TextView(this);
        label.setText("Server address");
        col.addView(label);

        urlField = new EditText(this);
        urlField.setSingleLine(true);
        urlField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setText(prefs().getString(KEY_URL, DEFAULT_URL));
        col.addView(urlField, matchWidth());

        Button perm = new Button(this);
        perm.setText("1. Allow display over other apps");
        perm.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        col.addView(perm, matchWidth());

        Button start = new Button(this);
        start.setText("2. Start overlay");
        start.setOnClickListener(v -> startOverlay());
        col.addView(start, matchWidth());

        Button stop = new Button(this);
        stop.setText("Stop overlay");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, OverlayService.class));
            updateStatus();
        });
        col.addView(stop, matchWidth());

        status = new TextView(this);
        status.setPadding(0, dp(16), 0, 0);
        col.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void startOverlay() {
        String url = urlField.getText().toString().trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        urlField.setText(url);
        prefs().edit().putString(KEY_URL, url).apply();

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"display over other apps\" first (step 1).",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, OverlayService.class);
        stopService(i);  // restart so a changed address takes effect
        startForegroundService(i);
        updateStatus();
        moveTaskToBack(true);
    }

    private void updateStatus() {
        boolean allowed = Settings.canDrawOverlays(this);
        status.setText("Permission: " + (allowed ? "allowed" : "not allowed yet")
                + "\nOverlay: " + (OverlayService.running ? "running" : "stopped"));
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private LinearLayout.LayoutParams matchWidth() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
