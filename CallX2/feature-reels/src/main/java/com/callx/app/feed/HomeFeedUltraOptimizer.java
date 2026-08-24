package com.callx.app.feed;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.collection.LruCache;
import com.callx.app.models.ReelModel;
import com.google.firebase.database.DatabaseReference;

import java.util.*;
import java.util.concurrent.*;

/**
 * HomeFeedUltraOptimizer — Production-grade performance coordinator for HomeFragment's
 * infinite-scroll feed (v281+). Coordinates 5 advanced subsystems for Instagram-level
 * feed responsiveness:
 *
 *  1. METADATA CACHE (LRU): Caches post counts (likes/comments/reposts), follow status,
 *     and caption preview for all visible + prefetch posts. Batches Firebase reads.
 *  2. SCROLL STATE MACHINE: Pauses non-urgent work (preload, analytics) during fling,
 *     resumes predictively when scroll settles. Prevents jank from competing threads.
 *  3. NETWORK BATCHER: Coalesces Firebase .child().addListenerForSingleValueEvent()
 *     queries into 50ms batches so 8 rapid postLoad calls to the same ref = 1 batch.
 *  4. VIEW RECYCLING: Enhances RecyclerView's pooling with explicit onAttach/onDetach
 *     hooks for aggressive Glide/ExoPlayer cleanup when cards scroll off-screen.
 *  5. PREFETCH MANAGER: Extends existing videoPreloader + predictivePreloader with
 *     metadata-driven decisions (skip prefetch if low battery/thermal).
 *
 * Call from HomeFragment.onViewCreated() → initialize(ctx, fbRef, handler).
 * All subsystems coordinate via scrollState: when scrollState enters FLINGING,
 * non-critical work automatically pauses until SCROLL_SETTLED is reached.
 */
public class HomeFeedUltraOptimizer {

    public interface ScrollStateListener {
        void onScrollStateChanged(int state);
    }

    public static final int SCROLL_IDLE        = 0;
    public static final int SCROLL_DRAGGING    = 1;
    public static final int SCROLL_FLINGING    = 2;
    public static final int SCROLL_SETTLING    = 3;

    private static final String TAG = "HomeFeedUltOpt";

    // ── Core subsystems ────────────────────────────────────────────
    private HomeFeedMetadataCache metadataCache;
    private HomeFeedScrollStateManager scrollStateManager;
    private HomeFeedNetworkBatcher networkBatcher;
    private HomeFeedViewRecyclingOptimizer recyclingOptimizer;
    private HomeFeedPrefetchManager prefetchManager;

    private Handler mainHandler;
    private final Set<ScrollStateListener> scrollListeners = Collections.newSetFromMap(
        new WeakHashMap<>());

    private volatile int currentScrollState = SCROLL_IDLE;

    public HomeFeedUltraOptimizer() {}

    public void initialize(@NonNull Context ctx, @NonNull DatabaseReference fbRef,
                          @NonNull Handler handler) {
        this.mainHandler = handler != null ? handler : new Handler(Looper.getMainLooper());

        // Initialize subsystems in order of dependency
        this.metadataCache = new HomeFeedMetadataCache(1024); // 1024 post cache entries

        this.scrollStateManager = new HomeFeedScrollStateManager(state -> {
            currentScrollState = state;
            notifyScrollListeners(state);
        });

        this.networkBatcher = new HomeFeedNetworkBatcher(fbRef, 50); // 50ms coalesce window

        this.recyclingOptimizer = new HomeFeedViewRecyclingOptimizer();

        this.prefetchManager = new HomeFeedPrefetchManager(ctx, mainHandler,
            scrollStateManager, metadataCache);
    }

    public void onRecyclerScrollStateChanged(int newState) {
        scrollStateManager.onRecyclerScrollStateChanged(newState);
    }

    public void onRecyclerScrolled(int dx, int dy) {
        scrollStateManager.onRecyclerScrolled(dx, dy);
    }

