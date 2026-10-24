package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import io.github.sawaichi9527.eyeshell.storage.HostDraft
import io.github.sawaichi9527.eyeshell.storage.SavedAuthenticationMethod
import io.github.sawaichi9527.eyeshell.storage.SavedHost
import java.util.UUID
import javax.swing.tree.DefaultMutableTreeNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostCatalogTreeTest {
    @Test
    fun `ungrouped hosts are listed first and groups are sorted case-insensitively`() {
        val nodes = HostCatalogTree.build(
            listOf(
                host(1, "Alpha", group = "production"),
                host(2, "Beta", group = "Lab"),
                host(3, "Gamma"),
                host(4, "Delta", group = "Production"),
            ),
        )

        // Group names are case-sensitive in the catalog, but case variants stay distinct and sort together.
        assertEquals(listOf<String?>(null, "Lab", "Production", "production"), nodes.map { it.name })
        assertEquals(listOf("Gamma"), nodes[0].hosts.map { it.draft.name })
        assertEquals(listOf("Beta"), nodes[1].hosts.map { it.draft.name })
        assertEquals(listOf("Delta"), nodes[2].hosts.map { it.draft.name })
        assertEquals(listOf("Alpha"), nodes[3].hosts.map { it.draft.name })
    }

    @Test
    fun `hosts inside a group are ordered by name and then by id`() {
        val nodes = HostCatalogTree.build(
            listOf(
                host(7, "same", group = "Lab"),
                host(3, "Zulu", group = "Lab"),
                host(5, "same", group = "Lab"),
                host(4, "alpha", group = "Lab"),
            ),
        )

        assertEquals(
            listOf(4L, 5L, 7L, 3L),
            nodes.single().hosts.map { it.id },
            "case-insensitive name order wins, equal names fall back to id",
        )
    }

    @Test
    fun `an empty catalog produces no tree nodes`() {
        assertTrue(HostCatalogTree.build(emptyList()).isEmpty())
        assertEquals(0, HostCatalogTree.rootNode(emptyList()).childCount)
    }

    @Test
    fun `the tree keeps ungrouped hosts at the root and grouped hosts under their group`() {
        val root = HostCatalogTree.rootNode(
            listOf(
                host(1, "direct"),
                host(2, "member", group = "Lab"),
                host(3, "other", group = "Lab"),
            ),
        )

        assertEquals(2, root.childCount)
        val direct = root.getChildAt(0) as DefaultMutableTreeNode
        assertTrue(direct.userObject is SavedHost)
        assertEquals("direct", (direct.userObject as SavedHost).draft.name)
        assertEquals(root, direct.parent)

        val group = root.getChildAt(1) as DefaultMutableTreeNode
        val groupNode = group.userObject as HostGroupNode
        assertEquals("Lab", groupNode.name)
        assertEquals(2, groupNode.hosts.size)
        assertEquals(2, group.childCount)
        assertTrue(group.getChildAt(0) is DefaultMutableTreeNode)
    }

    @Test
    fun `labels keep the endpoint and tags`() {
        val labelled = host(9, "edge-1", group = "Lab", tags = listOf("prod", "eu"))
        assertEquals("edge-1  user@host9.test:22  [prod, eu]", HostCatalogTree.hostLabel(labelled))
        assertEquals("Lab (1)", HostCatalogTree.groupLabel(HostGroupNode("Lab", listOf(labelled))))
        assertEquals("edge-1  user@host8.test:22", HostCatalogTree.hostLabel(host(8, "edge-1")))
    }

    private fun host(
        id: Long,
        name: String,
        group: String? = null,
        tags: List<String> = emptyList(),
    ): SavedHost = SavedHost(
        id = id,
        profileId = UUID(id, 0),
        draft = HostDraft(
            name = name,
            endpoint = SshEndpoint("host$id.test", 22, "user"),
            authenticationMethod = SavedAuthenticationMethod.PASSWORD,
            group = group,
            tags = tags,
        ),
    )
}
