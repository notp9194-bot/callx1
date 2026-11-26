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

    // v878: records per single-video bubble (target = 1.0). videoBind = bindVideo() calls (a recycled row
    // that rebinds counts again), videoRec = Bubble.record passes of a video bubble.
    private static long vidBindN, vidRecN, vidPreN;
    public static void videoPre()  { vidPreN++; }
    public static void videoBind() { vidBindN++; }
    public static void videoRec()  { vidRecN++; }
    public static void pre(long ns)     { preN++;  preNs  += ns; }
    public static void measure(long ns) { measN++; measNs += ns; if (ns > measMax) measMax = ns; }
    public static void bind(long ns)    { bindN++; bindNs += ns; if (ns > bindMax) bindMax = ns; }

    // v865: bind sub-sections (main thread). Use: long t = BubblePerfStats.t(); ...; BubblePerfStats.sec(SEC_X, t);
    public static final int SEC_PRE = 0, SEC_SPACING = 1, SEC_CANVAS = 2, SEC_TYPE = 3, SEC_REPLY = 4,
            SEC_RX = 5, SEC_AVATAR = 6, SEC_SEENBY = 7, SEC_TAIL = 8;
    private static final String[] SEC_NAME = {"bind.pre (cancel/index/spacing-before)", "bind.spacing",
            "bind.canvas (total bindCanvasMessage)", "  canvas.typeBind (text/media/etc)", "  canvas.reply",
            "  canvas.reactions/pinned/fwd", "  canvas.avatar", "  canvas.seenBy", "  canvas.tail (expiry/sel/layer/preRec sched)"};
    private static final long[] secNs = new long[9], secMax = new long[9];
    private static final long[] secN = new long[9];
    public static long t() { return on ? System.nanoTime() : 0L; }
    public static void sec(int id, long t0) {
        if (t0 == 0L) return;
        long d = System.nanoTime() - t0;
        secN[id]++; secNs[id] += d; if (d > secMax[id]) secMax[id] = d;
    }

    // v866: typeBind cost per message type (text / image / multi_media / ...)
    private static final java.util.HashMap<String, long[]> typeStat = new java.util.HashMap<>();
    public static void secType(String type, long t0) {
        if (t0 == 0L) return;
        long d = System.nanoTime() - t0;
        String k = type == null ? "?" : type;
        long[] a = typeStat.get(k);
        if (a == null) { if (typeStat.size() >= 24) return; typeStat.put(k, a = new long[3]); }
        a[0]++; a[1] += d; if (d > a[2]) a[2] = d;
    }

    // v868: what kind of bitmaps reach the bubbles (software ones are uploaded to the GPU on first draw)
    private static long bmHw, bmSw, bmPh, bmSwBytes, bmSwMax;
    public static void bitmap(android.graphics.Bitmap b, boolean placeholder) {
        if (b == null) return;
        if (placeholder) { bmPh++; return; }
        if (android.os.Build.VERSION.SDK_INT >= 26 && b.getConfig() == android.graphics.Bitmap.Config.HARDWARE) { bmHw++; return; }
        bmSw++;
        long by = b.getAllocationByteCount();
        bmSwBytes += by; if (by > bmSwMax) bmSwMax = by;
    }

    // v865: preRecord pipeline diagnostics (why was count=0?)
    private static long pSched, pFull, pRun, pStale, pEarly, pCached, pErr, pDrainFallback;
    public static void preSched()    { pSched++; }
    public static void preFullQ()    { pFull++; }
    public static void preRun()      { pRun++; }
    public static void preStale()    { pStale++; }
    public static void preEarly()    { pEarly++; }
    public static void preCached()   { pCached++; }
    public static void preErr()      { pErr++; }
    public static void preFallback() { pDrainFallback++; }

    // v865: why each Bubble.record happened
    private static long mDirty, mSize, mNoDL;
    public static void missWhy(boolean dirty, boolean sizeChanged, boolean noDisplayList) {
        if (dirty) mDirty++; else if (sizeChanged) mSize++; else if (noDisplayList) mNoDL++;
    }

    // v865: who dirtied a CLEAN cached bubble via invalidate() (the generator of re-records)
    private static final java.util.HashMap<String, int[]> dirtiers = new java.util.HashMap<>();
    private static long dirtiedClean;
    public static void dirtiedWhileClean() {
        dirtiedClean++;
        StackTraceElement[] st = new Throwable().getStackTrace();
        String key = "?";
        for (int i = 2; i < st.length && i < 8; i++) {
            String c = st[i].getClassName();
            if (c.startsWith("android.") || c.startsWith("androidx.recyclerview")) continue;
            key = c.substring(c.lastIndexOf('.') + 1) + "." + st[i].getMethodName() + ":" + st[i].getLineNumber();
            break;
        }
        int[] c = dirtiers.get(key);
        if (c == null) { if (dirtiers.size() >= 80) return; dirtiers.put(key, c = new int[1]); }
        c[0]++;
    }

    // frame metrics (background thread)
    private static final int CAP = 8000;
    private static final Object FL = new Object();
    private static final float[] frameMs = new float[CAP];
    private static int frameN;
    private static long fTotal, fLayout, fDraw, fSync;
    // v864 full stage breakdown: 0 input,1 anim,2 layout,3 draw,4 sync,5 cmdIssue,6 swap,7 unknownDelay,8 gpu(API31+)
    private static final String[] STAGE = {"input", "animation", "layout/measure", "draw", "sync",
            "cmdIssue", "swap", "unknownDelay", "gpu"};
    private static final long[] stAll = new long[9];
    private static final long[] stSlow = new long[9];     // frames > 2x budget
    private static final int[] domSlow = new int[9];      // dominant stage count among over-budget frames
    private static int slowN;
    private static boolean debuggable;
    private static float budgetMs = 16.67f;
    private static int over1, over2;
    private static Object listener;       // Window.OnFrameMetricsAvailableListener (API 24+)
    private static Window window;
    private static HandlerThread ht;

    @SuppressLint("NewApi")
    public static void start(Activity a) {
        stop(a);
        recN = recNs = recMax = hitN = preN = preNs = measN = measNs = measMax = bindN = bindNs = bindMax = 0;
        Arrays.fill(secN, 0L); Arrays.fill(secNs, 0L); Arrays.fill(secMax, 0L);
        pSched = pFull = pRun = pStale = pEarly = pCached = pErr = pDrainFallback = 0;
        typeStat.clear();
        bmHw = bmSw = bmPh = bmSwBytes = bmSwMax = 0;
        mDirty = mSize = mNoDL = 0; dirtiedClean = 0; dirtiers.clear();
        vidBindN = vidRecN = vidPreN = 0;
        startMs = System.currentTimeMillis();
        synchronized (FL) {
            frameN = 0; fTotal = fLayout = fDraw = fSync = 0; over1 = over2 = 0;
            Arrays.fill(stAll, 0L); Arrays.fill(stSlow, 0L); Arrays.fill(domSlow, 0); slowN = 0;
        }
        try {
            debuggable = (a.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (Throwable ignored) { }
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
                        final long[] st = new long[9];
                        st[0] = fm.getMetric(FrameMetrics.INPUT_HANDLING_DURATION);
                        st[1] = fm.getMetric(FrameMetrics.ANIMATION_DURATION);
                        st[2] = fm.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION);
                        st[3] = fm.getMetric(FrameMetrics.DRAW_DURATION);
                        st[4] = fm.getMetric(FrameMetrics.SYNC_DURATION);
                        st[5] = fm.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION);
                        st[6] = fm.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION);
                        st[7] = fm.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION);
                        if (Build.VERSION.SDK_INT >= 31) st[8] = fm.getMetric(FrameMetrics.GPU_DURATION);
                        for (int i = 0; i < 9; i++) stAll[i] += st[i];
                        if (ms > budgetMs) {
                            over1++;
                            int dom = 0;
                            for (int i = 1; i < 9; i++) if (st[i] > st[dom]) dom = i;
                            domSlow[dom]++;
                        }
                        if (ms > budgetMs * 2f) {
                            over2++; slowN++;
                            for (int i = 0; i < 9; i++) stSlow[i] += st[i];
                        }
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
        sb.append("build: ").append(debuggable ? "DEBUG (R8/baseline-profile off -> numbers pessimistic)" : "release").append('\n');
        sb.append("budget: ").append(String.format(Locale.US, "%.2f", budgetMs)).append(" ms/frame\n\n");

        sb.append("-- Bubble.record (cache miss; re-record) --\n");
        sb.append("count=").append(recN).append("  total=").append(ms(recNs)).append(" ms  avg=")
          .append(us(recN, recNs)).append(" us  max=").append(ms(recMax)).append(" ms\n");
        long draws = recN + hitN;
        sb.append("cache hits=").append(hitN).append("  hit-rate=")
          .append(draws == 0 ? "-" : (100 * hitN / draws) + "%").append('\n');
        sb.append("miss reason: dirty=").append(mDirty).append(" sizeChanged=").append(mSize)
          .append(" noDisplayList=").append(mNoDL).append('\n');
        sb.append("preRecord done=").append(preN).append("  total=").append(ms(preNs)).append(" ms\n");
        sb.append("preRecord pipeline: sched=").append(pSched).append(" queueFull=").append(pFull)
          .append(" run=").append(pRun).append(" staleSkip=").append(pStale).append(" earlySkip=").append(pEarly)
          .append(" alreadyCached=").append(pCached).append(" err=").append(pErr)
          .append(" fallbackDrain=").append(pDrainFallback).append("\n");
        sb.append("video bubbles: binds=").append(vidBindN).append(" records=").append(vidRecN)
          .append(" records/bind=").append(vidBindN == 0 ? "-" : String.format(Locale.US, "%.2f", (double) vidRecN / vidBindN))
          .append("  (target 1.00)  preRecorded video=").append(vidPreN).append("\n");
        sb.append("invalidate() on CLEAN bubble=").append(dirtiedClean).append(" (top callers):\n");
        {
            java.util.ArrayList<java.util.Map.Entry<String, int[]>> es = new java.util.ArrayList<>(dirtiers.entrySet());
            java.util.Collections.sort(es, (a, b) -> b.getValue()[0] - a.getValue()[0]);
            for (int i = 0; i < es.size() && i < 8; i++)
                sb.append("  ").append(es.get(i).getValue()[0]).append("x ").append(es.get(i).getKey()).append('\n');
        }
        sb.append('\n');

        sb.append("-- onMeasure --\n");
        sb.append("count=").append(measN).append("  total=").append(ms(measNs)).append(" ms  avg=")
          .append(us(measN, measNs)).append(" us  max=").append(ms(measMax)).append(" ms\n\n");

        sb.append("-- onBindViewHolder (full) --\n");
        sb.append("count=").append(bindN).append("  total=").append(ms(bindNs)).append(" ms  avg=")
          .append(us(bindN, bindNs)).append(" us  max=").append(ms(bindMax)).append(" ms\n\n");

        sb.append("-- bind sections (count / total ms / avg us / max ms) --\n");
        for (int i = 0; i < 9; i++) {
            sb.append(SEC_NAME[i]).append(": n=").append(secN[i]).append(" total=").append(ms(secNs[i]))
              .append(" avg=").append(us(secN[i], secNs[i])).append(" max=").append(ms(secMax[i])).append('\n');
        }
        sb.append("bitmaps set on bubbles: hardware=").append(bmHw).append(" software=").append(bmSw)
          .append(" (").append(bmSwBytes / 1024).append(" KB, max ").append(bmSwMax / 1024)
          .append(" KB) thumbhash-placeholder=").append(bmPh).append("\n\n");
        sb.append("typeBind by message type:\n");
        for (java.util.Map.Entry<String, long[]> e : typeStat.entrySet()) {
            long[] a = e.getValue();
            sb.append("  ").append(e.getKey()).append(": n=").append(a[0]).append(" total=").append(ms(a[1]))
              .append(" avg=").append(us(a[0], a[1])).append(" max=").append(ms(a[2])).append('\n');
        }
        sb.append('\n');

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
                sb.append("avg ms per frame, ALL frames:\n ");
                for (int i = 0; i < 9; i++) {
                    if (i == 8 && Build.VERSION.SDK_INT < 31) continue;
                    sb.append(String.format(Locale.US, " %s=%.2f", STAGE[i], stAll[i] / 1e6 / frameN));
                }
                sb.append('\n');
                if (slowN > 0) {
                    sb.append("avg ms per frame, frames over 2x budget (").append(slowN).append("):\n ");
                    for (int i = 0; i < 9; i++) {
                        if (i == 8 && Build.VERSION.SDK_INT < 31) continue;
                        sb.append(String.format(Locale.US, " %s=%.2f", STAGE[i], stSlow[i] / 1e6 / slowN));
                    }
                    sb.append('\n');
                }
                sb.append("slowest stage in over-budget frames (count):\n ");
                for (int i = 0; i < 9; i++) {
                    if (domSlow[i] > 0) sb.append(' ').append(STAGE[i]).append('=').append(domSlow[i]);
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
