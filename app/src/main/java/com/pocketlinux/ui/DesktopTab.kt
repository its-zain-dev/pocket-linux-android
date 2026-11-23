package com.pocketlinux.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketlinux.rfb.KeySymMapper
import com.pocketlinux.rfb.LinuxDesktopSurface
import com.pocketlinux.rfb.RfbConnectionState
import com.pocketlinux.rfb.RfbController
import com.pocketlinux.rfb.TouchInputMode

@Composable
fun DesktopTab(
    rfbController: RfbController,
    modifier: Modifier = Modifier
) {
    var touchMode by remember { mutableStateOf(TouchInputMode.DIRECT_TAP) }
    var ctrlLatched by remember { mutableStateOf(false) }
    var altLatched by remember { mutableStateOf(false) }
    val connectionState by rfbController.connectionState.collectAsState()

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B1120))
    ) {
        // Aspect-locked 1024:600 container ensures 1:1 viewport mapping without letterbox bars
        LinuxDesktopSurface(
            controller = rfbController,
            touchInputMode = touchMode,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1024f / 600f)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DesktopKeyButton(
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
            DesktopKeyButton(
                label = "Esc",
                onClick = { tapKeySym(KeySymMapper.XK_ESCAPE) }
            )
            DesktopKeyButton(
                label = "Tab",
                onClick = { tapKeySym(KeySymMapper.XK_TAB) }
            )
            DesktopKeyButton(
                label = "Ctrl",
                active = ctrlLatched,
                onClick = { ctrlLatched = !ctrlLatched }
            )
            DesktopKeyButton(
                label = "Alt",
                active = altLatched,
                onClick = { altLatched = !altLatched }
            )
            DesktopKeyButton(
                label = "Super",
                onClick = { tapKeySym(KeySymMapper.XK_SUPER_L) }
            )
            DesktopKeyButton(
                label = "←",
                onClick = { tapKeySym(KeySymMapper.XK_LEFT) }
            )
            DesktopKeyButton(
                label = "↑",
                onClick = { tapKeySym(KeySymMapper.XK_UP) }
            )
            DesktopKeyButton(
                label = "↓",
                onClick = { tapKeySym(KeySymMapper.XK_DOWN) }
            )
            DesktopKeyButton(
                label = "→",
                onClick = { tapKeySym(KeySymMapper.XK_RIGHT) }
            )
            DesktopKeyButton(
                label = "Reconnect",
                onClick = { rfbController.connect("127.0.0.1", 5901) }
            )
        }

        val statusLabel = when (val st = connectionState) {
            is RfbConnectionState.Connected ->
                "RFB Connected: ${st.width}x${st.height} (${st.serverName})"
            is RfbConnectionState.Connecting ->
                "RFB Connecting to 127.0.0.1:5901..."
            is RfbConnectionState.Disconnected ->
                "RFB Disconnected"
            is RfbConnectionState.Error ->
                "RFB Error: ${st.message}"
        }
        Text(
            text = statusLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun DesktopKeyButton(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val containerColor = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (active) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Text(text = label, fontSize = 11.sp)
    }
}
