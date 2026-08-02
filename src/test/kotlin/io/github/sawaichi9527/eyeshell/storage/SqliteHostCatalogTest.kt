package io.github.sawaichi9527.eyeshell.storage

import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.sql.DriverManager
import java.util.Collections
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SqliteHostCatalogTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `migrates and persists host profiles with groups and tags`() {
        val database = temporaryDirectory.resolve("data").resolve("eyeshell.db")
        val draft = HostDraft(
            name = "  Lab '主機'; DROP TABLE hosts;  ",
            endpoint = SshEndpoint("2001:db8::10", 2222, " 測試員 "),
            authenticationMethod = SavedAuthenticationMethod.KEYBOARD_INTERACTIVE,
            group = " Lab設備 ",
            tags = listOf(" nightly ", "中文", "nightly"),
        )

        val created = SqliteHostCatalog(database).use { catalog ->
            catalog.createHost(draft)
        }
        val reopened = SqliteHostCatalog(database)

        try {
            assertEquals(listOf(created), reopened.listHosts())
            assertEquals("Lab '主機'; DROP TABLE hosts;", created.draft.name)
            assertEquals("測試員", created.draft.endpoint.username)
            assertEquals(listOf("nightly", "中文"), created.draft.tags)
            assertEquals(1, queryInt(database, "SELECT MAX(version) FROM schema_versions"))
            assertEquals(5, queryInt(database, "SELECT COUNT(*) FROM sqlite_schema WHERE type = 'table'"))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `updates and deletes a profile transactionally`() {
        val database = temporaryDirectory.resolve("catalog.db")
        SqliteHostCatalog(database).use { catalog ->
            val created = catalog.createHost(draft("Original", "host-one"))
            val updated = catalog.updateHost(
                created.id,
                draft("Updated", "host-two").copy(
                    authenticationMethod = SavedAuthenticationMethod.SSH_AGENT,
                    group = "Production",
                    tags = listOf("critical"),
                ),
            )

            assertEquals(listOf(updated), catalog.listHosts())
            catalog.deleteHost(created.id)
            assertTrue(catalog.listHosts().isEmpty())
            assertEquals(0, queryInt(database, "SELECT COUNT(*) FROM host_tags"))
        }
    }

    @Test
    fun `failed update rolls back newly resolved catalog values`() {
        val database = temporaryDirectory.resolve("catalog.db")
        SqliteHostCatalog(database).use { catalog ->
            assertThrows(IllegalArgumentException::class.java) {
                catalog.updateHost(
                    999,
                    draft("Missing", "missing").copy(group = "Must rollback", tags = listOf("rollback-tag")),
                )
            }
        }

        assertEquals(0, queryInt(database, "SELECT COUNT(*) FROM host_groups"))
        assertEquals(0, queryInt(database, "SELECT COUNT(*) FROM tags"))
    }

    @Test
    fun `schema excludes secret material and rejects newer versions`() {
        val database = temporaryDirectory.resolve("catalog.db")
        SqliteHostCatalog(database).use { it.listHosts() }
        val columns = mutableListOf<String>()
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM pragma_table_info('hosts')").use { results ->
                    while (results.next()) columns += results.getString(1)
                }
                statement.execute("INSERT INTO schema_versions(version, description) VALUES (2, 'future')")
            }
        }

        assertTrue(columns.containsAll(listOf("name", "host", "port", "username", "authentication_method")))
        assertFalse(columns.any { it.contains("password", true) || it.contains("passphrase", true) || it.contains("secret", true) })
        assertThrows(IllegalStateException::class.java) {
            SqliteHostCatalog(database).use { it.listHosts() }
        }
    }

    @Test
    fun `creates owner-only POSIX directory and database permissions`() {
        assumeTrue(Files.getFileStore(temporaryDirectory).supportsFileAttributeView("posix"))
        val database = temporaryDirectory.resolve("private").resolve("catalog.db")

        SqliteHostCatalog(database).use { it.listHosts() }

        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            Files.getPosixFilePermissions(database.parent),
        )
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(database),
        )
    }

    @Test
    fun `rejects symbolic link database and operations after close`() {
        assumeTrue(Files.getFileStore(temporaryDirectory).supportsFileAttributeView("posix"))
        val target = Files.createFile(temporaryDirectory.resolve("target.db"))
        val link = temporaryDirectory.resolve("catalog.db")
        Files.createSymbolicLink(link, target)

        assertThrows(IllegalArgumentException::class.java) {
            SqliteHostCatalog(link).use { it.listHosts() }
        }

        val catalog = SqliteHostCatalog(temporaryDirectory.resolve("closed.db"))
        catalog.close()
        assertThrows(IllegalStateException::class.java) { catalog.listHosts() }
    }

    @Test
    fun `rejects a symbolic link in the database directory path`() {
        assumeTrue(Files.getFileStore(temporaryDirectory).supportsFileAttributeView("posix"))
        val realDirectory = Files.createDirectory(temporaryDirectory.resolve("real"))
        val linkedDirectory = temporaryDirectory.resolve("linked")
        Files.createSymbolicLink(linkedDirectory, realDirectory)

        assertThrows(IllegalArgumentException::class.java) {
            SqliteHostCatalog(linkedDirectory.resolve("catalog.db")).use { it.listHosts() }
        }
    }

    @Test
    fun `rejects symbolic link journal sidecar`() {
        assumeTrue(Files.getFileStore(temporaryDirectory).supportsFileAttributeView("posix"))
        val database = temporaryDirectory.resolve("catalog.db")
        val journalTarget = Files.createFile(temporaryDirectory.resolve("journal-target"))
        Files.createSymbolicLink(temporaryDirectory.resolve("catalog.db-journal"), journalTarget)

        assertThrows(IllegalArgumentException::class.java) {
            SqliteHostCatalog(database).use { it.listHosts() }
        }
    }

    @Test
    fun `rejects database replacement after initialization`() {
        assumeTrue(Files.getFileStore(temporaryDirectory).supportsFileAttributeView("posix"))
        val database = temporaryDirectory.resolve("catalog.db")
        val catalog = SqliteHostCatalog(database)
        try {
            catalog.listHosts()
            Files.move(database, temporaryDirectory.resolve("original.db"))
            Files.createFile(database)

            assertThrows(IllegalArgumentException::class.java) { catalog.listHosts() }
        } finally {
            catalog.close()
        }
    }

    @Test
    fun `requires an absolute database path`() {
        assertThrows(IllegalArgumentException::class.java) { SqliteHostCatalog(Path.of("relative.db")) }
    }

    @Test
    fun `loads JDBC classes from the artifact without bundled cross-platform natives`() {
        val resources = Collections.list(
            org.sqlite.JDBC::class.java.classLoader.getResources("org/sqlite/JDBC.class"),
        )

        assertEquals(1, resources.size)
        assertTrue(resources.single().toString().contains("without-natives"), resources.single().toString())
    }

    private fun draft(name: String, host: String) = HostDraft(
        name = name,
        endpoint = SshEndpoint(host, 22, "operator"),
        authenticationMethod = SavedAuthenticationMethod.PASSWORD,
    )

    private fun queryInt(database: Path, sql: String): Int =
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { results ->
                    results.next()
                    results.getInt(1)
                }
            }
        }
}
