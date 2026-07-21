package com.callx.app.conversation.controllers;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

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

    private final Semaphore semaphore;
    private volatile boolean paused = false;
    private final Object pauseLock = new Object();
    private final ExecutorService pool = Executors.newCachedThreadPool();

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
     * @param uploadTask Runnable that performs the full compress+upload
     *                   pipeline for one media item. Runs on a background
     *                   thread; may block for seconds. Must be self-
     *                   contained (no UI calls without runOnUiThread).
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

            // ── 4. Final cancel check before doing real work ───────────────
            try {
                if (!cancelledIds.contains(messageId)) {
                    uploadTask.run();
                }
            } finally {
                semaphore.release();
            }
        });
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
