package io.github.sawaichi9527.eyeshell.terminal

import java.nio.charset.Charset

/**
 * Session wrapper that first serves already decoded history and then continues with a live session.
 *
 * A charset change cannot rewrite text that is already inside the terminal buffer, so eyeShell
 * detaches the view from the live session, re-decodes the retained raw bytes with the new encoding,
 * and attaches this wrapper to the same widget. The literal history replays through the terminal
 * emulator (keeping ANSI colors, wrapping, and alt-screen behaviour) and everything after the prefix
 * comes from the unchanged live connection.
 *
 * Closing the wrapper does not close the delegate: the owning tab closes the real session.
 */
class ReplayTerminalSession(
    private val prefix: CharArray,
    private val delegate: TerminalSession,
) : TerminalSession {
    private var position = 0

    override val name: String
        get() = delegate.name

    override val isOpen: Boolean
        get() = delegate.isOpen

    override val charset: Charset
        get() = delegate.charset

    override fun setCharset(charset: Charset) = delegate.setCharset(charset)

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        if (position < prefix.size && length > 0) {
            val count = minOf(length, prefix.size - position)
            prefix.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
        return delegate.read(buffer, offset, length)
    }

    override fun write(bytes: ByteArray) = delegate.write(bytes)

    override fun write(text: String) = delegate.write(text)

    override fun resize(columns: Int, rows: Int) = delegate.resize(columns, rows)

    override fun ready(): Boolean = position < prefix.size || delegate.ready()

    override fun rawOutput(): ByteArray? = delegate.rawOutput()

    override fun awaitExit(): Int = delegate.awaitExit()

    /** The replay prefix belongs to the view, so closing it must not end the live connection. */
    override fun close() = Unit
}
