package io.github.sawaichi9527.eyeshell.terminal.jediterm

import com.jediterm.core.Color as TerminalRgb
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.emulator.ColorPalette
import com.jediterm.terminal.model.MainBufferSnapshot
import com.jediterm.terminal.model.TerminalLineSnapshot
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.TerminalAction
import com.jediterm.terminal.ui.TerminalActionPresentation
import com.jediterm.terminal.ui.TerminalActionProvider
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.jediterm.terminal.util.CharUtils
import io.github.sawaichi9527.eyeshell.platform.I18n
import io.github.sawaichi9527.eyeshell.terminal.TerminalBackgroundImage
import io.github.sawaichi9527.eyeshell.terminal.TerminalColorScheme
import io.github.sawaichi9527.eyeshell.terminal.TerminalColorSchemes
import io.github.sawaichi9527.eyeshell.terminal.TerminalContextActions
import io.github.sawaichi9527.eyeshell.terminal.TerminalHighlightRule
import io.github.sawaichi9527.eyeshell.terminal.TerminalOutputSnapshot
import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalTypography
import io.github.sawaichi9527.eyeshell.terminal.TerminalView
import java.io.Writer
import javax.swing.JComponent
import javax.swing.SwingUtilities

class JediTermTerminalView(
    columns: Int = TerminalTypography.INITIAL_COLUMNS,
    rows: Int = TerminalTypography.INITIAL_ROWS,
    scrollbackLines: Int = EyeShellTerminalSettings.MAX_SCROLLBACK_LINES,
    maxRefreshRate: Int = EyeShellTerminalSettings.MAX_REFRESH_RATE,
    font: java.awt.Font = TerminalTypography.defaultFont(),
    colorScheme: TerminalColorScheme = TerminalColorSchemes.DEFAULT,
) : TerminalView {
    private val settings = EyeShellTerminalSettings(scrollbackLines, maxRefreshRate, font, colorScheme)
    private val widget = EyeShellTerminalWidget(columns, rows, settings)
    private val searchController = JediTermSearchController(widget)
    private val highlightController = JediTermHighlightController(widget)
    private val contextActionProvider = ContextActionProvider(widget, searchController::show)
    private var activeConnector: SessionTtyConnector? = null

    init {
        widget.terminalPanel.setPopupMenuActionProvider(contextActionProvider)
        widget.terminalPanel.setRetainedMainCopyHandler {
            contextActionProvider.actions?.copyAllOutput?.invoke()
        }
        widget.setFindActionHandler(::showSearch)
    }

    override val component: JComponent
        get() = widget

    override fun attach(session: TerminalSession) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal sessions must be attached on the Swing EDT" }
        check(widget.canOpenSession()) { "A terminal session is already attached" }
        contextActionProvider.hasSession = true
        val connector = SessionTtyConnector(session)
        activeConnector = connector
        widget.setTtyConnector(connector)
        widget.start()
    }

    override fun canAttach(): Boolean = widget.canOpenSession()

    /**
     * Stops the emulator thread without closing the session, so [attach] can replay output into the
     * same widget. The connector polls, so the emulator thread observes the detach within a few
     * milliseconds; a session that does not yield inside [DETACH_TIMEOUT] is resumed instead.
     */
    override fun detachSession(): Boolean {
        val connector = activeConnector ?: return false
        connector.detach()
        val deadline = System.nanoTime() + DETACH_TIMEOUT.toNanos()
        while (widget.isSessionRunning) {
            if (System.nanoTime() >= deadline) {
                connector.resume()
                return false
            }
            Thread.sleep(2)
        }
        activeConnector = null
        contextActionProvider.hasSession = false
        return true
    }

    override fun resetForReattach() {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal reattach preparation must run on the Swing EDT" }
        if (widget.terminalTextBuffer.isUsingAlternateBuffer) {
            widget.terminalTextBuffer.useAlternateBuffer(false)
        }
    }

    override fun captureAllOutput(): TerminalOutputSnapshot {
        check(!SwingUtilities.isEventDispatchThread()) { "Terminal output must be exported off the Swing EDT" }
        val snapshot = widget.terminalTextBuffer.getMainBufferSnapshot()
        return TerminalOutputSnapshot(snapshot::writeLogicalLines)
    }

    override fun setContextActions(actions: TerminalContextActions) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal context actions must be configured on the Swing EDT" }
        contextActionProvider.actions = actions
    }

    override fun selectVisible() {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal selection must run on the Swing EDT" }
        widget.terminalPanel.selectVisible()
    }

    override fun selectAllOutput() {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal selection must run on the Swing EDT" }
        widget.terminalPanel.selectAllMainOutput()
    }

    override fun showSearch() {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal search must open on the Swing EDT" }
        searchController.show()
    }

    override fun setHighlightRules(rules: List<TerminalHighlightRule>) {
        highlightController.setRules(rules)
    }

    override fun clearScrollback() {
        check(SwingUtilities.isEventDispatchThread()) { "Scrollback must be cleared on the Swing EDT" }
        check(!widget.terminalTextBuffer.isUsingAlternateBuffer) {
            "Main scrollback cannot be cleared while the alternate screen is active"
        }
        widget.terminalTextBuffer.clearHistory()
    }

    internal val isSessionRunning: Boolean
        get() = widget.isSessionRunning

    internal val isUsingAlternateBuffer: Boolean
        get() = widget.terminalTextBuffer.isUsingAlternateBuffer

    internal val colorScheme: TerminalColorScheme
        get() = settings.colorScheme

    internal val defaultBackgroundRgb: Int
        get() = settings.colorScheme.backgroundRgb

    internal val defaultForegroundRgb: Int
        get() = settings.colorScheme.foregroundRgb

    internal val ansiPaletteRgb: List<Int>
        get() = settings.terminalColorPaletteRgb()

    internal val historyLineCount: Int
        get() = widget.terminalTextBuffer.historyLinesCount

    internal val contextActions: List<TerminalAction>
        get() = contextActionProvider.getActions()

    internal val searchComponent: JComponent
        get() = searchController.component

    internal val selection
        get() = widget.terminalPanel.selection

    internal val fontSize: Float
        get() = settings.font.size2D

    internal val isRetainedMainSelection: Boolean
        get() = widget.terminalPanel.isRetainedMainSelection

    internal val coordinateSearchResult
        get() = widget.terminalPanel.coordinateFindResult

    internal val coordinateHighlightResult
        get() = widget.terminalPanel.coordinateHighlightResult

    internal val mainBufferRevision: Long
        get() = widget.terminalTextBuffer.getMainBufferRevision()

    internal fun awaitHighlightTermination(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean =
        highlightController.awaitTermination(timeout, unit)

    override fun setFontSize(size: Float) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal font changes must happen on the Swing EDT" }
        require(size > 0f) { "Terminal font size must be positive" }
        settings.font = settings.font.deriveFont(size)
        (widget.terminalPanel as EyeShellTerminalPanel).refreshFont()
    }

    override fun setColorScheme(scheme: TerminalColorScheme) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal color changes must happen on the Swing EDT" }
        widget.applyColorScheme(scheme)
    }

    override fun setBackgroundImage(image: TerminalBackgroundImage?) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal background changes must happen on the Swing EDT" }
        widget.applyBackgroundImage(image)
    }

    /** Test hook: loads the image inline instead of on a background thread. */
    internal fun setBackgroundImageForTest(image: TerminalBackgroundImage?) {
        widget.applyBackgroundImage(image, loadSynchronously = true)
    }

    internal val terminalPanel: EyeShellTerminalPanel
        get() = widget.terminalPanel as EyeShellTerminalPanel

    override fun close() {
        highlightController.close()
        searchController.close()
        widget.close()
    }

    private companion object {
        /** How long a detach may take before the view gives up and keeps the session attached. */
        val DETACH_TIMEOUT: java.time.Duration = java.time.Duration.ofSeconds(2)
    }
}

