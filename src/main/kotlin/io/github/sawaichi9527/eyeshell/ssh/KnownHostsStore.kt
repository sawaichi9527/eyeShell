package io.github.sawaichi9527.eyeshell.ssh

import java.net.SocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.PublicKey
import io.github.sawaichi9527.eyeshell.platform.EyeShellPaths
import org.apache.sshd.client.config.hosts.KnownHostEntry
import org.apache.sshd.client.keyverifier.DefaultKnownHostsServerKeyVerifier
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.util.net.SshdSocketAddress

data class ChangedHostKey(
    val remoteAddress: String,
    val algorithm: String,
    val expectedFingerprint: String,
    val actualFingerprint: String,
)

fun interface ChangedHostKeyHandler {
    fun rejected(hostKey: ChangedHostKey)
}

class KnownHostsStore(
    val file: Path,
) {
    init {
        require(file.isAbsolute) { "Known Hosts file must use an absolute path" }
    }

    /**
     * Creates a strict Known Hosts verifier.
     *
     * When [verificationAddress] is set, Known Hosts lookups, accepted-key writes, and
     * change reports all use that address instead of the socket address the transport
     * actually connected to. Jump Host connections need this because the target SSH
     * transport is dialed through an ephemeral loopback forwarding port while the
     * trusted identity is still the target host and port.
     */
    internal fun createServerKeyVerifier(
        unknownHostVerifier: HostKeyVerifier,
        changedHostKeyHandler: ChangedHostKeyHandler,
        verificationAddress: SshdSocketAddress? = null,
    ): ServerKeyVerifier {
        prepareFile()
        val unknownDelegate = ServerKeyVerifier { _, remoteAddress, serverKey ->
            unknownHostVerifier.verify(serverKey.presentedAt(remoteAddress))
        }
        val verifier = object : DefaultKnownHostsServerKeyVerifier(unknownDelegate, true, file) {
            // Only the trusted identity is used for lookups and accepted-key writes. The default
            // implementation also adds the socket address that was dialed, which for a jump host
            // connection is an ephemeral loopback forwarding port.
            override fun resolveHostNetworkIdentities(
                clientSession: ClientSession,
                remoteAddress: SocketAddress,
            ): Collection<SshdSocketAddress> = listOf(SshdSocketAddress.toSshdSocketAddress(remoteAddress))

            override fun acceptIncompleteHostKeys(
                clientSession: ClientSession,
                remoteAddress: SocketAddress,
                serverKey: PublicKey,
                reason: Throwable,
            ): Boolean = false

            override fun handleKnownHostsFileUpdateFailure(
                clientSession: ClientSession,
                remoteAddress: SocketAddress,
                serverKey: PublicKey,
                file: Path,
                knownHosts: MutableCollection<KnownHostsServerKeyVerifier.HostEntryPair>,
                reason: Throwable,
            ) {
                throw IllegalStateException("Failed to persist the accepted SSH host key to $file", reason)
            }
        }.apply {
            modifiedServerKeyAcceptor = org.apache.sshd.client.keyverifier.ModifiedServerKeyAcceptor {
                    _, remoteAddress, _: KnownHostEntry, expected, actual ->
                changedHostKeyHandler.rejected(
                    ChangedHostKey(
                        remoteAddress = remoteAddress.toString(),
                        algorithm = KeyUtils.getKeyType(actual),
                        expectedFingerprint = KeyUtils.getFingerPrint(expected),
                        actualFingerprint = KeyUtils.getFingerPrint(actual),
                    ),
                )
                false
            }
        }
        if (verificationAddress == null) return verifier
        return ServerKeyVerifier { clientSession, _, serverKey ->
            verifier.verifyServerKey(clientSession, verificationAddress, serverKey)
        }
    }

    private fun prepareFile() {
        val directory = requireNotNull(file.parent) { "Known Hosts file must have a parent directory" }
        Files.createDirectories(directory)
        require(!Files.isSymbolicLink(directory)) { "Known Hosts directory must not be a symbolic link: $directory" }
        setPosixPermissions(directory, DIRECTORY_PERMISSIONS)
        if (Files.notExists(file)) Files.createFile(file)
        require(!Files.isSymbolicLink(file)) { "Known Hosts file must not be a symbolic link: $file" }
        setPosixPermissions(file, FILE_PERMISSIONS)
    }

    private fun setPosixPermissions(path: Path, permissions: Set<PosixFilePermission>) {
        try {
            Files.setPosixFilePermissions(path, permissions)
        } catch (_: UnsupportedOperationException) {
            // Windows ACLs are managed by the user profile directory.
        }
    }

    companion object {
        private val DIRECTORY_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        private val FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
    }
}

private fun PublicKey.presentedAt(remoteAddress: SocketAddress): PresentedHostKey = PresentedHostKey(
    remoteAddress = remoteAddress.toString(),
    algorithm = KeyUtils.getKeyType(this),
    fingerprint = KeyUtils.getFingerPrint(this),
)
