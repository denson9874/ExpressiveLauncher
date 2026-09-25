package app.lawnchair.ui.preferences

import android.app.Application
import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.R
import com.android.launcher3.util.SystemUiController
import com.google.common.truth.Truth.assertThat
import com.patrykmichalik.opto.core.firstBlocking
import com.patrykmichalik.opto.core.setBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class StatusBarPreferencesTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()
    private lateinit var prefs2: PreferenceManager2

    @Before
    fun setUp() {
        prefs2 = PreferenceManager2.getInstance(context)
        prefs2.darkStatusBar.setBlocking(false)
    }

    @Test
    fun statusBarIconPreferenceStrings_clarifyIconAndTextContrast() {
        val label = context.getString(R.string.dark_status_bar_label)
        val description = context.getString(R.string.dark_status_bar_description)

        assertThat(label).isEqualTo("Dark status bar icons")
        assertThat(description).contains("dark icons")
        assertThat(description).contains("wallpapers")
    }

    @Test
    fun darkStatusBarPreference_defaultsFalseAndPersistsValue() {
        assertThat(prefs2.darkStatusBar.firstBlocking()).isFalse()

        prefs2.darkStatusBar.setBlocking(true)
        assertThat(prefs2.darkStatusBar.firstBlocking()).isTrue()

        prefs2.darkStatusBar.setBlocking(false)
        assertThat(prefs2.darkStatusBar.firstBlocking()).isFalse()
    }

    @Test
    fun systemUiController_mapsDarkStatusBarToLightStatusBarUiFlag() {
        val view = View(context)
        val controller = SystemUiController(view)

        // When darkStatusBar is enabled (or workspace dark text is needed for light wallpaper),
        // updateUiState is called with isLight=true. This sets View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR,
        // which tells the system to paint dark/black status bar icons and clock text.
        val darkStatusBarEnabled = true
        controller.updateUiState(SystemUiController.UI_STATE_BASE_WINDOW, darkStatusBarEnabled)
        val flagsWithDarkIcons = controller.baseSysuiVisibility

        assertThat(flagsWithDarkIcons and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
            .isEqualTo(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)

        // When disabled, SYSTEM_UI_FLAG_LIGHT_STATUS_BAR is cleared, rendering light icons on dark wallpapers.
        controller.updateUiState(SystemUiController.UI_STATE_BASE_WINDOW, false)
        val flagsWithLightIcons = controller.baseSysuiVisibility

        assertThat(flagsWithLightIcons and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR).isEqualTo(0)
    }
}
