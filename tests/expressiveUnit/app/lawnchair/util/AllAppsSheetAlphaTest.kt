package app.lawnchair.util

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** XDA-019: with blur switched off (Battery Saver) the drawer sheet turns solid. */
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
}
