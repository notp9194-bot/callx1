package com.callx.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
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
 * v931 — bubble-colour feature frame gate.
 *
 * PRECONDITION: pick + Apply a custom bubble colour once on the test device (Chat settings -> Bubble colour)
 * so the custom-colour paths (tick bitmaps, reply colours, accent memo) are the ones being measured.
 *
 * Trace sections emitted by the app (see MessageBubbleCanvasView):
 *   BubbleColor#refresh      — one row repainted after Apply / theme flip (should be rare during scroll)
 *   BubbleColor#prewarmPool  — shared bubble drawable pool rebuild on Apply
 * Gate suggestion: frameDurationCpuMs P99 < 24 ms, and BubbleColor#refresh count ~0 during a plain scroll.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class BubbleColourBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val metrics = listOf(
        FrameTimingMetric(),
        TraceSectionMetric("Msg#bind"),
        TraceSectionMetric("BubbleColor#refresh"),
    )

    /** Chat open + scroll with a custom colour saved: first-frame cost (tick bitmaps / pool) + scroll jank. */
    @Test
    fun chatOpenAndScrollCustomColour() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = metrics,
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 8,
    ) {
        pressHome()
        startActivityAndWait()
        if (!device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "rv_chats")), 8_000L)) return@measureRepeated
        val list = device.findObject(By.res(TARGET_PACKAGE, "rv_chats")) ?: return@measureRepeated
        list.children.firstOrNull()?.click() ?: return@measureRepeated
        if (!device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "rv_messages")), 8_000L)) return@measureRepeated
        Thread.sleep(600)
        val rv = device.findObject(By.res(TARGET_PACKAGE, "rv_messages")) ?: return@measureRepeated
        rv.setGestureMargin(device.displayWidth / 5)
        repeat(3) {
            rv.fling(Direction.UP)
            Thread.sleep(400)
            rv.fling(Direction.DOWN)
            Thread.sleep(400)
        }
    }
}
