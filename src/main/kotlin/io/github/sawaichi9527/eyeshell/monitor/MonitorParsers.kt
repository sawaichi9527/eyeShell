package io.github.sawaichi9527.eyeshell.monitor

import kotlin.math.roundToInt

object MonitorParsers {
    fun parseSystemInfo(output: String): SystemInfo? {
        val lines = output.lines()
        val unameLine = lines.firstOrNull { it.trim().isNotEmpty() } ?: return null
        val uptimeLine = lines.dropWhile { it.trim().isEmpty() }
            .drop(1)
            .firstOrNull { it.trim().isNotEmpty() } ?: return null
        val uptimeSeconds = uptimeLine.trim().split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull()
            ?: return null
        val fields = unameLine.trim().split(Regex("\\s+"))
        val kernel = fields.getOrNull(2) ?: return null
        val hostname = fields.getOrNull(1) ?: return null
        val operatingSystem = fields.getOrNull(0) ?: return null
        return SystemInfo(
            hostname = hostname,
            kernel = kernel,
            operatingSystem = operatingSystem,
            uptimeSeconds = uptimeSeconds.roundToLong(),
        )
    }

    fun parseCpu(rawOutput: String, current: CpuRaw, previous: CpuRaw?): CpuUsage? {
        val cores = rawOutput.lines().count { it.startsWith("cpu") && !it.startsWith("cpu ") && it.trim().isNotEmpty() }
        val coreCount = cores.coerceAtLeast(1)
        return if (previous == null) {
            CpuUsage(percent = 0.0, cores = coreCount)
        } else {
            val totalDelta = (current.total - previous.total).coerceAtLeast(0L)
            val idleDelta = (current.idle - previous.idle).coerceAtLeast(0L)
            val percent = if (totalDelta == 0L) 0.0 else {
                ((totalDelta - idleDelta).toDouble() / totalDelta * 100.0).coerceIn(0.0, 100.0)
            }
            CpuUsage(percent = percent, cores = coreCount)
        }
    }

    fun cpuRaw(output: String): CpuRaw? {
        val cpuLine = output.lines().firstOrNull { it.startsWith("cpu ") } ?: return null
        val values = cpuLine.trim().split(Regex("\\s+")).drop(1).mapNotNull(String::toLongOrNull)
        if (values.size < 4) return null
        val idle = (values.getOrNull(3) ?: 0L) + (values.getOrNull(4) ?: 0L)
        return CpuRaw(values.sum(), idle)
    }

