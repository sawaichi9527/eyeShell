package io.github.sawaichi9527.eyeshell.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppInfoTest {
    @Test
    fun `build version is expanded into the resource`() {
        assertFalse(AppInfo.rawVersion.isBlank(), "eyeshell-build.properties was not expanded")
        assertEquals(AppInfo.rawVersion, AppInfo.version)
    }

    @Test
    fun `a development build announces its suffix in the window title`() {
        // The release is cut from a bare version, so a suffix can only mean a development build.
        assertEquals("eyeShell v${AppInfo.rawVersion}", AppInfo.windowTitle)
        assertTrue(AppInfo.windowTitle.startsWith("eyeShell v"), AppInfo.windowTitle)
    }

    @Test
    fun `package metadata still gets the numeric version`() {
        assertEquals(AppInfo.rawVersion.substringBefore('-'), PackageArtifacts.version(AppInfo.rawVersion))
        assertFalse(PackageArtifacts.version(AppInfo.rawVersion).contains("-"))
    }
}
