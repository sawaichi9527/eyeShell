package io.github.sawaichi9527.eyeshell.ssh

import io.github.sawaichi9527.eyeshell.sftp.SftpClient
import io.github.sawaichi9527.eyeshell.terminal.TerminalSession

interface HostSession : AutoCloseable {
    val endpoint: SshEndpoint

    fun openTerminal(columns: Int = 80, rows: Int = 24): TerminalSession

    fun execute(command: String): ExecResult

    fun sftp(): SftpClient

    fun isOpen(): Boolean
}

data class ExecResult(
    val exitStatus: Int,
    val output: String,
)
