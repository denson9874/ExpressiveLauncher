package app.lawnchair.folder.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FolderWidgetGridMathTest {
    private val base = FolderWidgetGridInput(
        widthPx = 620, heightPx = 700, spanX = 2, itemCount = 6,
        paddingPx = 24, headerHeightPx = 96, homeIconSizePx = 150, labelHeightPx = 45,
        iconLabelGapPx = 12, rowSpacingPx = 18, minTouchPx = 144,
    )

    @Test fun twoByTwo_isCenteredWithLabels() {
        val s = FolderWidgetGridMath.compute(base)
        assertThat(s.columns).isEqualTo(3)
        assertThat(s.columnWidthPx).isEqualTo(190)
        assertThat(s.iconSizePx).isEqualTo(142)
        assertThat(s.labelsVisible).isTrue()
        assertThat(s.rowHeightPx).isEqualTo(217)
        assertThat(s.contentHeightPx).isEqualTo(434)
        assertThat(s.scrollable).isFalse()
        assertThat(s.gridLeftPx).isEqualTo(25)
        assertThat(s.gridTopPx).isEqualTo(181)
    }

    @Test fun overflow_scrollsFromTheTop() {
        val s = FolderWidgetGridMath.compute(base.copy(itemCount = 12))
        assertThat(s.scrollable).isTrue()
        assertThat(s.gridTopPx).isEqualTo(120)
    }

    @Test fun singleRowThatFits_doesNotScroll() {
        // viewport = 343 - 2*24 - 96 = 199 = one labeled row (142 + 12 + 45); the row's spacing isn't content.
        val s = FolderWidgetGridMath.compute(base.copy(heightPx = 343, itemCount = 3))
        assertThat(s.labelsVisible).isTrue()
        assertThat(s.scrollable).isFalse()
    }

    @Test fun autoColumns_followOneAndAHalfPerCell() {
        assertThat((1..5).map(FolderWidgetGridMath::autoColumns)).containsExactly(2, 3, 5, 6, 6).inOrder()
    }

    @Test fun override_isClampedThenFitsTouch() {
        assertThat(FolderWidgetGridMath.compute(base.copy(columnOverride = 9)).columns).isEqualTo(3)
        assertThat(FolderWidgetGridMath.compute(base.copy(columnOverride = 1)).columns).isEqualTo(2)
    }

    @Test fun narrowPanel_dropsColumnsForTouch() {
        assertThat(FolderWidgetGridMath.compute(base.copy(widthPx = 300, paddingPx = 0, spanX = 4)).columns)
            .isEqualTo(2)
    }

    @Test fun tinyPanel_neverNegativeAndHidesLabels() {
        val s = FolderWidgetGridMath.compute(base.copy(heightPx = 260))
        assertThat(s.labelsVisible).isFalse()
        assertThat(s.iconSizePx).isEqualTo(116)
        val t = FolderWidgetGridMath.compute(base.copy(widthPx = 10, heightPx = 10))
        assertThat(t.columns).isAtLeast(1)
        assertThat(t.viewportHeightPx).isAtLeast(0)
        assertThat(t.iconSizePx).isAtLeast(0)
    }

    @Test fun iconScale_appliesAfterTheCap() {
        assertThat(FolderWidgetGridMath.compute(base.copy(iconScale = 0.7f)).iconSizePx).isEqualTo(99)
    }

    @Test fun empty_isCenteredAndNotScrollable() {
        val s = FolderWidgetGridMath.compute(base.copy(itemCount = 0))
        assertThat(s.scrollable).isFalse()
        assertThat(s.gridTopPx).isEqualTo(398)
    }
}
