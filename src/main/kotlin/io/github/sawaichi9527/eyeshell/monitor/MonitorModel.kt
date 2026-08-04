package io.github.sawaichi9527.eyeshell.monitor

data class MonitorSnapshot(
    val system: SystemInfo? = null,
    val cpu: CpuUsage? = null,
    val memory: MemoryUsage? = null,
    val swap: SwapUsage? = null,
    val load: LoadAverage? = null,
    val network: NetworkUsage? = null,
    val filesystems: List<FilesystemUsage> = emptyList(),
    val processes: List<ProcessUsage> = emptyList(),
    val unsupported: Boolean = false,
) {
    val swapPresent: Boolean
        get() = swap != null && swap.totalBytes > 0

    val hasData: Boolean
        get() = system != null || cpu != null || memory != null || load != null ||
            network != null || filesystems.isNotEmpty() || processes.isNotEmpty()
}

data class SystemInfo(
    val hostname: String,
    val kernel: String,
    val operatingSystem: String,
    val uptimeSeconds: Long,
)

data class CpuUsage(
    val percent: Double,
    val cores: Int,
)

data class MemoryUsage(
    val totalBytes: Long,
    val usedBytes: Long,
    val percent: Double,
)

data class SwapUsage(
    val totalBytes: Long,
    val usedBytes: Long,
    val percent: Double,
)

data class LoadAverage(
    val oneMinute: Double,
    val fiveMinute: Double,
    val fifteenMinute: Double,
)

data class NetworkUsage(
    val receiveBytesPerSecond: Long,
    val transmitBytesPerSecond: Long,
    val interfaces: List<NetworkInterfaceUsage>,
)

data class NetworkInterfaceUsage(
    val name: String,
    val receiveBytesPerSecond: Long,
    val transmitBytesPerSecond: Long,
)

data class FilesystemUsage(
    val mountPoint: String,
    val totalBytes: Long,
    val usedBytes: Long,
    val percent: Double,
)

data class ProcessUsage(
    val pid: Long,
    val name: String,
    val memoryBytes: Long,
    val cpuPercent: Double,
)
