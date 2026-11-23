package com.pocketlinux.automation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagnosticsStoreTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun sanitizeOutFileNameBlocksPathTraversalAndEnforcesJsonExtension() {
        assertNull(DiagnosticsStore.sanitizeOutFileName(null))
        assertNull(DiagnosticsStore.sanitizeOutFileName("   "))

        val trav = DiagnosticsStore.sanitizeOutFileName("../../evil_trav.json")
        assertNotNull(trav)
        assertFalse(trav!!.contains(".."))
        assertFalse(trav.contains("/"))
        assertTrue(trav.endsWith(".json"))

        val filesDir = tempFolder.newFolder("files")
        val resolved = DiagnosticsStore.resolveConfinedFile(filesDir, "../../evil_trav.json")
        assertNotNull(resolved)
        assertEquals(File(filesDir, "diagnostics").canonicalFile, resolved!!.canonicalFile.parentFile)
        assertFalse(File(filesDir, "evil_trav.json").exists())
    }

    @Test
    fun writeActionResponseWritesBothOutFileAndLastActionAtomically() {
        val filesDir = tempFolder.newFolder("files_atomic")
        val payload = JSONObject()
            .put("exitCode", 0)
            .put("stdout", "SAFE\n")
            .put("stderr", "")
            .put("durationMs", 12L)

        DiagnosticsStore.writeActionResponse(
            filesDir = filesDir,
            action = AutomationReceiver.ACTION_EXEC_LINUX,
            reqId = "req_123",
            rawOutFile = "../../evil_trav.json",
            payload = payload,
            emitLogcat = false
        )

        assertFalse(File(filesDir, "evil_trav.json").exists())
        val diagDir = File(filesDir, "diagnostics")
        val lastAction = File(diagDir, "last_action.json")
        assertTrue(lastAction.isFile)
        val parsed = JSONObject(lastAction.readText())
        assertEquals("req_123", parsed.getString("reqId"))
        assertEquals(0, parsed.getInt("exitCode"))
        assertEquals("SAFE\n", parsed.getString("stdout"))
    }

    @Test
    fun extractExecutableTokenSkipsEnvAssignments() {
        assertEquals("xterm", AutomationReceiver.extractExecutableToken("xterm -geometry 80x24"))
        assertEquals("feh", AutomationReceiver.extractExecutableToken("DISPLAY=:1 feh /tmp/a.ppm"))
        assertEquals("", AutomationReceiver.extractExecutableToken("   "))
    }
}
