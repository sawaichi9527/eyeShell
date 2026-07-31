package io.github.sawaichi9527.eyeshell

import com.formdev.flatlaf.FlatDarkLaf
import io.github.sawaichi9527.eyeshell.terminal.SyntheticTerminalSession
import io.github.sawaichi9527.eyeshell.terminal.jediterm.JediTermTerminalView
import io.github.sawaichi9527.eyeshell.ui.EyeShellWindow
import javax.swing.SwingUtilities

fun main() {
    FlatDarkLaf.setup()
    SwingUtilities.invokeLater {
        val terminalView = JediTermTerminalView()
        terminalView.attach(SyntheticTerminalSession.demo())
        EyeShellWindow(terminalView).isVisible = true
    }
}
