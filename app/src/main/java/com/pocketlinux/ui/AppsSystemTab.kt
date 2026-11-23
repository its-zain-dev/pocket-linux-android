package com.pocketlinux.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
    val subtitle: String,
    val glyph: String,
    val accent: Color,
    val command: String
)

private val BUNDLED_GUI_LAUNCHERS = listOf(
    GuiLauncherSpec("launcher_btn_xterm", "xterm", "Terminal", ">_", PocketLinuxColors.Cyan, "xterm"),
    GuiLauncherSpec("launcher_btn_pcmanfm", "pcmanfm", "File Manager", "▤", PocketLinuxColors.Amber, "pcmanfm"),
    GuiLauncherSpec("launcher_btn_mousepad", "mousepad", "Text Editor", "✎", PocketLinuxColors.Teal, "mousepad"),
    GuiLauncherSpec("launcher_btn_galculator", "galculator", "Calculator", "±", PocketLinuxColors.Indigo, "galculator"),
    GuiLauncherSpec("launcher_btn_xcalc", "xcalc", "Classic Calc", "#", PocketLinuxColors.Emerald, "xcalc"),
    GuiLauncherSpec("launcher_btn_xclock", "xclock", "Desktop Clock", "◷", PocketLinuxColors.CyanDeep, "xclock"),
    GuiLauncherSpec("launcher_btn_feh", "feh", "Image Viewer", "▣", PocketLinuxColors.Rose, "feh")
)

@Composable
fun AppsSystemTab(
    runtimeController: LinuxRuntimeController?,
    runtimeState: RuntimeState,
    latestMetrics: SystemMetrics?,
    onRefreshMetrics: suspend () -> SystemMetrics?,
    onLaunchAppAndSwitchToDesktop: (String) -> Unit = {},
    onSwitchToDesktop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var autoSwitchDesktop by remember { mutableStateOf(false) }
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

    fun runQuickCommand(rawCmd: String) {
        val controller = runtimeController
        if (controller == null) {
            commandResultText = "Runtime service starting (exitCode=1)"
            return
        }
        commandResultText = "Running: $rawCmd ..."
        scope.launch {
            val res = runCatching {
                controller.execCommand(rawCmd, timeoutMs = 45_000L)
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

    fun executeCommandRunner() {
        val rawCmd = commandInput
        commandInput = ""
        if (rawCmd.isBlank()) {
            commandResultText = "No command entered (exitCode=0)"
            return
        }
        runQuickCommand(rawCmd)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketLinuxColors.Obsidian)
            .verticalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Section 1: Desktop Applications Grid Card
        SectionCard(
            title = "Linux Desktop Applications (:1)",
            trailing = {
                Surface(
                    color = if (autoSwitchDesktop) PocketLinuxColors.CyanContainer else PocketLinuxColors.SurfaceElevated,
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(
                        1.dp,
                        if (autoSwitchDesktop) PocketLinuxColors.Cyan else PocketLinuxColors.Border
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { autoSwitchDesktop = !autoSwitchDesktop }
                ) {
                    Text(
                        text = if (autoSwitchDesktop) "Auto-Switch: ON" else "Auto-Switch: OFF",
                        color = if (autoSwitchDesktop) PocketLinuxColors.Cyan else PocketLinuxColors.TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        ) {
            BUNDLED_GUI_LAUNCHERS.chunked(2).forEach { rowSpecs ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowSpecs.forEach { spec ->
                        Surface(
                            color = PocketLinuxColors.SurfaceElevated,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, spec.accent.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (autoSwitchDesktop) {
                                        onLaunchAppAndSwitchToDesktop(spec.command)
                                    } else {
                                        val controller = runtimeController ?: return@clickable
                                        scope.launch {
                                            runCatching { controller.launchGuiApp(spec.command) }
                                            val refreshed = runCatching { onRefreshMetrics() }.getOrNull()
                                            if (refreshed != null) {
                                                metricsSummaryText = formatMetricsSummary(refreshed, runtimeState)
                                            }
                                        }
                                    }
                                }
                                .testTag(spec.tag)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    color = spec.accent.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = spec.glyph,
                                        color = spec.accent,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = spec.label,
                                        color = PocketLinuxColors.TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = spec.subtitle,
                                        color = PocketLinuxColors.TextSecondary,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    if (rowSpecs.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // Section 2: ELF Binary & Full Linux Quick Tools
        SectionCard(title = "ELF & Full Linux Diagnostics") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                QuickToolChip("ELF & Health Check") {
                    runQuickCommand("/usr/local/bin/linux-health.sh")
                }
                QuickToolChip("apk update") {
                    runQuickCommand("apk update")
                }
                QuickToolChip("List Storage") {
                    runQuickCommand("ls -la /root /mnt/sdcard")
                }
                QuickToolChip("View Desktop") {
                    onSwitchToDesktop()
                }
            }
        }

        // Section 3: Command & Package Runner
        SectionCard(title = "Linux Command & Package Runner (PRoot)") {
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
                    placeholder = {
                        Text(
                            "uname -a && file /bin/busybox",
                            fontSize = 11.sp,
                            color = PocketLinuxColors.TextMuted
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PocketLinuxColors.Cyan,
                        unfocusedBorderColor = PocketLinuxColors.Border,
                        focusedContainerColor = PocketLinuxColors.DeepConsole,
                        unfocusedContainerColor = PocketLinuxColors.DeepConsole
                    ),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = PocketLinuxColors.TextPrimary
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { executeCommandRunner() }),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("command_input")
                )

                Button(
                    onClick = { executeCommandRunner() },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PocketLinuxColors.Cyan,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("command_run_btn")
                ) {
                    Text("Run", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Surface(
                color = PocketLinuxColors.DeepConsole,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, PocketLinuxColors.BorderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = commandResultText,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = Color(0xFFCBD5E1)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                        .testTag("command_result")
                )
            }
        }

        // Section 4: System & Memory Telemetry
        SectionCard(
            title = "Linux & Android System Telemetry",
            trailing = {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val updated = runCatching { onRefreshMetrics() }.getOrNull()
                            metricsSummaryText = formatMetricsSummary(updated ?: latestMetrics, runtimeState)
                        }
                    },
                    modifier = Modifier
                        .height(30.dp)
                        .testTag("metrics_refresh_btn"),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text("Refresh", fontSize = 11.sp)
                }
            }
        ) {
            val totalKb = (latestMetrics?.linuxMemTotalKb ?: 0L).coerceAtLeast(1L)
            val availKb = (latestMetrics?.linuxMemAvailableKb ?: 0L).coerceIn(0L, totalKb)
            val usedFraction = if (latestMetrics != null && latestMetrics.linuxMemTotalKb > 0L) {
                ((totalKb - availKb).toFloat() / totalKb.toFloat()).coerceIn(0f, 1f)
            } else {
                0.2f
            }

            LinearProgressIndicator(
                progress = { usedFraction },
                color = PocketLinuxColors.Cyan,
                trackColor = PocketLinuxColors.SurfaceElevated,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
            )

            Surface(
                color = PocketLinuxColors.DeepConsole,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, PocketLinuxColors.BorderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = metricsSummaryText,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = PocketLinuxColors.TextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                        .testTag("metrics_summary")
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Surface(
        color = PocketLinuxColors.Surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, PocketLinuxColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = PocketLinuxColors.Cyan
                )
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun QuickToolChip(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        color = PocketLinuxColors.SurfaceElevated,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, PocketLinuxColors.Border),
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = PocketLinuxColors.TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
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
