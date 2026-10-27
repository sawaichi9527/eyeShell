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
        PROCESS_CPU_TIME_SECTION,
    )

    /**
     * Per-process CPU time for the same top-RSS processes, so the sampler can derive an
     * instantaneous percentage from the delta between two samples. `ps`'s own `%CPU` is the
     * average over the process lifetime and barely moves for long-running daemons.
     */
    internal const val PROCESS_CPU_TIME_SECTION =
        "ps -eo pid= --sort=-rss | head -n 10 | while read -r pid; do " +
            "printf '# %s ' \"\$pid\"; cat /proc/\$pid/stat 2>/dev/null | tr '\\n' ' '; printf '\\n'; done"

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
