package com.android.launcher3.celllayout

import android.app.Application
import android.graphics.Point
import android.view.ContextThemeWrapper
import android.view.View
import app.lawnchair.LawnchairLauncher
import app.lawnchair.allapps.views.ContactSearchAccessibilityTest
import com.android.launcher3.CellLayout
import com.android.launcher3.CellLayoutContainer
import com.android.launcher3.DeviceProfile
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/**
 * A resize or "make widget" search that finds no room must leave every Home item where it is.
 *
 * Owner's Pixel 7 Pro on 5.0.1: after "make widget" on a folder failed with "no room", the next
 * relayout drew every item on the page at (0,0) with its own span, because the failed search had
 * switched every child to its (never set) temporary cell.
 */
// SDK 34: Android 17's framework jar bundles an older com.android.launcher3.Flags that hides ours.
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = Application::class,
    shadows = [ContactSearchAccessibilityTest.ShadowThemeProvider::class],
)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class CreateAreaForResizeTempCoordsTest {

    @Test
    fun failedMakeWidgetSearch_keepsEveryItemInItsOwnCell() {
        val home = ownersFirstPage()
        val folder = home.item("Google folder")

        // FolderWidgetController.makeWidget on the folder at (0,5): the 2x2 at (0,4) needs the
        // 5x1 At a Glance widget to move, and no 5-wide row is free.
        home.layout.markCellsAsUnoccupiedForView(folder)
        val found = home.layout.createAreaForResize(0, 4, 2, 2, folder, intArrayOf(1, -1), false)
        home.layout.markCellsAsOccupiedForView(folder)
        assertThat(found).isFalse()

        // Something later relayouts the page (a Home settings change, a widget update...).
        home.relayout()

        home.assertEveryItemAt(home.boundCells, useTmpCoords = false)
    }

    @Test
    fun failedWidgetResizeSearch_keepsEveryItemInItsOwnCell() {
        val home = ownersFirstPage()
        val clock = home.item("Clock widget")

        // AppWidgetResizeFrame dragging the Clock's corner over the whole page: the items it
        // covers have nowhere to go.
        home.layout.markCellsAsUnoccupiedForView(clock)
        val found = home.layout.createAreaForResize(0, 1, 5, 5, clock, intArrayOf(1, 1), false)
        assertThat(found).isFalse()

        home.relayout()

        home.assertEveryItemAt(home.boundCells, useTmpCoords = false)
    }

    @Test
    fun successfulResizeSearch_stillPreviewsPushedItemsAtTheirTemporaryCells() {
        val home = page(
            Item("built-in At a Glance", 0, 0, COLUMNS, 1, canReorder = false),
            Item("Clock widget", 0, 1, 3, 3),
            Item("Photos", 3, 1, 1, 1),
        )
        val clock = home.item("Clock widget")

        // Growing the Clock to 4x3 pushes Photos one cell right, as a preview (no commit).
        home.layout.markCellsAsUnoccupiedForView(clock)
        val found = home.layout.createAreaForResize(0, 1, 4, 3, clock, intArrayOf(1, 0), false)
        assertThat(found).isTrue()
        // As AppWidgetResizeFrame.resizeWidgetIfNeeded does after a successful search.
        (clock.layoutParams as CellLayoutLayoutParams).apply {
            tmpCellX = 0
            tmpCellY = 1
            cellHSpan = 4
        }

        home.relayout()

        home.assertEveryItemAt(
            mapOf(
                "built-in At a Glance" to Point(0, 0),
                "Clock widget" to Point(0, 1),
                "Photos" to Point(4, 1),
            ),
            useTmpCoords = true,
        )
        // Nothing is committed: the model cells are unchanged until the frame is dismissed.
        assertThat((home.item("Photos").layoutParams as CellLayoutLayoutParams).cellX).isEqualTo(3)
        assertThat(clock.width).isEqualTo(4 * home.layout.cellWidth)
    }

    @Test
    fun freshLayoutParams_startWithTheirOwnCellAsTemporaryCell() {
        val lp = CellLayoutLayoutParams(3, 4, 2, 1)

        assertThat(lp.tmpCellX).isEqualTo(3)
        assertThat(lp.tmpCellY).isEqualTo(4)

        // A stray useTmpCoords can no longer send a freshly bound item to (0,0).
        lp.useTmpCoords = true
        lp.setup(100, 50, false, COLUMNS, ROWS, Point(0, 0))
        assertThat(lp.x).isEqualTo(300)
        assertThat(lp.y).isEqualTo(200)
        assertThat(lp.width).isEqualTo(200)
        assertThat(lp.height).isEqualTo(50)
    }

    private data class Item(
        val name: String,
        val x: Int,
        val y: Int,
        val spanX: Int,
        val spanY: Int,
        val canReorder: Boolean = true,
    )

    private class Home(val layout: CellLayout, private val views: Map<String, View>) {
        val boundCells: Map<String, Point> = views.mapValues { (_, view) ->
            val lp = view.layoutParams as CellLayoutLayoutParams
            Point(lp.cellX, lp.cellY)
        }

        fun item(name: String): View = views.getValue(name)

        fun relayout() {
            // A child's requestLayout reaches the container, which re-measures every child.
            layout.shortcutsAndWidgets.requestLayout()
            layout.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            layout.layout(0, 0, WIDTH, HEIGHT)
        }

        /** Where each item is drawn (from its laid-out pixels) and whether it uses tmp coords. */
        fun assertEveryItemAt(cells: Map<String, Point>, useTmpCoords: Boolean) {
            val drawn = views.mapValues { (_, view) ->
                val lp = view.layoutParams as CellLayoutLayoutParams
                Drawn(
                    view.left.toFloat() / layout.cellWidth,
                    view.top.toFloat() / layout.cellHeight,
                    lp.useTmpCoords,
                )
            }
            val expected = cells.mapValues { (_, cell) ->
                Drawn(cell.x.toFloat(), cell.y.toFloat(), useTmpCoords)
            }
            assertWithMessage("cell each item is drawn at").that(drawn)
                .containsExactlyEntriesIn(expected)
        }
    }

    private data class Drawn(val cellX: Float, val cellY: Float, val useTmpCoords: Boolean)

    /**
     * The owner's page 1 (00-launcher-dump.txt): a 5x6 grid with the built-in At a Glance on row
     * 0, a 3x3 Clock at (0,1), a 5x1 At a Glance widget at (0,4), the Google folder at (0,5) and
     * Play Store at (4,5).
     */
    private fun ownersFirstPage(): Home = page(
        // As Workspace binds the first-page QSB: it never moves.
        Item("built-in At a Glance", 0, 0, COLUMNS, 1, canReorder = false),
        Item("Clock widget", 0, 1, 3, 3),
        Item("At a Glance widget", 0, 4, COLUMNS, 1),
        Item("Google folder", 0, 5, 1, 1),
        Item("Play Store", 4, 5, 1, 1),
    )

    /** A laid-out 5x6 Home page whose items are bound as Workspace.addInScreen binds them. */
    private fun page(vararg items: Item): Home {
        val launcher = Robolectric.buildActivity(LawnchairLauncher::class.java).get()
        val dp = DeviceProfile()
        ReflectionHelpers.setField(dp, "inv", grid(COLUMNS, ROWS))
        dp.cellLayoutBorderSpacePx = Point(0, 0)
        dp.folderCellLayoutBorderSpacePx = Point(0, 0)
        ReflectionHelpers.setField(launcher, "mDeviceProfile", dp)
        val context = ContextThemeWrapper(launcher, R.style.LauncherTheme)
        val layout = CellLayout(context, SingleScreen)

        val views = LinkedHashMap<String, View>()
        items.forEachIndexed { index, item ->
            val view = View(context)
            val lp = CellLayoutLayoutParams(item.x, item.y, item.spanX, item.spanY)
            lp.canReorder = item.canReorder
            assertThat(layout.addViewToCellLayout(view, -1, index + 1, lp, true)).isTrue()
            views[item.name] = view
        }

        val home = Home(layout, views)
        home.relayout()
        assertThat(layout.cellWidth).isEqualTo(WIDTH / COLUMNS)
        assertThat(layout.cellHeight).isEqualTo(HEIGHT / ROWS)
        home.assertEveryItemAt(home.boundCells, useTmpCoords = false)
        return home
    }

    /** The grid init needs the full launcher graph; CellLayout only reads the grid size. */
    private fun grid(columns: Int, rows: Int): InvariantDeviceProfile {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null)
        val idp = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, InvariantDeviceProfile::class.java) as InvariantDeviceProfile
        idp.numColumns = columns
        idp.numRows = rows
        return idp
    }

    private object SingleScreen : CellLayoutContainer {
        override fun getCellLayoutId(cellLayout: CellLayout): Int = 0

        override fun getCellLayoutIndex(cellLayout: CellLayout): Int = 0

        override fun getPanelCount(): Int = 1

        override fun getPageDescription(pageIndex: Int): String = ""
    }

    private companion object {
        const val COLUMNS = 5
        const val ROWS = 6
        const val WIDTH = 1000
        const val HEIGHT = 1200
    }
}
