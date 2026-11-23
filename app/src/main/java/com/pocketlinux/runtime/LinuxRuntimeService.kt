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
import com.pocketlinux.rfb.RfbClient
import com.pocketlinux.rfb.RfbConnectionState
import com.pocketlinux.ui.TerminalSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class LinuxRuntimeService : Service(), LinuxRuntimeController {

    inner class LocalBinder : Binder() {
        fun getService(): LinuxRuntimeService = this@LinuxRuntimeService
    }

    private val binder = LocalBinder()
    private val serviceMonitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var engine: LinuxRuntimeEngine

    val rfbClient: RfbClient = RfbClient()
    lateinit var terminalSession: TerminalSessionController
        private set

    override val state: StateFlow<RuntimeState>
        get() = engine.state

    override fun onCreate() {
        super.onCreate()
        val rootfsManager = RootfsManager(this)
        val commandBuilder = ProotCommandBuilder(this)
        createNotificationChannel()
        engine = LinuxRuntimeEngine(
            rootfsProvider = RootfsManagerAdapter(rootfsManager),
            commandFactory = ProotCommandFactory(commandBuilder),
            readAndroidMemoryKb = {
                readSafeAndroidMemoryKb()
            },
            onNotificationUpdate = { text -> updateNotification(text) }
        )
        terminalSession = TerminalSessionController(this)
        _serviceInstance.value = this
        _sharedRfbClient.value = rfbClient
        _sharedTerminalSession.value = terminalSession

        serviceMonitorScope.launch {
            while (isActive) {
                val current = engine.state.value
                if (current is RuntimeState.Ready) {
                    val conn = rfbClient.connectionState.value
                    if (conn is RfbConnectionState.Disconnected || conn is RfbConnectionState.Error) {
                        runCatching {
                            rfbClient.connect(current.vncHost, current.vncPort)
                        }
                    }
                } else if (current is RuntimeState.Idle || current is RuntimeState.Error) {
                    if (rfbClient.connectionState.value !is RfbConnectionState.Disconnected) {
                        runCatching { rfbClient.disconnect() }
                    }
                }
                delay(400L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground("Linux runtime active")
        if (intent == null || intent.action == null || intent.action == ACTION_START_RUNTIME) {
            startRuntime()
        }
        return START_NOT_STICKY
    }

    override fun startRuntime() {
        engine.startRuntime()
    }

    override fun stopRuntime() {
        if (::terminalSession.isInitialized) {
            terminalSession.closeCurrentSession()
        }
        runCatching { rfbClient.disconnect() }
        engine.stopRuntime()
    }

    override suspend fun execCommand(command: String, timeoutMs: Long): CommandResult =
        engine.execCommand(command, timeoutMs)

    override suspend fun launchGuiApp(command: String): CommandResult =
        engine.launchGuiApp(command)

    override suspend fun openInteractiveShell(cols: Int, rows: Int): PtySession =
        engine.openInteractiveShell(cols, rows)

    override suspend fun collectMetrics(): SystemMetrics =
        engine.collectMetrics()

    fun readSafeAndroidMemoryKb(): Pair<Int, Int> {
        val pssKb = runCatching {
            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)
            memInfo.totalPss
        }.getOrDefault(0).coerceAtLeast(1)

        val rssKb = readAndroidRssKb().let { rawRss ->
            if (rawRss > 0) rawRss else pssKb
        }
        return pssKb to rssKb
    }

    override fun onDestroy() {
        serviceMonitorScope.cancel()
        _serviceInstance.compareAndSet(this, null)
        if (::terminalSession.isInitialized) {
            _sharedTerminalSession.compareAndSet(terminalSession, null)
            runCatching { terminalSession.destroy() }
        }
        runCatching { rfbClient.close() }
        if (::engine.isInitialized) {
            engine.destroy()
        }
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
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

    private fun createNotificationChannel() {
        runCatching {
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
        runCatching {
            val notification = buildNotification(contentText)
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
        }
    }

    private fun updateNotification(contentText: String) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIFICATION_ID, buildNotification(contentText))
        }
    }

    companion object {
        const val ACTION_START_RUNTIME = "com.pocketlinux.runtime.ACTION_START_RUNTIME"
        const val NOTIFICATION_CHANNEL_ID = "pocketlinux_runtime"
        const val NOTIFICATION_ID = 1001

        private val _serviceInstance = MutableStateFlow<LinuxRuntimeService?>(null)
        val serviceInstance: StateFlow<LinuxRuntimeService?> = _serviceInstance.asStateFlow()

        private val _sharedRfbClient = MutableStateFlow<RfbClient?>(null)
        val sharedRfbClient: StateFlow<RfbClient?> = _sharedRfbClient.asStateFlow()

        private val _sharedTerminalSession = MutableStateFlow<TerminalSessionController?>(null)
        val sharedTerminalSession: StateFlow<TerminalSessionController?> = _sharedTerminalSession.asStateFlow()
    }
}
