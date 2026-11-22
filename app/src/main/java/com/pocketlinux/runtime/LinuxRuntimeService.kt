package com.pocketlinux.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Debug
import android.os.IBinder
import android.system.Os
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pocketlinux.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit

class LinuxRuntimeService : Service(), LinuxRuntimeController {

    inner class LocalBinder : Binder() {
        fun getService(): LinuxRuntimeService = this@LinuxRuntimeService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()

    private lateinit var rootfsManager: RootfsManager
    private lateinit var commandBuilder: ProotCommandBuilder

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Idle)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    @Volatile
    private var startJob: Job? = null

    @Volatile
    private var desktopProcess: Process? = null

    @Volatile
    private var lastStartupDurationMs: Long = 0L

    private val managedProcesses: MutableSet<Process> = Collections.synchronizedSet(mutableSetOf())
    private val activePtySessions: MutableSet<PtySession> = Collections.synchronizedSet(mutableSetOf())

    override fun onCreate() {
        super.onCreate()
        rootfsManager = RootfsManager(this)
        commandBuilder = ProotCommandBuilder(this)
        createNotificationChannel()
        _serviceInstance.value = this
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground("Linux runtime active")
        if (intent?.action == ACTION_START_RUNTIME) {
            startRuntime()
        }
        return START_NOT_STICKY
    }

    override fun startRuntime() {
        synchronized(lifecycleLock) {
            val current = _state.value
            if (current is RuntimeState.Extracting ||
                current is RuntimeState.Booting ||
                current is RuntimeState.Ready
            ) {
                return
            }
            if (startJob?.isActive == true) {
                return
            }
            _state.value = RuntimeState.Extracting("Preparing Linux rootfs...")
            updateNotification("Preparing Linux rootfs...")
            startJob = serviceScope.launch {
                bootRuntimeInternal()
            }
        }
    }

    private suspend fun bootRuntimeInternal() {
        val overallStartNs = System.nanoTime()
        try {
            rootfsManager.ensureExtracted { progressLabel ->
                _state.value = RuntimeState.Extracting(progressLabel)
                updateNotification(progressLabel)
            }
            val rootfsReadyMs = ((System.nanoTime() - overallStartNs) / 1_000_000L).coerceAtLeast(0L)

            val desktopReadyFile = File(commandBuilder.rootfsDir, "tmp/.pocketlinux_desktop_ready")
            val desktopReadyTmp = File(commandBuilder.rootfsDir, "tmp/.pocketlinux_desktop_ready.tmp")
            desktopReadyFile.delete()
            desktopReadyTmp.delete()

            _state.value = RuntimeState.Booting
            updateNotification("Booting X11 desktop...")

            val startCmd = commandBuilder.buildStartDesktop(
                geometry = DEFAULT_GEOMETRY,
                rfbPort = DEFAULT_VNC_PORT,
                display = DEFAULT_DISPLAY
            )
            val proc = startCmd.toProcessBuilder().start()
            desktopProcess = proc

            val stdoutTail = BoundedOutputCollector(MAX_LOG_TAIL_CHARS)
            val stderrTail = BoundedOutputCollector(MAX_LOG_TAIL_CHARS)
            val outDrainJob = serviceScope.launch { drainStream(proc.inputStream, stdoutTail) }
            val errDrainJob = serviceScope.launch { drainStream(proc.errorStream, stderrTail) }

            val deadlineNs = System.nanoTime() + BOOT_TIMEOUT_MS * 1_000_000L
            while (System.nanoTime() < deadlineNs) {
                if (!serviceScope.isActive) {
                    throw CancellationException("Runtime service cancelled during boot")
                }
                if (!proc.isAlive) {
                    outDrainJob.join()
                    errDrainJob.join()
                    val code = proc.exitValue()
                    val errDetail = stderrTail.snapshot().ifBlank { stdoutTail.snapshot() }
                    throw IllegalStateException(
                        "Desktop server exited prematurely (exitCode=$code): $errDetail"
                    )
                }

                if (desktopReadyFile.isFile && isTcpPortOpen(LOCALHOST, DEFAULT_VNC_PORT, 200)) {
                    val xwinCheck = execCommand("xwininfo -display $DEFAULT_DISPLAY -root -tree", 5_000L)
                    if (xwinCheck.exitCode == 0) {
                        val startupDurationMs = ((System.nanoTime() - overallStartNs) / 1_000_000L).coerceAtLeast(1L)
                        lastStartupDurationMs = startupDurationMs
                        _state.value = RuntimeState.Ready(
                            vncHost = LOCALHOST,
                            vncPort = DEFAULT_VNC_PORT,
                            startupDurationMs = startupDurationMs,
                            rootfsReadyMs = rootfsReadyMs
                        )
                        updateNotification("Linux desktop ready ($DEFAULT_DISPLAY)")
                        return
                    }
                }

                delay(BOOT_POLL_INTERVAL_MS)
            }

            destroyProcessCleanly(proc)
            desktopProcess = null
            val detail = stderrTail.snapshot().ifBlank { stdoutTail.snapshot() }
            throw IllegalStateException("Timed out waiting for Xvnc desktop on $DEFAULT_DISPLAY: $detail")
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            desktopProcess?.let { destroyProcessCleanly(it) }
            desktopProcess = null
            val message = t.message ?: t::class.java.simpleName
            _state.value = RuntimeState.Error(message)
            updateNotification("Linux runtime error")
        }
    }

