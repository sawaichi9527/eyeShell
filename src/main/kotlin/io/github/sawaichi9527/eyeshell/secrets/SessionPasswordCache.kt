package io.github.sawaichi9527.eyeshell.secrets

import java.util.Arrays
import java.util.UUID

/**
 * Holds passwords typed in the UI when they cannot go to the OS credential store.
 *
 * Values live only inside this process, are keyed by Saved Host profile, and are zeroed by
 * [invalidate] or [close]. Nothing here is persisted or logged.
 */
internal class SessionPasswordCache : AutoCloseable {
    private val lock = Any()
    private val passwords = mutableMapOf<UUID, CharArray>()
    private var closed = false

    fun put(profileId: UUID, password: CharArray) {
        require(password.isNotEmpty()) { "Session password must not be empty" }
        synchronized(lock) {
            check(!closed) { "Session password cache is closed" }
            passwords.remove(profileId)?.let { Arrays.fill(it, '\u0000') }
            passwords[profileId] = password.copyOf()
        }
    }

    fun copyOf(profileId: UUID): CharArray? = synchronized(lock) {
        passwords[profileId]?.copyOf()
    }

    fun contains(profileId: UUID): Boolean = synchronized(lock) {
        passwords.containsKey(profileId)
    }

    fun invalidate(profileId: UUID) {
        synchronized(lock) {
            passwords.remove(profileId)?.let { Arrays.fill(it, '\u0000') }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            passwords.values.forEach { Arrays.fill(it, '\u0000') }
            passwords.clear()
        }
    }

    override fun toString(): String = "SessionPasswordCache[size=${synchronized(lock) { passwords.size }}]"
}
