package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.sftp.RemoteFile
import io.github.sawaichi9527.eyeshell.sftp.SftpClient
import io.github.sawaichi9527.eyeshell.sftp.SftpController
import java.awt.Container
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SftpPanelTest {
    @TempDir
    lateinit var temporaryDirectory: Path
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
    fun `dropping a local file uploads it to the current directory and shows progress`() {
        val local = temporaryDirectory.resolve("drop.txt")
        Files.writeString(local, "dropped content")
        val client = FakeSftpClient()
        lateinit var panel: SftpPanel
        lateinit var controller: SftpController

        SwingUtilities.invokeAndWait {
            panel = SftpPanel()
            controller = SftpController(client)
            panel.bind(controller)
        }

        try {
            val transferable = fileListTransferable(listOf(local.toFile()))
            SwingUtilities.invokeAndWait {
                val support = TransferHandler.TransferSupport(panel, transferable)
                assertTrue(panel.transferHandler.canImport(support))
                assertTrue(panel.transferHandler.importData(support))
            }

            assertTrue(
                await(5, TimeUnit.SECONDS) { client.uploaded["/drop.txt"] == "dropped content" },
                "dropped file was not uploaded to the current directory",
            )
            assertTrue(
                await(5, TimeUnit.SECONDS) { onEdt(panel) { it.tableRowCount("100%") } },
                "transfer table never showed 100%",
            )
            assertTrue(onEdt(panel) { it.tableRowCount("COMPLETED") })
        } finally {
            controller.close()
        }
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

    private fun fileListTransferable(files: List<File>): Transferable = object : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor

        override fun getTransferData(flavor: DataFlavor): Any = files
    }

    private class FakeSftpClient(
        val entries: List<RemoteFile> = emptyList(),
    ) : SftpClient {
        val uploaded = mutableMapOf<String, String>()

        override fun list(path: String): List<RemoteFile> = entries
        override fun stat(path: String): RemoteFile? = null
        override fun makeDirectory(path: String) = Unit
        override fun rename(from: String, to: String) = Unit
        override fun delete(path: String) = Unit
        override fun download(
            remotePath: String,
            localFile: Path,
            overwrite: Boolean,
            onProgress: (Long, Long) -> Unit,
        ) = Unit
        override fun upload(
            localFile: Path,
            remotePath: String,
            overwrite: Boolean,
            onProgress: (Long, Long) -> Unit,
        ) {
            val content = Files.readString(localFile)
            uploaded[remotePath] = content
            onProgress(content.length.toLong(), content.length.toLong())
        }
        override fun close() = Unit
    }
}
