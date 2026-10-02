package app.lawnchair.folder.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FolderWidgetResizeMathTest {
    private val r = GridRect(0, 0, 2, 2)
    private fun step(c: GridRect, l: Boolean, t: Boolean, rt: Boolean, b: Boolean, h: Int, v: Int) =
        FolderWidgetResizeMath.step(c, l, t, rt, b, h, v, 4, 5)

    @Test fun growsRight() {
        assertThat(step(r, false, false, true, false, 1, 0)).isEqualTo(GridRect(0, 0, 3, 2))
    }

    @Test fun growsDown() {
        assertThat(step(r, false, false, false, true, 0, 2)).isEqualTo(GridRect(0, 0, 2, 4))
    }

    @Test fun stopsAtGridEdge() {
        assertThat(step(r, false, false, true, false, 5, 0)).isEqualTo(GridRect(0, 0, 4, 2))
    }

    @Test fun leftEdgeMovesStart() {
        assertThat(step(GridRect(1, 0, 2, 2), true, false, false, false, -1, 0)).isEqualTo(GridRect(0, 0, 3, 2))
    }

    @Test fun shrinksToStrip() {
        assertThat(step(r, false, false, false, true, 0, -1)).isEqualTo(GridRect(0, 0, 2, 1))
    }

    @Test fun step_refusesSingleCell() {
        assertThat(step(GridRect(0, 0, 2, 1), false, false, true, false, -1, 0)).isEqualTo(GridRect(0, 0, 2, 1))
    }
}
