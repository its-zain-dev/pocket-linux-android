package com.pocketlinux.rfb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSurfaceInputTest {

    @Test
    fun viewToDesktopPreservesAspectRatioWithPillarboxingAndLetterboxing() {
        // Desktop 1000x500 (2:1) inside View 1000x1000 -> scale 1.0, letterboxed y=[250, 750)
        val letterboxVp = computeViewport(1000, 1000, 1000, 500)!!
        assertEquals(1.0f, letterboxVp.scale, 0.001f)
        assertEquals(0f, letterboxVp.offsetX, 0.001f)
        assertEquals(250f, letterboxVp.offsetY, 0.001f)

        assertNull(viewToDesktop(100f, 100f, 1000, 1000, 1000, 500, clampToBounds = false))
        assertEquals(DesktopPoint(100, 0), viewToDesktop(100f, 100f, 1000, 1000, 1000, 500, clampToBounds = true))
        assertEquals(DesktopPoint(500, 250), viewToDesktop(500f, 500f, 1000, 1000, 1000, 500))
        assertEquals(DesktopPoint(999, 499), viewToDesktop(999.9f, 999f, 1000, 1000, 1000, 500, clampToBounds = true))

        // Desktop 500x1000 (1:2) inside View 1000x1000 -> scale 1.0, pillarboxed x=[250, 750)
        val pillarVp = computeViewport(1000, 1000, 500, 1000)!!
        assertEquals(250f, pillarVp.offsetX, 0.001f)
        assertEquals(0f, pillarVp.offsetY, 0.001f)
        assertEquals(DesktopPoint(250, 500), viewToDesktop(500f, 500f, 1000, 1000, 500, 1000))

        // Round-trip desktopToView
        val (vx, vy) = desktopToView(250, 500, pillarVp)
        assertEquals(500.5f, vx, 0.01f)
        assertEquals(500.5f, vy, 0.01f)

        // Invalid dimensions
        assertNull(computeViewport(0, 100, 100, 100))
        assertNull(viewToDesktop(10f, 10f, 100, 100, 0, 100))
    }

    @Test
    fun directTapHandlesTapDragLongPressRightClickAndTwoFingerScroll() {
        val events = mutableListOf<Triple<Int, Int, Int>>()
        val ctrl = TouchInputController(mode = TouchInputMode.DIRECT_TAP) { x, y, mask ->
            events.add(Triple(x, y, mask))
        }

        // 1. Tap -> hover(0), left down(1), left up(0)
        ctrl.onTouchDown(0, 100f, 200f, 1000, 1000, 1000, 1000, 1000L)
        ctrl.onTouchUp(0, 102f, 202f, 1000, 1000, 1000, 1000, 1080L)
        assertEquals(
            listOf(
                Triple(100, 200, 0),
                Triple(102, 202, RfbConstants.BUTTON_LEFT),
                Triple(102, 202, 0)
            ),
            events
        )

        // 2. Drag -> hover(0), button 1 down at anchor, move with button 1, up(0)
        events.clear()
        ctrl.onTouchDown(0, 50f, 50f, 1000, 1000, 1000, 1000, 2000L)
        ctrl.onTouchMove(mapOf(0 to (120f to 130f)), 1000, 1000, 1000, 1000, 2050L)
        ctrl.onTouchUp(0, 150f, 160f, 1000, 1000, 1000, 1000, 2100L)
        assertEquals(
            listOf(
                Triple(50, 50, 0),
                Triple(50, 50, RfbConstants.BUTTON_LEFT),
                Triple(120, 130, RfbConstants.BUTTON_LEFT),
                Triple(150, 160, 0)
            ),
            events
        )

        // 3. Long press -> right click (button 4 + 0) with NO spurious left click!
        events.clear()
        ctrl.onTouchDown(0, 300f, 400f, 1000, 1000, 1000, 1000, 3000L)
        assertFalse(ctrl.checkLongPress(3400L))
        assertTrue(ctrl.checkLongPress(3500L))
        ctrl.onTouchUp(0, 300f, 400f, 1000, 1000, 1000, 1000, 3600L)
        assertEquals(
            listOf(
                Triple(300, 400, 0),
                Triple(300, 400, RfbConstants.BUTTON_RIGHT),
                Triple(300, 400, 0)
            ),
            events
        )

        // 4. Two-finger vertical scroll -> wheel up/down (8 / 16)
        events.clear()
        ctrl.onTouchDown(0, 400f, 400f, 1000, 1000, 1000, 1000, 4000L)
        ctrl.onPointerDown(1, 450f, 400f, 4020L)
        ctrl.onTouchMove(
            mapOf(0 to (400f to 370f), 1 to (450f to 370f)),
            1000, 1000, 1000, 1000, 4060L
        )
        ctrl.onPointerUp(1, 4080L)
        ctrl.onTouchUp(0, 400f, 370f, 1000, 1000, 1000, 1000, 4100L)
        assertTrue(events.any { it.third == RfbConstants.BUTTON_WHEEL_DOWN })
        assertFalse(events.any { it.third == RfbConstants.BUTTON_LEFT })
    }

    @Test
    fun trackpadModeSupportsRelativeMoveDoubleTapDragAndTwoFingerRightClick() {
        val events = mutableListOf<Triple<Int, Int, Int>>()
        val ctrl = TouchInputController(mode = TouchInputMode.TRACKPAD) { x, y, mask ->
            events.add(Triple(x, y, mask))
        }

        // 1. Relative cursor move starting from center (500, 300)
        ctrl.onTouchDown(0, 100f, 100f, 1000, 600, 1000, 600, 1000L)
        ctrl.onTouchMove(mapOf(0 to (140f to 125f)), 1000, 600, 1000, 600, 1030L)
        ctrl.onTouchUp(0, 140f, 125f, 1000, 600, 1000, 600, 1060L)
        assertEquals(540, ctrl.cursorX)
        assertEquals(325, ctrl.cursorY)
        assertEquals(listOf(Triple(540, 325, 0)), events)

        // 2. Tap then double-tap-and-drag
        events.clear()
        ctrl.onTouchDown(0, 200f, 200f, 1000, 600, 1000, 600, 2000L)
        ctrl.onTouchUp(0, 200f, 200f, 1000, 600, 1000, 600, 2050L)
        // Second down within 300ms
        ctrl.onTouchDown(0, 200f, 200f, 1000, 600, 1000, 600, 2150L)
        ctrl.onTouchMove(mapOf(0 to (230f to 210f)), 1000, 600, 1000, 600, 2200L)
        ctrl.onTouchUp(0, 230f, 210f, 1000, 600, 1000, 600, 2250L)
        assertEquals(
            listOf(
                Triple(540, 325, RfbConstants.BUTTON_LEFT),
                Triple(540, 325, 0),
                Triple(540, 325, RfbConstants.BUTTON_LEFT),
                Triple(570, 335, RfbConstants.BUTTON_LEFT),
                Triple(570, 335, 0)
            ),
            events
        )

        // 3. Two-finger tap -> right click
        events.clear()
        ctrl.onTouchDown(0, 300f, 300f, 1000, 600, 1000, 600, 3000L)
        ctrl.onPointerDown(1, 340f, 300f, 3010L)
        ctrl.onPointerUp(1, 3060L)
        ctrl.onTouchUp(0, 300f, 300f, 1000, 600, 1000, 600, 3080L)
        assertEquals(
            listOf(
                Triple(570, 335, RfbConstants.BUTTON_RIGHT),
                Triple(570, 335, 0)
            ),
            events
        )
    }
}
