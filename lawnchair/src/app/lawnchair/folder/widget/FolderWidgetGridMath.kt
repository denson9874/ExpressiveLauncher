package app.lawnchair.folder.widget

import android.graphics.Point
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Sizes the grid inside a Folder widget panel. [headerHeightPx] is 0 when the header is hidden. */
data class FolderWidgetGridInput(
    val widthPx: Int,
    val heightPx: Int,
    val spanX: Int,
    val spanY: Int = 2,
    val itemCount: Int,
    val paddingPx: Int,
    val headerHeightPx: Int,
    val folderCellWidthPx: Int = 195,
    val folderCellHeightPx: Int = 230,
    val folderChildIconSizePx: Int = 147,
    val folderChildDrawablePaddingPx: Int = 4,
    val folderBorderSpacePx: Point = Point(0, 0),
    val numFolderColumns: Int = 4,
    val numFolderRows: Int = 4,
    val labelHeightPx: Int,
    val minTouchPx: Int,
    val columnOverride: Int? = null,
    val showLabels: Boolean = true,
    val iconScale: Float = 1f,
    // Legacy compatibility properties
    val homeIconSizePx: Int = folderChildIconSizePx,
    val iconLabelGapPx: Int = folderChildDrawablePaddingPx,
    val rowSpacingPx: Int = 0,
)

