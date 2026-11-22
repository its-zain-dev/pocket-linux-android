package com.pocketlinux.rfb

import android.view.KeyEvent

/**
 * Pure-JVM X11 KeySym mapper for ASCII, Latin-1, Unicode code points, virtual toolbar keys,
 * and Android KEYCODE_* integer constants (compile-time inlined, no KeyEvent runtime calls).
 */
object KeySymMapper {

    const val XK_BACKSPACE = 0xFF08
    const val XK_TAB = 0xFF09
    const val XK_RETURN = 0xFF0D
    const val XK_PAUSE = 0xFF13
    const val XK_SCROLL_LOCK = 0xFF14
    const val XK_ESCAPE = 0xFF1B
    const val XK_HOME = 0xFF50
    const val XK_LEFT = 0xFF51
    const val XK_UP = 0xFF52
    const val XK_RIGHT = 0xFF53
    const val XK_DOWN = 0xFF54
    const val XK_PAGE_UP = 0xFF55
    const val XK_PAGE_DOWN = 0xFF56
    const val XK_END = 0xFF57
    const val XK_PRINT = 0xFF61
    const val XK_INSERT = 0xFF63
    const val XK_MENU = 0xFF67
    const val XK_NUM_LOCK = 0xFF7F
    const val XK_F1 = 0xFFBE
    const val XK_F2 = 0xFFBF
    const val XK_F3 = 0xFFC0
    const val XK_F4 = 0xFFC1
    const val XK_F5 = 0xFFC2
    const val XK_F6 = 0xFFC3
    const val XK_F7 = 0xFFC4
    const val XK_F8 = 0xFFC5
    const val XK_F9 = 0xFFC6
    const val XK_F10 = 0xFFC7
    const val XK_F11 = 0xFFC8
    const val XK_F12 = 0xFFC9
    const val XK_SHIFT_L = 0xFFE1
    const val XK_SHIFT_R = 0xFFE2
    const val XK_CONTROL_L = 0xFFE3
    const val XK_CONTROL_R = 0xFFE4
    const val XK_CAPS_LOCK = 0xFFE5
    const val XK_ALT_L = 0xFFE9
    const val XK_ALT_R = 0xFFEA
    const val XK_SUPER_L = 0xFFEB
    const val XK_SUPER_R = 0xFFEC
    const val XK_DELETE = 0xFFFF
    const val XK_SPACE = 0x0020

    enum class VirtualKey(val keySym: Int) {
        CTRL(XK_CONTROL_L),
        CTRL_LEFT(XK_CONTROL_L),
        CTRL_RIGHT(XK_CONTROL_R),
        ALT(XK_ALT_L),
        ALT_LEFT(XK_ALT_L),
        ALT_RIGHT(XK_ALT_R),
        ESC(XK_ESCAPE),
        ESCAPE(XK_ESCAPE),
        TAB(XK_TAB),
        SUPER(XK_SUPER_L),
        SUPER_LEFT(XK_SUPER_L),
        SUPER_RIGHT(XK_SUPER_R),
        SHIFT(XK_SHIFT_L),
        SHIFT_LEFT(XK_SHIFT_L),
        SHIFT_RIGHT(XK_SHIFT_R),
        UP(XK_UP),
        DOWN(XK_DOWN),
        LEFT(XK_LEFT),
        RIGHT(XK_RIGHT),
        F1(XK_F1),
        F2(XK_F2),
        F3(XK_F3),
        F4(XK_F4),
        F5(XK_F5),
        F6(XK_F6),
        F7(XK_F7),
        F8(XK_F8),
        F9(XK_F9),
        F10(XK_F10),
        F11(XK_F11),
        F12(XK_F12),
        BACKSPACE(XK_BACKSPACE),
        ENTER(XK_RETURN),
        DELETE(XK_DELETE),
        INSERT(XK_INSERT),
        HOME(XK_HOME),
        END(XK_END),
        PAGE_UP(XK_PAGE_UP),
        PAGE_DOWN(XK_PAGE_DOWN),
        SPACE(XK_SPACE),
        CAPS_LOCK(XK_CAPS_LOCK),
        NUM_LOCK(XK_NUM_LOCK),
        SCROLL_LOCK(XK_SCROLL_LOCK),
        PRINT_SCREEN(XK_PRINT),
        PAUSE(XK_PAUSE),
        MENU(XK_MENU)
    }

