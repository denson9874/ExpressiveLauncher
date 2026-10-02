package app.lawnchair.folder.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FolderWidgetPlacementTest {
    private val cols = 4
    private val rows = 5
    private fun occupancy(vararg taken: Pair<Int, GridRect>): (Int, GridRect) -> Boolean = { s, r ->
        r.x >= 0 && r.y >= 0 && r.x + r.spanX <= cols && r.y + r.spanY <= rows &&
            taken.none { (ts, t) ->
                ts == s && r.x < t.x + t.spanX && t.x < r.x + r.spanX && r.y < t.y + t.spanY && t.y < r.y + r.spanY
            }
    }

    @Test fun keepsAVacantPlace() {
        assertThat(FolderWidgetPlacement.place(GridRect(0, 1, 2, 2), 0, listOf(0), cols, rows, occupancy()))
            .isEqualTo(Placement(0, GridRect(0, 1, 2, 2)))
    }

    @Test fun overlapMovesToNearestFreeAreaOnSamePage() {
        val clock = 0 to GridRect(1, 1, 2, 2)
        assertThat(FolderWidgetPlacement.place(GridRect(0, 1, 2, 2), 0, listOf(0), cols, rows, occupancy(clock)))
            .isEqualTo(Placement(0, GridRect(0, 3, 2, 2)))
    }

    @Test fun outOfGridShrinksInPlace() {
        assertThat(FolderWidgetPlacement.place(GridRect(3, 0, 2, 2), 0, listOf(0), cols, rows, occupancy()))
            .isEqualTo(Placement(0, GridRect(3, 0, 1, 2)))
    }

    @Test fun fullPage_usesLaterPage() {
        val full = 0 to GridRect(0, 0, cols, rows)
        assertThat(FolderWidgetPlacement.place(GridRect(0, 0, 2, 2), 0, listOf(0, 3), cols, rows, occupancy(full)))
            .isEqualTo(Placement(3, GridRect(0, 0, 2, 2)))
    }

    @Test fun fullPages_useNewScreen() {
        val taken = occupancy(0 to GridRect(0, 0, cols, rows), 1 to GridRect(0, 0, cols, rows))
        assertThat(FolderWidgetPlacement.place(GridRect(0, 0, 2, 2), 0, listOf(0, 1), cols, rows, taken))
            .isEqualTo(Placement(2, GridRect(0, 0, 2, 2)))
    }

    @Test fun secondWidget_seesFirstWidgetsCells() {
        val first = 0 to GridRect(0, 0, 2, 2)
        assertThat(FolderWidgetPlacement.place(GridRect(0, 0, 2, 2), 0, listOf(0), cols, rows, occupancy(first)).rect)
            .isEqualTo(GridRect(2, 0, 2, 2))
    }

    @Test fun placeAll_secondWidgetMovesOffTheFirst() {
        val placed = FolderWidgetPlacement.placeAll(
            listOf(Placement(0, GridRect(0, 0, 2, 2)), Placement(0, GridRect(0, 0, 2, 2))),
            listOf(0), cols, rows, occupancy(),
        )
        assertThat(placed).containsExactly(Placement(0, GridRect(0, 0, 2, 2)), Placement(0, GridRect(2, 0, 2, 2)))
            .inOrder()
    }

    @Test fun placeAll_fullHome_sharesOneNewScreen() {
        val placed = FolderWidgetPlacement.placeAll(
            listOf(Placement(0, GridRect(0, 0, 2, 2)), Placement(0, GridRect(0, 0, 2, 2))),
            listOf(0), cols, rows, occupancy(0 to GridRect(0, 0, cols, rows)),
        )
        assertThat(placed).containsExactly(Placement(1, GridRect(0, 0, 2, 2)), Placement(1, GridRect(2, 0, 2, 2)))
            .inOrder()
    }

    @Test fun normalizeSpan_neverOneCellAndFitsGrid() {
        assertThat(FolderWidgetPlacement.normalizeSpan(1, 1, 4, 5)).isEqualTo(2 to 2)
        assertThat(FolderWidgetPlacement.normalizeSpan(6, 9, 4, 5)).isEqualTo(4 to 5)
        assertThat(FolderWidgetPlacement.normalizeSpan(1, 1, 1, 5)).isEqualTo(1 to 2)
    }
}
