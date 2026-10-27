package io.github.sawaichi9527.eyeshell.terminal

import com.jediterm.terminal.util.CharUtils
import java.awt.Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerminalFontFallbackTest {
    private fun run(text: String): CharArray = text.toCharArray()

    private fun choose(families: List<String>, text: CharArray, covers: (String, Int) -> Boolean): String? =
        TerminalGlyphFallback.chooseFamily(families, text, 0, text.size, covers)

    @Test
    fun `Windows tries Nerd Fonts before CJK and symbol families`() {
        val families = TerminalFallbackFonts.fallbackFamilies("Windows 11")

        assertTrue(families.indexOf("Maple Mono NF") < families.indexOf("MS Gothic"))
        assertTrue(families.indexOf("MS Gothic") < families.indexOf("Segoe UI Symbol"))
        assertTrue(families.indexOf("Segoe UI Symbol") < families.indexOf("Segoe UI Emoji"))
    }

    @Test
    fun `Linux and macOS keep their own CJK and emoji families`() {
        val linux = TerminalFallbackFonts.fallbackFamilies("Linux")
        assertTrue(linux.indexOf("Maple Mono NF") < linux.indexOf("Noto Sans Mono CJK TC"))
        assertTrue(linux.indexOf("Noto Sans Mono CJK TC") < linux.indexOf("Noto Sans Symbols2"))
        assertTrue(linux.indexOf("Noto Sans Symbols2") < linux.indexOf("DejaVu Sans"))

        val mac = TerminalFallbackFonts.fallbackFamilies("Mac OS X")
        assertTrue(mac.indexOf("Maple Mono NF") < mac.indexOf("Hiragino Sans"))
        assertTrue(mac.indexOf("Hiragino Sans") < mac.indexOf("Apple Color Emoji"))
    }

    @Test
    fun `Linux never offers a color bitmap emoji font that Java2D cannot paint`() {
        // Noto Color Emoji is CBDT: Java2D reports canDisplay for it but draws zero pixels, so
        // choosing it would make emoji disappear instead of falling through to a monochrome font.
        val linux = TerminalFallbackFonts.fallbackFamilies("Linux")

        assertFalse(linux.contains("Noto Color Emoji"))
        assertFalse(linux.contains("Noto Color Emoji "), "family names must match the installed spelling")
        assertTrue(linux.contains("Noto Sans Symbols2"))
        assertFalse(linux.contains("Noto Sans Symbols 2"), "the installed family name has no space")
    }

    @Test
    fun `a fully covered run needs no fallback`() {
        val text = run("plain ascii")

        assertTrue(TerminalGlyphFallback.isCovered(text, 0, text.size) { it < 0x80 })
    }

    @Test
    fun `the first family covering every code point wins`() {
        val text = run("中")

        val family = choose(listOf("Latin", "CJK", "Symbols"), text) { family, codePoint ->
            family == "CJK" && codePoint == 0x4E2D
        }

        assertEquals("CJK", family)
    }

    @Test
    fun `no family yields null so the caller keeps the primary font`() {
        val text = run("\uF015")

        assertNull(choose(listOf("Latin", "CJK"), text) { _, _ -> false })
    }

    @Test
    fun `the double-width continuation marker is ignored`() {
        val text = charArrayOf(0x4E2D.toChar(), CharUtils.DWC)

        val family = choose(listOf("CJK"), text) { candidate, codePoint ->
            candidate == "CJK" && codePoint == 0x4E2D
        }

        assertEquals("CJK", family)
    }

    @Test
    fun `a surrogate pair is one code point`() {
        val text = run("\uD83D\uDCBB") // U+1F4BB

        assertEquals(0x1F4BB, TerminalGlyphFallback.singleCodePoint(text, 0, text.size))
        assertEquals(
            "Emoji",
            choose(listOf("Emoji"), text) { candidate, codePoint ->
                candidate == "Emoji" && codePoint == 0x1F4BB
            },
        )
    }

    @Test
    fun `a run with several glyphs is not treated as a single glyph`() {
        val text = run("ab")

        assertNull(TerminalGlyphFallback.singleCodePoint(text, 0, text.size))
    }

    @Test
    fun `only installed families are candidates and matching ignores case`() {
        val fallback = TerminalFontFallback(
            candidateFamilies = listOf("Missing Font", "maple mono nf", "MAPLE MONO NF"),
            availableFamilies = setOf("Maple Mono NF"),
        )

        assertEquals(listOf("Maple Mono NF"), fallback.installedFallbacks)
    }

    @Test
    fun `ASCII and uncovered text keep the primary font when no family is installed`() {
        val primary = Font(Font.MONOSPACED, Font.PLAIN, 16)
        val fallback = TerminalFontFallback(candidateFamilies = emptyList(), availableFamilies = emptySet())

        val ascii = run("abc")
        assertSame(primary, fallback.resolve(primary, ascii, 0, ascii.size))

        val cjk = run("\u4E2D")
        assertSame(primary, fallback.resolve(primary, cjk, 0, cjk.size))
    }
}
