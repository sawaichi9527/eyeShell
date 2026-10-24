package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.ssh.PresentedHostKey
import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostKeyReviewTest {
    private val target = SshEndpoint("target.example", 22, "operator")
    private val jump = SshEndpoint("bastion.example", 2222, "operator")

    private fun presented(address: String, fingerprint: String = "SHA256:target") = PresentedHostKey(
        remoteAddress = address,
        algorithm = "ssh-ed25519",
        fingerprint = fingerprint,
    )

    @Test
    fun `direct connection prompt has no jump context`() {
        val prompt = HostKeyReview(target, null).targetPrompt(presented("target.example:22"))

        assertEquals("Verify SSH host key", prompt.title)
        assertEquals("target", prompt.subject)
        assertEquals(target.displayName, prompt.address)
        assertTrue(prompt.contextLines.isEmpty())
        assertEquals("Connect", prompt.acceptLabel)
    }

    @Test
    fun `jump prompt is a separate step that names the target`() {
        val review = HostKeyReview(target, jump)
        val prompt = review.jumpPrompt(presented("bastion.example:2222", "SHA256:jump"))

        assertEquals("Verify jump host key", prompt.title)
        assertEquals("jump", prompt.subject)
        assertEquals(jump.displayName, prompt.address)
        assertEquals(listOf("Target: ${target.displayName}"), prompt.contextLines)
        assertEquals("Continue", prompt.acceptLabel)
        assertEquals("SHA256:jump", prompt.key.fingerprint)
    }

    @Test
    fun `target prompt reports a jump key that was already trusted`() {
        val prompt = HostKeyReview(target, jump).targetPrompt(presented("target.example:22"))

        assertEquals("Verify SSH host chain", prompt.title)
        assertEquals(
            listOf("Via jump host: ${jump.displayName} (matches saved key)"),
            prompt.contextLines,
        )
    }

    @Test
    fun `target prompt reports a jump key accepted in this attempt`() {
        val review = HostKeyReview(target, jump)
        review.acceptJumpKey(presented("bastion.example:2222", "SHA256:jump"))

        val prompt = review.targetPrompt(presented("target.example:22"))

        assertEquals(
            listOf(
                "Via jump host: ${jump.displayName} (verified now)",
                "Jump key: ssh-ed25519 SHA256:jump",
            ),
            prompt.contextLines,
        )
        assertFalse(prompt.contextLines.any { it.contains("matches saved key") })
    }
}
