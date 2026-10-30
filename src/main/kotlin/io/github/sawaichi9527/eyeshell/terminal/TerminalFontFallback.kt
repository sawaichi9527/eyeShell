package io.github.sawaichi9527.eyeshell.terminal

import com.jediterm.terminal.util.CharUtils
import java.awt.Font

/**
 * Fallback families used when the terminal's primary font has no glyph for a code point.
 *
 * Java only applies cross-family fallback to the logical fonts, so a physical monospace font
 * renders a missing glyph as an empty box (`tofu`). Nerd Font icons are the common case: their
 * code points live in the Private Use Area and no operating-system font contains them.
 *
 * Resolution stays in this order:
 *
 * 1. Nerd Font families, which only add the icon range to a terminal.
 * 2. CJK families, so Chinese/Japanese/Korean text never depends on the primary font's coverage.
 * 3. Symbol and emoji families for the remaining code points.
 *
 * The first family that contains every code point of the drawn run wins. The run is a single
 * grapheme cluster in practice, so a fallback glyph is always drawn inside its own cell.
 *
 * Note that `CharUtils.DWC` (the second cell of a double-width character) is itself in the
 * Private Use Area and must never take part in coverage checks.
 */
internal object TerminalFallbackFonts {
    private val NERD_FONT_FAMILIES = listOf(
        "Maple Mono NF CN",
        "Maple Mono NF",
        "Sarasa Mono TC Nerd Font",
        "Sarasa Term TC Nerd Font",
        "CaskaydiaCove Nerd Font",
        "CaskaydiaMono Nerd Font",
        "JetBrainsMono Nerd Font",
        "JetBrainsMonoNL Nerd Font",
        "Hack Nerd Font",
        "FiraCode Nerd Font",
        "MesloLGL Nerd Font",
    )

    private val WINDOWS_FAMILIES = NERD_FONT_FAMILIES + listOf(
        "MS Gothic",
        "Microsoft JhengHei UI",
        "Microsoft YaHei UI",
        "Malgun Gothic",
        "Noto Sans Mono CJK TC",
        "Noto Sans Mono CJK SC",
        "Segoe UI Symbol",
        "Segoe UI Emoji",
        "Segoe UI",
        "Arial",
    )

    private val LINUX_FAMILIES = NERD_FONT_FAMILIES + listOf(
        "Noto Sans Mono CJK TC",
        "Noto Sans Mono CJK SC",
        "Noto Sans CJK TC",
        "Noto Sans CJK SC",
        // Emoji coverage must come from a monochrome font. Java2D cannot rasterize color bitmap
        // fonts, and `Noto Color Emoji` is CBDT, so selecting it paints no pixels at all — worse
        // than leaving the glyph uncovered. `Noto Emoji` and `Noto Sans Symbols2` are the
        // monochrome alternatives, and their family names are exactly as installed (`Symbols2`
        // has no space, unlike the fontconfig display name).
        "Noto Emoji",
        "Noto Sans Symbols2",
        "Noto Sans Symbols",
        "Symbola",
        "DejaVu Sans",
        "Liberation Sans",
    )

    private val MAC_FAMILIES = NERD_FONT_FAMILIES + listOf(
        "Hiragino Sans",
        "Hiragino Sans GB",
        "Apple Color Emoji",
        "Apple Symbols",
    )

    fun fallbackFamilies(osName: String = System.getProperty("os.name").orEmpty()): List<String> = when {
        osName.startsWith("Windows", ignoreCase = true) -> WINDOWS_FAMILIES
        osName.startsWith("Mac", ignoreCase = true) -> MAC_FAMILIES
        else -> LINUX_FAMILIES
    }

    /**
     * Candidate list with the user's chosen CJK family first, so CJK text uses it before the
     * built-in Nerd/CJK chain. A blank choice keeps the default order.
     */
    fun fallbackFamiliesWith(
        cjkFamily: String?,
        osName: String = System.getProperty("os.name").orEmpty(),
    ): List<String> {
        val base = fallbackFamilies(osName)
        val preferred = cjkFamily?.trim().orEmpty()
        return if (preferred.isEmpty()) base else listOf(preferred) + base
    }
}

