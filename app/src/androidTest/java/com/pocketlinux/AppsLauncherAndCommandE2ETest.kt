package com.pocketlinux

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketlinux.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppsLauncherAndCommandE2ETest {

    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = E2ETestHelpers.createRuleChain(composeRule)

    @After
    fun tearDown() {
        E2ETestHelpers.cleanupTestGuiApps()
    }

    @Test
    fun commandRunnerAndMetricsSummaryReflectLiveLinuxSystem() {
        E2ETestHelpers.waitUntilLinuxReady(composeRule, timeoutMs = 120_000L)
        E2ETestHelpers.clickTag(composeRule, "tab_apps")

        E2ETestHelpers.replaceTextTag(
            composeRule = composeRule,
            tag = "command_input",
            text = "uname -a && cat /etc/alpine-release",
            closeKeyboard = true
        )
        E2ETestHelpers.clickTag(composeRule, "command_run_btn")

        E2ETestHelpers.scrollToTag(composeRule, "command_result")
        val cmdResult = E2ETestHelpers.waitUntilNodeTextContains(
            composeRule = composeRule,
            tag = "command_result",
            substring = "3.20",
            timeoutMs = 25_000L
        )
        assertTrue(
            "Expected command_result to contain 'Linux', got '$cmdResult'",
            cmdResult.contains("Linux", ignoreCase = true)
        )

        E2ETestHelpers.clickTag(composeRule, "metrics_refresh_btn")
        E2ETestHelpers.scrollToTag(composeRule, "metrics_summary")

        var metricsText = ""
        E2ETestHelpers.waitUntilCondition(
            timeoutMs = 20_000L,
            pollIntervalMs = 250L,
            failureMessage = { "metrics_summary stayed blank or lacked telemetry: '$metricsText'" }
        ) {
            metricsText = E2ETestHelpers.readNodeText(composeRule, "metrics_summary")
            metricsText.isNotBlank() &&
                Regex("\\d+").containsMatchIn(metricsText) &&
                Regex("(?i)(mem|kb|mb|proc|linux|pss|rss|window)").containsMatchIn(metricsText)
        }
        assertTrue("Expected non-blank metrics_summary, got '$metricsText'", metricsText.isNotBlank())
    }

    @Test
    fun allSevenGuiLauncherButtonsSpawnWindowsAndCleanUp() {
        E2ETestHelpers.waitUntilLinuxReady(composeRule, timeoutMs = 120_000L)
        E2ETestHelpers.cleanupTestGuiApps()
        E2ETestHelpers.clickTag(composeRule, "tab_apps")

        val launchers = listOf(
            "launcher_btn_xterm" to "xterm",
            "launcher_btn_mousepad" to "mousepad",
            "launcher_btn_galculator" to "galculator",
            "launcher_btn_pcmanfm" to "pcmanfm",
            "launcher_btn_xcalc" to "xcalc",
            "launcher_btn_xclock" to "xclock",
            "launcher_btn_feh" to "feh"
        )

        launchers.forEachIndexed { index, (buttonTag, appToken) ->
            val beforeCount = countMatchingWindows(appToken)

            E2ETestHelpers.clickTag(composeRule, buttonTag)

            var lastXwininfo = ""
            var lastStatusWindows = emptyList<String>()
            E2ETestHelpers.waitUntilCondition(
                timeoutMs = 20_000L,
                pollIntervalMs = 350L,
                failureMessage = {
                    "Expected GUI app '$appToken' (via '$buttonTag') to appear in X11/activeXWindows. " +
                        "beforeCount=$beforeCount, activeXWindows=$lastStatusWindows, xwininfo='${lastXwininfo.take(600)}'"
                }
            ) {
                val xwinRes = E2ETestHelpers.execLinux(
                    "xwininfo -display :1 -root -tree 2>/dev/null || true",
                    timeoutMs = 10_000L
                )
                lastXwininfo = xwinRes.optString("stdout", "")

                val statusJson = E2ETestHelpers.getStatus(timeoutMs = 10_000L)
                lastStatusWindows = E2ETestHelpers.jsonArrayToList(
                    statusJson.optJSONArray("activeXWindows")
                )

                val xwinMatches = countMatchesInLines(lastXwininfo.lines(), appToken)
                val statusMatches = countMatchesInLines(lastStatusWindows, appToken)
                xwinMatches > beforeCount || statusMatches > beforeCount
            }

            // Clean up periodically so child process counts remain well below Android's 32-process limit
            if ((index + 1) % 2 == 0 || index == launchers.lastIndex) {
                E2ETestHelpers.cleanupTestGuiApps()
                val statusAfterCleanup = E2ETestHelpers.getStatus()
                assertTrue(
                    "Expected runtime to stay Ready after GUI cleanup, got $statusAfterCleanup",
                    statusAfterCleanup.optString("state").equals("Ready", ignoreCase = true)
                )
            }
        }
    }

    private fun countMatchingWindows(appToken: String): Int {
        val xwinOut = runCatching {
            E2ETestHelpers.execLinux(
                "xwininfo -display :1 -root -tree 2>/dev/null || true",
                timeoutMs = 10_000L
            ).optString("stdout", "")
        }.getOrDefault("")
        val statusWindows = runCatching {
            E2ETestHelpers.jsonArrayToList(
                E2ETestHelpers.getStatus(timeoutMs = 10_000L).optJSONArray("activeXWindows")
            )
        }.getOrDefault(emptyList())

        return maxOf(
            countMatchesInLines(xwinOut.lines(), appToken),
            countMatchesInLines(statusWindows, appToken)
        )
    }

    private fun countMatchesInLines(lines: List<String>, token: String): Int {
        return lines.count { it.contains(token, ignoreCase = true) }
    }
}
