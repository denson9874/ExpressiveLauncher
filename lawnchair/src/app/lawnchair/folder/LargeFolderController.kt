package app.lawnchair.folder

import android.widget.Toast
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.R
import com.android.launcher3.celllayout.CellLayoutLayoutParams
import com.android.launcher3.folder.FolderIcon

/** Switches a Home screen folder between 1x1 and large (2x2). */
object LargeFolderController {

    /** Whether [icon] can switch size: Home screen folders only. */
    @JvmStatic
    fun canResize(icon: FolderIcon?): Boolean = icon?.mInfo?.container == Favorites.CONTAINER_DESKTOP

    /**
     * Makes a 1x1 folder large, growing into free neighbouring cells, or a large folder 1x1 at its
     * top-left cell. Shows a message and returns false when there's no free 2x2 space.
     */
    @JvmStatic
    fun toggle(launcher: Launcher, icon: FolderIcon): Boolean {
        val info = icon.mInfo
        if (!canResize(icon)) return false
        val cellLayout = launcher.workspace.getScreenWithId(info.screenId) ?: return false
        val lp = icon.layoutParams as? CellLayoutLayoutParams ?: return false

        val (x, y, span) = if (LargeFolders.isLarge(info)) {
            Triple(info.cellX, info.cellY, 1)
        } else {
            val anchor = LargeFolders.findLargeAnchor(
                info.cellX,
                info.cellY,
                cellLayout.countX,
                cellLayout.countY,
            ) { cx, cy -> !(cx == info.cellX && cy == info.cellY) && cellLayout.isOccupied(cx, cy) }
            if (anchor == null) {
                Toast.makeText(launcher, R.string.large_folder_no_space, Toast.LENGTH_SHORT).show()
                return false
            }
            Triple(anchor[0], anchor[1], LargeFolders.SPAN)
        }

        cellLayout.markCellsAsUnoccupiedForView(icon)
        lp.setCellX(x)
        lp.setCellY(y)
        lp.setTmpCellX(x)
        lp.setTmpCellY(y)
        lp.cellHSpan = span
        lp.cellVSpan = span
        launcher.modelWriter.modifyItemInDatabase(info, info.container, info.screenId, x, y, span, span)
        cellLayout.markCellsAsOccupiedForView(icon)
        icon.onSizeModeChanged()
        icon.announceForAccessibility(
            launcher.getString(
                if (span > 1) R.string.large_folder_made_large else R.string.large_folder_made_small,
            ),
        )
        return true
    }
}
