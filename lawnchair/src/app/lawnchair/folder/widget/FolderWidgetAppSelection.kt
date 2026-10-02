package app.lawnchair.folder.widget

import com.android.launcher3.util.ComponentKey

object FolderWidgetAppSelection {
    /**
     * Diffs current items vs selected items:
     * returns (toAdd in selected order, toRemove in current order).
     */
    fun diff(
        current: List<ComponentKey>,
        selected: List<ComponentKey>,
    ): Pair<List<ComponentKey>, List<ComponentKey>> {
        val currentSet = current.toSet()
        val selectedSet = selected.toSet()
        val toAdd = selected.filter { it !in currentSet }
        val toRemove = current.filter { it !in selectedSet }
        return toAdd to toRemove
    }
}
