package app.lawnchair.folder.widget

import com.android.launcher3.R

/** Accessibility actions for Folder widgets: compute which resize directions are valid. */
object FolderWidgetAccessibility {

    /**
     * Of [wider, narrower, taller, shorter] (in that order), those whose one-cell step
     * (wider: right +1, narrower: right -1, taller: bottom +1, shorter: bottom -1)
     * changes [rect] and whose new cells outside [rect] are vacant according to [isVacant].
     */
    @JvmStatic
    fun resizeActions(
        rect: GridRect,
        columns: Int,
        rows: Int,
        isVacant: (GridRect) -> Boolean,
    ): List<Int> {
        val actions = mutableListOf<Int>()

        // wider: right +1
        val wider = FolderWidgetResizeMath.step(
            rect,
            left = false,
            top = false,
            right = true,
            bottom = false,
            hDelta = 1,
            vDelta = 0,
            columns = columns,
            rows = rows,
        )
        if (wider != rect) {
            val newCells = GridRect(rect.x + rect.spanX, rect.y, wider.spanX - rect.spanX, rect.spanY)
            if (isVacant(newCells)) {
                actions.add(R.string.folder_widget_wider)
            }
        }

        // narrower: right -1
        val narrower = FolderWidgetResizeMath.step(
            rect,
            left = false,
            top = false,
            right = true,
            bottom = false,
            hDelta = -1,
            vDelta = 0,
            columns = columns,
            rows = rows,
        )
        if (narrower != rect) {
            actions.add(R.string.folder_widget_narrower)
        }

        // taller: bottom +1
        val taller = FolderWidgetResizeMath.step(
            rect,
            left = false,
            top = false,
            right = false,
            bottom = true,
            hDelta = 0,
            vDelta = 1,
            columns = columns,
            rows = rows,
        )
        if (taller != rect) {
            val newCells = GridRect(rect.x, rect.y + rect.spanY, rect.spanX, taller.spanY - rect.spanY)
            if (isVacant(newCells)) {
                actions.add(R.string.folder_widget_taller)
            }
        }

        // shorter: bottom -1
        val shorter = FolderWidgetResizeMath.step(
            rect,
            left = false,
            top = false,
            right = false,
            bottom = true,
            hDelta = 0,
            vDelta = -1,
            columns = columns,
            rows = rows,
        )
        if (shorter != rect) {
            actions.add(R.string.folder_widget_shorter)
        }

        return actions
    }
}
