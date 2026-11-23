package com.pocketlinux

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketlinux.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesktopSurfacePixelVerificationTest {

    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = E2ETestHelpers.createRuleChain(composeRule)

    @After
    fun tearDown() {
        E2ETestHelpers.cleanupTestGuiApps()
    }

    @Test
    fun desktopSurfaceRendersNonBlankJwmUpdatesOnGuiLaunchAndHandlesTouch() {
        E2ETestHelpers.waitUntilLinuxReady(composeRule, timeoutMs = 120_000L)
        E2ETestHelpers.cleanupTestGuiApps()
        E2ETestHelpers.clickTag(composeRule, "tab_desktop")

        composeRule.onNodeWithTag("desktop_surface").assertIsDisplayed()
        val surfaceBounds = composeRule.onNodeWithTag("desktop_surface")
            .fetchSemanticsNode()
            .boundsInWindow
        assertTrue(
            "Expected non-zero desktop_surface bounds, got $surfaceBounds",
            surfaceBounds.width > 0f && surfaceBounds.height > 0f
        )

        // 1. Wait for live RFB frame verification before capturing baseline pixels
        var baselineRfbSha = ""
        E2ETestHelpers.waitUntilCondition(
            timeoutMs = 25_000L,
            pollIntervalMs = 300L,
            failureMessage = { "RFB frame did not report connected non-uniform render" }
        ) {
            val frame = E2ETestHelpers.verifyDesktopFrame(timeoutMs = 10_000L)
            val connected = frame.optBoolean("connected", false)
            val width = frame.optInt("width", 0)
            val height = frame.optInt("height", 0)
            val nonUniform = frame.optInt("nonUniformPixels", 0)
            val sha = frame.optString("sha256", "")
            if (connected && width > 0 && height > 0 && nonUniform > 0 && sha.isNotBlank()) {
                baselineRfbSha = sha
                true
            } else {
                false
            }
        }

        // Allow SurfaceView render thread to composite the latest RFB frame
        SystemClock.sleep(400L)

        var baselineBitmap: Bitmap? = null
        var postLaunchBitmap: Bitmap? = null
        try {
            baselineBitmap = E2ETestHelpers.captureNodeScreenshot(composeRule, "desktop_surface")
            val baselineStats = E2ETestHelpers.analyzeBitmap(baselineBitmap)
            assertTrue(
                "Expected baseline screenshot width/height > 0, got ${baselineStats.width}x${baselineStats.height}",
                baselineStats.width > 0 && baselineStats.height > 0
            )
            assertTrue(
                "Expected non-uniform baseline desktop pixels (> 0), got ${baselineStats.nonUniformPixels}",
                baselineStats.nonUniformPixels > 0
            )
            assertTrue(
                "Expected real JWM desktop with >= 4 unique sampled colors, got ${baselineStats.uniqueColorsSampled}",
                baselineStats.uniqueColorsSampled >= 4
            )

            // 2. Launch a visible GUI app (xclock) and verify X11 window + RFB SHA-256 delta
            val initialXclockCount = countXwininfoMatches("xclock")
            val initialStatusXclockCount = countStatusWindowMatches("xclock")
            val launchResult = E2ETestHelpers.launchGui("xclock", timeoutMs = 30_000L)
            assertTrue(
                "Expected launchGui('xclock') launched=true, got $launchResult",
                launchResult.optBoolean("launched", false)
            )

            E2ETestHelpers.waitUntilCondition(
                timeoutMs = 20_000L,
                pollIntervalMs = 350L,
                failureMessage = { "xclock window did not appear in X11 or activeXWindows" }
            ) {
                val xwinMatches = countXwininfoMatches("xclock")
                val statusMatches = countStatusWindowMatches("xclock")
                xwinMatches > initialXclockCount || statusMatches > initialStatusXclockCount
            }

            var postRfbSha = baselineRfbSha
            E2ETestHelpers.waitUntilCondition(
                timeoutMs = 20_000L,
                pollIntervalMs = 300L,
                failureMessage = { "RFB frame sha256 did not change from baseline '$baselineRfbSha'" }
            ) {
                val frame = E2ETestHelpers.verifyDesktopFrame(timeoutMs = 10_000L)
                postRfbSha = frame.optString("sha256", "")
                postRfbSha.isNotBlank() && postRfbSha != baselineRfbSha
            }
            assertNotEquals("RFB sha256 should change after launching xclock", baselineRfbSha, postRfbSha)

            // 3. Capture post-launch screenshot of desktop_surface and verify measurable pixel delta
            var pixelDiffRatio = 0.0
            var postStats: FrameStats? = null
            E2ETestHelpers.waitUntilCondition(
                timeoutMs = 15_000L,
                pollIntervalMs = 400L,
                failureMessage = {
                    "desktop_surface SurfaceView screenshot did not change from baseline (${baselineStats.sha256})"
                }
            ) {
                postLaunchBitmap?.recycle()
                postLaunchBitmap = E2ETestHelpers.captureNodeScreenshot(composeRule, "desktop_surface")
                val candidateStats = E2ETestHelpers.analyzeBitmap(postLaunchBitmap!!)
                val ratio = E2ETestHelpers.computePixelDiffRatio(baselineBitmap, postLaunchBitmap!!)
                if (candidateStats.sha256 != baselineStats.sha256 && ratio > 0.0) {
                    postStats = candidateStats
                    pixelDiffRatio = ratio
                    true
                } else {
                    false
                }
            }

            assertTrue("Expected measurable pixel diff ratio > 0.0, got $pixelDiffRatio", pixelDiffRatio > 0.0)
            assertNotEquals(
                "Expected post-launch desktop_surface SHA-256 to differ from baseline",
                baselineStats.sha256,
                postStats!!.sha256
            )

            // 4. Perform touch input on desktop_surface and verify responsiveness
            composeRule.onNodeWithTag("desktop_surface").performTouchInput {
                click(center)
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("desktop_surface").assertIsDisplayed()
            val statusAfterTouch = E2ETestHelpers.getStatus()
            assertTrue(
                "Expected Linux runtime to remain Ready after desktop touch, got $statusAfterTouch",
                statusAfterTouch.optString("state").equals("Ready", ignoreCase = true)
            )
        } finally {
            baselineBitmap?.recycle()
            postLaunchBitmap?.recycle()
        }
    }

    private fun countXwininfoMatches(token: String): Int {
        val out = runCatching {
            E2ETestHelpers.execLinux(
                "xwininfo -display :1 -root -tree 2>/dev/null || true",
                timeoutMs = 10_000L
            ).optString("stdout", "")
        }.getOrDefault("")
        return out.lines().count { it.contains(token, ignoreCase = true) }
    }

    private fun countStatusWindowMatches(token: String): Int {
        val windows = runCatching {
            E2ETestHelpers.jsonArrayToList(
                E2ETestHelpers.getStatus(timeoutMs = 10_000L).optJSONArray("activeXWindows")
            )
        }.getOrDefault(emptyList())
        return windows.count { it.contains(token, ignoreCase = true) }
    }
}
