package com.pocketlinux.rfb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater

class RfbClientLoopbackTest {

    private data class RecordedClientMessage(
        val type: Int,
        val arg1: Int,
        val arg2: Int,
        val arg3: Int
    )

    private fun waitUntil(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(15)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", predicate())
    }

    @Test
    fun loopbackClientCompletesHandshakeReceivesUpdatesSendsEventsAndReconnectsCleanly() {
        val receivedMessages = CopyOnWriteArrayList<RecordedClientMessage>()
        val firstUpdateSentLatch = CountDownLatch(1)
        val resizeReceivedLatch = CountDownLatch(1)
        val secondServerConnectedLatch = CountDownLatch(1)

        val server1 = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val server1Thread = Thread {
            server1.accept().use { client ->
                val input = DataInputStream(client.getInputStream())
                val output = DataOutputStream(client.getOutputStream())

                // Handshake
                output.write("RFB 003.008\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()

                val clientVer = ByteArray(12)
                input.readFully(clientVer)

                output.writeByte(1)
                output.writeByte(RfbConstants.SECURITY_NONE)
                output.flush()

                val chosenSec = input.readUnsignedByte()
                assertEquals(RfbConstants.SECURITY_NONE, chosenSec)

                output.writeInt(0) // SecurityResult OK
                output.flush()

                val shared = input.readUnsignedByte()
                assertEquals(1, shared)

                // ServerInit: 32x24
                output.writeShort(32)
                output.writeShort(24)
                output.writeByte(32); output.writeByte(24); output.writeByte(0); output.writeByte(1)
                output.writeShort(255); output.writeShort(255); output.writeShort(255)
                output.writeByte(16); output.writeByte(8); output.writeByte(0)
                output.write(ByteArray(3))
                val name = "LoopbackVNC".toByteArray(StandardCharsets.UTF_8)
                output.writeInt(name.size)
                output.write(name)
                output.flush()

                // Read SetPixelFormat + SetEncodings + first FBUpdateRequest
                var sentInitialFrame = false
                while (!client.isClosed) {
                    val msgType = try {
                        input.readUnsignedByte()
                    } catch (_: Throwable) {
                        break
                    }
                    when (msgType) {
                        RfbConstants.MSG_SET_PIXEL_FORMAT -> {
                            input.readFully(ByteArray(19))
                        }
                        RfbConstants.MSG_SET_ENCODINGS -> {
                            input.readUnsignedByte()
                            val count = input.readUnsignedShort()
                            repeat(count) { input.readInt() }
                        }
                        RfbConstants.MSG_FB_UPDATE_REQUEST -> {
                            val inc = input.readUnsignedByte()
                            val rx = input.readUnsignedShort()
                            val ry = input.readUnsignedShort()
                            val rw = input.readUnsignedShort()
                            val rh = input.readUnsignedShort()
                            if (!sentInitialFrame) {
                                sentInitialFrame = true
                                val deflater = Deflater()
                                val raw = ByteArray(2 * 2 * 4) { 0x42 }
                                deflater.setInput(raw)
                                val comp = ByteArray(128)
                                val compLen = deflater.deflate(comp, 0, comp.size, Deflater.SYNC_FLUSH)
                                deflater.end()

                                output.writeByte(RfbConstants.SMSG_FB_UPDATE)
                                output.writeByte(0)
                                output.writeShort(2)

                                // Zlib 2x2 rect
                                output.writeShort(rx); output.writeShort(ry)
                                output.writeShort(2); output.writeShort(2)
                                output.writeInt(RfbConstants.ENCODING_ZLIB)
                                output.writeInt(compLen)
                                output.write(comp, 0, compLen)

                                // ExtendedDesktopSize announcing support (stay 32x24)
                                output.writeShort(0); output.writeShort(0)
                                output.writeShort(32); output.writeShort(24)
                                output.writeInt(RfbConstants.ENCODING_EXTENDED_DESKTOP_SIZE)
                                output.writeByte(1)
                                output.write(ByteArray(3))
                                output.writeInt(7)
                                output.writeShort(0); output.writeShort(0)
                                output.writeShort(32); output.writeShort(24)
                                output.writeInt(0)
                                output.flush()
                                firstUpdateSentLatch.countDown()
                            } else {
                                // Keep parameters referenced cleanly
                                assertTrue(inc >= 0 && rw >= 0 && rh >= 0)
                            }
                        }
                        RfbConstants.MSG_KEY_EVENT -> {
                            val down = input.readUnsignedByte()
                            input.readUnsignedShort()
                            val keysym = input.readInt()
                            receivedMessages.add(RecordedClientMessage(msgType, down, keysym, 0))
                        }
                        RfbConstants.MSG_POINTER_EVENT -> {
                            val mask = input.readUnsignedByte()
                            val px = input.readUnsignedShort()
                            val py = input.readUnsignedShort()
                            receivedMessages.add(RecordedClientMessage(msgType, mask, px, py))
                        }
                        RfbConstants.MSG_SET_DESKTOP_SIZE -> {
                            input.readUnsignedByte()
                            val nw = input.readUnsignedShort()
                            val nh = input.readUnsignedShort()
                            val nScreens = input.readUnsignedByte()
                            input.readUnsignedByte()
                            repeat(nScreens) {
                                input.readInt()
                                input.readUnsignedShort(); input.readUnsignedShort()
                                input.readUnsignedShort(); input.readUnsignedShort()
                                input.readInt()
                            }
                            receivedMessages.add(RecordedClientMessage(msgType, nw, nh, 0))
                            resizeReceivedLatch.countDown()
                        }
                        else -> break
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val server2 = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val server2Thread = Thread {
            server2.accept().use { client ->
                val input = DataInputStream(client.getInputStream())
                val output = DataOutputStream(client.getOutputStream())
                output.write("RFB 003.008\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                input.readFully(ByteArray(12))
                output.writeByte(1); output.writeByte(1); output.flush()
                input.readUnsignedByte()
                output.writeInt(0); output.flush()
                input.readUnsignedByte()
                output.writeShort(80); output.writeShort(60)
                output.writeByte(32); output.writeByte(24); output.writeByte(0); output.writeByte(1)
                output.writeShort(255); output.writeShort(255); output.writeShort(255)
                output.writeByte(16); output.writeByte(8); output.writeByte(0)
                output.write(ByteArray(3))
                val name = "SecondVNC".toByteArray(StandardCharsets.UTF_8)
                output.writeInt(name.size); output.write(name); output.flush()
                secondServerConnectedLatch.countDown()
                Thread.sleep(500)
            }
        }.apply { isDaemon = true; start() }

        val client = RfbClient()
        val framesSeen = CopyOnWriteArrayList<Long>()
        val removeListener = client.addFrameListener {
            framesSeen.add(client.frameGeneration.get())
        }

        try {
            client.connect("127.0.0.1", server1.localPort)
            waitUntil { client.connectionState.value is RfbConnectionState.Connected }

            val connected = client.connectionState.value as RfbConnectionState.Connected
            assertEquals(32, connected.width)
            assertEquals(24, connected.height)
            assertEquals("LoopbackVNC", connected.serverName)
            assertEquals(32, client.desktopWidth)
            assertEquals(24, client.desktopHeight)

            assertTrue(firstUpdateSentLatch.await(5, TimeUnit.SECONDS))
            waitUntil { client.frameGeneration.get() >= 1L }
            assertTrue(framesSeen.isNotEmpty())

            client.sendPointerEvent(15, 10, RfbConstants.BUTTON_LEFT)
            client.sendKeyEvent(KeySymMapper.XK_RETURN, true)
            client.sendKeyEvent(KeySymMapper.XK_RETURN, false)
            client.requestDesktopSize(1280, 720)

            assertTrue(resizeReceivedLatch.await(5, TimeUnit.SECONDS))
            waitUntil { receivedMessages.size >= 4 }

            assertTrue(
                receivedMessages.any {
                    it.type == RfbConstants.MSG_POINTER_EVENT &&
                        it.arg1 == RfbConstants.BUTTON_LEFT &&
                        it.arg2 == 15 &&
                        it.arg3 == 10
                }
            )
            assertTrue(
                receivedMessages.any {
                    it.type == RfbConstants.MSG_KEY_EVENT &&
                        it.arg1 == 1 &&
                        it.arg2 == KeySymMapper.XK_RETURN
                }
            )
            assertTrue(
                receivedMessages.any {
                    it.type == RfbConstants.MSG_SET_DESKTOP_SIZE &&
                        it.arg1 == 1280 &&
                        it.arg2 == 720
                }
            )

            // Clean disconnect
            client.disconnect()
            assertEquals(RfbConnectionState.Disconnected, client.connectionState.value)

            // Reconnect to server 2
            client.connect("127.0.0.1", server2.localPort)
            assertTrue(secondServerConnectedLatch.await(5, TimeUnit.SECONDS))
            waitUntil {
                val st = client.connectionState.value
                st is RfbConnectionState.Connected && st.serverName == "SecondVNC"
            }
            val second = client.connectionState.value as RfbConnectionState.Connected
            assertEquals(80, second.width)
            assertEquals(60, second.height)
        } finally {
            removeListener()
            client.close()
            server1.close()
            server2.close()
            server1Thread.join(1_000)
            server2Thread.join(1_000)
        }
    }

    @Test
    fun connectToClosedPortTransitionsToErrorAndSupportsDisconnect() {
        val unusedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val client = RfbClient(connectTimeoutMs = 1_000, handshakeTimeoutMs = 1_000)
        try {
            client.connect("127.0.0.1", unusedPort)
            waitUntil { client.connectionState.value is RfbConnectionState.Error }
            client.disconnect()
            assertEquals(RfbConnectionState.Disconnected, client.connectionState.value)
        } finally {
            client.close()
        }
    }
}
