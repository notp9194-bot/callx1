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
 *  ChatScrollBenchmark  —  Frame timing for chat scroll jank
 *  ─────────────────────────────────────────────────────────────────────
 *  FrameTimingMetric measures:
 *    • frameDurationCpuMs   — frame production time (target: <16ms @60fps)
 *    • frameOverrunMs       — how many ms over deadline each frame is
 *    • P50 / P90 / P99      — percentile breakdown (P99 > 32ms = jank)
 *
 *  Kya test karta hai:
 *    1. Chat list scroll (ChatsFragment RecyclerView)
 *    2. Message thread scroll (ChatActivity RecyclerView)
 *    3. Group chat scroll
 *    4. Fast fling (the harshest test — ViewHolder recycling + Glide)
 *
 *  RUN:
 *    ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
 *      -Pandroid.testInstrumentationRunnerArguments.class=\
 *      com.callx.benchmark.ChatScrollBenchmark
 * ══════════════════════════════════════════════════════════════════════
 */
@RunWith(AndroidJUnit4::class)
class ChatScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /**
     * Chat list slow scroll — simulates normal user browsing.
     * P99 > 24ms here means ChatListAdapter.onBindViewHolder() is slow.
     */
    @Test
    fun chatListScrollSlowWithProfile() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        waitForRecycler("recyclerChats")

        val rv = findRecycler("recyclerChats") ?: return@measureRepeated
        repeat(3) {
            rv.scroll(Direction.DOWN, 0.7f, 1200)
            Thread.sleep(200)
            rv.scroll(Direction.UP, 0.7f, 1200)
            Thread.sleep(200)
        }
    }

    /**
     * Chat list fast fling — triggers aggressive ViewHolder recycling.
     * Glide load + avatar bitmap decode must complete in <8ms here.
     */
    @Test
    fun chatListFlingWithProfile() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        waitForRecycler("recyclerChats")

        val rv = findRecycler("recyclerChats") ?: return@measureRepeated
        repeat(4) {
            rv.fling(Direction.DOWN)
            Thread.sleep(300)
            rv.fling(Direction.UP)
            Thread.sleep(300)
        }
    }

    /**
     * Message thread scroll — tests MessageAdapter + MessagePagingAdapter.
     * Heavy test: media thumbnails, reaction chips, reply bars all in view.
     */
    @Test
    fun messageThreadScrollWithProfile() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric(), StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        waitForRecycler("recyclerChats")

        // Open first chat
        val chatList = findRecycler("recyclerChats")
        chatList?.children?.firstOrNull()?.click()

        waitForRecycler("recyclerMessages")
        Thread.sleep(600)

        val messages = findRecycler("recyclerMessages") ?: return@measureRepeated
        repeat(3) {
            messages.scroll(Direction.UP, 0.8f, 800)
            Thread.sleep(200)
            messages.scroll(Direction.DOWN, 0.8f, 800)
            Thread.sleep(200)
        }
    }

    /**
     * Same as above but WITHOUT baseline profile — establishes baseline jank
     * numbers so you can measure the P99 improvement with the profile.
     */
    @Test
    fun messageThreadScrollNoCompilation() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        waitForRecycler("recyclerChats")

        val chatList = findRecycler("recyclerChats")
        chatList?.children?.firstOrNull()?.click()

        waitForRecycler("recyclerMessages")
        Thread.sleep(600)

        val messages = findRecycler("recyclerMessages") ?: return@measureRepeated
        repeat(3) {
            messages.fling(Direction.UP)
            Thread.sleep(300)
            messages.fling(Direction.DOWN)
            Thread.sleep(300)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun androidx.benchmark.macro.MacrobenchmarkScope.waitForRecycler(resId: String) {
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, resId)), 5_000L)
        Thread.sleep(400)
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.findRecycler(resId: String) =
        device.findObject(By.res(TARGET_PACKAGE, resId))
            ?: device.findObject(By.clazz("androidx.recyclerview.widget.RecyclerView"))
}
