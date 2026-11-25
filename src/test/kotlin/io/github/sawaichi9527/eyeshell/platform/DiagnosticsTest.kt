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
            events = listOf("2026-11-25T14:07:29.487+08:00 ev=attach session=operator@192.0.2.10:22"),
            settings = listOf("terminal.charset=UTF-8", "terminal.font.size=16"),
        )

        assertTrue(text.contains("reason: terminal-stall-operator@192.0.2.10"), text)
        assertTrue(text.contains("settings:"), "the settings must be part of the dump")
        assertTrue(text.contains("terminal.charset=UTF-8"), text)
        assertTrue(text.contains("session=operator@192.0.2.10 state=stalled"), text)
        assertTrue(text.contains("events:"), "the timeline must be part of the dump")
        assertTrue(text.contains("ev=attach session=operator@192.0.2.10:22"), text)
        assertTrue(text.contains("\"${main.name}\""), text)
        assertTrue(text.contains("java: 21.0.12"), text)
        assertTrue(text.contains("    at "), text)
    }

    @Test
    fun `an empty timeline is named instead of omitted`() {
        val text = Diagnostics.threadDumpText(
            reason = "manual",
            sessions = emptyList(),
            threads = emptyMap(),
            properties = emptyMap(),
            events = emptyList(),
            settings = emptyList(),
        )

        assertTrue(text.contains("events:\n  (none)"), text)
        assertTrue(text.contains("settings:\n  (none)"), text)
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
