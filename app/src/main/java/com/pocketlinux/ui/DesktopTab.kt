package com.pocketlinux.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketlinux.rfb.KeySymMapper
import com.pocketlinux.rfb.LinuxDesktopSurface
import com.pocketlinux.rfb.RfbConnectionState
import com.pocketlinux.rfb.RfbConstants
import com.pocketlinux.rfb.RfbController
import com.pocketlinux.rfb.TouchInputMode
import com.pocketlinux.runtime.RuntimeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DesktopTab(
    rfbController: RfbController,
    runtimeState: RuntimeState = RuntimeState.Ready("127.0.0.1", 5901, 1L, 1L),
    onLaunchApp: (String) -> Unit = {},
    onOpenAppsTab: () -> Unit = {},
    onRestartRuntime: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var touchMode by remember { mutableStateOf(TouchInputMode.DIRECT_TAP) }
    var ctrlLatched by remember { mutableStateOf(false) }
    var altLatched by remember { mutableStateOf(false) }
    var showSpecialKeys by remember { mutableStateOf(false) }
    var softKeyboardTrigger by remember { mutableIntStateOf(0) }
    val connectionState by rfbController.connectionState.collectAsState()
    val scope = rememberCoroutineScope()

    fun tapKeySym(keySym: Int) {
        if (ctrlLatched) rfbController.sendKeyEvent(KeySymMapper.XK_CONTROL_L, true)
        if (altLatched) rfbController.sendKeyEvent(KeySymMapper.XK_ALT_L, true)
        rfbController.sendKeyEvent(keySym, true)
        rfbController.sendKeyEvent(keySym, false)
        if (altLatched) {
            rfbController.sendKeyEvent(KeySymMapper.XK_ALT_L, false)
            altLatched = false
        }
        if (ctrlLatched) {
            rfbController.sendKeyEvent(KeySymMapper.XK_CONTROL_L, false)
            ctrlLatched = false
        }
    }

    fun sendRightClickCenter() {
        val cx = (rfbController.desktopWidth / 2).coerceAtLeast(1)
        val cy = (rfbController.desktopHeight / 2).coerceAtLeast(1)
        scope.launch {
            rfbController.sendPointerEvent(cx, cy, RfbConstants.BUTTON_RIGHT)
            delay(45L)
            rfbController.sendPointerEvent(cx, cy, 0)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketLinuxColors.Obsidian)
            .imePadding()
    ) {
        // Quick App Dock right above the desktop so apps are 1 tap away (no right-click required)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DockAppChip(glyph = ">_", label = "Terminal", accent = PocketLinuxColors.Cyan) {
                onLaunchApp("xterm")
            }
            DockAppChip(glyph = "▤", label = "Files", accent = PocketLinuxColors.Amber) {
                onLaunchApp("pcmanfm")
            }
            DockAppChip(glyph = "✎", label = "Editor", accent = PocketLinuxColors.Teal) {
                onLaunchApp("mousepad")
            }
            DockAppChip(glyph = "±", label = "Calc", accent = PocketLinuxColors.Indigo) {
                onLaunchApp("galculator")
            }
            DockAppChip(glyph = "⊞", label = "All Apps", accent = PocketLinuxColors.Emerald) {
                onOpenAppsTab()
            }
        }

        // Full-space live X11 desktop canvas with boot/error overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(PocketLinuxColors.DeepConsole)
        ) {
            LinuxDesktopSurface(
                controller = rfbController,
                touchInputMode = touchMode,
                softKeyboardTrigger = softKeyboardTrigger,
                autoResizeDesktop = true,
                modifier = Modifier.fillMaxSize()
            )

            if (runtimeState !is RuntimeState.Ready) {
                RuntimeStatusOverlay(
                    runtimeState = runtimeState,
                    onRestartRuntime = onRestartRuntime
                )
            }
        }

        // Primary Desktop Control Deck: Keyboard, Mode, Right-Click, Keys drawer, Reconnect
        Surface(
            color = PocketLinuxColors.Surface,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            border = BorderStroke(1.dp, PocketLinuxColors.BorderSubtle),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DesktopActionPill(
                        label = "⌨ Keyboard",
                        active = true,
                        testTagName = "desktop_keyboard_btn",
                        onClick = { softKeyboardTrigger++ }
                    )
                    DesktopActionPill(
                        label = if (touchMode == TouchInputMode.DIRECT_TAP) "Mode: Tap" else "Mode: Trackpad",
                        active = touchMode == TouchInputMode.TRACKPAD,
                        onClick = {
                            touchMode = if (touchMode == TouchInputMode.DIRECT_TAP) {
                                TouchInputMode.TRACKPAD
                            } else {
                                TouchInputMode.DIRECT_TAP
                            }
                        }
                    )
                    DesktopActionPill(
                        label = if (showSpecialKeys) "Keys ▲" else "Keys ▼",
                        active = showSpecialKeys || ctrlLatched || altLatched,
                        onClick = { showSpecialKeys = !showSpecialKeys }
                    )
                    DesktopActionPill(
                        label = "Right Click",
                        onClick = { sendRightClickCenter() }
                    )
                    DesktopActionPill(
                        label = "Reconnect",
                        onClick = { rfbController.connect("127.0.0.1", 5901) }
                    )
                }

                AnimatedVisibility(visible = showSpecialKeys) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DesktopActionPill(label = "Esc", onClick = { tapKeySym(KeySymMapper.XK_ESCAPE) })
                        DesktopActionPill(label = "Tab", onClick = { tapKeySym(KeySymMapper.XK_TAB) })
                        DesktopActionPill(
                            label = "Ctrl",
                            active = ctrlLatched,
                            onClick = { ctrlLatched = !ctrlLatched }
                        )
                        DesktopActionPill(
                            label = "Alt",
                            active = altLatched,
                            onClick = { altLatched = !altLatched }
                        )
                        DesktopActionPill(label = "Super", onClick = { tapKeySym(KeySymMapper.XK_SUPER_L) })
                        DesktopActionPill(label = "←", onClick = { tapKeySym(KeySymMapper.XK_LEFT) })
                        DesktopActionPill(label = "↑", onClick = { tapKeySym(KeySymMapper.XK_UP) })
                        DesktopActionPill(label = "↓", onClick = { tapKeySym(KeySymMapper.XK_DOWN) })
                        DesktopActionPill(label = "→", onClick = { tapKeySym(KeySymMapper.XK_RIGHT) })
                    }
                }

                val (dotColor, statusLabel) = when (val st = connectionState) {
                    is RfbConnectionState.Connected ->
                        PocketLinuxColors.Emerald to "RFB Connected · ${st.width}×${st.height} (${st.serverName})"
                    is RfbConnectionState.Connecting ->
                        PocketLinuxColors.Amber to "Connecting to Xvnc (127.0.0.1:5901)..."
                    is RfbConnectionState.Disconnected ->
                        PocketLinuxColors.TextMuted to "RFB Disconnected"
                    is RfbConnectionState.Error ->
                        PocketLinuxColors.Rose to "RFB Error: ${st.message}"
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = PocketLinuxColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeStatusOverlay(
    runtimeState: RuntimeState,
    onRestartRuntime: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PocketLinuxColors.Obsidian.copy(alpha = 0.88f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = PocketLinuxColors.Surface,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, PocketLinuxColors.Border),
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(0.88f)
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (runtimeState) {
                    is RuntimeState.Extracting -> {
                        CircularProgressIndicator(
                            color = PocketLinuxColors.Cyan,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(38.dp)
                        )
                        Text(
                            text = "Setting up Alpine Linux 3.20",
                            style = MaterialTheme.typography.titleMedium,
                            color = PocketLinuxColors.TextPrimary
                        )
                        Text(
                            text = runtimeState.progressLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = PocketLinuxColors.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                    is RuntimeState.Booting -> {
                        CircularProgressIndicator(
                            color = PocketLinuxColors.Amber,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(38.dp)
                        )
                        Text(
                            text = "Starting X11 Desktop & JWM...",
                            style = MaterialTheme.typography.titleMedium,
                            color = PocketLinuxColors.TextPrimary
                        )
                        Text(
                            text = "Launching TigerVNC server and desktop icons inside PRoot",
                            style = MaterialTheme.typography.bodySmall,
                            color = PocketLinuxColors.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                    is RuntimeState.Error -> {
                        Text(
                            text = "Linux Runtime Error",
                            style = MaterialTheme.typography.titleMedium,
                            color = PocketLinuxColors.Rose
                        )
                        Text(
                            text = runtimeState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = PocketLinuxColors.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = onRestartRuntime,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PocketLinuxColors.Cyan,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text("Retry Boot")
                        }
                    }
                    is RuntimeState.Idle -> {
                        Text(
                            text = "PocketLinux Stopped",
                            style = MaterialTheme.typography.titleMedium,
                            color = PocketLinuxColors.TextPrimary
                        )
                        Button(
                            onClick = onRestartRuntime,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PocketLinuxColors.Cyan,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text("Start Linux Desktop")
                        }
                    }
                    is RuntimeState.Ready -> Unit
                }
            }
        }
    }
}

@Composable
private fun DockAppChip(
    glyph: String,
    label: String,
    accent: Color,
    onClick: () -> Unit
) {
    Surface(
        color = PocketLinuxColors.Surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = glyph,
                color = accent,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                color = PocketLinuxColors.TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DesktopActionPill(
    label: String,
    active: Boolean = false,
    testTagName: String? = null,
    onClick: () -> Unit
) {
    val bg = if (active) PocketLinuxColors.CyanContainer else PocketLinuxColors.SurfaceElevated
    val border = if (active) PocketLinuxColors.Cyan.copy(alpha = 0.7f) else PocketLinuxColors.Border
    val fg = if (active) PocketLinuxColors.Cyan else PocketLinuxColors.TextPrimary
    val mod = Modifier
        .height(34.dp)
        .clip(RoundedCornerShape(10.dp))
        .clickable(onClick = onClick)
        .let { base -> if (testTagName != null) base.testTag(testTagName) else base }

    Surface(
        color = bg,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, border),
        modifier = mod
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = fg,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}
