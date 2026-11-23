package com.pocketlinux

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

data class FrameStats(
    val width: Int,
    val height: Int,
    val uniqueColorsSampled: Int,
    val nonUniformPixels: Int,
    val sha256: String
)

object E2ETestHelpers {

    const val ACTION_GET_STATUS = "com.pocketlinux.ACTION_GET_STATUS"
    const val ACTION_EXEC_LINUX = "com.pocketlinux.ACTION_EXEC_LINUX"
    const val ACTION_LAUNCH_GUI = "com.pocketlinux.ACTION_LAUNCH_GUI"
    const val ACTION_VERIFY_DESKTOP_FRAME = "com.pocketlinux.ACTION_VERIFY_DESKTOP_FRAME"

    private const val AUTOMATION_RECEIVER_CLASS = "com.pocketlinux.automation.AutomationReceiver"
    private const val DIAGNOSTICS_DIR_NAME = "diagnostics"

    val ALL_TEST_TAGS: List<String> = listOf(
        "linux_status_badge",
        "startup_time_badge",
        "memory_usage_badge",
        "tab_desktop",
        "tab_terminal",
        "tab_apps",
        "desktop_surface",
        "terminal_input",
        "terminal_send_btn",
        "terminal_output",
        "launcher_btn_xterm",
        "launcher_btn_mousepad",
        "launcher_btn_galculator",
        "launcher_btn_pcmanfm",
        "launcher_btn_xcalc",
        "launcher_btn_xclock",
        "launcher_btn_feh",
        "command_input",
        "command_run_btn",
        "command_result",
        "metrics_refresh_btn",
        "metrics_summary"
    )

    private val broadcastSeq = AtomicInteger(0)

