package io.github.sawaichi9527.eyeshell.terminal

import java.awt.Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerminalTypographyTest {
    @Test
    fun `linux prefers a CJK-complete monospace font`() {
        val available = setOf(
            "DejaVu Sans Mono",
            "Noto Sans Mono",
            "Noto Sans Mono CJK TC",
            "Sarasa Mono TC",
        )

        assertEquals(
            "Sarasa Mono TC",
            TerminalTypography.resolveFamily(TerminalTypography.preferredFamilies("Linux"), available),
        )
        assertEquals(
            "Noto Sans Mono CJK TC",
            TerminalTypography.resolveFamily(
                TerminalTypography.preferredFamilies("Linux"),
                available - "Sarasa Mono TC",
            ),
        )
    }

    @Test
    fun `windows prefers a CJK font before Cascadia and Consolas`() {
        val families = TerminalTypography.preferredFamilies("Windows 11")
        assertEquals(
            "Noto Sans Mono CJK TC",
            TerminalTypography.resolveFamily(families, setOf("Noto Sans Mono CJK TC", "Cascadia Mono", "Consolas")),
        )
        assertEquals(
            "Cascadia Mono",
            TerminalTypography.resolveFamily(families, setOf("Cascadia Mono", "Consolas", "DejaVu Sans Mono")),
        )
        assertEquals(
            "Consolas",
            TerminalTypography.resolveFamily(families, setOf("Consolas", "DejaVu Sans Mono")),
        )
    }

    @Test
    fun `family matching ignores case and falls back to the logical monospaced font`() {
        assertEquals(
            "noto sans mono cjk tc",
            TerminalTypography.resolveFamily(
                listOf("Noto Sans Mono CJK TC"),
                setOf("noto sans mono cjk tc"),
            ),
        )
        assertEquals(
            Font.MONOSPACED,
            TerminalTypography.resolveFamily(listOf("Missing Font"), setOf("DejaVu Sans Mono")),
        )
        assertEquals(Font.MONOSPACED, TerminalTypography.resolveFamily(listOf("Missing Font"), emptySet()))
    }

    @Test
    fun `resolves a plain font with the requested size`() {
        val available = setOf("Noto Sans Mono CJK TC")
        assertEquals(
            "Noto Sans Mono CJK TC",
            TerminalTypography.resolveFamily(TerminalTypography.preferredFamilies("Linux"), available),
        )

        val font = TerminalTypography.resolveFont(size = 16f, osName = "Linux", available = available)

        // `Font` maps a family that is not installed on this host to a logical fallback, so the
        // requested family is asserted through resolveFamily and the font only for style and size.
        assertEquals(Font.PLAIN, font.style)
        assertEquals(16f, font.size2D)
    }

    @Test
    fun `initial terminal size is wider than the legacy 80 column default`() {
        assertTrue(TerminalTypography.INITIAL_COLUMNS >= 120)
        assertTrue(TerminalTypography.INITIAL_ROWS in 24..40)
        assertEquals(16f, TerminalTypography.DEFAULT_FONT_SIZE)
    }

    @Test
    fun `a selected family is matched case-insensitively`() {
        val available = setOf("Consolas", "DejaVu Sans Mono", "Sarasa Mono TC")

        assertEquals("Consolas", TerminalTypography.resolveFamilyFor("consolas", "Windows 10", available))

        // A Font built for a family this host lacks reports `Dialog`, so the size/style are asserted
        // through resolveFontFor and the family through resolveFamilyFor.
        val font = TerminalTypography.resolveFontFor("consolas", 15f, "Windows 10", available)
        assertEquals(15f, font.size2D)
        assertEquals(Font.PLAIN, font.style)
    }

    @Test
    fun `an unknown family falls back to the automatic choice`() {
        val available = setOf("Consolas", "DejaVu Sans Mono")

        assertEquals(
            TerminalTypography.resolveFamily(TerminalTypography.preferredFamilies("Windows 10"), available),
            TerminalTypography.resolveFamilyFor("Not Installed Mono", "Windows 10", available),
        )
    }

    @Test
    fun `a blank family means automatic`() {
        val available = setOf("Consolas")

        assertEquals("Consolas", TerminalTypography.resolveFamilyFor("", "Windows 10", available))
    }

    @Test
    fun `a chosen CJK family heads the fallback chain`() {
        val families = TerminalFallbackFonts.fallbackFamiliesWith("My CJK Font", "Windows 10")

        assertEquals("My CJK Font", families.first())
        assertEquals(TerminalFallbackFonts.fallbackFamilies("Windows 10"), families.drop(1))
    }

    @Test
    fun `a blank CJK family keeps the default chain`() {
        assertEquals(
            TerminalFallbackFonts.fallbackFamilies("Linux"),
            TerminalFallbackFonts.fallbackFamiliesWith("  ", "Linux"),
        )
    }
}
