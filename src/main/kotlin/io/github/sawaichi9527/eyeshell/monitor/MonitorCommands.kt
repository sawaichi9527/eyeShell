package io.github.sawaichi9527.eyeshell.monitor

object MonitorCommands {
    const val ENV_PREFIX = "export LC_ALL=C LANG=C"
    const val SECTION_DELIMITER = "\u000C"
    internal const val DELIMITER_COMMAND = "printf '\\f'"

    private val SAMPLE_SECTIONS = listOf(
        "cat /proc/stat",
        "cat /proc/meminfo",
        "cat /proc/loadavg",
        "cat /proc/net/dev",
        "df -P",
        "ps -eo pid=,rss=,pcpu=,comm= --sort=-rss",
    )

    fun systemInfo(): String = "$ENV_PREFIX; uname -a; echo; cat /proc/uptime"

    fun sample(): String = buildString {
        append(ENV_PREFIX)
        SAMPLE_SECTIONS.forEachIndexed { index, command ->
            append("; ")
            if (index > 0) append(DELIMITER_COMMAND).append("; ")
            append(command)
        }
    }
}
