package com.callx.benchmark

import androidx.benchmark.macro.ExperimentalBaselineProfilesApi
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ══════════════════════════════════════════════════════════════════════
 *  CallX Baseline Profile Generator  (v2 — 9 journeys)
 *  ─────────────────────────────────────────────────────────────────────
 *  Run: ./gradlew :macrobenchmark:generateBaselineProfile
 *  Output: app/src/main/baseline-prof.txt (auto-replaced by AGP)
 *
 *  9 real user journeys cover:
 *    1. Cold start → chat list
 *    2. Open chat → scroll messages
 *    3. Send a message (input pipeline)
 *    4. Group chat open + scroll
 *    5. Swipe-to-reply gesture
 *    6. Search in chat
 *    7. Status tab open + scroll
 *    8. Calls tab open + scroll
 *    9. Emoji reaction (long press)
 * ══════════════════════════════════════════════════════════════════════
 */
@OptIn(ExperimentalBaselineProfilesApi::class)
@RunWith(AndroidJUnit4::class)
class CallXBaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateChatListStartup() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE, stableIterations = 3, maxIterations = 8,
    ) { journeyChatListStartup() }

    @Test
    fun generateChatOpenAndScroll() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE, stableIterations = 3, maxIterations = 8,
    ) {
        journeyChatListStartup()
        journeyOpenFirstChat()
        journeyScrollMessages()
    }

    @Test
    fun generateMessageSendFlow() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE, stableIterations = 3, maxIterations = 8,
    ) {
        journeyChatListStartup()
        journeyOpenFirstChat()
        journeySendMessage()
    }

    @Test
    fun generateGroupChatFlow() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE, stableIterations = 3, maxIterations = 8,
    ) {
        journeyChatListStartup()
        journeyOpenGroupTab()
        journeyOpenFirstChat()
        journeyScrollMessages()
    }

    @Test
    fun generateSwipeReplyFlow() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE, stableIterations = 3, maxIterations = 8,
    ) {
        journeyChatListStartup()
        journeyOpenFirstChat()
        journeySwipeReply()
    }

    @Test
    fun generateChatSearchFlow() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE, stableIterations = 3, maxIterations = 8,
    ) {
        journeyChatListStartup()
        journeyOpenFirstChat()
        journeyChatSearch()
    }

    @Test
    fun generateStatusFlow() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE, stableIterations = 3, maxIterations = 8,
    ) {
        journeyChatListStartup()
        journeyOpenStatusTab()
    }

    @Test
    fun generateCallsFlow() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE, stableIterations = 3, maxIterations = 8,
    ) {
        journeyChatListStartup()
        journeyOpenCallsTab()
    }

    @Test
    fun generateEmojiReactionFlow() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE, stableIterations = 3, maxIterations = 8,
    ) {
        journeyChatListStartup()
        journeyOpenFirstChat()
        journeyEmojiReaction()
    }
}

private const val TARGET_PACKAGE = "com.callx.app"
private const val TIMEOUT = 5_000L
private const val SETTLE = 600L

private fun MacrobenchmarkScope.journeyChatListStartup() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerChats")), TIMEOUT)
    Thread.sleep(SETTLE)
}

private fun MacrobenchmarkScope.journeyOpenFirstChat() {
    val rv = device.findObject(By.res(TARGET_PACKAGE, "recyclerChats"))
        ?: device.findObject(By.clazz("androidx.recyclerview.widget.RecyclerView"))
        ?: return
    rv.children.firstOrNull()?.click()
    device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerMessages")), TIMEOUT)
    Thread.sleep(SETTLE)
}

private fun MacrobenchmarkScope.journeyScrollMessages() {
    val rv = device.findObject(By.res(TARGET_PACKAGE, "recyclerMessages"))
        ?: device.findObject(By.clazz("androidx.recyclerview.widget.RecyclerView"))
        ?: return
    repeat(3) { rv.fling(Direction.UP); Thread.sleep(300); rv.fling(Direction.DOWN); Thread.sleep(300) }
}

private fun MacrobenchmarkScope.journeySendMessage() {
    val input = device.findObject(By.res(TARGET_PACKAGE, "inputMessage"))
        ?: device.findObject(By.clazz("android.widget.EditText")) ?: return
    input.click()
    device.wait(Until.hasObject(By.focused(true)), 2_000L)
    input.text = "perf test"
    Thread.sleep(400)
    device.findObject(By.res(TARGET_PACKAGE, "btnSend"))?.click()
    Thread.sleep(SETTLE)
}

private fun MacrobenchmarkScope.journeyOpenGroupTab() {
    val tab = device.findObject(By.res(TARGET_PACKAGE, "tabGroups"))
        ?: device.findObject(By.text("Groups")) ?: return
    tab.click()
    device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerGroups")), TIMEOUT)
    Thread.sleep(SETTLE)
    device.findObject(By.res(TARGET_PACKAGE, "recyclerGroups"))
        ?.children?.firstOrNull()?.click()
    device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerMessages")), TIMEOUT)
    Thread.sleep(SETTLE)
}

private fun MacrobenchmarkScope.journeySwipeReply() {
    val rv = device.findObject(By.res(TARGET_PACKAGE, "recyclerMessages")) ?: return
    val msg = rv.children.firstOrNull() ?: return
    repeat(2) { msg.swipe(Direction.RIGHT, 0.35f); Thread.sleep(500); device.pressBack(); Thread.sleep(300) }
}

private fun MacrobenchmarkScope.journeyChatSearch() {
    val btn = device.findObject(By.res(TARGET_PACKAGE, "btnSearch"))
        ?: device.findObject(By.desc("Search")) ?: return
    btn.click()
    device.wait(Until.hasObject(By.focused(true)), 2_000L)
    device.findObject(By.focused(true))?.text = "hello"
    Thread.sleep(700)
    device.pressBack()
    Thread.sleep(400)
}

private fun MacrobenchmarkScope.journeyOpenStatusTab() {
    val tab = device.findObject(By.res(TARGET_PACKAGE, "tabStatus"))
        ?: device.findObject(By.text("Status")) ?: return
    tab.click()
    device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerStatus")), TIMEOUT)
    Thread.sleep(SETTLE)
    val rv = device.findObject(By.res(TARGET_PACKAGE, "recyclerStatus"))
        ?: device.findObject(By.clazz("androidx.recyclerview.widget.RecyclerView")) ?: return
    rv.scroll(Direction.DOWN, 0.7f, 800)
    Thread.sleep(300)
}

private fun MacrobenchmarkScope.journeyOpenCallsTab() {
    val tab = device.findObject(By.res(TARGET_PACKAGE, "tabCalls"))
        ?: device.findObject(By.text("Calls")) ?: return
    tab.click()
    device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "recyclerCalls")), TIMEOUT)
    Thread.sleep(SETTLE)
    val rv = device.findObject(By.res(TARGET_PACKAGE, "recyclerCalls"))
        ?: device.findObject(By.clazz("androidx.recyclerview.widget.RecyclerView")) ?: return
    rv.scroll(Direction.DOWN, 0.7f, 800)
    Thread.sleep(300)
}

private fun MacrobenchmarkScope.journeyEmojiReaction() {
    val rv = device.findObject(By.res(TARGET_PACKAGE, "recyclerMessages")) ?: return
    val msg = rv.children.drop(1).firstOrNull() ?: rv.children.firstOrNull() ?: return
    msg.longClick()
    Thread.sleep(800)
    device.pressBack()
    Thread.sleep(400)
}
