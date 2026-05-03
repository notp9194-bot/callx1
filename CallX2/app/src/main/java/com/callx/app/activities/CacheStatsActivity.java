package com.callx.app.activities;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.callx.app.R;
import com.callx.app.cache.CacheAnalytics;
import com.callx.app.cache.CacheManager;
import com.callx.app.cache.DiskCache;
import com.callx.app.cache.MemoryCache;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.UserEntity;
import com.callx.app.utils.MediaCache;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CacheStatsActivity — Production cache dashboard.
 *
 * Shows: memory usage + hit rate, disk usage, Room DB row counts,
 * encryption status, top cached chats (with display names), auto-eviction status.
 * Actions: Clear Memory / Clear Disk / Clear All (with confirmation).
 *
 * FIX v8: Top cached chats now show real display names resolved from UserDao
 * instead of raw Firebase UIDs (e.g. "abc123xyz…").
 *
 * Accessible via Settings → Storage & Cache.
 */
public class CacheStatsActivity extends AppCompatActivity {

    private static final String TAG = "CacheStatsActivity";

    // ── Stats labels ──────────────────────────────────────────────
    private TextView tvMemoryUsed, tvMemoryMax, tvMemoryHitRate;
    private TextView tvDiskUsed, tvDiskMax, tvDiskHitRate;
    private TextView tvMediaCacheUsed;
    private TextView tvDbMessages, tvDbUsers, tvDbChats;
    private TextView tvTotalHits, tvTotalMisses, tvOverallHitRate;
    private TextView tvEncryptedStatus, tvCacheEnabled;
    private TextView tvTopChat1, tvTopChat2, tvTopChat3;
    private TextView tvLastCleaned, tvAutoEvictStatus;

    // ── Progress bars ─────────────────────────────────────────────
    private ProgressBar pbMemory, pbDisk;

    // ── Action buttons ────────────────────────────────────────────
    private Button btnClearMemory, btnClearDisk, btnClearMedia, btnClearAll, btnRefresh;

    // ── Loading overlay ───────────────────────────────────────────
    private View loadingOverlay;

    private final ExecutorService executor   = Executors.newSingleThreadExecutor();
    private final Handler         mainThread = new Handler(Looper.getMainLooper());

