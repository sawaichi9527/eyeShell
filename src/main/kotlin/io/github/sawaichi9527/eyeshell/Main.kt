package io.github.sawaichi9527.eyeshell

import com.formdev.flatlaf.FlatDarkLaf
import io.github.sawaichi9527.eyeshell.terminal.jediterm.EyeShellTerminalSettings
import io.github.sawaichi9527.eyeshell.terminal.jediterm.JediTermTerminalView
import io.github.sawaichi9527.eyeshell.platform.DesktopSession
import io.github.sawaichi9527.eyeshell.platform.EyeShellPaths
import io.github.sawaichi9527.eyeshell.platform.LaunchStrategy
import io.github.sawaichi9527.eyeshell.platform.SafeMode
import io.github.sawaichi9527.eyeshell.secrets.SystemPasswordCredentialStore
import io.github.sawaichi9527.eyeshell.secrets.ProfileCredentialGuard
import io.github.sawaichi9527.eyeshell.storage.SqliteHostCatalog
import io.github.sawaichi9527.eyeshell.ui.EyeShellWindow
import io.github.sawaichi9527.eyeshell.ui.HostCatalogController
import io.github.sawaichi9527.eyeshell.ui.SshConnectionController
import javax.swing.SwingUtilities

fun main(arguments: Array<String>) {
    val safeMode = SafeMode.detect(
        arguments,
        EyeShellTerminalSettings.MAX_SCROLLBACK_LINES,
        EyeShellTerminalSettings.MAX_REFRESH_RATE,
    )
    val launchStrategy = LaunchStrategy.resolve(
        osName = System.getProperty("os.name"),
        session = DesktopSession.detect(
            xdgSessionType = System.getenv("XDG_SESSION_TYPE"),
            waylandDisplay = System.getenv("WAYLAND_DISPLAY"),
            display = System.getenv("DISPLAY"),
        ),
        forceX11 = safeMode.isActive,
        waylandToolkitSupported = LaunchStrategy.isNativeWaylandToolkitAvailable(),
    )
    launchStrategy.systemProperties().forEach { (name, value) -> System.setProperty(name, value) }
    safeMode.systemProperties().forEach { (name, value) -> System.setProperty(name, value) }
    if (safeMode.isActive) System.setProperty(SafeMode.ANIMATION_PROPERTY, "false")
    FlatDarkLaf.setup()
    SwingUtilities.invokeLater {
        val passwordStore = SystemPasswordCredentialStore.create()
        val credentialGuard = ProfileCredentialGuard()
        val connectionController = SshConnectionController(passwordStore, credentialGuard = credentialGuard)
        val hostCatalogController = HostCatalogController(
            SqliteHostCatalog(EyeShellPaths.catalogDatabaseFile()),
            passwordStore,
            credentialGuard,
            { owner, preset -> connectionController.connect(owner, preset) },
        )
        EyeShellWindow(
            terminalViewFactory = {
                JediTermTerminalView(
                    scrollbackLines = safeMode.scrollbackLines,
                    maxRefreshRate = safeMode.maxRefreshRate,
                )
            },
            connectAction = { owner -> connectionController.connect(owner) },
            hostsAction = hostCatalogController::open,
            closeAction = {
                hostCatalogController.close()
                connectionController.close()
                passwordStore.close()
            },
        ).isVisible = true
    }
}
