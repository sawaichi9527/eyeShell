package io.github.sawaichi9527.eyeshell.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerminalColorSchemeTest {
    private val schemes = TerminalColorSchemes.ALL

    @Test
    fun `the bundled schemes are unique and fully specified`() {
        assertEquals(TerminalColorSchemes.ALL.size, schemes.map { it.id }.toSet().size, "scheme ids must be unique")
        assertEquals(schemes.size, schemes.map { it.displayName }.toSet().size, "names must be unique")
        schemes.forEach { scheme ->
            assertEquals(TerminalColorScheme.ANSI_COLOR_COUNT, scheme.ansiRgb.size, scheme.id)
            assertNotEquals(scheme.backgroundRgb, scheme.foregroundRgb, scheme.id)
        }
    }

    @Test
    fun `every bundled scheme meets WCAG AA for body text`() {
        schemes.forEach { scheme ->
            assertTrue(
                scheme.contrastRatio >= 4.5,
                "${scheme.displayName} contrast ${scheme.contrastRatio} is below the 4.5:1 AA threshold",
            )
        }
    }

    @Test
    fun `the default scheme is the dark one and is listed first`() {
        assertSame(TerminalColorSchemes.DARK, TerminalColorSchemes.DEFAULT)
        assertSame(TerminalColorSchemes.DARK, schemes.first())
        assertTrue(TerminalColorSchemes.DARK.displayName.contains("Dark"))
    }

    @Test
    fun `light schemes use dark text and dark schemes use light text`() {
        schemes.forEach { scheme ->
            val backgroundLuminance = luminance(scheme.backgroundRgb)
            val foregroundLuminance = luminance(scheme.foregroundRgb)
            if (backgroundLuminance > 0.5) {
                assertTrue(foregroundLuminance < backgroundLuminance, "${scheme.displayName} needs dark text")
            } else {
                assertTrue(foregroundLuminance > backgroundLuminance, "${scheme.displayName} needs light text")
            }
        }
    }

    @Test
    fun `a light scheme keeps its white ANSI slots readable`() {
        listOf(TerminalColorSchemes.LIGHT, TerminalColorSchemes.SOLARIZED_LIGHT).forEach { scheme ->
            listOf(7, 15).forEach { index ->
                assertTrue(
                    luminance(scheme.ansiRgb[index]) < 0.5,
                    "${scheme.displayName} ANSI $index must be dark on a light background",
                )
            }
        }
    }

    @Test
    fun `a stored id resolves, and blank or unknown ids fall back to the default`() {
        assertSame(TerminalColorSchemes.DRACULA, TerminalColorSchemes.fromId("dracula"))
        assertSame(TerminalColorSchemes.DEFAULT, TerminalColorSchemes.fromId(null))
        assertSame(TerminalColorSchemes.DEFAULT, TerminalColorSchemes.fromId(""))
        assertSame(TerminalColorSchemes.DEFAULT, TerminalColorSchemes.fromId("removed-scheme"))
    }

    @Test
    fun `the contrast ratio bounds are one to twenty one`() {
        val blackOnWhite = TerminalColorScheme(
            id = "test",
            displayName = "test",
            backgroundRgb = 0xFFFFFF,
            foregroundRgb = 0x000000,
            ansiRgb = List(TerminalColorScheme.ANSI_COLOR_COUNT) { 0 },
        )

        assertEquals(21.0, blackOnWhite.contrastRatio, 0.01)
    }

    private fun luminance(rgb: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((rgb shr shift) and 0xFF) / 255.0
            return if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
