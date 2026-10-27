package io.github.sawaichi9527.eyeshell.terminal

/**
 * A terminal color scheme: a background, a foreground, and the 16 ANSI colors.
 *
 * Only published, widely used palettes are included, and none of them is copied from FinalShell
 * (the product specification forbids reusing its visual assets). Each scheme keeps its canonical
 * background as the terminal background instead of compositing it over the window theme, so the
 * text contrast a scheme documents is the contrast the user actually gets.
 *
 * [contrastRatio] is the WCAG 2.1 relative-luminance ratio of the foreground against the
 * background. It is shown in the Settings preview and asserted for every bundled scheme by
 * `TerminalColorSchemeTest`, which is what keeps a future palette edit from quietly reducing
 * readability.
 */
data class TerminalColorScheme(
    val id: String,
    val displayName: String,
    val backgroundRgb: Int,
    val foregroundRgb: Int,
    /** The 16 ANSI colors in index order: black, red, green, yellow, blue, magenta, cyan, white, then the bright variants. */
    val ansiRgb: List<Int>,
) {
    init {
        require(id.isNotBlank()) { "Color scheme id must not be blank" }
        require(backgroundRgb in 0..0xFFFFFF) { "Invalid background color" }
        require(foregroundRgb in 0..0xFFFFFF) { "Invalid foreground color" }
        require(ansiRgb.size == ANSI_COLOR_COUNT) { "A color scheme needs $ANSI_COLOR_COUNT ANSI colors" }
        require(ansiRgb.all { it in 0..0xFFFFFF }) { "Invalid ANSI color" }
    }

    /** WCAG 2.1 contrast ratio between [foregroundRgb] and [backgroundRgb], from 1.0 to 21.0. */
    val contrastRatio: Double
        get() {
            val foreground = relativeLuminance(foregroundRgb)
            val background = relativeLuminance(backgroundRgb)
            val lighter = maxOf(foreground, background)
            val darker = minOf(foreground, background)
            return (lighter + 0.05) / (darker + 0.05)
        }

    companion object {
        const val ANSI_COLOR_COUNT = 16

        private fun relativeLuminance(rgb: Int): Double {
            fun channel(shift: Int): Double {
                val value = ((rgb shr shift) and 0xFF) / 255.0
                return if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
    }
}

/**
 * The bundled schemes, in the order the Settings dialog lists them.
 *
 * The light schemes remap the `white` ANSI slots to a dark base color. The canonical Solarized
 * Light palette assigns near-white there, which is unreadable on a near-white background and is
 * exactly the contrast problem this feature exists to avoid.
 */
object TerminalColorSchemes {
    /** One Dark, the de-facto dark default for terminal chrome of this kind. */
    val DARK = TerminalColorScheme(
        id = "eyeShell-dark",
        displayName = "eyeShell Dark",
        backgroundRgb = 0x282C34,
        foregroundRgb = 0xABB2BF,
        ansiRgb = listOf(
            0x282C34, 0xE06C75, 0x98C379, 0xE5C07B, 0x61AFEF, 0xC678DD, 0x56B6C2, 0xABB2BF,
            0x5C6370, 0xE06C75, 0x98C379, 0xE5C07B, 0x61AFEF, 0xC678DD, 0x56B6C2, 0xFFFFFF,
        ),
    )

    /** GitHub's light palette: near-black text on pure white, the highest contrast of the set. */
    val LIGHT = TerminalColorScheme(
        id = "eyeShell-light",
        displayName = "eyeShell Light",
        backgroundRgb = 0xFFFFFF,
        foregroundRgb = 0x24292F,
        ansiRgb = listOf(
            0x24292F, 0xCF222E, 0x116329, 0x4D2D00, 0x0969DA, 0x8250DF, 0x1B7C83, 0x6E7781,
            0x57606A, 0xA40E26, 0x1A7F37, 0x633C01, 0x218BFF, 0xA475F9, 0x3192AA, 0x8C959F,
        ),
    )

    /** Solarized Light with `base01` text, so body text stays above 4.5:1 instead of the canonical 4.2:1. */
    val SOLARIZED_LIGHT = TerminalColorScheme(
        id = "solarized-light",
        displayName = "Solarized Light",
        backgroundRgb = 0xFDF6E3,
        foregroundRgb = 0x586E75,
        ansiRgb = listOf(
            0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0x586E75,
            0x002B36, 0xCB4B16, 0x6C71C4, 0x657B83, 0x268BD2, 0x6C71C4, 0x2AA198, 0x93A1A1,
        ),
    )

    val SOLARIZED_DARK = TerminalColorScheme(
        id = "solarized-dark",
        displayName = "Solarized Dark",
        backgroundRgb = 0x002B36,
        foregroundRgb = 0x839496,
        ansiRgb = listOf(
            0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0xEEE8D5,
            0x002B36, 0xCB4B16, 0x586E75, 0x657B83, 0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3,
        ),
    )

    val DRACULA = TerminalColorScheme(
        id = "dracula",
        displayName = "Dracula",
        backgroundRgb = 0x282A36,
        foregroundRgb = 0xF8F8F2,
        ansiRgb = listOf(
            0x21222C, 0xFF5555, 0x50FA7B, 0xF1FA8C, 0xBD93F9, 0xFF79C6, 0x8BE9FD, 0xF8F8F2,
            0x6272A4, 0xFF6E6E, 0x69FF94, 0xFFFFA5, 0xD6ACFF, 0xFF92DF, 0xA4FFFF, 0xFFFFFF,
        ),
    )

    val NORD = TerminalColorScheme(
        id = "nord",
        displayName = "Nord",
        backgroundRgb = 0x2E3440,
        foregroundRgb = 0xD8DEE9,
        ansiRgb = listOf(
            0x3B4252, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD, 0x88C0D0, 0xE5E9F0,
            0x4C566A, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD, 0x8FBCBB, 0xECEFF4,
        ),
    )

    /** The schemes in Settings order. */
    val ALL: List<TerminalColorScheme> = listOf(DARK, LIGHT, SOLARIZED_LIGHT, SOLARIZED_DARK, DRACULA, NORD)

    /** Applied when nothing is stored yet, or when a stored id no longer exists. */
    val DEFAULT: TerminalColorScheme = DARK

    /** Resolves a persisted id, falling back to [DEFAULT] for blank, unknown, or renamed schemes. */
    fun fromId(id: String?): TerminalColorScheme =
        ALL.firstOrNull { it.id == id } ?: DEFAULT
}
