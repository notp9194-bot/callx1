package com.callx.app.channel.canvas;

import android.content.Context;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.callx.app.models.ChannelPost;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ChannelPostGlidePreloader — primes Glide's in-memory bitmap cache for upcoming items.
 *
 * WHY THIS EXISTS
 * ───────────────
 * Glide's standard flow: bind() → load(url) → decode from network/disk → deliver bitmap
 * → invalidate() → onDraw(). The decode step (network + disk I/O + BitmapFactory) can
 * take 50–300 ms. During that window the item shows a placeholder, causing a visible
 * "image pop-in" flash every time a new post scrolls into view.
 *
 * This preloader calls Glide.preload() for items AHEAD of the current viewport while
 * the RecyclerView is idle. preload() fetches the bitmap into Glide's memory + disk
 * cache without delivering it to a Target. When the item is later bound normally,
 * Glide serves it from the memory cache — effectively instant, zero decode latency,
 * no pop-in flash.
 *
 * HOW TO USE
 * ──────────
 * 1. Call preloadRange(posts, last+1, last+8) from the RecyclerView SCROLL_STATE_IDLE
 *    listener (already wired in ChannelViewerActivity.configureRecyclerView()).
 * 2. Call preloadRange(posts, 0, 10) immediately after setPosts() for the initial batch.
 * 3. The preloaded bitmaps live in Glide's LruCache — no extra memory management needed.
 *    Glide evicts them under memory pressure automatically.
 *
 * DUPLICATE GUARD
 * ───────────────
 * A simple HashSet tracks recently preloaded postIds to avoid redundant Glide calls for
 * posts already in cache. The set is bounded at MAX_TRACKED to avoid unbounded growth;
 * it is cleared when a new list is set.
 */
public final class ChannelPostGlidePreloader {

    private static final int MAX_TRACKED = 200;

    private final RequestManager glide;
    private final Context        appCtx;
    private final Set<String>    preloadedIds = new HashSet<>();

    public ChannelPostGlidePreloader(Context context) {
        appCtx = context.getApplicationContext();
        glide  = Glide.with(appCtx);
    }

    /**
     * Preload bitmaps for posts[from..to) into Glide's memory cache.
     * Safe to call on the UI thread — Glide dispatches actual I/O to its thread pool.
     */
    public void preloadRange(List<ChannelPost> posts, int from, int to) {
        if (posts == null) return;
        int safeEnd = Math.min(to, posts.size());
        for (int i = Math.max(0, from); i < safeEnd; i++) {
            ChannelPost p = posts.get(i);
            if (p == null || p.id == null) continue;
            if (preloadedIds.contains(p.id)) continue;
            preloadPost(p);
            if (preloadedIds.size() < MAX_TRACKED) preloadedIds.add(p.id);
        }
    }

    /** Clear tracking set on list change so stale entries don't block re-preload. */
    public void onNewList() {
        preloadedIds.clear();
    }

    // ── Private ────────────────────────────────────────────────────────────

    private void preloadPost(ChannelPost p) {
        // Author avatar — small, very cheap.
        if (p.authorIconUrl != null && !p.authorIconUrl.isEmpty()) {
            int aPx = ChannelPostCanvasView.avatarPx(appCtx);
            glide.asBitmap()
                 .load(p.authorIconUrl)
                 .circleCrop()
                 .override(aPx, aPx)
                 .preload(aPx, aPx);
        }

        // Media (image / video thumbnail) — most expensive; highest priority to preload.
        String mediaUrl = "video".equals(p.type) ? p.thumbnailUrl : p.mediaUrl;
        if (mediaUrl != null && !mediaUrl.isEmpty()
                && ("image".equals(p.type) || "video".equals(p.type))) {
            int mH = ChannelPostCanvasView.mediaHeightPx(appCtx);
            glide.asBitmap()
                 .load(mediaUrl)
                 .centerCrop()
                 .override(com.bumptech.glide.request.target.Target.SIZE_ORIGINAL, mH)
                 .preload();
        }

        // Link preview thumbnail.
        if ("link".equals(p.type)
                && p.linkImageUrl != null && !p.linkImageUrl.isEmpty()) {
            glide.asBitmap()
                 .load(p.linkImageUrl)
                 .centerCrop()
                 .preload();
        }

        // Event banner.
        if ("event".equals(p.type)
                && p.eventImageUrl != null && !p.eventImageUrl.isEmpty()) {
            glide.asBitmap()
                 .load(p.eventImageUrl)
                 .centerCrop()
                 .preload();
        }
    }
}
