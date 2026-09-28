package app.lawnchair.ui.preferences.components.layout

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScrollIndicatorGeometryTest {

    @Test
    fun contentThatFits_drawsNoIndicator() {
        assertThat(
            ScrollIndicatorGeometry.thumb(
                viewportHeight = 1_000f,
                maxScroll = 0,
                scrollOffset = 0,
                minThumbHeight = 48f,
            ),
        ).isNull()
    }

    @Test
    fun overflowingContent_showsAThumbAtTheTopBeforeScrolling() {
        val thumb = ScrollIndicatorGeometry.thumb(
            viewportHeight = 1_000f,
            maxScroll = 1_000,
            scrollOffset = 0,
            minThumbHeight = 48f,
        )!!

        assertThat(thumb.top).isEqualTo(0f)
        assertThat(thumb.height).isEqualTo(500f)
    }

    @Test
    fun scrolledToTheEnd_thumbTouchesTheBottomOfTheTrack() {
        val thumb = ScrollIndicatorGeometry.thumb(
            viewportHeight = 1_000f,
            maxScroll = 3_000,
            scrollOffset = 3_000,
            minThumbHeight = 48f,
        )!!

        assertThat(thumb.top + thumb.height).isWithin(0.01f).of(1_000f)
    }

    @Test
    fun veryLongPages_keepAMinimumThumbHeight() {
        val thumb = ScrollIndicatorGeometry.thumb(
            viewportHeight = 1_000f,
            maxScroll = 999_000,
            scrollOffset = 0,
            minThumbHeight = 48f,
        )!!

        assertThat(thumb.height).isEqualTo(48f)
    }

    @Test
    fun overscroll_isClampedInsideTheTrack() {
        val thumb = ScrollIndicatorGeometry.thumb(
            viewportHeight = 1_000f,
            contentHeight = 2_000f,
            scrollOffset = 5_000f,
            minThumbHeight = 48f,
        )!!

        assertThat(thumb.top).isEqualTo(500f)
    }

    @Test
    fun trackInset_keepsTheThumbOutOfTheTopBarArea() {
        val thumb = ScrollIndicatorGeometry.thumb(
            viewportHeight = 1_000f,
            maxScroll = 1_000,
            scrollOffset = 1_000,
            minThumbHeight = 48f,
            trackHeight = 800f,
        )!!

        assertThat(thumb.height).isEqualTo(400f)
        assertThat(thumb.top + thumb.height).isWithin(0.01f).of(800f)
    }

    @Test
    fun lazyList_estimatesPositionFromVisibleRows() {
        val thumb = ScrollIndicatorGeometry.lazyThumb(
            viewportHeight = 1_000f,
            totalItems = 40,
            visibleItems = 10,
            visibleItemsHeight = 1_000f,
            firstVisibleIndex = 15,
            firstVisibleOffset = 0,
            canScroll = true,
            minThumbHeight = 48f,
        )!!

        // 40 rows of 100px: content 4000px, 1500px scrolled of 3000px maximum.
        assertThat(thumb.height).isEqualTo(250f)
        assertThat(thumb.top).isWithin(0.01f).of(375f)
    }

    @Test
    fun lazyList_thatCannotScroll_drawsNoIndicator() {
        assertThat(
            ScrollIndicatorGeometry.lazyThumb(
                viewportHeight = 1_000f,
                totalItems = 5,
                visibleItems = 5,
                visibleItemsHeight = 500f,
                firstVisibleIndex = 0,
                firstVisibleOffset = 0,
                canScroll = false,
                minThumbHeight = 48f,
            ),
        ).isNull()
    }
}
