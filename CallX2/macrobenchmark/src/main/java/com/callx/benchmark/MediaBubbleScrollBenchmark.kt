package com.callx.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
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
 *  MediaBubbleScrollBenchmark (v612) — measures the v611/v612 media-bubble work
 *  ─────────────────────────────────────────────────────────────────────
 *  Scrolls the 1:1 message list (rv_messages) of the FIRST chat in the chat list.
 *  PRECONDITION: that chat must contain many received/sent photos (>= ~60 messages,
 *  ideally a mix of cached and not-yet-downloaded) — otherwise the numbers say
 *  nothing about the bubble pipeline.
 *
 *  What each metric answers:
 *   • FrameTimingMetric            frameDurationCpuMs / frameOverrunMs  (jank, P50/P90/P99)
 *   • Msg#bind (Sum)               total main-thread bind cost per run
 *   • BubbleBitmap#decode (Sum)    total decode time on the 2 pipeline threads
 *                                  (background; should NOT correlate with jank)
 *   • BubbleBlur#compute (Sum)     placeholder blur, background
 *   • SpinnerClock#tick (Sum)      shared spinner ticker (needs a gated/downloading bubble)
 *   • MemoryUsageMetric HEAP       heap max/last — compare against the pre-v611 build
 *
 *  A/B: run on the pre-v611 build and on this one with the SAME device, same chat,
 *  and compare with scripts/compare_results.js. Do not compare across devices.
 *
 *  RUN:
 *    ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
 *      -Pandroid.testInstrumentationRunnerArguments.class=\
 *      com.callx.benchmark.MediaBubbleScrollBenchmark
 *  Then open the .perfetto-trace files under
 *  macrobenchmark/build/outputs/connected_android_test_additional_output/ in ui.perfetto.dev
 *  and look at the `Msg#bind`, `BubbleBitmap#decode` slices.
 * ══════════════════════════════════════════════════════════════════════
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class MediaBubbleScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val metrics = listOf(
        FrameTimingMetric(),
        TraceSectionMetric("Msg#bind", TraceSectionMetric.Mode.Sum),
        TraceSectionMetric("BubbleBitmap#decode", TraceSectionMetric.Mode.Sum),
        TraceSectionMetric("BubbleBlur#compute", TraceSectionMetric.Mode.Sum),
        TraceSectionMetric("SpinnerClock#tick", TraceSectionMetric.Mode.Sum),
        MemoryUsageMetric(MemoryUsageMetric.MemoryMetricType.HEAP),
    )

    /** Steady drag-scroll: the "normal user" case. Cache-hit rate of the look-ahead preloader shows here. */
    @Test
    fun mediaBubbleSlowScroll() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = metrics,
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 8,
    ) {
        pressHome()
        startActivityAndWait()
        openFirstChatOrReturn() ?: return@measureRepeated
        val rv = device.findObject(By.res(TARGET_PACKAGE, "rv_messages")) ?: return@measureRepeated
        rv.setGestureMargin(device.displayWidth / 5)
        repeat(4) {
            rv.scroll(Direction.UP, 0.8f, 700)
            Thread.sleep(150)
        }
        repeat(4) {
            rv.scroll(Direction.DOWN, 0.8f, 700)
            Thread.sleep(150)
        }
    }

    /** Fast flings: the worst case for pool exhaustion, bitmap churn and look-ahead misses. */
    @Test
    fun mediaBubbleFling() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = metrics,
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 8,
    ) {
        pressHome()
        startActivityAndWait()
        openFirstChatOrReturn() ?: return@measureRepeated
        val rv = device.findObject(By.res(TARGET_PACKAGE, "rv_messages")) ?: return@measureRepeated
        rv.setGestureMargin(device.displayWidth / 5)
        repeat(3) {
            rv.fling(Direction.UP)
            Thread.sleep(400)
            rv.fling(Direction.DOWN)
            Thread.sleep(400)
        }
    }

    /** Same as the fling test but without any AOT profile: shows how much of the cost is just JIT warm-up. */
    @Test
    fun mediaBubbleFlingNoCompilation() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = metrics,
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        iterations = 8,
    ) {
        pressHome()
        startActivityAndWait()
        openFirstChatOrReturn() ?: return@measureRepeated
        val rv = device.findObject(By.res(TARGET_PACKAGE, "rv_messages")) ?: return@measureRepeated
        rv.setGestureMargin(device.displayWidth / 5)
        repeat(3) {
            rv.fling(Direction.UP)
            Thread.sleep(400)
            rv.fling(Direction.DOWN)
            Thread.sleep(400)
        }
    }

    // ── helpers ──────────────────────────────────────────────────────
    /** Opens the first chat row. Returns null (benchmark iteration is skipped) if the chat list never appears. */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.openFirstChatOrReturn(): Unit? {
        if (!device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "rv_chats")), 8_000L)) return null
        val list = device.findObject(By.res(TARGET_PACKAGE, "rv_chats")) ?: return null
        val row = list.children.firstOrNull() ?: return null
        row.click()
        if (!device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "rv_messages")), 8_000L)) return null
        Thread.sleep(800) // let paging + look-ahead preload settle before measuring scroll
        return Unit
    }
}

