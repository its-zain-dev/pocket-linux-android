package com.pocketlinux.runtime

import android.content.Context
import android.os.Build
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

interface PosixOps {
    fun chmod(path: String, mode: Int)
    fun symlink(target: String, linkPath: String)
    fun link(existingPath: String, newPath: String)
    fun readlink(path: String): String
    fun isSymbolicLink(path: File): Boolean
    fun unlinkIfExists(path: File)
}

class AndroidPosixOps : PosixOps {
    override fun chmod(path: String, mode: Int) {
        Os.chmod(path, mode)
    }

    override fun symlink(target: String, linkPath: String) {
        Os.symlink(target, linkPath)
    }

    override fun link(existingPath: String, newPath: String) {
        Os.link(existingPath, newPath)
    }

    override fun readlink(path: String): String {
        return Os.readlink(path)
    }

    override fun isSymbolicLink(path: File): Boolean {
        return Files.isSymbolicLink(path.toPath())
    }

    override fun unlinkIfExists(path: File) {
        Files.deleteIfExists(path.toPath())
    }
}

class RootfsManager(
    private val filesDir: File,
    private val cacheDir: File,
    private val availableAssets: Set<String>,
    private val openAsset: (String) -> InputStream,
    private val supportedAbis: List<String>,
    private val posixOps: PosixOps,
    private val rootfsSchemaVersion: Int = 0
) {
    constructor(context: Context) : this(
        filesDir = context.filesDir,
        cacheDir = context.cacheDir,
        availableAssets = context.assets.list("")?.toSet() ?: emptySet(),
        openAsset = { name -> context.assets.open(name) },
        supportedAbis = Build.SUPPORTED_ABIS.toList(),
        posixOps = AndroidPosixOps(),
        rootfsSchemaVersion = CURRENT_ROOTFS_SCHEMA_VERSION
    )

    val rootfsDir: File = File(filesDir, "rootfs")
    val stagingDir: File = File(filesDir, "rootfs.staging")
    val markerFile: File = File(rootfsDir, READY_MARKER_NAME)
    val sharedDir: File = File(filesDir, "shared")
    val prootTmpDir: File = File(cacheDir, "proot_tmp")
    private val schemaMarkerFile: File = File(filesDir, ".pocketlinux_rootfs_schema_v$rootfsSchemaVersion")

    private val extractLock = Any()

    fun resolveAssetName(): String {
        for (abi in supportedAbis) {
            val candidate = ABI_TO_ASSET[abi] ?: continue
            if (candidate in availableAssets) {
                return candidate
            }
        }
        throw IllegalStateException(
            "No compatible rootfs asset found for ABIs=$supportedAbis in availableAssets=$availableAssets"
        )
    }

    fun isReady(): Boolean {
        val busybox = File(rootfsDir, "bin/busybox")
        val baseReady = markerFile.isFile && (busybox.exists() || posixOps.isSymbolicLink(busybox))
        if (!baseReady) return false
        if (rootfsSchemaVersion > 0 && !schemaMarkerFile.isFile) return false
        return true
    }

    fun ensureExtracted(onProgress: (String) -> Unit = {}): Long {
        synchronized(extractLock) {
            ensureAuxDirectories()
            if (isReady()) {
                onProgress("Rootfs ready")
                return 0L
            }

            val startNs = System.nanoTime()
            val assetName = resolveAssetName()
            onProgress("Preparing Linux rootfs...")

            val userBackupDir = File(cacheDir, "rootfs_user_backup")
            val hadUserBackup = backupUserHomeDirs(userBackupDir)

            deleteRecursivelyNoFollow(stagingDir)
            deleteRecursivelyNoFollow(rootfsDir)

            if (!stagingDir.mkdirs() && !stagingDir.isDirectory) {
                throw IOException("Failed to create rootfs staging directory: $stagingDir")
            }

            val stagingPath = stagingDir.toPath().toAbsolutePath().normalize()
            val dirModes = mutableListOf<Pair<File, Int>>()
            val buffer = ByteArray(IO_BUFFER_SIZE)

            try {
                openAsset(assetName).use { rawIn ->
                    BufferedInputStream(rawIn, IO_BUFFER_SIZE).use { bufIn ->
                        GzipCompressorInputStream.builder()
                            .setInputStream(bufIn)
                            .setDecompressConcatenated(true)
                            .get()
                            .use { gzIn ->
                            TarArchiveInputStream(gzIn).use { tarIn ->
                                var count = 0
                                while (true) {
                                    val entry = tarIn.nextEntry ?: break
                                    val relPath = sanitizeTarPath(entry.name) ?: continue
                                    val targetPath = stagingPath.resolve(relPath).normalize()
                                    if (!targetPath.startsWith(stagingPath) || targetPath == stagingPath) {
                                        throw SecurityException("Zip-slip entry rejected: ${entry.name}")
                                    }

                                    ensureParentSafe(stagingPath, targetPath)
                                    val targetFile = targetPath.toFile()
                                    val mode = entry.mode and 0xFFF

                                    when {
                                        entry.isDirectory -> {
                                            if (posixOps.isSymbolicLink(targetFile)) {
                                                posixOps.unlinkIfExists(targetFile)
                                            }
                                            if (!targetFile.isDirectory && !targetFile.mkdirs()) {
                                                throw IOException("Failed to create directory: $targetFile")
                                            }
                                            if (mode != 0) {
                                                posixOps.chmod(targetFile.absolutePath, MODE_DIR_WRITABLE)
                                                dirModes.add(targetFile to mode)
                                            }
                                        }

                                        entry.isSymbolicLink -> {
                                            if (targetFile.isDirectory && !posixOps.isSymbolicLink(targetFile)) {
                                                deleteRecursivelyNoFollow(targetFile)
                                            } else {
                                                posixOps.unlinkIfExists(targetFile)
                                            }
                                            posixOps.symlink(entry.linkName, targetFile.absolutePath)
                                        }

                                        entry.isLink -> {
                                            val linkRel = sanitizeTarPath(entry.linkName)
                                                ?: throw SecurityException("Invalid hardlink target: ${entry.linkName}")
                                            val linkPath = stagingPath.resolve(linkRel).normalize()
                                            if (!linkPath.startsWith(stagingPath) || linkPath == stagingPath) {
                                                throw SecurityException("Hardlink escape rejected: ${entry.linkName}")
                                            }
                                            ensureParentSafe(stagingPath, linkPath)
                                            val linkTarget = linkPath.toFile()
                                            if (targetFile.isDirectory && !posixOps.isSymbolicLink(targetFile)) {
                                                deleteRecursivelyNoFollow(targetFile)
                                            } else {
                                                posixOps.unlinkIfExists(targetFile)
                                            }
                                            val fileMode = if (mode != 0) mode else MODE_FILE_DEFAULT
                                            try {
                                                posixOps.link(linkTarget.absolutePath, targetFile.absolutePath)
                                            } catch (e: Exception) {
                                                if (posixOps.isSymbolicLink(linkTarget)) {
                                                    val originalLinkTarget = posixOps.readlink(linkTarget.absolutePath)
                                                    posixOps.symlink(originalLinkTarget, targetFile.absolutePath)
                                                } else if (linkTarget.isFile) {
                                                    linkTarget.inputStream().use { src ->
                                                        FileOutputStream(targetFile).use { dst ->
                                                            src.copyTo(dst, IO_BUFFER_SIZE)
                                                        }
                                                    }
                                                    posixOps.chmod(targetFile.absolutePath, fileMode)
                                                } else {
                                                    throw IOException("Hardlink target missing: $linkTarget", e)
                                                }
                                            }
                                        }

                                        entry.isFile -> {
                                            if (posixOps.isSymbolicLink(targetFile) || targetFile.isDirectory) {
                                                deleteRecursivelyNoFollow(targetFile)
                                            }
                                            FileOutputStream(targetFile).use { out ->
                                                while (true) {
                                                    val read = tarIn.read(buffer)
                                                    if (read <= 0) break
                                                    out.write(buffer, 0, read)
                                                }
                                            }
                                            val fileMode = if (mode != 0) mode else MODE_FILE_DEFAULT
                                            posixOps.chmod(targetFile.absolutePath, fileMode)
                                        }

                                        else -> {
                                            // Skip device/FIFO/other special entries
                                        }
                                    }

                                    count++
                                    if (count % PROGRESS_INTERVAL == 0) {
                                        onProgress("Extracting Linux system ($count files)...")
                                    }
                                }
                            }
                        }
                    }
                }

                // Apply directory modes bottom-up so restrictive modes (e.g., 0555) don't block child chmod
                dirModes.sortedByDescending { it.first.absolutePath.length }.forEach { (dir, mode) ->
                    posixOps.chmod(dir.absolutePath, mode)
                }

                ensureStickyTmpDirs(stagingDir)

                val tmpMarker = File(stagingDir, "$READY_MARKER_NAME.tmp")
                tmpMarker.writeText("v1\n")
                val stagedMarker = File(stagingDir, READY_MARKER_NAME)
                if (!tmpMarker.renameTo(stagedMarker)) {
                    throw IOException("Failed to write readiness marker: $stagedMarker")
                }

                if (!stagingDir.renameTo(rootfsDir)) {
                    throw IOException("Failed to commit staged rootfs from $stagingDir to $rootfsDir")
                }

                if (hadUserBackup) {
                    restoreUserHomeDirs(userBackupDir)
                }
                if (rootfsSchemaVersion > 0) {
                    runCatching { schemaMarkerFile.writeText("v$rootfsSchemaVersion\n") }
                }

                onProgress("Rootfs ready")
                return ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            } catch (t: Throwable) {
                deleteRecursivelyNoFollow(stagingDir)
                throw t
            }
        }
    }

    private fun backupUserHomeDirs(backupRoot: File): Boolean {
        if (rootfsSchemaVersion <= 0 || !rootfsDir.isDirectory) return false
        return runCatching {
            deleteRecursivelyNoFollow(backupRoot)
            var copiedAny = false
            for (sub in listOf("Documents", "Downloads", "Projects", ".config/pocketlinux")) {
                val src = File(rootfsDir, "root/$sub")
                if (src.isDirectory && !posixOps.isSymbolicLink(src)) {
                    val dst = File(backupRoot, sub)
                    dst.parentFile?.mkdirs()
                    src.copyRecursively(dst, overwrite = true)
                    copiedAny = true
                }
            }
            copiedAny
        }.getOrDefault(false)
    }

    private fun restoreUserHomeDirs(backupRoot: File) {
        runCatching {
            for (sub in listOf("Documents", "Downloads", "Projects", ".config/pocketlinux")) {
                val src = File(backupRoot, sub)
                if (src.isDirectory) {
                    val dst = File(rootfsDir, "root/$sub")
                    dst.parentFile?.mkdirs()
                    src.copyRecursively(dst, overwrite = true)
                }
            }
            deleteRecursivelyNoFollow(backupRoot)
        }
    }

    private fun ensureAuxDirectories() {
        if (!sharedDir.isDirectory && !sharedDir.mkdirs()) {
            throw IOException("Failed to create shared directory: $sharedDir")
        }
        if (!prootTmpDir.isDirectory && !prootTmpDir.mkdirs()) {
            throw IOException("Failed to create proot temp directory: $prootTmpDir")
        }
    }

    private fun ensureStickyTmpDirs(root: File) {
        val tmpDir = File(root, "tmp")
        if (!posixOps.isSymbolicLink(tmpDir)) {
            if (!tmpDir.exists() && !tmpDir.mkdirs()) {
                throw IOException("Failed to create tmp directory: $tmpDir")
            }
            if (tmpDir.isDirectory) {
                posixOps.chmod(tmpDir.absolutePath, MODE_STICKY_TMP)
            }
        }
        val x11Dir = File(tmpDir, ".X11-unix")
        if (tmpDir.isDirectory && !posixOps.isSymbolicLink(tmpDir) && !posixOps.isSymbolicLink(x11Dir)) {
            if (!x11Dir.exists() && !x11Dir.mkdirs()) {
                throw IOException("Failed to create X11 unix directory: $x11Dir")
            }
            if (x11Dir.isDirectory) {
                posixOps.chmod(x11Dir.absolutePath, MODE_STICKY_TMP)
            }
        }
    }

    private fun ensureParentSafe(stagingPath: Path, targetPath: Path) {
        val parent = targetPath.parent ?: return
        if (parent == stagingPath) return
        val relParent = stagingPath.relativize(parent)
        var cur = stagingPath
        for (i in 0 until relParent.nameCount) {
            val part = relParent.getName(i).toString()
            if (part.isEmpty() || part == ".") continue
            cur = cur.resolve(part)
            val curFile = cur.toFile()
            if (posixOps.isSymbolicLink(curFile)) {
                throw SecurityException("Refusing to traverse symlink parent during extraction: $cur")
            }
            if (!curFile.exists()) {
                if (!curFile.mkdir() && !curFile.isDirectory) {
                    throw IOException("Failed to create parent directory: $curFile")
                }
            } else if (!curFile.isDirectory) {
                throw IOException("Parent path is not a directory: $curFile")
            }
        }
    }

    private fun deleteRecursivelyNoFollow(file: File) {
        if (!file.exists() && !posixOps.isSymbolicLink(file)) return
        if (!posixOps.isSymbolicLink(file) && file.isDirectory) {
            try {
                posixOps.chmod(file.absolutePath, MODE_OWNER_RWX)
            } catch (_: Exception) {
            }
            file.listFiles()?.forEach { child ->
                deleteRecursivelyNoFollow(child)
            }
        }
        posixOps.unlinkIfExists(file)
    }

    companion object {
        const val READY_MARKER_NAME = ".rootfs_ready_v1"
        const val CURRENT_ROOTFS_SCHEMA_VERSION = 3
        private const val IO_BUFFER_SIZE = 65536
        private const val PROGRESS_INTERVAL = 250
        private const val MODE_DIR_WRITABLE = 493 // 0755
        private const val MODE_FILE_DEFAULT = 420 // 0644
        private const val MODE_OWNER_RWX = 448 // 0700
        private const val MODE_STICKY_TMP = 1023 // 01777

        private val ABI_TO_ASSET = mapOf(
            "arm64-v8a" to "rootfs-arm64-v8a.tar.gz",
            "x86_64" to "rootfs-x86_64.tar.gz"
        )

        internal fun sanitizeTarPath(rawName: String): String? {
            if (rawName.indexOf('\u0000') >= 0) {
                throw SecurityException("NUL byte in tar entry path")
            }
            if (rawName.isEmpty() || rawName == "." || rawName == "./") {
                return null
            }
            if (rawName.startsWith("/")) {
                throw SecurityException("Absolute tar path rejected: $rawName")
            }
            var stripped = rawName
            while (stripped.startsWith("./")) {
                stripped = stripped.removePrefix("./")
            }
            stripped = stripped.trimEnd('/')
            if (stripped.isEmpty() || stripped == ".") {
                return null
            }
            if (stripped.startsWith("/")) {
                throw SecurityException("Absolute tar path rejected: $rawName")
            }
            val segments = stripped.split('/')
            if (segments.any { it == ".." }) {
                throw SecurityException("Parent traversal in tar path rejected: $rawName")
            }
            val clean = segments.filter { it.isNotEmpty() && it != "." }
            if (clean.isEmpty()) return null
            return clean.joinToString("/")
        }
    }
}
