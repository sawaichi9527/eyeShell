package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.monitor.MonitorSampler
import io.github.sawaichi9527.eyeshell.monitor.MonitorSnapshot
import io.github.sawaichi9527.eyeshell.platform.AppInfo
import io.github.sawaichi9527.eyeshell.platform.AppSettings
import io.github.sawaichi9527.eyeshell.platform.AppIcons
import io.github.sawaichi9527.eyeshell.platform.I18n
import io.github.sawaichi9527.eyeshell.platform.WindowPlacement
import io.github.sawaichi9527.eyeshell.platform.resolveWindowPlacement
import io.github.sawaichi9527.eyeshell.platform.screenWorkAreas
import io.github.sawaichi9527.eyeshell.ssh.HostSession
import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import io.github.sawaichi9527.eyeshell.terminal.ReplayTerminalSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalBackgroundImage
import io.github.sawaichi9527.eyeshell.terminal.TerminalCharsets
import io.github.sawaichi9527.eyeshell.terminal.TerminalColorScheme
import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalView
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Frame
import java.awt.FlowLayout
import java.awt.Insets
import java.awt.KeyboardFocusManager
import java.awt.KeyEventDispatcher
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

enum class SessionStatus(
    val labelKey: String,
    val color: Color,
) {
    CONNECTED("sessionStatus.connected", Color(0x4CAF50)),
    EXITED("sessionStatus.exited", Color(0x9E9E9E)),
    FAILED("sessionStatus.failed", Color(0xF44336)),
    CLOSING("sessionStatus.closing", Color(0xFF9800)),
}

