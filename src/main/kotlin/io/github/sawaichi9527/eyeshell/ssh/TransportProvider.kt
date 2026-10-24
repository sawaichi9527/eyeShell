package io.github.sawaichi9527.eyeshell.ssh

/**
 * Describes how eyeShell reaches the target SSH server.
 *
 * Only one jump hop is supported in this milestone. A jump host is reached directly
 * from this process; the target is reached through an ephemeral loopback forwarding
 * channel opened on the jump host transport.
 */
sealed interface TransportProvider {
    data object Direct : TransportProvider

    class JumpHost(
        val endpoint: SshEndpoint,
        val authentication: SshAuthentication,
        val unknownHostVerifier: HostKeyVerifier,
        val changedHostKeyHandler: ChangedHostKeyHandler = ChangedHostKeyHandler {},
    ) : TransportProvider
}
