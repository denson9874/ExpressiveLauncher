package app.lawnchair.util

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * XDA-019: with blur switched off (Battery Saver) the drawer sheet turns solid, and Home only shows
 * through the sheet while something blurs it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AllAppsSheetAlphaTest {

    @Test
    fun opacitySliderTintsBlurredSheet() {
        assertThat(allAppsSheetAlpha(0.5f, blurAvailable = true)).isEqualTo(128)
        assertThat(allAppsSheetAlpha(0f, blurAvailable = true)).isEqualTo(0)
        assertThat(allAppsSheetAlpha(1.4f, blurAvailable = true)).isEqualTo(255)
    }

    @Test
    fun sheetIsSolidWithoutBlur() {
        assertThat(allAppsSheetAlpha(0.5f, blurAvailable = false)).isEqualTo(255)
        assertThat(allAppsSheetAlpha(0.1f, blurAvailable = false)).isEqualTo(255)
    }

    @Test
    fun homeShowsBehindSheetOnlyWhenBlurred() {
        assertThat(
            showHomeBehindAllAppsSheet(blurEnabled = true, depthEffectEnabled = true, sheetDepth = 0.3f),
        ).isTrue()
    }

    /** Pixel 7 Pro, 2026-11-03: Home showed sharp through the sheet with no blur behind it. */
    @Test
    fun homeHidesBehindSheetWithoutBlur() {
        // Device profile built before the sheet setting was read: no sheet depth, so no blur.
        assertThat(
            showHomeBehindAllAppsSheet(blurEnabled = true, depthEffectEnabled = true, sheetDepth = 0f),
        ).isFalse()
        // Wallpaper depth effect off: the depth controller blurs nothing.
        assertThat(
            showHomeBehindAllAppsSheet(blurEnabled = true, depthEffectEnabled = false, sheetDepth = 0.3f),
        ).isFalse()
        // Window blur off (Battery Saver or the system blur switch).
        assertThat(
            showHomeBehindAllAppsSheet(blurEnabled = false, depthEffectEnabled = true, sheetDepth = 0.3f),
        ).isFalse()
    }
}
