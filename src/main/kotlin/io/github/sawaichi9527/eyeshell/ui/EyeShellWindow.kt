package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.monitor.MonitorSampler
import io.github.sawaichi9527.eyeshell.monitor.MonitorSnapshot
import io.github.sawaichi9527.eyeshell.platform.AppInfo
import io.github.sawaichi9527.eyeshell.platform.AppSettings
import io.github.sawaichi9527.eyeshell.ssh.HostSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalView
import io.github.sawaichi9527.eyeshell.terminal.TerminalColorScheme
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.FlowLayout
import java.awt.Insets
import java.awt.KeyboardFocusManager
import java.awt.KeyEventDispatcher
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

enum class SessionStatus(
    val label: String,
    val color: Color,
) {
    CONNECTED("Connected", Color(0x4CAF50)),
    EXITED("Exited", Color(0x9E9E9E)),
    FAILED("Failed", Color(0xF44336)),
    CLOSING("Closing", Color(0xFF9800)),
}

class EyeShellWindow(
    private val terminalViewFactory: () -> TerminalView,
    connectAction: ((EyeShellWindow) -> Unit)? = null,
    hostsAction: ((EyeShellWindow) -> Unit)? = null,
    commandsController: CommandsController? = null,
    private val closeAction: () -> Unit = {},
    appSettings: AppSettings = AppSettings.inMemory(),
) : JFrame(AppInfo.windowTitle) {
    private val closed = AtomicBoolean()
    private val pagesByComponent = mutableMapOf<JComponent, TerminalSessionPage>()
    private val settingsController = SettingsController(
        appSettings,
        applyFontSize = { size -> applyTerminalFontSize(size) },
        applyColorScheme = { scheme -> applyTerminalColorScheme(scheme) },
    )
    private val workbench: WorkbenchPanel = WorkbenchPanel(
        connectAction = connectAction?.let { action -> { action(this) } },
        hostsAction = hostsAction?.let { action -> { action(this) } },
        commandsController = commandsController,
        monitorSelectionChanged = { component -> bindMonitorFor(component) },
        sftpSelectionChanged = { component -> bindSftpFor(component) },
        onOpenSettings = { settingsController.open(this) },
    )
    private var initialized = false

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        minimumSize = Dimension(960, 640)
        size = Dimension(1280, 800)
        contentPane = workbench
        setLocationRelativeTo(null)
        initialized = true
    }

    internal fun attachTerminal(
        hostSession: HostSession,
        terminal: TerminalSession,
        preset: HostConnectionPreset? = null,
        reconnect: ((HostConnectionPreset, JComponent) -> Boolean)? = null,
    ) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal sessions must be attached on the Swing EDT" }
        val terminalView = terminalViewFactory()
        val page = try {
            TerminalSessionPage(this, terminalView, hostSession, terminal)
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
            workbench.addSession(hostSession.endpoint.displayName, component, {
                pagesByComponent.remove(component)
                page.close()
            }, page::sendCommand)
        } catch (failure: Throwable) {
            try {
                page.close()
            } catch (closeFailure: Throwable) {
                accumulateFailure(failure, closeFailure)
            }
            throw failure
        }
        page.startExitMonitor {
            workbench.updateSessionStatus(component, SessionStatus.EXITED)
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
        if (!page.reconnect(hostSession, terminal, preset)) return false
        val title = hostSession.endpoint.displayName
        workbench.updateSessionTitle(component, title)
        workbench.updateSessionStatus(component, SessionStatus.CONNECTED)
        workbench.setConnectionState("Connected to $title", false)
        if (workbench.isSelectedSession(component)) {
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

    /** Applies the Settings color scheme to every open session; new sessions read it from the factory. */
    internal fun applyTerminalColorScheme(scheme: TerminalColorScheme) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal color changes must happen on the Swing EDT" }
        pagesByComponent.values.toList().forEach { page -> page.applyColorScheme(scheme) }
    }

    override fun dispose() {
        if (!closed.compareAndSet(false, true)) return
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
}

internal class TerminalSessionPage(
    private val owner: Component,
    private val terminalView: TerminalView,
    hostSession: HostSession,
    session: TerminalSession = hostSession.openTerminal(),
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
    val component: JComponent = JPanel(BorderLayout()).apply {
        name = "terminalWorkspace"
        getAccessibleContext().accessibleName = "Terminal workspace"
        add(terminalView.component, BorderLayout.CENTER)
    }

    fun applyFontSize(size: Float) = terminalView.setFontSize(size)

    fun applyColorScheme(scheme: TerminalColorScheme) = terminalView.setColorScheme(scheme)

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
            val outcome = runCatching { io.github.sawaichi9527.eyeshell.sftp.SftpController(host.sftp()) }
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
            watched.awaitExit()
            if (closed.get() || exitGeneration.get() != generation) return@start
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
}

class WorkbenchPanel(
    connectAction: (() -> Unit)? = null,
    hostsAction: (() -> Unit)? = null,
    commandsController: CommandsController? = null,
    monitorSelectionChanged: ((Component) -> Unit)? = null,
    sftpSelectionChanged: ((Component?) -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
) : JPanel(BorderLayout()) {
    private val canConnect = connectAction != null
    private val sessionTabs = JTabbedPane(JTabbedPane.TOP).apply {
        name = "sessionTabs"
        getAccessibleContext().accessibleName = "Sessions"
    }
    private val sessions = linkedMapOf<Component, SessionTab>()
    private val emptySession = createEmptySession()
    private var connecting = false
    private val monitorPanel = MonitorPanel(onOpenSettings = onOpenSettings)
    private val onMonitorSelectionChanged = monitorSelectionChanged
    private val onSftpSelectionChanged = sftpSelectionChanged
    private val connectionStatus = JLabel("Not connected").apply {
        name = "connectionStatus"
    }
    private val connectButton = JButton("Connect...").apply {
        name = "connectButton"
        isEnabled = canConnect
        addActionListener { connectAction?.invoke() }
    }
    private val newSessionButton = JButton("+").apply {
        name = "newSessionButton"
        toolTipText = "New connection"
        isEnabled = canConnect
        isFocusable = false
        margin = Insets(0, 6, 0, 6)
        addActionListener { connectAction?.invoke() }
    }
    private val sessionTabTrailing = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(newSessionButton, BorderLayout.WEST)
    }
    private val hostsButton = JButton("Hosts...").apply {
        name = "hostsButton"
        isEnabled = hostsAction != null
        addActionListener { hostsAction?.invoke() }
    }
    private val toolDock = CollapsibleToolDock(commandsController)
    private val toolDockToggle = JButton("Show tools").apply {
        name = "toolDockToggle"
        getAccessibleContext().accessibleName = "Toggle session tools"
        addActionListener {
            toolDock.setExpanded(!toolDock.isExpanded)
            text = if (toolDock.isExpanded) "Hide tools" else "Show tools"
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
    ) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal tabs must be added on the Swing EDT" }
        if (sessions.isEmpty()) sessionTabs.remove(emptySession)
        val tab = SessionTab(title, closeAction, sendCommand)
        sessions[component] = tab
        sessionTabs.addTab(title, component)
        val index = sessionTabs.indexOfComponent(component)
        sessionTabs.setTabComponentAt(index, createSessionTabHeader(title, component, tab))
        sessionTabs.selectedComponent = component
        setConnectionState("Connected to $title", false)
    }

    fun updateSessionStatus(component: Component, status: SessionStatus) {
        check(SwingUtilities.isEventDispatchThread()) { "Session status must be updated on the Swing EDT" }
        val tab = sessions[component] ?: return
        tab.status = status
        tab.statusLabel.text = status.label
        tab.statusLabel.foreground = status.color
        if (sessionTabs.selectedComponent === component) updateSelectedSessionStatus()
    }

    /** Renames a session tab after an in-place reconnect connected to a different endpoint. */
    fun updateSessionTitle(component: Component, title: String) {
        check(SwingUtilities.isEventDispatchThread()) { "Session titles must be updated on the Swing EDT" }
        val tab = sessions[component] ?: return
        tab.title = title
        tab.titleLabel.text = title
        tab.closeButton.toolTipText = "Close $title"
        val index = sessionTabs.indexOfComponent(component)
        if (index >= 0) sessionTabs.setTitleAt(index, title)
        if (sessionTabs.selectedComponent === component) updateSelectedSessionStatus()
    }

    fun setConnectionState(message: String, connecting: Boolean) {
        check(SwingUtilities.isEventDispatchThread()) { "Connection state must be updated on the Swing EDT" }
        this.connecting = connecting
        connectButton.isEnabled = !connecting && canConnect
        newSessionButton.isEnabled = !connecting && canConnect
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

    internal fun isSessionExited(component: Component): Boolean =
        sessions[component]?.status == SessionStatus.EXITED

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
        add(emptyState("No active terminal session. Use Connect... to open an SSH shell."), BorderLayout.CENTER)
    }

    private fun showEmptySession() {
        if (sessionTabs.indexOfComponent(emptySession) < 0) sessionTabs.addTab("Start", emptySession)
        if (!connecting) connectionStatus.text = "Not connected"
    }

    private fun createSessionTabHeader(title: String, component: Component, tab: SessionTab): JPanel = JPanel(
        FlowLayout(FlowLayout.LEADING, 4, 0),
    ).apply {
        isOpaque = false
        add(tab.titleLabel.also { it.text = title })
        add(tab.statusLabel.also { it.text = tab.status.label; it.foreground = tab.status.color })
        add(tab.closeButton.also {
            it.toolTipText = "Close $title"
            it.addActionListener { closeSession(component) }
        })
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
        connectionStatus.text = when (tab.status) {
            SessionStatus.CONNECTED -> "Connected to ${sessionTabs.getTitleAt(index)}"
            SessionStatus.EXITED -> "Exited: ${sessionTabs.getTitleAt(index)}"
            SessionStatus.FAILED -> "Failed: ${sessionTabs.getTitleAt(index)}"
            SessionStatus.CLOSING -> "Closing: ${sessionTabs.getTitleAt(index)}"
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
    val closeAction: () -> Unit,
    val sendCommand: ((String) -> Unit)? = null,
) {
    var title: String = title
    var status: SessionStatus = SessionStatus.CONNECTED
    val titleLabel = JLabel(title)
    val statusLabel = JLabel().apply { name = "sessionStatusLabel" }
    val closeButton = JButton("x").apply {
        name = "closeSessionButton"
        margin = Insets(0, 4, 0, 4)
        isFocusable = false
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
        addTab("SFTP", sftpPanel)
        addTab("Commands", commandsPanel)
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

    fun setExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return
        isExpanded = expanded
        content.isVisible = expanded
        revalidate()
        repaint()
    }
}
