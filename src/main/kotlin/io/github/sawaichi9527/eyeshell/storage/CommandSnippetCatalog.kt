package io.github.sawaichi9527.eyeshell.storage

data class CommandSnippet(
    val id: Long,
    val name: String,
    val command: String,
)

data class CommandSnippetDraft(
    val name: String,
    val command: String,
) {
    init {
        require(name.isNotBlank()) { "Command snippet name must not be blank" }
        require(command.isNotBlank()) { "Command snippet command must not be blank" }
    }

    internal fun normalized(): CommandSnippetDraft = copy(name = name.trim(), command = command.trim())
}

interface CommandSnippetCatalog {
    fun listCommandSnippets(): List<CommandSnippet>

    fun createCommandSnippet(draft: CommandSnippetDraft): CommandSnippet

    fun updateCommandSnippet(id: Long, draft: CommandSnippetDraft): CommandSnippet

    fun deleteCommandSnippet(id: Long)
}
