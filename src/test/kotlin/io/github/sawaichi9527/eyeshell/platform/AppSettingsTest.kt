package io.github.sawaichi9527.eyeshell.platform

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AppSettingsTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a missing file yields the default terminal font size`() {
        val settings = AppSettings.load(directory.resolve("settings.properties"))

        assertEquals(AppSettings.DEFAULT_TERMINAL_FONT_SIZE, settings.terminalFontSize)
    }

    @Test
    fun `the terminal font size survives a reload`() {
        val file = directory.resolve("settings.properties")
        AppSettings.load(file).terminalFontSize = 20

        assertTrue(Files.isRegularFile(file))
        assertEquals(20, AppSettings.load(file).terminalFontSize)
    }

    @Test
    fun `a font size outside the supported range is clamped`() {
        val file = directory.resolve("settings.properties")
        val settings = AppSettings.load(file)

        settings.terminalFontSize = 1
        assertEquals(AppSettings.MIN_TERMINAL_FONT_SIZE, AppSettings.load(file).terminalFontSize)

        settings.terminalFontSize = 99
        assertEquals(AppSettings.MAX_TERMINAL_FONT_SIZE, AppSettings.load(file).terminalFontSize)
    }

    @Test
    fun `the terminal color scheme id survives a reload`() {
        val file = directory.resolve("settings.properties")
        assertEquals("", AppSettings.load(file).terminalColorSchemeId)

        AppSettings.load(file).terminalColorSchemeId = "dracula"

        assertEquals("dracula", AppSettings.load(file).terminalColorSchemeId)
    }

    @Test
    fun `a damaged file falls back to defaults instead of failing`() {
        val file = directory.resolve("settings.properties")
        Files.writeString(file, "terminal.font.size=not-a-number\n")

        assertEquals(AppSettings.DEFAULT_TERMINAL_FONT_SIZE, AppSettings.load(file).terminalFontSize)
    }

    @Test
    fun `missing directories are created on save`() {
        val file = directory.resolve("nested").resolve("deeper").resolve("settings.properties")

        AppSettings.load(file).terminalFontSize = 18

        assertEquals(18, AppSettings.load(file).terminalFontSize)
    }
}
