package com.android.launcher3

import android.app.Application
import android.content.Context
import android.graphics.Point
import android.view.ContextThemeWrapper
import android.view.View
import androidx.datastore.preferences.core.preferencesOf
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.LawnchairApp
import app.lawnchair.LawnchairLauncher
import app.lawnchair.allapps.views.ContactSearchAccessibilityTest
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.celllayout.CellLayoutLayoutParams
import com.android.launcher3.folder.FolderIcon
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/**
 * R16b: a shortcut-config activity (widget picker > a shortcut) dropped on a folder, a Folder
 * widget or an app can return a legacy shortcut, which a folder refuses.
 * Launcher.completeAddShortcut then added it at the drop cell anyway, on top of the item that
 * refused it: the shortcut never showed, and the next load deleted it ("Item position overlap").
 * It now goes to the free cell nearest the drop. completeAddShortcut itself needs a bound Home,
 * so its two call sites (the drop cell and an open folder's own cell) are checked on a device.
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
class MoveToNearestFreeCellTest {

    @Before
    fun setUp() {
        // CellLayout.isOccupied reads "allow widget overlap" through LawnchairApp.instance, which
        // this plain Application host never creates.
        val app = LawnchairApp()
        ReflectionHelpers.callInstanceMethod<Any>(
            app,
            "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(
                Context::class.java,
                ApplicationProvider.getApplicationContext(),
            ),
        )
        ReflectionHelpers.setStaticField(LawnchairApp::class.java, "instance", app)
    }

    @After
    fun tearDown() {
        ReflectionHelpers.setStaticField(LawnchairApp::class.java, "instance", null)
    }

    @Test
    fun dropOnAFolderWidget_movesToTheNearestFreeCell() {
        // A 2x2 Folder widget at (1,2), with the cells around it free but (0,2), (0,3) and (2,4).
        val layout = page(
            Cell(1, 2, 2, 2, Kind.FOLDER),
            Cell(0, 2, kind = Kind.APP),
            Cell(0, 3, kind = Kind.APP),
            Cell(2, 4, kind = Kind.APP),
        )
        val cellXY = intArrayOf(2, 3)

        assertThat(Launcher.moveToNearestFreeCell(layout, cellXY)).isTrue()

        // Next to the widget's corner that took the drop, not the first free cell of the page.
        assertThat(Point(cellXY[0], cellXY[1])).isEqualTo(Point(3, 3))
        assertThat(layout.isOccupied(cellXY[0], cellXY[1])).isFalse()
    }

    @Test
    fun dropOnAFolder_movesNextToIt() {
        val layout = page(
            Cell(2, 5, kind = Kind.FOLDER),
            Cell(1, 5, kind = Kind.APP),
            Cell(3, 5, kind = Kind.APP),
        )
        val cellXY = intArrayOf(2, 5)

        assertThat(Launcher.moveToNearestFreeCell(layout, cellXY)).isTrue()

        assertThat(Point(cellXY[0], cellXY[1])).isEqualTo(Point(2, 4))
    }

    @Test
    fun dropOnAFreeCell_staysThere() {
        val layout = page(Cell(0, 0, COLUMNS, 1))
        val cellXY = intArrayOf(4, 5)

        assertThat(Launcher.moveToNearestFreeCell(layout, cellXY)).isTrue()

        assertThat(Point(cellXY[0], cellXY[1])).isEqualTo(Point(4, 5))
    }

    @Test
    fun allowWidgetOverlap_stillKeepsItOffAFolderOrAnApp() {
        allowWidgetOverlap()
        val layout = page(
            Cell(2, 5, kind = Kind.FOLDER),
            Cell(1, 5, kind = Kind.APP),
            Cell(3, 5, kind = Kind.APP),
            Cell(4, 5, kind = Kind.APP),
        )
        // With overlap on, CellLayout calls every cell free.
        assertThat(layout.isOccupied(2, 5)).isFalse()

        val onFolder = intArrayOf(2, 5)
        assertThat(Launcher.moveToNearestFreeCell(layout, onFolder)).isTrue()
        assertThat(Point(onFolder[0], onFolder[1])).isEqualTo(Point(2, 4))

        val onApp = intArrayOf(3, 5)
        assertThat(Launcher.moveToNearestFreeCell(layout, onApp)).isTrue()
        assertThat(Point(onApp[0], onApp[1])).isEqualTo(Point(3, 4))
    }

    @Test
    fun allowWidgetOverlap_stillLetsItSitOnAWidget() {
        allowWidgetOverlap()
        val layout = page(Cell(0, 1, 3, 3))
        val cellXY = intArrayOf(1, 2)

        assertThat(Launcher.moveToNearestFreeCell(layout, cellXY)).isTrue()

        assertThat(Point(cellXY[0], cellXY[1])).isEqualTo(Point(1, 2))
    }

    @Test
    fun fullPage_findsNoCell() {
        val layout = page(Cell(0, 0, COLUMNS, ROWS))
        val cellXY = intArrayOf(2, 3)

        assertThat(Launcher.moveToNearestFreeCell(layout, cellXY)).isFalse()
    }

    private enum class Kind { WIDGET, APP, FOLDER }

    private data class Cell(
        val x: Int,
        val y: Int,
        val spanX: Int = 1,
        val spanY: Int = 1,
        val kind: Kind = Kind.WIDGET,
    )

    /**
     * Settings > Home screen > "Allow widget overlap". Its value is read from the preference
     * cache, which the DataStore refills only on the main looper, which this test never idles.
     */
    private fun allowWidgetOverlap() {
        val prefs2 = PreferenceManager2.getInstance(ApplicationProvider.getApplicationContext())
        ReflectionHelpers.setField(
            prefs2,
            "cachedPreferences",
            preferencesOf(prefs2.allowWidgetOverlap.key to true),
        )
    }

    /** A laid-out 5x6 Home page holding [cells], bound as Workspace.addInScreen binds them. */
    private fun page(vararg cells: Cell): CellLayout {
        val launcher = Robolectric.buildActivity(LawnchairLauncher::class.java).get()
        val dp = DeviceProfile()
        ReflectionHelpers.setField(dp, "inv", grid(COLUMNS, ROWS))
        dp.cellLayoutBorderSpacePx = Point(0, 0)
        dp.folderCellLayoutBorderSpacePx = Point(0, 0)
        ReflectionHelpers.setField(launcher, "mDeviceProfile", dp)
        val context = ContextThemeWrapper(launcher, R.style.LauncherTheme)
        val layout = CellLayout(context, SingleScreen)
        cells.forEachIndexed { index, cell ->
            val view = when (cell.kind) {
                Kind.WIDGET -> View(context)
                Kind.APP -> BubbleTextView(context)
                // A Folder widget is a FolderIcon too. FolderIcon.fromXml sets its activity.
                Kind.FOLDER -> FolderIcon(context).also {
                    ReflectionHelpers.setField(it, "mActivity", launcher)
                }
            }
            val lp = CellLayoutLayoutParams(cell.x, cell.y, cell.spanX, cell.spanY)
            assertThat(layout.addViewToCellLayout(view, -1, index + 1, lp, true)).isTrue()
        }
        layout.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        layout.layout(0, 0, WIDTH, HEIGHT)
        assertThat(layout.cellWidth).isEqualTo(WIDTH / COLUMNS)
        assertThat(layout.cellHeight).isEqualTo(HEIGHT / ROWS)
        return layout
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
