package io.github.sawaichi9527.eyeshell.platform

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
 * application error: every other throwable still reaches the previous handler.
 */
object ShutdownNoiseFilter {
    private val logger = Logger.getLogger(ShutdownNoiseFilter::class.java.name)

    private const val MINA_MESSAGE = "Executor has been shut down"
    private const val MINA_PACKAGE = "org.apache.sshd."

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
