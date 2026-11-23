package com.pocketlinux.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TerminalTab(
    terminalSession: TerminalSessionController?,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    var ctrlArmed by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    val rawOutput by (terminalSession?.outputText?.collectAsState()
        ?: remember { mutableStateOf("") })

    LaunchedEffect(terminalSession) {
        terminalSession?.ensureSessionOpen()
    }

    LaunchedEffect(rawOutput) {
        scrollState.scrollTo(scrollState.maxValue)
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
            .background(Color(0xFF0B1120))
            .padding(8.dp)
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
        // Command input + Send button placed at top so it is always visible & accessible
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
                placeholder = { Text("Enter command...", fontSize = 12.sp) },
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = Color(0xFFE2E8F0)
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
                modifier = Modifier
                    .height(48.dp)
                    .testTag("terminal_send_btn")
            ) {
                Text("Send", fontSize = 13.sp)
            }
        }

        // Extra-keys toolbar (Ctrl, Tab, Esc, Up, Down, Ctrl+C)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExtraKeyButton(
                label = "Ctrl",
                active = ctrlArmed,
                onClick = {
                    ctrlArmed = !ctrlArmed
                    runCatching { focusRequester.requestFocus() }
                }
            )
            ExtraKeyButton(
                label = "Tab",
                onClick = {
                    terminalSession?.sendRawBytes(byteArrayOf('\t'.code.toByte()))
                }
            )
            ExtraKeyButton(
                label = "Esc",
                onClick = {
                    terminalSession?.sendRawBytes(byteArrayOf(0x1B))
                }
            )
            ExtraKeyButton(
                label = "Up",
                onClick = {
                    terminalSession?.sendRawBytes("\u001B[A".toByteArray(Charsets.UTF_8))
                }
            )
            ExtraKeyButton(
                label = "Down",
                onClick = {
                    terminalSession?.sendRawBytes("\u001B[B".toByteArray(Charsets.UTF_8))
                }
            )
            ExtraKeyButton(
                label = "Ctrl+C",
                onClick = {
                    ctrlArmed = false
                    terminalSession?.sendControlChar('C')
                }
            )
            ExtraKeyButton(
                label = "Ctrl+D",
                onClick = {
                    ctrlArmed = false
                    terminalSession?.sendControlChar('D')
                }
            )
        }

        // Interactive PTY VT100 output viewport
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF020617))
                .verticalScroll(scrollState)
                .padding(8.dp)
        ) {
            Text(
                text = rawOutput.ifBlank { "PocketLinux Interactive PTY (/bin/bash -l)\n/root # " },
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFF38BDF8)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("terminal_output")
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
        modifier = Modifier.height(30.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Text(text = label, fontSize = 11.sp)
    }
}
