package io.github.sawaichi9527.eyeshell.terminal

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RawOutputLogTest {
    @Test
    fun `retained bytes are returned in arrival order`() {
        val log = RawOutputLog(capacity = 8)
        log.append("abc".toByteArray())
        log.append("def".toByteArray())

        assertEquals(6, log.size())
        assertFalse(log.isTruncated())
        assertArrayEquals("abcdef".toByteArray(), log.snapshot())
    }

    @Test
    fun `the oldest bytes are dropped once the capacity is exceeded`() {
        val log = RawOutputLog(capacity = 8)
        log.append("abcdef".toByteArray())
        log.append("ghij".toByteArray())

        assertEquals(8, log.size())
        assertTrue(log.isTruncated())
        assertArrayEquals("cdefghij".toByteArray(), log.snapshot())
    }

    @Test
    fun `an oversized chunk keeps only its newest bytes`() {
        val log = RawOutputLog(capacity = 4)
        log.append("abcdefgh".toByteArray())

        assertEquals(4, log.size())
        assertArrayEquals("efgh".toByteArray(), log.snapshot())
    }

    @Test
    fun `snapshot can exclude the undecoded tail`() {
        val log = RawOutputLog(capacity = 16)
        log.append("helloWORLD".toByteArray())

        assertArrayEquals("hello".toByteArray(), log.snapshot(dropTail = 5))
        assertArrayEquals(ByteArray(0), log.snapshot(dropTail = 99))
    }

    @Test
    fun `clearing drops retained bytes`() {
        val log = RawOutputLog(capacity = 4)
        log.append("abcd".toByteArray())
        log.clear()

        assertEquals(0, log.size())
        assertFalse(log.isTruncated())
        assertEquals(0, log.snapshot().size)
    }

    @Test
    fun `appends wrap around the ring`() {
        val log = RawOutputLog(capacity = 5)
        log.append("ab".toByteArray())
        log.append("cd".toByteArray())
        log.append("ef".toByteArray())

        assertArrayEquals("bcdef".toByteArray(), log.snapshot())
    }
}
