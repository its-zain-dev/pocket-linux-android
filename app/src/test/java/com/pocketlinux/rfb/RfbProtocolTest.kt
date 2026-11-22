package com.pocketlinux.rfb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater

class RfbProtocolTest {

    private fun buildServerHandshakeBytes(
        version: String = "RFB 003.008\n",
        securityTypes: ByteArray = byteArrayOf(1),
        securityResult: Int = 0,
        width: Int = 640,
        height: Int = 480,
        serverName: String = "PocketLinux"
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        out.write(version.toByteArray(StandardCharsets.US_ASCII))
        out.writeByte(securityTypes.size)
        out.write(securityTypes)
        out.writeInt(securityResult)
        out.writeShort(width)
        out.writeShort(height)
        // 16-byte PixelFormat
        out.writeByte(32)
        out.writeByte(24)
        out.writeByte(0)
        out.writeByte(1)
        out.writeShort(255)
        out.writeShort(255)
        out.writeShort(255)
        out.writeByte(16)
        out.writeByte(8)
        out.writeByte(0)
        out.write(ByteArray(3))
        val nameBytes = serverName.toByteArray(StandardCharsets.UTF_8)
        out.writeInt(nameBytes.size)
        out.write(nameBytes)
        out.flush()
        return baos.toByteArray()
    }

    @Test
    fun performHandshakeSucceedsForRfb38WithSecurityNone() {
        val serverBytes = buildServerHandshakeBytes(width = 1024, height = 600, serverName = "Alpine-JWM")
        val clientOut = ByteArrayOutputStream()
        val init = RfbProtocol.performHandshake(
            DataInputStream(ByteArrayInputStream(serverBytes)),
            DataOutputStream(clientOut)
        )

        assertEquals(1024, init.width)
        assertEquals(600, init.height)
        assertEquals("Alpine-JWM", init.serverName)
        assertEquals(32, init.pixelFormat.bitsPerPixel)
        assertEquals(24, init.pixelFormat.depth)

        val sent = clientOut.toByteArray()
        assertEquals("RFB 003.008\n", String(sent, 0, 12, StandardCharsets.US_ASCII))
        assertEquals(RfbConstants.SECURITY_NONE, sent[12].toInt() and 0xFF)
        assertEquals(1, sent[13].toInt() and 0xFF) // ClientInit shared = 1
        assertEquals(14, sent.size)
    }

    @Test
    fun performHandshakeRejectsMissingSecurityNoneAndNonZeroSecurityResult() {
        val noNoneBytes = buildServerHandshakeBytes(securityTypes = byteArrayOf(2))
        var threwNoNone = false
        try {
            RfbProtocol.performHandshake(
                DataInputStream(ByteArrayInputStream(noNoneBytes)),
                DataOutputStream(ByteArrayOutputStream())
            )
        } catch (_: RfbProtocolException) {
            threwNoNone = true
        }
        assertTrue(threwNoNone)

        val failResultBaos = ByteArrayOutputStream()
        val failOut = DataOutputStream(failResultBaos)
        failOut.write("RFB 003.008\n".toByteArray(StandardCharsets.US_ASCII))
        failOut.writeByte(1)
        failOut.writeByte(1)
        failOut.writeInt(1)
        val reason = "Denied".toByteArray(StandardCharsets.UTF_8)
        failOut.writeInt(reason.size)
        failOut.write(reason)
        failOut.flush()

        var threwFailResult = false
        try {
            RfbProtocol.performHandshake(
                DataInputStream(ByteArrayInputStream(failResultBaos.toByteArray())),
                DataOutputStream(ByteArrayOutputStream())
            )
        } catch (e: RfbProtocolException) {
            assertTrue(e.message!!.contains("Denied"))
            threwFailResult = true
        }
        assertTrue(threwFailResult)

        // numSecurityTypes == 0 with reason
        val zeroSecBaos = ByteArrayOutputStream()
        val zeroSecOut = DataOutputStream(zeroSecBaos)
        zeroSecOut.write("RFB 003.008\n".toByteArray(StandardCharsets.US_ASCII))
        zeroSecOut.writeByte(0)
        val zeroReason = "Too many connections".toByteArray(StandardCharsets.UTF_8)
        zeroSecOut.writeInt(zeroReason.size)
        zeroSecOut.write(zeroReason)
        zeroSecOut.flush()

        var threwZeroSec = false
        try {
            RfbProtocol.performHandshake(
                DataInputStream(ByteArrayInputStream(zeroSecBaos.toByteArray())),
                DataOutputStream(ByteArrayOutputStream())
            )
        } catch (e: RfbProtocolException) {
            assertTrue(e.message!!.contains("Too many connections"))
            threwZeroSec = true
        }
        assertTrue(threwZeroSec)
    }

