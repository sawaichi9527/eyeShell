package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.monitor.FilesystemUsage
import io.github.sawaichi9527.eyeshell.monitor.MonitorSnapshot
import io.github.sawaichi9527.eyeshell.monitor.NetworkInterfaceUsage
import io.github.sawaichi9527.eyeshell.monitor.NetworkUsage
import io.github.sawaichi9527.eyeshell.monitor.ProcessUsage
import io.github.sawaichi9527.eyeshell.platform.I18n
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.text.DecimalFormat
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.event.ChangeEvent
import javax.swing.event.ListSelectionEvent
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.TableColumnModelListener
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * Left-hand live monitor sidebar.
 *
 * Layout follows the FinalShell reference: a compact two-column metric grid, coloured meters with
 * the percentage inside the bar, a muted detail line for absolute values, then network,
 * filesystem, and process sections. Every update rebuilds the grid so no stale value can survive
 * a snapshot that no longer carries that metric.
 */
class MonitorPanel(
    private val onOpenSettings: (() -> Unit)? = null,
) : JPanel(BorderLayout()) {
    private val content = ScrollableContent()
    private val contentScroll = JScrollPane(content).apply {
        name = "monitorScroll"
        border = BorderFactory.createEmptyBorder()
        isOpaque = false
        viewport.isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        verticalScrollBar.apply {
            putClientProperty("JScrollBar.showButtons", false)
            preferredSize = Dimension(THIN_SCROLLBAR_WIDTH, 0)
            unitIncrement = 16
        }
    }
    private var selectedInterface: String? = null
    private var applyingProcessColumnWidths = false
    private val userProcessColumnWidths = HashMap<Int, Int>()
    private val lastProcessColumnWidths = IntArray(3)
    private var lastSnapshot: MonitorSnapshot? = null
    private val processModel = ReadOnlyTableModel(arrayOf(I18n.t("monitor.process.name"), I18n.t("monitor.process.mem"), I18n.t("monitor.process.cpu")))
    private val processTable = JTable(processModel).apply {
        name = "monitorProcessTable"
        // Columns are measured from their content, so a long process name widens the table and the
        // horizontal bar below can reveal it instead of the renderer truncating it.
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        tableHeader.reorderingAllowed = false
        rowSelectionAllowed = false
        cellSelectionEnabled = false
        setShowGrid(false)
        intercellSpacing = Dimension(0, 2)
        rowHeight = 19
        isFocusable = false
        columnModel.getColumn(0).apply {
            preferredWidth = 108
            cellRenderer = alignedCellRenderer(SwingConstants.LEFT)
            headerRenderer = alignedHeaderRenderer(SwingConstants.LEFT)
        }
        columnModel.getColumn(1).apply {
            preferredWidth = 74
            cellRenderer = alignedCellRenderer(SwingConstants.RIGHT)
            headerRenderer = alignedHeaderRenderer(SwingConstants.RIGHT)
        }
        columnModel.getColumn(2).apply {
            preferredWidth = 48
            cellRenderer = alignedCellRenderer(SwingConstants.RIGHT)
            headerRenderer = alignedHeaderRenderer(SwingConstants.RIGHT)
        }
    }

    /**
     * Built once and reused across snapshots: the content grid is rebuilt on every sample, and a
     * fresh `JScrollPane` would reset the horizontal position while the user is reading a long row.
     */
    private val processScrollPane = JScrollPane(processTable).apply {
        name = "monitorProcessScroll"
        border = BorderFactory.createEmptyBorder()
        isOpaque = false
        viewport.isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        horizontalScrollBar.apply {
            name = "monitorProcessHorizontalScrollBar"
            putClientProperty("JScrollBar.showButtons", false)
            preferredSize = Dimension(0, THIN_SCROLLBAR_WIDTH)
            unitIncrement = 16
        }
    }

    /** Double-clicking a header auto-fits that column again. */
    private val processHeaderDoubleClickListener = object : MouseAdapter() {
        override fun mouseClicked(event: MouseEvent) {
            if (event.clickCount != 2 || event.button != MouseEvent.BUTTON1) return
            val column = processTable.columnModel.getColumnIndexAtX(event.x)
            if (column < 0) return
            autoFitProcessColumn(column)
            event.consume()
        }
    }

    /**
     * Records a width the user dragged. Swing reports every column-width change as one event with
     * no column identity, so the width that differs from the last applied value is the one the user
     * grabbed; [applyingProcessColumnWidths] filters out the changes this class makes itself.
     */
    private val processColumnResizeListener = object : TableColumnModelListener {
        override fun columnAdded(event: TableColumnModelEvent?) = Unit
        override fun columnRemoved(event: TableColumnModelEvent?) = Unit
        override fun columnMoved(event: TableColumnModelEvent?) = Unit
        override fun columnSelectionChanged(event: ListSelectionEvent?) = Unit
        override fun columnMarginChanged(event: ChangeEvent?) {
            if (applyingProcessColumnWidths) return
            for (column in 0 until processTable.columnModel.columnCount) {
                val width = processTable.columnModel.getColumn(column).width
                if (width != lastProcessColumnWidths[column]) {
                    userProcessColumnWidths[column] = width
                    lastProcessColumnWidths[column] = width
                }
            }
        }
    }


    init {
        name = "monitorPanel"
        getAccessibleContext().accessibleName = I18n.t("monitor.title")
        minimumSize = Dimension(190, 0)
        preferredSize = Dimension(230, 0)
        processTable.columnModel.addColumnModelListener(processColumnResizeListener)
        processTable.tableHeader.addMouseListener(processHeaderDoubleClickListener)
        content.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        add(contentScroll, BorderLayout.CENTER)
        resetToIdle()
    }

    override fun addNotify() {
        super.addNotify()
        // The LAF finalises fonts when the window is installed, so rebuild the idle skeleton once
        // the panel is displayable to keep the type scale identical to the connected state.
        if (lastSnapshot == null) resetToIdle()
    }

    /** Re-renders the current state so labels follow the new language. */
    fun relocalize() {
        lastSnapshot?.let { update(it) } ?: resetToIdle()
        processTable.columnModel.getColumn(0).headerValue = I18n.t("monitor.process.name")
        processTable.columnModel.getColumn(1).headerValue = I18n.t("monitor.process.mem")
        processTable.columnModel.getColumn(2).headerValue = I18n.t("monitor.process.cpu")
        processTable.tableHeader.repaint()
    }

    /** Test hook: the Processes table is only added to the tree while a snapshot is rendered. */
    internal val processTableComponent: JTable
        get() = processTable

    fun resetToIdle() {
        content.removeAll()
        selectedInterface = null
        lastSnapshot = null
        var row = addTopSpacer(0)
        row = addTitle(row)
        row = addSeparator(row)
        row = addDetail(row, I18n.t("monitor.host"), EMPTY_VALUE, tooltip = null)
        row = addDetail(row, I18n.t("monitor.address"), EMPTY_VALUE, tooltip = null)
        row = addDetail(row, I18n.t("monitor.os"), EMPTY_VALUE, tooltip = null)
        row = addDetail(row, I18n.t("monitor.kernel"), EMPTY_VALUE, tooltip = null)
        row = addDetail(row, I18n.t("monitor.uptime"), EMPTY_VALUE, tooltip = null)
        row = addSeparator(row)
        row = addDetail(row, I18n.t("monitor.load"), EMPTY_VALUE, tooltip = null)
        row = addDetail(row, I18n.t("monitor.cpu"), EMPTY_VALUE, tooltip = null)
        row = addDetail(row, I18n.t("monitor.memory"), EMPTY_VALUE, tooltip = null)
        row = addDetail(row, I18n.t("monitor.swap"), EMPTY_VALUE, tooltip = null)
        row = addSeparator(row)
        row = addHeading(row, I18n.t("monitor.network"))
        row = addDetail(row, I18n.t("monitor.down"), EMPTY_VALUE, tooltip = null)
        row = addDetail(row, I18n.t("monitor.up"), EMPTY_VALUE, tooltip = null)
        row = addSeparator(row)
        row = addHeading(row, I18n.t("monitor.filesystems"))
        row = addSpanning(row, mutedLabel(EMPTY_VALUE), top = 0, bottom = 2)
        row = addSeparator(row)
        row = addHeading(row, I18n.t("monitor.processes"))
        row = addSpanning(row, mutedLabel(EMPTY_VALUE), top = 0, bottom = 2)
        row = addSeparator(row)
        row = addSpanning(
            row,
            JLabel("●  ${UiText.IDLE_STATUS}").apply { foreground = mutedForeground() },
            top = 2,
            bottom = 1,
        )
        row = addSpanning(row, mutedLabel(UiText.IDLE_HINT), top = 0, bottom = 2)
        row = addSpanning(row, mutedLabel(runtimeSummary()), top = 2, bottom = 2)
        addVerticalFiller(row)
        syncContentPreferredSize()
        revalidate()
        repaint()
    }

    /**
     * GridBagLayout reports a preferred height that is smaller than the rows it then lays out (the
     * first display rebuild triggers this), which squeezes the title and the footer until a terminal
     * is attached. Sizing the scrollable content from the grid's own row heights keeps every row at
     * its preferred height.
     */
    private fun syncContentPreferredSize() {
        // The grid's preferred size and its laid-out row heights disagree on the first display pass
        // (614 vs 640 px), which squeezes the title and the footer. Recompute from the rows the
        // layout actually produced, one event-loop turn later, so the viewport cannot clip them.
        javax.swing.SwingUtilities.invokeLater {
            val layout = content.layout as? java.awt.GridBagLayout ?: return@invokeLater
            val rows = layout.getLayoutDimensions().getOrNull(1)?.sum() ?: return@invokeLater
            val insets = content.insets
            val needed = rows + insets.top + insets.bottom
            if (content.preferredSize.height != needed) {
                content.preferredSize = Dimension(1, needed)
                content.revalidate()
                contentScroll.revalidate()
                repaint()
            }
        }
    }

    private fun runtimeSummary(): String = buildString {
        append("Java ").append(System.getProperty("java.version", "?"))
        append("  ·  ").append(System.getProperty("os.name", "?"))
        append(' ').append(System.getProperty("os.arch", "?"))
    }

    fun update(snapshot: MonitorSnapshot) {
        content.removeAll()
        lastSnapshot = snapshot
        var row = addTopSpacer(0)
        row = addTitle(row)
        row = addSeparator(row)

        row = addDetail(row, I18n.t("monitor.host"), snapshot.system?.hostname?.takeIf(String::isNotEmpty) ?: "-", copyable = true)
        snapshot.endpoint?.let { endpoint ->
            row = addDetail(row, I18n.t("monitor.address"), displayAddress(endpoint), copyable = true)
        }
        snapshot.system?.let { systemInfo ->
            row = addDetail(
                row,
                I18n.t("monitor.os"),
                systemInfo.distribution.ifBlank { systemInfo.operatingSystem },
                copyable = true,
            )
            row = addDetail(row, I18n.t("monitor.kernel"), systemInfo.kernel, copyable = true)
            row = addDetail(row, I18n.t("monitor.uptime"), formatUptime(systemInfo.uptimeSeconds), copyable = true)
        }

        if (snapshot.cpu != null || snapshot.memory != null || snapshot.swapPresent || snapshot.load != null) {
            row = addSeparator(row)
        }
        snapshot.load?.let { load ->
            val cores = snapshot.cpu?.cores ?: 0
            row = addDetail(
                row = row,
                label = I18n.t("monitor.load"),
                value = String.format("%.2f %.2f %.2f", load.oneMinute, load.fiveMinute, load.fifteenMinute),
                tooltip = UiText.LOAD_TOOLTIP,
                warning = cores > 0 && load.oneMinute > cores,
            )
        }
        snapshot.cpu?.let { cpu ->
            row = addMeter(
                row = row,
                label = I18n.t("monitor.cpu"),
                percent = cpu.percent,
                color = CPU_COLOR,
                detail = cpu.cores.takeIf { it > 0 }?.let { cores -> if (cores == 1) I18n.t("monitor.cores.one") else I18n.t("monitor.cores.many", cores) },
            )
        }
        snapshot.memory?.let { memory ->
            row = addMeter(
                row = row,
                label = I18n.t("monitor.memory"),
                percent = memory.percent,
                color = MEMORY_COLOR,
                detail = "${formatSize(memory.usedBytes)} / ${formatSize(memory.totalBytes)}",
            )
        }
        if (snapshot.swapPresent) {
            snapshot.swap?.let { swap ->
                row = addMeter(
                    row = row,
                    label = I18n.t("monitor.swap"),
                    percent = swap.percent,
                    color = SWAP_COLOR,
                    detail = "${formatSize(swap.usedBytes)} / ${formatSize(swap.totalBytes)}",
                )
            }
        }

        snapshot.network?.let { network ->
            row = addSeparator(row)
            row = renderNetwork(row, network, snapshot.endpoint)
        }

        if (snapshot.filesystems.isNotEmpty()) {
            row = addSeparator(row)
            row = addHeading(row, I18n.t("monitor.filesystems"))
            row = renderFilesystems(row, snapshot.filesystems)
        }

        if (snapshot.processes.isNotEmpty()) {
            row = addSeparator(row)
            row = addHeading(row, I18n.t("monitor.processes"))
            row = renderProcesses(row, snapshot.processes)
        }

        if (snapshot.unsupported) {
            row = addSeparator(row)
            row = addSpanning(row, mutedLabel(I18n.t("monitor.unsupported")), top = 2, bottom = 2)
        }
        addVerticalFiller(row)
        syncContentPreferredSize()
        revalidate()
        repaint()
    }

    private fun renderNetwork(row: Int, network: NetworkUsage, endpoint: String?): Int {
        val interfaces = network.interfaces.sortedBy { it.name }
        if (interfaces.isEmpty()) {
            var current = addDetail(row, I18n.t("monitor.down"), formatRate(network.receiveBytesPerSecond))
            current = addDetail(current, I18n.t("monitor.up"), formatRate(network.transmitBytesPerSecond))
            return current
        }
        val known = interfaces.firstOrNull { it.name == selectedInterface }
        val active = known ?: defaultInterface(network, interfaces, endpoint)
        if (known == null) selectedInterface = active?.name

        var current = addHeadingWithControl(row, I18n.t("monitor.network"), interfaceSelector(network, interfaces, active))
        current = addDetail(current, I18n.t("monitor.down"), formatRate(active?.receiveBytesPerSecond ?: network.receiveBytesPerSecond))
        current = addDetail(current, I18n.t("monitor.up"), formatRate(active?.transmitBytesPerSecond ?: network.transmitBytesPerSecond))
        return current
    }

    private fun interfaceSelector(
        network: NetworkUsage,
        interfaces: List<NetworkInterfaceUsage>,
        active: NetworkInterfaceUsage?,
    ): JComboBox<String> = JComboBox(interfaces.map { it.name }.toTypedArray()).apply {
        name = "monitorInterfaceSelector"
        selectedItem = active?.name
        isFocusable = false
        font = baseFont()
        preferredSize = Dimension(96, 22)
        toolTipText = active?.let { iface ->
            network.interfaceAddresses[iface.name]?.joinToString(", ")?.takeIf(String::isNotEmpty)
                ?: I18n.t("monitor.interface.hint")
        } ?: I18n.t("monitor.interface.hint")
        addActionListener {
            val name = selectedItem as? String ?: return@addActionListener
            if (name == selectedInterface) return@addActionListener
            selectedInterface = name
            val snapshot = lastSnapshot ?: return@addActionListener
            SwingUtilities.invokeLater { update(snapshot) }
        }
    }

    private fun defaultInterface(
        network: NetworkUsage,
        interfaces: List<NetworkInterfaceUsage>,
        endpoint: String?,
    ): NetworkInterfaceUsage? {
        val candidates = interfaces.filter { it.name != LOOPBACK_INTERFACE }.ifEmpty { interfaces }
        if (candidates.isEmpty()) return null
        val target = endpoint?.let(::displayAddress)
        if (!target.isNullOrEmpty()) {
            candidates.firstOrNull { iface ->
                network.interfaceAddresses[iface.name]?.contains(target) == true
            }?.let { return it }
        }
        return candidates.maxByOrNull { it.receiveBytesPerSecond + it.transmitBytesPerSecond }
    }

    private fun copyToClipboard(text: String): Boolean = try {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        true
    } catch (_: Exception) {
        false
    }

    private fun displayAddress(endpoint: String): String {
        val trimmed = endpoint.trim()
        if (trimmed.startsWith("[") && trimmed.contains(']')) {
            return trimmed.substring(1, trimmed.indexOf(']'))
        }
        val colon = trimmed.lastIndexOf(':')
        val looksLikePort = colon > 0 &&
            trimmed.count { it == ':' } == 1 &&
            trimmed.substring(colon + 1).isNotEmpty() &&
            trimmed.substring(colon + 1).all(Char::isDigit)
        return if (looksLikePort) trimmed.substring(0, colon) else trimmed
    }

    private fun renderFilesystems(row: Int, filesystems: List<FilesystemUsage>): Int {
        val grid = JPanel(GridBagLayout())
        var fileRow = 0
        filesystems.take(MAX_FILESYSTEM_ROWS).forEach { filesystem ->
            val mount = elide(filesystem.mountPoint, MAX_MOUNT_CHARS).let { text ->
                JLabel(text).apply { toolTipText = filesystem.mountPoint }
            }
            grid.add(mount, tableConstraints(0, fileRow, weightx = 1.0, anchor = GridBagConstraints.WEST))
            grid.add(
                JLabel(formatSize(filesystem.usedBytes)).apply { horizontalAlignment = SwingConstants.RIGHT },
                tableConstraints(1, fileRow, weightx = 0.0, anchor = GridBagConstraints.EAST),
            )
            grid.add(
                JLabel(formatPercent(filesystem.percent)).apply {
                    horizontalAlignment = SwingConstants.RIGHT
                    foreground = mutedForeground()
                },
                tableConstraints(2, fileRow, weightx = 0.0, anchor = GridBagConstraints.EAST),
            )
            fileRow++
        }
        val placed = addSpanning(row, grid, top = 0, bottom = 4)
        if (filesystems.size > MAX_FILESYSTEM_ROWS) {
            return addSpanning(
                placed,
                mutedLabel(I18n.t("monitor.more", filesystems.size - MAX_FILESYSTEM_ROWS)),
                top = 0,
                bottom = 2,
            )
        }
        return placed
    }

    private fun renderProcesses(row: Int, processes: List<ProcessUsage>): Int {
        processModel.setRowCount(0)
        processes.take(MAX_PROCESS_ROWS).forEach { process ->
            processModel.addRow(arrayOf<Any>(
                process.name,
                formatSize(process.memoryBytes),
                formatPercent(process.cpuPercent),
            ))
        }
        measureProcessColumns()
        val headerHeight = processTable.tableHeader.preferredSize.height
        processScrollPane.preferredSize = Dimension(
            0,
            processTable.rowHeight * processModel.rowCount + headerHeight + THIN_SCROLLBAR_WIDTH + 2,
        )
        processScrollPane.minimumSize = processScrollPane.preferredSize
        return addSpanning(row, processScrollPane, top = 0, bottom = 2)
    }

    /**
     * Sizes each process column from the widest string it currently shows. `AUTO_RESIZE_OFF` keeps
     * that width instead of squeezing the columns into the sidebar, which is what makes the
     * horizontal scrollbar meaningful; the minimums keep the columns readable when every name is
     * short.
     *
     * A column the user dragged is no longer measured: the width chosen by hand wins for the rest
     * of the panel's life, otherwise the next sample (about every two seconds) would snap it back.
     */
    private fun measureProcessColumns() {
        val minimums = intArrayOf(PROCESS_NAME_MIN_WIDTH, PROCESS_MEMORY_MIN_WIDTH, PROCESS_CPU_MIN_WIDTH)
        applyingProcessColumnWidths = true
        try {
            for (column in 0 until processTable.columnModel.columnCount) {
                val applied = userProcessColumnWidths[column] ?: measuredProcessColumnWidth(column, minimums[column])
                applyProcessColumnWidth(column, applied)
            }
        } finally {
            applyingProcessColumnWidths = false
        }
    }

    /** Width that fits the column header and every string the column currently shows. */
    private fun measuredProcessColumnWidth(column: Int, minimum: Int): Int {
        val metrics = processTable.getFontMetrics(processTable.font)
        var widest = metrics.stringWidth(processTable.getColumnName(column))
        for (row in 0 until processModel.rowCount) {
            val value = processModel.getValueAt(row, column)?.toString().orEmpty()
            widest = maxOf(widest, metrics.stringWidth(value))
        }
        return maxOf(minimum, widest + PROCESS_COLUMN_PADDING)
    }

    private fun applyProcessColumnWidth(column: Int, width: Int) {
        processTable.columnModel.getColumn(column).apply {
            preferredWidth = width
            this.width = width
        }
        lastProcessColumnWidths[column] = width
    }

    /**
     * Double-clicking a column header restores that column to its content width, which is the
     * discoverable way back from a hand-dragged width without restarting the sidebar.
     */
    private fun autoFitProcessColumn(column: Int) {
        val minimums = intArrayOf(PROCESS_NAME_MIN_WIDTH, PROCESS_MEMORY_MIN_WIDTH, PROCESS_CPU_MIN_WIDTH)
        userProcessColumnWidths.remove(column)
        applyingProcessColumnWidths = true
        try {
            applyProcessColumnWidth(column, measuredProcessColumnWidth(column, minimums[column]))
        } finally {
            applyingProcessColumnWidths = false
        }
        processScrollPane.revalidate()
        processScrollPane.repaint()
    }

    private fun addTopSpacer(row: Int): Int {
        content.add(
            Box.createVerticalStrut(TOP_SPACER_HEIGHT),
            constraints(gridx = 0, gridy = row, gridwidth = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL),
        )
        return row + 1
    }

    private fun addTitle(row: Int): Int {
        val header = JPanel(BorderLayout()).apply {
            name = "monitorHeader"
            isOpaque = false
        }
        header.add(
            JLabel(I18n.t("monitor.title")).apply {
                font = titleFont()
            },
            BorderLayout.WEST,
        )
        header.add(createSettingsButton(), BorderLayout.EAST)
        content.add(
            header,
            constraints(gridx = 0, gridy = row, gridwidth = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL),
        )
        return row + 1
    }

    private fun createSettingsButton(): JButton = JButton(GearIcon(GEAR_ICON_SIZE)).apply {
        name = "monitorSettingsButton"
        toolTipText = I18n.t("monitor.settings")
        accessibleContext.accessibleName = I18n.t("monitor.settings")
        isFocusable = false
        isContentAreaFilled = false
        isBorderPainted = false
        isOpaque = false
        margin = Insets(0, 2, 0, 0)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isEnabled = onOpenSettings != null
        addActionListener { onOpenSettings?.invoke() }
    }

    private class GearIcon(private val diameter: Int) : Icon {
        override fun getIconWidth(): Int = diameter

        override fun getIconHeight(): Int = diameter

        override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = component?.foreground ?: Color.GRAY
                val centerX = x + diameter / 2.0
                val centerY = y + diameter / 2.0
                val outer = diameter * 0.48
                val root = diameter * 0.34
                val teeth = Path2D.Double()
                repeat(TEETH * 2) { index ->
                    val angle = Math.PI * index / TEETH
                    val radius = if (index % 2 == 0) outer else root
                    val pointX = centerX + radius * Math.cos(angle)
                    val pointY = centerY + radius * Math.sin(angle)
                    if (index == 0) teeth.moveTo(pointX, pointY) else teeth.lineTo(pointX, pointY)
                }
                teeth.closePath()
                val hole = Ellipse2D.Double(centerX - diameter * 0.16, centerY - diameter * 0.16, diameter * 0.32, diameter * 0.32)
                val shape = Area(teeth).apply { subtract(Area(hole)) }
                g.fill(shape)
            } finally {
                g.dispose()
            }
        }
    }

    private fun addSeparator(row: Int): Int {
        content.add(
            JPanel().apply {
                isOpaque = false
                border = BorderFactory.createMatteBorder(1, 0, 0, 0, dividerColor())
                preferredSize = Dimension(0, 1)
            },
            constraints(
                gridx = 0,
                gridy = row,
                gridwidth = 2,
                weightx = 1.0,
                fill = GridBagConstraints.HORIZONTAL,
                insets = Insets(10, 0, 8, 0),
            ),
        )
        return row + 1
    }

    private fun addHeading(row: Int, text: String): Int {
        content.add(
            JLabel(text).apply {
                font = headingFont()
                foreground = mutedForeground()
            },
            constraints(
                gridx = 0,
                gridy = row,
                gridwidth = 2,
                weightx = 1.0,
                fill = GridBagConstraints.HORIZONTAL,
                insets = Insets(2, 0, 4, 0),
            ),
        )
        return row + 1
    }

    private fun addHeadingWithControl(row: Int, text: String, control: Component): Int {
        content.add(
            JLabel(text).apply {
                font = headingFont()
                foreground = mutedForeground()
            },
            constraints(gridx = 0, gridy = row, weightx = 0.0, insets = Insets(2, 0, 4, 6)),
        )
        content.add(
            control,
            constraints(
                gridx = 1,
                gridy = row,
                weightx = 1.0,
                fill = GridBagConstraints.NONE,
                anchor = GridBagConstraints.EAST,
                insets = Insets(0, 0, 2, 0),
            ),
        )
        return row + 1
    }

    private fun addDetail(
        row: Int,
        label: String,
        value: String,
        top: Int = 3,
        bottom: Int = 3,
        tooltip: String? = value,
        warning: Boolean = false,
        copyable: Boolean = false,
    ): Int {
        val labelComponent = rowLabel(label)
        val valueComponent = JLabel(value).apply {
            toolTipText = tooltip
            if (warning) foreground = WARNING_COLOR
        }
        if (copyable) {
            makeCopyable(labelComponent, value, valueComponent)
            makeCopyable(valueComponent, value, valueComponent)
        }
        content.add(
            labelComponent,
            constraints(gridx = 0, gridy = row, weightx = 0.0, insets = Insets(top, 0, bottom, 8)),
        )
        content.add(
            valueComponent,
            constraints(
                gridx = 1,
                gridy = row,
                weightx = 1.0,
                fill = GridBagConstraints.HORIZONTAL,
                insets = Insets(top, 0, bottom, 0),
            ),
        )
        return row + 1
    }

    /**
     * Turns a label into a copy target: a left click puts [value] on the clipboard and briefly
     * tints [feedback] so the user sees that the copy happened.
     */
    private fun makeCopyable(component: JComponent, value: String, feedback: JLabel) {
        val restingForeground = feedback.foreground
        component.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        component.toolTipText = "${UiText.CLICK_TO_COPY}: $value"
        component.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) return
                if (!copyToClipboard(value)) return
                feedback.foreground = COPIED_COLOR
                Timer(COPY_FEEDBACK_MILLIS) { feedback.foreground = restingForeground }
                    .apply { isRepeats = false }
                    .start()
            }
        })
    }

    private fun addMeter(row: Int, label: String, percent: Double, color: Color, detail: String?): Int {
        var current = row
        content.add(
            rowLabel(label),
            constraints(gridx = 0, gridy = current, weightx = 0.0, insets = Insets(5, 0, 0, 8)),
        )
        content.add(
            JProgressBar(0, 100).apply {
                value = percent.coerceIn(0.0, 100.0).toInt()
                isStringPainted = true
                string = formatPercent(percent)
                foreground = color
                preferredSize = Dimension(0, 16)
            },
            constraints(
                gridx = 1,
                gridy = current,
                weightx = 1.0,
                fill = GridBagConstraints.HORIZONTAL,
                insets = Insets(5, 0, 0, 0),
            ),
        )
        current++
        if (detail != null) {
            content.add(
                mutedLabel(detail),
                constraints(
                    gridx = 1,
                    gridy = current,
                    weightx = 1.0,
                    fill = GridBagConstraints.HORIZONTAL,
                    insets = Insets(2, 0, 4, 0),
                ),
            )
            current++
        }
        return current
    }

    private fun addSpanning(row: Int, component: Component, top: Int, bottom: Int): Int {
        content.add(
            component,
            constraints(
                gridx = 0,
                gridy = row,
                gridwidth = 2,
                weightx = 1.0,
                fill = GridBagConstraints.HORIZONTAL,
                insets = Insets(top, 0, bottom, 0),
            ),
        )
        return row + 1
    }

    private fun addVerticalFiller(row: Int) {
        content.add(
            JPanel().apply { isOpaque = false },
            constraints(gridx = 0, gridy = row, gridwidth = 2, weightx = 1.0, weighty = 1.0, fill = GridBagConstraints.BOTH),
        )
    }

    private fun rowLabel(text: String): JLabel = JLabel(text).apply {
        foreground = mutedForeground()
        font = baseFont()
        val width = getFontMetrics(font).stringWidth("Memory") + 10
        val height = preferredSize.height
        preferredSize = Dimension(width, height)
        minimumSize = Dimension(width, height)
    }

    private fun mutedLabel(text: String): JLabel = JLabel(text).apply {
        foreground = mutedForeground()
        font = baseFont()
    }

    /**
     * One type scale for the whole sidebar: the title is the only size above the UI base font, and
     * secondary text is separated by colour instead of a size step so nothing looks mismatched.
     */
    private fun baseFont(): Font = UIManager.getFont("Label.font") ?: font

    private fun titleFont(): Font = baseFont().let { base -> base.deriveFont(Font.BOLD, base.size2D + 2f) }

    private fun headingFont(): Font = baseFont().deriveFont(Font.BOLD)

    private fun mutedForeground(): Color =
        UIManager.getColor("Label.disabledForeground") ?: Color(0x9E9E9E)

    private fun dividerColor(): Color =
        UIManager.getColor("Component.borderColor") ?: Color(0x4A4D4F)

    private fun constraints(
        gridx: Int,
        gridy: Int,
        gridwidth: Int = 1,
        weightx: Double = 0.0,
        weighty: Double = 0.0,
        fill: Int = GridBagConstraints.NONE,
        anchor: Int = GridBagConstraints.WEST,
        insets: Insets = Insets(0, 0, 0, 0),
    ): GridBagConstraints = GridBagConstraints().apply {
        this.gridx = gridx
        this.gridy = gridy
        this.gridwidth = gridwidth
        this.weightx = weightx
        this.weighty = weighty
        this.fill = fill
        this.anchor = anchor
        this.insets = insets
    }

    private fun tableConstraints(
        gridx: Int,
        gridy: Int,
        weightx: Double,
        anchor: Int,
    ): GridBagConstraints = constraints(
        gridx = gridx,
        gridy = gridy,
        weightx = weightx,
        fill = GridBagConstraints.HORIZONTAL,
        anchor = anchor,
        insets = Insets(1, 0, 1, 8),
    )

    private fun alignedCellRenderer(alignment: Int): DefaultTableCellRenderer = object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column).apply {
            horizontalAlignment = alignment
        }
    }

    private fun alignedHeaderRenderer(alignment: Int): DefaultTableCellRenderer = object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column).apply {
            horizontalAlignment = alignment
            font = headingFont()
            foreground = mutedForeground()
        }
    }

    private fun formatSize(bytes: Long): String {
        val gigabytes = bytes.toDouble() / GIB
        if (gigabytes >= 1.0) return "${SIZE_FORMAT.format(gigabytes)} GB"
        val megabytes = bytes.toDouble() / MIB
        if (megabytes >= 1.0) return "${SIZE_FORMAT.format(megabytes)} MB"
        val kilobytes = bytes.toDouble() / KIB
        if (kilobytes >= 1.0) return "${SIZE_FORMAT.format(kilobytes)} KB"
        return "$bytes B"
    }

    private fun formatRate(bytesPerSecond: Long): String = formatSize(bytesPerSecond) + "/s"

    private fun formatPercent(percent: Double): String = PERCENT_FORMAT.format(percent) + "%"

    private fun formatUptime(seconds: Long): String {
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        return if (days > 0) "${days}d ${hours}h ${minutes}m" else "${hours}h ${minutes}m"
    }

    private fun elide(text: String, limit: Int): String =
        if (text.length <= limit) text else text.take(limit - 1) + "…"

    private class ReadOnlyTableModel(columns: Array<String>) : DefaultTableModel(columns, 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }

    companion object {
        private val SIZE_FORMAT = DecimalFormat("#,##0.0")
        private val PERCENT_FORMAT = DecimalFormat("0.0")
        private const val GIB = 1024.0 * 1024.0 * 1024.0
        private const val MIB = 1024.0 * 1024.0
        private const val KIB = 1024.0
        private const val MAX_PROCESS_ROWS = 6
        private const val PROCESS_NAME_MIN_WIDTH = 108
        private const val PROCESS_MEMORY_MIN_WIDTH = 74
        private const val PROCESS_CPU_MIN_WIDTH = 48
        private const val PROCESS_COLUMN_PADDING = 18
        private const val MAX_FILESYSTEM_ROWS = 4
        private const val MAX_MOUNT_CHARS = 14
        private const val LOOPBACK_INTERFACE = "lo"
        private const val THIN_SCROLLBAR_WIDTH = 9
        private const val COPY_FEEDBACK_MILLIS = 1200
        private const val EMPTY_VALUE = "—"
        private const val GEAR_ICON_SIZE = 14
        private const val TEETH = 8
        private const val TOP_SPACER_HEIGHT = 6
        private val CPU_COLOR = Color(0x4C9AFF)
        private val MEMORY_COLOR = Color(0xB08CFF)
        private val SWAP_COLOR = Color(0xE0A458)
        private val WARNING_COLOR = Color(0xE0A458)
        private val COPIED_COLOR = Color(0x66BB6A)
    }
}

/**
 * Grid content that follows the viewport width but keeps its own height so a short window can
 * scroll the sidebar instead of clipping the lower sections.
 */
private class ScrollableContent : JPanel(GridBagLayout()), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 16

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        (if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width) - 16

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false
}
