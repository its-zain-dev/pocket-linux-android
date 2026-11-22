package com.pocketlinux.rfb

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Pure-JVM RFB 3.8 wire protocol reader/writer.
 * Maintains a single persistent java.util.zip.Inflater across the connection lifetime
 * as required by RFC 6143 / TigerVNC Zlib encoding.
 */
class RfbProtocol : Closeable {

    interface ServerMessageListener {
        fun onFramebufferUpdated(resized: Boolean) {}
        fun onDesktopResized(
            width: Int,
            height: Int,
            extended: Boolean,
            primaryScreenId: Long
        ) {}
        fun onBell() {}
        fun onServerCutText(text: String) {}
    }

    companion object {
        const val MAX_REASON_BYTES = 65_536L
        const val MAX_SERVER_NAME_BYTES = 65_536L
        const val MAX_CUT_TEXT_BYTES = 1_048_576L
        const val MAX_RAW_RECT_BYTES = 67_108_864L
        const val MAX_ZLIB_COMPRESSED_BYTES = 33_554_432L

        private val VERSION_REGEX = Regex("^RFB (\\d{3})\\.(\\d{3})\\n$")
        private val CLIENT_VERSION_3_8 = "RFB 003.008\n".toByteArray(StandardCharsets.US_ASCII)
        private val CLIENT_VERSION_3_7 = "RFB 003.007\n".toByteArray(StandardCharsets.US_ASCII)

        fun performHandshake(
            input: DataInputStream,
            output: DataOutputStream
        ): RfbServerInit {
            val versionBytes = ByteArray(12)
            input.readFully(versionBytes)
            val versionStr = String(versionBytes, StandardCharsets.US_ASCII)
            val match = VERSION_REGEX.matchEntire(versionStr)
                ?: throw RfbProtocolException("Invalid RFB server greeting: '$versionStr'")
            val major = match.groupValues[1].toInt()
            val minor = match.groupValues[2].toInt()
            if (major != 3 || minor < 7) {
                throw RfbProtocolException("Unsupported RFB version: $major.$minor")
            }

            val use38 = minor >= 8
            output.write(if (use38) CLIENT_VERSION_3_8 else CLIENT_VERSION_3_7)
            output.flush()

            val numSecurityTypes = input.readUnsignedByte()
            if (numSecurityTypes == 0) {
                val reason = readBoundedReason(input)
                throw RfbProtocolException("Server rejected RFB handshake: $reason")
            }

            val securityTypes = ByteArray(numSecurityTypes)
            input.readFully(securityTypes)
            val offersNone = securityTypes.any { (it.toInt() and 0xFF) == RfbConstants.SECURITY_NONE }
            if (!offersNone) {
                val offered = securityTypes.joinToString(",") { (it.toInt() and 0xFF).toString() }
                throw RfbProtocolException("Server does not offer SecurityType None (offered: [$offered])")
            }

            output.writeByte(RfbConstants.SECURITY_NONE)
            output.flush()

            if (use38) {
                val securityResult = input.readInt()
                if (securityResult != 0) {
                    val reason = try {
                        readBoundedReason(input)
                    } catch (_: EOFException) {
                        "authentication failed"
                    }
                    throw RfbProtocolException("RFB SecurityResult failed ($securityResult): $reason")
                }
            }

            // ClientInit: shared = 1
            output.writeByte(1)
            output.flush()

            // ServerInit
            val width = input.readUnsignedShort()
            val height = input.readUnsignedShort()
            try {
                RfbFramebuffer.validateDimensions(width, height)
            } catch (e: IllegalArgumentException) {
                throw RfbProtocolException("Invalid ServerInit framebuffer dimensions: ${width}x$height", e)
            }

            val bpp = input.readUnsignedByte()
            val depth = input.readUnsignedByte()
            val bigEndian = input.readUnsignedByte() != 0
            val trueColor = input.readUnsignedByte() != 0
            val redMax = input.readUnsignedShort()
            val greenMax = input.readUnsignedShort()
            val blueMax = input.readUnsignedShort()
            val redShift = input.readUnsignedByte()
            val greenShift = input.readUnsignedByte()
            val blueShift = input.readUnsignedByte()
            val pad3 = ByteArray(3)
            input.readFully(pad3)

            val nameLen = readUnsignedInt32(input)
            if (nameLen > MAX_SERVER_NAME_BYTES) {
                throw RfbProtocolException("ServerInit desktop name length exceeds cap: $nameLen")
            }
            val nameBytes = ByteArray(nameLen.toInt())
            input.readFully(nameBytes)
            val serverName = String(nameBytes, StandardCharsets.UTF_8)

            return RfbServerInit(
                width = width,
                height = height,
                pixelFormat = RfbPixelFormat(
                    bitsPerPixel = bpp,
                    depth = depth,
                    bigEndian = bigEndian,
                    trueColor = trueColor,
                    redMax = redMax,
                    greenMax = greenMax,
                    blueMax = blueMax,
                    redShift = redShift,
                    greenShift = greenShift,
                    blueShift = blueShift
                ),
                serverName = serverName
            )
        }

        fun writeSetPixelFormat(
            output: DataOutputStream,
            flush: Boolean = true
        ) {
            output.writeByte(RfbConstants.MSG_SET_PIXEL_FORMAT)
            output.writeByte(0)
            output.writeByte(0)
            output.writeByte(0)
            output.writeByte(32) // bits-per-pixel
            output.writeByte(24) // depth
            output.writeByte(0)  // little-endian
            output.writeByte(1)  // true-color
            output.writeShort(255) // red-max
            output.writeShort(255) // green-max
            output.writeShort(255) // blue-max
            output.writeByte(16) // red-shift
            output.writeByte(8)  // green-shift
            output.writeByte(0)  // blue-shift
            output.writeByte(0)
            output.writeByte(0)
            output.writeByte(0)
            if (flush) output.flush()
        }

        fun writeSetEncodings(
            output: DataOutputStream,
            encodings: IntArray = RfbConstants.DEFAULT_ENCODINGS,
            flush: Boolean = true
        ) {
            if (encodings.size > 0xFFFF) {
                throw IllegalArgumentException("Too many encodings: ${encodings.size}")
            }
            output.writeByte(RfbConstants.MSG_SET_ENCODINGS)
            output.writeByte(0)
            output.writeShort(encodings.size)
            for (encoding in encodings) {
                output.writeInt(encoding)
            }
            if (flush) output.flush()
        }

        fun writeFramebufferUpdateRequest(
            output: DataOutputStream,
            incremental: Boolean,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            flush: Boolean = true
        ) {
            output.writeByte(RfbConstants.MSG_FB_UPDATE_REQUEST)
            output.writeByte(if (incremental) 1 else 0)
            output.writeShort(x and 0xFFFF)
            output.writeShort(y and 0xFFFF)
            output.writeShort(width and 0xFFFF)
            output.writeShort(height and 0xFFFF)
            if (flush) output.flush()
        }

        fun writeKeyEvent(
            output: DataOutputStream,
            keysym: Int,
            down: Boolean,
            flush: Boolean = true
        ) {
            output.writeByte(RfbConstants.MSG_KEY_EVENT)
            output.writeByte(if (down) 1 else 0)
            output.writeShort(0)
            output.writeInt(keysym)
            if (flush) output.flush()
        }

        fun writePointerEvent(
            output: DataOutputStream,
            x: Int,
            y: Int,
            buttonMask: Int,
            flush: Boolean = true
        ) {
            output.writeByte(RfbConstants.MSG_POINTER_EVENT)
            output.writeByte(buttonMask and 0xFF)
            output.writeShort(x and 0xFFFF)
            output.writeShort(y and 0xFFFF)
            if (flush) output.flush()
        }

        fun writeClientCutText(
            output: DataOutputStream,
            text: String,
            flush: Boolean = true
        ) {
            val raw = text.toByteArray(StandardCharsets.ISO_8859_1)
            val len = minOf(raw.size, MAX_CUT_TEXT_BYTES.toInt())
            output.writeByte(RfbConstants.MSG_CLIENT_CUT_TEXT)
            output.writeByte(0)
            output.writeByte(0)
            output.writeByte(0)
            output.writeInt(len)
            output.write(raw, 0, len)
            if (flush) output.flush()
        }

        fun writeSetDesktopSize(
            output: DataOutputStream,
            width: Int,
            height: Int,
            screenId: Long = 0L,
            screenFlags: Long = 0L,
            flush: Boolean = true
        ) {
            RfbFramebuffer.validateDimensions(width, height)
            output.writeByte(RfbConstants.MSG_SET_DESKTOP_SIZE)
            output.writeByte(0)
            output.writeShort(width)
            output.writeShort(height)
            output.writeByte(1) // number-of-screens
            output.writeByte(0) // padding
            output.writeInt(screenId.toInt())
            output.writeShort(0) // x-position
            output.writeShort(0) // y-position
            output.writeShort(width)
            output.writeShort(height)
            output.writeInt(screenFlags.toInt())
            if (flush) output.flush()
        }

        private fun readUnsignedInt32(input: DataInputStream): Long {
            return input.readInt().toLong() and 0xFFFF_FFFFL
        }

        private fun readBoundedReason(input: DataInputStream): String {
            val len = readUnsignedInt32(input)
            if (len > MAX_REASON_BYTES) {
                throw RfbProtocolException("Reason string length exceeds cap: $len")
            }
            val buf = ByteArray(len.toInt())
            input.readFully(buf)
            return String(buf, StandardCharsets.UTF_8)
        }
    }

