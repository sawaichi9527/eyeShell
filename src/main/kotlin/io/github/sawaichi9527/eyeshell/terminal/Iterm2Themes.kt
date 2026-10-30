package io.github.sawaichi9527.eyeshell.terminal

import java.util.Properties

/**
 * Colour schemes imported from `mbadolato/iTerm2-Color-Schemes` (MIT).
 *
 * The generated resource contains only schemes whose foreground stays readable both against their
 * own background (WCAG AA, 4.5:1) and against the bundled `bg1.jpg` artwork (4.5:1), because a
 * background image replaces the scheme background behind default-colored cells. The list is
 * produced offline by a small script and committed as `themes/iterm2.properties`; eyeShell has no
 * runtime dependency on the upstream repository.
 */
object Iterm2Themes {
    const val RESOURCE = "/themes/iterm2.properties"

    fun load(): List<TerminalColorScheme> {
        val properties = Properties()
        val stream = Iterm2Themes::class.java.getResourceAsStream(RESOURCE) ?: return emptyList()
        stream.use(properties::load)

        val schemes = mutableListOf<TerminalColorScheme>()
        var index = 1
        while (true) {
            val id = properties.getProperty("theme.$index.id") ?: break
            val name = properties.getProperty("theme.$index.name") ?: id
            val background = properties.getProperty("theme.$index.background")?.toRgb() ?: break
            val foreground = properties.getProperty("theme.$index.foreground")?.toRgb() ?: break
            val ansi = (0 until TerminalColorScheme.ANSI_COLOR_COUNT).map { slot ->
                properties.getProperty("theme.$index.ansi.$slot")?.toRgb() ?: return schemes
            }
            schemes += TerminalColorScheme(id, name, background, foreground, ansi)
            index += 1
        }
        return schemes
    }

    private fun String.toRgb(): Int? = runCatching { removePrefix("#").toInt(16) and 0xFFFFFF }.getOrNull()
}
