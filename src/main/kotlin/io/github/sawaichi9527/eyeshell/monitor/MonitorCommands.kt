package io.github.sawaichi9527.eyeshell.monitor

object MonitorCommands {
    const val ENV_PREFIX = "export LC_ALL=C LANG=C"
    const val SECTION_DELIMITER = "\u000C"
    internal const val DELIMITER_COMMAND = "printf '\\f'"

    internal val SAMPLE_SECTIONS = listOf(
        "cat /proc/stat",
        "cat /proc/meminfo",
        "cat /proc/loadavg",
        "cat /proc/net/dev",
        "df -P",
        "ps -eo pid=,rss=,pcpu=,comm= --sort=-rss",
        "ip -o -4 addr show 2>/dev/null",
    )

    fun systemInfo(): String = "$ENV_PREFIX; uname -a; echo; cat /proc/uptime; echo; cat /etc/os-release 2>/dev/null"

    fun sample(): String = buildString {
        append(ENV_PREFIX)
        SAMPLE_SECTIONS.forEachIndexed { index, command ->
            append("; ")
            if (index > 0) append(DELIMITER_COMMAND).append("; ")
            append(command)
        }
    }
}
