package io.github.sawaichi9527.eyeshell

import com.formdev.flatlaf.FlatDarkLaf
import io.github.sawaichi9527.eyeshell.terminal.jediterm.JediTermTerminalView
import io.github.sawaichi9527.eyeshell.platform.EyeShellPaths
import io.github.sawaichi9527.eyeshell.secrets.SystemPasswordCredentialStore
import io.github.sawaichi9527.eyeshell.secrets.ProfileCredentialGuard
import io.github.sawaichi9527.eyeshell.storage.SqliteHostCatalog
import io.github.sawaichi9527.eyeshell.ui.EyeShellWindow
import io.github.sawaichi9527.eyeshell.ui.HostCatalogController
import io.github.sawaichi9527.eyeshell.ui.SshConnectionController
import javax.swing.SwingUtilities

fun main() {
    FlatDarkLaf.setup()
    SwingUtilities.invokeLater {
        val terminalView = JediTermTerminalView()
        val passwordStore = SystemPasswordCredentialStore.create()
        val credentialGuard = ProfileCredentialGuard()
        val connectionController = SshConnectionController(passwordStore, credentialGuard = credentialGuard)
        val hostCatalogController = HostCatalogController(
            SqliteHostCatalog(EyeShellPaths.catalogDatabaseFile()),
            passwordStore,
            credentialGuard,
            connectionController::connect,
        )
        EyeShellWindow(
            terminalView = terminalView,
            connectAction = connectionController::connect,
            hostsAction = hostCatalogController::open,
            closeAction = {
                hostCatalogController.close()
                connectionController.close()
                passwordStore.close()
            },
        ).isVisible = true
    }
}
