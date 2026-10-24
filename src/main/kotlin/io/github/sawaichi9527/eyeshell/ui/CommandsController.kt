package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.storage.CommandSnippet
import io.github.sawaichi9527.eyeshell.storage.CommandSnippetCatalog
import io.github.sawaichi9527.eyeshell.storage.CommandSnippetDraft
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

class CommandsController(
    private val catalog: CommandSnippetCatalog,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("commands-", 0).factory())
    private val closed = AtomicBoolean()

    fun load(onResult: (Result<List<CommandSnippet>>) -> Unit) {
        submit(catalog::listCommandSnippets, onResult)
    }

    fun create(draft: CommandSnippetDraft, onResult: (Result<CommandSnippet>) -> Unit) {
        submit({ catalog.createCommandSnippet(draft) }, onResult)
    }

    fun update(id: Long, draft: CommandSnippetDraft, onResult: (Result<CommandSnippet>) -> Unit) {
        submit({ catalog.updateCommandSnippet(id, draft) }, onResult)
    }

    fun delete(id: Long, onResult: (Result<Unit>) -> Unit) {
        submit({ catalog.deleteCommandSnippet(id) }, onResult)
    }

    private fun <T> submit(operation: () -> T, onResult: (Result<T>) -> Unit) {
        if (closed.get()) return
        try {
            executor.execute {
                val result = runCatching(operation)
                SwingUtilities.invokeLater {
                    if (!closed.get()) onResult(result)
                }
            }
        } catch (_: RejectedExecutionException) {
            return
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow()
    }
}
