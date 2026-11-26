package com.pocketlinux.rfb

import android.view.KeyEvent
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

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

data class DesktopSize(
    val width: Int,
    val height: Int
)

/**
 * Computes clean even-numbered desktop dimensions matching [surfaceWidth] x [surfaceHeight]'s
 * aspect ratio, clamped between [minWidth] x [minHeight] and [maxWidth] x [maxHeight].
 */
fun computeAutoDesktopSize(
    surfaceWidth: Int,
    surfaceHeight: Int,
    minWidth: Int = 800,
    minHeight: Int = 480,
    maxWidth: Int = 1920,
    maxHeight: Int = 1080
): DesktopSize? {
    if (surfaceWidth <= 0 || surfaceHeight <= 0) return null

    var w = surfaceWidth.toFloat()
    var h = surfaceHeight.toFloat()

    if (w > maxWidth || h > maxHeight) {
        val downScale = minOf(maxWidth / w, maxHeight / h)
        w *= downScale
        h *= downScale
    }

    if (w < minWidth || h < minHeight) {
        val upScale = maxOf(minWidth / w, minHeight / h)
        w *= upScale
        h *= upScale
    }

    fun clampEven(value: Float, minVal: Int, maxVal: Int): Int {
        val clamped = value.coerceIn(minVal.toFloat(), maxVal.toFloat())
        val even = ((clamped / 2f).roundToInt() * 2)
        return even.coerceIn(minVal, maxVal)
    }

    return DesktopSize(
        width = clampEven(w, minWidth, maxWidth),
        height = clampEven(h, minHeight, maxHeight)
    )
}

/**
 * Pure-JVM bridge translating Android IME InputConnection callbacks into RFB KeySym events.
 * Handles direct commitText, Gboard/Samsung composing sessions (setComposingText /
 * finishComposingText) without dropping or duplicating words, Enter/Return editor actions,
 * and Backspace/Delete surrounding-text operations.
 */
