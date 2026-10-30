package io.github.sawaichi9527.eyeshell.terminal

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TerminalBackgroundImageTest {
    @Test
    fun `stored settings build a selection only when the feature is enabled`() {
        val path = Path.of("/tmp/bg.png").toString()

        val enabled = TerminalBackgroundImage.fromSettings(true, path, 70)
        assertEquals(Path.of(path), enabled?.file)
        assertEquals(70, enabled?.opacityPercent)

        assertNull(TerminalBackgroundImage.fromSettings(false, path, 70))
        assertNull(TerminalBackgroundImage.fromSettings(true, "", 70))
        assertNull(TerminalBackgroundImage.fromSettings(true, "   ", 70))
    }

    @Test
    fun `opacity outside the supported range is rejected`() {
        val path = Path.of("bg.png")

        assertThrows(IllegalArgumentException::class.java) {
            TerminalBackgroundImage(path, TerminalBackgroundImage.MIN_OPACITY_PERCENT - 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TerminalBackgroundImage(path, TerminalBackgroundImage.MAX_OPACITY_PERCENT + 1)
        }
    }

    @Test
    fun `the default opacity keeps the image fully visible`() {
        assertEquals(100, TerminalBackgroundImage(Path.of("bg.png")).opacityPercent)
    }
}
