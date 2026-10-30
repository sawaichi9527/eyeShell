package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.sftp.RemoteFile
import io.github.sawaichi9527.eyeshell.sftp.SftpController
import io.github.sawaichi9527.eyeshell.sftp.SftpFormatting
import io.github.sawaichi9527.eyeshell.sftp.SftpTransferRequest
import io.github.sawaichi9527.eyeshell.sftp.TransferDirection
import io.github.sawaichi9527.eyeshell.sftp.TransferStatus
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Files
import io.github.sawaichi9527.eyeshell.platform.I18n
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
    private val refreshButton = JButton(I18n.t("sftp.refresh"))
    private val upButton = JButton(I18n.t("sftp.up"))
    private val uploadButton = JButton(I18n.t("sftp.upload"))
    private val downloadButton = JButton(I18n.t("sftp.download")).apply {
        name = "sftpDownload"
        toolTipText = I18n.t("sftp.download.hint")
        isEnabled = false
    }
    private val newFolderButton = JButton(I18n.t("sftp.newFolder"))
    private val renameButton = JButton(I18n.t("sftp.rename")).apply { isEnabled = false }
    private val deleteButton = JButton(I18n.t("sftp.delete")).apply { isEnabled = false }
    private val clearTransfersButton = JButton(I18n.t("sftp.clearTransfers")).apply {
        name = "sftpClearTransfers"
        toolTipText = I18n.t("sftp.clearTransfers.hint")
        isEnabled = false
    }
    private val emptyLabel = JLabel(I18n.t("sftp.empty"), SwingConstants.CENTER).apply {
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
    private val retryButton = JButton(I18n.t("sftp.retry")).apply {
        name = "sftpRetry"
    }
    private val unavailablePanel = JPanel(BorderLayout()).apply {
        add(JLabel(I18n.t("sftp.unavailable"), SwingConstants.CENTER), BorderLayout.NORTH)
        add(JScrollPane(unavailableMessage), BorderLayout.CENTER)
        add(JPanel().apply { add(retryButton) }, BorderLayout.SOUTH)
    }
    private val loadingLabel = JLabel(I18n.t("sftp.opening"), SwingConstants.CENTER).apply {
        name = "sftpLoading"
    }
    private val sizeRenderer = object : javax.swing.table.DefaultTableCellRenderer() {
        init { horizontalAlignment = SwingConstants.RIGHT }

        override fun setValue(value: Any?) {
            text = SftpFormatting.formatSize((value as? Long) ?: -1L)
        }
    }
    private val modifiedRenderer = object : javax.swing.table.DefaultTableCellRenderer() {
        init { horizontalAlignment = SwingConstants.CENTER }

        override fun setValue(value: Any?) {
            val millis = (value as? Long) ?: -1L
            text = if (millis < 0) SftpFormatting.UNKNOWN else SftpFormatting.formatModified(java.time.Instant.ofEpochMilli(millis))
        }
    }
    private val tableModel = SftpFileTableModel()
    private val fileTable = JTable(tableModel).apply {
        name = "sftpTable"
        setRowHeight(20)
        autoCreateRowSorter = true
        autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        tableHeader.reorderingAllowed = false
        setDefaultRenderer(Long::class.javaObjectType, sizeRenderer)
        columnModel.getColumn(SftpFileTableModel.NAME_COLUMN).preferredWidth = 260
        columnModel.getColumn(SftpFileTableModel.SIZE_COLUMN).apply {
            preferredWidth = 90
            maxWidth = 120
        }
        columnModel.getColumn(SftpFileTableModel.TYPE_COLUMN).apply {
            preferredWidth = 80
            maxWidth = 110
        }
        columnModel.getColumn(SftpFileTableModel.MODIFIED_COLUMN).apply {
            preferredWidth = 130
            maxWidth = 170
            cellRenderer = modifiedRenderer
        }
        columnModel.getColumn(SftpFileTableModel.PERMISSIONS_COLUMN).apply {
            preferredWidth = 110
            maxWidth = 140
        }
        columnModel.getColumn(SftpFileTableModel.OWNER_GROUP_COLUMN).preferredWidth = 140
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
            tableModel.setFiles(emptyList())
            updateActionEnablement()
            currentDirectory = "/"
            pathField.text = "/"
            statusLabel.text = I18n.t("sftp.openingHome")
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

    /** Re-applies localized texts after the language changed. */
    fun relocalize() {
        refreshButton.text = I18n.t("sftp.refresh")
        upButton.text = I18n.t("sftp.up")
        uploadButton.text = I18n.t("sftp.upload")
        downloadButton.text = I18n.t("sftp.download")
        downloadButton.toolTipText = I18n.t("sftp.download.hint")
        newFolderButton.text = I18n.t("sftp.newFolder")
        renameButton.text = I18n.t("sftp.rename")
        deleteButton.text = I18n.t("sftp.delete")
        clearTransfersButton.text = I18n.t("sftp.clearTransfers")
        clearTransfersButton.toolTipText = I18n.t("sftp.clearTransfers.hint")
        emptyLabel.text = I18n.t("sftp.empty")
        loadingLabel.text = I18n.t("sftp.opening")
        retryButton.text = I18n.t("sftp.retry")
        getAccessibleContext().accessibleName = I18n.t("dock.sftp")
    }

    private fun showCard(name: String) {
        cardLayout.show(cards, name)
        revalidate()
        repaint()
    }

    private fun buildConnectedPanel(): JPanel = JPanel(BorderLayout()).apply {
        // The path field takes every pixel that Refresh/Up do not need, and starts at the left edge.
        val toolbar = JPanel(BorderLayout(6, 0)).apply {
            add(pathField, BorderLayout.CENTER)
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0)).apply {
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
        pathField.addActionListener { navigate(pathField.text) }
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
        statusLabel.text = I18n.t("sftp.listing", currentDirectory)
        active.list(currentDirectory) { result ->
            SwingUtilities.invokeLater {
                if (controller !== active) return@invokeLater
                result.onSuccess { files ->
                    tableModel.setFiles(files)
                    pathField.text = currentDirectory
                    statusLabel.text = if (files.isEmpty()) {
                        "$currentDirectory is empty."
                    } else {
                        "${files.size} entries in $currentDirectory"
                    }
                    updateActionEnablement()
                }.onFailure { failure ->
                    tableModel.setFiles(emptyList())
                    updateActionEnablement()
                    statusLabel.text = I18n.t("sftp.listFailed", currentDirectory, failure.message ?: failure.javaClass.simpleName)
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
            statusLabel.text = I18n.t("sftp.downloadFolderHint")
            return
        }
        val target = downloadTargetChooser?.invoke(file) ?: askDownloadTarget(file) ?: return
        enqueueTransfer(active, target, remotePathFor(file.name), TransferDirection.DOWNLOAD)
    }

    private fun downloadSelected() {
        val file = selectedFile()
        if (file == null) {
            statusLabel.text = I18n.t("sftp.selectFile")
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
                I18n.t("sftp.overwrite.message", local),
                I18n.t("sftp.overwrite.title"),
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
            JOptionPane.showMessageDialog(this, failure.message, I18n.t("sftp.transferRejected"), JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun newFolder() {
        val active = controller ?: return
        val name = JOptionPane.showInputDialog(this, I18n.t("sftp.newFolder.prompt"), I18n.t("sftp.newFolder.title"), JOptionPane.PLAIN_MESSAGE) ?: return
        if (name.isBlank()) return
        active.makeDirectory(remotePathFor(name)) { result ->
            SwingUtilities.invokeLater {
                result.onSuccess { refresh() }
                    .onFailure { failure -> JOptionPane.showMessageDialog(this, failure.message, I18n.t("sftp.createFolder.failed"), JOptionPane.ERROR_MESSAGE) }
            }
        }
    }

    private fun rename() {
        val active = controller ?: return
        val file = selectedFile() ?: return
        val newName = JOptionPane.showInputDialog(this, I18n.t("sftp.rename.prompt"), file.name) ?: return
        if (newName.isBlank()) return
        active.rename(remotePathFor(file.name), remotePathFor(newName)) { result ->
            SwingUtilities.invokeLater {
                result.onSuccess { refresh() }
                    .onFailure { failure -> JOptionPane.showMessageDialog(this, failure.message, I18n.t("sftp.rename.failed"), JOptionPane.ERROR_MESSAGE) }
            }
        }
    }

    private fun delete() {
        val active = controller ?: return
        val file = selectedFile() ?: return
        if (JOptionPane.showConfirmDialog(
                this,
                I18n.t("sftp.delete.message", file.name),
                I18n.t("sftp.delete.title"),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
            ) != JOptionPane.YES_OPTION
        ) {
            return
        }
        active.delete(remotePathFor(file.name)) { result ->
            SwingUtilities.invokeLater {
                result.onSuccess { refresh() }
                    .onFailure { failure -> JOptionPane.showMessageDialog(this, failure.message, I18n.t("sftp.delete.failed"), JOptionPane.ERROR_MESSAGE) }
            }
        }
    }

    private fun selectedFile(): RemoteFile? {
        val row = fileTable.selectedRow
        if (row < 0) return null
        return tableModel.fileAt(fileTable.convertRowIndexToModel(row))
    }

    private fun normalize(path: String): String {
        val trimmed = path.trim().ifEmpty { "/" }
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    private fun remotePathFor(name: String): String =
        if (currentDirectory == "/") "/$name" else "${currentDirectory.trimEnd('/')}/$name"
}
