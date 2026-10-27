package io.github.sawaichi9527.eyeshell.platform

import java.util.logging.Level
import java.util.logging.LogRecord
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LoggingSetupTest {
    private fun record(message: String, thrown: Throwable? = null) = LogRecord(Level.INFO, message).apply {
        loggerName = "org.apache.sshd.test"
        instant = java.time.Instant.ofEpochMilli(1_790_000_000_000)
        this.thrown = thrown
    }

    @Test
    fun `the console prefix is ASCII regardless of the host locale`() {
        val text = LoggingSetup.PlainFormatter().format(record("connected"))

        assertFalse(text.any { it.code > 0x7F }, text)
        assertTrue(text.contains("INFO org.apache.sshd.test - connected"), text)
        assertTrue(text.contains("2026-"), text)
    }

    @Test
    fun `a localized-looking exception still keeps an ASCII prefix`() {
        val text = LoggingSetup.PlainFormatter().format(
            record("failed", IllegalStateException("executor shut down")),
        )

        val prefix = text.lineSequence().first()
        assertFalse(prefix.any { it.code > 0x7F }, prefix)
        assertTrue(text.contains("java.lang.IllegalStateException: executor shut down"), text)
        assertTrue(text.contains("at io.github.sawaichi9527"), text)
    }

    @Test
    fun `applying the setup installs the formatter on the root handlers`() {
        LoggingSetup.apply()

        val formatters = java.util.logging.Logger.getLogger("").handlers.map { it.formatter }
        assertTrue(formatters.isNotEmpty())
        assertTrue(formatters.all { it is LoggingSetup.PlainFormatter }, formatters.toString())
    }
}
