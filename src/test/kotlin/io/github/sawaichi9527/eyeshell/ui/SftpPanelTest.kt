package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.sftp.RemoteFile
import io.github.sawaichi9527.eyeshell.sftp.SftpClient
import io.github.sawaichi9527.eyeshell.sftp.SftpController
import java.awt.Container
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SftpPanelTest {
    @Test
    fun `shows disconnected then lists remote files after binding`() {
        val panelRef = arrayOfNulls<SftpPanel>(1)
        SwingUtilities.invokeAndWait {
            val panel = SftpPanel()
            panelRef[0] = panel
            assertTrue(panel.containsLabel("Connect to a host to browse remote files."))
        }
        val panel = requireNotNull(panelRef[0])

        val client = FakeSftpClient(
            entries = listOf(
                RemoteFile("docs", isDirectory = true, sizeBytes = 0, permissions = "drwxr-xr-x", owner = "u", group = "g"),
                RemoteFile("readme.txt", isDirectory = false, sizeBytes = 42, permissions = "-rw-r--r--", owner = "u", group = "g"),
            ),
        )
        val controller = SftpController(client)

        SwingUtilities.invokeAndWait { panel.bind(controller) }
        assertTrue(await(5, TimeUnit.SECONDS) { onEdt(panel) { it.tableRowCount("readme.txt") } }, "remote files did not render")
        assertTrue(onEdt(panel) { it.tableRowCount("docs/") })
        assertTrue(onEdt(panel) { it.containsTextField("/") })

        controller.close()
    }

    @Test
    fun `bind with null shows the disconnected state`() {
        val panelRef = arrayOfNulls<SftpPanel>(1)
        SwingUtilities.invokeAndWait {
            val panel = SftpPanel()
            panelRef[0] = panel
            val controller = SftpController(FakeSftpClient())
            panel.bind(controller)
            panel.bind(null)
            assertTrue(panel.containsLabel("Connect to a host to browse remote files."))
            controller.close()
        }
    }

    private fun <T> onEdt(panel: SftpPanel, action: (SftpPanel) -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching { action(panel) } }
        return requireNotNull(result).getOrThrow()
    }

    private fun await(timeout: Long, unit: TimeUnit, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun Container.containsLabel(text: String): Boolean {
        if (this is javax.swing.JLabel && this.text == text) return true
        return components.any { component ->
            (component is Container && component.containsLabel(text))
        }
    }

    private fun Container.containsTextField(text: String): Boolean {
        if (this is javax.swing.JTextField && this.text == text) return true
        return components.any { component ->
            (component is Container && component.containsTextField(text))
        }
    }

    private fun Container.tableRowCount(value: String): Boolean {
        if (this is javax.swing.JTable) {
            for (row in 0 until rowCount) {
                for (col in 0 until columnCount) {
                    if (getValueAt(row, col)?.toString() == value) return true
                }
            }
        }
        return components.any { component ->
            (component is Container && component.tableRowCount(value))
        }
    }

    private class FakeSftpClient(
        val entries: List<RemoteFile> = emptyList(),
    ) : SftpClient {
        override fun list(path: String): List<RemoteFile> = entries
        override fun stat(path: String): RemoteFile? = null
        override fun makeDirectory(path: String) = Unit
        override fun rename(from: String, to: String) = Unit
        override fun delete(path: String) = Unit
        override fun download(remotePath: String, localFile: Path, overwrite: Boolean) = Unit
        override fun upload(localFile: Path, remotePath: String, overwrite: Boolean) = Unit
        override fun close() = Unit
    }
}