class EyeShellWindow(
    private val terminalViewFactory: () -> TerminalView,
    connectAction: ((EyeShellWindow) -> Unit)? = null,
    hostsAction: ((EyeShellWindow) -> Unit)? = null,
    commandsController: CommandsController? = null,
    private val closeAction: () -> Unit = {},
    private val appSettings: AppSettings = AppSettings.inMemory(),
    /** Captures the retained terminal bytes when a tab fails; opt-in, see `docs/DIAGNOSTICS.md`. */
    private val captureRawBytes: Boolean = false,
    /**
     * Asked before the window closes while sessions are open. The default implementation shows a
     * localized confirmation; tests inject a stub so they never open a modal dialog.
     */
    private val confirmClose: ((sessionCount: Int) -> Boolean)? = null,
) : JFrame(AppInfo.windowTitle) {
    private val closed = AtomicBoolean()
    private val pagesByComponent = mutableMapOf<JComponent, TerminalSessionPage>()
    private var lastNormalBounds: Rectangle? = null
    private var terminalCharset: Charset = TerminalCharsets.charsetFromId(appSettings.terminalCharsetId)
    private var terminalBackgroundImage: TerminalBackgroundImage? = TerminalBackgroundImage.fromSettings(
        enabled = appSettings.terminalBackgroundEnabled,
        storedPath = appSettings.terminalBackgroundImage,
        opacityPercent = appSettings.terminalBackgroundOpacity,
    )
    private val settingsController = SettingsController(
        appSettings,
        applyFontSize = { size -> applyTerminalFontSize(size) },
        applyFontFamilies = { latin, cjk -> applyTerminalFontFamilies(latin, cjk) },
        applyColorScheme = { scheme -> applyTerminalColorScheme(scheme) },
        applyCharset = { charset -> applyTerminalCharset(charset) },
        applyBackgroundImage = { image -> applyTerminalBackgroundImage(image) },
        applyLocale = { workbench.relocalize() },
    )
    private val workbench: WorkbenchPanel = WorkbenchPanel(
        connectAction = connectAction?.let { action -> { action(this) } },
        hostsAction = hostsAction?.let { action -> { action(this) } },
        commandsController = commandsController,
        monitorSelectionChanged = { component -> bindMonitorFor(component) },
        sftpSelectionChanged = { component -> bindSftpFor(component) },
        sessionSelectionChanged = { component -> pagesByComponent[component]?.requestTerminalFocus() },
        onOpenSettings = { settingsController.open(this) },
    )
    private var initialized = false

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        minimumSize = Dimension(WindowPlacement.MINIMUM_WIDTH, WindowPlacement.MINIMUM_HEIGHT)
        contentPane = workbench
        iconImages = AppIcons.images()
        restoreWindowPlacement()
        addComponentListener(object : ComponentAdapter() {
            override fun componentMoved(event: ComponentEvent) = rememberNormalBounds()
            override fun componentResized(event: ComponentEvent) = rememberNormalBounds()
        })
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent) {
                if (mayCloseWindow()) dispose()
            }
        })
        initialized = true
    }

    /**
     * Applies the stored geometry, or centers the default size on the primary screen. The physical
     * size is clamped to the current screens so a resolution change cannot restore an oversized or
     * unreachable window.
     */
    private fun restoreWindowPlacement() {
        val placement = resolveWindowPlacement(
            stored = appSettings.windowPlacement,
            screens = screenWorkAreas(),
            minimumSize = Dimension(WindowPlacement.MINIMUM_WIDTH, WindowPlacement.MINIMUM_HEIGHT),
        )
        bounds = placement.bounds
        lastNormalBounds = placement.bounds
        if (placement.maximized) {
            extendedState = extendedState or Frame.MAXIMIZED_BOTH
        }
    }

    /** Remembers the size to restore when the user later un-maximizes or closes a maximized window. */
    private fun rememberNormalBounds() {
        if (!isVisible) return
        if (extendedState and (Frame.MAXIMIZED_BOTH or Frame.ICONIFIED) != 0) return
        lastNormalBounds = bounds
    }

    private fun saveWindowPlacement() {
        val state = extendedState
        val maximized = state and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH
        val normal = if (maximized || state and Frame.ICONIFIED != 0) lastNormalBounds else bounds
        val target = normal ?: bounds
        if (target.width <= 0 || target.height <= 0) return
        appSettings.windowPlacement = WindowPlacement(
            target.x,
            target.y,
            target.width,
            target.height,
            maximized,
        )
    }

    /**
     * Closing with open session tabs asks first, because it disconnects every session and discards
     * their scrollback. An empty workbench closes immediately; the confirmation defaults to Cancel.
     */
    private fun mayCloseWindow(): Boolean {
        val count = workbench.sessionCount
        if (count == 0) return true
        return confirmClose?.invoke(count) ?: confirmClosingWithSessions(count)
    }

    private fun confirmClosingWithSessions(count: Int): Boolean {
        val options = arrayOf(I18n.t("window.close.confirm"), I18n.t("window.close.cancel"))
        return JOptionPane.showOptionDialog(
            this,
            I18n.t("window.close.message", count),
            I18n.t("window.close.title"),
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            options,
            options[1],
        ) == 0
    }

    internal fun attachTerminal(
        hostSession: HostSession,
        terminal: TerminalSession,
        preset: HostConnectionPreset? = null,
        reconnect: ((HostConnectionPreset, JComponent) -> Boolean)? = null,
    ) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal sessions must be attached on the Swing EDT" }
        terminal.setCharset(terminalCharset)
        val terminalView = terminalViewFactory()
        terminalView.setBackgroundImage(terminalBackgroundImage)
        val page = try {
            TerminalSessionPage(this, terminalView, hostSession, terminal, captureRawBytes)
        } catch (failure: Throwable) {
            try {
                terminalView.close()
            } catch (closeFailure: Throwable) {
                accumulateFailure(failure, closeFailure)
            }
            throw failure
        }
        val component = page.component
        try {
            page.attach(preset) { reconnectPreset ->
                reconnect != null && workbench.isSessionExited(component) && reconnect(reconnectPreset, component)
            }
            pagesByComponent[component] = page
            workbench.addSession(sessionTitle(preset, hostSession.endpoint), component, {
                pagesByComponent.remove(component)
                page.close()
            }, page::sendCommand, endpointLabel = hostSession.endpoint.displayName)
        } catch (failure: Throwable) {
            try {
                page.close()
            } catch (closeFailure: Throwable) {
                accumulateFailure(failure, closeFailure)
            }
            throw failure
        }
        page.startEmulatorMonitor {
            workbench.updateSessionStatus(component, SessionStatus.FAILED)
        }
        page.startExitMonitor {
            workbench.updateSessionStatus(component, page.exitStatus)
        }
    }

    internal fun attachTerminal(
        hostSession: HostSession,
        preset: HostConnectionPreset? = null,
        reconnect: ((HostConnectionPreset, JComponent) -> Boolean)? = null,
    ) {
        attachTerminal(hostSession, hostSession.openTerminal(), preset, reconnect)
    }

    /**
     * A Saved Host profile names the tab; an ad-hoc connection has no name and keeps the endpoint.
     * The name is dropped when the endpoint no longer matches the profile that was picked.
     */
    private fun sessionTitle(preset: HostConnectionPreset?, endpoint: SshEndpoint): String =
        preset?.displayName?.takeIf { preset.endpoint == endpoint } ?: endpoint.displayName

    /**
     * Replaces the SSH session of an existing terminal tab in place, so the JediTerm widget,
     * its scrollback, highlight rules, and selection state survive the reconnect.
     * Returns false when the page is gone or its terminal view is still busy; the caller then opens a new tab.
     */
    internal fun reconnectTerminal(
        component: JComponent,
        hostSession: HostSession,
        terminal: TerminalSession,
        preset: HostConnectionPreset,
    ): Boolean {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal sessions must reconnect on the Swing EDT" }
        val page = pagesByComponent[component] ?: return false
        terminal.setCharset(terminalCharset)
        if (!page.reconnect(hostSession, terminal, preset)) return false
        val title = sessionTitle(preset, hostSession.endpoint)
        workbench.updateSessionTitle(component, title, hostSession.endpoint.displayName)
        workbench.updateSessionStatus(component, SessionStatus.CONNECTED)
        workbench.setConnectionState(I18n.t("workbench.connectedTo", title), false)
        if (workbench.isSelectedSession(component)) {
            page.requestTerminalFocus()
            bindMonitorFor(component, resetFirst = true)
            bindSftpFor(component)
        }
        return true
    }

    private fun bindMonitorFor(component: Component, resetFirst: Boolean = false) {
        val page = pagesByComponent[component]
        if (page == null) {
            if (initialized) workbench.monitorIdle()
            return
        }
        if (resetFirst && initialized) workbench.monitorIdle()
        try {
            page.startMonitor { snapshot ->
                SwingUtilities.invokeLater {
                    if (!closed.get() && workbench.isSelectedSession(component)) {
                        workbench.setMonitorSnapshot(snapshot)
                    }
                }
            }
        } catch (_: Throwable) {
            if (initialized) workbench.monitorIdle()
        }
    }

    private fun bindSftpFor(component: Component?) {
        try {
            val page = component?.let(pagesByComponent::get)
            if (page == null) {
                if (initialized) workbench.bindSftp(null)
                return
            }
            val target = component
            workbench.showSftpLoading()
            page.loadSftp { controller, failure ->
                if (closed.get() || !workbench.isSelectedSession(target)) return@loadSftp
                when {
                    controller != null -> workbench.bindSftp(controller)
                    failure != null -> workbench.bindSftpUnavailable(failure) {
                        page.resetSftp()
                        bindSftpFor(target)
                    }
                }
            }
        } catch (_: Throwable) {
            if (initialized) {
                try {
                    workbench.bindSftp(null)
                } catch (_: Throwable) {
                    // A broken SFTP panel must not break terminal attachment.
                }
            }
        }
    }

    fun setConnectionState(message: String, connecting: Boolean) {
        workbench.setConnectionState(message, connecting)
    }

    /** Applies the Settings font size to every open session; new sessions read it from the factory. */
    private fun applyTerminalFontSize(size: Float) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal font changes must happen on the Swing EDT" }
        pagesByComponent.values.toList().forEach { page -> page.applyFontSize(size) }
    }

    /** Applies the selected Latin/CJK font families to every open session. */
    private fun applyTerminalFontFamilies(latin: String, cjk: String) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal font changes must happen on the Swing EDT" }
        pagesByComponent.values.toList().forEach { page -> page.applyFontFamilies(latin, cjk) }
    }

    /** Applies the Settings color scheme to every open session; new sessions read it from the factory. */
    internal fun applyTerminalColorScheme(scheme: TerminalColorScheme) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal color changes must happen on the Swing EDT" }
        pagesByComponent.values.toList().forEach { page -> page.applyColorScheme(scheme) }
    }

    /**
     * Applies the Settings encoding to every open session and remembers it for new ones. Existing
     * output keeps its old decoding; only bytes not yet decoded are reinterpreted (§9.6).
     */
    internal fun applyTerminalCharset(charset: Charset) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal encoding changes must happen on the Swing EDT" }
        terminalCharset = charset
        pagesByComponent.values.toList().forEach { page -> page.applyCharset(charset) }
    }

    /**
     * Applies the Settings background image to every open session and remembers it for new ones.
     * A `null` image restores the plain scheme background (§9.5).
     */
    internal fun applyTerminalBackgroundImage(image: TerminalBackgroundImage?) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal background changes must happen on the Swing EDT" }
        terminalBackgroundImage = image
        pagesByComponent.values.toList().forEach { page -> page.applyBackgroundImage(image) }
    }

    override fun dispose() {
        if (!closed.compareAndSet(false, true)) return
        windowLogger.info("eyeShell window closing")
        saveWindowPlacement()
        var failure: Throwable? = null
        try {
            settingsController.close()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            workbench.closeSessions()
        } catch (error: Throwable) {
            failure = accumulateFailure(failure, error)
        }
        try {
            closeAction()
        } catch (error: Throwable) {
            failure = accumulateFailure(failure, error)
        }
        try {
            super.dispose()
        } catch (error: Throwable) {
            failure = accumulateFailure(failure, error)
        }
        failure?.let { throw it }
    }

    private companion object {
        val windowLogger: java.util.logging.Logger =
            java.util.logging.Logger.getLogger(EyeShellWindow::class.java.name)
    }
}

