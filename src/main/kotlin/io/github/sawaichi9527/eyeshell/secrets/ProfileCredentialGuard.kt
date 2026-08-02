package io.github.sawaichi9527.eyeshell.secrets

import java.util.UUID

internal class ProfileCredentialGuard {
    private val lock = Any()
    private val states = mutableMapOf<UUID, ProfileCredentialState>()

    fun <T> snapshot(profileId: UUID, load: (Boolean) -> T): ProfileCredentialSnapshot<T> = synchronized(lock) {
        val state = states[profileId] ?: ProfileCredentialState()
        ProfileCredentialSnapshot(state.revision, state.active, load(state.active))
    }

    fun <T> mutate(profileId: UUID, mutation: () -> T): T = synchronized(lock) {
        mutation().also { update(profileId) { state -> state.copy(revision = state.revision + 1) } }
    }

    fun <T> invalidate(profileId: UUID, mutation: () -> T): T = synchronized(lock) {
        mutation().also {
            update(profileId) { state -> state.copy(revision = state.revision + 1, active = false) }
        }
    }

    fun <T> activate(profileId: UUID, mutation: () -> T): T = synchronized(lock) {
        mutation().also {
            update(profileId) { state -> state.copy(revision = state.revision + 1, active = true) }
        }
    }

    fun saveIfCurrent(profileId: UUID, revision: Long, save: () -> Unit): Boolean = synchronized(lock) {
        val state = states[profileId] ?: ProfileCredentialState()
        if (!state.active || state.revision != revision) return@synchronized false
        save()
        true
    }

    private fun update(profileId: UUID, change: (ProfileCredentialState) -> ProfileCredentialState) {
        states[profileId] = change(states[profileId] ?: ProfileCredentialState())
    }
}

internal data class ProfileCredentialSnapshot<T>(
    val revision: Long,
    val active: Boolean,
    val value: T,
)

private data class ProfileCredentialState(
    val revision: Long = 0,
    val active: Boolean = true,
)
