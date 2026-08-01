package io.github.sawaichi9527.eyeshell.terminal

import java.io.Writer
import javax.swing.JComponent

interface TerminalView : AutoCloseable {
    val component: JComponent

    fun attach(session: TerminalSession)

    fun captureAllOutput(): TerminalOutputSnapshot

    fun writeAllOutput(writer: Writer) = captureAllOutput().writeTo(writer)

    fun setOutputActions(actions: TerminalOutputActions)

    fun clearScrollback()
}

fun interface TerminalOutputSnapshot {
    fun writeTo(writer: Writer)
}

data class TerminalOutputActions(
    val copyAll: () -> Unit,
    val saveAll: () -> Unit,
)
