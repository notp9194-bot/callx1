package com.callx.app.perf;

import android.app.Activity;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.FrameMetrics;
import android.view.Window;

import androidx.annotation.RequiresApi;

import com.callx.app.db.AppDatabase;
import com.callx.app.utils.AppBgExecutor;

import java.util.Arrays;
import java.util.List;

/**
 * PerformanceMonitor — real, on-device performance instrumentation for the
 * Chat List screen (and reusable for any other screen later).
 *
 * This is NOT a fake/demo report. Every number it hands back was actually
 * measured on this device, this session:
 *   - Screen load time     — wall-clock ms between ChatsFragment starting its
 *                             load and the first real data actually reaching
 *                             the RecyclerView (see markChatListLoadStart/End).
 *   - Row bind time         — wall-clock time spent inside
 *                             ChatListAdapter.onBindViewHolder() per row,
 *                             collected across every bind, not just one.
 *   - Frame jank            — real android.view.FrameMetrics captured from
 *                             the Window while the Chat List is the visible
 *                             tab (API 24+; below that, jank section is
 *                             reported as "unavailable" rather than faked).
 *   - Memory                — live Runtime/Debug heap snapshot at report time.
 *   - DB query cost         — an actual timed Room query run fresh each time
 *                             the report screen asks for it (not cached).
 *
 * All rolling stats are kept in small fixed-size circular buffers so this
 * class costs near-nothing to keep attached permanently — see RollingStats.
 */
public final class PerformanceMonitor {

    private static final PerformanceMonitor INSTANCE = new PerformanceMonitor();
    public static PerformanceMonitor get() { return INSTANCE; }
    private PerformanceMonitor() {}

    // ── Screen load time (Chat List) ────────────────────────────────────────
    private volatile long loadStartNanos = 0L;
    private final RollingStats loadTimesMs = new RollingStats(20);

    /** Call when the Chat List starts fetching/observing its data. */
    public void markChatListLoadStart() {
        loadStartNanos = System.nanoTime();
    }

    /** Call the FIRST time real (non-empty-shimmer) data actually reaches the RecyclerView. */
    public void markChatListLoadEnd() {
        long start = loadStartNanos;
        if (start == 0L) return;
        loadStartNanos = 0L;
        long ms = (System.nanoTime() - start) / 1_000_000L;
        loadTimesMs.add(ms);
    }

    // ── Row bind timing (ChatListAdapter.onBindViewHolder) ──────────────────
    private final RollingStats bindTimesUs = new RollingStats(1500);

    /** Returns a start token — pass to endBind(). Cheap (System.nanoTime only). */
    public long beginBind() {
        return System.nanoTime();
    }

    public void endBind(long startToken) {
        long micros = (System.nanoTime() - startToken) / 1_000L;
        bindTimesUs.add(micros);
    }

    // ── Frame jank tracking (real FrameMetrics, API 24+) ─────────────────────
    private Window.OnFrameMetricsAvailableListener frameListener;
    private Handler frameHandler;
    private final RollingStats frameDurationsMs = new RollingStats(1200);
    private volatile long totalFrames = 0;
    private volatile long jankyFrames = 0;
    private volatile float refreshRateHz = 60f;

    @RequiresApi(24)
    public void attachFrameTracking(Activity activity) {
        if (Build.VERSION.SDK_INT < 24 || activity == null) return;
        if (frameHandler == null) frameHandler = new Handler(Looper.getMainLooper());
        try {
            Display d = activity.getWindowManager().getDefaultDisplay();
            if (d != null && d.getRefreshRate() > 0) refreshRateHz = d.getRefreshRate();
        } catch (Exception ignored) { /* keep last known refresh rate */ }

        frameListener = (window, frameMetrics, dropCount) -> {
            FrameMetrics copy = new FrameMetrics(frameMetrics);
            long totalDurationNs = copy.getMetric(FrameMetrics.TOTAL_DURATION);
            long ms = totalDurationNs / 1_000_000L;
            frameDurationsMs.add(ms);
            totalFrames++;
            double budgetMs = 1000.0 / (refreshRateHz > 0 ? refreshRateHz : 60.0);
            if (ms > budgetMs) jankyFrames++;
        };
        try {
            activity.getWindow().addOnFrameMetricsAvailableListener(frameListener, frameHandler);
        } catch (Exception ignored) { /* window not ready — skip this session's tracking */ }
    }

    @RequiresApi(24)
    public void detachFrameTracking(Activity activity) {
        if (Build.VERSION.SDK_INT < 24 || activity == null || frameListener == null) return;
        try {
            activity.getWindow().removeOnFrameMetricsAvailableListener(frameListener);
        } catch (Exception ignored) { /* already detached / window gone */ }
    }

