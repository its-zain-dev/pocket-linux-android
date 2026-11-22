package com.pocketlinux.rfb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RfbFramebufferTest {

    @Test
    fun uninitializedFramebufferReturnsNullSnapshotAndRejectsWrites() {
        val fb = RfbFramebuffer()
        assertNull(fb.snapshotPixels())
        assertFalse(fb.copyPixelsInto(IntArray(4), 2, 2))

        var threw = false
        try {
            fb.fillRect(0, 0, 1, 1, 0x112233)
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun resizeInitializesOpaqueBlackAndValidatesOverflowBounds() {
        val fb = RfbFramebuffer()
        fb.resize(4, 3)
        assertEquals(4, fb.width)
        assertEquals(3, fb.height)

        val snap = fb.snapshotPixels()
        assertNotNull(snap)
        assertEquals(12, snap!!.pixels.size)
        for (px in snap.pixels) {
            assertEquals(0xFF000000.toInt(), px)
        }

        val invalidDims = listOf(
            0 to 10,
            10 to 0,
            -1 to 10,
            RfbFramebuffer.MAX_DIMENSION + 1 to 1,
            1 to RfbFramebuffer.MAX_DIMENSION + 1,
            5000 to 5000 // 25,000,000 > MAX_PIXEL_COUNT (16,777,216)
        )
        for ((w, h) in invalidDims) {
            var threw = false
            try {
                RfbFramebuffer.validateDimensions(w, h)
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("Expected failure for ${w}x$h", threw)
        }
    }

    @Test
    fun writeRawRectBgrxForcesOpaqueAlphaEvenWhenXByteIsZero() {
        val fb = RfbFramebuffer()
        fb.resize(3, 2)

        // BGRX pixels at (1, 0) size 2x2:
        // (1,0): B=0x11, G=0x22, R=0x33, X=0x00 -> 0xFF332211
        // (2,0): B=0xAA, G=0xBB, R=0xCC, X=0x00 -> 0xFFCCBBAA
        // (1,1): B=0x01, G=0x02, R=0x03, X=0x7F -> 0xFF030201
        // (2,1): B=0xFF, G=0x00, R=0x80, X=0x00 -> 0xFF8000FF
        val bgrx = byteArrayOf(
            0x11, 0x22, 0x33, 0x00,
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x00,
            0x01, 0x02, 0x03, 0x7F,
            0xFF.toByte(), 0x00, 0x80.toByte(), 0x00
        )
        fb.writeRawRectBgrx(1, 0, 2, 2, bgrx)

        assertEquals(0xFF000000.toInt(), fb.getPixel(0, 0))
        assertEquals(0xFF332211.toInt(), fb.getPixel(1, 0))
        assertEquals(0xFFCCBBAA.toInt(), fb.getPixel(2, 0))
        assertEquals(0xFF000000.toInt(), fb.getPixel(0, 1))
        assertEquals(0xFF030201.toInt(), fb.getPixel(1, 1))
        assertEquals(0xFF8000FF.toInt(), fb.getPixel(2, 1))
    }

    @Test
    fun copyRectHandlesAllFourOverlapDirectionsCorrectly() {
        fun createPatternFb(): RfbFramebuffer {
            val fb = RfbFramebuffer()
            fb.resize(5, 5)
            for (y in 0 until 5) {
                for (x in 0 until 5) {
                    fb.fillRect(x, y, 1, 1, (y shl 8) or x)
                }
            }
            return fb
        }

        // 1. Overlapping down-right: (0,0,3x3) -> (1,1)
        val fbDownRight = createPatternFb()
        fbDownRight.copyRect(0, 0, 1, 1, 3, 3)
        for (dy in 0 until 3) {
            for (dx in 0 until 3) {
                assertEquals(0xFF000000.toInt() or (dy shl 8) or dx, fbDownRight.getPixel(1 + dx, 1 + dy))
            }
        }

        // 2. Overlapping up-left: (1,1,3x3) -> (0,0)
        val fbUpLeft = createPatternFb()
        fbUpLeft.copyRect(1, 1, 0, 0, 3, 3)
        for (dy in 0 until 3) {
            for (dx in 0 until 3) {
                assertEquals(0xFF000000.toInt() or ((1 + dy) shl 8) or (1 + dx), fbUpLeft.getPixel(dx, dy))
            }
        }

        // 3. Overlapping down-left: (1,0,3x3) -> (0,1)
        val fbDownLeft = createPatternFb()
        fbDownLeft.copyRect(1, 0, 0, 1, 3, 3)
        for (dy in 0 until 3) {
            for (dx in 0 until 3) {
                assertEquals(0xFF000000.toInt() or (dy shl 8) or (1 + dx), fbDownLeft.getPixel(dx, 1 + dy))
            }
        }

        // 4. Overlapping up-right: (0,1,3x3) -> (1,0)
        val fbUpRight = createPatternFb()
        fbUpRight.copyRect(0, 1, 1, 0, 3, 3)
        for (dy in 0 until 3) {
            for (dx in 0 until 3) {
                assertEquals(0xFF000000.toInt() or ((1 + dy) shl 8) or dx, fbUpRight.getPixel(1 + dx, dy))
            }
        }
    }

    @Test
    fun validateRectRejectsIntOverflowAndOutOfBounds() {
        val fb = RfbFramebuffer()
        fb.resize(100, 100)

        val badRects = listOf(
            listOf(90, 0, 11, 1),
            listOf(0, 90, 1, 11),
            listOf(-1, 0, 5, 5),
            listOf(0, -1, 5, 5),
            listOf(10, 10, Int.MAX_VALUE, 1),
            listOf(Int.MAX_VALUE, 0, 10, 1)
        )
        for (r in badRects) {
            var threw = false
            try {
                fb.fillRect(r[0], r[1], r[2], r[3], 0xFFFFFF)
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("Expected IllegalArgumentException for rect $r", threw)
        }
    }

    @Test
    fun snapshotIsImmutableAndCopyPixelsIntoChecksDimensions() {
        val fb = RfbFramebuffer()
        fb.resize(2, 2)
        fb.fillRect(0, 0, 2, 2, 0x00112233)

        val snap = fb.snapshotPixels()!!
        fb.fillRect(0, 0, 2, 2, 0x00445566)

        assertArrayEquals(
            intArrayOf(
                0xFF112233.toInt(), 0xFF112233.toInt(),
                0xFF112233.toInt(), 0xFF112233.toInt()
            ),
            snap.pixels
        )

        val dest = IntArray(4)
        assertFalse(fb.copyPixelsInto(dest, 3, 2))
        assertFalse(fb.copyPixelsInto(IntArray(3), 2, 2))
        assertTrue(fb.copyPixelsInto(dest, 2, 2))
        assertArrayEquals(
            intArrayOf(
                0xFF445566.toInt(), 0xFF445566.toInt(),
                0xFF445566.toInt(), 0xFF445566.toInt()
            ),
            dest
        )
    }
}
