package app.lawnchair.ui.preferences

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.ui.preferences.components.SystemSettingsHandoff
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class SystemSettingsHandoffTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun wallpaperAndStyleRow_saysItOpensAndroidAndNoLongerPromisesAnAppGrid() {
        val description = context.getString(R.string.wallpaper_style_description)

        assertThat(description.lowercase()).doesNotContain("app grid")
        assertThat(description).contains("Android")
    }

    @Test
    fun handoffGlyph_announcesTheAndroidDestination() {
        assertThat(context.getString(SystemSettingsHandoff.announcementRes))
            .isEqualTo("Opens Android settings")
    }

    @Test
    fun handoffGlyph_onlyFillsAnEmptyTrailingSlot() {
        assertThat(SystemSettingsHandoff.showsIndicator(opensSystemSettings = true, hasOtherEndWidget = false))
            .isTrue()
        assertThat(SystemSettingsHandoff.showsIndicator(opensSystemSettings = true, hasOtherEndWidget = true))
            .isFalse()
        assertThat(SystemSettingsHandoff.showsIndicator(opensSystemSettings = false, hasOtherEndWidget = false))
            .isFalse()
    }
}
