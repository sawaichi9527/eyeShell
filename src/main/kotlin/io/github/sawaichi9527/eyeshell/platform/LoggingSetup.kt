package io.github.sawaichi9527.eyeshell.platform

import java.io.PrintWriter
import java.io.StringWriter
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.logging.ConsoleHandler
import java.util.logging.Formatter
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Console logging for the JVM's built-in logging framework (the `slf4j-jdk14` binding used by
 * Apache MINA SSHD writes through it).
 *
 * The JDK's `SimpleFormatter` localizes the month name and the level name. On a Traditional
 * Chinese Windows the console receives `10月`/`訊息` as UTF-8 while the console code page is CP950,
 * which turns the line prefix into mojibake. The formatter below never localizes: the timestamp,
 * level, and logger are ASCII regardless of the host locale or console code page, and any
 * non-ASCII text is only whatever a message itself contains.
 */
object LoggingSetup {
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun apply() {
        val root = Logger.getLogger("")
        if (root.handlers.isEmpty()) root.addHandler(ConsoleHandler())
        root.handlers.forEach { handler -> handler.formatter = PlainFormatter() }
    }

    internal class PlainFormatter : Formatter() {
        override fun format(record: LogRecord): String {
            val timestamp = TIMESTAMP.format(record.instant.atZone(ZoneId.systemDefault()))
            val message = record.message.orEmpty()
            val logger = record.loggerName.orEmpty()
            val text = buildString {
                append(timestamp)
                append(' ')
                append(record.level.name)
                append(' ')
                if (logger.isNotEmpty()) {
                    append(logger)
                    append(" - ")
                }
                append(message)
                append(System.lineSeparator())
            }
            val thrown = record.thrown ?: return text
            val stackTrace = StringWriter()
            thrown.printStackTrace(PrintWriter(stackTrace))
            return text + stackTrace.toString()
        }
    }
}
