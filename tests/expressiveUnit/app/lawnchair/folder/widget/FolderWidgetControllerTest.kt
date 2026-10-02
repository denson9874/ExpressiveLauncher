package app.lawnchair.folder.widget

import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FolderWidgetControllerTest {
    private val cols = 4
    private val rows = 5
    private fun vacant(takenByScreen: Map<Int, Set<Pair<Int, Int>>>): (Int, GridRect) -> Boolean =
        { s, r -> (takenByScreen[s] ?: emptySet()).none { it == r.x to r.y } && r.x < cols && r.y < rows }

    @Test fun putBack_fillsSamePageFirst() {
        val taken = (0 until cols).flatMap { x -> (0 until rows).map { y -> x to y } }.toSet() - setOf(2 to 4, 3 to 4)
        val plan = FolderWidgetController.putBackPlan(3, 0, listOf(0), cols, rows, vacant(mapOf(0 to taken)))
        assertThat(plan).containsExactly(
            Placement(0, GridRect(2, 4, 1, 1)), Placement(0, GridRect(3, 4, 1, 1)), Placement(1, GridRect(0, 0, 1, 1)),
        ).inOrder()
    }

    @Test fun putBack_opensNewPagesWhenFull() {
        val all = (0 until cols).flatMap { x -> (0 until rows).map { y -> x to y } }.toSet()
        val plan = FolderWidgetController.putBackPlan(21, 0, listOf(0, 1), cols, rows, vacant(mapOf(0 to all, 1 to all)))
        assertThat(plan.take(20).map { it.screenId }.toSet()).containsExactly(2)
        assertThat(plan[19]).isEqualTo(Placement(2, GridRect(3, 4, 1, 1)))
        assertThat(plan[20]).isEqualTo(Placement(3, GridRect(0, 0, 1, 1)))
    }

    @Test fun putBack_returnsOnePlacementPerApp() {
        assertThat(FolderWidgetController.putBackPlan(5, 0, listOf(0), cols, rows, vacant(emptyMap()))).hasSize(5)
    }

    @Test fun makeWidgetTarget_isTheTwoByTwoThatContainsTheFolder() {
        assertThat(FolderWidgetController.makeWidgetTarget(1, 2, cols, rows)).isEqualTo(GridRect(1, 2, 2, 2))
        assertThat(FolderWidgetController.makeWidgetTarget(3, 4, cols, rows)).isEqualTo(GridRect(2, 3, 2, 2))
        assertThat(FolderWidgetController.makeWidgetTarget(0, 3, 1, rows)).isEqualTo(GridRect(0, 3, 1, 2))
    }

    @Test fun removeChoices_putBackOrRemoveAll() {
        assertThat(FolderWidgetController.removeChoices(deckLayout = false))
            .containsExactly(R.string.folder_widget_put_back, R.string.folder_widget_remove_all).inOrder()
    }

    @Test fun removeChoices_deckLayoutKeepsTheApps() {
        // Deck layout has no app drawer: removing the apps with the widget would lose them.
        assertThat(FolderWidgetController.removeChoices(deckLayout = true)).containsExactly(R.string.folder_widget_put_back)
    }
}
