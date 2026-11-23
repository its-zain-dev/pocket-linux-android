package com.pocketlinux.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {

    @Test
    fun handlesPlainLinesCarriageReturnAndBackspace() {
        val term = TerminalEmulator(cols = 40, rows = 10)
        term.append("hello world\r\n")
        term.append("abc\bZ\n")

        val snap = term.snapshotText()
        assertEquals("hello world\nabZ", snap)
    }

    @Test
    fun stripsAnsiSgrColorsAndHandlesClearScreenAndCursorHome() {
        val term = TerminalEmulator(cols = 80, rows = 24)
        term.append("\u001B[31mRED\u001B[0m\u001B[2J\u001B[HVT_CLEAN\n")

        val snap = term.snapshotText()
        assertTrue("Expected VT_CLEAN after ESC[2J ESC[H, got '$snap'", snap.contains("VT_CLEAN"))
        assertFalse("Expected RED to be cleared by ESC[2J, got '$snap'", snap.contains("RED"))
    }

    @Test
    fun wrapsLongSingleLineAndRetainsTrailingMarker() {
        val term = TerminalEmulator(cols = 80, rows = 24)
        val burst = "P".repeat(4096)
        term.append("$burst\nPTY_LONG_DONE\n")

        val snap = term.snapshotText()
        assertTrue("Expected PTY_LONG_DONE at end of 4KB burst, got tail='${snap.takeLast(120)}'", snap.contains("PTY_LONG_DONE"))
    }

    @Test
    fun ignoresOscWindowTitleAndDecPrivateModeSequences() {
        val term = TerminalEmulator(cols = 80, rows = 24)
        term.append("\u001B]0;root@localhost:/tmp\u0007\u001B[?2004h/tmp # echo ok\r\nok\r\n\u001B[?2004l")

        val snap = term.snapshotText()
        assertTrue("Expected command and output without OSC garbage, got '$snap'", snap.contains("/tmp # echo ok\nok"))
        assertFalse(snap.contains("2004h"))
    }
}
