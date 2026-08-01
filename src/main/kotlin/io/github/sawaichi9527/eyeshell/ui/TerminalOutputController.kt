package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.terminal.TerminalContextActions
import io.github.sawaichi9527.eyeshell.terminal.TerminalOutputSnapshot
import io.github.sawaichi9527.eyeshell.terminal.TerminalView
import java.awt.Component
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.io.InterruptedIOException
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

internal class TerminalOutputController(
    private val terminalView: TerminalView,
) : AutoCloseable {
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val active = AtomicBoolean()
    private val closed = AtomicBoolean()
    private var activeTask: Future<*>? = null
    private var outputTask: OutputTask? = null

    fun install(
        owner: Component,
        addHighlightRule: (() -> Unit)? = null,
        manageHighlightRules: (() -> Unit)? = null,
    ) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal output actions must be installed on the Swing EDT" }
        terminalView.setContextActions(TerminalContextActions(
            copyAllOutput = { copyAll(owner) },
            saveAllOutput = { saveAll(owner) },
            addHighlightRule = addHighlightRule,
            manageHighlightRules = manageHighlightRules,
        ))
    }

    private fun copyAll(owner: Component) {
        check(SwingUtilities.isEventDispatchThread()) { "Copy All must start on the Swing EDT" }
        runInBackground(owner) { snapshot, _ ->
            try {
                val text = collectAllOutput(snapshot, MAX_CLIPBOARD_CHARACTERS)
                val completion: () -> Unit = {
                    try {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                    } catch (failure: Exception) {
                        showError(owner, "Could not copy terminal output", failure)
                    }
                }
                completion
            } catch (_: OutputTooLargeException) {
                {
                    if (JOptionPane.showConfirmDialog(
                            owner,
                            "The retained terminal output exceeds the clipboard size limit. Save it to a file instead?",
                            "Terminal output is too large",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE,
                        ) == JOptionPane.YES_OPTION
                    ) {
                        saveAll(owner)
                    }
                }
            }
        }
    }

    private fun saveAll(owner: Component) {
        check(SwingUtilities.isEventDispatchThread()) { "Save All must start on the Swing EDT" }
        val target = chooseSaveTarget(owner) ?: return
        runInBackground(owner) { snapshot, task ->
            writeAllOutputAtomically(snapshot, target) { temporary, destination ->
                publish(task, temporary, destination)
            }
            val completion: () -> Unit = {}
            completion
        }
    }

    private fun runInBackground(owner: Component, operation: (TerminalOutputSnapshot, OutputTask) -> (() -> Unit)) {
        if (closed.get() || !active.compareAndSet(false, true)) return
        val task = OutputTask()
        outputTask = task
        activeTask = executor.submit {
            val completion = try {
                if (!task.captureState.compareAndSet(CAPTURE_QUEUED, CAPTURE_RUNNING)) return@submit
                val snapshot = try {
                    terminalView.captureAllOutput()
                } finally {
                    task.captureState.set(CAPTURE_FINISHED)
                    task.captureFinished.complete(null)
                }
                operation(snapshot, task)
            } catch (failure: Exception) {
                { showError(owner, "Could not export terminal output", failure) }
            } finally {
                if (task.captureState.compareAndSet(CAPTURE_QUEUED, CAPTURE_CANCELLED)) {
                    task.captureFinished.complete(null)
                }
                active.set(false)
            }
            SwingUtilities.invokeLater {
                if (!closed.get() && owner.isDisplayable) completion()
            }
        }
    }

    private fun chooseSaveTarget(owner: Component): Path? {
        val chooser = JFileChooser().apply {
            dialogTitle = "Save all terminal output"
            selectedFile = java.io.File("eyeshell-output.txt")
            fileFilter = FileNameExtensionFilter("Text files (*.txt)", "txt")
        }
        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return null
        val selected = chooser.selectedFile.toPath().toAbsolutePath().normalize()
        val target = if (selected.fileName.toString().contains('.')) {
            selected
        } else {
            selected.resolveSibling("${selected.fileName}.txt")
        }
        if (Files.exists(target) && JOptionPane.showConfirmDialog(
                owner,
                "Replace the existing file?\n$target",
                "Confirm overwrite",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
            ) != JOptionPane.YES_OPTION
        ) {
            return null
        }
        return target
    }

    private fun showError(owner: Component, title: String, failure: Exception) {
        JOptionPane.showMessageDialog(
            owner,
            failure.message ?: failure.javaClass.simpleName,
            title,
            JOptionPane.ERROR_MESSAGE,
        )
    }

    fun close(whenTerminalSafe: () -> Unit) {
        if (!closed.compareAndSet(false, true)) return
        val task = outputTask
        task?.publicationState?.compareAndSet(PUBLICATION_PENDING, PUBLICATION_CANCELLED)
        if (task?.captureState?.compareAndSet(CAPTURE_QUEUED, CAPTURE_CANCELLED) == true) {
            task.captureFinished.complete(null)
        }
        activeTask?.cancel(true)
        executor.shutdownNow()
        if (task == null || task.captureFinished.isDone) {
            whenTerminalSafe()
        } else {
            Thread.ofVirtual().name("terminal-output-close").start {
                task.captureFinished.join()
                SwingUtilities.invokeLater(whenTerminalSafe)
            }
        }
    }

    override fun close() = close {}

    private fun publish(task: OutputTask, temporary: Path, target: Path) {
        if (!task.publicationState.compareAndSet(PUBLICATION_PENDING, PUBLICATION_RUNNING)) {
            throw InterruptedIOException("Terminal output export was cancelled")
        }
        try {
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            task.publicationState.set(PUBLICATION_FINISHED)
        }
    }

    companion object {
        internal const val MAX_CLIPBOARD_CHARACTERS: Int = 8 * 1024 * 1024
        private const val CAPTURE_QUEUED = 0
        private const val CAPTURE_RUNNING = 1
        private const val CAPTURE_FINISHED = 2
        private const val CAPTURE_CANCELLED = 3
        private const val PUBLICATION_PENDING = 0
        private const val PUBLICATION_RUNNING = 1
        private const val PUBLICATION_FINISHED = 2
        private const val PUBLICATION_CANCELLED = 3
    }

    private class OutputTask {
        val captureState = AtomicInteger(CAPTURE_QUEUED)
        val captureFinished = CompletableFuture<Void>()
        val publicationState = AtomicInteger(PUBLICATION_PENDING)
    }
}

