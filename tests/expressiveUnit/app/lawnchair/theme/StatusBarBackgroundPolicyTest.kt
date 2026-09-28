package app.lawnchair.theme

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import com.patrykmichalik.opto.core.firstBlocking
import com.patrykmichalik.opto.core.setBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class StatusBarBackgroundPolicyTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun solidBackground_coversExactlyTheStatusBarInset() {
        assertThat(StatusBarBackgroundPolicy.drawsSolidBackground(true, true, 96)).isTrue()
        assertThat(StatusBarBackgroundPolicy.solidBackgroundHeight(true, true, 96)).isEqualTo(96)
    }

    @Test
    fun solidBackground_isNotDrawnWhenOffHiddenOrWithoutInset() {
        assertThat(StatusBarBackgroundPolicy.solidBackgroundHeight(false, true, 96)).isEqualTo(0)
        // A black bar with no status bar on it would just waste space.
        assertThat(StatusBarBackgroundPolicy.solidBackgroundHeight(true, false, 96)).isEqualTo(0)
        assertThat(StatusBarBackgroundPolicy.solidBackgroundHeight(true, true, 0)).isEqualTo(0)
    }

    @Test
    fun solidBackground_forcesLightIconsOverLightWallpaperAndDarkIconSetting() {
        assertThat(
            StatusBarBackgroundPolicy.useDarkStatusBarIcons(
                isWorkspaceDarkText = true,
                darkStatusBar = true,
                solidBackground = true,
                statusBarShown = true,
            ),
        ).isFalse()
    }

    @Test
    fun withoutSolidBackground_existingIconRulesAreUnchanged() {
        fun dark(workspaceDarkText: Boolean, darkStatusBar: Boolean) =
            StatusBarBackgroundPolicy.useDarkStatusBarIcons(workspaceDarkText, darkStatusBar, false, true)

        assertThat(dark(workspaceDarkText = false, darkStatusBar = false)).isFalse()
        assertThat(dark(workspaceDarkText = true, darkStatusBar = false)).isTrue()
        assertThat(dark(workspaceDarkText = false, darkStatusBar = true)).isTrue()
        // Status bar hidden: the solid setting has no bar to paint, so it does not override.
        assertThat(StatusBarBackgroundPolicy.useDarkStatusBarIcons(true, false, true, false)).isTrue()
    }

    @Test
    fun solidBackground_isOpaqueBlack() {
        assertThat(StatusBarBackgroundPolicy.SOLID_BACKGROUND_COLOR).isEqualTo(0xFF000000.toInt())
    }

    @Test
    fun preference_isOffByDefaultAndPersists() {
        val prefs = PreferenceManager2.getInstance(context)
        assertThat(context.resources.getBoolean(R.bool.config_default_solid_status_bar_background)).isFalse()
        assertThat(prefs.solidStatusBarBackground.defaultValue).isFalse()

        prefs.solidStatusBarBackground.setBlocking(true)
        assertThat(prefs.solidStatusBarBackground.firstBlocking()).isTrue()
        prefs.solidStatusBarBackground.setBlocking(false)
    }

    @Test
    fun settingCopy_doesNotPromiseAHeightControl() {
        assertThat(context.getString(R.string.solid_status_bar_background_label))
            .isEqualTo("Solid status bar background")
        assertThat(context.getString(R.string.solid_status_bar_background_description))
            .contains("Android sets the bar's height")
    }
}
