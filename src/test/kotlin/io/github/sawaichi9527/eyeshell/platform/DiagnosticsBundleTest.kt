package io.github.sawaichi9527.eyeshell.platform

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * One attachment has to answer the whole question: what the app thinks now (the dump) and what it
 * was doing before (the log tail). The automatic watchdogs keep writing plain `.txt` dumps; this is
 * the archive the user-facing buttons produce.
 */
class DiagnosticsBundleTest {
    @Test
    fun `the archive holds the report and the log tail`(@TempDir directory: Path) {
        Files.writeString(directory.resolve(LoggingSetup.LOG_FILE_NAME), "0123456789ABCDEF")

        val file = checkNotNull(
            DiagnosticsBundle.write(
                directory = directory,
                reportText = "report body",
                logTailBytes = 8,
                timestamp = "20261125-140740",
            ),
        )

        assertEquals("eyeShell-diagnostics-20261125-140740.zip", file.fileName.toString())
        val entries = readEntries(file)
        assertEquals("report body", entries[DiagnosticsBundle.REPORT_ENTRY])
        assertEquals("89ABCDEF", entries[DiagnosticsBundle.LOG_ENTRY])
    }

    @Test
    fun `a missing log still produces an archive with the report`(@TempDir directory: Path) {
        val file = checkNotNull(DiagnosticsBundle.write(directory, "only report"))

        assertEquals(setOf(DiagnosticsBundle.REPORT_ENTRY), readEntries(file).keys)
    }

    @Test
    fun `the dump text is what the archive carries`() {
        val text = Diagnostics.dumpText("manual", listOf("name=a@b:22 status=CONNECTED"), events = emptyList())

        assertTrue(text.contains("reason: manual"), text)
        assertTrue(text.contains("name=a@b:22 status=CONNECTED"), text)
        assertTrue(text.contains("events:\n  (none)"), text)
    }

    private fun readEntries(file: Path): Map<String, String> = buildMap {
        ZipFile(file.toFile()).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                put(entry.name, zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8))
            }
        }
    }
}