private class ContextActionProvider(
    private val widget: JediTermWidget,
    private val showSearch: () -> Unit,
) : TerminalActionProvider {
    var actions: TerminalContextActions? = null
    var hasSession: Boolean = false
    private var nextProvider: TerminalActionProvider? = null

    override fun getActions(): List<TerminalAction> = listOf(
        action(I18n.t("terminal.copy"), enabled = widget.terminalPanel::canCopyCurrentSelection) {
            widget.terminalPanel.copyCurrentSelection()
        },
        action(I18n.t("terminal.paste"), enabled = { hasSession && widget.isSessionRunning }) {
            if (widget.isSessionRunning) widget.terminalPanel.pasteClipboard()
        },
        action(I18n.t("terminal.selectVisible"), separated = true, enabled = { hasSession }) {
            widget.terminalPanel.selectVisible()
        },
        action(I18n.t("terminal.selectAllOutput"), enabled = { hasSession }) {
            widget.terminalPanel.selectAllMainOutput()
        },
        action(I18n.t("terminal.copyAllOutput"), enabled = { hasSession && actions != null }) {
            actions?.copyAllOutput?.invoke()
        },
        action(I18n.t("terminal.saveAllOutput"), enabled = { hasSession && actions != null }) {
            actions?.saveAllOutput?.invoke()
        },
        action(I18n.t("terminal.search"), separated = true, enabled = { hasSession }) {
            showSearch()
        },
        action(I18n.t("terminal.addHighlight"), enabled = { hasSession && actions?.addHighlightRule != null }) {
            actions?.addHighlightRule?.invoke()
        },
        action(I18n.t("terminal.manageHighlights"), enabled = { hasSession && actions?.manageHighlightRules != null }) {
            actions?.manageHighlightRules?.invoke()
        },
        action(I18n.t("terminal.clearBuffer"), separated = true, enabled = {
            hasSession && !widget.terminalTextBuffer.isUsingAlternateBuffer
        }) {
            if (!widget.terminalTextBuffer.isUsingAlternateBuffer) widget.terminalPanel.clearBuffer()
        },
    )

    private fun action(
        name: String,
        separated: Boolean = false,
        enabled: () -> Boolean,
        run: () -> Unit,
    ): TerminalAction = TerminalAction(TerminalActionPresentation(name, emptyList())) {
        run()
        true
    }.withEnabledSupplier(enabled).separatorBefore(separated)

    override fun getNextProvider(): TerminalActionProvider? = nextProvider

    override fun setNextProvider(provider: TerminalActionProvider?) {
        nextProvider = provider
    }
}

