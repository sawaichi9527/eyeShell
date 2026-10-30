package io.github.sawaichi9527.eyeshell.terminal

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * A user-selectable terminal character encoding.
 *
 * The id is the canonical JVM `Charset` name, so [charset] can be resolved without a lookup table
 * and an unknown id falls back to UTF-8 instead of failing startup.
 */
data class TerminalCharset(
    val id: String,
    val displayName: String,
) {
    val charset: Charset get() = Charset.forName(id)

    override fun toString(): String = displayName
}

/**
 * Curated encodings offered by the Settings dialog, mirroring the character-set selector common to
 * SSH tools. Every entry is a JDK standard charset; no optional provider is required.
 *
 * UTF-8 stays the default, so an existing installation keeps behaving exactly as before.
 */
object TerminalCharsets {
    val UTF_8 = TerminalCharset("UTF-8", "UTF-8")

    val ALL: List<TerminalCharset> = listOf(
        UTF_8,
        TerminalCharset("GB18030", "GB18030 (簡體中文)"),
        TerminalCharset("GBK", "GBK (簡體中文)"),
        TerminalCharset("Big5", "BIG5 (繁體中文)"),
        TerminalCharset("Big5-HKSCS", "BIG5-HKSCS (香港)"),
        TerminalCharset("Shift_JIS", "Shift_JIS (日本語)"),
        TerminalCharset("EUC-JP", "EUC-JP (日本語)"),
        TerminalCharset("EUC-KR", "EUC-KR (한국어)"),
        TerminalCharset("ISO-8859-1", "ISO-8859-1 (Latin-1)"),
        TerminalCharset("windows-1252", "windows-1252 (Western)"),
    )

    val DEFAULT: TerminalCharset = UTF_8

    /** Resolves a stored id, ignoring case and surrounding whitespace; unknown ids use UTF-8. */
    fun fromId(id: String?): TerminalCharset {
        val normalized = id?.trim().orEmpty()
        if (normalized.isEmpty()) return DEFAULT
        return ALL.firstOrNull { it.id.equals(normalized, ignoreCase = true) } ?: DEFAULT
    }

    fun charsetFromId(id: String?): Charset = fromId(id).charset

    /** UTF-8 is the documented default for every session that has not been switched. */
    val DEFAULT_CHARSET: Charset = StandardCharsets.UTF_8
}
