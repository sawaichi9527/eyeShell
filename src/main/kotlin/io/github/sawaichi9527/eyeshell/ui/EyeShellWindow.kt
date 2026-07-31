package io.github.sawaichi9527.eyeshell.ui

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.SwingConstants

class EyeShellWindow : JFrame("eyeShell") {
    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        minimumSize = Dimension(960, 640)
        size = Dimension(1280, 800)
        contentPane = WorkbenchPanel()
        setLocationRelativeTo(null)
    }
}

class WorkbenchPanel : JPanel(BorderLayout()) {
    private val toolDock = CollapsibleToolDock()

    init {
        name = "workbench"
        add(createSessionTabs(), BorderLayout.CENTER)
    }

    val isToolDockExpanded: Boolean
        get() = toolDock.isExpanded

    private fun createSessionTabs(): JTabbedPane = JTabbedPane(JTabbedPane.TOP).apply {
        name = "sessionTabs"
        getAccessibleContext().accessibleName = "Sessions"
        addTab("Start", createSessionWorkspace())
    }

    private fun createSessionWorkspace(): JSplitPane = JSplitPane(
        JSplitPane.HORIZONTAL_SPLIT,
        createMonitorPanel(),
        createTerminalArea(),
    ).apply {
        name = "sessionWorkspace"
        dividerLocation = 230
        resizeWeight = 0.0
        isOneTouchExpandable = false
        border = BorderFactory.createEmptyBorder()
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

    private fun createTerminalArea(): JPanel = JPanel(BorderLayout()).apply {
        name = "terminalArea"
        add(JPanel(BorderLayout()).apply {
            name = "terminalWorkspace"
            getAccessibleContext().accessibleName = "Terminal workspace"
            border = BorderFactory.createEmptyBorder(24, 24, 24, 24)
            add(emptyState("No active terminal session."), BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            name = "terminalBottom"
            add(createCommandBar(), BorderLayout.NORTH)
            add(toolDock, BorderLayout.CENTER)
        }, BorderLayout.SOUTH)
    }

    private fun createCommandBar(): JPanel = JPanel(BorderLayout()).apply {
        name = "commandBar"
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, foreground),
            BorderFactory.createEmptyBorder(8, 12, 8, 12),
        )
        add(JLabel("Command input is available after connecting."), BorderLayout.CENTER)
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

private class CollapsibleToolDock : JPanel(BorderLayout()) {
    private val content = JTabbedPane(JTabbedPane.TOP).apply {
        name = "toolDockContent"
        preferredSize = Dimension(0, 220)
        addTab("SFTP", emptyToolPanel("Connect to a host to browse remote files."))
        addTab("Commands", emptyToolPanel("Saved commands will appear here."))
        isVisible = false
    }
    private val toggle = JButton("Show tools")

    var isExpanded: Boolean = false
        private set

    init {
        name = "toolDock"
        getAccessibleContext().accessibleName = "Session tools"
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, foreground)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 5)).apply {
            name = "toolDockHeader"
            toggle.name = "toolDockToggle"
            toggle.getAccessibleContext().accessibleName = "Toggle session tools"
            toggle.addActionListener { setExpanded(!isExpanded) }
            add(toggle)
        }, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
    }

    fun setExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return
        isExpanded = expanded
        content.isVisible = expanded
        toggle.text = if (expanded) "Hide tools" else "Show tools"
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
