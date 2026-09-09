package app.lawnchair.ui.popup

import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WallpaperCarouselWidthsTest {

    @Test
    fun zeroItems_clampsZeroWidthWithoutDividingByZero() {
        val widths = calculateWallpaperCarouselWidths(totalWidth = 0, itemCount = 0)

        assertThat(widths.expandedWidth).isEqualTo(1)
        assertThat(widths.collapsedWidth).isEqualTo(1)
        assertThat(widths.margin).isEqualTo(0)
    }

    @Test
    fun oneItem_usesTheEntireAvailableWidthWithoutDividingByZero() {
        val totalWidth = 1_080

        val widths = calculateWallpaperCarouselWidths(totalWidth, itemCount = 1)

        assertThat(widths.expandedWidth).isEqualTo(totalWidth)
        assertThat(widths.collapsedWidth).isEqualTo(totalWidth)
        assertThat(widths.margin).isEqualTo(0)
    }

    @Test
    fun twoItems_returnsPositiveDimensionsAndAccountsForEveryPixel() {
        val totalWidth = 1_000
        val itemCount = 2

        val widths = calculateWallpaperCarouselWidths(totalWidth, itemCount)

        assertThat(widths.expandedWidth).isGreaterThan(0)
        assertThat(widths.collapsedWidth).isGreaterThan(0)
        assertThat(widths.margin).isGreaterThan(0)
        assertThat(widths.totalUsedWidth(itemCount)).isEqualTo(totalWidth)
    }

    @Test
    fun fiveItems_returnsPositiveDimensionsAndAccountsForEveryPixel() {
        // Deliberately exercises a remainder: integer division must not silently lose two pixels.
        val totalWidth = 1_000
        val itemCount = 5

        val widths = calculateWallpaperCarouselWidths(totalWidth, itemCount)

        assertThat(widths.expandedWidth).isGreaterThan(0)
        assertThat(widths.collapsedWidth).isGreaterThan(0)
        assertThat(widths.margin).isGreaterThan(0)
        assertThat(widths.totalUsedWidth(itemCount)).isEqualTo(totalWidth)
    }

    @Test
    fun accessibilityState_collapsedCardExplainsFirstActivation() {
        assertThat(
            wallpaperCarouselStateDescriptionRes(
                selected = false,
                appliesWallpaper = false,
            ),
        ).isEqualTo(R.string.wallpaper_carousel_state_collapsed)
    }

    @Test
    fun accessibilityState_selectedCurrentCardOpensPicker() {
        assertThat(
            wallpaperCarouselStateDescriptionRes(
                selected = true,
                appliesWallpaper = false,
            ),
        ).isEqualTo(R.string.wallpaper_carousel_state_selected_open)
    }

    @Test
    fun accessibilityState_selectedSavedCardAppliesWallpaper() {
        assertThat(
            wallpaperCarouselStateDescriptionRes(
                selected = true,
                appliesWallpaper = true,
            ),
        ).isEqualTo(R.string.wallpaper_carousel_state_selected_apply)
    }

    private fun WallpaperCarouselWidths.totalUsedWidth(itemCount: Int): Int =
        expandedWidth + collapsedWidth * (itemCount - 1) + margin * (itemCount - 1)
}
