package app.lawnchair.folder.widget

/** A rectangle of Home cells. */
data class GridRect(val x: Int, val y: Int, val spanX: Int, val spanY: Int)

/** A page (screen id) and the cells a Folder widget takes on it. */
data class Placement(val screenId: Int, val rect: GridRect)

/**
 * Where a loaded Folder widget goes. Folder widgets never overlap other items and are never
 * deleted: they keep their cells, shrink to fit the grid, or move to free cells.
 */
object FolderWidgetPlacement {

    /** Clamps a span to the grid; a single cell becomes 2x2 (or a 1x2/2x1 strip on a one-cell-wide grid). */
    @JvmStatic
    fun normalizeSpan(spanX: Int, spanY: Int, columns: Int, rows: Int): Pair<Int, Int> {
        val maxX = maxOf(1, columns)
        val maxY = maxOf(1, rows)
        val x = spanX.coerceIn(1, maxX)
        val y = spanY.coerceIn(1, maxY)
        if (x * y >= FolderWidgets.MIN_CELLS) return x to y
        return minOf(FolderWidgets.DEFAULT_SPAN, maxX) to minOf(FolderWidgets.DEFAULT_SPAN, maxY)
    }

    /**
     * In order: keep [wanted] when it is inside the grid and vacant; shrink it in place to fit the
     * grid; the nearest vacant area on the same screen; the first vacant area on a later screen in
     * [screens]; a new screen after the last one.
     */
    @JvmStatic
    fun place(
        wanted: GridRect,
        screenId: Int,
        screens: List<Int>,
        columns: Int,
        rows: Int,
        isVacant: (Int, GridRect) -> Boolean,
    ): Placement {
        keepInPlace(wanted, screenId, columns, rows, isVacant)?.let { return it }

        val (spanX, spanY) = normalizeSpan(wanted.spanX, wanted.spanY, columns, rows)
        nearestVacant(screenId, wanted.x, wanted.y, spanX, spanY, columns, rows, isVacant)
            ?.let { return Placement(screenId, it) }

        for (screen in screens) {
            if (screen <= screenId) continue
            firstVacant(screen, spanX, spanY, columns, rows, isVacant)?.let { return Placement(screen, it) }
        }
        val newScreen = maxOf(screenId, screens.maxOrNull() ?: screenId) + 1
        return Placement(newScreen, GridRect(0, 0, spanX, spanY))
    }

    /**
     * Places several widgets, returning their placements in the same order. Widgets whose own
     * cells (or their shrunk-in-place cells) are free keep them first, so a widget that has to move
     * never takes the cells of one that didn't; the rest then move in order with [place], each
     * seeing every widget kept or placed before it, including on a new screen.
     */
    @JvmStatic
    fun placeAll(
        wanted: List<Placement>,
        screens: List<Int>,
        columns: Int,
        rows: Int,
        isVacant: (Int, GridRect) -> Boolean,
    ): List<Placement> {
        val result = arrayOfNulls<Placement>(wanted.size)
        val taken = ArrayList<Placement>(wanted.size)
        fun free(s: Int, r: GridRect) = isVacant(s, r) && taken.none { it.screenId == s && it.rect.overlaps(r) }

        wanted.forEachIndexed { i, w ->
            keepInPlace(w.rect, w.screenId, columns, rows, ::free)?.let {
                result[i] = it
                taken += it
            }
        }
        val knownScreens = screens.sorted().toMutableList()
        wanted.forEachIndexed { i, w ->
            if (result[i] != null) return@forEachIndexed
            val p = place(w.rect, w.screenId, knownScreens, columns, rows, ::free)
            result[i] = p
            taken += p
            if (p.screenId !in knownScreens) {
                knownScreens += p.screenId
                knownScreens.sort()
            }
        }
        return result.map { it!! }
    }

    /** Whether a placed widget's row must be rewritten: its screen, cells, span or options changed. */
    @JvmStatic
    fun needsRewrite(row: Placement, rowOptions: Int, placed: Placement, options: Int): Boolean =
        row != placed || rowOptions != options

    /** Steps 1–2 of [place]: [wanted] itself, or shrunk in place to fit the grid, when free. */
    private fun keepInPlace(
        wanted: GridRect,
        screenId: Int,
        columns: Int,
        rows: Int,
        isVacant: (Int, GridRect) -> Boolean,
    ): Placement? {
        fun fits(r: GridRect) = FolderWidgets.isValidSpan(r.spanX, r.spanY) &&
            r.x >= 0 && r.y >= 0 && r.x + r.spanX <= columns && r.y + r.spanY <= rows &&
            isVacant(screenId, r)

        if (fits(wanted)) return Placement(screenId, wanted)
        val shrunk = GridRect(
            wanted.x,
            wanted.y,
            minOf(wanted.spanX, columns - wanted.x),
            minOf(wanted.spanY, rows - wanted.y),
        )
        return if (fits(shrunk)) Placement(screenId, shrunk) else null
    }

    private fun GridRect.overlaps(o: GridRect) =
        x < o.x + o.spanX && o.x < x + spanX && y < o.y + o.spanY && o.y < y + spanY

    /** The vacant area nearest to (x, y) by squared distance; ties go to the smaller y, then x. */
    private fun nearestVacant(
        screen: Int,
        x: Int,
        y: Int,
        spanX: Int,
        spanY: Int,
        columns: Int,
        rows: Int,
        isVacant: (Int, GridRect) -> Boolean,
    ): GridRect? {
        var best: GridRect? = null
        var bestDistance = Long.MAX_VALUE
        for (cy in 0..rows - spanY) {
            for (cx in 0..columns - spanX) {
                val dx = (cx - x).toLong()
                val dy = (cy - y).toLong()
                val distance = dx * dx + dy * dy
                if (distance >= bestDistance) continue
                val r = GridRect(cx, cy, spanX, spanY)
                if (isVacant(screen, r)) {
                    best = r
                    bestDistance = distance
                }
            }
        }
        return best
    }

    private fun firstVacant(
        screen: Int,
        spanX: Int,
        spanY: Int,
        columns: Int,
        rows: Int,
        isVacant: (Int, GridRect) -> Boolean,
    ): GridRect? {
        for (cy in 0..rows - spanY) {
            for (cx in 0..columns - spanX) {
                val r = GridRect(cx, cy, spanX, spanY)
                if (isVacant(screen, r)) return r
            }
        }
        return null
    }
}
