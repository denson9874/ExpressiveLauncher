package app.lawnchair.folder.widget

/** Cell-by-cell resize steps for a Folder widget, with the rules of AppWidgetResizeFrame. */
object FolderWidgetResizeMath {

    /**
     * Moves the dragged edges by [hDelta]/[vDelta] whole cells. Per axis, as
     * AppWidgetResizeFrame.IntRange.applyDeltaAndBound with a minimum of one cell and the grid as
     * the maximum size and end. A result smaller than two cells is refused: [current] is returned.
     */
    @JvmStatic
    fun step(
        current: GridRect,
        left: Boolean,
        top: Boolean,
        right: Boolean,
        bottom: Boolean,
        hDelta: Int,
        vDelta: Int,
        columns: Int,
        rows: Int,
    ): GridRect {
        val (x, spanX) = applyDeltaAndBound(current.x, current.x + current.spanX, left, right, hDelta, columns)
        val (y, spanY) = applyDeltaAndBound(current.y, current.y + current.spanY, top, bottom, vDelta, rows)
        val next = GridRect(x, y, spanX, spanY)
        return if (FolderWidgets.isValidSpan(next.spanX, next.spanY)) next else current
    }

    /** Returns the new start and size of the range [start, end). */
    private fun applyDeltaAndBound(
        start: Int,
        end: Int,
        moveStart: Boolean,
        moveEnd: Boolean,
        delta: Int,
        gridSize: Int,
    ): Pair<Int, Int> {
        val minSize = 1
        val maxSize = gridSize
        var s = if (moveStart) start + delta else start
        var e = if (moveEnd) end + delta else end
        if (s < 0) s = 0
        if (e > gridSize) e = gridSize
        if (e - s < minSize) {
            if (moveStart) s = e - minSize else if (moveEnd) e = s + minSize
        }
        if (e - s > maxSize) {
            if (moveStart) s = e - maxSize else if (moveEnd) e = s + maxSize
        }
        return s to (e - s)
    }
}
