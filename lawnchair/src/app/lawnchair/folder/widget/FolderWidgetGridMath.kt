package app.lawnchair.folder.widget

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Sizes the grid inside a Folder widget panel. [headerHeightPx] is 0 when the header is hidden. */
data class FolderWidgetGridInput(
    val widthPx: Int,
    val heightPx: Int,
    val spanX: Int,
    val itemCount: Int,
    val paddingPx: Int,
    val headerHeightPx: Int,
    val homeIconSizePx: Int,
    val labelHeightPx: Int,
    val iconLabelGapPx: Int,
    val rowSpacingPx: Int,
    val minTouchPx: Int,
    val columnOverride: Int? = null,
    val showLabels: Boolean = true,
    val iconScale: Float = 1f,
)

data class FolderWidgetGridSpec(
    val columns: Int,
    val columnWidthPx: Int,
    val iconSizePx: Int,
    val labelsVisible: Boolean,
    val rowHeightPx: Int,
    val gridLeftPx: Int,
    val gridTopPx: Int,
    val gridWidthPx: Int,
    val viewportHeightPx: Int,
    val contentHeightPx: Int,
    val scrollable: Boolean,
)

object FolderWidgetGridMath {
    const val MAX_COLUMNS = 6
    const val MIN_COLUMN_OVERRIDE = 2

    /** About one and a half apps per Home cell. */
    @JvmStatic
    fun autoColumns(spanX: Int): Int = (spanX * 1.5).roundToInt().coerceIn(1, MAX_COLUMNS)

    /**
     * Columns drop until each is at least [FolderWidgetGridInput.minTouchPx] wide, icons are capped
     * by the Home icon size and three quarters of a column, labels hide when a labeled row doesn't
     * fit, and the grid is centered: horizontally always, vertically when it doesn't scroll.
     */
    @JvmStatic
    fun compute(input: FolderWidgetGridInput): FolderWidgetGridSpec = with(input) {
        val innerWidth = max(0, widthPx - 2 * paddingPx)
        val viewport = max(0, heightPx - 2 * paddingPx - headerHeightPx)
        val wantedColumns = columnOverride?.coerceIn(MIN_COLUMN_OVERRIDE, MAX_COLUMNS) ?: autoColumns(spanX)
        val columns = (wantedColumns downTo 1).firstOrNull { innerWidth / it >= minTouchPx } ?: 1
        val columnWidth = innerWidth / columns
        var icon = (min(homeIconSizePx, (0.75 * columnWidth).toInt()) * iconScale).toInt()
        val labeledRow = icon + iconLabelGapPx + labelHeightPx
        val labelsVisible = showLabels && labeledRow <= viewport
        if (!labelsVisible && icon > viewport) icon = viewport
        val rowHeight = (if (labelsVisible) labeledRow else icon) + rowSpacingPx
        val rows = (max(0, itemCount) + columns - 1) / columns
        val content = rows * rowHeight
        // The last row's spacing isn't content: a single row that fits doesn't scroll.
        val scrollable = content - rowSpacingPx > viewport
        val gridWidth = columns * columnWidth
        FolderWidgetGridSpec(
            columns = columns,
            columnWidthPx = columnWidth,
            iconSizePx = icon,
            labelsVisible = labelsVisible,
            rowHeightPx = rowHeight,
            gridLeftPx = paddingPx + (innerWidth - gridWidth) / 2,
            gridTopPx = paddingPx + headerHeightPx,
            gridWidthPx = gridWidth,
            viewportHeightPx = viewport,
            contentHeightPx = content,
            scrollable = scrollable,
        )
    }
}
