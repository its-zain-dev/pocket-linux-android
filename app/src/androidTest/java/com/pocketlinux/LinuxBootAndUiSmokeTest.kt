package com.pocketlinux

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketlinux.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LinuxBootAndUiSmokeTest {

    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = E2ETestHelpers.createRuleChain(composeRule)

    @Test
    fun bootToReadyAndVerifyStatusBadges() {
        E2ETestHelpers.waitUntilLinuxReady(composeRule, timeoutMs = 120_000L)

        composeRule.onNodeWithTag("linux_status_badge").assertIsDisplayed()
        composeRule.onNodeWithTag("startup_time_badge").assertIsDisplayed()
        composeRule.onNodeWithTag("memory_usage_badge").assertIsDisplayed()

        val statusText = E2ETestHelpers.readNodeText(composeRule, "linux_status_badge")
        val startupText = E2ETestHelpers.readNodeText(composeRule, "startup_time_badge")
        val memoryText = E2ETestHelpers.readNodeText(composeRule, "memory_usage_badge")

        assertTrue("Expected linux_status_badge to contain 'Ready', got '$statusText'", statusText.contains("Ready", ignoreCase = true))
        assertTrue("Expected startup_time_badge to be non-blank, got '$startupText'", startupText.isNotBlank())
        assertTrue("Expected memory_usage_badge to be non-blank, got '$memoryText'", memoryText.isNotBlank())

        val statusJson = E2ETestHelpers.getStatus()
        assertEquals("Ready", statusJson.optString("state"))
        assertEquals(5901, statusJson.optInt("vncPort", -1))
        assertTrue(
            "Expected non-negative startupMs in status JSON: $statusJson",
            statusJson.optLong("startupMs", -1L) >= 0L
        )
    }

    @Test
    fun switchTabsAndVerifyAll22ContractTestTags() {
        E2ETestHelpers.waitUntilLinuxReady(composeRule, timeoutMs = 120_000L)

        val verifiedTags = mutableSetOf<String>()

        // Persistent header badges and tab bar
        listOf(
            "linux_status_badge",
            "startup_time_badge",
            "memory_usage_badge",
            "tab_desktop",
            "tab_terminal",
            "tab_apps"
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).assertExists().assertIsDisplayed()
            verifiedTags.add(tag)
        }

        // Tab 1: Desktop
        E2ETestHelpers.clickTag(composeRule, "tab_desktop")
        composeRule.onNodeWithTag("desktop_surface").assertExists().assertIsDisplayed()
        val desktopBounds = composeRule.onNodeWithTag("desktop_surface")
            .fetchSemanticsNode()
            .boundsInWindow
        assertTrue(
            "Expected desktop_surface to have positive bounds, got $desktopBounds",
            desktopBounds.width > 0f && desktopBounds.height > 0f
        )
        verifiedTags.add("desktop_surface")

        // Tab 2: Terminal
        E2ETestHelpers.clickTag(composeRule, "tab_terminal")
        listOf("terminal_input", "terminal_send_btn", "terminal_output").forEach { tag ->
            E2ETestHelpers.scrollToTag(composeRule, tag)
            composeRule.onNodeWithTag(tag).assertExists().assertIsDisplayed()
            verifiedTags.add(tag)
        }

        // Tab 3: Apps & System
        E2ETestHelpers.clickTag(composeRule, "tab_apps")
        listOf(
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
        ).forEach { tag ->
            E2ETestHelpers.scrollToTag(composeRule, tag)
            composeRule.onNodeWithTag(tag).assertExists().assertIsDisplayed()
            verifiedTags.add(tag)
        }

        // Round-trip back to Desktop
        E2ETestHelpers.clickTag(composeRule, "tab_desktop")
        composeRule.onNodeWithTag("desktop_surface").assertIsDisplayed()

        assertEquals(
            "Expected all 22 contract testTags to be verified",
            E2ETestHelpers.ALL_TEST_TAGS.toSet(),
            verifiedTags
        )
    }
}
