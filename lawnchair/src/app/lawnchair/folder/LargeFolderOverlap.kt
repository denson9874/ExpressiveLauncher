package app.lawnchair.folder

import com.android.launcher3.LauncherSettings.Favorites

/** A rectangle of grid cells. */
data class CellRect(val x: Int, val y: Int, val spanX: Int, val spanY: Int) {
    fun contains(cx: Int, cy: Int): Boolean = cx in x until x + spanX && cy in y until y + spanY
    fun intersects(o: CellRect): Boolean =
        x < o.x + o.spanX && o.x < x + spanX && y < o.y + o.spanY && o.y < y + spanY
    fun cells(): List<Pair<Int, Int>> =
        (x until x + spanX).flatMap { cx -> (y until y + spanY).map { cy -> cx to cy } }
}

/** What holds a grid cell, as far as a large folder is concerned. */
enum class CellOwner { EMPTY, WIDGET, BLOCKING }

enum class LoadKind { LARGE_FOLDER_CANDIDATE, WIDGET, OTHER }

/** A Home item seen by the loader. Large-folder candidates are given at their 1x1 anchor. */
data class LoadItem(val id: Int, val rect: CellRect, val kind: LoadKind)

/**
 * Large folders v2: a large (2x2) Home folder may cover empty cells and widgets, never icons,
 * folders, app pairs or the search bar. Widgets are never moved for it.
 */
object LargeFolderOverlap {

    @JvmStatic
    fun canPlace(rect: CellRect, countX: Int, countY: Int, ownerAt: (Int, Int) -> CellOwner): Boolean {
        if (rect.x < 0 || rect.y < 0 || rect.x + rect.spanX > countX || rect.y + rect.spanY > countY) {
            return false
        }
        return rect.cells().all { (cx, cy) -> ownerAt(cx, cy) != CellOwner.BLOCKING }
    }

    /**
     * Which stored large folders keep 2x2 once every Home item on the page is known, independent
     * of load order. Candidates are checked in list order; a promoted folder blocks later ones.
     */
    @JvmStatic
    fun resolveLargeFolders(
        countX: Int,
        countY: Int,
        blocked: List<CellRect>,
        items: List<LoadItem>,
    ): Set<Int> {
        val promoted = mutableListOf<CellRect>()
        val result = linkedSetOf<Int>()
        for (candidate in items.filter { it.kind == LoadKind.LARGE_FOLDER_CANDIDATE }) {
            val target = CellRect(candidate.rect.x, candidate.rect.y, LargeFolders.SPAN, LargeFolders.SPAN)
            val ok = canPlace(target, countX, countY) { cx, cy ->
                when {
                    blocked.any { it.contains(cx, cy) } -> CellOwner.BLOCKING
                    promoted.any { it.contains(cx, cy) } -> CellOwner.BLOCKING
                    items.any { it.id != candidate.id && it.kind != LoadKind.WIDGET && it.rect.contains(cx, cy) } ->
                        CellOwner.BLOCKING
                    items.any { it.kind == LoadKind.WIDGET && it.rect.contains(cx, cy) } -> CellOwner.WIDGET
                    else -> CellOwner.EMPTY
                }
            }
            if (ok) {
                promoted += target
                result += candidate.id
            }
        }
        return result
    }

    /** After [unmarked] was cleared from the occupancy grid, these overlapping items must be re-marked. */
    @JvmStatic
    fun cellsToRemark(unmarked: CellRect, others: List<CellRect>): List<CellRect> =
        others.filter { it.intersects(unmarked) }

    @JvmStatic
    fun spanForContainer(container: Int, wantsLarge: Boolean): Int =
        if (wantsLarge && container == Favorites.CONTAINER_DESKTOP) LargeFolders.SPAN else 1

    /** Whether a bound item may share its anchor cell with the item already there. */
    @JvmStatic
    fun isAllowedBindOverlap(
        newIsWidget: Boolean,
        newIsLargeFolder: Boolean,
        occupantIsWidget: Boolean,
        occupantIsLargeFolder: Boolean,
    ): Boolean = (newIsWidget && occupantIsLargeFolder) || (newIsLargeFolder && occupantIsWidget)

    /** The span a dragged item uses over a target: a large folder becomes 1x1 over the dock. */
    @JvmStatic
    fun dragSpan(span: Int, isLargeFolder: Boolean, targetIsHotseat: Boolean): Int =
        if (isLargeFolder && targetIsHotseat) 1 else span

    /**
     * Where a folder that lost its 2x2 size goes when its 1x1 cell is under a widget (it would be
     * hidden there): the first free cell, row by row. Null when it isn't under a widget or the page
     * is full.
     */
    @JvmStatic
    fun relocationFor(folder: CellRect, widgets: List<CellRect>, taken: List<CellRect>, countX: Int, countY: Int): CellRect? {
        if (widgets.none { it.intersects(folder) }) return null
        for (y in 0 until countY) {
            for (x in 0 until countX) {
                if (taken.none { it.contains(x, y) }) return CellRect(x, y, 1, 1)
            }
        }
        return null
    }

    /**
     * The cell a large folder shrinks to (or its last app takes): its top-left cell if empty,
     * otherwise the first other empty cell it covers. A 1x1 item must never sit on a widget: it would
     * be hidden there, and the loader rejects the overlap. Null when every covered cell is a widget's.
     */
    @JvmStatic
    fun oneByOneCell(folder: CellRect, ownerAt: (Int, Int) -> CellOwner): CellRect? =
        folder.cells().firstOrNull { (x, y) -> ownerAt(x, y) == CellOwner.EMPTY }
            ?.let { (x, y) -> CellRect(x, y, 1, 1) }

    /** [relocationFor] for several folders at once; each new cell is taken for the next. */
    @JvmStatic
    fun relocateAll(folders: List<CellRect>, widgets: List<CellRect>, taken: List<CellRect>, countX: Int, countY: Int): List<CellRect?> {
        val soFar = taken.toMutableList()
        return folders.map { folder ->
            relocationFor(folder, widgets, soFar, countX, countY)?.also { soFar += it }
        }
    }
}
