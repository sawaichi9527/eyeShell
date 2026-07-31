package io.github.sawaichi9527.eyeshell.ui

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkbenchPanelTest {
    @Test
    fun `monitor stays visible while tool dock toggles`() {
        SwingUtilities.invokeAndWait {
            val panel = WorkbenchPanel()
            val monitor = panel.findByName("monitorPanel")
            val terminal = panel.findByName("terminalWorkspace")
            val toolDockContent = panel.findByName("toolDockContent")
            val toolDockToggle = panel.findByName("toolDockToggle") as? JButton

            assertNotNull(monitor)
            assertNotNull(terminal)
            assertNotNull(toolDockContent)
            assertNotNull(toolDockToggle)

            panel.size = Dimension(1280, 800)
            panel.layoutTree()
            assertTrue(monitor!!.isVisible)
            assertTrue(terminal!!.isVisible)
            assertTrue(monitor.width > 0)
            assertTrue(terminal.width > monitor.width)
            assertFalse(panel.isToolDockExpanded)
            assertFalse(toolDockContent!!.isVisible)
            assertTrue(toolDockToggle!!.text == "Show tools")

            toolDockToggle.doClick()
            panel.layoutTree()
            assertTrue(panel.isToolDockExpanded)
            assertTrue(toolDockContent.isVisible)
            assertTrue(toolDockContent.height > 0)
            assertTrue(toolDockToggle.text == "Hide tools")
            assertTrue(monitor.isVisible)
            assertTrue(monitor.width > 0)

            toolDockToggle.doClick()
            panel.layoutTree()
            assertFalse(panel.isToolDockExpanded)
            assertFalse(toolDockContent.isVisible)
            assertTrue(toolDockToggle.text == "Show tools")
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
}
