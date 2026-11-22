package com.pocketlinux.runtime

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LinuxRuntimeServiceTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private class ShellCommandFactory(
        override val rootfsDir: File,
        private val startDesktopShellCmd: String = "sleep 10",
        private val sysinfoShellCmd: String = "echo '{}'",
        private val onStartDesktopBuilt: (() -> Unit)? = null
    ) : RuntimeCommandFactory {
        private fun sh(script: String): ProotCommand = ProotCommand(
            argv = listOf("/bin/sh", "-c", script),
            env = emptyMap(),
            workingDir = rootfsDir.absolutePath
        )

        override fun buildShellExec(command: String, display: String): ProotCommand = sh(command)

        override fun buildInteractiveShell(display: String): ProotCommand = sh("cat")

        override fun buildStartDesktop(geometry: String, rfbPort: Int, display: String): ProotCommand {
            onStartDesktopBuilt?.invoke()
            return sh(startDesktopShellCmd)
        }

        override fun buildLaunchGuiApp(command: String, display: String): ProotCommand = sh(command)

        override fun buildSysInfo(textMode: Boolean, display: String): ProotCommand = sh(sysinfoShellCmd)
    }

    private class FakePtySession(override val pid: Int) : PtySession {
        var closedCount = 0
            private set

        override val inputStream: InputStream = ByteArrayInputStream(ByteArray(0))
        override val outputStream: OutputStream = ByteArrayOutputStream()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun write(bytes: ByteArray, offset: Int, length: Int) {}
        override fun write(text: String) {}
        override fun resize(cols: Int, rows: Int) {}
        override fun isAlive(): Boolean = closedCount == 0
        override fun waitFor(): Int = 0
        override fun close() {
            closedCount++
        }
    }

    @Test
    fun execCommandReturnsWithinBoundedTimeWhenGrandchildHoldsPipeOpenOnTimeout() = runBlocking {
        val rootfs = tempFolder.newFolder("rootfs_timeout")
        val engine = LinuxRuntimeEngine(
            rootfsProvider = object : RootfsProvider {
                override fun isReady(): Boolean = true
                override fun ensureExtracted(onProgress: (String) -> Unit): Long = 0L
            },
            commandFactory = ShellCommandFactory(rootfs)
        )

        try {
            val startNs = System.nanoTime()
            val result = engine.execCommand(
                command = "echo partial_out; sleep 8 & sleep 8",
                timeoutMs = 250L
            )
            val wallMs = (System.nanoTime() - startNs) / 1_000_000L

            assertEquals(-1, result.exitCode)
            assertTrue("Expected timeout message in stderr: ${result.stderr}", result.stderr.contains("timed out"))
            assertTrue("execCommand hung past bound: ${wallMs}ms", wallMs < 3_000L)
        } finally {
            engine.destroy()
        }
    }

    @Test
    fun execCommandReturnsBoundedWhenShellExitsEarlyWithBackgroundChildHoldingPipeOpen() = runBlocking {
        val rootfs = tempFolder.newFolder("rootfs_bg")
        val engine = LinuxRuntimeEngine(
            rootfsProvider = object : RootfsProvider {
                override fun isReady(): Boolean = true
                override fun ensureExtracted(onProgress: (String) -> Unit): Long = 0L
            },
            commandFactory = ShellCommandFactory(rootfs)
        )

        try {
            val startNs = System.nanoTime()
            val result = engine.execCommand(
                command = "echo quick_ok; sleep 8 &",
                timeoutMs = 500L
            )
            val wallMs = (System.nanoTime() - startNs) / 1_000_000L

            assertEquals(0, result.exitCode)
            assertTrue("Expected stdout to contain quick_ok: ${result.stdout}", result.stdout.contains("quick_ok"))
            assertTrue("execCommand hung waiting for pipe EOF: ${wallMs}ms", wallMs < 3_000L)
        } finally {
            engine.destroy()
        }
    }

    @Test
    fun execCommandCancellationInterruptsWaitPromptly() = runBlocking {
        val rootfs = tempFolder.newFolder("rootfs_cancel_exec")
        val engine = LinuxRuntimeEngine(
            rootfsProvider = object : RootfsProvider {
                override fun isReady(): Boolean = true
                override fun ensureExtracted(onProgress: (String) -> Unit): Long = 0L
            },
            commandFactory = ShellCommandFactory(rootfs)
        )

        try {
            val startNs = System.nanoTime()
            val job = launch {
                engine.execCommand("sleep 10", timeoutMs = 10_000L)
            }
            delay(150L)
            job.cancelAndJoin()
            val wallMs = (System.nanoTime() - startNs) / 1_000_000L

            assertTrue("Cancelled execCommand took too long: ${wallMs}ms", wallMs < 3_000L)
        } finally {
            engine.destroy()
        }
    }

    @Test
    fun collectMetricsReturnsWithinBoundedTimeWhenSysinfoHangsWithOpenPipe() = runBlocking {
        val rootfs = tempFolder.newFolder("rootfs_metrics_timeout")
        val engine = LinuxRuntimeEngine(
            rootfsProvider = object : RootfsProvider {
                override fun isReady(): Boolean = true
                override fun ensureExtracted(onProgress: (String) -> Unit): Long = 0L
            },
            commandFactory = ShellCommandFactory(
                rootfsDir = rootfs,
                sysinfoShellCmd = "sleep 8 & sleep 8"
            ),
            readAndroidMemoryKb = { 12345 to 23456 },
            sysinfoTimeoutMs = 250L
        )

        try {
            val startNs = System.nanoTime()
            val metrics = engine.collectMetrics()
            val wallMs = (System.nanoTime() - startNs) / 1_000_000L

            assertEquals(12345, metrics.androidPssKb)
            assertEquals(23456, metrics.androidRssKb)
            assertEquals(0L, metrics.linuxMemTotalKb)
            assertTrue("collectMetrics hung past bound: ${wallMs}ms", wallMs < 3_000L)
        } finally {
            engine.destroy()
        }
    }

    @Test
    fun stopRuntimeDuringExtractionPreventsDesktopSpawnAndResetsToIdle() = runBlocking {
        val rootfs = tempFolder.newFolder("rootfs_cancel_extract")
        val extractStarted = CountDownLatch(1)
        val allowExtractFinish = CountDownLatch(1)
        val desktopSpawns = AtomicInteger(0)

        val engine = LinuxRuntimeEngine(
            rootfsProvider = object : RootfsProvider {
                override fun isReady(): Boolean = false
                override fun ensureExtracted(onProgress: (String) -> Unit): Long {
                    extractStarted.countDown()
                    allowExtractFinish.await(5, TimeUnit.SECONDS)
                    return 50L
                }
            },
            commandFactory = ShellCommandFactory(
                rootfsDir = rootfs,
                onStartDesktopBuilt = { desktopSpawns.incrementAndGet() }
            )
        )

        try {
            engine.startRuntime()
            assertTrue(extractStarted.await(3, TimeUnit.SECONDS))

            val stopStartNs = System.nanoTime()
            engine.stopRuntime()
            val stopCallMs = (System.nanoTime() - stopStartNs) / 1_000_000L
            assertTrue("stopRuntime should not block caller: ${stopCallMs}ms", stopCallMs < 500L)

            allowExtractFinish.countDown()
            engine.awaitLifecycleIdleForTest()

            assertEquals(0, desktopSpawns.get())
            assertFalse(engine.hasDesktopProcess())
            assertEquals(RuntimeState.Idle, engine.state.value)
        } finally {
            allowExtractFinish.countDown()
            engine.destroy()
        }
    }

    @Test
    fun stopRuntimeDuringBootDestroysDesktopProcessAndRemovesMarker() = runBlocking {
        val rootfs = tempFolder.newFolder("rootfs_stop_boot")
        File(rootfs, "tmp").mkdirs()
        val readyMarker = File(rootfs, "tmp/.pocketlinux_desktop_ready")
        readyMarker.writeText("stale")

        val desktopSpawned = CountDownLatch(1)
        val engine = LinuxRuntimeEngine(
            rootfsProvider = object : RootfsProvider {
                override fun isReady(): Boolean = true
                override fun ensureExtracted(onProgress: (String) -> Unit): Long = 0L
            },
            commandFactory = ShellCommandFactory(
                rootfsDir = rootfs,
                startDesktopShellCmd = "sleep 10",
                onStartDesktopBuilt = { desktopSpawned.countDown() }
            ),
            bootPollIntervalMs = 50L
        )

        try {
            engine.startRuntime()
            assertTrue(desktopSpawned.await(3, TimeUnit.SECONDS))
            withTimeout(3_000L) {
                while (!(engine.state.value is RuntimeState.Booting && engine.hasDesktopProcess())) {
                    delay(10L)
                }
            }

            engine.stopRuntime()
            engine.awaitLifecycleIdleForTest()

            assertFalse(engine.hasDesktopProcess())
            assertFalse(readyMarker.exists())
            assertEquals(RuntimeState.Idle, engine.state.value)
        } finally {
            engine.destroy()
        }
    }

    @Test
    fun rapidStopThenStartSerializesCleanlyWithoutStaleIdleOverwrite() = runBlocking {
        val rootfs = tempFolder.newFolder("rootfs_rapid_restart")
        File(rootfs, "tmp").mkdirs()
        val readyMarker = File(rootfs, "tmp/.pocketlinux_desktop_ready")
        val extractCalls = AtomicInteger(0)
        val firstExtractEntered = CountDownLatch(1)
        val releaseFirstExtract = CountDownLatch(1)

        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
            val port = socket.localPort
            val engine = LinuxRuntimeEngine(
                rootfsProvider = object : RootfsProvider {
                    override fun isReady(): Boolean = false
                    override fun ensureExtracted(onProgress: (String) -> Unit): Long {
                        val call = extractCalls.incrementAndGet()
                        if (call == 1) {
                            firstExtractEntered.countDown()
                            releaseFirstExtract.await(5, TimeUnit.SECONDS)
                        }
                        return 10L
                    }
                },
                commandFactory = ShellCommandFactory(
                    rootfsDir = rootfs,
                    startDesktopShellCmd = "touch '${readyMarker.absolutePath}'; sleep 10"
                ),
                vncPort = port,
                bootPollIntervalMs = 50L,
                xwinReadyCheckCommand = "true"
            )

            try {
                engine.startRuntime()
                assertTrue(firstExtractEntered.await(3, TimeUnit.SECONDS))

                // Rapid stop -> immediate start while first extraction is still in flight
                engine.stopRuntime()
                engine.startRuntime()
                releaseFirstExtract.countDown()

                val readyState = withTimeout(5_000L) {
                    engine.state.first { it is RuntimeState.Ready }
                } as RuntimeState.Ready

                assertEquals(port, readyState.vncPort)
                assertTrue(engine.hasDesktopProcess())
                assertEquals(2, extractCalls.get())

                engine.stopRuntime()
                engine.awaitLifecycleIdleForTest()
                assertEquals(RuntimeState.Idle, engine.state.value)
                assertFalse(engine.hasDesktopProcess())
            } finally {
                releaseFirstExtract.countDown()
                engine.destroy()
            }
        }
    }

    @Test
    fun closedPtySessionsAreRemovedFromActiveSessionsAutomatically() = runBlocking {
        val rootfs = tempFolder.newFolder("rootfs_pty")
        val created = mutableListOf<FakePtySession>()
        val nextPid = AtomicInteger(100)

        val engine = LinuxRuntimeEngine(
            rootfsProvider = object : RootfsProvider {
                override fun isReady(): Boolean = true
                override fun ensureExtracted(onProgress: (String) -> Unit): Long = 0L
            },
            commandFactory = ShellCommandFactory(rootfs),
            openPtySession = { _, _, _ ->
                FakePtySession(nextPid.incrementAndGet()).also { created.add(it) }
            }
        )

        try {
            val s1 = engine.openInteractiveShell(80, 24)
            val s2 = engine.openInteractiveShell(100, 40)
            assertEquals(2, engine.activePtySessionCount())

            s1.close()
            assertEquals(1, engine.activePtySessionCount())
            assertEquals(1, created[0].closedCount)

            // Closing via inputStream should also unregister the session
            s2.inputStream.close()
            assertEquals(0, engine.activePtySessionCount())
            assertEquals(1, created[1].closedCount)

            val s3 = engine.openInteractiveShell(80, 24)
            assertEquals(1, engine.activePtySessionCount())

            engine.stopRuntime()
            engine.awaitLifecycleIdleForTest()
            assertEquals(0, engine.activePtySessionCount())
            assertEquals(1, created[2].closedCount)
            assertFalse(s3.isAlive())
        } finally {
            engine.destroy()
        }
    }
}
