package com.callx.app.feed;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import com.callx.app.models.ReelModel;
import com.callx.app.player.ReelThermalManager;
import com.google.firebase.database.DatabaseReference;

import java.util.List;

/**
 * PostFeedUltraOptimizer — same coordinator role {@link HomeFeedUltraOptimizer}
 * plays for HomeFragment, scoped down for
 * {@link com.callx.app.profile.PostsFeedActivity}'s photo-only feed.
 *
 * Posts is a plain scrollable RecyclerView of static images (no ExoPlayer,
 * no per-card hardware decoder — see PostsFeedActivity's class doc), so it
 * doesn't need Home's video-prewarm/prefetch subsystem. What it WAS missing
 * that Home already had:
 *
 *  1. SCROLL-STATE GATING — before this, {@code maybeTrimOrReloadPostWindow()}
 *     fired its network-triggering reload calls on every single
 *     {@code onScrolled} tick, including mid-fling. Now reload work defers
 *     while {@link HomeFeedScrollStateManager} reports FLINGING and fires
 *     once scroll settles (reuses the exact same state machine Home uses).
 *  2. NETWORK BATCHING — every per-post read
 *     ({@code loadPosts}/{@code reloadPostsBefore}/{@code reloadPostsAfter})
 *     fired its own uncoalesced {@code addListenerForSingleValueEvent}.
 *     Routed through {@link PostFeedNetworkBatcher} now, so duplicate reads
 *     for the same reelId arriving close together collapse into one.
 *  3. THERMAL/BATTERY AWARENESS — reuses the existing
 *     {@link ReelThermalManager} singleton (already monitoring for the Reels
 *     player). Under HOT, the v284 per-row LIVE count listener
 *     (a standing {@code addValueEventListener} per visible row) is skipped
 *     in favor of a single one-time read — the kind of background work
 *     Instagram sheds under thermal pressure, same principle Home's
 *     prefetch manager already applies to its own subsystems.
 */
public class PostFeedUltraOptimizer {

    public interface ScrollSettledListener {
        void onScrollSettled();
    }

    public interface ReelBatchCallback {
        void onLoaded(ReelModel[] slots);
    }

    private static final int COALESCE_WINDOW_MS = 50; // same window Home's batcher uses

    private Handler mainHandler;
    private HomeFeedScrollStateManager scrollStateManager;
    private PostFeedNetworkBatcher networkBatcher;
    private ReelThermalManager thermalManager;
    private ScrollSettledListener scrollSettledListener;

    /**
     * @param onSettled called (on main thread) whenever scroll returns to
     *                  SETTLING/IDLE after a fling — the caller should
     *                  re-run its window trim/reload check here, since any
     *                  reload that was deferred mid-fling needs a fresh
     *                  trigger once it's safe to fire.
     */
    public void initialize(@NonNull Context ctx, @NonNull DatabaseReference reelsRootRef,
                           @NonNull Handler handler, @NonNull ScrollSettledListener onSettled) {
        this.mainHandler = handler != null ? handler : new Handler(Looper.getMainLooper());
        this.scrollSettledListener = onSettled;

        this.scrollStateManager = new HomeFeedScrollStateManager(state -> {
            if (state == HomeFeedUltraOptimizer.SCROLL_IDLE
                || state == HomeFeedUltraOptimizer.SCROLL_SETTLING) {
                if (scrollSettledListener != null) scrollSettledListener.onScrollSettled();
            }
        });

        this.networkBatcher = new PostFeedNetworkBatcher(reelsRootRef, COALESCE_WINDOW_MS);
        this.thermalManager = ReelThermalManager.get(ctx);
    }

    public void onRecyclerScrollStateChanged(int newState) {
        if (scrollStateManager != null) scrollStateManager.onRecyclerScrollStateChanged(newState);
    }

    public void onRecyclerScrolled(int dx, int dy) {
        if (scrollStateManager != null) scrollStateManager.onRecyclerScrolled(dx, dy);
    }

    /** True while the list is mid-fling — callers should defer network-triggering work. */
    public boolean isFlinging() {
        return scrollStateManager != null && scrollStateManager.isFlinging();
    }

    /**
     * False under thermal HOT — caller should skip attaching a standing
     * v284 live count listener and fall back to a one-time read instead.
     */
    public boolean canAttachLiveCountListener() {
        return thermalManager == null || thermalManager.getLevel() != ReelThermalManager.Level.HOT;
    }

    /**
     * Batched, de-duped fetch for a list of reelIds. Replaces firing one
     * raw {@code addListenerForSingleValueEvent} per id with no coalescing.
     * Preserves input order in the result array (same contract the old
     * direct-fetch loadPosts() had).
     */
    public void batchFetchReels(@NonNull List<String> reelIds, @NonNull ReelBatchCallback callback) {
        int total = reelIds.size();
        if (total == 0) {
            callback.onLoaded(new ReelModel[0]);
            return;
        }
        ReelModel[] slots = new ReelModel[total];
        int[] remaining = { total };

        for (int i = 0; i < total; i++) {
            final int idx = i;
            networkBatcher.queueReelRead(reelIds.get(i), reel -> {
                slots[idx] = reel;
                remaining[0]--;
                if (remaining[0] == 0) callback.onLoaded(slots);
            });
        }
    }

    /**
     * Call from PostsFeedActivity.onDestroy() to tear down background
     * handlers and clear pending batch work. Does NOT release the shared
     * ReelThermalManager singleton — that's owned by whichever screen (Home
     * or the Reels player) started it; Posts only reads its current level.
     */
    public void shutdown() {
        if (scrollStateManager != null) scrollStateManager.shutdown();
        if (networkBatcher != null) networkBatcher.shutdown();
    }
}
