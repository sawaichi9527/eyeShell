package io.github.sawaichi9527.eyeshell.terminal

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class SyntheticTerminalSession private constructor(
    private val input: CharArray,
) : TerminalSession {
    private val output = ByteArrayOutputStream()
    private var position = 0
    private var open = true

    override val name: String = "eyeShell M1A synthetic terminal"

    override val isOpen: Boolean
        @Synchronized get() = open

    @Synchronized
    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        if (!open) return -1
        if (position == input.size) {
            open = false
            return -1
        }

        val count = minOf(length, input.size - position)
        input.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }

    @Synchronized
    override fun write(bytes: ByteArray) {
        output.write(bytes)
    }

    override fun write(text: String) {
        write(text.toByteArray(StandardCharsets.UTF_8))
    }

    override fun resize(columns: Int, rows: Int) {
        require(columns > 0 && rows > 0)
    }

    @Synchronized
    override fun ready(): Boolean = open && position < input.size

    override fun awaitExit(): Int = 0

    @Synchronized
    override fun close() {
        open = false
    }

    @Synchronized
    fun writtenText(): String = output.toString(StandardCharsets.UTF_8)

    companion object {
        private const val ESC = '\u001B'

        fun demo(): SyntheticTerminalSession = fromText(buildString {
            append("$ESC[2J$ESC[H")
            append("$ESC[1;36meyeShell synthetic terminal$ESC[0m\r\n")
            append("ANSI: $ESC[31mred$ESC[0m $ESC[32mgreen$ESC[0m $ESC[33myellow$ESC[0m\r\n")
            append("Unicode: 中文 console output - 日本語 - 한국어\r\n")
            append("Soft wrap: ")
            append("0123456789".repeat(10))
            append("\r\n")
            append("Hard break is preserved here.\r\n")
            repeat(28) { index ->
                val lineNumber = (index + 1).toString().padStart(2, '0')
                append("scrollback line $lineNumber: deterministic log entry\r\n")
            }
            append("$ESC[?1049hAlternate screen is isolated from main output.$ESC[?1049l")
            append("$ESC[1;35mM1A ready:$ESC[0m search, selection, scrollback, copy and export baseline.\r\n")
        })

        internal fun fromText(text: String): SyntheticTerminalSession = SyntheticTerminalSession(text.toCharArray())
    }
}
