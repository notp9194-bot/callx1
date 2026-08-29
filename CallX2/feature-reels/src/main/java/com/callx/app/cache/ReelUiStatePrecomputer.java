package com.callx.app.cache;

import android.util.Log;

import com.callx.app.models.ReelModel;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReelUiStatePrecomputer — PERF advance: "precompute next reel's UI state
 * (like counts, captions) before swipe completes".
 *
 * Mirrors ReelThumbnailPreloader/ReelVideoPreloader's shape exactly: called
 * from ReelsFragment.onPageSelected() with the current reel list + position,
 * walks PRELOAD_COUNT reels ahead and computes+caches their formatted UI
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

    private volatile boolean shutdown = false;

    public void precomputeFrom(List<ReelModel> reels, int position) {
        if (shutdown || reels == null || reels.isEmpty()) return;

        for (int i = position + 1; i <= position + PRELOAD_COUNT && i < reels.size(); i++) {
            ReelModel reel = reels.get(i);
            if (reel == null || reel.reelId == null) continue;
            if (ReelUiStateCache.get(reel.reelId) != null) continue; // already cached

            executor.execute(() -> {
                if (shutdown) return;
                try {
                    ReelUiStateCache.compute(reel);
                } catch (Exception e) {
                    Log.w(TAG, "precompute failed for " + reel.reelId + ": " + e.getMessage());
                }
            });
        }
    }

    public void shutdown() {
        shutdown = true;
        executor.shutdownNow();
    }
}
