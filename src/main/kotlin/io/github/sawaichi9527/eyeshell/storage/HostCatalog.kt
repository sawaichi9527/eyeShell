package io.github.sawaichi9527.eyeshell.storage

import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint

enum class SavedAuthenticationMethod {
    PASSWORD,
    PUBLIC_KEY,
    KEYBOARD_INTERACTIVE,
    SSH_AGENT,
}

data class HostDraft(
    val name: String,
    val endpoint: SshEndpoint,
    val authenticationMethod: SavedAuthenticationMethod,
    val group: String? = null,
    val tags: List<String> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "Host profile name must not be blank" }
        require(group == null || group.isNotBlank()) { "Host group must not be blank" }
        require(tags.none(String::isBlank)) { "Host tags must not be blank" }
    }

    internal fun normalized(): HostDraft = copy(
        name = name.trim(),
        endpoint = endpoint.copy(host = endpoint.host.trim(), username = endpoint.username.trim()),
        group = group?.trim()?.takeIf(String::isNotEmpty),
        tags = tags.map(String::trim).distinct().sorted(),
    )
}

data class SavedHost(
    val id: Long,
    val draft: HostDraft,
)

interface HostCatalog : AutoCloseable {
    fun listHosts(): List<SavedHost>

    fun createHost(host: HostDraft): SavedHost

    fun updateHost(id: Long, host: HostDraft): SavedHost

    fun deleteHost(id: Long)
}
