package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.ssh.PresentedHostKey
import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint

/**
 * Describes one host-key confirmation screen.
 *
 * A connection through a jump host produces at most two prompts: one for the jump host
 * when its key is not trusted yet, and one combined prompt for the target that restates
 * the jump host status. When the jump host key is already trusted the target prompt is
 * the only screen shown for the whole connection.
 */
internal data class HostKeyPrompt(
    val title: String,
    val subject: String,
    val address: String,
    val contextLines: List<String>,
    val key: PresentedHostKey,
    val acceptLabel: String,
)

/**
 * Tracks the host-key decisions of a single connection attempt.
 *
 * The jump host key must be accepted before jump credentials are sent, therefore the
 * jump key cannot share a single decision with the target key: the target key only
 * exists after the jump host transport has been authenticated. The review keeps the
 * accepted jump key so the target screen can restate it.
 */
internal class HostKeyReview(
    private val target: SshEndpoint,
    private val jump: SshEndpoint?,
) {
    private var acceptedJumpKey: PresentedHostKey? = null

    fun jumpPrompt(presented: PresentedHostKey): HostKeyPrompt {
        require(jump != null) { "This connection does not use a jump host" }
        return HostKeyPrompt(
            title = "Verify jump host key",
            subject = "jump",
            address = jump.displayName,
            contextLines = listOf("Target: ${target.displayName}"),
            key = presented,
            acceptLabel = "Continue",
        )
    }

    fun acceptJumpKey(presented: PresentedHostKey) {
        acceptedJumpKey = presented
    }

    fun targetPrompt(presented: PresentedHostKey): HostKeyPrompt {
        val context = if (jump == null) {
            emptyList()
        } else {
            val accepted = acceptedJumpKey
            if (accepted == null) {
                listOf("Via jump host: ${jump.displayName} (matches saved key)")
            } else {
                listOf(
                    "Via jump host: ${jump.displayName} (verified now)",
                    "Jump key: ${accepted.algorithm} ${accepted.fingerprint}",
                )
            }
        }
        return HostKeyPrompt(
            title = if (jump == null) "Verify SSH host key" else "Verify SSH host chain",
            subject = "target",
            address = target.displayName,
            contextLines = context,
            key = presented,
            acceptLabel = "Connect",
        )
    }
}
