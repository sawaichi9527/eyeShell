package io.github.sawaichi9527.eyeshell.sftp

import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Presentation helpers for the SFTP browser. They live outside the Swing panel so the column text
 * can be asserted without a GUI, and they never invent data: a value the server did not send is
 * shown as [UNKNOWN] instead of a plausible-looking placeholder.
 */
object SftpFormatting {
    const val UNKNOWN = "—"

    private val SIZE_FORMAT = DecimalFormat("#,##0.0")

    private val MODIFIED_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault())

    /** `Folder`, `Link`, or `File`, matching the folder/file distinction FinalShell shows. */
    fun typeLabel(file: RemoteFile): String = when {
        file.isDirectory -> "Folder"
        file.permissions.startsWith("l") -> "Link"
        else -> "File"
    }

    /** Human-readable size; folders report the server's directory size, which is usually 4.0 KB. */
    fun formatSize(bytes: Long): String = when {
        bytes < 0 -> UNKNOWN
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> SIZE_FORMAT.format(bytes / 1024.0) + " KB"
        bytes < 1024L * 1024 * 1024 -> SIZE_FORMAT.format(bytes / 1024.0 / 1024.0) + " MB"
        else -> SIZE_FORMAT.format(bytes / 1024.0 / 1024.0 / 1024.0) + " GB"
    }

    /** `yyyy-MM-dd HH:mm` in the local zone, or [UNKNOWN] when the server sent no mtime. */
    fun formatModified(modifiedTime: Instant?): String =
        modifiedTime?.let(MODIFIED_FORMAT::format) ?: UNKNOWN

    /** The `owner / group` pair, with [UNKNOWN] for a sender that omitted them. */
    fun formatOwnerGroup(file: RemoteFile): String {
        val parts = listOfNotNull(file.owner?.takeIf { it.isNotBlank() }, file.group?.takeIf { it.isNotBlank() })
        return if (parts.isEmpty()) UNKNOWN else parts.joinToString(" / ")
    }

    /** Directories keep the trailing slash the browser uses to signal double-click-to-open. */
    fun displayName(file: RemoteFile): String = if (file.isDirectory) "${file.name}/" else file.name
}