    fun createNotificationPermissionRule(): TestRule {
        return if (Build.VERSION.SDK_INT >= 33) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TestRule { base, _ ->
                object : Statement() {
                    override fun evaluate() {
                        base.evaluate()
                    }
                }
            }
        }
    }

    fun createRuleChain(composeRule: TestRule): RuleChain {
        return RuleChain
            .outerRule(createNotificationPermissionRule())
            .around(composeRule)
    }

    fun waitUntilLinuxReady(
        composeRule: ComposeTestRule,
        timeoutMs: Long = 120_000L
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastBadgeText = ""
        var lastState = ""

        while (SystemClock.elapsedRealtime() < deadline) {
            val badgeText = runCatching {
                readNodeText(composeRule, "linux_status_badge")
            }.getOrDefault("")
            if (badgeText.isNotEmpty()) {
                lastBadgeText = badgeText
            }

            if (badgeText.contains("Error", ignoreCase = true)) {
                val diagStatus = runCatching { getStatus(timeoutMs = 5_000L).toString() }
                    .getOrElse { it.message ?: "unavailable" }
                fail("Linux runtime entered Error state in UI badge='$badgeText', status=$diagStatus")
            }

            val statusJson = runCatching { getStatus(timeoutMs = 5_000L) }.getOrNull()
            val runtimeState = statusJson?.optString("state", "").orEmpty()
            if (runtimeState.isNotEmpty()) {
                lastState = runtimeState
            }

            if (runtimeState.equals("Error", ignoreCase = true)) {
                fail("Linux runtime entered Error state in AutomationReceiver: $statusJson")
            }

            val badgeReady = badgeText.contains("Ready", ignoreCase = true)
            val serviceReady = runtimeState.equals("Ready", ignoreCase = true)
            if (badgeReady && serviceReady) {
                composeRule.waitForIdle()
                return
            }

            SystemClock.sleep(250L)
        }

        fail(
            "Timed out after ${timeoutMs}ms waiting for Linux runtime Ready state. " +
                "lastBadgeText='$lastBadgeText', lastAutomationState='$lastState'"
        )
    }

    fun sendAutomationBroadcast(
        action: String,
        extras: Map<String, String> = emptyMap(),
        timeoutMs: Long = 35_000L
    ): JSONObject {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val seq = broadcastSeq.incrementAndGet()
        val reqId = "androidtest_${seq}_${SystemClock.elapsedRealtime()}"
        val outFileName = sanitizeOutFileName("androidtest_${seq}.json")

        val diagDir = File(targetContext.filesDir, DIAGNOSTICS_DIR_NAME)
        if (!diagDir.exists()) {
            diagDir.mkdirs()
        }
        val outFile = File(diagDir, outFileName)
        if (outFile.exists()) {
            outFile.delete()
        }

        val intent = Intent(action).apply {
            component = ComponentName(targetContext.packageName, AUTOMATION_RECEIVER_CLASS)
            putExtra("req_id", reqId)
            putExtra("out_file", outFileName)
            for ((key, value) in extras) {
                putExtra(key, value)
            }
        }

        try {
            targetContext.sendBroadcast(intent)

            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var lastRaw = ""
            while (SystemClock.elapsedRealtime() < deadline) {
                if (outFile.exists() && outFile.length() > 0L) {
                    val raw = runCatching { outFile.readText(Charsets.UTF_8).trim() }.getOrDefault("")
                    if (raw.isNotEmpty()) {
                        lastRaw = raw
                        val parsed = runCatching { JSONObject(raw) }.getOrNull()
                        if (parsed != null) {
                            val returnedReqId = parsed.optString("reqId", "")
                            if (returnedReqId.isEmpty() || returnedReqId == reqId) {
                                return parsed
                            }
                        }
                    }
                }
                SystemClock.sleep(150L)
            }

            fail(
                "Timed out after ${timeoutMs}ms waiting for automation broadcast response: " +
                    "action=$action reqId=$reqId outFile=${outFile.absolutePath} lastRaw='${lastRaw.take(300)}'"
            )
            error("Unreachable")
        } finally {
            runCatching {
                if (outFile.exists()) {
                    outFile.delete()
                }
            }
        }
    }

    fun getStatus(timeoutMs: Long = 15_000L): JSONObject {
        return sendAutomationBroadcast(ACTION_GET_STATUS, emptyMap(), timeoutMs)
    }

    fun execLinux(command: String, timeoutMs: Long = 35_000L): JSONObject {
        return sendAutomationBroadcast(
            ACTION_EXEC_LINUX,
            mapOf("command" to command),
            timeoutMs
        )
    }

    fun launchGui(app: String, timeoutMs: Long = 35_000L): JSONObject {
        return sendAutomationBroadcast(
            ACTION_LAUNCH_GUI,
            mapOf("app" to app),
            timeoutMs
        )
    }

    fun verifyDesktopFrame(timeoutMs: Long = 20_000L): JSONObject {
        return sendAutomationBroadcast(ACTION_VERIFY_DESKTOP_FRAME, emptyMap(), timeoutMs)
    }

    fun cleanupTestGuiApps() {
        runCatching {
            execLinux(
                "killall -q xclock xcalc xterm mousepad galculator pcmanfm feh || true",
                timeoutMs = 15_000L
            )
        }
        SystemClock.sleep(300L)
    }

    fun scrollToTag(composeRule: ComposeTestRule, tag: String) {
        composeRule.waitForIdle()

        val initialNodes = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
        if (initialNodes.isEmpty()) {
            val verticalScrollMatcher =
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
            val verticalScrollables = composeRule.onAllNodes(verticalScrollMatcher)
                .fetchSemanticsNodes()
            val scrollMatcher = if (verticalScrollables.isNotEmpty()) {
                verticalScrollMatcher
            } else {
                hasScrollAction()
            }

            if (composeRule.onAllNodes(scrollMatcher).fetchSemanticsNodes().isNotEmpty()) {
                // First swipe up (scroll down) to reveal nodes below the current viewport
                repeat(6) {
                    if (composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)
                            .fetchSemanticsNodes().isNotEmpty()
                    ) {
                        return@repeat
                    }
                    runCatching {
                        composeRule.onAllNodes(scrollMatcher)[0].performTouchInput {
                            swipeUp()
                        }
                    }
                    composeRule.waitForIdle()
                }

                // If still not composed, swipe down (scroll up) for nodes off-screen above
                if (composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)
                        .fetchSemanticsNodes().isEmpty()
                ) {
                    repeat(8) {
                        if (composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)
                                .fetchSemanticsNodes().isNotEmpty()
                        ) {
                            return@repeat
                        }
                        runCatching {
                            composeRule.onAllNodes(scrollMatcher)[0].performTouchInput {
                                swipeDown()
                            }
                        }
                        composeRule.waitForIdle()
                    }
                }
            }
        }

        val targetInteraction = if (
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = false)
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            composeRule.onNodeWithTag(tag, useUnmergedTree = false)
        } else {
            composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        }

        runCatching { targetInteraction.performScrollTo() }
        composeRule.waitForIdle()
    }

    fun clickTag(composeRule: ComposeTestRule, tag: String) {
        scrollToTag(composeRule, tag)
        val node = resolveNode(composeRule, tag)
        node.assertIsDisplayed()
        node.performClick()
        composeRule.waitForIdle()
    }

    fun replaceTextTag(
        composeRule: ComposeTestRule,
        tag: String,
        text: String,
        closeKeyboard: Boolean = true
    ) {
        scrollToTag(composeRule, tag)
        val node = resolveNode(composeRule, tag)
        node.assertIsDisplayed()
        node.performTextReplacement(text)
        composeRule.waitForIdle()
        if (closeKeyboard) {
            runCatching { Espresso.closeSoftKeyboard() }
            composeRule.waitForIdle()
        }
    }

    fun readNodeText(composeRule: ComposeTestRule, tag: String): String {
        val mergedNodes = composeRule.onAllNodesWithTag(tag, useUnmergedTree = false)
            .fetchSemanticsNodes()
        val nodes = if (mergedNodes.isNotEmpty()) {
            mergedNodes
        } else {
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        }
        if (nodes.isEmpty()) {
            return ""
        }
        return nodes.joinToString("\n") { collectNodeText(it) }.trim()
    }

    fun waitUntilNodeTextContains(
        composeRule: ComposeTestRule,
        tag: String,
        substring: String,
        timeoutMs: Long = 20_000L,
        ignoreCase: Boolean = false
    ): String {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastText = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            lastText = runCatching { readNodeText(composeRule, tag) }.getOrDefault("")
            if (lastText.contains(substring, ignoreCase = ignoreCase)) {
                return lastText
            }
            SystemClock.sleep(200L)
        }
        fail(
            "Timed out after ${timeoutMs}ms waiting for tag '$tag' text to contain '$substring'. " +
                "Actual text='${lastText.take(500)}'"
        )
        return lastText
    }

    fun waitUntilCondition(
        timeoutMs: Long = 20_000L,
        pollIntervalMs: Long = 200L,
        failureMessage: () -> String,
        predicate: () -> Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching(predicate).getOrDefault(false)) {
                return
            }
            SystemClock.sleep(pollIntervalMs)
        }
        fail("Timed out after ${timeoutMs}ms: ${failureMessage()}")
    }

    fun <A : ComponentActivity> captureNodeScreenshot(
        composeRule: AndroidComposeTestRule<*, A>,
        tag: String
    ): Bitmap {
        composeRule.waitForIdle()
        val semanticsNode = composeRule.onNodeWithTag(tag, useUnmergedTree = false)
            .fetchSemanticsNode("Node with tag '$tag' not found for screenshot")
        val bounds = semanticsNode.boundsInWindow

        val windowOffset = IntArray(2)
        composeRule.runOnUiThread {
            composeRule.activity.window.decorView.getLocationOnScreen(windowOffset)
        }

        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val rawFull = uiAutomation.takeScreenshot()
            ?: error("UiAutomation.takeScreenshot() returned null")

        val swFull: Bitmap = if (rawFull.config == Bitmap.Config.HARDWARE || rawFull.config != Bitmap.Config.ARGB_8888) {
            val origConfig = rawFull.config
            val copied = rawFull.copy(Bitmap.Config.ARGB_8888, false)
            rawFull.recycle()
            copied ?: error("Failed to convert screenshot Bitmap (config=$origConfig) to ARGB_8888")
        } else {
            rawFull
        }

        var cropped: Bitmap? = null
        try {
            val left = (bounds.left + windowOffset[0]).roundToInt().coerceIn(0, swFull.width - 1)
            val top = (bounds.top + windowOffset[1]).roundToInt().coerceIn(0, swFull.height - 1)
            val right = (bounds.right + windowOffset[0]).roundToInt().coerceIn(left + 1, swFull.width)
            val bottom = (bounds.bottom + windowOffset[1]).roundToInt().coerceIn(top + 1, swFull.height)

            val cropWidth = (right - left).coerceAtLeast(1)
            val cropHeight = (bottom - top).coerceAtLeast(1)
            cropped = Bitmap.createBitmap(swFull, left, top, cropWidth, cropHeight)
            return cropped
        } finally {
            if (cropped !== swFull) {
                swFull.recycle()
            }
        }
    }

    fun analyzeBitmap(bitmap: Bitmap): FrameStats {
        val width = bitmap.width
        val height = bitmap.height
        require(width > 0 && height > 0) { "Bitmap has non-positive dimensions ${width}x${height}" }

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val firstPixel = pixels[0]
        var nonUniformCount = 0
        val uniqueColors = HashSet<Int>(256)
        // Row/column-independent 2D grid sampling prevents column aliasing with bitmap.width
        val stepX = (width / 64).coerceAtLeast(1)
        val stepY = (height / 64).coerceAtLeast(1)

        val digest = MessageDigest.getInstance("SHA-256")
        val rowBytes = ByteArray(width * 4)

        for (y in 0 until height) {
            val rowOffset = y * width
            val sampleRow = (y % stepY == 0)
            var bIdx = 0
            for (x in 0 until width) {
                val p = pixels[rowOffset + x]
                if (p != firstPixel) {
                    nonUniformCount++
                }
                if (sampleRow && x % stepX == 0 && uniqueColors.size < 4096) {
                    uniqueColors.add(p)
                }
                rowBytes[bIdx++] = ((p ushr 24) and 0xFF).toByte()
                rowBytes[bIdx++] = ((p ushr 16) and 0xFF).toByte()
                rowBytes[bIdx++] = ((p ushr 8) and 0xFF).toByte()
                rowBytes[bIdx++] = (p and 0xFF).toByte()
            }
            digest.update(rowBytes)
        }

        val shaHex = digest.digest().joinToString("") { "%02x".format(it) }
        return FrameStats(
            width = width,
            height = height,
            uniqueColorsSampled = uniqueColors.size,
            nonUniformPixels = nonUniformCount,
            sha256 = shaHex
        )
    }

    fun computePixelDiffRatio(before: Bitmap, after: Bitmap): Double {
        val width = minOf(before.width, after.width)
        val height = minOf(before.height, after.height)
        if (width <= 0 || height <= 0) return 0.0

        val beforePixels = IntArray(width * height)
        val afterPixels = IntArray(width * height)
        before.getPixels(beforePixels, 0, width, 0, 0, width, height)
        after.getPixels(afterPixels, 0, width, 0, 0, width, height)

        var diffCount = 0
        val total = width * height
        for (i in 0 until total) {
            if (beforePixels[i] != afterPixels[i]) {
                diffCount++
            }
        }
        return diffCount.toDouble() / total.toDouble()
    }

    fun jsonArrayToList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val result = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            result.add(array.optString(i, ""))
        }
        return result
    }

    private fun resolveNode(composeRule: ComposeTestRule, tag: String) =
        if (composeRule.onAllNodesWithTag(tag, useUnmergedTree = false)
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            composeRule.onNodeWithTag(tag, useUnmergedTree = false)
        } else {
            composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        }

    private fun collectNodeText(node: SemanticsNode): String {
        val parts = mutableListOf<String>()
        collectNodeTextRecursive(node, parts)
        return parts.joinToString("\n")
    }

    private fun collectNodeTextRecursive(node: SemanticsNode, out: MutableList<String>) {
        val textList = node.config.getOrNull(SemanticsProperties.Text)
        if (!textList.isNullOrEmpty()) {
            val joined = textList.joinToString("") { it.text }
            if (joined.isNotEmpty()) {
                out.add(joined)
            }
        }
        val editable = node.config.getOrNull(SemanticsProperties.EditableText)
        if (editable != null && editable.text.isNotEmpty()) {
            out.add(editable.text)
        }
        for (child in node.children) {
            collectNodeTextRecursive(child, out)
        }
    }

    private fun sanitizeOutFileName(raw: String): String {
        val sanitized = raw.replace("/", "_").replace("\\", "_")
            .replace(Regex("\\.\\.+"), "_")
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('.', '_', '-')
        val nonEmpty = if (sanitized.isEmpty()) "androidtest_action" else sanitized
        return if (nonEmpty.endsWith(".json")) nonEmpty else "$nonEmpty.json"
    }
}
