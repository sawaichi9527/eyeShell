package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.storage.SavedHost
import java.util.Locale
import javax.swing.tree.DefaultMutableTreeNode

/**
 * One level of the Saved hosts tree: a named group folder and its hosts.
 * A null [name] carries the ungrouped hosts, which are shown directly under the tree root.
 */
internal data class HostGroupNode(
    val name: String?,
    val hosts: List<SavedHost>,
)

/**
 * Grouping, ordering, and label text for the Saved hosts tree.
 *
 * Kept free of Swing components so the structure can be validated without an EDT.
 */
internal object HostCatalogTree {
    // Case-insensitive primary order with an exact tie-break so distinct group names
    // ("Lab" vs "lab") never collapse into one sorted-map entry.
    private val nameOrder = Comparator<String> { left, right ->
        val byLowercase = left.lowercase(Locale.ROOT).compareTo(right.lowercase(Locale.ROOT))
        if (byLowercase != 0) byLowercase else left.compareTo(right)
    }

    private val hostOrder = Comparator<SavedHost> { left, right ->
        val byName = nameOrder.compare(left.draft.name, right.draft.name)
        if (byName != 0) byName else left.id.compareTo(right.id)
    }

    fun build(hosts: List<SavedHost>): List<HostGroupNode> {
        val ungrouped = hosts.filter { it.draft.group == null }.sortedWith(hostOrder)
        val grouped = hosts
            .mapNotNull { host -> host.draft.group?.let { name -> name to host } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap(nameOrder)
            .map { (name, members) -> HostGroupNode(name, members.sortedWith(hostOrder)) }
        return if (ungrouped.isEmpty()) grouped else listOf(HostGroupNode(null, ungrouped)) + grouped
    }

    fun rootNode(hosts: List<SavedHost>): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode()
        build(hosts).forEach { group ->
            if (group.name == null) {
                group.hosts.forEach { root.add(DefaultMutableTreeNode(it)) }
            } else {
                val groupNode = DefaultMutableTreeNode(group)
                group.hosts.forEach { groupNode.add(DefaultMutableTreeNode(it)) }
                root.add(groupNode)
            }
        }
        return root
    }

    fun groupLabel(group: HostGroupNode): String =
        "${group.name ?: "Ungrouped"} (${group.hosts.size})"

    fun hostLabel(host: SavedHost): String {
        val tags = host.draft.tags.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "  [", postfix = "]").orEmpty()
        return "${host.draft.name}  ${host.draft.endpoint.displayName}$tags"
    }
}