internal fun collectAllOutput(snapshot: TerminalOutputSnapshot, maxCharacters: Int): String {
    val writer = LimitedStringWriter(maxCharacters)
    snapshot.writeTo(InterruptibleWriter(writer))
    return writer.toString()
}

internal fun writeAllOutputAtomically(snapshot: TerminalOutputSnapshot, target: Path) {
    writeAllOutputAtomically(snapshot, target) { temporary, absoluteTarget ->
        Files.move(temporary, absoluteTarget, ATOMIC_MOVE, REPLACE_EXISTING)
    }
}

private fun writeAllOutputAtomically(
    snapshot: TerminalOutputSnapshot,
    target: Path,
    publish: (temporary: Path, target: Path) -> Unit,
) {
    val absoluteTarget = target.toAbsolutePath().normalize()
    val parent = absoluteTarget.parent ?: throw IOException("Output file has no parent directory")
    val temporary = Files.createTempFile(parent, ".${absoluteTarget.fileName}.", ".tmp")
    try {
        Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use { writer ->
            snapshot.writeTo(InterruptibleWriter(writer))
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Terminal output export was cancelled")
        publish(temporary, absoluteTarget)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private class LimitedStringWriter(
    private val maxCharacters: Int,
) : Writer() {
    private val content = StringBuilder(minOf(maxCharacters, 8192))

    init {
        require(maxCharacters >= 0) { "Character limit must not be negative" }
    }

    override fun write(character: Int) {
        ensureCapacity(1)
        content.append(character.toChar())
    }

    override fun write(characters: CharArray, offset: Int, length: Int) {
        ensureCapacity(length)
        content.append(characters, offset, length)
    }

    override fun write(text: String, offset: Int, length: Int) {
        ensureCapacity(length)
        content.append(text, offset, offset + length)
    }

    override fun flush() = Unit

    override fun close() = Unit

    override fun toString(): String = content.toString()

    private fun ensureCapacity(additional: Int) {
        if (additional < 0 || additional > maxCharacters - content.length) throw OutputTooLargeException()
    }
}

private class InterruptibleWriter(
    private val delegate: Writer,
) : Writer() {
    override fun write(character: Int) {
        checkInterrupted()
        delegate.write(character)
    }

    override fun write(characters: CharArray, offset: Int, length: Int) {
        checkInterrupted()
        delegate.write(characters, offset, length)
    }

    override fun write(text: String, offset: Int, length: Int) {
        checkInterrupted()
        delegate.write(text, offset, length)
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Terminal output export was cancelled")
    }
}

private class OutputTooLargeException : IOException("Terminal output exceeds the clipboard limit")
