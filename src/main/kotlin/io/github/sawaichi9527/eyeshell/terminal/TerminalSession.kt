package io.github.sawaichi9527.eyeshell.terminal

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

interface TerminalSession : AutoCloseable {
    val name: String

    val isOpen: Boolean

    /**
     * Encoding used to decode remote output and to encode typed text. UTF-8 by default; sessions
     * that cannot re-encode themselves keep reporting UTF-8.
     */
    val charset: Charset
        get() = StandardCharsets.UTF_8

    fun read(buffer: CharArray, offset: Int, length: Int): Int

    fun write(bytes: ByteArray)

    fun write(text: String)

    /**
     * Pending input and delivery counters, when the session queues keystrokes instead of writing
     * them inline. `lastBlockedMillis` above zero means the transport made the writer thread wait,
     * which is the state in which a tab used to ignore typing.
     */
    fun inputStats(): InputStats? = null

    /**
     * The exit status the remote reported, or `null` when the channel closed without one.
     *
     * [awaitExit] answers `0` for both "the shell exited cleanly" and "the transport died", which
     * makes a dropped connection look like a deliberate `exit` in the log. This distinguishes them.
     */
    fun reportedExitStatus(): Int? = null

    /**
     * Switches the encoding of a live session. Bytes that are still undecoded are reinterpreted with
     * the new charset, so only text already written into the buffer keeps its old decoding.
     * Sessions that cannot switch ignore the request.
     */
    fun setCharset(charset: Charset) = Unit

    /**
     * Raw output bytes this session has already handed to its decoder, or `null` when the session
     * does not retain them. A bounded snapshot is what lets the UI re-decode existing output after
     * an encoding change ([RawOutputLog]).
     */
    fun rawOutput(): ByteArray? = null

    /**
     * Transport flow control, when the session can report it. A depleted `localWindow` means the
     * peer has been told to wait and is blocked writing to us — the shape of a terminal that stopped
     * consuming while the SSH connection still looks open.
     */
    fun flowControl(): TerminalFlowControl? = null

    fun resize(columns: Int, rows: Int)

    fun ready(): Boolean

    fun awaitExit(): Int
}

/**
 * SSH channel windows for a live terminal. [localWindow] is what the peer may still send to us; when
 * it reaches zero the remote is blocked and every channel on that connection can stall.
 */
data class TerminalFlowControl(
    val localWindow: Long,
    val localWindowMax: Long,
    val remoteWindow: Long,
    val remoteWindowMax: Long,
)

/**
 * Input-path state for a live session. [pendingBytes] is what the writer thread still has to hand
 * to the transport (a value that grows while a remote stops reading stdin); [droppedBytes] counts
 * bytes discarded by the bounded queue; [lastBlockedMillis] is the duration of the last transport
 * write that took longer than the writer's threshold.
 */
data class InputStats(
    val pendingBytes: Int,
    val queuedChunks: Int,
    val droppedBytes: Long,
    val lastBlockedMillis: Long,
)
