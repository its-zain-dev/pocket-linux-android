package com.pocketlinux.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProotCommandBuilderTest {

    private val filesDir = File("/data/user/0/com.pocketlinux/files")
    private val cacheDir = File("/data/user/0/com.pocketlinux/cache")
    private val nativeDir = File("/data/app/com.pocketlinux/lib/arm64")
    private val builder = ProotCommandBuilder(filesDir, cacheDir, nativeDir)

    @Test
    fun buildCommandProducesExactProotFlagsAndBinds() {
        val cmd = builder.buildCommand(
            guestArgs = listOf("/bin/uname", "-a"),
            workingDir = "/root"
        )

        val expectedArgv = listOf(
            File(nativeDir, "libproot.so").absolutePath,
            "-0",
            "-r", File(filesDir, "rootfs").absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${File(filesDir, "shared").absolutePath}:/mnt/sdcard",
            "--link2symlink",
            "--sysvipc",
            "--ashmem-memfd",
            "--kill-on-exit",
            "-w", "/root",
            "/bin/uname", "-a"
        )
        assertEquals(expectedArgv, cmd.argv)
        assertEquals("/root", cmd.workingDir)
    }

    @Test
    fun buildEnvironmentSetsMandatoryProotVarsAndIsolatesFromHostEnv() {
        val cmd = builder.buildShellExec("echo hello", display = ":1")

        assertEquals(File(nativeDir, "libproot-loader.so").absolutePath, cmd.env["PROOT_LOADER"])
        assertEquals(File(cacheDir, "proot_tmp").absolutePath, cmd.env["PROOT_TMP_DIR"])
        assertNull(cmd.env["PROOT_NO_SECCOMP"])
        assertEquals("1", cmd.env["NO_AT_BRIDGE"])
        assertEquals("/root", cmd.env["HOME"])
        assertEquals("root", cmd.env["USER"])
        assertEquals("xterm-256color", cmd.env["TERM"])
        assertEquals("C.UTF-8", cmd.env["LANG"])
        assertEquals("/tmp", cmd.env["TMPDIR"])
        assertEquals("/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", cmd.env["PATH"])
        assertEquals(":1", cmd.env["DISPLAY"])
        assertFalse(cmd.env.containsKey("LD_LIBRARY_PATH"))
        assertFalse(cmd.env.containsKey("LD_PRELOAD"))

        val pbEnv = cmd.toProcessBuilder().environment()
        assertEquals(cmd.env, pbEnv)
        assertFalse(pbEnv.containsKey("LD_LIBRARY_PATH"))
        assertFalse(pbEnv.containsKey("LD_PRELOAD"))

        val envp = cmd.toEnvpArray()
        assertTrue(envp.contains("PROOT_LOADER=${File(nativeDir, "libproot-loader.so").absolutePath}"))
        assertTrue(envp.contains("NO_AT_BRIDGE=1"))
    }

    @Test
    fun helperBuildersConstructExpectedGuestCommands() {
        val shellCmd = builder.buildShellExec("ls -la /mnt/sdcard", workingDir = "/tmp", display = null)
        assertEquals(listOf("/bin/sh", "-c", "ls -la /mnt/sdcard"), shellCmd.argv.takeLast(3))
        assertNull(shellCmd.env["DISPLAY"])

        val interactive = builder.buildInteractiveShell()
        assertEquals(listOf("/bin/bash", "-l"), interactive.argv.takeLast(2))

        val desktop = builder.buildStartDesktop(geometry = "1280x720", rfbPort = 5901, display = ":1")
        assertEquals(listOf("/usr/local/bin/start-desktop.sh"), desktop.argv.takeLast(1))
        assertEquals("1280x720", desktop.env["GEOM"])
        assertEquals("5901", desktop.env["RFB_PORT"])

        val gui = builder.buildLaunchGuiApp("xterm -e top")
        assertEquals(listOf("/bin/sh", "-c", "exec xterm -e top"), gui.argv.takeLast(3))

        val sysinfo = builder.buildSysInfo(textMode = false)
        assertEquals(listOf("/usr/local/bin/sysinfo.sh"), sysinfo.argv.takeLast(1))

        val sysinfoText = builder.buildSysInfo(textMode = true)
        assertEquals(listOf("/usr/local/bin/sysinfo.sh", "--text"), sysinfoText.argv.takeLast(2))
    }
}