internal class TerminalSessionPage(
    private val owner: Component,
    private val terminalView: TerminalView,
    hostSession: HostSession,
    session: TerminalSession = hostSession.openTerminal(),
    private val captureRawBytes: Boolean = false,
) : AutoCloseable {
    private var hostSession: HostSession = hostSession
    private var session: TerminalSession = session
    private val closed = AtomicBoolean()
    private val monitorStarted = AtomicBoolean()
    private val exitGeneration = AtomicInteger()
    private var exitCallback: (() -> Unit)? = null
    private val outputController = TerminalOutputController(terminalView)
    private val highlightController = TerminalHighlightController(terminalView)
    private var monitorSampler = MonitorSampler(hostSession)
    private val sftpLock = Any()
    private var sftpLoading = false
    private var sftpController: io.github.sawaichi9527.eyeshell.sftp.SftpController? = null
    private var sftpFailure: String? = null
    private var reconnectDispatcher: KeyEventDispatcher? = null
    private var reconnectPreset: HostConnectionPreset? = null
    private var reconnectRequest: ((HostConnectionPreset) -> Boolean)? = null
    private val redecodeLock = Any()

    /**
     * Set when the terminal emulator stopped while the session was still open, which is the case
     * that used to leave a tab looking connected but frozen forever.
     */
    private val emulatorFailed = AtomicBoolean()
    private var emulatorStopCallback: (() -> Unit)? = null

    init {
        terminalView.setEmulatorStopHandler(::onEmulatorStopped)
    }

    val hasEmulatorFailed: Boolean
        get() = emulatorFailed.get()

    /** Status to publish when the session ends: an emulator crash is a failure, not a clean exit. */
    val exitStatus: SessionStatus
        get() = if (emulatorFailed.get()) SessionStatus.FAILED else SessionStatus.EXITED

    val component: JComponent = JPanel(BorderLayout()).apply {
        name = "terminalWorkspace"
        getAccessibleContext().accessibleName = "Terminal workspace"
        add(terminalView.component, BorderLayout.CENTER)
    }

    /**
     * Reports an emulator that stopped without reaching the end of the stream. The handler runs on
     * the emulator thread, so the callback is delivered on the EDT like every other status update.
     */
    private fun onEmulatorStopped(unexpected: Boolean) {
        if (!unexpected || closed.get()) return
        emulatorFailed.set(true)
        // Closing the channel is what makes a keystroke in a frozen tab harmless instead of a
        // command the user cannot see; it also unblocks a reader stuck in a channel read.
        runCatching { session.close() }
        if (captureRawBytes) captureTerminalBytes()
        val callback = emulatorStopCallback ?: return
        SwingUtilities.invokeLater {
            if (!closed.get()) callback()
        }
    }

    /**
     * Writes the tail of the retained raw output next to the log. The bytes are part of the terminal
     * stream, so this only runs when the user opted in with `--capture-terminal-bytes`.
     */
    private fun captureTerminalBytes() {
        val captured = runCatching { session.rawOutput() }.getOrNull() ?: return
        if (captured.isEmpty()) return
        val name = session.name
        val tail = if (captured.size > CAPTURE_LIMIT_BYTES) {
            captured.copyOfRange(captured.size - CAPTURE_LIMIT_BYTES, captured.size)
        } else {
            captured
        }
        Thread.ofVirtual().name("eyeshell-terminal-capture").start {
            runCatching {
                val directory = io.github.sawaichi9527.eyeshell.platform.EyeShellPaths.logDirectory()
                java.nio.file.Files.createDirectories(directory)
                val file = directory.resolve(
                    "terminal-${io.github.sawaichi9527.eyeshell.platform.Diagnostics.sanitize(name)}-" +
                        "${io.github.sawaichi9527.eyeshell.platform.Diagnostics.timestamp()}.bin",
                )
                java.nio.file.Files.write(file, tail)
                logger.warning("Wrote ${tail.size} bytes of terminal output to $file")
            }.onFailure { failure ->
                logger.log(java.util.logging.Level.WARNING, "Could not capture terminal output", failure)
            }
        }
    }

    fun startEmulatorMonitor(onUnexpectedStop: () -> Unit) {
        emulatorStopCallback = onUnexpectedStop
    }

    fun applyFontSize(size: Float) = terminalView.setFontSize(size)

    /**
     * Moves keyboard focus into the terminal grid.
     *
     * Adding or selecting a tab does not move focus by itself in Swing, so a freshly connected
     * session used to ignore typing until the user clicked it. Called on attach, reconnect, and
     * every tab selection change; the deferred attempt covers a tab that is still being laid out.
     */
    fun requestTerminalFocus() {
        SwingUtilities.invokeLater {
            if (closed.get()) return@invokeLater
            if (!terminalView.component.requestFocusInWindow()) {
                SwingUtilities.invokeLater {
                    if (!closed.get()) terminalView.component.requestFocusInWindow()
                }
            }
        }
    }

    fun applyFontFamilies(latin: String, cjk: String) = terminalView.setFontFamilies(latin, cjk)

    fun applyColorScheme(scheme: TerminalColorScheme) = terminalView.setColorScheme(scheme)

    /** Switches the encoding of the live session; the view only sees already decoded characters. */
    fun applyCharset(charset: Charset) {
        if (session.charset == charset) return
        session.setCharset(charset)

        // Without retained raw bytes only new output follows the new encoding.
        val raw = runCatching { session.rawOutput() }.getOrNull() ?: return
        if (raw.isEmpty()) return

        Thread.ofVirtual().name("eyeshell-redecode").start {
            val prefix = REDECODE_RESET + String(raw, charset)
            // Serialize replays so two quick Settings changes cannot attach at the same time; each
            // run replays the bytes it snapshotted, and the last one wins.
            synchronized(redecodeLock) {
                if (closed.get()) return@start
                if (!terminalView.detachSession()) {
                    logger.warning("Could not detach the terminal for re-decoding; new output uses $charset")
                    return@start
                }
                runCatching {
                    SwingUtilities.invokeAndWait {
                        if (!closed.get()) {
                            terminalView.attach(ReplayTerminalSession(prefix.toCharArray(), session))
                        }
                    }
                }.onFailure { failure ->
                    logger.log(java.util.logging.Level.WARNING, "Could not replay re-decoded output", failure)
                }
            }
        }
    }

    /** Paints or clears the background image without recreating the view. */
    fun applyBackgroundImage(image: TerminalBackgroundImage?) = terminalView.setBackgroundImage(image)

    /**
     * Opens the SFTP subsystem off the EDT and reports either the controller or the reason it
     * could not be opened. The outcome is cached: switching tabs does not retry, and [resetSftp]
     * clears a cached failure for an explicit retry.
     */
    fun loadSftp(onResult: (controller: io.github.sawaichi9527.eyeshell.sftp.SftpController?, failure: String?) -> Unit) {
        check(SwingUtilities.isEventDispatchThread()) { "SFTP binding must start on the Swing EDT" }
        val host = synchronized(sftpLock) {
            sftpController?.let {
                onResult(it, null)
                return
            }
            sftpFailure?.let {
                onResult(null, it)
                return
            }
            if (sftpLoading) return
            sftpLoading = true
            hostSession
        }
        Thread.ofVirtual().name("sftp-open").start {
            val outcome = runCatching {
                io.github.sawaichi9527.eyeshell.sftp.SftpController(
                    host.sftp(),
                    ownerNames = io.github.sawaichi9527.eyeshell.sftp.OwnerNameResolver {
                        host.execute(io.github.sawaichi9527.eyeshell.sftp.OwnerNames.COMMAND).output
                    },
                )
            }
            synchronized(sftpLock) { sftpLoading = false }
            val controller = outcome.getOrNull()
            val failure = outcome.exceptionOrNull()?.let(::describeFailureChain)
            if (controller != null) {
                val keep = synchronized(sftpLock) {
                    if (closed.get()) false else true.also { sftpController = controller }
                }
                if (!keep) {
                    runCatching { controller.close() }
                    return@start
                }
            } else {
                synchronized(sftpLock) { sftpFailure = failure }
            }
            SwingUtilities.invokeLater {
                if (!closed.get()) onResult(controller, failure)
            }
        }
    }

    /** Clears a cached SFTP failure so the next [loadSftp] opens the subsystem again. */
    fun resetSftp() {
        check(SwingUtilities.isEventDispatchThread()) { "SFTP binding must reset on the Swing EDT" }
        val previous = synchronized(sftpLock) {
            sftpFailure = null
            sftpController.also { sftpController = null }
        }
        runCatching { previous?.close() }
    }

    fun attach(
        preset: HostConnectionPreset? = null,
        requestReconnect: ((HostConnectionPreset) -> Boolean)? = null,
    ) {
        logger.info("Terminal session attached: ${session.name}")
        terminalView.attach(session)
        outputController.install(
            owner,
            addHighlightRule = { highlightController.addRule(owner) },
            manageHighlightRules = { highlightController.manageRules(owner) },
        )
        reconnectPreset = preset
        reconnectRequest = requestReconnect
        if (requestReconnect != null) installReconnectOnEnter()
    }

    /**
     * Swaps in a freshly opened SSH session without touching the terminal view, so the retained
     * scrollback, highlight rules, and context actions belong to the tab for its whole lifetime.
     * The caller keeps ownership of the new session until this returns true.
     */
    fun reconnect(
        newHostSession: HostSession,
        newSession: TerminalSession,
        newPreset: HostConnectionPreset,
    ): Boolean {
        if (closed.get() || !terminalView.canAttach()) return false
        terminalView.resetForReattach()
        terminalView.attach(newSession)
        emulatorFailed.set(false)
        logger.info("Terminal session reconnected in place: ${newSession.name}")

        exitGeneration.incrementAndGet()
        monitorStarted.set(false)
        monitorSampler.close()
        synchronized(sftpLock) {
            sftpController?.close()
            sftpController = null
            sftpFailure = null
            sftpLoading = false
        }

        val previousSession = session
        val previousHostSession = hostSession
        session = newSession
        hostSession = newHostSession
        monitorSampler = MonitorSampler(newHostSession)
        reconnectPreset = newPreset

        // The new session is already attached to the view, so a failure to release the replaced
        // connection must not be reported as a failed reconnect; it only leaks the old transport.
        runCatching { previousSession.close() }
        runCatching { previousHostSession.close() }

        armExitMonitor()
        return true
    }

    fun startMonitor(onSnapshot: (MonitorSnapshot) -> Unit): Boolean {
        if (!monitorStarted.compareAndSet(false, true)) return false
        return try {
            monitorSampler.start(onSnapshot)
            true
        } catch (_: Throwable) {
            monitorSampler.close()
            false
        }
    }

    fun stopMonitor() {
        monitorSampler.close()
    }

    fun sendCommand(command: String) {
        check(!closed.get()) { "Terminal session is closed" }
        session.write(command.trimEnd('\r', '\n') + "\r")
    }

    fun startExitMonitor(onExit: () -> Unit) {
        if (exitCallback != null) return
        exitCallback = onExit
        armExitMonitor()
    }

    private fun armExitMonitor() {
        val callback = exitCallback ?: return
        val watched = session
        val generation = exitGeneration.incrementAndGet()
        Thread.ofVirtual().name("terminal-exit-${watched.name}").start {
            val status = watched.awaitExit()
            if (closed.get() || exitGeneration.get() != generation) return@start
            val failure = emulatorFailed.get()
            logger.info(
                "Terminal session ended: ${watched.name} exitStatus=$status " +
                    "outcome=${if (failure) "failed" else "closed"}",
            )
            SwingUtilities.invokeLater {
                if (!closed.get() && exitGeneration.get() == generation) callback()
            }
        }
    }

    /**
     * Enter reconnects only while the tab shows an exited session. The dispatcher stays installed for
     * the lifetime of the page, so a cancelled or failed attempt can simply be retried; it is removed
     * when the tab closes.
     */
    private fun installReconnectOnEnter() {
        if (reconnectDispatcher != null) return
        val dispatcher = KeyEventDispatcher { event ->
            val source = event.component
            val matches = event.id == KeyEvent.KEY_PRESSED &&
                event.keyCode == KeyEvent.VK_ENTER &&
                source != null &&
                SwingUtilities.isDescendingFrom(source, component)
            val preset = reconnectPreset
            val request = reconnectRequest
            matches && preset != null && request != null && request(preset)
        }
        reconnectDispatcher = dispatcher
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
    }

    private fun removeReconnectOnEnter() {
        reconnectDispatcher?.let {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it)
        }
        reconnectDispatcher = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        logger.info("Terminal session closed from the user interface: ${session.name} (emulatorFailed=$hasEmulatorFailed)")
        exitGeneration.incrementAndGet()
        removeReconnectOnEnter()
        monitorSampler.close()
        synchronized(sftpLock) { sftpController?.close() }
        outputController.close(terminalView::close)
        try {
            session.close()
        } finally {
            hostSession.close()
        }
    }

    private companion object {
        val logger: java.util.logging.Logger = java.util.logging.Logger.getLogger(TerminalSessionPage::class.java.name)

        /** Clears the visible screen and the scrollback before the re-decoded history replays. */
        const val REDECODE_RESET = "\u001B[3J\u001B[H"

        /** Enough to contain the sequence that broke the emulator, small enough to attach to a report. */
        const val CAPTURE_LIMIT_BYTES = 256 * 1024
    }
}

