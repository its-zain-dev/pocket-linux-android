package com.pocketlinux.runtime

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.SystemClock
import java.io.File
import kotlin.math.max

/**
 * Dynamic hardware topology detector, SELinux-safe multi-core `/proc` synthesizer, and
 * memory-pressure governor for PocketLinux.
 *
 * Bridges real Android SoC topology (CPU core count, big.LITTLE performance/efficiency
 * cluster frequencies, RAM capacity, GPU model) into the Linux guest environment and
 * reclaims temporary caches when Android signals memory pressure.
 */
object LinuxResourceGovernor {

    data class HardwareProfile(
        val totalCores: Int,
        val performanceCores: Int,
        val efficiencyCores: Int,
        val maxFreqKhz: Long,
        val totalRamMb: Long,
        val availRamMb: Long,
        val gpuModel: String
    ) {
        val recommendedRenderThreads: Int
            get() = when {
                totalRamMb in 1..2500 -> max(2, performanceCores.coerceAtMost(4))
                performanceCores >= 2 -> performanceCores.coerceIn(2, 6)
                else -> totalCores.coerceIn(2, 6)
            }
    }

    fun probeHardware(context: Context? = null): HardwareProfile {
        val runtimeCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val freqs = ArrayList<Long>(runtimeCores)
        for (i in 0 until runtimeCores) {
            val f = readLongFirstLine(
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            )
            if (f > 0L) freqs.add(f)
        }

        val maxFreq = freqs.maxOrNull() ?: 0L
        val perfCores: Int
        val effCores: Int
        if (freqs.size >= 2 && maxFreq > 0L) {
            // Cores running at >= 80% of max cluster clock are treated as big/performance cores
            val threshold = (maxFreq * 80L) / 100L
            val big = freqs.count { it >= threshold }.coerceAtLeast(1)
            perfCores = big
            effCores = (runtimeCores - big).coerceAtLeast(0)
        } else {
            perfCores = runtimeCores
            effCores = 0
        }

        var totalMb = 0L
        var availMb = 0L
        if (context != null) {
            runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (am != null) {
                    val mi = ActivityManager.MemoryInfo()
                    am.getMemoryInfo(mi)
                    totalMb = (mi.totalMem / (1024L * 1024L)).coerceAtLeast(0L)
                    availMb = (mi.availMem / (1024L * 1024L)).coerceAtLeast(0L)
                }
            }
        }
        if (totalMb <= 0L) {
            val memInfo = readProcMeminfoKb()
            totalMb = (memInfo.first / 1024L).coerceAtLeast(512L)
            availMb = (memInfo.second / 1024L).coerceAtLeast(256L)
        }

        val gpuModel = detectGpuModel()

