package io.github.sawaichi9527.eyeshell.storage

import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** One Saved Host read from an import file, keeping the original profile identity. */
data class ImportedHost(
    val profileId: UUID,
    val draft: HostDraft,
    val lineNumber: Int,
)

/** Result of a bulk import. [missingJumpReferences] lists hosts whose jump host could not be resolved. */
data class HostImportOutcome(
    val added: Int,
    val updated: Int,
    val missingJumpReferences: List<String> = emptyList(),
)

/** A stable, translatable reason for a rejected import row. */
enum class HostTransferError {
    VERSION,
    COLUMNS,
    FIELD_COUNT,
    PROFILE_ID,
    NAME,
    HOST,
    PORT,
    USERNAME,
    AUTHENTICATION,
    JUMP_HOST,
    DUPLICATE_PROFILE,
    QUOTING,
}

class HostTransferException(
    val lineNumber: Int?,
    val reason: HostTransferError,
    val detail: String = "",
) : IllegalArgumentException(detail.ifEmpty { reason.name })

/**
 * Reads and writes the Saved hosts interchange file.
 *
 * The format is a versioned, human-editable CSV: a `# eyeShell saved hosts v1` marker, one header
 * row, then one row per profile. It deliberately has no column for passwords, private keys,
 * passphrases, or agent data, so exporting can never leak a secret.
 */
object HostTransferFile {
    const val VERSION_LINE = "# eyeShell saved hosts v1"

    val COLUMNS = listOf(
        "profileId",
        "name",
        "host",
        "port",
        "username",
        "authentication",
        "group",
        "tags",
        "jumpHostProfileId",
        "remark",
    )

    private const val CRLF = "\r\n"
    private const val TAG_SEPARATOR = ";"

    /** Serializes [hosts] with a UTF-8 BOM so spreadsheet tools detect the encoding. */
    fun format(hosts: List<SavedHost>): String = buildString {
        append('\uFEFF')
        append(VERSION_LINE).append(CRLF)
        append(COLUMNS.joinToString(",")).append(CRLF)
        hosts.forEach { host ->
            val draft = host.draft
            append(
                listOf(
                    host.profileId.toString(),
                    draft.name,
                    draft.endpoint.host,
                    draft.endpoint.port.toString(),
                    draft.endpoint.username,
                    draft.authenticationMethod.name,
                    draft.group.orEmpty(),
                    draft.tags.joinToString(TAG_SEPARATOR),
                    draft.jumpHostProfileId?.toString().orEmpty(),
                    draft.remark.orEmpty(),
                ).joinToString(",") { field -> quote(field) },
            )
            append(CRLF)
        }
    }

