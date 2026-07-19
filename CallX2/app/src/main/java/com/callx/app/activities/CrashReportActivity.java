package com.callx.app.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;

/**
 * CrashReportActivity — shown automatically whenever the app crashes
 * (wired up via Thread.setDefaultUncaughtExceptionHandler in CallxApp).
 *
 * Built entirely in code (no layout XML needed) so it can never itself
 * fail to inflate. Shows the full stack trace in a selectable/scrollable
 * TextView with a one-tap "Copy" button — this lets a user debug crashes
 * on-device without adb/logcat/a PC, by just copy-pasting the text.
 *
 * The same trace is also written to:
 *   /data/data/<pkg>/files/last_crash.txt
 * so it survives even if the crash screen itself is dismissed too fast
 * to read (e.g. accidentally tapped away).
 */
public class CrashReportActivity extends AppCompatActivity {

    public static final String EXTRA_TRACE = "extra_crash_trace";
    public static final String CRASH_FILE_NAME = "last_crash.txt";

    /** Call this from the uncaught-exception handler to persist the trace to disk. */
    public static void saveTraceToFile(Context ctx, String trace) {
        try (FileOutputStream fos = new FileOutputStream(new File(ctx.getFilesDir(), CRASH_FILE_NAME))) {
            fos.write(trace.getBytes());
        } catch (Exception ignored) {
            // Best-effort only — the in-memory extra is the primary path.
        }
    }

    /** Reads back the last saved crash trace, or null if none exists / read failed. */
    @Nullable
    public static String readLastTrace(Context ctx) {
        File f = new File(ctx.getFilesDir(), CRASH_FILE_NAME);
        if (!f.exists()) return null;
        try {
            byte[] data = new byte[(int) f.length()];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                fis.read(data);
            }
            return new String(data);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String trace = getIntent() != null ? getIntent().getStringExtra(EXTRA_TRACE) : null;
        if (trace == null || trace.isEmpty()) {
            trace = readLastTrace(this);
        }
        if (trace == null || trace.isEmpty()) {
            trace = "No crash trace available.";
        }
        final String finalTrace = trace;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(16));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("App Crashed");
        title.setTextSize(20);
        title.setTextColor(Color.parseColor("#D32F2F"));
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Copy this and send it to your developer / paste into chat:");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, 0, 0, dp(12));
        root.addView(subtitle);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scrollView.setBackgroundColor(Color.parseColor("#F5F5F5"));

        TextView tvTrace = new TextView(this);
        tvTrace.setText(finalTrace);
        tvTrace.setTextIsSelectable(true);
        tvTrace.setTextSize(12);
        tvTrace.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvTrace.setPadding(dp(12), dp(12), dp(12), dp(12));
        scrollView.addView(tvTrace);
        root.addView(scrollView);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        buttonRow.setPadding(0, dp(16), 0, 0);

        Button btnCopy = new Button(this);
        btnCopy.setText("Copy to Clipboard");
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("crash_trace", finalTrace));
                Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnLp.setMarginEnd(dp(8));
        buttonRow.addView(btnCopy, btnLp);

        Button btnRestart = new Button(this);
        btnRestart.setText("Restart App");
        btnRestart.setOnClickListener(v -> {
            Intent i = new Intent(this, AuthActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
        });
        LinearLayout.LayoutParams btnLp2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        buttonRow.addView(btnRestart, btnLp2);

        root.addView(buttonRow);

        setContentView(root);
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density);
    }
}
