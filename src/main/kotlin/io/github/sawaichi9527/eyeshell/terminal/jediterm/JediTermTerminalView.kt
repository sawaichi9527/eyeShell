package io.github.sawaichi9527.eyeshell.terminal.jediterm

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.model.TerminalLine
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.jediterm.terminal.util.CharUtils
import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalView
import java.io.Writer
import javax.swing.JComponent
import javax.swing.SwingUtilities

class JediTermTerminalView(
    columns: Int = 80,
    rows: Int = 24,
) : TerminalView {
    private val widget = JediTermWidget(columns, rows, EyeShellTerminalSettings())

    override val component: JComponent
        get() = widget

    override fun attach(session: TerminalSession) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal sessions must be attached on the Swing EDT" }
        check(widget.canOpenSession()) { "A terminal session is already attached" }
        widget.setTtyConnector(SessionTtyConnector(session))
        widget.start()
    }

    override fun writeAllOutput(writer: Writer) {
        check(!SwingUtilities.isEventDispatchThread()) { "Terminal output must be exported off the Swing EDT" }
        check(!widget.terminalTextBuffer.isUsingAlternateBuffer) {
            "Main-buffer export is unavailable while the alternate screen is active"
        }
        widget.terminalTextBuffer.writeLogicalLines(writer)
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

    override fun close() {
        widget.close()
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

private fun TerminalTextBuffer.writeLogicalLines(writer: Writer) {
    lock()
    try {
        fun writeLine(line: TerminalLine) {
            line.text.forEach { character ->
                if (character != CharUtils.DWC) writer.write(character.code)
            }
            if (!line.isWrapped) writer.write("\n")
        }

        historyLinesStorage.forEach(::writeLine)
        val lastScreenLine = (screenLinesStorage.size - 1 downTo 0)
            .firstOrNull { !screenLinesStorage[it].isNulOrEmpty }
        if (lastScreenLine != null) {
            for (index in 0..lastScreenLine) writeLine(screenLinesStorage[index])
        }
    } finally {
        unlock()
    }
}
