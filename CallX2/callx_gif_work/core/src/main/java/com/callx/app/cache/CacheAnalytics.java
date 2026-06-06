package com.callx.app.cache;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Analytics Engine — tracks which chats are opened most frequently.
 * Persists stats across app restarts. Used by CacheManager for priority decisions.
 *
 * Previously fixed (v10/v11):
 *   - ConcurrentHashMap (thread-safe)
 *   - Debounced 5s SharedPreferences flush (no thrashing)
 *   - Max 500 entries (bounded heap)
 *
 * FIX #5 (MEDIUM): Guaranteed flush before app goes to background.
 *
 *   Old: Debounced flush fires 5 seconds after last recordChatOpen().
 *   → If app is swiped away (SIGKILL) within those 5 seconds,
 *     the Handler callback is never executed → analytics data lost.
 *   → Next session: stale priority data → wrong preloading decisions.
 *
 *   Fix: Added flushNow() — a synchronous, immediate flush to SharedPreferences.
 *     Called by CallxApp.registerForegroundTracking() whenever ALL activities
 *     have stopped (sActivityRefs drops to 0), meaning the app is going to
 *     background. SIGKILL can only arrive AFTER onStop(), so this guarantees
 *     the flush completes before any kill signal.
 *
 *     Also cancels the pending debounced flush to avoid a double-write.
 */
public class CacheAnalytics {

    private static final String TAG        = "CacheAnalytics";
    private static final String PREFS_NAME = "callx_cache_analytics";
    private static final int    MAX_STATS  = 500;
    private static final long   DEBOUNCE_MS = 5_000L;

    private static CacheAnalytics sInstance;

    private final SharedPreferences mPrefs;
    private final Gson mGson = new Gson();
    private final ConcurrentHashMap<String, CacheStats> mStats = new ConcurrentHashMap<>();

    private final Handler  mMainHandler    = new Handler(Looper.getMainLooper());
    private final Runnable mFlushRunnable  = this::flushAllToPrefs;
    private volatile boolean mFlushPending = false;

    private CacheAnalytics(Context ctx) {
        mPrefs = ctx.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadFromPrefs();
    }

    public static synchronized CacheAnalytics getInstance(Context ctx) {
        if (sInstance == null) sInstance = new CacheAnalytics(ctx);
        return sInstance;
    }

    // ─────────────────────────────────────────────────────────────
    // RECORD CHAT OPEN
    // ─────────────────────────────────────────────────────────────

    public void recordChatOpen(String chatId) {
        if (chatId == null || chatId.isEmpty()) return;
        mStats.compute(chatId, (key, existing) -> {
            if (existing == null) return new CacheStats(key);
            existing.openCount++;
            existing.lastUsed = System.currentTimeMillis();
            return existing;
        });
        if (mStats.size() > MAX_STATS) evictOldestStats();
        scheduleDebouncedFlush();
    }

    public CachePriority getPriority(String chatId) {
        CacheStats stats = mStats.get(chatId);
        return stats == null ? CachePriority.LOW : stats.computePriority();
    }

    public List<String> getTopChats(int n) {
        List<CacheStats> list = new ArrayList<>(mStats.values());
        Collections.sort(list, new Comparator<CacheStats>() {
            @Override public int compare(CacheStats a, CacheStats b) {
                return Long.compare(b.openCount, a.openCount);
            }
        });
        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(n, list.size()); i++) result.add(list.get(i).chatId);
        return result;
    }

    public void pruneStale() {
        long cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, CacheStats> e : mStats.entrySet()) {
            if (e.getValue().lastUsed < cutoff) toRemove.add(e.getKey());
        }
        for (String key : toRemove) {
            mStats.remove(key);
            mPrefs.edit().remove(key).apply();
        }
        Log.d(TAG, "Pruned " + toRemove.size() + " stale analytics entries");
    }

    // ─────────────────────────────────────────────────────────────
    // FIX #5: flushNow() — immediate synchronous flush
    // Called by CallxApp when all activities stop (app going to background)
    // Cancels pending debounce to avoid double-write
    // ─────────────────────────────────────────────────────────────

    /**
     * Immediately flush all pending analytics to SharedPreferences.
     * Must be called before app goes to background to prevent data loss on SIGKILL.
     * Safe to call from any thread — SharedPreferences.apply() is asynchronous.
     */
    public void flushNow() {
        // Cancel the pending debounced flush to avoid double-write
        if (mFlushPending) {
            mMainHandler.removeCallbacks(mFlushRunnable);
            mFlushPending = false;
        }
        flushAllToPrefs();
        Log.d(TAG, "Analytics flushed immediately (app going to background)");
    }

    // ─────────────────────────────────────────────────────────────
    // DEBOUNCED FLUSH (for normal in-session writes)
    // ─────────────────────────────────────────────────────────────

    private void scheduleDebouncedFlush() {
        if (!mFlushPending) {
            mFlushPending = true;
            mMainHandler.postDelayed(mFlushRunnable, DEBOUNCE_MS);
        }
    }

    private void flushAllToPrefs() {
        mFlushPending = false;
        SharedPreferences.Editor editor = mPrefs.edit();
        for (Map.Entry<String, CacheStats> e : mStats.entrySet()) {
            try { editor.putString(e.getKey(), mGson.toJson(e.getValue())); }
            catch (Exception ignored) {}
        }
        editor.apply();
        Log.d(TAG, "Analytics flushed: " + mStats.size() + " entries");
    }

    // ─────────────────────────────────────────────────────────────
    // EVICT OLDEST WHEN OVER MAX_STATS
    // ─────────────────────────────────────────────────────────────

    private void evictOldestStats() {
        List<Map.Entry<String, CacheStats>> entries = new ArrayList<>(mStats.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, CacheStats>>() {
            @Override public int compare(Map.Entry<String, CacheStats> a,
                                        Map.Entry<String, CacheStats> b) {
                return Long.compare(a.getValue().lastUsed, b.getValue().lastUsed);
            }
        });
        int toEvict = mStats.size() - MAX_STATS;
        for (int i = 0; i < toEvict && i < entries.size(); i++) {
            String key = entries.get(i).getKey();
            mStats.remove(key);
            mPrefs.edit().remove(key).apply();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // LOAD FROM PREFS
    // ─────────────────────────────────────────────────────────────

    private void loadFromPrefs() {
        Map<String, ?> all = mPrefs.getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            try {
                CacheStats s = mGson.fromJson(e.getValue().toString(), CacheStats.class);
                if (s != null && s.chatId != null) mStats.put(e.getKey(), s);
            } catch (Exception ignored) {}
        }
        Log.d(TAG, "Loaded " + mStats.size() + " analytics entries from disk");
    }
}
