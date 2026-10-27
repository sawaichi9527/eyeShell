package io.github.sawaichi9527.eyeshell.terminal.jediterm

import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.TerminalPanel
import com.jediterm.terminal.ui.settings.SettingsProvider
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
    settingsProvider: SettingsProvider,
) : JediTermWidget(columns, rows, settingsProvider) {
    override fun createTerminalPanel(
        settingsProvider: SettingsProvider,
        styleState: StyleState,
        terminalTextBuffer: TerminalTextBuffer,
    ): TerminalPanel = EyeShellTerminalPanel(settingsProvider, terminalTextBuffer, styleState)
}

/**
 * Chooses the font per drawn run so CJK text, emoji, and Nerd Font icons render even when the
 * resolved terminal font only covers Latin. Cell metrics still come from the primary font.
 */
internal class EyeShellTerminalPanel(
    settingsProvider: SettingsProvider,
    terminalTextBuffer: TerminalTextBuffer,
    styleState: StyleState,
) : TerminalPanel(settingsProvider, terminalTextBuffer, styleState) {
    private val fallback = TerminalFontFallback()

    /** Re-reads the terminal font from the settings provider and re-flows the cell grid. */
    internal fun refreshFont() = reinitFontAndResize()

    override fun getFontToDisplay(text: CharArray, start: Int, end: Int, style: TextStyle): Font =
        fallback.resolve(super.getFontToDisplay(text, start, end, style), text, start, end)
}