    // ── Memory snapshot (on demand, always live) ─────────────────────────────
    public static final class MemorySnapshot {
        public final long usedMb, maxMb, nativeMb;
        MemorySnapshot(long usedMb, long maxMb, long nativeMb) {
            this.usedMb = usedMb; this.maxMb = maxMb; this.nativeMb = nativeMb;
        }
        public int usedPct() { return maxMb <= 0 ? 0 : (int) Math.round(100.0 * usedMb / maxMb); }
    }

    public MemorySnapshot getMemorySnapshot() {
        Runtime rt = Runtime.getRuntime();
        long maxMb = rt.maxMemory() / (1024 * 1024);
        long totalMb = rt.totalMemory() / (1024 * 1024);
        long freeMb = rt.freeMemory() / (1024 * 1024);
        long usedMb = totalMb - freeMb;
        long nativeMb;
        try { nativeMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024); }
        catch (Exception e) { nativeMb = -1; }
        return new MemorySnapshot(usedMb, maxMb, nativeMb);
    }

    // ── DB query benchmark (real, timed, fresh every call — not cached) ──────
    public interface DbBenchCallback { void onResult(long queryMs, int rowCount); }

    /** Runs the actual chat-list Room query on a background thread and times it for real. */
    public void benchmarkChatListQuery(android.content.Context ctx, DbBenchCallback cb) {
        android.content.Context appCtx = ctx.getApplicationContext();
        AppBgExecutor.execute(() -> {
            long ms; int count;
            try {
                AppDatabase db = AppDatabase.getInstance(appCtx);
                long start = System.nanoTime();
                List<?> rows = db.chatDao().getAllChatsSync();
                ms = (System.nanoTime() - start) / 1_000_000L;
                count = rows != null ? rows.size() : 0;
            } catch (Exception e) {
                ms = -1; count = -1;
            }
            long finalMs = ms; int finalCount = count;
            new Handler(Looper.getMainLooper()).post(() -> { if (cb != null) cb.onResult(finalMs, finalCount); });
        });
    }

    // ── Aggregated report ─────────────────────────────────────────────────
    public static final class Stat {
        public final long count, avg, p50, p90, p99, max;
        Stat(long count, long avg, long p50, long p90, long p99, long max) {
            this.count = count; this.avg = avg; this.p50 = p50; this.p90 = p90; this.p99 = p99; this.max = max;
        }
    }

    public Stat getLoadTimeStatsMs() { return loadTimesMs.summarize(); }
    public Stat getBindTimeStatsUs() { return bindTimesUs.summarize(); }
    public Stat getFrameDurationStatsMs() { return frameDurationsMs.summarize(); }
    public long getTotalFrames() { return totalFrames; }
    public long getJankyFrames() { return jankyFrames; }
    public float getRefreshRateHz() { return refreshRateHz; }
    public boolean isFrameTrackingSupported() { return Build.VERSION.SDK_INT >= 24; }

    /** Clears all rolling counters so the next report reflects a fresh measurement window. */
    public void reset() {
        loadTimesMs.clear();
        bindTimesUs.clear();
        frameDurationsMs.clear();
        totalFrames = 0;
        jankyFrames = 0;
    }

    // ── Small fixed-capacity circular buffer with percentile support ────────
    private static final class RollingStats {
        private final long[] buf;
        private int size = 0;
        private int head = 0;

        RollingStats(int capacity) { buf = new long[capacity]; }

        synchronized void add(long v) {
            buf[head] = v;
            head = (head + 1) % buf.length;
            if (size < buf.length) size++;
        }

        synchronized void clear() { size = 0; head = 0; }

        synchronized Stat summarize() {
            if (size == 0) return new Stat(0, 0, 0, 0, 0, 0);
            long[] copy = Arrays.copyOf(buf, size);
            Arrays.sort(copy);
            long sum = 0;
            for (long v : copy) sum += v;
            long avg = sum / copy.length;
            long p50 = percentile(copy, 50);
            long p90 = percentile(copy, 90);
            long p99 = percentile(copy, 99);
            long max = copy[copy.length - 1];
            return new Stat(copy.length, avg, p50, p90, p99, max);
        }

        private long percentile(long[] sorted, int p) {
            if (sorted.length == 0) return 0;
            int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
            if (idx < 0) idx = 0;
            if (idx >= sorted.length) idx = sorted.length - 1;
            return sorted[idx];
        }
    }
}
