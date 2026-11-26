package com.pocketlinux.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pocketlinux.rfb.RfbClient
import com.pocketlinux.rfb.RfbConnectionState
import com.pocketlinux.rfb.RfbConstants
import com.pocketlinux.rfb.TouchInputMode
import com.pocketlinux.runtime.LinuxRuntimeService
import com.pocketlinux.runtime.RuntimeState
import com.pocketlinux.runtime.SystemMetrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val fallbackRfbClient by lazy { RfbClient() }
    private var isImmersiveFullscreenActive: Boolean = true

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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        applyImmersiveFullscreen(true)

        requestNotificationPermissionIfNeeded()
        ensureRuntimeServiceStarted()

        setContent {
            PocketLinuxTheme {
                PocketLinuxMainScreen(
                    fallbackRfbClient = fallbackRfbClient,
                    onRestartRuntime = { ensureRuntimeServiceStarted() },
                    onImmersiveFullscreenChanged = { enabled ->
                        applyImmersiveFullscreen(enabled)
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ensureRuntimeServiceStarted()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveFullscreen(isImmersiveFullscreenActive)
        }
    }

    private fun applyImmersiveFullscreen(enabled: Boolean) {
        isImmersiveFullscreenActive = enabled
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
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
    onRestartRuntime: () -> Unit,
    onImmersiveFullscreenChanged: (Boolean) -> Unit
) {
    val service by LinuxRuntimeService.serviceInstance.collectAsState()
    val sharedRfb by LinuxRuntimeService.sharedRfbClient.collectAsState()
    val sharedTerminal by LinuxRuntimeService.sharedTerminalSession.collectAsState()
    val scope = rememberCoroutineScope()

    val activeRfbController = sharedRfb ?: fallbackRfbClient
    val connectionState by activeRfbController.connectionState.collectAsState()
    val runtimeState by (service?.state?.collectAsState()
        ?: remember { mutableStateOf<RuntimeState>(RuntimeState.Booting) })

    var selectedTab by remember { mutableIntStateOf(0) }
    var latestMetrics by remember { mutableStateOf<SystemMetrics?>(null) }
    var fullscreenEnabled by remember { mutableStateOf(true) }
    var menuExpanded by remember { mutableStateOf(false) }
    var touchMode by remember { mutableStateOf(TouchInputMode.DIRECT_TAP) }
    var softKeyboardTrigger by remember { mutableIntStateOf(0) }

    val isDesktopImmersive = selectedTab == 0 && fullscreenEnabled

    LaunchedEffect(isDesktopImmersive) {
        onImmersiveFullscreenChanged(isDesktopImmersive)
    }

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

    val toggleTouchMode: () -> Unit = {
        touchMode = if (touchMode == TouchInputMode.DIRECT_TAP) {
            TouchInputMode.TRACKPAD
        } else {
            TouchInputMode.DIRECT_TAP
        }
    }

    val requestKeyboard: () -> Unit = {
        selectedTab = 0
        menuExpanded = false
        softKeyboardTrigger++
    }

    val sendRightClickCenter: () -> Unit = {
        val cx = (activeRfbController.desktopWidth / 2).coerceAtLeast(1)
        val cy = (activeRfbController.desktopHeight / 2).coerceAtLeast(1)
        scope.launch {
            activeRfbController.sendPointerEvent(cx, cy, RfbConstants.BUTTON_RIGHT)
            delay(45L)
            activeRfbController.sendPointerEvent(cx, cy, 0)
        }
    }

    val launchAppAndSwitchToDesktop: (String) -> Unit = { command ->
        selectedTab = 0
        menuExpanded = false
        val currentService = service
        if (currentService != null) {
            scope.launch {
                runCatching { currentService.launchGuiApp(command) }
            }
        }
    }

    LaunchedEffect(service, runtimeState, selectedTab, menuExpanded) {
        if (service == null) return@LaunchedEffect
        if (selectedTab == 2 || menuExpanded || latestMetrics == null) {
            refreshMetricsNow()
        }
        if (runtimeState is RuntimeState.Ready && selectedTab == 2) {
            while (true) {
                delay(8_000L)
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
        Box(modifier = Modifier.fillMaxSize()) {
            if (selectedTab == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (!fullscreenEnabled) {
                                Modifier
                                    .statusBarsPadding()
                                    .navigationBarsPadding()
                            } else {
                                Modifier
                            }
                        )
                ) {
                    DesktopTab(
                        rfbController = activeRfbController,
                        runtimeState = runtimeState,
                        softKeyboardTrigger = softKeyboardTrigger,
                        touchMode = touchMode,
                        onToggleTouchMode = toggleTouchMode,
                        onRequestKeyboard = requestKeyboard,
                        onRestartRuntime = onRestartRuntime,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Tiny sleek semi-transparent floating menu pill at top-end
                FloatingDesktopMenuPill(
                    runtimeState = runtimeState,
                    menuExpanded = menuExpanded,
                    onClick = { menuExpanded = !menuExpanded },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .then(if (!fullscreenEnabled) Modifier.statusBarsPadding() else Modifier)
                        .padding(8.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    CompactSecondaryTopBar(
                        selectedTab = selectedTab,
                        runtimeState = runtimeState,
                        onBackToDesktop = {
                            menuExpanded = false
                            selectedTab = 0
                        },
                        onSelectTab = { tab ->
                            menuExpanded = false
                            selectedTab = tab
                        },
                        onToggleMenu = { menuExpanded = !menuExpanded }
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        when (selectedTab) {
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

            AnimatedVisibility(
                visible = menuExpanded,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { menuExpanded = false }
                        )
                )
            }

            AnimatedVisibility(
                visible = menuExpanded,
                enter = fadeIn(tween(150)) + scaleIn(
                    initialScale = 0.94f,
                    transformOrigin = TransformOrigin(1f, 0f),
                    animationSpec = tween(150)
                ),
                exit = fadeOut(tween(120)) + scaleOut(
                    targetScale = 0.94f,
                    transformOrigin = TransformOrigin(1f, 0f),
                    animationSpec = tween(120)
                ),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .then(if (!isDesktopImmersive) Modifier.statusBarsPadding() else Modifier)
                    .padding(top = 48.dp, end = 8.dp, start = 16.dp, bottom = 16.dp)
            ) {
                FloatingControlDrawer(
                    runtimeState = runtimeState,
                    connectionState = connectionState,
                    latestMetrics = latestMetrics,
                    selectedTab = selectedTab,
                    touchMode = touchMode,
                    fullscreenEnabled = fullscreenEnabled,
                    onSelectTab = { tab ->
                        selectedTab = tab
                        menuExpanded = false
                    },
                    onRequestKeyboard = requestKeyboard,
                    onToggleTouchMode = toggleTouchMode,
                    onRightClick = {
                        menuExpanded = false
                        sendRightClickCenter()
                    },
                    onLaunchStore = { launchAppAndSwitchToDesktop("pocketlinux-store.py") },
                    onLaunchSettings = { launchAppAndSwitchToDesktop("pocketlinux-settings.py") },
                    onToggleFullscreen = { fullscreenEnabled = !fullscreenEnabled },
                    onReconnect = {
                        activeRfbController.connect(
                            RfbConstants.DEFAULT_HOST,
                            RfbConstants.DEFAULT_PORT
                        )
                        menuExpanded = false
                    },
                    onRestartRuntime = {
                        onRestartRuntime()
                        menuExpanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun runtimeStatusColor(runtimeState: RuntimeState): Color = when (runtimeState) {
    is RuntimeState.Ready -> PocketLinuxColors.Emerald
    is RuntimeState.Error -> PocketLinuxColors.Rose
    is RuntimeState.Idle -> PocketLinuxColors.TextSecondary
    else -> PocketLinuxColors.Amber
}

@Composable
private fun runtimeStatusDotAlpha(runtimeState: RuntimeState): Float {
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
    return if (isAnimated) pulseAlpha else 1f
}

@Composable
private fun FloatingDesktopMenuPill(
    runtimeState: RuntimeState,
    menuExpanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColor = runtimeStatusColor(runtimeState)
    val dotAlpha = runtimeStatusDotAlpha(runtimeState)

    Surface(
        color = PocketLinuxColors.Surface.copy(alpha = if (menuExpanded) 0.94f else 0.70f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (menuExpanded) {
                PocketLinuxColors.Cyan.copy(alpha = 0.75f)
            } else {
                PocketLinuxColors.Border.copy(alpha = 0.8f)
            }
        ),
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .testTag("floating_menu_pill")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .alpha(dotAlpha)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Text(
                text = if (menuExpanded) "✕" else "☰",
                color = PocketLinuxColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CompactSecondaryTopBar(
    selectedTab: Int,
    runtimeState: RuntimeState,
    onBackToDesktop: () -> Unit,
    onSelectTab: (Int) -> Unit,
    onToggleMenu: () -> Unit
) {
    val statusColor = runtimeStatusColor(runtimeState)
    val dotAlpha = runtimeStatusDotAlpha(runtimeState)
    val title = if (selectedTab == 1) ">_ Terminal" else "⊞ Apps & System"
    val otherTabIndex = if (selectedTab == 1) 2 else 1
    val otherTabLabel = if (selectedTab == 1) "Apps" else "Terminal"

    Surface(
        color = PocketLinuxColors.Surface,
        border = BorderStroke(1.dp, PocketLinuxColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                color = PocketLinuxColors.CyanContainer,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, PocketLinuxColors.Cyan.copy(alpha = 0.55f)),
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onBackToDesktop)
                    .testTag("back_to_desktop_btn")
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "← Desktop",
                        color = PocketLinuxColors.Cyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }

            Text(
                text = title,
                color = PocketLinuxColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    color = PocketLinuxColors.SurfaceElevated,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, PocketLinuxColors.Border),
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelectTab(otherTabIndex) }
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = otherTabLabel,
                            color = PocketLinuxColors.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }

                Surface(
                    color = PocketLinuxColors.SurfaceElevated,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, PocketLinuxColors.Border),
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onToggleMenu)
                        .testTag("floating_menu_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .alpha(dotAlpha)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Text(
                            text = "☰",
                            color = PocketLinuxColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FloatingControlDrawer(
    runtimeState: RuntimeState,
    connectionState: RfbConnectionState,
    latestMetrics: SystemMetrics?,
    selectedTab: Int,
    touchMode: TouchInputMode,
    fullscreenEnabled: Boolean,
    onSelectTab: (Int) -> Unit,
    onRequestKeyboard: () -> Unit,
    onToggleTouchMode: () -> Unit,
    onRightClick: () -> Unit,
    onLaunchStore: () -> Unit,
    onLaunchSettings: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onReconnect: () -> Unit,
    onRestartRuntime: () -> Unit
) {
    val statusText = when (runtimeState) {
        is RuntimeState.Idle -> "Linux: Idle"
        is RuntimeState.Extracting -> "Linux: Extracting"
        is RuntimeState.Booting -> "Linux: Booting"
        is RuntimeState.Ready -> "Linux: Ready"
        is RuntimeState.Error -> "Linux: Error"
    }
    val statusColor = runtimeStatusColor(runtimeState)
    val startupMs = when (runtimeState) {
        is RuntimeState.Ready -> runtimeState.startupDurationMs.coerceAtLeast(1L)
        else -> latestMetrics?.startupDurationMs ?: 0L
    }
    val pssKb = (latestMetrics?.androidPssKb ?: 24576).coerceAtLeast(1)
    val pssMb = (pssKb / 1024).coerceAtLeast(1)

    Surface(
        color = PocketLinuxColors.Surface.copy(alpha = 0.96f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, PocketLinuxColors.Border),
        shadowElevation = 12.dp,
        modifier = Modifier
            .widthIn(min = 270.dp, max = 330.dp)
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Telemetry header pills
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "${startupMs} ms",
                        color = PocketLinuxColors.TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testTag("startup_time_badge")
                    )
                    Text(
                        text = "${pssMb} MB",
                        color = PocketLinuxColors.Cyan,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testTag("memory_usage_badge")
                    )
                }
            }

            // Tab Switcher
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DrawerChip(
                    label = "▣ Desktop",
                    active = selectedTab == 0,
                    tag = "tab_desktop",
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectTab(0) }
                )
                DrawerChip(
                    label = ">_ Term",
                    active = selectedTab == 1,
                    tag = "tab_terminal",
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectTab(1) }
                )
                DrawerChip(
                    label = "⊞ Apps",
                    active = selectedTab == 2,
                    tag = "tab_apps",
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectTab(2) }
                )
            }

            HorizontalDivider(color = PocketLinuxColors.BorderSubtle)

            // Quick Controls
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DrawerChip(
                    label = "⌨ Keyboard",
                    active = true,
                    tag = "desktop_keyboard_btn",
                    onClick = onRequestKeyboard
                )
                DrawerChip(
                    label = if (touchMode == TouchInputMode.DIRECT_TAP) "Mode: Tap" else "Mode: Trackpad",
                    active = touchMode == TouchInputMode.TRACKPAD,
                    onClick = onToggleTouchMode
                )
                DrawerChip(
                    label = "Right Click",
                    onClick = onRightClick
                )
                DrawerChip(
                    label = "🛒 Store",
                    onClick = onLaunchStore
                )
                DrawerChip(
                    label = "⚙ GPU & Settings",
                    onClick = onLaunchSettings
                )
                DrawerChip(
                    label = if (fullscreenEnabled) "Fullscreen: ON" else "Fullscreen: OFF",
                    active = fullscreenEnabled,
                    onClick = onToggleFullscreen
                )
                DrawerChip(
                    label = "Reconnect VNC",
                    onClick = onReconnect
                )
                DrawerChip(
                    label = "Restart Linux",
                    onClick = onRestartRuntime
                )
            }

            val connText = when (connectionState) {
                is RfbConnectionState.Connected ->
                    "VNC ${connectionState.width}×${connectionState.height} · ${connectionState.serverName}"
                is RfbConnectionState.Connecting -> "Connecting to 127.0.0.1:5901..."
                is RfbConnectionState.Disconnected -> "VNC Disconnected"
                is RfbConnectionState.Error -> "VNC Error: ${connectionState.message}"
            }
            Text(
                text = connText,
                style = MaterialTheme.typography.labelSmall,
                color = PocketLinuxColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DrawerChip(
    label: String,
    active: Boolean = false,
    tag: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = if (active) PocketLinuxColors.CyanContainer else PocketLinuxColors.SurfaceElevated
    val border = if (active) PocketLinuxColors.Cyan.copy(alpha = 0.7f) else PocketLinuxColors.Border
    val fg = if (active) PocketLinuxColors.Cyan else PocketLinuxColors.TextPrimary
    val mod = modifier
        .height(34.dp)
        .clip(RoundedCornerShape(10.dp))
        .clickable(onClick = onClick)
        .let { base -> if (tag != null) base.testTag(tag) else base }

    Surface(
        color = bg,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, border),
        modifier = mod
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = fg,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
