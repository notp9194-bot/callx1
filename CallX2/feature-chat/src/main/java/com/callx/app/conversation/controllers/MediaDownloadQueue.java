package com.callx.app.conversation.controllers;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

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
     * @param downloadTask  The actual download runnable (e.g. MediaCache.getWithProgress).
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

            // ── 4. Final cancel check + run ────────────────────────────────
            try {
                if (cancelledUrls == null || !cancelledUrls.contains(url)) {
                    downloadTask.run();
                }
            } finally {
                semaphore.release();
            }
        });
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