    override fun stopRuntime() {
        synchronized(lifecycleLock) {
            startJob?.cancel()
            startJob = null
        }
        cleanupProcessesAndSessions()
        File(commandBuilder.rootfsDir, "tmp/.pocketlinux_desktop_ready").delete()
        _state.value = RuntimeState.Idle
        updateNotification("Linux runtime stopped")
    }

    override suspend fun execCommand(command: String, timeoutMs: Long): CommandResult =
        withContext(Dispatchers.IO) {
            rootfsManager.ensureExtracted()
            val startNs = System.nanoTime()
            val cmd = commandBuilder.buildShellExec(command = command, display = DEFAULT_DISPLAY)
            val process = cmd.toProcessBuilder().start()

            coroutineScope {
                val stdoutDeferred = async {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                val stderrDeferred = async {
                    process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }

                try {
                    val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                    val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)
                    if (!finished) {
                        process.destroyForcibly()
                        process.waitFor(2_000L, TimeUnit.MILLISECONDS)
                        val out = runCatching { stdoutDeferred.await() }.getOrDefault("")
                        val err = runCatching { stderrDeferred.await() }.getOrDefault("")
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
                        val out = stdoutDeferred.await()
                        val err = stderrDeferred.await()
                        CommandResult(
                            exitCode = exitCode,
                            stdout = out,
                            stderr = err,
                            durationMs = elapsedMs
                        )
                    }
                } catch (ce: CancellationException) {
                    process.destroyForcibly()
                    throw ce
                }
            }
        }

