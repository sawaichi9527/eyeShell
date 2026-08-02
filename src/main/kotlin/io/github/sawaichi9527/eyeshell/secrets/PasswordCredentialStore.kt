package io.github.sawaichi9527.eyeshell.secrets

import java.util.Arrays
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class CredentialStoreStatus {
    AVAILABLE,
    UNAVAILABLE,
    LOCKED,
}

interface PasswordCredentialStore : AutoCloseable {
    fun status(): CredentialStoreStatus

    fun retrieve(profileId: UUID): StoredPassword?

    fun save(profileId: UUID, password: CharArray)

    fun forget(profileId: UUID)

    override fun close() = Unit
}

class StoredPassword(password: CharArray) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val value = password.copyOf()

    init {
        require(value.isNotEmpty()) { "Stored password must not be empty" }
    }

    fun copyValue(): CharArray {
        check(!closed.get()) { "Stored password is closed" }
        return value.copyOf()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) Arrays.fill(value, '\u0000')
    }

    override fun toString(): String = "StoredPassword[closed=${closed.get()}]"
}

class CredentialStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

class UnavailablePasswordCredentialStore(
    private val unavailableStatus: CredentialStoreStatus = CredentialStoreStatus.UNAVAILABLE,
) : PasswordCredentialStore {
    init {
        require(unavailableStatus != CredentialStoreStatus.AVAILABLE)
    }

    override fun status(): CredentialStoreStatus = unavailableStatus

    override fun retrieve(profileId: UUID): StoredPassword? = null

    override fun save(profileId: UUID, password: CharArray) {
        throw CredentialStoreException("Operating-system credential store is unavailable")
    }

    override fun forget(profileId: UUID) = Unit
}