data class FolderWidgetGridSpec(
    val columns: Int,
    val columnWidthPx: Int,
    val iconSizePx: Int,
    val labelsVisible: Boolean,
    val rowHeightPx: Int,
    val cellContentHeightPx: Int,
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

    /** Legacy helper for callers with only spanX. */
    @JvmStatic
    fun autoColumns(spanX: Int): Int = (spanX * 1.5).roundToInt().coerceIn(1, MAX_COLUMNS)

    /**
     * Adapts column count to item count and widget span, matching normal folder
     * behavior in Pixel Launcher and Expressive.
     */
    @JvmStatic
    fun autoColumns(spanX: Int, spanY: Int, itemCount: Int, maxAllowedColumns: Int): Int {
        if (spanX <= 1) return 1
        val maxColsForSpan = (spanX * 1.5).roundToInt().coerceIn(1, maxAllowedColumns)

        if (spanY == 1) {
            // For a single-row widget (e.g. 2x1, 3x1, 4x1), lay apps out across the single row
            return when {
                itemCount <= 0 -> maxColsForSpan
                itemCount <= maxColsForSpan -> itemCount.coerceAtLeast(1)
                else -> maxColsForSpan
            }
        }
        // Multi-row widget (e.g. 2x2, 3x2, 4x2): adapt dynamically to app count,
        // matching FolderGridOrganizer
        return when {
            itemCount <= 2 -> 2.coerceAtMost(maxColsForSpan)
            itemCount in 3..4 -> 2.coerceAtMost(maxColsForSpan) // 1-4 apps: 2x2 square layout like normal folder
            itemCount in 5..6 -> 3.coerceAtMost(maxColsForSpan) // 5-6 apps: 3x2 layout (2 rows)
            itemCount in 7..9 -> 3.coerceAtMost(maxColsForSpan) // 7-9 apps: 3x3 layout
            else -> 4.coerceAtMost(maxColsForSpan)              // 10+ apps: 4 columns if span allows
        }
    }

    /**
     * Computes folder widget grid layout using normal folder dimensions from DeviceProfile,
     * adapting to app count, keeping icon spacing true to normal folder proportions,
     * and strictly preventing app icon / label clipping at the bottom.
     */
    @JvmStatic
    fun compute(input: FolderWidgetGridInput): FolderWidgetGridSpec = with(input) {
        val innerWidth = max(0, widthPx - 2 * paddingPx)
        val viewport = max(0, heightPx - 2 * paddingPx - headerHeightPx)

        val wantedColumns = columnOverride?.coerceIn(MIN_COLUMN_OVERRIDE, MAX_COLUMNS)
            ?: autoColumns(spanX, spanY, itemCount, numFolderColumns)
        val columns = (wantedColumns downTo 1).firstOrNull { innerWidth / it >= minTouchPx } ?: 1
        val rows = max(1, (max(0, itemCount) + columns - 1) / columns)

        val borderX = folderBorderSpacePx.x
        val borderY = folderBorderSpacePx.y

        // Determine how many rows should fit on screen without scrolling.
        // For widgets with rows <= spanY (or single-row widgets), all rows must fit inside the viewport.
        val visibleRowsToFit = when {
            spanY == 1 -> 1
            rows <= spanY -> rows
            else -> spanY
        }
        val totalGutterY = (visibleRowsToFit - 1) * borderY
        val maxRowHeightToFit = max(1, (viewport - totalGutterY) / visibleRowsToFit)

        // Target row height: bounded by maxRowHeightToFit so nothing overflows or gets cut off at the bottom.
        val targetRowHeight = min(folderCellHeightPx, maxRowHeightToFit)

        // Spacing calculations:
        val minVerticalMargin = max(4, (targetRowHeight * 0.06f).roundToInt())

        // Calculate icon size from folderChildIconSizePx scaled by targetRowHeight / folderCellHeightPx
        val heightScale = if (folderCellHeightPx > 0) {
            min(1f, targetRowHeight.toFloat() / folderCellHeightPx)
        } else {
            1f
        }
        var icon = (folderChildIconSizePx * heightScale * iconScale).roundToInt()

        // Column width:
        // Match normal folderCellWidthPx when available width allows, avoiding artificial horizontal stretching.
        val normalWidthWithGutters = columns * folderCellWidthPx + (columns - 1) * borderX
        val columnWidth = if (normalWidthWithGutters in 1..innerWidth) {
            folderCellWidthPx
        } else {
            max(1, (innerWidth - (columns - 1) * borderX) / columns)
        }

        // Icon cannot exceed 85% of columnWidth:
        val maxIconForCol = (0.85f * columnWidth).toInt()
        if (icon > maxIconForCol) {
            icon = max(1, maxIconForCol)
        }

        // Drawable padding between icon and label:
        val drawablePadding = max(2, (folderChildDrawablePaddingPx * heightScale).roundToInt())

        // Check if labels can fit:
        val minLabeledRowHeight = (folderChildIconSizePx * 0.5f).roundToInt() + drawablePadding + labelHeightPx + 2 * minVerticalMargin
        val labelsVisible = showLabels && (spanY > 1 || targetRowHeight >= minLabeledRowHeight) && (targetRowHeight >= minLabeledRowHeight)

        // Clamp icon size so cellContentHeight + 2 * minVerticalMargin <= targetRowHeight ALWAYS
        val cellContentHeight: Int
        if (labelsVisible) {
            val maxIconForLabeled = max(1, targetRowHeight - 2 * minVerticalMargin - drawablePadding - labelHeightPx)
            if (icon > maxIconForLabeled) {
                icon = maxIconForLabeled
            }
            cellContentHeight = icon + drawablePadding + labelHeightPx
        } else {
            val maxIconForUnlabeled = max(1, targetRowHeight - 2 * minVerticalMargin)
            if (icon > maxIconForUnlabeled) {
                icon = maxIconForUnlabeled
            }
            cellContentHeight = icon
        }

        val actualGridWidth = columns * columnWidth + (columns - 1) * borderX
        val contentHeight = if (itemCount > 0) rows * targetRowHeight + (rows - 1) * borderY else 0
        val scrollable = contentHeight > viewport

        FolderWidgetGridSpec(
            columns = columns,
            columnWidthPx = columnWidth,
            iconSizePx = icon,
            labelsVisible = labelsVisible,
            rowHeightPx = targetRowHeight,
            cellContentHeightPx = cellContentHeight,
            gridLeftPx = paddingPx + max(0, (innerWidth - actualGridWidth) / 2),
            gridTopPx = paddingPx + headerHeightPx,
            gridWidthPx = actualGridWidth,
            viewportHeightPx = viewport,
            contentHeightPx = contentHeight,
            scrollable = scrollable,
        )
    }
}

