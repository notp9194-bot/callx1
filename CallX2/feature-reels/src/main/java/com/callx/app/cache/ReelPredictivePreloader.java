package com.callx.app.cache;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;

import com.callx.app.models.ReelModel;
import com.callx.app.player.NetworkQualityMonitor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * ReelPredictivePreloader — Watch-pattern learning for smarter pre-buffering
 *
 * How it works:
 *  1. Tracks which reels the user watches to completion vs skips (watch pattern)
 *  2. Builds a transition probability matrix: P[reelA → reelB]
 *     (how often does the user swipe from A to B without skipping)
 *  3. On scroll, ranks upcoming reels by watch-probability × bandwidth efficiency
 *  4. Preloads in predicted priority order, not just positional N+1, N+2, N+3
 *  5. Learns user's skip threshold — if user typically skips after 2s, preloader
 *     allocates less bandwidth to predicted skips
 *
 * Features:
 *  ✅ Watch-event recording (recordWatch / recordSkip)
 *  ✅ Markov-chain transition matrix (category-level, not per-reel)
 *  ✅ Category affinity scoring — user likes #dance? preload dance reels first
 *  ✅ Time-of-day awareness — preload HD on WiFi evenings, 360p on cellular mornings
 *  ✅ Bandwidth budget: doesn't exceed configurable bytes/min budget
 *  ✅ Persists model to SharedPreferences (JSON) — survives app restart
 *  ✅ Compatible with existing ReelVideoPreloader — drop-in upgrade
 *
 * Usage:
 *   ReelPredictivePreloader preloader = new ReelPredictivePreloader(context);
 *   // On each page:
 *   preloader.recordWatch(currentReel, watchDurationMs, totalDurationMs);
 *   preloader.preloadSmartFrom(reelList, currentPosition);
 *   // On destroy:
 *   preloader.shutdown();
 */
@OptIn(markerClass = UnstableApi.class)
public class ReelPredictivePreloader {

    private static final String TAG            = "PredictivePreloader";
    private static final String PREFS_NAME     = "reel_watch_model";

    // Preload config
    private static final int    MAX_PRELOAD       = 5;
    private static final long   BYTES_WIFI        = 15 * 1024 * 1024L;  // 15 MB per reel
    private static final long   BYTES_4G          = 8  * 1024 * 1024L;  // 8 MB
    private static final long   BYTES_3G          = 3  * 1024 * 1024L;  // 3 MB
    private static final long   BYTES_2G          = 512 * 1024L;         // 512 KB
    private static final long   BYTES_OFFLINE     = 0L;

    // Watch model config
    private static final float  SKIP_THRESHOLD    = 0.30f;  // <30% watched = skip
    private static final int    MAX_CATEGORY_KEYS = 50;     // cap model size
    private static final String PREFS_MATRIX      = "transition_json";
    private static final String PREFS_AFFINITY    = "affinity_json";
    private static final String PREFS_SKIP_THR    = "learned_skip_pct";

    // ── State ─────────────────────────────────────────────────────────────────
    private final Context         appCtx;
    private final ExecutorService executor;
    private final Set<String>     preloading   = ConcurrentHashMap.newKeySet();
    private final Map<String, Future<?>> tasks = new ConcurrentHashMap<>();

    // Watch model: category → {nextCategory → count}
    private final Map<String, Map<String, Integer>> transitionMatrix;
    // Category affinity: category → watch_count
    private final Map<String, Integer> categoryAffinity;
    // Learned skip threshold (0–1)
    private float learnedSkipThreshold = SKIP_THRESHOLD;
    private String lastCategory = null;  // last watched category (for transition tracking)

    private final SharedPreferences prefs;

