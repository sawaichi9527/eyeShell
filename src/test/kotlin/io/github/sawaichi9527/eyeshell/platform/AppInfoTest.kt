package io.github.sawaichi9527.eyeshell.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppInfoTest {
    @Test
    fun `build version is expanded into the resource and shown without a snapshot suffix`() {
        assertFalse(AppInfo.rawVersion.isBlank(), "eyeshell-build.properties was not expanded")
        assertEquals(AppInfo.rawVersion.substringBefore('-'), AppInfo.version)
        assertFalse(AppInfo.version.contains("-"), AppInfo.version)
    }

    @Test
    fun `window title carries the application name and the package version`() {
        assertTrue(
            AppInfo.windowTitle.matches(Regex("""eyeShell v\d+\.\d+\.\d+""")),
            AppInfo.windowTitle,
        )
        assertEquals("eyeShell v${AppInfo.version}", AppInfo.windowTitle)
    }
}
