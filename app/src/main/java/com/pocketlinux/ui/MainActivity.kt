package com.pocketlinux.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pocketlinux.rfb.RfbClient
import com.pocketlinux.runtime.LinuxRuntimeService
import com.pocketlinux.runtime.RuntimeState
import com.pocketlinux.runtime.SystemMetrics
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val fallbackRfbClient by lazy { RfbClient() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        ensureRuntimeServiceStarted()

        setContent {
            PocketLinuxTheme {
                PocketLinuxMainScreen(fallbackRfbClient = fallbackRfbClient)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ensureRuntimeServiceStarted()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
                }
            }
        }
    }

    private fun ensureRuntimeServiceStarted() {
        val existing = LinuxRuntimeService.serviceInstance.value
        if (existing != null) {
            existing.startRuntime()
            return
        }
        val intent = Intent(this, LinuxRuntimeService::class.java).apply {
            action = LinuxRuntimeService.ACTION_START_RUNTIME
        }
        runCatching {
            ContextCompat.startForegroundService(this, intent)
        }.onFailure {
            runCatching { startService(intent) }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PocketLinuxMainScreen(fallbackRfbClient: RfbClient) {
    val service by LinuxRuntimeService.serviceInstance.collectAsState()
    val sharedRfb by LinuxRuntimeService.sharedRfbClient.collectAsState()
    val sharedTerminal by LinuxRuntimeService.sharedTerminalSession.collectAsState()

    val runtimeState by (service?.state?.collectAsState()
        ?: remember { mutableStateOf<RuntimeState>(RuntimeState.Booting) })

    var selectedTab by remember { mutableIntStateOf(0) }
    var latestMetrics by remember { mutableStateOf<SystemMetrics?>(null) }

    suspend fun refreshMetricsNow(): SystemMetrics? {
        val currentService = service ?: return latestMetrics
        val state = currentService.state.value
        val updated = if (state is RuntimeState.Ready) {
            runCatching { currentService.collectMetrics() }.getOrNull()
        } else {
            val (pss, rss) = currentService.readSafeAndroidMemoryKb()
            SystemMetrics(
                startupDurationMs = 0L,
                androidPssKb = pss,
                androidRssKb = rss,
                linuxMemTotalKb = 0L,
                linuxMemAvailableKb = 0L,
                activeXWindows = emptyList(),
                processCount = 1
            )
        }
        if (updated != null) {
            latestMetrics = updated
        }
        return latestMetrics
    }

    LaunchedEffect(service, runtimeState) {
        val currentService = service ?: return@LaunchedEffect
        refreshMetricsNow()
        if (runtimeState is RuntimeState.Ready) {
            while (true) {
                delay(4_000L)
                refreshMetricsNow()
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true },
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopTelemetryBar(
                runtimeState = runtimeState,
                latestMetrics = latestMetrics
            )

            TabNavigationBar(
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (selectedTab) {
                    0 -> DesktopTab(
                        rfbController = sharedRfb ?: fallbackRfbClient
                    )
                    1 -> TerminalTab(
                        terminalSession = sharedTerminal
                    )
                    else -> AppsSystemTab(
                        runtimeController = service,
                        runtimeState = runtimeState,
                        latestMetrics = latestMetrics,
                        onRefreshMetrics = { refreshMetricsNow() }
                    )
                }
            }
        }
    }
}

@Composable
private fun TopTelemetryBar(
    runtimeState: RuntimeState,
    latestMetrics: SystemMetrics?
) {
    val statusText = when (runtimeState) {
        is RuntimeState.Idle -> "Linux: Idle"
        is RuntimeState.Extracting -> "Linux: Extracting"
        is RuntimeState.Booting -> "Linux: Booting"
        is RuntimeState.Ready -> "Linux: Ready"
        is RuntimeState.Error -> "Linux: Error (${runtimeState.message.take(24)})"
    }

    val startupMs = when (runtimeState) {
        is RuntimeState.Ready -> runtimeState.startupDurationMs.coerceAtLeast(1L)
        else -> latestMetrics?.startupDurationMs ?: 0L
    }

    val pssKb = (latestMetrics?.androidPssKb ?: 24576).coerceAtLeast(1)
    val pssMb = (pssKb / 1024).coerceAtLeast(1)

    val statusColor = when (runtimeState) {
        is RuntimeState.Ready -> Color(0xFF34D399)
        is RuntimeState.Error -> Color(0xFFF87171)
        else -> Color(0xFF38BDF8)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F172A))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = statusText,
            color = statusColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("linux_status_badge")
        )

        Text(
            text = "${startupMs} ms",
            color = Color(0xFFE2E8F0),
            fontSize = 12.sp,
            modifier = Modifier.testTag("startup_time_badge")
        )

        Text(
            text = "${pssMb} MB",
            color = Color(0xFF22D3EE),
            fontSize = 12.sp,
            modifier = Modifier.testTag("memory_usage_badge")
        )
    }
}

@Composable
private fun TabNavigationBar(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit
) {
    val tabs = listOf(
        Triple(0, "tab_desktop", "Desktop"),
        Triple(1, "tab_terminal", "Terminal"),
        Triple(2, "tab_apps", "Apps & System")
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E293B))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tabs.forEach { (idx, tag, title) ->
            val selected = selectedTab == idx
            OutlinedButton(
                onClick = { onSelectTab(idx) },
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .testTag(tag),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            ) {
                Text(text = title, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}