class WorkbenchPanel(
    connectAction: (() -> Unit)? = null,
    hostsAction: (() -> Unit)? = null,
    commandsController: CommandsController? = null,
    monitorSelectionChanged: ((Component) -> Unit)? = null,
    sftpSelectionChanged: ((Component?) -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    sessionSelectionChanged: ((Component) -> Unit)? = null,
) : JPanel(BorderLayout()) {
    private val canConnect = connectAction != null
    private val canOpenHosts = hostsAction != null
    private val sessionTabs = JTabbedPane(JTabbedPane.TOP).apply {
        name = "sessionTabs"
        getAccessibleContext().accessibleName = "Sessions"
        // A single row keeps the tab alignment stable and the terminal area from jumping when a
        // fifth or sixth session opens; overflowing tabs scroll instead of wrapping.
        tabLayoutPolicy = JTabbedPane.SCROLL_TAB_LAYOUT
    }
    private val sessions = linkedMapOf<Component, SessionTab>()
    private val emptyStateLabel = JLabel(I18n.t("workbench.empty"), SwingConstants.CENTER).apply {
        name = "emptyState"
        alignmentX = Component.LEFT_ALIGNMENT
    }
    private val emptySession = createEmptySession()
    private var connecting = false
    private val monitorPanel = MonitorPanel(onOpenSettings = onOpenSettings)
    private val onMonitorSelectionChanged = monitorSelectionChanged
    private val onSftpSelectionChanged = sftpSelectionChanged
    private val onSessionSelectionChanged = sessionSelectionChanged
    private val connectionStatus = JLabel(I18n.t("workbench.notConnected")).apply {
        name = "connectionStatus"
    }
    private val connectButton = JButton(I18n.t("workbench.connect")).apply {
        name = "connectButton"
        isEnabled = canConnect
        addActionListener { connectAction?.invoke() }
    }
    private val newSessionButton = JButton("+").apply {
        name = "newSessionButton"
        toolTipText = I18n.t("workbench.newSession.tooltip")
        isEnabled = canOpenHosts
        isFocusable = false
        margin = Insets(0, 6, 0, 6)
        // The "+" belongs to Saved hosts, not to the ad-hoc SSH dialog: only the bottom
        // Connect... button opens the quick connection form.
        addActionListener { hostsAction?.invoke() }
    }
    private val sessionTabTrailing = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(newSessionButton, BorderLayout.WEST)
    }
    private val hostsButton = JButton(I18n.t("workbench.hosts")).apply {
        name = "hostsButton"
        isEnabled = hostsAction != null
        addActionListener { hostsAction?.invoke() }
    }
    private val toolDock = CollapsibleToolDock(commandsController)
    private val toolDockToggle = JButton(I18n.t("workbench.tools.show")).apply {
        name = "toolDockToggle"
        getAccessibleContext().accessibleName = "Toggle session tools"
        addActionListener {
            toolDock.setExpanded(!toolDock.isExpanded)
            text = if (toolDock.isExpanded) I18n.t("workbench.tools.hide") else I18n.t("workbench.tools.show")
        }
    }

    init {
        name = "workbench"
        sessionTabs.putClientProperty("JTabbedPane.trailingComponent", sessionTabTrailing)
        sessionTabs.addChangeListener {
            updateSelectedSessionStatus()
            val selected = sessionTabs.selectedComponent
            toolDock.bindCommandSender(selected?.let(sessions::get)?.sendCommand)
            if (selected != null && sessions.containsKey(selected)) {
                onMonitorSelectionChanged?.invoke(selected)
                onSftpSelectionChanged?.invoke(selected)
                // Keyboard focus must follow the selected tab, otherwise a connected session
                // ignores typing until the user clicks inside the terminal.
                onSessionSelectionChanged?.invoke(selected)
            } else {
                monitorPanel.resetToIdle()
                onSftpSelectionChanged?.invoke(selected)
            }
        }
        showEmptySession()
        add(createWorkbenchSplit(), BorderLayout.CENTER)
    }

    val isToolDockExpanded: Boolean
        get() = toolDock.isExpanded

    fun addSession(
        title: String,
        component: JComponent,
        closeAction: () -> Unit,
        sendCommand: ((String) -> Unit)? = null,
        endpointLabel: String? = null,
    ) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal tabs must be added on the Swing EDT" }
        if (sessions.isEmpty()) sessionTabs.remove(emptySession)
        val tab = SessionTab(title, endpointLabel, closeAction, sendCommand)
        sessions[component] = tab
        sessionTabs.addTab(title, component)
        val index = sessionTabs.indexOfComponent(component)
        sessionTabs.setTabComponentAt(index, createSessionTabHeader(tab, component))
        sessionTabs.selectedComponent = component
        setConnectionState(I18n.t("workbench.connectedTo", title), false)
    }

    fun updateSessionStatus(component: Component, status: SessionStatus) {
        check(SwingUtilities.isEventDispatchThread()) { "Session status must be updated on the Swing EDT" }
        val tab = sessions[component] ?: return
        tab.status = status
        tab.statusLabel.text = I18n.t(status.labelKey)
        tab.statusLabel.foreground = status.color
        if (sessionTabs.selectedComponent === component) updateSelectedSessionStatus()
    }

    /** Renames a session tab after an in-place reconnect connected to a different endpoint. */
    fun updateSessionTitle(component: Component, title: String, endpointLabel: String? = null) {
        check(SwingUtilities.isEventDispatchThread()) { "Session titles must be updated on the Swing EDT" }
        val tab = sessions[component] ?: return
        tab.title = title
        tab.endpointLabel = endpointLabel
        tab.titleLabel.text = title
        tab.titleLabel.toolTipDetail = endpointLabel?.takeIf { label -> label != title }
        tab.closeButton.toolTipText = I18n.t("workbench.tab.close", title)
        val index = sessionTabs.indexOfComponent(component)
        if (index >= 0) sessionTabs.setTitleAt(index, title)
        if (sessionTabs.selectedComponent === component) updateSelectedSessionStatus()
    }

    fun setConnectionState(message: String, connecting: Boolean) {
        check(SwingUtilities.isEventDispatchThread()) { "Connection state must be updated on the Swing EDT" }
        this.connecting = connecting
        connectButton.isEnabled = !connecting && canConnect
        // Opening the Saved hosts list never starts a connection, so the tab-strip "+" stays
        // available while another attempt is in flight.
        newSessionButton.isEnabled = canOpenHosts
        if (connecting || sessions.isEmpty()) {
            connectionStatus.text = message
        } else {
            updateSelectedSessionStatus()
        }
    }

    internal val sessionCount: Int
        get() = sessions.size

    internal fun closeSessions() {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal tabs must close on the Swing EDT" }
        var failure: Throwable? = null
        sessions.values.toList().forEach { tab ->
            try {
                tab.closeAction()
            } catch (error: Throwable) {
                failure = accumulateFailure(failure, error)
            }
        }
        sessions.clear()
        sessionTabs.removeAll()
        showEmptySession()
        failure?.let { throw it }
    }

    internal fun setMonitorSnapshot(snapshot: MonitorSnapshot) {
        check(SwingUtilities.isEventDispatchThread()) { "Monitor snapshots must publish on the Swing EDT" }
        monitorPanel.update(snapshot)
    }

    internal fun monitorIdle() {
        check(SwingUtilities.isEventDispatchThread()) { "Monitor state must reset on the Swing EDT" }
        monitorPanel.resetToIdle()
    }

    internal fun isSelectedSession(component: Component): Boolean =
        sessionTabs.selectedComponent === component

    /**
     * A tab whose emulator failed is just as reconnectable as one whose shell exited: the session
     * is gone either way, and Enter is the user's remedy for both.
     */
    internal fun isSessionExited(component: Component): Boolean =
        sessions[component]?.status.let {
            it == SessionStatus.EXITED || it == SessionStatus.FAILED
        }

    internal fun commandsPanel(): CommandsPanel = toolDock.commandsPanel()

    internal fun bindSftp(controller: io.github.sawaichi9527.eyeshell.sftp.SftpController?) {
        check(SwingUtilities.isEventDispatchThread()) { "SFTP binding must run on the Swing EDT" }
        toolDock.bindSftp(controller)
    }

    internal fun bindSftpUnavailable(reason: String, retry: () -> Unit) {
        check(SwingUtilities.isEventDispatchThread()) { "SFTP binding must run on the Swing EDT" }
        toolDock.bindSftpUnavailable(reason, retry)
    }

    internal fun showSftpLoading() {
        check(SwingUtilities.isEventDispatchThread()) { "SFTP binding must run on the Swing EDT" }
        toolDock.showSftpLoading()
    }

    private fun createWorkbenchSplit(): JSplitPane = JSplitPane(
        JSplitPane.HORIZONTAL_SPLIT,
        monitorPanel,
        createSessionWorkspace(),
    ).apply {
        name = "workbenchSplit"
        dividerLocation = 230
        resizeWeight = 0.0
        isOneTouchExpandable = false
        border = BorderFactory.createEmptyBorder()
    }

    private fun createSessionWorkspace(): JPanel = JPanel(BorderLayout()).apply {
        name = "sessionWorkspace"
        add(sessionTabs, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            name = "terminalBottom"
            add(createCommandBar(), BorderLayout.NORTH)
            add(toolDock, BorderLayout.CENTER)
        }, BorderLayout.SOUTH)
    }

    private fun createEmptySession(): JPanel = JPanel(BorderLayout()).apply {
        name = "terminalWorkspace"
        border = BorderFactory.createEmptyBorder(24, 24, 24, 24)
        add(emptyStateLabel, BorderLayout.CENTER)
    }

    /**
     * Re-applies localized texts across the workbench after the Settings language change, so the
     * main window follows immediately instead of waiting for a restart.
     */
    fun relocalize() {
        check(SwingUtilities.isEventDispatchThread()) { "Localized texts must be refreshed on the Swing EDT" }
        connectButton.text = I18n.t("workbench.connect")
        hostsButton.text = I18n.t("workbench.hosts")
        newSessionButton.toolTipText = I18n.t("workbench.newSession.tooltip")
        toolDockToggle.text = if (toolDock.isExpanded) {
            I18n.t("workbench.tools.hide")
        } else {
            I18n.t("workbench.tools.show")
        }
        emptyStateLabel.text = I18n.t("workbench.empty")
        val startIndex = sessionTabs.indexOfComponent(emptySession)
        if (startIndex >= 0) sessionTabs.setTitleAt(startIndex, I18n.t("workbench.tab.start"))
        sessions.forEach { (_, tab) ->
            tab.statusLabel.text = I18n.t(tab.status.labelKey)
            tab.closeButton.toolTipText = I18n.t("workbench.tab.close", tab.title)
        }
        if (sessions.isEmpty()) {
            if (!connecting) connectionStatus.text = I18n.t("workbench.notConnected")
        } else {
            updateSelectedSessionStatus()
        }
        toolDock.relocalize()
        monitorPanel.relocalize()
    }

    private fun showEmptySession() {
        if (sessionTabs.indexOfComponent(emptySession) < 0) sessionTabs.addTab(I18n.t("workbench.tab.start"), emptySession)
        if (!connecting) connectionStatus.text = I18n.t("workbench.notConnected")
    }

    private fun createSessionTabHeader(tab: SessionTab, component: Component): JPanel {
        val header = JPanel(FlowLayout(FlowLayout.LEADING, 4, 0))
        header.isOpaque = false
        // A label that carries a tooltip installs ToolTipManager as a mouse listener, which makes
        // it the mouse target and stops the click from reaching the tabbed pane. Selecting the tab
        // explicitly keeps the title clickable while its tooltip stays available.
        val selectTab = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(event)) sessionTabs.selectedComponent = component
            }
        }
        header.add(tab.titleLabel.also {
            it.toolTipDetail = tab.endpointLabel?.takeIf { label -> label != tab.title }
            it.addMouseListener(selectTab)
        })
        header.add(tab.statusLabel.also {
            it.text = I18n.t(tab.status.labelKey)
            it.foreground = tab.status.color
            it.addMouseListener(selectTab)
        })
        header.add(tab.closeButton.also {
            it.toolTipText = I18n.t("workbench.tab.close", tab.title)
            it.addActionListener { closeSession(component) }
        })
        header.addMouseListener(selectTab)
        return header
    }

    private fun closeSession(component: Component) {
        val tab = sessions.remove(component) ?: return
        sessionTabs.remove(component)
        try {
            tab.closeAction()
        } finally {
            if (sessions.isEmpty()) {
                showEmptySession()
            } else {
                updateSelectedSessionStatus()
            }
        }
    }

    private fun updateSelectedSessionStatus() {
        if (connecting || sessions.isEmpty()) return
        val index = sessionTabs.selectedIndex
        if (index < 0) return
        val component = sessionTabs.getComponentAt(index)
        val tab = sessions[component] ?: return
        // The tab shows the Saved Host name; the status line keeps the real endpoint so a quick
        // connection and an edited host stay unambiguous.
        val detail = tab.endpointLabel ?: tab.title
        connectionStatus.text = when (tab.status) {
            SessionStatus.CONNECTED -> I18n.t("sessionStatus.line.connected", detail)
            SessionStatus.EXITED -> I18n.t("sessionStatus.line.exited", detail)
            SessionStatus.FAILED -> I18n.t("sessionStatus.line.failed", detail)
            SessionStatus.CLOSING -> I18n.t("sessionStatus.line.closing", detail)
        }
    }

    private fun createCommandBar(): JPanel = JPanel().apply {
        name = "commandBar"
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, foreground),
            BorderFactory.createEmptyBorder(8, 12, 8, 12),
        )
        add(connectionStatus)
        add(Box.createHorizontalGlue())
        add(connectButton)
        add(Box.createHorizontalStrut(8))
        add(hostsButton)
        add(Box.createHorizontalStrut(8))
        add(toolDockToggle)
    }

    private fun sectionTitle(text: String): JLabel = JLabel(text).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        font = font.deriveFont(Font.BOLD, 16f)
    }

    private fun emptyState(text: String): JLabel = JLabel(text, SwingConstants.CENTER).apply {
        name = "emptyState"
        alignmentX = Component.LEFT_ALIGNMENT
    }

}

