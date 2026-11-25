package io.github.sawaichi9527.eyeshell.platform

/**
 * The non-secret settings that change how the terminal behaves, for the diagnostics report.
 *
 * A dump without them cannot separate "the app is broken" from "the app is configured that way":
 * a non-UTF-8 charset, a CJK font that lacks glyphs, or a background image are all things a report
 * reader otherwise has to ask about. Values are one word each (`key=value`), so the report stays
 * greppable; the image is reduced to its file name so a personal folder path is not shipped.
 */
object SettingsReport {
    fun lines(settings: AppSettings): List<String> = listOf(
        "app.locale=${value(settings.localeId)}",
        "terminal.charset=${value(settings.terminalCharsetId)}",
        "terminal.colorScheme=${value(settings.terminalColorSchemeId)}",
        "terminal.font.size=${settings.terminalFontSize}",
        "terminal.font.family=${value(settings.terminalFontFamily)}",
        "terminal.font.cjkFamily=${value(settings.terminalFontCjkFamily)}",
        "terminal.background=${background(settings)}",
    )

    private fun background(settings: AppSettings): String {
        if (!settings.terminalBackgroundEnabled) {
            return "disabled"
        }
        val name = settings.terminalBackgroundImage
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { "-" }
        return "${value(name)}@${settings.terminalBackgroundOpacity}%"
    }

    private fun value(text: String): String = text
        .trim()
        .map { if (it.isWhitespace()) '_' else it }
        .joinToString("")
        .ifEmpty { "-" }
}
