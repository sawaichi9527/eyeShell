package io.github.sawaichi9527.eyeshell

import com.formdev.flatlaf.FlatDarkLaf
import io.github.sawaichi9527.eyeshell.terminal.jediterm.JediTermTerminalView
import io.github.sawaichi9527.eyeshell.ui.EyeShellWindow
import io.github.sawaichi9527.eyeshell.ui.SshConnectionController
import javax.swing.SwingUtilities

fun main() {
    FlatDarkLaf.setup()
    SwingUtilities.invokeLater {
        val terminalView = JediTermTerminalView()
        val connectionController = SshConnectionController()
        EyeShellWindow(
            terminalView = terminalView,
            connectAction = connectionController::connect,
            closeAction = connectionController::close,
        ).isVisible = true
    }
}
