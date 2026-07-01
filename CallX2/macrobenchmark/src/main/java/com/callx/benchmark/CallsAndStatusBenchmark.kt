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
 *  CallsAndStatusBenchmark — Calls + Status feature performance
 *  ─────────────────────────────────────────────────────────────────────
 *  Feature-calls aur feature-status modules ke hot paths benchmark karta hai:
 *
 *  1. Calls tab open + CallHistoryAdapter scroll (CallsFragment)
 *  2. IncomingCallActivity render time (most latency-sensitive screen)
 *  3. Status feed open + StatusListAdapter first frame
 *  4. Status view transition (StatusFragment → StatusViewerActivity)
 *  5. StatusMediaPreloader warm-up effectiveness
 *
 *  USAGE:
 *    ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
 *      -Pandroid.testInstrumentationRunnerArguments.class=\
 *      com.callx.benchmark.CallsAndStatusBenchmark
 * ══════════════════════════════════════════════════════════════════════
 */
@RunWith(AndroidJUnit4::class)
class CallsAndStatusBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /**
     * Calls tab open + call history scroll.
     * CallsFragment + CallHistoryAdapter first bind must be < 16ms/frame.
     * If slower → CallHistoryAdapter.onBindViewHolder() is doing too much work.
     */
    @Test
    fun callsTabOpenAndScroll() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            StartupTimingMetric(),
            FrameTimingMetric(),
            TraceSectionMetric("CallsFragment#load"),
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerChats")), 5_000L)

        // Navigate to Calls tab
        val callsTab = device.findObject(By.res(TARGET_PACKAGE, "tabCalls"))
            ?: device.findObject(By.text("Calls"))
            ?: return@measureRepeated
        callsTab.click()

        device.wait(
            Until.hasObject(By.res(TARGET_PACKAGE, "recyclerCalls")),
            5_000L
        )
        Thread.sleep(400)

        val rv = device.findObject(By.res(TARGET_PACKAGE, "recyclerCalls"))
            ?: device.findObject(By.clazz("androidx.recyclerview.widget.RecyclerView"))
            ?: return@measureRepeated

        repeat(3) {
            rv.scroll(Direction.DOWN, 0.8f, 800)
            Thread.sleep(200)
            rv.scroll(Direction.UP, 0.8f, 800)
            Thread.sleep(200)
        }
    }

    /**
     * Incoming call screen render time:
     * IncomingCallActivity is the most latency-sensitive screen —
     * user sees it before they can answer. Must render < 200ms.
     * TraceSectionMetric("IncomingCall#render") measures this.
     *
     * Add to IncomingCallActivity.onCreate():
     *   Trace.beginSection("IncomingCall#render")  ...  Trace.endSection()
     */
    @Test
    fun incomingCallActivityRenderTime() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            StartupTimingMetric(),
            TraceSectionMetric("IncomingCall#render"),
            TraceSectionMetric("IncomingCall#ringStart"),
            MemoryUsageMetric(MemoryUsageMetric.MemoryMetricType.HEAP),
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 5,
    ) {
        // Start IncomingCallActivity directly via intent
        val intent = device.executeShellCommand(
            "am start -n $TARGET_PACKAGE/.incoming.IncomingCallActivity " +
            "--es callerName 'Benchmark Test' " +
            "--es callerPhone '+91-0000000000'"
        )
        device.wait(
            Until.hasObject(By.res(TARGET_PACKAGE, "btnAnswer")),
            5_000L
        )
        Thread.sleep(500)
        device.pressBack()
        Thread.sleep(300)
    }

    /**
     * Status feed scroll perf (StatusListAdapter):
     * StatusMediaPreloader pre-fetches next 2 statuses.
     * Frame timing during swipe shows if preloading is working.
     * If P99 > 20ms → preloader is not warming up Glide in time.
     */
    @Test
    fun statusFeedScrollPerf() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            MemoryUsageMetric(MemoryUsageMetric.MemoryMetricType.HEAP),
            TraceSectionMetric("StatusPreloader#prefetch"),
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerChats")), 5_000L)

        // Navigate to Status tab
        val statusTab = device.findObject(By.res(TARGET_PACKAGE, "tabStatus"))
            ?: device.findObject(By.text("Status"))
            ?: return@measureRepeated
        statusTab.click()

        device.wait(
            Until.hasObject(By.res(TARGET_PACKAGE, "recyclerStatus")),
            5_000L
        )
        Thread.sleep(500)

        val rv = device.findObject(By.res(TARGET_PACKAGE, "recyclerStatus"))
            ?: device.findObject(By.clazz("androidx.recyclerview.widget.RecyclerView"))
            ?: return@measureRepeated

        repeat(4) {
            rv.scroll(Direction.DOWN, 0.7f, 700)
            Thread.sleep(300)
            rv.scroll(Direction.UP, 0.7f, 700)
            Thread.sleep(300)
        }
    }

    /**
     * Status viewer open transition perf:
     * Tap a status → StatusFragment transitions to full-screen viewer.
     * The crossfade + media load must complete in < 300ms.
     */
    @Test
    fun statusViewerOpenTransition() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            StartupTimingMetric(),
            FrameTimingMetric(),
            TraceSectionMetric("StatusViewer#open"),
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerChats")), 5_000L)

        val statusTab = device.findObject(By.res(TARGET_PACKAGE, "tabStatus"))
            ?: device.findObject(By.text("Status"))
            ?: return@measureRepeated
        statusTab.click()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerStatus")), 5_000L)
        Thread.sleep(400)

        val firstStatus = device.findObject(By.res(TARGET_PACKAGE, "recyclerStatus"))
            ?.children?.firstOrNull()
            ?: return@measureRepeated
        firstStatus.click()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "statusViewerContainer")), 4_000L)
        Thread.sleep(500)
        device.pressBack()
    }

    /**
     * Group call screen open perf (GroupCallActivity):
     * WebRTC init + participant adapter first frame.
     * TraceSectionMetric("GroupCall#webrtcInit") measures WebRTC setup time.
     */
    @Test
    fun groupCallScreenOpenPerf() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            StartupTimingMetric(),
            TraceSectionMetric("GroupCall#webrtcInit"),
            MemoryUsageMetric(MemoryUsageMetric.MemoryMetricType.HEAP),
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerChats")), 5_000L)

        // Open a group chat
        val groupTab = device.findObject(By.res(TARGET_PACKAGE, "tabGroups"))
            ?: device.findObject(By.text("Groups"))
            ?: return@measureRepeated
        groupTab.click()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerGroups")), 5_000L)
        Thread.sleep(400)

        device.findObject(By.res(TARGET_PACKAGE, "recyclerGroups"))
            ?.children?.firstOrNull()?.click()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerMessages")), 5_000L)
        Thread.sleep(400)

        // Tap the voice/video call button in group chat toolbar
        val callBtn = device.findObject(By.res(TARGET_PACKAGE, "btnGroupCall"))
            ?: device.findObject(By.desc("Group call"))
            ?: return@measureRepeated
        callBtn.click()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "groupCallContainer")), 5_000L)
        Thread.sleep(600)
        device.pressBack()
    }
}
