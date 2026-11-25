package io.github.sawaichi9527.eyeshell.platform

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * An automatic dump is only useful if it happens at the moment of the failure and cannot flood the
 * disk: one per kind and session every five minutes, and a hard cap per run.
 */
class SessionDumpsTest {
    @BeforeEach
    fun reset() {
        SessionDumps.reset()
        SessionEvents.clear()
    }

    @AfterEach
    fun cleanUp() {
        SessionDumps.reset()
        SessionEvents.clear()
    }

    @Test
    fun `one dump per kind and session within the throttle`(@TempDir directory: Path) {
        val file = requireNotNull(
            SessionDumps.auto("input-stall", "ops@10.0.0.5:22", listOf("name=ops@10.0.0.5:22"), directory),
        ) { "the dump must be written" }

        assertTrue(Files.exists(file), "the dump must exist")
        assertTrue(
            file.fileName.toString().startsWith("thread-dump-input-stall-ops_10.0.0.5_22-"),
            file.fileName.toString(),
        )
        assertNull(
            SessionDumps.auto("input-stall", "ops@10.0.0.5:22", emptyList(), directory),
            "the same episode must not write a second dump",
        )
        assertNotNull(
            SessionDumps.auto("transport-lost", "ops@10.0.0.5:22", emptyList(), directory),
            "a different kind is a different episode",
        )
        assertTrue(SessionEvents.snapshot().any { it.contains("ev=auto-dump kind=input-stall") })
    }

    @Test
    fun `the per-run cap stops further dumps`(@TempDir directory: Path) {
        repeat(SessionDumps.MAX_PER_RUN + 2) { index ->
            SessionDumps.auto("kind", "session-$index", emptyList(), directory)
        }

        assertNull(SessionDumps.auto("kind", "one-more", emptyList(), directory))
        val files = Files.list(directory).use { stream -> stream.count() }
        assertEquals(SessionDumps.MAX_PER_RUN.toLong(), files)
    }
}
