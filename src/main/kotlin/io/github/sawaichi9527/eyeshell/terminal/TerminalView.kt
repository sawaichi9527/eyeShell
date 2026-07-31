package io.github.sawaichi9527.eyeshell.terminal

import java.io.Writer
import javax.swing.JComponent

interface TerminalView : AutoCloseable {
    val component: JComponent

    fun attach(session: TerminalSession)

    fun writeAllOutput(writer: Writer)

    fun clearScrollback()
}
