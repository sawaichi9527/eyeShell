package io.github.sawaichi9527.eyeshell.terminal

interface TerminalSession : AutoCloseable {
    val name: String

    val isOpen: Boolean

    fun read(buffer: CharArray, offset: Int, length: Int): Int

    fun write(bytes: ByteArray)

    fun write(text: String)

    fun resize(columns: Int, rows: Int)

    fun ready(): Boolean

    fun awaitExit(): Int
}
