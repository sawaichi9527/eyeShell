package io.github.sawaichi9527.eyeshell.platform

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Guards the AppImage desktop entry: GNOME links the window to the application through
 * `StartupWMClass`, and AWT derives that value from the main class name. If the main class or the
 * entry drifts, the dock and app menu silently fall back to a generic gear icon.
 */
class AppImageDesktopEntryTest {
    private val entry: Map<String, String> by lazy {
        File("packaging/appimage/eyeShell.desktop").readLines()
            .map(String::trim)
            .filter { it.contains('=') && !it.startsWith("#") }
            .associate { line -> line.substringBefore('=') to line.substringAfter('=') }
    }

    @Test
    fun `startup class matches the AWT window class`() {
        assertEquals(PackageArtifacts.wmClass, entry["StartupWMClass"])
    }

    @Test
    fun `desktop entry points at the bundled icon and launcher`() {
        assertEquals("Application", entry["Type"])
        assertEquals("eyeshell", entry["Icon"])
        assertEquals("eyeShell", entry["Exec"])
        assertEquals("false", entry["Terminal"])
        assertNotNull(AppIcons.image(512), "the 512 px icon used as the AppImage icon must exist")
    }
}
