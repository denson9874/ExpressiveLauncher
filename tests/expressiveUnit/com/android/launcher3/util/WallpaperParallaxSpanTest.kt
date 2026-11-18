package com.android.launcher3.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WallpaperParallaxSpanTest {

    @Test
    fun byDefault_fewPagesUseOnlyPartOfTheWallpaper() {
        assertThat(WallpaperOffsetInterpolator.parallaxPageSpan(2, false, false)).isEqualTo(4)
        assertThat(WallpaperOffsetInterpolator.parallaxPageSpan(3, false, false)).isEqualTo(4)
        assertThat(WallpaperOffsetInterpolator.parallaxPageSpan(6, false, false)).isEqualTo(6)
    }

    @Test
    fun shortParallax_spreadsTheWholeWallpaperOverTheActualPages() {
        assertThat(WallpaperOffsetInterpolator.parallaxPageSpan(2, false, true)).isEqualTo(2)
        assertThat(WallpaperOffsetInterpolator.parallaxPageSpan(3, false, true)).isEqualTo(3)
        assertThat(WallpaperOffsetInterpolator.parallaxPageSpan(6, false, true)).isEqualTo(6)
    }

    @Test
    fun liveWallpapersAlwaysUseThePageCount() {
        assertThat(WallpaperOffsetInterpolator.parallaxPageSpan(2, true, false)).isEqualTo(2)
    }
}
