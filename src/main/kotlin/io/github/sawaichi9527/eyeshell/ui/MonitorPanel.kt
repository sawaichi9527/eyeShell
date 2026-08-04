package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.monitor.MonitorSnapshot
import io.github.sawaichi9527.eyeshell.monitor.NetworkUsage
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.text.DecimalFormat
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

class MonitorPanel : JPanel(BorderLayout()) {
    private val content = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val hostname = sectionLabel()
    private val system = sectionLabel()
    private val uptime = sectionLabel()
    private val load = sectionLabel()
    private val cpuValue = sectionLabel()
    private val cpuBar = bar()
    private val memoryValue = sectionLabel()
    private val memoryBar = bar()
    private val swapValue = sectionLabel()
    private val swapBar = bar()
    private val networkValue = sectionLabel()
    private val processTable = JTable(DefaultTableModel(arrayOf("Name", "Mem", "CPU"), 0))

    init {
        name = "monitorPanel"
        getAccessibleContext().accessibleName = "Monitor"
        minimumSize = Dimension(190, 0)
        preferredSize = Dimension(230, 0)
        content.border = javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12)
        add(content, BorderLayout.CENTER)
        resetToIdle()
    }

    fun resetToIdle() {
        content.removeAll()
        content.add(sectionTitle("Monitor"))
        content.add(Box.createVerticalStrut(6))
        content.add(sectionLabel("No active session."))
        content.add(Box.createVerticalStrut(4))
        content.add(sectionLabel("Connect to a host to view live metrics."))
        content.add(Box.createVerticalGlue())
        revalidate()
        repaint()
    }

    fun update(snapshot: MonitorSnapshot) {
        content.removeAll()
        content.add(sectionTitle("Monitor"))
        content.add(Box.createVerticalStrut(8))

        snapshot.system?.let { systemInfo ->
            hostname.text = systemInfo.hostname
            system.text = "${systemInfo.operatingSystem} ${systemInfo.kernel}"
            uptime.text = "Uptime: ${formatUptime(systemInfo.uptimeSeconds)}"
        }
        content.add(field("Host", hostname))
        content.add(field("OS", system))
        content.add(field("Uptime", uptime))
        content.add(Box.createVerticalStrut(6))

        snapshot.cpu?.let { cpu ->
            cpuValue.text = "CPU ${formatPercent(cpu.percent)}"
            cpuBar.value = cpu.percent.toInt()
        }
        content.add(field("CPU", cpuValue))
        content.add(cpuBar)
        content.add(Box.createVerticalStrut(6))

        snapshot.memory?.let { memory ->
            memoryValue.text = "${formatBytes(memory.usedBytes)} / ${formatBytes(memory.totalBytes)} (${formatPercent(memory.percent)})"
            memoryBar.value = memory.percent.toInt()
        }
        content.add(field("Memory", memoryValue))
        content.add(memoryBar)
        content.add(Box.createVerticalStrut(6))

        if (snapshot.swapPresent) {
            snapshot.swap?.let { swap ->
                swapValue.text = "${formatBytes(swap.usedBytes)} / ${formatBytes(swap.totalBytes)} (${formatPercent(swap.percent)})"
                swapBar.value = swap.percent.toInt()
            }
            content.add(field("Swap", swapValue))
            content.add(swapBar)
            content.add(Box.createVerticalStrut(6))
        }

        snapshot.load?.let { load ->
            this.load.text = String.format("%.2f %.2f %.2f", load.oneMinute, load.fiveMinute, load.fifteenMinute)
            content.add(field("Load", this.load))
            content.add(Box.createVerticalStrut(6))
        }

        snapshot.network?.let(::renderNetwork)
        if (snapshot.processes.isNotEmpty()) {
            content.add(Box.createVerticalStrut(6))
            renderProcesses(snapshot.processes.take(MAX_PROCESS_ROWS))
        }

        if (snapshot.unsupported) {
            content.add(Box.createVerticalStrut(8))
            content.add(sectionLabel("Metrics unsupported on this host."))
        }
        content.add(Box.createVerticalGlue())
        revalidate()
        repaint()
    }

    private fun renderNetwork(network: NetworkUsage) {
        networkValue.text = "↓ ${formatBytes(network.receiveBytesPerSecond)}/s  ↑ ${formatBytes(network.transmitBytesPerSecond)}/s"
        content.add(field("Network", networkValue))
    }

    private fun renderProcesses(processes: List<io.github.sawaichi9527.eyeshell.monitor.ProcessUsage>) {
        content.add(sectionLabel("Processes"))
        val model = processTable.model as DefaultTableModel
        model.setRowCount(0)
        processes.forEach { process ->
            model.addRow(arrayOf<Any>(
                process.name,
                formatBytes(process.memoryBytes),
                formatPercent(process.cpuPercent),
            ))
        }
        processTable.setRowHeight(18)
        content.add(processTable)
    }

    private fun sectionTitle(text: String): JLabel = JLabel(text).apply {
        alignmentX = 0f
        font = font.deriveFont(Font.BOLD, 14f)
    }

    private fun sectionLabel(text: String = ""): JLabel = JLabel(text).apply {
        alignmentX = 0f
    }

    private fun field(label: String, value: JLabel): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        alignmentX = 0f
        add(JLabel("$label: "))
        add(value)
    }

    private fun bar(): JProgressBar = JProgressBar(0, 100).apply {
        alignmentX = 0f
        maximumSize = Dimension(Int.MAX_VALUE, 14)
        isStringPainted = true
    }

    private fun formatBytes(bytes: Long): String = BYTES_FORMAT.format(bytes.toDouble() / 1024.0 / 1024.0) + " MB"

    private fun formatPercent(percent: Double): String = PERCENT_FORMAT.format(percent) + "%"

    private fun formatUptime(seconds: Long): String {
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        return if (days > 0) "${days}d ${hours}h ${minutes}m" else "${hours}h ${minutes}m"
    }

    companion object {
        private val BYTES_FORMAT = DecimalFormat("#,##0.0")
        private val PERCENT_FORMAT = DecimalFormat("0.0")
        private const val MAX_PROCESS_ROWS = 6
    }
}
