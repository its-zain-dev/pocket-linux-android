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

data class DirtyRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

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
    private var dirtyLeft: Int = Int.MAX_VALUE
    private var dirtyTop: Int = Int.MAX_VALUE
    private var dirtyRight: Int = 0
    private var dirtyBottom: Int = 0

    private fun markDirtyLocked(x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (x < dirtyLeft) dirtyLeft = x
        if (y < dirtyTop) dirtyTop = y
        val r = x + w
        val b = y + h
        if (r > dirtyRight) dirtyRight = r
        if (b > dirtyBottom) dirtyBottom = b
    }

    private fun clearDirtyLocked() {
        dirtyLeft = Int.MAX_VALUE
        dirtyTop = Int.MAX_VALUE
        dirtyRight = 0
        dirtyBottom = 0
    }

    fun dimensions(): Pair<Int, Int> = synchronized(lock) { width to height }

    fun resize(newWidth: Int, newHeight: Int) {
        validateDimensions(newWidth, newHeight)
        val count = (newWidth.toLong() * newHeight.toLong()).toInt()
        val fresh = IntArray(count)
        Arrays.fill(fresh, OPAQUE_ALPHA)
        synchronized(lock) {
            width = newWidth
            height = newHeight
            pixels = fresh
            dirtyLeft = 0
            dirtyTop = 0
            dirtyRight = newWidth
            dirtyBottom = newHeight
        }
    }

    fun fillRect(x: Int, y: Int, w: Int, h: Int, argb: Int) {
        synchronized(lock) {
            validateRect(x, y, w, h, width, height)
            if (w == 0 || h == 0) return
            val opaqueColor = OPAQUE_ALPHA or argb
            val px = pixels
            val stride = width
            if (x == 0 && w == stride) {
                val start = y * stride
                Arrays.fill(px, start, start + w * h, opaqueColor)
            } else {
                for (row in 0 until h) {
                    val start = (y + row) * stride + x
                    Arrays.fill(px, start, start + w, opaqueColor)
                }
            }
            markDirtyLocked(x, y, w, h)
        }
    }

    fun writeRawRowBgrx(
        x: Int,
        y: Int,
        w: Int,
        bgrxRow: ByteArray,
        rowOffset: Int = 0
    ) {
        writeRawRectBgrx(x, y, w, 1, bgrxRow, rowOffset)
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
            val px = pixels
            val stride = width
            var srcIdx = offset
            for (row in 0 until h) {
                var dstIdx = (y + row) * stride + x
                val rowEnd = dstIdx + w
                while (dstIdx < rowEnd) {
                    val b = bgrxBytes[srcIdx].toInt() and 0xFF
                    val g = (bgrxBytes[srcIdx + 1].toInt() and 0xFF) shl 8
                    val r = (bgrxBytes[srcIdx + 2].toInt() and 0xFF) shl 16
                    px[dstIdx] = OPAQUE_ALPHA or r or g or b
                    srcIdx += 4
                    dstIdx += 1
                }
            }
            markDirtyLocked(x, y, w, h)
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
            val px = pixels
            val stride = width
            if (dstY > srcY) {
                for (row in h - 1 downTo 0) {
                    val srcPos = (srcY + row) * stride + srcX
                    val dstPos = (dstY + row) * stride + dstX
                    System.arraycopy(px, srcPos, px, dstPos, w)
                }
            } else {
                for (row in 0 until h) {
                    val srcPos = (srcY + row) * stride + srcX
                    val dstPos = (dstY + row) * stride + dstX
                    System.arraycopy(px, srcPos, px, dstPos, w)
                }
            }
            markDirtyLocked(dstX, dstY, w, h)
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
            clearDirtyLocked()
            return true
        }
    }

    /**
     * Atomically extracts only the modified bounding rectangle into [dest] packed with stride
     * `dirtyRegion.width`, avoiding full-screen copies and GPU texture uploads when small regions
     * update. Returns `DirtyRegion(0, 0, 0, 0)` when nothing has changed since the last consume,
     * or `null` if dimensions changed / [dest] is too small.
     */
    fun consumeDirtyRegionInto(
        dest: IntArray,
        expectedWidth: Int,
        expectedHeight: Int,
        forceFull: Boolean
    ): DirtyRegion? {
        synchronized(lock) {
            if (width <= 0 || height <= 0) return null
            if (width != expectedWidth || height != expectedHeight) return null
            val px = pixels
            if (forceFull) {
                val count = width * height
                if (dest.size < count) return null
                System.arraycopy(px, 0, dest, 0, count)
                clearDirtyLocked()
                return DirtyRegion(0, 0, width, height)
            }
            val x0 = dirtyLeft.coerceAtLeast(0)
            val y0 = dirtyTop.coerceAtLeast(0)
            val x1 = dirtyRight.coerceAtMost(width)
            val y1 = dirtyBottom.coerceAtMost(height)
            if (x0 >= x1 || y0 >= y1) {
                clearDirtyLocked()
                return DirtyRegion(0, 0, 0, 0)
            }
            val dw = x1 - x0
            val dh = y1 - y0
            val needed = dw * dh
            if (dest.size < needed) return null
            if (dw == width) {
                System.arraycopy(px, y0 * width, dest, 0, needed)
            } else {
                val stride = width
                var dstPos = 0
                for (row in 0 until dh) {
                    System.arraycopy(px, (y0 + row) * stride + x0, dest, dstPos, dw)
                    dstPos += dw
                }
            }
            clearDirtyLocked()
            return DirtyRegion(x0, y0, dw, dh)
        }
    }
}
