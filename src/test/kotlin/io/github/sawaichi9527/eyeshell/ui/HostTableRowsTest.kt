package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import io.github.sawaichi9527.eyeshell.storage.HostDraft
import io.github.sawaichi9527.eyeshell.storage.SavedAuthenticationMethod
import io.github.sawaichi9527.eyeshell.storage.SavedHost
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostTableRowsTest {
    @Test
    fun `a collapsed folder is one row and ungrouped hosts stay at the top`() {
        val rows = HostTableRows.build(hosts, expandedGroups = emptySet(), query = "")

        assertEquals(
            listOf("direct", "Lab (2)", "Zeta (1)"),
            rows.map(::label),
        )
    }

    @Test
    fun `an expanded folder lists its hosts after the folder row`() {
        val rows = HostTableRows.build(hosts, expandedGroups = setOf("Lab"), query = "")

        assertEquals(
            listOf("direct", "Lab (2)", "alpha", "beta", "Zeta (1)"),
            rows.map(::label),
        )
    }

    @Test
    fun `the filter matches name, host, user, port, and authentication`() {
        val byName = HostTableRows.build(hosts, emptySet(), "solo")
        assertEquals(listOf("Zeta (1)", "solo"), byName.map(::label))

        val byHost = HostTableRows.build(hosts, emptySet(), "host2")
        assertEquals(listOf("Lab (1)", "alpha"), byHost.map(::label))

        val byUser = HostTableRows.build(hosts, emptySet(), "operator")
        assertEquals(listOf("direct", "Lab (2)", "alpha", "beta", "Zeta (1)", "solo"), byUser.map(::label))

        val byPort = HostTableRows.build(hosts, emptySet(), "2222")
        assertEquals(listOf("direct"), byPort.map(::label))

        val byAuth = HostTableRows.build(hosts, emptySet(), "public key")
        assertEquals(listOf("direct"), byAuth.map(::label))
    }

    @Test
    fun `a filter opens folders regardless of their collapsed state and hides empty ones`() {
        val rows = HostTableRows.build(hosts, expandedGroups = emptySet(), query = "beta")

        assertEquals(listOf("Lab (1)", "beta"), rows.map(::label))
    }

    @Test
    fun `a filter with no match yields no rows`() {
        assertTrue(HostTableRows.build(hosts, emptySet(), "does-not-exist").isEmpty())
    }

    @Test
    fun `authentication labels are user facing`() {
        assertEquals("Password", HostTableRows.authLabel(SavedAuthenticationMethod.PASSWORD))
        assertEquals("Public key", HostTableRows.authLabel(SavedAuthenticationMethod.PUBLIC_KEY))
        assertEquals(
            "Keyboard-interactive",
            HostTableRows.authLabel(SavedAuthenticationMethod.KEYBOARD_INTERACTIVE),
        )
        assertEquals("Agent", HostTableRows.authLabel(SavedAuthenticationMethod.SSH_AGENT))
    }

    private fun label(row: HostRow): String = when (row) {
        is HostRow.Group -> HostTableRows.groupLabel(row)
        is HostRow.Host -> row.host.draft.name
    }

    private val hosts = listOf(
        host(1, "direct", port = 2222, method = SavedAuthenticationMethod.PUBLIC_KEY),
        host(2, "alpha", group = "Lab"),
        host(3, "beta", group = "Lab"),
        host(4, "solo", group = "Zeta"),
    )

    private fun host(
        id: Long,
        name: String,
        group: String? = null,
        port: Int = 22,
        method: SavedAuthenticationMethod = SavedAuthenticationMethod.PASSWORD,
    ) = SavedHost(
        id,
        UUID(id, 0),
        HostDraft(
            name = name,
            endpoint = SshEndpoint("host$id.test", port, "operator"),
            authenticationMethod = method,
            group = group,
        ),
    )
}
