package com.callx.benchmark

import androidx.benchmark.macro.ExperimentalBaselineProfilesApi
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
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
 *  CallX Baseline Profile Generator
 *  ─────────────────────────────────────────────────────────────────────
 *  AGP + BaselineProfileRule is used to record REAL device traces of the
 *  user journeys that matter most for chat performance. ART uses these
 *  traces to decide which methods to AOT-compile at install time.
 *
 *  RUN COMMAND (real device / API 31+ emulator with Google Play):
 *    ./gradlew :macrobenchmark:generateBaselineProfile
 *
 *  OUTPUT:
 *    app/src/main/baseline-prof.txt  (auto-written by AGP plugin)
 *
 *  HOW IT WORKS:
 *    1. App cold-start karta hai COLD mode mein
 *    2. 3× journeys repeat karta hai (ART traces collect karne ke liye)
 *    3. Per-method HSP/SP/P flags decide hote hain actual usage se
 *    4. Generated profile hand-written profile se far more accurate hota hai
 * ══════════════════════════════════════════════════════════════════════
 */
@OptIn(ExperimentalBaselineProfilesApi::class)
@RunWith(AndroidJUnit4::class)
class CallXBaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    /** Journey 1: Cold start → Chat list visible */
    @Test
    fun generateChatListStartup() {
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            stableIterations = 3,
            maxIterations = 8,
        ) {
            journeyChatListStartup()
        }
    }

    /** Journey 2: Cold start → Open a chat → Scroll messages */
    @Test
    fun generateChatOpenAndScroll() {
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            stableIterations = 3,
            maxIterations = 8,
        ) {
            journeyChatListStartup()
            journeyOpenFirstChat()
            journeyScrollMessages()
        }
    }

    /** Journey 3: Cold start → Send a message (warm-up input pipeline) */
    @Test
    fun generateMessageSendFlow() {
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            stableIterations = 3,
            maxIterations = 8,
        ) {
            journeyChatListStartup()
            journeyOpenFirstChat()
            journeySendMessage()
        }
    }

    /** Journey 4: Group chat open + scroll (separate hot path from 1:1 chat) */
    @Test
    fun generateGroupChatFlow() {
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            stableIterations = 3,
            maxIterations = 8,
        ) {
            journeyChatListStartup()
            journeyOpenGroupTab()
            journeyOpenFirstChat()
            journeyScrollMessages()
        }
    }
}

// ── Shared journey helpers ─────────────────────────────────────────────

private const val TARGET_PACKAGE = "com.callx.app"
private const val LAUNCH_TIMEOUT_MS = 5_000L
private const val UI_SETTLE_MS = 800L

/**
 * Start the app cold and wait for the chat list to be displayed.
 * pressHome() + startActivity() = true cold start (process kill + re-launch).
 */
private fun MacrobenchmarkScope.journeyChatListStartup() {
    pressHome()
    startActivityAndWait()

    // Wait for RecyclerView that holds the chat list to appear
    device.wait(
        Until.hasObject(By.res(TARGET_PACKAGE, "recyclerChats")),
        LAUNCH_TIMEOUT_MS
    )
    Thread.sleep(UI_SETTLE_MS)
}

/**
 * Tap on the first conversation in the list to open ChatActivity.
 */
private fun MacrobenchmarkScope.journeyOpenFirstChat() {
    val chatList = device.findObject(By.res(TARGET_PACKAGE, "recyclerChats"))
        ?: device.findObject(By.clazz("androidx.recyclerview.widget.RecyclerView"))
        ?: return

    val firstItem = chatList.children.firstOrNull() ?: return
    firstItem.click()

    // Wait for MessageAdapter's RecyclerView to appear in ChatActivity
    device.wait(
        Until.hasObject(By.res(TARGET_PACKAGE, "recyclerMessages")),
        LAUNCH_TIMEOUT_MS
    )
    Thread.sleep(UI_SETTLE_MS)
}

/**
 * Scroll the message list up and down to warm up the RecyclerView scroll path.
 * This is where "first-scroll jank" comes from in the profiler.
 */
private fun MacrobenchmarkScope.journeyScrollMessages() {
    val messageList = device.findObject(By.res(TARGET_PACKAGE, "recyclerMessages"))
        ?: device.findObject(By.clazz("androidx.recyclerview.widget.RecyclerView"))
        ?: return

    repeat(3) {
        messageList.fling(Direction.UP)
        Thread.sleep(300)
        messageList.fling(Direction.DOWN)
        Thread.sleep(300)
    }
}

/**
 * Tap the message input field and type a short message to warm up
 * the input pipeline (GifAwareEditText, TypingDotsAnimator, etc.).
 */
private fun MacrobenchmarkScope.journeySendMessage() {
    val inputField = device.findObject(By.res(TARGET_PACKAGE, "inputMessage"))
        ?: device.findObject(By.clazz("android.widget.EditText"))
        ?: return

    inputField.click()
    device.wait(Until.hasObject(By.focused(true)), 2_000L)
    inputField.text = "perf test"
    Thread.sleep(500)

    val sendBtn = device.findObject(By.res(TARGET_PACKAGE, "btnSend"))
    sendBtn?.click()
    Thread.sleep(UI_SETTLE_MS)
}

/**
 * Switch to the Groups tab (if present) to warm up GroupChatActivity hot path.
 */
private fun MacrobenchmarkScope.journeyOpenGroupTab() {
    val groupsTab = device.findObject(By.res(TARGET_PACKAGE, "tabGroups"))
        ?: device.findObject(By.text("Groups"))
        ?: return

    groupsTab.click()
    device.wait(
        Until.hasObject(By.res(TARGET_PACKAGE, "recyclerGroups")),
        LAUNCH_TIMEOUT_MS
    )
    Thread.sleep(UI_SETTLE_MS)
}
