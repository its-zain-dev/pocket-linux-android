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
    nativeLibraryDir: File
) {
    constructor(context: Context) : this(
        filesDir = context.filesDir,
        cacheDir = context.cacheDir,
        nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
    )

    val prootExecutable: File = File(nativeLibraryDir, "libproot.so")
    val prootLoader: File = File(nativeLibraryDir, "libproot-loader.so")
    val rootfsDir: File = File(filesDir, "rootfs")
    val sharedDir: File = File(filesDir, "shared")
    val prootTmpDir: File = File(cacheDir, "proot_tmp")

    fun buildEnvironment(
        display: String? = ":1",
        extraEnv: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val env = linkedMapOf(
            "PROOT_LOADER" to prootLoader.absolutePath,
            "PROOT_TMP_DIR" to prootTmpDir.absolutePath,
            "PROOT_NO_SECCOMP" to "1",
            "HOME" to "/root",
            "USER" to "root",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "TMPDIR" to "/tmp",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        )
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
        val argv = listOf(
            prootExecutable.absolutePath,
            "-0",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${sharedDir.absolutePath}:/mnt/sdcard",
            "--link2symlink",
            "--sysvipc",
            "--ashmem-memfd",
            "--kill-on-exit",
            "-w", workingDir
        ) + guestArgs

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
            "xterm" -> "exec xterm -geometry 100x28+120+60"
            "feh" -> "if [ ! -s /tmp/pocketlinux_sample.ppm ]; then " +
                "printf 'P6\\n240 160\\n255\\n' > /tmp/pocketlinux_sample.ppm; " +
                "python3 -c 'open(\"/tmp/pocketlinux_sample.ppm\",\"ab\").write(b\"\\x38\\xbd\\xf8\"*(240*160))' 2>/dev/null || " +
                "dd if=/dev/zero bs=115200 count=1 >> /tmp/pocketlinux_sample.ppm 2>/dev/null || true; " +
                "fi; exec feh --title 'feh - PocketLinux' /tmp/pocketlinux_sample.ppm"
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
}
