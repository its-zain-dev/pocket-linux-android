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

    @Volatile
    private var activeFramebuffer: RfbFramebuffer = RfbFramebuffer()

    override val desktopWidth: Int
        get() = activeFramebuffer.width

    override val desktopHeight: Int
        get() = activeFramebuffer.height

    val frameGeneration = AtomicLong(0L)

    private class CoalescedMoveSlot(
        var x: Int,
        var y: Int,
        val mask: Int,
        var consumed: Boolean = false
    )

    private val frameListeners = CopyOnWriteArraySet<() -> Unit>()
    private val lifecycleLock = Any()
    private val writeLock = Any()
    private val renderScratchLock = Any()
    private val pointerCoalesceLock = Any()

    private var connectionGeneration: Long = 0L
    private var activeSocket: Socket? = null
    private var activeOutput: DataOutputStream? = null
    private var writerExecutor: ExecutorService? = null
    private var readerThread: Thread? = null
    private var renderScratch: IntArray = IntArray(0)
    private var activeMoveSlot: CoalescedMoveSlot? = null
    private var lastQueuedPointerMask: Int = -1

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
            synchronized(lifecycleLock) {
                if (_connectionState.value == RfbConnectionState.Disconnected) {
                    _connectionState.value = RfbConnectionState.Error("Invalid VNC endpoint: $host:$port")
                }
            }
            return
        }

        val oldSocket: Socket?
        val oldWriter: ExecutorService?
        val oldReader: Thread?
        val gen: Long
        val newWriter: ExecutorService
        val reader: Thread

        synchronized(pointerCoalesceLock) {
            activeMoveSlot = null
            lastQueuedPointerMask = -1
        }

        synchronized(lifecycleLock) {
            gen = ++connectionGeneration
            oldSocket = activeSocket
            oldWriter = writerExecutor
            oldReader = readerThread

            activeSocket = null
            activeOutput = null
            activeFramebuffer = RfbFramebuffer()
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

            reader = Thread(
                { runSession(gen, host, port, newWriter) },
                "PocketLinux-RFB-Reader-$gen"
            ).apply {
                isDaemon = true
            }
            readerThread = reader
        }

        closeQuietly(oldSocket)
        oldWriter?.shutdownNow()
        oldReader?.interrupt()
        if (oldReader != null && oldReader !== Thread.currentThread()) {
            try {
                oldReader.join(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        synchronized(lifecycleLock) {
            if (connectionGeneration != gen) {
                newWriter.shutdownNow()
                if (writerExecutor === newWriter) {
                    writerExecutor = null
                }
                if (readerThread === reader) {
                    readerThread = null
                }
                return
            }
            reader.start()
        }
    }

    override fun disconnect() {
        val oldSocket: Socket?
        val oldWriter: ExecutorService?
        val oldReader: Thread?

        synchronized(pointerCoalesceLock) {
            activeMoveSlot = null
            lastQueuedPointerMask = -1
        }

        synchronized(lifecycleLock) {
            connectionGeneration++
            oldSocket = activeSocket
            oldWriter = writerExecutor
            oldReader = readerThread

            activeSocket = null
            activeOutput = null
            writerExecutor = null
            readerThread = null
            activeFramebuffer = RfbFramebuffer()
            supportsExtendedDesktopSize = false
            primaryScreenId = 0L
            currentServerName = ""
            _connectionState.value = RfbConnectionState.Disconnected
        }

        closeQuietly(oldSocket)
        oldWriter?.shutdownNow()
        oldReader?.interrupt()
        if (oldReader != null && oldReader !== Thread.currentThread()) {
            try {
                oldReader.join(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    override fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        val (w, h) = activeFramebuffer.dimensions()
        if (w <= 0 || h <= 0) return
        val clampedX = x.coerceIn(0, w - 1)
        val clampedY = y.coerceIn(0, h - 1)

        val moveSlotToEnqueue: CoalescedMoveSlot?
        synchronized(pointerCoalesceLock) {
            val existing = activeMoveSlot
            if (existing != null && !existing.consumed && existing.mask == buttonMask) {
                existing.x = clampedX
                existing.y = clampedY
                return
            }
            if (buttonMask == lastQueuedPointerMask) {
                val slot = CoalescedMoveSlot(clampedX, clampedY, buttonMask)
                activeMoveSlot = slot
                moveSlotToEnqueue = slot
            } else {
                activeMoveSlot = null
                lastQueuedPointerMask = buttonMask
                moveSlotToEnqueue = null
            }
        }

        if (moveSlotToEnqueue != null) {
            enqueueWrite { out ->
                val fx: Int
                val fy: Int
                synchronized(pointerCoalesceLock) {
                    moveSlotToEnqueue.consumed = true
                    if (activeMoveSlot === moveSlotToEnqueue) {
                        activeMoveSlot = null
                    }
                    fx = moveSlotToEnqueue.x
                    fy = moveSlotToEnqueue.y
                }
                RfbProtocol.writePointerEvent(out, fx, fy, moveSlotToEnqueue.mask)
            }
        } else {
            enqueueWrite { out ->
                RfbProtocol.writePointerEvent(out, clampedX, clampedY, buttonMask)
            }
        }
    }

    override fun sendKeyEvent(keysym: Int, down: Boolean) {
        enqueueWrite { out ->
            RfbProtocol.writeKeyEvent(out, keysym, down)
        }
    }

    override fun requestDesktopSize(width: Int, height: Int) {
        try {
            RfbFramebuffer.validateDimensions(width, height)
        } catch (_: IllegalArgumentException) {
            return
        }
        val gen: Long
        val executor: ExecutorService
        val screenId: Long
        synchronized(lifecycleLock) {
            if (!supportsExtendedDesktopSize) return
            gen = connectionGeneration
            executor = writerExecutor ?: return
            screenId = primaryScreenId
        }
        enqueueWriteForGeneration(gen, executor) { out ->
            RfbProtocol.writeSetDesktopSize(out, width, height, screenId)
        }
    }

    fun sendCutText(text: String) {
        enqueueWrite { out ->
            RfbProtocol.writeClientCutText(out, text)
        }
    }

    /**
     * Requests a full (non-incremental) framebuffer update from the RFB server and waits up to
     * [waitMs] for [frameGeneration] to advance so callers see the latest X11 server pixels.
     */
    fun requestFullFrameUpdate(waitMs: Long = 350L): Boolean {
        val (w, h) = activeFramebuffer.dimensions()
        if (w <= 0 || h <= 0) return false
        val startGen = frameGeneration.get()
        enqueueWrite { out ->
            RfbProtocol.writeFramebufferUpdateRequest(
                output = out,
                incremental = false,
                x = 0,
                y = 0,
                width = w,
                height = h,
                flush = true
            )
        }
        if (waitMs <= 0L) return true
        val deadline = System.nanoTime() + waitMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (frameGeneration.get() > startGen) {
                return true
            }
            val state = _connectionState.value
            if (state !is RfbConnectionState.Connected) {
                return false
            }
            try {
                Thread.sleep(20L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return frameGeneration.get() > startGen
    }

    fun snapshotFrame(): FrameSnapshot? = activeFramebuffer.snapshotPixels()

    override fun snapshotBitmap(): Bitmap? {
        val snap = activeFramebuffer.snapshotPixels() ?: return null
        return Bitmap.createBitmap(snap.pixels, snap.width, snap.height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Copies only the dirty framebuffer region into [reusable] when dimensions and mutability
     * match, avoiding full-screen pixel copies and full-texture GPU uploads on incremental updates.
     */
    fun renderIntoBitmap(reusable: Bitmap?): Bitmap? {
        for (attempt in 0 until 2) {
            val fb = activeFramebuffer
            val (w, h) = fb.dimensions()
            if (w <= 0 || h <= 0) return null

            val canReuse = reusable != null &&
                !reusable.isRecycled &&
                reusable.isMutable &&
                reusable.width == w &&
                reusable.height == h &&
                reusable.config == Bitmap.Config.ARGB_8888
            val target = if (canReuse) {
                reusable!!
            } else {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }

            synchronized(renderScratchLock) {
                val needed = w * h
                if (renderScratch.size < needed) {
                    renderScratch = IntArray(needed)
                }
                val region = fb.consumeDirtyRegionInto(
                    dest = renderScratch,
                    expectedWidth = w,
                    expectedHeight = h,
                    forceFull = !canReuse
                )
                if (region != null) {
                    if (region.width > 0 && region.height > 0) {
                        target.setPixels(
                            renderScratch,
                            0,
                            region.width,
                            region.x,
                            region.y,
                            region.width,
                            region.height
                        )
                    }
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
        val sessionFramebuffer = RfbFramebuffer()

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
            runCatching {
                s.receiveBufferSize = 524_288
                s.sendBufferSize = 65_536
            }
            s.soTimeout = handshakeTimeoutMs
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)

            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 262_144))
            val output = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 32_768))

            val serverInit = RfbProtocol.performHandshake(input, output)
            s.soTimeout = 0

            sessionFramebuffer.resize(serverInit.width, serverInit.height)

            synchronized(lifecycleLock) {
                if (connectionGeneration != gen) {
                    return
                }
            }

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
                currentServerName = serverInit.serverName
                activeFramebuffer = sessionFramebuffer
                activeOutput = output
                _connectionState.value = RfbConnectionState.Connected(
                    width = serverInit.width,
                    height = serverInit.height,
                    serverName = serverInit.serverName
                )
            }

            val listener = object : RfbProtocol.ServerMessageListener {
                override fun onFramebufferUpdated(resized: Boolean) {
                    val active = synchronized(lifecycleLock) {
                        if (connectionGeneration != gen) {
                            false
                        } else {
                            frameGeneration.incrementAndGet()
                            true
                        }
                    }
                    if (!active) return

                    // Pipeline the next FramebufferUpdateRequest immediately so Xvnc starts
                    // preparing the next frame in parallel while SurfaceView renders this frame.
                    val (w, h) = sessionFramebuffer.dimensions()
                    if (w > 0 && h > 0) {
                        val stillCurrent = synchronized(lifecycleLock) { connectionGeneration == gen }
                        if (stillCurrent) {
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

                    for (cb in frameListeners) {
                        cb()
                    }
                }

                override fun onDesktopResized(
                    width: Int,
                    height: Int,
                    extended: Boolean,
                    primaryScreenId: Long
                ) {
                    synchronized(lifecycleLock) {
                        if (connectionGeneration != gen) return
                        if (extended) {
                            supportsExtendedDesktopSize = true
                            this@RfbClient.primaryScreenId = primaryScreenId
                        }
                        _connectionState.value = RfbConnectionState.Connected(
                            width = width,
                            height = height,
                            serverName = currentServerName
                        )
                    }
                }

                override fun onBell() {
                    val active = synchronized(lifecycleLock) { connectionGeneration == gen }
                    if (active) {
                        this@RfbClient.onBell()
                    }
                }

                override fun onServerCutText(text: String) {
                    val active = synchronized(lifecycleLock) { connectionGeneration == gen }
                    if (active) {
                        this@RfbClient.onServerCutText(text)
                    }
                }
            }

            while (!Thread.currentThread().isInterrupted) {
                synchronized(lifecycleLock) {
                    if (connectionGeneration != gen) return
                }
                protocol.readServerMessage(input, sessionFramebuffer, listener)
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
        enqueueWriteForGeneration(gen, executor, action)
    }

    private inline fun enqueueWriteForGeneration(
        gen: Long,
        executor: ExecutorService,
        crossinline action: (DataOutputStream) -> Unit
    ) {
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
