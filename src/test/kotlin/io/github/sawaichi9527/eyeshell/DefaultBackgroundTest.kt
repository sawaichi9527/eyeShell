package io.github.sawaichi9527.eyeshell

import io.github.sawaichi9527.eyeshell.platform.AppSettings
import io.github.sawaichi9527.eyeshell.platform.BackgroundImages
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DefaultBackgroundTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a fresh profile enables the bundled bg1 preset`() {
        val settings = AppSettings.inMemory()

        applyDefaultBackground(settings, directory)

        val expected = directory.resolve(BackgroundImages.DEFAULT_PRESET)
        assertEquals(expected.toString(), settings.terminalBackgroundImage)
        assertTrue(Files.isRegularFile(expected))
        assertTrue(settings.terminalBackgroundEnabled)
    }

    @Test
    fun `clearing the image is not overridden on the next launch`() {
        val settings = AppSettings.inMemory()
        settings.terminalBackgroundEnabled = false
        settings.terminalBackgroundImage = ""

        applyDefaultBackground(settings, directory)

        assertEquals("", settings.terminalBackgroundImage)
        assertEquals(false, settings.terminalBackgroundEnabled)
    }

    @Test
    fun `a chosen user image is left alone`() {
        val settings = AppSettings.inMemory()
        val chosen = directory.resolve("wallpaper.png")
        Files.writeString(chosen, "image")
        settings.terminalBackgroundImage = chosen.toString()

        applyDefaultBackground(settings, directory)

        assertEquals(chosen.toString(), settings.terminalBackgroundImage)
    }

    @Test
    fun `a deleted preset file is restored for the stored path`() {
        val settings = AppSettings.inMemory()
        val stored = checkNotNull(BackgroundImages.materializePreset("bg3.jpg", directory))
        Files.delete(stored)
        settings.terminalBackgroundImage = stored.toString()

        applyDefaultBackground(settings, directory)

        assertTrue(Files.isRegularFile(stored))
        assertEquals(stored.toString(), settings.terminalBackgroundImage)
    }
}
