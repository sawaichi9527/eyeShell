package io.github.sawaichi9527.eyeshell.platform

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DiagnosticsTest {
    @Test
    fun `the dump names the reason, the sessions, and every thread`() {
        val main = Thread.currentThread()
        val text = Diagnostics.threadDumpText(
            reason = "terminal-stall-operator@192.0.2.10",
            sessions = listOf("session=operator@192.0.2.10 state=stalled"),
            threads = mapOf(main to main.stackTrace),
            properties = mapOf("eyeShell" to "0.1.9", "java" to "21.0.12"),
        )

        assertTrue(text.contains("reason: terminal-stall-operator@192.0.2.10"), text)
        assertTrue(text.contains("session=operator@192.0.2.10 state=stalled"), text)
        assertTrue(text.contains("\"${main.name}\""), text)
        assertTrue(text.contains("java: 21.0.12"), text)
        assertTrue(text.contains("    at "), text)
    }

    @Test
    fun `writing a dump creates one timestamped file`(@TempDir directory: Path) {
        val file = checkNotNull(Diagnostics.write(directory, "manual", emptyList()))

        assertTrue(Files.exists(file), "the dump file must exist")
        assertTrue(file.fileName.toString().startsWith("thread-dump-manual-"), file.toString())
        assertTrue(Files.readString(file).contains("open sessions:"), "sessions are always listed")
    }

    @Test
    fun `a session name becomes a safe file name`() {
        assertEquals("operator_192.0.2.10_22", Diagnostics.sanitize("operator@192.0.2.10:22"))
        assertEquals("snapshot", Diagnostics.sanitize(""))
        assertFalse(Diagnostics.sanitize("a".repeat(100)).length > 48)
    }
}
