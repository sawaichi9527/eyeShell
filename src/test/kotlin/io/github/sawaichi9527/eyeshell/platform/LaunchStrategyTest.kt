package io.github.sawaichi9527.eyeshell.platform

import io.github.sawaichi9527.eyeshell.platform.DesktopSessionType.WAYLAND
import io.github.sawaichi9527.eyeshell.platform.DesktopSessionType.X11
import io.github.sawaichi9527.eyeshell.platform.DesktopSessionType.UNKNOWN
import io.github.sawaichi9527.eyeshell.platform.LaunchStrategy.ToolkitChoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LaunchStrategyTest {
    @Test
    fun `detects the desktop session from environment variables`() {
        assertEquals(WAYLAND, DesktopSession.detect("wayland", null, null))
        assertEquals(WAYLAND, DesktopSession.detect("Wayland", "wayland-0", null))
        assertEquals(WAYLAND, DesktopSession.detect(null, "wayland-0", ":0"))
        assertEquals(X11, DesktopSession.detect("x11", null, ":0"))
        assertEquals(X11, DesktopSession.detect(null, null, ":0"))
        assertEquals(UNKNOWN, DesktopSession.detect(null, null, null))
        assertEquals(UNKNOWN, DesktopSession.detect("tty", null, null))
    }

    @Test
    fun `windows uses the platform default toolkit`() {
        val strategy = LaunchStrategy.resolve("Windows 11", X11, forceX11 = false, waylandToolkitSupported = false)
        assertEquals(ToolkitChoice.PLATFORM_DEFAULT, strategy.toolkit)
        assertTrue(strategy.systemProperties().isEmpty())
    }

    @Test
    fun `linux x11 session selects the X toolkit`() {
        val strategy = LaunchStrategy.resolve("Linux", X11, forceX11 = false, waylandToolkitSupported = true)
        assertEquals(ToolkitChoice.X11, strategy.toolkit)
        assertEquals(mapOf("awt.toolkit" to "sun.awt.X11.XToolkit"), strategy.systemProperties())
    }

    @Test
    fun `linux wayland session selects native Wayland when supported`() {
        val strategy = LaunchStrategy.resolve("Linux", WAYLAND, forceX11 = false, waylandToolkitSupported = true)
        assertEquals(ToolkitChoice.WAYLAND, strategy.toolkit)
        assertEquals(mapOf("awt.toolkit" to "sun.awt.WLToolkit"), strategy.systemProperties())
    }

    @Test
    fun `linux wayland session falls back to X11 when the Wayland toolkit is unavailable`() {
        val strategy = LaunchStrategy.resolve("Linux", WAYLAND, forceX11 = false, waylandToolkitSupported = false)
        assertEquals(ToolkitChoice.X11, strategy.toolkit)
    }

    @Test
    fun `safe mode forces the X toolkit on Linux regardless of session`() {
        val wayland = LaunchStrategy.resolve("Linux", WAYLAND, forceX11 = true, waylandToolkitSupported = true)
        assertEquals(ToolkitChoice.X11, wayland.toolkit)
        val unknown = LaunchStrategy.resolve("Linux", UNKNOWN, forceX11 = true, waylandToolkitSupported = true)
        assertEquals(ToolkitChoice.X11, unknown.toolkit)
    }
}
