package com.callx.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ══════════════════════════════════════════════════════════════════════
 *  AdvancedChatMetricsBenchmark
 *  ─────────────────────────────────────────────────────────────────────
 *  3 advanced metric types combine karta hai:
 *
 *  1. TraceSectionMetric  — App ke specific `Trace.beginSection("X")`
 *     calls ka time measure karta hai. DB init, crypto warm-up, message
 *     decrypt — sab directly measurable hain isse.
 *
 *  2. MemoryUsageMetric   — Scroll ke dauran heap usage track karta hai.
 *     Agar scroll ke baad heap zyada hai → ViewHolder leak ya Bitmap
 *     recycle nahi ho raha.
 *
 *  3. Combined startup + frame + memory — full picture milti hai ek run mein.
 *
 *  USAGE:
 *    ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
 *      -Pandroid.testInstrumentationRunnerArguments.class=\
 *      com.callx.benchmark.AdvancedChatMetricsBenchmark
 *
 *  APP SIDE SETUP (Optional but recommended):
 *  In ChatActivity.onCreate(), AppDatabase.getInstance(), SecurityManager:
 *    Trace.beginSection("ChatActivity#onCreate")
 *    ...
 *    Trace.endSection()
 *  Without this, TraceSectionMetric falls back to system-level trace.
 * ══════════════════════════════════════════════════════════════════════
 */
@RunWith(AndroidJUnit4::class)
class AdvancedChatMetricsBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /**
     * Full startup: startup time + heap memory at full display.
     * MemoryUsageMetric(HEAP) → agar startup ke baad heap > 80MB hai
     * toh ChatActivity ya MainApplication mein leak hai.
     */
    @Test
    fun startupWithMemoryTracking() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            StartupTimingMetric(),
            MemoryUsageMetric(MemoryUsageMetric.MemoryMetricType.HEAP),
            MemoryUsageMetric(MemoryUsageMetric.MemoryMetricType.RSS),
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 6,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerChats")), 5_000L)
    }

    /**
     * DB + crypto warm-up trace:
     * Measures exactly how long AppDatabase.getInstance() + SecurityManager
     * block the main thread. This was the original "3-sec chat open" culprit.
     *
     * Add to AppDatabase.getInstance():
     *   Trace.beginSection("DB#getInstance")  ...  Trace.endSection()
     * Add to SecurityManager.<init>:
     *   Trace.beginSection("SecurityManager#init")  ...  Trace.endSection()
     */
    @Test
    fun dbAndCryptoInitTrace() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            TraceSectionMetric("DB#getInstance"),
            TraceSectionMetric("SecurityManager#init"),
            TraceSectionMetric("ChatActivity#onCreate"),
            StartupTimingMetric(),
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 6,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerChats")), 5_000L)
        // Open chat to trigger DB + crypto init trace
        val chatList = device.findObject(By.res(TARGET_PACKAGE, "recyclerChats"))
        chatList?.children?.firstOrNull()?.click()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerMessages")), 5_000L)
    }

    /**
     * Message decrypt trace + frame timing combined:
     * Measures message decryption overhead PER FRAME during scroll.
     * If TraceSectionMetric("Msg#decrypt") P99 > 4ms → decrypt is
     * happening on UI thread and must move to background.
     *
     * Add to MessageAdapter.onBindViewHolder():
     *   Trace.beginSection("Msg#decrypt")  ...  Trace.endSection()
     */
    @Test
    fun messageDecryptDuringScroll() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            TraceSectionMetric("Msg#decrypt"),
            TraceSectionMetric("Msg#bind"),
            MemoryUsageMetric(MemoryUsageMetric.MemoryMetricType.HEAP),
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerChats")), 5_000L)
        device.findObject(By.res(TARGET_PACKAGE, "recyclerChats"))
            ?.children?.firstOrNull()?.click()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerMessages")), 5_000L)
        Thread.sleep(600)

        val rv = device.findObject(By.res(TARGET_PACKAGE, "recyclerMessages"))
            ?: device.findObject(By.clazz("androidx.recyclerview.widget.RecyclerView"))
            ?: return@measureRepeated

        // 5 scroll passes — comprehensive decrypt trace capture
        repeat(5) {
            rv.scroll(Direction.UP, 0.9f, 600)
            Thread.sleep(150)
            rv.scroll(Direction.DOWN, 0.9f, 600)
            Thread.sleep(150)
        }
    }

    /**
     * Memory leak detector during heavy scroll:
     * Scrolls 200+ items up and down. If HEAP grows > 20MB net during
     * the test → strong leak signal in ViewHolder or Glide bitmap cache.
     * RSS growth indicates OS-level memory pressure.
     */
    @Test
    fun scrollMemoryLeakDetector() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            MemoryUsageMetric(MemoryUsageMetric.MemoryMetricType.HEAP),
            MemoryUsageMetric(MemoryUsageMetric.MemoryMetricType.RSS),
            FrameTimingMetric(),
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerChats")), 5_000L)
        device.findObject(By.res(TARGET_PACKAGE, "recyclerChats"))
            ?.children?.firstOrNull()?.click()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerMessages")), 5_000L)
        Thread.sleep(600)

        val rv = device.findObject(By.res(TARGET_PACKAGE, "recyclerMessages"))
            ?: return@measureRepeated

        // Heavy scroll — 8 full passes to catch accumulating leaks
        repeat(8) {
            rv.fling(Direction.UP)
            Thread.sleep(200)
            rv.fling(Direction.DOWN)
            Thread.sleep(200)
        }
    }

    /**
     * Swipe-reply gesture trace:
     * TraceSectionMetric("SwipeReply#start") measures how quickly
     * SwipeReplyHandler initializes the spring animation.
     * Target: < 2ms for smooth 60fps swipe feedback.
     *
     * Add to SwipeReplyHandler.onSwipeStarted():
     *   Trace.beginSection("SwipeReply#start")  ...  Trace.endSection()
     */
    @Test
    fun swipeReplyGestureTrace() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            TraceSectionMetric("SwipeReply#start"),
            TraceSectionMetric("SwipeReply#spring"),
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerChats")), 5_000L)
        device.findObject(By.res(TARGET_PACKAGE, "recyclerChats"))
            ?.children?.firstOrNull()?.click()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerMessages")), 5_000L)
        Thread.sleep(600)

        val rv = device.findObject(By.res(TARGET_PACKAGE, "recyclerMessages"))
            ?: return@measureRepeated
        val firstMsg = rv.children.firstOrNull() ?: return@measureRepeated

        // Simulate swipe-right gesture to trigger SwipeReplyHandler
        repeat(4) {
            firstMsg.swipe(Direction.RIGHT, 0.4f)
            Thread.sleep(400)
            firstMsg.swipe(Direction.LEFT, 0.4f)
            Thread.sleep(400)
        }
    }
}
