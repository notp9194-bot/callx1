package com.callx.app.player;

import android.content.Context;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * ExoPlayerPool — small reusable pool of ExoPlayer instances for the reels
 * feed (PERF advance #3 — "player pool reuse").
 *
 * Building a fresh ExoPlayer (renderers factory → codec/decoder setup →
 * internal playback thread + Handler) is the expensive part of
 * {@code AdaptiveStreamingManager.buildPlayer()} — noticeably more than
 * swapping which URL it's playing. Previously every reel (visible AND
 * every prewarmed one) built and later {@code release()}'d its own
 * instance from scratch. This pool keeps at most {@link #POOL_SIZE} real
 * ExoPlayer objects alive at once and hands them out/back — a prewarmed
 * player's object creation cost is paid once, not once per reel.
 *
 * POOL_SIZE=4 covers the realistic worst case for this feed: previous
 * (paused), current (playing), next (prewarmed), next+1 (prewarmed on a
 * fast scroll) — see ReelsFragment.controlPlayback()'s adaptive-distance
 * logic. If a 5th slot is ever requested (e.g. predictive prewarm reaching
 * further ahead than the positional window), acquire() falls back to
 * building a throwaway instance via AdaptiveStreamingManager.buildBarePlayer()
 * rather than blocking — better a slightly-more-expensive build than a
 * stall.
 *
 * Thread-confined to the main thread, same as every other ExoPlayer touch
 * point in this codebase.
 */
@OptIn(markerClass = UnstableApi.class)
public final class ExoPlayerPool {

    private static final String TAG        = "ExoPlayerPool";
    // FIX: POOL_SIZE 4→3. offscreenPageLimit=1 ke baad max 3 fragments exist
    // karte hain simultaneously: N-1 (paused), N (playing), N+1 (prewarmed).
    // Pehle 4 tha lekin 4th slot kabhi use nahi hota tha offscreenPageLimit=1 mein —
    // sirf memory waste. 3 players: ek playing, ek prewarmed next, ek paused prev.
    private static final int    POOL_SIZE  = 3;

    private static volatile ExoPlayerPool instance;

    private final Context appCtx;
    private final List<ExoPlayer> pooled  = new ArrayList<>(POOL_SIZE);
    private final List<ExoPlayer> inUse   = new ArrayList<>(POOL_SIZE);
    /** Listeners we attached on behalf of callers — removed on release() so a
     *  reused instance never carries a stale reel's listener into a new one. */
    private final Map<ExoPlayer, List<Player.Listener>> trackedListeners = new IdentityHashMap<>();

    private ExoPlayerPool(Context ctx) {
        appCtx = ctx.getApplicationContext();
    }

    public static ExoPlayerPool get(Context ctx) {
        if (instance == null) {
            synchronized (ExoPlayerPool.class) {
                if (instance == null) instance = new ExoPlayerPool(ctx);
            }
        }
        return instance;
    }

    /**
     * Hand out a ready-to-configure ExoPlayer — either a free instance from
     * the pool (stopped, cleared, listener-free) or a freshly built one if
     * the pool hasn't reached {@link #POOL_SIZE} yet. Caller is responsible
     * for setting a MediaSource, applying quality/track-selector params, and
     * attaching listeners (via {@link #trackListener}) before calling
     * prepare().
     */
    public synchronized ExoPlayer acquire() {
        if (!pooled.isEmpty()) {
            ExoPlayer p = pooled.remove(pooled.size() - 1);
            inUse.add(p);
            Log.d(TAG, "acquire: reused pooled instance (pool now " + pooled.size()
                + " free, " + inUse.size() + " in use)");
            return p;
        }
        if (inUse.size() < POOL_SIZE) {
            ExoPlayer p = AdaptiveStreamingManager.get(appCtx).buildBarePlayer();
            inUse.add(p);
            Log.d(TAG, "acquire: built new pooled instance (" + inUse.size() + "/" + POOL_SIZE + ")");
            return p;
        }
        // Pool exhausted (e.g. predictive prewarm reaching past the normal
        // N+1/N+2/N+3 window) — build a throwaway instance rather than block
        // or steal one that's actively playing/paused-visible.
        Log.d(TAG, "acquire: pool exhausted (" + POOL_SIZE + " in use) — building throwaway instance");
        return AdaptiveStreamingManager.get(appCtx).buildBarePlayer();
    }

    /**
     * Return a player to the pool for reuse. Fully resets playback state and
     * strips any listeners registered via {@link #trackListener} so the next
     * acquirer starts from a clean slate. If this instance was a throwaway
     * (pool was full when it was acquired), it's hard-released instead of
     * being added back, so the pool never grows past {@link #POOL_SIZE}.
     */
    public synchronized void release(ExoPlayer player) {
        if (player == null) return;

        removeTrackedListeners(player);
        try {
            player.stop();
            player.clearMediaItems();
            player.setVolume(0f);
            player.setPlayWhenReady(false);
        } catch (Exception e) {
            Log.w(TAG, "release: reset failed, hard-releasing instead: " + e.getMessage());
            hardRelease(player);
            return;
        }

        boolean wasTracked = inUse.remove(player);
        if (!wasTracked) {
            // Throwaway instance from an exhausted-pool acquire() — don't
            // let the pool grow unbounded.
            hardRelease(player);
            return;
        }
        if (pooled.size() < POOL_SIZE) {
            pooled.add(player);
            Log.d(TAG, "release: returned to pool (" + pooled.size() + " free)");
        } else {
            hardRelease(player);
        }
    }

    /** Register a listener as "belonging" to the current reel's use of this
     *  pooled player, so {@link #release} can strip it before reuse. */
    public synchronized void trackListener(ExoPlayer player, Player.Listener listener) {
        if (player == null || listener == null) return;
        trackedListeners.computeIfAbsent(player, k -> new ArrayList<>()).add(listener);
    }

    private void removeTrackedListeners(ExoPlayer player) {
        List<Player.Listener> ls = trackedListeners.remove(player);
        if (ls == null) return;
        for (Player.Listener l : ls) {
            try { player.removeListener(l); } catch (Exception ignored) {}
        }
    }

    private void hardRelease(ExoPlayer player) {
        removeTrackedListeners(player);
        try { player.release(); } catch (Exception ignored) {}
    }

    /**
     * Hard-releases every pooled AND in-use instance. Call when the whole
     * Reels feature goes away (ReelsFragment.onDestroyView) or on a
     * TRIM_MEMORY_CRITICAL callback — not on ordinary tab switches, where
     * keeping the pool warm is the entire point.
     */
    public synchronized void releaseAll() {
        for (ExoPlayer p : pooled) hardRelease(p);
        for (ExoPlayer p : inUse) hardRelease(p);
        pooled.clear();
        inUse.clear();
        trackedListeners.clear();
        Log.d(TAG, "releaseAll: pool fully torn down");
    }

    /** Current free-instance count — used by adaptive-distance logic to
     *  avoid requesting a prewarm the pool has no room for anyway. */
    public synchronized int freeCount() {
        return pooled.size() + Math.max(0, POOL_SIZE - inUse.size() - pooled.size());
    }
}
