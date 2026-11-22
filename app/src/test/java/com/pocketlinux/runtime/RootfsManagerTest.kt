package com.pocketlinux.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class RootfsManagerTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private class JvmPosixOps(private val failHardlink: Boolean = false) : PosixOps {
        val recordedModes = mutableMapOf<String, Int>()

        override fun chmod(path: String, mode: Int) {
            recordedModes[path] = mode
            val file = File(path)
            if (Files.isSymbolicLink(file.toPath())) return
            val perms = mutableSetOf<PosixFilePermission>()
            if ((mode and 0x100) != 0) perms.add(PosixFilePermission.OWNER_READ)
            if ((mode and 0x080) != 0) perms.add(PosixFilePermission.OWNER_WRITE)
            if ((mode and 0x040) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE)
            if ((mode and 0x020) != 0) perms.add(PosixFilePermission.GROUP_READ)
            if ((mode and 0x010) != 0) perms.add(PosixFilePermission.GROUP_WRITE)
            if ((mode and 0x008) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE)
            if ((mode and 0x004) != 0) perms.add(PosixFilePermission.OTHERS_READ)
            if ((mode and 0x002) != 0) perms.add(PosixFilePermission.OTHERS_WRITE)
            if ((mode and 0x001) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE)
            Files.setPosixFilePermissions(file.toPath(), perms)
        }

        override fun symlink(target: String, linkPath: String) {
            Files.createSymbolicLink(Path.of(linkPath), Path.of(target))
        }

        override fun link(existingPath: String, newPath: String) {
            if (failHardlink) throw IOException("Simulated EPERM on link()")
            Files.createLink(Path.of(newPath), Path.of(existingPath))
        }

        override fun isSymbolicLink(path: File): Boolean = Files.isSymbolicLink(path.toPath())

        override fun unlinkIfExists(path: File) {
            Files.deleteIfExists(path.toPath())
        }
    }

    private sealed class SyntheticEntry {
        data class Dir(val name: String, val mode: Int = 493) : SyntheticEntry()
        data class RegularFile(val name: String, val content: ByteArray, val mode: Int = 420) : SyntheticEntry()
        data class Symlink(val name: String, val target: String) : SyntheticEntry()
        data class Hardlink(val name: String, val target: String, val mode: Int = 493) : SyntheticEntry()
    }

    private fun buildTarGz(entries: List<SyntheticEntry>): ByteArray {
        val baos = ByteArrayOutputStream()
        GzipCompressorOutputStream(baos).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                for (entry in entries) {
                    when (entry) {
                        is SyntheticEntry.Dir -> {
                            val te = TarArchiveEntry(entry.name)
                            te.mode = entry.mode
                            tar.putArchiveEntry(te)
                            tar.closeArchiveEntry()
                        }
                        is SyntheticEntry.RegularFile -> {
                            val te = TarArchiveEntry(entry.name)
                            te.size = entry.content.size.toLong()
                            te.mode = entry.mode
                            tar.putArchiveEntry(te)
                            tar.write(entry.content)
                            tar.closeArchiveEntry()
                        }
                        is SyntheticEntry.Symlink -> {
                            val te = TarArchiveEntry(entry.name, TarArchiveEntry.LF_SYMLINK)
                            te.linkName = entry.target
                            tar.putArchiveEntry(te)
                            tar.closeArchiveEntry()
                        }
                        is SyntheticEntry.Hardlink -> {
                            val te = TarArchiveEntry(entry.name, TarArchiveEntry.LF_LINK)
                            te.linkName = entry.target
                            te.mode = entry.mode
                            tar.putArchiveEntry(te)
                            tar.closeArchiveEntry()
                        }
                    }
                }
            }
        }
        return baos.toByteArray()
    }

    @Test
    fun resolveAssetNameSelectsMatchingAbi() {
        val filesDir = tempFolder.newFolder("files1")
        val cacheDir = tempFolder.newFolder("cache1")
        val manager = RootfsManager(
            filesDir = filesDir,
            cacheDir = cacheDir,
            availableAssets = setOf("rootfs-x86_64.tar.gz", "other.txt"),
            openAsset = { ByteArrayInputStream(ByteArray(0)) },
            supportedAbis = listOf("armeabi-v7a", "x86_64"),
            posixOps = JvmPosixOps()
        )
        assertEquals("rootfs-x86_64.tar.gz", manager.resolveAssetName())
    }

    @Test
    fun extractsFilesSymlinksHardlinksModesAndIsIdempotent() {
        val filesDir = tempFolder.newFolder("files2")
        val cacheDir = tempFolder.newFolder("cache2")
        val posix = JvmPosixOps(failHardlink = false)
        val payload = "#!/bin/sh\necho ok\n".toByteArray()

        val archive = buildTarGz(
            listOf(
                SyntheticEntry.Dir("./", mode = 493),
                SyntheticEntry.Dir("./bin/", mode = 365), // 0555 read-only dir!
                SyntheticEntry.RegularFile("./bin/busybox", payload, mode = 493), // 0755
                SyntheticEntry.Symlink("./bin/sh", "/bin/busybox"),
                SyntheticEntry.Hardlink("./bin/ash", "./bin/busybox", mode = 493),
                SyntheticEntry.Dir("./tmp/", mode = 1023), // 01777
                SyntheticEntry.Dir("./tmp/.X11-unix/", mode = 1023)
            )
        )

        var openCount = 0
        val manager = RootfsManager(
            filesDir = filesDir,
            cacheDir = cacheDir,
            availableAssets = setOf("rootfs-arm64-v8a.tar.gz"),
            openAsset = {
                openCount++
                ByteArrayInputStream(archive)
            },
            supportedAbis = listOf("arm64-v8a"),
            posixOps = posix
        )

        val elapsed = manager.ensureExtracted()
        assertTrue(elapsed >= 1L)
        assertEquals(1, openCount)
        assertTrue(manager.isReady())
        assertTrue(manager.sharedDir.isDirectory)
        assertTrue(manager.prootTmpDir.isDirectory)

        val busybox = File(manager.rootfsDir, "bin/busybox")
        assertTrue(busybox.isFile)
        assertArrayEquals(payload, busybox.readBytes())
        assertTrue(busybox.canExecute())

        val sh = File(manager.rootfsDir, "bin/sh")
        assertTrue(Files.isSymbolicLink(sh.toPath()))
        assertEquals(Path.of("/bin/busybox"), Files.readSymbolicLink(sh.toPath()))

        val ash = File(manager.rootfsDir, "bin/ash")
        assertTrue(ash.isFile)
        assertArrayEquals(payload, ash.readBytes())

        // Verify sticky bit 01777 was passed to chmod for tmp and 0555 for bin
        val tmpPath = File(manager.rootfsDir.parentFile, "rootfs.staging/tmp").absolutePath
        val binPath = File(manager.rootfsDir.parentFile, "rootfs.staging/bin").absolutePath
        assertEquals(1023, posix.recordedModes[tmpPath])
        assertEquals(365, posix.recordedModes[binPath])

        // Idempotent second call should not reopen asset
        val secondElapsed = manager.ensureExtracted()
        assertEquals(0L, secondElapsed)
        assertEquals(1, openCount)
    }

    @Test
    fun hardlinkFallbackCopiesWhenLinkThrows() {
        val filesDir = tempFolder.newFolder("files3")
        val cacheDir = tempFolder.newFolder("cache3")
        val posix = JvmPosixOps(failHardlink = true)
        val payload = "binary-content".toByteArray()

        val archive = buildTarGz(
            listOf(
                SyntheticEntry.Dir("./bin/", mode = 493),
                SyntheticEntry.RegularFile("./bin/busybox", payload, mode = 493),
                SyntheticEntry.Hardlink("./bin/ls", "./bin/busybox", mode = 493)
            )
        )

        val manager = RootfsManager(
            filesDir = filesDir,
            cacheDir = cacheDir,
            availableAssets = setOf("rootfs-x86_64.tar.gz"),
            openAsset = { ByteArrayInputStream(archive) },
            supportedAbis = listOf("x86_64"),
            posixOps = posix
        )

        manager.ensureExtracted()
        val ls = File(manager.rootfsDir, "bin/ls")
        assertTrue(ls.isFile)
        assertArrayEquals(payload, ls.readBytes())
        assertTrue(ls.canExecute())
    }

    @Test
    fun rejectsZipSlipAndRecoversFromPartialExtraction() {
        val filesDir = tempFolder.newFolder("files4")
        val cacheDir = tempFolder.newFolder("cache4")
        val posix = JvmPosixOps()

        // Pre-seed stale partial rootfs without .rootfs_ready_v1 (including 0555 dir + dangling symlink)
        val staleDir = File(filesDir, "rootfs/stale_ro")
        staleDir.mkdirs()
        File(staleDir, "leftover.txt").writeText("old")
        posix.chmod(staleDir.absolutePath, 365) // 0555
        posix.symlink("/nonexistent/target", File(filesDir, "rootfs/dangling").absolutePath)

        val evilArchive = buildTarGz(
            listOf(
                SyntheticEntry.RegularFile("../outside.txt", "pwned".toByteArray())
            )
        )

        val evilManager = RootfsManager(
            filesDir = filesDir,
            cacheDir = cacheDir,
            availableAssets = setOf("rootfs-arm64-v8a.tar.gz"),
            openAsset = { ByteArrayInputStream(evilArchive) },
            supportedAbis = listOf("arm64-v8a"),
            posixOps = posix
        )

        var rejected = false
        try {
            evilManager.ensureExtracted()
        } catch (_: SecurityException) {
            rejected = true
        }
        assertTrue(rejected)
        assertFalse(File(filesDir, "outside.txt").exists())
        assertFalse(evilManager.stagingDir.exists())
        assertFalse(evilManager.isReady())

        // Now supply valid archive and verify clean recovery
        val validArchive = buildTarGz(
            listOf(
                SyntheticEntry.Dir("./bin/", mode = 493),
                SyntheticEntry.RegularFile("./bin/busybox", "ok".toByteArray(), mode = 493)
            )
        )
        val goodManager = RootfsManager(
            filesDir = filesDir,
            cacheDir = cacheDir,
            availableAssets = setOf("rootfs-arm64-v8a.tar.gz"),
            openAsset = { ByteArrayInputStream(validArchive) },
            supportedAbis = listOf("arm64-v8a"),
            posixOps = posix
        )
        goodManager.ensureExtracted()
        assertTrue(goodManager.isReady())
        assertFalse(File(goodManager.rootfsDir, "stale_ro").exists())
        assertFalse(Files.isSymbolicLink(File(goodManager.rootfsDir, "dangling").toPath()))
    }

    @Test
    fun rejectsTraversalThroughSymlinkParent() {
        val filesDir = tempFolder.newFolder("files5")
        val cacheDir = tempFolder.newFolder("cache5")
        val outsideDir = tempFolder.newFolder("outside")
        val posix = JvmPosixOps()

        val symlinkEscapeArchive = buildTarGz(
            listOf(
                SyntheticEntry.Symlink("./escape_dir", outsideDir.absolutePath),
                SyntheticEntry.RegularFile("./escape_dir/owned.txt", "bad".toByteArray())
            )
        )

        val manager = RootfsManager(
            filesDir = filesDir,
            cacheDir = cacheDir,
            availableAssets = setOf("rootfs-x86_64.tar.gz"),
            openAsset = { ByteArrayInputStream(symlinkEscapeArchive) },
            supportedAbis = listOf("x86_64"),
            posixOps = posix
        )

        var rejected = false
        try {
            manager.ensureExtracted()
        } catch (_: SecurityException) {
            rejected = true
        }
        assertTrue(rejected)
        assertFalse(File(outsideDir, "owned.txt").exists())
    }
}
