package com.pocketlinux.runtime

import org.json.JSONObject

data class SysinfoSnapshot(
    val sysname: String = "",
    val nodename: String = "",
    val release: String = "",
    val machine: String = "",
    val alpineRelease: String = "",
    val uptimeSec: Double = 0.0,
    val memTotalKb: Long = 0L,
    val memAvailableKb: Long = 0L,
    val memFreeKb: Long = 0L,
    val processCount: Int = 0,
    val xWindows: List<String> = emptyList()
)

object SysinfoParser {
    fun parse(jsonText: String): SysinfoSnapshot {
        val trimmed = jsonText.trim()
        if (trimmed.isEmpty()) return SysinfoSnapshot()

        val jsonCandidate = if (trimmed.startsWith("{")) {
            trimmed
        } else {
            trimmed.lineSequence()
                .map { it.trim() }
                .lastOrNull { it.startsWith("{") && it.endsWith("}") }
                ?: return SysinfoSnapshot()
        }

        return try {
            val obj = JSONObject(jsonCandidate)
            val windowsArray = obj.optJSONArray("x_windows")
            val windows = buildList {
                if (windowsArray != null) {
                    for (i in 0 until windowsArray.length()) {
                        val item = windowsArray.optString(i, "")
                        if (item.isNotBlank()) add(item)
                    }
                }
            }
            SysinfoSnapshot(
                sysname = obj.optString("sysname", ""),
                nodename = obj.optString("nodename", ""),
                release = obj.optString("release", ""),
                machine = obj.optString("machine", ""),
                alpineRelease = obj.optString("alpine_release", ""),
                uptimeSec = obj.optDouble("uptime_sec", 0.0),
                memTotalKb = obj.optLong("mem_total_kb", 0L),
                memAvailableKb = obj.optLong("mem_available_kb", 0L),
                memFreeKb = obj.optLong("mem_free_kb", 0L),
                processCount = obj.optInt("process_count", 0),
                xWindows = windows
            )
        } catch (_: Exception) {
            SysinfoSnapshot()
        }
    }

    fun parseProcVmRssKb(statusContent: String): Int {
        for (line in statusContent.lineSequence()) {
            if (line.startsWith("VmRSS:")) {
                val value = line.substringAfter("VmRSS:").trim().split(Regex("\\s+")).firstOrNull()
                return value?.toIntOrNull() ?: 0
            }
        }
        return 0
    }

    fun parseProcStatmRssKb(statmContent: String, pageSizeBytes: Long): Int {
        if (pageSizeBytes <= 0L) return 0
        val parts = statmContent.trim().split(Regex("\\s+"))
        if (parts.size < 2) return 0
        val residentPages = parts[1].toLongOrNull() ?: return 0
        return ((residentPages * pageSizeBytes) / 1024L).toInt()
    }
}