private class SessionTab(
    title: String,
    endpointLabel: String?,
    val closeAction: () -> Unit,
    val sendCommand: ((String) -> Unit)? = null,
) {
    var title: String = title
    var endpointLabel: String? = endpointLabel
    var status: SessionStatus = SessionStatus.CONNECTED
    val titleLabel = ElidedLabel(title, TAB_TITLE_MAX_WIDTH)
    val statusLabel = JLabel().apply { name = "sessionStatusLabel" }
    val closeButton = JButton("x").apply {
        name = "closeSessionButton"
        margin = Insets(0, 4, 0, 4)
        isFocusable = false
    }

    private companion object {
        const val TAB_TITLE_MAX_WIDTH = 240
    }
}

private fun accumulateFailure(current: Throwable?, next: Throwable): Throwable {
    if (current == null) return next
    if (current !== next) current.addSuppressed(next)
    return current
}

/** Renders a cause chain as one line, so the SFTP panel can say why it could not open. */
private fun describeFailureChain(failure: Throwable): String {
    val parts = generateSequence(failure) { current ->
        current.cause?.takeIf { it !== current }
    }.map { current ->
        current.message?.takeIf(String::isNotBlank) ?: current.javaClass.simpleName
    }.distinct().toList()
    return parts.joinToString(": ").take(600).ifEmpty { failure.javaClass.simpleName }
}

