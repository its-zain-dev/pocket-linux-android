package com.pocketlinux.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class AndroidNetworkBridgeTest {

    @Test
    fun bridgeForwardsHttpGetAndHttpsConnectTunnelOverLoopback() {
        val executor = Executors.newCachedThreadPool()
        val mockHttpServer = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val mockEchoServer = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))

        val bridge = AndroidNetworkBridge(listenPort = 0)
        assertTrue("Bridge should start on ephemeral port", bridge.start())
        assertTrue(bridge.isRunning())
        assertTrue(bridge.actualPort in 1..65535)

        try {
            // 1. Mock upstream HTTP origin server
            executor.execute {
                runCatching {
                    mockHttpServer.accept().use { s ->
                        val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1))
                        val reqLine = reader.readLine().orEmpty()
                        while (true) {
                            val h = reader.readLine() ?: break
                            if (h.isEmpty()) break
                        }
                        val body = "OK:$reqLine"
                        val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                        s.getOutputStream().write(resp.toByteArray(StandardCharsets.ISO_8859_1))
                        s.getOutputStream().flush()
                    }
                }
            }

            // Send HTTP proxy request via bridge
            Socket("127.0.0.1", bridge.actualPort).use { client ->
                client.soTimeout = 5_000
                val req = "GET http://127.0.0.1:${mockHttpServer.localPort}/test?ok=1 HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:${mockHttpServer.localPort}\r\n" +
                    "Proxy-Connection: keep-alive\r\n\r\n"
                client.getOutputStream().write(req.toByteArray(StandardCharsets.ISO_8859_1))
                client.getOutputStream().flush()
                val response = client.getInputStream().readBytes().toString(StandardCharsets.ISO_8859_1)
                assertTrue("Expected 200 OK in response: $response", response.startsWith("HTTP/1.1 200 OK"))
                assertTrue("Expected rewritten path in body: $response", response.contains("OK:GET /test?ok=1 HTTP/1.1"))
            }

            // 2. Mock raw TCP echo server for CONNECT tunnel test
            executor.execute {
                runCatching {
                    mockEchoServer.accept().use { s ->
                        val buf = ByteArray(64)
                        val n = s.getInputStream().read(buf)
                        if (n > 0) {
                            val reply = "ECHO:" + String(buf, 0, n, StandardCharsets.UTF_8)
                            s.getOutputStream().write(reply.toByteArray(StandardCharsets.UTF_8))
                            s.getOutputStream().flush()
                        }
                    }
                }
            }

            Socket("127.0.0.1", bridge.actualPort).use { client ->
                client.soTimeout = 5_000
                val out = client.getOutputStream()
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1))
                out.write("CONNECT 127.0.0.1:${mockEchoServer.localPort} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                out.flush()

                val statusLine = reader.readLine().orEmpty()
                assertEquals("HTTP/1.1 200 Connection Established", statusLine)
                // Consume blank line after CONNECT headers
                assertEquals("", reader.readLine())

                // Now send data through established tunnel
                out.write("PING_POCKETLINUX".toByteArray(StandardCharsets.UTF_8))
                out.flush()
                val echoed = reader.readLine().orEmpty()
                assertEquals("ECHO:PING_POCKETLINUX", echoed)
            }
        } finally {
            bridge.stop()
            runCatching { mockHttpServer.close() }
            runCatching { mockEchoServer.close() }
            executor.shutdownNow()
        }
    }
}
