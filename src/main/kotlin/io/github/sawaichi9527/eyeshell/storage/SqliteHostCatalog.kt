package io.github.sawaichi9527.eyeshell.storage

import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.BasicFileAttributes
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import org.sqlite.SQLiteConfig

class SqliteHostCatalog(
    databasePath: Path,
) : HostCatalog {
    val databaseFile: Path = databasePath.toAbsolutePath().normalize()
    private val closed = AtomicBoolean()
    private var initialized = false
    private var connection: Connection? = null
    private var databaseIdentity: DatabaseIdentity? = null

    init {
        require(databasePath.isAbsolute) { "Host catalog database path must be absolute" }
    }

    @Synchronized
    override fun listHosts(): List<SavedHost> = withConnection { connection ->
        val hosts = linkedMapOf<Long, HostRow>()
        connection.prepareStatement(LIST_HOSTS).use { statement ->
            statement.executeQuery().use { results ->
                while (results.next()) {
                    val id = results.getLong("id")
                    val row = hosts.getOrPut(id) { results.toHostRow() }
                    results.getString("tag_name")?.let(row.tags::add)
                }
            }
        }
        hosts.values.map(HostRow::savedHost)
    }

    @Synchronized
    override fun createHost(host: HostDraft): SavedHost = writeTransaction { connection ->
        val normalized = host.normalized()
        val groupId = normalized.group?.let { resolveNameId(connection, "host_groups", it) }
        val id = connection.prepareStatement(CREATE_HOST).use { statement ->
            statement.setObject(1, groupId)
            statement.setString(2, normalized.name)
            statement.setString(3, normalized.endpoint.host)
            statement.setInt(4, normalized.endpoint.port)
            statement.setString(5, normalized.endpoint.username)
            statement.setString(6, normalized.authenticationMethod.name)
            statement.executeQuery().use { results ->
                check(results.next()) { "Host insert did not return an identifier" }
                results.getLong(1)
            }
        }
        replaceTags(connection, id, normalized.tags)
        SavedHost(id, normalized)
    }

    @Synchronized
    override fun updateHost(id: Long, host: HostDraft): SavedHost = writeTransaction { connection ->
        require(id > 0) { "Host identifier must be positive" }
        val normalized = host.normalized()
        val groupId = normalized.group?.let { resolveNameId(connection, "host_groups", it) }
        val updated = connection.prepareStatement(UPDATE_HOST).use { statement ->
            statement.setObject(1, groupId)
            statement.setString(2, normalized.name)
            statement.setString(3, normalized.endpoint.host)
            statement.setInt(4, normalized.endpoint.port)
            statement.setString(5, normalized.endpoint.username)
            statement.setString(6, normalized.authenticationMethod.name)
            statement.setLong(7, id)
            statement.executeUpdate()
        }
        require(updated == 1) { "Unknown host profile: $id" }
        replaceTags(connection, id, normalized.tags)
        SavedHost(id, normalized)
    }

    @Synchronized
    override fun deleteHost(id: Long) {
        require(id > 0) { "Host identifier must be positive" }
        writeTransaction { connection ->
            connection.prepareStatement("DELETE FROM hosts WHERE id = ?").use { statement ->
                statement.setLong(1, id)
                statement.executeUpdate()
            }
        }
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connection?.close()
        connection = null
    }

    private fun <T> withConnection(operation: (Connection) -> T): T {
        checkOpen()
        ensureInitialized()
        requireCurrentDatabaseIdentity()
        return operation(checkNotNull(connection))
    }

    private fun <T> writeTransaction(operation: (Connection) -> T): T = withConnection { connection ->
        connection.autoCommit = false
        try {
            operation(connection).also { connection.commit() }
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    @Synchronized
    private fun ensureInitialized() {
        if (initialized) return
        checkOpen()
        preparePrivateDatabaseFile()
        val opened = openConnection()
        var transferred = false
        try {
            migrate(opened)
            setPosixPermissions(databaseFile, FILE_PERMISSIONS)
            databaseIdentity = readDatabaseIdentity()
            connection = opened
            initialized = true
            transferred = true
        } finally {
            if (!transferred) opened.close()
        }
    }

    private fun migrate(connection: Connection) {
        connection.autoCommit = false
        try {
            connection.createStatement().use { it.execute(CREATE_SCHEMA_VERSIONS) }
            val version = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_versions").use { results ->
                    results.next()
                    results.getInt(1)
                }
            }
            check(version <= CURRENT_SCHEMA_VERSION) {
                "Host catalog schema version $version is newer than supported version $CURRENT_SCHEMA_VERSION"
            }
            if (version == 0) {
                INITIAL_SCHEMA.forEach { sql -> connection.createStatement().use { it.execute(sql) } }
                connection.prepareStatement(
                    "INSERT INTO schema_versions(version, description) VALUES (?, ?)",
                ).use { statement ->
                    statement.setInt(1, CURRENT_SCHEMA_VERSION)
                    statement.setString(2, "initial host catalog")
                    statement.executeUpdate()
                }
            }
            connection.commit()
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_key_check").use { results ->
                check(!results.next()) { "Host catalog contains invalid foreign keys" }
            }
        }
    }

    private fun openConnection(): Connection {
        val config = SQLiteConfig().apply {
            enforceForeignKeys(true)
            setBusyTimeout(BUSY_TIMEOUT_MILLIS)
            setJournalMode(SQLiteConfig.JournalMode.DELETE)
            setSynchronous(SQLiteConfig.SynchronousMode.FULL)
            setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE)
        }
        requirePrivateDatabasePath()
        val identity = readDatabaseIdentity()
        val connection = DriverManager.getConnection("jdbc:sqlite:$databaseFile", config.toProperties())
        try {
            requirePrivateDatabasePath()
            val openedIdentity = readDatabaseIdentity()
            require(identity == openedIdentity) {
                "Host catalog database changed while it was being opened"
            }
            return connection
        } catch (failure: Throwable) {
            connection.close()
            throw failure
        }
    }

    private fun preparePrivateDatabaseFile() {
        val directory = requireNotNull(databaseFile.parent) { "Host catalog database must have a parent directory" }
        requireNoSymbolicLinks(directory)
        Files.createDirectories(directory)
        requireNoSymbolicLinks(directory)
        setPosixPermissions(directory, DIRECTORY_PERMISSIONS)
        requireNoSymbolicLinks(databaseFile)
        if (!Files.exists(databaseFile, LinkOption.NOFOLLOW_LINKS)) Files.createFile(databaseFile)
        requirePrivateDatabasePath()
        setPosixPermissions(databaseFile, FILE_PERMISSIONS)
        prepareJournalSidecar()
    }

    private fun requirePrivateDatabasePath() {
        requireNoSymbolicLinks(databaseFile)
        require(Files.isRegularFile(databaseFile, LinkOption.NOFOLLOW_LINKS)) {
            "Host catalog database must be a regular file: $databaseFile"
        }
    }

    private fun prepareJournalSidecar() {
        val journal = databaseFile.resolveSibling("${databaseFile.fileName}-journal")
        requireNoSymbolicLinks(journal)
        if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) {
                "Host catalog journal must be a regular file: $journal"
            }
            setPosixPermissions(journal, FILE_PERMISSIONS)
        }
    }

    private fun requireCurrentDatabaseIdentity() {
        requirePrivateDatabasePath()
        require(readDatabaseIdentity() == databaseIdentity) {
            "Host catalog database changed after it was opened"
        }
    }

    private fun readDatabaseIdentity(): DatabaseIdentity {
        val attributes = Files.readAttributes(
            databaseFile,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        return DatabaseIdentity(attributes.fileKey(), attributes.creationTime().toMillis())
    }

    private fun requireNoSymbolicLinks(path: Path) {
        var current = path.root ?: Path.of("")
        path.forEach { element ->
            current = current.resolve(element)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(current)) { "Host catalog path must not contain symbolic links: $current" }
            }
        }
    }

    private fun replaceTags(connection: Connection, hostId: Long, tags: List<String>) {
        connection.prepareStatement("DELETE FROM host_tags WHERE host_id = ?").use { statement ->
            statement.setLong(1, hostId)
            statement.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO host_tags(host_id, tag_id) VALUES (?, ?)").use { statement ->
            tags.forEach { tag ->
                statement.setLong(1, hostId)
                statement.setLong(2, resolveNameId(connection, "tags", tag))
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun resolveNameId(connection: Connection, table: String, name: String): Long {
        require(table == "host_groups" || table == "tags")
        connection.prepareStatement("INSERT INTO $table(name) VALUES (?) ON CONFLICT(name) DO NOTHING").use { statement ->
            statement.setString(1, name)
            statement.executeUpdate()
        }
        return connection.prepareStatement("SELECT id FROM $table WHERE name = ?").use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { results ->
                check(results.next()) { "Failed to resolve $table entry" }
                results.getLong(1)
            }
        }
    }

    private fun ResultSet.toHostRow(): HostRow = try {
        HostRow(
            id = getLong("id"),
            name = getString("name"),
            endpoint = SshEndpoint(getString("host"), getInt("port"), getString("username")),
            authenticationMethod = SavedAuthenticationMethod.valueOf(getString("authentication_method")),
            group = getString("group_name"),
        )
    } catch (failure: IllegalArgumentException) {
        throw SQLException("Host catalog contains invalid profile data", failure)
    }

    private fun checkOpen() {
        check(!closed.get()) { "Host catalog is closed" }
    }

    private fun setPosixPermissions(path: Path, permissions: Set<PosixFilePermission>) {
        try {
            Files.setPosixFilePermissions(path, permissions)
        } catch (_: UnsupportedOperationException) {
            // Windows ACLs are inherited from the user's Local AppData directory.
        }
    }

    private data class HostRow(
        val id: Long,
        val name: String,
        val endpoint: SshEndpoint,
        val authenticationMethod: SavedAuthenticationMethod,
        val group: String?,
        val tags: MutableList<String> = mutableListOf(),
    ) {
        fun savedHost(): SavedHost = SavedHost(
            id,
            HostDraft(name, endpoint, authenticationMethod, group, tags.toList()).normalized(),
        )
    }

    private data class DatabaseIdentity(
        val fileKey: Any?,
        val createdAtMillis: Long,
    )

    companion object {
        private const val CURRENT_SCHEMA_VERSION = 1
        private const val BUSY_TIMEOUT_MILLIS = 5_000
        private val DIRECTORY_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        private val FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        private const val CREATE_SCHEMA_VERSIONS = """
            CREATE TABLE IF NOT EXISTS schema_versions (
                version INTEGER PRIMARY KEY CHECK (version > 0),
                description TEXT NOT NULL CHECK (length(trim(description)) > 0),
                applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            ) STRICT
        """
        private val INITIAL_SCHEMA = listOf(
            """
                CREATE TABLE host_groups (
                    id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL UNIQUE CHECK (length(trim(name)) > 0),
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                ) STRICT
            """,
            """
                CREATE TABLE hosts (
                    id INTEGER PRIMARY KEY,
                    group_id INTEGER,
                    name TEXT NOT NULL CHECK (length(trim(name)) > 0),
                    host TEXT NOT NULL CHECK (length(trim(host)) > 0),
                    port INTEGER NOT NULL CHECK (port BETWEEN 1 AND 65535),
                    username TEXT NOT NULL CHECK (length(trim(username)) > 0),
                    authentication_method TEXT NOT NULL CHECK (
                        authentication_method IN ('PASSWORD', 'PUBLIC_KEY', 'KEYBOARD_INTERACTIVE', 'SSH_AGENT')
                    ),
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (group_id) REFERENCES host_groups(id) ON UPDATE CASCADE ON DELETE SET NULL
                ) STRICT
            """,
            "CREATE INDEX hosts_group_id_idx ON hosts(group_id)",
            """
                CREATE TABLE tags (
                    id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL UNIQUE CHECK (length(trim(name)) > 0),
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                ) STRICT
            """,
            """
                CREATE TABLE host_tags (
                    host_id INTEGER NOT NULL,
                    tag_id INTEGER NOT NULL,
                    PRIMARY KEY (host_id, tag_id),
                    FOREIGN KEY (host_id) REFERENCES hosts(id) ON UPDATE CASCADE ON DELETE CASCADE,
                    FOREIGN KEY (tag_id) REFERENCES tags(id) ON UPDATE CASCADE ON DELETE CASCADE
                ) WITHOUT ROWID, STRICT
            """,
            "CREATE INDEX host_tags_tag_id_idx ON host_tags(tag_id)",
        )
        private const val LIST_HOSTS = """
            SELECT h.id, h.name, h.host, h.port, h.username, h.authentication_method,
                   g.name AS group_name, t.name AS tag_name
            FROM hosts h
            LEFT JOIN host_groups g ON g.id = h.group_id
            LEFT JOIN host_tags ht ON ht.host_id = h.id
            LEFT JOIN tags t ON t.id = ht.tag_id
            ORDER BY h.name, h.id, t.name
        """
        private const val CREATE_HOST = """
            INSERT INTO hosts(group_id, name, host, port, username, authentication_method)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING id
        """
        private const val UPDATE_HOST = """
            UPDATE hosts
            SET group_id = ?, name = ?, host = ?, port = ?, username = ?, authentication_method = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
        """
    }
}
