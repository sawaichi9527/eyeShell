package io.github.sawaichi9527.eyeshell.monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonitorCommandsTest {
    @Test
    fun `sample emits shell delimiters instead of raw form feeds`() {
        val command = MonitorCommands.sample()

        assertFalse(command.contains(MonitorCommands.SECTION_DELIMITER), "A raw form feed never survives the remote shell")
        assertEquals(5, command.split(MonitorCommands.DELIMITER_COMMAND).size - 1)
    }

    @Test
    fun `sample keeps the locale for every remote command`() {
        val command = MonitorCommands.sample()

        assertTrue(command.startsWith(MonitorCommands.ENV_PREFIX))
        listOf("/proc/stat", "/proc/meminfo", "/proc/loadavg", "/proc/net/dev", "df -P", "ps -eo").forEach { section ->
            assertTrue(command.contains(section), "Missing sample section $section")
        }
    }

    @Test
    fun `system info applies the locale`() {
        val command = MonitorCommands.systemInfo()

        assertTrue(command.startsWith(MonitorCommands.ENV_PREFIX))
        assertTrue(command.contains("uname -a"))
        assertTrue(command.contains("/proc/uptime"))
    }
}
