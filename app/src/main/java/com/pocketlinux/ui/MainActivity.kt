package com.pocketlinux.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pocketlinux.rfb.RfbClient
import com.pocketlinux.runtime.LinuxRuntimeService
import com.pocketlinux.runtime.RuntimeState
import com.pocketlinux.runtime.SystemMetrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val fallbackRfbClient by lazy { RfbClient() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Runtime service continues regardless of notification permission outcome.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        requestNotificationPermissionIfNeeded()
        ensureRuntimeServiceStarted()

        setContent {
            PocketLinuxTheme {
                PocketLinuxMainScreen(
                    fallbackRfbClient = fallbackRfbClient,
                    onRestartRuntime = { ensureRuntimeServiceStarted() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ensureRuntimeServiceStarted()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val granted = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
private fun PocketLinuxMainScreen(
    fallbackRfbClient: RfbClient,
    onRestartRuntime: () -> Unit
) {
    val service by LinuxRuntimeService.serviceInstance.collectAsState()
    val sharedRfb by LinuxRuntimeService.sharedRfbClient.collectAsState()
    val sharedTerminal by LinuxRuntimeService.sharedTerminalSession.collectAsState()
    val scope = rememberCoroutineScope()

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

    val launchAppAndSwitchToDesktop: (String) -> Unit = { command ->
        selectedTab = 0
        val currentService = service
        if (currentService != null) {
            scope.launch {
                runCatching { currentService.launchGuiApp(command) }
                refreshMetricsNow()
            }
        }
    }

    LaunchedEffect(service, runtimeState) {
        if (service == null) return@LaunchedEffect
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
        color = PocketLinuxColors.Obsidian
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            TopTelemetryBar(
                runtimeState = runtimeState,
                latestMetrics = latestMetrics,
                onRestartRuntime = onRestartRuntime
            )

            SegmentedTabNavigationBar(
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
                        rfbController = sharedRfb ?: fallbackRfbClient,
                        runtimeState = runtimeState,
                        onLaunchApp = launchAppAndSwitchToDesktop,
                        onOpenAppsTab = { selectedTab = 2 },
                        onRestartRuntime = onRestartRuntime
                    )
                    1 -> TerminalTab(
                        terminalSession = sharedTerminal,
                        runtimeState = runtimeState
                    )
                    else -> AppsSystemTab(
                        runtimeController = service,
                        runtimeState = runtimeState,
                        latestMetrics = latestMetrics,
                        onRefreshMetrics = { refreshMetricsNow() },
                        onLaunchAppAndSwitchToDesktop = launchAppAndSwitchToDesktop,
                        onSwitchToDesktop = { selectedTab = 0 }
                    )
                }
            }
        }
    }
}

@Composable
private fun TopTelemetryBar(
    runtimeState: RuntimeState,
    latestMetrics: SystemMetrics?,
    onRestartRuntime: () -> Unit
) {
    val statusText = when (runtimeState) {
        is RuntimeState.Idle -> "Linux: Idle"
        is RuntimeState.Extracting -> "Linux: Extracting"
        is RuntimeState.Booting -> "Linux: Booting"
        is RuntimeState.Ready -> "Linux: Ready"
        is RuntimeState.Error -> "Linux: Error"
    }

    val statusColor = when (runtimeState) {
        is RuntimeState.Ready -> PocketLinuxColors.Emerald
        is RuntimeState.Error -> PocketLinuxColors.Rose
        is RuntimeState.Idle -> PocketLinuxColors.TextSecondary
        else -> PocketLinuxColors.Amber
    }

    val statusBg = when (runtimeState) {
        is RuntimeState.Ready -> PocketLinuxColors.EmeraldContainer.copy(alpha = 0.55f)
        is RuntimeState.Error -> PocketLinuxColors.RoseContainer.copy(alpha = 0.7f)
        is RuntimeState.Idle -> PocketLinuxColors.SurfaceElevated
        else -> PocketLinuxColors.AmberContainer.copy(alpha = 0.65f)
    }

    val isAnimated = runtimeState is RuntimeState.Booting || runtimeState is RuntimeState.Extracting
    val pulseTransition = rememberInfiniteTransition(label = "status_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "status_dot_alpha"
    )

    val startupMs = when (runtimeState) {
        is RuntimeState.Ready -> runtimeState.startupDurationMs.coerceAtLeast(1L)
        else -> latestMetrics?.startupDurationMs ?: 0L
    }

    val pssKb = (latestMetrics?.androidPssKb ?: 24576).coerceAtLeast(1)
    val pssMb = (pssKb / 1024).coerceAtLeast(1)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketLinuxColors.Obsidian)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = statusBg,
            shape = RoundedCornerShape(999.dp),
            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .alpha(if (isAnimated) pulseAlpha else 1f)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("linux_status_badge")
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TelemetryPill(
                text = "${startupMs} ms",
                accent = PocketLinuxColors.TextPrimary,
                tag = "startup_time_badge"
            )
            TelemetryPill(
                text = "${pssMb} MB",
                accent = PocketLinuxColors.Cyan,
                tag = "memory_usage_badge"
            )
            if (runtimeState is RuntimeState.Error || runtimeState is RuntimeState.Idle) {
                Surface(
                    color = PocketLinuxColors.CyanContainer,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, PocketLinuxColors.Cyan.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onRestartRuntime)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Restart Linux runtime",
                            tint = PocketLinuxColors.Cyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryPill(
    text: String,
    accent: Color,
    tag: String
) {
    Surface(
        color = PocketLinuxColors.Surface,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, PocketLinuxColors.Border)
    ) {
        Text(
            text = text,
            color = accent,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier
                .padding(horizontal = 9.dp, vertical = 4.dp)
                .testTag(tag)
        )
    }
}

private data class NavTabSpec(
    val index: Int,
    val tag: String,
    val glyph: String,
    val title: String
)

@Composable
private fun SegmentedTabNavigationBar(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit
) {
    val tabs = remember {
        listOf(
            NavTabSpec(0, "tab_desktop", "▣", "Desktop"),
            NavTabSpec(1, "tab_terminal", ">_", "Terminal"),
            NavTabSpec(2, "tab_apps", "⊞", "Apps & System")
        )
    }

    Surface(
        color = PocketLinuxColors.Surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, PocketLinuxColors.BorderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                val selected = selectedTab == tab.index
                val bgColor by animateColorAsState(
                    targetValue = if (selected) PocketLinuxColors.Cyan else Color.Transparent,
                    label = "tab_bg_${tab.index}"
                )
                val fgColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        PocketLinuxColors.TextSecondary
                    },
                    label = "tab_fg_${tab.index}"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bgColor)
                        .clickable { onSelectTab(tab.index) }
                        .testTag(tab.tag),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    ) {
                        Text(
                            text = tab.glyph,
                            color = fgColor,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = tab.title,
                            color = fgColor,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
