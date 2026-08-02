package io.github.sawaichi9527.eyeshell.secrets

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PasswordCredentialStoreTest {
    @Test
    fun `stored password copies caller data and clears on close`() {
        val caller = charArrayOf('s', 'a', 'f', 'e')
        val stored = StoredPassword(caller)
        caller.fill('x')

        val copy = stored.copyValue()
        assertArrayEquals(charArrayOf('s', 'a', 'f', 'e'), copy)
        copy.fill('\u0000')
        stored.close()

        assertThrows(IllegalStateException::class.java, stored::copyValue)
        assertFalse(stored.toString().contains("safe"))
    }

    @Test
    fun `unavailable store never persists a password`() {
        val store = UnavailablePasswordCredentialStore()
        val password = charArrayOf('n', 'o', 'p', 'e')

        assertThrows(CredentialStoreException::class.java) {
            store.save(UUID.randomUUID(), password)
        }
        assertArrayEquals(charArrayOf('n', 'o', 'p', 'e'), password)
    }
}
