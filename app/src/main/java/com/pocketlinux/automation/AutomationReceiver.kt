package com.pocketlinux.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Debug
import androidx.core.content.ContextCompat
import com.pocketlinux.rfb.FrameSnapshot
import com.pocketlinux.rfb.RfbConnectionState
import com.pocketlinux.runtime.LinuxRuntimeService
import com.pocketlinux.runtime.RuntimeState
import com.pocketlinux.runtime.SysinfoParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class AutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) {
            // Unknown broadcast actions are ignored safely without crashing (T2-F12-05).
            return
        }

        val appContext = context.applicationContext
        val reqId = intent.getStringExtra("req_id")?.trim().orEmpty()
        val outFile = intent.getStringExtra("out_file")?.trim()
        val commandExtra = intent.getStringExtra("command")
        val appExtra = intent.getStringExtra("app")
        val pendingResult = goAsync()

        receiverScope.launch {
            try {
                when (action) {
                    ACTION_START_RUNTIME, LinuxRuntimeService.ACTION_START_RUNTIME -> {
                        val service = ensureServiceStarted(appContext)
                        service?.startRuntime()
                        if (reqId.isNotBlank() || !outFile.isNullOrBlank()) {
                            val statusJson = buildStatusPayload(service, reqId)
                            DiagnosticsStore.writeActionResponse(
                                context = appContext,
                                action = action,
                                reqId = reqId,
                                rawOutFile = outFile,
                                payload = statusJson
                            )
                        }
                    }

                    ACTION_GET_STATUS -> {
                        val service = ensureServiceStarted(appContext)
                        val payload = buildStatusPayload(service, reqId)
                        DiagnosticsStore.writeActionResponse(
                            context = appContext,
                            action = action,
                            reqId = reqId,
                            rawOutFile = outFile,
                            payload = payload
                        )
                    }

                    ACTION_EXEC_LINUX -> {
                        val payload = handleExecLinux(appContext, reqId, commandExtra)
                        DiagnosticsStore.writeActionResponse(
                            context = appContext,
                            action = action,
                            reqId = reqId,
                            rawOutFile = outFile,
                            payload = payload
                        )
                    }

                    ACTION_LAUNCH_GUI -> {
                        val payload = handleLaunchGui(appContext, reqId, appExtra)
                        DiagnosticsStore.writeActionResponse(
                            context = appContext,
                            action = action,
                            reqId = reqId,
                            rawOutFile = outFile,
                            payload = payload
                        )
                    }

                    ACTION_VERIFY_DESKTOP_FRAME -> {
                        val payload = handleVerifyDesktopFrame(appContext, reqId)
                        DiagnosticsStore.writeActionResponse(
                            context = appContext,
                            action = action,
                            reqId = reqId,
                            rawOutFile = outFile,
                            payload = payload
                        )
                    }
                }
            } catch (t: Throwable) {
                val errPayload = JSONObject().apply {
                    if (reqId.isNotBlank()) put("reqId", reqId)
                    put("error", t.message ?: t.javaClass.simpleName)
                    put("exitCode", 1)
                    put("launched", false)
                    put("connected", false)
                }
                runCatching {
                    DiagnosticsStore.writeActionResponse(
                        context = appContext,
                        action = action,
                        reqId = reqId,
                        rawOutFile = outFile,
                        payload = errPayload
                    )
                }
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    private suspend fun ensureServiceStarted(context: Context): LinuxRuntimeService? {
        LinuxRuntimeService.serviceInstance.value?.let { return it }
        runCatching {
            val serviceIntent = Intent(context, LinuxRuntimeService::class.java).apply {
                action = LinuxRuntimeService.ACTION_START_RUNTIME
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }.onFailure {
            runCatching {
                context.startService(
                    Intent(context, LinuxRuntimeService::class.java).apply {
                        action = LinuxRuntimeService.ACTION_START_RUNTIME
                    }
                )
            }
        }

        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            LinuxRuntimeService.serviceInstance.value?.let { return it }
            delay(50L)
        }
        return LinuxRuntimeService.serviceInstance.value
    }

    private suspend fun buildStatusPayload(
        service: LinuxRuntimeService?,
        reqId: String
    ): JSONObject {
        val currentState = service?.state?.value ?: RuntimeState.Idle
        val stateName = when (currentState) {
            is RuntimeState.Idle -> "Idle"
            is RuntimeState.Extracting -> "Extracting"
            is RuntimeState.Booting -> "Booting"
            is RuntimeState.Ready -> "Ready"
            is RuntimeState.Error -> "Error"
        }

        val (fallbackPssKb, fallbackRssKb) = service?.readSafeAndroidMemoryKb() ?: readFallbackMemoryKb()

        val metrics = if (service != null && currentState is RuntimeState.Ready) {
            runCatching { service.collectMetrics() }.getOrNull()
        } else {
            null
        }

        val startupMs = when (currentState) {
            is RuntimeState.Ready -> currentState.startupDurationMs.coerceAtLeast(1L)
            else -> metrics?.startupDurationMs ?: 0L
        }
        val vncPort = when (currentState) {
            is RuntimeState.Ready -> currentState.vncPort
            else -> 5901
        }
        val pssKb = (metrics?.androidPssKb ?: fallbackPssKb).coerceAtLeast(1)
        val rssKb = (metrics?.androidRssKb ?: fallbackRssKb).coerceAtLeast(1)
        val windows = metrics?.activeXWindows ?: emptyList()

        return JSONObject().apply {
            if (reqId.isNotBlank()) put("reqId", reqId)
            put("state", stateName)
            put("startupMs", startupMs)
            put("vncPort", vncPort)
            put("androidPssKb", pssKb)
            put("androidRssKb", rssKb)
            put("linuxMemTotalKb", metrics?.linuxMemTotalKb ?: 0L)
            put("linuxMemAvailableKb", metrics?.linuxMemAvailableKb ?: 0L)
            put("processCount", metrics?.processCount ?: 0)
            put("activeXWindows", JSONArray(windows))
            if (currentState is RuntimeState.Error) {
                put("errorMessage", currentState.message)
            }
        }
    }

    private suspend fun handleExecLinux(
        context: Context,
        reqId: String,
        command: String?
    ): JSONObject {
        if (command == null) {
            return JSONObject().apply {
                if (reqId.isNotBlank()) put("reqId", reqId)
                put("exitCode", 2)
                put("stdout", "")
                put("stderr", "Missing 'command' extra for ACTION_EXEC_LINUX")
                put("durationMs", 0L)
            }
        }
        val service = ensureServiceStarted(context)
            ?: return JSONObject().apply {
                if (reqId.isNotBlank()) put("reqId", reqId)
                put("exitCode", 1)
                put("stdout", "")
                put("stderr", "LinuxRuntimeService unavailable")
                put("durationMs", 0L)
            }

        val res = service.execCommand(command, timeoutMs = 30_000L)
        if (command.contains("xsetroot") || command.contains("xrandr")) {
            runCatching { service.rfbClient.requestFullFrameUpdate(250L) }
        }
        return JSONObject().apply {
            if (reqId.isNotBlank()) put("reqId", reqId)
            put("exitCode", res.exitCode)
            put("stdout", res.stdout)
            put("stderr", res.stderr)
            put("durationMs", res.durationMs)
        }
    }

    private suspend fun handleLaunchGui(
        context: Context,
        reqId: String,
        rawApp: String?
    ): JSONObject {
        val app = rawApp?.trim().orEmpty()
        if (app.isEmpty()) {
            return JSONObject().apply {
                if (reqId.isNotBlank()) put("reqId", reqId)
                put("launched", false)
                put("app", "")
                put("xWindows", JSONArray())
                put("error", "Missing or empty 'app' extra")
            }
        }

        val service = ensureServiceStarted(context)
            ?: return JSONObject().apply {
                if (reqId.isNotBlank()) put("reqId", reqId)
                put("launched", false)
                put("app", app)
                put("xWindows", JSONArray())
                put("error", "LinuxRuntimeService unavailable")
            }

        val exeToken = extractExecutableToken(app)
        if (exeToken.isEmpty()) {
            return JSONObject().apply {
                if (reqId.isNotBlank()) put("reqId", reqId)
                put("launched", false)
                put("app", app)
                put("xWindows", JSONArray())
            }
        }

        // Verify the binary exists on guest PATH before launching (T2-F11-01)
        val checkCmd = "command -v ${shellSingleQuote(exeToken)} >/dev/null 2>&1"
        val checkRes = service.execCommand(checkCmd, timeoutMs = 10_000L)
        if (checkRes.exitCode != 0) {
            return JSONObject().apply {
                if (reqId.isNotBlank()) put("reqId", reqId)
                put("launched", false)
                put("app", app)
                put("xWindows", JSONArray())
                put("error", "Executable not found: $exeToken")
            }
        }

        val beforeWindows = queryX11Windows(service)
        val launchRes = service.launchGuiApp(app)
        if (launchRes.exitCode != 0) {
            val afterFailWindows = queryX11Windows(service)
            return JSONObject().apply {
                if (reqId.isNotBlank()) put("reqId", reqId)
                put("launched", false)
                put("app", app)
                put("xWindows", JSONArray(afterFailWindows))
                put("error", launchRes.stderr.ifBlank { "Exited with code ${launchRes.exitCode}" })
            }
        }

        val baseName = exeToken.substringAfterLast('/')
        var latestWindows = beforeWindows
        val deadline = System.currentTimeMillis() + 3_500L
        while (System.currentTimeMillis() < deadline) {
            latestWindows = queryX11Windows(service)
            val newWindowCount = latestWindows.size > beforeWindows.size
            val tokenMatched = latestWindows.count { it.contains(baseName, ignoreCase = true) } >
                beforeWindows.count { it.contains(baseName, ignoreCase = true) }
            if (tokenMatched || newWindowCount) {
                break
            }
            delay(200L)
        }

        // Pull latest framebuffer from Xvnc so subsequent frame verification sees the new window
        runCatching { service.rfbClient.requestFullFrameUpdate(300L) }

        return JSONObject().apply {
            if (reqId.isNotBlank()) put("reqId", reqId)
            put("launched", true)
            put("app", app)
            put("xWindows", JSONArray(latestWindows))
        }
    }

    private suspend fun handleVerifyDesktopFrame(
        context: Context,
        reqId: String
    ): JSONObject {
        val service = ensureServiceStarted(context)
        if (service == null) {
            return JSONObject().apply {
                if (reqId.isNotBlank()) put("reqId", reqId)
                put("connected", false)
                put("width", 0)
                put("height", 0)
                put("nonUniformPixels", 0)
                put("sha256", "")
            }
        }

        val rfbClient = service.rfbClient
        val runtimeState = service.state.value
        if (runtimeState is RuntimeState.Ready &&
            rfbClient.connectionState.value !is RfbConnectionState.Connected
        ) {
            rfbClient.connect(runtimeState.vncHost, runtimeState.vncPort)
            val connectDeadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < connectDeadline) {
                if (rfbClient.connectionState.value is RfbConnectionState.Connected &&
                    rfbClient.frameGeneration.get() > 0L
                ) {
                    break
                }
                delay(100L)
            }
        }

        // Request a fresh full frame update from Xvnc so any recent window open/close or xsetroot is reflected
        runCatching { rfbClient.requestFullFrameUpdate(350L) }

        var frame: FrameSnapshot? = rfbClient.snapshotFrame()
        var stats = frame?.let { computeFrameStats(it) }
        val nonUniformDeadline = System.currentTimeMillis() + 3_000L
        while ((stats == null || stats.first == 0) && System.currentTimeMillis() < nonUniformDeadline) {
            runCatching { rfbClient.requestFullFrameUpdate(250L) }
            delay(150L)
            frame = rfbClient.snapshotFrame()
            stats = frame?.let { computeFrameStats(it) }
        }

        val isConnected = rfbClient.connectionState.value is RfbConnectionState.Connected && frame != null
        val width = frame?.width ?: 0
        val height = frame?.height ?: 0
        val nonUniform = stats?.first ?: 0
        val sha256 = stats?.second.orEmpty()

        return JSONObject().apply {
            if (reqId.isNotBlank()) put("reqId", reqId)
            put("connected", isConnected)
            put("width", width)
            put("height", height)
            put("nonUniformPixels", nonUniform)
            put("sha256", sha256)
        }
    }

    private suspend fun queryX11Windows(service: LinuxRuntimeService): List<String> {
        val res = runCatching {
            service.execCommand("xwininfo -display :1 -root -tree 2>/dev/null || true", timeoutMs = 5_000L)
        }.getOrNull() ?: return emptyList()
        if (res.stdout.isBlank()) return emptyList()
        return res.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("0x") }
            .toList()
    }

    private fun computeFrameStats(snapshot: FrameSnapshot): Pair<Int, String> {
        val pixels = snapshot.pixels
        if (pixels.isEmpty()) return 0 to ""
        val first = pixels[0]
        var nonUniform = 0
        val digest = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(minOf(pixels.size, 1024) * 4)
        var bIdx = 0

        for (i in pixels.indices) {
            val p = pixels[i]
            if (p != first) {
                nonUniform++
            }
            chunk[bIdx++] = ((p ushr 24) and 0xFF).toByte()
            chunk[bIdx++] = ((p ushr 16) and 0xFF).toByte()
            chunk[bIdx++] = ((p ushr 8) and 0xFF).toByte()
            chunk[bIdx++] = (p and 0xFF).toByte()
            if (bIdx == chunk.size) {
                digest.update(chunk, 0, bIdx)
                bIdx = 0
            }
        }
        if (bIdx > 0) {
            digest.update(chunk, 0, bIdx)
        }
        val shaHex = digest.digest().joinToString("") { "%02x".format(it) }
        return nonUniform to shaHex
    }

    private fun readFallbackMemoryKb(): Pair<Int, Int> {
        val pss = runCatching {
            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)
            memInfo.totalPss
        }.getOrDefault(0).coerceAtLeast(1)
        val rss = runCatching {
            SysinfoParser.parseProcVmRssKb(File("/proc/self/status").readText())
        }.getOrDefault(0).let { if (it > 0) it else pss }
        return pss to rss
    }

    companion object {
        const val ACTION_GET_STATUS = "com.pocketlinux.ACTION_GET_STATUS"
        const val ACTION_EXEC_LINUX = "com.pocketlinux.ACTION_EXEC_LINUX"
        const val ACTION_LAUNCH_GUI = "com.pocketlinux.ACTION_LAUNCH_GUI"
        const val ACTION_VERIFY_DESKTOP_FRAME = "com.pocketlinux.ACTION_VERIFY_DESKTOP_FRAME"
        const val ACTION_START_RUNTIME = "com.pocketlinux.ACTION_START_RUNTIME"

        private val SUPPORTED_ACTIONS = setOf(
            ACTION_GET_STATUS,
            ACTION_EXEC_LINUX,
            ACTION_LAUNCH_GUI,
            ACTION_VERIFY_DESKTOP_FRAME,
            ACTION_START_RUNTIME,
            LinuxRuntimeService.ACTION_START_RUNTIME
        )

        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        internal fun extractExecutableToken(command: String): String {
            val tokens = command.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            for (tok in tokens) {
                if (tok.contains('=') && !tok.startsWith('/') && !tok.startsWith('.')) {
                    continue
                }
                return tok.trim('\'', '"')
            }
            return ""
        }

        internal fun shellSingleQuote(s: String): String {
            return "'" + s.replace("'", "'\\''") + "'"
        }
    }
}
