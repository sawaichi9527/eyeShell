package io.github.sawaichi9527.eyeshell.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PackageArtifactsTest {
    @Test
    fun `strips snapshot suffix for the package version`() {
        assertEquals("0.1.0", PackageArtifacts.version("0.1.0-SNAPSHOT"))
        assertEquals("0.1.0", PackageArtifacts.version("0.1.0"))
        assertEquals("1.2.3", PackageArtifacts.version("1.2.3"))
    }

    @Test
    fun `portable archive uses the platform extension`() {
        assertEquals("eyeShell-0.1.0.tar.gz", PackageArtifacts.portableFileName("0.1.0", windows = false))
        assertEquals("eyeShell-0.1.0.zip", PackageArtifacts.portableFileName("0.1.0", windows = true))
    }

    @Test
    fun `installer archive uses the platform naming`() {
        assertEquals("eyeshell_0.1.0_amd64.deb", PackageArtifacts.installerFileName("0.1.0", windows = false))
        assertEquals("eyeShell-0.1.0.msi", PackageArtifacts.installerFileName("0.1.0", windows = true))
    }
}
