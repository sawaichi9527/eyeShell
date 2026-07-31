package io.github.sawaichi9527.eyeshell.ssh

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EyeShellPathsTest {
    @Test
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
}
