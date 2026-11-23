package com.pocketlinux.ui

import com.pocketlinux.runtime.LinuxRuntimeController
import com.pocketlinux.runtime.PtySession
import com.pocketlinux.runtime.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pure-Kotlin VT100 / ANSI terminal screen & scrollback emulator.
 * Handles printable UTF-8 text, CR/LF/BS/TAB, CSI cursor movement, screen/line erase
 * (`ESC[2J`, `ESC[H`, `ESC[K`), SGR attributes (`ESC[...m`), DEC private modes, and OSC sequences.
 */
class TerminalEmulator(
    cols: Int = DEFAULT_COLS,
    rows: Int = DEFAULT_ROWS,
    private val maxScrollbackLines: Int = DEFAULT_MAX_SCROLLBACK
) {
    var cols: Int = cols.coerceIn(10, 500)
        private set
    var rows: Int = rows.coerceIn(5, 200)
        private set

    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set

    private var grid: Array<CharArray> = Array(this.rows) { CharArray(this.cols) { ' ' } }
    private val scrollback = ArrayDeque<String>(maxScrollbackLines)

    private enum class ParseState {
        GROUND,
        ESC,
        CSI,
        OSC,
        OSC_ESC
    }

    private var parseState = ParseState.GROUND
    private val csiBuf = StringBuilder()

    @Synchronized
    fun reset() {
        for (r in 0 until rows) {
            grid[r].fill(' ')
        }
        scrollback.clear()
        cursorRow = 0
        cursorCol = 0
        parseState = ParseState.GROUND
        csiBuf.setLength(0)
    }

    @Synchronized
    fun resize(newCols: Int, newRows: Int) {
        val targetCols = newCols.coerceIn(10, 500)
        val targetRows = newRows.coerceIn(5, 200)
        if (targetCols == cols && targetRows == rows) return

        val newGrid = Array(targetRows) { CharArray(targetCols) { ' ' } }
        val copyRows = minOf(rows, targetRows)
        val copyCols = minOf(cols, targetCols)
        for (r in 0 until copyRows) {
            System.arraycopy(grid[r], 0, newGrid[r], 0, copyCols)
        }
        cols = targetCols
        rows = targetRows
        grid = newGrid
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
    }

    @Synchronized
    fun append(text: String) {
        for (i in 0 until text.length) {
            processChar(text[i])
        }
    }

    private fun processChar(ch: Char) {
        when (parseState) {
            ParseState.GROUND -> handleGroundChar(ch)
            ParseState.ESC -> handleEscChar(ch)
            ParseState.CSI -> handleCsiChar(ch)
            ParseState.OSC -> {
                if (ch == '\u0007') {
                    parseState = ParseState.GROUND
                } else if (ch == '\u001B') {
                    parseState = ParseState.OSC_ESC
                }
            }
            ParseState.OSC_ESC -> {
                parseState = ParseState.GROUND
            }
        }
    }

    private fun handleGroundChar(ch: Char) {
        when (ch) {
            '\u001B' -> {
                parseState = ParseState.ESC
            }
            '\r' -> {
                cursorCol = 0
            }
            '\n', '\u000B', '\u000C' -> {
                cursorCol = 0
                lineFeed()
            }
            '\b' -> {
                if (cursorCol > 0) {
                    cursorCol--
                }
            }
            '\t' -> {
                val nextStop = ((cursorCol / 8) + 1) * 8
                cursorCol = nextStop.coerceAtMost(cols - 1)
            }
            '\u0007', '\u0000' -> {
                // Ignore BEL / NUL
            }
            else -> {
                if (ch.code >= 0x20) {
                    if (cursorCol >= cols) {
                        cursorCol = 0
                        lineFeed()
                    }
                    grid[cursorRow][cursorCol] = ch
                    cursorCol++
                }
            }
        }
    }

    private fun handleEscChar(ch: Char) {
        when (ch) {
            '[' -> {
                csiBuf.setLength(0)
                parseState = ParseState.CSI
            }
            ']' -> {
                parseState = ParseState.OSC
            }
            'c' -> {
                reset()
            }
            'D', 'E' -> {
                if (ch == 'E') cursorCol = 0
                lineFeed()
                parseState = ParseState.GROUND
            }
            'M' -> {
                if (cursorRow > 0) cursorRow--
                parseState = ParseState.GROUND
            }
            else -> {
                parseState = ParseState.GROUND
            }
        }
    }

    private fun handleCsiChar(ch: Char) {
        if (ch.code in 0x40..0x7E) {
            val params = csiBuf.toString()
            csiBuf.setLength(0)
            parseState = ParseState.GROUND
            executeCsi(params, ch)
        } else {
            if (csiBuf.length < 64) {
                csiBuf.append(ch)
            } else {
                csiBuf.setLength(0)
                parseState = ParseState.GROUND
            }
        }
    }

    private fun executeCsi(rawParams: String, finalByte: Char) {
        val cleanParams = rawParams.trimStart('?', '>', '!')
        val parts = if (cleanParams.isEmpty()) {
            emptyList()
        } else {
            cleanParams.split(';').map { it.toIntOrNull() ?: 0 }
        }
        val p0 = parts.getOrNull(0) ?: 0
        val p1 = parts.getOrNull(1) ?: 0

        when (finalByte) {
            'A' -> {
                val n = if (p0 <= 0) 1 else p0
                cursorRow = (cursorRow - n).coerceAtLeast(0)
            }
            'B' -> {
                val n = if (p0 <= 0) 1 else p0
                cursorRow = (cursorRow + n).coerceAtMost(rows - 1)
            }
            'C' -> {
                val n = if (p0 <= 0) 1 else p0
                cursorCol = (cursorCol + n).coerceAtMost(cols - 1)
            }
            'D' -> {
                val n = if (p0 <= 0) 1 else p0
                cursorCol = (cursorCol - n).coerceAtLeast(0)
            }
            'G' -> {
                val col1 = if (p0 <= 0) 1 else p0
                cursorCol = (col1 - 1).coerceIn(0, cols - 1)
            }
            'd' -> {
                val row1 = if (p0 <= 0) 1 else p0
                cursorRow = (row1 - 1).coerceIn(0, rows - 1)
            }
            'H', 'f' -> {
                val row1 = if (p0 <= 0) 1 else p0
                val col1 = if (p1 <= 0) 1 else p1
                cursorRow = (row1 - 1).coerceIn(0, rows - 1)
                cursorCol = (col1 - 1).coerceIn(0, cols - 1)
            }
            'J' -> {
                when (p0) {
                    2, 3 -> {
                        for (r in 0 until rows) {
                            grid[r].fill(' ')
                        }
                        if (p0 == 3) {
                            scrollback.clear()
                        }
                        cursorRow = 0
                        cursorCol = 0
                    }
                    1 -> {
                        for (r in 0 until cursorRow) {
                            grid[r].fill(' ')
                        }
                        for (c in 0..cursorCol.coerceAtMost(cols - 1)) {
                            grid[cursorRow][c] = ' '
                        }
                    }
                    else -> {
                        for (c in cursorCol.coerceIn(0, cols - 1) until cols) {
                            grid[cursorRow][c] = ' '
                        }
                        for (r in (cursorRow + 1) until rows) {
                            grid[r].fill(' ')
                        }
                    }
                }
            }
            'K' -> {
                when (p0) {
                    2 -> grid[cursorRow].fill(' ')
                    1 -> {
                        for (c in 0..cursorCol.coerceAtMost(cols - 1)) {
                            grid[cursorRow][c] = ' '
                        }
                    }
                    else -> {
                        for (c in cursorCol.coerceIn(0, cols - 1) until cols) {
                            grid[cursorRow][c] = ' '
                        }
                    }
                }
            }
            'P' -> {
                val n = (if (p0 <= 0) 1 else p0).coerceAtMost(cols - cursorCol)
                val rowArr = grid[cursorRow]
                for (c in cursorCol until (cols - n)) {
                    rowArr[c] = rowArr[c + n]
                }
                for (c in (cols - n).coerceAtLeast(cursorCol) until cols) {
                    rowArr[c] = ' '
                }
            }
            'm', 'h', 'l', 'r', 's', 'u', 'n', 'q' -> {
                // SGR and mode toggles: no visible character mutation needed
            }
        }
    }

    private fun lineFeed() {
        if (cursorRow < rows - 1) {
            cursorRow++
        } else {
            val topLine = String(grid[0]).trimEnd()
            if (maxScrollbackLines > 0) {
                if (scrollback.size >= maxScrollbackLines) {
                    scrollback.removeFirst()
                }
                scrollback.addLast(topLine)
            }
            for (r in 0 until rows - 1) {
                grid[r] = grid[r + 1]
            }
            grid[rows - 1] = CharArray(cols) { ' ' }
        }
    }

    @Synchronized
    fun snapshotText(maxTotalLines: Int = 100): String {
        val activeLines = ArrayList<String>(rows)
        var lastNonBlankRow = -1
        for (r in 0 until rows) {
            val line = String(grid[r]).trimEnd()
            activeLines.add(line)
            if (line.isNotEmpty() || r == cursorRow) {
                lastNonBlankRow = r
            }
        }
        val visibleActive = if (lastNonBlankRow >= 0) {
            activeLines.subList(0, lastNonBlankRow + 1)
        } else {
            emptyList()
        }

        val combined = ArrayList<String>(scrollback.size + visibleActive.size)
        combined.addAll(scrollback)
        combined.addAll(visibleActive)

        val startIdx = (combined.size - maxTotalLines).coerceAtLeast(0)
        return combined.subList(startIdx, combined.size).joinToString("\n").trimEnd()
    }

    companion object {
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
        const val DEFAULT_MAX_SCROLLBACK = 120
    }
}

