package io.github.sawaichi9527.eyeshell.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AboutComponentsTest {
    @Test
    fun `the generated manifest lists the runtime modules with versions`() {
        val components = AboutComponents.load()

        assertTrue(components.size >= 10, "Expected the runtime modules, got ${components.size}")
        val names = components.map { it.name }
        assertTrue(names.contains("FlatLaf"), names.toString())
        assertTrue(names.contains("Apache MINA SSHD core"), names.toString())
        assertTrue(names.contains("Xerial SQLite JDBC"), names.toString())
        assertTrue(names.contains("JediTerm (eyeShell fork)"), names.toString())

        components.filter { it.scope == OpenSourceComponent.Scope.RUNTIME }.forEach { component ->
            assertFalse(component.version.isBlank(), "Missing version for ${component.name}")
            assertFalse(component.version == "unspecified", "Unresolved version for ${component.name}")
            assertFalse(component.license.isBlank(), "Missing license for ${component.name}")
        }
    }

    @Test
    fun `test-only and bundled assets are marked as such`() {
        val components = AboutComponents.load()

        assertTrue(components.any { it.scope == OpenSourceComponent.Scope.TEST && it.name.contains("JUnit") })
        val assets = components.filter { it.scope == OpenSourceComponent.Scope.ASSET }
        assertEquals(1, assets.size)
        assertTrue(assets.single().name.contains("FinalShell"))
    }

    @Test
    fun `the third-party notices are bundled for the About dialog`() {
        val notices = AboutComponents.thirdPartyNotices()

        assertTrue(notices.contains("Third-Party Notices"))
        assertTrue(notices.contains("MIT"))
    }
}