    /**
     * Writes [hosts] through a same-directory temporary file and an atomic replace, so a failed
     * export never leaves a half-written file behind.
     */
    fun write(path: Path, hosts: List<SavedHost>) {
        val target = path.toAbsolutePath().normalize()
        val parent = target.parent ?: throw IllegalArgumentException("Host export needs a parent directory")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".eyeshell-hosts-", ".tmp")
        try {
            Files.writeString(temporary, format(hosts), StandardCharsets.UTF_8)
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Reads an interchange file written by [write] or edited by hand. */
    fun read(path: Path): List<ImportedHost> = parse(Files.readString(path, StandardCharsets.UTF_8))

    /**
     * Parses an interchange file. Any malformed row rejects the whole file with the offending line
     * number, so an import can never apply half of a broken document.
     */
    fun parse(text: String): List<ImportedHost> {
        val records = parseRecords(text.removePrefix("\uFEFF"))
        val rows = records.filterNot { record -> record.fields.size == 1 && record.fields[0].isBlank() }
        if (rows.isEmpty() || rows[0].fields.size != 1 || rows[0].fields[0] != VERSION_LINE) {
            throw HostTransferException(rows.firstOrNull()?.line, HostTransferError.VERSION)
        }
        val header = rows.getOrNull(1)
            ?: throw HostTransferException(null, HostTransferError.COLUMNS, "missing header row")
        if (header.fields != COLUMNS) {
            throw HostTransferException(header.line, HostTransferError.COLUMNS, header.fields.joinToString(","))
        }
        val seen = mutableSetOf<UUID>()
        return rows.drop(2).map { record -> toImportedHost(record, seen) }
    }

    private fun toImportedHost(record: Record, seen: MutableSet<UUID>): ImportedHost {
        if (record.fields.size != COLUMNS.size) {
            throw HostTransferException(
                record.line,
                HostTransferError.FIELD_COUNT,
                "expected ${COLUMNS.size} columns, found ${record.fields.size}",
            )
        }
        val profile = record.fields[0]
        val name = record.fields[1]
        val host = record.fields[2]
        val port = record.fields[3]
        val username = record.fields[4]
        val authentication = record.fields[5]
        val group = record.fields[6]
        val tags = record.fields[7]
        val jump = record.fields[8]
        val remark = record.fields[9]
        val profileId = profile.trim().let { value ->
            runCatching { UUID.fromString(value) }.getOrElse {
                throw HostTransferException(record.line, HostTransferError.PROFILE_ID, value)
            }
        }
        if (!seen.add(profileId)) {
            throw HostTransferException(record.line, HostTransferError.DUPLICATE_PROFILE, profileId.toString())
        }
        requireField(record, HostTransferError.NAME, name)
        requireField(record, HostTransferError.HOST, host)
        requireField(record, HostTransferError.USERNAME, username)
        val portNumber = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw HostTransferException(record.line, HostTransferError.PORT, port)
        val method = runCatching { SavedAuthenticationMethod.valueOf(authentication.trim()) }.getOrElse {
            throw HostTransferException(record.line, HostTransferError.AUTHENTICATION, authentication)
        }
        val jumpProfileId = jump.trim().takeIf(String::isNotEmpty)?.let { value ->
            runCatching { UUID.fromString(value) }.getOrElse {
                throw HostTransferException(record.line, HostTransferError.JUMP_HOST, value)
            }
        }
        val draft = try {
            HostDraft(
                name = name,
                endpoint = SshEndpoint(host.trim(), portNumber, username.trim()),
                authenticationMethod = method,
                group = group.trim().takeIf(String::isNotEmpty),
                tags = tags.split(TAG_SEPARATOR).map(String::trim).filter(String::isNotEmpty),
                jumpHostProfileId = jumpProfileId,
                remark = remark.trim().takeIf(String::isNotEmpty),
            ).normalized()
        } catch (failure: IllegalArgumentException) {
            throw HostTransferException(record.line, HostTransferError.NAME, failure.message.orEmpty())
        }
        return ImportedHost(profileId, draft, record.line)
    }

    private fun requireField(record: Record, reason: HostTransferError, value: String) {
        if (value.isBlank()) throw HostTransferException(record.line, reason)
    }

    private fun quote(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }

    private data class Record(val line: Int, val fields: List<String>)

    /** RFC 4180 reader: quoted fields may contain commas and line breaks. */
    private fun parseRecords(text: String): List<Record> {
        val records = mutableListOf<Record>()
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var line = 1
        var recordLine = 1
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (inQuotes) {
                when {
                    character == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                        field.append('"')
                        index += 2
                        continue
                    }
                    character == '"' -> {
                        inQuotes = false
                        index++
                        continue
                    }
                    else -> {
                        if (character == '\n') line++
                        field.append(character)
                        index++
                        continue
                    }
                }
            }
            when (character) {
                '"' -> {
                    if (field.isNotEmpty()) throw HostTransferException(line, HostTransferError.QUOTING)
                    inQuotes = true
                    index++
                }
                ',' -> {
                    fields.add(field.toString())
                    field.setLength(0)
                    index++
                }
                '\r' -> index++
                '\n' -> {
                    fields.add(field.toString())
                    field.setLength(0)
                    records.add(Record(recordLine, fields.toList()))
                    fields.clear()
                    line++
                    recordLine = line
                    index++
                }
                else -> {
                    field.append(character)
                    index++
                }
            }
        }
        if (inQuotes) throw HostTransferException(recordLine, HostTransferError.QUOTING)
        if (field.isNotEmpty() || fields.isNotEmpty()) {
            fields.add(field.toString())
            records.add(Record(recordLine, fields.toList()))
        }
        return records
    }
}