    fun parseMemory(output: String): MemoryUsage? {
        val values = output.lines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.isEmpty()) return@mapNotNull null
            val key = parts.first().removeSuffix(":")
            val value = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            key to (value * 1024L)
        }.toMap()
        val total = values["MemTotal"] ?: return null
        val available = values["MemAvailable"] ?: values["MemFree"] ?: return null
        val used = (total - available).coerceAtLeast(0L)
        val percent = if (total == 0L) 0.0 else (used.toDouble() / total * 100.0).coerceIn(0.0, 100.0)
        return MemoryUsage(totalBytes = total, usedBytes = used, percent = percent)
    }

    fun parseSwap(output: String): SwapUsage? {
        val values = output.lines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.isEmpty()) return@mapNotNull null
            val key = parts.first().removeSuffix(":")
            val value = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            key to (value * 1024L)
        }.toMap()
        val total = values["SwapTotal"] ?: return null
        val free = values["SwapFree"] ?: return null
        val used = (total - free).coerceAtLeast(0L)
        val percent = if (total == 0L) 0.0 else (used.toDouble() / total * 100.0).coerceIn(0.0, 100.0)
        return SwapUsage(totalBytes = total, usedBytes = used, percent = percent)
    }

    fun parseLoad(output: String): LoadAverage? {
        val fields = output.trim().split(Regex("\\s+"))
        val one = fields.getOrNull(0)?.toDoubleOrNull() ?: return null
        val five = fields.getOrNull(1)?.toDoubleOrNull() ?: return null
        val fifteen = fields.getOrNull(2)?.toDoubleOrNull() ?: return null
        return LoadAverage(one, five, fifteen)
    }

    fun parseNetwork(rawOutput: String, previous: NetworkRaw?): NetworkUsage? {
        val lines = rawOutput.lines()
            .map(String::trim)
            .filter { it.contains(":") }
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                val name = line.substring(0, separator).trim()
                val fields = line.substring(separator + 1).trim().split(Regex("\\s+")).mapNotNull(String::toLongOrNull)
                if (fields.size < 9) null else NetworkInterfaceRaw(name, fields[0], fields[8])
            }
        if (lines.isEmpty()) return null
        val current = NetworkRaw(lines)
        if (previous == null) return NetworkUsage(
            receiveBytesPerSecond = 0L,
            transmitBytesPerSecond = 0L,
            interfaces = lines.map { NetworkInterfaceUsage(it.name, 0L, 0L) },
        )
        val interfaces = current.interfaces.mapNotNull { currentInterface ->
            val prior = previous.interfaces.firstOrNull { it.name == currentInterface.name } ?: return@mapNotNull null
            NetworkInterfaceUsage(
                name = currentInterface.name,
                receiveBytesPerSecond = (currentInterface.receiveBytes - prior.receiveBytes).coerceAtLeast(0L),
                transmitBytesPerSecond = (currentInterface.transmitBytes - prior.transmitBytes).coerceAtLeast(0L),
            )
        }
        return NetworkUsage(
            receiveBytesPerSecond = interfaces.sumOf { it.receiveBytesPerSecond },
            transmitBytesPerSecond = interfaces.sumOf { it.transmitBytesPerSecond },
            interfaces = interfaces,
        )
    }

    fun parseFilesystems(output: String): List<FilesystemUsage> = output.lines()
        .map(String::trim)
        .filter { it.startsWith("/dev/") }
        .mapNotNull { line ->
            val fields = line.split(Regex("\\s+"))
            if (fields.size < 6) return@mapNotNull null
            val total = fields.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            val used = fields.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
            val mountPoint = fields.getOrNull(5) ?: return@mapNotNull null
            val percent = if (total == 0L) 0.0 else (used.toDouble() / total * 100.0).coerceIn(0.0, 100.0)
            FilesystemUsage(
                mountPoint = mountPoint,
                totalBytes = total * 1024L,
                usedBytes = used * 1024L,
                percent = percent,
            )
        }

    fun parseProcesses(output: String): List<ProcessUsage> = output.lines()
        .map(String::trim)
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val fields = line.split(Regex("\\s+"))
            if (fields.size < 4) return@mapNotNull null
            val pid = fields.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val rss = fields.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            val cpu = fields.getOrNull(2)?.toDoubleOrNull() ?: return@mapNotNull null
            val name = fields.drop(3).joinToString(" ")
            ProcessUsage(
                pid = pid,
                name = name,
                memoryBytes = rss * 1024L,
                cpuPercent = cpu,
            )
        }

    fun splitSample(output: String): List<String> = output.split(SECTION_DELIMITER_REGEX)
        .map(String::trim)
        .filter(String::isNotEmpty)

    private val SECTION_DELIMITER_REGEX = Regex(Regex.escape(MonitorCommands.SECTION_DELIMITER))

    data class CpuRaw(val total: Long, val idle: Long)

    data class NetworkRaw(val interfaces: List<NetworkInterfaceRaw>)

    data class NetworkInterfaceRaw(
        val name: String,
        val receiveBytes: Long,
        val transmitBytes: Long,
    )

    private fun Double.roundToLong(): Long = if (this >= 0) (this + 0.5).roundToInt().toLong() else (this - 0.5).roundToInt().toLong()
}
