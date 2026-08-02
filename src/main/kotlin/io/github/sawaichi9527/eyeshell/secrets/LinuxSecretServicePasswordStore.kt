package io.github.sawaichi9527.eyeshell.secrets

import java.nio.CharBuffer
import java.util.Arrays
import java.util.UUID
import org.freedesktop.secret.simple.SimpleCollection

internal class LinuxSecretServicePasswordStore(
    private val collections: SecretCollectionFactory = FreedesktopSecretCollectionFactory,
) : PasswordCredentialStore {
    override fun status(): CredentialStoreStatus = try {
        collections.open().use { collection ->
            if (collection.isLocked()) CredentialStoreStatus.LOCKED else CredentialStoreStatus.AVAILABLE
        }
    } catch (_: Exception) {
        CredentialStoreStatus.UNAVAILABLE
    } catch (_: LinkageError) {
        CredentialStoreStatus.UNAVAILABLE
    }

    override fun retrieve(profileId: UUID): StoredPassword? = withCollection("read") { collection ->
        val item = collection.items(attributes(profileId)).firstOrNull() ?: return@withCollection null
        val password = collection.secret(item)
            ?: throw CredentialStoreException("Secret service returned no value for an existing item")
        try {
            StoredPassword(password)
        } finally {
            Arrays.fill(password, '\u0000')
        }
    }

    override fun save(profileId: UUID, password: CharArray) = withCollection("save") { collection ->
        require(password.isNotEmpty()) { "Password must not be empty" }
        val attributes = attributes(profileId)
        val item = collection.items(attributes).firstOrNull()
        if (item == null) {
            collection.create("eyeShell SSH password", password, attributes)
        } else {
            collection.update(item, "eyeShell SSH password", password, attributes)
        }
        val savedItem = collection.items(attributes).firstOrNull()
            ?: throw CredentialStoreException("Secret service did not retain the saved item")
        val savedPassword = collection.secret(savedItem)
            ?: throw CredentialStoreException("Secret service returned no value for the saved item")
        try {
            if (!savedPassword.contentEquals(password)) {
                throw CredentialStoreException("Secret service did not retain the updated password")
            }
        } finally {
            Arrays.fill(savedPassword, '\u0000')
        }
    }

    override fun forget(profileId: UUID) = withCollection("delete") { collection ->
        val attributes = attributes(profileId)
        collection.items(attributes).forEach(collection::delete)
        if (collection.items(attributes).isNotEmpty()) {
            throw CredentialStoreException("Secret service did not delete the saved item")
        }
    }

    private fun attributes(profileId: UUID): Map<String, String> = mapOf(
        "application" to "eyeShell",
        "profile-id" to profileId.toString(),
    )

    private inline fun <T> withCollection(operation: String, action: (SecretCollection) -> T): T = try {
        collections.open().use { collection ->
            if (collection.isLocked()) throw CredentialStoreException("Linux secret service is locked")
            action(collection)
        }
    } catch (failure: CredentialStoreException) {
        throw failure
    } catch (failure: Exception) {
        throw CredentialStoreException("Could not $operation Linux secret", failure)
    } catch (failure: LinkageError) {
        throw CredentialStoreException("Could not $operation Linux secret", failure)
    }
}

internal fun interface SecretCollectionFactory {
    fun open(): SecretCollection
}

internal interface SecretCollection : AutoCloseable {
    fun isLocked(): Boolean
    fun items(attributes: Map<String, String>): List<String>
    fun secret(item: String): CharArray?
    fun create(label: String, password: CharArray, attributes: Map<String, String>)
    fun update(item: String, label: String, password: CharArray, attributes: Map<String, String>)
    fun delete(item: String)
}

private object FreedesktopSecretCollectionFactory : SecretCollectionFactory {
    override fun open(): SecretCollection = FreedesktopSecretCollection(SimpleCollection())
}

private class FreedesktopSecretCollection(
    private val collection: SimpleCollection,
) : SecretCollection {
    override fun isLocked(): Boolean = collection.isLocked

    override fun items(attributes: Map<String, String>): List<String> = collection.getItems(attributes).orEmpty()

    override fun secret(item: String): CharArray? = collection.getSecret(item)

    override fun create(label: String, password: CharArray, attributes: Map<String, String>) {
        collection.createItem(label, CharBuffer.wrap(password), attributes)
            ?: throw IllegalStateException("Secret service did not create an item")
    }

    override fun update(item: String, label: String, password: CharArray, attributes: Map<String, String>) {
        collection.updateItem(item, label, CharBuffer.wrap(password), attributes)
    }

    override fun delete(item: String) {
        collection.deleteItem(item)
    }

    override fun close() = collection.close()
}
