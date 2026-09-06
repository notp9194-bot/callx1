package com.callx.app.conversation.controllers;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * MediaUploadQueue — Feature 2 & 6: Upload queue with concurrency limit
 * and automatic pause/resume on network state changes.
 *
 * Max concurrent uploads: 3 (configurable via constructor).
 * Network-aware: pauses when offline (via ConnectivityManager callback),
 * auto-resumes when connectivity is restored — in-progress uploads are
 * allowed to finish (they handle their own errors); only newly queued
 * tasks wait for the connection to come back.
 *
 * Thread safety: all fields safe to call from any thread.
 */
public class MediaUploadQueue {

    private static final String TAG = "MediaUploadQueue";

    /** Max uploads that may run concurrently. */
    private static final int DEFAULT_MAX_CONCURRENT = 3;

    /**
     * PERF FIX (real "parallel upload" bug): a queued task used to be a bare
     * Runnable that just did {@code activity.runOnUiThread(() -> {...})} —
     * that post returns in microseconds, long before the actual
     * compress+encrypt+upload chain (which lives across several async
     * callbacks) finishes. The semaphore below was being released almost
     * instantly every time, so DEFAULT_MAX_CONCURRENT was a no-op and a
     * 15-20 photo multi-select fired every chain at once.
     *
     * Fix: the pool thread now blocks on a per-messageId CountDownLatch
     * after kicking the task off, and only releases the semaphore once the
     * caller calls {@link #markComplete(String)} from whichever callback
     * truly terminates the pipeline (success OR failure). A bounded watchdog
     * timeout guarantees a forgotten/buggy markComplete() call can never
     * wedge the whole queue forever.
     */
    private static final long COMPLETION_TIMEOUT_MS = 3 * 60 * 1000L; // 3 min safety net

    private final Semaphore semaphore;
    private volatile boolean paused = false;
    private final Object pauseLock = new Object();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, CountDownLatch> inFlight = new ConcurrentHashMap<>();

    private final ConnectivityManager.NetworkCallback networkCallback;
    private final ConnectivityManager cm;

    public MediaUploadQueue(Context ctx) {
        this(ctx, DEFAULT_MAX_CONCURRENT);
    }

    public MediaUploadQueue(Context ctx, int maxConcurrent) {
        this.semaphore = new Semaphore(maxConcurrent, true /* fair */);

        cm = (ConnectivityManager) ctx.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                Log.d(TAG, "Network available — resuming upload queue");
                synchronized (pauseLock) {
                    paused = false;
                    pauseLock.notifyAll();
                }
            }

            @Override public void onLost(Network network) {
                // Only pause if ALL networks are gone.
                Network active = cm.getActiveNetwork();
                if (active == null) {
                    Log.d(TAG, "Network lost — pausing upload queue");
                    synchronized (pauseLock) {
                        paused = true;
                    }
                }
            }
        };

        try {
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            cm.registerNetworkCallback(req, networkCallback);
        } catch (Exception e) {
            Log.w(TAG, "Could not register network callback", e);
        }
    }

    /**
     * Enqueues an upload task.
     *
     * @param messageId  Room row ID — checked against cancelledIds before
     *                   and after waiting so a cancelled item never starts.
     * @param cancelledIds mutable set maintained by the caller; this method
     *                   only reads it, never writes.
     * @param uploadTask Kicks off the compress+upload pipeline for one media
     *                   item and returns — it does NOT block until the
     *                   pipeline finishes (it typically just posts to the UI
     *                   thread to start async work). The concurrency slot is
     *                   held by this queue until the caller separately calls
     *                   {@link #markComplete(String)} for this same
     *                   messageId from the pipeline's real terminal
     *                   callback(s) (success AND failure paths — every exit
     *                   point must call it exactly once).
     */
    public void enqueue(String messageId, Set<String> cancelledIds, Runnable uploadTask) {
        pool.execute(() -> {
            // ── 1. Wait while offline ──────────────────────────────────────
            synchronized (pauseLock) {
                while (paused) {
                    if (cancelledIds.contains(messageId)) return;
                    try {
                        pauseLock.wait(5_000); // re-check every 5 s
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

            // ── 2. Cancelled before starting? ──────────────────────────────
            if (cancelledIds.contains(messageId)) return;

            // ── 3. Acquire concurrency slot ────────────────────────────────
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // ── 4. Final cancel check, then run + hold the slot until the
            //      caller signals REAL completion (not just "task posted") ──
            CountDownLatch latch = new CountDownLatch(1);
            inFlight.put(messageId, latch);
            try {
                if (!cancelledIds.contains(messageId)) {
                    uploadTask.run();
                    boolean signalled = latch.await(COMPLETION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (!signalled) {
                        Log.w(TAG, "markComplete() never received for " + messageId
                                + " within " + COMPLETION_TIMEOUT_MS + "ms — releasing slot anyway");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.remove(messageId);
                semaphore.release();
            }
        });
    }

    /**
     * Signals that messageId's upload pipeline has truly finished — success
     * or failure — and this concurrency slot can now go to the next queued
     * item. Call this from the pipeline's terminal callback(s), never from
     * uploadTask itself (which only kicks the async chain off). Safe to call
     * for an id that's unknown, already completed, or already timed out —
     * all are no-ops.
     */
    public void markComplete(String messageId) {
        CountDownLatch latch = inFlight.get(messageId);
        if (latch != null) latch.countDown();
    }

    /**
     * Force-pauses the queue (e.g. called when the activity knows it has
     * gone offline before the NetworkCallback fires). Already-running tasks
     * are not interrupted; subsequent enqueue() calls will block.
     */
    public void pause() {
        synchronized (pauseLock) {
            paused = true;
        }
    }

    /**
     * Resumes a manually-paused queue (mirrors the NetworkCallback.onAvailable
     * path). No-op if not paused.
     */
    public void resume() {
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
    }

    /**
     * Unregisters the network callback and shuts down the thread pool.
     * Call from Activity.onDestroy().
     */
    public void destroy() {
        try {
            cm.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {}
        pool.shutdownNow();
    }
}
