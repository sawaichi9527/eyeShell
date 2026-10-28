package io.github.sawaichi9527.eyeshell.sftp

/**
 * uid/gid to display-name mapping for the SFTP owner column.
 *
 * SFTP v3 directory listings (OpenSSH included) only carry numeric `uid`/`gid`; names arrive with
 * SFTP v4's `OwnerGroup` attribute. To show `eye / eye` instead of `1000 / 1000`, the browser fetches
 * the account database once per session over the connection it already has and caches it here.
 * The lookup never blocks a GUI thread: [OwnerNameResolver.decorate] runs on the controller's worker.
 */
data class OwnerNames(
    val users: Map<Int, String> = emptyMap(),
    val groups: Map<Int, String> = emptyMap(),
) {
    companion object {
        val EMPTY = OwnerNames()

        const val GROUPS_MARKER = "#eyeshell-groups#"

        /** Fetches passwd then group entries, separated so one command can return both. */
        const val COMMAND: String =
            "{ getent passwd || cat /etc/passwd; } 2>/dev/null; " +
                "echo '$GROUPS_MARKER'; " +
                "{ getent group || cat /etc/group; } 2>/dev/null"
    }
}

/** Parses `passwd`/`group` text (`name:x:id:...`) into id-to-name maps. */
object OwnerNamesParser {
    fun parse(output: String): OwnerNames {
        val sections = output.split(OwnerNames.GROUPS_MARKER, limit = 2)
        return OwnerNames(
            users = parseEntries(sections.getOrElse(0) { "" }),
            groups = parseEntries(sections.getOrElse(1) { "" }),
        )
    }

    private fun parseEntries(text: String): Map<Int, String> = buildMap {
        text.lineSequence().forEach { line ->
            val fields = line.trim().split(':')
            if (fields.size < 3) return@forEach
            val name = fields[0].trim()
            val id = fields[2].trim().toIntOrNull()
            if (name.isNotEmpty() && id != null && id >= 0) putIfAbsent(id, name)
        }
    }
}

/**
 * Resolves the numeric owner/group values produced by [io.github.sawaichi9527.eyeshell.ssh.MinaSftpClient]
 * into names, loading the account database at most once per instance (one SSH session).
 *
 * A failed fetch is cached as [OwnerNames.EMPTY] so an unavailable `getent`/`/etc/passwd` costs one
 * attempt per session instead of one per directory listing; numeric ids keep showing in that case.
 */
class OwnerNameResolver(private val fetch: () -> String) {
    @Volatile
    private var names: OwnerNames? = null

    fun decorate(files: List<RemoteFile>): List<RemoteFile> {
        if (files.none { it.hasNumericOwner() }) return files
        val resolved = names ?: loadOnce()
        return files.map { file ->
            file.copy(
                owner = resolved.users[file.owner?.toIntOrNull()] ?: file.owner,
                group = resolved.groups[file.group?.toIntOrNull()] ?: file.group,
            )
        }
    }

    private fun loadOnce(): OwnerNames {
        names?.let { return it }
        return synchronized(this) {
            names ?: runCatching { OwnerNamesParser.parse(fetch()) }
                .getOrDefault(OwnerNames.EMPTY)
                .also { names = it }
        }
    }

    private fun RemoteFile.hasNumericOwner(): Boolean =
        owner?.toIntOrNull() != null || group?.toIntOrNull() != null
}
