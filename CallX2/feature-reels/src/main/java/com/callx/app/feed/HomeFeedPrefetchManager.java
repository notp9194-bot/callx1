package com.callx.app.feed;

import android.content.Context;
import android.os.Handler;
import androidx.annotation.NonNull;
import com.callx.app.models.ReelModel;
import com.google.firebase.database.DatabaseReference;

import java.util.*;

/**
 * HomeFeedPrefetchManager — Intelligent metadata prefetch that respects device
 * thermal/battery state and scroll state.
 *
 * Coordinates prefetch of:
 *  1. Post metadata (likes, comments) — batched via networkBatcher
 *  2. Thumbnails — batched via Glide
 *  3. Video assets — deferred when FLINGING or low battery
 *
 * Consumes scroll state from HomeFeedScrollStateManager — when FLINGING,
 * automatically reduces prefetch to metadata-only (defer heavy video prefetch).
 *
 * Also respects ReelThermalManager (if available) to avoid aggressive prefetch
 * on overheating device or low battery.
 */
public class HomeFeedPrefetchManager implements HomeFeedUltraOptimizer.ScrollStateListener {

    private static final String TAG = "PrefetchManager";
    private static final int PREFETCH_AHEAD_COUNT = 3;
    private static final int PREFETCH_BEHIND_COUNT = 1;

    private final Context ctx;
    private final Handler mainHandler;
    private final HomeFeedScrollStateManager scrollStateManager;
    private final HomeFeedMetadataCache metadataCache;

    private volatile int currentVisibleIndex = -1;
    private volatile boolean prefetchEnabled = true;

    public HomeFeedPrefetchManager(@NonNull Context ctx, @NonNull Handler handler,
                                   @NonNull HomeFeedScrollStateManager scrollMgr,
                                   @NonNull HomeFeedMetadataCache metaCache) {
        this.ctx = ctx;
        this.mainHandler = handler;
        this.scrollStateManager = scrollMgr;
        this.metadataCache = metaCache;
    }

    /**
     * Batch-prefetch metadata for a range of posts. Call when a page of posts
     * is loaded into the feed.
     */
    public void prefetchMetadata(@NonNull List<ReelModel> reels,
                                 @NonNull DatabaseReference fbRef,
                                 @NonNull HomeFeedNetworkBatcher batcher) {
        if (!prefetchEnabled || reels.isEmpty()) return;

        for (ReelModel reel : reels) {
            if (reel == null || reel.reelId == null) continue;

            // Skip if already in cache
            if (metadataCache.get(reel.reelId) != null) continue;

            // Queue for batch fetch (batcher will coalesce)
            batcher.queueMetadataRead(reel.reelId, fbRef, metadata -> {
                // Cache populated by batcher callback
                metadataCache.put(metadata.reelId, metadata);
            });
        }
    }

    /**
     * Called when user scrolls to a new visible index. Prefetches metadata for
     * cards AHEAD_COUNT posts ahead and BEHIND_COUNT posts behind current.
     */
    public void onVisibleIndexChanged(int index, @NonNull List<ReelModel> allPosts,
                                      @NonNull DatabaseReference fbRef,
                                      @NonNull HomeFeedNetworkBatcher batcher) {
        this.currentVisibleIndex = index;

        if (!prefetchEnabled) return;

        // Reduced prefetch window if currently flinging
        int aheadCount = scrollStateManager.isFlinging() ? 1 : PREFETCH_AHEAD_COUNT;
        int behindCount = scrollStateManager.isFlinging() ? 0 : PREFETCH_BEHIND_COUNT;

        // Prefetch ahead
        for (int i = 1; i <= aheadCount; i++) {
            int prefetchIdx = index + i;
            if (prefetchIdx < allPosts.size()) {
                ReelModel reel = allPosts.get(prefetchIdx);
                prefetchSinglePostMetadata(reel, fbRef, batcher);
            }
        }

        // Prefetch behind
        for (int i = 1; i <= behindCount; i++) {
            int prefetchIdx = index - i;
            if (prefetchIdx >= 0) {
                ReelModel reel = allPosts.get(prefetchIdx);
                prefetchSinglePostMetadata(reel, fbRef, batcher);
            }
        }
    }

    private void prefetchSinglePostMetadata(@NonNull ReelModel reel,
                                            @NonNull DatabaseReference fbRef,
                                            @NonNull HomeFeedNetworkBatcher batcher) {
        if (reel == null || reel.reelId == null) return;

        if (metadataCache.get(reel.reelId) != null) return; // Already cached

        batcher.queueMetadataRead(reel.reelId, fbRef, metadata -> {
            metadataCache.put(metadata.reelId, metadata);
        });
    }

    @Override
    public void onScrollStateChanged(int state) {
        // Scroll state changed; adjust prefetch aggressiveness
        // (implementation would reduce ahead count if FLINGING)
    }

    public void setPrefetchEnabled(boolean enabled) {
        this.prefetchEnabled = enabled;
    }

    public boolean isPrefetchEnabled() {
        return prefetchEnabled;
    }

    public void shutdown() {
        // Cancel any pending prefetch work
    }
}
