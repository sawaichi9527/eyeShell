package io.github.sawaichi9527.eyeshell.platform

import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Writes a self-contained snapshot of the running JVM next to the diagnostic log, so a Windows user
 * never has to run `jcmd`, `jstack`, or any other tool to tell us where a terminal stopped.
 *
 * The snapshot holds thread stacks and session state only; it never contains terminal output.
 */
object Diagnostics {
    private val logger = Logger.getLogger(Diagnostics::class.java.name)
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** File name for [reason]; visible for tests. */
    internal fun fileName(reason: String, timestamp: String): String =
        "thread-dump-${sanitize(reason)}-$timestamp.txt"

    /**
     * Full text of a thread dump. [threads] and [properties] are injectable so the format can be
     * asserted without depending on the test JVM's own threads.
     */
    internal fun threadDumpText(
        reason: String,
        sessions: List<String>,
        threads: Map<Thread, Array<StackTraceElement>>,
        properties: Map<String, String>,
    ): String = buildString {
        appendLine("eyeShell diagnostics")
        appendLine("reason: ${reason.replace('\n', ' ')}")
        appendLine("time: ${TIMESTAMP.format(java.time.LocalDateTime.now())}")
        properties.toSortedMap().forEach { (name, value) -> appendLine("$name: $value") }
        appendLine("open sessions:")
        if (sessions.isEmpty()) appendLine("  (none)") else sessions.forEach { appendLine("  $it") }
        appendLine()
        appendLine("threads:")
        threads.entries
            .sortedBy { it.key.name }
            .forEach { (thread, stack) ->
                appendLine()
                appendLine("\"${thread.name}\" id=${thread.threadId()} state=${thread.state} daemon=${thread.isDaemon}")
                stack.forEach { frame -> appendLine("    at $frame") }
                if (stack.isEmpty()) appendLine("    (no stack trace)")
            }
    }

    /** Writes `<directory>/thread-dump-<reason>-<timestamp>.txt`; returns the file, or null on failure. */
    fun write(directory: Path, reason: String, sessions: List<String>): Path? = try {
        Files.createDirectories(directory)
        val file = directory.resolve(fileName(reason, timestamp()))
        val text = threadDumpText(
            reason = reason,
            sessions = sessions,
            threads = Thread.getAllStackTraces(),
            properties = runtimeProperties(),
        )
        Files.writeString(file, text)
        logger.info("Wrote a diagnostics snapshot to $file")
        file
    } catch (failure: Exception) {
        logger.log(Level.WARNING, "Could not write a diagnostics snapshot to $directory", failure)
        null
    }

    private fun runtimeProperties(): Map<String, String> = mapOf(
        "eyeShell" to AppInfo.rawVersion.ifBlank { "unknown" },
        "java" to System.getProperty("java.version").orEmpty(),
        "java.home" to System.getProperty("java.home").orEmpty(),
        "os" to "${System.getProperty("os.name").orEmpty()} ${System.getProperty("os.version").orEmpty()} ${System.getProperty("os.arch").orEmpty()}",
        "timezone" to ZoneId.systemDefault().id,
        "heap.max" to Runtime.getRuntime().maxMemory().toString(),
        "heap.used" to (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()).toString(),
    )

    /** Shared timestamp format for diagnostic file names. */
    fun timestamp(): String = TIMESTAMP.format(java.time.LocalDateTime.now())

    /** Keeps a reason from a session name safe for a file name on every platform. */
    internal fun sanitize(text: String): String = text
        .map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }
        .joinToString("")
        .take(48)
        .ifBlank { "snapshot" }
}
