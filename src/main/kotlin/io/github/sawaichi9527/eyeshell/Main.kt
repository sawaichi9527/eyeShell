package io.github.sawaichi9527.eyeshell

import com.formdev.flatlaf.FlatDarkLaf
import io.github.sawaichi9527.eyeshell.terminal.jediterm.JediTermTerminalView
import io.github.sawaichi9527.eyeshell.platform.EyeShellPaths
import io.github.sawaichi9527.eyeshell.storage.SqliteHostCatalog
import io.github.sawaichi9527.eyeshell.ui.EyeShellWindow
import io.github.sawaichi9527.eyeshell.ui.HostCatalogController
import io.github.sawaichi9527.eyeshell.ui.SshConnectionController
import javax.swing.SwingUtilities

fun main() {
    FlatDarkLaf.setup()
    SwingUtilities.invokeLater {
        val terminalView = JediTermTerminalView()
        val connectionController = SshConnectionController()
        val hostCatalogController = HostCatalogController(
            SqliteHostCatalog(EyeShellPaths.catalogDatabaseFile()),
            connectionController::connect,
        )
        EyeShellWindow(
            terminalView = terminalView,
            connectAction = connectionController::connect,
            hostsAction = hostCatalogController::open,
            closeAction = {
                hostCatalogController.close()
                connectionController.close()
            },
        ).isVisible = true
    }
}
