package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.sftp.RemoteFile
import io.github.sawaichi9527.eyeshell.sftp.SftpController
import io.github.sawaichi9527.eyeshell.sftp.SftpTransferRequest
import io.github.sawaichi9527.eyeshell.sftp.TransferDirection
import io.github.sawaichi9527.eyeshell.sftp.TransferStatus
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Files
import java.text.DecimalFormat
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import javax.swing.table.DefaultTableModel

class SftpPanel(
    /** Test seam: when present, the Download button writes here instead of opening a save dialog. */
    private val downloadTargetChooser: ((RemoteFile) -> java.nio.file.Path?)? = null,
) : JPanel(BorderLayout()) {
    private var controller: SftpController? = null
    private var currentDirectory = "/"
    private val cardLayout = CardLayout()
    private val cards = JPanel(cardLayout)
    private val pathField = JTextField("/").apply {
        name = "sftpPath"
    }
    private val refreshButton = JButton("Refresh")
    private val upButton = JButton("Up")
    private val uploadButton = JButton("Upload")
    private val downloadButton = JButton("Download").apply {
        name = "sftpDownload"
        toolTipText = "Download the selected file"
        isEnabled = false
    }
    private val newFolderButton = JButton("New Folder")
    private val renameButton = JButton("Rename").apply { isEnabled = false }
    private val deleteButton = JButton("Delete").apply { isEnabled = false }
    private val clearTransfersButton = JButton("Clear Transfers").apply {
        name = "sftpClearTransfers"
        toolTipText = "Clear finished uploads and downloads from the history"
        isEnabled = false
    }
    private val emptyLabel = JLabel("Connect to a host to browse remote files.", SwingConstants.CENTER).apply {
        name = "sftpEmpty"
    }
    private val unavailableMessage = javax.swing.JTextArea().apply {
        name = "sftpUnavailableMessage"
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        columns = 28
    }
    private val retryButton = JButton("Retry").apply {
        name = "sftpRetry"
    }
    private val unavailablePanel = JPanel(BorderLayout()).apply {
        add(JLabel("SFTP is unavailable for this session.", SwingConstants.CENTER), BorderLayout.NORTH)
        add(JScrollPane(unavailableMessage), BorderLayout.CENTER)
        add(JPanel().apply { add(retryButton) }, BorderLayout.SOUTH)
    }
    private val loadingLabel = JLabel("Opening SFTP...", SwingConstants.CENTER).apply {
        name = "sftpLoading"
    }
    private val tableModel = object : DefaultTableModel(arrayOf("Name", "Size", "Permissions"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val fileTable = JTable(tableModel).apply {
        name = "sftpTable"
        setRowHeight(20)
        autoCreateRowSorter = true
    }
    private val transferModel = object : DefaultTableModel(arrayOf("Direction", "Path", "Progress", "Status"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val transferTable = JTable(transferModel).apply {
        name = "sftpTransferTable"
        setRowHeight(18)
    }
    private val statusLabel = JLabel(" ").apply {
        name = "sftpStatus"
    }
    private var rememberedController: SftpController? = null
    private val dropHandler = object : TransferHandler() {
        override fun canImport(support: TransferHandler.TransferSupport): Boolean =
            controller != null && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

        override fun importData(support: TransferHandler.TransferSupport): Boolean {
            val active = controller ?: return false
            if (!support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
            val files = runCatching {
                @Suppress("UNCHECKED_CAST")
                support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
            }.getOrNull().orEmpty()
            val uploadable = files.filter { it.isFile }
            val rejected = files.filterNot { it.isFile }
            if (rejected.isNotEmpty()) {
                JOptionPane.showMessageDialog(
                    this@SftpPanel,
                    "Only files can be uploaded by drag and drop.",
                    "Unsupported drop",
                    JOptionPane.INFORMATION_MESSAGE,
                )
            }
            uploadable.forEach { file ->
                enqueueTransfer(active, file.toPath(), remotePathFor(file.name), TransferDirection.UPLOAD)
            }
            return uploadable.isNotEmpty()
        }
    }

    init {
        name = "sftpPanel"
        cards.add(emptyLabel, "disconnected")
        cards.add(buildConnectedPanel(), "connected")
        cards.add(unavailablePanel, "unavailable")
        cards.add(loadingLabel, "loading")
        add(cards, BorderLayout.CENTER)
        transferHandler = dropHandler
        fileTable.transferHandler = dropHandler
        showDisconnected()
    }

    fun bind(controller: SftpController?) {
        val sameController = controller != null && controller === rememberedController
        rememberedController = controller
        this.controller = controller
        refreshTransfers()
        if (controller == null) {
            showDisconnected()
            return
        }
        controller.setListener { SwingUtilities.invokeLater { refreshTransfers() } }
        showConnected()
        if (!sameController) {
            tableModel.setRowCount(0)
            updateActionEnablement()
            currentDirectory = "/"
            pathField.text = "/"
            statusLabel.text = "Opening the remote home directory..."
            controller.homeDirectory { home ->
                SwingUtilities.invokeLater {
                    if (this.controller !== controller) return@invokeLater
                    if (!home.isNullOrBlank() && home != "/") {
                        currentDirectory = normalize(home)
                        pathField.text = currentDirectory
                    }
                    refresh()
                }
            }
        } else {
            refresh()
        }
    }

    /**
     * Replaces the empty state with the reason the SFTP subsystem could not be opened, so a
     * server without SFTP no longer looks like an unconnected host.
     */
    fun showUnavailable(reason: String, retry: () -> Unit) {
        controller = null
        unavailableMessage.text = reason
        unavailableMessage.caretPosition = 0
        retryButton.actionListeners.toList().forEach(retryButton::removeActionListener)
        retryButton.addActionListener { retry() }
        showCard("unavailable")
    }

    /** Clears any previous session's listing while a newly selected session opens its subsystem. */
    fun showLoading() {
        controller = null
        showCard("loading")
    }

    private fun showCard(name: String) {
        cardLayout.show(cards, name)
        revalidate()
        repaint()
    }

    private fun buildConnectedPanel(): JPanel = JPanel(BorderLayout()).apply {
        val toolbar = JPanel(BorderLayout()).apply {
            add(JPanel().apply {
                add(pathField)
                pathField.columns = 14
                pathField.addActionListener { navigate(pathField.text) }
            }, BorderLayout.CENTER)
            add(JPanel().apply {
                add(refreshButton)
                add(upButton)
            }, BorderLayout.EAST)
        }
        val actions = JPanel().apply {
            add(downloadButton)
            add(uploadButton)
            add(newFolderButton)
            add(renameButton)
            add(deleteButton)
            add(clearTransfersButton)
        }
        refreshButton.addActionListener { refresh() }
        upButton.addActionListener { navigateUp() }
        uploadButton.addActionListener { upload() }
        downloadButton.addActionListener { downloadSelected() }
        newFolderButton.addActionListener { newFolder() }
        renameButton.addActionListener { rename() }
        deleteButton.addActionListener { delete() }
        clearTransfersButton.addActionListener {
            controller?.clearFinishedJobs()
            refreshTransfers()
        }
        fileTable.selectionModel.addListSelectionListener { updateActionEnablement() }
        fileTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.clickCount == 2) {
                    selectedFile()?.let { file ->
                        if (file.isDirectory) navigate(remotePathFor(file.name)) else download(file)
                    }
                }
            }
        })
        val center = JPanel(BorderLayout()).apply {
            add(statusLabel, BorderLayout.NORTH)
            add(JScrollPane(fileTable), BorderLayout.CENTER)
            add(JScrollPane(transferTable).apply {
                preferredSize = Dimension(0, 84)
                minimumSize = Dimension(0, 60)
            }, BorderLayout.SOUTH)
        }
        add(toolbar, BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
        add(actions, BorderLayout.SOUTH)
    }

    private fun showDisconnected() {
        showCard("disconnected")
    }

    private fun showConnected() {
        showCard("connected")
    }

    private fun refresh() {
        val active = controller ?: return
        pathField.text = currentDirectory
        statusLabel.text = "Listing $currentDirectory ..."
        active.list(currentDirectory) { result ->
            SwingUtilities.invokeLater {
                if (controller !== active) return@invokeLater
                result.onSuccess { files ->
                    tableModel.setRowCount(0)
                    files.forEach { file ->
                        tableModel.addRow(arrayOf<Any>(
                            if (file.isDirectory) "${file.name}/" else file.name,
                            formatSize(file.sizeBytes),
                            file.permissions,
                        ))
                    }
                    pathField.text = currentDirectory
                    statusLabel.text = if (files.isEmpty()) {
                        "$currentDirectory is empty."
                    } else {
                        "${files.size} entries in $currentDirectory"
                    }
                    updateActionEnablement()
                }.onFailure { failure ->
                    tableModel.setRowCount(0)
                    updateActionEnablement()
                    statusLabel.text = "Could not list $currentDirectory: ${failure.message ?: failure.javaClass.simpleName}"
                }
            }
        }
    }

    private fun refreshTransfers() {
        val jobs = controller?.jobsSnapshot().orEmpty()
        transferModel.setRowCount(0)
        jobs.forEach { job ->
            transferModel.addRow(arrayOf<Any>(
                job.request.direction.name,
                if (job.request.direction == TransferDirection.UPLOAD) job.request.localFile.fileName.toString() else job.request.remotePath,
                "${job.progressPercent}%",
                job.status.name,
            ))
        }
        clearTransfersButton.isEnabled = jobs.any { job ->
            job.status == TransferStatus.COMPLETED ||
                job.status == TransferStatus.FAILED ||
                job.status == TransferStatus.CANCELLED
        }
    }

    private fun navigate(path: String) {
        val trimmed = path.trim()
        currentDirectory = when {
            trimmed.isEmpty() -> "/"
            trimmed.startsWith("/") -> normalize(trimmed)
            // A typed or double-clicked relative name is resolved against the current directory.
            else -> remotePathFor(trimmed)
        }
        refresh()
    }

    private fun navigateUp() {
        if (currentDirectory == "/") return
        val trimmed = currentDirectory.trimEnd('/')
        val parent = trimmed.substringBeforeLast('/', "").ifEmpty { "/" }
        currentDirectory = parent
        refresh()
    }

    private fun upload() {
        val active = controller ?: return
        val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.FILES_ONLY }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val selected = chooser.selectedFile
        if (selected.isFile) {
            enqueueTransfer(active, selected.toPath(), remotePathFor(selected.name), TransferDirection.UPLOAD)
        }
    }

    private fun download(file: RemoteFile) {
        val active = controller ?: return
        if (file.isDirectory) {
            statusLabel.text = "Only files can be downloaded; double-click a folder to open it."
            return
        }
        val target = downloadTargetChooser?.invoke(file) ?: askDownloadTarget(file) ?: return
        enqueueTransfer(active, target, remotePathFor(file.name), TransferDirection.DOWNLOAD)
    }

    private fun downloadSelected() {
        val file = selectedFile()
        if (file == null) {
            statusLabel.text = "Select a file to download."
            return
        }
        download(file)
    }

    private fun askDownloadTarget(file: RemoteFile): java.nio.file.Path? {
        val chooser = JFileChooser().apply { selectedFile = File(file.name) }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return null
        return chooser.selectedFile.toPath()
    }

    private fun updateActionEnablement() {
        val file = selectedFile()
        downloadButton.isEnabled = file != null && !file.isDirectory
        renameButton.isEnabled = file != null
        deleteButton.isEnabled = file != null
    }

    private fun enqueueTransfer(active: SftpController, local: java.nio.file.Path, remote: String, direction: TransferDirection) {
        val overwrite = if (direction == TransferDirection.DOWNLOAD && Files.exists(local)) {
            JOptionPane.showConfirmDialog(
                this,
                "Replace the existing file?\n$local",
                "Confirm overwrite",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
            ) == JOptionPane.YES_OPTION
        } else {
            true
        }
        if (!overwrite) return
        try {
            active.enqueue(SftpTransferRequest(remote, local, direction, overwrite = true))
        } catch (failure: Exception) {
            JOptionPane.showMessageDialog(this, failure.message, "Transfer rejected", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun newFolder() {
        val active = controller ?: return
        val name = JOptionPane.showInputDialog(this, "Folder name:", "New Folder", JOptionPane.PLAIN_MESSAGE) ?: return
        if (name.isBlank()) return
        active.makeDirectory(remotePathFor(name)) { result ->
            SwingUtilities.invokeLater {
                result.onSuccess { refresh() }
                    .onFailure { failure -> JOptionPane.showMessageDialog(this, failure.message, "Could not create folder", JOptionPane.ERROR_MESSAGE) }
            }
        }
    }

    private fun rename() {
        val active = controller ?: return
        val file = selectedFile() ?: return
        val newName = JOptionPane.showInputDialog(this, "New name:", file.name) ?: return
        if (newName.isBlank()) return
        active.rename(remotePathFor(file.name), remotePathFor(newName)) { result ->
            SwingUtilities.invokeLater {
                result.onSuccess { refresh() }
                    .onFailure { failure -> JOptionPane.showMessageDialog(this, failure.message, "Could not rename", JOptionPane.ERROR_MESSAGE) }
            }
        }
    }

    private fun delete() {
        val active = controller ?: return
        val file = selectedFile() ?: return
        if (JOptionPane.showConfirmDialog(
                this,
                "Delete '${file.name}'?",
                "Confirm delete",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
            ) != JOptionPane.YES_OPTION
        ) {
            return
        }
        active.delete(remotePathFor(file.name)) { result ->
            SwingUtilities.invokeLater {
                result.onSuccess { refresh() }
                    .onFailure { failure -> JOptionPane.showMessageDialog(this, failure.message, "Could not delete", JOptionPane.ERROR_MESSAGE) }
            }
        }
    }

    private fun selectedFile(): RemoteFile? {
        val row = fileTable.selectedRow
        if (row < 0) return null
        val modelRow = fileTable.convertRowIndexToModel(row)
        val nameCell = tableModel.getValueAt(modelRow, 0).toString()
        val isDirectory = nameCell.endsWith("/")
        return RemoteFile(
            name = nameCell.removeSuffix("/"),
            isDirectory = isDirectory,
            sizeBytes = parseSize(tableModel.getValueAt(modelRow, 1).toString()),
            permissions = tableModel.getValueAt(modelRow, 2).toString(),
            owner = null,
            group = null,
        )
    }

    private fun normalize(path: String): String {
        val trimmed = path.trim().ifEmpty { "/" }
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    private fun remotePathFor(name: String): String =
        if (currentDirectory == "/") "/$name" else "${currentDirectory.trimEnd('/')}/$name"

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return SIZE_FORMAT.format(bytes / 1024.0) + " KB"
        if (bytes < 1024L * 1024 * 1024) return SIZE_FORMAT.format(bytes / 1024.0 / 1024.0) + " MB"
        return SIZE_FORMAT.format(bytes / 1024.0 / 1024.0 / 1024.0) + " GB"
    }

    private fun parseSize(text: String): Long {
        if (text.endsWith("B")) return text.removeSuffix("B").trim().toLongOrNull() ?: 0L
        if (text.endsWith("KB")) return (text.removeSuffix("KB").trim().toDoubleOrNull() ?: 0.0).times(1024).toLong()
        if (text.endsWith("MB")) return (text.removeSuffix("MB").trim().toDoubleOrNull() ?: 0.0).times(1024 * 1024).toLong()
        if (text.endsWith("GB")) return (text.removeSuffix("GB").trim().toDoubleOrNull() ?: 0.0).times(1024 * 1024 * 1024).toLong()
        return 0L
    }

    private companion object {
        private val SIZE_FORMAT = DecimalFormat("#,##0.0")
    }
}
