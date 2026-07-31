package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalView
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.io.Writer
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkbenchPanelTest {
    @Test
    fun `terminal view is embedded without exposing its implementation to the workbench`() {
        SwingUtilities.invokeAndWait {
            val terminal = JPanel().apply { name = "testTerminal" }
            val panel = WorkbenchPanel(TestTerminalView(terminal))

            assertSame(terminal, panel.findByName("testTerminal"))
            assertSame(panel.findByName("terminalWorkspace"), terminal.parent)
        }
    }

    @Test
    fun `monitor stays visible while tool dock toggles`() {
        SwingUtilities.invokeAndWait {
            val panel = WorkbenchPanel()
            val monitor = panel.findByName("monitorPanel")
            val terminal = panel.findByName("terminalWorkspace")
            val toolDockContent = panel.findByName("toolDockContent")
            val toolDockToggle = panel.findByName("toolDockToggle") as? JButton
            val commandBar = panel.findByName("commandBar") as? JPanel
            val workbenchSplit = panel.findByName("workbenchSplit") as? JSplitPane
            val sessionTabs = panel.findByName("sessionTabs") as? JTabbedPane

            assertNotNull(monitor)
            assertNotNull(terminal)
            assertNotNull(toolDockContent)
            assertNotNull(toolDockToggle)
            assertNotNull(commandBar)
            assertNotNull(workbenchSplit)
            assertNotNull(sessionTabs)
            assertSame(monitor, workbenchSplit!!.leftComponent)
            assertSame(sessionTabs, workbenchSplit.rightComponent)
            assertFalse(sessionTabs!!.containsComponent(monitor!!))
            assertSame(commandBar, toolDockToggle!!.parent)
            assertEquals("Show tools", toolDockToggle.text)

            panel.size = Dimension(1280, 800)
            panel.layoutTree()
            assertTrue(monitor.isVisible)
            assertTrue(terminal!!.isVisible)
            assertTrue(monitor.width > 0)
            assertTrue(terminal.width > monitor.width)
            assertFalse(panel.isToolDockExpanded)
            assertFalse(toolDockContent!!.isVisible)
            assertEquals("Show tools", toolDockToggle.text)

            toolDockToggle.doClick()
            panel.layoutTree()
            assertTrue(panel.isToolDockExpanded)
            assertTrue(toolDockContent.isVisible)
            assertTrue(toolDockContent.height > 0)
            assertEquals("Hide tools", toolDockToggle.text)
            assertTrue(monitor.isVisible)
            assertTrue(monitor.width > 0)

            toolDockToggle.doClick()
            panel.layoutTree()
            assertFalse(panel.isToolDockExpanded)
            assertFalse(toolDockContent.isVisible)
            assertEquals("Show tools", toolDockToggle.text)
            assertTrue(monitor.isVisible)
        }
    }

    private fun Container.findByName(componentName: String): Component? {
        components.forEach { component ->
            if (component.name == componentName) return component
            if (component is Container) {
                component.findByName(componentName)?.let { return it }
            }
        }
        return null
    }

    private fun Container.layoutTree() {
        doLayout()
        components.filterIsInstance<Container>().forEach { it.layoutTree() }
    }

    private fun Container.containsComponent(target: Component): Boolean =
        components.any { component ->
            component === target || (component is Container && component.containsComponent(target))
        }

    private class TestTerminalView(
        override val component: JComponent,
    ) : TerminalView {
        override fun attach(session: TerminalSession) = Unit

        override fun writeAllOutput(writer: Writer) = Unit

        override fun clearScrollback() = Unit

        override fun close() = Unit
    }
}
