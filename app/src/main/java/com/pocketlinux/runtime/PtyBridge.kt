package com.pocketlinux.runtime

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object PtyBridge {
    private const val SIGHUP = 1
    private const val SIGKILL = 9
    private const val READ_POLL_TIMEOUT_MS = 100

    @Volatile
    private var libraryLoaded = false
    private val loadLock = Any()

    private fun ensureLoaded() {
        if (!libraryLoaded) {
            synchronized(loadLock) {
                if (!libraryLoaded) {
                    System.loadLibrary("ptybridge")
                    libraryLoaded = true
                }
            }
        }
    }

    private external fun nativeSpawnPty(
        argv: Array<String>,
        envp: Array<String>?,
        workingDir: String?,
        cols: Int,
        rows: Int
    ): IntArray?

    private external fun nativeRead(
        masterFd: Int,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Int
    ): Int

    private external fun nativeWrite(
        masterFd: Int,
        data: ByteArray,
        offset: Int,
        length: Int
    ): Int

    private external fun nativeSetWindowSize(masterFd: Int, cols: Int, rows: Int): Int
    private external fun nativeWaitFor(pid: Int): Int
    private external fun nativePollExit(pid: Int): Int
    private external fun nativeKill(pid: Int, sig: Int): Int
    private external fun nativeCloseFd(fd: Int): Int

    fun openSession(
        argv: List<String>,
        env: Map<String, String>? = null,
        workingDir: String? = null,
        cols: Int = 80,
        rows: Int = 24
    ): PtySession {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        require(cols in 1..65535) { "cols must be in 1..65535, got $cols" }
        require(rows in 1..65535) { "rows must be in 1..65535, got $rows" }

        ensureLoaded()

        val envp = env?.entries?.map { "${it.key}=${it.value}" }?.toTypedArray()
        val spawned = nativeSpawnPty(argv.toTypedArray(), envp, workingDir, cols, rows)
            ?: throw IOException("nativeSpawnPty failed for ${argv.first()}")
        if (spawned.size != 2 || spawned[0] <= 0 || spawned[1] < 0) {
            throw IOException("nativeSpawnPty returned invalid pair: ${spawned.contentToString()}")
        }
        return NativePtySession(pid = spawned[0], masterFd = spawned[1])
    }

    private class NativePtySession(
        override val pid: Int,
        private val masterFd: Int
    ) : PtySession {
        private val closed = AtomicBoolean(false)
        private val activeOps = AtomicInteger(1)
        private val exitCodeCache = AtomicInteger(Int.MIN_VALUE)
        private val waitLock = Any()
        private val writeLock = Any()

        private fun tryAcquireFd(): Int? {
            while (true) {
                val cur = activeOps.get()
                if (cur <= 0 || closed.get()) return null
                if (activeOps.compareAndSet(cur, cur + 1)) {
                    return masterFd
                }
            }
        }

        private fun releaseFd() {
            if (activeOps.decrementAndGet() == 0) {
                nativeCloseFd(masterFd)
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || offset > buffer.size - length) {
                throw IndexOutOfBoundsException("Invalid read bounds: offset=$offset length=$length size=${buffer.size}")
            }
            if (length == 0) return 0

            while (!closed.get()) {
                val fd = tryAcquireFd() ?: return -1
                val n = try {
                    nativeRead(fd, buffer, offset, length, READ_POLL_TIMEOUT_MS)
                } finally {
                    releaseFd()
                }
                if (n > 0) return n
                if (n == -1) return -1
                if (n < -1) {
                    if (closed.get()) return -1
                    throw IOException("PTY read failed with errno=${-n}")
                }
            }
            return -1
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > bytes.size - length) {
                throw IndexOutOfBoundsException("Invalid write bounds: offset=$offset length=$length size=${bytes.size}")
            }
            if (length == 0) return

            synchronized(writeLock) {
                val fd = tryAcquireFd() ?: throw IOException("PTY session is closed")
                try {
                    val n = nativeWrite(fd, bytes, offset, length)
                    if (n < 0) {
                        throw IOException("PTY write failed with errno=${-n}")
                    }
                    if (n != length) {
                        throw IOException("Short PTY write: wrote $n of $length bytes")
                    }
                } finally {
                    releaseFd()
                }
            }
        }

        override fun write(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            write(bytes, 0, bytes.size)
        }

        override fun resize(cols: Int, rows: Int) {
            require(cols in 1..65535) { "cols must be in 1..65535, got $cols" }
            require(rows in 1..65535) { "rows must be in 1..65535, got $rows" }

            val fd = tryAcquireFd() ?: return
            try {
                nativeSetWindowSize(fd, cols, rows)
            } finally {
                releaseFd()
            }
        }

        override fun isAlive(): Boolean {
            if (exitCodeCache.get() != Int.MIN_VALUE) return false
            synchronized(waitLock) {
                if (exitCodeCache.get() != Int.MIN_VALUE) return false
                val polled = nativePollExit(pid)
                return if (polled == -2) {
                    true
                } else {
                    exitCodeCache.compareAndSet(Int.MIN_VALUE, polled)
                    false
                }
            }
        }

        override fun waitFor(): Int {
            val cached = exitCodeCache.get()
            if (cached != Int.MIN_VALUE) return cached
            synchronized(waitLock) {
                val cachedInside = exitCodeCache.get()
                if (cachedInside != Int.MIN_VALUE) return cachedInside
                val status = nativeWaitFor(pid)
                exitCodeCache.set(status)
                return status
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                releaseFd()
                if (isAlive()) {
                    nativeKill(pid, SIGHUP)
                    val deadline = System.nanoTime() + 150_000_000L
                    while (isAlive() && System.nanoTime() < deadline) {
                        try {
                            Thread.sleep(15)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                    if (isAlive()) {
                        nativeKill(pid, SIGKILL)
                    }
                }
                waitFor()
            }
        }

        override val inputStream: InputStream = object : InputStream() {
            override fun read(): Int {
                val single = ByteArray(1)
                val n = this@NativePtySession.read(single, 0, 1)
                return if (n <= 0) -1 else (single[0].toInt() and 0xFF)
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                return this@NativePtySession.read(b, off, len)
            }

            override fun close() {
                this@NativePtySession.close()
            }
        }

        override val outputStream: OutputStream = object : OutputStream() {
            override fun write(b: Int) {
                val single = byteArrayOf(b.toByte())
                this@NativePtySession.write(single, 0, 1)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                this@NativePtySession.write(b, off, len)
            }

            override fun close() {
                this@NativePtySession.close()
            }
        }
    }
}