    private val inflater = Inflater()
    private val pad3 = ByteArray(3)
    private val pixel4 = ByteArray(4)
    private val hextileTileBuffer = ByteArray(16 * 16 * 4)
    private val zlibDrainBuffer = ByteArray(64)
    private var rowBuffer = ByteArray(4096 * 4)
    private var compressedBuffer = ByteArray(65_536)
    private var closed = false

    fun readServerMessage(
        input: DataInputStream,
        framebuffer: RfbFramebuffer,
        listener: ServerMessageListener
    ) {
        if (closed) {
            throw RfbProtocolException("RfbProtocol instance is closed")
        }
        when (val msgType = input.readUnsignedByte()) {
            RfbConstants.SMSG_FB_UPDATE -> readFramebufferUpdate(input, framebuffer, listener)
            RfbConstants.SMSG_SET_COLOUR_MAP_ENTRIES -> {
                input.readUnsignedByte() // padding
                input.readUnsignedShort() // first-colour
                val numColours = input.readUnsignedShort()
                skipFully(input, numColours.toLong() * 6L)
            }
            RfbConstants.SMSG_BELL -> listener.onBell()
            RfbConstants.SMSG_SERVER_CUT_TEXT -> {
                input.readFully(pad3)
                val len = readUnsignedInt32(input)
                if (len > MAX_CUT_TEXT_BYTES) {
                    throw RfbProtocolException("ServerCutText length exceeds cap: $len")
                }
                val buf = ByteArray(len.toInt())
                input.readFully(buf)
                listener.onServerCutText(String(buf, StandardCharsets.ISO_8859_1))
            }
            else -> throw RfbProtocolException("Unknown RFB server message type: $msgType")
        }
    }

