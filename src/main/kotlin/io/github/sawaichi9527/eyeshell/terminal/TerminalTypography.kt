package io.github.sawaichi9527.eyeshell.terminal

import java.awt.Font
import java.awt.GraphicsEnvironment

/**
 * Terminal typography policy.
 *
 * The terminal renders each cell from the metrics of `W`, so a font is only safe for mixed
 * Latin/CJK output when a full-width character advances exactly two cells. Sarasa Mono and
 * Noto Sans Mono CJK are built that way; the logical `Monospaced` font falls back to a CJK
 * font whose advance is narrower than two cells, which leaves visible gaps in Chinese text.
 *
 * Resolution prefers the first installed family from a per-platform list and never relies on
 * `Font` returning the requested family (an unknown family silently becomes `Dialog`).
 */
object TerminalTypography {
    const val DEFAULT_FONT_SIZE: Float = 16f

    /**
     * PTY size used before the first component resize. At 16 pt this is close to the roughly 129
     * columns a 1280 px workbench shows, so the first prompt is not wrapped at the legacy 80.
     */
    const val INITIAL_COLUMNS: Int = 120
    const val INITIAL_ROWS: Int = 30

    private val COMMON_FAMILIES = listOf(
        "Sarasa Mono TC",
        "等距更紗黑體 TC",
        "Sarasa Mono SC",
        "等距更纱黑体 SC",
        "Maple Mono NF CN",
        "Noto Sans Mono CJK TC",
        "Noto Sans Mono CJK SC",
    )

    private val LINUX_FAMILIES = COMMON_FAMILIES + listOf(
        "Cascadia Mono",
        "Noto Sans Mono",
        "DejaVu Sans Mono",
        "Liberation Mono",
        "Ubuntu Mono",
        "Ubuntu Sans Mono",
        "Monospaced",
    )

    private val WINDOWS_FAMILIES = COMMON_FAMILIES + listOf(
        "Cascadia Mono",
        "Cascadia Code",
        "Consolas",
        "Noto Sans Mono",
        "DejaVu Sans Mono",
        "Monospaced",
    )

    private val MAC_FAMILIES = COMMON_FAMILIES + listOf(
        "JetBrains Mono",
        "Menlo",
        "Monaco",
        "Noto Sans Mono",
        "Monospaced",
    )

    fun preferredFamilies(osName: String = System.getProperty("os.name").orEmpty()): List<String> = when {
        osName.startsWith("Windows", ignoreCase = true) -> WINDOWS_FAMILIES
        osName.startsWith("Mac", ignoreCase = true) -> MAC_FAMILIES
        else -> LINUX_FAMILIES
    }

    /**
     * Picks the first preferred family that is actually installed, falling back to the logical
     * `Monospaced` family so the terminal always has a usable font.
     */
    fun resolveFamily(preferred: List<String>, available: Set<String>): String {
        val byLowercaseName = available.associateBy { it.lowercase() }
        for (family in preferred) {
            byLowercaseName[family.lowercase()]?.let { return it }
        }
        return Font.MONOSPACED
    }

    fun resolveFont(
        size: Float = DEFAULT_FONT_SIZE,
        osName: String = System.getProperty("os.name").orEmpty(),
        available: Set<String> = installedFamilies(),
    ): Font = Font(resolveFamily(preferredFamilies(osName), available), Font.PLAIN, size.toInt()).deriveFont(size)

    /**
     * Resolves a user-selected Latin family (blank or unknown means "automatic") at [size].
     * The family is matched case-insensitively against the installed families so a stale stored
     * name degrades to the automatic choice instead of silently becoming `Dialog`.
     */
    fun resolveFontFor(
        family: String?,
        size: Float = DEFAULT_FONT_SIZE,
        osName: String = System.getProperty("os.name").orEmpty(),
        available: Set<String> = installedFamilies(),
    ): Font = Font(resolveFamilyFor(family, osName, available), Font.PLAIN, size.toInt()).deriveFont(size)

    /**
     * The family [resolveFontFor] would use, without constructing a [Font]. A `Font` built for a
     * family this host lacks reports `Dialog`, so callers that need to assert the choice use this.
     */
    fun resolveFamilyFor(
        family: String?,
        osName: String = System.getProperty("os.name").orEmpty(),
        available: Set<String> = installedFamilies(),
    ): String {
        val selected = family?.trim().orEmpty().takeIf(String::isNotEmpty)
            ?.let { requested -> available.firstOrNull { it.equals(requested, ignoreCase = true) } }
        return selected ?: resolveFamily(preferredFamilies(osName), available)
    }

    /** Installed families sorted for a picker; `Automatic` is represented by a blank value. */
    fun familyChoices(): List<String> = installedFamilies().sortedBy { it.lowercase() }

    private val sharedDefaultFont: Font by lazy { resolveFont() }

    /** One resolved font shared by every terminal view created without an explicit font. */
    fun defaultFont(): Font = sharedDefaultFont

    /** Installed families, enumerated once: fonts installed while the app runs apply after a restart. */
    fun installedFamilies(): Set<String> = installedFamiliesCache

    private val installedFamiliesCache: Set<String> by lazy {
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }
}
