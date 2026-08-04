package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.monitor.CpuUsage
import io.github.sawaichi9527.eyeshell.monitor.MemoryUsage
import io.github.sawaichi9527.eyeshell.monitor.MonitorSnapshot
import io.github.sawaichi9527.eyeshell.monitor.SystemInfo
import java.awt.Container
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonitorPanelTest {
    @Test
    fun `shows metrics and resets to idle`() {
        SwingUtilities.invokeAndWait {
            val panel = MonitorPanel()
            val snapshot = MonitorSnapshot(
                system = SystemInfo("lab", "6.8.0", "Linux", 3661),
                cpu = CpuUsage(42.5, 4),
                memory = MemoryUsage(16L * 1024 * 1024 * 1024, 8L * 1024 * 1024 * 1024, 50.0),
            )

            panel.update(snapshot)

            val content = panel
            val text = collectText(content, StringBuilder())
            assertTrue(text.contains("lab"))
            assertTrue(text.contains("CPU"))
            assertTrue(text.contains("Memory"))

            panel.resetToIdle()
            val idleText = collectText(content, StringBuilder())
            assertTrue(idleText.contains("No active session"))
        }
    }

    private fun collectText(container: Container, builder: StringBuilder): String {
        container.components.forEach { component ->
            if (component is javax.swing.JLabel) builder.append(component.text).append("\n")
            if (component is Container) collectText(component, builder)
        }
        return builder.toString()
    }
}
