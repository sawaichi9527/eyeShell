package io.github.sawaichi9527.eyeshell.ssh

import io.github.sawaichi9527.eyeshell.platform.EyeShellPaths
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

class EyeShellPathsTest {
    @Test
    @EnabledOnOs(OS.LINUX)
    fun `uses XDG config home on Linux`() {
        assertEquals(
            Path.of("/tmp/config/eyeShell/known_hosts"),
            EyeShellPaths.resolveKnownHostsFile("Linux", "/home/user", null, "/tmp/config"),
        )
    }

    @Test
    fun `uses roaming app data on Windows`() {
        assertEquals(
            Path.of("C:/Users/test/AppData/Roaming/eyeShell/known_hosts"),
            EyeShellPaths.resolveKnownHostsFile(
                "Windows 11",
                "C:/Users/test",
                "C:/Users/test/AppData/Roaming",
                null,
            ),
        )
    }

    @Test
    fun `ignores relative XDG config home`() {
        assertEquals(
            Path.of("/home/user/.config/eyeShell/known_hosts"),
            EyeShellPaths.resolveKnownHostsFile("Linux", "/home/user", null, "relative/config"),
        )
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `uses XDG data home for the catalog on Linux`() {
        assertEquals(
            Path.of("/tmp/data/eyeShell/eyeshell.db"),
            EyeShellPaths.resolveCatalogDatabaseFile("Linux", "/home/user", null, "/tmp/data"),
        )
    }

    @Test
    fun `uses local app data for the catalog on Windows`() {
        assertEquals(
            Path.of("C:/Users/test/AppData/Local/eyeShell/eyeshell.db"),
            EyeShellPaths.resolveCatalogDatabaseFile(
                "Windows 11",
                "C:/Users/test",
                "C:/Users/test/AppData/Local",
                null,
            ),
        )
    }

    @Test
    fun `ignores relative XDG data home`() {
        assertEquals(
            Path.of("/home/user/.local/share/eyeShell/eyeshell.db"),
            EyeShellPaths.resolveCatalogDatabaseFile("Linux", "/home/user", null, "relative/data"),
        )
    }
}