        return HardwareProfile(
            totalCores = runtimeCores,
            performanceCores = perfCores,
            efficiencyCores = effCores,
            maxFreqKhz = maxFreq,
            totalRamMb = totalMb,
            availRamMb = availMb,
            gpuModel = gpuModel
        )
    }

    fun refreshDynamicFakeProc(
        fakeProcDir: File,
        rootfsDir: File,
        profile: HardwareProfile = probeHardware(null)
    ) {
        runCatching {
            if (!fakeProcDir.isDirectory) fakeProcDir.mkdirs()

            val uptimeSec = runCatching { SystemClock.elapsedRealtime() / 1000.0 }
                .getOrDefault(3600.0)
                .coerceAtLeast(1.0)
            val idleSec = uptimeSec * profile.totalCores * 0.85

            File(fakeProcDir, "uptime").writeText(
                String.format(java.util.Locale.US, "%.2f %.2f\n", uptimeSec, idleSec)
            )
            File(fakeProcDir, "loadavg").writeText(
                "0.18 0.14 0.10 1/${max(64, profile.totalCores * 32)} 1024\n"
            )

            val userTicks = (uptimeSec * 12.0).toLong().coerceAtLeast(100L)
            val sysTicks = (uptimeSec * 4.0).toLong().coerceAtLeast(40L)
            val idleTicks = (uptimeSec * 84.0).toLong().coerceAtLeast(1000L)
            val statContent = buildString {
                append("cpu  ${userTicks * profile.totalCores} 0 ${sysTicks * profile.totalCores} ${idleTicks * profile.totalCores} 0 0 0 0 0 0\n")
                for (c in 0 until profile.totalCores) {
                    append("cpu$c $userTicks 0 $sysTicks $idleTicks 0 0 0 0 0 0\n")
                }
                append("intr 0\n")
                append("ctxt ${userTicks * 16}\n")
                append("btime ${(System.currentTimeMillis() / 1000L - uptimeSec.toLong()).coerceAtLeast(1700000000L)}\n")
                append("processes 128\n")
                append("procs_running 1\n")
                append("procs_blocked 0\n")
            }
            File(fakeProcDir, "stat").writeText(statContent)

            val kernelRel = System.getProperty("os.version")?.takeIf { it.isNotBlank() } ?: "6.1.0-pocketlinux"
            File(fakeProcDir, "version").writeText(
                "Linux version $kernelRel (pocketlinux@android) (clang) #1 SMP PREEMPT\n"
            )

            val freePages = ((profile.availRamMb * 1024L) / 4L).coerceAtLeast(32768L)
            File(fakeProcDir, "vmstat").writeText(
                "nr_free_pages $freePages\npgpgin 2048\npgpgout 1024\n"
            )
            File(fakeProcDir, "filesystems").writeText(
                "nodev\tproc\nnodev\tsysfs\nnodev\ttmpfs\nnodev\tdevpts\n\text4\n\tf2fs\n"
            )

            if (rootfsDir.isDirectory) {
                val etcDir = File(rootfsDir, "etc")
                if (etcDir.isDirectory) {
                    File(etcDir, "pocketlinux-hardware.env").writeText(
                        buildString {
                            append("export PL_TOTAL_CORES=${profile.totalCores}\n")
                            append("export PL_PERF_CORES=${profile.performanceCores}\n")
                            append("export PL_EFF_CORES=${profile.efficiencyCores}\n")
                            append("export PL_MAX_FREQ_KHZ=${profile.maxFreqKhz}\n")
                            append("export PL_TOTAL_RAM_MB=${profile.totalRamMb}\n")
                            append("export PL_AVAIL_RAM_MB=${profile.availRamMb}\n")
                            append("export PL_RECOMMENDED_THREADS=${profile.recommendedRenderThreads}\n")
                            append("export PL_GPU_MODEL=\"${profile.gpuModel.replace("\"", "")}\"\n")
                        }
                    )
                }
            }
        }
    }

    /**
     * Reclaims non-essential guest & host caches when Android signals memory pressure
     * so Android's Low-Memory Killer / Phantom Process Killer spares the Linux session.
     */
    fun onTrimMemory(level: Int, rootfsDir: File, shmDir: File) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        runCatching {
            File(rootfsDir, "var/cache/apk").listFiles()?.forEach { it.deleteRecursively() }
            File(rootfsDir, "root/.cache/thumbnails").listFiles()?.forEach { it.deleteRecursively() }
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
            ) {
                File(rootfsDir, "tmp/mesa_shader_cache").listFiles()?.forEach { f ->
                    if (System.currentTimeMillis() - f.lastModified() > 120_000L) {
                        f.deleteRecursively()
                    }
                }
                shmDir.listFiles()?.forEach { f ->
                    if (System.currentTimeMillis() - f.lastModified() > 300_000L) {
                        f.deleteRecursively()
                    }
                }
            }
        }
    }

    private fun detectGpuModel(): String {
        val candidates = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_model",
            "/sys/devices/platform/mali.0/gpuinfo",
            "/sys/class/misc/mali0/device/gpuinfo"
        )
        for (path in candidates) {
            val f = File(path)
            if (f.isFile && f.canRead()) {
                val text = runCatching { f.readText().lineSequence().firstOrNull()?.trim().orEmpty() }
                    .getOrDefault("")
                if (text.isNotEmpty()) return text
            }
        }
        return "Android EGL/GLES Hardware GPU"
    }

    private fun readLongFirstLine(file: File): Long {
        if (!file.isFile || !file.canRead()) return 0L
        return runCatching { file.readText().lineSequence().firstOrNull()?.trim()?.toLongOrNull() ?: 0L }
            .getOrDefault(0L)
    }

    private fun readProcMeminfoKb(): Pair<Long, Long> {
        var total = 0L
        var avail = 0L
        runCatching {
            val f = File("/proc/meminfo")
            if (f.isFile && f.canRead()) {
                f.forEachLine { line ->
                    when {
                        line.startsWith("MemTotal:") -> {
                            total = line.substringAfter(':').trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L
                        }
                        line.startsWith("MemAvailable:") -> {
                            avail = line.substringAfter(':').trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L
                        }
                    }
                }
            }
        }
        return total to (if (avail > 0L) avail else total / 2L)
    }
}
