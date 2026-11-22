package com.pocketlinux.runtime

import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

sealed class RuntimeState {
    object Idle : RuntimeState() {
        override fun toString(): String = "Idle"
    }

    data class Extracting(val progressLabel: String) : RuntimeState()

    object Booting : RuntimeState() {
        override fun toString(): String = "Booting"
    }

    data class Ready(
        val vncHost: String = "127.0.0.1",
        val vncPort: Int = 5901,
        val startupDurationMs: Long,
        val rootfsReadyMs: Long
    ) : RuntimeState()

    data class Error(val message: String) : RuntimeState()
}

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long
)

data class SystemMetrics(
    val startupDurationMs: Long,
    val androidPssKb: Int,
    val androidRssKb: Int,
    val linuxMemTotalKb: Long,
    val linuxMemAvailableKb: Long,
    val activeXWindows: List<String>,
    val processCount: Int
)

interface PtySession : Closeable {
    val pid: Int
    val inputStream: InputStream
    val outputStream: OutputStream
    fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size)
    fun write(text: String)
    fun resize(cols: Int, rows: Int)
    fun isAlive(): Boolean
    fun waitFor(): Int
    override fun close()
}

interface LinuxRuntimeController {
    val state: StateFlow<RuntimeState>
    fun startRuntime()
    fun stopRuntime()
    suspend fun execCommand(command: String, timeoutMs: Long = 30_000L): CommandResult
    suspend fun launchGuiApp(command: String): CommandResult
    suspend fun openInteractiveShell(cols: Int = 80, rows: Int = 24): PtySession
    suspend fun collectMetrics(): SystemMetrics
}