    private val NAMED_KEYS = mapOf(
        "ctrl" to XK_CONTROL_L,
        "ctrl_l" to XK_CONTROL_L,
        "ctrl_left" to XK_CONTROL_L,
        "ctrl_r" to XK_CONTROL_R,
        "ctrl_right" to XK_CONTROL_R,
        "control" to XK_CONTROL_L,
        "alt" to XK_ALT_L,
        "alt_l" to XK_ALT_L,
        "alt_left" to XK_ALT_L,
        "alt_r" to XK_ALT_R,
        "alt_right" to XK_ALT_R,
        "esc" to XK_ESCAPE,
        "escape" to XK_ESCAPE,
        "tab" to XK_TAB,
        "super" to XK_SUPER_L,
        "meta" to XK_SUPER_L,
        "win" to XK_SUPER_L,
        "super_l" to XK_SUPER_L,
        "super_left" to XK_SUPER_L,
        "super_r" to XK_SUPER_R,
        "super_right" to XK_SUPER_R,
        "shift" to XK_SHIFT_L,
        "shift_l" to XK_SHIFT_L,
        "shift_left" to XK_SHIFT_L,
        "shift_r" to XK_SHIFT_R,
        "shift_right" to XK_SHIFT_R,
        "up" to XK_UP,
        "down" to XK_DOWN,
        "left" to XK_LEFT,
        "right" to XK_RIGHT,
        "f1" to XK_F1,
        "f2" to XK_F2,
        "f3" to XK_F3,
        "f4" to XK_F4,
        "f5" to XK_F5,
        "f6" to XK_F6,
        "f7" to XK_F7,
        "f8" to XK_F8,
        "f9" to XK_F9,
        "f10" to XK_F10,
        "f11" to XK_F11,
        "f12" to XK_F12,
        "backspace" to XK_BACKSPACE,
        "bksp" to XK_BACKSPACE,
        "enter" to XK_RETURN,
        "return" to XK_RETURN,
        "del" to XK_DELETE,
        "delete" to XK_DELETE,
        "ins" to XK_INSERT,
        "insert" to XK_INSERT,
        "home" to XK_HOME,
        "end" to XK_END,
        "pgup" to XK_PAGE_UP,
        "pageup" to XK_PAGE_UP,
        "page_up" to XK_PAGE_UP,
        "pgdn" to XK_PAGE_DOWN,
        "pagedown" to XK_PAGE_DOWN,
        "page_down" to XK_PAGE_DOWN,
        "space" to XK_SPACE,
        "capslock" to XK_CAPS_LOCK,
        "caps_lock" to XK_CAPS_LOCK,
        "numlock" to XK_NUM_LOCK,
        "num_lock" to XK_NUM_LOCK,
        "scrolllock" to XK_SCROLL_LOCK,
        "scroll_lock" to XK_SCROLL_LOCK,
        "print" to XK_PRINT,
        "printscreen" to XK_PRINT,
        "print_screen" to XK_PRINT,
        "pause" to XK_PAUSE,
        "menu" to XK_MENU
    )

    private val SHIFTED_DIGITS = intArrayOf(
        ')'.code, '!'.code, '@'.code, '#'.code, '$'.code,
        '%'.code, '^'.code, '&'.code, '*'.code, '('.code
    )

    fun virtualKeyToKeySym(key: VirtualKey): Int = key.keySym

    fun namedKeyToKeySym(name: String): Int? {
        val normalized = name.trim().lowercase().replace('-', '_')
        return NAMED_KEYS[normalized]
    }

    fun unicodeToKeySym(codePoint: Int): Int? {
        return when (codePoint) {
            0x08 -> XK_BACKSPACE
            0x09 -> XK_TAB
            0x0A, 0x0D -> XK_RETURN
            0x1B -> XK_ESCAPE
            0x7F -> XK_DELETE
            in 0x0020..0x007E -> codePoint
            in 0x00A0..0x00FF -> codePoint
            in 0xD800..0xDFFF -> null
            in 0x0100..0x10FFFF -> 0x01000000 or codePoint
            else -> null
        }
    }

