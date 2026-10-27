package io.github.sawaichi9527.eyeshell.monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonitorParsersTest {
    @Test
    fun `parses system info from uname, uptime, and os-release`() {
        val output = """
            Linux lab-server 6.8.0-45-generic #45-Ubuntu SMP PREEMPT_DYNAMIC x86_64 GNU/Linux

            12345.67 23456.78

            PRETTY_NAME="Ubuntu 26.04.1 LTS"
            NAME="Ubuntu"
            VERSION="26.04.1 LTS (Resolute Raccoon)"
            ID=ubuntu
        """.trimIndent()

        val info = MonitorParsers.parseSystemInfo(output)

        assertNotNull(info)
        assertEquals("lab-server", info!!.hostname)
        assertEquals("6.8.0-45-generic", info.kernel)
        assertEquals("Linux", info.operatingSystem)
        assertEquals("Ubuntu 26.04.1 LTS", info.distribution)
        assertEquals(12346L, info.uptimeSeconds)
    }

    @Test
    fun `system info falls back when os-release is unavailable`() {
        val output = """
            Linux lab-server 6.8.0-45-generic #45-Ubuntu SMP x86_64 GNU/Linux

            12345.67 23456.78
        """.trimIndent()

        val info = MonitorParsers.parseSystemInfo(output)

        assertNotNull(info)
        assertEquals("", info!!.distribution)
        assertEquals("Linux", info.operatingSystem)
    }

    @Test
    fun `parses cpu delta between consecutive samples`() {
        val first = """
            cpu  100 0 100 800 50 0 0 0 0 0
            cpu0 50 0 50 400 25 0 0 0 0 0
            cpu1 50 0 50 400 25 0 0 0 0 0
        """.trimIndent()
        val second = """
            cpu  200 0 300 1000 100 0 0 0 0 0
            cpu0 100 0 150 500 50 0 0 0 0 0
            cpu1 100 0 150 500 50 0 0 0 0 0
        """.trimIndent()

        val current = MonitorParsers.cpuRaw(second)
        val previous = MonitorParsers.cpuRaw(first)
        val usage = MonitorParsers.parseCpu(second, requireNotNull(current), previous)

        assertNotNull(usage)
        assertEquals(2, usage!!.cores)
        assertTrue(usage.percent in 40.0..60.0, "Expected ~50% but was ${usage.percent}")
    }

    @Test
    fun `first cpu sample reports zero percent`() {
        val output = "cpu  100 0 100 800 50 0 0 0 0 0\n"

        val usage = MonitorParsers.parseCpu(output, requireNotNull(MonitorParsers.cpuRaw(output)), null)

        assertNotNull(usage)
        assertEquals(0.0, usage!!.percent)
        assertEquals(1, usage.cores)
    }

    @Test
    fun `parses memory from meminfo`() {
        val output = """
            MemTotal:       16000000 kB
            MemFree:         4000000 kB
            MemAvailable:    6000000 kB
            Buffers:          500000 kB
        """.trimIndent()

        val memory = MonitorParsers.parseMemory(output)

        assertNotNull(memory)
        assertEquals(16_000_000L * 1024L, memory!!.totalBytes)
        assertEquals(10_000_000L * 1024L, memory.usedBytes)
        assertEquals(62.5, memory.percent)
    }

    @Test
    fun `parses load average`() {
        val load = MonitorParsers.parseLoad("0.52 0.33 0.20 2/345 12345")

        assertNotNull(load)
        assertEquals(0.52, load!!.oneMinute)
        assertEquals(0.33, load.fiveMinute)
        assertEquals(0.20, load.fifteenMinute)
    }

    @Test
    fun `parses network rates between consecutive samples`() {
        val first = """
            Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
                lo:  1000       10    0    0    0     0          0         0   1000       10    0    0    0     0       0          0
              eth0:  100000    100    0    0    0     0          0         0   50000     50    0    0    0     0       0          0
        """.trimIndent()
        val second = """
            Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
                lo:  1000       10    0    0    0     0          0         0   1000       10    0    0    0     0       0          0
              eth0:  104000    100    0    0    0     0          0         0   53000     50    0    0    0     0       0          0
        """.trimIndent()

        val previous = MonitorParsers.NetworkRaw(requireNotNull(rawNetwork(first)))
        val usage = MonitorParsers.parseNetwork(second, previous)

        assertNotNull(usage)
        assertEquals(4000L, usage!!.receiveBytesPerSecond)
        assertEquals(3000L, usage.transmitBytesPerSecond)
        assertEquals(2, usage.interfaces.size)
        assertEquals("eth0", usage.interfaces.first { it.name == "eth0" }.name)
    }

    @Test
    fun `parses interface addresses from ip output`() {
        val output = """
            1: lo    inet 127.0.0.1/8 scope host lo\       valid_lft forever preferred_lft forever
            2: enp975    inet 192.168.35.207/24 brd 192.168.35.255 scope global dynamic noprefixroute enp975\       valid_lft 5925sec preferred_lft 5925sec
            3: docker0    inet 172.17.0.1/16 brd 172.17.255.255 scope global docker0\       valid_lft forever preferred_lft forever
            sh: 1: ip: not found
        """.trimIndent()

        val addresses = MonitorParsers.parseInterfaceAddresses(output)

        assertEquals(listOf("127.0.0.1"), addresses["lo"])
        assertEquals(listOf("192.168.35.207"), addresses["enp975"])
        assertEquals(listOf("172.17.0.1"), addresses["docker0"])
        assertEquals(3, addresses.size)
    }

    @Test
    fun `missing interface address output yields an empty map`() {
        assertTrue(MonitorParsers.parseInterfaceAddresses("").isEmpty())
        assertTrue(MonitorParsers.parseInterfaceAddresses("sh: 1: ip: not found").isEmpty())
    }

    @Test
    fun `parses filesystems from df output`() {
        val output = """
            Filesystem     1024-blocks      Used Available Capacity Mounted on
            /dev/sda1        100000000  40000000  60000000      40% /
            /dev/sdb1       1000000000  800000000 200000000      80% /data
        """.trimIndent()

        val filesystems = MonitorParsers.parseFilesystems(output)

        assertEquals(2, filesystems.size)
        assertEquals("/", filesystems[0].mountPoint)
        assertEquals(100_000_000L * 1024L, filesystems[0].totalBytes)
        assertEquals(40.0, filesystems[0].percent)
        assertEquals("/data", filesystems[1].mountPoint)
    }

    @Test
    fun `parses process list sorted by rss`() {
        val output = """
            1234 204800  12.5 some-daemon
            5678  51200   3.2 sshd: user@pts/0
            9012  10240   0.5 bash
        """.trimIndent()

        val processes = MonitorParsers.parseProcesses(output)

        assertEquals(3, processes.size)
        assertEquals(1234L, processes[0].pid)
        assertEquals("some-daemon", processes[0].name)
        assertEquals(204800L * 1024L, processes[0].memoryBytes)
        assertEquals(12.5, processes[0].cpuPercent)
        assertEquals("sshd: user@pts/0", processes[1].name)
    }

    @Test
    fun `parses per-process cpu time from proc stat dumps`() {
        val output = """
            # 1234 1234 (sshd: user@pts/0) S 1 1234 1234 0 -1 4194304 300 0 0 0 500 250 0 0 20 0 1 0 100 1000 100
            # 5678 5678 (some process) R 1 5678 5678 0 -1 4194304 10 0 0 0 30 20 0 0 20 0 1 0 5 50 4
            not a stat line
        """.trimIndent()

        val times = MonitorParsers.parseProcessCpuTimes(output)

        assertEquals(2, times.size)
        assertEquals(7.5, times[1234L]!!, 0.001)
        assertEquals(0.5, times[5678L]!!, 0.001)
    }

    @Test
    fun `a malformed proc stat dump yields no cpu times`() {
        assertTrue(MonitorParsers.parseProcessCpuTimes("").isEmpty())
        assertTrue(MonitorParsers.parseProcessCpuTimes("# 1234 broken").isEmpty())
        assertTrue(MonitorParsers.parseProcessCpuTimes("1234 (pid) S 1").isEmpty())
    }

    @Test
    fun `parses swap from meminfo`() {
        val output = """
            SwapTotal:      2000000 kB
            SwapFree:        500000 kB
        """.trimIndent()

        val swap = MonitorParsers.parseSwap(output)

        assertNotNull(swap)
        assertEquals(2_000_000L * 1024L, swap!!.totalBytes)
        assertEquals(1_500_000L * 1024L, swap.usedBytes)
        assertEquals(75.0, swap.percent)
    }

    @Test
    fun `malformed output returns null or empty instead of throwing`() {
        assertNull(MonitorParsers.parseSystemInfo(""))
        assertNull(MonitorParsers.cpuRaw("no cpu line"))
        assertNull(MonitorParsers.parseMemory("garbage"))
        assertNull(MonitorParsers.parseLoad(""))
        assertTrue(MonitorParsers.parseFilesystems("no /dev lines").isEmpty())
        assertTrue(MonitorParsers.parseProcesses("").isEmpty())
    }

    @Test
    fun `splits combined sample into sections by form feed`() {
        val combined = "section-a\u000Csection-b\u000Csection-c"

        assertEquals(listOf("section-a", "section-b", "section-c"), MonitorParsers.splitSample(combined))
    }

    private fun rawNetwork(output: String): List<MonitorParsers.NetworkInterfaceRaw>? = output.lines()
        .map(String::trim)
        .filter { it.contains(":") }
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            val name = line.substring(0, separator).trim()
            val fields = line.substring(separator + 1).trim().split(Regex("\\s+")).mapNotNull(String::toLongOrNull)
            if (fields.size < 9) null else MonitorParsers.NetworkInterfaceRaw(name, fields[0], fields[8])
        }
        .takeIf { it.isNotEmpty() }
}
