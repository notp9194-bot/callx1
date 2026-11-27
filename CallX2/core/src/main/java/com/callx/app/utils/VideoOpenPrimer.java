package com.callx.app.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.exoplayer.ExoPlayer;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PERF: prepare the video player at TAP time (chat), so codec init + first frame
 * overlap the viewer's open animation instead of starting after its onCreate.
 *
 * Flow: chat tap (main) → prime(): a background task resolves the source
 * (local file verdict / MediaCache.getCached — no I/O on main) → main: pooled
 * ExoPlayer gets the source + prepare() (paused). MediaViewerActivity then take()s
 * the token and attaches the already-prepared player. Pooled players are built
 * from the application context, so they are safe outside any Activity.
 *
 * Only LOCAL / already-cached videos are primed (never a network stream — the
 * viewer's normal path owns download/stream). If the viewer is not ready to take it
 * (or never starts), the player returns to the pool: take() of a still-pending
 * prime cancels it, and an unclaimed prime expires after {@link #EXPIRY_MS}.
 * All state is touched on the main thread only.
 */
public final class VideoOpenPrimer {

    public static final String TAG = "VideoOpenPerf";
    public static final String EXTRA_TOKEN = "primedVideoToken";
    public static final String EXTRA_TAP_UPTIME = "tapUptimeMs";
    public static final String EXTRA_LOCAL_AVAIL = "localAvail";

    public static final int KIND_CACHE = 0;
    public static final int KIND_LOCAL = 1;

    private static final int PENDING = 0, READY = 1, DONE = 2;
    private static final long EXPIRY_MS = 8000L;

    public static final class Primed {
        public final int token;
        public final String url;
        public Uri uri;
        public int kind;
        private ExoPlayer player;
        private int state = PENDING;

        Primed(int token, String url) { this.token = token; this.url = url; }

        /** Hands the prepared (paused) player to the caller; it must be returned via ExoPlayerPool.release(). */
        @Nullable public ExoPlayer claimPlayer() {
            ExoPlayer p = player;
            player = null;
            return p;
        }

        /** Returns the player to the pool if nobody claimed it. */
        public void releaseUnused() {
            state = DONE;
            ExoPlayer p = player;
            player = null;
            if (p != null) ExoPlayerPool.release(p);
        }
    }

    private static final Map<Integer, Primed> HOLD = new HashMap<>(); // main thread only
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private VideoOpenPrimer() {}

    // ── Bubble bitmap handoff (main thread only) ─────────────────────────
    // The chat bubble already has its thumbnail decoded on screen. A private copy is handed
    // to the viewer so the shutter shows on frame 1 with zero decode (copy: Glide may recycle
    // the bubble's bitmap into its pool at any time).
    private static Bitmap shutterBmp;
    private static long shutterAtMs;

    /** Main thread, at tap time. Null / recycled / unreadable bitmaps are ignored. */
    public static void offerShutter(@Nullable Bitmap b) {
        shutterBmp = null;
        if (b == null || b.isRecycled() || b.getWidth() <= 0 || b.getHeight() <= 0) return;
        try {
            Bitmap.Config cfg = b.getConfig();
            if (cfg == null || cfg == Bitmap.Config.HARDWARE) cfg = Bitmap.Config.ARGB_8888;
            shutterBmp = b.copy(cfg, false);
            shutterAtMs = android.os.SystemClock.uptimeMillis();
        } catch (Throwable ignored) {
            shutterBmp = null;
        }
    }

    /** Main thread (viewer onCreate). Single use; stale (>5s) offers are dropped. */
    @Nullable
    public static Bitmap takeShutter() {
        Bitmap b = shutterBmp;
        shutterBmp = null;
        if (b == null || android.os.SystemClock.uptimeMillis() - shutterAtMs > 5000L) return null;
        return b;
    }

    /**
     * Call on the main thread at tap time.
     * @param localAvail chat adapter's LOCAL_AVAIL_CACHE verdict for localPath, or null if unknown
     * @return token for the Intent, or -1 if nothing will be primed
     */
    public static int prime(Context ctx, String url, @Nullable String localPath, @Nullable Boolean localAvail) {
        if (ctx == null || url == null || url.isEmpty()) return -1;
        if (Looper.myLooper() != Looper.getMainLooper()) return -1;
        final Context app = ctx.getApplicationContext();

        for (Primed old : HOLD.values()) old.releaseUnused(); // a newer tap supersedes older primes
        HOLD.clear();

        final Primed p = new Primed(COUNTER.incrementAndGet(), url);
        HOLD.put(p.token, p);

        IO.execute(() -> {
            Uri uri = null;
            int kind = KIND_CACHE;
            try {
                boolean local = localPath != null && !localPath.isEmpty()
                        && (localAvail != null ? localAvail : LocalMediaAvailability.isAvailable(app, localPath));
                if (local) {
                    uri = Uri.parse(localPath);
                    kind = KIND_LOCAL;
                } else {
                    File f = MediaCache.getCached(app, url);
                    if (f != null) uri = Uri.fromFile(f);
                }
            } catch (Throwable ignored) {}
            final Uri fu = uri;
            final int fk = kind;
            MAIN.post(() -> {
                if (p.state != PENDING) return; // viewer already took/cancelled it, or it expired
                if (fu == null) { // remote-only → nothing to prime
                    p.state = DONE;
                    HOLD.remove(p.token);
                    return;
                }
                try {
                    ExoPlayer ex = ExoPlayerPool.acquire(app);
                    LocalVideoSource.apply(app, ex, fu);
                    ex.setPlayWhenReady(false);
                    ex.prepare();
                    p.uri = fu;
                    p.kind = fk;
                    p.player = ex;
                    p.state = READY;
                } catch (Throwable t) {
                    p.state = DONE;
                    HOLD.remove(p.token);
                }
            });
        });

        MAIN.postDelayed(() -> {
            Primed stale = HOLD.remove(p.token);
            if (stale != null) stale.releaseUnused();
        }, EXPIRY_MS);
        return p.token;
    }

    /** Main thread (viewer onCreate). Null if not primed, still pending (cancelled), or expired. */
    @Nullable
    public static Primed take(int token) {
        if (token < 0) return null;
        Primed p = HOLD.remove(token);
        if (p == null) return null;
        if (p.state == READY) return p;
        p.state = DONE; // pending → cancel; the queued main-post becomes a no-op
        return null;
    }
}
