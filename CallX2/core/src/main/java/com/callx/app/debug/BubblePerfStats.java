package com.callx.app.debug;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.FrameMetrics;
import android.view.Window;

import java.util.Arrays;
import java.util.Locale;

/**
 * v863: on-device measurement for chat bubble performance (3-dot menu -> "Bubble Perf").
 * Zero cost while {@link #on} is false (call sites do one volatile read). Counters are written from the
 * main thread only (record / measure / bind); frame metrics arrive on a private thread and are synchronized.
 * Flow: menu -> Start -> scroll / send / receive in the chat -> menu -> Bubble Perf -> Copy -> paste.
 */
public final class BubblePerfStats {
    private BubblePerfStats() {}

    public static volatile boolean on = false;

    // main-thread counters
    private static long recN, recNs, recMax, hitN, preN, preNs, measN, measNs, measMax, bindN, bindNs, bindMax;
    private static long startMs;

    public static void rec(long ns)     { recN++;  recNs  += ns; if (ns > recMax)  recMax  = ns; }
    public static void hit()            { hitN++; }
    public static void pre(long ns)     { preN++;  preNs  += ns; }
    public static void measure(long ns) { measN++; measNs += ns; if (ns > measMax) measMax = ns; }
    public static void bind(long ns)    { bindN++; bindNs += ns; if (ns > bindMax) bindMax = ns; }

    // frame metrics (background thread)
    private static final int CAP = 8000;
    private static final Object FL = new Object();
    private static final float[] frameMs = new float[CAP];
    private static int frameN;
    private static long fTotal, fLayout, fDraw, fSync;
    private static float budgetMs = 16.67f;
    private static int over1, over2;
    private static Object listener;       // Window.OnFrameMetricsAvailableListener (API 24+)
    private static Window window;
    private static HandlerThread ht;

    @SuppressLint("NewApi")
    public static void start(Activity a) {
        stop(a);
        recN = recNs = recMax = hitN = preN = preNs = measN = measNs = measMax = bindN = bindNs = bindMax = 0;
        startMs = System.currentTimeMillis();
        synchronized (FL) {
            frameN = 0; fTotal = fLayout = fDraw = fSync = 0; over1 = over2 = 0;
        }
        try {
            float hz = a.getWindowManager().getDefaultDisplay().getRefreshRate();
            if (hz > 20f) budgetMs = 1000f / hz;
        } catch (Throwable ignored) { }
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                ht = new HandlerThread("BubblePerfFrames");
                ht.start();
                final Window.OnFrameMetricsAvailableListener l = (w, fm, dropped) -> {
                    if (fm.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return;
                    long total = fm.getMetric(FrameMetrics.TOTAL_DURATION);
                    synchronized (FL) {
                        if (frameN < CAP) frameMs[frameN] = total / 1e6f;
                        frameN++;
                        fTotal += total;
                        fLayout += fm.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION);
                        fDraw += fm.getMetric(FrameMetrics.DRAW_DURATION);
                        fSync += fm.getMetric(FrameMetrics.SYNC_DURATION);
                        float ms = total / 1e6f;
                        if (ms > budgetMs) over1++;
                        if (ms > budgetMs * 2f) over2++;
                    }
                };
                listener = l;
                window = a.getWindow();
                window.addOnFrameMetricsAvailableListener(l, new Handler(ht.getLooper()));
            } catch (Throwable t) { listener = null; }
        }
        on = true;
    }

    @SuppressLint("NewApi")
    public static void stop(Activity a) {
        on = false;
        if (Build.VERSION.SDK_INT >= 24 && listener != null && window != null) {
            try { window.removeOnFrameMetricsAvailableListener((Window.OnFrameMetricsAvailableListener) listener); }
            catch (Throwable ignored) { }
        }
        listener = null; window = null;
        if (ht != null) { ht.quitSafely(); ht = null; }
    }

    public static boolean hasData() { return startMs != 0; }

    private static String ms(long ns) { return String.format(Locale.US, "%.2f", ns / 1e6); }
    private static String us(long n, long ns) { return n == 0 ? "-" : String.format(Locale.US, "%.0f", ns / 1000.0 / n); }

    private static float pct(float[] sorted, int n, float p) {
        if (n == 0) return 0f;
        int i = Math.min(n - 1, Math.max(0, (int) Math.ceil(p * n) - 1));
        return sorted[i];
    }

    public static String report() {
        StringBuilder sb = new StringBuilder(1024);
        long secs = startMs == 0 ? 0 : (System.currentTimeMillis() - startMs) / 1000;
        sb.append("== BUBBLE PERF ==\n");
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
          .append(" | Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("collected: ").append(secs).append(" s | collecting=").append(on).append('\n');
        sb.append("budget: ").append(String.format(Locale.US, "%.2f", budgetMs)).append(" ms/frame\n\n");

        sb.append("-- Bubble.record (cache miss; re-record) --\n");
        sb.append("count=").append(recN).append("  total=").append(ms(recNs)).append(" ms  avg=")
          .append(us(recN, recNs)).append(" us  max=").append(ms(recMax)).append(" ms\n");
        long draws = recN + hitN;
        sb.append("cache hits=").append(hitN).append("  hit-rate=")
          .append(draws == 0 ? "-" : (100 * hitN / draws) + "%").append('\n');
        sb.append("preRecord count=").append(preN).append("  total=").append(ms(preNs)).append(" ms\n\n");

        sb.append("-- onMeasure --\n");
        sb.append("count=").append(measN).append("  total=").append(ms(measNs)).append(" ms  avg=")
          .append(us(measN, measNs)).append(" us  max=").append(ms(measMax)).append(" ms\n\n");

        sb.append("-- onBindViewHolder (full) --\n");
        sb.append("count=").append(bindN).append("  total=").append(ms(bindNs)).append(" ms  avg=")
          .append(us(bindN, bindNs)).append(" us  max=").append(ms(bindMax)).append(" ms\n\n");

        sb.append("-- Frames --\n");
        synchronized (FL) {
            int n = Math.min(frameN, CAP);
            if (Build.VERSION.SDK_INT < 24) {
                sb.append("frame metrics need API 24+\n");
            } else if (n == 0) {
                sb.append("no frames captured\n");
            } else {
                float[] s = Arrays.copyOf(frameMs, n);
                Arrays.sort(s);
                sb.append("frames=").append(frameN).append('\n');
                sb.append(String.format(Locale.US, "total ms  P50=%.1f  P90=%.1f  P95=%.1f  P99=%.1f  max=%.1f\n",
                        pct(s, n, 0.50f), pct(s, n, 0.90f), pct(s, n, 0.95f), pct(s, n, 0.99f), s[n - 1]));
                sb.append("over budget=").append(over1).append(" (")
                  .append(100 * over1 / Math.max(1, frameN)).append("%)  over 2x budget=").append(over2)
                  .append(" (").append(100 * over2 / Math.max(1, frameN)).append("%)\n");
                sb.append(String.format(Locale.US, "avg ms per frame: layout/measure=%.2f  draw=%.2f  sync=%.2f\n",
                        fLayout / 1e6 / frameN, fDraw / 1e6 / frameN, fSync / 1e6 / frameN));
            }
        }
        return sb.toString();
    }
}