    private fun readFramebufferUpdate(
        input: DataInputStream,
        framebuffer: RfbFramebuffer,
        listener: ServerMessageListener
    ) {
        input.readUnsignedByte() // padding
        val rectCount = input.readUnsignedShort()
        var resized = false

        for (i in 0 until rectCount) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            val encoding = input.readInt()

            when (encoding) {
                RfbConstants.ENCODING_RAW -> readRawRect(input, framebuffer, x, y, w, h)
                RfbConstants.ENCODING_COPYRECT -> readCopyRect(input, framebuffer, x, y, w, h)
                RfbConstants.ENCODING_HEXTILE -> readHextileRect(input, framebuffer, x, y, w, h)
                RfbConstants.ENCODING_ZLIB -> readZlibRect(input, framebuffer, x, y, w, h)
                RfbConstants.ENCODING_DESKTOP_SIZE -> {
                    validateDimensionsForProtocol(w, h)
                    framebuffer.resize(w, h)
                    resized = true
                    listener.onDesktopResized(w, h, extended = false, primaryScreenId = 0L)
                }
                RfbConstants.ENCODING_EXTENDED_DESKTOP_SIZE -> {
                    val primaryId = readExtendedDesktopSizePayload(input)
                    validateDimensionsForProtocol(w, h)
                    if (w != framebuffer.width || h != framebuffer.height) {
                        framebuffer.resize(w, h)
                        resized = true
                    }
                    listener.onDesktopResized(w, h, extended = true, primaryScreenId = primaryId)
                }
                else -> throw RfbProtocolException("Unsupported RFB encoding: $encoding")
            }
        }

