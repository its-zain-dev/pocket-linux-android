package com.pocketlinux.rfb

import android.graphics.Bitmap
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

enum class TouchInputMode {
    DIRECT_TAP,
    TRACKPAD
}

sealed class RfbConnectionState {
    object Disconnected : RfbConnectionState() {
        override fun toString(): String = "Disconnected"
    }

    object Connecting : RfbConnectionState() {
        override fun toString(): String = "Connecting"
    }

    data class Connected(
        val width: Int,
        val height: Int,
        val serverName: String
    ) : RfbConnectionState()

    data class Error(val message: String) : RfbConnectionState()
}

interface RfbController {
    val connectionState: StateFlow<RfbConnectionState>
    val desktopWidth: Int
    val desktopHeight: Int
    fun connect(host: String = "127.0.0.1", port: Int = 5901)
    fun disconnect()
    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int)
    fun sendKeyEvent(keysym: Int, down: Boolean)
    fun requestDesktopSize(width: Int, height: Int)
    fun snapshotBitmap(): Bitmap?
}

data class RfbPixelFormat(
    val bitsPerPixel: Int,
    val depth: Int,
    val bigEndian: Boolean,
    val trueColor: Boolean,
    val redMax: Int,
    val greenMax: Int,
    val blueMax: Int,
    val redShift: Int,
    val greenShift: Int,
    val blueShift: Int
)

data class RfbServerInit(
    val width: Int,
    val height: Int,
    val pixelFormat: RfbPixelFormat,
    val serverName: String
)

data class RfbExtendedScreen(
    val id: Long,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val flags: Long
)

class RfbProtocolException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

data class RfbCursorShape(
    val width: Int,
    val height: Int,
    val hotX: Int,
    val hotY: Int,
    val pixels: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RfbCursorShape) return false
        return width == other.width &&
            height == other.height &&
            hotX == other.hotX &&
            hotY == other.hotY &&
            pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + hotX
        result = 31 * result + hotY
        result = 31 * result + pixels.contentHashCode()
        return result
    }
}

object RfbConstants {
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 5901

    const val SECURITY_INVALID = 0
    const val SECURITY_NONE = 1
    const val SECURITY_VNC_AUTH = 2

    const val ENCODING_RAW = 0
    const val ENCODING_COPYRECT = 1
    const val ENCODING_HEXTILE = 5
    const val ENCODING_ZLIB = 6
    const val ENCODING_DESKTOP_SIZE = -223
    const val ENCODING_CURSOR = -239
    const val ENCODING_EXTENDED_DESKTOP_SIZE = -308

    const val MSG_SET_PIXEL_FORMAT = 0
    const val MSG_SET_ENCODINGS = 2
    const val MSG_FB_UPDATE_REQUEST = 3
    const val MSG_KEY_EVENT = 4
    const val MSG_POINTER_EVENT = 5
    const val MSG_CLIENT_CUT_TEXT = 6
    const val MSG_SET_DESKTOP_SIZE = 251

    const val SMSG_FB_UPDATE = 0
    const val SMSG_SET_COLOUR_MAP_ENTRIES = 1
    const val SMSG_BELL = 2
    const val SMSG_SERVER_CUT_TEXT = 3

    const val BUTTON_LEFT = 1
    const val BUTTON_MIDDLE = 2
    const val BUTTON_RIGHT = 4
    const val BUTTON_WHEEL_UP = 8
    const val BUTTON_WHEEL_DOWN = 16

    const val HEXTILE_RAW = 0x01
    const val HEXTILE_BACKGROUND_SPECIFIED = 0x02
    const val HEXTILE_FOREGROUND_SPECIFIED = 0x04
    const val HEXTILE_ANY_SUBRECTS = 0x08
    const val HEXTILE_SUBRECTS_COLOURED = 0x10

    val DEFAULT_ENCODINGS = intArrayOf(
        ENCODING_COPYRECT,
        ENCODING_RAW,
        ENCODING_HEXTILE,
        ENCODING_ZLIB,
        ENCODING_CURSOR,
        ENCODING_DESKTOP_SIZE,
        ENCODING_EXTENDED_DESKTOP_SIZE
    )
}
