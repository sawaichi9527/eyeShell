package io.github.sawaichi9527.eyeshell.ssh

import java.nio.file.Path
import java.util.Arrays

sealed class SshAuthentication : AutoCloseable {
    class Password(password: CharArray) : SshAuthentication() {
        private val value = password.copyOf()

        init {
            require(value.isNotEmpty()) { "SSH password must not be empty" }
        }

        internal fun copyValue(): CharArray = value.copyOf()

        override fun close() {
            Arrays.fill(value, '\u0000')
        }
    }

    class PublicKey(
        privateKeyFile: Path,
        passphrase: CharArray,
    ) : SshAuthentication() {
        val privateKeyFile: Path = privateKeyFile.toAbsolutePath().normalize()
        private val passphraseValue = passphrase.copyOf()

        internal fun copyPassphrase(): CharArray = passphraseValue.copyOf()

        override fun close() {
            Arrays.fill(passphraseValue, '\u0000')
        }
    }

    class KeyboardInteractive(
        internal val cancel: () -> Unit = {},
        internal val responder: KeyboardInteractiveResponder,
    ) : SshAuthentication() {
        override fun close() = Unit
    }

    class Agent private constructor(
        internal val endpoint: SshAgentEndpoint,
    ) : SshAuthentication() {
        override fun close() = Unit

        companion object {
            fun system(): Agent {
                val osName = System.getProperty("os.name")
                return when {
                    osName.startsWith("Windows", ignoreCase = true) -> Agent(SshAgentEndpoint.WindowsOpenSsh)
                    osName.startsWith("Linux", ignoreCase = true) -> {
                        val socket = System.getenv("SSH_AUTH_SOCK")
                        require(!socket.isNullOrBlank()) { "SSH_AUTH_SOCK is not available" }
                        Agent(SshAgentEndpoint.UnixSocket(Path.of(socket)))
                    }
                    else -> throw IllegalArgumentException("SSH agent authentication is not supported on $osName")
                }
            }

            internal fun unixSocket(path: Path): Agent = Agent(SshAgentEndpoint.UnixSocket(path))
        }
    }
}

internal sealed interface SshAgentEndpoint {
    data class UnixSocket(val path: Path) : SshAgentEndpoint

    data object WindowsOpenSsh : SshAgentEndpoint
}

data class KeyboardInteractiveChallenge(
    val name: String,
    val instruction: String,
    val language: String,
    val prompts: List<KeyboardInteractivePrompt>,
)

data class KeyboardInteractivePrompt(
    val text: String,
    val echo: Boolean,
)

fun interface KeyboardInteractiveResponder {
    fun respond(challenge: KeyboardInteractiveChallenge): List<CharArray>?
}
