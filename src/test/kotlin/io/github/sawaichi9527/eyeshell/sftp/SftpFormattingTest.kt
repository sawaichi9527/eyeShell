package io.github.sawaichi9527.eyeshell.sftp

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SftpFormattingTest {
    private fun file(
        name: String = "notes.txt",
        isDirectory: Boolean = false,
        permissions: String = "-rw-r--r--",
        owner: String? = "eye",
        group: String? = "eye",
        modifiedTime: Instant? = null,
    ) = RemoteFile(name, isDirectory, 42, permissions, owner, group, modifiedTime)

    @Test
    fun `type label distinguishes folders links and files`() {
        assertEquals("Folder", SftpFormatting.typeLabel(file(isDirectory = true, permissions = "drwxr-xr-x")))
        assertEquals("Link", SftpFormatting.typeLabel(file(permissions = "lrwxrwxrwx")))
        assertEquals("File", SftpFormatting.typeLabel(file()))
    }

    @Test
    fun `sizes pick the largest whole unit`() {
        assertEquals("512 B", SftpFormatting.formatSize(512))
        assertEquals("1.0 MB", SftpFormatting.formatSize(1024L * 1024))
        assertEquals("4.0 KB", SftpFormatting.formatSize(4096))
        assertEquals("1.5 MB", SftpFormatting.formatSize((1024L * 1024 * 3) / 2))
        assertEquals("2.0 GB", SftpFormatting.formatSize(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `a missing size is not invented`() {
        assertEquals(SftpFormatting.UNKNOWN, SftpFormatting.formatSize(-1))
    }

    @Test
    fun `modified time is rendered in the local zone and missing values stay unknown`() {
        val formatted = SftpFormatting.formatModified(Instant.parse("2026-10-27T04:34:00Z"))

        assertEquals(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(formatted), true, formatted)
        assertEquals(SftpFormatting.UNKNOWN, SftpFormatting.formatModified(null))
    }

    @Test
    fun `owner and group are combined and fall back to unknown`() {
        assertEquals("eye / sudo", SftpFormatting.formatOwnerGroup(file(owner = "eye", group = "sudo")))
        assertEquals("eye", SftpFormatting.formatOwnerGroup(file(owner = "eye", group = null)))
        assertEquals(SftpFormatting.UNKNOWN, SftpFormatting.formatOwnerGroup(file(owner = null, group = null)))
        assertEquals(
            SftpFormatting.UNKNOWN,
            SftpFormatting.formatOwnerGroup(file(owner = " ", group = "")),
        )
    }

    @Test
    fun `folders keep the trailing slash used for double-click navigation`() {
        assertEquals("logs/", SftpFormatting.displayName(file(name = "logs", isDirectory = true)))
        assertEquals("notes.txt", SftpFormatting.displayName(file()))
    }
}
