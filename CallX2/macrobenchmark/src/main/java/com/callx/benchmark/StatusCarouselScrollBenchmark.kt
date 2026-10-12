package com.callx.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Status tab horizontal card carousel (StatusListAdapter$StatusCardAdapter) scroll timing.
 *
 *   ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.callx.benchmark.StatusCarouselScrollBenchmark
 *
 * Compare frameDurationCpuMs P90/P99 + frameOverrunMs between the two tests:
 *   - carouselFlingNoCompilation : pure JIT (worst case)
 *   - carouselFlingBaselineProfile: what ships (needs baseline-prof.txt rules for the card adapter)
 * Needs a device/emulator with at least a few statuses so the carousel has cards to scroll.
 */
@RunWith(AndroidJUnit4::class)
class StatusCarouselScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun carouselFlingNoCompilation() = fling(CompilationMode.None())

    @Test
    fun carouselFlingBaselineProfile() = fling(CompilationMode.Partial())

    private fun fling(mode: CompilationMode) = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.WARM,
        iterations = 8,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerChats")), 5_000L)
            val tab = device.findObject(By.res(TARGET_PACKAGE, "tabStatus"))
                ?: device.findObject(By.text("Status"))
            tab?.click()
            device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "rv_status_carousel")), 5_000L)
            Thread.sleep(800) // let skeleton -> first data -> thumbnails settle
        },
    ) {
        val carousel = device.findObject(By.res(TARGET_PACKAGE, "rv_status_carousel")) ?: return@measureRepeated
        carousel.setGestureMargin(device.displayWidth / 10)
        repeat(4) {
            carousel.fling(Direction.RIGHT)
            device.waitForIdle()
            carousel.fling(Direction.LEFT)
            device.waitForIdle()
        }
    }
}

private const val TARGET_PACKAGE = "com.callx.app"
