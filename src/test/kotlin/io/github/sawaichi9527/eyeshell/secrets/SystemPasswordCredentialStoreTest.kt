package io.github.sawaichi9527.eyeshell.secrets

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class SystemPasswordCredentialStoreTest {
    @Test
    fun `selects a store for supported operating systems`() {
        assertInstanceOf(WindowsPasswordCredentialStore::class.java, SystemPasswordCredentialStore.create("Windows 11"))
        assertInstanceOf(LinuxSecretServicePasswordStore::class.java, SystemPasswordCredentialStore.create("Linux"))
        assertInstanceOf(UnavailablePasswordCredentialStore::class.java, SystemPasswordCredentialStore.create("macOS"))
    }

    @Test
    fun `windows store keys credentials by profile UUID`() {
        val profileId = UUID.fromString("00000000-0000-0000-0000-000000000042")
        val api = FakeWindowsCredentialApi()
        val store = WindowsPasswordCredentialStore(api)
        val password = charArrayOf('s', 'e', 'c', 'r', 'e', 't')

        assertEquals(CredentialStoreStatus.AVAILABLE, store.status())
        store.save(profileId, password)
        assertArrayEquals(charArrayOf('s', 'e', 'c', 'r', 'e', 't'), password)
        store.retrieve(profileId).use { stored ->
            assertArrayEquals(password, stored?.copyValue())
        }
        store.forget(profileId)

        assertEquals("eyeShell/ssh/$profileId", api.lastTarget)
        assertNull(store.retrieve(profileId))
    }

    @Test
    fun `linux store reports lock and creates updates and deletes one profile secret`() {
        val profileId = UUID.fromString("00000000-0000-0000-0000-000000000043")
        val collection = FakeSecretCollection()
        val store = LinuxSecretServicePasswordStore { collection }

        collection.locked = true
        assertEquals(CredentialStoreStatus.LOCKED, store.status())
        collection.locked = false
        store.save(profileId, charArrayOf('o', 'n', 'e'))
        store.save(profileId, charArrayOf('t', 'w', 'o'))
        store.retrieve(profileId).use { stored ->
            assertArrayEquals(charArrayOf('t', 'w', 'o'), stored?.copyValue())
        }
        store.forget(profileId)

        assertEquals(1, collection.created)
        assertEquals(1, collection.updated)
        assertTrue(collection.values.isEmpty())
        assertEquals(mapOf("application" to "eyeShell", "profile-id" to profileId.toString()), collection.lastAttributes)
    }

    @Test
    fun `linux store reports existence without unlocking the collection`() {
        val profileId = UUID.fromString("00000000-0000-0000-0000-000000000048")
        val collection = FakeSecretCollection().apply { locked = true }
        val store = LinuxSecretServicePasswordStore { collection }

        assertFalse(store.contains(profileId))
        assertEquals(0, collection.unlockCalls, "A missing credential must not unlock the keyring")

        collection.locked = false
        store.save(profileId, charArrayOf('s', 'e', 'c'))
        assertTrue(store.contains(profileId))
        assertEquals(0, collection.unlockCalls)
    }

    @Test
    fun `windows store reports existence without exposing the secret`() {
        val profileId = UUID.fromString("00000000-0000-0000-0000-000000000049")
        val api = FakeWindowsCredentialApi()
        val store = WindowsPasswordCredentialStore(api)

        assertFalse(store.contains(profileId))
        store.save(profileId, charArrayOf('s', 'e', 'c'))
        assertTrue(store.contains(profileId))
    }

    @Test
    fun `linux store unlocks a locked collection before writing`() {
        val profileId = UUID.fromString("00000000-0000-0000-0000-000000000046")
        val collection = FakeSecretCollection().apply { locked = true }
        val store = LinuxSecretServicePasswordStore { collection }

        store.save(profileId, charArrayOf('s', 'e', 'c'))

        assertEquals(1, collection.unlockCalls)
        assertStoredPassword(store, profileId, charArrayOf('s', 'e', 'c'))
    }

    @Test
    fun `linux store fails closed when the unlock is refused`() {
        val profileId = UUID.fromString("00000000-0000-0000-0000-000000000047")
        val collection = object : SecretCollection by FakeSecretCollection() {
            override fun isLocked(): Boolean = true
            override fun unlock() = Unit
        }
        val store = LinuxSecretServicePasswordStore { collection }

        assertThrows(CredentialStoreException::class.java) {
            store.save(profileId, charArrayOf('s', 'e', 'c'))
        }
    }

    @Test
    fun `platform failures report unavailable instead of escaping`() {
        val windows = WindowsPasswordCredentialStore(object : WindowsCredentialApi by FakeWindowsCredentialApi() {
            override fun probe() = throw UnsatisfiedLinkError("missing")
        })
        val linux = LinuxSecretServicePasswordStore { throw IllegalStateException("no session bus") }

        assertEquals(CredentialStoreStatus.UNAVAILABLE, windows.status())
        assertEquals(CredentialStoreStatus.UNAVAILABLE, linux.status())
    }

    @Test
    fun `linux store isolates credentials by profile UUID`() {
        val firstProfile = UUID.fromString("00000000-0000-0000-0000-000000000044")
        val secondProfile = UUID.fromString("00000000-0000-0000-0000-000000000045")
        val collection = FakeSecretCollection()
        val store = LinuxSecretServicePasswordStore { collection }

        store.save(firstProfile, charArrayOf('o', 'n', 'e'))
        store.save(secondProfile, charArrayOf('t', 'w', 'o'))
        assertStoredPassword(store, firstProfile, charArrayOf('o', 'n', 'e'))
        assertStoredPassword(store, secondProfile, charArrayOf('t', 'w', 'o'))
        store.forget(firstProfile)

        assertNull(store.retrieve(firstProfile))
        assertStoredPassword(store, secondProfile, charArrayOf('t', 'w', 'o'))
    }

    @Test
    fun `live Linux Secret Service supports the credential lifecycle`() {
        assumeTrue(System.getenv("EYESHELL_TEST_LIVE_SECRET_SERVICE") == "1")
        val profileId = UUID.randomUUID()
        val firstPassword = charArrayOf('e', 'y', 'e', 'S', 'h', 'e', 'l', 'l', '-', 'o', 'n', 'e')
        val secondPassword = charArrayOf('e', 'y', 'e', 'S', 'h', 'e', 'l', 'l', '-', 't', 'w', 'o')
        val store = LinuxSecretServicePasswordStore()

        try {
            assertEquals(CredentialStoreStatus.AVAILABLE, store.status())
            try {
                assertFalse(store.contains(profileId), "A random profile must not exist yet")
                store.save(profileId, firstPassword)
                assertTrue(store.contains(profileId))
                assertStoredPassword(store, profileId, firstPassword)
                store.save(profileId, secondPassword)
                assertStoredPassword(store, profileId, secondPassword)
            } finally {
                store.forget(profileId)
            }
            assertFalse(store.contains(profileId))
            assertNull(store.retrieve(profileId))
        } finally {
            firstPassword.fill('\u0000')
            secondPassword.fill('\u0000')
            store.close()
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    fun `live Windows Credential Manager supports the credential lifecycle`() {
        assumeTrue(System.getenv("EYESHELL_TEST_LIVE_WINDOWS_CREDENTIAL_MANAGER") == "1")
        val profileId = UUID.randomUUID()
        val firstPassword = charArrayOf('e', 'y', 'e', 'S', 'h', 'e', 'l', 'l', '-', 'o', 'n', 'e')
        val secondPassword = charArrayOf('e', 'y', 'e', 'S', 'h', 'e', 'l', 'l', '-', 't', 'w', 'o')
        val store = WindowsPasswordCredentialStore()

        try {
            assertEquals(CredentialStoreStatus.AVAILABLE, store.status())
            try {
                store.save(profileId, firstPassword)
                assertStoredPassword(store, profileId, firstPassword)
                store.save(profileId, secondPassword)
                assertStoredPassword(store, profileId, secondPassword)
            } finally {
                store.forget(profileId)
            }
            assertNull(store.retrieve(profileId))
        } finally {
            firstPassword.fill('\u0000')
            secondPassword.fill('\u0000')
        }
    }

    private fun assertStoredPassword(
        store: PasswordCredentialStore,
        profileId: UUID,
        expected: CharArray,
    ) {
        store.retrieve(profileId).use { stored ->
            val actual = checkNotNull(stored).copyValue()
            try {
                assertArrayEquals(expected, actual)
            } finally {
                actual.fill('\u0000')
            }
        }
    }

    private class FakeWindowsCredentialApi : WindowsCredentialApi {
        var lastTarget: String? = null
        private var value: CharArray? = null

        override fun probe() = Unit

        override fun exists(target: String): Boolean {
            lastTarget = target
            return value != null
        }

        override fun read(target: String): CharArray? {
            lastTarget = target
            return value?.copyOf()
        }

        override fun write(target: String, password: CharArray) {
            lastTarget = target
            value = password.copyOf()
        }

        override fun delete(target: String) {
            lastTarget = target
            value?.fill('\u0000')
            value = null
        }
    }

    private class FakeSecretCollection : SecretCollection {
        var locked = false
        var unlockCalls = 0
        var created = 0
        var updated = 0
        var lastAttributes: Map<String, String>? = null
        val values = mutableMapOf<String, FakeItem>()

        override fun isLocked(): Boolean = locked

        override fun unlock() {
            unlockCalls++
            locked = false
        }

        override fun items(attributes: Map<String, String>): List<String> {
            lastAttributes = attributes
            return values.filterValues { item -> item.attributes.entries.containsAll(attributes.entries) }.keys.toList()
        }

        override fun secret(item: String): CharArray? = values[item]?.password?.copyOf()

        override fun create(label: String, password: CharArray, attributes: Map<String, String>) {
            created++
            lastAttributes = attributes
            values["/item/${created}"] = FakeItem(password.copyOf(), attributes)
        }

        override fun update(item: String, label: String, password: CharArray, attributes: Map<String, String>) {
            updated++
            lastAttributes = attributes
            values[item]?.password?.fill('\u0000')
            values[item] = FakeItem(password.copyOf(), attributes)
        }

        override fun delete(item: String) {
            values.remove(item)?.password?.fill('\u0000')
        }

        override fun close() = Unit

        private data class FakeItem(
            val password: CharArray,
            val attributes: Map<String, String>,
        )
    }
}
