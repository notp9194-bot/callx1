package com.callx.app.conversation.controllers;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * MediaDownloadQueue — Receiver-side equivalent of MediaUploadQueue.
 *
 * Mirrors the upload queue exactly:
 *   • Max 3 concurrent downloads (configurable).
 *   • Network-aware: pauses when offline, auto-resumes on reconnect.
 *   • Fair semaphore so FIFO ordering is preserved.
 *
 * Usage:
 *   MediaDownloadQueue.getInstance(ctx).enqueue(url, cancelledUrls, task);
 *
 * Singleton so all chat download tasks share the same 3-slot pool,
 * preventing bandwidth flooding when many images are visible at once.
 */
public class MediaDownloadQueue {

    private static final String TAG = "MediaDownloadQueue";
    private static final int DEFAULT_MAX_CONCURRENT = 3;

    /** PERF FIX — same root cause as MediaUploadQueue: downloadTask kicks off
     *  MediaCache.getWithProgress() and returns instantly (it dispatches to
     *  its own internal pool), so the semaphore used to be released before
     *  any bytes moved. Now held until markComplete(url) is called. See
     *  MediaUploadQueue's javadoc for the full writeup. */
    private static final long COMPLETION_TIMEOUT_MS = 3 * 60 * 1000L; // 3 min safety net

    // ── Singleton ─────────────────────────────────────────────────────────
    private static volatile MediaDownloadQueue sInstance;

    public static MediaDownloadQueue getInstance(Context ctx) {
        if (sInstance == null) {
            synchronized (MediaDownloadQueue.class) {
                if (sInstance == null) {
                    sInstance = new MediaDownloadQueue(ctx.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    // ── State ─────────────────────────────────────────────────────────────
    private final Semaphore semaphore;
    private volatile boolean paused = false;
    private final Object pauseLock = new Object();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, CountDownLatch> inFlight = new ConcurrentHashMap<>();
    private final ConnectivityManager.NetworkCallback networkCallback;
    private final ConnectivityManager cm;

    private MediaDownloadQueue(Context ctx) {
        this(ctx, DEFAULT_MAX_CONCURRENT);
    }

    private MediaDownloadQueue(Context ctx, int maxConcurrent) {
        this.semaphore = new Semaphore(maxConcurrent, true /* fair */);
        cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.d(TAG, "Network available — resuming download queue");
                synchronized (pauseLock) {
                    paused = false;
                    pauseLock.notifyAll();
                }
            }

            @Override
            public void onLost(Network network) {
                Network active = cm.getActiveNetwork();
                if (active == null) {
                    Log.d(TAG, "Network lost — pausing download queue");
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
     * Enqueues a download task.
     *
     * @param url           The URL being downloaded — used as the cancel key.
     * @param cancelledUrls Thread-safe set; task is skipped if this URL is present.
     * @param downloadTask  Kicks off the download (e.g.
     *                      MediaCache.getWithProgress) and returns — does
     *                      NOT block until bytes finish arriving. The
     *                      concurrency slot is held until the caller calls
     *                      {@link #markComplete(String)} for this same url
     *                      from onReady/onError (every exit path, exactly
     *                      once).
     */
    public void enqueue(String url, java.util.Set<String> cancelledUrls, Runnable downloadTask) {
        pool.execute(() -> {
            // ── 1. Wait while offline ──────────────────────────────────────
            synchronized (pauseLock) {
                while (paused) {
                    if (cancelledUrls != null && cancelledUrls.contains(url)) return;
                    try {
                        pauseLock.wait(5_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

            // ── 2. Cancelled before starting? ──────────────────────────────
            if (cancelledUrls != null && cancelledUrls.contains(url)) return;

            // ── 3. Acquire concurrency slot ────────────────────────────────
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // ── 4. Final cancel check, then run + hold the slot until the
            //      caller signals REAL completion (not just "task started") ──
            CountDownLatch latch = new CountDownLatch(1);
            inFlight.put(url, latch);
            try {
                if (cancelledUrls == null || !cancelledUrls.contains(url)) {
                    downloadTask.run();
                    boolean signalled = latch.await(COMPLETION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (!signalled) {
                        Log.w(TAG, "markComplete() never received for " + url
                                + " within " + COMPLETION_TIMEOUT_MS + "ms — releasing slot anyway");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.remove(url);
                semaphore.release();
            }
        });
    }

    /** Signals url's download has truly finished — success or failure — so
     *  this slot can go to the next queued download. Call from onReady AND
     *  onError, never from downloadTask itself. Safe no-op for an unknown,
     *  already-completed, or already-timed-out url. */
    public void markComplete(String url) {
        CountDownLatch latch = inFlight.get(url);
        if (latch != null) latch.countDown();
    }

    /** Force-pause (e.g. called manually when going offline). */
    public void pause() {
        synchronized (pauseLock) { paused = true; }
    }

    /** Resume a manually-paused queue. */
    public void resume() {
        synchronized (pauseLock) { paused = false; pauseLock.notifyAll(); }
    }

    /**
     * Unregisters the network callback. Call once from Application.onTerminate()
     * or when the singleton is no longer needed.
     */
    public void destroy() {
        try { cm.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        pool.shutdownNow();
        sInstance = null;
    }
}