    @Test
    fun clientOutboundMessagesSerializeExactWireBytes() {
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)

        RfbProtocol.writeSetPixelFormat(out)
        assertArrayEquals(
            byteArrayOf(
                0, 0, 0, 0,
                32, 24, 0, 1,
                0, -1, 0, -1, 0, -1,
                16, 8, 0,
                0, 0, 0
            ),
            baos.toByteArray()
        )

        baos.reset()
        RfbProtocol.writeSetEncodings(out, intArrayOf(1, 5, 6, 0, -223, -308))
        val encIn = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(2, encIn.readUnsignedByte())
        assertEquals(0, encIn.readUnsignedByte())
        assertEquals(6, encIn.readUnsignedShort())
        assertEquals(1, encIn.readInt())
        assertEquals(5, encIn.readInt())
        assertEquals(6, encIn.readInt())
        assertEquals(0, encIn.readInt())
        assertEquals(-223, encIn.readInt())
        assertEquals(-308, encIn.readInt())

        baos.reset()
        RfbProtocol.writeFramebufferUpdateRequest(out, true, 10, 20, 300, 200)
        val fbReqIn = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(3, fbReqIn.readUnsignedByte())
        assertEquals(1, fbReqIn.readUnsignedByte())
        assertEquals(10, fbReqIn.readUnsignedShort())
        assertEquals(20, fbReqIn.readUnsignedShort())
        assertEquals(300, fbReqIn.readUnsignedShort())
        assertEquals(200, fbReqIn.readUnsignedShort())

        baos.reset()
        RfbProtocol.writeKeyEvent(out, KeySymMapper.XK_RETURN, true)
        val keyIn = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(4, keyIn.readUnsignedByte())
        assertEquals(1, keyIn.readUnsignedByte())
        assertEquals(0, keyIn.readUnsignedShort())
        assertEquals(KeySymMapper.XK_RETURN, keyIn.readInt())

        baos.reset()
        RfbProtocol.writePointerEvent(out, 512, 300, RfbConstants.BUTTON_RIGHT)
        val ptrIn = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(5, ptrIn.readUnsignedByte())
        assertEquals(4, ptrIn.readUnsignedByte())
        assertEquals(512, ptrIn.readUnsignedShort())
        assertEquals(300, ptrIn.readUnsignedShort())

