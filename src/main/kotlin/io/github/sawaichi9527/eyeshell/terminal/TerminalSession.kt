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
