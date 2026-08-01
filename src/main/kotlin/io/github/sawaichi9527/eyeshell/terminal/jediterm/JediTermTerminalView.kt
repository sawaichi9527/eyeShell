package io.github.sawaichi9527.eyeshell.terminal.jediterm

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.model.MainBufferSnapshot
import com.jediterm.terminal.model.TerminalLineSnapshot
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.TerminalAction
import com.jediterm.terminal.ui.TerminalActionPresentation
import com.jediterm.terminal.ui.TerminalActionProvider
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.jediterm.terminal.util.CharUtils
import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalContextActions
import io.github.sawaichi9527.eyeshell.terminal.TerminalOutputSnapshot
import io.github.sawaichi9527.eyeshell.terminal.TerminalView
import java.io.Writer
import javax.swing.JComponent
import javax.swing.SwingUtilities

class JediTermTerminalView(
    columns: Int = 80,
    rows: Int = 24,
) : TerminalView {
    private val widget = JediTermWidget(columns, rows, EyeShellTerminalSettings())
    private val searchController = JediTermSearchController(widget)
    private val contextActionProvider = ContextActionProvider(widget, searchController::show)

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
        widget.setTtyConnector(SessionTtyConnector(session))
        widget.start()
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

    internal val historyLineCount: Int
        get() = widget.terminalTextBuffer.historyLinesCount

    internal val contextActions: List<TerminalAction>
        get() = contextActionProvider.getActions()

    internal val searchComponent: JComponent
        get() = searchController.component

    internal val selection
        get() = widget.terminalPanel.selection

    internal val isRetainedMainSelection: Boolean
        get() = widget.terminalPanel.isRetainedMainSelection

    internal val coordinateSearchResult
        get() = widget.terminalPanel.coordinateFindResult

    override fun close() {
        searchController.close()
        widget.close()
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
        action("Copy", enabled = widget.terminalPanel::canCopyCurrentSelection) {
            widget.terminalPanel.copyCurrentSelection()
        },
        action("Paste", enabled = { hasSession && widget.isSessionRunning }) {
            if (widget.isSessionRunning) widget.terminalPanel.pasteClipboard()
        },
        action("Select Visible", separated = true, enabled = { hasSession }) {
            widget.terminalPanel.selectVisible()
        },
        action("Select All Output", enabled = { hasSession }) {
            widget.terminalPanel.selectAllMainOutput()
        },
        action("Copy All Output", enabled = { hasSession && actions != null }) {
            actions?.copyAllOutput?.invoke()
        },
        action("Save All Output...", enabled = { hasSession && actions != null }) {
            actions?.saveAllOutput?.invoke()
        },
        action("Search...", separated = true, enabled = { hasSession }) {
            showSearch()
        },
        action("Add Color Highlight Rule...", enabled = { actions?.addHighlightRule != null }) {
            actions?.addHighlightRule?.invoke()
        },
        action("Manage Color Highlight Rules...", enabled = { actions?.manageHighlightRules != null }) {
            actions?.manageHighlightRules?.invoke()
        },
        action("Clear Buffer", separated = true, enabled = {
            hasSession && !widget.terminalTextBuffer.isUsingAlternateBuffer
        }) {
            if (!widget.terminalTextBuffer.isUsingAlternateBuffer) widget.terminalTextBuffer.clearHistory()
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
    override fun read(buffer: CharArray, offset: Int, length: Int): Int = session.read(buffer, offset, length)

    override fun write(bytes: ByteArray) = session.write(bytes)

    override fun write(string: String) = session.write(string)

    override fun isConnected(): Boolean = session.isOpen

    override fun resize(termSize: TermSize) = session.resize(termSize.columns, termSize.rows)

    override fun waitFor(): Int = session.awaitExit()

    override fun ready(): Boolean = session.ready()

    override fun getName(): String = session.name

    override fun close() = session.close()
}

private class EyeShellTerminalSettings : DefaultSettingsProvider() {
    override fun audibleBell(): Boolean = false
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
