package io.github.sawaichi9527.eyeshell

import com.formdev.flatlaf.FlatDarkLaf
import io.github.sawaichi9527.eyeshell.terminal.jediterm.EyeShellTerminalSettings
import io.github.sawaichi9527.eyeshell.terminal.jediterm.JediTermTerminalView
import io.github.sawaichi9527.eyeshell.platform.DesktopSession
import io.github.sawaichi9527.eyeshell.platform.EyeShellPaths
import io.github.sawaichi9527.eyeshell.platform.LaunchStrategy
import io.github.sawaichi9527.eyeshell.platform.LoggingSetup
import io.github.sawaichi9527.eyeshell.platform.SafeMode
import io.github.sawaichi9527.eyeshell.platform.ShutdownNoiseFilter
import io.github.sawaichi9527.eyeshell.platform.AppSettings
import io.github.sawaichi9527.eyeshell.platform.BackgroundImages
import io.github.sawaichi9527.eyeshell.platform.I18n
import io.github.sawaichi9527.eyeshell.terminal.TerminalTypography
import io.github.sawaichi9527.eyeshell.terminal.TerminalColorSchemes
import io.github.sawaichi9527.eyeshell.secrets.SystemPasswordCredentialStore
import io.github.sawaichi9527.eyeshell.secrets.ProfileCredentialGuard
import io.github.sawaichi9527.eyeshell.secrets.SessionPasswordCache
import io.github.sawaichi9527.eyeshell.storage.SqliteHostCatalog
import io.github.sawaichi9527.eyeshell.ui.CommandsController
import io.github.sawaichi9527.eyeshell.ui.EyeShellWindow
import io.github.sawaichi9527.eyeshell.ui.HostCatalogController
import io.github.sawaichi9527.eyeshell.ui.SshConnectionController
import javax.swing.SwingUtilities

fun main(arguments: Array<String>) {
    LoggingSetup.apply()
    ShutdownNoiseFilter.install()
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
        val sessionPasswords = SessionPasswordCache()
        val connectionController = SshConnectionController(
            passwordStore,
            credentialGuard = credentialGuard,
            sessionPasswords = sessionPasswords,
        )
        val catalog = SqliteHostCatalog(EyeShellPaths.catalogDatabaseFile())
        val appSettings = AppSettings.load(EyeShellPaths.settingsFile())
        I18n.setLocaleId(appSettings.localeId)
        applyDefaultBackground(appSettings)
        val commandsController = CommandsController(catalog)
        val hostCatalogController = HostCatalogController(
            catalog,
            passwordStore,
            credentialGuard,
            sessionPasswords,
            { owner, preset -> connectionController.connect(owner, preset) },
        )
        EyeShellWindow(
            terminalViewFactory = {
                JediTermTerminalView(
                    scrollbackLines = safeMode.scrollbackLines,
                    maxRefreshRate = safeMode.maxRefreshRate,
                    font = TerminalTypography.resolveFont(size = appSettings.terminalFontSize.toFloat()),
                    colorScheme = TerminalColorSchemes.fromId(appSettings.terminalColorSchemeId),
                )
            },
            connectAction = { owner -> connectionController.connect(owner) },
            hostsAction = hostCatalogController::open,
            commandsController = commandsController,
            appSettings = appSettings,
            closeAction = {
                commandsController.close()
                hostCatalogController.close()
                connectionController.close()
                passwordStore.close()
            },
        ).isVisible = true
    }
}

/**
 * Turns on the bundled `bg1` preset for a fresh profile, and restores a bundled preset whose file was
 * deleted. A stored image path (including the empty string written by `Clear`) is otherwise
 * respected, so this never overrides a deliberate choice.
 */
internal fun applyDefaultBackground(
    settings: AppSettings,
    directory: java.nio.file.Path = BackgroundImages.defaultDirectory(),
) {
    // Keep the bundled presets on disk so the Settings list always shows bg1–bg5.
    BackgroundImages.materializePresets(directory)
    if (!settings.terminalBackgroundEnabled) return
    val stored = settings.terminalBackgroundImage
    if (stored.isBlank()) {
        val default = BackgroundImages.ensureDefaultPreset(directory) ?: return
        settings.terminalBackgroundImage = default.toString()
        return
    }
    val resolved = BackgroundImages.resolveStored(stored, directory) ?: return
    if (resolved.toString() != stored) settings.terminalBackgroundImage = resolved.toString()
}
