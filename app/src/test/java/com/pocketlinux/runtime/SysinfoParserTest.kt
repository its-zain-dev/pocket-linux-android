package com.pocketlinux.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SysinfoParserTest {

    @Test
    fun parsesExactSysinfoJsonSchema() {
        val json = """
            {"sysname": "Linux", "nodename": "localhost", "release": "6.1.75-android14", "machine": "aarch64", "alpine_release": "3.20.10", "uptime_sec": 1234.5, "mem_total_kb": 4096000, "mem_available_kb": 2500000, "mem_free_kb": 1200000, "process_count": 7, "x_windows": ["0x200001 \"JWM\"", "0x400003 \"xterm\""]}
        """.trimIndent()

        val snap = SysinfoParser.parse(json)
        assertEquals("Linux", snap.sysname)
        assertEquals("localhost", snap.nodename)
        assertEquals("6.1.75-android14", snap.release)
        assertEquals("aarch64", snap.machine)
        assertEquals("3.20.10", snap.alpineRelease)
        assertEquals(1234.5, snap.uptimeSec, 0.01)
        assertEquals(4096000L, snap.memTotalKb)
        assertEquals(2500000L, snap.memAvailableKb)
        assertEquals(1200000L, snap.memFreeKb)
        assertEquals(7, snap.processCount)
        assertEquals(listOf("0x200001 \"JWM\"", "0x400003 \"xterm\""), snap.xWindows)
    }

    @Test
    fun handlesMultilineWarningPrefixAndMalformedInputsGracefully() {
        val noisy = """
            proot warning: something benign
            {"sysname": "Linux", "mem_total_kb": 1000, "mem_available_kb": 500, "process_count": 2, "x_windows": []}
        """.trimIndent()
        val snap = SysinfoParser.parse(noisy)
        assertEquals("Linux", snap.sysname)
        assertEquals(1000L, snap.memTotalKb)
        assertEquals(500L, snap.memAvailableKb)
        assertEquals(2, snap.processCount)
        assertTrue(snap.xWindows.isEmpty())

        val emptySnap = SysinfoParser.parse("")
        assertEquals(0L, emptySnap.memTotalKb)
        assertEquals(0, emptySnap.processCount)

        val badSnap = SysinfoParser.parse("{not valid json")
        assertEquals(0L, badSnap.memTotalKb)
    }

    @Test
    fun parsesAndroidProcVmRssAndStatm() {
        val status = """
            Name:   com.pocketlinux
            State:  S (sleeping)
            VmSize:  1234560 kB
            VmRSS:     45678 kB
            Threads:        18
        """.trimIndent()
        assertEquals(45678, SysinfoParser.parseProcVmRssKb(status))
        assertEquals(0, SysinfoParser.parseProcVmRssKb("Name: missing"))

        // 10000 resident pages * 4096 bytes / 1024 = 40000 kB
        assertEquals(40000, SysinfoParser.parseProcStatmRssKb("30000 10000 4000 50 0 8000 0", 4096L))
        assertEquals(0, SysinfoParser.parseProcStatmRssKb("invalid", 4096L))
    }
}
