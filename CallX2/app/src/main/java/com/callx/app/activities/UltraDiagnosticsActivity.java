package com.callx.app.activities;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chatlist.ChatListAdapter;
import com.callx.app.chatlist.ChatListLayoutManager;
import com.callx.app.chatlist.ChatListTextPrecompute;
import com.callx.app.chatlist.ChatListTimeCache;
import com.callx.app.perf.PerformanceMonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * UltraDiagnosticsActivity — Chat List → 3-dot menu → "🔬 Ultra Advanced
 * Diagnostics" (also reachable from the regular "⚡ Performance" report).
 *
 * This is a DEEPER, LIVE full-body scan of the Chat List screen, built on
 * top of everything PerformanceReportActivity already measures, plus new
 * real instrumentation added specifically for this screen:
 *
 *   1. Main-thread responsiveness probe — live Handler round-trip lag
 *      (real ANR-risk signal, measured the moment you open this screen).
 *   2. Live RecyclerView introspection — actual attached RV: visible child
 *      count, RecycledViewPool occupancy, item view cache size, layout
 *      manager behaviour — read directly off the real object, not config.
 *   3. Firebase typing-listener leak check — net attach/detach counters
 *      from ChatListAdapter, so a listener leak shows up as a number
 *      instead of a hunch.
 *   4. Text/time LruCache efficiency — real hitCount()/missCount() from
 *      ChatListTextPrecompute + ChatListTimeCache.
 *   5. Memory + real ART GC counters + live thread count.
 *   6. Optional 5-second StrictMode live scan (Android 9+) — flags actual
 *      disk/network-on-main-thread calls while you scroll.
 *   7. The same load/bind/frame-jank/DB-query numbers as the regular
 *      report, folded into ONE combined root-cause analysis at the end.
 *
 * Every section either shows a real measured number or plainly says why it
 * can't yet (e.g. "Chat List not open right now") — nothing here is a
 * canned/simulated value. Use the numbers from this screen as the basis for
 * the next round of optimization work.
 */
public class UltraDiagnosticsActivity extends AppCompatActivity {

    // Same class of budgets as PerformanceReportActivity — kept local so this
    // screen can evolve its own thresholds independently.
    private static final long PROBE_GOOD_MS = 2, PROBE_OK_MS = 8;
    private static final long LOAD_GOOD_MS = 150, LOAD_OK_MS = 400;
    private static final double JANK_GOOD_PCT = 2.0, JANK_OK_PCT = 5.0;
    private static final long BIND_GOOD_US = 2000, BIND_OK_US = 8000;
    private static final long DB_GOOD_MS = 20, DB_OK_MS = 80;
    private static final int MEM_GOOD_PCT = 60, MEM_OK_PCT = 80;
    private static final double CACHE_GOOD_RATIO = 0.85, CACHE_OK_RATIO = 0.6;

