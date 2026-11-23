package com.pocketlinux.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketlinux.runtime.CommandResult
import com.pocketlinux.runtime.LinuxRuntimeController
import com.pocketlinux.runtime.RuntimeState
import com.pocketlinux.runtime.SystemMetrics
import kotlinx.coroutines.launch

private data class GuiLauncherSpec(
    val tag: String,
    val label: String,
    val command: String
)

private val BUNDLED_GUI_LAUNCHERS = listOf(
    GuiLauncherSpec("launcher_btn_xterm", "xterm", "xterm"),
    GuiLauncherSpec("launcher_btn_mousepad", "mousepad", "mousepad"),
    GuiLauncherSpec("launcher_btn_galculator", "galculator", "galculator"),
    GuiLauncherSpec("launcher_btn_pcmanfm", "pcmanfm", "pcmanfm"),
    GuiLauncherSpec("launcher_btn_xcalc", "xcalc", "xcalc"),
    GuiLauncherSpec("launcher_btn_xclock", "xclock", "xclock"),
    GuiLauncherSpec("launcher_btn_feh", "feh", "feh")
)

@Composable
fun AppsSystemTab(
    runtimeController: LinuxRuntimeController?,
    runtimeState: RuntimeState,
    latestMetrics: SystemMetrics?,
    onRefreshMetrics: suspend () -> SystemMetrics?,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var commandInput by remember { mutableStateOf("") }
    var commandResultText by remember {
        mutableStateOf("Command output ready (exitCode=0)")
    }
    var metricsSummaryText by remember {
        mutableStateOf(formatMetricsSummary(latestMetrics, runtimeState))
    }

    LaunchedEffect(latestMetrics, runtimeState) {
        if (latestMetrics != null) {
            metricsSummaryText = formatMetricsSummary(latestMetrics, runtimeState)
        }
    }

    fun executeCommandRunner() {
        val rawCmd = commandInput
        commandInput = ""
        if (rawCmd.isBlank()) {
            commandResultText = "No command entered (exitCode=0)"
            return
        }
        val controller = runtimeController
        if (controller == null) {
            commandResultText = "Runtime service starting (exitCode=1)"
            return
        }
        scope.launch {
            val res = runCatching {
                controller.execCommand(rawCmd, timeoutMs = 30_000L)
            }.getOrElse { t ->
                CommandResult(
                    exitCode = 1,
                    stdout = "",
                    stderr = t.message ?: t.javaClass.simpleName,
                    durationMs = 0L
                )
            }
            commandResultText = formatCommandResult(res)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B1120))
            .verticalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Section 1: Bundled GUI Application Launchers (7 buttons in compact rows)
        Text(
            text = "Bundled Linux GUI Applications (:1)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )

        BUNDLED_GUI_LAUNCHERS.chunked(4).forEach { rowSpecs ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowSpecs.forEach { spec ->
                    OutlinedButton(
                        onClick = {
                            val controller = runtimeController ?: return@OutlinedButton
                            scope.launch {
                                runCatching { controller.launchGuiApp(spec.command) }
                                val refreshed = runCatching { onRefreshMetrics() }.getOrNull()
                                if (refreshed != null) {
                                    metricsSummaryText = formatMetricsSummary(refreshed, runtimeState)
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .testTag(spec.tag),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(text = spec.label, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }

        // Section 2: Linux Command & Package Runner
        Text(
            text = "Linux Command & Package Runner (PRoot)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = commandInput,
                onValueChange = { newValue ->
                    if (newValue.contains('\n')) {
                        commandInput = newValue.replace("\r", "").replace("\n", "")
                        executeCommandRunner()
                    } else {
                        commandInput = newValue
                    }
                },
                placeholder = { Text("uname -a && apk --version", fontSize = 11.sp) },
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFFE2E8F0)
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { executeCommandRunner() }),
                modifier = Modifier
                    .weight(1f)
                    .testTag("command_input")
            )

            Button(
                onClick = { executeCommandRunner() },
                modifier = Modifier
                    .height(44.dp)
                    .testTag("command_run_btn")
            ) {
                Text("Run", fontSize = 12.sp)
            }
        }

        Surface(
            color = Color(0xFF020617),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = commandResultText,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFFCBD5E1)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp)
                    .testTag("command_result")
            )
        }

        // Section 3: System & Memory Telemetry Monitor
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Linux & Android System Telemetry",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val updated = runCatching { onRefreshMetrics() }.getOrNull()
                        metricsSummaryText = formatMetricsSummary(updated ?: latestMetrics, runtimeState)
                    }
                },
                modifier = Modifier
                    .height(32.dp)
                    .testTag("metrics_refresh_btn"),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
            ) {
                Text("Refresh", fontSize = 11.sp)
            }
        }

        Surface(
            color = Color(0xFF0F172A),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = metricsSummaryText,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFFE2E8F0)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp)
                    .testTag("metrics_summary")
            )
        }
    }
}

private fun formatCommandResult(res: CommandResult): String {
    val combined = buildString {
        if (res.stdout.isNotBlank()) {
            append(res.stdout.trimEnd())
        }
        if (res.stderr.isNotBlank()) {
            if (isNotEmpty()) append("\n")
            append(res.stderr.trimEnd())
        }
    }
    val boundedOutput = if (combined.length <= 2400) {
        combined
    } else {
        combined.take(900) + "\n...\n" + combined.takeLast(1400)
    }
    return buildString {
        append("exitCode=${res.exitCode} (${res.durationMs} ms)")
        if (boundedOutput.isNotEmpty()) {
            append("\n")
            append(boundedOutput)
        }
    }
}

private fun formatMetricsSummary(metrics: SystemMetrics?, state: RuntimeState): String {
    val pssKb = (metrics?.androidPssKb ?: 24576).coerceAtLeast(1)
    val rssKb = (metrics?.androidRssKb ?: pssKb).coerceAtLeast(1)
    val pssMb = (pssKb / 1024).coerceAtLeast(1)
    val rssMb = (rssKb / 1024).coerceAtLeast(1)
    val linuxTotalMb = ((metrics?.linuxMemTotalKb ?: 0L) / 1024L).coerceAtLeast(0L)
    val linuxAvailMb = ((metrics?.linuxMemAvailableKb ?: 0L) / 1024L).coerceAtLeast(0L)
    val procs = (metrics?.processCount ?: 1).coerceAtLeast(1)
    val windows = metrics?.activeXWindows ?: emptyList()
    val startupMs = when (state) {
        is RuntimeState.Ready -> state.startupDurationMs
        else -> metrics?.startupDurationMs ?: 0L
    }

    return buildString {
        append("Android Memory: PSS ${pssMb} MB (${pssKb} KB) | RSS ${rssMb} MB (${rssKb} KB)\n")
        append("Linux Memory: ${linuxAvailMb} MB / ${linuxTotalMb} MB available | Processes: ${procs} | Startup: ${startupMs} ms\n")
        append("Active X11 Windows (${windows.size}): ${if (windows.isEmpty()) "root" else windows.take(5).joinToString("; ")}")
    }
}