    fun textToKeySymSequence(text: String): List<Int> {
        if (text.isEmpty()) return emptyList()
        val result = ArrayList<Int>(text.length)
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            val charCount = Character.charCount(cp)
            val keySym = unicodeToKeySym(cp)
            if (keySym != null) {
                result.add(keySym)
            }
            i += charCount
        }
        return result
    }

    fun mapAndroidKeyCode(
        keyCode: Int,
        unicodeChar: Int = 0,
        isShiftPressed: Boolean = false
    ): Int? {
        val special = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> XK_RETURN
            KeyEvent.KEYCODE_TAB -> XK_TAB
            KeyEvent.KEYCODE_ESCAPE -> XK_ESCAPE
            KeyEvent.KEYCODE_DEL -> XK_BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> XK_DELETE
            KeyEvent.KEYCODE_INSERT -> XK_INSERT
            KeyEvent.KEYCODE_MOVE_HOME -> XK_HOME
            KeyEvent.KEYCODE_MOVE_END -> XK_END
            KeyEvent.KEYCODE_PAGE_UP -> XK_PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> XK_PAGE_DOWN
            KeyEvent.KEYCODE_DPAD_UP -> XK_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> XK_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> XK_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> XK_RIGHT
            KeyEvent.KEYCODE_SHIFT_LEFT -> XK_SHIFT_L
            KeyEvent.KEYCODE_SHIFT_RIGHT -> XK_SHIFT_R
            KeyEvent.KEYCODE_CTRL_LEFT -> XK_CONTROL_L
            KeyEvent.KEYCODE_CTRL_RIGHT -> XK_CONTROL_R
            KeyEvent.KEYCODE_ALT_LEFT -> XK_ALT_L
            KeyEvent.KEYCODE_ALT_RIGHT -> XK_ALT_R
            KeyEvent.KEYCODE_META_LEFT -> XK_SUPER_L
            KeyEvent.KEYCODE_META_RIGHT -> XK_SUPER_R
            KeyEvent.KEYCODE_CAPS_LOCK -> XK_CAPS_LOCK
            KeyEvent.KEYCODE_NUM_LOCK -> XK_NUM_LOCK
            KeyEvent.KEYCODE_SCROLL_LOCK -> XK_SCROLL_LOCK
            KeyEvent.KEYCODE_SYSRQ -> XK_PRINT
            KeyEvent.KEYCODE_BREAK -> XK_PAUSE
            KeyEvent.KEYCODE_MENU -> XK_MENU
            KeyEvent.KEYCODE_F1 -> XK_F1
            KeyEvent.KEYCODE_F2 -> XK_F2
            KeyEvent.KEYCODE_F3 -> XK_F3
            KeyEvent.KEYCODE_F4 -> XK_F4
            KeyEvent.KEYCODE_F5 -> XK_F5
            KeyEvent.KEYCODE_F6 -> XK_F6
            KeyEvent.KEYCODE_F7 -> XK_F7
            KeyEvent.KEYCODE_F8 -> XK_F8
            KeyEvent.KEYCODE_F9 -> XK_F9
            KeyEvent.KEYCODE_F10 -> XK_F10
            KeyEvent.KEYCODE_F11 -> XK_F11
            KeyEvent.KEYCODE_F12 -> XK_F12
            else -> null
        }
        if (special != null) return special

        val cleanUnicode = unicodeChar and 0x7FFF_FFFF
        if (cleanUnicode >= 0x20 && cleanUnicode !in 0x7F..0x9F) {
            unicodeToKeySym(cleanUnicode)?.let { return it }
        }

        // Fallback when Ctrl/Alt clears unicodeChar or synthetic keycodes are sent without unicodeChar.
        return when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> XK_SPACE
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                val base = if (isShiftPressed) 'A'.code else 'a'.code
                base + (keyCode - KeyEvent.KEYCODE_A)
            }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                val idx = keyCode - KeyEvent.KEYCODE_0
                if (isShiftPressed) SHIFTED_DIGITS[idx] else '0'.code + idx
            }
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                '0'.code + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
            }
            KeyEvent.KEYCODE_MINUS -> if (isShiftPressed) '_'.code else '-'.code
            KeyEvent.KEYCODE_EQUALS -> if (isShiftPressed) '+'.code else '='.code
            KeyEvent.KEYCODE_LEFT_BRACKET -> if (isShiftPressed) '{'.code else '['.code
            KeyEvent.KEYCODE_RIGHT_BRACKET -> if (isShiftPressed) '}'.code else ']'.code
            KeyEvent.KEYCODE_BACKSLASH -> if (isShiftPressed) '|'.code else '\\'.code
            KeyEvent.KEYCODE_SEMICOLON -> if (isShiftPressed) ':'.code else ';'.code
            KeyEvent.KEYCODE_APOSTROPHE -> if (isShiftPressed) '"'.code else '\''.code
            KeyEvent.KEYCODE_GRAVE -> if (isShiftPressed) '~'.code else '`'.code
            KeyEvent.KEYCODE_COMMA -> if (isShiftPressed) '<'.code else ','.code
            KeyEvent.KEYCODE_PERIOD -> if (isShiftPressed) '>'.code else '.'.code
            KeyEvent.KEYCODE_SLASH -> if (isShiftPressed) '?'.code else '/'.code
            KeyEvent.KEYCODE_AT -> '@'.code
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> '+'.code
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> '-'.code
            KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> '*'.code
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> '/'.code
            KeyEvent.KEYCODE_NUMPAD_DOT -> '.'.code
            KeyEvent.KEYCODE_NUMPAD_COMMA -> ','.code
            KeyEvent.KEYCODE_NUMPAD_EQUALS -> '='.code
            KeyEvent.KEYCODE_POUND -> '#'.code
            else -> null
        }
    }
}
