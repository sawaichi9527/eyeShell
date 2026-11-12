package io.github.sawaichi9527.eyeshell.platform

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Apache MINA's NIO2 transport can dispatch a socket-read completion after its executor has already
 * been shut down. On Windows the JDK runs that completion on an I/O thread (`Iocp`), so the failure
 * surfaces as an *uncaught* exception and the JVM prints
 * `Exception in thread "Thread-0" java.lang.IllegalStateException: Executor has been shut down`
 * with a full stack trace, long after eyeShell has closed the session and while the process is
 * exiting.
 *
 * The race happens inside `org.apache.sshd` + the JDK asynchronous channel group, after the
 * application has released every session, so it cannot be prevented by ordering the close calls:
 * the 2026-10-28 attempt to await the session close future before closing the client still produced
 * the trace. Suppressing exactly this signature keeps the console clean without hiding any
 * application error.
 *
 * Every other throwable is written to the diagnostic log before it reaches the previous handler.
 * A packaged build has no console, so without this an exception on the Swing EDT (for example a
 * failing paint, which leaves the terminal frozen while the tab still says "Connected") would never
 * be recorded anywhere.
 */
object ShutdownNoiseFilter {
    private val logger = Logger.getLogger(ShutdownNoiseFilter::class.java.name)

    private const val MINA_MESSAGE = "Executor has been shut down"
    private const val MINA_PACKAGE = "org.apache.sshd."

    /** A paint loop can throw hundreds of times per second; the log still needs to stay readable. */
    private const val LOG_INTERVAL_MILLIS = 1_000L

    private val lastLoggedAt = AtomicLong(0)
    private val suppressed = AtomicInteger(0)

    /**
     * Installs the filter and returns the handler it replaced (null when the JVM default was in
     * place). Call once, early in `main`.
     */
    fun install(): Thread.UncaughtExceptionHandler? {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            dispatch(previous, thread, error)
        }
        return previous
    }

    /**
     * Returns true when [error] is the MINA executor-shutdown race and was therefore swallowed.
     * Public so the behaviour can be asserted without touching the global handler.
     */
    fun dispatch(
        previous: Thread.UncaughtExceptionHandler?,
        thread: Thread,
        error: Throwable,
    ): Boolean {
        if (isMinaExecutorShutdownRace(error)) {
            logger.log(Level.FINE, "Ignored a MINA executor shutdown race on ${thread.name}", error)
            return true
        }
        logUncaught(thread, error)
        previous?.uncaughtException(thread, error) ?: printAsDefault(thread, error)
        return false
    }

    /**
     * True only for an `IllegalStateException` with MINA's exact message whose stack trace actually
     * comes from `org.apache.sshd`, so an identical message raised by eyeShell code is still
     * reported.
     */
    fun isMinaExecutorShutdownRace(error: Throwable): Boolean {
        if (error !is IllegalStateException) return false
        if (error.message != MINA_MESSAGE) return false
        return error.stackTrace.any { it.className.startsWith(MINA_PACKAGE) }
    }

    /**
     * Writes the first failure immediately and then at most one per second, reporting how many
     * identical bursts were skipped so a repeating paint error cannot fill the log.
     */
    private fun logUncaught(thread: Thread, error: Throwable) {
        val now = System.currentTimeMillis()
        val previous = lastLoggedAt.get()
        if (now - previous < LOG_INTERVAL_MILLIS || !lastLoggedAt.compareAndSet(previous, now)) {
            suppressed.incrementAndGet()
            return
        }
        val skipped = suppressed.getAndSet(0)
        val message = buildString {
            append("Uncaught exception in thread \"")
            append(thread.name)
            append('"')
            if (skipped > 0) {
                append(" (").append(skipped).append(" similar lines suppressed)")
            }
        }
        logger.log(Level.SEVERE, message, error)
    }

    /** Test hook: makes the next failure log immediately. */
    internal fun resetLogThrottleForTests() {
        lastLoggedAt.set(0)
        suppressed.set(0)
    }

    private fun printAsDefault(thread: Thread, error: Throwable) {
        val group = thread.threadGroup
        if (group != null) {
            // ThreadGroup.uncaughtException falls back to the standard "Exception in thread ..."
            // output when no parent handler is installed, which is the JVM's own behaviour.
            group.uncaughtException(thread, error)
        } else {
            System.err.print("Exception in thread \"${thread.name}\" ")
            error.printStackTrace(System.err)
        }
    }
}
