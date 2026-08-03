package io.github.sawaichi9527.eyeshell.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SafeModeTest {
    @Test
    fun `is active only when the safe mode argument is present`() {
        assertFalse(SafeMode.detect(emptyArray(), 100_000, 50).isActive)
        assertFalse(SafeMode.detect(arrayOf("--other"), 100_000, 50).isActive)
        assertTrue(SafeMode.detect(arrayOf("--safe-mode"), 100_000, 50).isActive)
        assertTrue(SafeMode.detect(arrayOf("--safe-mode", "--other"), 100_000, 50).isActive)
    }

    @Test
    fun `uses safe overrides when active and defaults otherwise`() {
        val active = SafeMode.detect(arrayOf("--safe-mode"), 100_000, 50)
        assertEquals(SafeMode.SAFE_SCROLLBACK_LINES, active.scrollbackLines)
        assertEquals(SafeMode.SAFE_MAX_REFRESH_RATE, active.maxRefreshRate)

        val inactive = SafeMode.detect(emptyArray(), 100_000, 50)
        assertEquals(100_000, inactive.scrollbackLines)
        assertEquals(50, inactive.maxRefreshRate)
    }

    @Test
    fun `windows safe mode disables d3d and opengl`() {
        val safeMode = SafeMode.detect(arrayOf("--safe-mode"), 100_000, 50)
        assertEquals(
            mapOf(
                "sun.java2d.d3d" to "false",
                "sun.java2d.opengl" to "false",
            ),
            safeMode.systemProperties("Windows 11"),
        )
    }

    @Test
    fun `linux safe mode applies no rendering properties`() {
        val safeMode = SafeMode.detect(arrayOf("--safe-mode"), 100_000, 50)
        assertTrue(safeMode.systemProperties("Linux").isEmpty())
    }

    @Test
    fun `inactive safe mode applies no system properties`() {
        val inactive = SafeMode.detect(emptyArray(), 100_000, 50)
        assertTrue(inactive.systemProperties("Windows 11").isEmpty())
        assertTrue(inactive.systemProperties("Linux").isEmpty())
    }
}
