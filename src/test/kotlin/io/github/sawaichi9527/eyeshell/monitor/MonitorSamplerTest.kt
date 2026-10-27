package io.github.sawaichi9527.eyeshell.monitor

import io.github.sawaichi9527.eyeshell.ssh.ExecResult
import io.github.sawaichi9527.eyeshell.ssh.HostSession
import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonitorSamplerTest {
    @Test
    fun `emits a snapshot with parsed metrics and system info once`() {
        val session = ScriptedHostSession(
            systemInfo = "Linux lab 6.8.0-45-generic #1 SMP x86_64 GNU/Linux\n1000 2000\n",
            sample = sampleOutput(),
        )
        val sampler = MonitorSampler(session, interval = Duration.ofMillis(50), backoff = Duration.ofMillis(50))
        val latch = CountDownLatch(1)
        var snapshot: MonitorSnapshot? = null

        try {
            sampler.start {
                snapshot = it
                if (it.hasData) latch.countDown()
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS), "sampler did not publish a snapshot")

            val received = requireNotNull(snapshot)
            assertEquals("lab", received.system!!.hostname)
            assertEquals(2, received.cpu!!.cores)
            assertEquals(16_000_000L * 1024L, received.memory!!.totalBytes)
            assertEquals(2_000_000L * 1024L, received.swap!!.totalBytes)
            assertEquals(0.52, received.load!!.oneMinute)
            assertEquals(2, received.filesystems.size)
            assertEquals(1, received.processes.size)
        } finally {
            sampler.close()
        }
    }

    @Test
    fun `reports unsupported when the sample command fails`() {
        val session = ScriptedHostSession(systemInfo = "Linux lab\n1000\n", sampleFails = true)
        val sampler = MonitorSampler(session, interval = Duration.ofMillis(50), backoff = Duration.ofMillis(50))
        val latch = CountDownLatch(1)
        var unsupported = false

        try {
            sampler.start {
                unsupported = it.unsupported
                latch.countDown()
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertTrue(unsupported)
        } finally {
            sampler.close()
        }
    }

    @Test
    fun `stops sampling after close`() {
        val session = ScriptedHostSession(
            systemInfo = "Linux lab 6.8.0 x86_64 GNU/Linux\n1000\n",
            sample = sampleOutput(),
        )
        val sampler = MonitorSampler(session, interval = Duration.ofMillis(20), backoff = Duration.ofMillis(20))
        val count = java.util.concurrent.atomic.AtomicInteger()

        sampler.start { count.incrementAndGet() }
        Thread.sleep(150)
        sampler.close()
        val afterClose = count.get()
        Thread.sleep(100)
        assertEquals(afterClose, count.get(), "sampler kept sampling after close")
        assertTrue(afterClose >= 1)
    }

    @Test
    fun `process cpu percent comes from the proc stat delta, not the lifetime average`() {
        val session = ScriptedHostSession(
            systemInfo = "Linux lab 6.8.0 x86_64 GNU/Linux\n1000\n",
            samples = listOf(sampleOutputWithCpuTicks(utime = 100, stime = 0), sampleOutputWithCpuTicks(utime = 150, stime = 0)),
        )
        val sampler = MonitorSampler(session, interval = Duration.ofMillis(200), backoff = Duration.ofMillis(50))
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        val snapshots = mutableListOf<MonitorSnapshot>()

        try {
            sampler.start { snapshot ->
                if (snapshot.processes.isNotEmpty()) {
                    synchronized(snapshots) { snapshots += snapshot }
                    if (snapshots.size == 1) first.countDown() else second.countDown()
                }
            }
            assertTrue(first.await(5, TimeUnit.SECONDS), "no first snapshot")
            assertTrue(second.await(5, TimeUnit.SECONDS), "no second snapshot")

            val measured = synchronized(snapshots) { snapshots.take(2) }
            assertEquals(
                12.5,
                measured[0].processes[0].cpuPercent,
                0.001,
                "The first sample has no previous CPU time, so the ps average is kept",
            )
            assertTrue(
                measured[1].processes[0].cpuPercent > 100.0,
                "0.5 s of CPU over a ~0.2 s interval must exceed one core: ${measured[1].processes[0].cpuPercent}",
            )
        } finally {
            sampler.close()
        }
    }

    private fun sampleOutput(): String = """
        cpu  100 0 100 800 50 0 0 0 0 0
        cpu0 50 0 50 400 25 0 0 0 0 0
        cpu1 50 0 50 400 25 0 0 0 0 0
        ${"\u000C"}MemTotal:       16000000 kB
        MemFree:         4000000 kB
        MemAvailable:    6000000 kB
        SwapTotal:       2000000 kB
        SwapFree:         500000 kB
        ${"\u000C"}0.52 0.33 0.20 2/345 12345
        ${"\u000C"}Inter-|   Receive                                                |  Transmit
         face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
            lo:  1000       10    0    0    0     0          0         0   1000       10    0    0    0     0       0          0
        ${"\u000C"}Filesystem     1024-blocks      Used Available Capacity Mounted on
        /dev/sda1        100000000  40000000  60000000      40% /
        /dev/sdb1       1000000000  800000000 200000000      80% /data
        ${"\u000C"}1234 204800  12.5 some-daemon
    """.trimIndent()

    /** Same sample as [sampleOutput] plus the per-pid CPU-time section used for instantaneous CPU. */
    private fun sampleOutputWithCpuTicks(utime: Long, stime: Long): String =
        sampleOutput() + "\n\u000C# 1234 1234 (some daemon) S 1 1234 1234 0 -1 4194304 300 0 0 0 $utime $stime 0 0 20 0 1 0 100 1000 100"

    private class ScriptedHostSession(
        private val systemInfo: String,
        private val sample: String = "",
        private val sampleFails: Boolean = false,
        private val samples: List<String> = emptyList(),
    ) : HostSession {
        private var sampleIndex = 0
        private val closed = AtomicBoolean()

        override val endpoint: SshEndpoint = SshEndpoint("127.0.0.1", 22, "test")

        override fun openTerminal(columns: Int, rows: Int): TerminalSession = throw UnsupportedOperationException()

        override fun execute(command: String): ExecResult {
            if (sampleFails) throw IllegalStateException("sample failed")
            val output = if (command.contains("uname")) {
                systemInfo
            } else if (samples.isNotEmpty()) {
                samples[minOf(sampleIndex++, samples.size - 1)]
            } else {
                sample
            }
            return ExecResult(0, output)
        }

        override fun sftp(): io.github.sawaichi9527.eyeshell.sftp.SftpClient =
            throw UnsupportedOperationException()

        override fun isOpen(): Boolean = !closed.get()

        override fun close() { closed.set(true) }
    }
}
