package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.platform.AppSettings
import io.github.sawaichi9527.eyeshell.platform.WindowPlacement
import io.github.sawaichi9527.eyeshell.platform.resolveWindowPlacement
import io.github.sawaichi9527.eyeshell.platform.screenWorkAreas
import java.awt.Frame
import java.awt.Rectangle
import java.awt.event.WindowEvent
import java.nio.file.Path
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EyeShellWindowPlacementTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a stored placement is restored on the next start`() {
        val screens = screenWorkAreas()
        assumeTrue(screens.isNotEmpty(), "needs a display")
        val primary = screens.first()
        val stored = WindowPlacement(
            x = primary.x + 40,
            y = primary.y + 30,
            width = (primary.width - 200).coerceAtLeast(WindowPlacement.MINIMUM_WIDTH),
            height = (primary.height - 160).coerceAtLeast(WindowPlacement.MINIMUM_HEIGHT),
        )
        val expected = resolveWindowPlacement(stored, screens)
        val settings = AppSettings.load(directory.resolve("settings.properties"))
        settings.windowPlacement = stored

        var window: EyeShellWindow? = null
        try {
            SwingUtilities.invokeAndWait {
                window = EyeShellWindow(terminalViewFactory = { error("no terminal requested") }, appSettings = settings)
            }

            assertEquals(expected.bounds, window!!.bounds)
        } finally {
            window?.let { frame -> SwingUtilities.invokeAndWait { frame.dispose() } }
        }
    }

    @Test
    fun `a maximized window is restored maximized`() {
        val screens = screenWorkAreas()
        assumeTrue(screens.isNotEmpty(), "needs a display")
        val settings = AppSettings.load(directory.resolve("settings.properties"))
        settings.windowPlacement = WindowPlacement(100, 80, 1200, 800, maximized = true)

        var window: EyeShellWindow? = null
        try {
            SwingUtilities.invokeAndWait {
                window = EyeShellWindow(terminalViewFactory = { error("no terminal requested") }, appSettings = settings)
            }

            assertTrue(window!!.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH)
        } finally {
            window?.let { frame -> SwingUtilities.invokeAndWait { frame.dispose() } }
        }
    }

    @Test
    fun `closing the window writes its placement`() {
        val file = directory.resolve("settings.properties")
        val settings = AppSettings.load(file)

        SwingUtilities.invokeAndWait {
            val window = EyeShellWindow(
                terminalViewFactory = { error("no terminal requested") },
                appSettings = settings,
            )
            try {
                window.bounds = Rectangle(120, 85, 1100, 720)
                window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
            } finally {
                window.dispose()
            }
        }

        assertEquals(
            WindowPlacement(120, 85, 1100, 720, maximized = false),
            AppSettings.load(file).windowPlacement,
        )
    }

    @Test
    fun `an off-screen stored placement is recentered on startup`() {
        val screens = screenWorkAreas()
        assumeTrue(screens.isNotEmpty(), "needs a display")
        val primary = screens.first()
        val settings = AppSettings.load(directory.resolve("settings.properties"))
        settings.windowPlacement = WindowPlacement(primary.x + primary.width + 5000, 5000, 1100, 720)

        var window: EyeShellWindow? = null
        try {
            SwingUtilities.invokeAndWait {
                window = EyeShellWindow(terminalViewFactory = { error("no terminal requested") }, appSettings = settings)
            }

            val bounds = window!!.bounds
            assertTrue(bounds.intersects(primary), "restored window must be reachable: $bounds")
            assertEquals(1100, bounds.width)
            assertEquals(720, bounds.height)
        } finally {
            window?.let { frame -> SwingUtilities.invokeAndWait { frame.dispose() } }
        }
    }
}
