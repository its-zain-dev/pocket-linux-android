package com.pocketlinux.runtime

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Manages the optional native VirGL rendering server (`libvirgl_test_server.so`)
 * bundled in `nativeLibraryDir`.
 *
 * When present and executable, the server listens on `<prootTmpDir>/.virgl_test`
 * using EGL surfaceless + GLES so guest Mesa `virpipe` apps can offload OpenGL
 * calls to the Android GPU. When the binary is not bundled, all operations
 * gracefully no-op so the app and JVM unit tests never fail.
 */
class VirglServerManager(
    private val nativeLibraryDir: File,
    private val prootTmpDir: File,
    private val binaryName: String = DEFAULT_BINARY_NAME,
    private val socketName: String = DEFAULT_SOCKET_NAME
) {
    constructor(context: Context) : this(
        nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
        prootTmpDir = File(context.cacheDir, "proot_tmp")
    )

    constructor(commandBuilder: ProotCommandBuilder) : this(
        nativeLibraryDir = commandBuilder.prootExecutable.parentFile ?: File(""),
        prootTmpDir = commandBuilder.prootTmpDir
    )

    val serverExecutable: File = File(nativeLibraryDir, binaryName)
    val socketFile: File = File(prootTmpDir, socketName)

    private val lock = Any()
    private val logTail = BoundedLogTail(MAX_LOG_TAIL_CHARS)

    @Volatile
    private var serverProcess: Process? = null

    private var monitorThread: Thread? = null

    fun isAvailable(): Boolean = serverExecutable.isFile && serverExecutable.canExecute()

    fun isRunning(): Boolean = serverProcess?.isAlive == true

    fun lastLogTail(): String = logTail.snapshot()

    fun start(waitReadyTimeoutMs: Long = DEFAULT_SOCKET_READY_TIMEOUT_MS): Boolean {
        val startedProc: Process
        synchronized(lock) {
            if (serverProcess?.isAlive == true) return true
            if (!isAvailable()) return false

            runCatching {
                if (!prootTmpDir.isDirectory) prootTmpDir.mkdirs()
                if (socketFile.exists()) socketFile.delete()
            }

            logTail.clear()

            val pb = ProcessBuilder(
                serverExecutable.absolutePath,
                "--use-egl-surfaceless",
                "--use-gles",
                "--socket-path",
                socketFile.absolutePath
            ).apply {
                directory(prootTmpDir)
                redirectErrorStream(true)
                val env = environment()
                env["TMPDIR"] = prootTmpDir.absolutePath
                env["VTEST_SOCKET_NAME"] = socketFile.absolutePath
                val existingLd = env["LD_LIBRARY_PATH"].orEmpty()
                env["LD_LIBRARY_PATH"] = if (existingLd.isBlank()) {
                    nativeLibraryDir.absolutePath
                } else {
                    "${nativeLibraryDir.absolutePath}:$existingLd"
                }
            }

            val proc = runCatching { pb.start() }.getOrElse { return false }
            serverProcess = proc
            startedProc = proc

            monitorThread = Thread(
                { monitorProcess(proc) },
                "pocketlinux-virgl-monitor"
            ).apply {
                isDaemon = true
                start()
            }
        }

        if (waitReadyTimeoutMs > 0L) {
            val deadlineNs = System.nanoTime() + waitReadyTimeoutMs * 1_000_000L
            while (System.nanoTime() < deadlineNs) {
                if (!startedProc.isAlive) {
                    stop()
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
        return startedProc.isAlive
    }

    fun stop() {
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

    private fun monitorProcess(proc: Process) {
        try {
            drainStreamUtf8(proc.inputStream)
            proc.waitFor()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
        } finally {
            closeProcessStreams(proc)
            synchronized(lock) {
                if (serverProcess === proc) {
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
                    if (read > 0) logTail.append(String(buffer, 0, read))
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

        private const val DEFAULT_SOCKET_READY_TIMEOUT_MS = 1_500L
        private const val SOCKET_POLL_INTERVAL_MS = 50L
        private const val GRACEFUL_TERM_WAIT_MS = 1_000L
        private const val FORCE_KILL_WAIT_MS = 500L
        private const val MAX_LOG_TAIL_CHARS = 4_096
    }
}
