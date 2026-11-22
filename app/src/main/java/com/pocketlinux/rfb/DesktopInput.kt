package com.pocketlinux.rfb

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

data class DesktopViewport(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val scaledWidth: Float,
    val scaledHeight: Float
)

data class DesktopPoint(
    val x: Int,
    val y: Int
)

fun computeViewport(
    viewWidth: Int,
    viewHeight: Int,
    desktopWidth: Int,
    desktopHeight: Int
): DesktopViewport? {
    if (viewWidth <= 0 || viewHeight <= 0 || desktopWidth <= 0 || desktopHeight <= 0) {
        return null
    }
    val scale = minOf(
        viewWidth.toFloat() / desktopWidth.toFloat(),
        viewHeight.toFloat() / desktopHeight.toFloat()
    )
    if (scale <= 0f || !scale.isFinite()) return null
    val scaledWidth = desktopWidth.toFloat() * scale
    val scaledHeight = desktopHeight.toFloat() * scale
    val offsetX = (viewWidth.toFloat() - scaledWidth) / 2f
    val offsetY = (viewHeight.toFloat() - scaledHeight) / 2f
    return DesktopViewport(
        scale = scale,
        offsetX = offsetX,
        offsetY = offsetY,
        scaledWidth = scaledWidth,
        scaledHeight = scaledHeight
    )
}

fun viewToDesktop(
    viewX: Float,
    viewY: Float,
    viewWidth: Int,
    viewHeight: Int,
    desktopWidth: Int,
    desktopHeight: Int,
    clampToBounds: Boolean = true
): DesktopPoint? {
    val vp = computeViewport(viewWidth, viewHeight, desktopWidth, desktopHeight) ?: return null
    val rawX = floor((viewX - vp.offsetX) / vp.scale).toInt()
    val rawY = floor((viewY - vp.offsetY) / vp.scale).toInt()
    if (!clampToBounds && (rawX !in 0 until desktopWidth || rawY !in 0 until desktopHeight)) {
        return null
    }
    return DesktopPoint(
        x = rawX.coerceIn(0, desktopWidth - 1),
        y = rawY.coerceIn(0, desktopHeight - 1)
    )
}

fun desktopToView(
    desktopX: Int,
    desktopY: Int,
    viewport: DesktopViewport
): Pair<Float, Float> {
    val vx = viewport.offsetX + (desktopX.toFloat() + 0.5f) * viewport.scale
    val vy = viewport.offsetY + (desktopY.toFloat() + 0.5f) * viewport.scale
    return vx to vy
}

/**
 * Pure-JVM touch gesture state machine supporting [TouchInputMode.DIRECT_TAP] and
 * [TouchInputMode.TRACKPAD].
 */
