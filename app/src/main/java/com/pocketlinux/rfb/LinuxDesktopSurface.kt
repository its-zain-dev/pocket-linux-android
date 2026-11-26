package com.pocketlinux.rfb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.InputType
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun LinuxDesktopSurface(
    controller: RfbController,
    modifier: Modifier = Modifier,
    touchInputMode: TouchInputMode = TouchInputMode.DIRECT_TAP,
    softKeyboardTrigger: Int = 0,
    hideKeyboardTrigger: Int = 0,
    autoResizeDesktop: Boolean = false
) {
    val currentController by rememberUpdatedState(controller)
    val currentMode by rememberUpdatedState(touchInputMode)
    val connectionState by controller.connectionState.collectAsState()
    val scope = rememberCoroutineScope()

    val surfaceHolderRef = remember { arrayOfNulls<DesktopSurfaceView>(1) }
    val viewSizeRef = remember { arrayOf(IntSize.Zero) }
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }

    val touchController = remember(controller) {
        TouchInputController(
            mode = touchInputMode,
            touchSlopPx = 8f,
            trackpadSensitivity = 1.6f,
            enableAcceleration = true,
            onPointerEvent = { x, y, mask ->
                currentController.sendPointerEvent(x, y, mask)
                surfaceHolderRef[0]?.requestRender()
            }
        )
    }

    LaunchedEffect(touchInputMode) {
        touchController.mode = touchInputMode
        surfaceHolderRef[0]?.requestRender()
    }

    LaunchedEffect(connectionState) {
        surfaceHolderRef[0]?.requestRender()
    }

    LaunchedEffect(softKeyboardTrigger) {
        if (softKeyboardTrigger > 0) {
            surfaceHolderRef[0]?.showSoftKeyboard()
        }
    }

    LaunchedEffect(hideKeyboardTrigger) {
        if (hideKeyboardTrigger > 0) {
            surfaceHolderRef[0]?.hideSoftKeyboard()
        }
    }

    val isConnected = connectionState is RfbConnectionState.Connected
    LaunchedEffect(autoResizeDesktop, surfaceSize, isConnected) {
        if (!autoResizeDesktop || !isConnected) return@LaunchedEffect
        if (surfaceSize.width <= 0 || surfaceSize.height <= 0) return@LaunchedEffect
        delay(200L)
        val target = computeAutoDesktopSize(
            surfaceWidth = surfaceSize.width,
            surfaceHeight = surfaceSize.height,
            minWidth = 640,
            minHeight = 480,
            maxWidth = 1280,
            maxHeight = 800
        ) ?: return@LaunchedEffect
        if (target.width != currentController.desktopWidth ||
            target.height != currentController.desktopHeight
        ) {
            currentController.requestDesktopSize(target.width, target.height)
        }
    }

    DisposableEffect(controller) {
        val removeListener = if (controller is RfbClient) {
            controller.addFrameListener {
                surfaceHolderRef[0]?.requestRender()
            }
        } else {
            null
        }
        onDispose {
            removeListener?.invoke()
            touchController.onTouchCancel()
        }
    }

    Box(
        modifier = modifier
            .testTag("desktop_surface")
            .focusable()
            .onSizeChanged { newSize ->
                viewSizeRef[0] = newSize
                surfaceSize = newSize
            }
            .onPreviewKeyEvent { composeKeyEvent ->
                val nativeEvent = composeKeyEvent.nativeKeyEvent
                dispatchNativeKeyEvent(currentController, nativeEvent)
            }
            .pointerInput(controller) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    val surfaceView = surfaceHolderRef[0]
                    if (surfaceView != null && !surfaceView.hasFocus()) {
                        surfaceView.requestFocus()
                    }

                    val size = viewSizeRef[0]
                    val dw = currentController.desktopWidth
                    val dh = currentController.desktopHeight
                    touchController.onTouchDown(
                        pointerId = firstDown.id.value.toInt(),
                        x = firstDown.position.x,
                        y = firstDown.position.y,
                        viewWidth = size.width,
                        viewHeight = size.height,
                        desktopWidth = dw,
                        desktopHeight = dh,
                        nowMs = System.currentTimeMillis()
                    )
                    firstDown.consume()

                    var longPressJob: Job? = scope.launch {
                        delay(500L)
                        if (touchController.checkLongPress(System.currentTimeMillis())) {
                            surfaceHolderRef[0]?.requestRender()
                        }
                    }

                    val activeIds = HashSet<Int>(4)
                    activeIds.add(firstDown.id.value.toInt())
                    val movedMap = HashMap<Int, Pair<Float, Float>>(4)

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val currentSize = viewSizeRef[0]
                            val w = currentController.desktopWidth
                            val h = currentController.desktopHeight
                            val now = System.currentTimeMillis()

                            when (event.type) {
                                PointerEventType.Press -> {
                                    for (change in event.changes) {
                                        val pid = change.id.value.toInt()
                                        if (change.pressed && activeIds.add(pid)) {
                                            longPressJob?.cancel()
                                            longPressJob = null
                                            touchController.onPointerDown(
                                                pointerId = pid,
                                                x = change.position.x,
                                                y = change.position.y,
                                                nowMs = now
                                            )
                                        }
                                        change.consume()
                                    }
                                }
                                PointerEventType.Move -> {
                                    movedMap.clear()
                                    for (change in event.changes) {
                                        movedMap[change.id.value.toInt()] =
                                            change.position.x to change.position.y
                                        change.consume()
                                    }
                                    touchController.onTouchMove(
                                        pointers = movedMap,
                                        viewWidth = currentSize.width,
                                        viewHeight = currentSize.height,
                                        desktopWidth = w,
                                        desktopHeight = h,
                                        nowMs = now
                                    )
                                }
                                PointerEventType.Release -> {
                                    for (change in event.changes) {
                                        val pid = change.id.value.toInt()
                                        if (!change.pressed && activeIds.remove(pid)) {
                                            longPressJob?.cancel()
                                            longPressJob = null
                                            if (activeIds.isEmpty()) {
                                                touchController.onTouchUp(
                                                    pointerId = pid,
                                                    x = change.position.x,
                                                    y = change.position.y,
                                                    viewWidth = currentSize.width,
                                                    viewHeight = currentSize.height,
                                                    desktopWidth = w,
                                                    desktopHeight = h,
                                                    nowMs = now
                                                )
                                            } else {
                                                touchController.onPointerUp(pid, now)
                                            }
                                        }
                                        change.consume()
                                    }
                                    if (activeIds.isEmpty()) break
                                }
                                else -> {
                                    for (change in event.changes) {
                                        if (!change.pressed) {
                                            activeIds.remove(change.id.value.toInt())
                                        }
                                        change.consume()
                                    }
                                    if (activeIds.isEmpty()) {
                                        touchController.onTouchCancel()
                                        break
                                    }
                                }
                            }
                        }
                    } finally {
                        longPressJob?.cancel()
                    }
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                DesktopSurfaceView(
                    context = ctx,
                    controllerProvider = { currentController },
                    touchController = touchController,
                    modeProvider = { currentMode }
                ).also { view ->
                    surfaceHolderRef[0] = view
                }
            },
            update = { view ->
                surfaceHolderRef[0] = view
                view.requestRender()
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun dispatchNativeKeyEvent(
    controller: RfbController,
    event: KeyEvent
): Boolean {
    val down = when (event.action) {
        KeyEvent.ACTION_DOWN -> true
        KeyEvent.ACTION_UP -> false
        else -> return false
    }

    val keySym = KeySymMapper.mapAndroidKeyCode(
        keyCode = event.keyCode,
        unicodeChar = event.unicodeChar,
        isShiftPressed = event.isShiftPressed
    ) ?: return false

    controller.sendKeyEvent(keySym, down)
    return true
}

private class DesktopSurfaceView(
    context: Context,
    private val controllerProvider: () -> RfbController,
    private val touchController: TouchInputController,
    private val modeProvider: () -> TouchInputMode
) : SurfaceView(context), SurfaceHolder.Callback {

    private val renderLock = Any()
    private val renderScheduled = AtomicBoolean(false)
    private val imeBridge = DesktopImeInputBridge { keySym, down ->
        controllerProvider().sendKeyEvent(keySym, down)
    }
    private val bitmapPaint = Paint().apply {
        isFilterBitmap = true
    }
    private val cursorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val cursorStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
    }
    private val dstRect = RectF()
    private val cursorDstRect = RectF()
    private val fallbackArrowPath = Path()

    @Volatile
    private var surfaceActive = false
    private var renderExecutor: ExecutorService? = null
    private var reusableBitmap: Bitmap? = null
    private var cachedCursorBitmap: Bitmap? = null
    private var cachedCursorGen: Long = -1L
    private var cachedCursorHotX: Int = 0
    private var cachedCursorHotY: Int = 0

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        holder.addCallback(this)
    }

    fun showSoftKeyboard() {
        imeBridge.reset()
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        post {
            requestFocus()
            imm.restartInput(this)
            val shown = imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            if (!shown) {
                postDelayed({
                    requestFocus()
                    imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                }, 80L)
            }
        }
    }

    fun hideSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        post {
            imm.hideSoftInputFromWindow(windowToken, 0)
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        imeBridge.reset()
        return object : BaseInputConnection(this, false) {
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                imeBridge.setComposingText(text)
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                imeBridge.commitText(text)
                return true
            }

            override fun finishComposingText(): Boolean {
                imeBridge.finishComposingText()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                imeBridge.deleteSurroundingText(beforeLength, afterLength)
                return true
            }

            override fun deleteSurroundingTextInCodePoints(
                beforeLength: Int,
                afterLength: Int
            ): Boolean {
                imeBridge.deleteSurroundingText(beforeLength, afterLength)
                return true
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                imeBridge.performEditorAction(editorAction)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                val handled = dispatchNativeKeyEvent(controllerProvider(), event)
                if (handled) {
                    imeBridge.onKeyEventHandled(event.keyCode, event.action == KeyEvent.ACTION_DOWN)
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handled = dispatchNativeKeyEvent(controllerProvider(), event)
        if (handled) {
            imeBridge.onKeyEventHandled(keyCode, true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return dispatchNativeKeyEvent(controllerProvider(), event) || super.onKeyUp(keyCode, event)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(renderLock) {
            surfaceActive = true
            if (renderExecutor == null) {
                renderExecutor = Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "PocketLinux-SurfaceRender").apply {
                        isDaemon = true
                    }
                }
            }
        }
        requestRender()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        requestRender()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val oldExecutor: ExecutorService?
        synchronized(renderLock) {
            surfaceActive = false
            oldExecutor = renderExecutor
            renderExecutor = null
            reusableBitmap?.recycle()
            reusableBitmap = null
            cachedCursorBitmap?.recycle()
            cachedCursorBitmap = null
            cachedCursorGen = -1L
        }
        oldExecutor?.shutdownNow()
    }

    fun requestRender() {
        val executor = synchronized(renderLock) {
            if (!surfaceActive) return
            renderExecutor
        } ?: return

        if (renderScheduled.compareAndSet(false, true)) {
            try {
                executor.execute {
                    renderScheduled.set(false)
                    drawFrame()
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                renderScheduled.set(false)
            }
        }
    }

    private fun resolveCursorBitmap(rfbClient: RfbClient): Bitmap? {
        val gen = rfbClient.cursorGeneration.get()
        if (gen == cachedCursorGen) {
            return cachedCursorBitmap
        }
        cachedCursorBitmap?.recycle()
        cachedCursorBitmap = null
        cachedCursorGen = gen

        val shape = rfbClient.cursorShape ?: return null
        if (shape.width <= 0 || shape.height <= 0 || shape.pixels.size < shape.width * shape.height) {
            return null
        }
        cachedCursorHotX = shape.hotX
        cachedCursorHotY = shape.hotY
        val bmp = Bitmap.createBitmap(shape.pixels, shape.width, shape.height, Bitmap.Config.ARGB_8888)
        cachedCursorBitmap = bmp
        return bmp
    }

    private fun drawFallbackArrow(canvas: android.graphics.Canvas, cx: Float, cy: Float) {
        fallbackArrowPath.reset()
        fallbackArrowPath.moveTo(cx, cy)
        fallbackArrowPath.lineTo(cx, cy + 20f)
        fallbackArrowPath.lineTo(cx + 5.5f, cy + 15f)
        fallbackArrowPath.lineTo(cx + 9.5f, cy + 23f)
        fallbackArrowPath.lineTo(cx + 12.5f, cy + 21.5f)
        fallbackArrowPath.lineTo(cx + 8.5f, cy + 13.5f)
        fallbackArrowPath.lineTo(cx + 15f, cy + 13.5f)
        fallbackArrowPath.close()
        canvas.drawPath(fallbackArrowPath, cursorFillPaint)
        canvas.drawPath(fallbackArrowPath, cursorStrokePaint)
    }

    private fun drawFrame() {
        synchronized(renderLock) {
            if (!surfaceActive || !holder.surface.isValid) return

            val controller = controllerProvider()
            var ownedSnapshot: Bitmap? = null
            val frameBitmap = if (controller is RfbClient) {
                reusableBitmap = controller.renderIntoBitmap(reusableBitmap)
                reusableBitmap
            } else {
                ownedSnapshot = controller.snapshotBitmap()
                ownedSnapshot
            }
            val cursorBmp = if (controller is RfbClient) resolveCursorBitmap(controller) else null

            val canvas = try {
                holder.lockHardwareCanvas()
            } catch (_: UnsupportedOperationException) {
                holder.lockCanvas()
            } catch (_: IllegalStateException) {
                null
            }

            if (canvas == null) {
                ownedSnapshot?.recycle()
                return
            }

            try {
                canvas.drawColor(0xFF090D16.toInt())
                if (frameBitmap != null && !frameBitmap.isRecycled) {
                    val vp = computeViewport(
                        viewWidth = canvas.width,
                        viewHeight = canvas.height,
                        desktopWidth = frameBitmap.width,
                        desktopHeight = frameBitmap.height
                    )
                    if (vp != null) {
                        dstRect.set(
                            vp.offsetX,
                            vp.offsetY,
                            vp.offsetX + vp.scaledWidth,
                            vp.offsetY + vp.scaledHeight
                        )
                        canvas.drawBitmap(frameBitmap, null, dstRect, bitmapPaint)

                        val curX = touchController.cursorX
                        val curY = touchController.cursorY
                        val cursorHiddenByServer = (controller as? RfbClient)?.cursorShape?.let {
                            it.width == 0 || it.height == 0
                        } == true
                        if (
                            !cursorHiddenByServer &&
                            (curX in 0 until frameBitmap.width && curY in 0 until frameBitmap.height) &&
                            (modeProvider() == TouchInputMode.TRACKPAD || cursorBmp != null || touchController.isDragging)
                        ) {
                            val (cx, cy) = desktopToView(curX, curY, vp)
                            if (cursorBmp != null && !cursorBmp.isRecycled) {
                                val left = cx - cachedCursorHotX * vp.scale
                                val top = cy - cachedCursorHotY * vp.scale
                                cursorDstRect.set(
                                    left,
                                    top,
                                    left + cursorBmp.width * vp.scale,
                                    top + cursorBmp.height * vp.scale
                                )
                                canvas.drawBitmap(cursorBmp, null, cursorDstRect, bitmapPaint)
                            } else {
                                drawFallbackArrow(canvas, cx, cy)
                            }
                        }
                    }
                }
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (_: IllegalStateException) {
                } catch (_: IllegalArgumentException) {
                }
                ownedSnapshot?.recycle()
            }
        }
    }
}