/**
 * Service/Activity-scoped interactive PTY session controller that survives Compose tab
 * switches (`T2-F6-04`, `T3-09`) and automatically closes when the Linux runtime stops.
 */
class TerminalSessionController(
    private val runtimeController: LinuxRuntimeController,
    initialCols: Int = TerminalEmulator.DEFAULT_COLS,
    initialRows: Int = TerminalEmulator.DEFAULT_ROWS
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()
    private val emulator = TerminalEmulator(cols = initialCols, rows = initialRows)

    private val _outputText = MutableStateFlow("")
    val outputText: StateFlow<String> = _outputText.asStateFlow()

    @Volatile
    private var activeSession: PtySession? = null
    private var readerJob: Job? = null

    init {
        scope.launch {
            runtimeController.state.collect { state ->
                if (state is RuntimeState.Ready) {
                    runCatching { ensureSessionOpen() }
                } else if (state is RuntimeState.Idle || state is RuntimeState.Error) {
                    closeCurrentSession()
                }
            }
        }
    }

    suspend fun ensureSessionOpen(): PtySession? = sessionMutex.withLock {
        val existing = activeSession
        if (existing != null && existing.isAlive()) {
            return@withLock existing
        }
        closeCurrentSessionLocked()
        val session = try {
            runtimeController.openInteractiveShell(
                cols = emulator.cols,
                rows = emulator.rows
            )
        } catch (_: Throwable) {
            null
        } ?: return@withLock null

        activeSession = session
        readerJob = scope.launch {
            val buf = ByteArray(4096)
            try {
                while (isActive && session.isAlive()) {
                    val n = try {
                        session.read(buf, 0, buf.size)
                    } catch (_: Throwable) {
                        -1
                    }
                    if (n < 0) break
                    if (n > 0) {
                        val chunk = String(buf, 0, n, Charsets.UTF_8)
                        emulator.append(chunk)
                        _outputText.value = emulator.snapshotText()
                    }
                }
            } finally {
                _outputText.value = emulator.snapshotText()
            }
        }
        // Wait briefly for initial prompt emission
        delay(80L)
        session
    }

    fun sendCommand(command: String) {
        scope.launch {
            val session = ensureSessionOpen() ?: return@launch
            val payload = if (command.endsWith("\n")) command else "$command\n"
            runCatching {
                session.write(payload)
            }
        }
    }

    fun sendRawBytes(bytes: ByteArray) {
        scope.launch {
            val session = ensureSessionOpen() ?: return@launch
            runCatching {
                session.write(bytes)
            }
        }
    }

    fun sendControlChar(letter: Char) {
        val code = letter.uppercaseChar().code
        val ctrlByte = (code and 0x1F).toByte()
        sendRawBytes(byteArrayOf(ctrlByte))
    }

    fun resize(cols: Int, rows: Int) {
        val safeCols = cols.coerceIn(20, 300)
        val safeRows = rows.coerceIn(10, 120)
        emulator.resize(safeCols, safeRows)
        _outputText.value = emulator.snapshotText()
        val session = activeSession
        if (session != null && session.isAlive()) {
            runCatching { session.resize(safeCols, safeRows) }
        }
    }

    fun closeCurrentSession() {
        scope.launch {
            sessionMutex.withLock {
                closeCurrentSessionLocked()
            }
        }
    }

    private fun closeCurrentSessionLocked() {
        readerJob?.cancel()
        readerJob = null
        val old = activeSession
        activeSession = null
        if (old != null) {
            runCatching { old.close() }
        }
    }

    fun destroy() {
        closeCurrentSessionLocked()
        scope.cancel()
    }
}
