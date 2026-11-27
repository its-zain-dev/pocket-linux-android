package com.pocketlinux.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

internal interface RootfsProvider {
    fun isReady(): Boolean
    fun ensureExtracted(onProgress: (String) -> Unit = {}): Long
}

internal class RootfsManagerAdapter(
    private val manager: RootfsManager
) : RootfsProvider {
    override fun isReady(): Boolean = manager.isReady()
    override fun ensureExtracted(onProgress: (String) -> Unit): Long =
        manager.ensureExtracted(onProgress)
}

internal interface RuntimeCommandFactory {
    val rootfsDir: File
    fun prepareEnvironment() {}
    fun buildShellExec(command: String, display: String): ProotCommand
    fun buildInteractiveShell(display: String): ProotCommand
    fun buildStartDesktop(geometry: String, rfbPort: Int, display: String): ProotCommand
    fun buildLaunchGuiApp(command: String, display: String): ProotCommand
    fun buildSysInfo(textMode: Boolean, display: String): ProotCommand
}

internal class ProotCommandFactory(
    private val builder: ProotCommandBuilder
) : RuntimeCommandFactory {
    override val rootfsDir: File
        get() = builder.rootfsDir

    override fun prepareEnvironment() = builder.ensureRuntimeDirsAndFakeProc()

    override fun buildShellExec(command: String, display: String): ProotCommand =
        builder.buildShellExec(command = command, display = display)

    override fun buildInteractiveShell(display: String): ProotCommand =
        builder.buildInteractiveShell(display = display)

    override fun buildStartDesktop(geometry: String, rfbPort: Int, display: String): ProotCommand =
        builder.buildStartDesktop(geometry = geometry, rfbPort = rfbPort, display = display)

    override fun buildLaunchGuiApp(command: String, display: String): ProotCommand =
        builder.buildLaunchGuiApp(command = command, display = display)

    override fun buildSysInfo(textMode: Boolean, display: String): ProotCommand =
        builder.buildSysInfo(textMode = textMode, display = display)
}

