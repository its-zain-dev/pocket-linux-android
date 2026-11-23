package com.pocketlinux

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketlinux.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElfAndLinuxFullVerificationTest {

    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = E2ETestHelpers.createRuleChain(composeRule)

    @After
    fun tearDown() {
        E2ETestHelpers.cleanupTestGuiApps()
    }

    @Test
    fun linuxHealthScriptReportsAlpine320AndElfOk() {
        E2ETestHelpers.waitUntilLinuxReady(composeRule, timeoutMs = 120_000L)

        val res = E2ETestHelpers.execLinux(
            "/usr/local/bin/linux-health.sh",
            timeoutMs = 30_000L
        )
        val exitCode = res.optInt("exitCode", -1)
        val stdout = res.optString("stdout", "")
        val stderr = res.optString("stderr", "")
        val combined = "$stdout\n$stderr"

        assertEquals(
            "Expected /usr/local/bin/linux-health.sh to exit 0, got exitCode=$exitCode, stdout='$stdout', stderr='$stderr'",
            0,
            exitCode
        )
        assertTrue(
            "Expected linux-health.sh output to report Alpine 3.20, got '$combined'",
            combined.contains("Alpine", ignoreCase = true) && combined.contains("3.20")
        )
        assertTrue(
            "Expected linux-health.sh output to report ELF OK, got '$combined'",
            combined.contains("ELF", ignoreCase = true) && combined.contains("OK", ignoreCase = true)
        )
    }

    @Test
    fun nativeElfBinariesPythonCtypesAndLibgcompatWorkInsideProot() {
        E2ETestHelpers.waitUntilLinuxReady(composeRule, timeoutMs = 120_000L)

        val fileRes = E2ETestHelpers.execLinux("file -L /bin/busybox", timeoutMs = 20_000L)
        assertEquals("Expected 'file -L /bin/busybox' to exit 0, got $fileRes", 0, fileRes.optInt("exitCode", -1))
        val fileOut = fileRes.optString("stdout", "")
        assertTrue(
            "Expected 'file -L /bin/busybox' to identify an ELF 64-bit binary, got '$fileOut'",
            fileOut.contains("ELF", ignoreCase = true) && fileOut.contains("64-bit", ignoreCase = true)
        )

        val unameRes = E2ETestHelpers.execLinux("/bin/busybox uname -a", timeoutMs = 20_000L)
        assertEquals("Expected '/bin/busybox uname -a' to exit 0, got $unameRes", 0, unameRes.optInt("exitCode", -1))
        assertTrue(unameRes.optString("stdout", "").contains("Linux", ignoreCase = true))

        val ctypesRes = E2ETestHelpers.execLinux(
            """python3 -c 'import ctypes; print("ELF_CTYPES_OK")'""",
            timeoutMs = 25_000L
        )
        assertEquals("Expected python3 ctypes ELF test to exit 0, got $ctypesRes", 0, ctypesRes.optInt("exitCode", -1))
        assertTrue(ctypesRes.optString("stdout", "").contains("ELF_CTYPES_OK"))

        val gcompatRes = E2ETestHelpers.execLinux(
            "if [ -e /lib/libgcompat.so.0 ]; then echo GCOMPAT=/lib/libgcompat.so.0; " +
                "elif [ -e /usr/lib/libgcompat.so.0 ]; then echo GCOMPAT=/usr/lib/libgcompat.so.0; " +
                "else echo GCOMPAT_MISSING >&2; exit 1; fi",
            timeoutMs = 20_000L
        )
        assertEquals("Expected libgcompat.so.0 to exist, got $gcompatRes", 0, gcompatRes.optInt("exitCode", -1))
        assertTrue(gcompatRes.optString("stdout", "").contains("GCOMPAT="))
    }

    @Test
    fun homeDirectoriesBookmarksAndWritableSdcardExistForFileManager() {
        E2ETestHelpers.waitUntilLinuxReady(composeRule, timeoutMs = 120_000L)

        val checkScript = """
            set -eu
            for dir in /root/Desktop /root/Documents /root/Downloads /root/Projects /mnt/sdcard; do
              test -d "${'$'}dir" || { echo "MISSING_DIR:${'$'}dir" >&2; exit 1; }
            done
            test -s /root/.config/gtk-3.0/bookmarks || { echo "MISSING_BOOKMARKS" >&2; exit 1; }
            probe="/mnt/sdcard/.pocketlinux_e2e_probe_${'$'}${'$'}"
            printf "ok\n" > "${'$'}probe"
            test -f "${'$'}probe"
            rm -f "${'$'}probe"
            echo "STORAGE_AND_BOOKMARKS_OK"
        """.trimIndent()

        val res = E2ETestHelpers.execLinux(checkScript, timeoutMs = 25_000L)
        assertEquals("Expected home dirs & storage check to exit 0, got $res", 0, res.optInt("exitCode", -1))
        assertTrue(res.optString("stdout", "").contains("STORAGE_AND_BOOKMARKS_OK"))
    }

    @Test
    fun launchingPcmanfmCreatesActiveX11WindowReportedByCollectMetrics() {
        E2ETestHelpers.waitUntilLinuxReady(composeRule, timeoutMs = 120_000L)
        E2ETestHelpers.cleanupTestGuiApps()

        val beforeWindows = E2ETestHelpers.jsonArrayToList(
            E2ETestHelpers.getStatus(timeoutMs = 10_000L).optJSONArray("activeXWindows")
        )
        val beforePcmanfmCount = beforeWindows.count { it.contains("pcmanfm", ignoreCase = true) }

        val launchRes = E2ETestHelpers.launchGui("pcmanfm", timeoutMs = 30_000L)
        assertTrue("Expected launchGui('pcmanfm') launched=true, got $launchRes", launchRes.optBoolean("launched", false))

        var lastActiveWindows = emptyList<String>()
        E2ETestHelpers.waitUntilCondition(
            timeoutMs = 20_000L,
            pollIntervalMs = 350L,
            failureMessage = {
                "Expected pcmanfm to appear in activeXWindows. before=$beforePcmanfmCount, current=$lastActiveWindows"
            }
        ) {
            val statusJson = E2ETestHelpers.getStatus(timeoutMs = 10_000L)
            lastActiveWindows = E2ETestHelpers.jsonArrayToList(statusJson.optJSONArray("activeXWindows"))
            lastActiveWindows.count { it.contains("pcmanfm", ignoreCase = true) } > beforePcmanfmCount
        }

        assertTrue(lastActiveWindows.any { it.contains("pcmanfm", ignoreCase = true) })
    }
}
