package io.github.sawaichi9527.eyeshell.ssh

data class SshEndpoint(
    val host: String,
    val port: Int,
    val username: String,
) {
    init {
        require(host.isNotBlank()) { "SSH host must not be blank" }
        require(port in 1..65535) { "SSH port must be between 1 and 65535" }
        require(username.isNotBlank()) { "SSH username must not be blank" }
    }

    val displayName: String
        get() {
            val displayHost = if (':' in host && !host.startsWith('[')) "[$host]" else host
            return "$username@$displayHost:$port"
        }
}

data class PresentedHostKey(
    val remoteAddress: String,
    val algorithm: String,
    val fingerprint: String,
)

fun interface HostKeyVerifier {
    fun verify(hostKey: PresentedHostKey): Boolean
}