    override suspend fun launchGuiApp(command: String): CommandResult =
        withContext(Dispatchers.IO) {
            rootfsManager.ensureExtracted()
            val startNs = System.nanoTime()
            val cmd = commandBuilder.buildLaunchGuiApp(command = command, display = DEFAULT_DISPLAY)
            val process = cmd.toProcessBuilder().start()
            managedProcesses.add(process)

            val stdoutCollector = BoundedOutputCollector(4096)
            val stderrCollector = BoundedOutputCollector(4096)

            val outJob = serviceScope.launch {
                drainStream(process.inputStream, stdoutCollector)
            }
            val errJob = serviceScope.launch {
                drainStream(process.errorStream, stderrCollector)
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

            val exitedEarly = process.waitFor(GUI_STARTUP_CHECK_MS, TimeUnit.MILLISECONDS)
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)
            if (exitedEarly) {
                managedProcesses.remove(process)
                val code = process.exitValue()
                if (code != 0) {
                    outJob.join()
                    errJob.join()
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
        }

    override suspend fun openInteractiveShell(cols: Int, rows: Int): PtySession =
        withContext(Dispatchers.IO) {
            rootfsManager.ensureExtracted()
            val cmd = commandBuilder.buildInteractiveShell(display = DEFAULT_DISPLAY)
            val session = PtyBridge.openSession(
                argv = cmd.argv,
                env = cmd.env,
                workingDir = null,
                cols = cols,
                rows = rows
            )
            activePtySessions.add(session)
            session
        }

    override suspend fun collectMetrics(): SystemMetrics =
        withContext(Dispatchers.IO) {
            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)
            val pssKb = memInfo.totalPss
            val rssKb = readAndroidRssKb()

            val sysinfo = if (rootfsManager.isReady()) {
                val cmd = commandBuilder.buildSysInfo(textMode = false, display = DEFAULT_DISPLAY)
                val res = runCatching {
                    val proc = cmd.toProcessBuilder().start()
                    coroutineScope {
                        val outDef = async {
                            proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        }
                        val errDef = async {
                            proc.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        }
                        val done = proc.waitFor(5_000L, TimeUnit.MILLISECONDS)
                        if (!done) {
                            proc.destroyForcibly()
                            ""
                        } else {
                            errDef.await()
                            outDef.await()
                        }
                    }
                }.getOrDefault("")
                SysinfoParser.parse(res)
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

    override fun onDestroy() {
        _serviceInstance.compareAndSet(this, null)
        synchronized(lifecycleLock) {
            startJob?.cancel()
            startJob = null
        }
        cleanupProcessesAndSessions()
        serviceScope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun cleanupProcessesAndSessions() {
        val sessions = activePtySessions.toList()
        activePtySessions.clear()
        sessions.forEach { session ->
            runCatching { session.close() }
        }

        val guiProcs = managedProcesses.toList()
        managedProcesses.clear()
        guiProcs.forEach { proc ->
            destroyProcessCleanly(proc)
        }

        desktopProcess?.let { proc ->
            destroyProcessCleanly(proc)
        }
        desktopProcess = null
    }

    private fun destroyProcessCleanly(process: Process) {
        runCatching {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(300L, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(500L, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    private fun readAndroidRssKb(): Int {
        val statusRss = runCatching {
            SysinfoParser.parseProcVmRssKb(File("/proc/self/status").readText())
        }.getOrDefault(0)
        if (statusRss > 0) return statusRss

        return runCatching {
            val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
            SysinfoParser.parseProcStatmRssKb(File("/proc/self/statm").readText(), pageSize)
        }.getOrDefault(0)
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

    private fun drainStream(stream: InputStream, collector: BoundedOutputCollector) {
        val buffer = ByteArray(4096)
        try {
            stream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    collector.append(String(buffer, 0, read, Charsets.UTF_8))
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.runtime_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.runtime_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun promoteToForeground(contentText: String) {
        val notification = buildNotification(contentText)
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
    }

    private fun updateNotification(contentText: String) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIFICATION_ID, buildNotification(contentText))
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
        const val ACTION_START_RUNTIME = "com.pocketlinux.runtime.ACTION_START_RUNTIME"
        const val NOTIFICATION_CHANNEL_ID = "pocketlinux_runtime"
        const val NOTIFICATION_ID = 1001

        private const val LOCALHOST = "127.0.0.1"
        private const val DEFAULT_DISPLAY = ":1"
        private const val DEFAULT_GEOMETRY = "1024x600"
        private const val DEFAULT_VNC_PORT = 5901
        private const val BOOT_TIMEOUT_MS = 30_000L
        private const val BOOT_POLL_INTERVAL_MS = 150L
        private const val GUI_STARTUP_CHECK_MS = 250L
        private const val MAX_LOG_TAIL_CHARS = 8192

        private val _serviceInstance = MutableStateFlow<LinuxRuntimeController?>(null)
        val serviceInstance: StateFlow<LinuxRuntimeController?> = _serviceInstance.asStateFlow()
    }
}