class TouchInputController(
    mode: TouchInputMode = TouchInputMode.DIRECT_TAP,
    private val touchSlopPx: Float = 12f,
    private val longPressMs: Long = 500L,
    private val doubleTapMs: Long = 300L,
    private val scrollStepPx: Float = 24f,
    private val trackpadSensitivity: Float = 1.0f,
    private val onPointerEvent: (x: Int, y: Int, buttonMask: Int) -> Unit
) {
    private enum class GestureState {
        IDLE,
        PENDING_TAP,
        DRAGGING,
        TWO_FINGER,
        WAIT_ALL_UP
    }

    var mode: TouchInputMode = mode
        set(value) {
            if (field != value) {
                onTouchCancel()
                field = value
            }
        }

    @Volatile
    var cursorX: Int = -1
        private set

    @Volatile
    var cursorY: Int = -1
        private set

    private var state = GestureState.IDLE
    private val activePointers = LinkedHashMap<Int, Pair<Float, Float>>()

    private var primaryPointerId = -1
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downDesktopX = 0
    private var downDesktopY = 0
    private var downTimeMs = 0L

    private var longPressTriggered = false
    private var trackpadMoved = false
    private var doubleTapCandidate = false
    private var lastTapUpTimeMs = 0L

    private var accumX = 0f
    private var accumY = 0f

    private var scrollAnchorY = 0f
    private var twoFingerDownX0 = 0f
    private var twoFingerDownY0 = 0f
    private var twoFingerDownX1 = 0f
    private var twoFingerDownY1 = 0f
    private var twoFingerDownTimeMs = 0L
    private var twoFingerMoved = false
    private var twoFingerTapFired = false

    fun onTouchDown(
        pointerId: Int,
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
        desktopWidth: Int,
        desktopHeight: Int,
        nowMs: Long
    ) {
        if (desktopWidth <= 0 || desktopHeight <= 0) return
        activePointers.clear()
        activePointers[pointerId] = x to y
        primaryPointerId = pointerId
        downX = x
        downY = y
        lastX = x
        lastY = y
        downTimeMs = nowMs
        longPressTriggered = false
        trackpadMoved = false
        twoFingerMoved = false
        twoFingerTapFired = false
        accumX = 0f
        accumY = 0f

        if (mode == TouchInputMode.DIRECT_TAP) {
            val pt = viewToDesktop(x, y, viewWidth, viewHeight, desktopWidth, desktopHeight) ?: return
            cursorX = pt.x
            cursorY = pt.y
            downDesktopX = pt.x
            downDesktopY = pt.y
            state = GestureState.PENDING_TAP
            onPointerEvent(cursorX, cursorY, 0)
        } else {
            if (cursorX !in 0 until desktopWidth || cursorY !in 0 until desktopHeight) {
                cursorX = desktopWidth / 2
                cursorY = desktopHeight / 2
            }
            doubleTapCandidate = (nowMs - lastTapUpTimeMs) in 1L..doubleTapMs
            state = GestureState.PENDING_TAP
        }
    }

    fun onPointerDown(
        pointerId: Int,
        x: Float,
        y: Float,
        nowMs: Long
    ) {
        activePointers[pointerId] = x to y
        if (activePointers.size == 2 && state != GestureState.WAIT_ALL_UP) {
            if (state == GestureState.DRAGGING && cursorX >= 0 && cursorY >= 0) {
                onPointerEvent(cursorX, cursorY, 0)
            }
            val pts = activePointers.values.toList()
            twoFingerDownX0 = pts[0].first
            twoFingerDownY0 = pts[0].second
            twoFingerDownX1 = pts[1].first
            twoFingerDownY1 = pts[1].second
            scrollAnchorY = (twoFingerDownY0 + twoFingerDownY1) / 2f
            twoFingerDownTimeMs = nowMs
            twoFingerMoved = false
            twoFingerTapFired = false
            doubleTapCandidate = false
            state = GestureState.TWO_FINGER
        }
    }

    fun checkLongPress(nowMs: Long): Boolean {
        if (state != GestureState.PENDING_TAP || longPressTriggered || cursorX < 0 || cursorY < 0) {
            return false
        }
        if (mode == TouchInputMode.TRACKPAD && (trackpadMoved || doubleTapCandidate)) {
            return false
        }
        if (nowMs - downTimeMs >= longPressMs) {
            longPressTriggered = true
            onPointerEvent(cursorX, cursorY, RfbConstants.BUTTON_RIGHT)
            onPointerEvent(cursorX, cursorY, 0)
            state = GestureState.WAIT_ALL_UP
            return true
        }
        return false
    }

    fun onTouchMove(
        pointers: Map<Int, Pair<Float, Float>>,
        viewWidth: Int,
        viewHeight: Int,
        desktopWidth: Int,
        desktopHeight: Int,
        nowMs: Long
    ) {
        if (desktopWidth <= 0 || desktopHeight <= 0) return
        for ((id, pos) in pointers) {
            if (activePointers.containsKey(id)) {
                activePointers[id] = pos
            }
        }

        when (state) {
            GestureState.IDLE, GestureState.WAIT_ALL_UP -> return
            GestureState.TWO_FINGER -> {
                if (activePointers.size < 2) return
                val pts = activePointers.values.toList()
                val avgY = (pts[0].second + pts[1].second) / 2f
                val move0 = hypot(pts[0].first - twoFingerDownX0, pts[0].second - twoFingerDownY0)
                val move1 = hypot(pts[1].first - twoFingerDownX1, pts[1].second - twoFingerDownY1)
                if (move0 > touchSlopPx || move1 > touchSlopPx) {
                    twoFingerMoved = true
                }

                var deltaY = avgY - scrollAnchorY
                while (abs(deltaY) >= scrollStepPx) {
                    twoFingerMoved = true
                    val wheelMask = if (deltaY > 0f) {
                        scrollAnchorY += scrollStepPx
                        deltaY -= scrollStepPx
                        RfbConstants.BUTTON_WHEEL_UP
                    } else {
                        scrollAnchorY -= scrollStepPx
                        deltaY += scrollStepPx
                        RfbConstants.BUTTON_WHEEL_DOWN
                    }
                    if (cursorX >= 0 && cursorY >= 0) {
                        onPointerEvent(cursorX, cursorY, wheelMask)
                        onPointerEvent(cursorX, cursorY, 0)
                    }
                }
            }
            GestureState.PENDING_TAP, GestureState.DRAGGING -> {
                val pos = activePointers[primaryPointerId] ?: return
                val x = pos.first
                val y = pos.second

                if (mode == TouchInputMode.DIRECT_TAP) {
                    val pt = viewToDesktop(x, y, viewWidth, viewHeight, desktopWidth, desktopHeight) ?: return
                    if (state == GestureState.PENDING_TAP) {
                        if (hypot(x - downX, y - downY) > touchSlopPx) {
                            state = GestureState.DRAGGING
                            onPointerEvent(downDesktopX, downDesktopY, RfbConstants.BUTTON_LEFT)
                            cursorX = pt.x
                            cursorY = pt.y
                            onPointerEvent(cursorX, cursorY, RfbConstants.BUTTON_LEFT)
                        }
                    } else {
                        cursorX = pt.x
                        cursorY = pt.y
                        onPointerEvent(cursorX, cursorY, RfbConstants.BUTTON_LEFT)
                    }
                } else {
                    if (!trackpadMoved && hypot(x - downX, y - downY) > touchSlopPx) {
                        trackpadMoved = true
                        if (doubleTapCandidate) {
                            state = GestureState.DRAGGING
                            onPointerEvent(cursorX, cursorY, RfbConstants.BUTTON_LEFT)
                        }
                    } else if (!trackpadMoved && !doubleTapCandidate && nowMs - downTimeMs >= longPressMs) {
                        checkLongPress(nowMs)
                        return
                    }

                    val vp = computeViewport(viewWidth, viewHeight, desktopWidth, desktopHeight)
                    val scale = vp?.scale ?: 1f
                    accumX += ((x - lastX) / scale) * trackpadSensitivity
                    accumY += ((y - lastY) / scale) * trackpadSensitivity
                    lastX = x
                    lastY = y

                    val dx = accumX.toInt()
                    val dy = accumY.toInt()
                    accumX -= dx
                    accumY -= dy

                    if (dx != 0 || dy != 0) {
                        cursorX = (cursorX + dx).coerceIn(0, desktopWidth - 1)
                        cursorY = (cursorY + dy).coerceIn(0, desktopHeight - 1)
                        val mask = if (state == GestureState.DRAGGING) RfbConstants.BUTTON_LEFT else 0
                        onPointerEvent(cursorX, cursorY, mask)
                    }
                }
            }
        }
    }

    fun onPointerUp(
        pointerId: Int,
        nowMs: Long
    ) {
        activePointers.remove(pointerId)
        if (state == GestureState.TWO_FINGER) {
            if (
                mode == TouchInputMode.TRACKPAD &&
                !twoFingerMoved &&
                !twoFingerTapFired &&
                nowMs - twoFingerDownTimeMs < longPressMs &&
                cursorX >= 0 && cursorY >= 0
            ) {
                twoFingerTapFired = true
                onPointerEvent(cursorX, cursorY, RfbConstants.BUTTON_RIGHT)
                onPointerEvent(cursorX, cursorY, 0)
            }
            state = GestureState.WAIT_ALL_UP
        }
        if (activePointers.isEmpty()) {
            state = GestureState.IDLE
        }
    }

    fun onTouchUp(
        pointerId: Int,
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
        desktopWidth: Int,
        desktopHeight: Int,
        nowMs: Long
    ) {
        activePointers.remove(pointerId)
        when (state) {
            GestureState.IDLE -> return
            GestureState.WAIT_ALL_UP -> {
                if (activePointers.isEmpty()) {
                    state = GestureState.IDLE
                }
            }
            GestureState.TWO_FINGER -> {
                if (
                    mode == TouchInputMode.TRACKPAD &&
                    !twoFingerMoved &&
                    !twoFingerTapFired &&
                    nowMs - twoFingerDownTimeMs < longPressMs &&
                    cursorX >= 0 && cursorY >= 0
                ) {
                    twoFingerTapFired = true
                    onPointerEvent(cursorX, cursorY, RfbConstants.BUTTON_RIGHT)
                    onPointerEvent(cursorX, cursorY, 0)
                }
                state = if (activePointers.isEmpty()) GestureState.IDLE else GestureState.WAIT_ALL_UP
            }
            GestureState.DRAGGING -> {
                if (mode == TouchInputMode.DIRECT_TAP) {
                    val pt = viewToDesktop(x, y, viewWidth, viewHeight, desktopWidth, desktopHeight)
                    if (pt != null) {
                        cursorX = pt.x
                        cursorY = pt.y
                    }
                }
                if (cursorX >= 0 && cursorY >= 0) {
                    onPointerEvent(cursorX, cursorY, 0)
                }
                lastTapUpTimeMs = 0L
                state = GestureState.IDLE
            }
            GestureState.PENDING_TAP -> {
                if (mode == TouchInputMode.DIRECT_TAP) {
                    val pt = viewToDesktop(x, y, viewWidth, viewHeight, desktopWidth, desktopHeight)
                    if (pt != null) {
                        cursorX = pt.x
                        cursorY = pt.y
                    }
                    if (!longPressTriggered && cursorX >= 0 && cursorY >= 0) {
                        if (nowMs - downTimeMs >= longPressMs) {
                            onPointerEvent(cursorX, cursorY, RfbConstants.BUTTON_RIGHT)
                            onPointerEvent(cursorX, cursorY, 0)
                        } else {
                            onPointerEvent(cursorX, cursorY, RfbConstants.BUTTON_LEFT)
                            onPointerEvent(cursorX, cursorY, 0)
                        }
                    }
                } else {
                    if (!trackpadMoved && !longPressTriggered && cursorX >= 0 && cursorY >= 0) {
                        if (!doubleTapCandidate && nowMs - downTimeMs >= longPressMs) {
                            onPointerEvent(cursorX, cursorY, RfbConstants.BUTTON_RIGHT)
                            onPointerEvent(cursorX, cursorY, 0)
                            lastTapUpTimeMs = 0L
                        } else {
                            onPointerEvent(cursorX, cursorY, RfbConstants.BUTTON_LEFT)
                            onPointerEvent(cursorX, cursorY, 0)
                            lastTapUpTimeMs = nowMs
                        }
                    }
                }
                state = GestureState.IDLE
            }
        }
    }

    fun onTouchCancel() {
        if (state == GestureState.DRAGGING && cursorX >= 0 && cursorY >= 0) {
            onPointerEvent(cursorX, cursorY, 0)
        }
        activePointers.clear()
        state = GestureState.IDLE
        longPressTriggered = false
        trackpadMoved = false
        doubleTapCandidate = false
    }
}
