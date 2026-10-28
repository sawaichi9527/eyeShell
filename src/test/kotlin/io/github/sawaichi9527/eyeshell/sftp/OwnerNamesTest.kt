package io.github.sawaichi9527.eyeshell.sftp

import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class OwnerNamesTest {
    private val passwdAndGroup = """
        root:x:0:0:root:/root:/bin/bash
        eye:x:1000:1000:Eye:/home/eye:/bin/bash
        ${OwnerNames.GROUPS_MARKER}
        root:x:0:
        eye:x:1000:
        sudo:x:27:eye
    """.trimIndent()

    private fun file(owner: String?, group: String?) =
        RemoteFile("notes.txt", false, 42, "-rw-r--r--", owner, group)

    @Test
    fun `parses passwd and group sections into id maps`() {
        val names = OwnerNamesParser.parse(passwdAndGroup)

        assertEquals("eye", names.users[1000])
        assertEquals("root", names.users[0])
        assertEquals("sudo", names.groups[27])
        assertEquals(3, names.groups.size)
    }

    @Test
    fun `ignores malformed and blank lines`() {
        val names = OwnerNamesParser.parse("garbage\n\nbroken:x\nok:x:12:\n" + OwnerNames.GROUPS_MARKER)

        assertEquals(mapOf(12 to "ok"), names.users)
        assertEquals(emptyMap<Int, String>(), names.groups)
    }

    @Test
    fun `numeric ids become names while real names are kept`() {
        val resolver = OwnerNameResolver { passwdAndGroup }

        val decorated = resolver.decorate(
            listOf(
                file("1000", "1000"),
                file("eye", "sudo"),
                file("4242", "4242"),
                file(null, null),
            ),
        )

        assertEquals("eye / eye", "${decorated[0].owner} / ${decorated[0].group}")
        assertEquals("eye / sudo", "${decorated[1].owner} / ${decorated[1].group}")
        assertEquals("4242 / 4242", "${decorated[2].owner} / ${decorated[2].group}")
        assertEquals(null, decorated[3].owner)
    }

    @Test
    fun `the account database is fetched once per resolver`() {
        val fetches = AtomicInteger()
        val resolver = OwnerNameResolver {
            fetches.incrementAndGet()
            passwdAndGroup
        }

        resolver.decorate(listOf(file("1000", "1000")))
        resolver.decorate(listOf(file("1000", "27")))

        assertEquals(1, fetches.get())
    }

    @Test
    fun `a listing with names only does not query the server`() {
        val fetches = AtomicInteger()
        val resolver = OwnerNameResolver {
            fetches.incrementAndGet()
            passwdAndGroup
        }

        val files = listOf(file("eye", "sudo"))
        assertSame(files, resolver.decorate(files))
        assertEquals(0, fetches.get())
    }

    @Test
    fun `a failing fetch keeps numeric ids instead of breaking the listing`() {
        val resolver = OwnerNameResolver { throw IllegalStateException("getent is not installed") }

        val decorated = resolver.decorate(listOf(file("1000", "27")))

        assertEquals("1000", decorated.single().owner)
        assertEquals("27", decorated.single().group)
    }
}
