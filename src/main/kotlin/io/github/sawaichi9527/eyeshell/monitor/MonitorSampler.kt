package io.github.sawaichi9527.eyeshell.monitor

import io.github.sawaichi9527.eyeshell.ssh.HostSession
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class MonitorSampler(
    private val hostSession: HostSession,
    private val interval: Duration = Duration.ofSeconds(2),
    private val backoff: Duration = Duration.ofSeconds(1),
    private val backoffLimit: Duration = Duration.ofSeconds(30),
    private val processLimit: Int = 10,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private var thread: Thread? = null
    private var previousCpu: MonitorParsers.CpuRaw? = null
    private var previousNetwork: MonitorParsers.NetworkRaw? = null
    private var previousProcessCpuSeconds: Map<Long, Double> = emptyMap()
    private var previousProcessSampleNanos: Long = 0L
    private var currentBackoff = backoff

    fun start(onSnapshot: (MonitorSnapshot) -> Unit) {
        if (!closed.compareAndSet(false, false)) return
        thread = Thread.ofVirtual().name("eyeShell-monitor").start {
            var systemInfo: SystemInfo? = null
            while (!closed.get()) {
                var failed = false
                val snapshot = try {
                    if (systemInfo == null) {
                        hostSession.execute(MonitorCommands.systemInfo()).let { result ->
                            systemInfo = MonitorParsers.parseSystemInfo(result.output)
                        }
                    }
                    sampleOnce(systemInfo)
                } catch (_: Throwable) {
                    failed = true
                    currentBackoff = (currentBackoff.multipliedBy(2)).coerceAtMost(backoffLimit)
                    MonitorSnapshot(endpoint = hostSession.endpoint.host, unsupported = true)
                }
                if (!closed.get()) onSnapshot(snapshot)
                sleep(waitDuration(failed))
            }
        }
    }

    private fun waitDuration(failed: Boolean): Duration =
        if (failed) currentBackoff else interval

    private fun sampleOnce(systemInfo: SystemInfo?): MonitorSnapshot {
        val result = hostSession.execute(MonitorCommands.sample())
        val sections = MonitorParsers.splitSample(result.output)

        val currentCpu = sections.getOrNull(0)?.let(MonitorParsers::cpuRaw)
        val cpu = currentCpu?.let { current ->
            sections.getOrNull(0)?.let { MonitorParsers.parseCpu(it, current, previousCpu) }
        }
        previousCpu = currentCpu ?: previousCpu

        val currentNetwork = rawNetwork(sections.getOrNull(3))
        val network = sections.getOrNull(3)?.let { MonitorParsers.parseNetwork(it, previousNetwork) }
        previousNetwork = currentNetwork ?: previousNetwork

        val memory = sections.getOrNull(1)?.let(MonitorParsers::parseMemory)
        val swap = sections.getOrNull(1)?.let(MonitorParsers::parseSwap)
        val load = sections.getOrNull(2)?.let(MonitorParsers::parseLoad)
        val filesystems = sections.getOrNull(4)?.let(MonitorParsers::parseFilesystems).orEmpty()
        val processRows = sections.getOrNull(5)?.let(MonitorParsers::parseProcesses).orEmpty()
        // The CPU-time section is last; an empty optional section earlier in the list is dropped
        // by splitSample, so the last entry is the reliable place to look.
        val processes = withInstantaneousCpu(processRows, sections.lastOrNull())
        val interfaceAddresses = sections.getOrNull(6)
            ?.let(MonitorParsers::parseInterfaceAddresses)
            .orEmpty()
        if (cpu == null && memory == null && load == null && network == null) {
            return MonitorSnapshot(endpoint = hostSession.endpoint.host, unsupported = true)
        }
        currentBackoff = backoff
        return MonitorSnapshot(
            endpoint = hostSession.endpoint.host,
            system = systemInfo,
            cpu = cpu,
            memory = memory,
            swap = swap,
            load = load,
            network = network?.copy(interfaceAddresses = interfaceAddresses),
            filesystems = filesystems.take(processLimit),
            processes = processes.take(processLimit),
        )
    }

    /**
     * Replaces `ps`'s lifetime-average `%CPU` with the CPU used since the previous sample.
     *
     * The stat section reports cumulative CPU seconds per pid, so the delta over the elapsed wall
     * time is the instantaneous share of one core (100% = one core, matching `ps`'s convention).
     * A pid that was not present in the previous sample, or whose stat file could not be read,
     * keeps the `ps` value instead of showing a fabricated zero.
     */
    private fun withInstantaneousCpu(
        rows: List<ProcessUsage>,
        cpuTimeSection: String?,
    ): List<ProcessUsage> {
        val cpuSeconds = cpuTimeSection?.let(MonitorParsers::parseProcessCpuTimes).orEmpty()
        if (cpuSeconds.isEmpty()) return rows
        val now = System.nanoTime()
        val elapsedSeconds = if (previousProcessSampleNanos == 0L) {
            0.0
        } else {
            (now - previousProcessSampleNanos) / 1_000_000_000.0
        }
        val previous = previousProcessCpuSeconds
        val measured = rows.map { process ->
            val currentCpu = cpuSeconds[process.pid]
            val previousCpu = previous[process.pid]
            if (currentCpu == null || previousCpu == null || elapsedSeconds <= 0.0) {
                process
            } else {
                val percent = (currentCpu - previousCpu) / elapsedSeconds * 100.0
                process.copy(cpuPercent = percent.coerceAtLeast(0.0))
            }
        }
        previousProcessCpuSeconds = cpuSeconds
        previousProcessSampleNanos = now
        return measured
    }

    private fun rawNetwork(section: String?): MonitorParsers.NetworkRaw? {
        val interfaces = section?.lines()
            ?.map(String::trim)
            ?.filter { it.contains(":") }
            ?.mapNotNull { line ->
                val separator = line.indexOf(':')
                val name = line.substring(0, separator).trim()
                val fields = line.substring(separator + 1).trim().split(Regex("\\s+")).mapNotNull(String::toLongOrNull)
                if (fields.size < 9) null else MonitorParsers.NetworkInterfaceRaw(name, fields[0], fields[8])
            }
            ?: return null
        if (interfaces.isEmpty()) return null
        return MonitorParsers.NetworkRaw(interfaces)
    }

    private fun sleep(wait: Duration) {
        try {
            Thread.sleep(wait.toMillis())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        thread?.interrupt()
        thread?.join(Duration.ofSeconds(2))
    }
}
