package com.pocketlinux.ui

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketlinux.runtime.RuntimeState

@Composable
fun TerminalTab(
    terminalSession: TerminalSessionController?,
    runtimeState: RuntimeState = RuntimeState.Ready("127.0.0.1", 5901, 1L, 1L),
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    var ctrlArmed by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val scrollState = rememberScrollState()

    val rawOutput by (terminalSession?.outputText?.collectAsState()
        ?: remember { mutableStateOf("") })

    LaunchedEffect(terminalSession, runtimeState) {
        if (runtimeState is RuntimeState.Ready || runtimeState is RuntimeState.Booting) {
            terminalSession?.ensureSessionOpen()
        }
    }

    LaunchedEffect(rawOutput) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    fun focusAndOpenKeyboard() {
        runCatching { focusRequester.requestFocus() }
        keyboardController?.show()
    }

    fun submitCurrentCommand() {
        val cmd = inputText
        inputText = ""
        ctrlArmed = false
        terminalSession?.sendCommand(cmd)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketLinuxColors.Obsidian)
            .imePadding()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .onPreviewKeyEvent { keyEvent ->
                val native = keyEvent.nativeKeyEvent
                if (native.action == KeyEvent.ACTION_DOWN) {
                    if (native.keyCode == KeyEvent.KEYCODE_BREAK ||
                        (native.isCtrlPressed && native.keyCode == KeyEvent.KEYCODE_C)
                    ) {
                        ctrlArmed = false
                        terminalSession?.sendControlChar('C')
                        return@onPreviewKeyEvent true
                    }
                    if (ctrlArmed && native.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                        val ch = ('A'.code + (native.keyCode - KeyEvent.KEYCODE_A)).toChar()
                        ctrlArmed = false
                        terminalSession?.sendControlChar(ch)
                        return@onPreviewKeyEvent true
                    }
                    if (native.keyCode == KeyEvent.KEYCODE_ENTER ||
                        native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    ) {
                        submitCurrentCommand()
                        return@onPreviewKeyEvent true
                    }
                }
                false
            },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Terminal header strip with session status, Keyboard trigger, Clear, and Restart Shell
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (terminalSession != null) PocketLinuxColors.Emerald
                            else PocketLinuxColors.Amber
                        )
                )
                Text(
                    text = "Alpine PTY · /bin/bash -l",
                    color = PocketLinuxColors.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TerminalHeaderChip(label = "⌨ Keyboard") {
                    focusAndOpenKeyboard()
                }
                TerminalHeaderChip(label = "Clear") {
                    terminalSession?.sendCommand("clear")
                }
                TerminalHeaderChip(label = "Restart") {
                    terminalSession?.closeCurrentSession()
                    terminalSession?.ensureSessionOpen()
                }
            }
        }

        // Scrollable VT100 output viewport — tapping ANYWHERE opens the keyboard immediately
        Surface(
            color = PocketLinuxColors.DeepConsole,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, PocketLinuxColors.BorderSubtle),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { focusAndOpenKeyboard() }
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(10.dp)
            ) {
                Text(
                    text = rawOutput.ifBlank {
                        "PocketLinux Interactive PTY (/bin/bash -l)\nTap anywhere to open keyboard...\n/root # "
                    },
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = PocketLinuxColors.Cyan
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("terminal_output")
                )
            }
        }

        // Extra-keys toolbar above the command input bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExtraKeyButton(
                label = "Ctrl",
                active = ctrlArmed,
                onClick = {
                    ctrlArmed = !ctrlArmed
                    focusAndOpenKeyboard()
                }
            )
            ExtraKeyButton(label = "Tab") {
                terminalSession?.sendRawBytes(byteArrayOf('\t'.code.toByte()))
            }
            ExtraKeyButton(label = "Esc") {
                terminalSession?.sendRawBytes(byteArrayOf(0x1B))
            }
            ExtraKeyButton(label = "↑") {
                terminalSession?.sendRawBytes("\u001B[A".toByteArray(Charsets.UTF_8))
            }
            ExtraKeyButton(label = "↓") {
                terminalSession?.sendRawBytes("\u001B[B".toByteArray(Charsets.UTF_8))
            }
            ExtraKeyButton(label = "←") {
                terminalSession?.sendRawBytes("\u001B[D".toByteArray(Charsets.UTF_8))
            }
            ExtraKeyButton(label = "→") {
                terminalSession?.sendRawBytes("\u001B[C".toByteArray(Charsets.UTF_8))
            }
            ExtraKeyButton(label = "Ctrl+C") {
                ctrlArmed = false
                terminalSession?.sendControlChar('C')
            }
            ExtraKeyButton(label = "Ctrl+D") {
                ctrlArmed = false
                terminalSession?.sendControlChar('D')
            }
            ExtraKeyButton(label = "/") { inputText += "/" }
            ExtraKeyButton(label = "-") { inputText += "-" }
            ExtraKeyButton(label = "|") { inputText += "|" }
        }

        // Command input + Send button anchored right above the keyboard
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { newValue ->
                    if (ctrlArmed && newValue.length == inputText.length + 1) {
                        val addedChar = newValue.last()
                        if (addedChar.isLetter()) {
                            ctrlArmed = false
                            terminalSession?.sendControlChar(addedChar)
                            return@OutlinedTextField
                        }
                    }
                    if (newValue.contains('\n')) {
                        val clean = newValue.replace("\r", "").replace("\n", "")
                        inputText = ""
                        ctrlArmed = false
                        terminalSession?.sendCommand(clean)
                    } else {
                        inputText = newValue
                    }
                },
                placeholder = {
                    Text(
                        "Type Linux command (e.g. ls -la, apk update)...",
                        fontSize = 12.sp,
                        color = PocketLinuxColors.TextMuted
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PocketLinuxColors.Cyan,
                    unfocusedBorderColor = PocketLinuxColors.Border,
                    focusedContainerColor = PocketLinuxColors.Surface,
                    unfocusedContainerColor = PocketLinuxColors.Surface,
                    cursorColor = PocketLinuxColors.Cyan
                ),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = PocketLinuxColors.TextPrimary
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submitCurrentCommand() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .testTag("terminal_input")
            )

            Button(
                onClick = { submitCurrentCommand() },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PocketLinuxColors.Cyan,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier
                    .height(50.dp)
                    .testTag("terminal_send_btn")
            ) {
                Text("Send", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TerminalHeaderChip(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        color = PocketLinuxColors.SurfaceElevated,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, PocketLinuxColors.Border),
        modifier = Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = PocketLinuxColors.TextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ExtraKeyButton(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val bg = if (active) PocketLinuxColors.CyanContainer else PocketLinuxColors.Surface
    val border = if (active) PocketLinuxColors.Cyan else PocketLinuxColors.Border
    val fg = if (active) PocketLinuxColors.Cyan else PocketLinuxColors.TextPrimary

    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, border),
        modifier = Modifier
            .height(30.dp)
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
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