internal class LinuxRuntimeEngine(
    private val rootfsProvider: RootfsProvider,
    private val commandFactory: RuntimeCommandFactory,
    private val openPtySession: (ProotCommand, Int, Int) -> PtySession = { cmd, cols, rows ->
        PtyBridge.openSession(
            argv = cmd.argv,
            env = cmd.env,
            workingDir = null,
            cols = cols,
            rows = rows
        )
    },
    private val readAndroidMemoryKb: () -> Pair<Int, Int> = { 0 to 0 },
    private val onNotificationUpdate: (String) -> Unit = {},
    private val onKillOrphanedGuestProcesses: (() -> Unit)? = null,
    private val bootTimeoutMs: Long = DEFAULT_BOOT_TIMEOUT_MS,
    private val bootPollIntervalMs: Long = DEFAULT_BOOT_POLL_INTERVAL_MS,
    private val guiStartupCheckMs: Long = DEFAULT_GUI_STARTUP_CHECK_MS,
    private val vncHost: String = LOCALHOST,
    private val vncPort: Int = DEFAULT_VNC_PORT,
    private val display: String = DEFAULT_DISPLAY,
    private val geometry: String = DEFAULT_GEOMETRY,
    private val xwinReadyCheckCommand: String = "xwininfo -display $display -root -tree",
    private val sysinfoTimeoutMs: Long = SYSINFO_TIMEOUT_MS
) : LinuxRuntimeController {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private val lifecycleMutex = Mutex()

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Idle)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private var lifecycleGeneration = 0L
    private var startJob: Job? = null
    private var stopJob: Job? = null
    private var destroyed = false

    @Volatile
    private var desktopProcess: Process? = null

    @Volatile
    private var lastStartupDurationMs: Long = 0L

    private val managedProcesses: MutableSet<Process> =
        Collections.synchronizedSet(mutableSetOf())
    private val activePtySessions: MutableSet<PtySession> =
        Collections.synchronizedSet(mutableSetOf())

    private fun isCurrentGeneration(gen: Long): Boolean =
        synchronized(lifecycleLock) { !destroyed && lifecycleGeneration == gen }

    internal fun activePtySessionCount(): Int =
        synchronized(activePtySessions) { activePtySessions.size }

    internal fun hasDesktopProcess(): Boolean = desktopProcess != null

    override fun startRuntime() {
        val gen: Long
        val prevStop: Job?
        synchronized(lifecycleLock) {
            if (destroyed) return
            val current = _state.value
            val activeStart = startJob?.isActive == true
            val activeStop = stopJob?.isActive == true
            if (!activeStop && (
                    activeStart ||
                        current is RuntimeState.Extracting ||
                        current is RuntimeState.Booting ||
                        current is RuntimeState.Ready
                    )
            ) {
                return
            }
            gen = ++lifecycleGeneration
            prevStop = stopJob
            _state.value = RuntimeState.Extracting("Preparing Linux rootfs...")
            onNotificationUpdate("Preparing Linux rootfs...")
            startJob = serviceScope.launch {
                prevStop?.join()
                lifecycleMutex.withLock {
                    if (!isCurrentGeneration(gen) || !coroutineContext.isActive) {
                        return@withLock
                    }
                    bootRuntimeInternal(gen)
                }
            }
        }
    }

    private suspend fun bootRuntimeInternal(gen: Long) {
        val overallStartNs = System.nanoTime()
        val jobContext = coroutineContext
        var localDesktopProc: Process? = null
        var outDrainJob: Job? = null
        var errDrainJob: Job? = null
        try {
            jobContext.ensureActive()
            commandFactory.prepareEnvironment()
            val rootfsReadyMs = rootfsProvider.ensureExtracted { progressLabel ->
                jobContext.ensureActive()
                if (isCurrentGeneration(gen)) {
                    _state.value = RuntimeState.Extracting(progressLabel)
                    onNotificationUpdate(progressLabel)
                }
            }
            jobContext.ensureActive()
            if (!isCurrentGeneration(gen)) return

            val bootElapsedMs = ((System.nanoTime() - overallStartNs) / 1_000_000L).coerceAtLeast(0L)
            val effectiveRootfsMs = if (rootfsReadyMs > 0L) rootfsReadyMs else bootElapsedMs

            deleteDesktopReadyMarkers()
            val orphanReaper = onKillOrphanedGuestProcesses
            if (orphanReaper != null && isTcpPortOpen(vncHost, vncPort, 80)) {
                runCatching { orphanReaper() }
                waitUntilVncPortClosed(1_500L)
                deleteDesktopReadyMarkers()
            }

            _state.value = RuntimeState.Booting
            onNotificationUpdate("Booting X11 desktop...")

            jobContext.ensureActive()
            if (!isCurrentGeneration(gen)) return

            val startCmd = commandFactory.buildStartDesktop(
                geometry = geometry,
                rfbPort = vncPort,
                display = display
            )
            val proc = startCmd.toProcessBuilder().start()
            localDesktopProc = proc
            desktopProcess = proc

            val stdoutTail = BoundedOutputCollector(MAX_LOG_TAIL_CHARS)
            val stderrTail = BoundedOutputCollector(MAX_LOG_TAIL_CHARS)
            outDrainJob = streamScope.launch { drainStreamUtf8(proc.inputStream, stdoutTail) }
            errDrainJob = streamScope.launch { drainStreamUtf8(proc.errorStream, stderrTail) }

            val desktopReadyFile = File(commandFactory.rootfsDir, "tmp/.pocketlinux_desktop_ready")
            val deadlineNs = System.nanoTime() + bootTimeoutMs * 1_000_000L
            while (System.nanoTime() < deadlineNs) {
                jobContext.ensureActive()
                if (!isCurrentGeneration(gen)) {
                    throw CancellationException("Runtime boot superseded by newer lifecycle transition")
                }

                if (!proc.isAlive) {
                    withTimeoutOrNull(STREAM_DRAIN_GRACE_MS) {
                        outDrainJob.join()
                        errDrainJob.join()
                    }
                    val code = proc.exitValue()
                    val errDetail = stderrTail.snapshot().ifBlank { stdoutTail.snapshot() }
                    throw IllegalStateException(
                        "Desktop server exited prematurely (exitCode=$code): $errDetail"
                    )
                }

                if (desktopReadyFile.isFile && isTcpPortOpen(vncHost, vncPort, 200)) {
                    val xwinCmd = commandFactory.buildShellExec(
                        command = xwinReadyCheckCommand,
                        display = display
                    )
                    val xwinCheck = executeProcessBounded(xwinCmd, XWIN_CHECK_TIMEOUT_MS)
                    jobContext.ensureActive()
                    if (!isCurrentGeneration(gen)) {
                        throw CancellationException("Runtime boot superseded before Ready")
                    }
                    if (xwinCheck.exitCode == 0) {
                        val startupDurationMs =
                            ((System.nanoTime() - overallStartNs) / 1_000_000L).coerceAtLeast(1L)
                        lastStartupDurationMs = startupDurationMs
                        _state.value = RuntimeState.Ready(
                            vncHost = vncHost,
                            vncPort = vncPort,
                            startupDurationMs = startupDurationMs,
                            rootfsReadyMs = effectiveRootfsMs
                        )
                        onNotificationUpdate("Linux desktop ready ($display)")
                        return
                    }
                }

                delay(bootPollIntervalMs)
            }

            destroyProcessCleanly(proc)
            outDrainJob.cancel()
            errDrainJob.cancel()
            desktopProcess = null
            localDesktopProc = null
            deleteDesktopReadyMarkers()
            val detail = stderrTail.snapshot().ifBlank { stdoutTail.snapshot() }
            throw IllegalStateException("Timed out waiting for Xvnc desktop on $display: $detail")
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                (localDesktopProc ?: desktopProcess)?.let { destroyProcessCleanly(it) }
                outDrainJob?.cancel()
                errDrainJob?.cancel()
                desktopProcess = null
                deleteDesktopReadyMarkers()
            }
            throw ce
        } catch (t: Throwable) {
            (localDesktopProc ?: desktopProcess)?.let { destroyProcessCleanly(it) }
            outDrainJob?.cancel()
            errDrainJob?.cancel()
            desktopProcess = null
            deleteDesktopReadyMarkers()
            if (isCurrentGeneration(gen)) {
                val message = t.message ?: t::class.java.simpleName
                _state.value = RuntimeState.Error(message)
                onNotificationUpdate("Linux runtime error")
            }
        }
    }

    override fun stopRuntime() {
        val gen: Long
        val jobToCancel: Job?
        val prevStop: Job?
        synchronized(lifecycleLock) {
            if (destroyed) return
            gen = ++lifecycleGeneration
            jobToCancel = startJob
            startJob = null
            prevStop = stopJob
            jobToCancel?.cancel()
            stopJob = serviceScope.launch {
                prevStop?.join()
                jobToCancel?.cancelAndJoin()
                lifecycleMutex.withLock {
                    cleanupProcessesAndSessions()
                    deleteDesktopReadyMarkers()
                    if (isCurrentGeneration(gen)) {
                        _state.value = RuntimeState.Idle
                        onNotificationUpdate("Linux runtime stopped")
                    }
                }
            }
        }
    }

    fun restartRuntime() {
        stopRuntime()
        startRuntime()
    }

    override suspend fun execCommand(command: String, timeoutMs: Long): CommandResult =
        withContext(Dispatchers.IO) {
            rootfsProvider.ensureExtracted()
            coroutineContext.ensureActive()
            commandFactory.prepareEnvironment()
            val cmd = commandFactory.buildShellExec(command = command, display = display)
            executeProcessBounded(cmd, timeoutMs)
        }

    override suspend fun launchGuiApp(command: String): CommandResult =
        withContext(Dispatchers.IO) {
            rootfsProvider.ensureExtracted()
            coroutineContext.ensureActive()
            val startNs = System.nanoTime()
            commandFactory.prepareEnvironment()
            val cmd = commandFactory.buildLaunchGuiApp(command = command, display = display)
            val process = cmd.toProcessBuilder().start()
            val tracked = synchronized(lifecycleLock) {
                if (destroyed) {
                    false
                } else {
                    managedProcesses.add(process)
                    true
                }
            }
            if (!tracked) {
                destroyProcessCleanly(process)
                throw CancellationException("Runtime destroyed while launching GUI app")
            }

            val stdoutCollector = BoundedOutputCollector(4096)
            val stderrCollector = BoundedOutputCollector(4096)

            val outJob = streamScope.launch {
                drainStreamUtf8(process.inputStream, stdoutCollector)
            }
            val errJob = streamScope.launch {
                drainStreamUtf8(process.errorStream, stderrCollector)
            }
            serviceScope.launch {
                try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    managedProcesses.remove(process)
                }
            }

            try {
                val exitedEarly = runInterruptible(Dispatchers.IO) {
                    process.waitFor(guiStartupCheckMs, TimeUnit.MILLISECONDS)
                }
                val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)
                if (exitedEarly) {
                    managedProcesses.remove(process)
                    val code = process.exitValue()
                    if (code != 0) {
                        val drained = withTimeoutOrNull(STREAM_DRAIN_GRACE_MS) {
                            outJob.join()
                            errJob.join()
                            true
                        } ?: false
                        if (!drained) {
                            outJob.cancel()
                            errJob.cancel()
                            streamScope.launch { closeProcessStreams(process) }
                        }
                        return@withContext CommandResult(
                            exitCode = code,
                            stdout = stdoutCollector.snapshot(),
                            stderr = stderrCollector.snapshot(),
                            durationMs = elapsedMs
                        )
                    }
                }

                CommandResult(
                    exitCode = 0,
                    stdout = "",
                    stderr = "",
                    durationMs = elapsedMs
                )
            } catch (ce: CancellationException) {
                withContext(NonCancellable) {
                    managedProcesses.remove(process)
                    destroyProcessCleanly(process)
                    outJob.cancel()
                    errJob.cancel()
                }
                throw ce
            }
        }

    override suspend fun openInteractiveShell(cols: Int, rows: Int): PtySession =
        withContext(Dispatchers.IO) {
            rootfsProvider.ensureExtracted()
            coroutineContext.ensureActive()
            commandFactory.prepareEnvironment()
            val cmd = commandFactory.buildInteractiveShell(display = display)
            val rawSession = openPtySession(cmd, cols, rows)
            val tracked = TrackedPtySession(rawSession)
            val added = synchronized(lifecycleLock) {
                if (destroyed) {
                    false
                } else {
                    activePtySessions.add(tracked)
                    true
                }
            }
            if (!added) {
                runCatching { tracked.close() }
                throw CancellationException("Runtime destroyed while opening PTY session")
            }
            tracked
        }

    override suspend fun collectMetrics(): SystemMetrics =
        withContext(Dispatchers.IO) {
            val (pssKb, rssKb) = readAndroidMemoryKb()

            val sysinfo = if (rootfsProvider.isReady()) {
                val cmd = commandFactory.buildSysInfo(textMode = false, display = display)
                val res = try {
                    executeProcessBounded(cmd, sysinfoTimeoutMs)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    null
                }
                if (res != null && res.exitCode != -1) {
                    SysinfoParser.parse(res.stdout)
                } else {
                    SysinfoSnapshot()
                }
            } else {
                SysinfoSnapshot()
            }

            SystemMetrics(
                startupDurationMs = lastStartupDurationMs,
                androidPssKb = pssKb,
                androidRssKb = rssKb,
                linuxMemTotalKb = sysinfo.memTotalKb,
                linuxMemAvailableKb = sysinfo.memAvailableKb,
                activeXWindows = sysinfo.xWindows,
                processCount = sysinfo.processCount
            )
        }

    fun destroy() {
        val jobsToCancel = synchronized(lifecycleLock) {
            destroyed = true
            lifecycleGeneration++
            listOfNotNull(startJob, stopJob).also {
                startJob = null
                stopJob = null
            }
        }
        jobsToCancel.forEach { it.cancel() }
        cleanupProcessesAndSessions()
        deleteDesktopReadyMarkers()
        serviceScope.cancel()
        streamScope.cancel()
    }

    internal suspend fun awaitLifecycleIdleForTest() {
        val (sJob, stJob) = synchronized(lifecycleLock) { startJob to stopJob }
        stJob?.join()
        sJob?.join()
    }

    private suspend fun executeProcessBounded(
        cmd: ProotCommand,
        timeoutMs: Long,
        maxOutputChars: Int = MAX_EXEC_OUTPUT_CHARS
    ): CommandResult = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        val process = cmd.toProcessBuilder().start()
        val stdoutCollector = BoundedOutputCollector(maxOutputChars)
        val stderrCollector = BoundedOutputCollector(maxOutputChars)

        val outJob = streamScope.launch { drainStreamUtf8(process.inputStream, stdoutCollector) }
        val errJob = streamScope.launch { drainStreamUtf8(process.errorStream, stderrCollector) }

        try {
            val finished = runInterruptible(Dispatchers.IO) {
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            }
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)
            if (!finished) {
                withContext(NonCancellable) {
                    terminateProcessGracefully(process)
                    outJob.cancel()
                    errJob.cancel()
                    val closeJob = streamScope.launch { closeProcessStreams(process) }
                    withTimeoutOrNull(STREAM_DRAIN_GRACE_MS) {
                        outJob.join()
                        errJob.join()
                        closeJob.join()
                    }
                }
                val out = stdoutCollector.snapshot()
                val err = stderrCollector.snapshot()
                val timeoutMsg = "Command timed out after ${timeoutMs}ms"
                val combinedErr = if (err.isBlank()) timeoutMsg else "$err\n$timeoutMsg"
                CommandResult(
                    exitCode = -1,
                    stdout = out,
                    stderr = combinedErr,
                    durationMs = elapsedMs
                )
            } else {
                val exitCode = process.exitValue()
                val drained = withTimeoutOrNull(STREAM_DRAIN_GRACE_MS) {
                    outJob.join()
                    errJob.join()
                    true
                } ?: false
                if (!drained) {
                    outJob.cancel()
                    errJob.cancel()
                    streamScope.launch { closeProcessStreams(process) }
                } else {
                    runCatching { process.outputStream.close() }
                }
                CommandResult(
                    exitCode = exitCode,
                    stdout = stdoutCollector.snapshot(),
                    stderr = stderrCollector.snapshot(),
                    durationMs = elapsedMs
                )
            }
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                terminateProcessGracefully(process)
                outJob.cancel()
                errJob.cancel()
                val closeJob = streamScope.launch { closeProcessStreams(process) }
                withTimeoutOrNull(STREAM_DRAIN_GRACE_MS) {
                    outJob.join()
                    errJob.join()
                    closeJob.join()
                }
            }
            throw ce
        }
    }

    private fun cleanupProcessesAndSessions() {
        val sessions = synchronized(activePtySessions) {
            activePtySessions.toList().also { activePtySessions.clear() }
        }
        sessions.forEach { session ->
            runCatching { session.close() }
        }

        val guiProcs = synchronized(managedProcesses) {
            managedProcesses.toList().also { managedProcesses.clear() }
        }
        guiProcs.forEach { proc ->
            destroyProcessCleanly(proc)
        }

        desktopProcess?.let { proc ->
            destroyProcessCleanly(proc)
        }
        desktopProcess = null
        val orphanReaper = onKillOrphanedGuestProcesses
        if (orphanReaper != null) {
            runCatching { orphanReaper() }
            waitUntilVncPortClosed(1_500L)
        }
    }

    private fun waitUntilVncPortClosed(timeoutMs: Long) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (!isTcpPortOpen(vncHost, vncPort, 80)) {
                return
            }
            try {
                Thread.sleep(50L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun deleteDesktopReadyMarkers() {
        runCatching {
            File(commandFactory.rootfsDir, "tmp/.pocketlinux_desktop_ready").delete()
            File(commandFactory.rootfsDir, "tmp/.pocketlinux_desktop_ready.tmp").delete()
            File(commandFactory.rootfsDir, "tmp/.X1-lock").delete()
            File(commandFactory.rootfsDir, "tmp/.X11-unix/X1").delete()
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

    private fun destroyProcessCleanly(process: Process) {
        terminateProcessGracefully(process)
        if (!streamScope.isActive) {
            closeProcessStreams(process)
        } else {
            streamScope.launch { closeProcessStreams(process) }
        }
    }

    private fun closeProcessStreams(process: Process) {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    private fun isTcpPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun drainStreamUtf8(stream: InputStream, collector: BoundedOutputCollector) {
        val buffer = CharArray(4096)
        try {
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        collector.append(String(buffer, 0, read))
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private inner class TrackedPtySession(
        private val delegate: PtySession
    ) : PtySession {
        private val closeNotified = AtomicBoolean(false)

        override val pid: Int
            get() = delegate.pid

        override val inputStream: InputStream = object : InputStream() {
            override fun read(): Int = delegate.inputStream.read()
            override fun read(b: ByteArray): Int = delegate.inputStream.read(b)
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                delegate.inputStream.read(b, off, len)

            override fun available(): Int = delegate.inputStream.available()
            override fun skip(n: Long): Long = delegate.inputStream.skip(n)
            override fun close() {
                this@TrackedPtySession.close()
            }
        }

        override val outputStream: OutputStream = object : OutputStream() {
            override fun write(b: Int) = delegate.outputStream.write(b)
            override fun write(b: ByteArray) = delegate.outputStream.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) =
                delegate.outputStream.write(b, off, len)

            override fun flush() = delegate.outputStream.flush()
            override fun close() {
                this@TrackedPtySession.close()
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun write(bytes: ByteArray, offset: Int, length: Int) =
            delegate.write(bytes, offset, length)

        override fun write(text: String) = delegate.write(text)

        override fun resize(cols: Int, rows: Int) = delegate.resize(cols, rows)

        override fun isAlive(): Boolean = delegate.isAlive()

        override fun waitFor(): Int = delegate.waitFor()

        override fun close() {
            if (closeNotified.compareAndSet(false, true)) {
                try {
                    delegate.close()
                } finally {
                    activePtySessions.remove(this)
                }
            }
        }
    }

    private class BoundedOutputCollector(private val maxChars: Int) {
        private val sb = StringBuilder()

        @Synchronized
        fun append(chunk: String) {
            if (chunk.isEmpty()) return
            sb.append(chunk)
            if (sb.length > maxChars) {
                sb.delete(0, sb.length - maxChars)
            }
        }

        @Synchronized
        fun snapshot(): String = sb.toString()
    }

    companion object {
        private const val LOCALHOST = "127.0.0.1"
        private const val DEFAULT_DISPLAY = ":1"
        private const val DEFAULT_GEOMETRY = "1024x600"
        private const val DEFAULT_VNC_PORT = 5901
        private const val DEFAULT_BOOT_TIMEOUT_MS = 30_000L
        private const val DEFAULT_BOOT_POLL_INTERVAL_MS = 60L
        private const val DEFAULT_GUI_STARTUP_CHECK_MS = 80L
        private const val XWIN_CHECK_TIMEOUT_MS = 5_000L
        private const val SYSINFO_TIMEOUT_MS = 5_000L
        private const val GRACEFUL_TERM_WAIT_MS = 1_500L
        private const val FORCE_KILL_WAIT_MS = 500L
        private const val STREAM_DRAIN_GRACE_MS = 1_000L
        private const val MAX_LOG_TAIL_CHARS = 8192
        private const val MAX_EXEC_OUTPUT_CHARS = 1_048_576
    }
}