        listener.onFramebufferUpdated(resized)
    }

    private fun readRawRect(
        input: DataInputStream,
        framebuffer: RfbFramebuffer,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ) {
        validateRectForProtocol(framebuffer, x, y, w, h)
        val totalBytes = w.toLong() * h.toLong() * 4L
        if (totalBytes > MAX_RAW_RECT_BYTES) {
            throw RfbProtocolException("Raw rectangle byte count exceeds cap: $totalBytes")
        }
        if (w == 0 || h == 0) return

        val rowBytes = w * 4
        val buf = ensureRowBuffer(rowBytes)
        for (row in 0 until h) {
            input.readFully(buf, 0, rowBytes)
            framebuffer.writeRawRowBgrx(x, y + row, w, buf, 0)
        }
    }

    private fun readCopyRect(
        input: DataInputStream,
        framebuffer: RfbFramebuffer,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ) {
        val srcX = input.readUnsignedShort()
        val srcY = input.readUnsignedShort()
        try {
            framebuffer.copyRect(srcX, srcY, x, y, w, h)
        } catch (e: RuntimeException) {
            throw RfbProtocolException(
                "Invalid CopyRect ($srcX,$srcY)->($x,$y) ${w}x$h",
                e
            )
        }
    }

    private fun readHextileRect(
        input: DataInputStream,
        framebuffer: RfbFramebuffer,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ) {
        validateRectForProtocol(framebuffer, x, y, w, h)
        if (w == 0 || h == 0) return

        var bg = 0xFF000000.toInt()
        var fg = 0xFFFFFFFF.toInt()

        var ty = y
        while (ty < y + h) {
            val tileH = minOf(16, y + h - ty)
            var tx = x
            while (tx < x + w) {
                val tileW = minOf(16, x + w - tx)
                val subencoding = input.readUnsignedByte()

                if ((subencoding and RfbConstants.HEXTILE_RAW) != 0) {
                    val tileBytes = tileW * tileH * 4
                    input.readFully(hextileTileBuffer, 0, tileBytes)
                    val rowStride = tileW * 4
                    for (r in 0 until tileH) {
                        framebuffer.writeRawRowBgrx(
                            tx,
                            ty + r,
                            tileW,
                            hextileTileBuffer,
                            r * rowStride
                        )
                    }
                } else {
                    if ((subencoding and RfbConstants.HEXTILE_BACKGROUND_SPECIFIED) != 0) {
                        bg = readPixelBgrx(input)
                    }
                    framebuffer.fillRect(tx, ty, tileW, tileH, bg)

                    if ((subencoding and RfbConstants.HEXTILE_FOREGROUND_SPECIFIED) != 0) {
                        fg = readPixelBgrx(input)
                    }

                    if ((subencoding and RfbConstants.HEXTILE_ANY_SUBRECTS) != 0) {
                        val numSubrects = input.readUnsignedByte()
                        val coloured = (subencoding and RfbConstants.HEXTILE_SUBRECTS_COLOURED) != 0
                        for (s in 0 until numSubrects) {
                            val color = if (coloured) readPixelBgrx(input) else fg
                            val xy = input.readUnsignedByte()
                            val wh = input.readUnsignedByte()
                            val sx = (xy ushr 4) and 0x0F
                            val sy = xy and 0x0F
                            val sw = ((wh ushr 4) and 0x0F) + 1
                            val sh = (wh and 0x0F) + 1
                            if (sx + sw > tileW || sy + sh > tileH) {
                                throw RfbProtocolException(
                                    "Hextile subrect ($sx,$sy,${sw}x$sh) exceeds tile bounds (${tileW}x$tileH)"
                                )
                            }
                            framebuffer.fillRect(tx + sx, ty + sy, sw, sh, color)
                        }
                    }
                }
                tx += 16
            }
            ty += 16
        }
    }

    private fun readZlibRect(
        input: DataInputStream,
        framebuffer: RfbFramebuffer,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ) {
        validateRectForProtocol(framebuffer, x, y, w, h)
        val expectedBytes = w.toLong() * h.toLong() * 4L
        if (expectedBytes > MAX_RAW_RECT_BYTES) {
            throw RfbProtocolException("Zlib uncompressed rectangle exceeds cap: $expectedBytes")
        }

        val compLen = readUnsignedInt32(input)
        if (compLen > MAX_ZLIB_COMPRESSED_BYTES) {
            throw RfbProtocolException("Zlib compressed length exceeds cap: $compLen")
        }
        if (expectedBytes > 0L && compLen == 0L) {
            throw RfbProtocolException("Zlib rectangle has empty compressed payload for $expectedBytes bytes")
        }

        val compLenInt = compLen.toInt()
        val compBuf = ensureCompressedBuffer(compLenInt)
        input.readFully(compBuf, 0, compLenInt)

        if (compLenInt == 0) return

        inflater.setInput(compBuf, 0, compLenInt)

        if (w > 0 && h > 0) {
            val rowBytes = w * 4
            val rowBuf = ensureRowBuffer(rowBytes)
            for (row in 0 until h) {
                var filled = 0
                while (filled < rowBytes) {
                    val produced = try {
                        inflater.inflate(rowBuf, filled, rowBytes - filled)
                    } catch (e: DataFormatException) {
                        throw RfbProtocolException("Malformed Zlib stream", e)
                    }
                    if (produced == 0) {
                        when {
                            inflater.needsInput() -> {
                                throw RfbProtocolException(
                                    "Truncated Zlib stream (expected $expectedBytes bytes)"
                                )
                            }
                            inflater.finished() -> {
                                throw RfbProtocolException(
                                    "Zlib stream finished prematurely before $expectedBytes bytes"
                                )
                            }
                            inflater.needsDictionary() -> {
                                throw RfbProtocolException("Zlib stream requires unsupported preset dictionary")
                            }
                            else -> {
                                throw RfbProtocolException("Zlib inflater stalled")
                            }
                        }
                    }
                    filled += produced
                }
                framebuffer.writeRawRowBgrx(x, y + row, w, rowBuf, 0)
            }
        }

        // Consume any trailing Z_SYNC_FLUSH empty stored block so persistent Inflater stays block-aligned.
        while (!inflater.needsInput() && !inflater.finished()) {
            val extra = try {
                inflater.inflate(zlibDrainBuffer, 0, zlibDrainBuffer.size)
            } catch (e: DataFormatException) {
                throw RfbProtocolException("Malformed trailing Zlib stream", e)
            }
            if (extra > 0) {
                throw RfbProtocolException("Zlib stream produced more bytes than expected $expectedBytes")
            }
            if (extra == 0 && inflater.needsDictionary()) {
                throw RfbProtocolException("Zlib stream requires unsupported preset dictionary")
            }
        }
    }

    private fun readExtendedDesktopSizePayload(input: DataInputStream): Long {
        val numScreens = input.readUnsignedByte()
        input.readFully(pad3)
        var primaryScreenId = 0L
        for (i in 0 until numScreens) {
            val screenId = readUnsignedInt32(input)
            input.readUnsignedShort() // x
            input.readUnsignedShort() // y
            input.readUnsignedShort() // width
            input.readUnsignedShort() // height
            readUnsignedInt32(input) // flags
            if (i == 0) {
                primaryScreenId = screenId
            }
        }
        return primaryScreenId
    }

    private fun readPixelBgrx(input: DataInputStream): Int {
        input.readFully(pixel4)
        return RfbFramebuffer.unpackBgrxPixel(pixel4[0], pixel4[1], pixel4[2])
    }

    private fun validateDimensionsForProtocol(w: Int, h: Int) {
        try {
            RfbFramebuffer.validateDimensions(w, h)
        } catch (e: IllegalArgumentException) {
            throw RfbProtocolException("Invalid framebuffer dimensions ${w}x$h", e)
        }
    }

    private fun validateRectForProtocol(
        framebuffer: RfbFramebuffer,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ) {
        val (fbW, fbH) = framebuffer.dimensions()
        try {
            RfbFramebuffer.validateRect(x, y, w, h, fbW, fbH)
        } catch (e: RuntimeException) {
            throw RfbProtocolException("Rectangle ($x,$y,${w}x$h) out of bounds (${fbW}x$fbH)", e)
        }
    }

    private fun ensureRowBuffer(size: Int): ByteArray {
        if (rowBuffer.size < size) {
            rowBuffer = ByteArray(size)
        }
        return rowBuffer
    }

    private fun ensureCompressedBuffer(size: Int): ByteArray {
        if (compressedBuffer.size < size) {
            compressedBuffer = ByteArray(size)
        }
        return compressedBuffer
    }

    private fun skipFully(input: DataInputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skipBytes(minOf(remaining, Int.MAX_VALUE.toLong()).toInt())
            if (skipped <= 0) {
                if (input.read() < 0) {
                    throw EOFException("Unexpected EOF while skipping $remaining remaining bytes")
                }
                remaining -= 1L
            } else {
                remaining -= skipped.toLong()
            }
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            inflater.end()
        }
    }
}