class DesktopImeInputBridge(
    private val sendKey: (keySym: Int, down: Boolean) -> Unit
) {
    private var composingCodePoints: IntArray = IntArray(0)

    private fun tapKey(keySym: Int) {
        sendKey(keySym, true)
        sendKey(keySym, false)
    }

    private fun sendText(text: String) {
        val normalized = text.replace("\r\n", "\n")
        for (ks in KeySymMapper.textToKeySymSequence(normalized)) {
            tapKey(ks)
        }
    }

    private fun toCodePoints(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val normalized = text.replace("\r\n", "\n")
        val count = normalized.codePointCount(0, normalized.length)
        val out = IntArray(count)
        var offset = 0
        var idx = 0
        while (offset < normalized.length) {
            val cp = normalized.codePointAt(offset)
            out[idx++] = cp
            offset += Character.charCount(cp)
        }
        return out
    }

    private fun replaceComposingWith(newText: String, keepAsComposing: Boolean) {
        val next = toCodePoints(newText)
        val minLen = minOf(composingCodePoints.size, next.size)
        var commonPrefix = 0
        while (commonPrefix < minLen && composingCodePoints[commonPrefix] == next[commonPrefix]) {
            commonPrefix++
        }
        val toDelete = composingCodePoints.size - commonPrefix
        repeat(toDelete) {
            tapKey(KeySymMapper.XK_BACKSPACE)
        }
        for (i in commonPrefix until next.size) {
            val ks = KeySymMapper.unicodeToKeySym(next[i])
            if (ks != null) {
                tapKey(ks)
            }
        }
        composingCodePoints = if (keepAsComposing) next else IntArray(0)
    }

    fun setComposingText(text: CharSequence?) {
        replaceComposingWith(text?.toString().orEmpty(), keepAsComposing = true)
    }

    fun commitText(text: CharSequence?) {
        val str = text?.toString().orEmpty()
        if (composingCodePoints.isNotEmpty()) {
            replaceComposingWith(str, keepAsComposing = false)
        } else if (str.isNotEmpty()) {
            sendText(str)
        }
    }

    fun finishComposingText() {
        composingCodePoints = IntArray(0)
    }

    fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
        val before = beforeLength.coerceAtLeast(0)
        val after = afterLength.coerceAtLeast(0)
        repeat(before) {
            tapKey(KeySymMapper.XK_BACKSPACE)
        }
        if (before > 0 && composingCodePoints.isNotEmpty()) {
            val remaining = (composingCodePoints.size - before).coerceAtLeast(0)
            composingCodePoints = composingCodePoints.copyOf(remaining)
        }
        repeat(after) {
            tapKey(KeySymMapper.XK_DELETE)
        }
    }

    fun performEditorAction(actionCode: Int) {
        composingCodePoints = IntArray(0)
        tapKey(KeySymMapper.XK_RETURN)
    }

    fun onKeyEventHandled(keyCode: Int, isDown: Boolean) {
        if (!isDown) return
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                if (composingCodePoints.isNotEmpty()) {
                    composingCodePoints = composingCodePoints.copyOf(composingCodePoints.size - 1)
                }
            }
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                composingCodePoints = IntArray(0)
            }
        }
    }

    fun reset() {
        composingCodePoints = IntArray(0)
    }
}

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
    private val enableAcceleration: Boolean = false,
    private val snapDoubleTap: Boolean = false,
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
    var dragLocked: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (cursorX >= 0 && cursorY >= 0) {
                    onPointerEvent(cursorX, cursorY, if (value) RfbConstants.BUTTON_LEFT else 0)
                }
            }
        }

    @Volatile
    var cursorX: Int = -1
        private set

    @Volatile
    var cursorY: Int = -1
        private set

    val isDragging: Boolean
        get() = state == GestureState.DRAGGING || dragLocked

    private var state = GestureState.IDLE
    private val activePointers = LinkedHashMap<Int, Pair<Float, Float>>()

    private var primaryPointerId = -1
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastMoveTimeMs = 0L
    private var downDesktopX = 0
    private var downDesktopY = 0
    private var downTimeMs = 0L

    private var longPressTriggered = false
    private var trackpadMoved = false
    private var doubleTapCandidate = false
    private var lastTapUpTimeMs = 0L
    private var lastDirectTapTimeMs = 0L
    private var lastDirectTapX = -1
    private var lastDirectTapY = -1

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
        lastMoveTimeMs = nowMs
        downTimeMs = nowMs
        longPressTriggered = false
        trackpadMoved = false
        twoFingerMoved = false
        twoFingerTapFired = false
        accumX = 0f
        accumY = 0f

        if (mode == TouchInputMode.DIRECT_TAP) {
            val pt = viewToDesktop(x, y, viewWidth, viewHeight, desktopWidth, desktopHeight) ?: return
            var targetX = pt.x
            var targetY = pt.y
            if (snapDoubleTap &&
                (nowMs - lastDirectTapTimeMs) in 1L..450L &&
                lastDirectTapX >= 0 && lastDirectTapY >= 0 &&
                hypot((pt.x - lastDirectTapX).toFloat(), (pt.y - lastDirectTapY).toFloat()) <= 28f
            ) {
                targetX = lastDirectTapX
                targetY = lastDirectTapY
            }
            cursorX = targetX
            cursorY = targetY
            downDesktopX = targetX
            downDesktopY = targetY
            state = GestureState.PENDING_TAP
            onPointerEvent(cursorX, cursorY, if (dragLocked) RfbConstants.BUTTON_LEFT else 0)
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
            if (state == GestureState.DRAGGING && !dragLocked && cursorX >= 0 && cursorY >= 0) {
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
        if (state != GestureState.PENDING_TAP || longPressTriggered || dragLocked || cursorX < 0 || cursorY < 0) {
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
                    val rawDx = x - lastX
                    val rawDy = y - lastY
                    val accel = if (enableAcceleration) {
                        val dtMs = (nowMs - lastMoveTimeMs).coerceIn(4L, 100L).toFloat()
                        val speedPxPerMs = hypot(rawDx, rawDy) / dtMs
                        (1.0f + (speedPxPerMs - 0.35f).coerceAtLeast(0f) * 0.55f).coerceIn(1.0f, 2.25f)
                    } else {
                        1.0f
                    }
                    val gain = trackpadSensitivity * accel
                    accumX += (rawDx / scale) * gain
                    accumY += (rawDy / scale) * gain
                    lastX = x
                    lastY = y
                    lastMoveTimeMs = nowMs

                    val dx = accumX.toInt()
                    val dy = accumY.toInt()
                    accumX -= dx
                    accumY -= dy

                    if (dx != 0 || dy != 0) {
                        cursorX = (cursorX + dx).coerceIn(0, desktopWidth - 1)
                        cursorY = (cursorY + dy).coerceIn(0, desktopHeight - 1)
                        val mask = if (state == GestureState.DRAGGING || dragLocked) RfbConstants.BUTTON_LEFT else 0
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
                if (!dragLocked && cursorX >= 0 && cursorY >= 0) {
                    onPointerEvent(cursorX, cursorY, 0)
                }
                lastTapUpTimeMs = 0L
                state = GestureState.IDLE
            }
            GestureState.PENDING_TAP -> {
                if (mode == TouchInputMode.DIRECT_TAP) {
                    val pt = viewToDesktop(x, y, viewWidth, viewHeight, desktopWidth, desktopHeight)
                    if (pt != null) {
                        if (snapDoubleTap &&
                            (nowMs - lastDirectTapTimeMs) in 1L..450L &&
                            lastDirectTapX >= 0 && lastDirectTapY >= 0 &&
                            hypot((pt.x - lastDirectTapX).toFloat(), (pt.y - lastDirectTapY).toFloat()) <= 28f
                        ) {
                            cursorX = lastDirectTapX
                            cursorY = lastDirectTapY
                        } else {
                            cursorX = pt.x
                            cursorY = pt.y
                        }
                    }
                    if (!longPressTriggered && cursorX >= 0 && cursorY >= 0) {
                        if (nowMs - downTimeMs >= longPressMs) {
                            onPointerEvent(cursorX, cursorY, RfbConstants.BUTTON_RIGHT)
                            onPointerEvent(cursorX, cursorY, 0)
                            lastDirectTapTimeMs = 0L
                        } else if (!dragLocked) {
                            onPointerEvent(cursorX, cursorY, RfbConstants.BUTTON_LEFT)
                            onPointerEvent(cursorX, cursorY, 0)
                            lastDirectTapTimeMs = nowMs
                            lastDirectTapX = cursorX
                            lastDirectTapY = cursorY
                        }
                    }
                } else {
                    if (!trackpadMoved && !longPressTriggered && cursorX >= 0 && cursorY >= 0) {
                        if (!doubleTapCandidate && nowMs - downTimeMs >= longPressMs) {
                            onPointerEvent(cursorX, cursorY, RfbConstants.BUTTON_RIGHT)
                            onPointerEvent(cursorX, cursorY, 0)
                            lastTapUpTimeMs = 0L
                        } else if (!dragLocked) {
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
        if ((state == GestureState.DRAGGING || dragLocked) && cursorX >= 0 && cursorY >= 0) {
            onPointerEvent(cursorX, cursorY, 0)
        }
        dragLocked = false
        activePointers.clear()
        state = GestureState.IDLE
        longPressTriggered = false
        trackpadMoved = false
        doubleTapCandidate = false
    }
}
