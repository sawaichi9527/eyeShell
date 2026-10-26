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
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `shows the reason and retries when SFTP is unavailable`() {
        val panelRef = arrayOfNulls<SftpPanel>(1)
        val retries = java.util.concurrent.atomic.AtomicInteger()

        SwingUtilities.invokeAndWait {
            panelRef[0] = SftpPanel()
        }
        val panel = requireNotNull(panelRef[0])

        SwingUtilities.invokeAndWait {
            panel.showUnavailable("Could not open the SFTP subsystem: subsystem request failed") {
                retries.incrementAndGet()
            }
        }
        assertTrue(onEdt(panel) { it.containsLabel("SFTP is unavailable for this session.") })
        assertTrue(onEdt(panel) { it.containsText("subsystem request failed") })

        val retry = onEdt(panel) { panel.findByName("sftpRetry") as javax.swing.JButton }
        SwingUtilities.invokeAndWait { retry.doClick() }
        org.junit.jupiter.api.Assertions.assertEquals(1, retries.get())

        SwingUtilities.invokeAndWait { panel.bind(null) }
        assertTrue(onEdt(panel) { it.containsLabel("Connect to a host to browse remote files.") })
    }

    @Test
    fun `shows an opening state while the subsystem is being created`() {
        val panelRef = arrayOfNulls<SftpPanel>(1)
        SwingUtilities.invokeAndWait { panelRef[0] = SftpPanel() }
        val panel = requireNotNull(panelRef[0])

        SwingUtilities.invokeAndWait { panel.showLoading() }

        assertTrue(onEdt(panel) { it.containsLabel("Opening SFTP...") })
    }

    @Test
    fun `starts in the server home directory and reports the entry count`() {
        val client = FakeSftpClient(
            entries = listOf(
                RemoteFile("notes.txt", isDirectory = false, sizeBytes = 4, permissions = "-rw-r--r--", owner = "u", group = "g"),
            ),
            home = "/home/eye",
        )
        val controller = SftpController(client)
        lateinit var panel: SftpPanel
        SwingUtilities.invokeAndWait {
            panel = SftpPanel()
            panel.bind(controller)
        }

        try {
            assertTrue(await(5, TimeUnit.SECONDS) { client.listedPaths.contains("/home/eye") }, "home was not listed")
            assertTrue(onEdt(panel) { it.containsTextField("/home/eye") })
            assertTrue(onEdt(panel) { it.containsText("1 entries in /home/eye") })
            assertTrue(onEdt(panel) { it.tableRowCount("notes.txt") })
        } finally {
            controller.close()
        }
    }

    @Test
    fun `reports an empty directory and a listing failure inline`() {
        lateinit var panel: SftpPanel
        val empty = SftpController(FakeSftpClient())
        SwingUtilities.invokeAndWait {
            panel = SftpPanel()
            panel.bind(empty)
        }
        assertTrue(await(5, TimeUnit.SECONDS) { onEdt(panel) { it.containsText("is empty") } }, "empty state not shown")
        empty.close()

        val failing = SftpController(FakeSftpClient(failList = true))
        SwingUtilities.invokeAndWait { panel.bind(failing) }
        try {
            assertTrue(
                await(5, TimeUnit.SECONDS) { onEdt(panel) { it.containsText("Could not list") } },
                "listing failure was not reported inline",
            )
            assertTrue(onEdt(panel) { it.containsText("Permission denied") })
        } finally {
            failing.close()
        }
    }

    @Test
    fun `lays the file list out above the transfer table`() {
        val client = FakeSftpClient(
            entries = listOf(
                RemoteFile("notes.txt", isDirectory = false, sizeBytes = 4, permissions = "-rw-r--r--", owner = "u", group = "g"),
            ),
        )
        val controller = SftpController(client)
        lateinit var panel: SftpPanel
        SwingUtilities.invokeAndWait {
            panel = SftpPanel()
            panel.bind(controller)
        }

        try {
            assertTrue(await(5, TimeUnit.SECONDS) { onEdt(panel) { it.tableRowCount("notes.txt") } })
            val fileListHeight = onEdt(panel) {
                panel.size = java.awt.Dimension(1040, 300)
                layoutRecursively(panel)
                val fileScroll = (panel.findByName("sftpTable") as javax.swing.JTable).parent.parent as javax.swing.JScrollPane
                fileScroll.height
            }
            assertTrue(
                fileListHeight > 40,
                "The transfer table must not squeeze the file list; height was $fileListHeight",
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun `entering a subdirectory resolves against the current directory`() {
        val client = FakeSftpClient(
            entriesByPath = mapOf(
                "/home" to listOf(dir("eye")),
                "/home/eye" to listOf(file("notes.txt")),
                "/eye" to listOf(file("wrong.txt")),
            ),
            home = "/home",
        )
        val controller = SftpController(client)
        lateinit var panel: SftpPanel
        SwingUtilities.invokeAndWait {
            panel = SftpPanel()
            panel.bind(controller)
        }

        try {
            assertTrue(await(5, TimeUnit.SECONDS) { onEdt(panel) { it.tableRowCount("eye/") } })

            val table = onEdt(panel) { panel.findByName("sftpTable") as javax.swing.JTable }
            SwingUtilities.invokeAndWait {
                table.setRowSelectionInterval(0, 0)
                val rect = table.getCellRect(0, 0, true)
                val event = java.awt.event.MouseEvent(
                    table,
                    java.awt.event.MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    rect.x + 5,
                    rect.y + 5,
                    2,
                    false,
                    java.awt.event.MouseEvent.BUTTON1,
                )
                table.mouseListeners.forEach { it.mouseClicked(event) }
            }

            assertTrue(
                await(5, TimeUnit.SECONDS) { client.listedPaths.contains("/home/eye") },
                "Double-click must list /home/eye, not /eye: ${client.listedPaths}",
            )
            assertTrue(onEdt(panel) { it.containsTextField("/home/eye") })
            assertTrue(await(5, TimeUnit.SECONDS) { onEdt(panel) { it.tableRowCount("notes.txt") } })
            org.junit.jupiter.api.Assertions.assertFalse(client.listedPaths.contains("/eye"))
        } finally {
            controller.close()
        }
    }

    @Test
    fun `a typed relative path is resolved against the current directory`() {
        val client = FakeSftpClient(
            entriesByPath = mapOf(
                "/home" to listOf(dir("eye")),
                "/home/eye" to listOf(file("notes.txt")),
            ),
            home = "/home",
        )
        val controller = SftpController(client)
        lateinit var panel: SftpPanel
        SwingUtilities.invokeAndWait {
            panel = SftpPanel()
            panel.bind(controller)
        }

        try {
            assertTrue(await(5, TimeUnit.SECONDS) { onEdt(panel) { it.tableRowCount("eye/") } })
            val field = onEdt(panel) { panel.findByName("sftpPath") as javax.swing.JTextField }
            SwingUtilities.invokeAndWait {
                field.text = "eye"
                field.postActionEvent()
            }
            assertTrue(await(5, TimeUnit.SECONDS) { client.listedPaths.contains("/home/eye") })
        } finally {
            controller.close()
        }
    }

    @Test
    fun `download button writes the selected file to the chosen location`() {
        val client = FakeSftpClient(entries = listOf(dir("logs"), file("notes.txt")))
        val controller = SftpController(client)
        val target = temporaryDirectory.resolve("notes-download.txt")
        lateinit var panel: SftpPanel

        SwingUtilities.invokeAndWait {
            panel = SftpPanel { target }
            panel.bind(controller)
        }

        try {
            assertTrue(await(5, TimeUnit.SECONDS) { onEdt(panel) { it.tableRowCount("notes.txt") } })
            val button = onEdt(panel) { panel.findByName("sftpDownload") as javax.swing.JButton }
            assertFalse(onEdt(panel) { button.isEnabled }, "Download must stay disabled without a selection")

            SwingUtilities.invokeAndWait {
                val table = panel.findByName("sftpTable") as javax.swing.JTable
                table.setRowSelectionInterval(0, 0)
            }
            assertFalse(onEdt(panel) { button.isEnabled }, "A selected directory must not enable Download")

            SwingUtilities.invokeAndWait {
                val table = panel.findByName("sftpTable") as javax.swing.JTable
                table.setRowSelectionInterval(1, 1)
                button.doClick()
            }

            assertTrue(
                await(5, TimeUnit.SECONDS) { Files.exists(target) && Files.readString(target).contains("/notes.txt") },
                "The selected file was not downloaded",
            )
            assertTrue(onEdt(panel) { it.tableRowCount("100%") })
            assertTrue(onEdt(panel) { it.tableRowCount("COMPLETED") })
        } finally {
            controller.close()
        }
    }

    @Test
    fun `clear transfers removes the finished upload history`() {
        val local = temporaryDirectory.resolve("clear.txt")
        Files.writeString(local, "clear me")
        val client = FakeSftpClient()
        lateinit var panel: SftpPanel
        lateinit var controller: SftpController

        SwingUtilities.invokeAndWait {
            panel = SftpPanel()
            controller = SftpController(client)
            panel.bind(controller)
        }

        try {
            SwingUtilities.invokeAndWait {
                val support = TransferHandler.TransferSupport(panel, fileListTransferable(listOf(local.toFile())))
                panel.transferHandler.importData(support)
            }
            assertTrue(await(5, TimeUnit.SECONDS) { onEdt(panel) { it.tableRowCount("COMPLETED") } })

            val clear = onEdt(panel) { panel.findByName("sftpClearTransfers") as javax.swing.JButton }
            assertTrue(onEdt(panel) { clear.isEnabled }, "Clear Transfers must enable once a transfer finished")
            SwingUtilities.invokeAndWait { clear.doClick() }

            assertFalse(onEdt(panel) { it.tableRowCount("COMPLETED") })
            assertFalse(onEdt(panel) { it.tableRowCount("clear.txt") })
            assertFalse(onEdt(panel) { clear.isEnabled })
            assertTrue(controller.jobsSnapshot().isEmpty())
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

    private fun dir(name: String): RemoteFile =
        RemoteFile(name, isDirectory = true, sizeBytes = 0, permissions = "drwxr-xr-x", owner = "u", group = "g")

    private fun file(name: String): RemoteFile =
        RemoteFile(name, isDirectory = false, sizeBytes = 4, permissions = "-rw-r--r--", owner = "u", group = "g")

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

    private fun Container.findByName(componentName: String): java.awt.Component? {
        if (name == componentName) return this
        return components.firstNotNullOfOrNull { component ->
            if (component is Container) component.findByName(componentName) else null
        }
    }

    private fun layoutRecursively(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach { child ->
            if (child.isVisible) layoutRecursively(child)
        }
    }

    private fun Container.containsTextField(text: String): Boolean {
        if (this is javax.swing.JTextField && this.text == text) return true
        return components.any { component ->
            (component is Container && component.containsTextField(text))
        }
    }

    private fun Container.containsText(text: String): Boolean {
        if (this is javax.swing.JLabel && this.text.contains(text)) return true
        if (this is javax.swing.text.JTextComponent && this.text.contains(text)) return true
        return components.any { component ->
            (component is Container && component.containsText(text))
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
        val entriesByPath: Map<String, List<RemoteFile>> = emptyMap(),
        val home: String? = null,
        private val failList: Boolean = false,
    ) : SftpClient {
        val uploaded = mutableMapOf<String, String>()
        val downloaded = mutableMapOf<String, Path>()
        val listedPaths = mutableListOf<String>()

        override fun list(path: String): List<RemoteFile> {
            listedPaths += path
            if (failList) throw java.io.IOException("Permission denied: $path")
            return entriesByPath[path] ?: entries
        }

        override fun homeDirectory(): String? = home
        override fun stat(path: String): RemoteFile? = null
        override fun makeDirectory(path: String) = Unit
        override fun rename(from: String, to: String) = Unit
        override fun delete(path: String) = Unit
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

        override fun download(
            remotePath: String,
            localFile: Path,
            overwrite: Boolean,
            onProgress: (Long, Long) -> Unit,
        ) {
            val content = "content of $remotePath"
            Files.writeString(localFile, content)
            downloaded[remotePath] = localFile
            onProgress(content.length.toLong(), content.length.toLong())
        }

        override fun close() = Unit
    }
}
