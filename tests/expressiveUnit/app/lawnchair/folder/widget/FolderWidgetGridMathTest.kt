package app.lawnchair.folder.widget

import android.graphics.Point
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class FolderWidgetGridMathTest {
    private val base = FolderWidgetGridInput(
        widthPx = 620, heightPx = 700, spanX = 2, spanY = 2, itemCount = 4,
        paddingPx = 24, headerHeightPx = 96,
        folderCellWidthPx = 195, folderCellHeightPx = 230,
        folderChildIconSizePx = 147, folderChildDrawablePaddingPx = 4,
        folderBorderSpacePx = Point(0, 0), numFolderColumns = 4, numFolderRows = 4,
        labelHeightPx = 38, minTouchPx = 144,
    )

    @Test fun twoByTwo_fourApps_adaptsToTwoColumnsAndMatchesNormalFolder() {
        val s = FolderWidgetGridMath.compute(base)
        assertThat(s.columns).isEqualTo(2)
        assertThat(s.columnWidthPx).isEqualTo(195)
        assertThat(s.iconSizePx).isEqualTo(147)
        assertThat(s.labelsVisible).isTrue()
        assertThat(s.rowHeightPx).isEqualTo(230)
        assertThat(s.scrollable).isFalse()
        // Centered horizontally: innerWidth = 620 - 48 = 572; gridWidth = 2 * 195 = 390; left = 24 + (572 - 390) / 2 = 115
        assertThat(s.gridLeftPx).isEqualTo(115)
        assertThat(s.gridTopPx).isEqualTo(120)
    }

    @Test fun adaptiveColumns_matchesFolderGridOrganizer() {
        // Multi-row: 1-4 apps -> 2 cols; 5-6 apps -> 3 cols; 7-9 apps -> 3 cols; 10+ apps -> 4 cols (if span allows)
        assertThat(FolderWidgetGridMath.autoColumns(spanX = 3, spanY = 2, itemCount = 1, maxAllowedColumns = 4)).isEqualTo(2)
        assertThat(FolderWidgetGridMath.autoColumns(spanX = 3, spanY = 2, itemCount = 4, maxAllowedColumns = 4)).isEqualTo(2)
        assertThat(FolderWidgetGridMath.autoColumns(spanX = 3, spanY = 2, itemCount = 5, maxAllowedColumns = 4)).isEqualTo(3)
        assertThat(FolderWidgetGridMath.autoColumns(spanX = 3, spanY = 2, itemCount = 9, maxAllowedColumns = 4)).isEqualTo(3)
        assertThat(FolderWidgetGridMath.autoColumns(spanX = 4, spanY = 2, itemCount = 10, maxAllowedColumns = 4)).isEqualTo(4)
        // Single-row (spanY = 1): lays apps across the row up to spanX
        assertThat(FolderWidgetGridMath.autoColumns(spanX = 3, spanY = 1, itemCount = 3, maxAllowedColumns = 4)).isEqualTo(3)
    }

    @Test fun singleRow_neverClipsIcon() {
        // In 3x1 widget (heightPx = 300, header = 73, padding = 21 -> viewport = 185)
        val s = FolderWidgetGridMath.compute(
            base.copy(
                widthPx = 1000, heightPx = 300, spanX = 3, spanY = 1, itemCount = 4,
                paddingPx = 21, headerHeightPx = 73,
            ),
        )
        // The icon drawable must be smaller than the row height, and row height must not exceed viewport
        assertThat(s.rowHeightPx).isAtMost(s.viewportHeightPx)
        assertThat(s.iconSizePx).isLessThan(s.rowHeightPx)
        val verticalPadding = (s.rowHeightPx - s.iconSizePx) / 2
        assertThat(verticalPadding).isAtLeast(4)
    }

    @Test fun overflow_scrollsFromTheTop() {
        val s = FolderWidgetGridMath.compute(base.copy(itemCount = 20))
        assertThat(s.scrollable).isTrue()
        assertThat(s.gridTopPx).isEqualTo(120)
    }

    @Test fun override_isRespected() {
        val s = FolderWidgetGridMath.compute(base.copy(columnOverride = 3))
        assertThat(s.columns).isEqualTo(3)
    }

    @Test fun tinyPanel_neverNegativeAndHidesLabels() {
        val s = FolderWidgetGridMath.compute(base.copy(heightPx = 200))
        assertThat(s.labelsVisible).isFalse()
        assertThat(s.iconSizePx).isAtLeast(1)
        assertThat(s.rowHeightPx).isAtLeast(s.iconSizePx)

        val t = FolderWidgetGridMath.compute(base.copy(widthPx = 10, heightPx = 10))
        assertThat(t.columns).isAtLeast(1)
        assertThat(t.viewportHeightPx).isAtLeast(0)
        assertThat(t.iconSizePx).isAtLeast(0)
    }

    @Test fun iconScale_appliesToIcon() {
        val full = FolderWidgetGridMath.compute(base)
        val scaled = FolderWidgetGridMath.compute(base.copy(iconScale = 0.8f))
        assertThat(scaled.iconSizePx).isLessThan(full.iconSizePx)
    }

    @Test fun empty_isTopAlignedAndNotScrollable() {
        val s = FolderWidgetGridMath.compute(base.copy(itemCount = 0))
        assertThat(s.scrollable).isFalse()
        assertThat(s.gridTopPx).isEqualTo(120)
    }

    @Test fun pixel8Pro_threeByTwo_labelsVisibleAndNeverClipsBottom() {
        // Pixel 8 Pro 3x2 widget dimensions: width=936, height=646, spanX=3, spanY=2, itemCount=4
        val p8p = FolderWidgetGridInput(
            widthPx = 936, heightPx = 646, spanX = 3, spanY = 2, itemCount = 4,
            paddingPx = 24, headerHeightPx = 66,
            folderCellWidthPx = 249, folderCellHeightPx = 283,
            folderChildIconSizePx = 195, folderChildDrawablePaddingPx = 4,
            folderBorderSpacePx = Point(0, 0), numFolderColumns = 4, numFolderRows = 4,
            labelHeightPx = 45, minTouchPx = 144,
        )
        val s = FolderWidgetGridMath.compute(p8p)
        assertThat(s.columns).isEqualTo(2)
        // Horizontal spacing matches normal folderCellWidthPx exactly (not stretched)
        assertThat(s.columnWidthPx).isEqualTo(249)
        assertThat(s.labelsVisible).isTrue()
        // Content height with 2 rows fits inside viewport with zero clipping
        val totalGridHeight = 2 * s.rowHeightPx
        assertThat(totalGridHeight).isAtMost(s.viewportHeightPx)
        // Icon + padding + label fits with generous vertical margin
        assertThat(s.cellContentHeightPx).isAtMost(s.rowHeightPx)
        val vMargin = (s.rowHeightPx - s.cellContentHeightPx) / 2
        assertThat(vMargin).isAtLeast(10)
    }

    @Test fun fourByFour_reducesHorizontalSpacingAndSpansColumns() {
        val p8p4x4 = FolderWidgetGridInput(
            widthPx = 1260, heightPx = 1260, spanX = 4, spanY = 4, itemCount = 4,
            paddingPx = 24, headerHeightPx = 66,
            folderCellWidthPx = 249, folderCellHeightPx = 283,
            folderChildIconSizePx = 195, folderChildDrawablePaddingPx = 4,
            folderBorderSpacePx = Point(0, 0), numFolderColumns = 4, numFolderRows = 4,
            labelHeightPx = 45, minTouchPx = 144,
        )
        val s = FolderWidgetGridMath.compute(p8p4x4)
        assertThat(s.columns).isEqualTo(4)
        assertThat(s.columnWidthPx).isEqualTo(303)
        assertThat(s.gridLeftPx).isEqualTo(24)
        assertThat(s.labelsVisible).isTrue()
    }
}

