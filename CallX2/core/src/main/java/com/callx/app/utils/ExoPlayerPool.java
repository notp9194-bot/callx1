package com.callx.app.utils;

import android.content.Context;

import androidx.media3.exoplayer.ExoPlayer;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * ExoPlayerPool — shared pool of pre-built {@link ExoPlayer} instances.
 *
 * WHY: MediaViewerActivity's video pages (GalleryPagerAdapter.PageVH) used
 * to call `new ExoPlayer.Builder(ctx).build()` on every bind and
 * `player.release()` on every recycle/unbind. Every swipe between video
 * pages therefore fully tore down and rebuilt an ExoPlayer — internal
 * decoder/renderer threads, audio track, and native buffers all
 * allocated+freed per swipe. On a fast fling through a multi-video gallery
 * this is a steady stream of GC pressure and jank, purely from player
 * churn (the actual media itself is already cache-first via MediaCache).
 *
 * FIX: keep a small pool of already-built idle ExoPlayer instances.
 * acquire() reuses one instead of constructing a new one when the pool has
 * a spare; release() clears/stops the player and returns it to the pool
 * instead of destroying it. Only when the pool is exhausted (more
 * concurrent video pages than the pool size, effectively never in a
 * ViewPager2 with offscreenPageLimit=1) does this fall back to building a
 * fresh instance — same as the old behavior, so nothing regresses.
 *
 * Pool size of 2 covers the realistic case: current page + one adjacent
 * page that ViewPager2 keeps alive. Application-scoped Context is used for
 * building pooled players so a pooled instance safely outlives any single
 * Activity across viewer opens.
 */
public final class ExoPlayerPool {

    private static final int MAX_POOL_SIZE = 2;

    private static final ConcurrentLinkedDeque<ExoPlayer> POOL = new ConcurrentLinkedDeque<>();

    private ExoPlayerPool() {}

    /** Returns a ready-to-use ExoPlayer — reused from the pool if one is idle, else newly built. */
    public static ExoPlayer acquire(Context context) {
        ExoPlayer pooled = POOL.pollFirst();
        if (pooled != null) {
            return pooled;
        }
        return new ExoPlayer.Builder(context.getApplicationContext()).build();
    }

    /**
     * Returns a player to the pool for reuse instead of releasing its
     * native resources. Clears media items and detaches listeners'
     * playback state (stop) so the next acquirer gets a clean instance.
     * If the pool is already at capacity, the instance is released for
     * real (matches the pre-pool behavior — no unbounded growth).
     */
    public static void release(ExoPlayer player) {
        if (player == null) return;
        player.stop();
        player.clearMediaItems();
        if (POOL.size() < MAX_POOL_SIZE) {
            POOL.addLast(player);
        } else {
            player.release();
        }
    }

    /** Call on process/low-memory teardown to actually free pooled native resources. */
    public static void drain() {
        ExoPlayer p;
        while ((p = POOL.pollFirst()) != null) {
            p.release();
        }
    }
}