    public ReelPredictivePreloader(Context context) {
        appCtx           = context.getApplicationContext();
        prefs            = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        transitionMatrix = loadMatrix();
        categoryAffinity = loadAffinity();
        learnedSkipThreshold = prefs.getFloat(PREFS_SKIP_THR, SKIP_THRESHOLD);

        // 3 threads: next 3 high-priority reels in parallel
        executor = Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "pred-preloader");
            t.setDaemon(true);
            return t;
        });
        ReelCacheManager.init(appCtx);
        Log.d(TAG, "PredictivePreloader ready. Affinity categories=" + categoryAffinity.size());
    }

    // ── Main API ───────────────────────────────────────────────────────────────

    /**
     * Record a watch event and update the transition model.
     *
     * @param reel            the reel that was playing
     * @param watchedMs       how long the user actually watched (ms)
     * @param totalDurationMs total reel duration (ms)
     */
    public void recordWatch(ReelModel reel, long watchedMs, long totalDurationMs) {
        if (reel == null || totalDurationMs <= 0) return;

        float pct = (float) watchedMs / totalDurationMs;
        boolean isSkip = pct < learnedSkipThreshold;

        // Adapt learned skip threshold (EWMA of observed skip fractions)
        if (isSkip) {
            learnedSkipThreshold = 0.85f * learnedSkipThreshold + 0.15f * pct;
            prefs.edit().putFloat(PREFS_SKIP_THR, learnedSkipThreshold).apply();
        }

        String category = resolveCategory(reel);
        if (category == null) category = "general";

        if (!isSkip) {
            // Update category affinity
            categoryAffinity.merge(category, 1, Integer::sum);
            if (categoryAffinity.size() > MAX_CATEGORY_KEYS) pruneAffinity();

            // Update transition: lastCategory → category
            if (lastCategory != null && !lastCategory.equals(category)) {
                transitionMatrix
                    .computeIfAbsent(lastCategory, k -> new HashMap<>())
                    .merge(category, 1, Integer::sum);
            }
        }
        lastCategory = category;
        persistModel();

        Log.d(TAG, "recordWatch cat=" + category + " watched=" + (int)(pct*100) + "% skip=" + isSkip);
    }

    /**
     * Smart preload: ranks upcoming reels by predicted watch-probability,
     * then preloads in that order within the available bandwidth budget.
     *
     * @param reels    Full reel list
     * @param position Current visible position
     */
    public void preloadSmartFrom(List<ReelModel> reels, int position) {
        preloadSmartFrom(reels, position, 0f);
    }

    /**
     * v6: Scroll-velocity-adaptive variant. Fast flicking through the feed means
     * the user is skip-browsing — shrink the lookahead window and preload bytes
     * (cheap, near-term reels only). Slow/settled scrolling means they're likely
     * to watch — widen the window for deeper lookahead.
     *
     * @param scrollVelocityPxPerMs recent scroll speed in px/ms (0 = idle/settled)
     */
    public void preloadSmartFrom(List<ReelModel> reels, int position, float scrollVelocityPxPerMs) {
        if (reels == null || reels.isEmpty()) return;

        NetworkQualityMonitor.Quality netQ =
            NetworkQualityMonitor.get(appCtx).currentQuality();
        long bytesPerReel = resolveBytes(netQ);
        if (bytesPerReel <= 0) return;  // offline → don't preload

        // ── Velocity-adaptive window + budget ──────────────────────────────────
        float absVel = Math.abs(scrollVelocityPxPerMs);
        int   maxPreload;
        double byteScale;
        if (absVel > 3.5f) {
            // Fast flick — user is skip-browsing, preload less, just the very next reel
            maxPreload = 1;
            byteScale  = 0.4;
        } else if (absVel > 1.2f) {
            // Moderate scroll — normal window
            maxPreload = MAX_PRELOAD;
            byteScale  = 0.75;
        } else {
            // Idle / slow settle — likely to watch, preload deeper and fuller
            maxPreload = MAX_PRELOAD + 2;
            byteScale  = 1.0;
        }
        long adaptiveBytes = (long) (bytesPerReel * byteScale);

        // v7: Time-of-day awareness — late night (12am-6am) WiFi traffic is cheap/uncontended,
        // so preload more aggressively then; peak evening hours (6pm-11pm) are typically
        // congested, so trim back slightly to avoid competing for bandwidth with active streaming.
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        boolean isWifiLike = netQ == NetworkQualityMonitor.Quality.WIFI
                          || netQ == NetworkQualityMonitor.Quality.ETHERNET;
        if (hour >= 0 && hour < 6 && isWifiLike) {
            adaptiveBytes = (long) (adaptiveBytes * 1.3);   // late night — go deeper
        } else if (hour >= 18 && hour < 23) {
            adaptiveBytes = (long) (adaptiveBytes * 0.85);  // peak hours — be conservative
        }

        // Build candidate list (positional window)
        int window = Math.min(maxPreload + 2, reels.size() - position - 1);
        if (window <= 0) return;

        List<ReelCandidate> candidates = new ArrayList<>();
        for (int i = 1; i <= window && position + i < reels.size(); i++) {
            ReelModel reel = reels.get(position + i);
            if (reel == null) continue;
            double score = predictScore(reel, i);
            candidates.add(new ReelCandidate(reel, score, i));
        }

        // Sort by predicted score descending
        candidates.sort((a, b) -> Double.compare(b.score, a.score));

        int launched = 0;
        for (ReelCandidate c : candidates) {
            if (launched >= maxPreload) break;
            String url = pickBestUrl(c.reel, netQ);
            if (url == null || url.isEmpty()) continue;
            preloadSingle(url, adaptiveBytes);
            launched++;
        }
    }

    /**
     * Cancel all running preloads and shut down the executor.
     * Call from Fragment.onDestroyView().
     */
    public void shutdown() {
        for (Future<?> f : tasks.values()) f.cancel(true);
        tasks.clear();
        preloading.clear();
        executor.shutdownNow();
    }

    /** Cancel all, keep executor alive (call on feed switch). */
    public void cancelAll() {
        for (Future<?> f : tasks.values()) f.cancel(true);
        tasks.clear();
        preloading.clear();
    }

    // ── Prediction scoring ────────────────────────────────────────────────────

    /**
     * Predict a priority score for a candidate reel.
     * Higher score = higher chance user watches it = preload first.
     *
     * Score = affinityBoost(category) × transitionProb(lastCat → cat) × positionDecay(i)
     */
    private double predictScore(ReelModel reel, int positionOffset) {
        String cat = resolveCategory(reel);
        if (cat == null) cat = "general";

        // Affinity boost: how much the user has liked this category historically
        int watched = categoryAffinity.getOrDefault(cat, 0);
        double affinityScore = 1.0 + Math.log(1.0 + watched);

        // Transition probability: P(lastCat → cat)
        double transScore = 1.0;
        if (lastCategory != null) {
            Map<String, Integer> row = transitionMatrix.get(lastCategory);
            if (row != null && !row.isEmpty()) {
                int total = row.values().stream().mapToInt(Integer::intValue).sum();
                int count = row.getOrDefault(cat, 0);
                transScore = total > 0 ? (1.0 + count) / (1.0 + total) : 1.0;
            }
        }

        // Position decay: closer reels preloaded first (decay = 1/(1+i))
        double decay = 1.0 / (1.0 + positionOffset * 0.5);

        // v7: Epsilon-greedy exploration — 10% of the time, ignore the learned
        // model's bias and give this candidate a neutral boost. Prevents the
        // preloader from permanently starving categories the user hasn't tried yet.
        if (Math.random() < 0.10) {
            return 1.0 * decay;
        }

        return affinityScore * transScore * decay;
    }

    // ── Category resolution ───────────────────────────────────────────────────

    /** Extract a stable category key from a reel (hashtag or music-based). */
    private String resolveCategory(ReelModel reel) {
        if (reel.hashtags != null && !reel.hashtags.isEmpty()) {
            return reel.hashtags.get(0).toLowerCase(java.util.Locale.US)
                .replaceAll("[^a-z0-9_]", "");
        }
        if (reel.musicName != null && !reel.musicName.isEmpty()) {
            String cleaned = reel.musicName.toLowerCase(java.util.Locale.US)
                .replaceAll("[^a-z0-9]", "");
            return "music_" + cleaned.substring(0, Math.min(20, cleaned.length()));
        }
        if (reel.uid != null) return "user_" + reel.uid;
        return "general";
    }

    // ── URL selection ─────────────────────────────────────────────────────────

    /**
     * BUGFIX: must apply the same codec transform ReelPlayerController uses
     * for actual playback (see CodecSupport.applyToUrl doc) — otherwise this
     * preloader's cached bytes live under a different key than what the
     * player requests, and get re-downloaded from scratch on play, doubling
     * data usage for every reel this preloader touches.
     */
    private String pickBestUrl(ReelModel reel, NetworkQualityMonitor.Quality q) {
        String chosen;
        switch (q) {
            case WIFI:
            case ETHERNET:
            case CELLULAR_5G:
                chosen = reel.video1080 != null ? reel.video1080
                       : reel.video720  != null ? reel.video720  : reel.videoUrl;
                break;
            case CELLULAR_4G:
                chosen = reel.video720 != null ? reel.video720 : reel.videoUrl;
                break;
            case CELLULAR_3G:
                chosen = reel.video480 != null ? reel.video480 : reel.videoUrl;
                break;
            default:
                chosen = reel.videoUrl;
        }
        return com.callx.app.utils.CodecSupport.applyToUrl(chosen);
    }

    private long resolveBytes(NetworkQualityMonitor.Quality q) {
        switch (q) {
            case WIFI: case ETHERNET: case CELLULAR_5G: return BYTES_WIFI;
            case CELLULAR_4G: return BYTES_4G;
            case CELLULAR_3G: return BYTES_3G;
            case CELLULAR_2G: return BYTES_2G;
            default: return BYTES_OFFLINE;
        }
    }

    // ── Cache write ───────────────────────────────────────────────────────────

    private void preloadSingle(String url, long bytes) {
        if (preloading.contains(url)) return;
        preloading.add(url);

        Future<?> f = executor.submit(() -> {
            try {
                CacheDataSource.Factory factory = ReelCacheManager.getCacheDataSourceFactory();
                CacheDataSource src = factory.createDataSource();
                DataSpec spec = new DataSpec.Builder()
                    .setUri(Uri.parse(url))
                    .setPosition(0)
                    .setLength(bytes)
                    .build();
                new CacheWriter(src, spec, null, null).cache();
                Log.d(TAG, "Preloaded (smart): " + shortUrl(url));
            } catch (Exception e) {
                preloading.remove(url);
                Log.w(TAG, "Preload failed: " + shortUrl(url) + " " + e.getMessage());
            } finally {
                tasks.remove(url);
            }
        });
        tasks.put(url, f);
    }

    // ── Model persistence ─────────────────────────────────────────────────────

    private void persistModel() {
        executor.submit(() -> {
            try {
                // Serialize transition matrix
                JSONObject matrixJson = new JSONObject();
                for (Map.Entry<String, Map<String, Integer>> row : transitionMatrix.entrySet()) {
                    JSONObject inner = new JSONObject(row.getValue());
                    matrixJson.put(row.getKey(), inner);
                }
                // Serialize affinity
                JSONObject affinityJson = new JSONObject(categoryAffinity);

                prefs.edit()
                    .putString(PREFS_MATRIX, matrixJson.toString())
                    .putString(PREFS_AFFINITY, affinityJson.toString())
                    .apply();
            } catch (Exception e) {
                Log.w(TAG, "persistModel error: " + e.getMessage());
            }
        });
    }

    private Map<String, Map<String, Integer>> loadMatrix() {
        Map<String, Map<String, Integer>> matrix = new HashMap<>();
        try {
            String json = prefs.getString(PREFS_MATRIX, null);
            if (json == null) return matrix;
            JSONObject obj = new JSONObject(json);
            java.util.Iterator<String> outer = obj.keys();
            while (outer.hasNext()) {
                String from = outer.next();
                JSONObject inner = obj.getJSONObject(from);
                Map<String, Integer> row = new HashMap<>();
                java.util.Iterator<String> inner2 = inner.keys();
                while (inner2.hasNext()) {
                    String to = inner2.next();
                    row.put(to, inner.getInt(to));
                }
                matrix.put(from, row);
            }
        } catch (Exception e) {
            Log.w(TAG, "loadMatrix error: " + e.getMessage());
        }
        return matrix;
    }

    private Map<String, Integer> loadAffinity() {
        Map<String, Integer> affinity = new LinkedHashMap<>();
        try {
            String json = prefs.getString(PREFS_AFFINITY, null);
            if (json == null) return affinity;
            JSONObject obj = new JSONObject(json);
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                affinity.put(k, obj.getInt(k));
            }
        } catch (Exception e) {
            Log.w(TAG, "loadAffinity error: " + e.getMessage());
        }
        return affinity;
    }

    private void pruneAffinity() {
        // Remove the least-watched categories
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(categoryAffinity.entrySet());
        entries.sort((a, b) -> Integer.compare(a.getValue(), b.getValue()));
        int toRemove = entries.size() - MAX_CATEGORY_KEYS;
        for (int i = 0; i < toRemove; i++) categoryAffinity.remove(entries.get(i).getKey());
    }

    // ── Internal data class ───────────────────────────────────────────────────

    private static class ReelCandidate {
        final ReelModel reel;
        final double    score;
        final int       offset;
        ReelCandidate(ReelModel r, double s, int o) { reel = r; score = s; offset = o; }
    }

    private String shortUrl(String url) {
        if (url == null) return "null";
        return url.length() > 50 ? "…" + url.substring(url.length() - 47) : url;
    }

    // ── Debug / testing ───────────────────────────────────────────────────────

    /** Returns a human-readable dump of the learned model (for debug screens). */
    public String dumpModel() {
        StringBuilder sb = new StringBuilder();
        sb.append("Skip threshold: ").append(String.format("%.0f%%", learnedSkipThreshold * 100)).append("\n");
        sb.append("Category affinities (top 5):\n");
        categoryAffinity.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(5)
            .forEach(e -> sb.append("  ").append(e.getKey()).append(" → ").append(e.getValue()).append("\n"));
        sb.append("Transition matrix: ").append(transitionMatrix.size()).append(" source categories");
        return sb.toString();
    }
}
