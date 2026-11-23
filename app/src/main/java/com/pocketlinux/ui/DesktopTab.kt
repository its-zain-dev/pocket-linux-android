package com.pocketlinux.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketlinux.rfb.KeySymMapper
import com.pocketlinux.rfb.LinuxDesktopSurface
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
    softKeyboardTrigger: Int = 0,
    touchMode: TouchInputMode = TouchInputMode.DIRECT_TAP,
    onToggleTouchMode: () -> Unit = {},
    onRequestKeyboard: () -> Unit = {},
    onRestartRuntime: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var ctrlLatched by remember { mutableStateOf(false) }
    var altLatched by remember { mutableStateOf(false) }
    var hideKeyboardTrigger by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val density = LocalDensity.current
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0

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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketLinuxColors.Obsidian)
    ) {
        // 100% pure fullscreen Linux desktop surface
        LinuxDesktopSurface(
            controller = rfbController,
            touchInputMode = touchMode,
            softKeyboardTrigger = softKeyboardTrigger,
            hideKeyboardTrigger = hideKeyboardTrigger,
            autoResizeDesktop = true,
            modifier = Modifier.fillMaxSize()
        )

        if (runtimeState !is RuntimeState.Ready) {
            RuntimeStatusOverlay(
                runtimeState = runtimeState,
                onRestartRuntime = onRestartRuntime
            )
        }

        // Smart Automatic Control Bar: ONLY visible while the Android soft keyboard is open
        AnimatedVisibility(
            visible = isKeyboardVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
        ) {
            Surface(
                color = PocketLinuxColors.Surface.copy(alpha = 0.94f),
                border = BorderStroke(1.dp, PocketLinuxColors.Border),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("keyboard_control_bar")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ImeControlKey("Esc") { tapKeySym(KeySymMapper.XK_ESCAPE) }
                    ImeControlKey("Tab") { tapKeySym(KeySymMapper.XK_TAB) }
                    ImeControlKey("Ctrl", active = ctrlLatched) { ctrlLatched = !ctrlLatched }
                    ImeControlKey("Alt", active = altLatched) { altLatched = !altLatched }
                    ImeControlKey("Super") { tapKeySym(KeySymMapper.XK_SUPER_L) }
                    ImeControlKey("←") { tapKeySym(KeySymMapper.XK_LEFT) }
                    ImeControlKey("↑") { tapKeySym(KeySymMapper.XK_UP) }
                    ImeControlKey("↓") { tapKeySym(KeySymMapper.XK_DOWN) }
                    ImeControlKey("→") { tapKeySym(KeySymMapper.XK_RIGHT) }
                    ImeControlKey(
                        label = if (touchMode == TouchInputMode.DIRECT_TAP) "Tap" else "Trackpad",
                        active = touchMode == TouchInputMode.TRACKPAD
                    ) { onToggleTouchMode() }
                    ImeControlKey("R-Click") { sendRightClickCenter() }
                    ImeControlKey("✕ Hide", active = true) { hideKeyboardTrigger++ }
                }
            }
        }
    }
}

@Composable
private fun ImeControlKey(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val bg = if (active) PocketLinuxColors.CyanContainer else PocketLinuxColors.SurfaceElevated
    val border = if (active) PocketLinuxColors.Cyan.copy(alpha = 0.75f) else PocketLinuxColors.Border
    val fg = if (active) PocketLinuxColors.Cyan else PocketLinuxColors.TextPrimary

    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, border),
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = fg,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
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
                .fillMaxWidth(0.84f)
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
                            text = "Setting up PocketLinux v2.0",
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
                            text = "Starting Fullscreen X11 Desktop...",
                            style = MaterialTheme.typography.titleMedium,
                            color = PocketLinuxColors.TextPrimary
                        )
                        Text(
                            text = "Initializing VirGL GPU, TigerVNC, JWM & PocketStore",
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
