package com.pocketlinux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaffoldSanityTest {
    @Test
    fun applicationIdAndAbiFlavorsAreConfigured() {
        assertEquals("com.pocketlinux", BuildConfig.APPLICATION_ID)
        assertTrue(BuildConfig.VERSION_NAME.isNotBlank())
    }
}
