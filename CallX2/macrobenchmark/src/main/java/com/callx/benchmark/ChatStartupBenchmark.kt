package com.callx.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ══════════════════════════════════════════════════════════════════════
 *  ChatStartupBenchmark  —  Real device startup timing measurement
 *  ─────────────────────────────────────────────────────────────────────
 *  3 compilation modes compare karta hai:
 *
 *  1. None       — pure JIT (worst case, first-ever install on device)
 *  2. Partial    — current baseline profile applied (what ships in APK)
 *  3. Full       — full AOT (theoretical best case; not shipped in prod)
 *
 *  RUN:
 *    ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
 *      -Pandroid.testInstrumentationRunnerArguments.class=\
 *      com.callx.benchmark.ChatStartupBenchmark
 *
 *  RESULTS: Android Studio > Profiler > Macrobenchmark Results
 *           OR check build/outputs/connected_android_test_additional_output/
 * ══════════════════════════════════════════════════════════════════════
 */
@RunWith(AndroidJUnit4::class)
class ChatStartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /**
     * Baseline: no profile, pure JIT — shows cold start WITHOUT any
     * optimisation. This is the "before" number in your A/B comparison.
     */
    @Test
    fun startupColdNoCompilation() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 8,
    ) {
        pressHome()
        startActivityAndWait()
        waitForChatList()
    }

    /**
     * With Baseline Profile: measures the actual benefit of the profile
     * shipped in the APK (profileinstaller installs it at first app launch).
     * This is the "after" number — compare TimeToFullDisplayMs delta.
     */
    @Test
    fun startupColdWithBaselineProfile() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),     // Applies baseline profile
        startupMode = StartupMode.COLD,
        iterations = 8,
    ) {
        pressHome()
        startActivityAndWait()
        waitForChatList()
    }

    /**
     * Warm start: app already in memory, back stack cleared.
     * Should be fast even without a profile — if it isn't, look for
     * onResume/onStart allocations (Allocation Tracker in Studio).
     */
    @Test
    fun startupWarm() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 10,
    ) {
        pressHome()
        startActivityAndWait()
        waitForChatList()
    }

    /**
     * Hot start: process alive, activity in back stack.
     * If hot start > 300ms, there is an onResume regression.
     */
    @Test
    fun startupHot() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.HOT,
        iterations = 10,
    ) {
        pressHome()
        startActivityAndWait()
        waitForChatList()
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.waitForChatList() {
        device.wait(
            Until.hasObject(By.res(TARGET_PACKAGE, "recyclerChats")),
            5_000L
        )
    }
}