private class SessionTtyConnector(
    private val session: TerminalSession,
) : TtyConnector {
    @Volatile
    private var detached = false

    fun detach() {
        detached = true
    }

    fun resume() {
        detached = false
    }

    /**
     * Polls instead of blocking so [detach] can stop the emulator thread without closing the SSH
     * session; a terminal that would otherwise wait forever in a read must be interruptible for the
     * encoding replay to work.
     */
    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        while (true) {
            if (detached) return -1
            // Drain buffered output before reporting the end of a closed session, so the last chunk
            // is not lost when a stream reports closed while its reader still has characters.
            if (session.ready()) return session.read(buffer, offset, length)
            if (!session.isOpen) return -1
            Thread.sleep(READ_POLL_MILLIS)
        }
    }

    override fun write(bytes: ByteArray) = session.write(bytes)

    override fun write(string: String) = session.write(string)

    override fun isConnected(): Boolean = session.isOpen

    override fun resize(termSize: TermSize) = session.resize(termSize.columns, termSize.rows)

    override fun waitFor(): Int = session.awaitExit()

    override fun ready(): Boolean = session.ready()

    override fun getName(): String = session.name

    /** A detached connector must not end the connection the tab still owns. */
    override fun close() {
        if (!detached) session.close()
    }

    private companion object {
        const val READ_POLL_MILLIS = 5L
    }
}

internal class EyeShellTerminalSettings(
    private val scrollbackLines: Int = MAX_SCROLLBACK_LINES,
    private val refreshRate: Int = MAX_REFRESH_RATE,
    font: java.awt.Font = TerminalTypography.defaultFont(),
    colorScheme: TerminalColorScheme = TerminalColorSchemes.DEFAULT,
) : DefaultSettingsProvider() {
    /** Mutable so the Settings dialog can change the size of a live terminal. */
    var font: java.awt.Font = font

    /** Mutable so the Settings dialog can recolor a live terminal. */
    var colorScheme: TerminalColorScheme = colorScheme

    override fun audibleBell(): Boolean = false

    override fun getTerminalFont(): java.awt.Font = font

    override fun getTerminalFontSize(): Float = font.size2D

    override fun getBufferMaxLinesCount(): Int = scrollbackLines

    override fun maxRefreshRate(): Int = refreshRate

    /**
     * The palette is re-read on every paint by [TerminalPanel], so returning a fresh instance for
     * the current scheme is what makes a live color change take effect.
     */
    override fun getTerminalColorPalette(): ColorPalette = SchemeColorPalette(colorScheme)

    override fun getDefaultForeground(): TerminalColor = TerminalColor(TerminalRgb(colorScheme.foregroundRgb))

    override fun getDefaultBackground(): TerminalColor = TerminalColor(TerminalRgb(colorScheme.backgroundRgb))

    /** Default style for SGR-default cells; [EyeShellTerminalWidget] pushes it into the live `StyleState`. */
    fun defaultStyle(): TextStyle = TextStyle(getDefaultForeground(), getDefaultBackground())

    internal fun terminalColorPaletteRgb(): List<Int> = colorScheme.ansiRgb

    companion object {
        internal const val MAX_SCROLLBACK_LINES = 100_000
        internal const val MAX_REFRESH_RATE = 50
    }
}

/** Maps a bundled scheme's 16 ANSI colors onto JediTerm's palette adapter. */
private class SchemeColorPalette(scheme: TerminalColorScheme) : ColorPalette() {
    private val colors = scheme.ansiRgb.map(::TerminalRgb)

    override fun getForegroundByColorIndex(colorIndex: Int): TerminalRgb = colors[colorIndex]

    override fun getBackgroundByColorIndex(colorIndex: Int): TerminalRgb = colors[colorIndex]
}

private fun MainBufferSnapshot.writeLogicalLines(writer: Writer) {
    fun writeLine(line: TerminalLineSnapshot) {
        line.text.forEach { character ->
            if (character != CharUtils.DWC) writer.write(character.code)
        }
        if (!line.isWrapped) writer.write("\n")
    }

    historyLines.forEach(::writeLine)
    val lastScreenLine = (screenLines.size - 1 downTo 0)
        .firstOrNull { !screenLines[it].isNulOrEmpty }
    if (lastScreenLine != null) {
        for (index in 0..lastScreenLine) writeLine(screenLines[index])
    }
}
