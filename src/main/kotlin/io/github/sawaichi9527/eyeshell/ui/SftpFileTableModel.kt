package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.platform.I18n
import io.github.sawaichi9527.eyeshell.sftp.RemoteFile
import io.github.sawaichi9527.eyeshell.sftp.SftpFormatting
import javax.swing.table.AbstractTableModel

/**
 * Table model for the SFTP file browser. It keeps the [RemoteFile] entries themselves instead of
 * formatted strings, so selection no longer has to parse cell text back into a file and the Size and
 * Modified columns can sort numerically.
 */
class SftpFileTableModel : AbstractTableModel() {
    private val files = mutableListOf<RemoteFile>()

    fun setFiles(newFiles: List<RemoteFile>) {
        files.clear()
        files.addAll(newFiles)
        fireTableDataChanged()
    }

    /** The entry shown at [modelRow], or null when the index is stale (for example after a reload). */
    fun fileAt(modelRow: Int): RemoteFile? = files.getOrNull(modelRow)

    override fun getRowCount(): Int = files.size

    override fun getColumnCount(): Int = COLUMN_KEYS.size

    override fun getColumnName(column: Int): String = I18n.t(COLUMN_KEYS[column])

    override fun getColumnClass(column: Int): Class<*> = when (column) {
        SIZE_COLUMN, MODIFIED_COLUMN -> Long::class.javaObjectType
        else -> String::class.java
    }

    override fun isCellEditable(row: Int, column: Int): Boolean = false

    override fun getValueAt(row: Int, column: Int): Any {
        val file = files[row]
        return when (column) {
            NAME_COLUMN -> SftpFormatting.displayName(file)
            SIZE_COLUMN -> file.sizeBytes
            TYPE_COLUMN -> SftpFormatting.typeLabel(file)
            // Epoch millis keeps the column sortable; the renderer formats it, and -1 means "the
            // server did not send a modification time".
            MODIFIED_COLUMN -> file.modifiedTime?.toEpochMilli() ?: -1L
            PERMISSIONS_COLUMN -> file.permissions
            else -> SftpFormatting.formatOwnerGroup(file)
        }
    }

    companion object {
        const val NAME_COLUMN = 0
        const val SIZE_COLUMN = 1
        const val TYPE_COLUMN = 2
        const val MODIFIED_COLUMN = 3
        const val PERMISSIONS_COLUMN = 4
        const val OWNER_GROUP_COLUMN = 5

        val COLUMN_KEYS = listOf(
            "column.name",
            "column.size",
            "column.type",
            "column.modified",
            "column.permissions",
            "column.ownerGroup",
        )
    }
}
