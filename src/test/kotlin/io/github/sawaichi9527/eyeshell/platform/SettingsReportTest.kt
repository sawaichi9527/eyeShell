package io.github.sawaichi9527.eyeshell.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A report reader must be able to separate "the app is broken" from "the app is configured that way",
 * without the report leaking a personal folder path.
 */
class SettingsReportTest {
    @Test
    fun `the report names the settings that change terminal behaviour`() {
        val lines = SettingsReport.lines(AppSettings.inMemory())

        assertTrue(lines.any { it.startsWith("app.locale=") }, lines.toString())
        assertTrue(lines.any { it.startsWith("terminal.charset=") }, lines.toString())
        assertTrue(lines.any { it.startsWith("terminal.colorScheme=") }, lines.toString())
        assertTrue(lines.any { it.startsWith("terminal.font.size=") }, lines.toString())
        assertTrue(lines.any { it.startsWith("terminal.font.family=") }, lines.toString())
        assertTrue(lines.any { it.startsWith("terminal.font.cjkFamily=") }, lines.toString())
        assertTrue(lines.any { it.startsWith("terminal.background=") }, lines.toString())
        assertTrue(lines.none { it.contains(' ') }, "values must stay one word: $lines")
        assertEquals(lines.size, lines.distinct().size, "keys must be unique: $lines")
    }

    @Test
    fun `a background image is reduced to its file name`() {
        val settings = AppSettings.inMemory().apply {
            terminalBackgroundEnabled = true
            terminalBackgroundImage = "C:\\Users\\someone\\Pictures\\my photo.jpg"
            terminalBackgroundOpacity = 80
        }

        val line = SettingsReport.lines(settings).single { it.startsWith("terminal.background=") }

        assertEquals("terminal.background=my_photo.jpg@80%", line)
        assertFalse(line.contains("Users"), line)
    }

    @Test
    fun `a disabled background is named instead of omitted`() {
        val settings = AppSettings.inMemory().apply { terminalBackgroundEnabled = false }
        val line = SettingsReport.lines(settings).single { it.startsWith("terminal.background=") }

        assertEquals("terminal.background=disabled", line)
    }
}
