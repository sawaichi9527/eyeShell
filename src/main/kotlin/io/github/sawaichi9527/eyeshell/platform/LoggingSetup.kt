package io.github.sawaichi9527.eyeshell.platform

import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.logging.ConsoleHandler
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Logging for the JVM's built-in logging framework (the `slf4j-jdk14` binding used by Apache MINA
 * SSHD and by the bundled JediTerm writes through it).
 *
 * The JDK's `SimpleFormatter` localizes the month name and the level name. On a Traditional
 * Chinese Windows the console receives `10月`/`訊息` as UTF-8 while the console code page is CP950,
 * which turns the line prefix into mojibake. The formatter below never localizes: the timestamp,
 * level, and logger are ASCII regardless of the host locale or console code page, and any
 * non-ASCII text is only whatever a message itself contains.
 *
 * A packaged build has no console attached, so the same formatter is also installed on a rotating
 * file handler. Without it a terminal-emulator failure (reported by `LOG.error` inside JediTerm)
 * would be printed to a stream nobody can read and leave no evidence at all.
 */
object LoggingSetup {
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /** File name inside the log directory; rotations append `.1`, `.2`, ... */
    const val LOG_FILE_NAME = "eyeshell.log"

    /** 2 MiB per run; a long-running window keeps appending, a restart rotates first. */
    private const val MAX_BYTES = 2L * 1024 * 1024

    /** Kept sibling files (`eyeshell.log.1`, `eyeshell.log.2`) besides the active log. */
    private const val ROTATIONS = 2

    private val logger = Logger.getLogger(LoggingSetup::class.java.name)

    @Volatile
    private var fileHandler: FileHandler? = null

    /**
     * Installs the console formatter and, when [logDirectory] is given, the rotating file handler.
     * Returns the installed file handler, or `null` when the directory could not be used (a missing
     * log must never prevent the application from starting).
     */
    fun apply(logDirectory: Path? = null): FileHandler? {
        val root = Logger.getLogger("")
        if (root.handlers.isEmpty()) root.addHandler(ConsoleHandler())
        root.handlers.forEach { handler -> handler.formatter = PlainFormatter() }
        return logDirectory?.let(::installFileHandler)
    }

    /** Visible for tests; replaces a handler installed by an earlier call. */
    fun installFileHandler(directory: Path): FileHandler? {
        val root = Logger.getLogger("")
        fileHandler?.let { previous ->
            root.removeHandler(previous)
            previous.close()
            fileHandler = null
        }
        return try {
            Files.createDirectories(directory)
            rotateExisting(directory)
            // A single file per run: JDK rotation would name the active file `eyeshell.log.0`, and a
            // plain `eyeshell.log` is what the diagnostics instructions tell a user to tail.
            val handler = FileHandler(directory.resolve(LOG_FILE_NAME).toString(), MAX_BYTES, 1, true)
            handler.formatter = PlainFormatter()
            root.addHandler(handler)
            fileHandler = handler
            handler
        } catch (failure: IOException) {
            logger.log(Level.WARNING, "Could not open the diagnostic log in $directory", failure)
            null
        } catch (failure: SecurityException) {
            logger.log(Level.WARNING, "Not allowed to open the diagnostic log in $directory", failure)
            null
        }
    }

    /** Shifts `eyeshell.log` to `.1` and `.1` to `.2` so a restart does not lose the last crash. */
    private fun rotateExisting(directory: Path) {
        val active = directory.resolve(LOG_FILE_NAME)
        if (!Files.exists(active)) return
        for (index in ROTATIONS downTo 1) {
            val source = directory.resolve("$LOG_FILE_NAME.$index")
            if (!Files.exists(source)) continue
            Files.move(source, directory.resolve("$LOG_FILE_NAME.${index + 1}"), StandardCopyOption.REPLACE_EXISTING)
        }
        Files.move(active, directory.resolve("$LOG_FILE_NAME.1"), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Writes one startup line so a log always identifies the build that produced it. */
    fun logStartup(context: Map<String, String>) {
        if (context.isEmpty()) return
        Logger.getLogger("io.github.sawaichi9527.eyeshell").info(
            context.entries.joinToString(" ") { (name, value) -> "$name=$value" },
        )
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
