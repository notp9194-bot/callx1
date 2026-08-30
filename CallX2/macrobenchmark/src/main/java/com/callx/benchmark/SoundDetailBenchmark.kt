package com.callx.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
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
 *  SoundDetailBenchmark  —  Startup + frame timing for Sound Detail
 *  ─────────────────────────────────────────────────────────────────────
 *  v311 (gap #6): SoundDetailFragment/SoundDetailActivity previously had
 *  NO macrobenchmark coverage at all — this repo's `macrobenchmark/`
 *  module existed, but every benchmark + baseline-profile journey in it
 *  was chat/status/calls only. That meant SoundDetailFragment's cold-open
 *  path (bindViews()'s ~48 view lookups, applySoundsNodeEntry(), the reel
 *  grid's DiffUtil/adapter setup, SoundWaveformView's waveform draw) was
 *  never AOT-compiled by the baseline profile, and had no jank regression
 *  guard either — see CallXBaselineProfileGenerator.kt's
 *  generateSoundDetailFlow() for the matching baseline-profile journey.
 *
 *  Kya test karta hai:
 *    1. Cold open of Sound Detail (Reels tab → tap a reel's music row)
 *    2. Reel-grid scroll jank, WITH baseline profile (Partial compilation)
 *    3. Same scroll, WITHOUT compilation — baseline numbers to diff against
 *
 *  RUN:
 *    ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
 *      -Pandroid.testInstrumentationRunnerArguments.class=\
 *      com.callx.benchmark.SoundDetailBenchmark
 * ══════════════════════════════════════════════════════════════════════
 */
@RunWith(AndroidJUnit4::class)
class SoundDetailBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /**
     * Cold start straight through to a populated Sound Detail screen —
     * the journey this file exists to give AOT coverage to. rv_sound_reels
     * appearing (not just the Activity/shimmer) is what StartupTimingMetric
     * times against, so this captures the actual "reels visible" moment,
     * same signal generateSoundDetailFlow() waits on.
     */
    @Test
    fun soundDetailColdOpenWithProfile() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        waitFor("recyclerChats")

        openReelsTab()
        openSoundDetailFromFirstReel()
    }

    /**
     * Reel-grid scroll jank once Sound Detail is already open — tests the
     * same ReelThumbAdapter + Glide-thumbnail path UserReelsActivity's own
     * grid benchmark covers, just reached via Sound Detail's entry point.
     */
    @Test
    fun soundDetailGridScrollWithProfile() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        waitFor("recyclerChats")

        openReelsTab()
        openSoundDetailFromFirstReel()

        val scroller = device.findObject(By.res(TARGET_PACKAGE, "scroll_sound_detail"))
            ?: return@measureRepeated
        repeat(3) {
            scroller.scroll(Direction.DOWN, 0.7f, 1000)
            Thread.sleep(200)
            scroller.scroll(Direction.UP, 0.7f, 1000)
            Thread.sleep(200)
        }
    }

    /**
     * Same scroll as above but WITHOUT compilation — establishes baseline
     * jank numbers so the P99 improvement from the profile is measurable,
     * same reasoning as ChatScrollBenchmark's *NoCompilation variant.
     */
    @Test
    fun soundDetailGridScrollNoCompilation() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        waitFor("recyclerChats")

        openReelsTab()
        openSoundDetailFromFirstReel()

        val scroller = device.findObject(By.res(TARGET_PACKAGE, "scroll_sound_detail"))
            ?: return@measureRepeated
        repeat(3) {
            scroller.fling(Direction.DOWN)
            Thread.sleep(300)
            scroller.fling(Direction.UP)
            Thread.sleep(300)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun androidx.benchmark.macro.MacrobenchmarkScope.waitFor(resId: String) {
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, resId)), 5_000L)
        Thread.sleep(400)
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.openReelsTab() {
        val tab = device.findObject(By.res(TARGET_PACKAGE, "nav_reels"))
            ?: device.findObject(By.text("Reels")) ?: return
        tab.click()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "vp_reels")), 5_000L)
        Thread.sleep(600)
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.openSoundDetailFromFirstReel() {
        val musicRow = device.findObject(By.res(TARGET_PACKAGE, "tv_music_name")) ?: return
        musicRow.click()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "rv_sound_reels")), 5_000L)
        Thread.sleep(600)
    }
}

private const val TARGET_PACKAGE = "com.callx.app"
