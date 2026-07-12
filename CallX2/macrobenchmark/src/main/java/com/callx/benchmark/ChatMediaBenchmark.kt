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
 *  ChatMediaBenchmark — Media, Voice, Search, Emoji Reaction perf
 *  ─────────────────────────────────────────────────────────────────────
 *  Chat ke advanced interactions benchmark karta hai:
 *
 *  1. Media gallery open — PhotoView + Glide full-res load time
 *  2. AudioWaveformView render — voice note waveform draw time
 *  3. ChatSearchController — search debounce + result render time
 *  4. ChatReactionController — emoji reaction popup + animation
 *  5. MultiMediaPreviewDialog — multi-image preview open time
 *
 *  USAGE:
 *    ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
 *      -Pandroid.testInstrumentationRunnerArguments.class=\
 *      com.callx.benchmark.ChatMediaBenchmark
 * ══════════════════════════════════════════════════════════════════════
 */
@RunWith(AndroidJUnit4::class)
class ChatMediaBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /**
     * Audio waveform render benchmark:
     * AudioWaveformView.onDraw() CPU time during voice note scroll.
     * Target: onDraw < 3ms (otherwise causes frame drops on low-end devices).
     */
    @Test
    fun audioWaveformScrollPerf() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            TraceSectionMetric("AudioWaveform#onDraw"),
            MemoryUsageMetric(MemoryUsageMetric.MemoryMetricType.HEAP),
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerChats")), 5_000L)
        openFirstChat()
        Thread.sleep(600)

        // Scroll past voice note messages — triggers AudioWaveformView.onDraw()
        val rv = device.findObject(By.res(TARGET_PACKAGE, "recyclerMessages"))
            ?: return@measureRepeated
        repeat(4) {
            rv.scroll(Direction.UP, 0.8f, 500)
            Thread.sleep(200)
        }
    }

    /**
     * Chat search open + first result render:
     * ChatSearchController debounce + Firebase query + result bind time.
     * Target: first result visible < 400ms after typing stops.
     */
    @Test
    fun chatSearchOpenAndType() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            StartupTimingMetric(),
            FrameTimingMetric(),
            TraceSectionMetric("ChatSearch#query"),
            TraceSectionMetric("ChatSearch#bind"),
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerChats")), 5_000L)
        openFirstChat()
        Thread.sleep(500)

        // Tap search icon in ChatActivity toolbar
        val searchBtn = device.findObject(By.res(TARGET_PACKAGE, "btnSearch"))
            ?: device.findObject(By.desc("Search"))
            ?: return@measureRepeated
        searchBtn.click()

        device.wait(Until.hasObject(By.focused(true)), 2_000L)
        val searchField = device.findObject(By.focused(true))
        searchField?.text = "hello"
        Thread.sleep(600) // debounce settle

        // Wait for search results to render
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerSearchResults")), 3_000L)
        Thread.sleep(300)

        // Scroll through results
        val results = device.findObject(By.res(TARGET_PACKAGE, "recyclerSearchResults"))
        results?.scroll(Direction.DOWN, 0.8f, 600)
    }

    /**
     * Emoji reaction popup performance:
     * Long-press on message → ChatReactionController popup animation.
     * Target: popup visible < 100ms, animation P99 frame < 16ms.
     */
    @Test
    fun emojiReactionPopupPerf() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            TraceSectionMetric("EmojiReaction#popup"),
            TraceSectionMetric("EmojiReaction#animate"),
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerChats")), 5_000L)
        openFirstChat()
        Thread.sleep(600)

        val rv = device.findObject(By.res(TARGET_PACKAGE, "recyclerMessages"))
            ?: return@measureRepeated
        val firstMsg = rv.children.drop(1).firstOrNull() ?: rv.children.firstOrNull()
            ?: return@measureRepeated

        // Long press to trigger reaction popup
        repeat(3) {
            firstMsg.longClick()
            Thread.sleep(800) // animation complete
            // Dismiss by pressing back
            device.pressBack()
            Thread.sleep(400)
        }
    }

    /**
     * Media thumbnail grid scroll (MediaThumbAdapter):
     * When user taps media-attach icon — thumbnail grid must render at 60fps.
     * Glide thumbnail decode + RecyclerView bind is the hot path here.
     */
    @Test
    fun mediaThumbnailGridScroll() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            MemoryUsageMetric(MemoryUsageMetric.MemoryMetricType.HEAP),
            TraceSectionMetric("MediaThumb#bind"),
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerChats")), 5_000L)
        openFirstChat()
        Thread.sleep(500)

        // Tap media/attachment button
        val attachBtn = device.findObject(By.res(TARGET_PACKAGE, "btnAttach"))
            ?: device.findObject(By.desc("Attach"))
            ?: device.findObject(By.res(TARGET_PACKAGE, "btnMedia"))
            ?: return@measureRepeated
        attachBtn.click()
        Thread.sleep(600)

        val thumbGrid = device.findObject(By.res(TARGET_PACKAGE, "recyclerMediaThumbs"))
            ?: device.findObject(By.clazz("androidx.recyclerview.widget.RecyclerView"))
            ?: return@measureRepeated

        repeat(3) {
            thumbGrid.scroll(Direction.DOWN, 0.9f, 800)
            Thread.sleep(200)
            thumbGrid.scroll(Direction.UP, 0.9f, 800)
            Thread.sleep(200)
        }
    }

    /**
     * Chat emoji burst animation perf (ChatEmojiBurstController):
     * Double-tap on message triggers full-screen emoji burst.
     * All burst particles must render within 2 frames (< 32ms total).
     */
    @Test
    fun emojiBurstAnimationPerf() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            TraceSectionMetric("EmojiBurst#start"),
            TraceSectionMetric("EmojiBurst#draw"),
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerChats")), 5_000L)
        openFirstChat()
        Thread.sleep(600)

        val rv = device.findObject(By.res(TARGET_PACKAGE, "recyclerMessages"))
            ?: return@measureRepeated
        val msg = rv.children.firstOrNull() ?: return@measureRepeated

        repeat(3) {
            // Double-tap to trigger emoji burst
            msg.click()
            Thread.sleep(80)
            msg.click()
            Thread.sleep(1000) // burst animation complete
        }
    }

    // ── Helper ────────────────────────────────────────────────────────
    private fun androidx.benchmark.macro.MacrobenchmarkScope.openFirstChat() {
        val chatList = device.findObject(By.res(TARGET_PACKAGE, "recyclerChats"))
            ?: device.findObject(By.clazz("androidx.recyclerview.widget.RecyclerView"))
            ?: return
        chatList.children.firstOrNull()?.click()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerMessages")), 5_000L)
    }
}
