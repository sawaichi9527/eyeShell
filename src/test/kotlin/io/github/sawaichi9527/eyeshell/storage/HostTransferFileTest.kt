package io.github.sawaichi9527.eyeshell.storage

import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostTransferFileTest {
    @Test
    fun `every non-secret field survives a round trip`() {
        val hosts = listOf(host(1, "Freelancer"), host(2, "Bastion", jump = true))

        val imported = HostTransferFile.parse(HostTransferFile.format(hosts))

        assertEquals(listOf(hosts[0].profileId, hosts[1].profileId), imported.map { it.profileId })
        assertEquals(hosts[0].draft, imported[0].draft)
        assertEquals(hosts[1].draft, imported[1].draft)
        assertEquals(3, imported[0].lineNumber)
        assertEquals(4, imported[1].lineNumber)
    }

    @Test
    fun `commas, quotes, line breaks, and CJK text survive quoting`() {
        val tricky = HostDraft(
            name = "Lab, \"alpha\"",
            endpoint = SshEndpoint("lab.test", 2222, "operator"),
            authenticationMethod = SavedAuthenticationMethod.PUBLIC_KEY,
            group = "備援, 群組",
            tags = listOf("hermes-agent", "備援"),
            remark = "第一行\n第二行, with \"quotes\"",
        )
        val host = SavedHost(1, UUID.fromString("00000000-0000-0000-0000-0000000000e1"), tricky.normalized())

        val imported = HostTransferFile.parse(HostTransferFile.format(listOf(host)))

        assertEquals(host.draft, imported.single().draft)
    }

    @Test
    fun `the file carries a version marker, a BOM, and no secret column`() {
        val text = HostTransferFile.format(listOf(host(1, "Freelancer")))
        val header = text.removePrefix("\uFEFF").lineSequence().elementAt(1)

        assertTrue(text.startsWith("\uFEFF${HostTransferFile.VERSION_LINE}"))
        assertEquals(HostTransferFile.COLUMNS.joinToString(","), header)
        listOf("password", "passphrase", "private", "secret", "credential").forEach { secret ->
            assertFalse(header.contains(secret, ignoreCase = true), "The header must not carry $secret")
        }
    }

    @Test
    fun `a missing or unsupported version is rejected`() {
        val failure = assertThrows(HostTransferException::class.java) {
            HostTransferFile.parse("profileId,name\n")
        }

        assertEquals(HostTransferError.VERSION, failure.reason)
    }

    @Test
    fun `a malformed row reports its line number and reason`() {
        val broken = HostTransferFile.format(listOf(host(1, "Freelancer")))
            .replace(",22,", ",not-a-port,")

        val failure = assertThrows(HostTransferException::class.java) {
            HostTransferFile.parse(broken)
        }

        assertEquals(HostTransferError.PORT, failure.reason)
        assertEquals(3, failure.lineNumber)
    }

    @Test
    fun `duplicate profile ids are rejected instead of silently overwriting`() {
        val line = HostTransferFile.format(listOf(host(1, "Freelancer"))).lineSequence().last { it.isNotBlank() }
        val duplicated = HostTransferFile.format(listOf(host(1, "Freelancer"))) + line + "\r\n"

        val failure = assertThrows(HostTransferException::class.java) {
            HostTransferFile.parse(duplicated)
        }

        assertEquals(HostTransferError.DUPLICATE_PROFILE, failure.reason)
        assertEquals(4, failure.lineNumber)
    }

    @Test
    fun `a missing jump host reference is parsed and left for the catalog to validate`() {
        val jump = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val draft = HostDraft(
            name = "Target",
            endpoint = SshEndpoint("target.test", 22, "operator"),
            authenticationMethod = SavedAuthenticationMethod.PASSWORD,
            jumpHostProfileId = jump,
        )
        val host = SavedHost(2, UUID.fromString("00000000-0000-0000-0000-0000000000a2"), draft)

        val imported = HostTransferFile.parse(HostTransferFile.format(listOf(host)))

        assertEquals(jump, imported.single().draft.jumpHostProfileId)
    }

    private fun host(id: Long, name: String, jump: Boolean = false) = SavedHost(
        id,
        UUID(0, id),
        HostDraft(
            name = name,
            endpoint = SshEndpoint("host$id.test", 22, "operator"),
            authenticationMethod = SavedAuthenticationMethod.PASSWORD,
            group = if (jump) "Bastions" else null,
            tags = if (jump) listOf("jump") else emptyList(),
            jumpHostProfileId = if (jump) UUID(0, 1) else null,
            remark = if (jump) "gateway" else null,
        ).normalized(),
    )
}
