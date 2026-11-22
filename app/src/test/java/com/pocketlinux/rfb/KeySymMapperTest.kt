package com.pocketlinux.rfb

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeySymMapperTest {

    @Test
    fun asciiLatin1AndUnicodeIncludingSurrogatePairsMapToX11KeySyms() {
        assertEquals('a'.code, KeySymMapper.unicodeToKeySym('a'.code))
        assertEquals('Z'.code, KeySymMapper.unicodeToKeySym('Z'.code))
        assertEquals(' '.code, KeySymMapper.unicodeToKeySym(' '.code))
        assertEquals('~'.code, KeySymMapper.unicodeToKeySym('~'.code))

        // Latin-1
        assertEquals(0x00E9, KeySymMapper.unicodeToKeySym('é'.code))
        assertEquals(0x00FC, KeySymMapper.unicodeToKeySym('ü'.code))

        // BMP Unicode (> 0x00FF)
        assertEquals(0x010020AC, KeySymMapper.unicodeToKeySym('€'.code))

        // Surrogate pair (U+1F600 😀)
        val seq = KeySymMapper.textToKeySymSequence("Hi😀!")
        assertEquals(
            listOf('H'.code, 'i'.code, 0x0101F600, '!'.code),
            seq
        )

        // Control characters
        assertEquals(KeySymMapper.XK_BACKSPACE, KeySymMapper.unicodeToKeySym(0x08))
        assertEquals(KeySymMapper.XK_TAB, KeySymMapper.unicodeToKeySym(0x09))
        assertEquals(KeySymMapper.XK_RETURN, KeySymMapper.unicodeToKeySym(0x0A))
        assertEquals(KeySymMapper.XK_RETURN, KeySymMapper.unicodeToKeySym(0x0D))
        assertEquals(KeySymMapper.XK_ESCAPE, KeySymMapper.unicodeToKeySym(0x1B))
        assertEquals(KeySymMapper.XK_DELETE, KeySymMapper.unicodeToKeySym(0x7F))
        assertNull(KeySymMapper.unicodeToKeySym(0xD800))
    }

    @Test
    fun virtualToolbarAndNamedKeysMapToExpectedX11KeySyms() {
        assertEquals(0xFFE3, KeySymMapper.virtualKeyToKeySym(KeySymMapper.VirtualKey.CTRL))
        assertEquals(0xFFE9, KeySymMapper.virtualKeyToKeySym(KeySymMapper.VirtualKey.ALT))
        assertEquals(0xFF1B, KeySymMapper.virtualKeyToKeySym(KeySymMapper.VirtualKey.ESC))
        assertEquals(0xFF09, KeySymMapper.virtualKeyToKeySym(KeySymMapper.VirtualKey.TAB))
        assertEquals(0xFFEB, KeySymMapper.virtualKeyToKeySym(KeySymMapper.VirtualKey.SUPER))
        assertEquals(0xFF52, KeySymMapper.virtualKeyToKeySym(KeySymMapper.VirtualKey.UP))
        assertEquals(0xFF54, KeySymMapper.virtualKeyToKeySym(KeySymMapper.VirtualKey.DOWN))
        assertEquals(0xFF51, KeySymMapper.virtualKeyToKeySym(KeySymMapper.VirtualKey.LEFT))
        assertEquals(0xFF53, KeySymMapper.virtualKeyToKeySym(KeySymMapper.VirtualKey.RIGHT))
        assertEquals(0xFF08, KeySymMapper.virtualKeyToKeySym(KeySymMapper.VirtualKey.BACKSPACE))
        assertEquals(0xFF0D, KeySymMapper.virtualKeyToKeySym(KeySymMapper.VirtualKey.ENTER))
        assertEquals(0xFFFF, KeySymMapper.virtualKeyToKeySym(KeySymMapper.VirtualKey.DELETE))
        assertEquals(0xFF63, KeySymMapper.virtualKeyToKeySym(KeySymMapper.VirtualKey.INSERT))
        assertEquals(0xFF50, KeySymMapper.virtualKeyToKeySym(KeySymMapper.VirtualKey.HOME))
        assertEquals(0xFF57, KeySymMapper.virtualKeyToKeySym(KeySymMapper.VirtualKey.END))
        assertEquals(0xFF55, KeySymMapper.virtualKeyToKeySym(KeySymMapper.VirtualKey.PAGE_UP))
        assertEquals(0xFF56, KeySymMapper.virtualKeyToKeySym(KeySymMapper.VirtualKey.PAGE_DOWN))
        assertEquals(0xFFE1, KeySymMapper.virtualKeyToKeySym(KeySymMapper.VirtualKey.SHIFT))
        assertEquals(0x0020, KeySymMapper.virtualKeyToKeySym(KeySymMapper.VirtualKey.SPACE))

        val fKeys = listOf(
            KeySymMapper.VirtualKey.F1, KeySymMapper.VirtualKey.F2, KeySymMapper.VirtualKey.F3,
            KeySymMapper.VirtualKey.F4, KeySymMapper.VirtualKey.F5, KeySymMapper.VirtualKey.F6,
            KeySymMapper.VirtualKey.F7, KeySymMapper.VirtualKey.F8, KeySymMapper.VirtualKey.F9,
            KeySymMapper.VirtualKey.F10, KeySymMapper.VirtualKey.F11, KeySymMapper.VirtualKey.F12
        )
        fKeys.forEachIndexed { idx, vk ->
            assertEquals(0xFFBE + idx, KeySymMapper.virtualKeyToKeySym(vk))
            assertEquals(0xFFBE + idx, KeySymMapper.namedKeyToKeySym("f${idx + 1}"))
        }

        assertEquals(0xFFE3, KeySymMapper.namedKeyToKeySym("Ctrl"))
        assertEquals(0xFFEB, KeySymMapper.namedKeyToKeySym("Super"))
        assertEquals(0xFF55, KeySymMapper.namedKeyToKeySym("PageUp"))
        assertEquals(0xFF56, KeySymMapper.namedKeyToKeySym("PageDown"))
    }

    @Test
    fun androidKeyCodesAndCtrlFallbackMapWithoutInstantiatingKeyEvent() {
        assertEquals(KeySymMapper.XK_RETURN, KeySymMapper.mapAndroidKeyCode(KeyEvent.KEYCODE_ENTER))
        assertEquals(KeySymMapper.XK_BACKSPACE, KeySymMapper.mapAndroidKeyCode(KeyEvent.KEYCODE_DEL))
        assertEquals(KeySymMapper.XK_DELETE, KeySymMapper.mapAndroidKeyCode(KeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals(KeySymMapper.XK_ESCAPE, KeySymMapper.mapAndroidKeyCode(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(KeySymMapper.XK_UP, KeySymMapper.mapAndroidKeyCode(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(KeySymMapper.XK_F10, KeySymMapper.mapAndroidKeyCode(KeyEvent.KEYCODE_F10))

        // Ctrl+C yields unicodeChar == 0 or 0x03 on Android -> falls back to 'c'
        assertEquals('c'.code, KeySymMapper.mapAndroidKeyCode(KeyEvent.KEYCODE_C, unicodeChar = 0))
        assertEquals('c'.code, KeySymMapper.mapAndroidKeyCode(KeyEvent.KEYCODE_C, unicodeChar = 0x03))
        assertEquals('C'.code, KeySymMapper.mapAndroidKeyCode(KeyEvent.KEYCODE_C, unicodeChar = 0, isShiftPressed = true))

        // Printable unicodeChar overrides keycode fallback
        assertEquals('@'.code, KeySymMapper.mapAndroidKeyCode(KeyEvent.KEYCODE_2, unicodeChar = '@'.code, isShiftPressed = true))
        assertEquals(')'.code, KeySymMapper.mapAndroidKeyCode(KeyEvent.KEYCODE_0, unicodeChar = 0, isShiftPressed = true))
    }
}
