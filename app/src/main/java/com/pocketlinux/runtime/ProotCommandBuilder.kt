package com.pocketlinux.runtime

import android.content.Context
import java.io.File

data class ProotCommand(
    val argv: List<String>,
    val env: Map<String, String>,
    val workingDir: String
) {
    fun toEnvpArray(): Array<String> = env.entries.map { "${it.key}=${it.value}" }.toTypedArray()

    fun toProcessBuilder(): ProcessBuilder = ProcessBuilder(argv).apply {
        environment().clear()
        environment().putAll(env)
    }
}

class ProotCommandBuilder(
    filesDir: File,
    cacheDir: File,
    nativeLibraryDir: File,
    private val appContext: Context? = null
) {
    constructor(context: Context) : this(
        filesDir = context.filesDir,
        cacheDir = context.cacheDir,
        nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
        appContext = context.applicationContext
    )

    val prootExecutable: File = File(nativeLibraryDir, "libproot.so")
    val prootLoader: File = File(nativeLibraryDir, "libproot-loader.so")
    val rootfsDir: File = File(filesDir, "rootfs")
    val sharedDir: File = File(filesDir, "shared")
    val prootTmpDir: File = File(cacheDir, "proot_tmp")
    val shmDir: File = File(cacheDir, "proot_shm")
    val virglIpcDir: File = File(cacheDir, "virgl_ipc")
    val fakeProcDir: File = File(filesDir, "proot_fake_proc")

    /**
     * Ensures auxiliary runtime directories, POSIX `/dev/shm` shared memory, VirGL IPC dir,
     * and dynamic multi-core `/proc` files synced from real Android hardware exist.
     */
    fun ensureRuntimeDirsAndFakeProc() {
        runCatching {
            if (!sharedDir.isDirectory) sharedDir.mkdirs()
            if (!prootTmpDir.isDirectory) prootTmpDir.mkdirs()
            if (!shmDir.isDirectory) shmDir.mkdirs()
            if (!virglIpcDir.isDirectory) virglIpcDir.mkdirs()
            if (!fakeProcDir.isDirectory) fakeProcDir.mkdirs()

            val profile = LinuxResourceGovernor.probeHardware(appContext)
            LinuxResourceGovernor.refreshDynamicFakeProc(
                fakeProcDir = fakeProcDir,
                rootfsDir = rootfsDir,
                profile = profile
            )
        }
    }

    fun buildEnvironment(
        display: String? = ":1",
        extraEnv: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val env = linkedMapOf(
            "PROOT_LOADER" to prootLoader.absolutePath,
            "PROOT_TMP_DIR" to prootTmpDir.absolutePath,
            "HOME" to "/root",
            "USER" to "root",
            "XDG_CONFIG_HOME" to "/root/.config",
            "XDG_DATA_HOME" to "/root/.local/share",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "TMPDIR" to "/tmp",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        )
        // PRoot's seccomp-BPF filter lets it skip ptrace stops for syscalls it does not
        // need to translate, which is several times faster than full syscall tracing.
        // Devices that kill proot with SIGSYS can opt out by creating files/proot_no_seccomp.
        if (File(rootfsDir.parentFile, "proot_no_seccomp").exists()) {
            env["PROOT_NO_SECCOMP"] = "1"
        }
        // Skip the GTK/Qt accessibility bus and remote GVFS/dconf D-Bus lookups
        // (each otherwise causes a 3-10s timeout on every GUI app launch inside PRoot).
        env["NO_AT_BRIDGE"] = "1"
        env["GTK_A11Y"] = "none"
        env["QT_ACCESSIBILITY"] = "0"
        env["GIO_USE_VFS"] = "local"
        env["GSETTINGS_BACKEND"] = "memory"
        env["GDK_RENDERING"] = "image"
        env["MESA_SHADER_CACHE_DIR"] = "/tmp/mesa_shader_cache"
        val dbusAddrFile = File(rootfsDir, "tmp/.pocketlinux_dbus_address")
        if (dbusAddrFile.isFile) {
            val addr = runCatching { dbusAddrFile.readText().trim() }.getOrDefault("")
            if (addr.isNotEmpty()) {
                env["DBUS_SESSION_BUS_ADDRESS"] = addr
            }
        }
        if (display != null) {
            env["DISPLAY"] = display
        }
        env.putAll(extraEnv)
        return env
    }

    fun buildCommand(
        guestArgs: List<String>,
        workingDir: String = "/root",
        display: String? = ":1",
        extraEnv: Map<String, String> = emptyMap()
    ): ProotCommand {
        require(guestArgs.isNotEmpty()) { "guestArgs must not be empty" }
        val argv = mutableListOf(
            prootExecutable.absolutePath,
            "-0",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${sharedDir.absolutePath}:/mnt/sdcard"
        )

        if (shmDir.isDirectory) {
            argv.add("-b")
            argv.add("${shmDir.absolutePath}:/dev/shm")
        }

        if (virglIpcDir.isDirectory) {
            argv.add("-b")
            argv.add("${virglIpcDir.absolutePath}:/tmp/.virgl_ipc")
        }

        val virglSocket = File(prootTmpDir, VirglServerManager.DEFAULT_SOCKET_NAME)
        if (virglSocket.exists()) {
            argv.add("-b")
            argv.add("${virglSocket.absolutePath}:/tmp/.virgl_test")
        }

        val phoneStorage = File("/storage/emulated/0")
        if (phoneStorage.isDirectory && phoneStorage.canRead()) {
            argv.add("-b")
            argv.add("${phoneStorage.absolutePath}:/mnt/phone")
        }

        for (name in FAKE_PROC_FILES) {
            val fakeFile = File(fakeProcDir, name)
            if (fakeFile.isFile) {
                argv.add("-b")
                argv.add("${fakeFile.absolutePath}:/proc/$name")
            }
        }

        argv.addAll(
            listOf(
                "--link2symlink",
                "--sysvipc",
                "--ashmem-memfd",
                "--kill-on-exit",
                "-w", workingDir
            )
        )
        argv.addAll(guestArgs)

        return ProotCommand(
            argv = argv,
            env = buildEnvironment(display = display, extraEnv = extraEnv),
            workingDir = workingDir
        )
    }

    fun buildShellExec(
        command: String,
        workingDir: String = "/root",
        display: String? = ":1",
        extraEnv: Map<String, String> = emptyMap()
    ): ProotCommand {
        return buildCommand(
            guestArgs = listOf("/bin/sh", "-c", command),
            workingDir = workingDir,
            display = display,
            extraEnv = extraEnv
        )
    }

    fun buildInteractiveShell(
        shellArgs: List<String> = listOf("/bin/bash", "-l"),
        workingDir: String = "/root",
        display: String? = ":1",
        extraEnv: Map<String, String> = emptyMap()
    ): ProotCommand {
        return buildCommand(
            guestArgs = shellArgs,
            workingDir = workingDir,
            display = display,
            extraEnv = extraEnv
        )
    }

    fun buildStartDesktop(
        geometry: String = "1024x600",
        rfbPort: Int = 5901,
        display: String = ":1"
    ): ProotCommand {
        return buildCommand(
            guestArgs = listOf("/usr/local/bin/start-desktop.sh"),
            workingDir = "/root",
            display = display,
            extraEnv = mapOf(
                "GEOM" to geometry,
                "RFB_PORT" to rfbPort.toString()
            )
        )
    }

    fun buildLaunchGuiApp(
        command: String,
        workingDir: String = "/root",
        display: String? = ":1",
        extraEnv: Map<String, String> = emptyMap()
    ): ProotCommand {
        val trimmed = command.trim()
        val effectiveShellCommand = when (trimmed) {
            "xterm" -> "exec xterm -fa 'DejaVu Sans Mono' -fs 12 -bg '#0F172A' -fg '#E2E8F0' -geometry 92x26+60+40"
            "pcmanfm" -> "exec pcmanfm /root"
            "feh" -> "if [ -s /usr/share/backgrounds/pocketlinux-wallpaper.png ]; then " +
                "exec feh --title 'feh - PocketLinux' /usr/share/backgrounds/pocketlinux-wallpaper.png; " +
                "else " +
                "printf 'P6\\n240 160\\n255\\n' > /tmp/pocketlinux_sample.ppm; " +
                "python3 -c 'open(\"/tmp/pocketlinux_sample.ppm\",\"ab\").write(b\"\\x38\\xbd\\xf8\"*(240*160))' 2>/dev/null || " +
                "dd if=/dev/zero bs=115200 count=1 >> /tmp/pocketlinux_sample.ppm 2>/dev/null || true; " +
                "exec feh --title 'feh - PocketLinux' /tmp/pocketlinux_sample.ppm; fi"
            else -> "exec $command"
        }
        return buildCommand(
            guestArgs = listOf("/bin/sh", "-c", effectiveShellCommand),
            workingDir = workingDir,
            display = display,
            extraEnv = extraEnv
        )
    }

    fun buildSysInfo(
        textMode: Boolean = false,
        display: String = ":1"
    ): ProotCommand {
        val args = if (textMode) {
            listOf("/usr/local/bin/sysinfo.sh", "--text")
        } else {
            listOf("/usr/local/bin/sysinfo.sh")
        }
        return buildCommand(
            guestArgs = args,
            workingDir = "/root",
            display = display
        )
    }

    companion object {
        private val FAKE_PROC_FILES = listOf("stat", "uptime", "version", "loadavg", "vmstat", "filesystems")
    }
}
