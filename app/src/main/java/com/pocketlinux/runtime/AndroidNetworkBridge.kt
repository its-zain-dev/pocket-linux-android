package com.pocketlinux.runtime

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight local loopback HTTP / HTTPS CONNECT proxy running on the Android host side.
 *
 * Uses Android's native `InetAddress` / `netd` DNS resolver (supporting Android Private DNS /
 * DoT, VPNs, carrier NAT64/IPv6, and restricted Wi-Fi where raw UDP port 53 inside PRoot fails),
 * fixing "Error resolving ...: Try again" in Luakit, Firefox, Chromium, curl, git, and apk.
 */
class AndroidNetworkBridge(
    val listenPort: Int = DEFAULT_PORT,
    private val connectTimeoutMs: Int = 12_000
) : Closeable {

    private val running = AtomicBoolean(false)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var boundPort: Int = listenPort

    private var acceptThread: Thread? = null
    private var workerPool: ExecutorService? = null

    val actualPort: Int
        get() = boundPort

    fun isRunning(): Boolean = running.get() && serverSocket?.isClosed == false

    fun start(): Boolean {
        if (isRunning()) return true
        return runCatching {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort), 64)
            boundPort = ss.localPort
            serverSocket = ss
            running.set(true)

            val pool = Executors.newCachedThreadPool { r ->
                Thread(r, "PocketLinux-NetBridge-Worker").apply { isDaemon = true }
            }
            workerPool = pool

            val acceptor = Thread({ acceptLoop(ss, pool) }, "PocketLinux-NetBridge-Accept").apply {
                isDaemon = true
                start()
            }
            acceptThread = acceptor
            true
        }.getOrDefault(false)
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { acceptThread?.interrupt() }
        acceptThread = null
        runCatching { workerPool?.shutdownNow() }
        workerPool = null
    }

    override fun close() = stop()

    private fun acceptLoop(ss: ServerSocket, pool: ExecutorService) {
        while (running.get() && !ss.isClosed) {
            try {
                val client = ss.accept()
                pool.execute { handleClient(client, pool) }
            } catch (_: Throwable) {
                if (!running.get() || ss.isClosed) break
            }
        }
    }

    private fun handleClient(client: Socket, pool: ExecutorService) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = 30_000
            val clientIn = BufferedInputStream(client.getInputStream(), 16_384)
            val clientOut = client.getOutputStream()

            val headerBytes = readHttpHeaderBlock(clientIn) ?: return
            val headerText = String(headerBytes, StandardCharsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            if (lines.isEmpty()) return

            val reqParts = lines[0].trim().split(" ")
            if (reqParts.size < 3) return
            val method = reqParts[0].uppercase()
            val target = reqParts[1]
            val version = reqParts[2]

            if (method == "CONNECT") {
                val (host, port) = parseHostPort(target, 443) ?: run {
                    writeSimpleError(clientOut, "400 Bad Request", "Invalid CONNECT target")
                    return
                }
                val remote = connectUpstream(host, port) ?: run {
                    writeSimpleError(clientOut, "502 Bad Gateway", "Failed to connect to $host:$port")
                    return
                }
                upstream = remote
                client.soTimeout = 0
                remote.soTimeout = 0

                clientOut.write("$version 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                clientOut.flush()

                tunnelBidirectional(clientIn, clientOut, remote, pool)
            } else {
                val uri = runCatching { URI(target) }.getOrNull()
                var host = uri?.host
                var port = if (uri != null && uri.port > 0) uri.port else 80
                if (host.isNullOrBlank()) {
                    val hostHdr = lines.drop(1)
                        .firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                        ?.substringAfter(':')
                        ?.trim()
                    if (!hostHdr.isNullOrBlank()) {
                        val parsed = parseHostPort(hostHdr, 80)
                        if (parsed != null) {
                            host = parsed.first
                            port = parsed.second
                        }
                    }
                }
                if (host.isNullOrBlank()) {
                    writeSimpleError(clientOut, "400 Bad Request", "Missing target host")
                    return
                }

                val pathAndQuery = if (uri != null && uri.scheme != null) {
                    val p = uri.rawPath.orEmpty().ifEmpty { "/" }
                    if (uri.rawQuery != null) "$p?${uri.rawQuery}" else p
                } else {
                    target
                }

                val remote = connectUpstream(host, port) ?: run {
                    writeSimpleError(clientOut, "502 Bad Gateway", "Failed to connect to $host:$port")
                    return
                }
                upstream = remote
                client.soTimeout = 0
                remote.soTimeout = 0

                val rewritten = StringBuilder()
                rewritten.append("$method $pathAndQuery $version\r\n")
                var hasHost = false
                for (i in 1 until lines.size) {
                    val line = lines[i]
                    if (line.isEmpty()) continue
                    val lower = line.lowercase()
                    if (lower.startsWith("proxy-connection:") || lower.startsWith("proxy-authorization:")) {
                        continue
                    }
                    if (lower.startsWith("connection:")) {
                        continue
                    }
                    if (lower.startsWith("host:")) {
                        hasHost = true
                    }
                    rewritten.append(line).append("\r\n")
                }
                if (!hasHost) {
                    val hostHeader = if (port == 80) host else "$host:$port"
                    rewritten.append("Host: ").append(hostHeader).append("\r\n")
                }
                rewritten.append("Connection: close\r\n\r\n")

                val remoteOut = remote.getOutputStream()
                remoteOut.write(rewritten.toString().toByteArray(StandardCharsets.ISO_8859_1))
                remoteOut.flush()

                tunnelBidirectional(clientIn, clientOut, remote, pool)
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { upstream?.close() }
            runCatching { client.close() }
        }
    }

    private fun connectUpstream(host: String, port: Int): Socket? {
        return runCatching {
            val addrs = InetAddress.getAllByName(host)
            var lastErr: Throwable? = null
            // Prefer IPv4 first for predictable latency, fallback to IPv6
            val sorted = addrs.sortedBy { if (it.address.size == 4) 0 else 1 }
            for (addr in sorted) {
                val sock = Socket()
                try {
                    sock.tcpNoDelay = true
                    sock.connect(InetSocketAddress(addr, port), connectTimeoutMs)
                    return@runCatching sock
                } catch (t: Throwable) {
                    lastErr = t
                    runCatching { sock.close() }
                }
            }
            if (lastErr != null) throw lastErr
            null
        }.getOrNull()
    }

    private fun tunnelBidirectional(
        clientIn: InputStream,
        clientOut: OutputStream,
        upstream: Socket,
        pool: ExecutorService
    ) {
        val upIn = upstream.getInputStream()
        val upOut = upstream.getOutputStream()
        val c2u = pool.submit {
            copyStream(clientIn, upOut)
            runCatching { upstream.shutdownOutput() }
        }
        try {
            copyStream(upIn, clientOut)
        } finally {
            runCatching { c2u.cancel(true) }
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buf = ByteArray(32_768)
        try {
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Throwable) {
        }
    }

    private fun readHttpHeaderBlock(input: InputStream): ByteArray? {
        val baos = ByteArrayOutputStream(1024)
        var state = 0
        while (baos.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            baos.write(b)
            state = when (state) {
                0 -> if (b == '\r'.code) 1 else 0
                1 -> if (b == '\n'.code) 2 else if (b == '\r'.code) 1 else 0
                2 -> if (b == '\r'.code) 3 else 0
                3 -> if (b == '\n'.code) return baos.toByteArray() else 0
                else -> 0
            }
        }
        return null
    }

    private fun writeSimpleError(out: OutputStream, status: String, body: String) {
        runCatching {
            val payload = body.toByteArray(StandardCharsets.UTF_8)
            val resp = "HTTP/1.1 $status\r\nContent-Type: text/plain\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
            out.write(resp.toByteArray(StandardCharsets.ISO_8859_1))
            out.write(payload)
            out.flush()
        }
    }

    private fun parseHostPort(authority: String, defaultPort: Int): Pair<String, Int>? {
        val trimmed = authority.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("[")) {
            val closeIdx = trimmed.indexOf(']')
            if (closeIdx <= 1) return null
            val host = trimmed.substring(1, closeIdx)
            val rest = trimmed.substring(closeIdx + 1)
            val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: return null else defaultPort
            return if (port in 1..65535) host to port else null
        }
        val colon = trimmed.lastIndexOf(':')
        return if (colon > 0) {
            val host = trimmed.substring(0, colon)
            val port = trimmed.substring(colon + 1).toIntOrNull() ?: return null
            if (host.isNotBlank() && port in 1..65535) host to port else null
        } else {
            trimmed to defaultPort
        }
    }

    companion object {
        const val DEFAULT_PORT = 18080
        private const val MAX_HEADER_BYTES = 32_768
    }
}
