package app.lawnchair.folder

import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo

/**
 * Large folders (XDA-014): a Home screen folder can take 2x2 cells and show its first apps as
 * tappable icons. Only Home screen folders can be large; the dock and app drawer stay 1x1.
 */
object LargeFolders {

    const val SPAN = 2

    /** Up to this many apps are shown directly; with more, the last slot previews the rest. */
    const val DIRECT_SLOTS = 4

    /** Apps the tile draws: the direct slots, then up to four in the preview slot. */
    const val TILE_ICON_COUNT = DIRECT_SLOTS - 1 + 4

    /** Whether a large folder's tile draws the app at [rank], so it needs a full icon. */
    @JvmStatic
    fun drawsRank(rank: Int): Boolean = rank in 0 until TILE_ICON_COUNT

    @JvmStatic
    fun isLarge(info: ItemInfo?): Boolean =
        info is FolderInfo && wantsLarge(info.container, info.spanX, info.spanY)

    /** Whether a stored folder row asks to be large. Anything else loads as 1x1. */
    @JvmStatic
    fun wantsLarge(container: Int, spanX: Int, spanY: Int): Boolean =
        container == Favorites.CONTAINER_DESKTOP && spanX == SPAN && spanY == SPAN

    /**
     * Top-left cell of a free 2x2 area that contains ([cellX], [cellY]), or null. Prefers growing
     * right and down, then left, then up, so the folder stays where the user put it.
     * [occupied] must ignore the folder's own cell.
     */
    @JvmStatic
    fun findLargeAnchor(
        cellX: Int,
        cellY: Int,
        countX: Int,
        countY: Int,
        occupied: (x: Int, y: Int) -> Boolean,
    ): IntArray? {
        val candidates = listOf(
            cellX to cellY,
            cellX - 1 to cellY,
            cellX to cellY - 1,
            cellX - 1 to cellY - 1,
        )
        for ((x, y) in candidates) {
            if (x < 0 || y < 0 || x + SPAN > countX || y + SPAN > countY) continue
            var free = true
            for (dx in 0 until SPAN) {
                for (dy in 0 until SPAN) {
                    if (occupied(x + dx, y + dy)) free = false
                }
            }
            if (free) return intArrayOf(x, y)
        }
        return null
    }

    /**
     * What each of the 2x2 slots of a large folder with [itemCount] apps shows: an app index, or
     * [SLOT_MORE] for the slot that previews the remaining apps and opens the folder, or
     * [SLOT_EMPTY].
     */
    @JvmStatic
    fun slotContents(itemCount: Int): IntArray = IntArray(DIRECT_SLOTS) { slot ->
        when {
            itemCount > DIRECT_SLOTS && slot == DIRECT_SLOTS - 1 -> SLOT_MORE
            slot < itemCount -> slot
            else -> SLOT_EMPTY
        }
    }

    /** Slot (0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right) at a point in the tile. */
    @JvmStatic
    fun slotAt(x: Float, y: Float, left: Float, top: Float, size: Float): Int {
        if (x < left || y < top || x >= left + size || y >= top + size) return -1
        val column = if (x < left + size / 2) 0 else 1
        val row = if (y < top + size / 2) 0 else 1
        return row * 2 + column
    }

    const val SLOT_MORE = -1
    const val SLOT_EMPTY = -2
}