    /**
     * Batch-fetch post metadata (likes, comments, reposts, follow status) for a
     * range of posts. Results cached in metadataCache; subsequent calls for the
     * same reelIds return instant-hit cache entries. Firebase reads coalesced
     * into single batches.
     */
    public void prefetchPostMetadata(@NonNull List<ReelModel> reels,
                                     @NonNull DatabaseReference fbRef) {
        if (reels.isEmpty()) return;
        prefetchManager.prefetchMetadata(reels, fbRef, networkBatcher);
    }

    /**
     * Request batched metadata read for a single post. Returns cached values if
     * available, otherwise queues for next batch window. Callback fires when
     * data is available (immediately if cached, or after batch fires).
     */
    public void getPostMetadata(@NonNull String reelId, @NonNull DatabaseReference fbRef,
                               @NonNull HomeFeedMetadataCache.MetadataCallback callback) {
        // Try cache first (instant hit)
        HomeFeedMetadataCache.PostMetadata cached = metadataCache.get(reelId);
        if (cached != null) {
            mainHandler.post(() -> callback.onMetadata(cached));
            return;
        }

        // Not in cache; queue for batch fetch
        networkBatcher.queueMetadataRead(reelId, fbRef, callback);
    }

    /**
     * Update metadata for a post (e.g., after user likes it). Reflected
     * immediately in cache, so any View bound to this reelId sees the new
     * count without waiting for Firebase callback.
     */
    public void updateCachedMetadata(@NonNull String reelId,
                                     @NonNull HomeFeedMetadataCache.PostMetadata updated) {
        metadataCache.put(reelId, updated);
    }

    /**
     * Called when a HomeFeedCard (ViewHolder) is about to scroll off-screen.
     * Coordinator cues recyclingOptimizer to detach Glide/ExoPlayer resources.
     */
    public void onCardDetaching(@NonNull HomeFeedCard card) {
        recyclingOptimizer.onCardDetaching(card);
    }

    /**
     * Called when a HomeFeedCard is rebound (scrolled back into view).
     * Coordinator cues recyclingOptimizer to restore bindings if needed.
     */
    public void onCardAttaching(@NonNull HomeFeedCard card) {
        recyclingOptimizer.onCardAttaching(card);
    }

    /**
     * Hook for HomeFragment.addFeedPostCard() → call after card binding to
     * prefetch the next-ahead card's metadata if not yet loaded.
     */
    public void onCardBoundAtIndex(int index, @NonNull List<ReelModel> feedPosts,
                                   @NonNull DatabaseReference fbRef) {
        if (index < feedPosts.size() - 1) {
            ReelModel nextCard = feedPosts.get(index + 1);
            if (nextCard != null && metadataCache.get(nextCard.reelId) == null) {
                getPostMetadata(nextCard.reelId, fbRef, m -> {
                    // Cache hit; nothing else to do
                });
            }
        }
    }

    /**
     * Register a listener to be notified whenever scroll state changes
     * (IDLE → DRAGGING → FLINGING → SETTLING).
     */
    public void addScrollStateListener(@NonNull ScrollStateListener listener) {
        scrollListeners.add(listener);
    }

    public void removeScrollStateListener(@NonNull ScrollStateListener listener) {
        scrollListeners.remove(listener);
    }

    private void notifyScrollListeners(int state) {
        for (ScrollStateListener listener : scrollListeners) {
            listener.onScrollStateChanged(state);
        }
    }

    public int getCurrentScrollState() {
        return currentScrollState;
    }

    /**
     * Call from HomeFragment.onDestroyView() to tear down all background
     * threads, cancel pending batches, and clear caches.
     */
    public void shutdown() {
        if (scrollStateManager != null) scrollStateManager.shutdown();
        if (networkBatcher != null) networkBatcher.shutdown();
        if (metadataCache != null) metadataCache.clear();
        if (prefetchManager != null) prefetchManager.shutdown();
        if (recyclingOptimizer != null) recyclingOptimizer.clear();
        scrollListeners.clear();
    }

    // ── Public accessors (for debugging/monitoring) ────────────────────────
    public HomeFeedMetadataCache getMetadataCache() {
        return metadataCache;
    }

    public HomeFeedScrollStateManager getScrollStateManager() {
        return scrollStateManager;
    }

    public HomeFeedNetworkBatcher getNetworkBatcher() {
        return networkBatcher;
    }
}
