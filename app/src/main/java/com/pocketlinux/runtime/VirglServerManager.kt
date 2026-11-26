package com.pocketlinux.runtime

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages and supervises the native VirGL hardware rendering server (`libvirgl_test_server.so`)
 * bundled in `nativeLibraryDir`.
 *
 * Uses a dedicated directory (`<cacheDir>/virgl_ipc/.virgl_test`) bind-mounted into PRoot at
 * `/tmp/.virgl_ipc` so socket recreation across restarts is immediately visible to all Linux
 * processes, and automatically respawns the server if it ever exits unexpectedly.
 */
class VirglServerManager(
    private val nativeLibraryDir: File,
    private val ipcDir: File,
    private val binaryName: String = DEFAULT_BINARY_NAME,
    private val socketName: String = DEFAULT_SOCKET_NAME
) {
    constructor(context: Context) : this(
        nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
        ipcDir = File(context.cacheDir, "virgl_ipc")
    )

    constructor(commandBuilder: ProotCommandBuilder) : this(
        nativeLibraryDir = commandBuilder.prootExecutable.parentFile ?: File(""),
        ipcDir = commandBuilder.virglIpcDir
    )

    val serverExecutable: File = File(nativeLibraryDir, binaryName)
    val socketFile: File = File(ipcDir, socketName)
    val logFile: File = File(ipcDir, "virgl_server.log")

    private val lock = Any()
    private val keepAlive = AtomicBoolean(false)
    private val logTail = BoundedLogTail(MAX_LOG_TAIL_CHARS)

    @Volatile
    private var serverProcess: Process? = null

    private var monitorThread: Thread? = null

    fun isAvailable(): Boolean = serverExecutable.isFile && serverExecutable.canExecute()

    fun isRunning(): Boolean = serverProcess?.isAlive == true

    fun lastLogTail(): String = logTail.snapshot()

    fun start(waitReadyTimeoutMs: Long = DEFAULT_SOCKET_READY_TIMEOUT_MS): Boolean {
        if (!isAvailable()) return false
        keepAlive.set(true)

        val startedProc: Process = synchronized(lock) {
            val existing = serverProcess
            if (existing?.isAlive == true) {
                existing
            } else {
                spawnLocked() ?: return false
            }
        }

        if (waitReadyTimeoutMs > 0L) {
            val deadlineNs = System.nanoTime() + waitReadyTimeoutMs * 1_000_000L
            while (System.nanoTime() < deadlineNs) {
                if (!startedProc.isAlive && serverProcess?.isAlive != true) {
                    return false
                }
                if (socketFile.exists()) return true
                try {
                    Thread.sleep(SOCKET_POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        return isRunning()
    }

    private fun spawnLocked(): Process? {
        runCatching {
            if (!ipcDir.isDirectory) ipcDir.mkdirs()
            ipcDir.setReadable(true, false)
            ipcDir.setWritable(true, false)
            ipcDir.setExecutable(true, false)
            if (socketFile.exists()) socketFile.delete()
            logFile.writeText("[VirGL] Starting ${serverExecutable.name} -> ${socketFile.absolutePath}\n")
            logFile.setReadable(true, false)
        }

        val pb = ProcessBuilder(
            serverExecutable.absolutePath,
            "--use-egl-surfaceless",
            "--use-gles",
            "--socket-path",
            socketFile.absolutePath
        ).apply {
            directory(ipcDir)
            redirectErrorStream(true)
            val env = environment()
            env["TMPDIR"] = ipcDir.absolutePath
            env["VTEST_SOCKET_NAME"] = socketFile.absolutePath
            val existingLd = env["LD_LIBRARY_PATH"].orEmpty()
            env["LD_LIBRARY_PATH"] = if (existingLd.isBlank()) {
                nativeLibraryDir.absolutePath
            } else {
                "${nativeLibraryDir.absolutePath}:$existingLd"
            }
        }

        val proc = runCatching { pb.start() }.getOrElse {
            appendLog("[VirGL] Failed to spawn server: ${it.message}\n")
            return null
        }
        serverProcess = proc

        monitorThread = Thread(
            { superviseProcess(proc) },
            "pocketlinux-virgl-supervisor"
        ).apply {
            isDaemon = true
            start()
        }
        return proc
    }

    fun stop() {
        keepAlive.set(false)
        val proc: Process?
        val thread: Thread?
        synchronized(lock) {
            proc = serverProcess
            serverProcess = null
            thread = monitorThread
            monitorThread = null
        }

        if (proc != null) {
            terminateProcessGracefully(proc)
            closeProcessStreams(proc)
        }
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.interrupt() }
        }
        runCatching {
            if (socketFile.exists()) socketFile.delete()
        }
    }

    private fun appendLog(chunk: String) {
        if (chunk.isEmpty()) return
        logTail.append(chunk)
        runCatching {
            if (logFile.length() > 65_536L) {
                logFile.writeText(logTail.snapshot())
            } else {
                logFile.appendText(chunk)
            }
            logFile.setReadable(true, false)
        }
    }

    private fun superviseProcess(proc: Process) {
        var rapidFailures = 0
        var currentProc: Process? = proc
        while (currentProc != null) {
            val startMs = System.currentTimeMillis()
            try {
                drainStreamUtf8(currentProc.inputStream)
                val code = currentProc.waitFor()
                appendLog("\n[VirGL supervisor] Process exited with code=$code\n")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (_: Exception) {
            } finally {
                closeProcessStreams(currentProc)
            }

            if (!keepAlive.get()) break

            val ranMs = System.currentTimeMillis() - startMs
            rapidFailures = if (ranMs < 1_000L) rapidFailures + 1 else 0
            if (rapidFailures >= MAX_RAPID_FAILURES) {
                appendLog("\n[VirGL supervisor] Disabled auto-restart after $rapidFailures rapid exits.\n")
                break
            }

            try {
                Thread.sleep(150L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }

            currentProc = synchronized(lock) {
                if (!keepAlive.get()) {
                    serverProcess = null
                    monitorThread = null
                    null
                } else {
                    spawnLocked()
                }
            }
        }

        synchronized(lock) {
            if (serverProcess === currentProc || currentProc == null) {
                if (!keepAlive.get()) {
                    serverProcess = null
                    monitorThread = null
                }
            }
        }
    }

    private fun drainStreamUtf8(stream: InputStream) {
        val buffer = CharArray(1024)
        try {
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    if (read > 0) appendLog(String(buffer, 0, read))
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun terminateProcessGracefully(process: Process) {
        runCatching {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(GRACEFUL_TERM_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(FORCE_KILL_WAIT_MS, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    private fun closeProcessStreams(process: Process) {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    private class BoundedLogTail(private val maxChars: Int) {
        private val sb = StringBuilder()

        @Synchronized
        fun append(chunk: String) {
            if (chunk.isEmpty()) return
            sb.append(chunk)
            if (sb.length > maxChars) sb.delete(0, sb.length - maxChars)
        }

        @Synchronized
        fun clear() = sb.setLength(0)

        @Synchronized
        fun snapshot(): String = sb.toString()
    }

    companion object {
        const val DEFAULT_BINARY_NAME = "libvirgl_test_server.so"
        const val DEFAULT_SOCKET_NAME = ".virgl_test"

        private const val DEFAULT_SOCKET_READY_TIMEOUT_MS = 2_000L
        private const val SOCKET_POLL_INTERVAL_MS = 40L
        private const val GRACEFUL_TERM_WAIT_MS = 1_000L
        private const val FORCE_KILL_WAIT_MS = 500L
        private const val MAX_LOG_TAIL_CHARS = 4_096
        private const val MAX_RAPID_FAILURES = 5
    }
}
