package com.pocketlinux.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class RuntimeModelsTest {

    @Test
    fun runtimeStateModelsAndDefaultsMatchContract() {
        assertEquals("Idle", RuntimeState.Idle.toString())
        assertEquals("Booting", RuntimeState.Booting.toString())

        val extracting = RuntimeState.Extracting("Extracting Linux system (250 files)...")
        assertEquals("Extracting Linux system (250 files)...", extracting.progressLabel)

        val ready = RuntimeState.Ready(startupDurationMs = 1450L, rootfsReadyMs = 320L)
        assertEquals("127.0.0.1", ready.vncHost)
        assertEquals(5901, ready.vncPort)
        assertEquals(1450L, ready.startupDurationMs)
        assertEquals(320L, ready.rootfsReadyMs)

        val error = RuntimeState.Error("Xvnc failed")
        assertEquals("Xvnc failed", error.message)
    }

    @Test
    fun ptyBridgeValidatesInputsWithoutLoadingNativeLibraryOnJvm() {
        var threwArgv = false
        try {
            PtyBridge.openSession(argv = emptyList())
        } catch (_: IllegalArgumentException) {
            threwArgv = true
        }
        assertTrue(threwArgv)

        var threwCols = false
        try {
            PtyBridge.openSession(argv = listOf("/bin/sh"), cols = 70000, rows = 24)
        } catch (_: IllegalArgumentException) {
            threwCols = true
        }
        assertTrue(threwCols)

        var threwRows = false
        try {
            PtyBridge.openSession(argv = listOf("/bin/sh"), cols = 80, rows = 0)
        } catch (_: IllegalArgumentException) {
            threwRows = true
        }
        assertTrue(threwRows)
    }

    @Test
    fun runtimeControllerContractWorksInCoroutines() = runTest {
        val stateFlow = MutableStateFlow<RuntimeState>(RuntimeState.Idle)
        val controller = object : LinuxRuntimeController {
            override val state: StateFlow<RuntimeState> = stateFlow

            override fun startRuntime() {
                stateFlow.value = RuntimeState.Ready(startupDurationMs = 500L, rootfsReadyMs = 100L)
            }

            override fun stopRuntime() {
                stateFlow.value = RuntimeState.Idle
            }

            override suspend fun execCommand(command: String, timeoutMs: Long): CommandResult {
                return CommandResult(exitCode = 0, stdout = "ok:$command", stderr = "", durationMs = 5L)
            }

            override suspend fun launchGuiApp(command: String): CommandResult {
                return CommandResult(exitCode = 0, stdout = "", stderr = "", durationMs = 10L)
            }

            override suspend fun openInteractiveShell(cols: Int, rows: Int): PtySession {
                return object : PtySession {
                    override val pid: Int = 4242
                    override val inputStream: InputStream = ByteArrayInputStream(ByteArray(0))
                    override val outputStream: OutputStream = ByteArrayOutputStream()
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {}
                    override fun write(text: String) {}
                    override fun resize(cols: Int, rows: Int) {}
                    override fun isAlive(): Boolean = false
                    override fun waitFor(): Int = 0
                    override fun close() {}
                }
            }

            override suspend fun collectMetrics(): SystemMetrics {
                return SystemMetrics(
                    startupDurationMs = 500L,
                    androidPssKb = 12000,
                    androidRssKb = 18000,
                    linuxMemTotalKb = 1024000L,
                    linuxMemAvailableKb = 800000L,
                    activeXWindows = listOf("0x200001 \"jwm\""),
                    processCount = 4
                )
            }
        }

        controller.startRuntime()
        assertTrue(controller.state.value is RuntimeState.Ready)
        val exec = controller.execCommand("uname -a")
        assertEquals(0, exec.exitCode)
        assertEquals("ok:uname -a", exec.stdout)
        val metrics = controller.collectMetrics()
        assertEquals(4, metrics.processCount)
        controller.stopRuntime()
        assertEquals(RuntimeState.Idle, controller.state.value)
    }
}