        baos.reset()
        RfbProtocol.writeSetDesktopSize(out, 1280, 720, screenId = 42L)
        val dsIn = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(251, dsIn.readUnsignedByte())
        assertEquals(0, dsIn.readUnsignedByte())
        assertEquals(1280, dsIn.readUnsignedShort())
        assertEquals(720, dsIn.readUnsignedShort())
        assertEquals(1, dsIn.readUnsignedByte())
        assertEquals(0, dsIn.readUnsignedByte())
        assertEquals(42, dsIn.readInt())
        assertEquals(0, dsIn.readUnsignedShort())
        assertEquals(0, dsIn.readUnsignedShort())
        assertEquals(1280, dsIn.readUnsignedShort())
        assertEquals(720, dsIn.readUnsignedShort())
        assertEquals(0, dsIn.readInt())
    }

    @Test
    fun rawAndCopyRectDecodersUpdateFramebufferAccurately() {
        val fb = RfbFramebuffer()
        fb.resize(4, 4)
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)

        out.writeByte(RfbConstants.SMSG_FB_UPDATE)
        out.writeByte(0)
        out.writeShort(2)

        // Rect 1: Raw 2x2 at (0,0)
        out.writeShort(0); out.writeShort(0); out.writeShort(2); out.writeShort(2)
        out.writeInt(RfbConstants.ENCODING_RAW)
        out.write(byteArrayOf(
            0x10, 0x20, 0x30, 0x00,
            0x40, 0x50, 0x60, 0x00,
            0x70, 0x80.toByte(), 0x90.toByte(), 0x00,
            0x01, 0x02, 0x03, 0x00
        ))

        // Rect 2: CopyRect (0,0) -> (2,2) 2x2
        out.writeShort(2); out.writeShort(2); out.writeShort(2); out.writeShort(2)
        out.writeInt(RfbConstants.ENCODING_COPYRECT)
        out.writeShort(0); out.writeShort(0)
        out.flush()

        var updates = 0
        RfbProtocol().use { proto ->
            proto.readServerMessage(
                DataInputStream(ByteArrayInputStream(baos.toByteArray())),
                fb,
                object : RfbProtocol.ServerMessageListener {
                    override fun onFramebufferUpdated(resized: Boolean) {
                        assertFalse(resized)
                        updates++
                    }
                }
            )
        }

        assertEquals(1, updates)
        assertEquals(0xFF302010.toInt(), fb.getPixel(2, 2))
        assertEquals(0xFF605040.toInt(), fb.getPixel(3, 2))
        assertEquals(0xFF908070.toInt(), fb.getPixel(2, 3))
        assertEquals(0xFF030201.toInt(), fb.getPixel(3, 3))
    }

    @Test
    fun hextileDecoderHandlesEdgeTilesPersistentColorsSubrectsAndRawIsolation() {
        val fb = RfbFramebuffer()
        fb.resize(20, 4) // 2 tiles across: 16x4 and 4x4 (edge tile clamping)

        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        out.writeByte(RfbConstants.SMSG_FB_UPDATE)
        out.writeByte(0)
        out.writeShort(1)
        out.writeShort(0); out.writeShort(0); out.writeShort(20); out.writeShort(4)
        out.writeInt(RfbConstants.ENCODING_HEXTILE)

        // Tile 0 (16x4): BackgroundSpecified (0xFF112233) + ForegroundSpecified (0xFFAABBCC) + 1 mono subrect (2,1,3x2)
        out.writeByte(0x02 or 0x04 or 0x08)
        out.write(byteArrayOf(0x33, 0x22, 0x11, 0x00)) // bg BGRX
        out.write(byteArrayOf(0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte(), 0x00)) // fg BGRX
        out.writeByte(1) // 1 subrect
        out.writeByte((2 shl 4) or 1) // x=2, y=1
        out.writeByte(((3 - 1) shl 4) or (2 - 1)) // w=3, h=2

        // Tile 1 (4x4): Raw bit set -> 16 pixels, must NOT clobber bg/fg for subsequent tiles
        out.writeByte(0x01)
        for (i in 0 until 16) {
            out.write(byteArrayOf(0x05, 0x06, 0x07, 0x00))
        }
        out.flush()

        // Test persistence within one Hextile rect: 36x4 -> 3 tiles (16x4, 16x4, 4x4)
        val fb2 = RfbFramebuffer()
        fb2.resize(36, 4)
        val baos3 = ByteArrayOutputStream()
        val out3 = DataOutputStream(baos3)
        out3.writeByte(RfbConstants.SMSG_FB_UPDATE)
        out3.writeByte(0)
        out3.writeShort(1)
        out3.writeShort(0); out3.writeShort(0); out3.writeShort(36); out3.writeShort(4)
        out3.writeInt(RfbConstants.ENCODING_HEXTILE)

        // Tile 0 (16x4): set bg & fg
        out3.writeByte(0x02 or 0x04)
        out3.write(byteArrayOf(0x10, 0x20, 0x30, 0x00)) // bg = 0xFF302010
        out3.write(byteArrayOf(0x40, 0x50, 0x60, 0x00)) // fg = 0xFF605040

        // Tile 1 (16x4): Raw tile
        out3.writeByte(0x01)
        for (i in 0 until 16 * 4) {
            out3.write(byteArrayOf(0x01, 0x02, 0x03, 0x00))
        }

        // Tile 2 (4x4): AnySubrects (0x08) -> uses persisted bg & fg, with 1 subrect at (1,1) 2x2
        out3.writeByte(0x08)
        out3.writeByte(1)
        out3.writeByte((1 shl 4) or 1)
        out3.writeByte(((2 - 1) shl 4) or (2 - 1))
        out3.flush()

        RfbProtocol().use { proto ->
            proto.readServerMessage(
                DataInputStream(ByteArrayInputStream(baos.toByteArray())),
                fb,
                object : RfbProtocol.ServerMessageListener {}
            )
            proto.readServerMessage(
                DataInputStream(ByteArrayInputStream(baos3.toByteArray())),
                fb2,
                object : RfbProtocol.ServerMessageListener {}
            )
        }

        assertEquals(0xFF112233.toInt(), fb.getPixel(0, 0))
        assertEquals(0xFFAABBCC.toInt(), fb.getPixel(2, 1))
        assertEquals(0xFFAABBCC.toInt(), fb.getPixel(4, 2))
        assertEquals(0xFF112233.toInt(), fb.getPixel(5, 2))
        assertEquals(0xFF070605.toInt(), fb.getPixel(19, 3))

        // Tile 2 in fb2 used persisted bg & fg despite Raw tile in between
        assertEquals(0xFF302010.toInt(), fb2.getPixel(32, 0))
        assertEquals(0xFF605040.toInt(), fb2.getPixel(33, 1))
        assertEquals(0xFF605040.toInt(), fb2.getPixel(34, 2))
        assertEquals(0xFF302010.toInt(), fb2.getPixel(35, 3))
    }

    @Test
    fun zlibDecoderMaintainsSinglePersistentInflaterAcrossMultipleUpdates() {
        val fb = RfbFramebuffer()
        fb.resize(4, 2)

        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        fun compressSync(raw: ByteArray): ByteArray {
            deflater.setInput(raw)
            val out = ByteArray(raw.size + 128)
            val n = deflater.deflate(out, 0, out.size, Deflater.SYNC_FLUSH)
            return out.copyOf(n)
        }

        val rect1Raw = byteArrayOf(
            0x11, 0x22, 0x33, 0x00, 0x44, 0x55, 0x66, 0x00,
            0x77, 0x88.toByte(), 0x99.toByte(), 0x00, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x00
        )
        val rect2Raw = byteArrayOf(
            0x01, 0x02, 0x03, 0x00, 0x04, 0x05, 0x06, 0x00,
            0x07, 0x08, 0x09, 0x00, 0x0A, 0x0B, 0x0C, 0x00
        )

        val comp1 = compressSync(rect1Raw)
        val comp2 = compressSync(rect2Raw)
        deflater.end()

        val msgBaos = ByteArrayOutputStream()
        val msgOut = DataOutputStream(msgBaos)
        // Update 1: row 0
        msgOut.writeByte(RfbConstants.SMSG_FB_UPDATE); msgOut.writeByte(0); msgOut.writeShort(1)
        msgOut.writeShort(0); msgOut.writeShort(0); msgOut.writeShort(4); msgOut.writeShort(1)
        msgOut.writeInt(RfbConstants.ENCODING_ZLIB)
        msgOut.writeInt(comp1.size)
        msgOut.write(comp1)

        // Update 2: row 1 (continues SAME zlib stream without new zlib header!)
        msgOut.writeByte(RfbConstants.SMSG_FB_UPDATE); msgOut.writeByte(0); msgOut.writeShort(1)
        msgOut.writeShort(0); msgOut.writeShort(1); msgOut.writeShort(4); msgOut.writeShort(1)
        msgOut.writeInt(RfbConstants.ENCODING_ZLIB)
        msgOut.writeInt(comp2.size)
        msgOut.write(comp2)
        msgOut.flush()

        val dis = DataInputStream(ByteArrayInputStream(msgBaos.toByteArray()))
        RfbProtocol().use { proto ->
            proto.readServerMessage(dis, fb, object : RfbProtocol.ServerMessageListener {})
            proto.readServerMessage(dis, fb, object : RfbProtocol.ServerMessageListener {})
        }

        assertEquals(0xFF332211.toInt(), fb.getPixel(0, 0))
        assertEquals(0xFFCCBBAA.toInt(), fb.getPixel(3, 0))
        assertEquals(0xFF030201.toInt(), fb.getPixel(0, 1))
        assertEquals(0xFF0C0B0A.toInt(), fb.getPixel(3, 1))
    }

    @Test
    fun desktopSizeAndExtendedDesktopSizeResizeFramebufferAndNotifyListener() {
        val fb = RfbFramebuffer()
        fb.resize(640, 480)

        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        out.writeByte(RfbConstants.SMSG_FB_UPDATE); out.writeByte(0); out.writeShort(2)

        // DesktopSize -> 800x600
        out.writeShort(0); out.writeShort(0); out.writeShort(800); out.writeShort(600)
        out.writeInt(RfbConstants.ENCODING_DESKTOP_SIZE)

        // ExtendedDesktopSize -> 1280x720 with 1 screen id=99
        out.writeShort(0); out.writeShort(0); out.writeShort(1280); out.writeShort(720)
        out.writeInt(RfbConstants.ENCODING_EXTENDED_DESKTOP_SIZE)
        out.writeByte(1) // numScreens
        out.write(ByteArray(3))
        out.writeInt(99)
        out.writeShort(0); out.writeShort(0); out.writeShort(1280); out.writeShort(720)
        out.writeInt(0)
        out.flush()

        var sawExtended = false
        var sawScreenId = -1L
        var updatedWithResize = false

        RfbProtocol().use { proto ->
            proto.readServerMessage(
                DataInputStream(ByteArrayInputStream(baos.toByteArray())),
                fb,
                object : RfbProtocol.ServerMessageListener {
                    override fun onDesktopResized(
                        width: Int,
                        height: Int,
                        extended: Boolean,
                        primaryScreenId: Long
                    ) {
                        if (extended) {
                            sawExtended = true
                            sawScreenId = primaryScreenId
                        }
                    }

                    override fun onFramebufferUpdated(resized: Boolean) {
                        updatedWithResize = resized
                    }
                }
            )
        }

        assertEquals(1280, fb.width)
        assertEquals(720, fb.height)
        assertTrue(sawExtended)
        assertEquals(99L, sawScreenId)
        assertTrue(updatedWithResize)
    }

    @Test
    fun serverCutTextBellAndColourMapEntriesAreHandled() {
        val fb = RfbFramebuffer()
        fb.resize(10, 10)
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)

        // Bell
        out.writeByte(RfbConstants.SMSG_BELL)
        // SetColourMapEntries (1 colour = 6 bytes)
        out.writeByte(RfbConstants.SMSG_SET_COLOUR_MAP_ENTRIES)
        out.writeByte(0)
        out.writeShort(0)
        out.writeShort(1)
        out.write(byteArrayOf(0, 1, 0, 2, 0, 3))
        // ServerCutText
        val textBytes = "hello-clipboard".toByteArray(StandardCharsets.ISO_8859_1)
        out.writeByte(RfbConstants.SMSG_SERVER_CUT_TEXT)
        out.write(ByteArray(3))
        out.writeInt(textBytes.size)
        out.write(textBytes)
        out.flush()

        var bells = 0
        var cutText: String? = null
        val dis = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        RfbProtocol().use { proto ->
            val listener = object : RfbProtocol.ServerMessageListener {
                override fun onBell() {
                    bells++
                }

                override fun onServerCutText(text: String) {
                    cutText = text
                }
            }
            proto.readServerMessage(dis, fb, listener)
            proto.readServerMessage(dis, fb, listener)
            proto.readServerMessage(dis, fb, listener)
        }

        assertEquals(1, bells)
        assertEquals("hello-clipboard", cutText)
    }

    @Test
    fun malformedFramesAndDosBoundsAreRejectedWithRfbProtocolException() {
        val fb = RfbFramebuffer()
        fb.resize(16, 16)

        // 1. Out-of-bounds Hextile subrect
        val badHextile = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeByte(RfbConstants.SMSG_FB_UPDATE); writeByte(0); writeShort(1)
                writeShort(0); writeShort(0); writeShort(8); writeShort(8)
                writeInt(RfbConstants.ENCODING_HEXTILE)
                writeByte(0x08) // AnySubrects
                writeByte(1)
                writeByte((6 shl 4) or 0) // x=6
                writeByte(((4 - 1) shl 4) or 0) // w=4 -> 6+4=10 > tileW(8)
                flush()
            }
        }.toByteArray()

        assertThrowsProtocol {
            RfbProtocol().use {
                it.readServerMessage(
                    DataInputStream(ByteArrayInputStream(badHextile)),
                    fb,
                    object : RfbProtocol.ServerMessageListener {}
                )
            }
        }

        // 2. Oversized Zlib payload length
        val oversizedZlib = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeByte(RfbConstants.SMSG_FB_UPDATE); writeByte(0); writeShort(1)
                writeShort(0); writeShort(0); writeShort(2); writeShort(2)
                writeInt(RfbConstants.ENCODING_ZLIB)
                writeInt((RfbProtocol.MAX_ZLIB_COMPRESSED_BYTES + 1L).toInt())
                flush()
            }
        }.toByteArray()

        assertThrowsProtocol {
            RfbProtocol().use {
                it.readServerMessage(
                    DataInputStream(ByteArrayInputStream(oversizedZlib)),
                    fb,
                    object : RfbProtocol.ServerMessageListener {}
                )
            }
        }

        // 3. Corrupt Zlib stream
        val corruptZlib = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeByte(RfbConstants.SMSG_FB_UPDATE); writeByte(0); writeShort(1)
                writeShort(0); writeShort(0); writeShort(2); writeShort(2)
                writeInt(RfbConstants.ENCODING_ZLIB)
                writeInt(4)
                write(byteArrayOf(0x7F, 0x00, 0x01, 0x02))
                flush()
            }
        }.toByteArray()

        assertThrowsProtocol {
            RfbProtocol().use {
                it.readServerMessage(
                    DataInputStream(ByteArrayInputStream(corruptZlib)),
                    fb,
                    object : RfbProtocol.ServerMessageListener {}
                )
            }
        }

        // 4. Oversized ServerCutText
        val hugeCut = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeByte(RfbConstants.SMSG_SERVER_CUT_TEXT)
                write(ByteArray(3))
                writeInt((RfbProtocol.MAX_CUT_TEXT_BYTES + 1L).toInt())
                flush()
            }
        }.toByteArray()

        assertThrowsProtocol {
            RfbProtocol().use {
                it.readServerMessage(
                    DataInputStream(ByteArrayInputStream(hugeCut)),
                    fb,
                    object : RfbProtocol.ServerMessageListener {}
                )
            }
        }
    }

    @Test
    fun rfbConnectionStateAndTouchInputModeMatchContract() {
        assertEquals("Disconnected", RfbConnectionState.Disconnected.toString())
        assertEquals("Connecting", RfbConnectionState.Connecting.toString())
        val connected = RfbConnectionState.Connected(1024, 600, "Xvnc")
        assertEquals(1024, connected.width)
        assertEquals(600, connected.height)
        assertEquals("Xvnc", connected.serverName)
        val err = RfbConnectionState.Error("timeout")
        assertEquals("timeout", err.message)
        assertEquals(listOf(TouchInputMode.DIRECT_TAP, TouchInputMode.TRACKPAD), TouchInputMode.entries)
    }

    private fun assertThrowsProtocol(block: () -> Unit) {
        var threw = false
        try {
            block()
        } catch (_: RfbProtocolException) {
            threw = true
        }
        assertTrue("Expected RfbProtocolException", threw)
    }
}
