package io.github.sawaichi9527.eyeshell.ssh

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Opt-in live check against a real OpenSSH server: a host-key review that takes longer than the
 * handshake windows must not fail the connection.
 *
 * Enable with EYESHELL_TEST_SLOW_KEY_HOST, EYESHELL_TEST_SLOW_KEY_USER and
 * EYESHELL_TEST_SLOW_KEY_PASSWORD (plus optional EYESHELL_TEST_SLOW_KEY_PORT).
 */
class LiveSlowHostKeyReviewTest {
    @Test
    fun `a twenty second host key review still connects`() {
        val host = System.getenv("EYESHELL_TEST_SLOW_KEY_HOST")
        assumeTrue(host != null, "Set EYESHELL_TEST_SLOW_KEY_HOST to run this check")
        val port = System.getenv("EYESHELL_TEST_SLOW_KEY_PORT")?.toIntOrNull() ?: 22
        val user = checkNotNull(System.getenv("EYESHELL_TEST_SLOW_KEY_USER"))
        val password = checkNotNull(System.getenv("EYESHELL_TEST_SLOW_KEY_PASSWORD")).toCharArray()
        val knownHosts = Files.createTempDirectory("eyeshell-slow-key").resolve("known_hosts")
        val reviewMillis = 20_000L

        val startedAt = System.nanoTime()
        val connection = try {
            SshAuthentication.Password(password).use { authentication ->
                MinaSshConnection.connect(
                    endpoint = SshEndpoint(host, port, user),
                    authentication = authentication,
                    knownHostsStore = KnownHostsStore(knownHosts),
                    unknownHostVerifier = HostKeyVerifier {
                        Thread.sleep(reviewMillis)
                        true
                    },
                )
            }
        } finally {
            password.fill('\u0000')
        }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        try {
            val terminal = connection.openTerminal(columns = 80, rows = 24)
            try {
                assertTrue(terminal.isOpen, "the live terminal must be open after a slow review")
            } finally {
                terminal.close()
            }
        } finally {
            connection.close()
        }
        assertTrue(
            elapsedMillis >= reviewMillis,
            "the review really did take $elapsedMillis ms, expected at least $reviewMillis ms",
        )
        println("SLOW_KEY_RESULT=success elapsedMs=$elapsedMillis host=$host")
    }

}
