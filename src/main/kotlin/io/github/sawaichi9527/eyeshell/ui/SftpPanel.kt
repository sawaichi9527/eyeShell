package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.sftp.RemoteFile
import io.github.sawaichi9527.eyeshell.sftp.SftpController
import io.github.sawaichi9527.eyeshell.sftp.SftpTransferRequest
import io.github.sawaichi9527.eyeshell.sftp.TransferDirection
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
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
import javax.swing.table.DefaultTableModel

class SftpPanel : JPanel(BorderLayout()) {
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
    private val newFolderButton = JButton("New Folder")
    private val renameButton = JButton("Rename")
    private val deleteButton = JButton("Delete")
    private val emptyLabel = JLabel("Connect to a host to browse remote files.", SwingConstants.CENTER).apply {
        name = "sftpEmpty"
    }
    private val tableModel = object : DefaultTableModel(arrayOf("Name", "Size", "Permissions"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val fileTable = JTable(tableModel).apply {
        name = "sftpTable"
        setRowHeight(20)
        autoCreateRowSorter = true
    }
    private val transferModel = object : DefaultTableModel(arrayOf("Direction", "Path", "Status"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val transferTable = JTable(transferModel).apply {
        name = "sftpTransferTable"
        setRowHeight(18)
    }

    init {
        name = "sftpPanel"
        cards.add(emptyLabel, "disconnected")
        cards.add(buildConnectedPanel(), "connected")
        add(cards, BorderLayout.CENTER)
        showDisconnected()
    }

    fun bind(controller: SftpController?) {
        this.controller = controller
        if (controller == null) {
            showDisconnected()
            return
        }
        controller.setListener { SwingUtilities.invokeLater { refreshTransfers() } }
        showConnected()
        refresh()
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
            add(uploadButton)
            add(newFolderButton)
            add(renameButton)
            add(deleteButton)
        }
        refreshButton.addActionListener { refresh() }
        upButton.addActionListener { navigateUp() }
        uploadButton.addActionListener { upload() }
        newFolderButton.addActionListener { newFolder() }
        renameButton.addActionListener { rename() }
        deleteButton.addActionListener { delete() }
        fileTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.clickCount == 2) {
                    selectedFile()?.let { file ->
                        if (file.isDirectory) navigate(file.name) else download(file)
                    }
                }
            }
        })
        val center = JPanel(BorderLayout()).apply {
            add(JScrollPane(fileTable), BorderLayout.CENTER)
            add(JScrollPane(transferTable), BorderLayout.SOUTH)
            transferTable.preferredSize = Dimension(0, 90)
        }
        add(toolbar, BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
        add(actions, BorderLayout.SOUTH)
    }

    private fun showDisconnected() {
        cardLayout.show(cards, "disconnected")
        revalidate()
        repaint()
    }

    private fun showConnected() {
        cardLayout.show(cards, "connected")
        revalidate()
        repaint()
    }

    private fun refresh() {
        val active = controller ?: return
        active.list(currentDirectory) { result ->
            SwingUtilities.invokeLater {
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
                }.onFailure { failure ->
                    JOptionPane.showMessageDialog(this, failure.message, "Could not list directory", JOptionPane.ERROR_MESSAGE)
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
                job.status.name,
            ))
        }
    }

    private fun navigate(path: String) {
        currentDirectory = normalize(path)
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
            enqueueTransfer(active, selected.toPath(), "${currentDirectory}/${selected.name}", TransferDirection.UPLOAD)
        }
    }

    private fun download(file: RemoteFile) {
        val active = controller ?: return
        val chooser = JFileChooser().apply { selectedFile = File(file.name) }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        enqueueTransfer(active, chooser.selectedFile.toPath(), "${currentDirectory}/${file.name}", TransferDirection.DOWNLOAD)
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
        active.makeDirectory("${currentDirectory}/$name") { result ->
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
        active.rename("${currentDirectory}/${file.name}", "${currentDirectory}/$newName") { result ->
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
        active.delete("${currentDirectory}/${file.name}") { result ->
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
