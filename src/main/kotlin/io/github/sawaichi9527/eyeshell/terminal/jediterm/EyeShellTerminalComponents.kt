package io.github.sawaichi9527.eyeshell.terminal.jediterm

import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.TerminalPanel
import com.jediterm.terminal.ui.settings.SettingsProvider
import io.github.sawaichi9527.eyeshell.terminal.TerminalColorScheme
import io.github.sawaichi9527.eyeshell.terminal.TerminalFontFallback
import java.awt.Font

/**
 * JediTerm widget whose panel resolves a fallback font for glyphs the primary terminal font does
 * not contain. Extending the stock widget keeps the patch inside eyeShell instead of the pinned
 * JediTerm fork.
 */
internal class EyeShellTerminalWidget(
    columns: Int,
    rows: Int,
    private val settings: EyeShellTerminalSettings,
) : JediTermWidget(columns, rows, settings) {
    init {
        applyColorScheme(settings.colorScheme)
    }

    override fun createTerminalPanel(
        settingsProvider: SettingsProvider,
        styleState: StyleState,
        terminalTextBuffer: TerminalTextBuffer,
    ): TerminalPanel = EyeShellTerminalPanel(settingsProvider, terminalTextBuffer, styleState)

    /**
     * Supplies the `StyleState` deliberately: the stock one pins the default style to indexed
     * black/white, and every SGR reset copies that style into the cells being written, which would
     * freeze the old scheme into the scrollback. This state keeps the default colors dynamic and
     * resets to an empty (color-inheriting) style instead.
     */
    override fun createDefaultStyle(): StyleState =
        SchemeStyleState(mySettingsProvider as EyeShellTerminalSettings)

    /**
     * Recolors the live widget: the terminal panel reads the palette on every paint, and the
     * default foreground/background are resolved from the current scheme, so a repaint is enough.
     */
    internal fun applyColorScheme(scheme: TerminalColorScheme) {
        settings.colorScheme = scheme
        myTerminalPanel.repaint()
    }
}

/**
 * `StyleState` whose default colors follow the live scheme instead of a copy taken at startup.
 *
 * Cells written with no explicit SGR color store null and are resolved through
 * [getDefaultForeground]/[getDefaultBackground] while painting, which is what makes an existing
 * scrollback recolor when the user picks another scheme.
 */
private class SchemeStyleState(
    private val settings: EyeShellTerminalSettings,
) : StyleState() {
    override fun getDefaultForeground(): TerminalColor = settings.getDefaultForeground()

    override fun getDefaultBackground(): TerminalColor = settings.getDefaultBackground()

    /** SGR reset clears attributes; leaving the colors unset keeps them scheme-resolved. */
    override fun reset() = setCurrent(TextStyle.EMPTY)
}

/**
 * Chooses the font per drawn run so CJK text, emoji, and Nerd Font icons render even when the
 * resolved terminal font only covers Latin. Cell metrics still come from the primary font.
 */
internal class EyeShellTerminalPanel(
    settingsProvider: SettingsProvider,
    terminalTextBuffer: TerminalTextBuffer,
    internal val styleState: StyleState,
) : TerminalPanel(settingsProvider, terminalTextBuffer, styleState) {
    private val fallback = TerminalFontFallback()

    /** Re-reads the terminal font from the settings provider and re-flows the cell grid. */
    internal fun refreshFont() = reinitFontAndResize()

    override fun getFontToDisplay(text: CharArray, start: Int, end: Int, style: TextStyle): Font =
        fallback.resolve(super.getFontToDisplay(text, start, end, style), text, start, end)
}
