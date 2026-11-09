package io.github.sawaichi9527.eyeshell.platform

import java.awt.Dimension
import java.awt.Rectangle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WindowPlacementTest {
    private val primary = Rectangle(0, 0, 1920, 1040)
    private val leftMonitor = Rectangle(-1920, 0, 1920, 1040)

    @Test
    fun `no stored placement centers the default size on the primary screen`() {
        val resolved = resolveWindowPlacement(null, listOf(primary))

        assertEquals(WindowPlacement(320, 120, 1280, 800, false), resolved)
    }

    @Test
    fun `a visible placement is restored unchanged`() {
        val stored = WindowPlacement(100, 50, 1000, 700)

        assertEquals(stored, resolveWindowPlacement(stored, listOf(primary)))
    }

    @Test
    fun `a placement on a monitor left of the primary screen is kept`() {
        val stored = WindowPlacement(-1800, 100, 1000, 700)

        assertEquals(stored, resolveWindowPlacement(stored, listOf(primary, leftMonitor)))
    }

    @Test
    fun `an off-screen placement is centered again`() {
        val stored = WindowPlacement(5000, 5000, 1000, 700)

        assertEquals(
            WindowPlacement(460, 170, 1000, 700, false),
            resolveWindowPlacement(stored, listOf(primary)),
        )
    }

    @Test
    fun `a stored size below the minimum is enlarged`() {
        val stored = WindowPlacement(100, 50, 400, 300)

        assertEquals(
            WindowPlacement(100, 50, WindowPlacement.MINIMUM_WIDTH, WindowPlacement.MINIMUM_HEIGHT),
            resolveWindowPlacement(stored, listOf(primary)),
        )
    }

    @Test
    fun `a stored size larger than the screen is clamped`() {
        val stored = WindowPlacement(0, 0, 3000, 2000)

        assertEquals(
            WindowPlacement(0, 0, primary.width, primary.height),
            resolveWindowPlacement(stored, listOf(primary)),
        )
    }

    @Test
    fun `a window dragged above the screen keeps its title bar reachable`() {
        val stored = WindowPlacement(100, -50, 1000, 700)

        assertEquals(
            WindowPlacement(100, 0, 1000, 700),
            resolveWindowPlacement(stored, listOf(primary)),
        )
    }

    @Test
    fun `a mostly hidden placement is centered instead of trusted`() {
        val stored = WindowPlacement(1890, 300, 1000, 700)

        assertEquals(
            WindowPlacement(460, 170, 1000, 700),
            resolveWindowPlacement(stored, listOf(primary)),
        )
    }

    @Test
    fun `the maximized state survives the restore`() {
        val stored = WindowPlacement(100, 50, 1000, 700, maximized = true)

        val resolved = resolveWindowPlacement(stored, listOf(primary))

        assertTrue(resolved.maximized)
        assertEquals(stored.bounds, resolved.bounds)
    }

    @Test
    fun `a missing display falls back to the stored size at the minimum`() {
        val resolved = resolveWindowPlacement(
            stored = null,
            screens = emptyList(),
            minimumSize = Dimension(960, 640),
            defaultSize = Dimension(400, 300),
        )

        assertEquals(960, resolved.width)
        assertEquals(640, resolved.height)
        assertFalse(resolved.maximized)
    }
}
