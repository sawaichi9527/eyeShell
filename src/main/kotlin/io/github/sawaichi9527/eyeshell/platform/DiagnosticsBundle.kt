package io.github.sawaichi9527.eyeshell.platform

import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One file a user can attach to a report: the diagnostics dump plus the tail of the log, zipped so a
 * single attachment carries both halves of the evidence.
 *
 * The industry pattern behind this (Android's `bugreport`, VS Code's issue reporter, Firefox's
 * troubleshooting information) is a single archive that needs no tooling on the receiving side. The
 * automatic watchdogs keep writing plain `.txt` dumps next to the log; this archive is what the
 * "write diagnostics" buttons produce, because a person can find and send it.
 *
 * The archive holds no terminal output, passwords, or key material; it does contain host names and
 * user names, which is why the UI reminds the user before sharing it.
 */
object DiagnosticsBundle {
    const val REPORT_ENTRY = "report.txt"
    const val LOG_ENTRY = "eyeshell.log"

    /** The log is read from its tail: a long-running window keeps appending to one file. */
    const val LOG_TAIL_BYTES = 2 * 1024 * 1024

    private val logger = Logger.getLogger(DiagnosticsBundle::class.java.name)

    internal fun fileName(timestamp: String): String = "eyeShell-diagnostics-$timestamp.zip"

    /**
     * Writes `<directory>/eyeShell-diagnostics-<timestamp>.zip`; returns the file, or null on
     * failure (a missing bundle must never break the UI action that asked for it).
     */
    fun write(
        directory: Path,
        reportText: String,
        logTailBytes: Int = LOG_TAIL_BYTES,
        timestamp: String = Diagnostics.timestamp(),
    ): Path? = try {
        Files.createDirectories(directory)
        val file = directory.resolve(fileName(timestamp))
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry(REPORT_ENTRY))
            zip.write(reportText.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            logTail(directory, logTailBytes)?.let { tail ->
                zip.putNextEntry(ZipEntry(LOG_ENTRY))
                zip.write(tail)
                zip.closeEntry()
            }
        }
        logger.info("Wrote a diagnostics bundle to $file")
        file
    } catch (failure: Exception) {
        logger.log(Level.WARNING, "Could not write a diagnostics bundle to $directory", failure)
        null
    }

    /** Last [limit] bytes of `eyeshell.log`, or null when the log has not been written yet. */
    internal fun logTail(directory: Path, limit: Int): ByteArray? {
        val log = directory.resolve(LoggingSetup.LOG_FILE_NAME)
        if (!Files.isRegularFile(log)) return null
        return try {
            val size = Files.size(log)
            val skip = (size - limit).coerceAtLeast(0)
            Files.newInputStream(log).use { input ->
                input.skipNBytes(skip)
                input.readNBytes(limit)
            }
        } catch (failure: Exception) {
            logger.log(Level.FINE, "Could not read the log tail from $log", failure)
            null
        }
    }
}
