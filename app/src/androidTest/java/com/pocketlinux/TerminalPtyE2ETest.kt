package com.pocketlinux

import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketlinux.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalPtyE2ETest {

    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = E2ETestHelpers.createRuleChain(composeRule)

    @Test
    fun interactivePtyProvidesDevPtsSttySizeAndStatefulSession() {
        E2ETestHelpers.waitUntilLinuxReady(composeRule, timeoutMs = 120_000L)
        E2ETestHelpers.clickTag(composeRule, "tab_terminal")

        // 1. Genuine PTY allocation (/dev/pts/N)
        val ttyDelta = sendCommandAndAwaitOutput("tty", timeoutMs = 20_000L) { delta ->
            Regex("/dev/pts/\\d+").containsMatchIn(delta)
        }
        assertTrue(
            "Expected tty to allocate /dev/pts/*, got delta='$ttyDelta'",
            Regex("/dev/pts/\\d+").containsMatchIn(ttyDelta)
        )

        // 2. Non-zero terminal dimensions via stty size
        val sttyRegex = Regex("(?m)^\\s*([1-9]\\d*)\\s+([1-9]\\d*)\\s*$")
        val sttyDelta = sendCommandAndAwaitOutput("stty size", timeoutMs = 20_000L) { delta ->
            sttyRegex.containsMatchIn(delta)
        }
        val sttyMatch = sttyRegex.find(sttyDelta)
        assertTrue("Expected stty size '<rows> <cols>' output, got '$sttyDelta'", sttyMatch != null)
        val rows = sttyMatch!!.groupValues[1].toInt()
        val cols = sttyMatch.groupValues[2].toInt()
        assertTrue("Expected rows > 0 and cols > 0, got ${rows}x${cols}", rows > 0 && cols > 0)

        // 3. Stateful interactive session persistence across sequential commands
        sendCommandAndAwaitOutput(
            "export PL_E2E_VAR=pocket_42 && cd /tmp && printf '__STATE_%s__\\n' SET",
            timeoutMs = 20_000L
        ) { delta ->
            Regex("(?m)^\\s*__STATE_SET__\\s*$").containsMatchIn(delta)
        }

        val stateDelta = sendCommandAndAwaitOutput(
            "pwd && echo \"\$PL_E2E_VAR\"",
            timeoutMs = 20_000L
        ) { delta ->
            Regex("(?m)^\\s*/tmp\\s*$").containsMatchIn(delta) &&
                Regex("(?m)^\\s*pocket_42\\s*$").containsMatchIn(delta)
        }
        assertTrue(
            "Expected stateful pwd '/tmp' in subsequent command output, got '$stateDelta'",
            Regex("(?m)^\\s*/tmp\\s*$").containsMatchIn(stateDelta)
        )
        assertTrue(
            "Expected stateful env 'pocket_42' in subsequent command output, got '$stateDelta'",
            Regex("(?m)^\\s*pocket_42\\s*$").containsMatchIn(stateDelta)
        )
    }

    @Test
    fun interactivePtyExecutesApkAndPython3() {
        E2ETestHelpers.waitUntilLinuxReady(composeRule, timeoutMs = 120_000L)
        E2ETestHelpers.clickTag(composeRule, "tab_terminal")

        val apkDelta = sendCommandAndAwaitOutput("apk --version", timeoutMs = 20_000L) { delta ->
            delta.contains("apk-tools", ignoreCase = true)
        }
        assertTrue(
            "Expected 'apk-tools' in apk --version output, got '$apkDelta'",
            apkDelta.contains("apk-tools", ignoreCase = true)
        )

        val pyDelta = sendCommandAndAwaitOutput(
            "python3 -c \"print(6*7)\"",
            timeoutMs = 25_000L
        ) { delta ->
            Regex("(?m)^\\s*42\\s*$").containsMatchIn(delta)
        }
        assertTrue(
            "Expected '42' from python3 execution, got '$pyDelta'",
            Regex("(?m)^\\s*42\\s*$").containsMatchIn(pyDelta)
        )
    }

    private fun sendCommandAndAwaitOutput(
        command: String,
        timeoutMs: Long = 20_000L,
        predicate: (String) -> Boolean
    ): String {
        val beforeOutput = E2ETestHelpers.readNodeText(composeRule, "terminal_output")

        E2ETestHelpers.replaceTextTag(
            composeRule = composeRule,
            tag = "terminal_input",
            text = command,
            closeKeyboard = true
        )
        E2ETestHelpers.clickTag(composeRule, "terminal_send_btn")

        var lastDelta = ""
        E2ETestHelpers.waitUntilCondition(
            timeoutMs = timeoutMs,
            pollIntervalMs = 200L,
            failureMessage = {
                "Command '$command' output did not match expectation. lastDelta='${lastDelta.take(500)}'"
            }
        ) {
            val currentOutput = E2ETestHelpers.readNodeText(composeRule, "terminal_output")
            lastDelta = extractAppendedOutput(beforeOutput, currentOutput, command)
            predicate(lastDelta)
        }
        SystemClock.sleep(150L)
        return lastDelta
    }

    private fun extractAppendedOutput(
        before: String,
        after: String,
        command: String = ""
    ): String {
        if (before.isEmpty()) return after

        val normBefore = stripTrailingCursorAndWhitespace(before)
        if (normBefore.isEmpty()) return after
        if (after.startsWith(normBefore)) return after.substring(normBefore.length)

        // Multi-line fallback when scrollback trims top lines:
        // drop the trailing prompt line so we anchor on preceding output lines instead of the repeated bottom prompt.
        val beforeLines = normBefore.lines().filter { it.isNotBlank() }
        if (beforeLines.size >= 2) {
            val precedingLines = beforeLines.dropLast(1)
            val maxContextLines = minOf(3, precedingLines.size)
            for (lineCount in maxContextLines downTo 1) {
                val anchor = precedingLines.takeLast(lineCount).joinToString("\n").takeLast(160)
                if (anchor.isNotEmpty()) {
                    val idx = after.lastIndexOf(anchor)
                    if (idx >= 0) {
                        return after.substring(idx + anchor.length)
                    }
                }
            }
        }

        // Single-line / short-prompt scrollback trim fallback:
        // locate the echoed command prefix first if present, otherwise use the earliest matching prompt.
        if (command.isNotBlank()) {
            val cmdToken = command.lineSequence().first().trim().take(24)
            if (cmdToken.isNotEmpty()) {
                val cmdIdx = after.lastIndexOf(cmdToken)
                if (cmdIdx >= 0) {
                    return after.substring(cmdIdx + cmdToken.length)
                }
            }
        }

        val promptIdx = after.indexOf(normBefore)
        if (promptIdx >= 0) {
            return after.substring(promptIdx + normBefore.length)
        }

        val tailAnchor = normBefore.takeLast(minOf(120, normBefore.length))
        val tailIdx = after.indexOf(tailAnchor)
        return if (tailIdx >= 0) {
            after.substring(tailIdx + tailAnchor.length)
        } else {
            after
        }
    }

    private fun stripTrailingCursorAndWhitespace(text: String): String {
        var s = text.trimEnd { it.isWhitespace() || it in CURSOR_GLYPHS }
        while (s.endsWith("_") && (s.length == 1 || s[s.length - 2].isWhitespace())) {
            s = s.dropLast(1).trimEnd { it.isWhitespace() || it in CURSOR_GLYPHS }
        }
        return s
    }

    companion object {
        private val CURSOR_GLYPHS = charArrayOf(
            '\u2588', // █
            '\u258C', // ▌
            '\u2593', // ▓
            '\u2592', // ▒
            '\u2591', // ░
            '\u258F', // ▏
            '\u2581'  // ▁
        )
    }
}
