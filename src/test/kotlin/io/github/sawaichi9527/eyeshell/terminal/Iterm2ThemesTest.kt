package io.github.sawaichi9527.eyeshell.terminal

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the imported iTerm2 palettes: they were filtered so a machine without a background image
 * still gets WCAG AA text, and a machine with the bundled `bg1.jpg` keeps readable text as well.
 */
class Iterm2ThemesTest {
    private val themes = Iterm2Themes.load()

    @Test
    fun `a reasonable number of curated themes is bundled`() {
        assertTrue(themes.size >= 20, "Expected at least 20 imported themes, got ${themes.size}")
        themes.forEach { scheme ->
            assertTrue(scheme.id.startsWith("iterm-"), "Unexpected imported id: ${scheme.id}")
            assertTrue(scheme.displayName.isNotBlank())
            assertEquals(TerminalColorScheme.ANSI_COLOR_COUNT, scheme.ansiRgb.size)
        }
    }

    @Test
    fun `imported themes meet WCAG AA on their own background`() {
        themes.forEach { scheme ->
            assertTrue(
                scheme.contrastRatio >= 4.5,
                "${scheme.displayName} contrast ${scheme.contrastRatio} is below 4.5:1",
            )
        }
    }

    @Test
    fun `imported themes stay readable over the bundled bg1 artwork`() {
        val luminance = bg1Luminance()

        themes.forEach { scheme ->
            val foreground = relativeLuminance(scheme.foregroundRgb)
            val lighter = maxOf(foreground, luminance)
            val darker = minOf(foreground, luminance)
            val contrast = (lighter + 0.05) / (darker + 0.05)
            assertTrue(
                contrast >= 4.5,
                "${scheme.displayName} foreground contrast over bg1 is ${"%.2f".format(contrast)}",
            )
        }
    }

    @Test
    fun `the combined scheme list has unique ids and names`() {
        val all = TerminalColorSchemes.ALL

        assertEquals(all.size, all.map { it.id }.toSet().size, "ids must be unique")
        assertEquals(all.size, all.map { it.displayName.lowercase() }.toSet().size, "names must be unique")
        assertTrue(all.size >= TerminalColorSchemes.ALL.size)
        assertTrue(all.any { it.id.startsWith("iterm-") })
    }

    /** Average sRGB luminance of the bundled background preset. */
    private fun bg1Luminance(): Double {
        val stream = checkNotNull(javaClass.getResourceAsStream("/backgrounds/bg1.jpg"))
        val image: BufferedImage = stream.use { ImageIO.read(it) }
        var total = 0.0
        var count = 0
        for (y in 0 until image.height step 8) {
            for (x in 0 until image.width step 8) {
                val rgb = image.getRGB(x, y)
                total += relativeLuminance(rgb and 0xFFFFFF)
                count++
            }
        }
        return total / count
    }

    private fun relativeLuminance(rgb: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((rgb shr shift) and 0xFF) / 255.0
            return if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