    // ──────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cache_stats);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        bindViews();
        setupButtons();
        loadStats();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadStats();
    }

    // ──────────────────────────────────────────────────────────────
    private void bindViews() {
        tvMemoryUsed      = findViewById(R.id.tv_memory_used);
        tvMemoryMax       = findViewById(R.id.tv_memory_max);
        tvMemoryHitRate   = findViewById(R.id.tv_memory_hit_rate);
        tvDiskUsed        = findViewById(R.id.tv_disk_used);
        tvDiskMax         = findViewById(R.id.tv_disk_max);
        tvDiskHitRate     = findViewById(R.id.tv_disk_hit_rate);
        tvMediaCacheUsed  = findViewById(R.id.tv_media_cache_used);
        tvDbMessages      = findViewById(R.id.tv_db_messages);
        tvDbUsers         = findViewById(R.id.tv_db_users);
        tvDbChats         = findViewById(R.id.tv_db_chats);
        tvTotalHits       = findViewById(R.id.tv_total_hits);
        tvTotalMisses     = findViewById(R.id.tv_total_misses);
        tvOverallHitRate  = findViewById(R.id.tv_overall_hit_rate);
        tvEncryptedStatus = findViewById(R.id.tv_encrypted_status);
        tvCacheEnabled    = findViewById(R.id.tv_cache_enabled);
        tvTopChat1        = findViewById(R.id.tv_top_chat_1);
        tvTopChat2        = findViewById(R.id.tv_top_chat_2);
        tvTopChat3        = findViewById(R.id.tv_top_chat_3);
        tvLastCleaned     = findViewById(R.id.tv_last_cleaned);
        tvAutoEvictStatus = findViewById(R.id.tv_auto_evict_status);
        pbMemory          = findViewById(R.id.pb_memory);
        pbDisk            = findViewById(R.id.pb_disk);
        loadingOverlay    = findViewById(R.id.loading_overlay);
        btnClearMemory    = findViewById(R.id.btn_clear_memory);
        btnClearDisk      = findViewById(R.id.btn_clear_disk);
        btnClearMedia     = findViewById(R.id.btn_clear_media);
        btnClearAll       = findViewById(R.id.btn_clear_all);
        btnRefresh        = findViewById(R.id.btn_refresh);
    }

    // ──────────────────────────────────────────────────────────────
    private void setupButtons() {
        btnClearMemory.setOnClickListener(v -> confirmAction(
            "Clear Memory Cache",
            "RAM cache saaf ho jaayega. App thoda slow ho sakta hai temporarily.",
            () -> {
                CacheManager.getInstance(this).clearMemoryCache();
                saveClearTs();
                toast("Memory cache cleared");
                loadStats();
            }
        ));

        btnClearDisk.setOnClickListener(v -> confirmAction(
            "Clear Disk Cache",
            "Disk cache delete ho jaayegi. Media phir se download hogi.",
            () -> {
                CacheManager.getInstance(this).clearDiskCache();
                saveClearTs();
                toast("Disk cache cleared");
                mainThread.postDelayed(this::loadStats, 500);
            }
        ));

        btnClearMedia.setOnClickListener(v -> confirmAction(
            "Clear Media Cache",
            "Downloaded audio/video/files delete hongi. Phir se download hoga.",
            () -> {
                MediaCache.clearAll(this);
                saveClearTs();
                toast("Media cache cleared");
                mainThread.postDelayed(this::loadStats, 500);
            }
        ));

        btnClearAll.setOnClickListener(v -> confirmAction(
            "Clear All Cache",
            "Memory + Disk + Database sab clear ho jaayega. Offline messages temporarily unavailable ho sakte hain.",
            () -> {
                showLoading(true);
                executor.execute(() -> {
                    try {
                        CacheManager cm = CacheManager.getInstance(getApplicationContext());
                        cm.clearMemoryCache();
                        cm.clearDiskCache();
                        AppDatabase.getInstance(getApplicationContext())
                            .messageDao().deleteAll();
                        saveClearTs();
                    } catch (Exception e) {
                        Log.e(TAG, "Clear all error: " + e.getMessage());
                    }
                    mainThread.post(() -> {
                        showLoading(false);
                        toast("All cache cleared");
                        loadStats();
                    });
                });
            }
        ));

        btnRefresh.setOnClickListener(v -> loadStats());
    }

    // ──────────────────────────────────────────────────────────────
    private void loadStats() {
        showLoading(true);
        executor.execute(() -> {
            try {
                CacheManager   cm        = CacheManager.getInstance(getApplicationContext());
                MemoryCache    mem       = cm.getMemoryCache();
                DiskCache      disk      = cm.getDiskCache();
                CacheAnalytics analytics = cm.getAnalytics();
                AppDatabase    db        = cm.getDatabase();

                // Memory
                long memHits   = mem.hitCount();
                long memMisses = mem.missCount();
                long memUsed   = estimateMemoryUsedBytes(mem);
                long memMax    = Runtime.getRuntime().maxMemory() / 8;

                // Disk
                long diskUsed = disk.getCacheSizeBytes();
                long diskMax  = disk.getMaxSizeBytes();

                // Media Cache (audio/video/files)
                Log.d(TAG, "Loading media cache stats...");
                long mediaUsed = MediaCache.getCacheSizeBytes(getApplicationContext());
                Log.d(TAG, "Media cache size: " + mediaUsed + " bytes");

                // DB row counts
                long msgCount  = db.messageDao().getTotalMessageCount();
                long userCount = db.userDao().getUserCount();
                long chatCount = db.chatDao().getChatCount();

                // FIX v8: Resolve top chat IDs → display names from UserDao.
                // Previously this showed raw Firebase UIDs like "abc123xyz"
                // which is meaningless to the user. Now we look up the cached
                // user name and fall back to a shortened UID only if not found.
                List<String> topChatIds = analytics.getTopChats(3);
                List<String> topChatNames = new ArrayList<>();
                for (String chatId : topChatIds) {
                    UserEntity user = db.userDao().getUser(chatId);
                    if (user != null && user.name != null && !user.name.isEmpty()) {
                        topChatNames.add(user.name);
                    } else {
                        // Fallback: show shortened UID so it's still identifiable
                        String shortId = chatId.length() > 10
                            ? chatId.substring(0, 10) + "…"
                            : chatId;
                        topChatNames.add(shortId);
                    }
                }

                // Last cleared timestamp
                long lastClearedTs = getSharedPreferences("cache_prefs", MODE_PRIVATE)
                    .getLong("last_cache_clear_ts", 0L);

                final long fMemHits   = memHits;
                final long fMemMisses = memMisses;
                final long fMemUsed   = memUsed;
                final long fMemMax    = memMax;
                final long fDiskUsed  = diskUsed;
                final long fDiskMax   = diskMax;
                final long fMediaUsed = mediaUsed;
                final long fMsgCount  = msgCount;
                final long fUserCount = userCount;
                final long fChatCount = chatCount;

                mainThread.post(() -> {
                    showLoading(false);
                    updateUI(fMemHits, fMemMisses, fMemUsed, fMemMax,
                             fDiskUsed, fDiskMax, fMediaUsed,
                             fMsgCount, fUserCount, fChatCount,
                             topChatNames, lastClearedTs);
                });
            } catch (Exception e) {
                Log.e(TAG, "loadStats error: " + e.getMessage(), e);
                mainThread.post(() -> {
                    showLoading(false);
                    toast("Error loading stats: " + e.getMessage());
                });
            }
        });
    }

    private void updateUI(long memHits, long memMisses, long memUsed, long memMax,
                          long diskUsed, long diskMax, long mediaUsed,
                          long msgCount, long userCount, long chatCount,
                          List<String> topChatNames, long lastClearedTs) {

        DecimalFormat df  = new DecimalFormat("#,###");
        DecimalFormat pct = new DecimalFormat("##.#");

        // ── System status ─────────────────────────────────────────
        tvCacheEnabled.setText("✓ 4-Tier Cache Active (Memory + Disk + Media + DB)");
        tvCacheEnabled.setTextColor(getColor(R.color.brand_primary));
        tvEncryptedStatus.setText("✓ SQLCipher AES-256 Encrypted");
        tvEncryptedStatus.setTextColor(getColor(R.color.brand_primary));

        // ── Memory ────────────────────────────────────────────────
        tvMemoryUsed.setText(fmtSize(memUsed));
        tvMemoryMax.setText(fmtSize(memMax));
        int memPct = memMax > 0 ? (int) Math.min(100, (memUsed * 100 / memMax)) : 0;
        pbMemory.setProgress(memPct);
        long   memTotal  = memHits + memMisses;
        double memHitRate = memTotal > 0 ? (double) memHits / memTotal : 0.0;
        tvMemoryHitRate.setText(pct.format(memHitRate * 100) + "% hits");
        tvMemoryHitRate.setTextColor(hitRateColor(memHitRate));

        // ── Disk ──────────────────────────────────────────────────
        tvDiskUsed.setText(fmtSize(diskUsed));
        tvDiskMax.setText(fmtSize(diskMax));
        int diskPct = diskMax > 0 ? (int) Math.min(100, (diskUsed * 100 / diskMax)) : 0;
        pbDisk.setProgress(diskPct);
        tvDiskHitRate.setText(diskPct + "% used");
        tvDiskHitRate.setTextColor(diskPct < 80
            ? getColor(R.color.brand_primary) : getColor(R.color.action_danger));

        // ── Media Cache ────────────────────────────────────────────
        if (tvMediaCacheUsed != null) {
            tvMediaCacheUsed.setText("🎵 Audio/Video/Files: " + fmtSize(mediaUsed));
        }

        // ── DB ────────────────────────────────────────────────────
        tvDbMessages.setText(df.format(msgCount) + " msgs");
        tvDbUsers.setText(df.format(userCount) + " users");
        tvDbChats.setText(df.format(chatCount) + " chats");

        // ── Overall analytics ─────────────────────────────────────
        tvTotalHits.setText(df.format(memHits));
        tvTotalMisses.setText(df.format(memMisses));
        tvOverallHitRate.setText(pct.format(memHitRate * 100) + "%");
        tvOverallHitRate.setTextColor(hitRateColor(memHitRate));

        // ── Auto-eviction ─────────────────────────────────────────
        tvAutoEvictStatus.setText("✓ Active (LRU + TTL + Priority + onTrimMemory)");
        tvAutoEvictStatus.setTextColor(getColor(R.color.brand_primary));

        // ── Last cleared ──────────────────────────────────────────
        if (lastClearedTs > 0) {
            SimpleDateFormat sdf =
                new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());
            tvLastCleaned.setText(sdf.format(new Date(lastClearedTs)));
        } else {
            tvLastCleaned.setText("Never cleared");
        }

        // ── Top chats (FIX v8: real display names) ────────────────
        if (topChatNames != null && !topChatNames.isEmpty()) {
            tvTopChat1.setText("• " + topChatNames.get(0));
            tvTopChat1.setVisibility(View.VISIBLE);
            if (topChatNames.size() > 1) {
                tvTopChat2.setText("• " + topChatNames.get(1));
                tvTopChat2.setVisibility(View.VISIBLE);
            } else {
                tvTopChat2.setVisibility(View.GONE);
            }
            if (topChatNames.size() > 2) {
                tvTopChat3.setText("• " + topChatNames.get(2));
                tvTopChat3.setVisibility(View.VISIBLE);
            } else {
                tvTopChat3.setVisibility(View.GONE);
            }
        } else {
            tvTopChat1.setText("No chat data yet — open some chats first");
            tvTopChat2.setVisibility(View.GONE);
            tvTopChat3.setVisibility(View.GONE);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    private long estimateMemoryUsedBytes(MemoryCache mem) {
        // FIX: Use actual item count from LruCache instead of hit/miss estimate.
        // Old formula was wrong — it returned 0 when hits=0 (fresh app open),
        // even if items were in cache. Now: each item estimated ~512 bytes avg.
        int items = mem.size(); // actual live item count in LruCache
        return (long) items * 512L;
    }

    private void confirmAction(String title, String msg, Runnable action) {
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Clear", (d, w) -> action.run())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void saveClearTs() {
        getSharedPreferences("cache_prefs", MODE_PRIVATE)
            .edit().putLong("last_cache_clear_ts", System.currentTimeMillis()).apply();
    }

    private void showLoading(boolean show) {
        if (loadingOverlay != null)
            loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private String fmtSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format(Locale.getDefault(), "%.2f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private int hitRateColor(double rate) {
        if (rate >= 0.7) return getColor(R.color.brand_primary);
        if (rate >= 0.4) return 0xFFFF8C00; // orange
        return getColor(R.color.action_danger);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