private class CollapsibleToolDock(commandsController: CommandsController?) : JPanel(BorderLayout()) {
    private val sftpPanel = SftpPanel()
    private val commandsPanel = CommandsPanel().apply { bind(commandsController) }
    private val content = JTabbedPane(JTabbedPane.TOP).apply {
        name = "toolDockContent"
        preferredSize = Dimension(0, 300)
        addTab(I18n.t("dock.sftp"), sftpPanel)
        addTab(I18n.t("dock.commands"), commandsPanel)
        isVisible = false
    }
    var isExpanded: Boolean = false
        private set

    init {
        name = "toolDock"
        getAccessibleContext().accessibleName = "Session tools"
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, foreground)
        add(content, BorderLayout.CENTER)
    }

    fun bindSftp(controller: io.github.sawaichi9527.eyeshell.sftp.SftpController?) {
        sftpPanel.bind(controller)
    }

    fun bindSftpUnavailable(reason: String, retry: () -> Unit) {
        sftpPanel.showUnavailable(reason, retry)
    }

    fun showSftpLoading() {
        sftpPanel.showLoading()
    }

    fun bindCommandSender(sender: ((String) -> Unit)?) {
        commandsPanel.bindSession(sender)
    }

    fun commandsPanel(): CommandsPanel = commandsPanel

    /** Re-applies the dock tab titles and the panel texts after a language change. */
    fun relocalize() {
        content.setTitleAt(0, I18n.t("dock.sftp"))
        content.setTitleAt(1, I18n.t("dock.commands"))
        sftpPanel.relocalize()
        commandsPanel.relocalize()
    }

    fun setExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return
        isExpanded = expanded
        content.isVisible = expanded
        revalidate()
        repaint()
    }
}
