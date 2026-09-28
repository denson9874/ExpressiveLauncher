package app.lawnchair.ui.popup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomeMenuQpr2StyleTest {

    @Test
    fun width_matchesPixelOnPhones() {
        // Pixel 8 Pro portrait: 1344px at density 3.0. Pixel Launcher's QPR2 menu spans about 78%.
        assertThat(homeMenuWidthPx(screenWidthPx = 1_344, density = 3f)).isEqualTo(1_048)
    }

    @Test
    fun width_isCappedOnLargeScreens() {
        assertThat(homeMenuWidthPx(screenWidthPx = 2_560, density = 2f)).isEqualTo(720)
    }

    @Test
    fun carousel_showsTheCurrentWallpaperThenRecentOnesWithoutALockPreview() {
        assertThat(homeMenuCarouselPlan(savedCount = 0)).isEqualTo(HomeMenuCarouselPlan(currentPreviews = 1, savedPreviews = 0))
        assertThat(homeMenuCarouselPlan(savedCount = 2)).isEqualTo(HomeMenuCarouselPlan(currentPreviews = 1, savedPreviews = 2))
    }

    @Test
    fun carousel_showsAtMostThreeRecentWallpapers() {
        assertThat(homeMenuCarouselPlan(savedCount = 9).savedPreviews).isEqualTo(3)
    }
}
