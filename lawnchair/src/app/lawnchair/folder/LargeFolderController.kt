package app.lawnchair.folder

import android.widget.Toast
import com.android.launcher3.CellLayout
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.R
import com.android.launcher3.celllayout.CellLayoutLayoutParams
import com.android.launcher3.folder.FolderIcon

/** Switches a Home screen folder between 1x1 and large (2x2). */
object LargeFolderController {

    @JvmStatic
    fun canResize(icon: FolderIcon?): Boolean = icon?.mInfo?.container == Favorites.CONTAINER_DESKTOP

    /** Footer button / TalkBack: grow right/down first, shrink to the top-left cell. */
    @JvmStatic
    fun toggle(launcher: Launcher, icon: FolderIcon): Boolean {
        val info = icon.mInfo
        if (!canResize(icon)) return false
        val cellLayout = launcher.workspace.getScreenWithId(info.screenId) ?: return false
        val target = if (LargeFolders.isLarge(info)) {
            CellRect(info.cellX, info.cellY, 1, 1)
        } else {
            val anchor = LargeFolders.findLargeAnchor(info.cellX, info.cellY, cellLayout.countX, cellLayout.countY) { x, y ->
                LargeFolderLayout.ownerAt(cellLayout, icon, x, y)
            }
            if (anchor == null) {
                Toast.makeText(launcher, R.string.large_folder_no_space, Toast.LENGTH_SHORT).show()
                return false
            }
            CellRect(anchor[0], anchor[1], LargeFolders.SPAN, LargeFolders.SPAN)
        }
        return applySize(launcher, icon, target)
    }

    /** Moves/resizes [icon] to [target] (1x1 or 2x2), saving it. False if [target] is blocked. */
    @JvmStatic
    fun applySize(launcher: Launcher, icon: FolderIcon, target: CellRect): Boolean {
        val info = icon.mInfo
        val cellLayout = launcher.workspace.getScreenWithId(info.screenId) ?: return false
        val lp = icon.layoutParams as? CellLayoutLayoutParams ?: return false
        if (target.spanX > 1 && !LargeFolderOverlap.canPlace(target, cellLayout.countX, cellLayout.countY) { x, y ->
                LargeFolderLayout.ownerAt(cellLayout, icon, x, y)
            }
        ) {
            return false
        }
        cellLayout.markCellsAsUnoccupiedForView(icon)
        lp.setCellX(target.x)
        lp.setCellY(target.y)
        lp.setTmpCellX(target.x)
        lp.setTmpCellY(target.y)
        lp.cellHSpan = target.spanX
        lp.cellVSpan = target.spanY
        launcher.modelWriter.modifyItemInDatabase(info, info.container, info.screenId, target.x, target.y, target.spanX, target.spanY)
        cellLayout.markCellsAsOccupiedForView(icon)
        icon.onSizeModeChanged()
        icon.announceForAccessibility(
            launcher.getString(if (target.spanX > 1) R.string.large_folder_made_large else R.string.large_folder_made_small),
        )
        return true
    }

    /** Where a dragged large folder lands when it may cover widgets near [nearest], or null. */
    @JvmStatic
    fun overlayDropAnchor(layout: CellLayout, icon: FolderIcon, nearest: IntArray): IntArray? {
        if (!icon.isLarge) return null
        val rect = CellRect(nearest[0], nearest[1], LargeFolders.SPAN, LargeFolders.SPAN)
        return if (LargeFolderOverlap.canPlace(rect, layout.countX, layout.countY) { x, y ->
                LargeFolderLayout.ownerAt(layout, icon, x, y)
            }
        ) nearest.copyOf() else null
    }
}
