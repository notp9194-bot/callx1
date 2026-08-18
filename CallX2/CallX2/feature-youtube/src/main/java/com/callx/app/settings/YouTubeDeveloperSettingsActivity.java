package com.callx.app.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.utils.Constants;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * YouTubeDeveloperSettingsActivity — Developer preferences.
 * Stats for nerds, debug logs, database info, cache management.
 */
public class YouTubeDeveloperSettingsActivity extends AppCompatActivity {

    private static final String PREFS = "yt_developer_prefs";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_developer);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        Toolbar toolbar = findViewById(R.id.toolbar_yt_developer);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Developer Preferences");
        }

        // UID display
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String uid = user != null ? user.getUid() : "Not logged in";
        TextView tvUid = findViewById(R.id.tv_yt_dev_uid);
        if (tvUid != null) {
            tvUid.setText("UID: " + uid);
            tvUid.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("UID", uid));
                    Toast.makeText(this, "UID copied!", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // App version
        TextView tvVersion = findViewById(R.id.tv_yt_dev_version);
        if (tvVersion != null) tvVersion.setText("App Version: v178-CallX2");

        // Firebase DB URL
        TextView tvDbUrl = findViewById(R.id.tv_yt_dev_db_url);
        if (tvDbUrl != null) {
            tvDbUrl.setText("Firebase DB: " + Constants.DB_URL);
            tvDbUrl.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("DB_URL", Constants.DB_URL));
                    Toast.makeText(this, "DB URL copied!", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Stats for nerds
        setupSwitch(prefs, R.id.sw_yt_dev_stats, "stats_for_nerds", false);

        // Debug overlay
        setupSwitch(prefs, R.id.sw_yt_dev_debug_overlay, "debug_overlay", false);

        // Verbose logging
        setupSwitch(prefs, R.id.sw_yt_dev_verbose_log, "verbose_logging", false);

        // Disable cache
        setupSwitch(prefs, R.id.sw_yt_dev_disable_cache, "disable_cache", false);

        // Network logging
        setupSwitch(prefs, R.id.sw_yt_dev_net_log, "network_logging", false);

        // Clear all caches
        View btnClearCache = findViewById(R.id.btn_yt_dev_clear_cache);
        if (btnClearCache != null) {
            btnClearCache.setOnClickListener(v -> {
                try {
                    // Clear app internal cache
                    deleteCache(this);
                    Toast.makeText(this, "✅ Cache cleared!", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Show cache size
        TextView tvCacheSize = findViewById(R.id.tv_yt_dev_cache_size);
        if (tvCacheSize != null) {
            long size = getDirSize(getCacheDir());
            tvCacheSize.setText("Cache: " + formatSize(size));
        }
    }

    private void setupSwitch(SharedPreferences prefs, int id, String key, boolean def) {
        SwitchCompat sw = findViewById(id);
        if (sw == null) return;
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean(key, checked).apply());
    }

    private static void deleteCache(Context ctx) {
        java.io.File cacheDir = ctx.getCacheDir();
        if (cacheDir != null && cacheDir.isDirectory()) deleteDir(cacheDir);
    }

    private static boolean deleteDir(java.io.File dir) {
        if (dir != null && dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null)
                for (String child : children) deleteDir(new java.io.File(dir, child));
        }
        return dir != null && dir.delete();
    }

    private long getDirSize(java.io.File dir) {
        long size = 0;
        if (dir == null || !dir.exists()) return 0;
        java.io.File[] files = dir.listFiles();
        if (files != null)
            for (java.io.File f : files) size += f.isDirectory() ? getDirSize(f) : f.length();
        return size;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
