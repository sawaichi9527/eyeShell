package io.github.sawaichi9527.eyeshell.terminal

import java.io.Writer
import javax.swing.JComponent

interface TerminalView : AutoCloseable {
    val component: JComponent

    fun attach(session: TerminalSession)

    fun captureAllOutput(): TerminalOutputSnapshot

    fun writeAllOutput(writer: Writer) = captureAllOutput().writeTo(writer)

    fun setContextActions(actions: TerminalContextActions)

    fun selectVisible()

    fun selectAllOutput()

    fun showSearch()

    fun clearScrollback()
}

fun interface TerminalOutputSnapshot {
    fun writeTo(writer: Writer)
}

data class TerminalContextActions(
    val copyAllOutput: () -> Unit,
    val saveAllOutput: () -> Unit,
    val addHighlightRule: (() -> Unit)? = null,
    val manageHighlightRules: (() -> Unit)? = null,
)
