package com.callx.app.cache;

import android.util.Log;

import com.callx.app.models.ReelModel;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReelUiStatePrecomputer — PERF advance: "precompute next reel's UI state
 * (like counts, captions) before swipe completes".
 *
 * Mirrors ReelThumbnailPreloader/ReelVideoPreloader's shape exactly: called
 * from ReelsFragment.onPageSelected() with the current reel list + position,
 * walks the current reel plus PRELOAD_COUNT reels ahead and computes+caches their formatted UI
 * strings into ReelUiStateCache (see that class' doc for why this is worth
 * doing off the swipe-completion frame). Runs on a single background thread
 * — this is cheap string formatting, not I/O, so one thread is plenty and
 * keeps it from competing with the video/thumbnail preloaders' threads.
 */
public final class ReelUiStatePrecomputer {

    private static final String TAG = "UiStatePrecomputer";
    private static final int PRELOAD_COUNT = 3; // matches N+1..N+3 prewarm window elsewhere

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "reel-ui-state-precompute");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> pendingIds = new HashSet<>();

    private volatile boolean shutdown = false;

    public void precomputeFrom(List<ReelModel> reels, int position) {
        if (shutdown || reels == null || reels.isEmpty()) return;

        // Include the current item. renderPage() calls this before the first
        // page is selected, so its metadata is ready before the first bind.
        for (int i = Math.max(0, position);
             i <= position + PRELOAD_COUNT && i < reels.size(); i++) {
            ReelModel reel = reels.get(i);
            if (reel == null || reel.reelId == null) continue;
            if (ReelUiStateCache.get(reel.reelId) != null) continue; // already cached
            synchronized (pendingIds) {
                if (!pendingIds.add(reel.reelId)) continue; // already queued
            }

            executor.execute(() -> {
                try {
                    if (shutdown) return;
                    ReelUiStateCache.compute(reel);
                } catch (Exception e) {
                    Log.w(TAG, "precompute failed for " + reel.reelId + ": " + e.getMessage());
                } finally {
                    synchronized (pendingIds) {
                        pendingIds.remove(reel.reelId);
                    }
                }
            });
        }
    }

    public void shutdown() {
        shutdown = true;
        executor.shutdownNow();
        synchronized (pendingIds) {
            pendingIds.clear();
        }
    }
}
