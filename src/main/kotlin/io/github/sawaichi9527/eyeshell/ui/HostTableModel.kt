package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.storage.SavedAuthenticationMethod
import io.github.sawaichi9527.eyeshell.storage.SavedHost
import java.util.Locale
import javax.swing.table.AbstractTableModel

/** One visible row of the Saved hosts table: either a folder header or a host. */
internal sealed interface HostRow {
    data class Group(val name: String, val count: Int, val expanded: Boolean) : HostRow

    data class Host(val host: SavedHost) : HostRow
}

/**
 * Flattens the grouped host catalog into the rows a tree-table shows.
 *
 * A group is one row when collapsed and one row plus its hosts when expanded. A non-blank filter
 * matches hosts only (by name, host, port, user, authentication, or group), shows matching hosts
 * under their folder regardless of the collapsed state, and hides folders with no match.
 */
internal object HostTableRows {
    fun build(hosts: List<SavedHost>, expandedGroups: Set<String>, query: String): List<HostRow> {
        val groups = HostCatalogTree.build(hosts)
        val filter = query.trim()
        return groups.flatMap { group ->
            if (group.name == null) {
                val members = group.hosts.filter { matches(it, filter) }
                members.map(HostRow::Host)
            } else {
                val members = group.hosts.filter { matches(it, filter) }
                when {
                    members.isEmpty() -> emptyList()
                    filter.isEmpty() && group.name !in expandedGroups -> listOf(HostRow.Group(group.name, members.size, false))
                    else -> listOf(HostRow.Group(group.name, members.size, true)) + members.map(HostRow::Host)
                }
            }
        }
    }

    fun matches(host: SavedHost, query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.trim().lowercase(Locale.ROOT)
        val draft = host.draft
        return listOf(
            draft.name,
            draft.endpoint.host,
            draft.endpoint.port.toString(),
            draft.endpoint.username,
            draft.group.orEmpty(),
            draft.remark.orEmpty(),
            authLabel(draft.authenticationMethod),
        ).any { value -> value.lowercase(Locale.ROOT).contains(needle) }
    }

    fun authLabel(method: SavedAuthenticationMethod): String = when (method) {
        SavedAuthenticationMethod.PASSWORD -> "Password"
        SavedAuthenticationMethod.PUBLIC_KEY -> "Public key"
        SavedAuthenticationMethod.KEYBOARD_INTERACTIVE -> "Keyboard-interactive"
        SavedAuthenticationMethod.SSH_AGENT -> "Agent"
    }

    fun groupLabel(row: HostRow.Group): String = "${row.name} (${row.count})"
}

/** Read-only table model over [HostRow] instances. */
internal class HostTableModel : AbstractTableModel() {
    private var rows: List<HostRow> = emptyList()

    fun setRows(rows: List<HostRow>) {
        this.rows = rows
        fireTableDataChanged()
    }

    fun row(index: Int): HostRow? = rows.getOrNull(index)

    fun rows(): List<HostRow> = rows

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = COLUMNS.size

    override fun getColumnName(column: Int): String = COLUMNS[column]

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rows[rowIndex]
        if (row is HostRow.Group) {
            return if (columnIndex == NAME_COLUMN) HostTableRows.groupLabel(row) else ""
        }
        val host = (row as HostRow.Host).host
        val draft = host.draft
        return when (columnIndex) {
            NAME_COLUMN -> draft.name
            HOST_COLUMN -> draft.endpoint.host
            PORT_COLUMN -> draft.endpoint.port
            USER_COLUMN -> draft.endpoint.username
            AUTH_COLUMN -> HostTableRows.authLabel(draft.authenticationMethod)
            GROUP_COLUMN -> draft.group.orEmpty()
            else -> draft.remark.orEmpty()
        }
    }

    companion object {
        const val NAME_COLUMN = 0
        const val HOST_COLUMN = 1
        const val PORT_COLUMN = 2
        const val USER_COLUMN = 3
        const val AUTH_COLUMN = 4
        const val GROUP_COLUMN = 5
        const val REMARK_COLUMN = 6

        val COLUMNS = listOf("Name", "Host", "Port", "User", "Authentication", "Group", "Remark")
    }
}
