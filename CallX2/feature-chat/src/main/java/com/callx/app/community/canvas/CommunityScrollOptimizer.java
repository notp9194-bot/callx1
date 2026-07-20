package com.callx.app.community.canvas;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Central RecyclerView optimizer for all community list screens.
 *
 * Bundles four independent optimizations into a single call:
 *
 *  1. Hardware layer during fling/settle — the GPU composites the already-
 *     rasterized tiles without invoking onDraw per frame, eliminating the
 *     per-frame rasterize cost on mid-range devices.
 *  2. Off-screen view cache (20) — RecyclerView keeps 20 extra ViewHolders
 *     alive beyond the visible window without returning them to the pool,
 *     so they need no rebind on fast scroll reversal.
 *  3. LinearLayoutManager initial prefetch count (5) — during RenderThread
 *     idle gaps the LLM prefetches 5 items off the main thread so they are
 *     layout-ready before they scroll into view.
 *  4. Shared RecycledViewPool — community screens that show the same canvas
 *     view type (all use viewType 0) share a pool of 15 pre-inflated (but
 *     actually pre-constructed) ViewHolders, reducing allocation on fast tab
 *     switches between Members / Events / Notifications screens.
 *
 * Usage:
 *   LinearLayoutManager llm = new LinearLayoutManager(ctx);
 *   rv.setLayoutManager(llm);
 *   CommunityScrollOptimizer.apply(rv, llm);
 *   // if screen also needs the shared pool:
 *   CommunityScrollOptimizer.applySharedPool(rv);
 */
public final class CommunityScrollOptimizer {

    /** ViewHolder cache beyond the visible window — avoids rebind on scroll reversal. */
    private static final int VIEW_CACHE_SIZE = 20;

    /** Items the LLM pre-layouts during idle frame gaps. */
    private static final int INITIAL_PREFETCH_COUNT = 5;

    /**
     * Shared pool for screens that use the same canvas view item type.
     * 15 slots × one view type. The pool is process-scoped, so it survives
     * fragment back-stacks and tab switches within the same Activity.
     */
    private static final RecyclerView.RecycledViewPool SHARED_POOL;
    static {
        SHARED_POOL = new RecyclerView.RecycledViewPool();
        SHARED_POOL.setMaxRecycledViews(0, 15);
    }

    private CommunityScrollOptimizer() {}

    /**
     * Apply all standard optimizations to {@code rv}.
     * Call after setLayoutManager() and setAdapter() but before adding data.
     */
    public static void apply(@NonNull RecyclerView rv, @NonNull LinearLayoutManager llm) {
        rv.setItemViewCacheSize(VIEW_CACHE_SIZE);
        llm.setInitialPrefetchItemCount(INITIAL_PREFETCH_COUNT);
        if (rv.getItemAnimator() != null) rv.setItemAnimator(null); // no change-flash animations
        rv.addOnScrollListener(hardwareLayerListener());
    }

    /**
     * Additionally attach the process-scoped shared pool.
     * Use for screens whose canvas view type matches other community screens
     * (Members, JoinRequests, MemberSearch, Notifications, ModerationLog,
     *  ScheduledPosts — they all use viewType 0 and similar view heights).
     */
    public static void applySharedPool(@NonNull RecyclerView rv) {
        rv.setRecycledViewPool(SHARED_POOL);
    }

    // ── Hardware-layer scroll listener ────────────────────────────────────────

    /**
     * Returns an OnScrollListener that enables GPU compositing during fling
     * and settling, then drops back to software rendering when idle so the
     * next bind/invalidate renders correctly.
     *
     * Why not DRAGGING? During drag the user expects pixel-perfect frame
     * response — hardware layer caches the previous rasterization, which
     * can lag one frame behind a finger position change. Settling/fling
     * don't have that constraint because the physics-driven animation
     * already accepts sub-frame latency.
     */
    private static RecyclerView.OnScrollListener hardwareLayerListener() {
        return new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                switch (state) {
                    case RecyclerView.SCROLL_STATE_SETTLING:
                        rv.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                        break;
                    case RecyclerView.SCROLL_STATE_IDLE:
                        rv.setLayerType(View.LAYER_TYPE_NONE, null);
                        break;
                    // DRAGGING: leave the layer type as-is (it will be NONE initially)
                }
            }
        };
    }
}
