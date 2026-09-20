package com.callx.app.conversation.controllers;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * MediaDownloadQueue — Receiver-side equivalent of MediaUploadQueue.
 *
 * Mirrors the upload queue exactly:
 *   • Max 3 concurrent downloads (configurable).
 *   • Network-aware: pauses when offline, auto-resumes on reconnect.
 *   • FIFO ordering is preserved.
 *
 * v426 PERF: the queue used to park ONE THREAD PER WAITING TASK (a cached
 * thread pool whose workers blocked on a fair Semaphore / the offline
 * pauseLock) — opening a chat with 30 undownloaded photos meant ~27 idle
 * blocked threads. It is now a plain pending deque + a small dispatcher:
 * only running downloads (max 3) hold a thread, and pending ones can be
 * dropped for free via {@link #cancelPending(String)} when their row is
 * scrolled away/recycled before it ever started.
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
    private static final class Job {
        final String url;
        final java.util.Set<String> cancelledUrls;
        final Runnable task;
        Job(String url, java.util.Set<String> cancelledUrls, Runnable task) {
            this.url = url;
            this.cancelledUrls = cancelledUrls;
            this.task = task;
        }
    }

    private final int maxConcurrent;
    private volatile boolean paused = false;
    private final Object lock = new Object();          // guards pending + running
    private final ArrayDeque<Job> pending = new ArrayDeque<>();
    private int running = 0;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, CountDownLatch> inFlight = new ConcurrentHashMap<>();
    private final ConnectivityManager.NetworkCallback networkCallback;
    private final ConnectivityManager cm;

    private MediaDownloadQueue(Context ctx) {
        this(ctx, DEFAULT_MAX_CONCURRENT);
    }

    private MediaDownloadQueue(Context ctx, int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
        cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.d(TAG, "Network available — resuming download queue");
                paused = false;
                pump();
            }

            @Override
            public void onLost(Network network) {
                Network active = cm.getActiveNetwork();
                if (active == null) {
                    Log.d(TAG, "Network lost — pausing download queue");
                    paused = true;
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
        synchronized (lock) {
            pending.addLast(new Job(url, cancelledUrls, downloadTask));
        }
        pump();
    }

    /** Starts as many pending jobs as there are free slots (no-op while offline). */
    private void pump() {
        while (true) {
            final Job job;
            synchronized (lock) {
                if (paused || running >= maxConcurrent) return;
                job = pending.pollFirst();
                if (job == null) return;
                // Cancelled while still waiting? Drop it without ever taking a slot.
                if (job.cancelledUrls != null && job.cancelledUrls.contains(job.url)) continue;
                running++;
            }
            pool.execute(() -> runJob(job));
        }
    }

    private void runJob(Job job) {
        final CountDownLatch latch = new CountDownLatch(1);
        inFlight.put(job.url, latch);
        try {
            // Final cancel check, then run + hold the slot until the caller
            // signals REAL completion (not just "task started").
            if (job.cancelledUrls == null || !job.cancelledUrls.contains(job.url)) {
                job.task.run();
                boolean signalled = latch.await(COMPLETION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!signalled) {
                    Log.w(TAG, "markComplete() never received for " + job.url
                            + " within " + COMPLETION_TIMEOUT_MS + "ms — releasing slot anyway");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            inFlight.remove(job.url);
            synchronized (lock) { running--; }
            pump();
        }
    }

    /**
     * Drops every job for {@code url} that has NOT started yet (a running
     * download is left alone).
     *
     * @return true if at least one pending job was removed — the caller
     *         should then clear its own "download in progress" marker for
     *         this url, since neither onReady nor onError will ever fire.
     */
    public boolean cancelPendingUrl(String url) {
        if (url == null) return false;
        boolean removed = false;
        synchronized (lock) {
            for (Iterator<Job> it = pending.iterator(); it.hasNext(); ) {
                if (url.equals(it.next().url)) { it.remove(); removed = true; }
            }
        }
        return removed;
    }

    /** Static convenience — never instantiates the singleton just to cancel. */
    public static boolean cancelPending(String url) {
        MediaDownloadQueue q = sInstance;
        return q != null && q.cancelPendingUrl(url);
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
        paused = true;
    }

    /** Resume a manually-paused queue. */
    public void resume() {
        paused = false;
        pump();
    }

    /**
     * Unregisters the network callback. Call once from Application.onTerminate()
     * or when the singleton is no longer needed.
     */
    public void destroy() {
        try { cm.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        synchronized (lock) { pending.clear(); }
        pool.shutdownNow();
        sInstance = null;
    }
}
