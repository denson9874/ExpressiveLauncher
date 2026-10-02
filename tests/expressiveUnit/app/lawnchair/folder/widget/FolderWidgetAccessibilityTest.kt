package app.lawnchair.folder.widget

import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FolderWidgetAccessibilityTest {
    private val all = { _: GridRect -> true }

    @Test fun twoByTwoInOpenSpace_offersAllFour() {
        assertThat(FolderWidgetAccessibility.resizeActions(GridRect(0, 0, 2, 2), 4, 5, all)).containsExactly(
            R.string.folder_widget_wider, R.string.folder_widget_narrower,
            R.string.folder_widget_taller, R.string.folder_widget_shorter).inOrder()
    }

    @Test fun stripCannotBecomeOneCell() {
        assertThat(FolderWidgetAccessibility.resizeActions(GridRect(0, 0, 2, 1), 4, 5, all))
            .containsNoneOf(R.string.folder_widget_narrower, R.string.folder_widget_shorter)
    }

    @Test fun atGridEdge_noWider() {
        assertThat(FolderWidgetAccessibility.resizeActions(GridRect(2, 0, 2, 2), 4, 5, all))
            .doesNotContain(R.string.folder_widget_wider)
    }

    @Test fun blockedCells_noWider() {
        assertThat(FolderWidgetAccessibility.resizeActions(GridRect(0, 0, 2, 2), 4, 5) { it.x + it.spanX <= 2 })
            .doesNotContain(R.string.folder_widget_wider)
    }
}
