package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.monitor.CpuUsage
import io.github.sawaichi9527.eyeshell.monitor.FilesystemUsage
import io.github.sawaichi9527.eyeshell.monitor.MemoryUsage
import io.github.sawaichi9527.eyeshell.monitor.MonitorSnapshot
import io.github.sawaichi9527.eyeshell.monitor.NetworkInterfaceUsage
import io.github.sawaichi9527.eyeshell.monitor.NetworkUsage
import io.github.sawaichi9527.eyeshell.monitor.ProcessUsage
import io.github.sawaichi9527.eyeshell.monitor.SystemInfo
import java.awt.Component
import java.awt.Container
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComboBox
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
            assertTrue(idleText.contains(UiText.IDLE_STATUS))
            assertTrue(idleText.contains("Host"))
            assertTrue(idleText.contains("CPU"))
            assertTrue(idleText.contains("Filesystems"))
            assertTrue(idleText.contains("Processes"))
            assertTrue(idleText.contains("Java "))
        }
    }

    @Test
    fun `shows large values in gigabytes without duplicating the label`() {
        SwingUtilities.invokeAndWait {
            val panel = MonitorPanel()
            panel.update(
                MonitorSnapshot(
                    system = SystemInfo("lab", "6.8.0", "Linux", 3661),
                    memory = MemoryUsage(16L * 1024 * 1024 * 1024, 8L * 1024 * 1024 * 1024, 50.0),
                ),
            )

            val text = collectText(panel, StringBuilder())
            assertTrue(text.contains("8.0 GB / 16.0 GB"))
            assertTrue(text.contains("1h 1m"))
            assertFalse(text.contains("Uptime: "), "The row label must not repeat inside the value")
            assertEquals(1, text.lineSequence().count { it == "Uptime" })
        }
    }

    @Test
    fun `shows filesystem usage, cpu details, and a process table`() {
        SwingUtilities.invokeAndWait {
            val panel = MonitorPanel()
            panel.update(
                MonitorSnapshot(
                    cpu = CpuUsage(42.5, 8),
                    filesystems = listOf(
                        FilesystemUsage("/", 100L * 1024 * 1024 * 1024, 62L * 1024 * 1024 * 1024, 62.0),
                        FilesystemUsage("/mnt/data", 10L * 1024 * 1024 * 1024, 1L * 1024 * 1024 * 1024, 10.0),
                    ),
                    processes = listOf(ProcessUsage(1, "daemon", 512L * 1024 * 1024, 4.5)),
                ),
            )

            val text = collectText(panel, StringBuilder())
            assertTrue(text.contains("Filesystems"))
            assertTrue(text.contains("Processes"))
            assertTrue(text.contains("/mnt/data"))
            assertTrue(text.contains("62.0%"))
            assertTrue(text.contains("8 cores"))

            val table = findTable(panel) ?: error("No process table in the monitor panel")
            assertEquals(1, table.rowCount)
            assertEquals("daemon", table.getValueAt(0, 0))
            assertEquals("512.0 MB", table.getValueAt(0, 1))
            assertEquals("4.5%", table.getValueAt(0, 2))
        }
    }

    private fun findTable(container: Container): javax.swing.JTable? {
        container.components.forEach { component ->
            if (component is javax.swing.JTable) return component
            if (component is Container) findTable(component)?.let { return it }
        }
        return null
    }

    @Test
    fun `idle state keeps the empty-state message and no metrics`() {
        SwingUtilities.invokeAndWait {
            val panel = MonitorPanel()
            panel.update(MonitorSnapshot(endpoint = "10.0.0.9:22"))
            val attached = collectText(panel, StringBuilder())
            assertTrue(attached.contains("-"))
            assertTrue(attached.contains("10.0.0.9"))
            assertFalse(attached.contains("10.0.0.9:22"))
            panel.resetToIdle()
            val idleText = collectText(panel, StringBuilder())
            assertTrue(idleText.contains(UiText.IDLE_STATUS))
            assertTrue(idleText.contains("Host"))
            assertTrue(idleText.contains("CPU"))
            assertTrue(idleText.contains("Network"))
            assertFalse(idleText.contains("10.0.0.9"))
        }
    }

    @Test
    fun `defaults the interface selector to the connected address and makes identity rows copyable`() {
        SwingUtilities.invokeAndWait {
            val panel = MonitorPanel()
            panel.update(networkSnapshot("10.0.0.5:22"))

            val selector = findByName(panel, "monitorInterfaceSelector") as JComboBox<*>
            assertEquals("eth1", selector.selectedItem)

            val text = collectText(panel, StringBuilder())
            assertTrue(text.contains("10.0.0.5"))
            assertFalse(text.contains("10.0.0.5:22"), "The monitor address must not carry the SSH port")
            assertTrue(text.contains("2.0 MB/s"))

            assertNull(findByName(panel, "monitorCopyAddress"), "The copy button was replaced by clickable labels")
            val address = findLabel(panel, "10.0.0.5") ?: error("Address value label is missing")
            assertEquals(java.awt.Cursor.HAND_CURSOR, address.cursor.type)
            assertTrue(address.toolTipText.contains("10.0.0.5"))
        }
    }

    @Test
    fun `switching the interface re-renders with that interface's rates`() {
        val panel = AtomicReference<MonitorPanel>()
        SwingUtilities.invokeAndWait {
            panel.set(MonitorPanel().also { it.update(networkSnapshot("10.0.0.5:22")) })
        }

        SwingUtilities.invokeAndWait {
            val selector = findByName(panel.get(), "monitorInterfaceSelector") as JComboBox<*>
            selector.selectedItem = "eth0"
        }
        SwingUtilities.invokeAndWait { }

        SwingUtilities.invokeAndWait {
            val text = collectText(panel.get(), StringBuilder())
            assertTrue(text.contains("1.0 MB/s"))
            assertFalse(text.contains("2.0 MB/s"))
            val selector = findByName(panel.get(), "monitorInterfaceSelector") as JComboBox<*>
            assertEquals("eth0", selector.selectedItem)
        }
    }

    @Test
    fun `the sidebar scrolls vertically with a thin scrollbar`() {
        SwingUtilities.invokeAndWait {
            val panel = MonitorPanel()
            panel.update(
                MonitorSnapshot(
                    system = SystemInfo("lab", "6.8.0", "Linux", 3661),
                    cpu = CpuUsage(42.0, 8),
                    memory = MemoryUsage(16L * 1024 * 1024 * 1024, 8L * 1024 * 1024 * 1024, 50.0),
                    swap = io.github.sawaichi9527.eyeshell.monitor.SwapUsage(
                        2L * 1024 * 1024 * 1024,
                        512L * 1024 * 1024,
                        25.0,
                    ),
                    load = io.github.sawaichi9527.eyeshell.monitor.LoadAverage(0.1, 0.2, 0.3),
                    network = networkSnapshot("10.0.0.5:22").network,
                    filesystems = listOf(FilesystemUsage("/", 100L, 50L, 50.0)),
                    processes = listOf(ProcessUsage(1, "daemon", 512L * 1024 * 1024, 4.5)),
                    endpoint = "10.0.0.5",
                ),
            )

            val scroll = findByName(panel, "monitorScroll") as javax.swing.JScrollPane
            assertEquals(
                javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                scroll.verticalScrollBarPolicy,
            )
            assertEquals(
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
                scroll.horizontalScrollBarPolicy,
            )
            assertTrue(
                scroll.verticalScrollBar.preferredSize.width <= 10,
                "The monitor scrollbar must stay thin",
            )
            assertTrue(scroll.viewport.view.preferredSize.height > 300)
        }
    }

    private fun networkSnapshot(endpoint: String) = MonitorSnapshot(
        endpoint = endpoint,
        network = NetworkUsage(
            receiveBytesPerSecond = 3_145_728,
            transmitBytesPerSecond = 314_572,
            interfaces = listOf(
                NetworkInterfaceUsage("lo", 1024, 1024),
                NetworkInterfaceUsage("eth0", 1_048_576, 104_857),
                NetworkInterfaceUsage("eth1", 2_097_152, 209_715),
            ),
            interfaceAddresses = mapOf(
                "eth0" to listOf("192.168.1.10"),
                "eth1" to listOf("10.0.0.5"),
            ),
        ),
    )

    @Test
    fun `shows the distribution as OS and the kernel on its own row`() {
        SwingUtilities.invokeAndWait {
            val panel = MonitorPanel()
            panel.update(
                MonitorSnapshot(
                    system = SystemInfo("lab", "6.8.0-45-generic", "Linux", 3661, "Ubuntu 26.04.1 LTS"),
                ),
            )

            val text = collectText(panel, StringBuilder())
            assertTrue(text.contains("Ubuntu 26.04.1 LTS"))
            assertTrue(text.contains("Kernel"))
            assertTrue(text.contains("6.8.0-45-generic"))
            assertFalse(text.contains("Linux 6.8.0-45-generic"), "OS must not repeat the kernel")
        }
    }

    @Test
    fun `falls back to the uname operating system when os-release is missing`() {
        SwingUtilities.invokeAndWait {
            val panel = MonitorPanel()
            panel.update(MonitorSnapshot(system = SystemInfo("lab", "6.8.0", "Linux", 60)))

            val text = collectText(panel, StringBuilder())
            assertTrue(text.contains("Linux"))
            assertTrue(text.contains("Kernel"))
        }
    }

    @Test
    fun `uses one type scale for row labels and their detail values`() {
        SwingUtilities.invokeAndWait {
            val panel = MonitorPanel()
            panel.update(
                MonitorSnapshot(
                    cpu = CpuUsage(42.0, 8),
                    memory = MemoryUsage(16L * 1024 * 1024 * 1024, 8L * 1024 * 1024 * 1024, 50.0),
                    swap = io.github.sawaichi9527.eyeshell.monitor.SwapUsage(
                        2L * 1024 * 1024 * 1024,
                        512L * 1024 * 1024,
                        25.0,
                    ),
                    filesystems = listOf(FilesystemUsage("/", 100L, 50L, 50.0)),
                ),
            )

            val sizes = collectLabelSizes(panel)
            val base = sizes.getValue("CPU")
            assertEquals(base, sizes.getValue("8 cores"), "CPU detail must match the row label size")
            assertEquals(base, sizes.getValue("Memory"))
            assertEquals(base, sizes.getValue("8.0 GB / 16.0 GB"))
            assertEquals(base, sizes.getValue("Swap"))
            assertEquals(base, sizes.getValue("Filesystems"))
        }
    }

    private fun collectLabelSizes(container: Container): Map<String, Float> {
        val sizes = mutableMapOf<String, Float>()
        container.components.forEach { component ->
            if (component is javax.swing.JLabel && component.text.isNotEmpty()) {
                sizes[component.text] = component.font.size2D
            }
            if (component is Container) sizes.putAll(collectLabelSizes(component))
        }
        return sizes
    }

    private fun findByName(container: Container, name: String): Component? {
        container.components.forEach { component ->
            if (component.name == name) return component
            if (component is Container) findByName(component, name)?.let { return it }
        }
        return null
    }

    private fun findLabel(container: Container, text: String): javax.swing.JLabel? {
        container.components.forEach { component ->
            if (component is javax.swing.JLabel && component.text == text) return component
            if (component is Container) findLabel(component, text)?.let { return it }
        }
        return null
    }

    private fun collectText(container: Container, builder: StringBuilder): String {
        container.components.forEach { component ->
            if (component is javax.swing.JLabel) builder.append(component.text).append("\n")
            if (component is Container) collectText(component, builder)
        }
        return builder.toString()
    }
}
