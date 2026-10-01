package app.lawnchair.folder

import android.view.View
import com.android.launcher3.CellLayout
import com.android.launcher3.R
import com.android.launcher3.celllayout.CellLayoutLayoutParams
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.widget.LauncherAppWidgetHostView

/** Large folders v2: CellLayout glue for large folders drawn over widgets. */
object LargeFolderLayout {

    @JvmStatic
    fun rectOf(view: View): CellRect {
        val lp = view.layoutParams as CellLayoutLayoutParams
        return CellRect(lp.cellX, lp.cellY, lp.cellHSpan, lp.cellVSpan)
    }

    private fun children(cellLayout: CellLayout): List<View> {
        val container = cellLayout.shortcutsAndWidgets
        return (0 until container.childCount).map { container.getChildAt(it) }
    }

    /** Who holds ([x], [y]) for a large folder; [self] (the folder being placed) counts as empty. */
    @JvmStatic
    fun ownerAt(cellLayout: CellLayout, self: View?, x: Int, y: Int): CellOwner {
        var owner = CellOwner.EMPTY
        for (child in children(cellLayout)) {
            if (child === self || child.layoutParams !is CellLayoutLayoutParams) continue
            if (!rectOf(child).contains(x, y)) continue
            if (child !is LauncherAppWidgetHostView) return CellOwner.BLOCKING
            owner = CellOwner.WIDGET
        }
        return owner
    }

    /** Re-marks items that overlap [unmarked] after its cells were cleared. */
    @JvmStatic
    fun remarkOverlapping(cellLayout: CellLayout, unmarked: View) {
        if (unmarked.layoutParams !is CellLayoutLayoutParams) return
        val others = children(cellLayout).filter { it !== unmarked && it.layoutParams is CellLayoutLayoutParams }
        val toRemark = LargeFolderOverlap.cellsToRemark(rectOf(unmarked), others.map(::rectOf))
        others.filter { rectOf(it) in toRemark }.forEach { cellLayout.markCellsForViewRaw(it, true) }
    }

    /**
     * Locks a large folder and the widgets it covers against reorder, so neither is pushed while
     * something else is dragged. Only locks set here are released here.
     */
    @JvmStatic
    fun refresh(cellLayout: CellLayout) {
        val all = children(cellLayout).filter { it.layoutParams is CellLayoutLayoutParams }
        val folders = all.filter { it is FolderIcon && it.isLarge }
        val widgets = all.filterIsInstance<LauncherAppWidgetHostView>()
        for (view in all) {
            val lp = view.layoutParams as CellLayoutLayoutParams
            val rect = rectOf(view)
            val overlapped = when (view) {
                in folders -> widgets.any { rectOf(it).intersects(rect) }
                is LauncherAppWidgetHostView -> folders.any { rectOf(it).intersects(rect) }
                else -> false
            }
            val lockedHere = view.getTag(R.id.large_folder_reorder_lock) == true
            if (overlapped && lp.canReorder) {
                lp.canReorder = false
                view.setTag(R.id.large_folder_reorder_lock, true)
            } else if (!overlapped && lockedHere) {
                lp.canReorder = true
                view.setTag(R.id.large_folder_reorder_lock, null)
            }
        }
    }
}
