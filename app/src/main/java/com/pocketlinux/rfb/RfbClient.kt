package com.pocketlinux.rfb

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure-Kotlin RFB 3.8 client implementing [RfbController].
 * Socket I/O and framebuffer decoding run on background threads with zero android.* runtime
 * dependencies outside [snapshotBitmap] and [renderIntoBitmap], allowing full JVM loopback tests.
 */
class RfbClient(
    private val connectTimeoutMs: Int = 5_000,
    private val handshakeTimeoutMs: Int = 5_000,
    private val onBell: () -> Unit = {},
    private val onServerCutText: (String) -> Unit = {}
) : RfbController, Closeable {

    private val _connectionState = MutableStateFlow<RfbConnectionState>(RfbConnectionState.Disconnected)
    override val connectionState: StateFlow<RfbConnectionState> = _connectionState.asStateFlow()

    private val framebuffer = RfbFramebuffer()

    override val desktopWidth: Int
        get() = framebuffer.width

    override val desktopHeight: Int
        get() = framebuffer.height

    val frameGeneration = AtomicLong(0L)

    private val frameListeners = CopyOnWriteArraySet<() -> Unit>()
    private val lifecycleLock = Any()
    private val writeLock = Any()
    private val renderScratchLock = Any()

    private var connectionGeneration: Long = 0L
    private var activeSocket: Socket? = null
    private var activeOutput: DataOutputStream? = null
    private var writerExecutor: ExecutorService? = null
    private var readerThread: Thread? = null
    private var renderScratch: IntArray = IntArray(0)

    @Volatile
    private var supportsExtendedDesktopSize: Boolean = false

    @Volatile
    private var primaryScreenId: Long = 0L

    @Volatile
    private var currentServerName: String = ""

    fun addFrameListener(listener: () -> Unit): () -> Unit {
        frameListeners.add(listener)
        return { frameListeners.remove(listener) }
    }

    fun removeFrameListener(listener: () -> Unit) {
        frameListeners.remove(listener)
    }

    override fun connect(host: String, port: Int) {
        if (host.isBlank() || port !in 1..65535) {
            disconnect()
            _connectionState.value = RfbConnectionState.Error("Invalid VNC endpoint: $host:$port")
            return
        }

        val oldSocket: Socket?
        val oldWriter: ExecutorService?
        val oldReader: Thread?
        val gen: Long
        val newWriter: ExecutorService

        synchronized(lifecycleLock) {
            gen = ++connectionGeneration
            oldSocket = activeSocket
            oldWriter = writerExecutor
            oldReader = readerThread

            activeSocket = null
            activeOutput = null
            supportsExtendedDesktopSize = false
            primaryScreenId = 0L
            currentServerName = ""

            _connectionState.value = RfbConnectionState.Connecting

            newWriter = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "PocketLinux-RFB-Writer-$gen").apply {
                    isDaemon = true
                }
            }
            writerExecutor = newWriter

            val reader = Thread(
                { runSession(gen, host, port, newWriter) },
                "PocketLinux-RFB-Reader-$gen"
            ).apply {
                isDaemon = true
            }
            readerThread = reader
            reader.start()
        }

        closeQuietly(oldSocket)
        oldWriter?.shutdownNow()
        oldReader?.interrupt()
    }

    override fun disconnect() {
        val oldSocket: Socket?
        val oldWriter: ExecutorService?
        val oldReader: Thread?

        synchronized(lifecycleLock) {
            connectionGeneration++
            oldSocket = activeSocket
            oldWriter = writerExecutor
            oldReader = readerThread

            activeSocket = null
            activeOutput = null
            writerExecutor = null
            readerThread = null
            supportsExtendedDesktopSize = false
            primaryScreenId = 0L
            currentServerName = ""
            _connectionState.value = RfbConnectionState.Disconnected
        }

        closeQuietly(oldSocket)
        oldWriter?.shutdownNow()
        oldReader?.interrupt()
    }

    override fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        val w = framebuffer.width
        val h = framebuffer.height
        if (w <= 0 || h <= 0) return
        val clampedX = x.coerceIn(0, w - 1)
        val clampedY = y.coerceIn(0, h - 1)

        enqueueWrite { out ->
            RfbProtocol.writePointerEvent(out, clampedX, clampedY, buttonMask)
        }
    }

    override fun sendKeyEvent(keysym: Int, down: Boolean) {
        enqueueWrite { out ->
            RfbProtocol.writeKeyEvent(out, keysym, down)
        }
    }

    override fun requestDesktopSize(width: Int, height: Int) {
        if (!supportsExtendedDesktopSize) return
        try {
            RfbFramebuffer.validateDimensions(width, height)
        } catch (_: IllegalArgumentException) {
            return
        }
        val screenId = primaryScreenId
        enqueueWrite { out ->
            RfbProtocol.writeSetDesktopSize(out, width, height, screenId)
        }
    }

    fun sendCutText(text: String) {
        enqueueWrite { out ->
            RfbProtocol.writeClientCutText(out, text)
        }
    }

    override fun snapshotBitmap(): Bitmap? {
        val snap = framebuffer.snapshotPixels() ?: return null
        return Bitmap.createBitmap(snap.pixels, snap.width, snap.height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Copies the latest framebuffer into [reusable] when dimensions and mutability match,
     * avoiding per-frame allocations on the dedicated SurfaceView render thread.
     */
    fun renderIntoBitmap(reusable: Bitmap?): Bitmap? {
        for (attempt in 0 until 2) {
            val (w, h) = framebuffer.dimensions()
            if (w <= 0 || h <= 0) return null

            val target = if (
                reusable != null &&
                !reusable.isRecycled &&
                reusable.isMutable &&
                reusable.width == w &&
                reusable.height == h &&
                reusable.config == Bitmap.Config.ARGB_8888
            ) {
                reusable
            } else {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }

            synchronized(renderScratchLock) {
                val needed = w * h
                if (renderScratch.size < needed) {
                    renderScratch = IntArray(needed)
                }
                if (framebuffer.copyPixelsInto(renderScratch, w, h)) {
                    target.setPixels(renderScratch, 0, w, 0, 0, w, h)
                    return target
                }
            }
        }
        return null
    }

    override fun close() {
        disconnect()
    }

    private fun runSession(
        gen: Long,
        host: String,
        port: Int,
        localWriter: ExecutorService
    ) {
        var socket: Socket? = null
        val protocol = RfbProtocol()

        try {
            val s = Socket()
            synchronized(lifecycleLock) {
                if (connectionGeneration != gen) {
                    closeQuietly(s)
                    return
                }
                activeSocket = s
            }
            socket = s

            s.tcpNoDelay = true
            s.soTimeout = handshakeTimeoutMs
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)

            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 65_536))
            val output = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 16_384))

            val serverInit = RfbProtocol.performHandshake(input, output)
            s.soTimeout = 0

            framebuffer.resize(serverInit.width, serverInit.height)
            currentServerName = serverInit.serverName

            synchronized(writeLock) {
                RfbProtocol.writeSetPixelFormat(output, flush = false)
                RfbProtocol.writeSetEncodings(output, flush = false)
                RfbProtocol.writeFramebufferUpdateRequest(
                    output = output,
                    incremental = false,
                    x = 0,
                    y = 0,
                    width = serverInit.width,
                    height = serverInit.height,
                    flush = true
                )
            }

            synchronized(lifecycleLock) {
                if (connectionGeneration != gen) {
                    return
                }
                activeOutput = output
                _connectionState.value = RfbConnectionState.Connected(
                    width = serverInit.width,
                    height = serverInit.height,
                    serverName = serverInit.serverName
                )
            }

            val listener = object : RfbProtocol.ServerMessageListener {
                override fun onFramebufferUpdated(resized: Boolean) {
                    frameGeneration.incrementAndGet()
                    for (cb in frameListeners) {
                        cb()
                    }
                    synchronized(lifecycleLock) {
                        if (connectionGeneration != gen) return
                    }
                    val (w, h) = framebuffer.dimensions()
                    if (w > 0 && h > 0) {
                        synchronized(writeLock) {
                            RfbProtocol.writeFramebufferUpdateRequest(
                                output = output,
                                incremental = !resized,
                                x = 0,
                                y = 0,
                                width = w,
                                height = h,
                                flush = true
                            )
                        }
                    }
                }

                override fun onDesktopResized(
                    width: Int,
                    height: Int,
                    extended: Boolean,
                    primaryScreenId: Long
                ) {
                    if (extended) {
                        supportsExtendedDesktopSize = true
                        this@RfbClient.primaryScreenId = primaryScreenId
                    }
                    synchronized(lifecycleLock) {
                        if (connectionGeneration == gen) {
                            _connectionState.value = RfbConnectionState.Connected(
                                width = width,
                                height = height,
                                serverName = currentServerName
                            )
                        }
                    }
                }

                override fun onBell() {
                    this@RfbClient.onBell()
                }

                override fun onServerCutText(text: String) {
                    this@RfbClient.onServerCutText(text)
                }
            }

            while (!Thread.currentThread().isInterrupted) {
                synchronized(lifecycleLock) {
                    if (connectionGeneration != gen) return
                }
                protocol.readServerMessage(input, framebuffer, listener)
            }
        } catch (t: Throwable) {
            synchronized(lifecycleLock) {
                if (connectionGeneration == gen) {
                    _connectionState.value = RfbConnectionState.Error(
                        t.message ?: t.javaClass.simpleName
                    )
                }
            }
        } finally {
            protocol.close()
            closeQuietly(socket)
            synchronized(lifecycleLock) {
                if (connectionGeneration == gen) {
                    if (activeSocket === socket) {
                        activeSocket = null
                    }
                    activeOutput = null
                    if (writerExecutor === localWriter) {
                        writerExecutor = null
                    }
                    if (readerThread === Thread.currentThread()) {
                        readerThread = null
                    }
                }
            }
            localWriter.shutdownNow()
        }
    }

    private inline fun enqueueWrite(crossinline action: (DataOutputStream) -> Unit) {
        val gen: Long
        val executor: ExecutorService
        synchronized(lifecycleLock) {
            gen = connectionGeneration
            executor = writerExecutor ?: return
        }
        try {
            executor.execute {
                val out = synchronized(lifecycleLock) {
                    if (connectionGeneration != gen) null else activeOutput
                } ?: return@execute
                try {
                    synchronized(writeLock) {
                        action(out)
                    }
                } catch (t: Throwable) {
                    val sockToClose: Socket?
                    synchronized(lifecycleLock) {
                        if (connectionGeneration == gen) {
                            _connectionState.value = RfbConnectionState.Error(
                                t.message ?: t.javaClass.simpleName
                            )
                            sockToClose = activeSocket
                        } else {
                            sockToClose = null
                        }
                    }
                    closeQuietly(sockToClose)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor already shut down during disconnect/reconnect.
        }
    }

    private fun closeQuietly(socket: Socket?) {
        if (socket != null) {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }
}