/**
 * Font-independent glyph selection. Kept separate from [TerminalFontFallback] so the ordering and
 * code-point handling can be tested without depending on the fonts installed on the build host.
 */
internal object TerminalGlyphFallback {
    /** Code points that never need a glyph: controls and the double-width continuation marker. */
    fun isIgnored(codePoint: Int): Boolean = codePoint == CharUtils.DWC.code || codePoint < 0x20

    /** True when every drawn code point in the run is covered. */
    fun isCovered(text: CharArray, start: Int, end: Int, covers: (Int) -> Boolean): Boolean {
        var index = start
        while (index < end) {
            val codePoint = Character.codePointAt(text, index, end)
            index += Character.charCount(codePoint)
            if (isIgnored(codePoint)) continue
            if (!covers(codePoint)) return false
        }
        return true
    }

    /** The first family covering the whole run, or null when no family does. */
    fun chooseFamily(
        families: List<String>,
        text: CharArray,
        start: Int,
        end: Int,
        covers: (String, Int) -> Boolean,
    ): String? = families.firstOrNull { family -> isCovered(text, start, end) { covers(family, it) } }

    /** The single drawn code point of the run, or null when the run holds zero or several. */
    fun singleCodePoint(text: CharArray, start: Int, end: Int): Int? {
        var index = start
        var result: Int? = null
        while (index < end) {
            val codePoint = Character.codePointAt(text, index, end)
            index += Character.charCount(codePoint)
            if (isIgnored(codePoint)) continue
            if (result != null) return null
            result = codePoint
        }
        return result
    }
}

/**
 * Resolves the font used for one drawn run: the primary font for covered text, otherwise the first
 * installed fallback family that covers every code point, otherwise the primary font so the
 * renderer still behaves predictably when nothing can draw the glyph.
 */
internal class TerminalFontFallback(
    candidateFamilies: List<String> = TerminalFallbackFonts.fallbackFamilies(),
    availableFamilies: Set<String> = TerminalTypography.installedFamilies(),
) {
    private val installedByLowercase = availableFamilies.associateBy(String::lowercase)

    /** Installed fallback families in resolution order, matched case-insensitively. */
    internal val installedFallbacks: List<String> = candidateFamilies
        .mapNotNull { installedByLowercase[it.lowercase()] }
        .distinct()

    private data class FontKey(val family: String, val style: Int, val size: Float)

    private data class PrimaryKey(val name: String, val style: Int, val size: Float)

    private val fonts = HashMap<FontKey, Font>()
    private val singleGlyphFamilies = HashMap<PrimaryKey, HashMap<Int, String?>>()

    fun resolve(primary: Font, text: CharArray, start: Int, end: Int): Font {
        if (isPlainAscii(text, start, end)) return primary
        if (TerminalGlyphFallback.isCovered(text, start, end) { codePoint -> primary.canDisplay(codePoint) }) {
            return primary
        }

        val family = familyFor(primary, text, start, end) ?: return primary
        return fontFor(family, primary)
    }

    private fun familyFor(primary: Font, text: CharArray, start: Int, end: Int): String? {
        val single = TerminalGlyphFallback.singleCodePoint(text, start, end) ?: return chooseFamily(primary, text, start, end)

        val key = PrimaryKey(primary.name, primary.style, primary.size2D)
        val cache = singleGlyphFamilies.getOrPut(key) { HashMap() }
        if (cache.containsKey(single)) return cache[single]

        val family = chooseFamily(primary, text, start, end)
        cache[single] = family
        return family
    }

    private fun chooseFamily(primary: Font, text: CharArray, start: Int, end: Int): String? =
        TerminalGlyphFallback.chooseFamily(installedFallbacks, text, start, end) { family, codePoint ->
            fontFor(family, primary).canDisplay(codePoint)
        }

    private fun fontFor(family: String, primary: Font): Font {
        val key = FontKey(family, primary.style, primary.size2D)
        return fonts.getOrPut(key) {
            Font(family, Font.PLAIN, 1).deriveFont(primary.style, primary.size2D)
        }
    }

    private fun isPlainAscii(text: CharArray, start: Int, end: Int): Boolean {
        for (index in start until end) {
            val code = text[index].code
            if (code < 0x20 || code > 0x7E) return false
        }
        return true
    }
}
