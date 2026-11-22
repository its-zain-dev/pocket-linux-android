package com.pocketlinux.rfb

import java.util.Arrays

data class FrameSnapshot(
    val width: Int,
    val height: Int,
    val pixels: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameSnapshot) return false
        return width == other.width && height == other.height && pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + pixels.contentHashCode()
        return result
    }
}

/**
 * Pure-JVM synchronized ARGB_8888 pixel buffer with strict overflow and bounds validation.
 * Zero android.* dependencies so all buffer operations are unit-testable on the JVM.
 */
class RfbFramebuffer {

    companion object {
        const val MAX_DIMENSION = 16_384
        const val MAX_PIXEL_COUNT = 16_777_216L
        private const val OPAQUE_ALPHA = 0xFF000000.toInt()

        fun validateDimensions(width: Int, height: Int) {
            if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) {
                throw IllegalArgumentException(
                    "Framebuffer dimensions out of range (1..$MAX_DIMENSION): ${width}x$height"
                )
            }
            val pixelCount = width.toLong() * height.toLong()
            if (pixelCount <= 0L || pixelCount > MAX_PIXEL_COUNT) {
                throw IllegalArgumentException(
                    "Framebuffer pixel count exceeds limit ($MAX_PIXEL_COUNT): $pixelCount"
                )
            }
        }

        fun validateRect(
            x: Int,
            y: Int,
            w: Int,
            h: Int,
            fbWidth: Int,
            fbHeight: Int
        ) {
            if (fbWidth <= 0 || fbHeight <= 0) {
                throw IllegalStateException("Framebuffer has not been initialized")
            }
            if (x < 0 || y < 0 || w < 0 || h < 0) {
                throw IllegalArgumentException("Negative rect coordinates or size: ($x,$y,${w}x$h)")
            }
            val right = x.toLong() + w.toLong()
            val bottom = y.toLong() + h.toLong()
            if (right > fbWidth.toLong() || bottom > fbHeight.toLong()) {
                throw IllegalArgumentException(
                    "Rect ($x,$y,${w}x$h) exceeds framebuffer bounds (${fbWidth}x$fbHeight)"
                )
            }
        }

        fun unpackBgrxPixel(b: Byte, g: Byte, r: Byte): Int {
            return OPAQUE_ALPHA or
                ((r.toInt() and 0xFF) shl 16) or
                ((g.toInt() and 0xFF) shl 8) or
                (b.toInt() and 0xFF)
        }
    }

    private val lock = Any()

    @Volatile
    var width: Int = 0
        private set

    @Volatile
    var height: Int = 0
        private set

    private var pixels: IntArray = IntArray(0)

    fun dimensions(): Pair<Int, Int> = synchronized(lock) { width to height }

    fun resize(newWidth: Int, newHeight: Int) {
        validateDimensions(newWidth, newHeight)
        val count = (newWidth.toLong() * newHeight.toLong()).toInt()
        val fresh = IntArray(count) { OPAQUE_ALPHA }
        synchronized(lock) {
            width = newWidth
            height = newHeight
            pixels = fresh
        }
    }

    fun fillRect(x: Int, y: Int, w: Int, h: Int, argb: Int) {
        synchronized(lock) {
            validateRect(x, y, w, h, width, height)
            if (w == 0 || h == 0) return
            val opaqueColor = OPAQUE_ALPHA or argb
            val stride = width
            for (row in 0 until h) {
                val start = (y + row) * stride + x
                Arrays.fill(pixels, start, start + w, opaqueColor)
            }
        }
    }

    fun writeRawRowBgrx(
        x: Int,
        y: Int,
        w: Int,
        bgrxRow: ByteArray,
        rowOffset: Int = 0
    ) {
        if (rowOffset < 0) {
            throw IllegalArgumentException("Negative rowOffset: $rowOffset")
        }
        val neededBytes = w.toLong() * 4L
        if (rowOffset.toLong() + neededBytes > bgrxRow.size.toLong()) {
            throw IllegalArgumentException(
                "Source BGRX row too small: offset=$rowOffset, needed=$neededBytes, size=${bgrxRow.size}"
            )
        }
        synchronized(lock) {
            validateRect(x, y, w, 1, width, height)
            if (w == 0) return
            var srcIdx = rowOffset
            var dstIdx = y * width + x
            for (col in 0 until w) {
                val b = bgrxRow[srcIdx]
                val g = bgrxRow[srcIdx + 1]
                val r = bgrxRow[srcIdx + 2]
                pixels[dstIdx] = unpackBgrxPixel(b, g, r)
                srcIdx += 4
                dstIdx += 1
            }
        }
    }

    fun writeRawRectBgrx(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        bgrxBytes: ByteArray,
        offset: Int = 0
    ) {
        if (offset < 0) {
            throw IllegalArgumentException("Negative offset: $offset")
        }
        val neededBytes = w.toLong() * h.toLong() * 4L
        if (offset.toLong() + neededBytes > bgrxBytes.size.toLong()) {
            throw IllegalArgumentException(
                "Source BGRX rect too small: offset=$offset, needed=$neededBytes, size=${bgrxBytes.size}"
            )
        }
        synchronized(lock) {
            validateRect(x, y, w, h, width, height)
            if (w == 0 || h == 0) return
            val stride = width
            var srcIdx = offset
            for (row in 0 until h) {
                var dstIdx = (y + row) * stride + x
                for (col in 0 until w) {
                    val b = bgrxBytes[srcIdx]
                    val g = bgrxBytes[srcIdx + 1]
                    val r = bgrxBytes[srcIdx + 2]
                    pixels[dstIdx] = unpackBgrxPixel(b, g, r)
                    srcIdx += 4
                    dstIdx += 1
                }
            }
        }
    }

    fun copyRect(
        srcX: Int,
        srcY: Int,
        dstX: Int,
        dstY: Int,
        w: Int,
        h: Int
    ) {
        synchronized(lock) {
            validateRect(srcX, srcY, w, h, width, height)
            validateRect(dstX, dstY, w, h, width, height)
            if (w == 0 || h == 0 || (srcX == dstX && srcY == dstY)) return
            val stride = width
            if (dstY > srcY) {
                for (row in h - 1 downTo 0) {
                    val srcPos = (srcY + row) * stride + srcX
                    val dstPos = (dstY + row) * stride + dstX
                    System.arraycopy(pixels, srcPos, pixels, dstPos, w)
                }
            } else {
                for (row in 0 until h) {
                    val srcPos = (srcY + row) * stride + srcX
                    val dstPos = (dstY + row) * stride + dstX
                    System.arraycopy(pixels, srcPos, pixels, dstPos, w)
                }
            }
        }
    }

    fun getPixel(x: Int, y: Int): Int {
        synchronized(lock) {
            validateRect(x, y, 1, 1, width, height)
            return pixels[y * width + x]
        }
    }

    fun snapshotPixels(): FrameSnapshot? {
        synchronized(lock) {
            if (width <= 0 || height <= 0) return null
            return FrameSnapshot(width, height, pixels.copyOf())
        }
    }

    fun copyPixelsInto(dest: IntArray, expectedWidth: Int, expectedHeight: Int): Boolean {
        synchronized(lock) {
            if (width <= 0 || height <= 0) return false
            if (width != expectedWidth || height != expectedHeight) return false
            val count = width * height
            if (dest.size < count) return false
            System.arraycopy(pixels, 0, dest, 0, count)
            return true
        }
    }
}
