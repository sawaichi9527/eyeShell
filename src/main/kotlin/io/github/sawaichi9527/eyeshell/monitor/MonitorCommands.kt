package io.github.sawaichi9527.eyeshell.monitor

object MonitorCommands {
    const val ENV_PREFIX = "LC_ALL=C LANG=C"
    const val SECTION_DELIMITER = "\u000C"

    fun systemInfo(): String = "$ENV_PREFIX; uname -a; echo; cat /proc/uptime"

    fun sample(): String = listOf(
        "$ENV_PREFIX; cat /proc/stat",
        "$ENV_PREFIX; cat /proc/meminfo",
        "$ENV_PREFIX; cat /proc/loadavg",
        "$ENV_PREFIX; cat /proc/net/dev",
        "$ENV_PREFIX; df -P",
        "$ENV_PREFIX; ps -eo pid=,rss=,pcpu=,comm= --sort=-rss",
    ).joinToString(SECTION_DELIMITER)
}
