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
}
