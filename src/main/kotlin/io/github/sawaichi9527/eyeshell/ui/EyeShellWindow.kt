package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalView
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.FlowLayout
import java.awt.Insets
import java.util.concurrent.atomic.AtomicBoolean
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

class EyeShellWindow(
    private val terminalViewFactory: () -> TerminalView,
    connectAction: ((EyeShellWindow) -> Unit)? = null,
    hostsAction: ((EyeShellWindow) -> Unit)? = null,
    private val closeAction: () -> Unit = {},
) : JFrame("eyeShell") {
    private val closed = AtomicBoolean()
    private val workbench = WorkbenchPanel(
        connectAction = connectAction?.let { action -> { action(this) } },
        hostsAction = hostsAction?.let { action -> { action(this) } },
    )

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        minimumSize = Dimension(960, 640)
        size = Dimension(1280, 800)
        contentPane = workbench
        setLocationRelativeTo(null)
    }

    fun attachTerminal(session: TerminalSession) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal sessions must be attached on the Swing EDT" }
        val terminalView = terminalViewFactory()
        val page = try {
            TerminalSessionPage(this, terminalView, session)
        } catch (failure: Throwable) {
            try {
                terminalView.close()
            } catch (closeFailure: Throwable) {
                accumulateFailure(failure, closeFailure)
            }
            throw failure
        }
        try {
            page.attach()
            workbench.addSession(session.name, page.component, page::close)
        } catch (failure: Throwable) {
            try {
                page.close()
            } catch (closeFailure: Throwable) {
                accumulateFailure(failure, closeFailure)
            }
            throw failure
        }
    }

    fun setConnectionState(message: String, connecting: Boolean) {
        workbench.setConnectionState(message, connecting)
    }

    override fun dispose() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        try {
            workbench.closeSessions()
        } catch (error: Throwable) {
            failure = error
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
    private val session: TerminalSession,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val outputController = TerminalOutputController(terminalView)
    private val highlightController = TerminalHighlightController(terminalView)
    val component: JComponent = JPanel(BorderLayout()).apply {
        name = "terminalWorkspace"
        getAccessibleContext().accessibleName = "Terminal workspace"
        add(terminalView.component, BorderLayout.CENTER)
    }

    fun attach() {
        terminalView.attach(session)
        outputController.install(
            owner,
            addHighlightRule = { highlightController.addRule(owner) },
            manageHighlightRules = { highlightController.manageRules(owner) },
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        outputController.close(terminalView::close)
    }
}

class WorkbenchPanel(
    connectAction: (() -> Unit)? = null,
    hostsAction: (() -> Unit)? = null,
) : JPanel(BorderLayout()) {
    private val canConnect = connectAction != null
    private val sessionTabs = JTabbedPane(JTabbedPane.TOP).apply {
        name = "sessionTabs"
        getAccessibleContext().accessibleName = "Sessions"
    }
    private val sessions = linkedMapOf<Component, () -> Unit>()
    private val emptySession = createEmptySession()
    private var connecting = false
    private val connectionStatus = JLabel("Not connected").apply {
        name = "connectionStatus"
    }
    private val connectButton = JButton("Connect...").apply {
        name = "connectButton"
        isEnabled = canConnect
        addActionListener { connectAction?.invoke() }
    }
    private val hostsButton = JButton("Hosts...").apply {
        name = "hostsButton"
        isEnabled = hostsAction != null
        addActionListener { hostsAction?.invoke() }
    }
    private val toolDock = CollapsibleToolDock()
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
        sessionTabs.addChangeListener { updateSelectedSessionStatus() }
        showEmptySession()
        add(createWorkbenchSplit(), BorderLayout.CENTER)
    }

    val isToolDockExpanded: Boolean
        get() = toolDock.isExpanded

    fun addSession(title: String, component: JComponent, closeAction: () -> Unit) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal tabs must be added on the Swing EDT" }
        if (sessions.isEmpty()) sessionTabs.remove(emptySession)
        sessions[component] = closeAction
        sessionTabs.addTab(title, component)
        val index = sessionTabs.indexOfComponent(component)
        sessionTabs.setTabComponentAt(index, createSessionTabHeader(title, component))
        sessionTabs.selectedComponent = component
        setConnectionState("Connected to $title", false)
    }

    fun setConnectionState(message: String, connecting: Boolean) {
        check(SwingUtilities.isEventDispatchThread()) { "Connection state must be updated on the Swing EDT" }
        this.connecting = connecting
        connectButton.isEnabled = !connecting && canConnect
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
        sessions.values.toList().forEach { close ->
            try {
                close()
            } catch (error: Throwable) {
                failure = accumulateFailure(failure, error)
            }
        }
        sessions.clear()
        sessionTabs.removeAll()
        showEmptySession()
        failure?.let { throw it }
    }

    private fun createWorkbenchSplit(): JSplitPane = JSplitPane(
        JSplitPane.HORIZONTAL_SPLIT,
        createMonitorPanel(),
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

    private fun createMonitorPanel(): JPanel = JPanel().apply {
        name = "monitorPanel"
        getAccessibleContext().accessibleName = "Monitor"
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(18, 16, 18, 16)
        minimumSize = Dimension(190, 0)
        preferredSize = Dimension(230, 0)

        add(sectionTitle("Monitor"))
        add(Box.createVerticalStrut(18))
        add(emptyState("Connect to a host to view live metrics."))
        add(Box.createVerticalGlue())
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

    private fun createSessionTabHeader(title: String, component: Component): JPanel = JPanel(
        FlowLayout(FlowLayout.LEADING, 4, 0),
    ).apply {
        isOpaque = false
        add(JLabel(title))
        add(JButton("x").apply {
            name = "closeSessionButton"
            toolTipText = "Close $title"
            margin = Insets(0, 4, 0, 4)
            isFocusable = false
            addActionListener { closeSession(component) }
        })
    }

    private fun closeSession(component: Component) {
        val close = sessions.remove(component) ?: return
        sessionTabs.remove(component)
        try {
            close()
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
        if (index >= 0) connectionStatus.text = "Connected to ${sessionTabs.getTitleAt(index)}"
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

private fun accumulateFailure(current: Throwable?, next: Throwable): Throwable {
    if (current == null) return next
    if (current !== next) current.addSuppressed(next)
    return current
}

private class CollapsibleToolDock : JPanel(BorderLayout()) {
    private val content = JTabbedPane(JTabbedPane.TOP).apply {
        name = "toolDockContent"
        preferredSize = Dimension(0, 220)
        addTab("SFTP", emptyToolPanel("Connect to a host to browse remote files."))
        addTab("Commands", emptyToolPanel("Saved commands will appear here."))
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

    fun setExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return
        isExpanded = expanded
        content.isVisible = expanded
        revalidate()
        repaint()
    }

    companion object {
        private fun emptyToolPanel(text: String): JPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(20, 20, 20, 20)
            add(JLabel(text, SwingConstants.CENTER), BorderLayout.CENTER)
        }
    }
}