    private LinearLayout root;
    private final List<String> rootCauseNotes = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Color.parseColor("#141414"));
        int hPad = dp(16);
        header.setPadding(hPad, hPad, hPad, hPad);

        TextView title = new TextView(this);
        title.setText("🔬 Ultra Advanced Diagnostics");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(20);
        close.setPadding(dp(12), 0, dp(4), 0);
        close.setOnClickListener(v -> finish());
        header.addView(close);

        outer.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        outer.addView(root);

        scroll.addView(outer);
        setContentView(scroll);

        renderAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Safety net: never leave a StrictMode scan armed after this screen
        // is backgrounded — restores the app's normal thread policy.
        PerformanceMonitor.get().stopStrictModeScan();
    }

    private void renderAll() {
        root.removeAllViews();
        rootCauseNotes.clear();
        // Reset the async DB-benchmark result each render pass — otherwise a
        // stale value from a previous renderAll() call would make
        // maybeRenderRootCause() think this pass's benchmark already finished.
        lastDbQueryMs = -1;
        lastDbRowCount = -1;
        rootCausePlaceholder = null;

        PerformanceMonitor pm = PerformanceMonitor.get();

        addIntro("Har number is screen ka LIVE measurement hai — kuch bhi simulate nahi kiya gaya. "
                + "Jo section abhi data nahi de sakta, wo bata dega kyun (jaise Chat List band hai).");

        renderMainThreadProbe(pm);
        renderRecyclerViewLiveState(pm);
        renderTypingListenerLeakCheck();
        renderCacheEfficiency();
        renderMemoryAndGc(pm);
        renderStrictModeScan(pm);
        renderCoreMetricsRecap(pm);

        addSectionHeader("🎯 Combined Root-Cause Analysis");
        TextView placeholder = addInfoLine("Waiting for the live DB benchmark above to finish…");
        rootCausePlaceholder = placeholder;
        maybeRenderRootCause(pm);

        Button rerun = new Button(this);
        rerun.setText("Reset Counters & Re-run Full Scan");
        rerun.setOnClickListener(v -> {
            pm.reset();
            Toast.makeText(this, "Counters reset. Scroll the Chats tab a bit, then reopen this screen.", Toast.LENGTH_LONG).show();
            renderAll();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(24);
        rlp.bottomMargin = dp(32);
        root.addView(rerun, rlp);
    }

    // ── 1. Main-thread responsiveness probe ──────────────────────────────────
    private void renderMainThreadProbe(PerformanceMonitor pm) {
        addSectionHeader("⏱️ Main-Thread Responsiveness (live probe)");
        TextView result = addMetricLine("Running probe…", "…", null);
        addInfoLine("Posts 10 Handler messages to the real main looper and measures actual "
                + "delivery lag — a genuine ANR-risk signal, not an estimate.");
        pm.probeMainThreadResponsiveness(10, (avgMs, maxMs, samples) -> {
            int rating = rate(maxMs, PROBE_GOOD_MS, PROBE_OK_MS);
            result.setText("Avg lag " + avgMs + "ms / Worst " + maxMs + "ms (" + samples + " pings)");
            result.setTextColor(colorFor(rating));
            if (rating >= 1) {
                rootCauseNotes.add("Main thread lag peaked at " + maxMs + "ms during the probe — something is "
                        + "blocking the UI thread (heavy Firebase callback, synchronous DB call, or GC pause). "
                        + "This directly causes dropped chat-list frames.");
            }
        });
    }

    // ── 2. Live RecyclerView introspection ───────────────────────────────────
    private void renderRecyclerViewLiveState(PerformanceMonitor pm) {
        addSectionHeader("📜 RecyclerView Live State");
        RecyclerView rv = pm.getChatListRecyclerView();
        if (rv == null) {
            addInfoLine("Chat List RecyclerView is not currently attached (open the Chats tab first, "
                    + "then come back to this screen without closing it).");
            return;
        }
        RecyclerView.Adapter<?> adapter = rv.getAdapter();
        int itemCount = adapter != null ? adapter.getItemCount() : -1;
        int visibleChildren = rv.getChildCount();
        int pooled = -1;
        try { pooled = rv.getRecycledViewPool().getRecycledViewCount(0); } catch (Exception ignored) {}

        addMetricLine("Adapter item count (live)", String.valueOf(itemCount), null);
        addMetricLine("Visible children right now", String.valueOf(visibleChildren), null);
        if (pooled >= 0) addMetricLine("RecycledViewPool occupancy (viewType 0)", pooled + " VHs cached", null);
        addMetricLine("hasFixedSize()", String.valueOf(rv.hasFixedSize()), rv.hasFixedSize() ? -1 : 1);
        addMetricLine("ItemAnimator", rv.getItemAnimator() == null ? "null (fast path)" : rv.getItemAnimator().getClass().getSimpleName(),
                rv.getItemAnimator() == null ? -1 : 0);

        RecyclerView.LayoutManager lm = rv.getLayoutManager();
        if (lm instanceof ChatListLayoutManager) {
            addInfoLine("Layout manager: ChatListLayoutManager — predictive animations off, "
                    + "one-screen extra layout space, measurement caching on. All three already "
                    + "in place for this screen.");
        } else if (lm != null) {
            addInfoLine("Layout manager: " + lm.getClass().getSimpleName() + " (not the custom ChatListLayoutManager).");
            rootCauseNotes.add("Chat List is using " + lm.getClass().getSimpleName()
                    + " instead of ChatListLayoutManager — you'll lose the extra-layout-space and "
                    + "predictive-animation-off optimizations that class provides.");
        }

        if (itemCount > 0 && pooled == 0 && visibleChildren < itemCount) {
            rootCauseNotes.add("RecycledViewPool is empty right now — every row scrolling into view for the "
                    + "first time this session pays full inflate cost. This is expected right after a cold "
                    + "start; if it stays empty after scrolling, the pool isn't being reused correctly.");
        }
    }

    // ── 3. Firebase typing-listener leak check ───────────────────────────────
    private void renderTypingListenerLeakCheck() {
        addSectionHeader("📡 Firebase Typing-Listener Leak Check");
        int active = ChatListAdapter.getActiveTypingListenerCount();
        int attaches = ChatListAdapter.getTotalTypingAttaches();
        int detaches = ChatListAdapter.getTotalTypingDetaches();
        addMetricLine("Active listeners right now", String.valueOf(active), null);
        addMetricLine("Total attach / detach (session)", attaches + " / " + detaches, null);
        addInfoLine("Active count should stay roughly bounded (≈ item view cache + pool size). "
                + "A number that keeps climbing while you scroll means listeners aren't being detached.");
        // 20 (setItemViewCacheSize) + 25 (RecyclerViewPoolViewModel chatsPool) + a small
        // margin for in-flight binds — real bound already established elsewhere in this codebase.
        int expectedBound = 20 + 25 + 10;
        if (active > expectedBound) {
            rootCauseNotes.add("Active Firebase typing listeners (" + active + ") is well above the expected "
                    + "bound (~" + expectedBound + " = item cache + pool + margin) — likely a listener leak in "
                    + "attachTypingListener/detachTypingListener or a VH recycling path that skips detach.");
        }
    }

    // ── 4. Text/time LruCache efficiency ─────────────────────────────────────
    private void renderCacheEfficiency() {
        addSectionHeader("🗂️ Text & Time Cache Efficiency");
        ChatListTextPrecompute.CacheStats text = ChatListTextPrecompute.getCacheStats();
        ChatListTimeCache.CacheStats time = ChatListTimeCache.getCacheStats();

        addMetricLine("Name cache hit ratio", pct(text.nameHitRatio()) + " (" + text.nameSize + "/" + text.nameMax + " entries)",
                rate((long) ((1 - text.nameHitRatio()) * 100), (long) ((1 - CACHE_GOOD_RATIO) * 100), (long) ((1 - CACHE_OK_RATIO) * 100)));
        addMetricLine("Message cache hit ratio", pct(text.msgHitRatio()) + " (" + text.msgSize + "/" + text.msgMax + " entries)",
                rate((long) ((1 - text.msgHitRatio()) * 100), (long) ((1 - CACHE_GOOD_RATIO) * 100), (long) ((1 - CACHE_OK_RATIO) * 100)));
        addMetricLine("Time-label cache hit ratio", pct(time.hitRatio()) + " (" + time.size + "/" + time.maxSize + " entries)",
                rate((long) ((1 - time.hitRatio()) * 100), (long) ((1 - CACHE_GOOD_RATIO) * 100), (long) ((1 - CACHE_OK_RATIO) * 100)));
        addInfoLine("Low hit ratio means onDraw() is still falling back to synchronous "
                + "TextUtils.ellipsize()/SimpleDateFormat work instead of serving from cache.");

        if (text.nameHitRatio() < CACHE_OK_RATIO && (text.nameHits + text.nameMisses) > 20)
            rootCauseNotes.add("Name cache hit ratio is only " + pct(text.nameHitRatio())
                    + " — the background precompute in ChatListTextPrecompute isn't keeping up, or the "
                    + "estimated name width is drifting from the real measured width, causing key misses.");
        if (text.msgHitRatio() < CACHE_OK_RATIO && (text.msgHits + text.msgMisses) > 20)
            rootCauseNotes.add("Message-preview cache hit ratio is only " + pct(text.msgHitRatio())
                    + " — same risk as the name cache: check the estimated message width vs the real layout width.");
    }

    // ── 5. Memory + GC + threads ──────────────────────────────────────────────
    private void renderMemoryAndGc(PerformanceMonitor pm) {
        addSectionHeader("📊 Memory, GC & Threads (live)");
        PerformanceMonitor.MemorySnapshot mem = pm.getMemorySnapshot();
        addMetricLine("Heap used", mem.usedMb + " MB / " + mem.maxMb + " MB (" + mem.usedPct() + "%)",
                rate(mem.usedPct(), MEM_GOOD_PCT, MEM_OK_PCT));
        if (mem.nativeMb >= 0) addMetricLine("Native heap", mem.nativeMb + " MB", null);

        PerformanceMonitor.GcStats gc = pm.getGcStats();
        if (gc.available) {
            addMetricLine("ART GC count (process lifetime)", String.valueOf(gc.gcCount), null);
            addMetricLine("ART GC total pause time", gc.gcTimeMs + " ms", null);
            addInfoLine("High GC count/time usually traces back to per-row allocations in onBindViewHolder "
                    + "or onDraw() — canvas rows here are meant to be allocation-free once cached.");
        } else {
            addInfoLine("GC runtime stats need Android 6.0+ (API 23).");
        }

        addMetricLine("Active threads (whole app)", String.valueOf(pm.getActiveThreadCount()), null);

        if (mem.usedPct() >= MEM_OK_PCT)
            rootCauseNotes.add("Heap usage is at " + mem.usedPct() + "% of max — close to memory pressure, "
                    + "watch bitmap/cache growth if the Chats tab is left open a long time.");
    }

    // ── 6. Optional StrictMode live scan ─────────────────────────────────────
    private TextView strictModeResult;
    private void renderStrictModeScan(PerformanceMonitor pm) {
        addSectionHeader("🚨 StrictMode Live Scan (optional, 5 seconds)");
        if (!pm.isStrictModeScanSupported()) {
            addInfoLine("Needs Android 9.0+ (API 28) for violation counting.");
            return;
        }
        strictModeResult = addInfoLine("Not run yet.");
        Button scanBtn = new Button(this);
        scanBtn.setText("Start 5s Scan (then go scroll the Chats tab)");
        scanBtn.setOnClickListener(v -> {
            pm.startStrictModeScan();
            strictModeResult.setText("Scanning… switch to the Chats tab and scroll for 5 seconds.");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                pm.stopStrictModeScan();
                int violations = pm.getStrictModeViolationCount();
                strictModeResult.setText(violations == 0
                        ? "0 violations — no disk/network calls detected on the main thread during the scan."
                        : violations + " violation(s) detected — a disk read/write or network call happened "
                          + "on the main thread while scrolling.");
                strictModeResult.setTextColor(colorFor(violations == 0 ? -1 : 1));
                if (violations > 0) {
                    rootCauseNotes.add("StrictMode caught " + violations + " disk/network call(s) on the main "
                            + "thread during a live scroll — this is a direct jank cause and should be moved "
                            + "to a background thread.");
                }
            }, 5000);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        root.addView(scanBtn, lp);
    }

    // ── 7. Core metrics recap (same source as the regular Performance report) ─
    private long lastDbQueryMs = -1;
    private int lastDbRowCount = -1;
    private TextView rootCausePlaceholder;

    private void renderCoreMetricsRecap(PerformanceMonitor pm) {
        addSectionHeader("📥 Screen Load / 🖱️ Frame Jank / 🧱 Bind Cost (recap)");

        PerformanceMonitor.Stat load = pm.getLoadTimeStatsMs();
        if (load.count == 0) {
            addInfoLine("No load measured yet — reopen the Chats tab from a cold start to capture one.");
        } else {
            addMetricLine("Load time avg / worst", load.avg + "ms / " + load.max + "ms", rate(load.avg, LOAD_GOOD_MS, LOAD_OK_MS));
        }

        if (!pm.isFrameTrackingSupported()) {
            addInfoLine("Frame jank tracking needs Android 7.0+.");
        } else if (pm.getTotalFrames() == 0) {
            addInfoLine("No frames captured yet — scroll the Chats tab, then come back.");
        } else {
            long total = pm.getTotalFrames(), janky = pm.getJankyFrames();
            double pct = 100.0 * janky / total;
            addMetricLine("Janky frames", janky + "/" + total + " (" + String.format(Locale.US, "%.1f", pct) + "%)",
                    rate((long) (pct * 10), (long) (JANK_GOOD_PCT * 10), (long) (JANK_OK_PCT * 10)));
        }

        PerformanceMonitor.Stat bind = pm.getBindTimeStatsUs();
        if (bind.count == 0) {
            addInfoLine("No rows bound yet.");
        } else {
            addMetricLine("Row bind p99", fmtUs(bind.p99), rate(bind.p99, BIND_GOOD_US, BIND_OK_US));
        }

        TextView dbLine = addMetricLine("Chat list DB query (live)", "measuring…", null);
        ProgressBar prog = new ProgressBar(this);
        root.addView(prog);
        pm.benchmarkChatListQuery(this, (queryMs, rowCount) -> {
            prog.setVisibility(View.GONE);
            lastDbQueryMs = queryMs;
            lastDbRowCount = rowCount;
            if (queryMs < 0) {
                dbLine.setText("Chat list DB query: failed to run");
            } else {
                dbLine.setText(queryMs + "ms for " + rowCount + " rows");
                dbLine.setTextColor(colorFor(rate(queryMs, DB_GOOD_MS, DB_OK_MS)));
                if (queryMs >= DB_OK_MS) rootCauseNotes.add("Chat list DB query took " + queryMs + "ms for "
                        + rowCount + " rows — check for a missing index on the sort column.");
            }
            maybeRenderRootCause(pm);
        });
    }

    // ── Root-cause synthesis (waits for the async DB benchmark) ──────────────
    private void maybeRenderRootCause(PerformanceMonitor pm) {
        if (lastDbQueryMs == -1 || rootCausePlaceholder == null) return;
        int idx = root.indexOfChild(rootCausePlaceholder);
        if (idx == -1) return;
        root.removeViewAt(idx);

        if (rootCauseNotes.isEmpty()) {
            addViewAt(idx, buildInfoLine("Nothing flagged against any budget on this run — every "
                    + "diagnostic above is within its healthy range right now."));
        } else {
            int insertAt = idx;
            for (String note : rootCauseNotes) {
                View bullet = buildBullet(note);
                root.addView(bullet, insertAt);
                insertAt++;
            }
        }
    }

    private void addViewAt(int index, View v) { root.addView(v, index); }

    // ── small render helpers (same visual language as PerformanceReportActivity) ─

    private int rate(long value, long goodMax, long okMax) {
        if (value <= goodMax) return -1;
        if (value <= okMax) return 0;
        return 1;
    }

    private int colorFor(int rating) {
        if (rating < 0) return Color.parseColor("#2E7D32");
        if (rating == 0) return Color.parseColor("#F9A825");
        return Color.parseColor("#C62828");
    }

    private void addSectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(Color.parseColor("#212121"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        lp.bottomMargin = dp(6);
        root.addView(tv, lp);
    }

    private void addIntro(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(Color.parseColor("#616161"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(tv, lp);
    }

    private TextView addMetricLine(String label, String value, @Nullable Integer rating) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(14);
        tvLabel.setTextColor(Color.parseColor("#616161"));
        row.addView(tvLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvValue = new TextView(this);
        tvValue.setText(value);
        tvValue.setTextSize(14);
        tvValue.setTypeface(null, Typeface.BOLD);
        tvValue.setTextColor(rating == null ? Color.parseColor("#212121") : colorFor(rating));
        row.addView(tvValue);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(4);
        root.addView(row, rlp);
        return tvValue;
    }

    private TextView addInfoLine(String text) {
        TextView tv = buildInfoLine(text);
        root.addView(tv);
        return tv;
    }

    private TextView buildInfoLine(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(Color.parseColor("#9E9E9E"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View buildBullet(String text) {
        TextView tv = new TextView(this);
        tv.setText("• " + text);
        tv.setTextSize(13);
        tv.setTextColor(Color.parseColor("#B71C1C"));
        tv.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private String fmtUs(long micros) {
        if (micros < 1000) return micros + "µs";
        return String.format(Locale.US, "%.1fms", micros / 1000.0);
    }

    private String pct(double ratio) {
        return String.format(Locale.US, "%.0f%%", ratio * 100);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
