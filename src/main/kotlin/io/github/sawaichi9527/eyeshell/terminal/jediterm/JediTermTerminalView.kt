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
import io.github.sawaichi9527.eyeshell.terminal.TerminalOutputActions
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
    private val outputActionProvider = OutputActionProvider().also(widget::setNextProvider)

    override val component: JComponent
        get() = widget

    override fun attach(session: TerminalSession) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal sessions must be attached on the Swing EDT" }
        check(widget.canOpenSession()) { "A terminal session is already attached" }
        outputActionProvider.hasSession = true
        widget.setTtyConnector(SessionTtyConnector(session))
        widget.start()
    }

    override fun captureAllOutput(): TerminalOutputSnapshot {
        check(!SwingUtilities.isEventDispatchThread()) { "Terminal output must be exported off the Swing EDT" }
        val snapshot = widget.terminalTextBuffer.getMainBufferSnapshot()
        return TerminalOutputSnapshot(snapshot::writeLogicalLines)
    }

    override fun setOutputActions(actions: TerminalOutputActions) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal output actions must be configured on the Swing EDT" }
        outputActionProvider.actions = actions
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

    internal val contextOutputActions: List<TerminalAction>
        get() = outputActionProvider.getActions()

    override fun close() {
        widget.close()
    }
}

private class OutputActionProvider : TerminalActionProvider {
    var actions: TerminalOutputActions? = null
    var hasSession: Boolean = false
    private var nextProvider: TerminalActionProvider? = null

    override fun getActions(): List<TerminalAction> = listOf(
        TerminalAction(TerminalActionPresentation("Copy All Output", emptyList())) {
            actions?.copyAll?.invoke()
            true
        }.withEnabledSupplier { hasSession && actions != null },
        TerminalAction(TerminalActionPresentation("Save All Output...", emptyList())) {
            actions?.saveAll?.invoke()
            true
        }.withEnabledSupplier { hasSession && actions != null },
    )

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
