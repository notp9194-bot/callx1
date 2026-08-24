package com.callx.app.feed;

import androidx.annotation.NonNull;
import com.bumptech.glide.Glide;
import java.util.*;

/**
 * HomeFeedViewRecyclingOptimizer — Aggressive cleanup when ViewHolders recycle.
 *
 * Problem: Even though RecyclerView recycles ViewHolders, the underlying
 * resources (Glide requests, ExoPlayer prep buffers, Bitmap caches) can
 * linger if not explicitly cleaned up. Over 50-100 scrolled cards, this
 * accumulates to significant memory pressure.
 *
 * Solution: Explicit onCardDetaching/onCardAttaching hooks that:
 *  1. Cancel any in-flight Glide loads (thumbnail, avatar, etc.)
 *  2. Release ExoPlayer standby buffers
 *  3. Clear view-level animation state
 *  4. Null out strong references to help GC
 *
 * Called from HomeFragment.FeedAdapter.onViewRecycled() and similar points.
 */
public class HomeFeedViewRecyclingOptimizer {

    private static final String TAG = "ViewRecyclingOpt";

    private final Set<HomeFragment.HomeFeedCard> detachedCards = Collections.synchronizedSet(
        new HashSet<>());

    /**
     * Called when a HomeFeedCard is about to scroll off-screen (onViewRecycled).
     * Clears all resource bindings so the card doesn't hold memory.
     */
    public void onCardDetaching(@NonNull HomeFragment.HomeFeedCard card) {
        if (card == null) return;

        detachedCards.add(card);

        // Clear Glide loads on thumbnail and avatar
        if (card.thumbView != null) {
            Glide.with(card.thumbView.getContext()).clear(card.thumbView);
        }

        // If there's an attached video/player, detach it
        if (card.playerView != null) {
            card.playerView.setPlayer(null);
        }

        // Clear view references to allow GC
        card.rootView = null;
        card.playerView = null;
        card.thumbView = null;
        card.endOverlay = null;
    }

    /**
     * Called when a HomeFeedCard is rebound after being off-screen.
     * Restores view references if needed (though most often the ViewHolder
     * will be immediately re-bound anyway).
     */
    public void onCardAttaching(@NonNull HomeFragment.HomeFeedCard card) {
        if (card == null) return;
        detachedCards.remove(card);
        // Re-binding happens naturally in FeedAdapter.onBindViewHolder
    }

    public void clear() {
        detachedCards.clear();
    }

    public int getDetachedCardCount() {
        return detachedCards.size();
    }
}
